package com.comerzzia.dinosol.pos.ncr.services.parking;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.validator.routines.checkdigit.CheckDigitException;
import org.apache.commons.validator.routines.checkdigit.EAN13CheckDigit;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.ticket.ITicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.util.config.AppConfig;
import com.comerzzia.pos.util.i18n.I18N;

@Component
public class ParkingService {

	private Logger log = Logger.getLogger(ParkingService.class);

	public static final String ID_VARIABLE_TIPO_PARKING = "X_PARKING_TIPO";

	public static final String ID_VARIABLE_MINUTOS_CORTESIA = "X_PARKING_MINUTOS_CORTESIA";
	public static final String ID_VARIABLE_FORMATO_CODIGO = "X_PARKING_FORMATO_CODIGO";
	public static final String ID_VARIABLE_CODART_PARKING = "X_PARKING_CODART";
	public static final String ID_VARIABLE_CODART_EXTRAVIO = "X_PARKING_CODART_EXTRAVIO";
	public static final String ID_VARIABLE_PREFIJO = "X_PARKING_PREFIJO";
	public static final String ID_VARIABLE_TERMINAL = "X_PARKING_TERMINAL";

	@Autowired
	private VariablesServices variablesServices;

	public boolean isParkingActivo() {
		String tipoParking = variablesServices.getVariableAsString(ID_VARIABLE_TIPO_PARKING);
		return StringUtils.isNotBlank(tipoParking);
	}

	public boolean isParkingExterno() {
		return StringUtils.equalsIgnoreCase(variablesServices.getVariableAsString(ID_VARIABLE_TIPO_PARKING), "E")
		        || StringUtils.equalsIgnoreCase(variablesServices.getVariableAsString(ID_VARIABLE_FORMATO_CODIGO), "03");
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public boolean isTicketTieneArticuloParking(ITicket ticket) {
		String codartParking = variablesServices.getVariableAsString(ID_VARIABLE_CODART_PARKING);
		String codartExtravio = variablesServices.getVariableAsString(ID_VARIABLE_CODART_EXTRAVIO);

		boolean tieneArticuloParking = false;

		for (LineaTicket linea : (List<LineaTicket>) ticket.getLineas()) {
			if (linea.getCodArticulo().equals(codartParking) || linea.getCodArticulo().equals(codartExtravio)) {
				tieneArticuloParking = true;
				break;
			}
		}

		return tieneArticuloParking;
	}

	public DatosParkingDto obtenerDatosParking(String codigoParking) throws Exception {
		log.debug("obtenerDatosParking() - Leyendo parking con código de barras: " + codigoParking);
		
		Date horaEntrada = null;

		// En modo desarrollo, si poner *<minutos> te hace la simulación de la hora de entrada sin tener que calcular un
		// código de barras
		if (AppConfig.modoDesarrollo && "*".equals(StringUtils.left(codigoParking, 1))) {
			horaEntrada = DateUtils.addMinutes(new Date(), Integer.valueOf(codigoParking.substring(1)) * -1);
		}
		else {
			horaEntrada = leerCodigoBarras(codigoParking);
		}

		if (horaEntrada != null) {
			Date horaSalida = new Date();
			long milisegundosDiferencia = horaSalida.getTime() - horaEntrada.getTime();
			Integer minutosDiferencia = Math.round(milisegundosDiferencia / 1000 / 60);
			if (minutosDiferencia < 0) {
				String mensajeError = I18N.getTexto("La fecha del código de barras introducido no es válida ya que es un fecha futura.");
				throw new IllegalArgumentException(mensajeError);
			}

			String[] resultado = generarCodigoBarrasSalida(horaEntrada);
			String codigo = resultado[0];

			DatosParkingDto datosParking = new DatosParkingDto();
			datosParking.setCodigoBarrasSalida(codigo);
			datosParking.setHoraSalida(resultado[1]);

			String codartParking = variablesServices.getVariableAsString(ID_VARIABLE_CODART_PARKING);;
			datosParking.setCodartParking(codartParking);

			datosParking.setMinutosDiferencia(minutosDiferencia);

			return datosParking;
		}
		else {
			return null;
		}
	}

	protected Date leerCodigoBarras(String codigoParking) {
		String fecha = StringUtils.substring(codigoParking, 4, 7);

		String hora = StringUtils.substring(codigoParking, 7, 12);
		Integer horaDia = new Integer(new Integer(hora) / 3600);
		Integer minuto = new Integer((new Integer(hora) - (horaDia * 3600)) / 60);

		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.DAY_OF_YEAR, new Integer(fecha));
		calendar.set(Calendar.HOUR_OF_DAY, horaDia);
		calendar.set(Calendar.MINUTE, minuto);

		Date horaEntrada = calendar.getTime();

		return horaEntrada;
	}

