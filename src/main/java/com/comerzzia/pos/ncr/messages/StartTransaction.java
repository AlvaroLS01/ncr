package com.comerzzia.pos.ncr.messages;

public class StartTransaction extends BasicNCRMessage {
	public static final String Type = "Type";
	public static final String Id = "Id";
	
	public StartTransaction() {
		addField(new NCRField<String>(Type, "string"));
		addField(new NCRField<String>(Id, "string"));
   }
}
