package com.comerzzia.pos.ncr.messages;

public class Language extends BasicNCRMessage {
	public static final String CustomerLanguage = "CustomerLanguage";
	
	public Language() {
		addField(new NCRField<String>(CustomerLanguage, "string"));
   }
}
