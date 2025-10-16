package com.comerzzia.pos.ncr.actions.init;

import java.text.SimpleDateFormat;
import java.util.Date;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.NCRPOSApplication;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.Command;
import com.comerzzia.pos.ncr.messages.Heartbeat;
import com.comerzzia.pos.ncr.messages.InitializationComplete;
import com.comerzzia.pos.ncr.messages.Initialize;
import com.comerzzia.pos.ncr.messages.PosState;
import com.comerzzia.pos.ncr.messages.RequestPosState;
import com.comerzzia.pos.ncr.messages.ShutdownComplete;
import com.comerzzia.pos.ncr.messages.ShuttingDown;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;

@Lazy(false)
@Service
public class InitializeManager implements ActionManager {	
	private static final Logger log = Logger.getLogger(InitializeManager.class);
	private SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX");
	
	@Autowired
	NCRController ncrController;
	
	@Autowired
	protected ScoTicketManager ticketManager;
	
	@Autowired
	protected ItemsManager itemsManager;
	
	@Override
	public void processMessage(BasicNCRMessage message) {
		if (message instanceof Initialize) {
			ticketManager.initSession();
			itemsManager.initSession();
			
			InitializationComplete response = new InitializationComplete();
			response.setFieldValue(InitializationComplete.Version, "1");
			response.setFieldValue(InitializationComplete.LaneNumber, NCRPOSApplication.getSesion().getAplicacion().getCodCaja());
			response.setFieldValue(InitializationComplete.HeartbeatTimeout, "3000");
			
			ncrController.sendMessage(response);
		} else if (message instanceof RequestPosState) {
			PosState response = new PosState();
			response.setFieldValue(PosState.State, PosState.STATE_INACTIVE);
			
			ncrController.sendMessage(response);
		} else if (message instanceof Heartbeat) {
			Heartbeat response = new Heartbeat();
			response.setFieldValue(Heartbeat.Time, fmt.format(new Date()));
			response.setFieldValue(Heartbeat.Response, Heartbeat.RESPONSE_POS_INITIATED);
			
			ncrController.sendMessage(response);
		} else if (message instanceof ShuttingDown) {
			ShutdownComplete response = new ShutdownComplete();
			ncrController.sendMessage(response);
			ncrController.stop();
			
		} else if (message instanceof Command) {
			// ignore
		} else {
			log.warn("Message type not managed: " + message.getName());
		}
			
	}
	
	@PostConstruct
	public void init() {
	   ncrController.registerActionManager(Initialize.class, this);
	   ncrController.registerActionManager(RequestPosState.class, this);
	   ncrController.registerActionManager(Heartbeat.class, this);
	   ncrController.registerActionManager(Command.class, this);
	   ncrController.registerActionManager(ShuttingDown.class, this);
	}
}
