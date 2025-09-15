package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;

/**
 * Simple immutable representation of a balance inquiry.
 */
public class GiftCardBalance {
    private final BigDecimal preBalance;
    private final BigDecimal postBalance;

    public GiftCardBalance(BigDecimal preBalance, BigDecimal postBalance) {
        this.preBalance = preBalance;
        this.postBalance = postBalance;
    }

    public BigDecimal getPreBalance() {
        return preBalance;
    }

    public BigDecimal getPostBalance() {
        return postBalance;
    }
}
