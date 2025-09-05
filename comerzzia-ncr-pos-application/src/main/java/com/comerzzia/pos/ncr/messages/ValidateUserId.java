package com.comerzzia.pos.ncr.messages;

public class ValidateUserId extends BasicNCRMessage {
	public static final String UserId = "UserId";
	public static final String Password = "Password";
	public static final String AuthenticationLevel = "AuthenticationLevel";
	public static final String Message = "Message.1";
	
	// not used
	public static final String role = "role";
	
	public static final String AUTH_LEVEL_INVALID = "0";
	public static final String AUTH_LEVEL_CASHIER = "1";
	public static final String AUTH_LEVEL_HEAD_CASHIER = "2";
	
	public ValidateUserId() {
		addField(new NCRField<String>(UserId, "string"));
		addField(new NCRField<String>(Password, "string"));
		addField(new NCRField<String>(AuthenticationLevel, "int"));
		addField(new NCRField<String>(Message, "string"));
		
		// not used
		addField(new NCRField<String>(role, "string"));
   }
}
