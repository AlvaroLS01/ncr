package com.comerzzia.pos.ncr.messages;

/**
 * Mensaje provisional utilizado para reconocer el comando {@code ExitAssistMode}
 * de NCR y permitir que la factoría de mensajes cree una instancia sin lanzar
 * un error {@code Message class not found}.
 */
public class ExitAssistMode extends BasicNCRMessage {
    // no se necesitan campos
}
