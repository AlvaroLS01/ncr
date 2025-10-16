package com.comerzzia.pos.ncr.messages;

public class ItemException extends BasicNCRMessage {
	public static final String UPC = "UPC";
	public static final String NotFound = "NotFound";
	public static final String Recalled = "Recalled";
	public static final String TimeRestricted = "TimeRestricted";
	public static final String WeightRequired = "WeightRequired";
	public static final String QuantityRequired = "QuantityRequired";
	public static final String QuantityRestricted = "QuantityRestricted";
	public static final String PriceRequired = "PriceRequired";
	public static final String Inactive = "Inactive";
	public static final String ExceptionType = "ExceptionType";
	public static final String ExceptionId = "ExceptionId";
	public static final String Message = "Message.1";
	public static final String TopCaption = "TopCaption.1";

	public ItemException() {
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(NotFound, "int"));
		addField(new NCRField<String>(Recalled, "int"));
		addField(new NCRField<String>(TimeRestricted, "int"));
		addField(new NCRField<String>(WeightRequired, "int"));
		addField(new NCRField<String>(QuantityRequired, "int"));
		addField(new NCRField<String>(QuantityRestricted, "int"));
		addField(new NCRField<String>(PriceRequired, "int"));
		addField(new NCRField<String>(Inactive, "int"));
		addField(new NCRField<String>(ExceptionType, "int"));
		addField(new NCRField<String>(ExceptionId, "int"));
		addField(new NCRField<String>(Message, "string"));
		addField(new NCRField<String>(TopCaption, "string"));
	}
}
