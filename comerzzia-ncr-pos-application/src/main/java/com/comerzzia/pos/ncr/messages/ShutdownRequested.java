package com.comerzzia.pos.ncr.messages;

public class ShutdownRequested extends BasicNCRMessage {
	public static final String Action = "Action";

	public ShutdownRequested() {
		addField(new NCRField<String>(Action, "string"));
	}
}
