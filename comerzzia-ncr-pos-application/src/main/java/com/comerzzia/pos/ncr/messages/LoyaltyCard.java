package com.comerzzia.pos.ncr.messages;

public class LoyaltyCard extends BasicNCRMessage {
	public static final String AccountNumber = "AccountNumber";
	public static final String CountryCode = "CountryCode";
	public static final String EntryMethod = "EntryMethod";

	public static final String CardType = "CardType";
	public static final String Status = "Status";
	public static final String Points = "Points";
	public static final String Amount = "Amount";
	public static final String Message = "Message.1";
	
	public static final String STATUS_REJECTED = "0";
	public static final String STATUS_ACCEPTED = "1";
	public static final String STATUS_ALREADY_SCANNED = "2";
	
	public LoyaltyCard() {
		addField(new NCRField<String>(AccountNumber, "string"));
		addField(new NCRField<String>(CountryCode, "string"));
		addField(new NCRField<String>(EntryMethod, "int"));
		addField(new NCRField<String>(Message, "string"));
		
		addField(new NCRField<String>(CardType, "string"));
		addField(new NCRField<String>(Status, "int"));
		addField(new NCRField<String>(Points, "int"));
		addField(new NCRField<String>(Amount, "int"));
   }
}
