package com.comerzzia.pos.ncr.messages;

public class TenderAccepted extends BasicNCRMessage {
	public static final String Amount = "Amount";
	public static final String TenderType = "TenderType";
	public static final String Description = "Description";
	
	public TenderAccepted() {
		addField(new NCRField<String>(Amount, "int"));
		addField(new NCRField<String>(TenderType, "string"));
		addField(new NCRField<String>(Description, "string"));
   }
}
