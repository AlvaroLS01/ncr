package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.core.servicios.variables.Variables;
import com.comerzzia.pos.core.dispositivos.Dispositivos;
import com.comerzzia.pos.core.dispositivos.dispositivo.impresora.IPrinter;
import com.comerzzia.pos.ncr.actions.sale.PayManager;
import com.comerzzia.pos.ncr.devices.printer.NCRSCOPrinter;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.messages.EndTransaction;
import com.comerzzia.pos.ncr.messages.Receipt;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.persistence.giftcard.GiftCardBean;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;
import com.comerzzia.pos.services.ticket.ITicket;
import com.comerzzia.pos.services.ticket.cabecera.ITotalesTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.pagos.PagoTicket;
import com.comerzzia.pos.util.i18n.I18N;

@Lazy(false)
@Service
@Primary
@DependsOn("ametllerCommandManager")
public class AmetllerPayManager extends PayManager {

	private static final Logger log = Logger.getLogger(AmetllerPayManager.class);

	private static final String AUTO_TENDER_TYPE = "OTRASTARJETAS";
	private static final String AUTO_TENDER_TYPE_ALT = "OTHERCARDS";
	private static final String EXPLICIT_TENDER = "GIFTCARD";

	private static final String DIALOG_CONFIRM_TYPE = "4";
	private static final String DIALOG_CONFIRM_ID = "1";

	private static final String DESCUENTO25_DIALOG_TYPE = "1";
	private static final String DESCUENTO25_DIALOG_ID = "2";

	private static final String WAIT_TYPE = "1";
	private static final String WAIT_ID = "1";

	private static final String RECEIPT_SEPARATOR = "------------------------------";
	private static final Locale RECEIPT_LOCALE = new Locale("es", "ES");

	@Autowired
	private Sesion sesion;
	@Autowired
	private VariablesServices variablesServices;

	private PendingPayment pendingPayment;

	@PostConstruct
	@Override
	public void init() {
		super.init();
		ncrController.registerActionManager(DataNeededReply.class, this);
	}

	@Override
	public void processMessage(BasicNCRMessage message) {
		if (message instanceof DataNeededReply) {
			DataNeededReply reply = (DataNeededReply) message;
			if (handleDescuento25DataNeededReply(reply)) {
				return;
			}
			if (!handleDataNeededReply(reply)) {
				String t = StringUtils.trimToEmpty(reply.getFieldValue(DataNeededReply.Type));
				String i = StringUtils.trimToEmpty(reply.getFieldValue(DataNeededReply.Id));
				if ("0".equals(t) && "0".equals(i))
					return;
				log.warn("processMessage() - DataNeededReply not managed by gift card flow");
			}
			return;
		}
		super.processMessage(message);
	}

	@Override
	protected void activateTenderMode() {
		if (ticketManager instanceof AmetllerScoTicketManager) {
			((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(false);
		}
		super.activateTenderMode();
	}

	@Override
	protected void trayPay(Tender message) {
		String tenderTypeRaw = StringUtils.trimToEmpty(message.getFieldValue(Tender.TenderType));
		String normalizedTender = tenderTypeRaw.replace(" ", "").toUpperCase(Locale.ROOT);
		String cardNumber = StringUtils.trimToNull(message.getFieldValue(Tender.UPC));

		PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
		GiftCardContext context = resolveGiftCardContext(tenderTypeRaw, normalizedTender, cardNumber, paymentsManager);

		if (context == null) {
			super.trayPay(message);
			return;
		}

		BigDecimal amount = parseTenderAmount(message);
		if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
			amount = ticketManager.getTicket().getTotales().getPendiente();
		}
		if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
			sendGiftCardError(I18N.getTexto("El importe indicado no es válido."), context.scoTenderType);
			return;
		}

		PendingPayment payment = new PendingPayment(message, context, cardNumber, amount);

		if (context.requiresConfirmation) {
			pendingPayment = payment;
			sendConfirmationDialog(context);
			return;
		}
		executeGiftCardPayment(payment);
	}

