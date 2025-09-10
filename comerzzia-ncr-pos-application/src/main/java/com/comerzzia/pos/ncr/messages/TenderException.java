package com.comerzzia.pos.ncr.messages;

public class TenderException extends BasicNCRMessage {
	public static final String TenderType = "TenderType";
	public static final String ExceptionType = "ExceptionType";
	public static final String ExceptionId = "ExceptionId";
	public static final String Message = "Message.1";
	
	public TenderException() {
		addField(new NCRField<String>(TenderType, "string"));
		addField(new NCRField<String>(ExceptionType, "int"));
		addField(new NCRField<String>(ExceptionId, "int"));
		addField(new NCRField<String>(Message, "string"));
   }
}
