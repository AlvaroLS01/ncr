package com.comerzzia.ametller.pos.ncr.actions.init;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.actions.init.InitializeManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.Command;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;

@Service
@DependsOn("initializeManager")
public class AmetllerInitializeManager extends InitializeManager {

    @Autowired
    private NCRController ncrController;

    private boolean waitingDiscountConfirmation = false;

    @Override
    public void processMessage(BasicNCRMessage message) {
        if (message instanceof Command) {
            Command command = (Command) message;
            String cmd = command.getFieldValue(Command.Command);
            if ("descuento".equalsIgnoreCase(cmd)) {
                // A2
                DataNeeded dataNeeded = new DataNeeded();
                dataNeeded.setFieldValue(DataNeeded.Type, "1");
                dataNeeded.setFieldValue(DataNeeded.Id, "2");
                dataNeeded.setFieldValue(DataNeeded.Mode, "0");
                dataNeeded.setFieldValue(DataNeeded.SummaryInstruction1, "Descuento 25% activado");
                ncrController.sendMessage(dataNeeded);
                waitingDiscountConfirmation = true;
                return;
            }
        } else if (message instanceof DataNeededReply) {
            DataNeededReply reply = (DataNeededReply) message;
            String type = reply.getFieldValue(DataNeededReply.Type);
            String id = reply.getFieldValue(DataNeededReply.Id);
            if (waitingDiscountConfirmation && "1".equals(type) && "2".equals(id)) {
                // A4
                DataNeeded clean = new DataNeeded();
                clean.setFieldValue(DataNeeded.Type, "0");
                clean.setFieldValue(DataNeeded.Id, "0");
                clean.setFieldValue(DataNeeded.Mode, "0");
                ncrController.sendMessage(clean);
                waitingDiscountConfirmation = false;
                return;
            }
        }

        super.processMessage(message);
    }

    @PostConstruct
    @Override
    public void init() {
        super.init();
        ncrController.registerActionManager(DataNeededReply.class, this);
    }
}
