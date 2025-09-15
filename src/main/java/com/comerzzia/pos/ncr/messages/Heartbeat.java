package com.comerzzia.pos.ncr.messages;

public class Heartbeat extends BasicNCRMessage {
	public static final String Time = "Time";
	public static final String Response = "Response";

	public static final String RESPONSE_TO_TB_REQUEST = "0";
	public static final String RESPONSE_POS_INITIATED = "1";
	
	public Heartbeat() {
		addField(new NCRField<String>(Time, "string"));
		addField(new NCRField<String>(Response, "int"));
   }
}
