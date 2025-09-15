package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;

/**
 * Abstraction for gift card operations. Implementations may interact with
 * external providers to query balances or redeem amounts.
 */
public interface GiftCardProvider {

    /**
     * Obtains the current balance for the given barcode.
     *
     * @param barcode card identifier
     * @return available balance
     * @throws GiftCardBalanceException if the balance cannot be retrieved
     */
    BigDecimal getBalance(String barcode) throws GiftCardBalanceException;

    /**
     * Redeems the indicated amount from the card for the provided ticket.
     *
     * @param barcode card identifier
     * @param amount amount to apply
     * @param ticketId identifier of the ticket
     * @return result including applied amount and balances
     * @throws GiftCardRedeemException if the redemption fails
     */
    ApplyGiftCardResult redeem(String barcode, BigDecimal amount, String ticketId) throws GiftCardRedeemException;
}
