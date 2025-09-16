package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.core.servicios.ContextHolder;
import com.comerzzia.pos.ncr.actions.sale.PayManager;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.payments.PaymentException;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.events.PaymentErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentsErrorEvent;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;

@Service
@Primary
public class AmetllerPayManager extends PayManager {

    private static final Logger LOG = Logger.getLogger(AmetllerPayManager.class);

    private static final String OTHER_CARDS_TENDER_TYPE = "Otras Tarjetas";
    private static final String DIALOG_CONFIRM_TYPE = "4";
    private static final String DIALOG_CONFIRM_ID = "1";
    private static final String DEFAULT_GIFT_CARD_PARAMETER = "PARAM_NUMERO_TARJETA";
    private static final String DEFAULT_GIFT_CARD_PARAMETER_ALTERNATIVE = "PARAM_TARJETA";
    private static final String[] PREFIJOS_TARJETAS_BEAN_NAMES = {
            "prefijosTarjetasService",
            "prefijosTarjetasSrv"
    };
    private static final String[] PREFIJOS_TARJETAS_CLASS_NAMES = {
            "com.comerzzia.pos.services.mediospagos.PrefijosTarjetasService",
            "com.comerzzia.pos.services.mediospagos.prefijos.PrefijosTarjetasService",
            "com.comerzzia.pos.services.payments.PrefijosTarjetasService",
            "com.comerzzia.pos.services.payments.methods.PrefijosTarjetasService",
            "com.comerzzia.pos.services.payments.methods.types.PrefijosTarjetasService",
            "com.comerzzia.ametller.pos.services.mediospagos.PrefijosTarjetasService"
    };

    private PendingPaymentRequest pendingPayment;

    @Override
    protected void activateTenderMode() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            ((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(false);
        }
        super.activateTenderMode();
    }

