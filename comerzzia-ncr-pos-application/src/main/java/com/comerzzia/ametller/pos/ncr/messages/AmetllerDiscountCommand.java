package com.comerzzia.ametller.pos.ncr.messages;

import com.comerzzia.pos.ncr.messages.Command;

public class AmetllerDiscountCommand extends Command {

	public AmetllerDiscountCommand() {
		super();
		setName("Command");
		setFieldValue(Command, "descuento");
	}
}
