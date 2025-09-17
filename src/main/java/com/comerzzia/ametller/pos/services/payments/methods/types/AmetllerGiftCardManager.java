package com.comerzzia.ametller.pos.services.payments.methods.types;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.comerzzia.api.rest.client.exceptions.RestException;
import com.comerzzia.api.rest.client.exceptions.RestHttpException;
import com.comerzzia.api.rest.client.movimientos.ListaMovimientoRequestRest;
import com.comerzzia.api.rest.client.movimientos.MovimientoRequestRest;
import com.comerzzia.api.rest.client.movimientos.MovimientosRest;
import com.comerzzia.core.servicios.variables.Variables;
import com.comerzzia.core.util.numeros.BigDecimalUtil;
import com.comerzzia.pos.persistence.giftcard.GiftCardBean;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.payments.PaymentDto;
import com.comerzzia.pos.services.payments.PaymentException;
import com.comerzzia.pos.services.payments.configuration.PaymentMethodConfiguration;
import com.comerzzia.pos.services.payments.events.PaymentErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentInitEvent;
import com.comerzzia.pos.services.payments.events.PaymentOkEvent;
import com.comerzzia.pos.services.payments.methods.types.BasicPaymentMethodManager;
import com.comerzzia.pos.util.i18n.I18N;

@Component
@Scope("prototype")
public class AmetllerGiftCardManager extends BasicPaymentMethodManager {

    public static final String PARAM_TARJETA = "PARAM_TARJETA";

    private static final Logger LOG = Logger.getLogger(AmetllerGiftCardManager.class);
    private static final Set<String> CONSULT_METHOD_NAMES = new LinkedHashSet<>(
            Arrays.asList("consultarSaldoTarjetaRegalo", "consultarTarjetaRegalo", "consultarSaldoTarjeta"));

    @Autowired
    private Sesion sesion;

    @Autowired
    private VariablesServices variablesServices;

    @Override
    public void setConfiguration(PaymentMethodConfiguration configuration) {
        super.setConfiguration(configuration);
        LOG.debug("setConfiguration() - Cargando configuración para el medio de pago: " + paymentCode);
    }

    @Override
    public boolean pay(BigDecimal amount) throws PaymentException {
        try {
            PaymentInitEvent initEvent = new PaymentInitEvent(this);
            getEventHandler().paymentInitProcess(initEvent);

            GiftCardBean giftCard = (GiftCardBean) parameters.get(PARAM_TARJETA);

            if (giftCard == null) {
                throw new PaymentException(I18N.getTexto("No se ha informado ninguna tarjeta regalo."));
            }

            ensureGiftCardBalanceDefaults(giftCard);

            if (BigDecimalUtil.isMayor(amount, ticket.getTotales().getPendiente())) {
                throw new PaymentException(I18N.getTexto("El importe indicado supera el importe pendiente de la venta."));
            }

            if (BigDecimalUtil.isMayor(amount, giftCard.getSaldo())) {
                throw new PaymentException(I18N.getTexto("El importe indicado supera el saldo de la tarjeta."));
            }

            String uidTransaccion = UUID.randomUUID().toString();

            String numeroTarjeta = resolveNumeroTarjeta(giftCard);

            ListaMovimientoRequestRest request = createProvisionalMovements(amount, giftCard, true, uidTransaccion);
            MovimientosRest.crearMovimientosTarjetaRegaloProvisionales(request);

            PaymentOkEvent event = new PaymentOkEvent(this, paymentId, amount);

            giftCard.setUidTransaccion(uidTransaccion);
            event.addExtendedData(PARAM_TARJETA, giftCard);
            if (StringUtils.isNotBlank(numeroTarjeta)) {
                event.addExtendedData("numeroTarjeta", numeroTarjeta);
            }

            getEventHandler().paymentOk(event);

            return true;
        }
        catch (Exception e) {
            LOG.error("pay() - Ha habido un error al realizar el pago con tarjeta regalo: " + e.getMessage(), e);

            PaymentErrorEvent event = new PaymentErrorEvent(this, paymentId, e, null, e.getMessage());
            getEventHandler().paymentError(event);

            return false;
        }
    }