    @Override
    protected void trayPay(Tender message) {
        if (ticketManager.isTrainingMode()) {
            super.trayPay(message);
            return;
        }

        if (pendingPayment != null) {
            LOG.debug("trayPay() - Pending payment context detected. It will be replaced by the new tender message.");
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
            LOG.warn("handleDataNeededReply() - Received confirmation without pending payment context");
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

    private PendingPaymentRequest createPaymentRequest(Tender message) {
        String tenderType = message.getFieldValue(Tender.TenderType);
        String numeroTarjeta = StringUtils.trimToNull(message.getFieldValue(Tender.UPC));

        BigDecimal amount = extractAmount(message);

        if (amount == null) {
            LOG.error("createPaymentRequest() - Amount received in tender message is not valid");
            sendTenderException(tenderType, "Importe no válido");
            return null;
        }

        boolean autoDetected = isAutoDetectedTender(tenderType);
        String paymentMethodCode = resolvePaymentMethodCode(tenderType, numeroTarjeta);

        if (StringUtils.isBlank(paymentMethodCode) && autoDetected) {
            paymentMethodCode = findUniqueGiftCardPaymentCode();
        }

        if (StringUtils.isBlank(paymentMethodCode)) {
            LOG.error(String.format("createPaymentRequest() - Unable to resolve payment method for tender type %s", tenderType));
            sendTenderExceptionMessage("Medio de pago no encontrado");
            return null;
        }

        PaymentMethodManager paymentMethodManager = findPaymentMethodManager(paymentMethodCode);

        if (paymentMethodManager == null) {
            LOG.error(String.format("createPaymentRequest() - Payment manager not found for code %s", paymentMethodCode));
            sendTenderExceptionMessage("Medio de pago no encontrado");
            return null;
        }

        MedioPagoBean medioPago = mediosPagosService.getMedioPago(paymentMethodCode);
        boolean giftCard = isGiftCardManager(paymentMethodManager);

        return new PendingPaymentRequest(message, paymentMethodCode, paymentMethodManager, medioPago, numeroTarjeta, amount,
                tenderType, autoDetected, giftCard);
    }

    private BigDecimal extractAmount(Tender message) {
        String amountValue = message.getFieldValue(Tender.Amount);

        if (StringUtils.isBlank(amountValue)) {
            return null;
        }

        try {
            return new BigDecimal(amountValue).divide(new BigDecimal(100));
        } catch (NumberFormatException e) {
            LOG.error("extractAmount() - Unable to parse tender amount: " + amountValue, e);
            return null;
        }
    }

    private String resolvePaymentMethodCode(String tenderType, String numeroTarjeta) {
        if (isAutoDetectedTender(tenderType)) {
            return getPaymentCodeFromPrefixes(numeroTarjeta);
        }

        try {
            return scoTenderTypeToComerzziaPaymentCode(tenderType);
        } catch (RuntimeException e) {
            LOG.error("resolvePaymentMethodCode() - Error resolving payment method code: " + e.getMessage(), e);
            return null;
        }
    }

    private String getPaymentCodeFromPrefixes(String numeroTarjeta) {
        if (StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        Object service = findPrefijosTarjetasService();

        if (service == null) {
            LOG.warn("getPaymentCodeFromPrefixes() - PrefijosTarjetasService not available");
            return null;
        }

        try {
            Method method = service.getClass().getMethod("getMedioPagoPrefijo", String.class);
            Object result = method.invoke(service, numeroTarjeta);

            return result != null ? result.toString() : null;
        } catch (Exception e) {
            LOG.error("getPaymentCodeFromPrefixes() - Error invoking getMedioPagoPrefijo: " + e.getMessage(), e);
            return null;
        }
    }

    private Object findPrefijosTarjetasService() {
        for (String beanName : PREFIJOS_TARJETAS_BEAN_NAMES) {
            try {
                Object bean = ContextHolder.getBean(beanName);

                if (bean != null) {
                    return bean;
                }
            } catch (Exception e) {
                LOG.debug(String.format("findPrefijosTarjetasService() - Bean %s not found", beanName));
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
                LOG.debug(String.format("findPrefijosTarjetasService() - Class %s not found", className));
            } catch (Exception e) {
                LOG.debug(String.format("findPrefijosTarjetasService() - Unable to obtain bean for %s", className), e);
            }
        }

        return null;
    }

    private String findUniqueGiftCardPaymentCode() {
        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();

        if (paymentsManager == null) {
            return null;
        }

        Map<String, PaymentMethodManager> managers = getAvailablePaymentManagers(paymentsManager);
        String candidate = null;

        for (Map.Entry<String, PaymentMethodManager> entry : managers.entrySet()) {
            if (isGiftCardManager(entry.getValue())) {
                if (candidate != null) {
                    return null;
                }
                candidate = entry.getKey();
            }
        }

        return candidate;
    }

    private PaymentMethodManager findPaymentMethodManager(String paymentMethodCode) {
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
            LOG.debug("findPaymentMethodManager() - getPaymentMethodManager method not available");
        } catch (Exception e) {
            LOG.error("findPaymentMethodManager() - Error invoking getPaymentMethodManager: " + e.getMessage(), e);
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, PaymentMethodManager> getAvailablePaymentManagers(PaymentsManager paymentsManager) {
        List<String> methodNames = Arrays.asList("getPaymentsMehtodManagerAvailables", "getPaymentsMethodManagerAvailables",
                "getPaymentMethodManagers");

        for (String methodName : methodNames) {
            try {
                Method method = paymentsManager.getClass().getMethod(methodName);
                Object result = method.invoke(paymentsManager);

                if (result instanceof Map) {
                    return (Map<String, PaymentMethodManager>) result;
                }
            } catch (NoSuchMethodException e) {
                LOG.debug(String.format("getAvailablePaymentManagers() - Method %s not found", methodName));
            } catch (Exception e) {
                LOG.error(String.format("getAvailablePaymentManagers() - Error invoking %s: %s", methodName, e.getMessage()), e);
            }
        }

        return Collections.emptyMap();
    }

    private boolean isAutoDetectedTender(String tenderType) {
        return StringUtils.equalsIgnoreCase(OTHER_CARDS_TENDER_TYPE, StringUtils.trimToEmpty(tenderType));
    }

    private boolean isGiftCardManager(PaymentMethodManager paymentMethodManager) {
        if (paymentMethodManager == null) {
            return false;
        }

        String className = paymentMethodManager.getClass().getName();

        return StringUtils.containsIgnoreCase(className, "VirtualMoneyManager")
                || StringUtils.containsIgnoreCase(className, "BalanceCardManager")
                || StringUtils.containsIgnoreCase(className, "GiftCardManager");
    }

    private void sendAutoDetectedPaymentDialog(PendingPaymentRequest paymentRequest) {
        DataNeeded dialog = new DataNeeded();
        dialog.setFieldValue(DataNeeded.Type, DIALOG_CONFIRM_TYPE);
        dialog.setFieldValue(DataNeeded.Id, DIALOG_CONFIRM_ID);
        dialog.setFieldValue(DataNeeded.Mode, "0");

        String description = paymentRequest.medioPago != null ? paymentRequest.medioPago.getDesMedioPago()
                : paymentRequest.paymentMethodCode;
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

    private void executePayment(PendingPaymentRequest paymentRequest) {
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
                    LOG.error("executePayment() - Error preparing gift card payment: " + e.getMessage(), e);
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

    private void configureGiftCardManager(PaymentMethodManager paymentMethodManager, String numeroTarjeta) {
        if (paymentMethodManager == null || StringUtils.isBlank(numeroTarjeta)) {
            return;
        }

        String parameterName = resolveCardNumberParameterName(paymentMethodManager);

        paymentMethodManager.addParameter(parameterName, numeroTarjeta);
    }

    private String resolveCardNumberParameterName(PaymentMethodManager paymentMethodManager) {
        List<String> possibleNames = Arrays.asList("PARAM_NUMERO_TARJETA", "PARAM_TARJETA", DEFAULT_GIFT_CARD_PARAMETER,
                DEFAULT_GIFT_CARD_PARAMETER_ALTERNATIVE);

        for (String fieldName : possibleNames) {
            String value = findStaticStringField(paymentMethodManager.getClass(), fieldName);

            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }

        return DEFAULT_GIFT_CARD_PARAMETER;
    }

    private String findStaticStringField(Class<?> clazz, String fieldName) {
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
                LOG.debug(String.format("findStaticStringField() - Unable to read field %s from %s", fieldName, current.getName()), e);
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private BigDecimal consultGiftCardBalance(PaymentMethodManager paymentMethodManager, String numeroTarjeta) {
        if (paymentMethodManager == null || StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        try {
            Method method = paymentMethodManager.getClass().getMethod("consultarSaldo", String.class);
            Object account = method.invoke(paymentMethodManager, numeroTarjeta);

            return extractBalanceFromAccount(account);
        } catch (NoSuchMethodException e) {
            LOG.debug("consultGiftCardBalance() - consultarSaldo method not available");
        } catch (Exception e) {
            LOG.error("consultGiftCardBalance() - Error invoking consultarSaldo: " + e.getMessage(), e);
        }

        return null;
    }

    private BigDecimal extractBalanceFromAccount(Object account) {
        if (account == null) {
            return null;
        }

        try {
            Method principalBalanceMethod = findMethod(account.getClass(), "getPrincipaltBalance");

            if (principalBalanceMethod == null) {
                principalBalanceMethod = findMethod(account.getClass(), "getPrincipalBalance");
            }

            if (principalBalanceMethod == null) {
                return null;
            }

            Object principalBalance = principalBalanceMethod.invoke(account);

            if (principalBalance == null) {
                return null;
            }

            Method balanceMethod = findMethod(principalBalance.getClass(), "getBalance");

            if (balanceMethod == null) {
                return null;
            }

            Object balance = balanceMethod.invoke(principalBalance);

            if (balance instanceof BigDecimal) {
                return (BigDecimal) balance;
            }

            if (balance != null) {
                return new BigDecimal(balance.toString());
            }
        } catch (Exception e) {
            LOG.error("extractBalanceFromAccount() - Error obtaining balance: " + e.getMessage(), e);
        }

        return null;
    }

    private Method findMethod(Class<?> clazz, String methodName) {
        Class<?> current = clazz;

        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                // continue searching
            }
            current = current.getSuperclass();
        }

        return null;
    }

    private void sendCloseDataNeeded() {
        DataNeeded closeMessage = new DataNeeded();
        closeMessage.setFieldValue(DataNeeded.Type, "0");
        closeMessage.setFieldValue(DataNeeded.Id, "0");
        closeMessage.setFieldValue(DataNeeded.Mode, "0");

        ncrController.sendMessage(closeMessage);
    }

    private void sendTenderException(String tenderType, String message) {
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

    private void sendTenderExceptionMessage(String message) {
        sendTenderException(message, message);
    }

    private void handlePaymentException(PaymentsManager paymentsManager, Exception e) {
        if (paymentsManager == null) {
            return;
        }

        if (e instanceof PaymentException) {
            PaymentException paymentException = (PaymentException) e;
            PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, paymentException.getPaymentId(), e,
                    paymentException.getErrorCode(), paymentException.getMessage());
            PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
            paymentsManager.getEventsHandler().paymentsError(event);
        } else {
            PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, -1, e, null, null);
            PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
            paymentsManager.getEventsHandler().paymentsError(event);
        }
    }

    private static class PendingPaymentRequest {
        private final Tender tenderMessage;
        private final String paymentMethodCode;
        private final PaymentMethodManager paymentMethodManager;
        private final MedioPagoBean medioPago;
        private final String numeroTarjeta;
        private BigDecimal amount;
        private final String scoTenderType;
        private final boolean autoDetected;
        private final boolean giftCard;

        private PendingPaymentRequest(Tender tenderMessage, String paymentMethodCode,
                PaymentMethodManager paymentMethodManager, MedioPagoBean medioPago, String numeroTarjeta, BigDecimal amount,
                String scoTenderType, boolean autoDetected, boolean giftCard) {
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
}

