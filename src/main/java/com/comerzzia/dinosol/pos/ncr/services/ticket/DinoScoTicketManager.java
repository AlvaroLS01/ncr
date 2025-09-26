package com.comerzzia.dinosol.pos.ncr.services.ticket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.comarch.clm.partner.dto.ExtendedBalanceInquiryResponse;
import com.comarch.clm.partner.dto.TenderRedemptionGroupDataResponse;
import com.comerzzia.core.servicios.api.errorhandlers.ApiClientException;
import com.comerzzia.dinosol.api.client.loyalty.model.CouponDTO;
import com.comerzzia.dinosol.pos.ncr.services.ticket.auxiliar.LineaPlasticoInexistente;
import com.comerzzia.dinosol.pos.ncr.services.ticket.auxiliar.LineaPlasticoInexistenteException;
import com.comerzzia.dinosol.pos.ncr.services.virtualmoney.restrictions.RestrictionsService;
import com.comerzzia.dinosol.pos.services.auditorias.AuditoriaDto;
import com.comerzzia.dinosol.pos.services.auditorias.AuditoriasService;
import com.comerzzia.dinosol.pos.services.core.sesion.DinoSesionPromociones;
import com.comerzzia.dinosol.pos.services.cupones.CustomerCouponDTO;
import com.comerzzia.dinosol.pos.services.cupones.DinoCuponesService;
import com.comerzzia.dinosol.pos.services.payments.methods.types.DinoPaymentsManagerImpl;
import com.comerzzia.dinosol.pos.services.payments.methods.types.bp.BPManager;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.DinoCabeceraTicket;
import com.comerzzia.dinosol.pos.services.ticket.lineas.DinoLineaTicket;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.persistence.core.documentos.tipos.TipoDocumentoBean;
import com.comerzzia.pos.services.articulos.ArticuloNotFoundException;
import com.comerzzia.pos.services.core.documentos.DocumentoException;
import com.comerzzia.pos.services.cupones.CuponAplicationException;
import com.comerzzia.pos.services.cupones.CuponUseException;
import com.comerzzia.pos.services.cupones.CuponesServiceException;
import com.comerzzia.pos.services.cupones.CuponesServices;
import com.comerzzia.pos.services.mediospagos.MediosPagosService;
import com.comerzzia.pos.services.payments.PaymentDto;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.promociones.DocumentoPromocionable;
import com.comerzzia.pos.services.ticket.TicketVenta;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.TicketsServiceException;
import com.comerzzia.pos.services.ticket.cabecera.CabeceraTicket;
import com.comerzzia.pos.services.ticket.cabecera.TotalesTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketAbstract;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketException;
import com.comerzzia.pos.services.ticket.pagos.IPagoTicket;
import com.comerzzia.pos.services.ticket.pagos.PagoTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionLineaTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.config.SpringContext;
import com.comerzzia.pos.util.i18n.I18N;

@Component
@Primary
@SuppressWarnings({ "rawtypes", "unchecked" })
public class DinoScoTicketManager extends ScoTicketManager {

	public static final String X_SCO_NCR_CODART_TICKET_VACIO = "X_SCO_NCR.CODART_TICKET_VACIO";

	@Autowired
	private AuditoriasService auditoriasService;

	@Autowired
	private CuponesServices cuponesServices;

	@Autowired
	private RestrictionsService restrictionsService;

	private BPManager bpManager;

	@Override
	public void deleteTicketLine(Integer idLinea) {
		guardarAuditoriaEliminacionLinea(idLinea);

		super.deleteTicketLine(idLinea);
	}

	private void guardarAuditoriaEliminacionLinea(Integer idLinea) {
		LineaTicket linea = (LineaTicket) ticketPrincipal.getLinea(idLinea);

		AuditoriaDto auditoria = new AuditoriaDto();
		auditoria.setTipo("ANULACIÓN LÍNEA (SCO)");
		auditoria.setUidTicket(ticketPrincipal.getUidTicket());
		auditoria.setCodart(linea.getCodArticulo());
		auditoria.setPrecioAnterior(linea.getPrecioTotalConDto());
		auditoria.setCantidadLinea(linea.getCantidad());
		auditoriasService.guardarAuditoria(auditoria);
	}

