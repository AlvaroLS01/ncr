package com.comerzzia.dinosol.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comarch.clm.partner.dto.ExtendedBalanceInquiryResponse;
import com.comarch.clm.partner.dto.IssuanceResponse;
import com.comarch.clm.partner.dto.TenderRedemptionGroupDataResponse;
import com.comarch.clm.partner.exception.BpConfiguracionException;
import com.comarch.clm.partner.exception.BpRespuestaException;
import com.comarch.clm.partner.exception.BpSoapException;
import com.comarch.clm.partner.util.CLMServiceErrorString;
import com.comarch.clm.partner.util.CLMServiceFomatString;
import com.comerzzia.api.virtualmoney.client.model.AccountDTO;
import com.comerzzia.dinosol.pos.devices.fidelizacion.DinoFidelizacion;
import com.comerzzia.dinosol.pos.ncr.actions.sale.seleccionpromos.SeleccionPromosManager;
import com.comerzzia.dinosol.pos.ncr.services.parking.DatosParkingDto;
import com.comerzzia.dinosol.pos.ncr.services.parking.ParkingService;
import com.comerzzia.dinosol.pos.ncr.services.ticket.DinoScoTicketManager;
import com.comerzzia.dinosol.pos.services.core.sesion.DinoSesionPromociones;
import com.comerzzia.dinosol.pos.services.cupones.ComparatorCuponImporte;
import com.comerzzia.dinosol.pos.services.cupones.CustomerCouponDTO;
import com.comerzzia.dinosol.pos.services.payments.methods.prefijos.PrefijosTarjetasService;
import com.comerzzia.dinosol.pos.services.payments.methods.types.DinoPaymentsManagerImpl;
import com.comerzzia.dinosol.pos.services.payments.methods.types.bp.BPManager;
import com.comerzzia.dinosol.pos.services.payments.methods.types.virtualmoney.DescuentosEmpleadoManager;
import com.comerzzia.dinosol.pos.services.payments.methods.types.virtualmoney.VirtualMoneyManager;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.DinoCabeceraTicket;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.TarjetaIdentificacionDto;
import com.comerzzia.dinosol.pos.util.text.DinoTextUtils;
import com.comerzzia.pos.dispositivo.tarjeta.conexflow.TefConexflowManager;
import com.comerzzia.pos.ncr.actions.sale.PayManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.messages.LoyaltyCard;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderAccepted;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.ncr.messages.TenderExceptionReply;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.persistence.fidelizacion.FidelizacionBean;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.payments.PaymentException;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.events.PaymentErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentOkEvent;
import com.comerzzia.pos.services.payments.events.PaymentsErrorEvent;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.BasicPaymentMethodManager;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.pagos.PagoTicket;
import com.comerzzia.pos.services.ticket.pagos.tarjeta.DatosRespuestaPagoTarjeta;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.i18n.I18N;
import com.comerzzia.pos.util.text.TextUtils;

@Lazy(false)
@Service
@Primary
@SuppressWarnings("unchecked")
public class DinoPayManager extends PayManager {

	public static final Logger log = Logger.getLogger(DinoPayManager.class);

	public static final String MEDIO_PAGO_OTRAS_TARJETAS = "Otras Tarjetas";
	public static final String CODMEDPAG_EMPLEADO = "0300";
	public static final String CODMEDPAG_VIP = "0302";
	public static final String CODMEDPAG_BP = "0101";

	@Autowired
	protected PrefijosTarjetasService prefijosTarjetasService;
	
	@Autowired
	private ParkingService parkingService;
	
	@Autowired
	private SeleccionPromosManager seleccionPromosManager;
	
	private boolean activoProcesoPagoTarjetasSaldo;
	private List<String> tarjetasFidelizadoSaldo;

	public DinoPayManager() {
		TextUtils.setCustomInstance(new DinoTextUtils());
	}

	@Override
	public void init() {
		super.init();

		ncrController.registerActionManager(DataNeededReply.class, this);
		ncrController.registerActionManager(TenderExceptionReply.class, this);
	}
	
	@Override
	public void processMessage(BasicNCRMessage message) {
		super.processMessage(message);
		
		if (message instanceof DataNeededReply) {
			readDataNeededReply((DataNeededReply) message);
		}
		else if(message instanceof TenderExceptionReply) {
			readTenderExceptionReply((TenderExceptionReply) message);
		}
	}

