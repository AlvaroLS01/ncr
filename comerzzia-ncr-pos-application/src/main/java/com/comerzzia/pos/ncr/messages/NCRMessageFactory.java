package com.comerzzia.pos.ncr.messages;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.log4j.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

public class NCRMessageFactory {
   private static final Logger log = Logger.getLogger(NCRMessageFactory.class);
   
   public static BasicNCRMessage createFromString(String message) {
	   Document doc = convertStringToXMLDocument(message);
	   
	   Element root = doc.getDocumentElement();
	   
	   String messageName = root.getAttribute("name");

	   BasicNCRMessage ncrMessage = newMessage(messageName);
	   
	   if (ncrMessage == null) return null;
	   
	   ncrMessage.readXml(doc);
	   
	   return ncrMessage;
   }
   
   private static Document convertStringToXMLDocument(String xmlString) {
       DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        
       DocumentBuilder builder = null;
       try
       {
           //Create DocumentBuilder with default configuration
           builder = factory.newDocumentBuilder();
            
           //Parse the content to Document object
           Document doc = builder.parse(new InputSource(new StringReader(xmlString)));
           return doc;
       } 
       catch (Exception e) {
          log.error("Error parsing XML message: " + e.getMessage(), e);
       }
       return null;
   }
   
   public static BasicNCRMessage newMessage(String messageName) {
	   try {
         // returns the Class object for the class with the specified name
         Class<?> cls = Class.forName("com.comerzzia.pos.ncr.messages." + messageName);
         
         return (BasicNCRMessage)cls.newInstance();
	  } catch(ClassNotFoundException e) {
	     log.error("Message class not found: " + messageName);	  
      } catch(InstantiationException | IllegalAccessException ex) {
    	  log.error("Error creating Message object: " + ex.getMessage(), ex);
      }	   
	  return null;
   }
}
