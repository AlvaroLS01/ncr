package com.comerzzia.pos.ncr.messages;

public class VoidTransaction extends BasicNCRMessage {
	public static final String Id = "Id";
	public static final String AttendantId = "AttendantId";
	
	public VoidTransaction() {
		addField(new NCRField<String>(Id, "string"));
		addField(new NCRField<String>(AttendantId, "string"));
   }
}
