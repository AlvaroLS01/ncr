package com.comerzzia.pos.ncr.messages;

public class TransactionVoided extends BasicNCRMessage {
	public static final String Id = "Id";
	public static final String Message = "Message.1";
	
	public TransactionVoided() {
		addField(new NCRField<String>(Id, "string"));
		addField(new NCRField<String>(Message, "string"));
   }
}
