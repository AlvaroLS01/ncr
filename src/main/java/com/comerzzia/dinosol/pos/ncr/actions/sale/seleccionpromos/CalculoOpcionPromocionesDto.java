package com.comerzzia.dinosol.pos.ncr.actions.sale.seleccionpromos;

import java.math.BigDecimal;

import com.comerzzia.core.util.numeros.BigDecimalUtil;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;

public class CalculoOpcionPromocionesDto {

	private BigDecimal ahorroMenosMargen;

	private BigDecimal ahorroAFuturo;

	public CalculoOpcionPromocionesDto() {
		this.ahorroMenosMargen = BigDecimal.ZERO;
		this.ahorroAFuturo = BigDecimal.ZERO;
	}

	public BigDecimal getAhorroMenosMargen() {
		return ahorroMenosMargen;
	}

	public void setAhorroMenosMargen(BigDecimal ahorroMenosMargen) {
		this.ahorroMenosMargen = ahorroMenosMargen;
	}

	public BigDecimal getAhorroAFuturo() {
		return ahorroAFuturo;
	}

	public void setAhorroAFuturo(BigDecimal ahorroAFuturo) {
		this.ahorroAFuturo = ahorroAFuturo;
	}
	
	public boolean hayAhorro() {
		return BigDecimalUtil.isMayorACero(ahorroMenosMargen) || BigDecimalUtil.isMayorACero(ahorroAFuturo);
	}
	
	public boolean isOpcionConDescuentoDirecto() {
		return BigDecimalUtil.isMayorACero(ahorroMenosMargen);
	}

	public void addPromocion(PromocionTicket promocion) {
		if(promocion.isDescuentoMenosMargen()) {
			this.ahorroMenosMargen = this.ahorroMenosMargen.add(promocion.getImporteTotalAhorro());
		}
		else if(promocion.isDescuentoAFuturo()) {
			this.ahorroAFuturo = this.ahorroAFuturo.add(promocion.getImporteTotalAhorro());
		}
	}

}
