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
    public BigDecimal getBalance(String barcode) throws GiftCardBalanceException {
        BigDecimal balance = balances.get(barcode);
        if (balance == null) {
            throw new GiftCardBalanceException("Card not found");
        }
        return balance;
    }

    @Override
    public ApplyGiftCardResult redeem(String barcode, BigDecimal amount, String ticketId)
            throws GiftCardRedeemException {
        BigDecimal balance = balances.get(barcode);
        if (balance == null) {
            throw new GiftCardRedeemException("Card not found");
        }
        if (amount.compareTo(balance) > 0) {
            amount = balance;
        }
        BigDecimal post = balance.subtract(amount);
        balances.put(barcode, post);
        return new ApplyGiftCardResult(amount, balance, post);
    }
}
