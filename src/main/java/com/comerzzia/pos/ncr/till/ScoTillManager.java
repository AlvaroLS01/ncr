package com.comerzzia.pos.ncr.till;

import java.math.BigDecimal;
import java.util.Date;

import org.apache.commons.lang.time.DateUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.persistence.cajas.conceptos.CajaConceptoBean;
import com.comerzzia.pos.services.cajas.CajaEstadoException;
import com.comerzzia.pos.services.cajas.CajasServiceException;
import com.comerzzia.pos.services.cajas.conceptos.CajaConceptosServices;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.util.i18n.I18N;

@Service
public class ScoTillManager {
	protected Logger log = Logger.getLogger(getClass());
	
	@Autowired
	protected Sesion sesion;
	
	@Autowired
	NCRController ncrController;
	
	@Autowired
	private CajaConceptosServices cajaConceptosServices;
	
	public boolean ensureTillIsOpened() {
		// Nos aseguramos de que hay caja abierta
        try {
        	if (sesion.getSesionCaja().isCajaAbierta()) {
        		// automatic close if date changes
    	    	if (!DateUtils.isSameDay(sesion.getSesionCaja().getCajaAbierta().getFechaApertura(), new Date())) {
    	    		closeTill();
    	    		sesion.getSesionCaja().abrirCajaAutomatica();    	    		
    	    	}        		
        	} else {
			   sesion.getSesionCaja().abrirCajaAutomatica();
        	}
		} catch (CajasServiceException | CajaEstadoException e) {
			log.error("Error in till open procedure", e);
			return false;
		}
        
        return true;
	}
	
	public boolean closeTill() {
		// Nos aseguramos de que hay caja abierta
        try {
        	if (sesion.getSesionCaja().isCajaAbierta()) {
        		sesion.getSesionCaja().actualizarDatosCaja();
        		sesion.getSesionCaja().actualizarRecuentoCaja();
	    		sesion.getSesionCaja().guardarCierreCaja(new Date());
	    		sesion.getSesionCaja().cerrarCaja();
        	}
		} catch (CajasServiceException e) {
			log.error("Error in till close procedure", e);
			return false;
		}
        
        return true;		
	}	
	
	public boolean pickup(BigDecimal amount) {
		ensureTillIsOpened();
		
		try {
			String conceptCode = ncrController.getConfiguration().getPickupConcept();
			
			CajaConceptoBean concepto = cajaConceptosServices.consultarConcepto(conceptCode);

			sesion.getSesionCaja().crearApunteManual(amount, conceptCode, null, I18N.getTexto(concepto.getDesConceptoMovimiento()));
			
			return true;
		} catch (Exception e) {
			log.error("Error in pickup procedure: " + e.getMessage(), e);
			return false;
		}
	}
	
	public boolean loan(BigDecimal amount) {
		ensureTillIsOpened();
		
		try {
            String conceptCode = ncrController.getConfiguration().getLoanConcept();
			
			CajaConceptoBean concepto = cajaConceptosServices.consultarConcepto(conceptCode);

			sesion.getSesionCaja().crearApunteManual(amount, conceptCode, null, I18N.getTexto(concepto.getDesConceptoMovimiento()));
			
			return true;
		} catch (Exception e) {
			log.error("Error in loan procedure: " + e.getMessage(), e);
			return false;
		}
	}
	

}
