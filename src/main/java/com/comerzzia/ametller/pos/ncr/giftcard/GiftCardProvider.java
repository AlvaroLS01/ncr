package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;

/**
 * Abstraction for gift card operations. Implementations talk to the
 * backend gift card manager (BalanceCardManager) and return simplified
 * results for the POS layer.
 */
public interface GiftCardProvider {

    /**
     * Retrieves the current balance of the given card.
     *
     * @param cardNumber barcode of the card
     * @return available balance
     * @throws GiftCardException when the balance cannot be obtained
     */
    BigDecimal getBalance(String cardNumber) throws GiftCardException;

    /**
     * Redeems the specified amount for the ticket.
     *
     * @param cardNumber barcode of the card
     * @param amount amount to redeem
     * @param ticketId ticket identifier for traceability
     * @return response with approved amount and remaining balance
     * @throws GiftCardException when redemption fails
     */
    RedeemResponse redeem(String cardNumber, BigDecimal amount, String ticketId) throws GiftCardException;

    /**
     * Minimal redeem information used by the POS.
     */
    class RedeemResponse {
        private final BigDecimal approvedAmount;
        private final BigDecimal remainingBalance;
        private final String authCode;
        private final String transactionId;

        public RedeemResponse(BigDecimal approvedAmount, BigDecimal remainingBalance,
                String authCode, String transactionId) {
            this.approvedAmount = approvedAmount;
            this.remainingBalance = remainingBalance;
            this.authCode = authCode;
            this.transactionId = transactionId;
        }

        public BigDecimal getApprovedAmount() {
            return approvedAmount;
        }

        public BigDecimal getRemainingBalance() {
            return remainingBalance;
        }

        public String getAuthCode() {
            return authCode;
        }

        public String getTransactionId() {
            return transactionId;
        }
    }
}
