package com.comerzzia.pos.ncr.messages;

public class Loan extends BasicNCRMessage {
	public static final String Amount = "Amount";
	
	public Loan() {
		addField(new NCRField<String>(Amount, "int"));
   }
}
