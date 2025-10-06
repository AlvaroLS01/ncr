package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.ItemException;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.Totals;
import com.comerzzia.pos.services.ticket.cabecera.ITotalesTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.i18n.I18N;

@Lazy(false)
@Service
@Primary
@DependsOn("itemsManager")
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCUENTO_25_DESCRIPTION = "Descuento del 25% aplicado";

    private TotalsSnapshot lastTotals;

    @Override
    protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
        ItemSold itemSold = super.lineaTicketToItemSold(linea);

        if (linea != null && itemSold != null && ticketManager instanceof AmetllerScoTicketManager) {
            AmetllerScoTicketManager ametllerScoTicketManager = (AmetllerScoTicketManager) ticketManager;
            if (ametllerScoTicketManager.hasDescuento25Aplicado(linea)) {
                BigDecimal importeSinDto = linea.getImporteTotalSinDto();
                BigDecimal importeConDto = linea.getImporteTotalConDto();

                if (importeSinDto != null && importeConDto != null) {
                    BigDecimal ahorro = importeSinDto.subtract(importeConDto);

                    if (BigDecimalUtil.isMayorACero(ahorro)) {
                        itemSold.setDiscount(importeConDto, ahorro, DESCUENTO_25_DESCRIPTION);
                    } else {
                        ametllerScoTicketManager.removeDescuento25(linea.getIdLinea());
                    }
                }
            }
        }

        if (linea != null && itemSold != null) {
            BigDecimal importePromociones = linea.getImporteTotalPromociones();

            if (BigDecimalUtil.isMayorACero(importePromociones)) {
                BigDecimal precioConDto = linea.getPrecioTotalConDto();
                BigDecimal importeConDto = linea.getImporteTotalConDto();

                if (precioConDto != null) {
                    itemSold.setFieldIntValue(ItemSold.Price, precioConDto);
                }

                if (importeConDto != null) {
                    itemSold.setFieldIntValue(ItemSold.ExtendedPrice, importeConDto);
                }

                ItemSold discountApplied = itemSold.getDiscountApplied();

                if (discountApplied != null) {
                    String discountDescription = discountApplied.getFieldValue(ItemSold.Description);

                    if (StringUtils.isNotBlank(discountDescription)) {
                        itemSold.setFieldValue(ItemSold.Description, discountDescription);
                    }

                    discountApplied.setFieldIntValue(ItemSold.Price, BigDecimal.ZERO);
                    discountApplied.setFieldIntValue(ItemSold.ExtendedPrice, BigDecimal.ZERO);
                }
            }
        }

        return itemSold;
    }


    @Override
    protected void sendItemSold(final ItemSold itemSold) {
        if (itemSold == null) {
            return;
        }

        ncrController.sendMessage(itemSold);
        sendTotals();
    }


    //Actualizamos linea si el articulo tiene promoción
    @Override
    @SuppressWarnings("unchecked")
    public void updateItems() {
        super.updateItems();

        if (ticketManager == null || ticketManager.getTicket() == null) {
            return;
        }

        for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
            ItemSold cachedItem = linesCache.get(ticketLine.getIdLinea());

            if (cachedItem == null) {
                continue;
            }

            ItemSold refreshedItem = lineaTicketToItemSold(ticketLine);

            String cachedPrice = cachedItem.getFieldValue(ItemSold.Price);
            String refreshedPrice = refreshedItem.getFieldValue(ItemSold.Price);
            String cachedExtendedPrice = cachedItem.getFieldValue(ItemSold.ExtendedPrice);
            String refreshedExtendedPrice = refreshedItem.getFieldValue(ItemSold.ExtendedPrice);
            String cachedDescription = cachedItem.getFieldValue(ItemSold.Description);
            String refreshedDescription = refreshedItem.getFieldValue(ItemSold.Description);

            if (!StringUtils.equals(cachedPrice, refreshedPrice)
                    || !StringUtils.equals(cachedExtendedPrice, refreshedExtendedPrice)
                    || !StringUtils.equals(cachedDescription, refreshedDescription)) {
                ncrController.sendMessage(refreshedItem);
                sendTotals();
                linesCache.put(ticketLine.getIdLinea(), refreshedItem);
            }
        }
    }

    @Override
    public boolean isCoupon(String code) {
        boolean couponAlreadyApplied = globalDiscounts.containsKey(GLOBAL_DISCOUNT_COUPON_PREFIX + code);

        boolean handled = super.isCoupon(code);

        boolean couponApplied = globalDiscounts.containsKey(GLOBAL_DISCOUNT_COUPON_PREFIX + code);

        if (handled && couponApplied && !couponAlreadyApplied) {
            ItemException itemException = new ItemException();
            itemException.setFieldValue(ItemException.UPC, "");
            itemException.setFieldValue(ItemException.ExceptionType, "0");
            itemException.setFieldValue(ItemException.ExceptionId, "25");
            itemException.setFieldValue(ItemException.Message, I18N.getTexto("Tu cupon ha sido leído correctamente"));
            itemException.setFieldValue(ItemException.TopCaption, I18N.getTexto("Cupon leído"));
            ncrController.sendMessage(itemException);
        }

        return handled;
    }

    @Override
    public void resetTicket() {
        super.resetTicket();
        lastTotals = null;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void sendTotals() {
        ITotalesTicket totales = ticketManager.getTicket().getTotales();

        BigDecimal headerDiscounts = getHeaderDiscounts();
        BigDecimal totalAmount = totales.getTotalAPagar().subtract(headerDiscounts);
        BigDecimal entregado = totales.getEntregado();

        if (ticketManager.isTenderMode()) {
            entregado = entregado.subtract(headerDiscounts);
        }

        BigDecimal taxAmount = totales.getImpuestos();
        BigDecimal balanceDue;
        BigDecimal changeDue;

        if (totalAmount.compareTo(entregado) >= 0) {
            balanceDue = totalAmount.subtract(entregado);
            changeDue = BigDecimal.ZERO;
        } else {
            balanceDue = BigDecimal.ZERO;
            changeDue = entregado.subtract(totalAmount);
        }

        int itemCount = ticketManager.getTicket().getLineas().size();
        BigDecimal discountAmount = totales.getTotalPromociones();
        int points = totales.getPuntos();

        TotalsSnapshot newTotals = new TotalsSnapshot(totalAmount, taxAmount, balanceDue, changeDue, itemCount,
                discountAmount, points);

        if (newTotals.sameAs(lastTotals)) {
            return;
        }

        Totals totals = new Totals();
        totals.setFieldIntValue(Totals.TotalAmount, totalAmount);
        totals.setFieldIntValue(Totals.TaxAmount, taxAmount);

        if (changeDue.compareTo(BigDecimal.ZERO) > 0) {
            totals.setFieldIntValue(Totals.BalanceDue, BigDecimal.ZERO);
            totals.setFieldIntValue(Totals.ChangeDue, changeDue);
        } else {
            totals.setFieldIntValue(Totals.BalanceDue, balanceDue);
        }

        totals.setFieldValue(Totals.ItemCount, String.valueOf(itemCount));
        totals.setFieldIntValue(Totals.DiscountAmount, discountAmount);
        totals.setFieldValue(Totals.Points, String.valueOf(points));

        ncrController.sendMessage(totals);
        lastTotals = newTotals;
    }

    private static final class TotalsSnapshot {
        private final BigDecimal totalAmount;
        private final BigDecimal taxAmount;
        private final BigDecimal balanceDue;
        private final BigDecimal changeDue;
        private final int itemCount;
        private final BigDecimal discountAmount;
        private final int points;

        private TotalsSnapshot(BigDecimal totalAmount, BigDecimal taxAmount, BigDecimal balanceDue,
                BigDecimal changeDue, int itemCount, BigDecimal discountAmount, int points) {
            this.totalAmount = totalAmount;
            this.taxAmount = taxAmount;
            this.balanceDue = balanceDue;
            this.changeDue = changeDue;
            this.itemCount = itemCount;
            this.discountAmount = discountAmount;
            this.points = points;
        }

        private boolean sameAs(TotalsSnapshot other) {
            if (other == null) {
                return false;
            }

            return compare(totalAmount, other.totalAmount)
                    && compare(taxAmount, other.taxAmount)
                    && compare(balanceDue, other.balanceDue)
                    && compare(changeDue, other.changeDue)
                    && itemCount == other.itemCount
                    && compare(discountAmount, other.discountAmount)
                    && points == other.points;
        }

        private boolean compare(BigDecimal first, BigDecimal second) {
            if (first == null && second == null) {
                return true;
            }

            if (first == null || second == null) {
                return false;
            }

            return first.compareTo(second) == 0;
        }
    }
}
