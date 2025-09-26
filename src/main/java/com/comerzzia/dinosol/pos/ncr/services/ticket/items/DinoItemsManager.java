package com.comerzzia.dinosol.pos.ncr.services.ticket.items;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comarch.clm.partner.exception.BpConfiguracionException;
import com.comarch.clm.partner.exception.BpRespuestaException;
import com.comarch.clm.partner.exception.BpSoapException;
import com.comerzzia.dinosol.librerias.cryptoutils.CryptoUtils;
import com.comerzzia.dinosol.pos.ncr.actions.sale.DinoPayManager;
import com.comerzzia.dinosol.pos.ncr.services.parking.ParkingService;
import com.comerzzia.dinosol.pos.ncr.services.ticket.DinoScoTicketManager;
import com.comerzzia.dinosol.pos.ncr.services.virtualmoney.restrictions.RestrictionsService;
import com.comerzzia.dinosol.pos.services.articulos.QRBalanzaService;
import com.comerzzia.dinosol.pos.services.articulos.QRBalanzaService.LineaQRBalanza;
import com.comerzzia.dinosol.pos.services.auditorias.AuditoriaDto;
import com.comerzzia.dinosol.pos.services.auditorias.AuditoriasService;
import com.comerzzia.dinosol.pos.services.codbarrasesp.DinoCodBarrasEspecialesException;
import com.comerzzia.dinosol.pos.services.codbarrasesp.DinoCodBarrasEspecialesServices;
import com.comerzzia.dinosol.pos.services.codbarrasesp.liquidacion.QRLiquidacionDTO;
import com.comerzzia.dinosol.pos.services.codbarrasesp.liquidacion.QRTipoLiquidacion;
import com.comerzzia.dinosol.pos.services.core.sesion.DinoSesionPromociones;
import com.comerzzia.dinosol.pos.services.cupones.CustomerCouponDTO;
import com.comerzzia.dinosol.pos.services.dispositivos.recargas.articulos.ArticulosRecargaService;
import com.comerzzia.dinosol.pos.services.documents.LocatorManagerImpl;
import com.comerzzia.dinosol.pos.services.payments.methods.prefijos.PrefijosTarjetasService;
import com.comerzzia.dinosol.pos.services.payments.methods.types.bp.BPManager;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.DinoCabeceraTicket;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.TarjetaIdentificacionDto;
import com.comerzzia.dinosol.pos.services.ticket.lineas.DinoLineaTicket;
import com.comerzzia.dinosol.pos.services.ticket.liquidacion.QRLiquidacionException;
import com.comerzzia.dinosol.pos.services.ticket.liquidacion.QrLiquidacionService;
import com.comerzzia.pos.core.dispositivos.Dispositivos;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.Coupon;
import com.comerzzia.pos.ncr.messages.CouponException;
import com.comerzzia.pos.ncr.messages.Item;
import com.comerzzia.pos.ncr.messages.ItemException;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.ItemVoided;
import com.comerzzia.pos.ncr.messages.LoyaltyCard;
import com.comerzzia.pos.ncr.messages.NCRField;
import com.comerzzia.pos.ncr.messages.Totals;
import com.comerzzia.pos.ncr.messages.VoidItem;
import com.comerzzia.pos.ncr.messages.VoidTransaction;
import com.comerzzia.pos.persistence.articulos.tarifas.TarifaDetalleBean;
import com.comerzzia.pos.persistence.codBarras.CodigoBarrasBean;
import com.comerzzia.pos.persistence.fidelizacion.FidelizacionBean;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.persistence.promociones.tipos.PromocionTipoBean;
import com.comerzzia.pos.services.articulos.ArticuloNotFoundException;
import com.comerzzia.pos.services.articulos.tarifas.ArticuloTarifaNotFoundException;
import com.comerzzia.pos.services.articulos.tarifas.ArticuloTarifaServiceException;
import com.comerzzia.pos.services.articulos.tarifas.ArticulosTarifaService;
import com.comerzzia.pos.services.core.documentos.DocumentoException;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.cupones.CuponAplicationException;
import com.comerzzia.pos.services.cupones.CuponUseException;
import com.comerzzia.pos.services.cupones.CuponesServiceException;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.promociones.DocumentoPromocionable;
import com.comerzzia.pos.services.promociones.PromocionesServiceException;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.cabecera.ITotalesTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketAbstract;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketException;
import com.comerzzia.pos.services.ticket.lineas.LineasTicketServices;
import com.comerzzia.pos.services.ticket.promociones.IPromocionTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionLineaTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.config.SpringContext;
import com.comerzzia.pos.util.format.FormatUtil;
import com.comerzzia.pos.util.i18n.I18N;

@Lazy(false)
@Service
@Primary
public class DinoItemsManager extends ItemsManager {

	private static final String CODIGO_QR_LIQUIDACION_FIELD_AUX = "CodigoQRLiquidacion";
	private static final String PORCENTAJE_QR_LIQUIDACION_FIELD_AUX = "PorcentajeQRLiquidacion";
	private static final String CODIGO_MOTIVO_QR_LIQUIDACION_FIELD_AUX = "CodMotivoQRLiquidacion";
	public static final String ID_VARIABLE_TIMESTAMP = "X_FIDELIZACION.TIMESTAMP";
	public static final String ID_VARIABLE_SCO_ARTICULO_NO_PERMITIDO = "X_SCO.ARTICULO_NO_PERMITIDO";

	public Logger log = Logger.getLogger(DinoItemsManager.class);
	
	@Autowired
	private ArticulosRecargaService articulosRecargaService;

	@Autowired
	DinoCodBarrasEspecialesServices dinoCodBarrasEspecialesService;

	@Autowired
	LineasTicketServices lineasTicketServices;

	@Autowired
	VariablesServices variablesServices;

	@Autowired
	QRBalanzaService qrBalanzaService;

	@Autowired
	protected DinoSesionPromociones dinoSesionPromociones;
	
	@Autowired
	private AuditoriasService auditoriasService;
	
	@Autowired
	protected Sesion sesion;
	
	@Autowired
	private RestrictionsService restrictionsService;
	
	@Autowired
	private ArticulosTarifaService articulosTarifaService;
	
	@Autowired
	private QrLiquidacionService qrLiquidacionService;
	
	@Autowired
	private PrefijosTarjetasService prefijosTarjetasService;
	
	BPManager bpManager;

	@Override
	public void evaluateItemType(Item message) {
		log.debug("evaluateItemType() - Mensaje recibido: " + message.toString());
		String codigo = message.getFieldValue(Item.UPC);

		if (isLoyaltyCard(codigo)) {
			return;
		}
		
		if(!comprobarArticulosRestringidaVentaSco(message)) {
			return;
		}

		if (isContenidoDigital(codigo)) {
			return;
		}

		if (isCoupon(codigo, true)) {
			return;
		}
		
		if (isEspecialBarcode(message)) {
			if (dinoCodBarrasEspecialesService.esCodigoBarrasEspecialQRLiquidacion(codigo)) {
				tratarArticuloConQRLiquidacion(codigo, message);
			}
			return;
		}
		
		newItemMessage((Item) message);
	}

