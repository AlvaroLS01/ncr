package com.comerzzia.pos.ncr.messages;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@SuppressWarnings("rawtypes")
public abstract class BasicNCRMessage {
	private static final Logger log = Logger.getLogger(BasicNCRMessage.class);
	
	protected String name = this.getClass().getSimpleName();
	protected String msgid = "b2";

	protected LinkedHashMap<String, NCRField> fields = new LinkedHashMap<>();

	public BasicNCRMessage() {
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMsgid() {
		return msgid;
	}

	public void setMsgid(String msgid) {
		this.msgid = msgid;
	}

	public LinkedHashMap<String, NCRField> getFields() {
		return fields;
	}

	public void setFields(LinkedHashMap<String, NCRField> fields) {
		this.fields = fields;
	}

	public void readXml(final Document doc) {
		Element root = doc.getDocumentElement();

		setName(root.getAttribute("name"));
		setMsgid(root.getAttribute("msgid"));

		NodeList xmlFieldsNodes = root.getElementsByTagName("fields");

		if (xmlFieldsNodes.getLength() == 0) {
		} else if (xmlFieldsNodes.getLength() == 1) {
			NodeList xmlFields = ((Element) xmlFieldsNodes.item(0)).getElementsByTagName("field");

			for (int x = 0; x < xmlFields.getLength(); x++) {
				Element field = (Element) xmlFields.item(x);

				setFieldValue(field.getAttribute("name"), field.getTextContent());
			}
		} else {
			log.warn("Incorrect number of fields node: " + xmlFieldsNodes.getLength());
		}
	}

	public void addField(final NCRField field) {
		fields.put(field.getName(), field);
	}

	public void setFieldValue(final String fieldName, final String value) {
		if (fields.containsKey(fieldName)) {
			fields.get(fieldName).setStringValue(value);
		} else {
			log.warn("Field " + fieldName + " not found in message " + getName());
		}
	}
	
	public void setFieldIntValue(final String fieldName, final BigDecimal value) {
		setFieldValue(fieldName, value.multiply(new BigDecimal(100)).stripTrailingZeros().toPlainString());
	}

	public String getFieldValue(final String fieldName) {
		if (fields.containsKey(fieldName)) {
			if (fields.get(fieldName).getValue() == null) {
			   return null;
			}
			return fields.get(fieldName).getValue().toString();
		} else {
			log.warn("Field " + fieldName + " not found in message " + getName());
			return null;
		}
	}
	
	public BigDecimal getFieldBigDecimalValue(final String fieldName, final int decimalPlaces) {
		if (fields.containsKey(fieldName)) {
			if (fields.get(fieldName).getValue() == null) {
			   return null;
			}
			return fields.get(fieldName).getBigDecimalValue(decimalPlaces);
		} else {
			log.warn("Field " + fieldName + " not found in message " + getName());
			return null;
		}
	}
	
	
	
	@Override
	public String toString() {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = null;
		
		try {
			// Create DocumentBuilder with default configuration
			builder = factory.newDocumentBuilder();

			// Parse the content to Document object
			Document doc = builder.newDocument();
			Element rootElement = doc.createElement("message");
			rootElement.setAttribute("name", getName());
			if (getMsgid() != null) {
			   rootElement.setAttribute("msgid", getMsgid());
			}
			
			doc.appendChild(rootElement);
			
			if (fields.size() > 0) {
				Element fieldsElement = doc.createElement("fields");
				
				fields.forEach((key,value) -> {
					if (value.getValue() != null) {
						Element fieldElement = doc.createElement("field");
						
						fieldElement.setAttribute("ftype", value.getFtype());
						fieldElement.setAttribute("name", key);
					
					
					   fieldElement.setTextContent(value.getValue().toString());
					   fieldsElement.appendChild(fieldElement);
					}
				});
				
				rootElement.appendChild(fieldsElement);
			}
			return documentToString(doc);
		} catch (Exception e) {
			log.error("Error creating XML message: " + e.getMessage(), e);
		}
		
		return null;
	}
	
	private String documentToString(Document doc) {
	    try {
	        StringWriter sw = new StringWriter();
	        TransformerFactory tf = TransformerFactory.newInstance();
	        Transformer transformer = tf.newTransformer();
	        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
	        
	        transformer.transform(new DOMSource(doc), new StreamResult(sw));
	        return sw.toString();
	    } catch (Exception ex) {
	        throw new RuntimeException("Error converting to String", ex);
	    }
	}
}
