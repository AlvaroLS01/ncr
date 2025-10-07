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

    private void sendItemSoldInternal(final ItemSold itemSold, final boolean includeTotals) {
        if (itemSold == null) {
            return;
        }

        ncrController.sendMessage(itemSold);

        if (includeTotals) {
            sendTotals();
        }

        ItemSold discountApplied = itemSold.getDiscountApplied();

        if (discountApplied != null && !StringUtils.equals(discountApplied.getFieldValue(ItemSold.Price), "0")) {
            ncrController.sendMessage(discountApplied);

            if (includeTotals) {
                sendTotals();
            }
        }
    }

    @Override
    protected void sendItemSold(final ItemSold itemSold) {
        sendItemSoldInternal(itemSold, true);
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
        }

        ItemSold newLineItemSold = null;

        if (ticketManager != null && ticketManager.getTicket() != null) {
            @SuppressWarnings("unchecked")
            List<LineaTicket> ticketLines = (List<LineaTicket>) ticketManager.getTicket().getLineas();

            for (LineaTicket ticketLine : ticketLines) {
                if (ticketLine == null || ticketLine.getIdLinea() == null) {
                    continue;
                }

                ItemSold refreshedItem = lineaTicketToItemSold(ticketLine);

                if (ticketLine.getIdLinea().equals(newLine.getIdLinea())) {
                    newLineItemSold = refreshedItem;
                    continue;
                }

                ItemSold cachedItem = linesCache.get(ticketLine.getIdLinea());

                if (!hasLineChanged(cachedItem, refreshedItem)) {
                    linesCache.put(ticketLine.getIdLinea(), refreshedItem);
                    continue;
                }

                disableBaggingPrompts(refreshedItem);
                sendItemSoldInternal(refreshedItem, false);

                linesCache.put(ticketLine.getIdLinea(), refreshedItem);
            }
        }

        if (newLineItemSold == null) {
            newLineItemSold = lineaTicketToItemSold(newLine);
        }

        sendItemSold(newLineItemSold);

        linesCache.put(newLine.getIdLinea(), newLineItemSold);
        lastScannedLineId = newLine.getIdLinea();
    }

    @Override
    public void newTicket() {
        super.newTicket();

        lastScannedLineId = null;

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

        super.deleteAllItems(message);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateItems() {
        super.updateItems();

        if (ticketManager == null || ticketManager.getTicket() == null) {
            return;
        }

        boolean totalsPending = false;

        for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
            if (ticketLine == null || ticketLine.getIdLinea() == null) {
                continue;
            }

            ItemSold cachedItem = linesCache.get(ticketLine.getIdLinea());

            if (cachedItem == null) {
                continue;
            }

            ItemSold refreshedItem = lineaTicketToItemSold(ticketLine);

            if (!hasLineChanged(cachedItem, refreshedItem)) {
                continue;
            }

            boolean isLastScanned = ticketLine.getIdLinea().equals(lastScannedLineId);

            if (!isLastScanned) {
                disableBaggingPrompts(refreshedItem);
                sendItemSoldInternal(refreshedItem, false);
                totalsPending = true;
            } else {
                sendItemSold(refreshedItem);
            }

            linesCache.put(ticketLine.getIdLinea(), refreshedItem);
        }

        if (totalsPending) {
            sendTotals();
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
            itemException.setFieldValue(ItemException.Message,
                    I18N.getTexto("Tu cupon ha sido leído correctamente"));
            itemException.setFieldValue(ItemException.TopCaption, I18N.getTexto("Cupon leído"));
            ncrController.sendMessage(itemException);
        }

        return handled;
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

    private void disableBaggingPrompts(final ItemSold itemSold) {
        itemSold.setFieldValue(ItemSold.RequiresSecurityBagging, "2");
        itemSold.setFieldValue(ItemSold.RequiresSubsCheck, "2");

        ItemSold discountApplied = itemSold.getDiscountApplied();
        if (discountApplied != null) {
            discountApplied.setFieldValue(ItemSold.RequiresSecurityBagging, "2");
            discountApplied.setFieldValue(ItemSold.RequiresSubsCheck, "2");
        }
    }
}
