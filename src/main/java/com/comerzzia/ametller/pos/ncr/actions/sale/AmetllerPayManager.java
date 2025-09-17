package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.services.payments.methods.types.AmetllerGiftCardManager;
import com.comerzzia.core.servicios.ContextHolder;
import com.comerzzia.pos.ncr.actions.sale.PayManager;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.persistence.giftcard.GiftCardBean;
import com.comerzzia.pos.services.payments.PaymentException;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.events.PaymentErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentsErrorEvent;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.BasicPaymentMethodManager;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.i18n.I18N;

@Service
@Primary
public class AmetllerPayManager extends PayManager {

    private static final Logger LOG = Logger.getLogger(AmetllerPayManager.class);
    private static final String OTHER_CARDS_TENDER_TYPE = "Otras Tarjetas";
    private static final String[] PREFIJOS_BEAN_NAMES = { "prefijosTarjetasService", "prefijosTarjetasSrv" };
    private static final String[] PREFIJOS_CLASS_NAMES = {
            "com.comerzzia.pos.services.payments.methods.prefijos.PrefijosTarjetasService",
            "com.comerzzia.pos.services.mediospagos.prefijos.PrefijosTarjetasService"
    };

    @Override
    protected void trayPay(Tender message) {
        if (!ticketManager.isTrainingMode() && isOtherCardsTender(message)) {
            if (processOtherCardsTender(message)) {
                return;
            }
        }

        super.trayPay(message);
    }

    public boolean handleDataNeededReply(DataNeededReply message) {
        return false;
    }

    private boolean isOtherCardsTender(Tender message) {
        if (message == null) {
            return false;
        }

        return StringUtils.equalsIgnoreCase(OTHER_CARDS_TENDER_TYPE,
                StringUtils.trimToEmpty(message.getFieldValue(Tender.TenderType)));
    }

    private boolean processOtherCardsTender(Tender message) {
        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();

        if (paymentsManager == null) {
            LOG.warn("processOtherCardsTender() - PaymentsManager not available");
            return false;
        }

        BigDecimal amount = extractAmount(message);

        if (amount == null) {
            sendTenderException(message.getFieldValue(Tender.TenderType), I18N.getTexto("Importe no válido"));
            return true;
        }

        String numeroTarjeta = StringUtils.trimToNull(message.getFieldValue(Tender.UPC));

        String paymentMethodCode = resolvePaymentMethodCode(numeroTarjeta);

        if (StringUtils.isBlank(paymentMethodCode)) {
            sendTenderException(message.getFieldValue(Tender.TenderType), I18N.getTexto("Medio de pago no encontrado"));
            return true;
        }

        PaymentMethodManager paymentMethodManager = findPaymentMethodManager(paymentsManager, paymentMethodCode);

        if (paymentMethodManager == null) {
            sendTenderException(message.getFieldValue(Tender.TenderType), I18N.getTexto("Medio de pago no encontrado"));
            return true;
        }

        if (paymentMethodManager instanceof AmetllerGiftCardManager) {
            return processGiftCardTender(message, paymentsManager, (AmetllerGiftCardManager) paymentMethodManager,
                    paymentMethodCode, numeroTarjeta, amount);
        }

        try {
            paymentsManager.pay(paymentMethodCode, amount);
        }
        catch (Exception e) {
            handlePaymentException(paymentsManager, e);
        }

        return true;
    }

    private BigDecimal extractAmount(Tender message) {
        String amountValue = message.getFieldValue(Tender.Amount);

        if (StringUtils.isBlank(amountValue)) {
            return null;
        }

        try {
            return new BigDecimal(amountValue).divide(new BigDecimal(100));
        }
        catch (NumberFormatException e) {
            LOG.error("extractAmount() - Unable to parse tender amount: " + amountValue, e);
            return null;
        }
    }

    private String resolvePaymentMethodCode(String numeroTarjeta) {
        String prefijoCode = getPaymentCodeFromPrefixes(numeroTarjeta);

        if (StringUtils.isNotBlank(prefijoCode)) {
            return prefijoCode;
        }

        return findGiftCardPaymentCode();
    }

    private String getPaymentCodeFromPrefixes(String numeroTarjeta) {
        if (StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        Object service = findPrefijosTarjetasService();

        if (service == null) {
            return null;
        }

        try {
            Method method = service.getClass().getMethod("getMedioPagoPrefijo", String.class);
            Object result = method.invoke(service, numeroTarjeta);

            return result != null ? result.toString() : null;
        }
        catch (NoSuchMethodException e) {
            LOG.debug("getPaymentCodeFromPrefixes() - getMedioPagoPrefijo method not available");
            return null;
        }
        catch (Exception e) {
            LOG.error("getPaymentCodeFromPrefixes() - Error invoking getMedioPagoPrefijo: " + e.getMessage(), e);
            return null;
        }
    }

    private Object findPrefijosTarjetasService() {
        for (String beanName : PREFIJOS_BEAN_NAMES) {
            try {
                Object bean = ContextHolder.getBean(beanName);

                if (bean != null) {
                    return bean;
                }
            }
            catch (Exception e) {
                LOG.debug(String.format("findPrefijosTarjetasService() - Bean %s not found", beanName));
            }
        }

        if (ContextHolder.get() == null) {
            return null;
        }

        for (String className : PREFIJOS_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                Object bean = ContextHolder.get().getBean(clazz);

                if (bean != null) {
                    return bean;
                }
            }
            catch (ClassNotFoundException e) {
                LOG.debug(String.format("findPrefijosTarjetasService() - Class %s not found", className));
            }
            catch (Exception e) {
                LOG.debug(String.format("findPrefijosTarjetasService() - Unable to obtain bean for %s", className), e);
            }
        }

        return null;
    }