	private void tratarArticuloConQRLiquidacion(String codigo, Item message) {
		try {
			QRLiquidacionDTO qrLiquidacion = obtenerQrValido(codigo);
			
			BigDecimal precio = message.getFieldBigDecimalValue(Item.Price, 2);
			String codEAN13 = qrLiquidacion.getCodEAN13();
			if(StringUtils.isNotBlank(codEAN13) && codEAN13.startsWith("241") && precio == null && qrLiquidacion.getTipoLiquidacion().equals(QRTipoLiquidacion.QR_LIQUIDACION_DESCUENTO)) {
				log.debug("tratarArticuloConQRLiquidacion() - QR de liquidación de artículo 241 de tipo descuento. Se pide confirmación de precio del cajero.");
				ItemException solicitudPrecioMsg = new ItemException();
				solicitudPrecioMsg.setFieldValue(ItemException.UPC, codigo);
				solicitudPrecioMsg.setFieldValue(ItemException.PriceRequired, "1");
				ncrController.sendMessage(solicitudPrecioMsg);
			}
			else {
				String codArticulo = qrLiquidacion.getCodArticulo();
				actualizarItemConDatosArticulo(message, codArticulo, qrLiquidacion);				
				newItemMessage(message);
				guardarAuditoriaQrLiquidacion(qrLiquidacion);
			}
		}
		catch (LineaTicketException e) {
			log.error("tratarArticuloConQRLiquidacion() - QR no vigente: " + e.getMessage(), e);
			enviarItemExceptionAutorizacion(message);
		}
		catch (Exception e) {
			log.error("tratarArticuloConQRLiquidacion() - QR inválido o error inesperado: " + e.getMessage(), e);
			enviarItemExceptionAutorizacion(message);
		}
	}

	private void guardarAuditoriaQrLiquidacion(QRLiquidacionDTO qrLiquidacion) throws ArticuloTarifaNotFoundException, ArticuloTarifaServiceException, QRLiquidacionException {
		LineaTicket lineaTicket = (LineaTicket) ticketManager.getTicket().getLineas().get(ticketManager.getTicket().getLineas().size() - 1);
		LineaTicket lineaOriginal = crearLineaOriginalAuxiliarQrLiquidacion(qrLiquidacion);
		
		qrLiquidacionService.guardaAuditoria(ticketManager.getTicket(), lineaTicket, lineaOriginal, qrLiquidacion);
	}

	private LineaTicket crearLineaOriginalAuxiliarQrLiquidacion(QRLiquidacionDTO qrLiquidacion) throws ArticuloTarifaNotFoundException, ArticuloTarifaServiceException {
		TarifaDetalleBean tarifa = articulosTarifaService.consultarArticuloTarifa(qrLiquidacion.getCodArticulo(), ticketManager.getTicket().getCliente(), null, null, null);
		LineaTicket lineaOriginal = SpringContext.getBean(LineaTicket.class);
		lineaOriginal.setPrecioTotalConDto(tarifa.getPrecioTotal());
		lineaOriginal.setPrecioTotalTarifaOrigen(tarifa.getPrecioTotal());
		return lineaOriginal;
	}
	
	private QRLiquidacionDTO obtenerQrValido(String codigo) throws LineaTicketException, DinoCodBarrasEspecialesException {
		QRLiquidacionDTO qr = dinoCodBarrasEspecialesService.obtenDatosQrLiquidacionDTO(codigo);
		if (qr == null || !qr.esVigente()) {
			String error = I18N.getTexto("Código QR no vigente.");
			throw new LineaTicketException(error);
		}
		return qr;
	}
	
	private void enviarItemExceptionAutorizacion(Item message) {
		ItemException itemException = new ItemException();
		itemException.setFieldValue(ItemException.UPC, message.getFieldValue(Item.UPC));
		itemException.setFieldValue(ItemException.ExceptionType, "0");
		itemException.setFieldValue(ItemException.ExceptionId, "6");
		
		ncrController.sendMessage(itemException);
	}
	
