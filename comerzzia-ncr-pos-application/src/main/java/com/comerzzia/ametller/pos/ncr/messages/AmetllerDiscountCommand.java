package com.comerzzia.ametller.pos.ncr.messages;

import com.comerzzia.pos.ncr.messages.Command;

/**
 * Custom command message used to activate the 25% discount mode on the SCO.
 *
 * <p>Sequence reference: A1 – Activación del modo –25%.<br>
 * Generates the XML:
 * {@code <message name="Command"><fields><field ftype="string" name="Command.1">descuento</field></fields></message>}
 * which is sent from SCO to POS when the operator presses the "Descuento 25%" button.</p>
 */
public class AmetllerDiscountCommand extends Command {

    public AmetllerDiscountCommand() {
        super();
        // Ensure the message name matches the NCR protocol
        setName("Command");
        // Step A1: send command.1="descuento" to enable discount mode
        setFieldValue(Command, "descuento");
    }
}
