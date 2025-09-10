package com.comerzzia.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.PostConstruct;

import com.comerzzia.core.servicios.api.errorhandlers.ApiException;
import com.comerzzia.pos.core.dispositivos.Dispositivos;
import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.ChangeItemPrice;
import com.comerzzia.pos.ncr.messages.Coupon;
import com.comerzzia.pos.ncr.messages.CouponException;
import com.comerzzia.pos.ncr.messages.EndTransaction;
import com.comerzzia.pos.ncr.messages.Item;
import com.comerzzia.pos.ncr.messages.ItemException;
import com.comerzzia.pos.ncr.messages.ItemPriceChanged;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.ncr.messages.ItemVoided;
import com.comerzzia.pos.ncr.messages.Language;
import com.comerzzia.pos.ncr.messages.LoyaltyCard;
import com.comerzzia.pos.ncr.messages.NCRField;
import com.comerzzia.pos.ncr.messages.Receipt;
import com.comerzzia.pos.ncr.messages.StartTransaction;
import com.comerzzia.pos.ncr.messages.Totals;
import com.comerzzia.pos.ncr.messages.TransactionVoided;
import com.comerzzia.pos.ncr.messages.VoidItem;
import com.comerzzia.pos.ncr.messages.VoidTransaction;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.persistence.codBarras.CodigoBarrasBean;
import com.comerzzia.pos.persistence.fidelizacion.CustomerCouponDTO;
import com.comerzzia.pos.persistence.fidelizacion.FidelizacionBean;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.persistence.promociones.tipos.PromocionTipoBean;
import com.comerzzia.pos.services.articulos.ArticuloNotFoundException;
import com.comerzzia.pos.services.codBarrasEsp.CodBarrasEspecialesServices;
import com.comerzzia.pos.services.cupones.CuponAplicationException;
import com.comerzzia.pos.services.cupones.CuponUseException;
import com.comerzzia.pos.services.cupones.CuponesServiceException;
import com.comerzzia.pos.services.mediospagos.MediosPagosService;
import com.comerzzia.pos.services.promociones.DocumentoPromocionable;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.cabecera.ITotalesTicket;
import com.comerzzia.pos.services.ticket.cupones.CuponAplicadoTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketAbstract;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketException;
import com.comerzzia.pos.services.ticket.promociones.IPromocionTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionLineaTicket;
import com.comerzzia.pos.services.ticket.promociones.PromocionTicket;
import com.comerzzia.pos.util.bigdecimal.BigDecimalUtil;
import com.comerzzia.pos.util.format.FormatUtil;
import com.comerzzia.pos.util.i18n.I18N;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Lazy(false)
@Service
public class ItemsManager implements ActionManager {
	protected static final Logger log = Logger.getLogger(ItemsManager.class);
	protected static final Integer PROMOTIONS_FIRST_ITEM_ID = 1000;
	protected static final Integer GLOBAL_DISCOUNT_FIRST_ITEM_ID = 2000;
	
	protected static final String GLOBAL_DISCOUNT_COUPON_PREFIX = "c->";
	protected static final String GLOBAL_DISCOUNT_PAYMENT_PREFIX = "P->";

	protected String transactionId;
    
    protected HashMap <Integer, ItemSold> linesCache = new HashMap <Integer, ItemSold> ();
    
    public class HeaderDiscountData {
    	public String paymentMethodCode;
    	public String paymentMethodDes;
    	public String cuponCode;
    	public BigDecimal amount;
    }    

    
    public class GlobalDiscountData {
    	public CustomerCouponDTO coupon;
    	public Coupon couponMessage;
    	
    	public HeaderDiscountData headerDiscount;
    	public ItemSold itemSoldMessage;
    }
        
    protected HashMap <String, GlobalDiscountData> globalDiscounts = new HashMap <> ();
    protected Integer globalDiscountCounter = GLOBAL_DISCOUNT_FIRST_ITEM_ID;
    
	@Autowired
	protected NCRController ncrController;

	@Autowired
	protected ScoTicketManager ticketManager;
	
