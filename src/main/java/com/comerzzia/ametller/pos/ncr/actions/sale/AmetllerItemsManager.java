package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;

import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.ItemException;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.i18n.I18N;

@Lazy(false)
@Service
@Primary
@DependsOn("itemsManager")
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCUENTO_25_DESCRIPTION = "Descuento del 25% aplicado";

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

        return itemSold;
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