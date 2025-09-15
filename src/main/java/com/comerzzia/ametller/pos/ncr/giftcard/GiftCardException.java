package com.comerzzia.ametller.pos.ncr.giftcard;

/**
 * Base exception for gift card operations.
 */
public class GiftCardException extends Exception {

    public GiftCardException(String message) {
        super(message);
    }

    public GiftCardException(String message, Throwable cause) {
        super(message, cause);
    }
}
