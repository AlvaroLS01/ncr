package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;

/**
 * Result of applying a gift card to a ticket.
 */
public class ApplyGiftCardResult extends GiftCardBalance {

    private final BigDecimal appliedAmount;

    public ApplyGiftCardResult(BigDecimal appliedAmount, BigDecimal preBalance, BigDecimal postBalance) {
        super(preBalance, postBalance);
        this.appliedAmount = appliedAmount;
    }

    public BigDecimal getAppliedAmount() {
        return appliedAmount;
    }
}
