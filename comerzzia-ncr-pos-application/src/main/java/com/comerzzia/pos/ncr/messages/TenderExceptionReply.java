package com.comerzzia.pos.ncr.messages;

public class TenderExceptionReply extends BasicNCRMessage {
	public static final String TenderType = "TenderType";
	public static final String ExceptionType = "ExceptionType";
	public static final String ExceptionId = "ExceptionId";
	public static final String UPC = "UPC";
	
	public static final String Data = "Data";
	
	public TenderExceptionReply() {
		addField(new NCRField<String>(TenderType, "string"));
		addField(new NCRField<String>(ExceptionType, "int"));
		addField(new NCRField<String>(ExceptionId, "int"));
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(Data, "string"));
   }
}
