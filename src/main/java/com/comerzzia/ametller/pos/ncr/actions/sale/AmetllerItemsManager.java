package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang.StringUtils;

import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.Coupon;
import com.comerzzia.pos.ncr.messages.ItemException;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.ItemVoided;
import com.comerzzia.pos.ncr.messages.VoidItem;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.i18n.I18N;
import com.comerzzia.pos.services.ticket.promociones.IPromocionTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;

@Lazy(false)
@Service
@Primary
@DependsOn("itemsManager")
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCUENTO_25_DESCRIPTION = "Descuento del 25% aplicado";

    @Override
    protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
        ItemSold itemSold = super.lineaTicketToItemSold(linea);

        if (linea != null && itemSold != null) {
            BigDecimal importeAhorrado = linea.getImporteTotalPromociones();
            ItemSold discountApplied = itemSold.getDiscountApplied();

            if (discountApplied != null) {
                if (importeAhorrado == null) {
                    importeAhorrado = BigDecimal.ZERO;
                }

                discountApplied.setFieldIntValue(ItemSold.DiscountAmount, importeAhorrado);
            }
        }

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

        return itemSold;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updateItems() {
        ticketManager.getSesion().getSesionPromociones().aplicarPromociones((TicketVentaAbono) ticketManager.getTicket());
        ticketManager.getTicket().getTotales().recalcular();

        Map<String, HeaderDiscountData> headerDiscounts = getHeaderDiscountsDetail();

        for (HeaderDiscountData headerDiscountData : headerDiscounts.values()) {
            GlobalDiscountData globalDiscountData = globalDiscounts.get(GLOBAL_DISCOUNT_PAYMENT_PREFIX + headerDiscountData.paymentMethodCode);

            if (globalDiscountData == null) {
                globalDiscountData = new GlobalDiscountData();
                globalDiscountData.headerDiscount = headerDiscountData;

                ItemSold discount = new ItemSold();
                discount.setFieldValue(ItemSold.ItemNumber, String.valueOf(globalDiscountCounter));
                discount.setFieldIntValue(ItemSold.DiscountAmount, headerDiscountData.amount);
                discount.setFieldValue(ItemSold.RewardLocation, "1");
                discount.setFieldValue(ItemSold.ShowRewardPoints, "1");
                discount.setFieldValue(ItemSold.DiscountDescription, headerDiscountData.paymentMethodDes);

                globalDiscountCounter++;

                globalDiscountData.itemSoldMessage = discount;

                globalDiscounts.put(GLOBAL_DISCOUNT_PAYMENT_PREFIX + headerDiscountData.paymentMethodCode, globalDiscountData);

                ncrController.sendMessage(discount);

                sendTotals();
            } else {
                ItemSold discount = globalDiscountData.itemSoldMessage;

                if (headerDiscountData.amount.compareTo(discount.getFieldBigDecimalValue(ItemSold.DiscountAmount, 2)) != 0) {
                    log.debug("Updating header discount " + discount.getFieldValue(ItemSold.ItemNumber));

                    discount.setFieldIntValue(ItemSold.DiscountAmount, headerDiscountData.amount);

                    ncrController.sendMessage(discount);

                    sendTotals();
                }
            }
        }

        if (globalDiscounts.size() > 0) {
            Iterator<Entry<String, GlobalDiscountData>> globalDiscountIterator = globalDiscounts.entrySet().iterator();

            while (globalDiscountIterator.hasNext()) {
                Entry<String, GlobalDiscountData> grobalDiscountItem = globalDiscountIterator.next();

                GlobalDiscountData globalDiscountData = grobalDiscountItem.getValue();

                if (globalDiscountData.coupon != null) {
                    boolean applied = false;

                    for (PromocionTicket appliedPromotion : ((TicketVentaAbono) ticketManager.getTicket()).getPromociones()) {
                        if (IPromocionTicket.COUPON_ACCESS.equals(appliedPromotion.getAcceso()) &&
                                StringUtils.equals(appliedPromotion.getCodAcceso(), globalDiscountData.coupon.getCouponCode())) {
                            applied = true;
                            break;
                        }
                    }

                    if (!applied) {
                        ItemVoided voidItem = new ItemVoided();
                        voidItem.setFieldValue(VoidItem.ItemNumber, globalDiscountData.couponMessage.getFieldValue(Coupon.ItemNumber));
                        ncrController.sendMessage(voidItem);

                        sendTotals();

                        globalDiscountIterator.remove();
                    }
                } else if (globalDiscountData.headerDiscount != null) {
                    if (!headerDiscounts.containsKey(globalDiscountData.headerDiscount.paymentMethodCode)) {
                        ItemVoided voidItem = new ItemVoided();
                        voidItem.setFieldValue(VoidItem.ItemNumber, globalDiscountData.itemSoldMessage.getFieldValue(ItemSold.ItemNumber));
                        ncrController.sendMessage(voidItem);

                        sendTotals();

                        globalDiscountIterator.remove();
                    }
                }

            }
        }

        for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
            ItemSold currentItemSold = linesCache.get(ticketLine.getIdLinea());

            if (currentItemSold != null) {
                ItemSold newItemSold = lineaTicketToItemSold(ticketLine);

                String actualDiscount = currentItemSold.getDiscountApplied().getFieldValue(ItemSold.DiscountAmount);
                String newDiscount = newItemSold.getDiscountApplied().getFieldValue(ItemSold.DiscountAmount);

                if (!StringUtils.equals(actualDiscount, newDiscount)) {
                    log.debug("Updating line " + ticketLine.getIdLinea());

                    if (!StringUtils.equals(actualDiscount, "0") && StringUtils.equals(newDiscount, "0")) {
                        ItemVoided voidItem = new ItemVoided();
                        voidItem.setFieldValue(VoidItem.ItemNumber, currentItemSold.getDiscountApplied().getFieldValue(ItemSold.ItemNumber));
                        ncrController.sendMessage(voidItem);

                        ncrController.sendMessage(newItemSold);
                        sendTotals();
                    } else if (!StringUtils.equals(newDiscount, "0")) {
                        ItemSold discountUpdate = newItemSold.getDiscountApplied();
                        String discountPrice = discountUpdate.getFieldValue(ItemSold.Price);

                        if (StringUtils.isNotBlank(discountPrice)) {
                            discountUpdate.setFieldValue(ItemSold.ExtendedPrice, discountPrice);
                        }

                        ncrController.sendMessage(newItemSold);
                        ncrController.sendMessage(discountUpdate);
                        sendTotals();
                    } else {
                        ncrController.sendMessage(newItemSold);
                        sendTotals();
                    }

                    linesCache.put(ticketLine.getIdLinea(), newItemSold);
                }
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
}