package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.giftcard.GiftCardProvider;
import com.comerzzia.ametller.pos.ncr.giftcard.GiftCardProvider.RedeemResponse;
import com.comerzzia.ametller.pos.ncr.giftcard.GiftCardException;
import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.PayManager;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.services.payments.PaymentException;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.events.PaymentErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentsErrorEvent;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;
import com.comerzzia.pos.persistence.giftcard.GiftCardBean;

@Service
@Primary
public class AmetllerPayManager extends PayManager {

    @Autowired
    private GiftCardProvider giftCardProvider;

    @Override
    protected void activateTenderMode() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            ((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(false);
        }
        super.activateTenderMode();
    }

    @Override
    protected void trayPay(Tender message) {
        if ("Gift Card".equalsIgnoreCase(message.getFieldValue(Tender.TenderType))) {
            payWithGiftCard(message);
        } else {
            super.trayPay(message);
        }
    }

    private void payWithGiftCard(Tender message) {
        String barcode = message.getFieldValue(Tender.UPC);
        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
        String paymentCode = scoTenderTypeToComerzziaPaymentCode(message.getFieldValue(Tender.TenderType));
        PaymentMethodManager manager = paymentsManager.getPaymentsMehtodManagerAvailables().get(paymentCode);
        try {
            BigDecimal balance = giftCardProvider.getBalance(barcode);
            BigDecimal pending = ticketManager.getTicket().getTotales().getPendiente();
            BigDecimal apply = balance.min(pending);
            RedeemResponse redeem = giftCardProvider.redeem(barcode, apply,
                    String.valueOf(ticketManager.getTicket().getIdTicket()));

            BigDecimal approvedAmount = redeem.getApprovedAmount();
            if (approvedAmount == null) {
                approvedAmount = BigDecimal.ZERO;
            }
            BigDecimal remainingBalance = redeem.getRemainingBalance();
            if (remainingBalance == null) {
                remainingBalance = BigDecimal.ZERO;
            }

            GiftCardBean card = new GiftCardBean();
            card.setNumTarjetaRegalo(barcode);
            card.setSaldo(remainingBalance);
            card.setSaldoProvisional(approvedAmount);
            card.setImportePago(approvedAmount);
            card.setUidTransaccion(redeem.getTransactionId());
            manager.addParameter(GiftCardManager.PARAM_TARJETA, card);

            paymentsManager.pay(paymentCode, approvedAmount);
        } catch (GiftCardException e) {
            TenderException ex = new TenderException();
            ex.setFieldValue(TenderException.ExceptionId, "0");
            ex.setFieldValue(TenderException.ExceptionType, "0");
            ex.setFieldValue(TenderException.TenderType, message.getFieldValue(Tender.TenderType));
            ex.setFieldValue(TenderException.Message, e.getMessage());
            ncrController.sendMessage(ex);
        } catch (PaymentException e) {
            PaymentErrorEvent errorEvent = new PaymentErrorEvent(this, e.getPaymentId(), e, e.getErrorCode(), e.getMessage());
            PaymentsErrorEvent event = new PaymentsErrorEvent(this, errorEvent);
            paymentsManager.getEventsHandler().paymentsError(event);
        } catch (Exception e) {
            TenderException ex = new TenderException();
            ex.setFieldValue(TenderException.ExceptionId, "0");
            ex.setFieldValue(TenderException.ExceptionType, "0");
            ex.setFieldValue(TenderException.TenderType, message.getFieldValue(Tender.TenderType));
            ex.setFieldValue(TenderException.Message, e.getMessage());
            ncrController.sendMessage(ex);
        }
    }
}
