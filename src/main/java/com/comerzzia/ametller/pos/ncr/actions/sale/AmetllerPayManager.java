package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.context.ApplicationContext;
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
            Object giftCardData = null;

            if (paymentRequest.giftCard) {
                ncrController.sendWaitState("Validando tarjeta...");
                waitStateSent = true;

                try {
                    giftCardData = configureGiftCardManager(paymentRequest);

                    BigDecimal balance = null;

                    Object restGiftCard = fetchGiftCardBeanFromFidelizados(paymentRequest.numeroTarjeta);

                    if (restGiftCard != null) {
                        balance = applyGiftCardRestData(giftCardData, restGiftCard);

                        if (LOG.isDebugEnabled()) {
                            LOG.debug(String.format(
                                    "executePayment() - Obtained gift card balance from REST service: %s",
                                    balance));
                        }
                    }

                    if (balance == null) {
                        balance = extractBalanceFromGiftCardBean(giftCardData);
                    }

                    if (balance == null) {
                        LOG.debug(
                                "executePayment() - Gift card balance not available from REST, falling back to legacy consultation");

                        BigDecimal consultedBalance = consultGiftCardBalance(paymentRequest.paymentMethodManager,
                                paymentRequest.numeroTarjeta);

                        if (consultedBalance != null) {
                            balance = consultedBalance;

                            if (giftCardData != null) {
                                ensureGiftCardBalanceInitialized(giftCardData, balance, BigDecimal.ZERO);
                                invokeSetter(giftCardData, "setSaldoTotal", BigDecimal.class, balance);
                            }
                        }
                    }

                    if (giftCardData != null && balance != null) {
                        invokeSetter(giftCardData, "setSaldoTotal", BigDecimal.class, balance);
                        updateGiftCardBeanBalance(giftCardData, balance);
                    }

                    if (balance != null && balance.compareTo(BigDecimal.ZERO) > 0
                            && amountToPay.compareTo(balance) > 0) {
                        LOG.info(String.format(
                                "executePayment() - Gift card balance %s lower than requested amount %s. Adjusting tender amount.",
                                balance, amountToPay));
                        amountToPay = balance;
                    }
                } catch (Exception e) {
                    LOG.error("executePayment() - Error preparing gift card payment: " + e.getMessage(), e);
                } finally {
                    paymentRequest.amount = amountToPay;
                    updateGiftCardBeanAmount(giftCardData, amountToPay);
                }
            }

            paymentRequest.amount = amountToPay;

            if (paymentRequest.tenderMessage != null && amountToPay != null) {
                paymentRequest.tenderMessage.setFieldIntValue(Tender.Amount, amountToPay);
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

    private Object configureGiftCardManager(PendingPaymentRequest paymentRequest) {
        PaymentMethodManager paymentMethodManager = paymentRequest.paymentMethodManager;
        String numeroTarjeta = paymentRequest.numeroTarjeta;

        if (paymentMethodManager == null || StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        String beanParameterName = resolveGiftCardBeanParameterName(paymentMethodManager);
        String numberParameterName = resolveCardNumberParameterName(paymentMethodManager);

        if (StringUtils.isBlank(numberParameterName) && StringUtils.isBlank(beanParameterName)) {
            numberParameterName = DEFAULT_GIFT_CARD_PARAMETER;
        }

        if (StringUtils.isNotBlank(numberParameterName)
                && !StringUtils.equals(numberParameterName, beanParameterName)) {
            paymentMethodManager.addParameter(numberParameterName, numeroTarjeta);
        }

        Object giftCardBean = obtainGiftCardBean(paymentMethodManager, numeroTarjeta, paymentRequest.amount);

        if (giftCardBean == null) {
            giftCardBean = findGiftCardBean(numeroTarjeta, paymentRequest.amount);
        }

        if (giftCardBean != null) {
            String targetParameterName = StringUtils.isNotBlank(beanParameterName) ? beanParameterName : numberParameterName;

            if (StringUtils.isBlank(targetParameterName)) {
                targetParameterName = DEFAULT_GIFT_CARD_PARAMETER;
            }

            paymentMethodManager.addParameter(targetParameterName, giftCardBean);
        } else if (StringUtils.isNotBlank(beanParameterName)
                && !StringUtils.equals(beanParameterName, numberParameterName)) {
            paymentMethodManager.addParameter(beanParameterName, numeroTarjeta);
        }

        return giftCardBean;
    }

    private String resolveCardNumberParameterName(PaymentMethodManager paymentMethodManager) {
        return resolveParameterName(paymentMethodManager.getClass(), Arrays.asList("PARAM_NUMERO_TARJETA", "PARAM_CARD_NUMBER",
                "PARAM_NUM_TARJETA"));
    }

    private String resolveGiftCardBeanParameterName(PaymentMethodManager paymentMethodManager) {
        return resolveParameterName(paymentMethodManager.getClass(), Arrays.asList("PARAM_TARJETA", "PARAM_TARJETA_REGALO",
                "PARAM_GIFT_CARD", "PARAM_GIFTCARD"));
    }

    private String resolveParameterName(Class<?> clazz, List<String> fieldNames) {
        for (String fieldName : fieldNames) {
            String value = findStaticStringField(clazz, fieldName);

            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }

        return null;
    }

    private Object createGiftCardBean(String numeroTarjeta, BigDecimal amount) {
        try {
            Class<?> beanClass = Class.forName("com.comerzzia.pos.persistence.giftcard.GiftCardBean");
            Object bean = beanClass.getDeclaredConstructor().newInstance();

            invokeSetter(bean, "setNumTarjetaRegalo", String.class, numeroTarjeta);
            invokeSetter(bean, "setNumeroTarjeta", String.class, numeroTarjeta);
            invokeSetter(bean, "setImportePago", BigDecimal.class, amount);
            invokeSetter(bean, "setImporte", BigDecimal.class, amount);
            ensureGiftCardBalanceInitialized(bean, BigDecimal.ZERO, BigDecimal.ZERO);

            return bean;
        } catch (ClassNotFoundException e) {
            LOG.debug("createGiftCardBean() - GiftCardBean class not available");
        } catch (Exception e) {
            LOG.error("createGiftCardBean() - Error creating GiftCardBean: " + e.getMessage(), e);
        }

        return null;
    }

    private void updateGiftCardBeanAmount(Object giftCardBean, BigDecimal amount) {
        if (giftCardBean == null || amount == null) {
            return;
        }

        invokeSetter(giftCardBean, "setImportePago", BigDecimal.class, amount);
        invokeSetter(giftCardBean, "setImporte", BigDecimal.class, amount);
    }

    private Object obtainGiftCardBean(PaymentMethodManager paymentMethodManager, String numeroTarjeta, BigDecimal amount) {
        Object bean = loadGiftCardBean(paymentMethodManager, numeroTarjeta);

        if (bean == null) {
            bean = createGiftCardBean(numeroTarjeta, amount);
        } else {
            invokeSetter(bean, "setNumTarjetaRegalo", String.class, numeroTarjeta);
            invokeSetter(bean, "setNumeroTarjeta", String.class, numeroTarjeta);
            updateGiftCardBeanAmount(bean, amount);
        }

        ensureGiftCardBalanceDefaults(bean);

        return bean;
    }

    private Object findGiftCardBean(String numeroTarjeta, BigDecimal amount) {
        Object bean = adaptGiftCardResult(lookupGiftCardBeanInContext(numeroTarjeta), numeroTarjeta, amount);

        if (bean == null) {
            bean = adaptGiftCardResult(lookupGiftCardBeanFromRest(numeroTarjeta), numeroTarjeta, amount);
        }

        if (bean == null) {
            bean = createGiftCardBean(numeroTarjeta, amount);
        }

        if (bean != null) {
            ensureGiftCardBalanceDefaults(bean);
            updateGiftCardBeanAmount(bean, amount);
        }

        return bean;
    }

    private Object lookupGiftCardBeanInContext(String numeroTarjeta) {
        ApplicationContext context = ContextHolder.get();

        if (context == null) {
            LOG.debug("lookupGiftCardBeanInContext() - Spring context not available");
            return null;
        }

        Set<String> inspected = new LinkedHashSet<>();

        try {
            String[] beanNames = context.getBeanDefinitionNames();

            for (String beanName : beanNames) {
                if (!StringUtils.containsIgnoreCase(beanName, "gift")
                        && !StringUtils.containsIgnoreCase(beanName, "balance")) {
                    continue;
                }

                Object candidate = context.getBean(beanName);
                Object result = invokeGiftCardLookup(candidate, numeroTarjeta);

                if (result != null) {
                    return result;
                }

                if (candidate != null) {
                    inspected.add(candidate.getClass().getName());
                }
            }

            for (String beanName : beanNames) {
                Object candidate = context.getBean(beanName);

                if (candidate == null) {
                    continue;
                }

                String className = candidate.getClass().getName();

                if (inspected.contains(className)) {
                    continue;
                }

                if (!StringUtils.containsIgnoreCase(className, "giftcard")
                        && !StringUtils.containsIgnoreCase(className, "balancecard")
                        && !StringUtils.containsIgnoreCase(className, "virtualmoney")) {
                    continue;
                }

                Object result = invokeGiftCardLookup(candidate, numeroTarjeta);

                if (result != null) {
                    return result;
                }
            }
        } catch (Exception e) {
            LOG.debug("lookupGiftCardBeanInContext() - Error resolving bean: " + e.getMessage(), e);
        }

        return null;
    }

    private Object lookupGiftCardBeanFromRest(String numeroTarjeta) {
        if (StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        try {
            Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.movimientos.MovimientosRest");
            Set<String> methodNames = new LinkedHashSet<>(Arrays.asList("consultarSaldoTarjetaRegalo",
                    "consultarTarjetaRegalo", "consultarSaldoTarjeta"));

            for (Method method : restClass.getMethods()) {
                if (!methodNames.contains(method.getName()) || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }

                Object[] arguments = buildRestArguments(method.getParameterTypes(), numeroTarjeta);

                if (arguments == null) {
                    continue;
                }

                try {
                    return method.invoke(null, arguments);
                } catch (Exception e) {
                    LOG.debug(String.format("lookupGiftCardBeanFromRest() - Unable to invoke %s: %s", method.getName(),
                            e.getMessage()), e);
                }
            }
        } catch (ClassNotFoundException e) {
            LOG.debug("lookupGiftCardBeanFromRest() - MovimientosRest class not available");
        }

        return null;
    }

    private Object fetchGiftCardBeanFromFidelizados(String numeroTarjeta) {
        if (StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        try {
            Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.FidelizadosRest");

            for (Method method : restClass.getMethods()) {
                if (!StringUtils.equals(method.getName(), "getTarjetaRegalo")
                        || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }

                Object[] arguments = buildRestArguments(method.getParameterTypes(), numeroTarjeta);

                if (arguments == null) {
                    LOG.debug("fetchGiftCardBeanFromFidelizados() - Unsupported getTarjetaRegalo signature");
                    continue;
                }

                try {
                    return method.invoke(null, arguments);
                } catch (Exception e) {
                    LOG.error("fetchGiftCardBeanFromFidelizados() - Error invoking getTarjetaRegalo: "
                            + e.getMessage(), e);
                }
            }

            LOG.debug("fetchGiftCardBeanFromFidelizados() - getTarjetaRegalo method not available");
        } catch (ClassNotFoundException e) {
            LOG.debug("fetchGiftCardBeanFromFidelizados() - FidelizadosRest class not available");
        }

        return null;
    }

    private Object adaptGiftCardResult(Object result, String numeroTarjeta, BigDecimal amount) {
        if (result == null) {
            return null;
        }

        if (isGiftCardBean(result)) {
            populateGiftCardIdentifiers(result, numeroTarjeta);
            updateGiftCardBeanAmount(result, amount);
            ensureGiftCardBalanceDefaults(result);
            return result;
        }

        Object bean = createGiftCardBean(numeroTarjeta, amount);

        if (bean == null) {
            return null;
        }

        if (result instanceof BigDecimal) {
            updateGiftCardBeanBalance(bean, (BigDecimal) result);
            return bean;
        }

        if (result instanceof Map) {
            BigDecimal saldo = extractBigDecimalFromMap(result, "saldo", "balance");
            BigDecimal saldoProvisional = extractBigDecimalFromMap(result, "saldoProvisional", "provisionalBalance");

            if (saldo != null || saldoProvisional != null) {
                ensureGiftCardBalanceInitialized(bean, saldo, saldoProvisional);
                return bean;
            }

            BigDecimal saldoTotal = extractBigDecimalFromMap(result, "saldoTotal", "totalBalance");

            if (saldoTotal != null) {
                updateGiftCardBeanBalance(bean, saldoTotal);
                return bean;
            }
        }

        BigDecimal saldo = extractBigDecimal(result, "getSaldo");
        BigDecimal saldoProvisional = extractBigDecimal(result, "getSaldoProvisional");
        BigDecimal saldoTotal = extractBigDecimal(result, "getSaldoTotal");

        if (saldo != null || saldoProvisional != null) {
            ensureGiftCardBalanceInitialized(bean, saldo, saldoProvisional);
            return bean;
        }

        if (saldoTotal != null) {
            updateGiftCardBeanBalance(bean, saldoTotal);
            return bean;
        }

        BigDecimal balance = extractBigDecimal(result, "getBalance");

        if (balance != null) {
            updateGiftCardBeanBalance(bean, balance);
            return bean;
        }

        return null;
    }

    private BigDecimal applyGiftCardRestData(Object giftCardBean, Object restGiftCard) {
        if (restGiftCard == null) {
            return null;
        }

        BigDecimal saldo = extractBigDecimal(restGiftCard, "getSaldo");
        BigDecimal saldoProvisional = extractBigDecimal(restGiftCard, "getSaldoProvisional");
        BigDecimal saldoTotal = extractBigDecimal(restGiftCard, "getSaldoTotal");

        if (giftCardBean != null) {
            if (saldo != null) {
                invokeSetter(giftCardBean, "setSaldo", BigDecimal.class, saldo);
            }

            if (saldoProvisional != null) {
                invokeSetter(giftCardBean, "setSaldoProvisional", BigDecimal.class, saldoProvisional);
            }

            if (saldo != null || saldoProvisional != null) {
                ensureGiftCardBalanceInitialized(giftCardBean, saldo, saldoProvisional);
            }

            BigDecimal totalToApply = saldoTotal;

            if (totalToApply == null && (saldo != null || saldoProvisional != null)) {
                BigDecimal saldoValue = saldo != null ? saldo : BigDecimal.ZERO;
                BigDecimal provisionalValue = saldoProvisional != null ? saldoProvisional : BigDecimal.ZERO;
                totalToApply = saldoValue.add(provisionalValue);
            }

            if (totalToApply != null) {
                invokeSetter(giftCardBean, "setSaldoTotal", BigDecimal.class, totalToApply);

                if (saldo == null && saldoProvisional == null) {
                    ensureGiftCardBalanceInitialized(giftCardBean, totalToApply, BigDecimal.ZERO);
                }
            }
        }

        if (saldoTotal != null) {
            return saldoTotal;
        }

        if (saldo == null && saldoProvisional == null) {
            return null;
        }

        BigDecimal saldoValue = saldo != null ? saldo : BigDecimal.ZERO;
        BigDecimal provisionalValue = saldoProvisional != null ? saldoProvisional : BigDecimal.ZERO;

        return saldoValue.add(provisionalValue);
    }

    private Object invokeGiftCardLookup(Object candidate, String numeroTarjeta) {
        if (candidate == null || StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        List<String> methodNames = Arrays.asList("consultarSaldo", "consultarTarjeta", "consultarGiftCard",
                "obtenerTarjeta", "obtenerGiftCard", "getGiftCard", "getTarjetaRegalo");

        for (String methodName : methodNames) {
            Method method = findMethod(candidate.getClass(), methodName, String.class);

            if (method == null) {
                continue;
            }

            try {
                return method.invoke(candidate, numeroTarjeta);
            } catch (Exception e) {
                LOG.debug(String.format("invokeGiftCardLookup() - Unable to invoke %s on %s: %s", methodName,
                        candidate.getClass().getName(), e.getMessage()));
            }
        }

        return null;
    }

    private Object[] buildRestArguments(Class<?>[] parameterTypes, String numeroTarjeta) {
        if (parameterTypes.length == 1 && String.class.equals(parameterTypes[0])) {
            return new Object[] { numeroTarjeta };
        }

        if (parameterTypes.length == 2 && String.class.equals(parameterTypes[0])
                && String.class.equals(parameterTypes[1])) {
            String uidActividad = resolveUidActividad();

            if (StringUtils.isBlank(uidActividad)) {
                return null;
            }

            return new Object[] { uidActividad, numeroTarjeta };
        }

        return null;
    }

    private Object findBeanByType(ApplicationContext context, String className) {
        if (context == null || StringUtils.isBlank(className)) {
            return null;
        }

        try {
            Class<?> type = Class.forName(className);
            return context.getBean(type);
        } catch (ClassNotFoundException e) {
            LOG.debug("findBeanByType() - Class not available: " + className);
        } catch (Exception e) {
            LOG.debug("findBeanByType() - Unable to obtain bean for " + className + ": " + e.getMessage(), e);
        }

        return null;
    }

    private String resolveUidActividad() {
        ApplicationContext context = ContextHolder.get();

        if (context == null) {
            return null;
        }

        try {
            Object sesion;

            try {
                sesion = context.getBean("sesion");
            } catch (Exception e) {
                sesion = findBeanByType(context, "com.comerzzia.pos.services.core.sesion.Sesion");
            }

            if (sesion == null) {
                return null;
            }

            Method getAplicacion = findMethod(sesion.getClass(), "getAplicacion");

            if (getAplicacion == null) {
                return null;
            }

            Object aplicacion = getAplicacion.invoke(sesion);

            if (aplicacion == null) {
                return null;
            }

            Method getUidActividad = findMethod(aplicacion.getClass(), "getUidActividad");

            if (getUidActividad == null) {
                return null;
            }

            Object uid = getUidActividad.invoke(aplicacion);

            return uid != null ? uid.toString() : null;
        } catch (Exception e) {
            LOG.debug("resolveUidActividad() - Unable to resolve UID: " + e.getMessage(), e);
            return null;
        }
    }

    private void populateGiftCardIdentifiers(Object giftCardBean, String numeroTarjeta) {
        invokeSetter(giftCardBean, "setNumTarjetaRegalo", String.class, numeroTarjeta);
        invokeSetter(giftCardBean, "setNumeroTarjeta", String.class, numeroTarjeta);
    }

    private Object loadGiftCardBean(PaymentMethodManager paymentMethodManager, String numeroTarjeta) {
        if (paymentMethodManager == null || StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        Method consultarSaldo = findMethod(paymentMethodManager.getClass(), "consultarSaldo", String.class);

        if (consultarSaldo == null) {
            return null;
        }

        try {
            Object result = consultarSaldo.invoke(paymentMethodManager, numeroTarjeta);

            if (result == null) {
                return null;
            }

            if (isGiftCardBean(result)) {
                return result;
            }

            if (result instanceof BigDecimal) {
                Object bean = createGiftCardBean(numeroTarjeta, (BigDecimal) result);
                updateGiftCardBeanBalance(bean, (BigDecimal) result);
                return bean;
            }

            BigDecimal saldo = extractBigDecimal(result, "getSaldo");
            BigDecimal saldoProvisional = extractBigDecimal(result, "getSaldoProvisional");

            if (saldo != null || saldoProvisional != null) {
                Object bean = createGiftCardBean(numeroTarjeta, null);
                ensureGiftCardBalanceInitialized(bean, saldo, saldoProvisional);
                return bean;
            }
        } catch (Exception e) {
            LOG.error("loadGiftCardBean() - Error invoking consultarSaldo: " + e.getMessage(), e);
        }

        return null;
    }

    private boolean isGiftCardBean(Object candidate) {
        if (candidate == null) {
            return false;
        }

        try {
            Class<?> giftCardClass = Class.forName("com.comerzzia.pos.persistence.giftcard.GiftCardBean");

            return giftCardClass.isInstance(candidate);
        } catch (ClassNotFoundException e) {
            return StringUtils.containsIgnoreCase(candidate.getClass().getName(), "GiftCardBean");
        }
    }

    private BigDecimal extractBigDecimal(Object source, String getterName) {
        if (source == null) {
            return null;
        }

        Method getter = findMethod(source.getClass(), getterName);

        if (getter == null) {
            return null;
        }

        try {
            Object value = getter.invoke(source);

            return toBigDecimal(value);
        } catch (NumberFormatException e) {
            LOG.debug(String.format("extractBigDecimal() - Value returned by %s is not numeric", getterName));
        } catch (Exception e) {
            LOG.debug(String.format("extractBigDecimal() - Unable to invoke %s on %s", getterName, source.getClass().getName()), e);
        }

        return null;
    }

    private BigDecimal extractBigDecimalFromMap(Object source, String... keys) {
        if (!(source instanceof Map)) {
            return null;
        }

        Map<?, ?> map = (Map<?, ?>) source;

        if (keys == null) {
            return null;
        }

        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }

            BigDecimal value = toBigDecimal(map.get(key));

            if (value != null) {
                return value;
            }
        }

        return null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }

        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            LOG.debug("toBigDecimal() - Value is not numeric: " + value);
            return null;
        }
    }

    private void ensureGiftCardBalanceDefaults(Object giftCardBean) {
        if (giftCardBean == null) {
            return;
        }

        BigDecimal saldo = extractBigDecimal(giftCardBean, "getSaldo");
        BigDecimal saldoProvisional = extractBigDecimal(giftCardBean, "getSaldoProvisional");

        if (saldo == null || saldoProvisional == null) {
            ensureGiftCardBalanceInitialized(giftCardBean, saldo, saldoProvisional);
        }
    }

    private void ensureGiftCardBalanceInitialized(Object giftCardBean, BigDecimal saldo, BigDecimal saldoProvisional) {
        if (giftCardBean == null) {
            return;
        }

        BigDecimal saldoValue = saldo != null ? saldo : BigDecimal.ZERO;
        BigDecimal saldoProvisionalValue = saldoProvisional != null ? saldoProvisional : BigDecimal.ZERO;

        invokeSetter(giftCardBean, "setSaldo", BigDecimal.class, saldoValue);
        invokeSetter(giftCardBean, "setSaldoProvisional", BigDecimal.class, saldoProvisionalValue);
    }

    private void updateGiftCardBeanBalance(Object giftCardBean, BigDecimal balance) {
        if (giftCardBean == null || balance == null) {
            return;
        }

        BigDecimal currentSaldo = extractBigDecimal(giftCardBean, "getSaldo");
        BigDecimal currentSaldoProvisional = extractBigDecimal(giftCardBean, "getSaldoProvisional");

        if (currentSaldo == null && currentSaldoProvisional == null) {
            ensureGiftCardBalanceInitialized(giftCardBean, balance, BigDecimal.ZERO);
            return;
        }

        BigDecimal total = (currentSaldo != null ? currentSaldo : BigDecimal.ZERO)
                .add(currentSaldoProvisional != null ? currentSaldoProvisional : BigDecimal.ZERO);

        if (total.compareTo(balance) != 0) {
            invokeSetter(giftCardBean, "setSaldo", BigDecimal.class, balance);
            invokeSetter(giftCardBean, "setSaldoProvisional", BigDecimal.class, BigDecimal.ZERO);
        }
    }

    private BigDecimal extractBalanceFromGiftCardBean(Object giftCardBean) {
        if (giftCardBean == null) {
            return null;
        }

        BigDecimal saldoTotal = extractBigDecimal(giftCardBean, "getSaldoTotal");

        if (saldoTotal != null) {
            return saldoTotal;
        }

        BigDecimal saldo = extractBigDecimal(giftCardBean, "getSaldo");
        BigDecimal saldoProvisional = extractBigDecimal(giftCardBean, "getSaldoProvisional");

        if (saldo == null && saldoProvisional == null) {
            return null;
        }

        BigDecimal saldoValue = saldo != null ? saldo : BigDecimal.ZERO;
        BigDecimal saldoProvisionalValue = saldoProvisional != null ? saldoProvisional : BigDecimal.ZERO;

        return saldoValue.add(saldoProvisionalValue);
    }

    private void invokeSetter(Object target, String methodName, Class<?> parameterType, Object value) {
        if (target == null || value == null) {
            return;
        }

        Method method = findMethod(target.getClass(), methodName, parameterType);

        if (method == null) {
            return;
        }

        try {
            method.invoke(target, value);
        } catch (Exception e) {
            LOG.debug(String.format("invokeSetter() - Unable to invoke %s on %s", methodName, target.getClass().getName()), e);
        }
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

    private Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?> current = clazz;

        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
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