	protected String[] generarCodigoBarrasSalida(Date horaEntrada) throws CheckDigitException {
		String terminal = variablesServices.getVariableAsString(ID_VARIABLE_TERMINAL);
		String prefijo = variablesServices.getVariableAsString(ID_VARIABLE_PREFIJO);
		String formatoParking = variablesServices.getVariableAsString(ID_VARIABLE_FORMATO_CODIGO);

		String[] resultado = new String[2];
		resultado[0] = "";
		resultado[1] = "";

		if ("01".equals(formatoParking)) {
			Date horaSalida = new Date();

			Calendar calendar = Calendar.getInstance();
			calendar.setTime(horaSalida);

			Integer minutosCortesia = variablesServices.getVariableAsInteger(ID_VARIABLE_MINUTOS_CORTESIA);
			Integer minutos = new Integer(calendar.get(Calendar.HOUR_OF_DAY) * 3600 + calendar.get(Calendar.MINUTE) * 60 + minutosCortesia * 60);

			SimpleDateFormat dateFormat = new SimpleDateFormat("D");
			String dia = StringUtils.leftPad(dateFormat.format(horaSalida), 3, '0');

			String codigoBarrasSalida = prefijo + terminal + dia + minutos;
			codigoBarrasSalida = codigoBarrasSalida + new EAN13CheckDigit().calculate(codigoBarrasSalida);

			Integer horaDia = new Integer(minutos / 3600);
			Integer minuto = new Integer((minutos - (horaDia * 3600)) / 60);
			String horaSalidaStr = StringUtils.leftPad(horaDia.toString(), 2, '0') + ":" + StringUtils.leftPad(minuto.toString(), 2, '0');

			resultado[0] = codigoBarrasSalida;
			resultado[1] = horaSalidaStr;
		}

		return resultado;
	}

	public DataNeeded generarMensajePantallaLecturaParking() {
		DataNeeded msg = new DataNeeded();
		msg.setFieldValue(DataNeeded.Type, "3");
		msg.setFieldValue(DataNeeded.Id, "1");
		msg.setFieldValue(DataNeeded.Mode, "0");
		msg.setFieldValue(DataNeeded.TopCaption1, I18N.getTexto("Parking"));
		msg.setFieldValue(DataNeeded.SummaryInstruction1, I18N.getTexto("Escanee ahora su ticket de parking o pulse Continuar si no tiene parking."));
		msg.setFieldValue(DataNeeded.EnableScanner, "1");
		msg.setFieldValue(DataNeeded.ButtonData1, "Continuar");
		msg.setFieldValue(DataNeeded.ButtonText1, "Continuar");
		msg.setFieldValue(DataNeeded.HideGoBack, "1");
		return msg;
	}

	public DataNeeded generarMensajeErrorLecturaParking(Exception e) {
		DataNeeded msg = new DataNeeded();
		msg.setFieldValue(DataNeeded.Type, "1");
		msg.setFieldValue(DataNeeded.Id, "2");
		msg.setFieldValue(DataNeeded.Mode, "0");
		msg.setFieldValue(DataNeeded.TopCaption1, I18N.getTexto("Parking"));
		msg.setFieldValue(DataNeeded.SummaryInstruction1, I18N.getTexto("Ticket no encontrado."));
		msg.setFieldValue(DataNeeded.ButtonData1, "OK");
		msg.setFieldValue(DataNeeded.ButtonText1, "OK");
		msg.setFieldValue(DataNeeded.HideGoBack, "1");
		return msg;
	}

}
