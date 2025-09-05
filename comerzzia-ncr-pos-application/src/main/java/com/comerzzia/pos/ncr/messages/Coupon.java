package com.comerzzia.pos.ncr.messages;

public class Coupon extends BasicNCRMessage {
	public static final String UPC = "UPC";
	public static final String Description = "Description";
	public static final String ItemNumber = "ItemNumber";
	public static final String Amount = "Amount";
	
	public Coupon() {
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(Description, "string"));
		addField(new NCRField<String>(ItemNumber, "int"));
		addField(new NCRField<String>(Amount, "int"));
   }
}
