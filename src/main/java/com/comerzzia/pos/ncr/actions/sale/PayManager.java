package com.comerzzia.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.core.dispositivos.Dispositivos;
import com.comerzzia.pos.core.dispositivos.dispositivo.impresora.IPrinter;
import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.devices.printer.NCRSCOPrinter;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.EndTransaction;
import com.comerzzia.pos.ncr.messages.EnterTenderMode;
import com.comerzzia.pos.ncr.messages.ExitTenderMode;
import com.comerzzia.pos.ncr.messages.Receipt;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderAccepted;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.persistence.promociones.tipos.PromocionTipoBean;
import com.comerzzia.pos.services.mediospagos.MediosPagosService;
import com.comerzzia.pos.services.payments.PaymentException;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.events.PaymentErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentOkEvent;
import com.comerzzia.pos.services.payments.events.PaymentsCompletedEvent;
import com.comerzzia.pos.services.payments.events.PaymentsErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentsOkEvent;
import com.comerzzia.pos.services.payments.events.listeners.PaymentsCompletedListener;
import com.comerzzia.pos.services.payments.events.listeners.PaymentsErrorListener;
import com.comerzzia.pos.services.payments.events.listeners.PaymentsOkListener;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.BasicPaymentMethodManager;
import com.comerzzia.pos.services.ticket.pagos.IPagoTicket;
import com.comerzzia.pos.services.ticket.pagos.PagoTicket;
import com.comerzzia.pos.services.ticket.pagos.tarjeta.DatosRespuestaPagoTarjeta;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;

@Lazy(false)
@Service
public class PayManager implements ActionManager {
	private static final Logger log = Logger.getLogger(PayManager.class);

	protected boolean eventsRegistered = false;
	
	@Autowired
	protected NCRController ncrController;

	@Autowired
	protected ItemsManager itemsManager;

	@Autowired
	protected ScoTicketManager ticketManager;
	
	@Autowired
	protected MediosPagosService mediosPagosService;
	
	@SuppressWarnings("unchecked")
	protected void addHeaderDiscountsPayments() {
		ticketManager.getTicket().getPagos().removeIf(p->!((IPagoTicket)p).isIntroducidoPorCajero());
		ticketManager.getTicket().getCabecera().getTotales().recalcular();
		
		Map<String, BigDecimal> descuentosPromocionales = new HashMap<String, BigDecimal>();
		
		for(PromocionTicket promocion : (List<PromocionTicket>) ticketManager.getTicket().getPromociones()) {
			if(promocion.isDescuentoMenosIngreso()) {
				PromocionTipoBean tipoPromocion = ticketManager.getSesion().getSesionPromociones().getPromocionActiva(promocion.getIdPromocion()).getPromocionBean().getTipoPromocion();
				String codMedioPago = tipoPromocion.getCodMedioPagoMenosIngreso();
				if(codMedioPago != null) {
					BigDecimal importeDescPromocional = BigDecimalUtil.redondear(promocion.getImporteTotalAhorro(), 2);
					BigDecimal importeDescAcum = descuentosPromocionales.get(codMedioPago) != null ? descuentosPromocionales.get(codMedioPago) : BigDecimal.ZERO;
					importeDescAcum = importeDescAcum.add(importeDescPromocional);
					descuentosPromocionales.put(codMedioPago, importeDescAcum);
				}
			}
		}
		
		for(String codMedioPago : descuentosPromocionales.keySet()) {
			BigDecimal importe = descuentosPromocionales.get(codMedioPago);
			
			if(BigDecimalUtil.isMayorACero(importe)) {
				ticketManager.addPayToTicket(codMedioPago, importe, null, false, false);
			}
		}
		
		ticketManager.getTicket().getCabecera().getTotales().recalcular();
	}

	@Override
	public void processMessage(BasicNCRMessage message) {
		if (message instanceof EnterTenderMode) {
			activateTenderMode();
		} else if (message instanceof ExitTenderMode) {
			disableTenderMode();
		} else if (message instanceof Tender) {
			trayPay((Tender) message);
		} else {
			log.warn("Message type not managed: " + message.getName());
		}

	}

	protected void disableTenderMode() {
		ticketManager.setTenderMode(false);
		itemsManager.sendTotals();
	}