    protected ListaMovimientoRequestRest createProvisionalMovements(BigDecimal amount, GiftCardBean giftCard, boolean isPay,
            String uidTransaccion) throws RestException, RestHttpException {
        List<MovimientoRequestRest> movimientos = new ArrayList<>();

        MovimientoRequestRest movimiento = new MovimientoRequestRest();
        movimiento.setUidActividad(sesion.getAplicacion().getUidActividad());
        movimiento.setNumeroTarjeta(resolveNumeroTarjeta(giftCard));
        movimiento.setConcepto(ticket.getCabecera().getDesTipoDocumento() + " " + ticket.getCabecera().getCodTicket());
        movimiento.setUidTransaccion(uidTransaccion);
        movimiento.setFecha(new Date());
        movimiento.setDocumento(String.valueOf(ticket.getIdTicket()));
        movimiento.setApiKey(variablesServices.getVariableAsString(Variables.WEBSERVICES_APIKEY));
        movimiento.setSaldo(safeBigDecimal(giftCard.getSaldo()).doubleValue());
        movimiento.setSaldoProvisional(safeBigDecimal(giftCard.getSaldoProvisional()).doubleValue());

        if (isPay) {
            movimiento.setSalida(amount.doubleValue());
            movimiento.setEntrada(0.0);
        }
        else {
            movimiento.setSalida(0.0);
            movimiento.setEntrada(amount.doubleValue());
        }

        movimientos.add(movimiento);

        ListaMovimientoRequestRest request = new ListaMovimientoRequestRest();
        request.setMovimientos(movimientos);

        return request;
    }

    @Override
    public boolean returnAmount(BigDecimal amount) throws PaymentException {
        try {
            PaymentInitEvent initEvent = new PaymentInitEvent(this);
            getEventHandler().paymentInitProcess(initEvent);

            GiftCardBean giftCard = (GiftCardBean) parameters.get(PARAM_TARJETA);

            if (giftCard == null) {
                throw new PaymentException(I18N.getTexto("No se ha informado ninguna tarjeta regalo."));
            }

            ensureGiftCardBalanceDefaults(giftCard);

            String uidTransaccion = UUID.randomUUID().toString();

            String numeroTarjeta = resolveNumeroTarjeta(giftCard);

            ListaMovimientoRequestRest request = createProvisionalMovements(amount, giftCard, false, uidTransaccion);
            MovimientosRest.crearMovimientosTarjetaRegaloProvisionales(request);

            PaymentOkEvent event = new PaymentOkEvent(this, paymentId, amount);

            giftCard.setUidTransaccion(uidTransaccion);
            event.addExtendedData(PARAM_TARJETA, giftCard);
            if (StringUtils.isNotBlank(numeroTarjeta)) {
                event.addExtendedData("numeroTarjeta", numeroTarjeta);
            }

            getEventHandler().paymentOk(event);

            return true;
        }
        catch (Exception e) {
            LOG.error("returnAmount() - Ha habido un error al realizar la devolución con tarjeta regalo: " + e.getMessage(),
                    e);

            PaymentErrorEvent event = new PaymentErrorEvent(this, paymentId, e, null, e.getMessage());
            getEventHandler().paymentError(event);

            return false;
        }
    }

    @Override
    public boolean cancelPay(PaymentDto paymentDto) throws PaymentException {
        try {
            GiftCardBean giftCard = (GiftCardBean) paymentDto.getExtendedData().get(PARAM_TARJETA);

            if (giftCard == null) {
                throw new PaymentException(I18N.getTexto("No se ha podido recuperar la tarjeta regalo asociada al pago."));
            }

            String uidTransaccion = giftCard.getUidTransaccion();

            ListaMovimientoRequestRest request = createProvisionalMovements(paymentDto.getAmount(), giftCard, true,
                    uidTransaccion);
            MovimientosRest.anularMovimientosProvisionalesTarjetaRegalo(request);

            PaymentOkEvent event = new PaymentOkEvent(this, paymentDto.getPaymentId(), paymentDto.getAmount());
            event.setCanceled(true);
            getEventHandler().paymentOk(event);

            return true;
        }
        catch (Exception e) {
            LOG.error("cancelPay() - Ha habido un error al cancelar el pago con tarjeta regalo: " + e.getMessage(), e);

            PaymentErrorEvent event = new PaymentErrorEvent(this, paymentId, e, null, e.getMessage());
            getEventHandler().paymentError(event);

            return false;
        }
    }

