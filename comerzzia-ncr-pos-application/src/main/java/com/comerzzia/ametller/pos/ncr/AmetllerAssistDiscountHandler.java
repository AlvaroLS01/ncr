package com.comerzzia.ametller.pos.ncr;

import java.io.IOException;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.comerzzia.ametller.pos.ncr.messages.AmetllerDiscountCommand;
import com.comerzzia.ametller.pos.ncr.messages.AmetllerExitAssistMode;
import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.Command;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.messages.Item;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.NCRField;
import com.comerzzia.pos.ncr.messages.Totals;

/**
 * Handler that orchestrates the messaging sequence for the 25% discount in
 * assist mode. It sends and waits for the exact NCR messages defined in the
 * functional documentation.
 */
@Component
public class AmetllerAssistDiscountHandler {

    private static final Logger log = Logger.getLogger(AmetllerAssistDiscountHandler.class);

    @Autowired
    private NCRController ncrController;

    private boolean active = false;

    /**
     * Activates the 25% discount mode sending steps A1 to A5.
     *
     * @return {@code true} if activation succeeded, {@code false} otherwise
     */
    public synchronized boolean activate() {
        if (active) {
            log.debug("Discount already active; ignoring duplicate activation");
            return true;
        }
        try {
            // A1 – SCO⇒POS: command "descuento"
            log.debug("[A1] Sending command descuento");
            ncrController.sendMessage(new AmetllerDiscountCommand());

            // A2 – POS⇒SCO: Dataneeded informativo
            log.debug("[A2] Waiting for Dataneeded");
            BasicNCRMessage a2 = ncrController.readNCRMessage();
            if (!(a2 instanceof DataNeeded)) {
                log.warn("Expected Dataneeded in A2 but received " + (a2 != null ? a2.getName() : "null"));
                return false;
            }

            // A3 – SCO⇒POS: DataNeededReply OK
            log.debug("[A3] Sending DataNeededReply confirmation");
            DataNeededReply confirm = new DataNeededReply();
            confirm.addField(new NCRField<String>("Confirmation", "int"));
            confirm.setFieldValue("Confirmation", "1");
            confirm.setFieldValue(DataNeededReply.Type, "1");
            confirm.setFieldValue(DataNeededReply.Id, "2");
            ncrController.sendMessage(confirm);

            // A4 – POS⇒SCO: Dataneeded clear
            log.debug("[A4] Waiting for Dataneeded clear");
            BasicNCRMessage a4 = ncrController.readNCRMessage();
            if (!(a4 instanceof DataNeeded)) {
                log.warn("Expected Dataneeded clear in A4 but received " + (a4 != null ? a4.getName() : "null"));
            }

            // A5 – SCO⇒POS: DataNeededReply clear + ExitAssistMode
            log.debug("[A5] Sending DataNeededReply clear and ExitAssistMode");
            DataNeededReply clear = new DataNeededReply();
            clear.setFieldValue(DataNeededReply.Type, "0");
            clear.setFieldValue(DataNeededReply.Id, "0");
            ncrController.sendMessage(clear);
            ncrController.sendMessage(new AmetllerExitAssistMode());

            active = true;
            return true;
        } catch (IOException e) {
            log.error("Error activating discount mode", e);
            return false;
        }
    }

    /**
     * Sends an item while the discount mode is active following steps B1 to B5.
     */
    public synchronized void sendDiscountItem(String upc, String scanCodeType, String labelData) {
        if (!active) {
            log.debug("sendDiscountItem called with inactive mode");
            return;
        }
        try {
            // B1 – SCO⇒POS: Item
            log.debug("[B1] Sending Item");
            Item item = new Item();
            item.setFieldValue(Item.UPC, upc);
            item.setFieldValue(Item.ScanCodeType, scanCodeType);
            item.setFieldValue(Item.Scanned, "1");
            item.setFieldValue(Item.LabelData, labelData);
            item.setFieldValue(Item.PicklistEntry, "0");
            ncrController.sendMessage(item);

            // B2 – POS⇒SCO: itemsold
            log.debug("[B2] Waiting for ItemSold");
            BasicNCRMessage b2 = ncrController.readNCRMessage();
            if (!(b2 instanceof ItemSold)) {
                log.warn("Expected ItemSold in B2 but received " + (b2 != null ? b2.getName() : "null"));
            }

            // B3 – SCO⇒POS: ExitAssistMode
            log.debug("[B3] Sending ExitAssistMode");
            ncrController.sendMessage(new AmetllerExitAssistMode());

            // B4 – POS⇒SCO: Totals
            log.debug("[B4] Waiting for Totals");
            BasicNCRMessage b4 = ncrController.readNCRMessage();
            if (!(b4 instanceof Totals)) {
                log.warn("Expected Totals in B4 but received " + (b4 != null ? b4.getName() : "null"));
            }

            // B5 – SCO⇒POS: ExitAssistMode
            log.debug("[B5] Sending ExitAssistMode");
            ncrController.sendMessage(new AmetllerExitAssistMode());
        } catch (IOException e) {
            log.error("Error during discounted item sequence", e);
            deactivate();
        }
    }

    /**
     * Deactivates the discount mode sending step C1.
     */
    public synchronized void deactivate() {
        if (!active) {
            return;
        }
        // C1 – SCO⇒POS: command "EnteredCustomerMode"
        log.debug("[C1] Sending command EnteredCustomerMode");
        Command cmd = new Command();
        cmd.setFieldValue(Command.Command, "EnteredCustomerMode");
        ncrController.sendMessage(cmd);
        active = false;
    }

    public boolean isActive() {
        return active;
    }
}
