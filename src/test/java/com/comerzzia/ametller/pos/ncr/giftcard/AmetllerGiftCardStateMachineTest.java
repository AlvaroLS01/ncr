package com.comerzzia.ametller.pos.ncr.giftcard;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

/**
 * Unit tests for {@link AmetllerGiftCardStateMachine}.
 */
public class AmetllerGiftCardStateMachineTest extends TestCase {

    private static class MockUi implements GiftCardUi {
        List<String> commands = new ArrayList<>();
        BigDecimal remaining;
        BigDecimal applied;

        @Override public void showSelectPaymentType() { commands.add("SelectPaymentType"); }
        @Override public void showOtherPayments() { commands.add("OtherPayments"); }
        @Override public void showScanCard() { commands.add("ScanCard"); }
        @Override public void showYourBalance(BigDecimal r, BigDecimal a) { commands.add("YourBalance"); remaining = r; applied = a; }
        @Override public void showDepositGiftCard() { commands.add("DepositGiftCard"); }
        @Override public void showTakeReceipt() { commands.add("TakeReceipt"); }
        @Override public void showThankYou() { commands.add("ThankYou"); }
        @Override public void showError(String message) { commands.add("Error:" + message); }
    }

    public void testFullPaymentWithRemainingBalance() throws Exception {
        InMemoryGiftCardProvider provider = new InMemoryGiftCardProvider();
        provider.setBalance("111", new BigDecimal("50"));
        MockUi ui = new MockUi();
        AmetllerGiftCardStateMachine sm = new AmetllerGiftCardStateMachine(provider, ui, new BigDecimal("30"), "T1");

        sm.giftCardSelected();
        sm.giftCardScanned("111");
        assertEquals(AmetllerGiftCardStateMachine.State.YOUR_BALANCE, sm.getState());
        assertEquals(new BigDecimal("20"), sm.getRemainingBalance());
        assertEquals(new BigDecimal("30"), sm.getLastApplied());

        sm.confirmBalanceOk();
        assertEquals(AmetllerGiftCardStateMachine.State.THANK_YOU, sm.getState());
        assertTrue(ui.commands.contains("TakeReceipt"));
        assertTrue(ui.commands.contains("ThankYou"));
    }

    public void testPartialPaymentRequiresDepositAndReturn() throws Exception {
        InMemoryGiftCardProvider provider = new InMemoryGiftCardProvider();
        provider.setBalance("222", new BigDecimal("10"));
        MockUi ui = new MockUi();
        AmetllerGiftCardStateMachine sm = new AmetllerGiftCardStateMachine(provider, ui, new BigDecimal("30"), "T1");

        sm.giftCardSelected();
        sm.giftCardScanned("222");
        assertEquals(new BigDecimal("0"), sm.getRemainingBalance());
        sm.confirmBalanceOk();
        assertEquals(AmetllerGiftCardStateMachine.State.DEPOSIT_GIFT_CARD, sm.getState());
        sm.depositGiftCardDone();
        assertEquals(AmetllerGiftCardStateMachine.State.SELECT_PAYMENT_TYPE, sm.getState());
        assertTrue(ui.commands.contains("SelectPaymentType"));
    }

    public void testExactBalanceDepositThenReceipt() throws Exception {
        InMemoryGiftCardProvider provider = new InMemoryGiftCardProvider();
        provider.setBalance("333", new BigDecimal("30"));
        MockUi ui = new MockUi();
        AmetllerGiftCardStateMachine sm = new AmetllerGiftCardStateMachine(provider, ui, new BigDecimal("30"), "T1");

        sm.giftCardSelected();
        sm.giftCardScanned("333");
        sm.confirmBalanceOk();
        assertEquals(AmetllerGiftCardStateMachine.State.DEPOSIT_GIFT_CARD, sm.getState());
        sm.depositGiftCardDone();
        assertEquals(AmetllerGiftCardStateMachine.State.THANK_YOU, sm.getState());
        assertTrue(ui.commands.contains("TakeReceipt"));
    }

    public void testBalanceInquiryErrorReturnsToScan() throws Exception {
        GiftCardProvider provider = new GiftCardProvider() {
            @Override public BigDecimal getBalance(String barcode) throws GiftCardBalanceException { throw new GiftCardBalanceException("fail"); }
            @Override public ApplyGiftCardResult redeem(String b, BigDecimal a, String t) throws GiftCardRedeemException { return null; }
        };
        MockUi ui = new MockUi();
        AmetllerGiftCardStateMachine sm = new AmetllerGiftCardStateMachine(provider, ui, new BigDecimal("30"), "T1");
        sm.giftCardSelected();
        sm.giftCardScanned("444");
        assertEquals(AmetllerGiftCardStateMachine.State.SCAN_CARD, sm.getState());
        assertTrue(ui.commands.contains("ScanCard"));
    }

    public void testRedeemErrorReturnsToSelectPaymentType() throws Exception {
        GiftCardProvider provider = new GiftCardProvider() {
            @Override public BigDecimal getBalance(String barcode) { return new BigDecimal("5"); }
            @Override public ApplyGiftCardResult redeem(String b, BigDecimal a, String t) throws GiftCardRedeemException { throw new GiftCardRedeemException("redeem"); }
        };
        MockUi ui = new MockUi();
        AmetllerGiftCardStateMachine sm = new AmetllerGiftCardStateMachine(provider, ui, new BigDecimal("30"), "T1");
        sm.giftCardSelected();
        sm.giftCardScanned("555");
        assertEquals(AmetllerGiftCardStateMachine.State.SELECT_PAYMENT_TYPE, sm.getState());
        assertTrue(ui.commands.contains("SelectPaymentType"));
    }
}