	protected void activateTenderMode() {
		ticketManager.setTenderMode(true);
		initializePaymentsManager();
		
		addHeaderDiscountsPayments();
		
		ticketManager.getPaymentsManager().setTicketData(ticketManager.getTicket(), null);									
		
		itemsManager.sendTotals();
	}

	@PostConstruct
	public void init() {
		ncrController.registerActionManager(EnterTenderMode.class, this);
		ncrController.registerActionManager(ExitTenderMode.class, this);
		ncrController.registerActionManager(Tender.class, this);
	}
	
	protected void initializePaymentsManager() {
		if (!eventsRegistered) {
			eventsRegistered = true;
			
			PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
			
			paymentsManager.addListenerOk(new PaymentsOkListener(){
				@Override
				public void paymentsOk(PaymentsOkEvent event) {
					PaymentOkEvent eventOk = event.getOkEvent();
					processPaymentOk(eventOk);
				}
			});
			
			paymentsManager.addListenerPaymentCompleted(new PaymentsCompletedListener(){
				@Override
				public void paymentsCompleted(PaymentsCompletedEvent event) {
					finishSale(event);
				}
			});
			
			paymentsManager.addListenerError(new PaymentsErrorListener(){
				@Override
				public void paymentsError(PaymentsErrorEvent event) {
					processPaymentError(event);
				}
			});
		}		
	}
	
	public String scoTenderTypeToComerzziaPaymentCode(String code) {
		if (ncrController.getConfiguration().isSimulateAllPayAsCash()) {
			code = "Cash";
		} else {
			code = code.replace(" ", "_");
		}
		
		String comerzzayPayCode = ncrController.getConfiguration().getPaymentsCodesMapping().get(code);
		
		if (StringUtils.isEmpty(comerzzayPayCode)) {
			throw new RuntimeException("SCO tender type not supported");
		}
		
		return comerzzayPayCode;
	}

	public String comerzziaPaymentCodeToScoTenderType(String code) {
		if (ncrController.getConfiguration().isSimulateAllPayAsCash()) {
			return "Cash";
		}
		
		String scoCode = "Credit";
		
		for (Map.Entry<String, String> entry : ncrController.getConfiguration().getPaymentsCodesMapping().entrySet()) {
			if (StringUtils.equals(entry.getValue(), code)) {
				scoCode = entry.getKey().replace("_", " ");
				break;
			}
		}				
		
		return scoCode;
	}
	
