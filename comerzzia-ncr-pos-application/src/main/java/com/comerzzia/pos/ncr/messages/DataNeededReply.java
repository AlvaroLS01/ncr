package com.comerzzia.pos.ncr.messages;

public class DataNeededReply extends BasicNCRMessage {
	public static final String Type = "Type";
	public static final String Id = "Id";
	public static final String Data1 = "Data.1";
	public static final String EntryMethod = "EntryMethod";
	public static final String ScanCodeType = "ScanCodeType";
	
	public DataNeededReply() {
		addField(new NCRField<String>(Type, "int"));
		addField(new NCRField<String>(Id, "int"));
		addField(new NCRField<String>(Data1, "string"));
		addField(new NCRField<String>(EntryMethod, "int"));
		addField(new NCRField<String>(ScanCodeType, "string"));
   }
}
