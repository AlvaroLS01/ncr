package com.comerzzia.pos.ncr.messages;

public class Totals extends BasicNCRMessage {
	public static final String TotalAmount = "TotalAmount";
	public static final String FoodStampAmount = "FoodStampAmount";
	public static final String TaxAmount = "TaxAmount.A";
	public static final String BalanceDue = "BalanceDue";
	public static final String ItemCount = "ItemCount";
	public static final String DiscountAmount = "DiscountAmount";
	public static final String Points = "Points";

	public static final String TenderType = "TenderType";
	public static final String ChangeDue = "ChangeDue";
	
	public Totals() {
		addField(new NCRField<String>(TotalAmount, "int"));
		addField(new NCRField<String>(FoodStampAmount, "int"));
		addField(new NCRField<String>(TaxAmount, "int"));
		addField(new NCRField<String>(BalanceDue, "int"));
		addField(new NCRField<String>(ItemCount, "int"));
		addField(new NCRField<String>(DiscountAmount, "int"));
		addField(new NCRField<String>(Points, "int"));
		
		addField(new NCRField<String>(TenderType, "int"));
		addField(new NCRField<String>(ChangeDue, "int"));
   }
}
