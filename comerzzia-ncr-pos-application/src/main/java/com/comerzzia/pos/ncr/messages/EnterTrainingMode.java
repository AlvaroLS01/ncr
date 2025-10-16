package com.comerzzia.pos.ncr.messages;

public class EnterTrainingMode extends BasicNCRMessage {
	public static final String UserId = "UserId";
	public static final String Password = "Password";
	
	public EnterTrainingMode() {
		addField(new NCRField<String>(UserId, "string"));
		addField(new NCRField<String>(Password, "string"));
   }
}
