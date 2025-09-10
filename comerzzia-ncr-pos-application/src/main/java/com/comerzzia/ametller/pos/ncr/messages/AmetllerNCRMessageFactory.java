package com.comerzzia.ametller.pos.ncr.messages;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.NCRMessageFactory;

/**
 * Custom message factory that knows how to build Ametller-specific NCR
 * messages while delegating to the standard {@link NCRMessageFactory} for all
 * others.
 */
public class AmetllerNCRMessageFactory {

    private static final Logger log = Logger.getLogger(AmetllerNCRMessageFactory.class);

    public static BasicNCRMessage createFromString(String message) {
        Document doc = convertStringToXMLDocument(message);
        if (doc == null) {
            return null;
        }

        Element root = doc.getDocumentElement();
        String messageName = root.getAttribute("name");

        BasicNCRMessage ncrMessage = newMessage(messageName);
        if (ncrMessage == null) {
            return null;
        }

        ncrMessage.readXml(doc);
        return ncrMessage;
    }

    private static Document convertStringToXMLDocument(String xmlString) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xmlString)));
        } catch (Exception e) {
            log.error("Error parsing XML message: " + e.getMessage(), e);
            return null;
        }
    }

    public static BasicNCRMessage newMessage(String messageName) {
        if ("ExitAssistMode".equals(messageName)) {
            return new AmetllerExitAssistMode();
        }
        return NCRMessageFactory.newMessage(messageName);
    }
}