	private GiftCardContext resolveGiftCardContext(String tenderTypeRaw, String normalizedTender, String cardNumber, PaymentsManager paymentsManager) {
		if (paymentsManager == null)
			return null;

		Map<String, PaymentMethodManager> managers = paymentsManager.getPaymentsMehtodManagerAvailables();
		if (managers == null || managers.isEmpty())
			return null;

		String mappedCode = null;
		try {
			mappedCode = scoTenderTypeToComerzziaPaymentCode(tenderTypeRaw);
		}
		catch (RuntimeException ignore) {
			mappedCode = null;
		}

		if (mappedCode != null) {
			PaymentMethodManager manager = managers.get(mappedCode);
			if (manager instanceof GiftCardManager) {
				MedioPagoBean mp = mediosPagosService.getMedioPago(mappedCode);
				return new GiftCardContext(mappedCode, (GiftCardManager) manager, mp, false, tenderTypeRaw, false);
			}
		}

		boolean autoDetected = AUTO_TENDER_TYPE.equals(normalizedTender) || AUTO_TENDER_TYPE_ALT.equals(normalizedTender) || EXPLICIT_TENDER.equals(normalizedTender);

		if (!autoDetected)
			return null;

		if (StringUtils.isBlank(cardNumber)) {
			sendGiftCardError(I18N.getTexto("No se ha informado ningún número de tarjeta regalo."), tenderTypeRaw);
			return null;
		}

		GiftCardManager foundManager = null;
		String foundCode = null;
		for (Map.Entry<String, PaymentMethodManager> e : managers.entrySet()) {
			if (e.getValue() instanceof GiftCardManager) {
				if (foundManager != null) {
					foundManager = null;
					break;
				}
				foundManager = (GiftCardManager) e.getValue();
				foundCode = e.getKey();
			}
		}
		if (foundManager == null) {
			sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."), tenderTypeRaw);
			return null;
		}

		MedioPagoBean mp = mediosPagosService.getMedioPago(foundCode);
		return new GiftCardContext(foundCode, foundManager, mp, true, tenderTypeRaw, true);
	}

	private void sendConfirmationDialog(GiftCardContext context) {
		DataNeeded d = new DataNeeded();
		d.setFieldValue(DataNeeded.Type, DIALOG_CONFIRM_TYPE);
		d.setFieldValue(DataNeeded.Id, DIALOG_CONFIRM_ID);
		d.setFieldValue(DataNeeded.Mode, "0");

		String desc = context.medioPago != null ? context.medioPago.getDesMedioPago() : I18N.getTexto("Tarjeta regalo");
		d.setFieldValue(DataNeeded.TopCaption1, MessageFormat.format(I18N.getTexto("¿Desea usar su tarjeta {0}?"), desc));
		d.setFieldValue(DataNeeded.SummaryInstruction1, I18N.getTexto("Pulse una opción"));
		d.setFieldValue(DataNeeded.ButtonData1, "TxSi");
		d.setFieldValue(DataNeeded.ButtonText1, I18N.getTexto("SI"));
		d.setFieldValue(DataNeeded.ButtonData2, "TxNo");
		d.setFieldValue(DataNeeded.ButtonText2, I18N.getTexto("NO"));
		d.setFieldValue(DataNeeded.EnableScanner, "0");
		d.setFieldValue(DataNeeded.HideGoBack, "1");

		ncrController.sendMessage(d);
	}

	private boolean handleDataNeededReply(DataNeededReply reply) {
		String type = StringUtils.trimToEmpty(reply.getFieldValue(DataNeededReply.Type));
		String id = StringUtils.trimToEmpty(reply.getFieldValue(DataNeededReply.Id));

		if (!StringUtils.equals(type, DIALOG_CONFIRM_TYPE) || !StringUtils.equals(id, DIALOG_CONFIRM_ID)) {
			return false;
		}

		PendingPayment payment = pendingPayment;
		pendingPayment = null;

		sendCloseDialog(DIALOG_CONFIRM_TYPE, DIALOG_CONFIRM_ID);
		sendCloseDialog();

		if (payment == null)
			return true;

		String option = reply.getFieldValue(DataNeededReply.Data1);
		if (StringUtils.equalsIgnoreCase("TxSi", option)) {
			executeGiftCardPayment(payment);
		}
		else {
			sendGiftCardError(I18N.getTexto("Operación cancelada por el usuario"), payment.context.scoTenderType);
			itemsManager.sendTotals();
		}
		return true;
	}

