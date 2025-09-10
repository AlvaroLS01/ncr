package com.comerzzia.ametller.pos.ncr.actions;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.Command;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;

@Lazy(false)
@Service
public class AmetllerCommandManager implements ActionManager {

    private static final Logger log = Logger.getLogger(AmetllerCommandManager.class);

    @Autowired
    private NCRController ncrController;

    @Autowired
    private ScoTicketManager ticketManager;

    @Override
    public void processMessage(BasicNCRMessage message) {
        if (message instanceof Command) {
            String cmd = message.getFieldValue(Command.Command);
            if (cmd != null && cmd.equalsIgnoreCase("Descuento")) {
                activarDescuento25();
                enviarDataNeeded25();
            } else if ("EnteredCustomerMode".equalsIgnoreCase(cmd)) {
                desactivarDescuento25();
            }
        } else if (message instanceof DataNeededReply) {
            String type = message.getFieldValue(DataNeededReply.Type);
            String id = message.getFieldValue(DataNeededReply.Id);
            if ("1".equals(type) && "2".equals(id)) {
                DataNeeded clear = new DataNeeded();
                clear.setFieldValue(DataNeeded.Type, "0");
                clear.setFieldValue(DataNeeded.Id, "0");
                clear.setFieldValue(DataNeeded.Mode, "0");
                ncrController.sendMessage(clear);
            }
        } else {
            log.warn("Message type not managed: " + message.getName());
        }
    }

    private void activarDescuento25() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            ((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(true);
        } else {
            log.warn("ScoTicketManager is not instance of AmetllerScoTicketManager");
        }
    }

    private void desactivarDescuento25() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            ((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(false);
        }
    }

    private void enviarDataNeeded25() {
        DataNeeded dn = new DataNeeded();
        dn.setFieldValue(DataNeeded.Type, "1");
        dn.setFieldValue(DataNeeded.Id, "2");
        dn.setFieldValue(DataNeeded.Mode, "0");
        dn.setFieldValue(DataNeeded.SummaryInstruction1,
                "Descuento 25% ACTIVADO.\n" +
                "Escanee los artículos con fecha próxima de caducidad.\n" +
                "Pulse OK para continuar.");
        ncrController.sendMessage(dn);
    }

    @PostConstruct
    public void init() {
        ncrController.registerActionManager(Command.class, this);
        ncrController.registerActionManager(DataNeededReply.class, this);
    }
}
