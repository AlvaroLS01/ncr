package com.comerzzia.pos.ncr.messages;

public class TransactionSuspended extends BasicNCRMessage {
	public static final String Id = "Id";
	public static final String Message = "Message.1";
	
	public TransactionSuspended() {
		addField(new NCRField<String>(Id, "string"));
		addField(new NCRField<String>(Message, "string"));
   }
}
