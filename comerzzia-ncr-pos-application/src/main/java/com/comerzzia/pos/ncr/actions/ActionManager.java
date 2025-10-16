package com.comerzzia.pos.ncr.actions;

import com.comerzzia.pos.ncr.messages.BasicNCRMessage;

public interface ActionManager {
   void processMessage(BasicNCRMessage message);
}
