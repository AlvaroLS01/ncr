package com.comerzzia.pos.ncr.ticket;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.comerzzia.core.servicios.ContextHolder;
import com.comerzzia.pos.persistence.core.documentos.tipos.TipoDocumentoBean;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.articulos.ArticuloNotFoundException;
import com.comerzzia.pos.services.core.documentos.DocumentoException;
import com.comerzzia.pos.services.core.documentos.Documentos;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.sesion.SesionPromociones;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.cupones.CuponesServices;
import com.comerzzia.pos.services.mediospagos.MediosPagosService;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.configuration.PaymentsMethodsConfiguration;
import com.comerzzia.pos.services.promociones.PromocionesServiceException;
import com.comerzzia.pos.services.ticket.ITicket;
import com.comerzzia.pos.services.ticket.Ticket;
import com.comerzzia.pos.services.ticket.TicketVenta;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.TicketsService;
import com.comerzzia.pos.services.ticket.TicketsServiceException;
import com.comerzzia.pos.services.ticket.cupones.CuponEmitidoTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketAbstract;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketException;
import com.comerzzia.pos.services.ticket.lineas.LineasTicketServices;
import com.comerzzia.pos.services.ticket.pagos.IPagoTicket;
import com.comerzzia.pos.services.ticket.pagos.PagoTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionLineaTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.servicios.impresion.ServicioImpresion;
import com.comerzzia.pos.util.config.SpringContext;


@SuppressWarnings("rawtypes")
@Service
public class ScoTicketManager {
    protected Logger log = Logger.getLogger(getClass());
    public static final String PLANTILLA_CUPON = "cupon_promocion";
    
	protected ITicket ticketPrincipal;
	
	protected TipoDocumentoBean documentoActivo; 
	
	protected Integer contadorLinea;
	
	@Autowired
	protected Sesion sesion;
	
	@Autowired
	protected SesionPromociones sesionPromociones;
    
    @Autowired
    protected LineasTicketServices lineasTicketServices;    
    
    @Autowired
    protected MediosPagosService mediosPagosService;    
    
    @Autowired
    protected TicketsService ticketsService;
    
	@Autowired
	protected VariablesServices variablesServices;
	
	@Autowired
	protected PaymentsMethodsConfiguration paymentsMethodsConfiguration;
	
	@Autowired
	protected CuponesServices cuponesService;
	
	protected PaymentsManager paymentsManager;
	
	protected boolean tenderMode = false;
	protected boolean trainingMode = false;
    
    public final ITicket getTicket() {
    	return ticketPrincipal;
    }
    
    public final Sesion getSesion() {
    	return sesion;
    }
	
    public void initSession() {
    	ticketPrincipal = null;
    	
    	if (paymentsManager == null) {
		   paymentsManager = ContextHolder.get().getBean(PaymentsManager.class);			
		   paymentsManager.setPaymentsMethodsConfiguration(paymentsMethodsConfiguration);
    	}
    }
    
    public PaymentsManager getPaymentsManager() {
    	return this.paymentsManager;
    }
    
	@SuppressWarnings("unchecked")
	public void ticketInitilize() {	
	    log.debug("ticketInitilize() - Creating ticket with default values...");
	    
        try {
			documentoActivo = sesion.getAplicacion().getDocumentos().getDocumento(Documentos.FACTURA_SIMPLIFICADA);

	        ticketPrincipal = SpringContext.getBean(getTicketClass(documentoActivo));
	        ticketPrincipal.setEsDevolucion(false);        
	        ticketPrincipal.getCabecera().inicializarCabecera(ticketPrincipal);
	        ((TicketVentaAbono)ticketPrincipal).inicializarTotales();
	        ticketPrincipal.setCliente(sesion.getAplicacion().getTienda().getCliente().clone());
	        ticketPrincipal.setCajero(sesion.getSesionUsuario().getUsuario());
	        ticketPrincipal.getCabecera().getTotales().setCambio(SpringContext.getBean(PagoTicket.class , MediosPagosService.medioPagoDefecto));
	        ticketPrincipal.getTotales().recalcular();
	        contadorLinea = 0;
	        
	        // Establecemos los parámetros de tipo de documento del ticket
	        ticketPrincipal.getCabecera().setDocumento(documentoActivo);
	        
	        // Actualizamos promociones activas
	        sesionPromociones.actualizarPromocionesActivas();
		} catch (DocumentoException | PromocionesServiceException e) {
			log.error(e);
			throw new RuntimeException("Internal error detected: " + e.getMessage(), e);
		}
    }
	
