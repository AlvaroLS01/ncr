package com.comerzzia.ametller.pos.ncr.giftcard;

/**
 * Thrown when a balance inquiry cannot be performed.
 */
public class GiftCardBalanceException extends GiftCardException {
    public GiftCardBalanceException(String message) {
        super(message);
    }

    public GiftCardBalanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
