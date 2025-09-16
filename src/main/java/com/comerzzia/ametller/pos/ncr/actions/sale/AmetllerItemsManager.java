package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;

import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;

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
}