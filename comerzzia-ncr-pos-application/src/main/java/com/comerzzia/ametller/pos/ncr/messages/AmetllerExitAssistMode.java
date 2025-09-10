package com.comerzzia.ametller.pos.ncr.messages;

import com.comerzzia.pos.ncr.messages.BasicNCRMessage;

/**
 * NCR message used to exit the assist mode. It contains no fields and only
 * sets the message name to "ExitAssistMode".
 */
public class AmetllerExitAssistMode extends BasicNCRMessage {

    public AmetllerExitAssistMode() {
        // Step A5/B3/B5: send ExitAssistMode without fields
        setName("ExitAssistMode");
    }
}
