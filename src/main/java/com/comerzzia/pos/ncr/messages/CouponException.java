package com.comerzzia.pos.ncr.messages;

public class CouponException extends BasicNCRMessage {
	public static final String UPC = "UPC";
	public static final String ExceptionType = "ExceptionType";
	public static final String ExceptionId = "ExceptionId";
	public static final String Message = "Message.1";
	public static final String Title = "Title";
	
	public CouponException() {
		addField(new NCRField<String>(Title, "string"));
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(ExceptionType, "int"));
		addField(new NCRField<String>(ExceptionId, "int"));
		addField(new NCRField<String>(Message, "string"));
   }
}