	public boolean comprobarCupon(String codArticulo) throws CuponUseException, CuponesServiceException, CuponAplicationException {
		if (StringUtils.isBlank(codArticulo)) {
			log.debug("comprobarCupon() - Código vacío");
			return false;
		}
		log.debug("comprobarCupon() - Comprobando si el código " + codArticulo + " es un cupón.");

		boolean isCupon = false;

		boolean esCuponNuevo = ((DinoSesionPromociones) sesionPromociones).isCouponWithPrefix(codArticulo);
		boolean esCuponAntiguo = ((DinoCuponesService) cuponesServices).esCuponAntiguo(codArticulo);

		if (esCuponNuevo) {
			try {
				CouponDTO couponDto = ((DinoSesionPromociones) sesionPromociones).validateCoupon(codArticulo);

				for (CustomerCouponDTO cupon : getCuponesLeidos()) {
					if (cupon.getCouponCode().equals(codArticulo)) {
						// A petición de Dinosol, si es un cupón de los nuevos no se puede introducir dos veces en la
						// venta.
						log.debug("comprobarCupon() - Este cupón ya ha sido introducido en la venta y es un cupón de los nuevos.");
						throw new CuponAplicationException(I18N.getTexto("Este cupón ya ha sido introducido en la venta actual."));
					}
				}

				if (couponDto != null) {
					CustomerCouponDTO customerCouponDTO = new CustomerCouponDTO(couponDto.getCouponCode(), true, CustomerCouponDTO.CUPON_NUEVO);
					customerCouponDTO.setPromotionId(couponDto.getPromotionId());
					customerCouponDTO.setBalance(couponDto.getBalance());
					getCuponesLeidos().add(customerCouponDTO);

					isCupon = true;
				}
				else if (esCuponAntiguo) {
					CustomerCouponDTO coupon = new CustomerCouponDTO(codArticulo, false, CustomerCouponDTO.CUPON_ANTIGUO);
					getCuponesLeidos().add(coupon);

					isCupon = true;
				}
			}
			catch (ApiClientException exception) {
				if ("Registro no encontrado".equals(exception.getMessage())) {
					if (esCuponAntiguo) {
						CustomerCouponDTO coupon = new CustomerCouponDTO(codArticulo, false, CustomerCouponDTO.CUPON_ANTIGUO);
						getCuponesLeidos().add(coupon);

						isCupon = true;
					}
				}
				else {
					throw exception;
				}
			}
		}
		else if (esCuponAntiguo) {
			CustomerCouponDTO coupon = new CustomerCouponDTO(codArticulo, false, CustomerCouponDTO.CUPON_ANTIGUO);
			getCuponesLeidos().add(coupon);

			isCupon = true;
		}

		return isCupon;
	}

	public List<CustomerCouponDTO> getCuponesLeidos() {
		if (((DinoCabeceraTicket) ticketPrincipal.getCabecera()).getCuponesLeidos() == null) {
			((DinoCabeceraTicket) ticketPrincipal.getCabecera()).setCuponesLeidos(new ArrayList<>());
		}
		return ((DinoCabeceraTicket) ticketPrincipal.getCabecera()).getCuponesLeidos();
	}

	@Override
	public void ticketInitilize() {
		log.debug("ticketInitilize()");

		initSession();

		Map<Integer, PaymentDto> currentsPayments = new HashMap<Integer, PaymentDto>();
		((DinoPaymentsManagerImpl) paymentsManager).setCurrentPayments(currentsPayments);

		super.ticketInitilize();
	}

	public boolean aplicarCupon(CustomerCouponDTO cupon) throws CuponUseException, CuponesServiceException, CuponAplicationException {
		return ((DinoSesionPromociones) sesion.getSesionPromociones()).aplicarCupon(cupon, (DocumentoPromocionable) getTicket());
	}