	@Override
	protected void trayPay(Tender message) {
		String numeroTarjeta = message.getFieldValue(Tender.UPC);

		if (StringUtils.isEmpty(numeroTarjeta)) {
			numeroTarjeta = ""; // evitar NPE
		}

		PaymentsManager paymentsManager = ticketManager.getPaymentsManager();

		String paymentMethodCode = null;

		if (StringUtils.equals(message.getFieldValue(Tender.TenderType), MEDIO_PAGO_OTRAS_TARJETAS)) {
			if(numeroTarjeta.length() == 12) {
				numeroTarjeta = "0" + numeroTarjeta;
			}
			
			paymentMethodCode = prefijosTarjetasService.getMedioPagoPrefijo(numeroTarjeta);
		}
		else {
			paymentMethodCode = scoTenderTypeToComerzziaPaymentCode(message.getFieldValue(Tender.TenderType));
		}

		if (StringUtils.isBlank(paymentMethodCode)) {
			enviarTenderException(I18N.getTexto("Medio de pago no encontrado"));
			return;
		}

		BigDecimal importe = new BigDecimal(message.getFieldValue(Tender.Amount)).divide(new BigDecimal(100));
		PaymentMethodManager paymentManager = paymentsManager.getPaymentsMehtodManagerAvailables().get(paymentMethodCode);

		if (paymentManager instanceof BPManager) {
			if (numeroTarjeta.length() == 12) {
				numeroTarjeta = "0" + numeroTarjeta;
			}

			BigDecimal payAmount = calcularImportePagoConBp(((BPManager) paymentManager), numeroTarjeta);

			if (payAmount == null) {
				return;
			}
			importe = payAmount;

			message.setFieldIntValue(Tender.Amount, payAmount);
		}
		else if (paymentManager instanceof VirtualMoneyManager) {
			paymentManager.addParameter(VirtualMoneyManager.PARAM_NUMERO_TARJETA, numeroTarjeta);
			importe = calcularImportePagoVirtualMoney(message, numeroTarjeta, importe, paymentManager);
		}

		if (ticketManager.isTrainingMode()) {
			// Automatic pay accepted message for training mode
			TenderAccepted response = new TenderAccepted();
			response.setFieldValue(TenderAccepted.Amount, message.getFieldValue(Tender.Amount));
			response.setFieldValue(TenderAccepted.TenderType, message.getFieldValue(Tender.TenderType));
			response.setFieldValue(TenderAccepted.Description, message.getFieldValue(Tender.TenderType));

			return;
		}

		try {
			if (StringUtils.equalsIgnoreCase("Credit", message.getFieldValue(Tender.TenderType))) {
				// this message force the SCO to wait TenderAccepted or TenderException message
				TenderException response = new TenderException();
				response.setFieldValue(TenderException.TenderType, message.getFieldValue(Tender.TenderType));
				response.setFieldValue(TenderException.ExceptionType, "0");
				response.setFieldValue(TenderException.ExceptionId, "1");
				ncrController.sendMessage(response);
			}

			paymentsManager.pay(paymentMethodCode, importe);
		}
		catch (Exception e) {
			if (e instanceof PaymentException) {
				PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, ((PaymentException) e).getPaymentId(), e, ((PaymentException) e).getErrorCode(), ((PaymentException) e).getMessage());
				PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
				paymentsManager.getEventsHandler().paymentsError(event);

			}
			else {
				PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, -1, e, null, null);
				PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
				paymentsManager.getEventsHandler().paymentsError(event);
			}
		}
	}

	protected BigDecimal calcularImportePagoConBp(BPManager bpManager, String numeroTarjeta) {
		log.debug("calcularImportePagoConBp() - Numero de tarjeta: " + numeroTarjeta);
		ncrController.sendWaitState(I18N.getTexto("Validando tarjeta..."));

		try {
			ExtendedBalanceInquiryResponse saldoResponse;
			try {
				saldoResponse = bpManager.getSaldo(numeroTarjeta);
			}
			catch (BpSoapException e) {
				log.error("calcularImportePagoConBp() - " + e.getMessage(), e);
				enviarTenderException(I18N.getTexto("Error conectando con el servicio de BP"));
				// throw new RuntimeException("Error conectando con el servicio de BP");
				return null;
			}
			catch (BpRespuestaException e) {
				// throw new RuntimeException(e.getMessage(), e);
				log.error("calcularImportePagoConBp() - " + e.getMessage(), e);
				enviarTenderException(e.getMessage());
				return null;
			}
			catch (BpConfiguracionException e) {
				// throw new RuntimeException("Medio de pago de BP no está correctamente configurado");
				log.error("calcularImportePagoConBp() - " + e.getMessage(), e);
				enviarTenderException(I18N.getTexto("Medio de pago de BP no está correctamente configurado"));
				return null;
			}

			/*
			 * Si la tarjeta se ha validado, añadiremos el fidelizado de la tarjeta BP al ticket y realizaremos su envío
			 * al SCO
			 */
			fidelizadoTarjetaBP(numeroTarjeta);
			ncrController.sendWaitState(I18N.getTexto("Fidelizado añadido, validando importes..."));

			BigDecimal saldo = BigDecimal.ZERO;
			BigDecimal saldoDisponible = null;
			BigDecimal importeACobrar = BigDecimal.ZERO;

			if (saldoResponse != null && "0".equals(saldoResponse.getErrorCode())) {
				if (saldoResponse.getTenderRedemptionGroupDataList() != null) {
					for (TenderRedemptionGroupDataResponse virtual : saldoResponse.getTenderRedemptionGroupDataList()) {
						/* Filtramos para solo coger uno de los dos saldos que nos trae. */
						if (BPManager.VIRTUAL_MONEY_CODE.equals(virtual.getTenderRedemptionGroupCode())) {
							saldo = new BigDecimal(virtual.getVirtualMoneyBalance());
							importeACobrar = saldo;
						}

						if (!BigDecimalUtil.isIgualACero(saldo)) {
							BigDecimal total = ticketManager.getTicket().getTotales().getTotalAPagar();
							BigDecimal tramo = bpManager.getTramo();

							if (total.compareTo(tramo) < 0) {
								log.error("calcularImportePagoConBp() - El total de la compra no supera el tramo mínimo para pagar con DINOBP");
								enviarTenderException(I18N.getTexto("El total de la compra no supera el tramo mínimo para pagar con DINOBP"));
								return null;
								// throw new RuntimeException(I18N.getTexto("El total de la compra no supera el tramo
								// mínimo para pagar con DINOBP"));
							}

							BigDecimal eurosTramo = bpManager.getEurosTramo();
							boolean condicionQuemadoPositiva = bpManager.isCondicionQuemadoPositiva();

							saldoDisponible = BigDecimal.ZERO;
							if (tramo == null || eurosTramo == null || BigDecimalUtil.isMenorACero(tramo) || BigDecimalUtil.isMenorACero(eurosTramo)) {
								log.error("calcularImportePagoConBp() - No está configurado el pago con la tarjeta BP.");
								enviarTenderException(I18N.getTexto("No está configurado el pago con la tarjeta BP."));
								return null;
								// throw new RuntimeException(I18N.getTexto("No está configurado el pago con la tarjeta
								// BP."));
							}
							else {
								if (condicionQuemadoPositiva && BigDecimalUtil.isMayorACero(total)) {
									saldoDisponible = total.divide(tramo, 0, RoundingMode.FLOOR);
								}
								else {
									saldoDisponible = total.divide(tramo, 0, RoundingMode.CEILING);
								}
								saldoDisponible = BigDecimalUtil.redondear(saldoDisponible.multiply(eurosTramo));
							}

							if (BigDecimalUtil.isMayor(saldoDisponible, saldo)) {
								saldoDisponible = saldo;
							}

							importeACobrar = saldoDisponible;
						}
					}
				}
			}

			if (importeACobrar.setScale(0, RoundingMode.DOWN).compareTo(BigDecimal.ZERO) <= 0) {
				log.error("calcularImportePagoConBp() - La tarjeta no tiene saldo suficiente");
				enviarTenderException(I18N.getTexto("La tarjeta no tiene saldo suficiente"));
				return null;
				// throw new RuntimeException(I18N.getTexto("La tarjeta no tiene saldo suficiente"));
			}

			if (BigDecimalUtil.isMayor(importeACobrar, saldoDisponible)) {
				importeACobrar = saldoDisponible;
			}

			bpManager.addParameter(BPManager.PARAM_NUMERO_TARJETA_BP, numeroTarjeta);
			bpManager.addParameter(BPManager.PARAM_TARJETA_BP, saldo);

			// Recalculamos promociones para la promoción de DinoBP
			aplicarPromocionesBp();

			ncrController.sendFinishWaitState();

			return importeACobrar;
		}
		catch (Exception e) {
			ncrController.sendFinishWaitState();

			TenderException tenderException = new TenderException();
			tenderException.setFieldValue(TenderException.ExceptionId, "0");
			tenderException.setFieldValue(TenderException.ExceptionType, "0");
			tenderException.setFieldValue(TenderException.TenderType, comerzziaPaymentCodeToScoTenderType(bpManager.getPaymentCode()));
			tenderException.setFieldValue(TenderException.Message, e.getMessage());

			ncrController.sendMessage(tenderException);

			return null;
		}
	}

	protected BigDecimal payWithVirtualMoney(VirtualMoneyManager manager, String numeroTarjeta) {
		ncrController.sendWaitState(I18N.getTexto("Validando tarjeta..."));

		try {
			AccountDTO account = manager.consultarSaldo(numeroTarjeta);

			BigDecimal importeMaximoVirtualMoney = account.getPrincipaltBalance().getBalance();

			BigDecimal importeACobrar = BigDecimalUtil.isMenor(importeMaximoVirtualMoney, ticketManager.getTicket().getTotales().getPendiente()) ? importeMaximoVirtualMoney
			        : ticketManager.getTicket().getTotales().getPendiente();

			ncrController.sendFinishWaitState();

			return importeACobrar;
		}
		catch (Exception e) {
			ncrController.sendFinishWaitState();

			TenderException tenderException = new TenderException();
			tenderException.setFieldValue(TenderException.ExceptionId, "0");
			tenderException.setFieldValue(TenderException.ExceptionType, "0");
			tenderException.setFieldValue(TenderException.TenderType, "Gift Card");
			tenderException.setFieldValue(TenderException.Message, e.getMessage());

			ncrController.sendMessage(tenderException);

			return null;
		}
	}

	public void aplicarPromocionesBp() {
		((DinoSesionPromociones) ticketManager.getSesion().getSesionPromociones()).aplicarPromocionesBp((TicketVentaAbono) ticketManager.getTicket());
		ticketManager.getTicket().getTotales().recalcular();
	}

	private void fidelizadoTarjetaBP(String codigoBarras) {
		LoyaltyCard message = new LoyaltyCard();
		DinoCabeceraTicket cabecera = (DinoCabeceraTicket) ticketManager.getTicket().getCabecera();

		if (!cabecera.containsTarjeta(codigoBarras)) {
			TarjetaIdentificacionDto tarjetaIdentificacion = new TarjetaIdentificacionDto();
			tarjetaIdentificacion.setTipoTarjeta("BP");
			tarjetaIdentificacion.setNumeroTarjeta(codigoBarras);
			cabecera.addTarjetaIdentificacion(tarjetaIdentificacion);

			message.setFieldValue(LoyaltyCard.Status, LoyaltyCard.STATUS_ACCEPTED);
		}
		else {
			message.setFieldValue(LoyaltyCard.Status, LoyaltyCard.STATUS_ALREADY_SCANNED);
		}

		ncrController.sendMessage(message);
	}

	@Override
	protected void processPaymentOk(PaymentOkEvent eventOk) {
		log.debug("processPaymentOk() - Pay accepted");

		BigDecimal amount = eventOk.getAmount();
		String paymentCode = ((PaymentMethodManager) eventOk.getSource()).getPaymentCode();
		Integer paymentId = eventOk.getPaymentId();

		MedioPagoBean paymentMethod = mediosPagosService.getMedioPago(paymentCode);

		boolean cashFlowRecorded = ((PaymentMethodManager) eventOk.getSource()).recordCashFlowImmediately();

		PagoTicket payment = ticketManager.addPayToTicket(paymentCode, amount, paymentId, true, cashFlowRecorded);

		if (paymentMethod.getTarjetaCredito() != null && paymentMethod.getTarjetaCredito()) {
			if (eventOk.getExtendedData().containsKey(BasicPaymentMethodManager.PARAM_RESPONSE_TEF)) {
				DatosRespuestaPagoTarjeta datosRespuestaPagoTarjeta = (DatosRespuestaPagoTarjeta) eventOk.getExtendedData().get(BasicPaymentMethodManager.PARAM_RESPONSE_TEF);
				payment.setDatosRespuestaPagoTarjeta(datosRespuestaPagoTarjeta);
			}
		}

		if (!(eventOk.getSource() instanceof TefConexflowManager)) {
			payment.setExtendedData(eventOk.getExtendedData());
		}

		if (eventOk.getSource() instanceof BPManager) {
			TarjetaIdentificacionDto tarjetaIdentificacion = ((DinoCabeceraTicket) ticketManager.getTicket().getCabecera()).buscarTarjeta("BP");
			if (tarjetaIdentificacion == null) {
				tarjetaIdentificacion = new TarjetaIdentificacionDto();
				tarjetaIdentificacion.setTipoTarjeta("BP");
				tarjetaIdentificacion.setNumeroTarjeta((String) eventOk.getExtendedData().get("numeroTarjeta"));
				((DinoCabeceraTicket) ticketManager.getTicket().getCabecera()).addTarjetaIdentificacion(tarjetaIdentificacion);
			}
			tarjetaIdentificacion.setLineasImpresion((List<String>) eventOk.getExtendedData().get("formatedText"));
			tarjetaIdentificacion.setRespuesta(eventOk.getExtendedData());
		}
		
		if(eventOk.getSource() instanceof DescuentosEmpleadoManager) {
			BigDecimal amountPayment = eventOk.getAmount();
			if(!eventOk.isCanceled()) {
				amountPayment = amountPayment.negate();
			}
			((DinoPaymentsManagerImpl) ticketManager.getPaymentsManager()).addPending(amountPayment);
		}

		// Pay accepted message
		TenderAccepted response = new TenderAccepted();
		response.setFieldIntValue(TenderAccepted.Amount, amount);
		response.setFieldValue(TenderAccepted.TenderType, comerzziaPaymentCodeToScoTenderType(paymentCode));
		response.setFieldValue(TenderAccepted.Description, paymentMethod.getDesMedioPago());

		ncrController.sendMessage(response);

		// totals update
		itemsManager.sendTotals();
	}

	private void enviarTenderException(String mensaje) {
		TenderException tenderException = new TenderException();
		tenderException.setFieldValue(TenderException.ExceptionId, "0");
		tenderException.setFieldValue(TenderException.ExceptionType, "0");
		tenderException.setFieldValue(TenderException.TenderType, mensaje);
		tenderException.setFieldValue(TenderException.Message, mensaje);

		ncrController.sendMessage(tenderException);
	}

	@Override
	protected void activateTenderMode() {
		activoProcesoPagoTarjetasSaldo = false;
		
		boolean esNecesarioPreguntarParking = parkingService.isParkingActivo() && !parkingService.isTicketTieneArticuloParking(ticketManager.getTicket());
		
		if(!esNecesarioPreguntarParking) {
			enterTenderScreen();
		}
		else {
			ticketManager.setTenderMode(true);
			DataNeeded messageOpcionesPromociones = seleccionarOpcionesPromociones();
			if(messageOpcionesPromociones != null) {
				ncrController.sendMessage(messageOpcionesPromociones);
			}
			else  {
				mostrarPantallaPeticionCodigoParking();
			}
		}
	}

	private void mostrarPantallaPeticionCodigoParking() {
		DataNeeded msg = parkingService.generarMensajePantallaLecturaParking();
		
		ncrController.sendMessage(msg);
	}

	private void enterTenderScreen() {
		aplicarCupones();

		ticketManager.setTenderMode(true);
		initializePaymentsManager();

		addHeaderDiscountsPayments();

		ticketManager.getPaymentsManager().setTicketData(ticketManager.getTicket(), null);
		
		aplicarTarjetasDescuentoAutomatico();

		itemsManager.sendTotals();
		
		empezarProcesoPagoTarjetasSaldo();
	}

	private void aplicarTarjetasDescuentoAutomatico() {
		List<String> tarjetasDisponibles = getTarjetasDescuentoAutomatico();
		
		for(String tarjeta : tarjetasDisponibles) {
			pagarAutomaticamenteConTarjeta(tarjeta);
			break;
		}
	}

	private List<String> getTarjetasDescuentoAutomatico() {
		return getTarjetasFiltradas((Predicate<String>) this::esTarjetaPagoAutomatico);
	}

	private List<String> getTarjetasFiltradas(Predicate<String> filtro) {
		Set<String> tarjetas = new LinkedHashSet<>();

		// Añade las tarjetas fidelizadas
		tarjetas.addAll(
			Optional.ofNullable(ticketManager.getTicket().getCabecera().getDatosFidelizado())
				.map(FidelizacionBean::getAdicionales)
				.map(m -> (List<String>) m.get(DinoFidelizacion.FIDELIZADO_TARJETAS))
				.orElse(Collections.emptyList())
		);

		// Añade las tarjetas de identificación
		tarjetas.addAll(
			Optional.ofNullable(((DinoCabeceraTicket) ticketManager.getTicket().getCabecera()).getTarjetasIdentificacion())
				.orElse(Collections.emptyList()).stream()
				.map(TarjetaIdentificacionDto::getNumeroTarjeta)
				.collect(Collectors.toList())
		);

		// Aplica el filtro al final
		return tarjetas.stream().filter(filtro).collect(Collectors.toList());
	}

	protected boolean esTarjetaPagoAutomatico(String tarjeta) {
		String codMedPag = prefijosTarjetasService.getMedioPagoPrefijo(tarjeta);
		return CODMEDPAG_EMPLEADO.equals(codMedPag) || CODMEDPAG_VIP.equals(codMedPag);
	}

	private void aplicarCupones() {
		List<CustomerCouponDTO> cuponesLeidos = new ArrayList<CustomerCouponDTO>(((DinoScoTicketManager) ticketManager).getCuponesLeidos());

		eliminarCuponesDuplicados(cuponesLeidos);

		boolean hayCuponesLeidos = cuponesLeidos != null && !cuponesLeidos.isEmpty();
		if (hayCuponesLeidos) {
			try {
				Collections.sort(cuponesLeidos, new ComparatorCuponImporte());
			}
			catch (Exception e) {
				log.error("aplicarCupones() - Ha habido un error al ordenar la lista de cupones: " + e.getMessage(), e);
			}

			for (CustomerCouponDTO cupon : cuponesLeidos) {
				if (cupon.isCuponNuevo()) {
					try {
						boolean resultado = ((DinoScoTicketManager) ticketManager).aplicarCupon(cupon);
						log.debug("aplicarCupones() - ¿Cupón " + cupon.getCouponCode() + " aplicado? " + resultado);

						if (!resultado) {
						}
					}
					catch (Exception e) {
						log.error("aplicarCupones() - Error al aplicar el cupón " + cupon.getCouponCode() + ": " + e.getMessage());
					}
				}
			}
		}

		itemsManager.updateItems();
	}

	private void eliminarCuponesDuplicados(List<CustomerCouponDTO> cuponesLeidos) {
		Set<CustomerCouponDTO> set = new HashSet<>(cuponesLeidos);
		cuponesLeidos.clear();
		cuponesLeidos.addAll(set);
	}

	@Override
	protected void disableTenderMode() {
		super.disableTenderMode();

		ticketManager.recalculateTicket();

		itemsManager.updateItems();

		itemsManager.sendTotals();
		
		activoProcesoPagoTarjetasSaldo = false;
	}

	private BigDecimal calcularImportePagoVirtualMoney(Tender message, String numeroTarjeta, BigDecimal importe, PaymentMethodManager paymentManager) {
		log.debug("calcularImportePagoVirtualMoney() - Calculando importe virtual money");
		log.debug("calcularImportePagoVirtualMoney() - Tarjeta: " + numeroTarjeta);
		log.debug("calcularImportePagoVirtualMoney() - Importe: " + importe);

		if (paymentManager instanceof DescuentosEmpleadoManager) {
			log.debug("calcularImportePagoVirtualMoney() - Se trata de un descuento de empleado");
			return BigDecimal.ONE;
		}

		try {
			AccountDTO respuesta = ((VirtualMoneyManager) paymentManager).consultarSaldo(numeroTarjeta);
			BigDecimal saldo = respuesta.getPrincipaltBalance().getBalance();
			if (BigDecimalUtil.isMayor(importe, saldo)) {
				message.setFieldIntValue(Tender.Amount, saldo);
				importe = saldo;
			}
		}
		catch (Exception e) {
			log.error("calcularImportePagoVirtualMoney() - Error al consultar el saldo: " + e.getMessage(), e);
		}
		return importe;
	}

	protected void finishSale() {
		log.debug("finishSale()");

		acumularPuntosBP();

		super.finishSale();
	}

	private TarjetaIdentificacionDto buscarTarjetaIdentificacion(String tipo) {
		return ((DinoCabeceraTicket) this.ticketManager.getTicket().getCabecera()).buscarTarjeta(tipo);
	}

	private void acumularPuntosBP() {
		log.debug("acumularPuntosBP()");
		TarjetaIdentificacionDto tarjetaBp = buscarTarjetaIdentificacion("BP");
		
		if(tarjetaBp == null) {
			return;
		}
		
		BPManager bpManager = ((DinoScoTicketManager) this.ticketManager).buscarBpManager();
		try {
			BigDecimal puntosConcedidos = BigDecimal.ZERO;
			if ((tarjetaBp.getAdicionales() != null) && (tarjetaBp.getAdicionales().containsKey("puntosConcedidos"))) {
				puntosConcedidos = (BigDecimal) tarjetaBp.getAdicionales().get("puntosConcedidos");
			}
			
			log.debug("acumularPuntosBP() - Haciendo petición a BP con parámetros:");
			log.debug("acumularPuntosBP() - Número de tarjeta: " + tarjetaBp.getNumeroTarjeta());
			log.debug("acumularPuntosBP() - Puntos concedidos: " + puntosConcedidos);
			IssuanceResponse respuesta = bpManager.realizarMovimiento(null, tarjetaBp.getNumeroTarjeta(), puntosConcedidos);
			
			if(respuesta == null) {
				log.error("acumularPuntosBP() - Ha habido algún error inesperado al realizar la petición. Se procesarán los puntos de forma offline");

				tarjetaBp.setProcesamientoOffline(true);
				tarjetaBp.putAdicional("puntosAcumulados", Boolean.valueOf(false));
				tarjetaBp.putAdicional("urlBp", bpManager.getUrl());
				
				return;
			}

			if (!"0".equals(respuesta.getErrorCode())) {
				throw new BpRespuestaException("Respuesta de BP al sumar/quemar saldo: " + respuesta.getErrorCode() + "/" + CLMServiceErrorString.getErrorString(respuesta.getErrorCode()));
			}

			ExtendedBalanceInquiryResponse saldo = bpManager.getSaldo(tarjetaBp.getNumeroTarjeta());

			for (TenderRedemptionGroupDataResponse virtual : saldo.getTenderRedemptionGroupDataList()) {
				if ("TRG_€HD".equals(virtual.getTenderRedemptionGroupCode())) {
					tarjetaBp.putAdicional("puntosHiperDino", Double.valueOf(virtual.getVirtualMoneyBalance()));
				}
				else {
					tarjetaBp.putAdicional("puntosBp", Double.valueOf(virtual.getVirtualMoneyBalance()));
				}
			}

			List<String> formatedString = CLMServiceFomatString.getFormatedString(respuesta.getText());
			tarjetaBp.setLineasImpresion(formatedString);
			String textoPromocion = "";
			for (String texto : formatedString) {
				if (texto.length() < 40) {
					texto = StringUtils.rightPad(texto, 40);
				}
				textoPromocion = textoPromocion + texto;
			}

			tarjetaBp.setRespuesta(respuesta);
			tarjetaBp.putAdicional("puntosAcumulados", Boolean.valueOf(true));

			List<PromocionTicket> promociones = ticketManager.getTicket().getPromociones();
			if(promociones != null && !promociones.isEmpty()) {
				for (PromocionTicket promo : (List<PromocionTicket>) promociones) {
					if (promo.getIdTipoPromocion().equals(1003L)) {
						promo.setTextoPromocion(textoPromocion);
					}
				}
			}
		}
		catch (Exception e) {
			log.error("acumularPuntosBP() - " + e.getMessage(), e);

			tarjetaBp.setProcesamientoOffline(true);
			tarjetaBp.putAdicional("puntosAcumulados", Boolean.valueOf(false));
			tarjetaBp.putAdicional("urlBp", bpManager.getUrl());
		}
	}

	private void readDataNeededReply(DataNeededReply message) {
		String typeMensaje = message.getFieldValue(DataNeededReply.Type);
		String idMensaje = message.getFieldValue(DataNeededReply.Id);
		String data1 = message.getFieldValue(DataNeededReply.Data1);
		
		if(typeMensaje.equals("3") && idMensaje.equals("1")) {
			String data = data1;
			if(!"Continuar".equals(data)) {
				leerCodigoParking(message);
			}
			else {
				mandarMensajeCierreDataNeeded();
			}
		}
		else if(typeMensaje.equals("1") && idMensaje.equals("2")) {
			mandarMensajeCierreDataNeeded();
		}
		else if(typeMensaje.equals("0") && idMensaje.equals("0") && ticketManager.isTenderMode() && !activoProcesoPagoTarjetasSaldo) {
			enterTenderScreen();
		}
		else if(typeMensaje.equals("0") && idMensaje.equals("0") && ticketManager.isTenderMode() && activoProcesoPagoTarjetasSaldo && !tarjetasFidelizadoSaldo.isEmpty()) {
			solicitarPagoConTarjetaSaldo();
		}
		else if(typeMensaje.equals("4") && idMensaje.equals("1")) {
			if(StringUtils.isNotBlank(data1) && data1.startsWith("Opt")) {
				procesarRespuestaSeleccionPromociones(ticketManager, data1);
			}
			else {
				procesarRespuestaPagoTarjetaSaldo(data1);
			}
		}
	}

	private void mandarMensajeCierreDataNeeded() {
		DataNeeded dataNeeded = new DataNeeded();
		dataNeeded.setFieldValue(DataNeeded.Type, "0");
		dataNeeded.setFieldValue(DataNeeded.Id, "0");
		ncrController.sendMessage(dataNeeded);
	}

	private void leerCodigoParking(DataNeededReply message) {
		String codigoParking = message.getFieldValue(DataNeededReply.Data1);
		
		try {
			DatosParkingDto datosParking = parkingService.obtenerDatosParking(codigoParking);
			LineaTicket lineaTicket = ticketManager.createAndInsertTicketLine(datosParking.getCodartParking(), null, null, null, new BigDecimal(datosParking.getMinutosDiferencia()), null);
			itemsManager.newItemAndUpdateAllItems(lineaTicket);
		}
		catch (Exception e) {
			log.error("leerCodigoParking() - Error al leer el código del parking: " + e.getMessage(), e);
			
			DataNeeded dataNeeded = parkingService.generarMensajeErrorLecturaParking(e);
			ncrController.sendMessage(dataNeeded);
		}
	}

	private void empezarProcesoPagoTarjetasSaldo() {
		activoProcesoPagoTarjetasSaldo = true;
		buscarTarjetasSaldo();
		solicitarPagoConTarjetaSaldo();
	}

	private void buscarTarjetasSaldo() {
		tarjetasFidelizadoSaldo = getTarjetasFiltradas((Predicate<String>) t -> !esTarjetaPagoAutomatico(t));
	}

	private void pagarAutomaticamenteConTarjeta(String tarjeta) {
		Tender tenderAux = new Tender();
		tenderAux.setFieldValue(Tender.UPC, tarjeta);
		tenderAux.setFieldValue(Tender.TenderType, MEDIO_PAGO_OTRAS_TARJETAS);
		tenderAux.setFieldValue(Tender.Amount, "100");
		trayPay(tenderAux);
	}

	private void solicitarPagoConTarjetaSaldo() {
		if (tarjetasFidelizadoSaldo.isEmpty()) {
			return;
		}

		String tarjeta = tarjetasFidelizadoSaldo.get(0);
		String codMedPag = prefijosTarjetasService.getMedioPagoPrefijo(tarjeta);
		MedioPagoBean medioPago = mediosPagosService.getMedioPago(codMedPag);

		boolean vigenciaTarjetaSaldo = !CODMEDPAG_BP.equals(codMedPag) && !esTarjetaVigente(tarjeta);
		boolean vigenciaBP = CODMEDPAG_BP.equals(codMedPag) && BigDecimalUtil.isIgualACero(((DinoScoTicketManager) ticketManager).calcularImportePagoTarjetaBP(tarjeta));

		if ((vigenciaTarjetaSaldo || vigenciaBP) || hayPagosMedioPago(codMedPag)) {
			tarjetasFidelizadoSaldo.remove(0);
			solicitarPagoConTarjetaSaldo();
			return;
		}

		DataNeeded dataNeeded = new DataNeeded();
		dataNeeded.setFieldValue(DataNeeded.Type, "4");
		dataNeeded.setFieldValue(DataNeeded.Id, "1");
		dataNeeded.setFieldValue(DataNeeded.Mode, "0");
		dataNeeded.setFieldValue(DataNeeded.TopCaption1, I18N.getTexto(medioPago.getDesMedioPago()));
		dataNeeded.setFieldValue(DataNeeded.SummaryInstruction1, I18N.getTexto("¿Desea usar su tarjeta {0} ?", tarjeta));
		dataNeeded.setFieldValue(DataNeeded.EnableScanner, "0");
		dataNeeded.setFieldValue(DataNeeded.ButtonData1, "TxSi");
		dataNeeded.setFieldValue(DataNeeded.ButtonText1, I18N.getTexto("SI"));
		dataNeeded.setFieldValue(DataNeeded.ButtonData2, "TxNo");
		dataNeeded.setFieldValue(DataNeeded.ButtonText2, I18N.getTexto("NO"));
		dataNeeded.setFieldValue(DataNeeded.HideGoBack, "1");

		ncrController.sendMessage(dataNeeded);
	}

	private boolean hayPagosMedioPago(String codMedPag) {
		List<PagoTicket> pagos = ticketManager.getTicket().getPagos();
		for (PagoTicket pago : pagos) {
			if (pago.getCodMedioPago().equals(codMedPag)) {
				return true;
			}
		}
		return false;
	}

	private void procesarRespuestaPagoTarjetaSaldo(String respuesta) {
		if (respuesta.equals("TxNo")) {
			tarjetasFidelizadoSaldo.remove(0);
			mandarMensajeCierreDataNeeded();
			itemsManager.sendTotals();
			solicitarPagoConTarjetaSaldo();
		}
		else {
			String tarjeta = tarjetasFidelizadoSaldo.get(0);
			String codMedPag = prefijosTarjetasService.getMedioPagoPrefijo(tarjeta);
			if (codMedPag.equals(CODMEDPAG_BP)) {
				mandarMensajeConfirmacionCajeroPagoTarjetaBP(tarjeta);
			}
			else {
				mandarMensajeConfirmacionCajeroPagoTarjetaSaldo();
			}

			itemsManager.sendTotals();
		}
	}

	private void mandarMensajeConfirmacionCajeroPagoTarjetaSaldo() {
		TenderException tenderException = new TenderException();
		tenderException.setFieldValue(TenderException.TenderType, "Credit");
		tenderException.setFieldValue(TenderException.ExceptionType, "2");
		tenderException.setFieldValue(TenderException.ExceptionId, "4");
		tenderException.setFieldValue(TenderException.Data1, I18N.getTexto("Aceptar"));
		tenderException.setFieldValue(TenderException.TenderType, I18N.getTexto("Cancelar"));
		
		ncrController.sendMessage(tenderException);
	}
	
	private void mandarMensajeConfirmacionCajeroPagoTarjetaBP(String numTarjeta) {
		BigDecimal importe = ((DinoScoTicketManager) ticketManager).calcularImportePagoTarjetaBP(numTarjeta);
		
		((DinoScoTicketManager) ticketManager).payBPCard(numTarjeta, importe);
		
		itemsManager.sendTotals();
		
		solicitarPagoConTarjetaSaldo();
	}

	private void readTenderExceptionReply(TenderExceptionReply message) {
		String typeMessage = message.getFieldValue(TenderExceptionReply.ExceptionType);
		String idMessage = message.getFieldValue(TenderExceptionReply.ExceptionId);
		
		if("2".equals(typeMessage) && "4".equals(idMessage)) {
			String numeroTarjeta = tarjetasFidelizadoSaldo.get(0);
			tarjetasFidelizadoSaldo.clear();
			pagarAutomaticamenteConTarjetaSaldo(numeroTarjeta);
		}
	}

	private void pagarAutomaticamenteConTarjetaSaldo(String numeroTarjeta) {
		Tender tenderAux = new Tender();
		tenderAux.setFieldValue(Tender.UPC, numeroTarjeta);
		tenderAux.setFieldValue(Tender.TenderType, MEDIO_PAGO_OTRAS_TARJETAS);
		tenderAux.setFieldIntValue(Tender.Amount, calcularImportePagoTarjetaVirtualMoney(numeroTarjeta));
		trayPay(tenderAux);
		
		solicitarPagoConTarjetaSaldo();
	}

	private BigDecimal calcularImportePagoTarjetaVirtualMoney(String numeroTarjeta) {
		try {
			AccountDTO respuesta = consultarEstadoTarjetaVirtualMoney(numeroTarjeta);
			
			BigDecimal saldo = respuesta.getPrincipaltBalance().getBalance();
			
			BigDecimal pendiente = ticketManager.getTicket().getTotales().getPendiente();
			if (saldo != null && BigDecimalUtil.isMayor(saldo, pendiente)) {
				saldo = pendiente;
			}
			
			return saldo;
		}
		catch (Exception e) {
			log.error("calcularImportePagoTarjetaVirtualMoney() - Ha habido un error al calcular el importe de Virtual Money: " + e.getMessage(), e);
			return BigDecimal.ONE;
		}
	}

	private boolean esTarjetaVigente(String tarjeta) {
		try {
			AccountDTO respuesta = consultarEstadoTarjetaVirtualMoney(tarjeta);

			if (respuesta.getEndDate().after(new Date()) && BigDecimalUtil.isMayorACero(respuesta.getPrincipaltBalance().getBalance())) {
				return true;
			}
			
			return false;
		}
		catch (Exception e) {
			log.error("comprobarVigenciaTarjeta() - Ha habido un error al calcular el importe de Virtual Money: " + e.getMessage(), e);
			return false;
		}
	}
	
	private AccountDTO consultarEstadoTarjetaVirtualMoney(String numeroTarjeta) throws Exception {
		String medioPago = prefijosTarjetasService.getMedioPagoPrefijo(numeroTarjeta);
		
		VirtualMoneyManager virtualMoneyManager = (VirtualMoneyManager) ticketManager.getPaymentsManager().getPaymentsMehtodManagerAvailables().get(medioPago);
		
		return virtualMoneyManager.consultarSaldo(numeroTarjeta);
	}
	
	private void procesarRespuestaSeleccionPromociones(ScoTicketManager ticketManager, String data1) {
		seleccionPromosManager.seleccionarOpcionPromocion(ticketManager, data1);
		itemsManager.updateItems();
		mostrarPantallaPeticionCodigoParking();
	}
	
	private DataNeeded seleccionarOpcionesPromociones() {
		DataNeeded message = seleccionPromosManager.calcularOpcionesPromociones(ticketManager);
		return message;
	}
}
