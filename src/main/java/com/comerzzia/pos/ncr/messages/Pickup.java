package com.comerzzia.pos.ncr.messages;

public class Pickup extends BasicNCRMessage {
	public static final String Amount = "Amount";
	
	public Pickup() {
		addField(new NCRField<String>(Amount, "int"));
   }
}
