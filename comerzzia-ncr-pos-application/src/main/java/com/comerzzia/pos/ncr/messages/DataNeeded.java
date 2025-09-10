package com.comerzzia.pos.ncr.messages;

public class DataNeeded extends BasicNCRMessage {
	public static final String Type = "Type";
	public static final String Id = "Id";
	public static final String Mode = "Mode";
	public static final String TopCaption1 = "TopCaption.1";
	public static final String SummaryInstruction1 = "SummaryInstruction.1";
	public static final String ButtonData1 = "ButtonData.1";
	public static final String ButtonText1 = "ButtonText.1";
	public static final String HideGoBack = "HideGoBack";
	public static final String EnableScanner = "EnableScanner";
	public static final String ButtonData2 = "ButtonData.2";
	public static final String ButtonText2 = "ButtonText.2";
	
	public DataNeeded() {
		addField(new NCRField<String>(Type, "int"));
		addField(new NCRField<String>(Id, "int"));
		addField(new NCRField<String>(Mode, "int"));
		addField(new NCRField<String>(TopCaption1, "string"));
		addField(new NCRField<String>(SummaryInstruction1, "string"));
		addField(new NCRField<String>(ButtonData1, "string"));
		addField(new NCRField<String>(ButtonText1, "string"));
		addField(new NCRField<String>(HideGoBack, "int"));
		addField(new NCRField<String>(EnableScanner, "int"));
		addField(new NCRField<String>(ButtonData2, "string"));
		addField(new NCRField<String>(ButtonText2, "string"));
   }
}
