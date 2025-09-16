package com.comerzzia.ametller.pos.services.payments.methods.types;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;

/**
 * Clase puente: únicamente renombra el manager. Mantiene exactamente el mismo comportamiento que
 * {@link GiftCardManager} y utiliza el estándar de Comerzzia 4.8.1 (sin cambios funcionales).
 */
@Component
@Scope("prototype")
public class BalanceCardManager extends GiftCardManager {
    // Hereda todo de GiftCardManager.
}