    private String findGiftCardPaymentCode() {
        Map<String, String> mapping = ncrController.getConfiguration().getPaymentsCodesMapping();

        if (mapping == null || mapping.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (StringUtils.isBlank(key) || StringUtils.isBlank(value)) {
                continue;
            }

            String normalizedKey = key.replace('_', ' ').replace('-', ' ');

            if (StringUtils.equalsIgnoreCase(key, "GiftCard")
                    || StringUtils.equalsIgnoreCase(key, "Gift_Card")
                    || StringUtils.equalsIgnoreCase(key, "Gift Card")
                    || (StringUtils.containsIgnoreCase(normalizedKey, "gift")
                            && StringUtils.containsIgnoreCase(normalizedKey, "card"))
                    || (StringUtils.containsIgnoreCase(normalizedKey, "tarjeta")
                            && StringUtils.containsIgnoreCase(normalizedKey, "regalo"))) {
                return value;
            }
        }

        return null;
    }

    private boolean processGiftCardTender(Tender message, PaymentsManager paymentsManager,
            AmetllerGiftCardManager giftCardManager, String paymentMethodCode, String numeroTarjeta, BigDecimal amount) {
        if (StringUtils.isBlank(numeroTarjeta)) {
            sendTenderException(message.getFieldValue(Tender.TenderType),
                    I18N.getTexto("Debe indicar el número de la tarjeta"));
            return true;
        }

        try {
            GiftCardBean giftCard = giftCardManager.consultarSaldo(numeroTarjeta);

            if (giftCard == null) {
                sendTenderException(message.getFieldValue(Tender.TenderType),
                        I18N.getTexto("No se ha podido obtener el saldo de la tarjeta"));
                return true;
            }

            BigDecimal saldo = giftCard.getSaldo() != null ? giftCard.getSaldo() : BigDecimal.ZERO;
            BigDecimal amountToPay = amount;
            BigDecimal pendiente = getPendingAmount();

            if (pendiente != null && BigDecimalUtil.isMayor(amountToPay, pendiente)) {
                amountToPay = pendiente;
            }

            if (BigDecimalUtil.isMayor(amountToPay, saldo)) {
                amountToPay = saldo;
            }

            if (!BigDecimalUtil.isMayorACero(amountToPay)) {
                sendTenderException(message.getFieldValue(Tender.TenderType),
                        I18N.getTexto("La tarjeta no tiene saldo suficiente"));
                return true;
            }

            amountToPay = amountToPay.setScale(2, RoundingMode.HALF_UP);

            if (amountToPay.compareTo(amount) != 0) {
                message.setFieldIntValue(Tender.Amount, amountToPay);
            }

            giftCard.setNumTarjetaRegalo(numeroTarjeta);

            if (giftCard.getSaldoProvisional() == null) {
                giftCard.setSaldoProvisional(BigDecimal.ZERO);
            }

            if (giftCard.getSaldo() == null) {
                giftCard.setSaldo(BigDecimal.ZERO);
            }

            ((BasicPaymentMethodManager) giftCardManager).addParameter(AmetllerGiftCardManager.PARAM_TARJETA, giftCard);

            paymentsManager.pay(paymentMethodCode, amountToPay);
        }
        catch (Exception e) {
            handlePaymentException(paymentsManager, e);
        }

        return true;
    }

    private BigDecimal getPendingAmount() {
        if (ticketManager.getTicket() == null || ticketManager.getTicket().getTotales() == null) {
            return null;
        }

        return ticketManager.getTicket().getTotales().getPendiente();
    }

    private PaymentMethodManager findPaymentMethodManager(PaymentsManager paymentsManager, String paymentMethodCode) {
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
        }
        catch (NoSuchMethodException e) {
            LOG.debug("findPaymentMethodManager() - getPaymentMethodManager method not available");
        }
        catch (Exception e) {
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
            }
            catch (NoSuchMethodException e) {
                LOG.debug(String.format("getAvailablePaymentManagers() - Method %s not found", methodName));
            }
            catch (Exception e) {
                LOG.error(String.format("getAvailablePaymentManagers() - Error invoking %s: %s", methodName,
                        e.getMessage()), e);
            }
        }

        return Collections.emptyMap();
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
        }
        else {
            PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, -1, e, null, null);
            PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
            paymentsManager.getEventsHandler().paymentsError(event);
        }
    }
}