	@Autowired
	protected CodBarrasEspecialesServices codBarrasEspecialesServices;
	
	@Autowired
    protected MediosPagosService mediosPagosService;
	
	@Override
	public void processMessage(BasicNCRMessage message) {
		if (message instanceof Language) {
		  String lcidCode = message.getFieldValue(Language.CustomerLanguage);
          String isoCode = ncrController.getConfiguration().getLcidToIsoMapping().get(lcidCode);
          
          if (isoCode == null) {
        	  log.warn("LCID language code " + lcidCode + " not found in NCR POS configuration file mapping");
          } else {
        	  Locale newLocale = new Locale(isoCode);
			  Locale.setDefault(newLocale);
			  log.info("Locale changed to " + newLocale);
          }          
		} else if (message instanceof Item) {
			if (ticketManager.getTicket() == null || transactionId == null) {
				newTicket();

				StartTransaction startTransaction = new StartTransaction();
				startTransaction.setFieldValue(StartTransaction.Type, "normal");
				startTransaction.setFieldValue(StartTransaction.Id, transactionId);

				ncrController.sendMessage(startTransaction);
			}
			
			evaluateItemType((Item) message);
		} else if (message instanceof VoidItem) {
			deleteItem((VoidItem)message);
		} else if (message instanceof VoidTransaction) {
			deleteAllItems((VoidTransaction)message);
		} 
		else if (message instanceof ChangeItemPrice) {
			changePriceItem((ChangeItemPrice) message);
		}
		else {
			log.warn("Message type not managed: " + message.getName());
		}

	}

	@PostConstruct
	public void init() {
		ncrController.registerActionManager(Language.class, this);
		ncrController.registerActionManager(Item.class, this);
		ncrController.registerActionManager(VoidItem.class, this);
		ncrController.registerActionManager(VoidTransaction.class, this);
		ncrController.registerActionManager(ChangeItemPrice.class, this);
	}
	
	protected BigDecimal getHeaderDiscounts() {
		BigDecimal descuentosCabecera = BigDecimal.ZERO;
				
		for (HeaderDiscountData headerDiscountData : getHeaderDiscountsDetail().values()) {
			descuentosCabecera = descuentosCabecera.add(headerDiscountData.amount);
		}
		
		return descuentosCabecera;
	}
	
	@SuppressWarnings("unchecked")
	protected Map<String, HeaderDiscountData> getHeaderDiscountsDetail() {		
		Map<String, BigDecimal> descuentosPromocionales = new HashMap<String, BigDecimal>();
		
		for(PromocionTicket promocion : (List<PromocionTicket>) ticketManager.getTicket().getPromociones()) {
			if(promocion.isDescuentoMenosIngreso()) {
				PromocionTipoBean tipoPromocion = ticketManager.getSesion().getSesionPromociones().getPromocionActiva(promocion.getIdPromocion()).getPromocionBean().getTipoPromocion();
				String codMedioPago = tipoPromocion.getCodMedioPagoMenosIngreso();
				if(codMedioPago != null) {
					BigDecimal importeDescPromocional = BigDecimalUtil.redondear(promocion.getImporteTotalAhorro(), 2);
					BigDecimal importeDescAcum = descuentosPromocionales.get(codMedioPago) != null ? descuentosPromocionales.get(codMedioPago) : BigDecimal.ZERO;
					importeDescAcum = importeDescAcum.add(importeDescPromocional);
					descuentosPromocionales.put(codMedioPago, importeDescAcum);
				}
			}
		}
		
		Map<String, HeaderDiscountData> headerDiscounts = new HashMap<String, HeaderDiscountData>();
		
		for(String codMedioPago : descuentosPromocionales.keySet()) {			
			BigDecimal importe = descuentosPromocionales.get(codMedioPago);
			
			if(BigDecimalUtil.isMayorACero(importe)) {
				MedioPagoBean medioPago = mediosPagosService.getMedioPago(codMedioPago);
				
				HeaderDiscountData headerDiscountData = new HeaderDiscountData();
				headerDiscountData.paymentMethodCode = codMedioPago;
				headerDiscountData.paymentMethodDes = I18N.getTexto(medioPago.getDesMedioPago());
				headerDiscountData.amount = importe;
				
				headerDiscounts.put(codMedioPago, headerDiscountData);
			}
		}
		
		return headerDiscounts;
	}

