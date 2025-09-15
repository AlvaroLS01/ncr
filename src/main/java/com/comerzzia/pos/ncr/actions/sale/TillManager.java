package com.comerzzia.pos.ncr.actions.sale;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.Loan;
import com.comerzzia.pos.ncr.messages.Pickup;
import com.comerzzia.pos.ncr.till.ScoTillManager;

@Lazy(false)
@Service
public class TillManager implements ActionManager {
	private static final Logger log = Logger.getLogger(TillManager.class);
	
	@Autowired
	protected NCRController ncrController;

	@Autowired
	protected ScoTillManager scoTillManager;
	

	@Override
	public void processMessage(BasicNCRMessage message) {
		if (message instanceof Pickup) {
			pickup((Pickup)message);		
		} if (message instanceof Loan) {
			loan((Loan)message);
		} else {
			log.warn("Message type not managed: " + message.getName());
		}

	}

	@PostConstruct
	public void init() {
		ncrController.registerActionManager(Pickup.class, this);
		ncrController.registerActionManager(Loan.class, this);
	}
	
	protected void pickup(Pickup message) {
		scoTillManager.pickup(message.getFieldBigDecimalValue(Pickup.Amount, 2));
		
		Pickup response = new Pickup();
		response.setFieldValue(Pickup.Amount, message.getFieldValue(Pickup.Amount));
		
		ncrController.sendMessage(response);
	}
	
	protected void loan(Loan message) {
		scoTillManager.loan(message.getFieldBigDecimalValue(Loan.Amount, 2));
		
		Loan response = new Loan();
		response.setFieldValue(Pickup.Amount, message.getFieldValue(Loan.Amount));
		
		ncrController.sendMessage(response);
	}
}
