package com.comerzzia.ametller.pos.ncr.actions.sale;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.PayManager;

@Service
@Primary
public class AmetllerPayManager extends PayManager {

    @Override
    protected void activateTenderMode() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            ((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(false);
        }
        super.activateTenderMode();
    }
}
