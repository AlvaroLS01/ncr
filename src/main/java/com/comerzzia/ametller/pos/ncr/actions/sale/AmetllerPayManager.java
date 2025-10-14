package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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
import com.comerzzia.api.rest.client.exceptions.RestException;
import com.comerzzia.api.rest.client.exceptions.RestHttpException;
import com.comerzzia.api.rest.client.movimientos.ListaMovimientoRequestRest;
import com.comerzzia.api.rest.client.movimientos.MovimientoRequestRest;
import com.comerzzia.api.rest.client.movimientos.MovimientosRest;
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
import com.comerzzia.pos.ncr.messages.TenderAccepted;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.persistence.giftcard.GiftCardBean;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.events.PaymentOkEvent;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.BasicPaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;
import com.comerzzia.pos.persistence.promociones.tipos.PromocionTipoBean;
import com.comerzzia.pos.services.ticket.ITicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.pagos.IPagoTicket;
import com.comerzzia.pos.services.ticket.pagos.PagoTicket;
import com.comerzzia.pos.services.ticket.pagos.tarjeta.DatosRespuestaPagoTarjeta;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
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

        private final Map<String, GiftCardPaymentContext> pendingGiftCardPayments = new HashMap<>();
        private final Map<Integer, String> paymentIdToGiftCardUid = new HashMap<>();

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

	// LUST-141048 Correción pendiente pago de promociones del estandar sobre la clase personalizada
	@Override
	@SuppressWarnings("unchecked")
	protected void addHeaderDiscountsPayments() {
		Map<String, BigDecimal> descuentosPromocionales = new HashMap<String, BigDecimal>();

		for (PromocionTicket promocion : (List<PromocionTicket>) ticketManager.getTicket().getPromociones()) {
			if (promocion.isDescuentoMenosIngreso()) {
				PromocionTipoBean tipoPromocion = ticketManager.getSesion().getSesionPromociones().getPromocionActiva(promocion.getIdPromocion()).getPromocionBean().getTipoPromocion();
				String codMedioPago = tipoPromocion.getCodMedioPagoMenosIngreso();
				if (codMedioPago != null) {
					BigDecimal importeDescPromocional = BigDecimalUtil.redondear(promocion.getImporteTotalAhorro(), 2);
					BigDecimal importeDescAcum = descuentosPromocionales.get(codMedioPago) != null ? descuentosPromocionales.get(codMedioPago) : BigDecimal.ZERO;
					importeDescAcum = importeDescAcum.add(importeDescPromocional);
					descuentosPromocionales.put(codMedioPago, importeDescAcum);
				}
			}
		}

		ticketManager.getTicket().getPagos().removeIf(p -> {
			if (!(p instanceof IPagoTicket)) {
				return false;
			}

			IPagoTicket pagoTicket = (IPagoTicket) p;

			if (!pagoTicket.isIntroducidoPorCajero()) {
				return true;
			}

			MedioPagoBean medioPago = pagoTicket.getMedioPago();

			return medioPago != null && descuentosPromocionales.containsKey(medioPago.getCodMedioPago());
		});
		ticketManager.getTicket().getCabecera().getTotales().recalcular();

		for (Map.Entry<String, BigDecimal> descuento : descuentosPromocionales.entrySet()) {
			String codMedioPago = descuento.getKey();
			BigDecimal importe = descuento.getValue();

			if (BigDecimalUtil.isMayorACero(importe)) {
				ticketManager.addPayToTicket(codMedioPago, importe, null, true, false);
			}
		}

		ticketManager.getTicket().getCabecera().getTotales().recalcular();
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
		// DataNeeded w = new DataNeeded();
		// w.setFieldValue(DataNeeded.Type, WAIT_TYPE);
		// w.setFieldValue(DataNeeded.Id, WAIT_ID);
		// w.setFieldValue(DataNeeded.Mode, "1");
		// ncrController.sendMessage(w);
	}

	private void sendCloseDialog(String type, String id) {
		if (StringUtils.isBlank(type) || StringUtils.isBlank(id)) {
			return;
		}
		// DataNeeded close = new DataNeeded();
		// close.setFieldValue(DataNeeded.Type, type);
		// close.setFieldValue(DataNeeded.Id, id);
		// close.setFieldValue(DataNeeded.Mode, "1");
		// ncrController.sendMessage(close);
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
                        GiftCardBean giftCard = consultarTarjetaRegalo(payment.cardNumber);
                        ensureGiftCardDefaults(giftCard);
                        BigDecimal available = calculateAvailableBalance(giftCard);
                        if (available.compareTo(BigDecimal.ZERO) <= 0) {
                                throw new GiftCardException(I18N.getTexto("El saldo de la tarjeta regalo no es suficiente."));
                        }

                        BigDecimal amountToCharge = payment.amount.min(available);
                        if (amountToCharge.compareTo(BigDecimal.ZERO) <= 0) {
                                throw new GiftCardException(I18N.getTexto("El saldo de la tarjeta regalo no es suficiente."));
                        }

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

                clearPendingGiftCardPayments();
                itemsManager.resetTicket();
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

        private void sendReceiptMessage() {
                Receipt receipt = new Receipt();
                receipt.setFieldValue(Receipt.Id, itemsManager.getTransactionId());
                receipt.setFieldValue(Receipt.Complete, Receipt.COMPLETE_OK);

                IPrinter printer = Dispositivos.getInstance().getImpresora1();
                if (printer instanceof NCRSCOPrinter) {
                        ((NCRSCOPrinter) printer).setRecepipt(receipt);
                        try {
                                ticketManager.printDocument();
                        }
                        catch (Exception e) {
                                log.error("sendReceiptMessage() - Error al imprimir el documento: " + e.getMessage(), e);
                        }
                }
                else if (printer != null) {
                        log.warn("sendReceiptMessage() - Implementación de impresora no esperada: " + printer.getClass().getName());
                }
                else {
                        log.warn("sendReceiptMessage() - No existe impresora configurada para SCO");
                }

                ensureReceiptHasData(receipt);
                ncrController.sendMessage(receipt);
        }

        private void ensureReceiptHasData(Receipt receipt) {
                if (receipt == null) {
                        return;
                }
                for (String fieldName : receipt.getFields().keySet()) {
                        if (StringUtils.startsWith(fieldName, Receipt.PrinterData)) {
                                return;
                        }
                }
                String data = buildReceiptPrinterData();
                if (StringUtils.isNotBlank(data)) {
                        receipt.setFieldValue(Receipt.PrinterData + "1", data);
                }
        }

        private String buildReceiptPrinterData() {
                ITicket ticket = ticketManager != null ? ticketManager.getTicket() : null;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));

                writer.println("Ametller Origen");
                if (ticket != null && ticket.getCabecera() != null) {
                        Date fecha = ticket.getCabecera().getFecha();
                        if (fecha != null) {
                                writer.println(new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fecha));
                        }
                        String uidTicket = StringUtils.trimToNull(ticket.getCabecera().getUidTicket());
                        if (uidTicket != null) {
                                writer.println("Ticket: " + uidTicket);
                        }
                }

                writer.println(RECEIPT_SEPARATOR);
                appendReceiptLines(writer, resolveTicketLines(ticket));
                writer.println(RECEIPT_SEPARATOR);
                appendReceiptTotals(writer, ticket);
                writer.println(RECEIPT_SEPARATOR);
                appendReceiptPayments(writer, resolveTicketPayments(ticket));
                writer.println(RECEIPT_SEPARATOR);
                writer.println("¡Gracias por su compra!");
                writer.flush();

                return buffer.size() > 0 ? new String(buffer.toByteArray(), StandardCharsets.UTF_8) : null;
        }

        private void appendReceiptLines(PrintWriter writer, List<LineaTicket> lines) {
                if (lines == null || lines.isEmpty()) {
                        writer.println("Sin artículos");
                        return;
                }
                for (LineaTicket line : lines) {
                        if (line == null || BigDecimalUtil.isCero(line.getImporteTotalConDto())) {
                                continue;
                        }
                        writer.println(buildLineDescription(line));
                        writer.printf("  %s x %s = %s%n", formatQuantity(line.getCantidad()), formatCurrency(line.getPrecioTotalConDto()),
                                formatCurrency(line.getImporteTotalConDto()));
                }
        }

        private void appendReceiptTotals(PrintWriter writer, ITicket ticket) {
                if (ticket == null || ticket.getCabecera() == null || ticket.getCabecera().getTotales() == null) {
                        return;
                }
                BigDecimal subtotal = firstNonNull(ticket.getCabecera().getTotales().getTotalVentaSinDto(), BigDecimal.ZERO);
                BigDecimal descuentos = firstNonNull(ticket.getCabecera().getTotales().getTotalPromociones(), BigDecimal.ZERO);
                BigDecimal total = firstNonNull(ticket.getCabecera().getTotales().getTotalVentaConDto(), BigDecimal.ZERO);

                writer.printf("Subtotal: %s%n", formatCurrency(subtotal));
                if (descuentos.compareTo(BigDecimal.ZERO) > 0) {
                        writer.printf("Descuentos: -%s%n", formatCurrency(descuentos));
                }
                writer.printf("Total: %s%n", formatCurrency(total));
        }

        private void appendReceiptPayments(PrintWriter writer, List<PagoTicket> payments) {
                if (payments == null || payments.isEmpty()) {
                        writer.println("Pagos: -");
                        return;
                }
                writer.println("Pagos");
                for (PagoTicket payment : payments) {
                        if (payment == null) {
                                continue;
                        }
                        BigDecimal amount = firstNonNull(payment.getImporte(), BigDecimal.ZERO);
                        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                                continue;
                        }
                        MedioPagoBean medio = payment.getMedioPago();
                        String description = medio != null && StringUtils.isNotBlank(medio.getDesMedioPago())
                                ? medio.getDesMedioPago()
                                : "Pago";
                        writer.printf("  %s: %s%n", description, formatCurrency(amount));
                }
        }

        private List<LineaTicket> resolveTicketLines(ITicket ticket) {
                if (ticket == null) {
                        return Collections.emptyList();
                }
                try {
                        @SuppressWarnings("unchecked")
                        List<LineaTicket> lines = (List<LineaTicket>) ticket.getLineas();
                        return lines != null ? lines : Collections.emptyList();
                }
                catch (ClassCastException e) {
                        return Collections.emptyList();
                }
        }

        private List<PagoTicket> resolveTicketPayments(ITicket ticket) {
                if (ticket == null) {
                        return Collections.emptyList();
                }
                try {
                        @SuppressWarnings("unchecked")
                        List<PagoTicket> payments = (List<PagoTicket>) ticket.getPagos();
                        return payments != null ? payments : Collections.emptyList();
                }
                catch (ClassCastException e) {
                        return Collections.emptyList();
                }
        }

        private String buildLineDescription(LineaTicket line) {
                StringBuilder description = new StringBuilder(StringUtils.trimToEmpty(line.getDesArticulo()));
                appendLineDetail(description, line.getDesglose1());
                appendLineDetail(description, line.getDesglose2());
                appendLineDetail(description, line.getDesglose3());
                return description.toString();
        }

        private void appendLineDetail(StringBuilder description, String detail) {
                String value = StringUtils.trimToNull(detail);
                if (value == null || "*".equals(value)) {
                        return;
                }
                if (description.length() > 0) {
                        description.append(" - ");
                }
                description.append(value);
        }

        private BigDecimal firstNonNull(BigDecimal candidate, BigDecimal fallback) {
                return candidate != null ? candidate : fallback;
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
                return new DecimalFormat("#,##0.###", symbols).format(value);
        }

        private GiftCardBean consultarTarjetaRegalo(String numeroTarjeta) throws GiftCardException {
                if (StringUtils.isBlank(numeroTarjeta)) {
                        throw new GiftCardException(I18N.getTexto("No se ha informado ningún número de tarjeta regalo."));
                }
                try {
                        Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.FidelizadosRest");
                        Method requestMethod = findGiftCardRequestMethod(restClass);
                        Object respuesta;
                        if (requestMethod != null) {
                                Object request = crearPeticionTarjeta(requestMethod.getParameterTypes()[0], numeroTarjeta);
                                respuesta = requestMethod.invoke(null, request);
                        }
                        else {
                                Method fallback = findGiftCardStringMethod(restClass);
                                if (fallback == null) {
                                        throw new GiftCardException(I18N.getTexto("Servicio de tarjetas regalo no disponible."));
                                }
                                Object[] argumentos = construirArgumentosTarjeta(fallback.getParameterTypes(), numeroTarjeta);
                                if (argumentos == null) {
                                        throw new GiftCardException(I18N.getTexto("Servicio de tarjetas regalo no disponible."));
                                }
                                respuesta = fallback.invoke(null, argumentos);
                        }

                        GiftCardBean tarjeta = convertirRespuestaTarjeta(respuesta, numeroTarjeta);
                        if (tarjeta == null) {
                                throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
                        }
                        return tarjeta;
                }
                catch (ClassNotFoundException e) {
                        throw new GiftCardException(I18N.getTexto("Servicio de tarjetas regalo no disponible."), e);
                }
                catch (InvocationTargetException e) {
                        Throwable causa = e.getTargetException() != null ? e.getTargetException() : e;
                        throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), causa);
                }
                catch (IllegalAccessException e) {
                        throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
                }
        }

        private Method findGiftCardRequestMethod(Class<?> restClass) {
                for (Method method : restClass.getMethods()) {
                        if (!Modifier.isStatic(method.getModifiers()) || !"getTarjetaRegalo".equals(method.getName())) {
                                continue;
                        }
                        if (method.getParameterCount() == 1 && !String.class.equals(method.getParameterTypes()[0])) {
                                method.setAccessible(true);
                                return method;
                        }
                }
                return null;
        }

        private Method findGiftCardStringMethod(Class<?> restClass) {
                for (Method method : restClass.getMethods()) {
                        if (!Modifier.isStatic(method.getModifiers()) || !"getTarjetaRegalo".equals(method.getName())) {
                                continue;
                        }
                        Class<?>[] parameterTypes = method.getParameterTypes();
                        boolean allStrings = true;
                        for (Class<?> parameterType : parameterTypes) {
                                if (!String.class.equals(parameterType)) {
                                        allStrings = false;
                                        break;
                                }
                        }
                        if (allStrings) {
                                method.setAccessible(true);
                                return method;
                        }
                }
                return null;
        }

        private Object crearPeticionTarjeta(Class<?> requestType, String numeroTarjeta) throws GiftCardException {
                try {
                        Object request = requestType.getDeclaredConstructor().newInstance();
                        setIfPresent(request, numeroTarjeta, "setNumeroTarjeta", "setNumTarjeta", "setNumeroTarjetaRegalo", "setTarjeta");
                        setIfPresent(request, obtenerUidActividad(), "setUidActividad", "setUidActividadServicio");
                        setIfPresent(request, obtenerApiKey(), "setApiKey");
                        return request;
                }
                catch (ReflectiveOperationException e) {
                        throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
                }
        }

        private void setIfPresent(Object target, String value, String... methodNames) throws ReflectiveOperationException {
                if (target == null || StringUtils.isBlank(value) || methodNames == null) {
                        return;
                }
                for (String name : methodNames) {
                        Method setter = findMethod(target.getClass(), name, String.class);
                        if (setter != null) {
                                setter.invoke(target, value);
                                return;
                        }
                }
        }

        private Object[] construirArgumentosTarjeta(Class<?>[] parameterTypes, String numeroTarjeta) {
                if (parameterTypes == null) {
                        return null;
                }
                Object[] args = new Object[parameterTypes.length];
                for (int i = 0; i < parameterTypes.length; i++) {
                        if (!String.class.equals(parameterTypes[i])) {
                                return null;
                        }
                }
                if (parameterTypes.length > 0) {
                        args[0] = numeroTarjeta;
                }
                if (parameterTypes.length > 1) {
                        args[1] = obtenerApiKey();
                }
                if (parameterTypes.length > 2) {
                        args[2] = obtenerUidActividad();
                }
                return args;
        }

        private GiftCardBean convertirRespuestaTarjeta(Object respuesta, String numeroTarjeta) throws GiftCardException {
                Object payload = extraerPayloadTarjeta(respuesta);
                if (payload == null) {
                        return null;
                }
                if (payload instanceof GiftCardBean) {
                        return (GiftCardBean) payload;
                }
                GiftCardBean tarjeta = new GiftCardBean();
                tarjeta.setNumTarjetaRegalo(numeroTarjeta);
                BigDecimal saldo = extraerBigDecimal(payload, "getSaldo", "getBalance");
                BigDecimal saldoProvisional = extraerBigDecimal(payload, "getSaldoProvisional", "getProvisionalBalance");
                String uid = extraerString(payload, "getUidTransaccion", "getTransactionUid");
                if (saldo != null) {
                        tarjeta.setSaldo(saldo);
                }
                if (saldoProvisional != null) {
                        tarjeta.setSaldoProvisional(saldoProvisional);
                }
                if (uid != null) {
                        tarjeta.setUidTransaccion(uid);
                }
                return tarjeta;
        }

        private Object extraerPayloadTarjeta(Object respuesta) throws GiftCardException {
                if (respuesta == null) {
                        return null;
                }
                if (respuesta instanceof GiftCardBean) {
                        return respuesta;
                }
                Method getter = findMethod(respuesta.getClass(), "getTarjetaRegalo");
                if (getter == null) {
                        getter = findMethod(respuesta.getClass(), "getTarjeta");
                }
                if (getter == null) {
                        getter = findMethod(respuesta.getClass(), "getGiftCard");
                }
                if (getter != null) {
                        try {
                                return getter.invoke(respuesta);
                        }
                        catch (IllegalAccessException | InvocationTargetException e) {
                                throw new GiftCardException(I18N.getTexto("No se ha podido validar la tarjeta regalo."), e);
                        }
                }
                return respuesta;
        }

        private BigDecimal extraerBigDecimal(Object source, String... methodNames) {
                if (source == null || methodNames == null) {
                        return null;
                }
                for (String methodName : methodNames) {
                        Method getter = findMethod(source.getClass(), methodName);
                        if (getter == null) {
                                continue;
                        }
                        try {
                                Object value = getter.invoke(source);
                                if (value instanceof BigDecimal) {
                                        return (BigDecimal) value;
                                }
                                if (value instanceof Number) {
                                        return BigDecimal.valueOf(((Number) value).doubleValue());
                                }
                        }
                        catch (IllegalAccessException | InvocationTargetException e) {
                                log.warn("extraerBigDecimal() - No se pudo leer el valor del saldo", e);
                        }
                }
                return null;
        }

        private String extraerString(Object source, String... methodNames) {
                if (source == null || methodNames == null) {
                        return null;
                }
                for (String methodName : methodNames) {
                        Method getter = findMethod(source.getClass(), methodName);
                        if (getter == null) {
                                continue;
                        }
                        try {
                                Object value = getter.invoke(source);
                                if (value instanceof String) {
                                        return (String) value;
                                }
                        }
                        catch (IllegalAccessException | InvocationTargetException e) {
                                log.warn("extraerString() - No se pudo leer el texto", e);
                        }
                }
                return null;
        }

        private String obtenerApiKey() {
                if (variablesServices == null) {
                        return null;
                }
                try {
                        return variablesServices.getVariableAsString(Variables.WEBSERVICES_APIKEY);
                }
                catch (Exception e) {
                        log.debug("obtenerApiKey() - No se pudo resolver la API key", e);
                        return null;
                }
        }

        private String obtenerUidActividad() {
                if (sesion == null || sesion.getAplicacion() == null) {
                        return null;
                }
                return sesion.getAplicacion().getUidActividad();
        }
	@Override
	protected void processPaymentOk(PaymentOkEvent eventOk) {
		registerOrClearGiftCardPayment(eventOk);
		if (eventOk == null) {
			super.processPaymentOk(eventOk);
			return;
		}

		BigDecimal amount = eventOk.getAmount();

		Object source = eventOk.getSource();
		if (!(source instanceof PaymentMethodManager)) {
			super.processPaymentOk(eventOk);
			return;
		}

		PaymentMethodManager methodManager = (PaymentMethodManager) source;
		String paymentCode = methodManager.getPaymentCode();
		MedioPagoBean paymentMethod = mediosPagosService.getMedioPago(paymentCode);

		Integer paymentId = eventOk.getPaymentId();
		boolean cashFlowRecorded = methodManager.recordCashFlowImmediately();

		PagoTicket payment = ticketManager.addPayToTicket(paymentCode, amount, paymentId, true, cashFlowRecorded);

		applyGiftCardPaymentData(eventOk, payment);

		Map<String, Object> extendedData = eventOk.getExtendedData();
		if (paymentMethod != null && Boolean.TRUE.equals(paymentMethod.getTarjetaCredito()) && extendedData != null && extendedData.containsKey(BasicPaymentMethodManager.PARAM_RESPONSE_TEF)) {
			DatosRespuestaPagoTarjeta datosRespuestaPagoTarjeta = (DatosRespuestaPagoTarjeta) extendedData.get(BasicPaymentMethodManager.PARAM_RESPONSE_TEF);
			payment.setDatosRespuestaPagoTarjeta(datosRespuestaPagoTarjeta);
		}

		TenderAccepted response = new TenderAccepted();
		response.setFieldIntValue(TenderAccepted.Amount, amount);
		response.setFieldValue(TenderAccepted.TenderType, comerzziaPaymentCodeToScoTenderType(paymentCode));
		if (paymentMethod != null) {
			response.setFieldValue(TenderAccepted.Description, paymentMethod.getDesMedioPago());
		}

		ncrController.sendMessage(response);

		itemsManager.sendTotals();
	}

	private void applyGiftCardPaymentData(PaymentOkEvent eventOk, PagoTicket payment) {
		if (eventOk == null || payment == null) {
			return;
		}

		GiftCardBean giftCard = extractGiftCardFromEvent(eventOk);
		if (giftCard == null) {
			return;
		}

		try {
			Method setter = findMethod(payment.getClass(), "setGiftcard", GiftCardBean.class);
			if (setter == null) {
				setter = findMethod(payment.getClass(), "setGiftCard", GiftCardBean.class);
			}
			if (setter != null) {
				setter.invoke(payment, giftCard);
				return;
			}

			Method adder = findMethod(payment.getClass(), "addGiftcard", GiftCardBean.class);
			if (adder == null) {
				adder = findMethod(payment.getClass(), "addGiftCard", GiftCardBean.class);
			}
			if (adder != null) {
				adder.invoke(payment, giftCard);
				return;
			}

			Method listSetter = findMethod(payment.getClass(), "setGiftcards", List.class);
			if (listSetter == null) {
				listSetter = findMethod(payment.getClass(), "setGiftCards", List.class);
			}
			if (listSetter != null) {
				listSetter.invoke(payment, Collections.singletonList(giftCard));
				return;
			}

			if (log.isDebugEnabled()) {
				log.debug(String.format("applyGiftCardPaymentData() - Unable to locate gift card setter for payment class %s", payment.getClass().getName()));
			}
		}
		catch (Exception e) {
			log.error("applyGiftCardPaymentData() - Error applying gift card data to payment", e);
		}
	}

	private GiftCardBean extractGiftCardFromEvent(PaymentOkEvent eventOk) {
		Map<String, Object> extendedData = eventOk.getExtendedData();
		if (extendedData == null || extendedData.isEmpty()) {
			return null;
		}

		Object value = extendedData.get(GiftCardManager.PARAM_TARJETA);
		if (value instanceof GiftCardBean) {
			return (GiftCardBean) value;
		}
		return null;
	}

	private void registerOrClearGiftCardPayment(PaymentOkEvent eventOk) {
		if (eventOk == null) {
			return;
		}

		Map<String, Object> extendedData = eventOk.getExtendedData();
		if (extendedData == null || extendedData.isEmpty()) {
			return;
		}

		Object value = extendedData.get(GiftCardManager.PARAM_TARJETA);
		if (!(value instanceof GiftCardBean)) {
			return;
		}

		GiftCardBean giftCard = (GiftCardBean) value;
		String uid = normalizeUid(giftCard != null ? giftCard.getUidTransaccion() : null);

		if (eventOk.isCanceled()) {
			if (uid == null) {
				Integer paymentId = normalizePaymentId(eventOk.getPaymentId());
				if (paymentId != null) {
					uid = paymentIdToGiftCardUid.get(paymentId);
				}
			}
			if (uid != null) {
				removeGiftCardTracking(uid);
			}
			return;
		}

		if (uid == null) {
			return;
		}

		BigDecimal amount = eventOk.getAmount() != null ? eventOk.getAmount() : BigDecimal.ZERO;
		if (amount.compareTo(BigDecimal.ZERO) <= 0) {
			return;
		}

		Integer paymentId = normalizePaymentId(eventOk.getPaymentId());
		GiftCardPaymentContext context = new GiftCardPaymentContext(giftCard, amount, paymentId);
		pendingGiftCardPayments.put(uid, context);

		if (paymentId != null) {
			paymentIdToGiftCardUid.put(paymentId, uid);
		}
	}

	public void onTransactionVoided() {
		if (pendingGiftCardPayments.isEmpty()) {
			return;
		}

		Map<String, GiftCardPaymentContext> snapshot = new HashMap<>(pendingGiftCardPayments);
		for (Map.Entry<String, GiftCardPaymentContext> entry : snapshot.entrySet()) {
			String uid = entry.getKey();
			GiftCardPaymentContext context = entry.getValue();
			cancelGiftCardMovement(uid, context);
		}

		clearPendingGiftCardPayments();
	}

	public void onTransactionStarted() {
		clearPendingGiftCardPayments();
	}

	private void cancelGiftCardMovement(String uid, GiftCardPaymentContext context) {
		if (context == null || context.giftCard == null) {
			return;
		}

		String normalizedUid = normalizeUid(uid);
		if (normalizedUid == null) {
			normalizedUid = normalizeUid(context.giftCard.getUidTransaccion());
		}

		BigDecimal amount = context.amount != null ? context.amount : BigDecimal.ZERO;
		if (normalizedUid == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
			removeGiftCardTracking(normalizedUid);
			return;
		}

		try {
			ListaMovimientoRequestRest request = buildGiftCardMovementRequest(amount, context.giftCard, normalizedUid);
			MovimientosRest.anularMovimientosProvisionalesTarjetaRegalo(request);
		}
		catch (RestHttpException | RestException e) {
			log.error(MessageFormat.format("cancelGiftCardMovement() - Error cancelling provisional movement for card {0}: {1}", safeCardNumber(context.giftCard), e.getMessage()), e);
		}
		catch (Exception e) {
			log.error(MessageFormat.format("cancelGiftCardMovement() - Unexpected error cancelling provisional movement for card {0}: {1}", safeCardNumber(context.giftCard), e.getMessage()), e);
		}
		finally {
			removeGiftCardTracking(normalizedUid);
		}
	}

	private ListaMovimientoRequestRest buildGiftCardMovementRequest(BigDecimal amount, GiftCardBean giftCard, String uidTransaccion) throws RestException, RestHttpException {
		MovimientoRequestRest movimiento = new MovimientoRequestRest();

		if (sesion != null && sesion.getAplicacion() != null) {
			movimiento.setUidActividad(sesion.getAplicacion().getUidActividad());
		}

		movimiento.setNumeroTarjeta(giftCard.getNumTarjetaRegalo());
		movimiento.setUidTransaccion(uidTransaccion);
		movimiento.setFecha(new Date());

		ITicket ticket = ticketManager != null ? ticketManager.getTicket() : null;
		String concepto = buildGiftCardConcept(ticket);
		if (StringUtils.isNotBlank(concepto)) {
			movimiento.setConcepto(concepto);
		}
		if (ticket != null && ticket.getIdTicket() != null) {
			movimiento.setDocumento(String.valueOf(ticket.getIdTicket()));
		}

		try {
			if (variablesServices != null) {
				String apiKey = variablesServices.getVariableAsString(Variables.WEBSERVICES_APIKEY);
				if (StringUtils.isNotBlank(apiKey)) {
					movimiento.setApiKey(apiKey);
				}
			}
		}
		catch (Exception e) {
			log.warn("buildGiftCardMovementRequest() - Unable to resolve API key", e);
		}

		if (giftCard.getSaldo() != null) {
			movimiento.setSaldo(giftCard.getSaldo().doubleValue());
		}
		if (giftCard.getSaldoProvisional() != null) {
			movimiento.setSaldoProvisional(giftCard.getSaldoProvisional().doubleValue());
		}

		BigDecimal normalizedAmount = amount != null ? amount : BigDecimal.ZERO;
		movimiento.setSalida(normalizedAmount.doubleValue());
		movimiento.setEntrada(0.0);

		ListaMovimientoRequestRest request = new ListaMovimientoRequestRest();
		request.setMovimientos(Collections.singletonList(movimiento));
		return request;
	}

	private String buildGiftCardConcept(ITicket ticket) {
		String descripcion = ticket != null && ticket.getCabecera() != null ? StringUtils.trimToEmpty(ticket.getCabecera().getDesTipoDocumento()) : StringUtils.EMPTY;
		String codigo = ticket != null && ticket.getCabecera() != null ? StringUtils.trimToEmpty(ticket.getCabecera().getCodTicket()) : StringUtils.EMPTY;
		String concepto = (descripcion + " " + codigo).trim();
		return concepto;
	}

	private void removeGiftCardTracking(String uid) {
		if (StringUtils.isBlank(uid)) {
			return;
		}
		pendingGiftCardPayments.remove(uid);
		paymentIdToGiftCardUid.entrySet().removeIf(entry -> StringUtils.equals(entry.getValue(), uid));
	}

	private void clearPendingGiftCardPayments() {
		pendingGiftCardPayments.clear();
		paymentIdToGiftCardUid.clear();
	}

	private Integer normalizePaymentId(Integer paymentId) {
		if (paymentId == null) {
			return null;
		}
		return paymentId.intValue() > 0 ? paymentId : null;
	}

	private String normalizeUid(String uid) {
		return StringUtils.trimToNull(uid);
	}

	private String safeCardNumber(GiftCardBean giftCard) {
		return giftCard != null ? StringUtils.defaultString(giftCard.getNumTarjetaRegalo(), "?") : "?";
	}

        private static class GiftCardException extends Exception {

                private static final long serialVersionUID = 1L;

                GiftCardException(String message) {
                        super(message);
                }

                GiftCardException(String message, Throwable cause) {
                        super(message, cause);
                }
        }

        private static final class GiftCardPaymentContext {

                private final GiftCardBean giftCard;
                private final BigDecimal amount;
                private final Integer paymentId;

		private GiftCardPaymentContext(GiftCardBean giftCard, BigDecimal amount, Integer paymentId) {
			this.giftCard = giftCard;
			this.amount = amount;
			this.paymentId = paymentId;
		}
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

}
