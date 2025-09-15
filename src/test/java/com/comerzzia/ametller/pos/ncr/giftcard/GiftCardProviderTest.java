package com.comerzzia.ametller.pos.ncr.giftcard;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GiftCardProviderTest {

    private InMemoryGiftCardProvider provider;

    @BeforeEach
    public void setUp() {
        provider = new InMemoryGiftCardProvider();
        provider.setBalance("123", new BigDecimal("25.00"));
    }

    @Test
    public void testRedeemFullAmount() throws Exception {
        GiftCardProvider.RedeemResponse resp = provider.redeem("123", new BigDecimal("10.00"), "T1");
        assertEquals(new BigDecimal("10.00"), resp.getApprovedAmount());
        assertEquals(new BigDecimal("15.00"), resp.getRemainingBalance());
        assertEquals(new BigDecimal("15.00"), provider.getBalance("123"));
    }

    @Test
    public void testRedeemPartial() throws Exception {
        GiftCardProvider.RedeemResponse resp = provider.redeem("123", new BigDecimal("30.00"), "T1");
        assertEquals(new BigDecimal("25.00"), resp.getApprovedAmount());
        assertEquals(BigDecimal.ZERO, resp.getRemainingBalance());
        assertEquals(BigDecimal.ZERO, provider.getBalance("123"));
    }

    @Test
    public void testRedeemErrorUnknownCard() {
        assertThrows(GiftCardException.class, () -> provider.redeem("999", BigDecimal.ONE, "T1"));
    }
}