	private void actualizarItemConDatosArticulo(Item message, String codArticulo, QRLiquidacionDTO qrLiquidacion) throws ArticuloTarifaNotFoundException, ArticuloTarifaServiceException, ArticuloNotFoundException {
		log.debug("actualizarItemConDatosArticulo() - Aplicando descuento al artículo con código: " + codArticulo);
		
	    message.setFieldValue(Item.UPC, codArticulo);
		
	    message.addField(new NCRField<>(CODIGO_QR_LIQUIDACION_FIELD_AUX, "int"));
	    message.setFieldValue(CODIGO_QR_LIQUIDACION_FIELD_AUX, qrLiquidacion.getCodEAN13());
	    
	    message.addField(new NCRField<>(CODIGO_MOTIVO_QR_LIQUIDACION_FIELD_AUX, "int"));
	    message.setFieldValue(CODIGO_MOTIVO_QR_LIQUIDACION_FIELD_AUX, qrLiquidacion.getTipoLiquidacion().motivo);
		
	    if (qrLiquidacion.getTipoLiquidacion().equals(QRTipoLiquidacion.QR_LIQUIDACION_DESCUENTO)) {
			BigDecimal precio = message.getFieldBigDecimalValue(Item.Price, 2);
			if(precio != null) {
				message.setFieldValue(Item.Price, precio.toString());
			}
			
	    	message.addField(new NCRField<>(PORCENTAJE_QR_LIQUIDACION_FIELD_AUX, "int"));
		    message.setFieldIntValue(PORCENTAJE_QR_LIQUIDACION_FIELD_AUX, qrLiquidacion.getPrecioLiquidacion());
	    }
	    else {
			BigDecimal precioFinal = qrLiquidacion.getPrecioLiquidacion().setScale(2, RoundingMode.HALF_UP);
			message.setFieldValue(Item.Price, precioFinal.toString());
	    }
	    
	    if(qrLiquidacion.getCodEAN13().startsWith("241")) {
	    	LineaTicketAbstract linea = ticketManager.getItemPrice(codArticulo);
	    	BigDecimal precioCodBarra = new BigDecimal(message.getFieldValue(Item.Price));
	    	BigDecimal cantidadCalculada = calculaCantidadLinea(precioCodBarra, linea.getImporteTotalConDto());	    	
	    	message.setFieldValue(Item.Quantity, cantidadCalculada.toString());
	    	message.setFieldValue(Item.Price, null);
	    }
	    else if(qrLiquidacion.getCodEAN13().startsWith("251")) {
	    	CodigoBarrasBean codigoBarras251 = codBarrasEspecialesServices.esCodigoBarrasEspecial(qrLiquidacion.getCodEAN13());
	    	BigDecimal cantidad251 = FormatUtil.getInstance().desformateaBigDecimal(codigoBarras251.getCantidad());
			message.setFieldValue(Item.Quantity, cantidad251.toString());
	    }
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean isLoyaltyCard(String code) {
		log.debug("isLoyaltyCard() - Codigo a comprobar: " + code);

		if (!Dispositivos.getInstance().getFidelizacion().isPrefijoTarjeta(code)) {
			return false;
		}

		ncrController.sendWaitState(I18N.getTexto("Buscando fidelizado..."));

		try {
			String numeroTarjetaDesencriptado = CryptoUtils.decrypt(code, LocatorManagerImpl.secretKey);

			if (numeroTarjetaDesencriptado == null) {
				throw new IllegalArgumentException(I18N.getTexto("Código de tarjeta no válido"));
			}

			String[] split = numeroTarjetaDesencriptado.split("-");
			String numTarjeta = split[0];

			Long timestampInSecondsQR = new Long(split[1]);
			Long timestampInSecondsNow = System.currentTimeMillis() / 1000L;
			Integer minutosPermitidos = variablesServices.getVariableAsInteger(ID_VARIABLE_TIMESTAMP);

			if (!timestampInSecondsQR.equals(0L) && minutosPermitidos > 0) {
				Long diferenciaEnSegundos = timestampInSecondsNow - timestampInSecondsQR;
				int segundosPermitidos = (minutosPermitidos * 60);
				if (diferenciaEnSegundos > segundosPermitidos) {
					throw new IllegalArgumentException(I18N.getTexto("Este código ya no es válido. Por favor, renueve su código."));
				}
			}

			FidelizacionBean fidelizado = Dispositivos.getInstance().getFidelizacion().consultarTarjetaFidelizado(numTarjeta, ticketManager.getSesion().getAplicacion().getUidActividad());

			ncrController.sendFinishWaitState();

			if (fidelizado.isBaja()) {
				throw new IllegalArgumentException(I18N.getTexto("La tarjeta de fidelización {0} no está activa", fidelizado.getNumTarjetaFidelizado()));
			}

			// Tarjeta válida - lo seteamos en el ticket
			ticketManager.getTicket().getCabecera().setDatosFidelizado(fidelizado);
			ticketManager.recalculateTicket();

			LoyaltyCard message = new LoyaltyCard();
			message.setFieldValue(LoyaltyCard.AccountNumber, fidelizado.getNumTarjetaFidelizado());
			message.setFieldValue(LoyaltyCard.Status, "1");
			message.setFieldValue(LoyaltyCard.CardType, "loyalty");
			ncrController.sendMessage(message);

			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, "");
			itemException.setFieldValue(ItemException.ExceptionType, "0");
			itemException.setFieldValue(ItemException.ExceptionId, "25");

			String mensaje = I18N.getTexto("Cliente APP activado");

			Object cupones = fidelizado.getAdicionales().get("coupons");
			if (cupones != null && !((List<CustomerCouponDTO>) cupones).isEmpty()) {
				int numCupones = ((List<CustomerCouponDTO>) cupones).size();
				String mensajeCupones = numCupones == 1 ? I18N.getTexto("Un cupón activo") : numCupones + I18N.getTexto(" cupones activos");
				mensaje = mensaje + System.lineSeparator() + mensajeCupones;
			}

			mensaje = generarMensajeTarjetasIdentificacion(mensaje);

			itemException.setFieldValue(ItemException.Message, mensaje);

			ncrController.sendMessage(itemException);

			sendTotals();

			comprobarCuponesFidelizado(fidelizado);
			
			updateItems();

		}
		catch (IllegalArgumentException e) {
			LoyaltyCard message = new LoyaltyCard();
			message.setFieldValue(LoyaltyCard.Status, "0");
			message.setFieldValue(LoyaltyCard.CardType, "loyalty");
			message.setFieldValue(LoyaltyCard.Message, e.getMessage());
			ncrController.sendMessage(message);
		}
		catch (Exception e) {
			log.error("isLoyaltyCard() - Error consultando tarjeta de fidelizado: " + e.getMessage(), e);

			LoyaltyCard message = new LoyaltyCard();
			message.setFieldValue(LoyaltyCard.Status, "0");
			message.setFieldValue(LoyaltyCard.CardType, "loyalty");
			message.setFieldValue(LoyaltyCard.Message, I18N.getTexto("Error consultando tarjeta de fidelizado"));
			ncrController.sendMessage(message);
		}

		return true;
	}
	
	public boolean isCoupon(String code, boolean mostrarMensajeCuponLeido) {
		log.debug("isCoupon() - Codigo a comprobar: " + code);
	
		try {
			if (!((DinoScoTicketManager) ticketManager).comprobarCupon(code)) {
				return false;
			}
		}
		catch (Exception e) {
			CouponException couponException = new CouponException();
			couponException.setFieldValue(CouponException.UPC, code);
			couponException.setFieldValue(CouponException.Message, e.getMessage());
			couponException.setFieldValue(CouponException.ExceptionType, "0");
			couponException.setFieldValue(CouponException.ExceptionId, "0");
			ncrController.sendMessage(couponException);
			return true;
		}
	
		if(mostrarMensajeCuponLeido) {
			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, "");
			itemException.setFieldValue(ItemException.ExceptionType, "0");
			itemException.setFieldValue(ItemException.ExceptionId, "25");
			itemException.setFieldValue(ItemException.Message, I18N.getTexto("Tu cupon ha sido leído correctamente"));
			itemException.setFieldValue(ItemException.TopCaption, I18N.getTexto("Cupon leído"));
			ncrController.sendMessage(itemException);
		}
	
		sendTotals();
	