	@Override
	public void saveEmptyTicket() {
		log.debug("saveEmptyTicket() - Inicio de salvado de un ticket vacío");
		// Inicializamos un ticket sin lineas
		TicketVentaAbono ticketVacio = SpringContext.getBean(TicketVentaAbono.class);
		ticketVacio.getCabecera().inicializarCabecera(ticketVacio);
		ticketVacio.inicializarTotales();
		ticketVacio.setCliente(ticketPrincipal.getCliente());
		ticketVacio.setCajero(ticketPrincipal.getCabecera().getCajero());
		IPagoTicket cambio = ticketsService.createPago();
		cambio.setMedioPago(MediosPagosService.medioPagoDefecto);
		ticketVacio.getCabecera().getTotales().setCambio(cambio);
		ticketVacio.getTotales().recalcular();
		ticketVacio.getCabecera().setDocumento(documentoActivo);
		ticketVacio.setEsDevolucion(ticketPrincipal.isEsDevolucion());
		// Añadimos los datos del contador obtenido antes
		ticketVacio.setIdTicket(ticketPrincipal.getIdTicket());
		ticketVacio.getCabecera().setSerieTicket(ticketPrincipal.getCabecera().getSerieTicket());
		ticketVacio.getCabecera().setCodTicket(ticketPrincipal.getCabecera().getCodTicket());
		ticketVacio.getCabecera().setDatosDocOrigen(ticketPrincipal.getCabecera().getDatosDocOrigen());
		ticketVacio.getCabecera().setUidTicket(ticketPrincipal.getCabecera().getUidTicket());

		addLineaVaciaFicticia();

		for (LineaTicket lineaOriginal : (List<LineaTicket>) ticketPrincipal.getLineas()) {
			ticketVacio.addLinea(lineaOriginal.clone());

			LineaTicket lineaNegativa = lineaOriginal.clone();

			List<PromocionLineaTicket> promocionesNuevas = new ArrayList<PromocionLineaTicket>();
			for (PromocionLineaTicket promocion : lineaNegativa.getPromociones()) {
				PromocionTicket promocionTicket = new PromocionTicket();
				promocionTicket.setIdPromocion(promocion.getIdPromocion());
				promocionTicket.setIdTipoPromocion(promocion.getIdTipoPromocion());
				promocionTicket.setCodAcceso(promocion.getCodAcceso());
				promocionTicket.setAcceso(promocion.getAcceso());
				promocionTicket.setExclusiva(false);
				promocionTicket.setTipoDescuento(promocion.getTipoDescuento());

				PromocionLineaTicket promocionNueva = new PromocionLineaTicket(promocionTicket);
				promocionNueva.setCantidadPromocion(promocion.getCantidadPromocion().negate());
				promocionNueva.addImporteTotalDto(promocion.getImporteTotalDtoMenosMargen().negate());

				promocionesNuevas.add(promocionNueva);
			}
			lineaNegativa.setPromociones(promocionesNuevas);

			lineaNegativa.setCantidad(lineaNegativa.getCantidad().negate());
			lineaNegativa.recalcularImporteFinal();
			ticketVacio.addLinea(lineaNegativa);
		}

		PagoTicket pagoVacio = ticketsService.createPago();
		pagoVacio.setMedioPago(MediosPagosService.medioPagoDefecto);
		pagoVacio.setImporte(BigDecimal.ZERO);
		ticketVacio.addPago(pagoVacio);

		// Lo salvamos
		try {
			ticketsService.registrarTicket(ticketVacio, documentoActivo, false);
		}
		catch (TicketsServiceException e) {
			log.error("salvarTicketVacio() - Ha ocurrido un error al salvar un ticket vacío: " + e.getMessage(), e);
		}
	}

	public void addLineaVaciaFicticia() {
		/* [DIN-118] */
		if (ticketPrincipal.getLineas() == null || ticketPrincipal.getLineas().isEmpty()) {
			log.debug("addLineaVaciaFicticia()");
			String codArtLineaVacia = variablesServices.getVariableAsString(X_SCO_NCR_CODART_TICKET_VACIO);

			try {
				createAndInsertTicketLine(codArtLineaVacia, "*", "*", null, new BigDecimal(1), null);
			}
			catch (Exception e) {
				log.error("addLineaVaciaFicticia() - Error al crear la línea ficticia para guardar el ticket vacío: " + e.getMessage(), e);
			}
		}
	}

	public synchronized LineaTicket nuevaLineaArticuloCodart(String codArticulo, BigDecimal cantidad) throws LineaTicketException {
		log.debug("nuevaLineaArticuloCodart() - Creando nueva línea de artículo para comprobar el precio para el artículo " + codArticulo);

		LineaTicketAbstract linea = null;
		BigDecimal precio = null;

		try {
			linea = lineasTicketServices.createLineaArticulo((TicketVenta) ticketPrincipal, codArticulo, null, null, cantidad, precio, createLinea());
			linea.setCantidad(tratarSignoCantidad(linea.getCantidad(), linea.getCabecera().getCodTipoDocumento()));

			ticketPrincipal.getLineas().add(linea);
			ticketPrincipal.getTotales().recalcular();
		}
		catch (ArticuloNotFoundException e) {
			log.error("nuevaLineaArticuloCodart() - Ha habido un error al al comprobar el precio del artículo: " + e.getMessage(), e);
		}
		return (LineaTicket) linea;
	}

