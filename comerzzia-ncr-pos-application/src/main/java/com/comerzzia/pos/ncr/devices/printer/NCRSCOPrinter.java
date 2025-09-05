package com.comerzzia.pos.ncr.devices.printer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.lang.StringUtils;

import com.comerzzia.pos.dispositivo.impresora.ImpresoraDriver;
import com.comerzzia.pos.drivers.direct.DirectPrinter;
import com.comerzzia.pos.drivers.escpos.DevicePrinter;
import com.comerzzia.pos.drivers.javapos.facade.jpos.POSPrinterFacade;
import com.comerzzia.pos.ncr.messages.Receipt;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class NCRSCOPrinter extends ImpresoraDriver {
	private static final org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(NCRSCOPrinter.class.getName());
	
    private Receipt receipt;

//    private final String directorioQR = "DataType=Bitmap;URL=file:///c:/Comerzzia/qr/qr.bmp;Alignment=centered";
    
    @Override
    public boolean terminarDocumento() {    	
        try {
            receipt.addPrinterData(streamOut, getCharsetName());
            
            return true;
        }
        catch (Exception ex) {
            return false;
        }	    	        
    }
    
    private String getCharsetName() {
    	String charset = (String)parametros.get("charset");
    	
    	// check if charset is valid
    	if (charset != null) {
	    	try {
				"test".getBytes(charset);
			} catch (UnsupportedEncodingException e) {
				log.error("Error setting charset " + charset + ": " + e.getMessage(), e);
				charset = null;
			}
    	}
        return charset != null? charset : "Windows-1252";
    }

	public Receipt getRecepipt() {
		return receipt;
	}

	public void setRecepipt(Receipt receipt) {
		this.receipt = receipt;
	}
	
    @Override
    public void imprimirLogo() {
    	try {
			streamOut.write((codigosImpresora.getImageHeader() + System.getProperty("line.separator")).getBytes());
		} catch (Exception e) {
			log.error("Error imprimiendo logo: " + e.getMessage(), e);
		}
    }
		
    @Override
	public void imprimirCodigoBarras(String codigoBarras, String tipo, String alineacion, int tipoLeyendaNumericaCodBar,
			int height) {
		log.trace("imprimirCodigoBarras() - codigoBarras: " + codigoBarras + ", tipo: " + tipo + ", alineacion: "
				+ alineacion + ", tipoLeyendaNumericaCodBar: " + tipoLeyendaNumericaCodBar + ", height: " + height);

        try {
        	if(height == 0) {
        		height = 3;
        	}
        	
        	String JPOSAlign = "centered";
        	
            // Alineación del código de barras
            if (alineacion != null && !alineacion.isEmpty()) {
                switch (alineacion) {
                    case ("left"):
                    	JPOSAlign = alineacion;
                        break;
                    case ("right"):
                    	JPOSAlign = alineacion;                    	
                        break;
                    default:
                    	JPOSAlign = "centered";
                        break;
                }
            }        	
            
            // Texto del codigo de barras, por defecto, desactivado
            String JPOSTextPosition = "none";
            
            if (tipoLeyendaNumericaCodBar != 0) {
                switch (tipoLeyendaNumericaCodBar) {
                    case (1):
                    	JPOSTextPosition = "above";                    	
                        break;
                    case (2):
                    	JPOSTextPosition = "below";                   	                	
                        break;
                    default:
                    	JPOSTextPosition = "none";
                        break;
                }
            }          
            
            String comando = "DataType=Barcode;Symbology=";
        	
        	if(tipo ==  null || tipo.equals("128")){
        		if (codigoBarras.startsWith("{")) {
        		   comando += "Code128-" + codigoBarras.charAt(1);
        		   codigoBarras = codigoBarras.substring(2);
        		} else {
        		   comando += "Code128-B";
        		}
        	}
//        	else if(tipo.equals("QR")){
//        		comando += "Pdf417";
//        	}
        	else if(tipo.equals("8")){
        		comando += "EAN8";
        	}
        	else if(tipo.equals("13")){
        		comando += "EAN13";
        	} else {
        		comando += tipo;
        	}
        	
        	comando += ";Alignment=" + JPOSAlign;
        	
        	if (height > 0) {
        		comando += ";Height=" + String.valueOf(height);
        	}
        	
        	comando +=  ";Text-Position=" +  JPOSTextPosition + ";Value=" + codigoBarras + System.getProperty("line.separator");
        	
        	if(tipo.equals("QR")){
				String nombreFichero = procesarQR(codigoBarras);
				comando = ("DataType=Bitmap;URL=file:///" + nombreFichero + ";Alignment=centered" + System.getProperty("line.separator"));
			}
        	
        	streamOut.write(comando.getBytes());
        }
        catch (Exception e) {
            log.error("Error imprimiendo Código de Barras : " + codigoBarras, e);
        }
	}
    
    @Override
    public void comandoEntradaCodigoBarras(String comandoEntradaCodBar) {        
    }
	
	@Override
    public void abrirCajon() {
		// no hay cajón en el Selfcheckout
	}
	
	@Override
    public void comandoEntradaPlantilla(String comandoEntradaTexto, int leftMargin) {
		
	}
	
	@Override
    public void comandoSalidaPlantilla(String comandoSalidaTexto) {
		
	}
	
    @Override
    public void imprimirTexto(String texto, Integer size, String align, Integer style, String fontName, int fontSize) {
    	log.trace("imprimirTexto() - texto: " + texto + ", size: " + size + ", align: " + align + ", style: " + style + ", fontName: " + fontName);
        try {
        	List<byte[]> constants = new LinkedList<byte[]>();
        	
        	//Si tenemos size y align, alineamos con espacios
        	if(size != null && align != null) {
        		switch (align) {
	                case ALIGN_CENTER: {
	                	texto = DirectPrinter.alignCenter(texto, size);
	                	break;
	                }
	                case ALIGN_RIGHT: {
	                	texto = DirectPrinter.alignRight(texto, size);
	                	break;
	                }
	                default:
	                	texto = DirectPrinter.alignLeft(texto, size);
	                	break;
        		}
        	}else{
        		//Alineación normal por comando UPOS
        		if (align != null && !align.isEmpty()) {
        			switch (align) {
	        			case ALIGN_CENTER: {
	        				constants.add(POSPrinterFacade.CENTERED_SEQUENCE);
	        				break;
	        			}
	        			case ALIGN_RIGHT: {
	        				constants.add(POSPrinterFacade.RIGHT_JUSTIFY_SEQUENCE);
	        				break;
	        			}
	        			default:
	        				constants.add(POSPrinterFacade.LEFT_JUSTIFY_SEQUENCE);
	        				break;
        			}
        		}
        	}
        	
            if ((style & DevicePrinter.STYLE_BOLD) != 0) {
            	constants.add(POSPrinterFacade.BOLD_SEQUENCE);
            }
            if ((style & DevicePrinter.STYLE_UNDERLINE) != 0) {
                constants.add(POSPrinterFacade.UNDERLINE_SEQUENCE);
            }
            
            if(fontSize == 1) {
            	constants.add(POSPrinterFacade.DOUBLE_HEIGHT_SEQUENCE);
            } else if (fontSize == 4) {
            	constants.add("\u001b|tpC".getBytes());
            }
                        
            for(int i = 0; i < constants.size(); i++) {
            	streamOut.write(constants.get(i));
   		    }
            
            streamOut.write(texto.getBytes(getCharsetName()));
        }
        catch (IOException ex) {
            log.error("Error imprimiendo texto : " + texto, ex);
        }
    }
    
    @Override
    public void empezarLinea(int size, int lineCols) {
    	try {
	    	if (lineCols == 56) {    		
	    		//streamOut.write(("\u001b|" + String.valueOf(lineCols) + "vC").getBytes());
	    		streamOut.write(("\u001b|tbC").getBytes());
	    	} else {
//	    		streamOut.write(("\u001b|44vC").getBytes());
	    		//streamOut.write(("\u001b|1hC").getBytes());
	    		streamOut.write("\u001b|N".getBytes()); //Estilo normal
	    	}
    	} catch (IOException ex) {
            log.error("Error imprimiendo empezarlinea" , ex);
        }
    }
    
    @Override
    public void cortarPapel() {
        
        for (int i = 0; i <= codigosImpresora.getMargenInferior(); i++) {
                //ejecutaComando(codigosImpresora.getNewLine());
        }
        
        ejecutaComando(codigosImpresora.getCutReceipt());
        
        try {
			streamOut.write(System.getProperty("line.separator").getBytes());
		} catch (IOException e) {
			log.error("Error cortando papel" , e);
		}
    }
    
    @Override
    protected void ejecutaComando(String comando) {
    	if (!StringUtils.trimToEmpty(comando).isEmpty()) {
    		super.ejecutaComando(comando);
    	}
    }
        
	public String procesarQR(String codigo) {
		log.debug("procesarQR() - Inicio procesamiento del QR");
		String nombreFichero = "";
		try {
			log.debug("procesarQR() - Entra con código: " + codigo);
			/* Generamos la imagen del QR */
			QRCodeWriter barcodeWriter = new QRCodeWriter();
			BitMatrix bitMatrix = barcodeWriter.encode(codigo, BarcodeFormat.QR_CODE, 200, 200);
			BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
			
			nombreFichero = "C:\\Comerzzia\\qr\\qr_" + new Date().getTime() + ".bmp";
			ImageIO.write(qrImage, "bmp", new File(nombreFichero));	
			
		} catch (Exception e) {
			log.error("procesarQR() - No se ha añadido el QR: " + e.getMessage(), e);
		}
		
		return nombreFichero;
	}
	
}
