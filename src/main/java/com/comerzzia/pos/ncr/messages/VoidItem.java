package com.comerzzia.pos.ncr.messages;

public class VoidItem extends BasicNCRMessage {
	public static final String UPC = "UPC";
	public static final String ItemNumber = "ItemNumber";
	
	// not used
	public static final String AttendantId = "AttendantId";
	public static final String Quantity = "Quantity";
	public static final String Scanned = "Scanned";
	public static final String LabelData = "LabelData";
	
	public VoidItem() {
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(ItemNumber, "int"));
		
		// not used
		addField(new NCRField<String>(AttendantId, "string"));
		addField(new NCRField<String>(Quantity, "string"));
		addField(new NCRField<String>(Scanned, "string"));
		addField(new NCRField<String>(LabelData, "string"));		
   }
}
