package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;

import org.apache.log4j.Logger;

/**
 * State machine that orchestrates the gift card payment flow for the NCR
 * self checkout. It is intentionally decoupled from the underlying NCR
 * messaging so it can be unit tested easily.
 */
public class AmetllerGiftCardStateMachine {

    public enum State {
        SCAN_AND_BAG,
        SELECT_PAYMENT_TYPE,
        OTHER_PAYMENTS,
        SCAN_CARD,
        YOUR_BALANCE,
        DEPOSIT_GIFT_CARD,
        TAKE_RECEIPT,
        THANK_YOU
    }

    private static final Logger log = Logger.getLogger(AmetllerGiftCardStateMachine.class);

    private final GiftCardProvider provider;
    private final GiftCardUi ui;
    private final String ticketId;

    private State state = State.SCAN_AND_BAG;
    private BigDecimal ticketPending;
    private BigDecimal remainingBalance = BigDecimal.ZERO;
    private BigDecimal lastApplied = BigDecimal.ZERO;

    public AmetllerGiftCardStateMachine(GiftCardProvider provider, GiftCardUi ui,
            BigDecimal ticketPending, String ticketId) {
        this.provider = provider;
        this.ui = ui;
        this.ticketPending = ticketPending;
        this.ticketId = ticketId;
    }

    public State getState() {
        return state;
    }

    public BigDecimal getRemainingBalance() {
        return remainingBalance;
    }

    public BigDecimal getLastApplied() {
        return lastApplied;
    }

    public void finishAndPaySelected() {
        state = State.SELECT_PAYMENT_TYPE;
        ui.showSelectPaymentType();
    }

    public void otherPaymentOptionsSelected() {
        state = State.OTHER_PAYMENTS;
        ui.showOtherPayments();
    }

    public void giftCardSelected() {
        state = State.SCAN_CARD;
        ui.showScanCard();
    }

    public void giftCardScanned(String barcode) {
        try {
            BigDecimal balance = provider.getBalance(barcode);
            BigDecimal toApply = balance.min(ticketPending);
            ApplyGiftCardResult result = provider.redeem(barcode, toApply, ticketId);
            remainingBalance = result.getPostBalance();
            lastApplied = result.getAppliedAmount();
            ticketPending = ticketPending.subtract(lastApplied);
            ui.showYourBalance(remainingBalance, lastApplied);
            state = State.YOUR_BALANCE;
            log.info(String.format("Gift card %s applied %.2f, remaining %.2f", barcode,
                    lastApplied, remainingBalance));
        } catch (GiftCardBalanceException e) {
            log.error("Balance inquiry failed: " + e.getMessage(), e);
            ui.showError(e.getMessage());
            ui.showScanCard();
            state = State.SCAN_CARD;
        } catch (GiftCardRedeemException e) {
            log.error("Redeem failed: " + e.getMessage(), e);
            ui.showError(e.getMessage());
            ui.showSelectPaymentType();
            state = State.SELECT_PAYMENT_TYPE;
        }
    }

    public void confirmBalanceOk() {
        if (remainingBalance.compareTo(BigDecimal.ZERO) > 0) {
            ui.showTakeReceipt();
            ui.showThankYou();
            state = State.THANK_YOU;
        } else {
            ui.showDepositGiftCard();
            state = State.DEPOSIT_GIFT_CARD;
        }
    }

    public void depositGiftCardDone() {
        if (ticketPending.compareTo(BigDecimal.ZERO) > 0) {
            ui.showSelectPaymentType();
            state = State.SELECT_PAYMENT_TYPE;
        } else {
            ui.showTakeReceipt();
            ui.showThankYou();
            state = State.THANK_YOU;
        }
    }
}
