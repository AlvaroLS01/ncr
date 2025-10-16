package com.comerzzia.pos.ncr.messages;

public class TrainingModeEntered extends BasicNCRMessage {
	public static final String Message = "Message.1";	
	
	public TrainingModeEntered() {
		addField(new NCRField<String>(Message, "string"));
   }
}
