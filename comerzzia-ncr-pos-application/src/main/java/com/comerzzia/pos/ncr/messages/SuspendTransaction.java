package com.comerzzia.pos.ncr.messages;

public class SuspendTransaction extends BasicNCRMessage {
	public static final String Id = "Id";
	
	public SuspendTransaction() {
		addField(new NCRField<String>(Id, "string"));
   }
}