	@Override
	public LineaTicket addLineToTicket(LineaTicketAbstract linea) {
		super.addLineToTicket(linea);

		procesarArticuloPlastico(linea);
		
		return (LineaTicket) linea;
	}
	
	public BigDecimal tratarSignoDocumento(BigDecimal valor, String codTipoDoc) {

		BigDecimal valorRetorno = valor;

		try {
			TipoDocumentoBean doc = sesion.getAplicacion().getDocumentos().getDocumento(codTipoDoc);

			if (doc.isSignoPositivo()) {
				valorRetorno = valorRetorno.abs();
			}
			else if (doc.isSignoNegativo()) {
				valorRetorno = valorRetorno.abs().negate();
			}

		}
		catch (DocumentoException ex) {
			log.error("No se pudo obtener el documento para establecer el signo del documentod", ex);
		}
		return valorRetorno;
	}

	public BigDecimal tratarSignoCantidad(BigDecimal cantidad, String codTipoDoc) {
		return tratarSignoDocumento(cantidad, codTipoDoc);
	}

	protected LineaTicketAbstract createLinea() {
		return SpringContext.getBean(LineaTicket.class);
	}

	public void recalcularConPromociones() {
		Integer puntosAnteriores = 0;
		sesionPromociones.aplicarPromociones((TicketVentaAbono) ticketPrincipal);
		ticketPrincipal.getTotales().recalcular();
		ticketPrincipal.getTotales().addPuntos(puntosAnteriores);
	}

	public BPManager buscarBpManager() {
		for (PaymentMethodManager paymentMethodManager : this.paymentsManager.getPaymentsMehtodManagerAvailables().values()) {
			if ((paymentMethodManager instanceof BPManager)) {
				this.bpManager = ((BPManager) paymentMethodManager);
				break;
			}
		}

		return this.bpManager;
	}

	@Override
	public void initSession() {
		super.initSession();

		restrictionsService.searchActiveRestrictions();
	}

	private void procesarArticuloPlastico(LineaTicketAbstract linea) {
		try {
			LineaTicketAbstract lineaPlastico = getLineaArticuloPlastico(linea.getCantidad(), linea);
			if (lineaPlastico != null) {
				if (lineaPlastico instanceof LineaPlasticoInexistente) {
					ticketPrincipal.getTotales().recalcular();
					throw new LineaPlasticoInexistenteException(I18N.getTexto("El envase de plástico asociado no está disponible. Consulte con el administrador."));
				}
				
				contadorLinea++;
				lineaPlastico.setIdLinea(contadorLinea);
				((DinoLineaTicket) linea).setIdLineaAsociado(lineaPlastico.getIdLinea());
				((DinoLineaTicket) lineaPlastico).setIdLineaAsociado(linea.getIdLinea());

				((TicketVentaAbono)ticketPrincipal).addLinea((LineaTicket)lineaPlastico);
				recalculateTicket();
			}
		}
		catch (Exception e) {
			log.error("procesarArticuloPlastico() - Ha habido un error al comprobar el artículo plástico: " + e.getMessage(), e);
		}
	}
	
	protected LineaTicketAbstract getLineaArticuloPlastico(BigDecimal cantidad, LineaTicketAbstract linea) throws LineaTicketException, ArticuloNotFoundException {
		LineaTicketAbstract lineaPlastico = null;
		boolean articuloEsEnvasePlastico = ((DinoLineaTicket) linea).isEsPlastico();

		if (articuloEsEnvasePlastico && BigDecimalUtil.isMayorACero(cantidad)) {
			throw new LineaTicketException(I18N.getTexto("Este artículo no se puede vender de manera unitaria"));
		}
		else {
			String codArtPlasticoAsociado = ((DinoLineaTicket) linea).getCodArtPlasticoAsociado();
			if (codArtPlasticoAsociado != null) {
				lineaPlastico = null;
				try {
					lineaPlastico = lineasTicketServices.createLineaArticulo((TicketVenta) ticketPrincipal, codArtPlasticoAsociado, null, null, cantidad, null, createLinea());
				}
				catch (ArticuloNotFoundException e) {
					log.error("getLineaArticuloPlastico() - No se ha encontrado el artículo de plástico asociado.");
					return new LineaPlasticoInexistente();
				}
				BigDecimal cantidadLinea = linea.getCantidad();
				if (BigDecimalUtil.isMenor(cantidadLinea, BigDecimal.ONE)) {
					cantidadLinea = BigDecimal.ONE;
				}
				lineaPlastico.setCantidad(tratarSignoCantidad(cantidadLinea, linea.getCabecera().getCodTipoDocumento()));
			}
		}
		return lineaPlastico;
	}
	
