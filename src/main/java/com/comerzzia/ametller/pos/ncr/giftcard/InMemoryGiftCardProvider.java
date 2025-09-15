package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple in-memory provider useful for tests or offline demos.
 */
public class InMemoryGiftCardProvider implements GiftCardProvider {

    private final Map<String, BigDecimal> balances = new HashMap<>();

    public void setBalance(String barcode, BigDecimal balance) {
        balances.put(barcode, balance);
    }

    @Override
    public BigDecimal getBalance(String barcode) throws GiftCardException {
        BigDecimal balance = balances.get(barcode);
        if (balance == null) {
            throw new GiftCardException("Card not found");
        }
        return balance;
    }

    @Override
    public RedeemResponse redeem(String barcode, BigDecimal amount, String ticketId) throws GiftCardException {
        BigDecimal balance = getBalance(barcode);
        if (amount.compareTo(balance) > 0) {
            amount = balance;
        }
        BigDecimal post = balance.subtract(amount);
        balances.put(barcode, post);
        return new RedeemResponse(amount, post, "AUTH", "TXN-" + ticketId);
    }
}