    @Override
    public boolean cancelReturn(PaymentDto paymentDto) throws PaymentException {
        try {
            GiftCardBean giftCard = (GiftCardBean) paymentDto.getExtendedData().get(PARAM_TARJETA);

            if (giftCard == null) {
                throw new PaymentException(I18N.getTexto("No se ha podido recuperar la tarjeta regalo asociada al pago."));
            }

            String uidTransaccion = giftCard.getUidTransaccion();

            ListaMovimientoRequestRest request = createProvisionalMovements(paymentDto.getAmount(), giftCard, true,
                    uidTransaccion);
            MovimientosRest.anularMovimientosProvisionalesTarjetaRegalo(request);

            PaymentOkEvent event = new PaymentOkEvent(this, paymentDto.getPaymentId(), paymentDto.getAmount());
            event.setCanceled(true);
            getEventHandler().paymentOk(event);

            return true;
        }
        catch (Exception e) {
            LOG.error("cancelReturn() - Ha habido un error al cancelar la devolución con tarjeta regalo: " + e.getMessage(),
                    e);

            PaymentErrorEvent event = new PaymentErrorEvent(this, paymentId, e, null, e.getMessage());
            getEventHandler().paymentError(event);

            return false;
        }
    }

    @Override
    public boolean isAvailableForExchange() {
        return false;
    }

    public GiftCardBean consultarSaldo(String numeroTarjeta) throws PaymentException {
        LOG.debug("consultarSaldo() - Consultando saldo de la tarjeta " + numeroTarjeta);

        if (StringUtils.isBlank(numeroTarjeta)) {
            throw new PaymentException(I18N.getTexto("Debe indicar el número de la tarjeta regalo."));
        }

        try {
            Object response = invokeGiftCardConsult(numeroTarjeta);

            GiftCardBean giftCard = adaptGiftCardResponse(response, numeroTarjeta);

            if (giftCard == null) {
                throw new PaymentException(I18N.getTexto("No se ha podido obtener el saldo de la tarjeta regalo."));
            }

            return giftCard;
        }
        catch (PaymentException e) {
            throw e;
        }
        catch (Exception e) {
            LOG.error("consultarSaldo() - Error al consultar el saldo de la tarjeta regalo: " + e.getMessage(), e);
            throw new PaymentException(I18N.getTexto("Ha habido un error al consultar el saldo de la tarjeta regalo."), e);
        }
    }

