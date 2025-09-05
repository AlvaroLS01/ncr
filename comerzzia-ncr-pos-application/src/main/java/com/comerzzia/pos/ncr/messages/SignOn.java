package com.comerzzia.pos.ncr.messages;

public class SignOn extends BasicNCRMessage {
	public static final String LaneNumber = "LaneNumber";
	public static final String UserId = "UserId";
	public static final String Password = "Password";
	public static final String Message = "Message.1";	
	
	public SignOn() {
		addField(new NCRField<String>(LaneNumber, "string"));
		addField(new NCRField<String>(UserId, "string"));
		addField(new NCRField<String>(Password, "string"));
		addField(new NCRField<String>(Message, "string"));
   }
}
