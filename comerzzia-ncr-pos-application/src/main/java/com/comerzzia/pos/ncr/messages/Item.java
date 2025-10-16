package com.comerzzia.pos.ncr.messages;

public class Item extends BasicNCRMessage {
	public static final String UPC = "UPC";
	public static final String ScanCodeType = "ScanCodeType";
	public static final String Scanned = "Scanned";
	
	public static final String Quantity = "Quantity";
	public static final String Price = "Price";
	
	public static final String Weight = "Weight";
	public static final String TareCode = "TareCode";
	
	public static final String Age = "Age";
	
	// not used
	public static final String LabelData = "LabelData";
	public static final String PicklistEntry = "PicklistEntry";
	
	public Item() {
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(ScanCodeType, "string"));
		addField(new NCRField<String>(Scanned, "int"));
		
		addField(new NCRField<String>(Quantity, "int"));
		addField(new NCRField<String>(Price, "int"));
		
		addField(new NCRField<String>(Weight, "int"));
		addField(new NCRField<String>(TareCode, "int"));
		
		addField(new NCRField<String>(Age, "int"));
		
		// not used
		addField(new NCRField<String>(LabelData, "string"));
		addField(new NCRField<String>(PicklistEntry, "string"));
   }
}
