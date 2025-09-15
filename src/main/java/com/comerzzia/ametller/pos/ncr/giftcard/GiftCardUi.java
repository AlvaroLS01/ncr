package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;

/**
 * Abstraction for the UI layer shown by the NCR ADK. The state machine
 * delegates to this interface to keep presentation concerns isolated.
 */
public interface GiftCardUi {

    void showSelectPaymentType();

    void showOtherPayments();

    void showScanCard();

    void showYourBalance(BigDecimal remainingBalance, BigDecimal appliedAmount);

    void showDepositGiftCard();

    void showTakeReceipt();

    void showThankYou();

    void showError(String message);
}
