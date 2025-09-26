package com.comerzzia.dinosol.pos.ncr.actions.init;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.comerzzia.core.servicios.ContextHolder;
import com.comerzzia.dinosol.pos.ncr.services.ticket.DinoScoTicketManager;
import com.comerzzia.dinosol.pos.services.payments.methods.types.sipay.SipayService;
import com.comerzzia.dinosol.pos.services.payments.methods.types.sipay.TefSipayManager;
import com.comerzzia.dinosol.pos.services.ticket.cabecera.DinoCabeceraTicket;
import com.comerzzia.dinosol.pos.services.ticket.pagos.sipay.InfoSipayTransaction;
import com.comerzzia.pos.ncr.actions.init.InitializeManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.Initialize;
import com.comerzzia.pos.persistence.core.documentos.tipos.TipoDocumentoBean;
import com.comerzzia.pos.persistence.tickets.aparcados.TicketAparcadoBean;
import com.comerzzia.pos.services.core.documentos.Documentos;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.configuration.PaymentsMethodsConfiguration;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.ticket.TicketVentaAbono;
import com.comerzzia.pos.services.ticket.copiaSeguridad.CopiaSeguridadTicketService;
import com.comerzzia.pos.util.xml.MarshallUtil;

@Lazy(false)
@Service
public class DinoInitializeManager extends InitializeManager {

	public static final Logger log = Logger.getLogger(DinoInitializeManager.class);
	
	public static final String CODMEDPAG_SIPAY = "0002";
	
	@Autowired
	private Sesion sesion;
	
	@Autowired
	private PaymentsMethodsConfiguration paymentsMethodsConfiguration;
	
	@Autowired
	private CopiaSeguridadTicketService copiaSeguridadTicketService;

	@Override
	public void processMessage(BasicNCRMessage message) {
		super.processMessage(message);
		
		if (message instanceof Initialize) {
			consultarCopiaSeguridad();
		}
	}

	@SuppressWarnings("rawtypes")
	protected void consultarCopiaSeguridad() {
		log.debug("consultarCopiaSeguridad() - Consultando copia de seguridad");
		try {
			TipoDocumentoBean tipoDocumentoActivo = sesion.getAplicacion().getDocumentos().getDocumento(Documentos.FACTURA_SIMPLIFICADA);
			final TicketAparcadoBean copiaSeguridad = copiaSeguridadTicketService.consultarCopiaSeguridadTicket(tipoDocumentoActivo);

			if (copiaSeguridad != null) {
				log.debug("consultarCopiaSeguridad() - Se ha encontrado una copia de seguridad");
				Class[] classes = ((DinoScoTicketManager) ticketManager).getTicketClasses(tipoDocumentoActivo).toArray(new Class[] {});
				TicketVentaAbono ticketRecuperado = (TicketVentaAbono) MarshallUtil.leerXML(copiaSeguridad.getTicket(), classes);
				anularTransaccionSipay(ticketRecuperado);
				if (ticketRecuperado.getIdTicket() != null) {
					ticketManager.ticketInitilize();
					ticketManager.getTicket().getCabecera().setIdTicket(ticketRecuperado.getIdTicket());
					ticketManager.getTicket().getCabecera().setCodTicket(ticketRecuperado.getCabecera().getCodTicket());
					ticketManager.saveEmptyTicket();
				}
			}
		}
		catch (Exception e) {
			log.error("consultarCopiaSeguridad() - Se ha producido un error al anular la trasacción de Sipay: " + e.getMessage(), e);
		}
	}

	private void anularTransaccionSipay(TicketVentaAbono ticketRecuperado) throws Exception {
		InfoSipayTransaction transaction = ((DinoCabeceraTicket) ticketRecuperado.getCabecera()).getTransactionsSipay();
		if (transaction == null) {
			return;
		}
		
		log.debug("anularTransaccionSipay() - Se va a anular la transaccion de Sipay con id " + transaction.getPaymentId());
		PaymentsManager paymentsManager = ContextHolder.get().getBean(PaymentsManager.class);
		paymentsManager.setPaymentsMethodsConfiguration(paymentsMethodsConfiguration);
		for (PaymentMethodManager paymentMethodManager : paymentsManager.getPaymentsMehtodManagerAvailables().values()) {
			if (paymentMethodManager instanceof TefSipayManager) {
				((TefSipayManager) paymentMethodManager).cancelPayPendingTicket(ticketRecuperado);
			}
		}
	}
}
