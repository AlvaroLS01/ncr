package com.comerzzia.pos.ncr.messages;

public class Command extends BasicNCRMessage {
	// not used
	public static final String Command = "Command.1";
	
	public Command() {
		addField(new NCRField<String>(Command, "string"));
	}
}
