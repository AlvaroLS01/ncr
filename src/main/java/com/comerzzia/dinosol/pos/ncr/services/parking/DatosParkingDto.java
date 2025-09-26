package com.comerzzia.dinosol.pos.ncr.services.parking;

public class DatosParkingDto {

	private String codigoBarrasSalida;

	private String horaSalida;

	private String codartParking;

	private int minutosDiferencia;

	public String getCodigoBarrasSalida() {
		return codigoBarrasSalida;
	}

	public void setCodigoBarrasSalida(String codigoBarrasSalida) {
		this.codigoBarrasSalida = codigoBarrasSalida;
	}

	public String getHoraSalida() {
		return horaSalida;
	}

	public void setHoraSalida(String horaSalida) {
		this.horaSalida = horaSalida;
	}

	public String getCodartParking() {
		return codartParking;
	}

	public void setCodartParking(String codartParking) {
		this.codartParking = codartParking;
	}

	public int getMinutosDiferencia() {
		return minutosDiferencia;
	}

	public void setMinutosDiferencia(int minutosDiferencia) {
		this.minutosDiferencia = minutosDiferencia;
	}

}