    @SuppressWarnings({ "unchecked" })
	protected Class<? extends ITicket> getTicketClass(TipoDocumentoBean tipoDocumento){
    	String claseDocumento = tipoDocumento.getClaseDocumento();
    	if(claseDocumento != null){
    		try {
				return (Class<? extends ITicket>) Class.forName(claseDocumento);
			} catch (ClassNotFoundException e) {
				log.error(String.format("getTicketClass() - Clase %s no encontrada, devolveremos TicketVentaAbono", claseDocumento));
			}
    	}
		return TicketVentaAbono.class;
    }	
    
    public void deleteTicketLine(Integer idLinea)  {
        log.debug("deleteTicketLine() - Eliminando línea de ticket con idLinea: " + idLinea);
        LineaTicket linea = ((TicketVentaAbono)ticketPrincipal).getLinea(idLinea);
        ticketPrincipal.getLineas().remove(linea);
        recalculateTicket();
    }    
    
    public void recalculateTicket(){    	
        sesionPromociones.aplicarPromociones((TicketVentaAbono) ticketPrincipal);
        ticketPrincipal.getTotales().recalcular();
    }    
        
    public LineaTicketAbstract createTicketLine(String codArticulo, String desglose1, String desglose2, String codigoBarras, BigDecimal cantidad, BigDecimal precio) throws LineaTicketException, ArticuloNotFoundException {
		log.debug("createTicketLine() - New ticket line...");

		LineaTicketAbstract linea = lineasTicketServices.createLineaArticulo((TicketVenta) ticketPrincipal, codArticulo, desglose1, desglose2, cantidad, precio, SpringContext.getBean(LineaTicket.class));			
		linea.setCodigoBarras(codigoBarras);
		
		return linea;		
	}
    
	public LineaTicket createAndInsertTicketLine(String codArticulo, String desglose1, String desglose2, String codigoBarras, BigDecimal cantidad, BigDecimal precio) throws LineaTicketException, ArticuloNotFoundException {
		LineaTicketAbstract linea = createTicketLine(codArticulo, desglose1, desglose2, codigoBarras, cantidad, precio);
		
		return addLineToTicket(linea);		
	}
	
	public LineaTicket addLineToTicket(LineaTicketAbstract linea) {
		log.debug("addLineToTicket() - Insert new line into ticket...");
				
        contadorLinea++;
        
        linea.setIdLinea(contadorLinea);
        
        ((TicketVentaAbono)ticketPrincipal).addLinea((LineaTicket)linea);
        
        recalculateTicket();
		
		return (LineaTicket) linea;
		
	}	

	@SuppressWarnings("unchecked")
	public PagoTicket addPayToTicket(String codigo, BigDecimal importe, Integer paymentId, boolean enterByCashier, boolean cashFlowRecorded) {
        log.debug("addPayToTicket() - Insert pay to ticket...");
        
		MedioPagoBean medioPago = mediosPagosService.getMedioPago(codigo);
		
		if(medioPago == null) {
			throw new IllegalArgumentException("The payment code " + codigo + " does not exists");
		}
		
		PagoTicket pago = SpringContext.getBean(PagoTicket.class, medioPago);
		
		pago.setEliminable(false);
		pago.setImporte(importe);
		pago.setIntroducidoPorCajero(enterByCashier);
		pago.setMovimientoCajaInsertado(cashFlowRecorded);
		
		if(paymentId != null) {
        	pago.setPaymentId(paymentId);
        }
		
		((TicketVenta)ticketPrincipal).addPago(pago);
		
        ticketPrincipal.getTotales().recalcular();
        
        return pago;
    }	
	
	public void saveTicket() {
        sesionPromociones.aplicarPromocionesFinales((TicketVentaAbono) ticketPrincipal);
        
        // Registrar cupones utilizados. Si hay algún error, se ignorará y se registrarán al procesar el ticket en central
//         cuponesService.registraUsoCupones((TicketVentaAbono) ticketPrincipal);
        
        if(ticketPrincipal.getIdTicket() == null) {
			try {
				ticketsService.setContadorIdTicket((Ticket) ticketPrincipal);
			} catch (TicketsServiceException e) {
				throw new RuntimeException("Error asignando contador de ticket", e);
			}
		}
        sesionPromociones.generarCuponesDtoFuturo(ticketPrincipal);
        
        ticketPrincipal.getTotales().recalcular();
        
        if (!isTrainingMode()) {
			try {
				ticketsService.registrarTicket((Ticket)ticketPrincipal, documentoActivo, false);
			} catch (TicketsServiceException e) {
				throw new RuntimeException("Error salvando ticket", e);
			}
        }
	}
	