	private boolean handleDescuento25DataNeededReply(DataNeededReply reply) {
		String type = StringUtils.trimToEmpty(reply.getFieldValue(DataNeededReply.Type));
		String id = StringUtils.trimToEmpty(reply.getFieldValue(DataNeededReply.Id));

		if (!StringUtils.equals(type, DESCUENTO25_DIALOG_TYPE) || !StringUtils.equals(id, DESCUENTO25_DIALOG_ID)) {
			return false;
		}

		if (log.isDebugEnabled()) {
			log.debug("handleDescuento25DataNeededReply() - Closing Descuento25 dialog");
		}

		sendCloseDialog(DESCUENTO25_DIALOG_TYPE, DESCUENTO25_DIALOG_ID);
		sendCloseDialog();
		return true;
	}

	private void sendShowWait(String caption) {
		DataNeeded w = new DataNeeded();
		w.setFieldValue(DataNeeded.Type, WAIT_TYPE);
		w.setFieldValue(DataNeeded.Id, WAIT_ID);
		w.setFieldValue(DataNeeded.Mode, "0"); // show
		if (StringUtils.isNotBlank(caption)) {
			w.setFieldValue(DataNeeded.TopCaption1, caption);
		}
		ncrController.sendMessage(w);
	}

	private void sendHideWait() {
//		DataNeeded w = new DataNeeded();
//		w.setFieldValue(DataNeeded.Type, WAIT_TYPE);
//		w.setFieldValue(DataNeeded.Id, WAIT_ID);
//		w.setFieldValue(DataNeeded.Mode, "1");
//		ncrController.sendMessage(w);
	}

	private void sendCloseDialog(String type, String id) {
		if (StringUtils.isBlank(type) || StringUtils.isBlank(id)) {
			return;
		}
//		DataNeeded close = new DataNeeded();
//		close.setFieldValue(DataNeeded.Type, type);
//		close.setFieldValue(DataNeeded.Id, id);
//		close.setFieldValue(DataNeeded.Mode, "1");
//		ncrController.sendMessage(close);
	}

	private void sendCloseDialog() {
		DataNeeded close = new DataNeeded();
		close.setFieldValue(DataNeeded.Type, "0");
		close.setFieldValue(DataNeeded.Id, "0");
		close.setFieldValue(DataNeeded.Mode, "1");
		ncrController.sendMessage(close);
	}

	private BigDecimal parseTenderAmount(Tender message) {
		String amountValue = message.getFieldValue(Tender.Amount);
		if (StringUtils.isBlank(amountValue))
			return null;
		try {
			return new BigDecimal(amountValue).divide(BigDecimal.valueOf(100));
		}
		catch (NumberFormatException e) {
			log.error("parseTenderAmount() - Unable to parse tender amount: " + amountValue, e);
			return null;
		}
	}

	private void executeGiftCardPayment(PendingPayment payment) {
		sendShowWait(I18N.getTexto("Validando tarjeta..."));

		try {
			GiftCardBean giftCard = consultGiftCard(payment.cardNumber);
			ensureGiftCardDefaults(giftCard);
			BigDecimal available = calculateAvailableBalance(giftCard);
			if (available.compareTo(BigDecimal.ZERO) <= 0)
				throw new GiftCardException(I18N.getTexto("El saldo de la tarjeta regalo no es suficiente."));

			BigDecimal amountToCharge = payment.amount.min(available);
			if (amountToCharge.compareTo(BigDecimal.ZERO) <= 0)
				throw new GiftCardException(I18N.getTexto("El saldo de la tarjeta regalo no es suficiente."));

			giftCard.setNumTarjetaRegalo(payment.cardNumber);
			giftCard.setImportePago(amountToCharge);

			payment.context.manager.addParameter(GiftCardManager.PARAM_TARJETA, giftCard);

			payment.message.setFieldIntValue(Tender.Amount, amountToCharge.setScale(2, RoundingMode.HALF_UP));

			PaymentsManager pm = ticketManager.getPaymentsManager();
			pm.pay(payment.context.paymentCode, amountToCharge);

			sendHideWait();
			sendCloseDialog();

		}
		catch (GiftCardException e) {
			log.error("executeGiftCardPayment() - " + e.getMessage(), e);
			sendGiftCardError(e.getMessage(), payment.context.scoTenderType);
			sendHideWait();
			sendCloseDialog();
		}
		catch (Exception e) {
			log.error("executeGiftCardPayment() - Unexpected error: " + e.getMessage(), e);
			sendGiftCardError(I18N.getTexto("No se ha podido validar la tarjeta regalo."), payment.context.scoTenderType);
			sendHideWait();
			sendCloseDialog();
		}
	}

