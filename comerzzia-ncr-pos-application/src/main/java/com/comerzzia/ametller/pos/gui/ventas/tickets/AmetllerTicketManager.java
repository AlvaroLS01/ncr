package com.comerzzia.ametller.pos.gui.ventas.tickets;

import java.math.BigDecimal;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.comerzzia.pos.gui.ventas.tickets.TicketManager;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketException;

import javafx.stage.Stage;

/**
 * Custom ticket manager that applies a 25% manual discount to every new line
 * when the discount mode is active. It extends the standard {@link TicketManager}
 * used by the POS.
 */
@Component
@Scope("prototype")
@Primary
public class AmetllerTicketManager extends TicketManager {

    private static final BigDecimal DESCUENTO25 = new BigDecimal("25.00");

    private boolean descuento25Activo = false;

    /**
     * Toggles the 25% discount mode. Intended for use by the GUI button.
     */
    public boolean toggleDescuento25() {
        descuento25Activo = !descuento25Activo;
        return descuento25Activo;
    }

    /**
     * Explicit setter used by the NCR messaging to control the flag.
     */
    public void setDescuento25Activo(boolean activo) {
        this.descuento25Activo = activo;
    }

    /**
     * Indicates whether the discount mode is active.
     */
    public boolean isDescuento25Activo() {
        return descuento25Activo;
    }

    @Override
    public synchronized LineaTicket nuevaLineaArticulo(String codArticulo, String desglose1, String desglose2,
            BigDecimal cantidad, Stage stage, Integer idLineaDocOrigen, boolean esLineaDevolucionPositiva,
            boolean applyDUN14Factor) throws LineaTicketException {
        LineaTicket linea = super.nuevaLineaArticulo(codArticulo, desglose1, desglose2, cantidad, stage,
                idLineaDocOrigen, esLineaDevolucionPositiva, applyDUN14Factor);
        if (descuento25Activo && linea != null) {
            linea.setDescuentoManual(DESCUENTO25);
        }
        return linea;
    }
}
