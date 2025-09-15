package com.comerzzia.ametller.pos.ncr.giftcard;

/**
 * Thrown when the redemption/authorization of a gift card fails.
 */
public class GiftCardRedeemException extends GiftCardException {
    public GiftCardRedeemException(String message) {
        super(message);
    }

    public GiftCardRedeemException(String message, Throwable cause) {
        super(message, cause);
    }
}