	@Override
	protected void finishSale() {
		ticketManager.saveTicket();

		sendReceiptMessage();

		EndTransaction message = new EndTransaction();
		message.setFieldValue(EndTransaction.Id, itemsManager.getTransactionId());

		ncrController.sendMessage(message);

		itemsManager.resetTicket();
	}

	private void sendReceiptMessage() {
		Receipt receipt = new Receipt();
		receipt.setFieldValue(Receipt.Id, itemsManager.getTransactionId());
		receipt.setFieldValue(Receipt.Complete, Receipt.COMPLETE_OK);

		IPrinter impresora = Dispositivos.getInstance().getImpresora1();

		if (impresora instanceof NCRSCOPrinter) {
			((NCRSCOPrinter) impresora).setRecepipt(receipt);
			try {
				ticketManager.printDocument();
			}
			catch (Exception e) {
				log.error("sendReceiptMessage() - Error while printing document: " + e.getMessage(), e);
			}
		}
		else {
			if (impresora != null && log.isWarnEnabled()) {
				log.warn("sendReceiptMessage() - Unexpected printer implementation: " + impresora.getClass().getName());
			}
			else if (log.isWarnEnabled()) {
				log.warn("sendReceiptMessage() - No printer configured for SCO lane");
			}
		}

		ensureReceiptHasData(receipt);

		ncrController.sendMessage(receipt);
	}

	private void ensureReceiptHasData(Receipt receipt) {
		if (receipt == null) {
			return;
		}
		boolean hasPrinterData = false;
		for (String fieldName : receipt.getFields().keySet()) {
			if (StringUtils.startsWith(fieldName, Receipt.PrinterData)) {
				hasPrinterData = true;
				break;
			}
		}

		if (!hasPrinterData) {
			populateReceiptPrinterData(receipt);
		}
	}

