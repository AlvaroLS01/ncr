package com.comerzzia.pos.ncr.messages;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;

public class Receipt extends BasicNCRMessage {
	public static final String Id = "Id";
	public static final String PrinterData = "PrinterData.";
	public static final String Complete = "Complete";

	public static final String COMPLETE_OK = "1";

	private int documentLines = 0;

	public Receipt() {
		addField(new NCRField<String>(Id, "string"));
		addField(new NCRField<String>(Complete, "int"));
	}

	public void addPrinterData(ByteArrayOutputStream streamOut, String charsetName) {
		String document = "";
		try {
			document = new String(streamOut.toByteArray(), charsetName);
			
			String[] lines = document.split(System.getProperty("line.separator"));

			for (int x = 0; x < lines.length; x++) {
				if (StringUtils.length(lines[x]) > 0) {
					documentLines++;
					
					if (lines[x].startsWith("DataType=")) {
						addField(new NCRField<String>(PrinterData + documentLines, "string"));
						setFieldValue(PrinterData + documentLines, lines[x]);
					} else {
						lines[x] += System.getProperty("line.separator");
					    addField(new NCRField<String>(PrinterData + documentLines, "bin.base64"));
					    setFieldValue(PrinterData + documentLines, Base64.getEncoder().encodeToString(lines[x].getBytes(charsetName)));
					}					
					
				}
			}
		} catch (UnsupportedEncodingException ignore) {}
		
	}
}
