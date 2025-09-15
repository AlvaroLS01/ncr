package com.comerzzia.pos.ncr.messages;

public class ChangeItemPrice extends BasicNCRMessage {
	
	public static final String ItemNumber = "ItemNumber";
	public static final String NewPrice = "NewPrice";
	public static final String UPC = "UPC";
	
	public ChangeItemPrice() {
		addField(new NCRField<String>(ItemNumber, "int"));
		addField(new NCRField<String>(NewPrice, "string"));
		addField(new NCRField<String>(UPC, "string"));
   }
}
