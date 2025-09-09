package com.comerzzia.pos.ncr.configuration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import com.comerzzia.pos.util.xml.MapAdapter;

@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class NCRPosConfiguration {
   protected boolean authenticationBypass = false;
   protected boolean sendLinesDiscounts = true;
   protected boolean simulateAllPayAsCash = false;
      
   @XmlJavaTypeAdapter(MapAdapter.class)
   protected Map<String, String> paymentsCodesMapping = new HashMap<String, String>();
   protected String pickupConcept = "01";
   protected String loanConcept = "03";
      
   @XmlElementWrapper(name = "headCashierRolesList")
   @XmlElement(name = "role")
   protected Set<Long> headCashierRoles = new HashSet<Long>();
   
   @XmlJavaTypeAdapter(MapAdapter.class)
   protected Map<String, String> lcidToIsoMapping = new HashMap<String, String>();
   
   public NCRPosConfiguration() {
	   paymentsCodesMapping.put("Credit", "0010");
	   paymentsCodesMapping.put("Cash", "0000");
	   headCashierRoles.add(0L);
	   
	   lcidToIsoMapping.put("0C0A", "es-es");
	   lcidToIsoMapping.put("0403", "ca-es");
	   lcidToIsoMapping.put("0409", "en-us"); 
	   lcidToIsoMapping.put("0809", "en-gb");
	   lcidToIsoMapping.put("040C", "fr-fr"); 
	   
   }

	public boolean isAuthenticationBypass() {
		return authenticationBypass;
	}
	
	public void setAuthenticationBypass(boolean authenticationBypass) {
		this.authenticationBypass = authenticationBypass;
	}
	
	public boolean isSendLinesDiscounts() {
		return sendLinesDiscounts;
	}
	
	public void setSendLinesDiscounts(boolean sendLinesDiscounts) {
		this.sendLinesDiscounts = sendLinesDiscounts;
	}
	
	public boolean isSimulateAllPayAsCash() {
		return simulateAllPayAsCash;
	}

	public void setSimulateAllPayAsCash(boolean simulateAllPayAsCash) {
		this.simulateAllPayAsCash = simulateAllPayAsCash;
	}

	public Map<String, String> getPaymentsCodesMapping() {
		return paymentsCodesMapping;
	}
	
	public void setPaymentsCodesMapping(Map<String, String> paymentsCodesMapping) {
		this.paymentsCodesMapping = paymentsCodesMapping;
	}

	public String getPickupConcept() {
		return pickupConcept;
	}

	public void setPickupConcept(String pickupConcept) {
		this.pickupConcept = pickupConcept;
	}

	public String getLoanConcept() {
		return loanConcept;
	}

	public void setLoanConcept(String loanConcept) {
		this.loanConcept = loanConcept;
	}

	public Set<Long> getHeadCashierRoles() {
		return headCashierRoles;
	}

	public void setHeadCashierRoles(Set<Long> headCashierRoles) {
		this.headCashierRoles = headCashierRoles;
	}

	public Map<String, String> getLcidToIsoMapping() {
		return lcidToIsoMapping;
	}

	public void setLcidToIsoMapping(Map<String, String> lcidToIsoMapping) {
		this.lcidToIsoMapping = lcidToIsoMapping;
	}
	
	
	
}
