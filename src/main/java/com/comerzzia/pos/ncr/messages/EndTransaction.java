package com.comerzzia.pos.ncr.messages;

public class EndTransaction extends BasicNCRMessage {
	public static final String Id = "Id";
	
	public EndTransaction() {
		addField(new NCRField<String>(Id, "string"));
   }
}