	@SuppressWarnings("unchecked")
	public LineaTicket getItemPrice(String codart) throws ArticuloNotFoundException {
		ITicket ticketTemporal;
        
        ticketTemporal = SpringContext.getBean(getTicketClass(documentoActivo));
        ticketTemporal.setEsDevolucion(false);        
        ticketTemporal.getCabecera().inicializarCabecera(ticketPrincipal);
        ((TicketVentaAbono)ticketTemporal).inicializarTotales();
        ticketTemporal.setCliente(sesion.getAplicacion().getTienda().getCliente().clone());
        ticketTemporal.setCajero(sesion.getSesionUsuario().getUsuario());
        ticketTemporal.getCabecera().getTotales().setCambio(SpringContext.getBean(PagoTicket.class , MediosPagosService.medioPagoDefecto));
        ticketTemporal.getTotales().recalcular();
        ticketTemporal.getCabecera().setDocumento(documentoActivo);
        
		// asignar datos de fidelizado si están asignados
		if (this.getTicket().getCabecera().getDatosFidelizado() != null) {
			ticketTemporal.getCabecera().setDatosFidelizado(this.getTicket().getCabecera().getDatosFidelizado());
		}
			
		try {
		   LineaTicketAbstract linea = lineasTicketServices.createLineaArticulo((TicketVenta) ticketTemporal, codart, null, null, BigDecimal.ONE, null, SpringContext.getBean(LineaTicket.class));
			
			return (LineaTicket)linea;				
		} catch (LineaTicketException e) {
			throw new RuntimeException("Error obteniendo precio de venta", e);
		}
	}
	
	public LineaTicket changePriceItem(Integer itemId, BigDecimal newPrice) {
		LineaTicket line = (LineaTicket) ticketPrincipal.getLinea(itemId);

		BigDecimal priceWithoutTax = sesion.getImpuestos().getPrecioSinImpuestos(line.getCodImpuesto(), newPrice, ticketPrincipal.getIdTratImpuestos());
		line.setPrecioSinDto(priceWithoutTax);
		line.setPrecioTotalSinDto(newPrice);
		
		line.recalcularImporteFinal();
		
		recalculateTicket();
		
		return line;
	}
	    

//    public void cancelTicket()   {
//        log.debug("cancelTicket() - Ticket cancel...");
//        
//        ticketInitilize();
//    }
    
    
	public void printDocument() throws Exception {
		String formatoImpresion = getTicket().getCabecera().getFormatoImpresion();
		
		if (formatoImpresion.equals(TipoDocumentoBean.PROPIEDAD_FORMATO_IMPRESION_NO_CONFIGURADO)) {
			log.error("printDocument() - print format not configured. Print cancelled");
			return;
		}

		Map<String, Object> mapaParametros = new HashMap<String, Object>();
		mapaParametros.put("ticket", getTicket());
		mapaParametros.put("urlQR", variablesServices.getVariableAsString("TPV.URL_VISOR_DOCUMENTOS"));
		mapaParametros.put("SCOMode", true);
		mapaParametros.put("TrainingMode", this.trainingMode);

		ServicioImpresion.imprimir(formatoImpresion, mapaParametros);

		// Coupons
		List<CuponEmitidoTicket> cupones = ((TicketVentaAbono) getTicket()).getCuponesEmitidos();
		
		if (cupones.size() > 0) {
			Map<String, Object> mapaParametrosCupon = new HashMap<String, Object>();
			mapaParametrosCupon.put("ticket", getTicket());
			mapaParametrosCupon.put("SCOMode", true);
			mapaParametrosCupon.put("TrainingMode", this.trainingMode);
			
			for (CuponEmitidoTicket cupon : cupones) {
				mapaParametrosCupon.put("cupon", cupon);
				SimpleDateFormat df = new SimpleDateFormat();
				mapaParametrosCupon.put("fechaEmision", df.format(getTicket().getCabecera().getFecha()));
				ServicioImpresion.imprimir(PLANTILLA_CUPON, mapaParametrosCupon);
			}
		}
					            
        return;
	}

	public boolean isTrainingMode() {
		return trainingMode;
	}

	public void setTrainingMode(boolean trainingMode) {
		this.trainingMode = trainingMode;
	}

	public boolean isTenderMode() {
		return tenderMode;
	}

	public void setTenderMode(boolean tenderMode) {
		this.tenderMode = tenderMode;
	}

	@SuppressWarnings("unchecked")
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
		
}
