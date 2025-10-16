package com.comerzzia.pos.ncr.messages;

public class Table extends BasicNCRMessage {
   public Table() {
	   addField(new NCRField<String>("Name", "string"));
	   addField(new NCRField<Integer>("Cell.1.1", "string"));
	   addField(new NCRField<Integer>("Cell.1.2", "string"));
   }
}
