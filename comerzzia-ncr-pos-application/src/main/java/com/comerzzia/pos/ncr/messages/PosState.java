package com.comerzzia.pos.ncr.messages;

public class PosState extends BasicNCRMessage {
	public static final String State = "State";
	public static final String STATE_INACTIVE = "inactive";
	
	public PosState() {
		addField(new NCRField<String>(State, "string"));
   }
}