	@SuppressWarnings("unchecked")
	private void populateReceiptPrinterData(Receipt receipt) {
		ITicket ticket = ticketManager != null ? ticketManager.getTicket() : null;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintWriter writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));

		writer.println(text("Ametller Origen"));

		if (ticket != null && ticket.getCabecera() != null) {
			Date fecha = ticket.getCabecera().getFecha();
			if (fecha != null) {
				writer.println(new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fecha));
			}

			String uidTicket = ticket.getCabecera().getUidTicket();
			if (StringUtils.isNotBlank(uidTicket)) {
				writer.println(text("Ticket") + ": " + uidTicket);
			}
		}

		writer.println(RECEIPT_SEPARATOR);

		int itemCount = 0;
		List<LineaTicket> lineas = ticket != null ? (List<LineaTicket>) ticket.getLineas() : null;
		if (lineas != null && !lineas.isEmpty()) {
			for (LineaTicket linea : lineas) {
				if (linea == null) {
					continue;
				}
				itemCount++;
				writer.println(buildReceiptDescription(linea));
				BigDecimal quantity = linea.getCantidad();
				BigDecimal unitPrice = firstNonNull(linea.getPrecioTotalConDto(), linea.getPrecioTotalSinDto());
				BigDecimal lineTotal = firstNonNull(linea.getImporteTotalConDto(), linea.getImporteTotalSinDto());
				writer.printf("  %s x %s = %s%n", formatQuantity(quantity), formatCurrency(unitPrice), formatCurrency(lineTotal));
			}
		}
		else {
			writer.println(text("Sin artículos"));
		}

		writer.println(RECEIPT_SEPARATOR);

		ITotalesTicket totales = ticket != null ? ticket.getTotales() : null;
		if (totales != null) {
			writer.printf("%s: %d%n", text("Artículos"), itemCount);

			BigDecimal total = firstNonNull(totales.getTotalAPagar(), BigDecimal.ZERO);
			BigDecimal impuestos = firstNonNull(totales.getImpuestos(), BigDecimal.ZERO);
			BigDecimal descuentos = firstNonNull(totales.getTotalPromociones(), BigDecimal.ZERO);
			BigDecimal entregado = firstNonNull(totales.getEntregado(), BigDecimal.ZERO);
			BigDecimal pendiente = firstNonNull(totales.getPendiente(), BigDecimal.ZERO);

			if (descuentos.compareTo(BigDecimal.ZERO) > 0) {
				writer.printf("%s: -%s%n", text("Descuentos"), formatCurrency(descuentos));
			}

			if (impuestos.compareTo(BigDecimal.ZERO) > 0) {
				writer.printf("%s: %s%n", text("Impuestos"), formatCurrency(impuestos));
			}

			writer.printf("%s: %s%n", text("Total"), formatCurrency(total));

			if (entregado.compareTo(BigDecimal.ZERO) > 0) {
				writer.printf("%s: %s%n", text("Pagado"), formatCurrency(entregado));
			}

			if (pendiente.compareTo(BigDecimal.ZERO) > 0) {
				writer.printf("%s: %s%n", text("Pendiente"), formatCurrency(pendiente));
			}
		}

		appendPayments(writer, ticket);

		writer.println();
		writer.println(text("Gracias por su compra"));

		writer.flush();

		if (buffer.size() > 0) {
			receipt.addPrinterData(buffer, StandardCharsets.UTF_8.name());
		}
	}

	@SuppressWarnings("unchecked")
	private void appendPayments(PrintWriter writer, ITicket ticket) {
		if (ticket == null) {
			return;
		}

		List<PagoTicket> pagos;
		try {
			pagos = (List<PagoTicket>) ticket.getPagos();
		}
		catch (ClassCastException e) {
			return;
		}

		if (pagos == null || pagos.isEmpty()) {
			return;
		}

		writer.println(text("Pagos"));
		for (PagoTicket pago : pagos) {
			if (pago == null) {
				continue;
			}

			MedioPagoBean medio = pago.getMedioPago();
			String descripcion = medio != null && StringUtils.isNotBlank(medio.getDesMedioPago()) ? medio.getDesMedioPago() : text("Pago");

			BigDecimal importe = firstNonNull(pago.getImporte(), BigDecimal.ZERO);

			if (importe.compareTo(BigDecimal.ZERO) == 0) {
				continue;
			}

			writer.printf("  %s: %s%n", descripcion, formatCurrency(importe));
		}
	}

	private BigDecimal firstNonNull(BigDecimal... values) {
		if (values == null) {
			return BigDecimal.ZERO;
		}
		for (BigDecimal value : values) {
			if (value != null) {
				return value;
			}
		}
		return BigDecimal.ZERO;
	}

	private String formatCurrency(BigDecimal amount) {
		BigDecimal value = amount != null ? amount : BigDecimal.ZERO;
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(RECEIPT_LOCALE);
		symbols.setDecimalSeparator(',');
		symbols.setGroupingSeparator('.');
		DecimalFormat formatter = new DecimalFormat("#,##0.00", symbols);
		return formatter.format(value) + " €";
	}

	private String formatQuantity(BigDecimal quantity) {
		BigDecimal value = quantity != null ? quantity : BigDecimal.ONE;
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(RECEIPT_LOCALE);
		symbols.setDecimalSeparator(',');
		symbols.setGroupingSeparator('.');
		DecimalFormat formatter = new DecimalFormat("#,##0.###", symbols);
		return formatter.format(value);
	}

	private String buildReceiptDescription(LineaTicket linea) {
		if (linea == null) {
			return text("Artículo");
		}

		StringBuilder description = new StringBuilder(StringUtils.trimToEmpty(linea.getDesArticulo()));

		if (!StringUtils.equals(linea.getDesglose1(), "*")) {
			if (description.length() > 0) {
				description.append(' ');
			}
			description.append(StringUtils.trimToEmpty(linea.getDesglose1()));
		}

		if (!StringUtils.equals(linea.getDesglose2(), "*")) {
			if (description.length() > 0) {
				description.append(' ');
			}
			description.append(StringUtils.trimToEmpty(linea.getDesglose2()));
		}

		return description.toString();
	}

	private String text(String defaultText) {
		try {
			String translated = I18N.getTexto(defaultText);
			if (StringUtils.isNotBlank(translated)) {
				return translated;
			}
		}
		catch (Exception ignore) {
		}
		return defaultText;
	}

	private GiftCardBean consultGiftCard(String cardNumber) throws GiftCardException {
		if (StringUtils.isBlank(cardNumber))
			throw new GiftCardException(I18N.getTexto("No se ha informado ningún número de tarjeta regalo."));

		try {
			Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.FidelizadosRest");
			Class<?> requestClass;
			try {
				requestClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.ConsultarFidelizadoRequestRest");
			}
			catch (ClassNotFoundException ignored) {
				requestClass = null;
			}

			Object response = invokeGiftCardEndpoint(restClass, requestClass, cardNumber);
			Object payload = extractGiftCardPayload(response);
			GiftCardBean bean = convertToGiftCardBean(payload, cardNumber);
			if (bean == null)
				throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
			return bean;

		}
		catch (GiftCardException e) {
			throw e;
		}
		catch (ClassNotFoundException e) {
			throw new GiftCardException(I18N.getTexto("Servicio de tarjetas regalo no disponible."), e);
		}
	}

	private Object invokeGiftCardEndpoint(Class<?> restClass, Class<?> requestClass, String cardNumber) throws GiftCardException {
		String apiKey = null;
		try {
			apiKey = variablesServices.getVariableAsString(Variables.WEBSERVICES_APIKEY);
		}
		catch (Exception e) {
			log.debug("invokeGiftCardEndpoint() - Unable to resolve API key", e);
		}

		String uidActividad = null;
		if (sesion != null && sesion.getAplicacion() != null)
			uidActividad = sesion.getAplicacion().getUidActividad();

		if (requestClass != null) {
			Method m = findMethod(restClass, "getTarjetaRegalo", requestClass);
			if (m != null) {
				Object req = instantiateGiftCardRequest(requestClass, cardNumber, apiKey, uidActividad);
				return invokeGiftCardMethod(m, new Object[] { req });
			}
		}

		for (Method m : restClass.getMethods()) {
			if (!Modifier.isStatic(m.getModifiers()) || !"getTarjetaRegalo".equals(m.getName()))
				continue;
			if (allStringParameters(m.getParameterTypes())) {
				Object r = tryInvokeWithStringArguments(m, cardNumber, apiKey, uidActividad);
				if (r != null)
					return r;
			}
		}
		throw new GiftCardException(I18N.getTexto("Servicio de tarjetas regalo no disponible."));
	}

	private Object invokeGiftCardMethod(Method method, Object[] args) throws GiftCardException {
		try {
			return method.invoke(null, args);
		}
		catch (IllegalAccessException e) {
			throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
		}
		catch (InvocationTargetException e) {
			Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
			throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), cause);
		}
	}

	private Object instantiateGiftCardRequest(Class<?> requestClass, String cardNumber, String apiKey, String uidActividad) throws GiftCardException {
		try {
			Object instance = instantiateWithDefaults(requestClass);
			applyStringProperty(instance, new String[] { "setNumeroTarjeta", "setNumTarjeta", "setNumeroTarjetaRegalo", "setTarjeta" }, cardNumber);
			applyStringProperty(instance, new String[] { "setUidActividad", "setUidActividadServicio" }, uidActividad);
			applyStringProperty(instance, new String[] { "setApiKey" }, apiKey);
			return instance;
		}
		catch (ReflectiveOperationException e) {
			throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
		}
	}

	private Object instantiateWithDefaults(Class<?> clazz) throws ReflectiveOperationException {
		try {
			Constructor<?> ctor = clazz.getDeclaredConstructor();
			ctor.setAccessible(true);
			return ctor.newInstance();
		}
		catch (NoSuchMethodException e) {
			for (Constructor<?> c : clazz.getDeclaredConstructors()) {
				c.setAccessible(true);
				Object[] args = buildDefaultArguments(c.getParameterTypes());
				if (args == null)
					continue;
				try {
					return c.newInstance(args);
				}
				catch (InvocationTargetException | InstantiationException | IllegalAccessException ignore) {
				}
			}
			throw e;
		}
	}

	private Object[] buildDefaultArguments(Class<?>[] parameterTypes) {
		Object[] args = new Object[parameterTypes.length];
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> p = parameterTypes[i];
			if (!p.isPrimitive())
				args[i] = null;
			else if (p == boolean.class)
				args[i] = Boolean.FALSE;
			else if (p == byte.class)
				args[i] = (byte) 0;
			else if (p == short.class)
				args[i] = (short) 0;
			else if (p == int.class)
				args[i] = 0;
			else if (p == long.class)
				args[i] = 0L;
			else if (p == float.class)
				args[i] = 0f;
			else if (p == double.class)
				args[i] = 0d;
			else if (p == char.class)
				args[i] = '\0';
			else
				return null;
		}
		return args;
	}

	private void applyStringProperty(Object target, String[] methodNames, String value) throws IllegalAccessException, InvocationTargetException {
		if (target == null || StringUtils.isBlank(value) || methodNames == null)
			return;
		for (String name : methodNames) {
			Method setter = findMethod(target.getClass(), name, String.class);
			if (setter != null) {
				setter.invoke(target, value);
				return;
			}
		}
	}

	private boolean allStringParameters(Class<?>[] parameterTypes) {
		if (parameterTypes.length == 0)
			return false;
		for (Class<?> t : parameterTypes)
			if (!String.class.equals(t))
				return false;
		return true;
	}

	private Object tryInvokeWithStringArguments(Method method, String cardNumber, String apiKey, String uidActividad) throws GiftCardException {
		List<Object[]> cands = buildStringArgumentCandidates(method.getParameterCount(), cardNumber, apiKey, uidActividad);
		for (Object[] c : cands) {
			try {
				return method.invoke(null, c);
			}
			catch (IllegalArgumentException ignore) {
			}
			catch (IllegalAccessException e) {
				throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
			}
			catch (InvocationTargetException e) {
				Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
				throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), cause);
			}
		}
		return null;
	}

	private List<Object[]> buildStringArgumentCandidates(int n, String cardNumber, String apiKey, String uidActividad) {
		List<Object[]> out = new ArrayList<>();
		if (n <= 0)
			return out;
		if (n == 1) {
			out.add(new Object[] { cardNumber });
			if (apiKey != null)
				out.add(new Object[] { apiKey });
			if (uidActividad != null)
				out.add(new Object[] { uidActividad });
			return out;
		}
		if (n == 2) {
			out.add(new Object[] { cardNumber, apiKey });
			out.add(new Object[] { cardNumber, uidActividad });
			out.add(new Object[] { apiKey, uidActividad });
			out.add(new Object[] { uidActividad, apiKey });
			out.add(new Object[] { apiKey, cardNumber });
			out.add(new Object[] { uidActividad, cardNumber });
			return out;
		}
		if (n == 3) {
			Object[] v = new Object[] { cardNumber, apiKey, uidActividad };
			int[][] p = new int[][] { { 0, 1, 2 }, { 0, 2, 1 }, { 1, 0, 2 }, { 1, 2, 0 }, { 2, 0, 1 }, { 2, 1, 0 } };
			for (int[] a : p) {
				Object[] c = new Object[n];
				for (int i = 0; i < n; i++)
					c[i] = v[a[i]];
				out.add(c);
			}
			return out;
		}
		Object[] fb = new Object[n];
		Arrays.fill(fb, null);
		if (n > 0)
			fb[0] = cardNumber;
		if (n > 1)
			fb[1] = apiKey;
		if (n > 2)
			fb[2] = uidActividad;
		out.add(fb);
		return out;
	}

	private Object extractGiftCardPayload(Object response) throws GiftCardException {
		if (response == null)
			return null;
		if (response instanceof GiftCardBean)
			return response;
		Method g = findNoArgMethod(response.getClass(), "getTarjetaRegalo", "getTarjeta", "getGiftCard");
		if (g != null) {
			try {
				return g.invoke(response);
			}
			catch (IllegalAccessException | InvocationTargetException e) {
				throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
			}
		}
		return response;
	}

	private GiftCardBean convertToGiftCardBean(Object payload, String cardNumber) {
		if (payload == null)
			return null;

		if (payload instanceof GiftCardBean) {
			GiftCardBean b = (GiftCardBean) payload;
			if (StringUtils.isBlank(b.getNumTarjetaRegalo()))
				b.setNumTarjetaRegalo(cardNumber);
			ensureGiftCardDefaults(b);
			return b;
		}

		GiftCardBean b = new GiftCardBean();
		b.setNumTarjetaRegalo(cardNumber);

		BigDecimal saldo = readBigDecimal(payload, "getSaldo");
		BigDecimal saldoProv = readBigDecimal(payload, "getSaldoProvisional");
		BigDecimal saldoTotal = readBigDecimal(payload, "getSaldoTotal");
		BigDecimal saldoDisponible = readBigDecimal(payload, "getSaldoDisponible");

		if (saldo == null)
			saldo = (saldoTotal != null ? saldoTotal : saldoDisponible);
		if (saldo == null)
			saldo = BigDecimal.ZERO;
		if (saldoProv == null)
			saldoProv = BigDecimal.ZERO;

		b.setSaldo(saldo);
		b.setSaldoProvisional(saldoProv);
		return b;
	}

	private BigDecimal readBigDecimal(Object src, String methodName) {
		if (src == null)
			return null;
		Method m = findNoArgMethod(src.getClass(), methodName);
		if (m == null)
			return null;
		try {
			Object v = m.invoke(src);
			if (v == null)
				return null;
			if (v instanceof BigDecimal)
				return (BigDecimal) v;
			if (v instanceof Number)
				return BigDecimal.valueOf(((Number) v).doubleValue());
			return new BigDecimal(v.toString());
		}
		catch (Exception ignore) {
			return null;
		}
	}

	private Method findNoArgMethod(Class<?> t, String... names) {
		for (String n : names) {
			if (n == null)
				continue;
			try {
				Method m = t.getMethod(n);
				m.setAccessible(true);
				return m;
			}
			catch (NoSuchMethodException ignore) {
			}
		}
		return null;
	}

	private BigDecimal calculateAvailableBalance(GiftCardBean b) {
		ensureGiftCardDefaults(b);
		try {
			return b.getSaldoTotal();
		}
		catch (Exception ex) {
			BigDecimal s = b.getSaldo() != null ? b.getSaldo() : BigDecimal.ZERO;
			BigDecimal sp = b.getSaldoProvisional() != null ? b.getSaldoProvisional() : BigDecimal.ZERO;
			return s.add(sp);
		}
	}

	private void ensureGiftCardDefaults(GiftCardBean b) {
		if (b.getSaldo() == null)
			b.setSaldo(BigDecimal.ZERO);
		if (b.getSaldoProvisional() == null)
			b.setSaldoProvisional(BigDecimal.ZERO);
	}

	private Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
		Class<?> c = type;
		while (c != null) {
			try {
				Method m = c.getMethod(name, parameterTypes);
				m.setAccessible(true);
				return m;
			}
			catch (NoSuchMethodException e) {
				c = c.getSuperclass();
			}
		}
		return null;
	}

	private void sendGiftCardError(String message, String tenderType) {
		TenderException te = new TenderException();
		te.setFieldValue(TenderException.ExceptionId, "0");
		te.setFieldValue(TenderException.ExceptionType, "0");
		if (StringUtils.isNotBlank(tenderType))
			te.setFieldValue(TenderException.TenderType, tenderType);
		te.setFieldValue(TenderException.Message, message);
		ncrController.sendMessage(te);
	}

	private static class PendingPayment {

		private final Tender message;
		private final GiftCardContext context;
		private final String cardNumber;
		private final BigDecimal amount;

		PendingPayment(Tender m, GiftCardContext c, String n, BigDecimal a) {
			this.message = m;
			this.context = c;
			this.cardNumber = n;
			this.amount = a;
		}
	}

	private static class GiftCardContext {

		private final String paymentCode;
		private final GiftCardManager manager;
		private final MedioPagoBean medioPago;
		private final boolean requiresConfirmation;
		private final String scoTenderType;
		private final boolean autoDetected;

		GiftCardContext(String code, GiftCardManager m, MedioPagoBean mp, boolean confirm, String scoType, boolean auto) {
			this.paymentCode = code;
			this.manager = m;
			this.medioPago = mp;
			this.requiresConfirmation = confirm;
			this.scoTenderType = scoType;
			this.autoDetected = auto;
		}
	}

	private static class GiftCardException extends Exception {

		private static final long serialVersionUID = 1L;

		GiftCardException(String m) {
			super(m);
		}

		GiftCardException(String m, Throwable c) {
			super(m, c);
		}
	}
}