		return true;
	}

	public boolean isContenidoDigital(String codigo) {
		try {
			for (String codArtRecarga : articulosRecargaService.getConfiguracion().getArticulosRecargaMovil()) {
				if (codArtRecarga.equals(codigo)) {
					throw new IllegalArgumentException("Artículo de recarga");
				}
			}

			for (String codArtContenidoDigital : articulosRecargaService.getConfiguracion().getArticulosPinPrinting()) {
				if (codArtContenidoDigital.equals(codigo)) {
					throw new IllegalArgumentException("Artículo de contenido digital (PinPrinting)");
				}
			}
			for (String codArtContenidoDigital : articulosRecargaService.getConfiguracion().getArticulosPosaCard()) {
				if (codArtContenidoDigital.equals(codigo)) {
					throw new IllegalArgumentException("Artículo de contenido digital (PosaCard)");
				}
			}
		}
		catch (IllegalArgumentException e) {
			log.info("Item not valid for sale: " + e.getMessage(), e);

			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, codigo);
			itemException.setFieldValue(ItemException.Inactive, "1");
			ncrController.sendMessage(itemException);

			return true;
		}
		catch (Exception e) {
			log.error("Error inesperado: " + e.getMessage(), e);
			throw new RuntimeException("Error inesperado:" + e.getMessage(), e);
		}
		return false;
	}

	@Override
	public boolean isEspecialBarcode(Item message) {
		log.debug("isEspecialBarcode() - Codigo leído");
		String codigo = message.getFieldValue(Item.UPC);
		
		if (codigo.startsWith("029") && codigo.length() == 12) {
			codigo = "0" + codigo;
		}
		
		CodigoBarrasBean codBarrasEspecial = dinoCodBarrasEspecialesService.esCodigoBarrasEspecial(codigo);

		if (codBarrasEspecial == null)
			return false;

		if (codBarrasEspecial.getCodticket() != null) {
			if (codBarrasEspecial.getDescripcion().equals("BP")) {
				return tratarTarjetaBP(codBarrasEspecial.getCodigoIntroducido());
			} else if (codBarrasEspecial.getDescripcion().equals("QR BALANZA")) {
				return leerQrBalanza(codBarrasEspecial.getCodigoIntroducido());
			} else if (codBarrasEspecial.getDescripcion().equals("FLYER")) {
				// return leerCuponImporte(codBarrasEspecial);
			}

			return true;
		}

		BigDecimal cantidad = null;
		BigDecimal precio = null;

		String codArticulo = codBarrasEspecial.getCodart();

		if (codArticulo == null) {
			log.error(String.format(
					"checkCodigoBarrasEspecial() - El código de barra especial obtenido no es válido. CodArticulo: %s",
					codArticulo));
			return false;
		}

		String cantCodBar = codBarrasEspecial.getCantidad();
		if (cantCodBar != null) {
			cantidad = FormatUtil.getInstance().desformateaBigDecimal(cantCodBar, 3);
		}
		
		String precioCodBar = codBarrasEspecial.getPrecio();

		if (precioCodBar != null) {
			precioCodBar = precioCodBar.replaceAll("\\.", ",");
			precio = FormatUtil.getInstance().desformateaBigDecimal(precioCodBar, 2);
		}

		try {
			LineaTicketAbstract linea = ticketManager.getItemPrice(codArticulo);
			
			linea.setCodigoBarras(codigo);

			if (message.getFieldValue(Item.Weight) != null) {
				cantidad = new BigDecimal(message.getFieldValue(Item.Weight)).divide(new BigDecimal(1000));
				((DinoLineaTicket)linea).setCodOperador(ticketManager.getSesion().getSesionUsuario().getUsuario().getUsuario());
			}

			if (codBarrasEspecial.getDescripcion().equals("Etiquetas Precios") && cantidad == null && precio != null
					&& precio.compareTo(BigDecimal.ZERO) > 0) {
				// calcular cantidad desde el precio tarificado
				BigDecimal precioLinea = null;
				try {
					precioLinea = damePrecioVenta(codArticulo);
				}
				catch (Exception e) {
					log.error("isEspecialBarcode() - Error recuperando la linea: " + e.getMessage(), e);
				}

				BigDecimal cantidadCalculada = calculaCantidadLinea(precio, precioLinea);
				
				if(cantidad == null) {
					cantidad = BigDecimal.ONE;
				}
				
				if("U".equals(linea.getArticulo().getBalanzaTipoArticulo()) && !BigDecimalUtil.isIgual(cantidad, BigDecimal.ONE)) {
					cantidadCalculada = BigDecimalUtil.redondear(cantidadCalculada.multiply(cantidad), 3);
				}

				linea.setCantidad(cantidadCalculada);
				linea.recalcularImporteFinal();

				linea = ticketManager.addLineToTicket(linea);
			} else {
				// Si el artículo tiene en su campo FORMATO en BBDD...
				if (cantidad == null && StringUtils.isNotBlank(linea.getArticulo().getBalanzaTipoArticulo())
						&& "P".equals(linea.getArticulo().getBalanzaTipoArticulo().trim().toUpperCase())) {
					ItemException itemException = new ItemException();
					itemException.setFieldValue(ItemException.UPC, codigo);
					itemException.setFieldValue(ItemException.WeightRequired, "1");
					ncrController.sendMessage(itemException);

					return true;
				}

				if (cantidad == null) {
					cantidad = BigDecimal.ONE;
				}

				linea = ticketManager.createAndInsertTicketLine(codArticulo, "*", "*", codigo, cantidad, null);
				
				if(codigo.startsWith("251")) {
					String codigoBarras = linea.getCodigoBarras();
					codigoBarras = ((DinoCodBarrasEspecialesServices) codBarrasEspecialesServices).generarCodBarras241Equivalente(codigo, linea);
					linea.setCodigoBarras(codigoBarras);
				}
			}
			newItemAndUpdateAllItems((LineaTicket) linea);

			return true;
		} catch (ArticuloNotFoundException | LineaTicketException e) {
			log.error("Error insertando línea:" + e.getMessageI18N(), e);

			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, codigo);
			itemException.setFieldValue(ItemException.NotFound, "1");
			ncrController.sendMessage(itemException);
		}

		return false;

	}
	
	public BigDecimal calculaCantidadLinea(BigDecimal precioCodBarra, BigDecimal precioLinea){
		BigDecimal cantidad = BigDecimal.ONE;
		
		if(!BigDecimalUtil.isIgualACero(precioCodBarra) && !BigDecimalUtil.isIgualACero(precioLinea)){
			cantidad = precioCodBarra.divide(precioLinea, 5, RoundingMode.HALF_UP);
		}
		
		return cantidad;
	}
	
	public BigDecimal damePrecioVenta(String codart) throws DocumentoException, PromocionesServiceException {
		try {
			LineaTicket linea =((DinoScoTicketManager)ticketManager).nuevaLineaArticuloCodart(codart, BigDecimal.ONE);
			((DinoScoTicketManager)ticketManager).recalcularConPromociones();
			
			int indexBorrado = ticketManager.getTicket().getLineas().size()-1;
			ticketManager.getTicket().getLineas().remove(indexBorrado);
			
			return linea.getImporteTotalConDto(); 
		} catch (LineaTicketException e) {
			throw new RuntimeException("Error obteniendo precio de venta", e);
		}
	}

	private boolean leerQrBalanza(String codBarrasEspecial) {
		List<LineaQRBalanza> lineas = null;

		try {
			lineas = qrBalanzaService.leerQrBalanza(codBarrasEspecial);
		} catch (Exception e) {
			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, codBarrasEspecial);
			itemException.setFieldValue(ItemException.NotFound, "1");
			ncrController.sendMessage(itemException);
			return true;
		}
		
		for (int i = 0 ; i < lineas.size() ; i++) {
			try {
				LineaQRBalanza lineaQR = lineas.get(i);
				DinoLineaTicket linea = (DinoLineaTicket) ticketManager.getItemPrice(lineaQR.getCodart());
				linea.setCantidad(lineaQR.getCantidad());

				// si el precio no coincide (discrepancias entre precio tarifa y balanza)
				// se aplica el precio origen de la balanza
				if (linea.getPrecioTotalConDto().compareTo(lineaQR.getPrecio()) != 0) {
					linea.setPrecioTotalConDto(lineaQR.getPrecio());
					linea.resetPromociones();
					linea.setPrecioSinDto(lineaQR.getPrecio());
					linea.setPrecioTotalSinDto(lineaQR.getPrecio());
				}

				linea.recalcularImporteFinal();

				linea.setCodOperador(lineaQR.getOperador());
				linea.setCodSeccion(lineaQR.getDepartamento());

				LineaTicket nuevaLinea = ticketManager.addLineToTicket(linea);

				// enviar linea al SCO
				newItemQRBalanza(nuevaLinea, i == 0, i == lineas.size()-1);
			}
			catch (ArticuloNotFoundException ignore) {} // En la carga del QR se controla que el artículo exista			
		}
		
		updateItems();
		sendTotals();

		return true;
	}
	
	
	private boolean tratarTarjetaBP(String codigoBarras) {
		LoyaltyCard message = new LoyaltyCard();
		message.setFieldValue(LoyaltyCard.AccountNumber, codigoBarras);
		message.setFieldValue(LoyaltyCard.CardType, "loyalty");
		
		/* Solo en el caso de estar en el TENDERMODE, se utilizará la tarjeta BP como medio de pago */
		if (ticketManager.isTenderMode()) {
			Boolean resultado = pagarConTarjetaBP(codigoBarras, message);
			if (!resultado) {
				return false;
			}
		}
		
		fidelizadoTarjetaBP(codigoBarras, message);
		
		ncrController.sendMessage(message);
		
		ItemException itemException = new ItemException();
		itemException.setFieldValue(ItemException.UPC, "");
		itemException.setFieldValue(ItemException.ExceptionType, "0");
		itemException.setFieldValue(ItemException.ExceptionId, "25");
		itemException.setFieldValue(ItemException.Message, "Tarjeta DinoBP leída");
		ncrController.sendMessage(itemException);
		
		return true;
	}

	@Override
	public void deleteAllItems(VoidTransaction message) {
		guardarAuditoriaAnularTicket();
		
		if(ticketManager.getTicket().getLineas().size() == 0) {
			((DinoScoTicketManager) ticketManager).addLineaVaciaFicticia();
		}

		super.deleteAllItems(message);
	}

	private void guardarAuditoriaAnularTicket() {
		AuditoriaDto auditoria = new AuditoriaDto();
		auditoria.setTipo("ANULAR TICKET (SCO)");
		auditoria.setUidTicket(ticketManager.getTicket().getUidTicket());
		auditoriasService.guardarAuditoria(auditoria);
	}

	private void fidelizadoTarjetaBP(String codigoBarras, LoyaltyCard message) {
		if (bpManager == null) {
			buscarBpManager();
		}
		
		if (bpManager == null) {
			log.error("No se ha consegido obtener el manejador de BP");
		}
		
		DinoCabeceraTicket cabecera = (DinoCabeceraTicket) ticketManager.getTicket().getCabecera();

		if (!cabecera.containsTarjeta(codigoBarras)) {
			TarjetaIdentificacionDto tarjetaIdentificacion = new TarjetaIdentificacionDto();
			tarjetaIdentificacion.setTipoTarjeta("BP");
			tarjetaIdentificacion.setNumeroTarjeta(codigoBarras);
			cabecera.addTarjetaIdentificacion(tarjetaIdentificacion);

			message.setFieldValue(LoyaltyCard.Status, LoyaltyCard.STATUS_ACCEPTED);
		}
		else {
			message.setFieldValue(LoyaltyCard.Status, LoyaltyCard.STATUS_ALREADY_SCANNED);
		}
	}
	
	private boolean pagarConTarjetaBP(String codigoBarras, LoyaltyCard message) {
		if (bpManager == null) {
			buscarBpManager();
		}
		
		if (bpManager == null) {
			log.error("No se ha consegido obtener el manejador de BP");
			return false;
		}

		
		if (codigoBarras.startsWith("029") && codigoBarras.length() == 12) {
			codigoBarras = "0" + codigoBarras;
		}
		
		try {	
			bpManager.getSaldo(codigoBarras);
		}
		catch (BpSoapException | BpRespuestaException | BpConfiguracionException e1) {
			log.error("Error consultando tarjeta BP: " + e1.getMessage(), e1);

			message.setFieldValue(LoyaltyCard.Status, LoyaltyCard.STATUS_REJECTED);
		}

		return true;
	}

	private void buscarBpManager() {
		Map<String, PaymentMethodManager> listaMediosDePago = ticketManager.getPaymentsManager().getPaymentsMehtodManagerAvailables();

		listaMediosDePago.forEach((key, paymentMethod) -> {
			if (paymentMethod instanceof BPManager) {
				bpManager = (BPManager) paymentMethod;
			}
		});
	}
	
	public boolean applyCoupon(CustomerCouponDTO customerCouponDTO) throws CuponAplicationException {
    	try {
			boolean applied = ((DinoSesionPromociones) sesion.getSesionPromociones()).aplicarCupon(customerCouponDTO, (DocumentoPromocionable) ticketManager.getTicket());
			
			// marcar como validado
			customerCouponDTO.setValidationRequired(false);
			
			return applied;
		} catch (CuponUseException | CuponesServiceException | CuponAplicationException e) {
			throw new CuponAplicationException(e.getMessage());
		}
    }
	
	@Override
	@SuppressWarnings("unchecked")
	public void updateItems() {
		ticketManager.getTicket().getTotales().recalcular();

		Map<String, HeaderDiscountData> headerDiscounts = getHeaderDiscountsDetail();

		for (HeaderDiscountData headerDiscountData : headerDiscounts.values()) {
			GlobalDiscountData globalDiscountData = globalDiscounts.get(GLOBAL_DISCOUNT_PAYMENT_PREFIX + headerDiscountData.cuponCode);

			if (globalDiscountData == null) {
				globalDiscountData = new GlobalDiscountData();
				globalDiscountData.headerDiscount = headerDiscountData;

				ItemSold discount = new ItemSold();
				discount.setFieldValue(ItemSold.UPC, headerDiscountData.cuponCode);
				discount.setFieldValue(ItemSold.Description, headerDiscountData.paymentMethodDes);
				discount.setFieldValue(ItemSold.ItemNumber, String.valueOf(globalDiscountCounter));
				discount.setFieldIntValue(ItemSold.Price, headerDiscountData.amount.negate());
				discount.setFieldValue(ItemSold.RequiresSecurityBagging, "5");

				globalDiscountCounter++;

				globalDiscountData.itemSoldMessage = discount;

				globalDiscounts.put(GLOBAL_DISCOUNT_PAYMENT_PREFIX + headerDiscountData.paymentMethodCode, globalDiscountData);

				ncrController.sendMessage(discount);

				sendTotals();
			}
			else {
				ItemSold discount = globalDiscountData.itemSoldMessage;

				if (headerDiscountData.amount.compareTo(discount.getFieldBigDecimalValue(ItemSold.Price, 2).negate()) != 0) {
					log.debug("Updating header discount " + discount.getFieldValue(ItemSold.ItemNumber));

					discount.setFieldIntValue(ItemSold.DiscountAmount, headerDiscountData.amount);

					ncrController.sendMessage(discount);

					sendTotals();
				}
			}
		}

		if (globalDiscounts.size() > 0) {
			Iterator<Entry<String, GlobalDiscountData>> globalDiscountIterator = globalDiscounts.entrySet().iterator();

			while (globalDiscountIterator.hasNext()) {
				Entry<String, GlobalDiscountData> grobalDiscountItem = globalDiscountIterator.next();

				GlobalDiscountData globalDiscountData = grobalDiscountItem.getValue();

				if (globalDiscountData.coupon != null) {
					boolean applied = false;

					for (PromocionTicket appliedPromotion : ((TicketVentaAbono) ticketManager.getTicket()).getPromociones()) {
						if (IPromocionTicket.COUPON_ACCESS.equals(appliedPromotion.getAcceso()) && StringUtils.equals(appliedPromotion.getCodAcceso(), globalDiscountData.coupon.getCouponCode())) {
							applied = true;
							break;
						}
					}

					if (!applied) {
						ItemVoided voidItem = new ItemVoided();
						voidItem.setFieldValue(VoidItem.ItemNumber, globalDiscountData.couponMessage.getFieldValue(Coupon.ItemNumber));
						ncrController.sendMessage(voidItem);

						sendTotals();

						globalDiscountIterator.remove();
					}
				}
				else if (globalDiscountData.headerDiscount != null) {
					if (!headerDiscounts.containsKey(globalDiscountData.headerDiscount.paymentMethodCode)) {
						ItemVoided voidItem = new ItemVoided();
						voidItem.setFieldValue(VoidItem.ItemNumber, globalDiscountData.itemSoldMessage.getFieldValue(ItemSold.ItemNumber));
						ncrController.sendMessage(voidItem);

						sendTotals();

						globalDiscountIterator.remove();
					}
				}

			}
		}

		// check prices changes
		for (LineaTicket ticketLine : (List<LineaTicket>) ticketManager.getTicket().getLineas()) {
			ItemSold currentItemSold = linesCache.get(ticketLine.getIdLinea());

			if (currentItemSold != null) {
				ItemSold newItemSold = lineaTicketToItemSold(ticketLine);

				String actualDiscount = currentItemSold.getDiscountApplied().getFieldValue(ItemSold.Price);
				String newDiscount = newItemSold.getDiscountApplied().getFieldValue(ItemSold.Price);

				// compare cached values & send changes
				if (!StringUtils.equals(actualDiscount, newDiscount)) {
					log.debug("Updating line " + ticketLine.getIdLinea());

					if (newItemSold.getDiscountApplied().getFieldValue(ItemSold.Description).equals("Eliminado")) {
						ncrController.sendMessage(newItemSold);
					}
					else {
						ncrController.sendMessage(newItemSold.getDiscountApplied());
					}
					sendTotals();

					linesCache.put(ticketLine.getIdLinea(), newItemSold);
				}
			}
		}
	}
	
	@Override
	@SuppressWarnings("unchecked")
	protected Map<String, HeaderDiscountData> getHeaderDiscountsDetail() {		
		Map<String, BigDecimal> descuentosPromocionales = new HashMap<String, BigDecimal>();
		Map<String, HeaderDiscountData> headerDiscounts = new HashMap<String, HeaderDiscountData>();

		for(PromocionTicket promocion : (List<PromocionTicket>) ticketManager.getTicket().getPromociones()) {
			if(promocion.isDescuentoMenosIngreso()) {
				PromocionTipoBean tipoPromocion = ticketManager.getSesion().getSesionPromociones().getPromocionActiva(promocion.getIdPromocion()).getPromocionBean().getTipoPromocion();
				String codMedioPago = tipoPromocion.getCodMedioPagoMenosIngreso();
				if(codMedioPago != null) {
					BigDecimal importeDescPromocional = BigDecimalUtil.redondear(promocion.getImporteTotalAhorro(), 2);
					BigDecimal importeDescAcum = descuentosPromocionales.get(codMedioPago) != null ? descuentosPromocionales.get(codMedioPago) : BigDecimal.ZERO;
					importeDescAcum = importeDescAcum.add(importeDescPromocional);
					
					if(BigDecimalUtil.isMayorACero(importeDescAcum)) {
						MedioPagoBean medioPago = mediosPagosService.getMedioPago(codMedioPago);
						
						HeaderDiscountData headerDiscountData = new HeaderDiscountData();
						headerDiscountData.paymentMethodCode = codMedioPago;
						headerDiscountData.paymentMethodDes = I18N.getTexto(medioPago.getDesMedioPago());
						headerDiscountData.amount = importeDescAcum;
						
						if(IPromocionTicket.COUPON_ACCESS.equals(promocion.getAcceso())) {
							headerDiscountData.cuponCode = promocion.getCodAcceso();							
						}
						
						headerDiscounts.put(codMedioPago, headerDiscountData);
					}
				}
			}
		}
		
		return headerDiscounts;
	}
	
	@SuppressWarnings("unchecked")
	private void comprobarCuponesFidelizado(FidelizacionBean fidelizado) throws CuponAplicationException {
		log.debug("comprobarCuponesFidelizado() - Comprobando cupones del fidelizado: " + fidelizado.getNombre() + " " + fidelizado.getApellido());
		if (fidelizado != null && fidelizado.getAdicionales() != null) {
			List<CustomerCouponDTO> cuponesFidelizado = (List<CustomerCouponDTO>) fidelizado.getAdicionales().get("coupons");

			if (cuponesFidelizado != null && !cuponesFidelizado.isEmpty()) {
				for (CustomerCouponDTO cupon : cuponesFidelizado) {
					isCoupon(cupon.getCouponCode(), false);
				}
			}
		}
	}
	
	@Override
	public void newItemMessage(Item message) {
		log.debug("newItemMessage()");

		if (!comprobarCantidadMaximaLinea(message)) {
			return;
		}

		BigDecimal quantity = BigDecimal.ONE;
		BigDecimal price = null;

		if (!StringUtils.isEmpty(message.getFieldValue(Item.Quantity))) {
			quantity = new BigDecimal(message.getFieldValue(Item.Quantity));
		}
		
		if (!StringUtils.isEmpty(message.getFieldValue(Item.Price))) {
			price = new BigDecimal(message.getFieldValue(Item.Price));
		}

		try {
			LineaTicketAbstract lineaTicket = ticketManager.createTicketLine(message.getFieldValue(Item.UPC), null, null, message.getFieldValue(Item.UPC), quantity, price);
			
			if(message.getFieldValue(CODIGO_QR_LIQUIDACION_FIELD_AUX) != null) {
				((DinoLineaTicket) lineaTicket).setQrLiquidacionOrigen(message.getFieldValue(CODIGO_QR_LIQUIDACION_FIELD_AUX));
				((DinoLineaTicket) lineaTicket).setCodMotivo(message.getFieldValue(CODIGO_MOTIVO_QR_LIQUIDACION_FIELD_AUX));
				
				if(message.getFieldValue(PORCENTAJE_QR_LIQUIDACION_FIELD_AUX) != null) {
					BigDecimal descuentoManual = message.getFieldBigDecimalValue(PORCENTAJE_QR_LIQUIDACION_FIELD_AUX, 2);
					lineaTicket.setDescuentoManual(descuentoManual);
					lineaTicket.recalcularImporteFinal();
				}
			}
			else if (StringUtils.equals(lineaTicket.getArticulo().getBalanzaTipoArticulo(), "P")) {
				if (StringUtils.isEmpty(message.getFieldValue(Item.Weight))) {
					ItemException itemException = new ItemException();
					itemException.setFieldValue(ItemException.UPC, message.getFieldValue(Item.UPC));
					itemException.setFieldValue(ItemException.WeightRequired, "1");
					ncrController.sendMessage(itemException);
					return;
				}

				lineaTicket.setCantidad(message.getFieldBigDecimalValue(Item.Weight, 3));
				lineaTicket.recalcularImporteFinal();
			}

			if (lineaTicket.getGenerico()) {
				if (StringUtils.isEmpty(message.getFieldValue(Item.Price))) {
					ItemException itemException = new ItemException();
					itemException.setFieldValue(ItemException.UPC, message.getFieldValue(Item.UPC));
					itemException.setFieldValue(ItemException.PriceRequired, "1");
					ncrController.sendMessage(itemException);
					return;
				}

				lineaTicket.resetPromociones();
				lineaTicket.setPrecioTotalConDto(message.getFieldBigDecimalValue(Item.Price, 2));
				// lineaTicket.setPrecioSinDto(lineaTicket.getPrecioTotalConDto());
				lineaTicket.setPrecioTotalSinDto(lineaTicket.getPrecioTotalConDto());
				lineaTicket.recalcularImporteFinal();
			}

			ticketManager.addLineToTicket(lineaTicket);

			newItemAndUpdateAllItems((LineaTicket) lineaTicket);
		}
		catch (LineaTicketException | ArticuloNotFoundException e) {
			if (e instanceof LineaTicketException) {
				log.error("Internal error while inserting line:" + e.getMessageI18N(), e);
			}

			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, message.getFieldValue(Item.UPC));
			itemException.setFieldValue(ItemException.NotFound, "1");
			ncrController.sendMessage(itemException);
		}
	}

	private boolean comprobarArticulosRestringidaVentaSco(Item message) {
		try {
			LineaTicketAbstract lineaTicket = ticketManager.createTicketLine(message.getFieldValue(Item.UPC), null, null, message.getFieldValue(Item.UPC), BigDecimal.ONE, null);
			
			boolean itemsRestriction = restrictionsService.checkItemsRestriction((LineaTicket) lineaTicket);
			if(itemsRestriction) {
				ItemException itemException = new ItemException();
				itemException.setFieldValue(ItemException.UPC, "");
				itemException.setFieldValue(ItemException.ExceptionType, "0");
				itemException.setFieldValue(ItemException.ExceptionId, "6");
				
				ncrController.sendMessage(itemException);
				
				return false;
			}
		}
		catch (Exception e) {
			log.error("comprobarArticulosRestringidaVentaSco() - No se ha podido comprobar el artículo de venta restringida: " + e.getMessage(), e);
		}
		return true;
	}

	protected boolean comprobarCantidadMaximaLinea(Item message) {
		Integer numArticulosNoPermitido = variablesServices.getVariableAsInteger(ID_VARIABLE_SCO_ARTICULO_NO_PERMITIDO);
		
		BigDecimal quantity = BigDecimal.ONE;	
		if (!StringUtils.isEmpty(message.getFieldValue(Item.Quantity))) {
			quantity = new BigDecimal(message.getFieldValue(Item.Quantity));
		}
		
		if (numArticulosNoPermitido != 0 && quantity.intValue() >= numArticulosNoPermitido) {
			log.debug("newItemMessage() - Cantidad introducida: " + quantity);
			log.debug("newItemMessage() - Cantidad permitida: " + numArticulosNoPermitido);
			log.debug("newItemMessage() - No se cumplen las condiciones");
			
			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, message.getFieldValue(Item.UPC));
			itemException.setFieldValue(ItemException.ExceptionType, "0");
			itemException.setFieldValue(ItemException.ExceptionId, "10");
			
			ncrController.sendMessage(itemException);
			
			return false;
		}
		
		return true;
	}
	
	public void newItemQRBalanza(final LineaTicket newLine, boolean esPrimerItem, boolean esUltimoItem) {
		// send new line & totals
		ItemSold response = lineaTicketToItemSold(newLine);

		if (!esPrimerItem) {
			response.setFieldValue(ItemSold.RequiresSubsCheck, "4");
		}
		
		if(!esUltimoItem) {
			response.setFieldValue(ItemSold.Weight, null);
		}
		
		sendItemSold(response);
		
		// add new line to cache
		linesCache.put(newLine.getIdLinea(), response);
	}
	
	@SuppressWarnings("rawtypes")
	@Override
	public void sendTotals() {
		ITotalesTicket totales = ticketManager.getTicket().getTotales();
		
		BigDecimal headerDiscounts = getHeaderDiscounts();
		BigDecimal totalAmount = totales.getTotalAPagar().subtract(headerDiscounts);		
		BigDecimal entregado = totales.getEntregado();
		
		// in tender mode, header discounts are included in the amount delivered 
		if (ticketManager.isTenderMode()) {
		   entregado = entregado.subtract(headerDiscounts);			
		}		
				
		Totals totals = new Totals();
		totals.setFieldIntValue(Totals.TotalAmount, totalAmount);
		totals.setFieldIntValue(Totals.TaxAmount, totales.getImpuestos());
				
		// pay control for change
		if (totalAmount.compareTo(entregado) >= 0) {
		   totals.setFieldIntValue(Totals.BalanceDue, totalAmount.subtract(entregado));
		} else {
			totals.setFieldIntValue(Totals.BalanceDue, BigDecimal.ZERO);
			totals.setFieldIntValue(Totals.ChangeDue, entregado.subtract(totalAmount));
			
		}
		totals.setFieldValue(Totals.ItemCount, String.valueOf(ticketManager.getTicket().getLineas().size()));
		totals.setFieldIntValue(Totals.DiscountAmount, totales.getTotalPromociones());
		totals.setFieldValue(Totals.Points, String.valueOf(0));

		ncrController.sendMessage(totals);
	}
	
	@Override
	protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
		ItemSold itemSold = super.lineaTicketToItemSold(linea);
		
		setFieldsItemSoldAgeRestriction(linea, itemSold);
		setFieldsItemSoldParking(linea, itemSold);
		setFieldsItemSoldQrLiquidacion(linea, itemSold);
		setFieldsItemSoldPromociones(linea, itemSold);
		setFieldsItemSoldDescuentoManual(linea, itemSold);
		
		return itemSold;
	}

	private void setFieldsItemSoldDescuentoManual(LineaTicket linea, ItemSold itemSold) {
		BigDecimal descuentoManual = linea.getDescuentoManual();
		if(descuentoManual != null && BigDecimalUtil.isMayorACero(descuentoManual)) {
			String description = itemSold.getFieldValue(ItemSold.Description) + "\n" + I18N.getTexto("Descuento promocional aplicado") + ": " + FormatUtil.getInstance().formateaNumero(descuentoManual, 0) + "%";
			itemSold.setFieldValue(ItemSold.Description, description);
			itemSold.setFieldIntValue(ItemSold.ExtendedPrice, linea.getImporteTotalConDto());
		}
	}

	private void setFieldsItemSoldPromociones(LineaTicket linea, ItemSold itemSold) {
		if (linea.getPromociones() != null && !linea.getPromociones().isEmpty()) {        
	        String promociones = "";
			for (PromocionLineaTicket promocion : linea.getPromociones()) {
				if (BigDecimalUtil.isMayorACero(promocion.getImporteTotalDtoMenosMargen())) {
					promociones = promociones + "," + promocion.getIdPromocion();
				}
			}
			if (StringUtils.isBlank(promociones)) {
				promociones = ",";
			}
			
			
			BigDecimal importeAhorrado = linea.getImporteTotalPromociones();
			BigDecimal importeLineaConDescuento = BigDecimal.ZERO;
			if (importeAhorrado != null && BigDecimalUtil.isMayorACero(importeAhorrado)) {
				importeLineaConDescuento = linea.getImporteTotalConDto();
			}

			String description = itemSold.getFieldValue(ItemSold.Description) + "\n" + I18N.getTexto("Descuento promocional aplicado") + ": " + promociones.substring(1) + " -" + importeAhorrado;
	        
			itemSold.setFieldValue(ItemSold.Description, description);
			itemSold.setFieldIntValue(ItemSold.ExtendedPrice, importeLineaConDescuento);
		}
	}

	private void setFieldsItemSoldQrLiquidacion(LineaTicket linea, ItemSold itemSold) {
		String codigoQrLiquidacion = ((DinoLineaTicket) linea).getQrLiquidacionOrigen();
		BigDecimal descuentoManual = linea.getDescuentoManual();
		if(StringUtils.isNotBlank(codigoQrLiquidacion) && !(codigoQrLiquidacion.startsWith("241") && descuentoManual != null && BigDecimalUtil.isMayorACero(descuentoManual))) {
			itemSold.setFieldValue(ItemSold.VisualVerifyRequired, "1");
		}
	}

	private void setFieldsItemSoldAgeRestriction(LineaTicket linea, ItemSold itemSold) {
		boolean ageRestriction = restrictionsService.checkAgeRestriction(linea);
		if(ageRestriction) {
			itemSold.setFieldValue(Item.Age, "18");
		}
	}

	private void setFieldsItemSoldParking(LineaTicket linea, ItemSold itemSold) {
		String codartParking = variablesServices.getVariableAsString(ParkingService.ID_VARIABLE_CODART_PARKING);
		if(linea.getCodArticulo().equals(codartParking)) {
			itemSold.setFieldValue(ItemSold.RequiresSecurityBagging, "4");
			itemSold.setFieldValue(ItemSold.RequiresSubsCheck, "2");
			itemSold.setFieldValue(ItemSold.ExtendedPrice, null);
			itemSold.setFieldValue(ItemSold.Quantity, null);
		}
	}
	
	@Override
	protected void sendItemSold(ItemSold itemSold) {		
		ncrController.sendMessage(itemSold);
		sendTotals();
	}

	@Override
	public void newItem(final LineaTicket newLine) {
		ItemSold response = lineaTicketToItemSold(newLine);
		
		ItemSold itemPlastico = tratarArticuloPlastico(newLine, response);
		
		sendItemSold(response);
		linesCache.put(newLine.getIdLinea(), response);
		
		if(itemPlastico != null) {
			sendItemSold(itemPlastico);
		}
	}

	private ItemSold tratarArticuloPlastico(final LineaTicket newLine, ItemSold response) {
		if (((DinoLineaTicket) newLine).isEsPlastico()) {
			response.setFieldValue(ItemSold.AssociatedItemNumber, newLine.getCodArticulo());
		}
		else if (StringUtils.isNotBlank(((DinoLineaTicket) newLine).getCodArtPlasticoAsociado())) {
			LineaTicket lineaPlastico = (LineaTicket) ticketManager.getTicket().getLinea(((DinoLineaTicket) newLine).getIdLineaAsociado());
			ItemSold res = lineaTicketToItemSold(lineaPlastico);
			ItemSold itemSoldOriginal = lineaTicketToItemSold(newLine);
			res.setFieldValue(ItemSold.LinkedItem, itemSoldOriginal.getFieldValue(ItemSold.UPC));
			return res;
		}
		
		return null;
	}
	
	@Override
	public void deleteItem(VoidItem message) {
		deleteAssociatedItem(message);
		super.deleteItem(message);
	}

	private void deleteAssociatedItem(VoidItem message) {
		Integer itemNumber = Integer.valueOf(message.getFieldValue(VoidItem.ItemNumber));

		DinoLineaTicket associatedLine = (DinoLineaTicket) ticketManager.getTicket().getLinea(itemNumber);
		if (associatedLine.getIdLineaAsociado() != null) {
			ticketManager.deleteTicketLine(associatedLine.getIdLineaAsociado());

			updateItems();

			ItemVoided response = new ItemVoided();
			response.setFieldValue(ItemVoided.UPC, associatedLine.getCodigoBarras());
			response.setFieldValue(ItemVoided.ItemNumber, associatedLine.getIdLineaAsociado().toString());

			ncrController.sendMessage(response);

			sendTotals();
		}
	}

	private String generarMensajeTarjetasIdentificacion(String mensaje) {
		TarjetaIdentificacionDto tarjetaBP = ((DinoCabeceraTicket) ticketManager.getTicket().getCabecera()).buscarTarjeta("BP");
		TarjetaIdentificacionDto tarjetaEMP = ((DinoCabeceraTicket) ticketManager.getTicket().getCabecera()).buscarTarjeta("EMP");
		if (tarjetaBP != null) {
			mensaje = mensaje + System.lineSeparator() + I18N.getTexto("Tarjeta DinoBP añadida");
		}
		if (tarjetaEMP != null) {
			String medioPago = prefijosTarjetasService.getMedioPagoPrefijo(tarjetaEMP.getNumeroTarjeta());
			if (medioPago.equals(DinoPayManager.CODMEDPAG_EMPLEADO)) {
				mensaje = mensaje + System.lineSeparator() + I18N.getTexto("Tarjeta de empleado añadida");
			}
			else if (medioPago.equals(DinoPayManager.CODMEDPAG_VIP)) {
				mensaje = mensaje + System.lineSeparator() + I18N.getTexto("Tarjeta VIP añadida");
			}
		}
		return mensaje;
	}
}
