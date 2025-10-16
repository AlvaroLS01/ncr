package com.comerzzia.pos.ncr.messages;

public class ItemPriceChanged extends BasicNCRMessage {
	
	public static final String UPC = "UPC";
	public static final String ItemNumber = "ItemNumber";
	public static final String NewPrice = "NewPrice";
	public static final String ExtendedPrice = "ExtendedPrice";
	public static final String Quantity = "Quantity";
	public static final String MessageX = "Message.x";
	
	public ItemPriceChanged() {
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(ItemNumber, "int"));
		addField(new NCRField<String>(NewPrice, "string"));
		addField(new NCRField<String>(ExtendedPrice, "string"));
		addField(new NCRField<String>(Quantity, "string"));
		addField(new NCRField<String>(MessageX, "string"));
   }
}
