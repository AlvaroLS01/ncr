package com.comerzzia.dinosol.pos.ncr.services.ticket.auxiliar;

import com.comerzzia.pos.services.ticket.lineas.LineaTicketException;

public class LineaPlasticoInexistenteException extends LineaTicketException {

	/**
	 * 
	 */
	private static final long serialVersionUID = -6837721702904465307L;

	public LineaPlasticoInexistenteException(String texto) {
		super(texto);
	}

}
