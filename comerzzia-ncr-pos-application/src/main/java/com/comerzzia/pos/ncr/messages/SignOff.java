package com.comerzzia.pos.ncr.messages;

public class SignOff extends BasicNCRMessage {
	public static final String LaneNumber = "LaneNumber";
	public static final String Message = "Message.1";	
	
	public SignOff() {
		addField(new NCRField<String>(LaneNumber, "string"));
		addField(new NCRField<String>(Message, "string"));
   }
}
