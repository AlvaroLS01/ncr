package com.comerzzia.pos.ncr.messages;

public class Initialize extends BasicNCRMessage {
   public Initialize() {
	   addField(new NCRField<String>("Version", "string"));
	   addField(new NCRField<Integer>("HeartbeatTimeout", "int"));
	   addField(new NCRField<String>("PrimaryLanguage", "string"));
   }
}
