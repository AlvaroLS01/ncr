package com.comerzzia.dinosol.pos.ncr.actions.sale.seleccionpromos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.comerzzia.dinosol.pos.gui.ventas.tickets.DinoTicketManager;
import com.comerzzia.dinosol.pos.services.core.sesion.DinoSesionPromociones;
import com.comerzzia.dinosol.pos.services.cupones.DinoCuponesService;
import com.comerzzia.dinosol.pos.services.promociones.opciones.OpcionPromocionesDto;
import com.comerzzia.dinosol.pos.services.promociones.opciones.OpcionesPromocionService;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.DinoCabeceraTicket;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.opciones.AhorroPromoDto;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.opciones.OpcionPromocionesSeleccionadaDto;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.persistence.tickets.aparcados.TicketAparcadoBean;
import com.comerzzia.pos.services.promociones.DocumentoPromocionable;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.config.SpringContext;
import com.comerzzia.pos.util.format.FormatUtil;
import com.comerzzia.pos.util.i18n.I18N;
import com.comerzzia.pos.util.xml.MarshallUtil;
import com.comerzzia.pos.util.xml.MarshallUtilException;

@Service
public class SeleccionPromosManager {

	private Logger log = Logger.getLogger(SeleccionPromosManager.class);

	@Autowired
	private OpcionesPromocionService opcionesPromocionService;

	@Autowired
	private DinoSesionPromociones sesionPromociones;

	public DataNeeded calcularOpcionesPromociones(ScoTicketManager ticketManager) {
		try {
			Map<OpcionPromocionesDto, DocumentoPromocionable> resultadoOpciones = calcularOpciones(ticketManager);

			return mandarMensajeSeleccionOpcion(resultadoOpciones);
		}
		catch (Exception e) {
			log.error("calcularOpcionesPromociones() - Ha habido un error al calcular las opciones de promoción: " + e.getMessage(), e);
			return null;
		}
	}

	private Map<OpcionPromocionesDto, DocumentoPromocionable> calcularOpciones(ScoTicketManager ticketManager) throws Exception {
		TicketAparcadoBean ticketAparcado = crearTicketAparcado(ticketManager);
		Map<OpcionPromocionesDto, DocumentoPromocionable> resultadoOpciones = new HashMap<OpcionPromocionesDto, DocumentoPromocionable>();
		for (OpcionPromocionesDto opcion : opcionesPromocionService.getOpciones()) {
			DocumentoPromocionable ticket = calcularAplicacionOpcionTicketCopia(ticketAparcado, opcion);
			if (ticket != null) {
				resultadoOpciones.put(opcion, ticket);
			}
		}
		return resultadoOpciones;
	}

	private TicketAparcadoBean crearTicketAparcado(ScoTicketManager ticketManager) throws MarshallUtilException {
		log.debug("crearTicketAparcado() - Creando ticket aparcado base para simulaciones.");
		byte[] xmlTicket = MarshallUtil.crearXML(ticketManager.getTicket());
		log.debug("crearTicketAparcado() - Ticket base: " + new String(xmlTicket));
		TicketAparcadoBean ticketAparcado = new TicketAparcadoBean();
		ticketAparcado.setTicket(xmlTicket);
		return ticketAparcado;
	}

	private DocumentoPromocionable calcularAplicacionOpcionTicketCopia(TicketAparcadoBean ticketAparcado, OpcionPromocionesDto opcion) throws Exception {
		log.debug("calcularAplicacionOpcionTicketCopia() - Realizando simulación de aplicación de promociones para opción " + opcion.getTitulo());

		DinoTicketManager ticketManagerAux = SpringContext.getBean(DinoTicketManager.class);
		ticketManagerAux.recuperarDatosTicket(ticketAparcado);
		DocumentoPromocionable ticket = (DocumentoPromocionable) ticketManagerAux.getTicket();

		sesionPromociones.aplicarOpcionPromociones(opcion, ticket);

		log.debug("calcularAplicacionOpcionTicketCopia() - Finalizada simulación de aplicación de promociones para opción " + opcion.getTitulo());

		return ticket;
	}