	protected void trayPay(Tender message) {
		if (ticketManager.isTrainingMode()) {
			// Automatic pay accepted message for training mode
			TenderAccepted response = new TenderAccepted();
			response.setFieldValue(TenderAccepted.Amount, message.getFieldValue(Tender.Amount));
			response.setFieldValue(TenderAccepted.TenderType, message.getFieldValue(Tender.TenderType));
			response.setFieldValue(TenderAccepted.Description, message.getFieldValue(Tender.TenderType));
			
			return;
		}
		
		PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
		
		BigDecimal importe = new BigDecimal(message.getFieldValue(Tender.Amount)).divide(new BigDecimal(100));

		try {
		   if (StringUtils.equalsIgnoreCase("Credit", message.getFieldValue(Tender.TenderType))) {
			    // this message force the SCO to wait TenderAccepted or TenderException message
			    TenderException response = new TenderException();
			    response.setFieldValue(TenderException.TenderType, message.getFieldValue(Tender.TenderType));
			    response.setFieldValue(TenderException.ExceptionType, "0");
			    response.setFieldValue(TenderException.ExceptionId, "1");			    
			    ncrController.sendMessage(response);
		   }
		   
		   paymentsManager.pay(scoTenderTypeToComerzziaPaymentCode(message.getFieldValue(Tender.TenderType)), importe);
		} catch (Exception e) {
			if (e instanceof PaymentException) {
				PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, ((PaymentException)e).getPaymentId(), e, ((PaymentException)e).getErrorCode(), ((PaymentException)e).getMessage());
				PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
				paymentsManager.getEventsHandler().paymentsError(event);						
				
			} else {
				PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, -1, e, null, null);
				PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
				paymentsManager.getEventsHandler().paymentsError(event);						
			}			
		}
	}
	
	protected void processPaymentOk(PaymentOkEvent eventOk) {
		log.debug("processPaymentOk() - Pay accepted");
		
		BigDecimal amount = eventOk.getAmount();
		String paymentCode = ((PaymentMethodManager) eventOk.getSource()).getPaymentCode();
		Integer paymentId = eventOk.getPaymentId();
		
		MedioPagoBean paymentMethod = mediosPagosService.getMedioPago(paymentCode);
		
		boolean cashFlowRecorded = ((PaymentMethodManager) eventOk.getSource()).recordCashFlowImmediately();
		
		PagoTicket payment = ticketManager.addPayToTicket(paymentCode, amount, paymentId, true, cashFlowRecorded);		
		
		if(paymentMethod.getTarjetaCredito() != null && paymentMethod.getTarjetaCredito()) {
			if(eventOk.getExtendedData().containsKey(BasicPaymentMethodManager.PARAM_RESPONSE_TEF)) {
				DatosRespuestaPagoTarjeta datosRespuestaPagoTarjeta = (DatosRespuestaPagoTarjeta) eventOk.getExtendedData().get(BasicPaymentMethodManager.PARAM_RESPONSE_TEF);
				payment.setDatosRespuestaPagoTarjeta(datosRespuestaPagoTarjeta);
			}
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


	private void printReceipt() {
		Receipt receipt = new Receipt();
		receipt.setFieldValue(Receipt.Id, itemsManager.getTransactionId());
		
		IPrinter impresora = Dispositivos.getInstance().getImpresora1();
		
		if (!(impresora instanceof NCRSCOPrinter)) {
			log.error("printDocument() - Tipo de impresora seleccionada no valida");
			receipt.setFieldValue(Receipt.PrinterData + "1", Base64.getEncoder().encodeToString("Se ha seleccionado un tipo de impresora incorrecta\n".getBytes()));
			receipt.setFieldValue(Receipt.Complete, Receipt.COMPLETE_OK);

			ncrController.sendMessage(receipt);
			
			return;
		}
		
        // Asignar objeto al controlador de la impresora para que añada las líneas de impresión		
		((NCRSCOPrinter)impresora).setRecepipt(receipt);
		
		receipt.setFieldValue(Receipt.Complete, Receipt.COMPLETE_OK);
		
		try {
			ticketManager.printDocument();
		} catch (Exception e) {
			log.error("Error while print document: " + e.getMessage(), e);
		}

		ncrController.sendMessage(receipt);
	}
	
	protected void finishSale(final PaymentsCompletedEvent event) {
		log.debug("finishSale() - Printing receipt and end trasaction");

		finishSale();
	}

	protected void finishSale() {
		ticketManager.saveTicket();
		
		// print receipt
		printReceipt();

		// end transaction
		EndTransaction message = new EndTransaction();
		message.setFieldValue(EndTransaction.Id, itemsManager.getTransactionId());

		ncrController.sendMessage(message);

		itemsManager.resetTicket();
	}
	
	protected void processPaymentError() {
		TenderException message = new TenderException();
		
		ncrController.sendMessage(message);
	}
	
	protected void processPaymentError(PaymentsErrorEvent event) {
		log.debug("processPaymentError() - Pay rejected!!!");
		
		PaymentErrorEvent errorEvent = event.getErrorEvent();

		TenderException message = new TenderException();
		message.setFieldValue(TenderException.ExceptionId, "0");
		message.setFieldValue(TenderException.ExceptionType, "0");
		
		if(event.getSource() instanceof PaymentMethodManager) {			
			PaymentMethodManager paymentMethodManager = (PaymentMethodManager) event.getSource();
			
			message.setFieldValue(TenderException.TenderType, comerzziaPaymentCodeToScoTenderType(paymentMethodManager.getPaymentCode()));
		} else {
		   message.setFieldValue(TenderException.TenderType, "Credit");
		}
		
		if(errorEvent.getException() != null) {
			message.setFieldValue(TenderException.Message, errorEvent.getException().getMessage());
		}
		else {
			message.setFieldValue(TenderException.Message, errorEvent.getErrorMessage());
		}
		
		ncrController.sendMessage(message);
	}
		
}