	@SuppressWarnings("rawtypes")
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
		totals.setFieldValue(Totals.Points, String.valueOf(totales.getPuntos()));

		ncrController.sendMessage(totals);
	}

	public void initSession() {
		resetTicket();
	}
	public void resetTicket() {
		transactionId = null;
		linesCache.clear();
		globalDiscounts.clear();
		globalDiscountCounter = GLOBAL_DISCOUNT_FIRST_ITEM_ID;
		ticketManager.setTenderMode(false);
	}

	public void newTicket() {
		resetTicket();
		
    	ticketManager.ticketInitilize();

    	transactionId = ticketManager.getTicket().getCabecera().getUidTicket();
	}

	public String getTransactionId() {
		return this.transactionId;
	}

	public void newItemMessage(Item message) {
		BigDecimal quantity = BigDecimal.ONE;
		
		if (!StringUtils.isEmpty(message.getFieldValue(Item.Quantity))) {
			quantity = new BigDecimal(message.getFieldValue(Item.Quantity));
		}
		
		try {
			LineaTicketAbstract lineaTicket = ticketManager.createTicketLine(message.getFieldValue(Item.UPC), null, null, message.getFieldValue(Item.UPC), quantity, null);
			
			if (StringUtils.equals(lineaTicket.getArticulo().getBalanzaTipoArticulo(), "P")) {
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
				//lineaTicket.setPrecioSinDto(lineaTicket.getPrecioTotalConDto());
				lineaTicket.setPrecioTotalSinDto(lineaTicket.getPrecioTotalConDto());
				lineaTicket.recalcularImporteFinal();
			}
			
			ticketManager.addLineToTicket(lineaTicket);
			
			newItemAndUpdateAllItems((LineaTicket)lineaTicket);
		} catch (LineaTicketException | ArticuloNotFoundException e) {
			if (e instanceof LineaTicketException) {
			   log.error("Internal error while inserting line:" + e.getMessageI18N(), e);
			}

			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, message.getFieldValue(Item.UPC));
			itemException.setFieldValue(ItemException.NotFound, "1");
			ncrController.sendMessage(itemException);
		}
	}
	
	protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
		ItemSold itemSold = new ItemSold();
		
        if (linea.getCodigoBarras() == null) {
           itemSold.setFieldValue(ItemSold.UPC, linea.getCodArticulo());
        } else {
		   itemSold.setFieldValue(ItemSold.UPC, linea.getCodigoBarras());
        }
        
        // build description
        String description = linea.getDesArticulo();
        
        if (!StringUtils.equals(linea.getDesglose1(), "*")) {
         	description += " " + linea.getDesglose1();
        }
        
        if (!StringUtils.equals(linea.getDesglose2(), "*")) {
         	description += "/" + linea.getDesglose2();
        }
        
		itemSold.setFieldValue(ItemSold.Description, description);
		itemSold.setFieldValue(ItemSold.ItemNumber, String.valueOf(linea.getIdLinea()));
		itemSold.setFieldIntValue(ItemSold.Price, linea.getPrecioTotalSinDto());
		itemSold.setFieldIntValue(ItemSold.ExtendedPrice, linea.getImporteTotalSinDto());
		
		if (StringUtils.isNotBlank(linea.getArticulo().getBalanzaTipoArticulo())
				&& "P".equals(linea.getArticulo().getBalanzaTipoArticulo().trim().toUpperCase())) {
			itemSold.setFieldValue(ItemSold.Weight, linea.getCantidad().setScale(3, RoundingMode.DOWN).multiply(new BigDecimal(1000)).stripTrailingZeros().toPlainString());			
			itemSold.setFieldValue(ItemSold.Quantity, "1");
		} else {
			if (linea.getCantidad().compareTo(BigDecimal.ONE) > 0) {
			   itemSold.setFieldValue(ItemSold.Quantity, new Integer(linea.getCantidad().intValue()).toString());
			}
		}
		
		/* Si es item de peso o generico, no tiene que pedir embolsado */
		if (StringUtils.isNotBlank(linea.getArticulo().getBalanzaTipoArticulo())
				&& "P".equals(linea.getArticulo().getBalanzaTipoArticulo().trim().toUpperCase()) || linea.getGenerico()) {
			itemSold.setFieldValue(ItemSold.RequiresSubsCheck, "2");
			itemSold.setFieldValue(ItemSold.RequiresSecurityBagging, "2");
		}
		
		//itemSold.setDiscount(linea.getImporteTotalPromociones().add(linea.getImporteTotalPromocionesMenosIngreso()), I18N.getTexto("Descuento promocional aplicado"));
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

		itemSold.setDiscount(importeLineaConDescuento, importeAhorrado, I18N.getTexto("Descuento promocional aplicado") + ": " + promociones.substring(1));

		return itemSold;
	}
	
	@SuppressWarnings("unchecked")
	public PromocionTicket getCouponPromotion(String couponCode) {
		for(PromocionTicket promocion : (List<PromocionTicket>) ticketManager.getTicket().getPromociones()) {
			if (StringUtils.equals(promocion.getAcceso(), "CUPON") &&
				StringUtils.equals(promocion.getCodAcceso(), couponCode)) {
				return promocion;
			}
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public void updateItems() {
		// reaplicar promociones
		ticketManager.getSesion().getSesionPromociones().aplicarPromociones((TicketVentaAbono) ticketManager.getTicket());
	    ticketManager.getTicket().getTotales().recalcular();
	    
	    Map<String, HeaderDiscountData> headerDiscounts = getHeaderDiscountsDetail();
	    
	    for (HeaderDiscountData headerDiscountData : headerDiscounts.values()) {
	    	GlobalDiscountData globalDiscountData = globalDiscounts.get(GLOBAL_DISCOUNT_PAYMENT_PREFIX + headerDiscountData.paymentMethodCode);
	    	
	    	if (globalDiscountData == null) {
	    		globalDiscountData = new GlobalDiscountData();
	    		globalDiscountData.headerDiscount = headerDiscountData;
	    		
	    		ItemSold discount = new ItemSold();	    		
	    		discount.setFieldValue(ItemSold.ItemNumber, String.valueOf(globalDiscountCounter));	    		
	    		discount.setFieldIntValue(ItemSold.DiscountAmount, headerDiscountData.amount);	    		
	    		discount.setFieldValue(ItemSold.RewardLocation, "1");	    		
	    		discount.setFieldValue(ItemSold.ShowRewardPoints, "1");
	    		discount.setFieldValue(ItemSold.DiscountDescription, headerDiscountData.paymentMethodDes);						
	    		
	    		globalDiscountCounter++;
	    		
	    		globalDiscountData.itemSoldMessage = discount;
	    		
	    		globalDiscounts.put(GLOBAL_DISCOUNT_PAYMENT_PREFIX + headerDiscountData.paymentMethodCode, globalDiscountData);
	    		
	    		ncrController.sendMessage(discount);		 
				
				sendTotals();
	    	} else {
	    		ItemSold discount = globalDiscountData.itemSoldMessage;
	    		
	    		if (headerDiscountData.amount.compareTo(discount.getFieldBigDecimalValue(ItemSold.DiscountAmount, 2)) != 0) {
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
			    	
					for (PromocionTicket appliedPromotion : ((TicketVentaAbono)ticketManager.getTicket()).getPromociones()) {
			        	if(IPromocionTicket.COUPON_ACCESS.equals(appliedPromotion.getAcceso()) &&
			        	   StringUtils.equals(appliedPromotion.getCodAcceso(), globalDiscountData.coupon.getCouponCode()) ) {
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
		    	} else if (globalDiscountData.headerDiscount != null) {
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
			  
			  String actualDiscount = currentItemSold.getDiscountApplied().getFieldValue(ItemSold.DiscountAmount);
			  String newDiscount = newItemSold.getDiscountApplied().getFieldValue(ItemSold.DiscountAmount);
			  
//			  log.debug("Updating line " + ticketLine.getIdLinea());
//			  log.debug("actualDiscount " + actualDiscount);
//			  log.debug("newDiscount " + newDiscount);
			  
			  // compare cached values & send changes				
			  if (!StringUtils.equals(actualDiscount, newDiscount)) {
				  log.debug("Updating line " + ticketLine.getIdLinea());
				  
				  if (!StringUtils.equals(actualDiscount, "0") && StringUtils.equals(newDiscount, "0")) {
					  // delete discount line
					  ItemVoided voidItem = new ItemVoided();
					  voidItem.setFieldValue(VoidItem.ItemNumber, currentItemSold.getDiscountApplied().getFieldValue(ItemSold.ItemNumber));
					  ncrController.sendMessage(voidItem);
				  } else if (!StringUtils.equals(newDiscount, "0")) {
					  ncrController.sendMessage(newItemSold.getDiscountApplied());					  
				  }
				  sendTotals();
				  
				  linesCache.put(ticketLine.getIdLinea(), newItemSold);
			  }
		  }			
		}		
	}
	
	protected void sendItemSold(final ItemSold itemsold) {
		ncrController.sendMessage(itemsold);
		sendTotals();

		if (!StringUtils.equals(itemsold.getDiscountApplied().getFieldValue(ItemSold.Price), "0")) {
			ncrController.sendMessage(itemsold.getDiscountApplied());
			sendTotals();
		}
	}

	public void newItem(final LineaTicket newLine) {
		// send new line & totals
		ItemSold response = lineaTicketToItemSold(newLine);

		sendItemSold(response);
		
		// add new line to cache
		linesCache.put(newLine.getIdLinea(), response);
	}	
	
	public void newItemAndUpdateAllItems(final LineaTicket newLine) {
		newItem(newLine);

		// check/update all items
		updateItems();
	}

	public void evaluateItemType(final Item message) {
		String codigo = message.getFieldValue(Item.UPC);

		if (isLoyaltyCard(codigo))
			return;

		if (isCoupon(codigo)) {
			return;
		}

		if (isEspecialBarcode(message))
			return;

		newItemMessage(message);
	}
	
	@SuppressWarnings("unchecked")
	public CuponAplicadoTicket getApplicatedCoupon(String couponCode) {
		List<CuponAplicadoTicket> appliedCoupons = (List<CuponAplicadoTicket>) ticketManager.getTicket()
				.getCuponesAplicados();

		for (CuponAplicadoTicket appliedCoupon : appliedCoupons) {
			if (StringUtils.equals(appliedCoupon.getCodigo(), couponCode)) {
				return appliedCoupon;
			}
		}

		return null;
	}
	
	protected void deleteCoupon(String couponCode) {
		CuponAplicadoTicket appliedCoupon = getApplicatedCoupon(couponCode);
		
		if (appliedCoupon == null) {
			log.error("deleteCoupon()- Applied coupon not found: " + couponCode);
			return;
		}
		
		try {
			ticketManager.getSesion().getSesionPromociones().deleteCoupon(couponCode, (DocumentoPromocionable)ticketManager.getTicket());
		} catch (CuponUseException | CuponesServiceException | CuponAplicationException e) {
			log.error("deleteCoupon()- Error deleting coupon: " + couponCode, e);
		}
		
		globalDiscounts.remove(GLOBAL_DISCOUNT_COUPON_PREFIX + couponCode);
	}
	
	public void deleteItem(VoidItem message) {
		Integer itemNumber = Integer.valueOf(message.getFieldValue(VoidItem.ItemNumber));
		
		if (itemNumber < PROMOTIONS_FIRST_ITEM_ID) {
		   ticketManager.deleteTicketLine(itemNumber);
		} else if (itemNumber >= GLOBAL_DISCOUNT_FIRST_ITEM_ID) {
		   deleteCoupon(message.getFieldValue(VoidItem.UPC));
		}
		
		updateItems();		
		
		ItemVoided response = new ItemVoided();
		response.setFieldValue(ItemVoided.UPC, message.getFieldValue(VoidItem.UPC));
		response.setFieldValue(ItemVoided.ItemNumber, message.getFieldValue(VoidItem.ItemNumber));

		ncrController.sendMessage(response);	

		sendTotals();
	}
	
	public void deleteAllItems(VoidTransaction message) {
		if(ticketManager.getTicket().getCabecera().getIdTicket() != null) {
			ticketManager.saveEmptyTicket();
		}
		
		// copy transactionId before delete
		String transactionReferenceId = new String(transactionId);
		
		// cancel ticket and reset values
		resetTicket();
		
		// send transaction voided message
		TransactionVoided transactionVoided = new TransactionVoided();
		transactionVoided.setFieldValue(TransactionVoided.Id, transactionReferenceId);
		ncrController.sendMessage(transactionVoided);

		// print receipt
		Receipt receipt = new Receipt();
		receipt.addField(new NCRField<String>(Receipt.PrinterData + "1", "bin.base64"));
		
		receipt.setFieldValue(Receipt.Id, transactionReferenceId);		
		receipt.setFieldValue(Receipt.PrinterData + "1", Base64.getEncoder().encodeToString(I18N.getTexto("Transaccion cancelada").getBytes()));
		receipt.setFieldValue(Receipt.Complete, Receipt.COMPLETE_OK);
		ncrController.sendMessage(receipt);
		
		// end transaction
		EndTransaction endTransaction = new EndTransaction();
		endTransaction.setFieldValue(EndTransaction.Id, transactionReferenceId);
		ncrController.sendMessage(endTransaction);
	}

	public boolean isEspecialBarcode(Item message) {
		String codigo = message.getFieldValue(Item.UPC);

		CodigoBarrasBean codBarrasEspecial = null;
		try {
			codBarrasEspecial = codBarrasEspecialesServices.esCodigoBarrasEspecial(codigo);
		} catch (Exception e) {
			log.error(String.format(
					"checkCodigoBarrasEspecial() - Error inesperado %s tratando código de barras especial %s",
					e.getMessage(), codigo), e);
		}

		if (codBarrasEspecial == null)
			return false;

		BigDecimal cantidad = null;
		BigDecimal precio = null;
		boolean disableWeightItemFlag = false;

		String sku = codBarrasEspecial.getCodart();

		if (sku == null) {
			log.error(String.format(
					"checkCodigoBarrasEspecial() - El código de barra especial obtenido no es válido. CodArticulo: %s",
					sku));
			return false;
		}

		String cantCodBar = codBarrasEspecial.getCantidad();
		
		if (cantCodBar != null) {
			cantidad = new BigDecimal(cantCodBar);
		} else {
			if (message.getFieldValue(Item.Weight) != null) {
				cantidad = new BigDecimal(message.getFieldValue(Item.Weight)).divide(new BigDecimal(1000));
			}
		}
		
		String precioCodBar = codBarrasEspecial.getPrecio();

		if (precioCodBar != null) {
			if (cantidad == null) {
				cantidad = BigDecimal.ONE;
				disableWeightItemFlag = true;
			}
			
			precioCodBar = precioCodBar.replaceAll("\\.", ",");
			precio = FormatUtil.getInstance().desformateaBigDecimal(precioCodBar, 2);
		}

		try {
			LineaTicketAbstract linea = ticketManager.getItemPrice(sku);

			// Check if item is weight required
			if (cantidad == null && StringUtils.isNotBlank(linea.getArticulo().getBalanzaTipoArticulo())
					&& "P".equals(linea.getArticulo().getBalanzaTipoArticulo().trim().toUpperCase())) {
				ItemException itemException = new ItemException();
				itemException.setFieldValue(ItemException.UPC, codigo);
				itemException.setFieldValue(ItemException.WeightRequired, "1");
				ncrController.sendMessage(itemException);

				return true;
			}	
			
			// if sku is barcode, change to internal item code
			if (!StringUtils.equals(linea.getCodArticulo(), sku)) {
				sku = linea.getCodArticulo();
			}
						
			if (cantidad == null) {
				cantidad = BigDecimal.ONE;
			}

			linea = ticketManager.createAndInsertTicketLine(sku, linea.getDesglose1(), linea.getDesglose2(), codigo, cantidad, precio);

			if (disableWeightItemFlag && StringUtils.equals(linea.getArticulo().getBalanzaTipoArticulo(), "P")) {
				// disable kg/price flag
				linea.getArticulo().setBalanzaTipoArticulo("U");
			}
			
			newItemAndUpdateAllItems((LineaTicket) linea);
		} catch (ArticuloNotFoundException | LineaTicketException e) {
			if (e instanceof LineaTicketException) {
			   log.error("Internal error while inserting line:" + e.getMessageI18N(), e);
			}

			ItemException itemException = new ItemException();
			itemException.setFieldValue(ItemException.UPC, codigo);
			itemException.setFieldValue(ItemException.NotFound, "1");
			ncrController.sendMessage(itemException);
		}

		return true;

	}
	
	
	public boolean isLoyaltyCard(String code) {
		if (!Dispositivos.getInstance().getFidelizacion().isPrefijoTarjeta(code))
			return false;

		try {
			FidelizacionBean fidelizado = Dispositivos.getInstance().getFidelizacion().consultarTarjetaFidelizado(
					code, ticketManager.getSesion().getAplicacion().getUidActividad());

			if (fidelizado.isBaja()) {
				throw new IllegalArgumentException(I18N.getTexto("La tarjeta de fidelización {0} no está activa",
						fidelizado.getNumTarjetaFidelizado()));
			}

			// valid card
			ticketManager.getTicket().getCabecera().setDatosFidelizado(fidelizado);
			ticketManager.recalculateTicket();

			LoyaltyCard message = new LoyaltyCard();
			message.setFieldValue(LoyaltyCard.AccountNumber, fidelizado.getNumTarjetaFidelizado());
			message.setFieldValue(LoyaltyCard.Status, "1");
			message.setFieldValue(LoyaltyCard.CardType, "loyalty");
			ncrController.sendMessage(message);

			sendTotals();
			
			updateItems();
		} catch (IllegalArgumentException e) {
			LoyaltyCard message = new LoyaltyCard();
			message.setFieldValue(LoyaltyCard.Status, "0");
			message.setFieldValue(LoyaltyCard.Message, e.getMessage());
			ncrController.sendMessage(message);
		} catch (Exception e) {
			log.error("nuevoCodigoArticulo() - Ha habido un error al leer la tarjeta de fidelizado: " + e.getMessage(),
					e);

			LoyaltyCard message = new LoyaltyCard();
			message.setFieldValue(LoyaltyCard.Status, "0");
			message.setFieldValue(LoyaltyCard.Message,
					I18N.getTexto("Ha habido un error al leer la tarjeta de fidelizado"));
			ncrController.sendMessage(message);
		}

		return true;
	}

	public boolean isCoupon(String code) {
		if (!ticketManager.getSesion().getSesionPromociones().isCoupon(code)) {
			return false;
		}
		
		boolean applied = false;
		String errorMessage = I18N.getTexto("No se ha podido aplicar el cupón.");
		
		if (globalDiscounts.containsKey(GLOBAL_DISCOUNT_COUPON_PREFIX + code)) {
			errorMessage = I18N.getTexto("El cupón ya ha sido aplicado");
		} else {
			try {
			    applied = applyCoupon(code);
			} catch (ApiException | CuponAplicationException e) {
				errorMessage = e.getMessage();			
			}
		}
		
		if (!applied) {
			CouponException couponException = new CouponException();
			couponException.setFieldValue(CouponException.UPC, code);
			couponException.setFieldValue(CouponException.Message, errorMessage);
			couponException.setFieldValue(CouponException.ExceptionType, "0");
			couponException.setFieldValue(CouponException.ExceptionId, "0");
			ncrController.sendMessage(couponException);	
			return true;
		}
						
		GlobalDiscountData couponData = globalDiscounts.get(GLOBAL_DISCOUNT_COUPON_PREFIX + code);
				
		ncrController.sendMessage(couponData.couponMessage);
		
		sendTotals();
						
		updateItems();
		
		return true;
	}
	
    public boolean applyCoupon(String couponCode) throws CuponAplicationException {
    	
		/*
		 * Solo necesita validación si el código de cupón tiene los prefijos
		 * configurados. En caso contrario, se entiende que es un código de acceso
		 * directo a una promoción.
		 */
    	boolean needValidation = ticketManager.getSesion().getSesionPromociones().isCouponWithPrefix(couponCode);
  		CustomerCouponDTO customerCouponDTO = new CustomerCouponDTO(couponCode, needValidation);
    		
   		boolean applied = applyCoupon(customerCouponDTO);
   		   		
   		return applied;
    }
    
    public boolean applyCoupon(CustomerCouponDTO customerCouponDTO) throws CuponAplicationException {
    	try {
    		String couponCode = customerCouponDTO.getCouponCode();
    		
			boolean applied = ticketManager.getSesion().getSesionPromociones().aplicarCupon(customerCouponDTO, (TicketVentaAbono)ticketManager.getTicket());
			
			// marcar como validado
			customerCouponDTO.setValidationRequired(false);
			
	   		if (applied && !globalDiscounts.containsKey(GLOBAL_DISCOUNT_COUPON_PREFIX + couponCode)) {
	   			// get promotion data of coupon
	   			PromocionTicket couponPromotion = getCouponPromotion(couponCode);
	   			
	   			if (couponPromotion == null) {
	   				throw new RuntimeException("Error interno buscando la promocion aplicada por el cupon");
	   			}
	   			
	   			Coupon message = new Coupon();
	   			message.setFieldValue(Coupon.UPC, couponCode);
	   			message.setFieldValue(Coupon.ItemNumber, globalDiscountCounter.toString());
                message.setFieldValue(Coupon.Description, couponPromotion.getTextoPromocion() + " " + couponCode +  " (" +  couponPromotion.getIdPromocionAsString() + ")");
	   			message.setFieldIntValue(Coupon.Amount, BigDecimal.ZERO);
	   			
	   			GlobalDiscountData couponData = new GlobalDiscountData();
	   			couponData.couponMessage = message;
	   			couponData.coupon = customerCouponDTO;
	   			
	   			globalDiscounts.put(GLOBAL_DISCOUNT_COUPON_PREFIX + couponCode, couponData);
	   			globalDiscountCounter++;
	   		}
			
			return applied;
		} catch (CuponUseException | CuponesServiceException | CuponAplicationException e) {
			throw new CuponAplicationException(e.getMessage());
			//return false;
		}
    }
    
    public void changePriceItem(ChangeItemPrice message) {
    	Integer itemNumber = Integer.valueOf(message.getFieldValue(ChangeItemPrice.ItemNumber));
    	BigDecimal newPrice = new BigDecimal(message.getFieldValue(ChangeItemPrice.NewPrice));
    	newPrice = newPrice.divide(new BigDecimal(100), 2, RoundingMode.HALF_UP);
    	
    	LineaTicket line = ticketManager.changePriceItem(itemNumber, newPrice);
		
		ItemPriceChanged response = new ItemPriceChanged();
		response.setFieldValue(ItemPriceChanged.UPC, line.getCodArticulo());
		response.setFieldIntValue(ItemPriceChanged.ItemNumber, new BigDecimal(line.getIdLinea()));
		response.setFieldIntValue(ItemPriceChanged.NewPrice, line.getPrecioTotalConDto());
		response.setFieldIntValue(ItemPriceChanged.ExtendedPrice, line.getImporteTotalConDto());
		response.setFieldIntValue(ItemPriceChanged.Quantity, line.getCantidad());

		ncrController.sendMessage(response);	

		sendTotals();
		
		updateItems();
    }
}
