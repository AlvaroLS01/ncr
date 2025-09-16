package com.comerzzia.pos.ncr.actions.sale;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.comerzzia.core.servicios.ContextHolder;
import com.comerzzia.pos.core.dispositivos.Dispositivos;
import com.comerzzia.pos.core.dispositivos.dispositivo.impresora.IPrinter;
import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.devices.printer.NCRSCOPrinter;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
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

        protected static final String OTHER_CARDS_TENDER_TYPE = "Otras Tarjetas";
        protected static final String DIALOG_CONFIRM_TYPE = "4";
        protected static final String DIALOG_CONFIRM_ID = "1";
        protected static final String DEFAULT_GIFT_CARD_PARAMETER = "PARAM_NUMERO_TARJETA";
        protected static final String DEFAULT_GIFT_CARD_PARAMETER_ALTERNATIVE = "PARAM_TARJETA";
        protected static final String[] PREFIJOS_TARJETAS_BEAN_NAMES = {
                        "prefijosTarjetasService",
                        "prefijosTarjetasSrv"
        };
        protected static final String[] PREFIJOS_TARJETAS_CLASS_NAMES = {
                        "com.comerzzia.pos.services.mediospagos.PrefijosTarjetasService",
                        "com.comerzzia.pos.services.mediospagos.prefijos.PrefijosTarjetasService",
                        "com.comerzzia.pos.services.payments.PrefijosTarjetasService",
                        "com.comerzzia.pos.services.payments.methods.PrefijosTarjetasService",
                        "com.comerzzia.pos.services.payments.methods.types.PrefijosTarjetasService"
        };

        protected boolean eventsRegistered = false;

        protected PendingPaymentRequest pendingPayment;

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

                if (pendingPayment != null) {
                        log.debug("trayPay() - Pending payment context detected. It will be replaced by the new tender message.");
                        pendingPayment = null;
                }

                PendingPaymentRequest paymentRequest = createPaymentRequest(message);

                if (paymentRequest == null) {
                        return;
                }

                if (paymentRequest.autoDetected) {
                        pendingPayment = paymentRequest;
                        sendAutoDetectedPaymentDialog(paymentRequest);
                        return;
                }

                executePayment(paymentRequest);
        }

        protected PendingPaymentRequest createPaymentRequest(Tender message) {
                String tenderType = message.getFieldValue(Tender.TenderType);
                String numeroTarjeta = StringUtils.trimToNull(message.getFieldValue(Tender.UPC));

                BigDecimal amount = extractAmount(message);

                if (amount == null) {
                        log.error("createPaymentRequest() - Amount received in tender message is not valid");
                        sendTenderException(tenderType, "Importe no válido");
                        return null;
                }

                String paymentMethodCode = resolvePaymentMethodCode(tenderType, numeroTarjeta);

                if (StringUtils.isBlank(paymentMethodCode)) {
                        log.error(String.format("createPaymentRequest() - Unable to resolve payment method for tender type %s", tenderType));
                        sendTenderExceptionMessage("Medio de pago no encontrado");
                        return null;
                }

                PaymentMethodManager paymentMethodManager = findPaymentMethodManager(paymentMethodCode);

                if (paymentMethodManager == null) {
                        log.error(String.format("createPaymentRequest() - Payment manager not found for code %s", paymentMethodCode));
                        sendTenderExceptionMessage("Medio de pago no encontrado");
                        return null;
                }

                MedioPagoBean medioPago = mediosPagosService.getMedioPago(paymentMethodCode);

                boolean autoDetected = isAutoDetectedTender(tenderType);
                boolean giftCard = isGiftCardManager(paymentMethodManager);

                return new PendingPaymentRequest(message, paymentMethodCode, paymentMethodManager, medioPago, numeroTarjeta, amount, tenderType, autoDetected, giftCard);
        }

        protected BigDecimal extractAmount(Tender message) {
                String amountValue = message.getFieldValue(Tender.Amount);

                if (StringUtils.isBlank(amountValue)) {
                        return null;
                }

                try {
                        return new BigDecimal(amountValue).divide(new BigDecimal(100));
                } catch (NumberFormatException e) {
                        log.error("extractAmount() - Unable to parse tender amount: " + amountValue, e);
                        return null;
                }
        }

        protected String resolvePaymentMethodCode(String tenderType, String numeroTarjeta) {
                if (isAutoDetectedTender(tenderType)) {
                        return getPaymentCodeFromPrefixes(numeroTarjeta);
                }

                try {
                        return scoTenderTypeToComerzziaPaymentCode(tenderType);
                } catch (RuntimeException e) {
                        log.error("resolvePaymentMethodCode() - Error resolving payment method code: " + e.getMessage(), e);
                        return null;
                }
        }

        protected String getPaymentCodeFromPrefixes(String numeroTarjeta) {
                if (StringUtils.isBlank(numeroTarjeta)) {
                        return null;
                }

                Object service = findPrefijosTarjetasService();

                if (service == null) {
                        log.warn("getPaymentCodeFromPrefixes() - PrefijosTarjetasService not available");
                        return null;
                }

                try {
                        Method method = service.getClass().getMethod("getMedioPagoPrefijo", String.class);
                        Object result = method.invoke(service, numeroTarjeta);

                        return result != null ? result.toString() : null;
                } catch (Exception e) {
                        log.error("getPaymentCodeFromPrefixes() - Error invoking getMedioPagoPrefijo: " + e.getMessage(), e);
                        return null;
                }
        }

        protected Object findPrefijosTarjetasService() {
                for (String beanName : PREFIJOS_TARJETAS_BEAN_NAMES) {
                        try {
                                Object bean = ContextHolder.getBean(beanName);

                                if (bean != null) {
                                        return bean;
                                }
                        } catch (Exception e) {
                                log.debug(String.format("findPrefijosTarjetasService() - Bean %s not found", beanName));
                        }
                }

                for (String className : PREFIJOS_TARJETAS_CLASS_NAMES) {
                        try {
                                Class<?> clazz = Class.forName(className);

                                Object bean = ContextHolder.get().getBean(clazz);

                                if (bean != null) {
                                        return bean;
                                }
                        } catch (ClassNotFoundException e) {
                                log.debug(String.format("findPrefijosTarjetasService() - Class %s not found", className));
                        } catch (Exception e) {
                                log.debug(String.format("findPrefijosTarjetasService() - Unable to obtain bean for %s", className), e);
                        }
                }

                return null;
        }

        protected PaymentMethodManager findPaymentMethodManager(String paymentMethodCode) {
                PaymentsManager paymentsManager = ticketManager.getPaymentsManager();

                if (paymentsManager == null) {
                        return null;
                }

                Map<String, PaymentMethodManager> managers = getAvailablePaymentManagers(paymentsManager);

                if (managers.containsKey(paymentMethodCode)) {
                        return managers.get(paymentMethodCode);
                }

                try {
                        Method method = paymentsManager.getClass().getMethod("getPaymentMethodManager", String.class);
                        Object result = method.invoke(paymentsManager, paymentMethodCode);

                        if (result instanceof PaymentMethodManager) {
                                return (PaymentMethodManager) result;
                        }
                } catch (NoSuchMethodException e) {
                        log.debug("findPaymentMethodManager() - getPaymentMethodManager method not available");
                } catch (Exception e) {
                        log.error("findPaymentMethodManager() - Error invoking getPaymentMethodManager: " + e.getMessage(), e);
                }

                return null;
        }

        @SuppressWarnings("unchecked")
        protected Map<String, PaymentMethodManager> getAvailablePaymentManagers(PaymentsManager paymentsManager) {
                List<String> methodNames = Arrays.asList("getPaymentsMehtodManagerAvailables", "getPaymentsMethodManagerAvailables", "getPaymentMethodManagers");

                for (String methodName : methodNames) {
                        try {
                                Method method = paymentsManager.getClass().getMethod(methodName);
                                Object result = method.invoke(paymentsManager);

                                if (result instanceof Map) {
                                        return (Map<String, PaymentMethodManager>) result;
                                }
                        } catch (NoSuchMethodException e) {
                                log.debug(String.format("getAvailablePaymentManagers() - Method %s not found", methodName));
                        } catch (Exception e) {
                                log.error(String.format("getAvailablePaymentManagers() - Error invoking %s: %s", methodName, e.getMessage()), e);
                        }
                }

                return Collections.emptyMap();
        }

        protected boolean isAutoDetectedTender(String tenderType) {
                return StringUtils.equalsIgnoreCase(OTHER_CARDS_TENDER_TYPE, StringUtils.trimToEmpty(tenderType));
        }

        protected boolean isGiftCardManager(PaymentMethodManager paymentMethodManager) {
                if (paymentMethodManager == null) {
                        return false;
                }

                String className = paymentMethodManager.getClass().getName();

                return StringUtils.containsIgnoreCase(className, "VirtualMoneyManager")
                                || StringUtils.containsIgnoreCase(className, "BalanceCardManager")
                                || StringUtils.containsIgnoreCase(className, "GiftCardManager");
        }

        protected void sendAutoDetectedPaymentDialog(PendingPaymentRequest paymentRequest) {
                DataNeeded dialog = new DataNeeded();
                dialog.setFieldValue(DataNeeded.Type, DIALOG_CONFIRM_TYPE);
                dialog.setFieldValue(DataNeeded.Id, DIALOG_CONFIRM_ID);
                dialog.setFieldValue(DataNeeded.Mode, "0");

                String description = paymentRequest.medioPago != null ? paymentRequest.medioPago.getDesMedioPago() : paymentRequest.paymentMethodCode;
                String caption = MessageFormat.format("¿Desea usar su tarjeta {0}?", description);

                dialog.setFieldValue(DataNeeded.TopCaption1, caption);
                dialog.setFieldValue(DataNeeded.SummaryInstruction1, "Pulse una opción");
                dialog.setFieldValue(DataNeeded.ButtonData1, "TxSi");
                dialog.setFieldValue(DataNeeded.ButtonText1, "SI");
                dialog.setFieldValue(DataNeeded.ButtonData2, "TxNo");
                dialog.setFieldValue(DataNeeded.ButtonText2, "NO");
                dialog.setFieldValue(DataNeeded.EnableScanner, "0");
                dialog.setFieldValue(DataNeeded.HideGoBack, "1");

                ncrController.sendMessage(dialog);
        }

        protected void executePayment(PendingPaymentRequest paymentRequest) {
                PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
                BigDecimal amountToPay = paymentRequest.amount;
                boolean waitStateSent = false;

                try {
                        if (paymentRequest.giftCard) {
                                ncrController.sendWaitState("Validando tarjeta...");
                                waitStateSent = true;

                                try {
                                        configureGiftCardManager(paymentRequest.paymentMethodManager, paymentRequest.numeroTarjeta);

                                        BigDecimal balance = consultGiftCardBalance(paymentRequest.paymentMethodManager, paymentRequest.numeroTarjeta);

                                        if (balance != null && amountToPay.compareTo(balance) > 0) {
                                                amountToPay = balance;
                                                paymentRequest.amount = balance;
                                                paymentRequest.tenderMessage.setFieldIntValue(Tender.Amount, balance);
                                        }
                                } catch (Exception e) {
                                        log.error("executePayment() - Error preparing gift card payment: " + e.getMessage(), e);
                                }
                        }

                        if (StringUtils.equalsIgnoreCase("Credit", paymentRequest.scoTenderType)) {
                                TenderException response = new TenderException();
                                response.setFieldValue(TenderException.TenderType, paymentRequest.tenderMessage.getFieldValue(Tender.TenderType));
                                response.setFieldValue(TenderException.ExceptionType, "0");
                                response.setFieldValue(TenderException.ExceptionId, "1");
                                ncrController.sendMessage(response);
                        }

                        paymentsManager.pay(paymentRequest.paymentMethodCode, amountToPay);
                } catch (Exception e) {
                        handlePaymentException(paymentsManager, e);
                } finally {
                        if (waitStateSent) {
                                ncrController.sendFinishWaitState();
                        }
                }
        }

        protected void configureGiftCardManager(PaymentMethodManager paymentMethodManager, String numeroTarjeta) {
                if (paymentMethodManager == null || StringUtils.isBlank(numeroTarjeta)) {
                        return;
                }

                String parameterName = resolveCardNumberParameterName(paymentMethodManager);

                paymentMethodManager.addParameter(parameterName, numeroTarjeta);
        }

        protected String resolveCardNumberParameterName(PaymentMethodManager paymentMethodManager) {
                List<String> possibleNames = Arrays.asList("PARAM_NUMERO_TARJETA", "PARAM_TARJETA", DEFAULT_GIFT_CARD_PARAMETER, DEFAULT_GIFT_CARD_PARAMETER_ALTERNATIVE);

                for (String fieldName : possibleNames) {
                        String value = findStaticStringField(paymentMethodManager.getClass(), fieldName);

                        if (StringUtils.isNotBlank(value)) {
                                return value;
                        }
                }

                return DEFAULT_GIFT_CARD_PARAMETER;
        }

        protected String findStaticStringField(Class<?> clazz, String fieldName) {
                Class<?> current = clazz;

                while (current != null) {
                        try {
                                Field field = current.getDeclaredField(fieldName);
                                field.setAccessible(true);
                                Object value = field.get(null);

                                if (value instanceof String) {
                                        return (String) value;
                                }
                        } catch (NoSuchFieldException e) {
                                // continue searching in superclass
                        } catch (Exception e) {
                                log.debug(String.format("findStaticStringField() - Unable to read field %s from %s", fieldName, current.getName()), e);
                        }

                        current = current.getSuperclass();
                }

                return null;
        }

        protected BigDecimal consultGiftCardBalance(PaymentMethodManager paymentMethodManager, String numeroTarjeta) {
                if (paymentMethodManager == null || StringUtils.isBlank(numeroTarjeta)) {
                        return null;
                }

                try {
                        Method method = paymentMethodManager.getClass().getMethod("consultarSaldo", String.class);
                        Object account = method.invoke(paymentMethodManager, numeroTarjeta);

                        return extractBalanceFromAccount(account);
                } catch (NoSuchMethodException e) {
                        log.debug("consultGiftCardBalance() - consultarSaldo method not available");
                } catch (Exception e) {
                        log.error("consultGiftCardBalance() - Error invoking consultarSaldo: " + e.getMessage(), e);
                }

                return null;
        }

        protected BigDecimal extractBalanceFromAccount(Object account) {
                if (account == null) {
                        return null;
                }

                try {
                        Method principalBalanceMethod = account.getClass().getMethod("getPrincipaltBalance");
                        Object principalBalance = principalBalanceMethod.invoke(account);

                        if (principalBalance == null) {
                                return null;
                        }

                        Method balanceMethod = principalBalance.getClass().getMethod("getBalance");
                        Object balance = balanceMethod.invoke(principalBalance);

                        if (balance instanceof BigDecimal) {
                                return (BigDecimal) balance;
                        }

                        if (balance != null) {
                                return new BigDecimal(balance.toString());
                        }
                } catch (Exception e) {
                        log.error("extractBalanceFromAccount() - Error obtaining balance: " + e.getMessage(), e);
                }

                return null;
        }

        protected void sendCloseDataNeeded() {
                DataNeeded closeMessage = new DataNeeded();
                closeMessage.setFieldValue(DataNeeded.Type, "0");
                closeMessage.setFieldValue(DataNeeded.Id, "0");
                closeMessage.setFieldValue(DataNeeded.Mode, "0");

                ncrController.sendMessage(closeMessage);
        }

        protected void sendTenderException(String tenderType, String message) {
                TenderException error = new TenderException();
                error.setFieldValue(TenderException.ExceptionId, "0");
                error.setFieldValue(TenderException.ExceptionType, "0");

                String tenderTypeValue = StringUtils.isNotBlank(tenderType) ? tenderType : message;

                if (StringUtils.isNotBlank(tenderTypeValue)) {
                        error.setFieldValue(TenderException.TenderType, tenderTypeValue);
                }

                if (StringUtils.isNotBlank(message)) {
                        error.setFieldValue(TenderException.Message, message);
                }

                ncrController.sendMessage(error);
        }

        protected void sendTenderExceptionMessage(String message) {
                sendTenderException(message, message);
        }

        public boolean handleDataNeededReply(DataNeededReply message) {
                if (message == null) {
                        return false;
                }

                if (!StringUtils.equals(DIALOG_CONFIRM_TYPE, message.getFieldValue(DataNeededReply.Type))
                                || !StringUtils.equals(DIALOG_CONFIRM_ID, message.getFieldValue(DataNeededReply.Id))) {
                        return false;
                }

                PendingPaymentRequest paymentRequest = pendingPayment;
                pendingPayment = null;

                sendCloseDataNeeded();

                if (paymentRequest == null) {
                        log.warn("handleDataNeededReply() - Received confirmation without pending payment context");
                        return true;
                }

                String option = message.getFieldValue(DataNeededReply.Data1);

                if (StringUtils.equalsIgnoreCase("TxSi", option)) {
                        executePayment(paymentRequest);
                } else {
                        sendTenderException(paymentRequest.scoTenderType, "Operación cancelada por el usuario");
                }

                return true;
        }

        protected void handlePaymentException(PaymentsManager paymentsManager, Exception e) {
                if (e instanceof PaymentException) {
                        PaymentException paymentException = (PaymentException) e;
                        PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, paymentException.getPaymentId(), e, paymentException.getErrorCode(), paymentException.getMessage());
                        PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
                        paymentsManager.getEventsHandler().paymentsError(event);
                } else {
                        PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, -1, e, null, null);
                        PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
                        paymentsManager.getEventsHandler().paymentsError(event);
                }
        }

        protected static class PendingPaymentRequest {
                protected final Tender tenderMessage;
                protected final String paymentMethodCode;
                protected final PaymentMethodManager paymentMethodManager;
                protected final MedioPagoBean medioPago;
                protected final String numeroTarjeta;
                protected BigDecimal amount;
                protected final String scoTenderType;
                protected final boolean autoDetected;
                protected final boolean giftCard;

                protected PendingPaymentRequest(Tender tenderMessage, String paymentMethodCode, PaymentMethodManager paymentMethodManager,
                                MedioPagoBean medioPago, String numeroTarjeta, BigDecimal amount, String scoTenderType,
                                boolean autoDetected, boolean giftCard) {
                        this.tenderMessage = tenderMessage;
                        this.paymentMethodCode = paymentMethodCode;
                        this.paymentMethodManager = paymentMethodManager;
                        this.medioPago = medioPago;
                        this.numeroTarjeta = numeroTarjeta;
                        this.amount = amount;
                        this.scoTenderType = scoTenderType;
                        this.autoDetected = autoDetected;
                        this.giftCard = giftCard;
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
