package com.comerzzia.pos.ncr.messages;

public class ItemVoided extends BasicNCRMessage {
	public static final String UPC = "UPC";
	public static final String ItemNumber = "ItemNumber";
	public static final String Message = "Message.1";
	
	public ItemVoided() {
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(ItemNumber, "int"));
		addField(new NCRField<String>(Message, "string"));
   }
}
