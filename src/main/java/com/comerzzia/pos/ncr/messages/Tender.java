package com.comerzzia.pos.ncr.messages;

public class Tender extends BasicNCRMessage {
	public static final String Amount = "Amount";
	public static final String TenderType = "TenderType";
	
	public static final String UPC = "UPC";
	
	// not used
	public static final String CashBack = "CashBack";
	
	public Tender() {
		addField(new NCRField<String>(Amount, "int"));
		addField(new NCRField<String>(TenderType, "string"));
		
		addField(new NCRField<String>(UPC, "string"));
		
		// not used
		addField(new NCRField<String>(CashBack, "string"));
   }
}