    private Object invokeGiftCardConsult(String numeroTarjeta) throws Exception {
        Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.movimientos.MovimientosRest");

        for (Method method : restClass.getMethods()) {
            if (!CONSULT_METHOD_NAMES.contains(method.getName()) || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            Object[] arguments = buildRestArguments(method.getParameterTypes(), numeroTarjeta);

            if (arguments == null) {
                continue;
            }

            Object result = method.invoke(null, arguments);

            if (result != null) {
                return result;
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
            String uidActividad = null;

            if (sesion != null && sesion.getAplicacion() != null) {
                uidActividad = sesion.getAplicacion().getUidActividad();
            }

            if (StringUtils.isBlank(uidActividad)) {
                return null;
            }

            return new Object[] { uidActividad, numeroTarjeta };
        }

        return null;
    }

    private GiftCardBean adaptGiftCardResponse(Object response, String numeroTarjeta) {
        if (response == null) {
            return null;
        }

        if (response instanceof GiftCardBean) {
            GiftCardBean bean = (GiftCardBean) response;
            ensureCardIdentifiers(bean, numeroTarjeta);
            ensureGiftCardBalanceDefaults(bean);
            return bean;
        }

        GiftCardBean bean = createGiftCardBean(numeroTarjeta);
        boolean updated = false;

        if (response instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) response;
            BigDecimal saldo = findMapBigDecimal(map, "saldo", "balance");
            BigDecimal saldoProvisional = findMapBigDecimal(map, "saldoProvisional", "provisionalBalance");
            BigDecimal saldoTotal = findMapBigDecimal(map, "saldoTotal", "totalBalance");

            if (saldoTotal != null) {
                applyBalances(bean, saldoTotal, BigDecimal.ZERO);
                updated = true;
            }
            else if (saldo != null || saldoProvisional != null) {
                applyBalances(bean, saldo, saldoProvisional);
                updated = true;
            }
        }
        else if (response instanceof BigDecimal) {
            applyBalances(bean, (BigDecimal) response, BigDecimal.ZERO);
            updated = true;
        }
        else {
            BigDecimal saldo = extractBigDecimal(response, "getSaldo");
            BigDecimal saldoProvisional = extractBigDecimal(response, "getSaldoProvisional");
            BigDecimal saldoTotal = extractBigDecimal(response, "getSaldoTotal");

            if (saldoTotal != null) {
                applyBalances(bean, saldoTotal, BigDecimal.ZERO);
                updated = true;
            }
            else if (saldo != null || saldoProvisional != null) {
                applyBalances(bean, saldo, saldoProvisional);
                updated = true;
            }
            else {
                BigDecimal balance = extractBigDecimal(response, "getBalance");

                if (balance != null) {
                    applyBalances(bean, balance, BigDecimal.ZERO);
                    updated = true;
                }
            }
        }

        if (!updated) {
            return null;
        }

        ensureCardIdentifiers(bean, numeroTarjeta);
        ensureGiftCardBalanceDefaults(bean);
        return bean;
    }

    private GiftCardBean createGiftCardBean(String numeroTarjeta) {
        GiftCardBean bean = new GiftCardBean();
        bean.setNumTarjetaRegalo(numeroTarjeta);
        setNumeroTarjeta(bean, numeroTarjeta);
        bean.setSaldo(BigDecimal.ZERO);
        bean.setSaldoProvisional(BigDecimal.ZERO);
        updateGiftCardTotal(bean);
        return bean;
    }

    private void ensureCardIdentifiers(GiftCardBean bean, String numeroTarjeta) {
        if (StringUtils.isBlank(bean.getNumTarjetaRegalo())) {
            bean.setNumTarjetaRegalo(numeroTarjeta);
        }

        try {
            Method getter = bean.getClass().getMethod("getNumeroTarjeta");
            Object numero = getter.invoke(bean);

            if (numero == null || StringUtils.isBlank(numero.toString())) {
                setNumeroTarjeta(bean, numeroTarjeta);
            }
        }
        catch (Exception e) {
            setNumeroTarjeta(bean, numeroTarjeta);
        }
    }

    private BigDecimal extractBigDecimal(Object source, String getterName) {
        if (source == null) {
            return null;
        }

        try {
            Method method = source.getClass().getMethod(getterName);
            Object value = method.invoke(source);
            return toBigDecimal(value);
        }
        catch (Exception e) {
            return null;
        }
    }

    private BigDecimal findMapBigDecimal(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            if (key == null) {
                continue;
            }

            for (Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }

                if (key.equalsIgnoreCase(entry.getKey().toString())) {
                    BigDecimal value = toBigDecimal(entry.getValue());

                    if (value != null) {
                        return value;
                    }
                }
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

        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }

        if (value instanceof String) {
            String text = ((String) value).trim();

            if (text.isEmpty()) {
                return null;
            }

            try {
                return new BigDecimal(text);
            }
            catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    private void applyBalances(GiftCardBean bean, BigDecimal saldo, BigDecimal saldoProvisional) {
        bean.setSaldo(safeBigDecimal(saldo));
        bean.setSaldoProvisional(safeBigDecimal(saldoProvisional));
        updateGiftCardTotal(bean);
    }

    private BigDecimal safeBigDecimal(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private void ensureGiftCardBalanceDefaults(GiftCardBean bean) {
        if (bean.getSaldo() == null) {
            bean.setSaldo(BigDecimal.ZERO);
        }

        if (bean.getSaldoProvisional() == null) {
            bean.setSaldoProvisional(BigDecimal.ZERO);
        }

        updateGiftCardTotal(bean);
    }

    private void updateGiftCardTotal(GiftCardBean bean) {
        try {
            Method method = bean.getClass().getMethod("setSaldoTotal", BigDecimal.class);
            method.invoke(bean, safeBigDecimal(bean.getSaldo()).add(safeBigDecimal(bean.getSaldoProvisional())));
        }
        catch (NoSuchMethodException e) {
            // Ignored: some implementations may not expose setSaldoTotal
        }
        catch (Exception e) {
            LOG.debug("updateGiftCardTotal() - No ha sido posible establecer el saldo total: " + e.getMessage(), e);
        }
    }

    private String resolveNumeroTarjeta(GiftCardBean giftCard) {
        if (giftCard == null) {
            return null;
        }

        if (StringUtils.isNotBlank(giftCard.getNumTarjetaRegalo())) {
            return giftCard.getNumTarjetaRegalo();
        }

        try {
            Method getter = giftCard.getClass().getMethod("getNumeroTarjeta");
            Object numero = getter.invoke(giftCard);
            return numero != null ? numero.toString() : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    private void setNumeroTarjeta(GiftCardBean bean, String numeroTarjeta) {
        try {
            Method setter = bean.getClass().getMethod("setNumeroTarjeta", String.class);
            setter.invoke(bean, numeroTarjeta);
        }
        catch (NoSuchMethodException e) {
            // Algunos modelos de tarjeta pueden no disponer de este atributo
        }
        catch (Exception e) {
            LOG.debug("setNumeroTarjeta() - No ha sido posible asignar el número de tarjeta: " + e.getMessage(), e);
        }
    }
}
