package com.comerzzia.pos.ncr.messages;

import java.math.BigDecimal;

public class ItemSold extends BasicNCRMessage {
	public static final String UPC = "UPC";
	public static final String Description = "Description";
	public static final String ItemNumber = "ItemNumber";
	public static final String ExtendedPrice = "ExtendedPrice";
	public static final String Price = "Price";
	public static final String RequiresSecurityBagging = "RequiresSecurityBagging";
	public static final String RequiresSubsCheck = "RequiresSubsCheck";
	

	public static final String Quantity = "Quantity";
	public static final String Age = "Age";

	public static final String Weight = "Weight";
	public static final String TareCode = "TareCode";

	public static final String DiscountAmount = "DiscountAmount";
	public static final String DiscountDescription = "DiscountDescription.1";
	public static final String AssociatedItemNumber = "AssociatedItemNumber";
	public static final String LinkedItem = "LinkedItem";
	public static final String VisualVerifyRequired = "VisualVerifyRequired";
	public static final String RewardLocation = "RewardLocation";
	public static final String ShowRewardPoints = "ShowRewardPoints";
	

	protected ItemSold discount = null;
	
	//protected DiscountApplied discountApplied = new DiscountApplied();

	public ItemSold() {
		addField(new NCRField<String>(UPC, "string"));
		addField(new NCRField<String>(Description, "string"));
		addField(new NCRField<String>(ItemNumber, "int"));
		addField(new NCRField<String>(ExtendedPrice, "int"));
		addField(new NCRField<String>(Price, "int"));
		addField(new NCRField<String>(RequiresSecurityBagging, "int"));
		addField(new NCRField<String>(RequiresSubsCheck, "int"));

		addField(new NCRField<String>(Quantity, "int"));
		addField(new NCRField<String>(Age, "int"));

		addField(new NCRField<String>(Weight, "int"));
		addField(new NCRField<String>(TareCode, "int"));
		
		addField(new NCRField<String>(DiscountAmount, "int"));
		addField(new NCRField<String>(DiscountDescription, "string"));
		addField(new NCRField<String>(AssociatedItemNumber, "int"));
		addField(new NCRField<String>(LinkedItem, "string"));
		addField(new NCRField<String>(VisualVerifyRequired, "string"));
		addField(new NCRField<String>(RewardLocation, "int"));
		addField(new NCRField<String>(ShowRewardPoints, "int"));		
	}

	public void setDiscount(BigDecimal importeLineaConDescuento, BigDecimal importeAhorrado, String description) {
		if (discount == null) {
			discount = new ItemSold();
		}
//		discount.setFieldValue(ItemSold.ItemNumber, String.valueOf(new Integer(getFieldValue(ItemSold.ItemNumber)) +  1000) );
//		discount.setFieldIntValue(ItemSold.DiscountAmount, amount);
//		
//		discount.setFieldValue(ItemSold.AssociatedItemNumber, getFieldValue(ItemSold.ItemNumber));
//		discount.setFieldValue(ItemSold.RewardLocation, "3");
//		if (amount.compareTo(BigDecimal.ZERO) == 0) {
//   		   discount.setFieldValue(ItemSold.ShowRewardPoints, "0");
//   		   discount.setFieldValue(ItemSold.DiscountDescription, "Eliminado");
//		} else {
//		   discount.setFieldValue(ItemSold.ShowRewardPoints, "1");
//		   discount.setFieldValue(ItemSold.DiscountDescription, description);						
//		}
		
		discount.setFieldValue(ItemSold.ItemNumber, getFieldValue(ItemSold.ItemNumber));
		discount.setFieldIntValue(ItemSold.Price, importeLineaConDescuento);
		discount.setFieldValue(ItemSold.UPC, getFieldValue(ItemSold.UPC));

		if (importeLineaConDescuento.compareTo(BigDecimal.ZERO) == 0) {
//   		   discount.setFieldValue(ItemSold.Description, getFieldValue(ItemSold.Description));
			discount.setFieldValue(ItemSold.Description, "Eliminado");
//   		   discount.setFieldValue(ItemSold.Price, getFieldValue(ItemSold.Price));
		} else {
		   discount.setFieldValue(ItemSold.Description, getFieldValue(ItemSold.Description) + "\n" + description + " -" + importeAhorrado);						
		}
		
		/*
		discountApplied.setFieldValue(DiscountApplied.Scope, "Item");
		discountApplied.setFieldValue(DiscountApplied.ItemNumber, String.valueOf(new Integer(getFieldValue(ItemSold.ItemNumber)) +  1000) );
		discountApplied.setFieldValue(DiscountApplied.UPC, getFieldValue(ItemSold.UPC));
		discountApplied.setFieldIntValue(DiscountApplied.Amount, amount);
		discountApplied.setFieldValue(DiscountApplied.RewardLocation, "3");
		if (amount.compareTo(BigDecimal.ZERO) == 0) {
		   discountApplied.setFieldValue(DiscountApplied.ShowRewardPoints, "0");
		   discountApplied.setFieldValue(DiscountApplied.Description, "Eliminado");
		} else {
		   discountApplied.setFieldValue(DiscountApplied.ShowRewardPoints, "1");
		   discountApplied.setFieldValue(DiscountApplied.Description, description);
		}
		//discountApplied.setFieldValue(DiscountApplied.ShowRewardPoints, "1");
		discountApplied.setFieldValue(DiscountApplied.AssociatedItemNumber, getFieldValue(ItemSold.ItemNumber));
		*/		
	}
	
	public void clearDiscount() {
		discount = null;
	}
	
	public ItemSold getDiscountApplied() {
		return this.discount;
	}
}