	public List<Class<?>> getTicketClasses(TipoDocumentoBean tipoDocumento) {
		List<Class<?>> classes = new LinkedList<>();

		// Obtenemos la clase root
		Class<?> clazz = SpringContext.getBean(getTicketClass(tipoDocumento)).getClass();

		// Generamos lista de clases "ancestras" de la principal
		Class<?> superClass = clazz.getSuperclass();
		while (!superClass.equals(Object.class)) {
			classes.add(superClass);
			superClass = superClass.getSuperclass();
		}
		// Las ordenamos descendentemente
		Collections.reverse(classes);

		// Añadimos la clase principal y otras necesarias
		classes.add(clazz);
		classes.add(SpringContext.getBean(LineaTicket.class).getClass());
		classes.add(SpringContext.getBean(CabeceraTicket.class).getClass());
		classes.add(SpringContext.getBean(TotalesTicket.class).getClass());
		classes.add(SpringContext.getBean(PagoTicket.class).getClass());

		return classes;
	}
	
	public BigDecimal calcularImportePagoTarjetaBP(String tarjeta) {
		BigDecimal saldo = BigDecimal.ZERO;
		BigDecimal saldoDisponible = null;
		BigDecimal tramo = null;

		ExtendedBalanceInquiryResponse saldoResponse;
		try {
			buscarBpManager();
			saldoResponse = bpManager.getSaldo(tarjeta);

			if (saldoResponse == null) {
				log.debug("calcularImportePagoTarjetaBP() - No se ha obtenido respuesta de BP");
				return BigDecimal.ZERO;
			}
			
			if (saldoResponse.getTenderRedemptionGroupDataList() == null) {
				log.debug("calcularImportePagoTarjetaBP() - No se han encontrado datos de oferta");
				return BigDecimal.ZERO;
			}
			
			for (TenderRedemptionGroupDataResponse virtual : saldoResponse.getTenderRedemptionGroupDataList()) {
				/* Filtramos para solo coger uno de los dos saldos que nos trae. */
				if (BPManager.VIRTUAL_MONEY_CODE.equals(virtual.getTenderRedemptionGroupCode())) {
					saldo = BigDecimal.valueOf(virtual.getVirtualMoneyBalance());
				}

				if (BigDecimalUtil.isIgualACero(saldo)) {
					return BigDecimal.ZERO;
				}
				BigDecimal total = ticketPrincipal.getTotales().getPendiente();

				tramo = bpManager.getTramo();
				BigDecimal eurosTramo = bpManager.getEurosTramo();
				boolean condicionQuemadoPositiva = bpManager.isCondicionQuemadoPositiva();

				saldoDisponible = BigDecimal.ZERO;
				if (tramo == null || eurosTramo == null || BigDecimalUtil.isMenorACero(tramo) || BigDecimalUtil.isMenorACero(eurosTramo)) {
					throw new RuntimeException(I18N.getTexto("No está configurado el pago con la tarjeta BP."));
				}
				else {
					if (condicionQuemadoPositiva && BigDecimalUtil.isMayorACero(total)) {
						saldoDisponible = total.divide(tramo, 0, RoundingMode.FLOOR);
					}
					else {
						saldoDisponible = total.divide(tramo, 0, RoundingMode.CEILING);
					}
					saldoDisponible = BigDecimalUtil.redondear(saldoDisponible.multiply(eurosTramo));
				}

				if (BigDecimalUtil.isMayor(saldoDisponible, saldo)) {
					saldoDisponible = saldo;
				}
			}

		}
		catch (Exception e) {
			log.error("calcularImportePagoTarjetaBP() - Error al realizar la consulta: " + e.getMessage(), e);
			return BigDecimal.ZERO;
		}
		return saldoDisponible;
	}
	
	public void payBPCard(String numTarjeta, BigDecimal importe) {
		try {
			bpManager.addParameter(BPManager.PARAM_NUMERO_TARJETA_BP, numTarjeta);
			bpManager.pay(importe);
		}
		catch (Exception e) {
			log.error("payBPCard() - Ha ocurrido un error al pagar con la tarjeta BP: " + e.getMessage(), e);
		}
	}
		
}
