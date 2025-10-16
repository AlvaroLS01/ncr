package com.comerzzia.pos.ncr.messages;

public class DiscountApplied extends BasicNCRMessage {
	public static final String Scope = "Scope";
	public static final String ItemNumber = "ItemNumber";
	public static final String UPC = "UPC";
	public static final String Description = "Description.1";
	
	public static final String Amount = "Amount";
	
	public static final String RewardLocation = "RewardLocation";
	public static final String ShowRewardPoints = "ShowRewardPoints";
	
	public static final String AssociatedItemNumber = "AssociatedItemNumber";
	
	public DiscountApplied() {
		addField(new NCRField<String>(Scope, "string"));
		addField(new NCRField<String>(ItemNumber, "int"));
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(Description, "string"));
		
		addField(new NCRField<String>(Amount, "int"));
		
		addField(new NCRField<String>(RewardLocation, "int"));
		addField(new NCRField<String>(ShowRewardPoints, "int"));
		
		addField(new NCRField<String>(AssociatedItemNumber, "int"));
   }
}
