package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.ItemException;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.VoidTransaction;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.i18n.I18N;

@Lazy(false)
@Service
@Primary
@DependsOn("itemsManager")
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCUENTO_25_DESCRIPTION = "Descuento del 25% aplicado";

    @Autowired
    @Lazy
    private AmetllerPayManager ametllerPayManager;

    private Integer lastScannedLineId;
    private boolean suppressTotals;
    private boolean pendingTotals;

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

        //Enviamos un unico ItemSold y un unico Totals para que no salga el problema del embolsado
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
        sendItemSoldMessage(itemSold, true, false);
    }

    @Override
    public void sendTotals() {
        if (suppressTotals) {
            pendingTotals = true;
            return;
        }

        super.sendTotals();
        pendingTotals = false;
    }

    private void sendItemSoldMessage(final ItemSold itemSold, final boolean includeTotals) {
        sendItemSoldMessage(itemSold, includeTotals, false);
    }

    private void sendItemSoldMessage(final ItemSold itemSold, final boolean includeTotals, final boolean skipBaggingPrompt) {
        if (itemSold == null) {
            return;
        }

        if (skipBaggingPrompt) {
            disableBaggingPrompts(itemSold);
        }

        ncrController.sendMessage(itemSold);

        if (includeTotals) {
            sendTotals();
        } else {
            pendingTotals = true;
        }
    }

    private void disableBaggingPrompts(final ItemSold itemSold) {
        itemSold.setFieldValue(ItemSold.RequiresSecurityBagging, "2");
        itemSold.setFieldValue(ItemSold.RequiresSubsCheck, "2");

        ItemSold discountApplied = itemSold.getDiscountApplied();
        if (discountApplied != null) {
            discountApplied.setFieldValue(ItemSold.RequiresSecurityBagging, "2");
            discountApplied.setFieldValue(ItemSold.RequiresSubsCheck, "2");
        }
    }

    private void flushPendingTotalsIfNeeded() {
        if (!pendingTotals) {
            return;
        }

        boolean previousSuppress = suppressTotals;
        suppressTotals = false;
        sendTotals();
        suppressTotals = previousSuppress;
    }


    @Override
    public void newItem(final LineaTicket newLine) {
        if (newLine == null) {
            return;
        }

        if (ticketManager != null && ticketManager.getTicket() != null) {
            ticketManager.getSesion().getSesionPromociones()
                    .aplicarPromociones((TicketVentaAbono) ticketManager.getTicket());
            ticketManager.getTicket().getTotales().recalcular();
            refreshExistingLinesBeforeNewItem(newLine);
        }

        ItemSold response = lineaTicketToItemSold(newLine);

        sendItemSold(response);

        linesCache.put(newLine.getIdLinea(), response);

        lastScannedLineId = newLine.getIdLinea();
    }

    @Override
    public void newTicket() {
        super.newTicket();

        lastScannedLineId = null;
        suppressTotals = false;
        pendingTotals = false;

        if (ametllerPayManager != null) {
            ametllerPayManager.onTransactionStarted();
        }
    }

    @Override
    public void deleteAllItems(VoidTransaction message) {
        if (ametllerPayManager != null) {
            ametllerPayManager.onTransactionVoided();
        }

        lastScannedLineId = null;
        suppressTotals = false;
        pendingTotals = false;

        super.deleteAllItems(message);
    }


    //Actualizamos linea si el articulo tiene promoción
    @Override
    @SuppressWarnings("unchecked")
    public void updateItems() {
        boolean previousSuppress = suppressTotals;
        suppressTotals = true;
        try {
            super.updateItems();
        } finally {
            suppressTotals = previousSuppress;
        }

        if (ticketManager == null || ticketManager.getTicket() == null) {
            flushPendingTotalsIfNeeded();
            return;
        }

        boolean ticketLinesUpdated = false;

        for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
            ItemSold cachedItem = linesCache.get(ticketLine.getIdLinea());

            if (cachedItem == null) {
                continue;
            }

            ItemSold refreshedItem = lineaTicketToItemSold(ticketLine);

            if (hasLineChanged(cachedItem, refreshedItem)) {
                sendItemSoldMessage(refreshedItem, false, true);
                linesCache.put(ticketLine.getIdLinea(), refreshedItem);
                ticketLinesUpdated = true;
            }
        }

        boolean shouldResendLastLine = pendingTotals || ticketLinesUpdated;

        resendLastScannedLine(shouldResendLastLine);

        flushPendingTotalsIfNeeded();
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

    @SuppressWarnings("unchecked")
    private void resendLastScannedLine(boolean shouldResend) {
        if (!shouldResend || lastScannedLineId == null
                || ticketManager == null || ticketManager.getTicket() == null) {
            return;
        }

        LineaTicket lastLine = null;

        for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
            if (lastScannedLineId.equals(ticketLine.getIdLinea())) {
                lastLine = ticketLine;
                break;
            }
        }

        if (lastLine == null) {
            return;
        }

        ItemSold lastItemSold = lineaTicketToItemSold(lastLine);

        sendItemSoldMessage(lastItemSold, false, true);

        linesCache.put(lastLine.getIdLinea(), lastItemSold);
    }

    private boolean hasLineChanged(ItemSold cachedItem, ItemSold refreshedItem) {
        if (cachedItem == null || refreshedItem == null) {
            return false;
        }

        String cachedPrice = cachedItem.getFieldValue(ItemSold.Price);
        String refreshedPrice = refreshedItem.getFieldValue(ItemSold.Price);
        if (!StringUtils.equals(cachedPrice, refreshedPrice)) {
            return true;
        }

        String cachedExtendedPrice = cachedItem.getFieldValue(ItemSold.ExtendedPrice);
        String refreshedExtendedPrice = refreshedItem.getFieldValue(ItemSold.ExtendedPrice);
        if (!StringUtils.equals(cachedExtendedPrice, refreshedExtendedPrice)) {
            return true;
        }

        String cachedDescription = cachedItem.getFieldValue(ItemSold.Description);
        String refreshedDescription = refreshedItem.getFieldValue(ItemSold.Description);
        if (!StringUtils.equals(cachedDescription, refreshedDescription)) {
            return true;
        }

        ItemSold cachedDiscount = cachedItem.getDiscountApplied();
        ItemSold refreshedDiscount = refreshedItem.getDiscountApplied();
        if (cachedDiscount != null && refreshedDiscount != null) {
            String cachedDiscountAmount = cachedDiscount.getFieldValue(ItemSold.DiscountAmount);
            String refreshedDiscountAmount = refreshedDiscount.getFieldValue(ItemSold.DiscountAmount);

            if (!StringUtils.equals(cachedDiscountAmount, refreshedDiscountAmount)) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private void refreshExistingLinesBeforeNewItem(LineaTicket newLine) {
        if (newLine == null || ticketManager == null || ticketManager.getTicket() == null) {
            return;
        }

        Integer newLineId = newLine.getIdLinea();

        for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
            if (ticketLine == null || ticketLine.getIdLinea() == null) {
                continue;
            }

            if (ticketLine.getIdLinea().equals(newLineId)) {
                continue;
            }

            ItemSold cachedItem = linesCache.get(ticketLine.getIdLinea());

            if (cachedItem == null) {
                continue;
            }

            ItemSold refreshedItem = lineaTicketToItemSold(ticketLine);

            if (hasLineChanged(cachedItem, refreshedItem)) {
                sendItemSoldMessage(refreshedItem, false, true);
                linesCache.put(ticketLine.getIdLinea(), refreshedItem);
            }
        }
    }
}
