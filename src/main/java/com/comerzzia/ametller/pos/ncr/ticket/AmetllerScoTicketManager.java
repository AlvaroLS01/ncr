package com.comerzzia.ametller.pos.ncr.ticket;

import java.math.BigDecimal;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketAbstract;

@Service
@Primary
public class AmetllerScoTicketManager extends ScoTicketManager {

    private static final BigDecimal DESCUENTO25 = new BigDecimal("25.00");
    private boolean descuento25Activo = false;

    public void setDescuento25Activo(boolean activo) {
        this.descuento25Activo = activo;
    }

    @Override
    public LineaTicket addLineToTicket(LineaTicketAbstract linea) {
        LineaTicket added = super.addLineToTicket(linea);
        if (descuento25Activo && added != null) {
            added.setDescuentoManual(DESCUENTO25);
            recalculateTicket();
        }
        return added;
    }
}
