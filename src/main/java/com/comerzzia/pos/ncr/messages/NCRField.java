package com.comerzzia.pos.ncr.messages;

import java.math.BigDecimal;

public class NCRField<t> {
	protected String name;
	protected String ftype;
	protected t value;
	protected Class<?> javaType;

	public NCRField(String name, String ftype, Class<?> javaType) {
	   this.name = name;
	   this.ftype = ftype;
	   if (javaType == null) {
		   this.javaType = String.class;
	   } else {
		   this.javaType = javaType;
	   }
	}
	
	public NCRField(String name, String ftype) {
	   this(name, ftype, null);
	}	
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getFtype() {
		return ftype;
	}
	public void setFtype(String ftype) {
		this.ftype = ftype;
	}
	public t getValue() {
		return value;
	}
	public void setValue(t value) {
		this.value = value;
	}
	
	@SuppressWarnings("unchecked")
	public void setStringValue(String value) {
		if (value == null) {
			this.value = null;
			return;
		}
		
		if (javaType == String.class) {
		   setValue((t) new String(value));		   
		} else if (javaType == Integer.class) {
		   setValue((t) Integer.valueOf(value));	
		}
	}
	
	public String getStringValue() {
		return value.toString();
	}
	
	public BigDecimal getBigDecimalValue(int decimalPlaces) {
		if (decimalPlaces > 0) {
			return new BigDecimal(getStringValue()).divide(new BigDecimal(Math.pow(10, decimalPlaces)));	
		} else {
			return new BigDecimal(getStringValue());
		}	   
	}
}
