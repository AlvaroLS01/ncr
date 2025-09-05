package com.comerzzia.pos.ncr.messages;

public class InitializationComplete extends BasicNCRMessage {
	public static String LaneNumber = "LaneNumber";
	public static String Version = "Version";
	public static String HeartbeatTimeout = "HeartbeatTimeout";
	
	public InitializationComplete() {
	   addField(new NCRField<String>(LaneNumber, "string"));
	   addField(new NCRField<String>(Version, "string"));	   
	   addField(new NCRField<Integer>(HeartbeatTimeout, "int"));
   }
}
