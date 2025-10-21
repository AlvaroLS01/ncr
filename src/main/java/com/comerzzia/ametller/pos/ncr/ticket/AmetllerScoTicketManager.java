package com.comerzzia.ametller.pos.ncr.ticket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketAbstract;
import com.comerzzia.pos.services.cupones.CuponAplicationException;
import com.comerzzia.pos.util.i18n.I18N;

import org.apache.commons.lang3.StringUtils;

@Service
@Primary
public class AmetllerScoTicketManager extends ScoTicketManager {

	private static final BigDecimal DESCUENTO25 = new BigDecimal("25.00");
	private boolean descuento25Activo = false;
	private final Set<Integer> lineasConDescuento25 = new HashSet<>();
	private final LinkedHashSet<String> cuponesLeidos = new LinkedHashSet<>();
	private final Set<String> cuponesAplicados = new HashSet<>();

	public void setDescuento25Activo(boolean activo) {
		this.descuento25Activo = activo;
	}

	public boolean hasDescuento25Aplicado(LineaTicket linea) {
		return linea != null && lineasConDescuento25.contains(linea.getIdLinea());
	}

	public void removeDescuento25(Integer idLinea) {
		if (idLinea != null) {
			lineasConDescuento25.remove(idLinea);
		}
	}

	private void resetCupones() {
		cuponesLeidos.clear();
		cuponesAplicados.clear();
	}

	public boolean registrarCupon(String codigo) throws CuponAplicationException {
		if (StringUtils.isBlank(codigo)) {
			log.debug("registrarCupon() - Código vacío");
			return false;
		}

		if (sesionPromociones == null || !sesionPromociones.isCoupon(codigo)) {
			log.debug("registrarCupon() - El código " + codigo + " no es un cupón válido para la sesión actual");
			return false;
		}

		if (!cuponesLeidos.add(codigo)) {
			throw new CuponAplicationException(I18N.getTexto("Este cupón ya ha sido introducido"));
		}

		cuponesAplicados.remove(codigo);

		return true;
	}

	public List<String> getCuponesLeidos() {
		return new ArrayList<>(cuponesLeidos);
	}

	public List<String> getCuponesPendientes() {
		return cuponesLeidos.stream().filter(codigo -> !cuponesAplicados.contains(codigo)).collect(Collectors.toList());
	}

	public void marcarCuponAplicado(String codigo) {
		if (cuponesLeidos.contains(codigo)) {
			cuponesAplicados.add(codigo);
		}
	}

	public void descartarCupon(String codigo) {
		cuponesLeidos.remove(codigo);
		cuponesAplicados.remove(codigo);
	}

	@Override
	public LineaTicket addLineToTicket(LineaTicketAbstract linea) {
		LineaTicket added = super.addLineToTicket(linea);
		if (descuento25Activo && added != null) {
			added.setDescuentoManual(DESCUENTO25);
			recalculateTicket();
			if (added.getIdLinea() != null) {
				lineasConDescuento25.add(added.getIdLinea());
			}
		}
		return added;
	}

	@Override
	public void deleteTicketLine(Integer idLinea) {
		super.deleteTicketLine(idLinea);
		removeDescuento25(idLinea);
	}

	@Override
	public void ticketInitilize() {
		super.ticketInitilize();
		lineasConDescuento25.clear();
		resetCupones();
	}

	@Override
	public void initSession() {
		super.initSession();
		lineasConDescuento25.clear();
		resetCupones();
	}
}