	private DataNeeded mandarMensajeSeleccionOpcion(Map<OpcionPromocionesDto, DocumentoPromocionable> resultadoOpciones) {
		DataNeeded dataNeeded = new DataNeeded();
		dataNeeded.setFieldValue(DataNeeded.Type, "4");
		dataNeeded.setFieldValue(DataNeeded.Id, "1");
		dataNeeded.setFieldValue(DataNeeded.Mode, "0");
		dataNeeded.setFieldValue(DataNeeded.TopCaption1, opcionesPromocionService.getTituloVisor());
		dataNeeded.setFieldValue(DataNeeded.EnableScanner, "0");
		dataNeeded.setFieldValue(DataNeeded.ButtonData1, "Opt1");
		dataNeeded.setFieldValue(DataNeeded.ButtonText1, I18N.getTexto("Opc1: Cupón descuento"));
		dataNeeded.setFieldValue(DataNeeded.ButtonData2, "Opt2");
		dataNeeded.setFieldValue(DataNeeded.ButtonText2, I18N.getTexto("Opc2: Descuento directo + cupón descuento"));
		dataNeeded.setFieldValue(DataNeeded.HideGoBack, "1");

		List<CalculoOpcionPromocionesDto> calculosOpciones = new ArrayList<CalculoOpcionPromocionesDto>();
		for (OpcionPromocionesDto opcion : resultadoOpciones.keySet()) {
			CalculoOpcionPromocionesDto calculoOpcionPromociones = calcularAhorroOpcion(opcion, resultadoOpciones);
			calculosOpciones.add(calculoOpcionPromociones);
		}
		
		if(calculosOpciones.size() != 2) {
			return null;
		}
		else if (calculosOpciones.get(0).hayAhorro() && calculosOpciones.get(1).hayAhorro()) {
			CalculoOpcionPromocionesDto opcion1 = calculosOpciones.get(0);
			CalculoOpcionPromocionesDto opcion2 = calculosOpciones.get(1);
			
			String ahorro1Formateado = "";
			String ahorro2Formateado = "";
			
			if(opcion1.isOpcionConDescuentoDirecto()) {
				ahorro1Formateado = I18N.getTexto("Cupón {0} EUR", FormatUtil.getInstance().formateaImporte(opcion2.getAhorroAFuturo()));
				ahorro2Formateado = I18N.getTexto("Dto - {0} EUR + Cupón {1} EUR", FormatUtil.getInstance().formateaImporte(opcion1.getAhorroMenosMargen()), FormatUtil.getInstance().formateaImporte(opcion1.getAhorroAFuturo()));
			}
			else {
				ahorro1Formateado = I18N.getTexto("Cupón {0} EUR", FormatUtil.getInstance().formateaImporte(opcion1.getAhorroAFuturo()));
				ahorro2Formateado = I18N.getTexto("Dto - {0} EUR + Cupón {1} EUR", FormatUtil.getInstance().formateaImporte(opcion2.getAhorroMenosMargen()), FormatUtil.getInstance().formateaImporte(opcion2.getAhorroAFuturo()));
			}
			
			String texto = I18N.getTexto("Opc1: ") + ahorro1Formateado + System.lineSeparator() + I18N.getTexto("Opc2: ") + ahorro2Formateado;
			dataNeeded.setFieldValue(DataNeeded.SummaryInstruction1, texto);
			return dataNeeded;
		}
		else {
			return null;
		}
	}

	private CalculoOpcionPromocionesDto calcularAhorroOpcion(OpcionPromocionesDto opcion, Map<OpcionPromocionesDto, DocumentoPromocionable> resultadoOpciones) {
		DocumentoPromocionable ticket = resultadoOpciones.get(opcion);
		CalculoOpcionPromocionesDto calculoOpcionPromociones = new CalculoOpcionPromocionesDto();
		
		for (PromocionTicket promocion : ticket.getPromociones()) {
			List<String> promocionesOpcion = opcion.getPromociones();
			String idPromocionSap = promocion.getPromocion().getExtension(DinoCuponesService.ID_PROMOCION_SAP);
			BigDecimal ahorro = promocion.getImporteTotalAhorro();
			if (promocionesOpcion.contains(idPromocionSap) && BigDecimalUtil.isMayorACero(ahorro)) {
				log.debug("addPromocionesOpcion() - La promoción " + promocion.getIdPromocion() + " se ha podido aplicar en la simulación con un ahorro de " + ahorro);
				calculoOpcionPromociones.addPromocion(promocion);
			}
		}
		
		return calculoOpcionPromociones;
	}

	public void seleccionarOpcionPromocion(ScoTicketManager ticketManager, String data1) {
		int indiceOpcion = Integer.parseInt(data1.replace("Opt", ""));
		OpcionPromocionesDto opcion = opcionesPromocionService.getOpciones().get(indiceOpcion - 1);

		sesionPromociones.aplicarOpcionPromociones(opcion, (DocumentoPromocionable) ticketManager.getTicket());
		setOpcionSeleccionadaTicket(opcion, (TicketVentaAbono) ticketManager.getTicket());
	}

	private void setOpcionSeleccionadaTicket(OpcionPromocionesDto opcion, TicketVentaAbono ticket) {
		OpcionPromocionesSeleccionadaDto opcionSeleccionada = new OpcionPromocionesSeleccionadaDto();

		opcionSeleccionada.setTituloOpcion(opcion.getTitulo());

		String textoTicket = opcion.getTextoTicket();
		textoTicket = textoTicket.replaceAll("\\{", "");
		textoTicket = textoTicket.replaceAll("\\}", "");

		for (PromocionTicket promocion : ticket.getPromociones()) {
			String idPromocionSap = promocion.getPromocion().getExtension(DinoCuponesService.ID_PROMOCION_SAP);
			if (opcion.getPromociones().contains(idPromocionSap) && !promocion.getIdTipoPromocion().equals(13L)) {
				textoTicket = textoTicket.replaceAll(idPromocionSap, promocion.getImporteTotalAhorroAsString());
				opcionSeleccionada.addAhorroPromocion(promocion.getIdPromocion(), promocion.getImporteTotalAhorro(), promocion.getTipoDescuento());
			}
		}

		for (String promocionSap : opcion.getPromociones()) {
			if (textoTicket.contains(promocionSap)) {
				textoTicket = "";
			}
		}

		opcionSeleccionada.setTextoTicket(textoTicket);

		List<AhorroPromoDto> ahorrosPromociones = opcionSeleccionada.getAhorrosPromociones();
		if (ahorrosPromociones != null && !ahorrosPromociones.isEmpty()) {
			((DinoCabeceraTicket) ticket.getCabecera()).setOpcionPromocionesSeleccionada(opcionSeleccionada);
		}
		else {
			log.warn("setOpcionSeleccionadaTicket() - No se ha aplicado ningún ahorro en la venta.");
		}
	}

}