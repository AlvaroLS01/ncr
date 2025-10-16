package com.comerzzia.pos.ncr;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Marshaller;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.comerzzia.core.util.config.ComerzziaApp;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.configuration.NCRPosConfiguration;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.NCRMessageFactory;
import com.comerzzia.pos.util.xml.MarshallUtil;

@Service
public class NCRController {
	private static final Logger log = Logger.getLogger(NCRController.class);
	private boolean stop;
	
	HashMap<String, ActionManager> actionsManager = new HashMap<>();
	
	@Autowired
	ComerzziaApp comerzziaApp;

	private ServerSocket server;
	private DataInputStream ois;
	private DataOutputStream oos;
	
	protected NCRPosConfiguration ncrPosConfiguration;

	public void start(int port) throws IOException, ClassNotFoundException {
		// create the socket server object
		server = new ServerSocket(port);
		stop = false;

		// keep listens indefinitely until receives 'exit' call or program terminates
		while (!stop) {
			log.info("Waiting for client connextion in port " + port);
			
			// creating socket and waiting for client connection
			Socket clientSocket = server.accept();
			
			ois = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));
			oos = new DataOutputStream(clientSocket.getOutputStream());

			log.info("Client connected from " + clientSocket.getInetAddress());
			
			loadConfiguration();
			
			try {
				while (clientSocket.isConnected() && !stop) {
					BasicNCRMessage message = readNCRMessage();
					
					if (message != null) {
					   if (actionsManager.containsKey(message.getName())) {
						   actionsManager.get(message.getName()).processMessage(message);
					   } else {
						   log.warn("No action manager for message " + message.getName());
					   }
					}
				}
			} catch (EOFException e1) {
				log.info("End of file received");
			} catch (java.net.SocketException e) {
				log.error("Socket exception: " + e.getMessage(), e);
			}

			oos.close();
			ois.close();
			clientSocket.close();

			log.info("Client disconnected");
		}

		log.info("Shutting down server!!");

		server.close();
	}
	
	
	public BasicNCRMessage readNCRMessage() throws IOException {
		String message = readMessage();
		
		if (message == null) {
			return null;
		}
		
		return NCRMessageFactory.createFromString(message);
	}
	
	private String readMessage() throws IOException {
 	    int length = ois.readInt();

		byte[] messageByte = new byte[length];
		boolean end = false;
		StringBuilder dataString = new StringBuilder(length);
		int totalBytesRead = 0;

		while (!end) {
			int currentBytesRead = ois.read(messageByte);
			totalBytesRead = currentBytesRead + totalBytesRead;
			if (totalBytesRead <= length) {
				dataString.append(new String(messageByte, 0, currentBytesRead, StandardCharsets.UTF_8));
			} else {
				dataString.append(new String(messageByte, 0, length - totalBytesRead + currentBytesRead,
						StandardCharsets.UTF_8));
			}
			if (dataString.length() >= length) {
				end = true;
			}
		}

		log.debug("Message Received: " + dataString.toString());
		
		if (dataString.toString().equalsIgnoreCase("exit")) {
			stop = true;
		}

		return dataString.toString();
	}

	public void stop() {
		stop = true;
	}
	
	public void sendMessage(BasicNCRMessage message) {
		String xmlMessage = message.toString(); 
		
		log.debug("Sending message: " + xmlMessage);
		try {
			oos.writeInt(xmlMessage.length());
			oos.writeBytes(xmlMessage);
			oos.flush();
		} catch (IOException e) {
			log.error("Error sending message: " + e.getMessage(), e);
		}
	}
	
	public void registerActionManager(Class<?> messageClass, ActionManager actionManager) {
		log.info("Registering action manager " + actionManager.getClass().getName() + " for message " + messageClass.getSimpleName());
		
		actionsManager.put(messageClass.getSimpleName(), actionManager);
	}
	
	public void unregisterActionManager(Class<?> messageClass) {
		log.info("Unregistering action manager for message " + messageClass.getSimpleName());
		actionsManager.remove(messageClass.getSimpleName());
	}	
	
	public void sendWaitState(String caption) {
		DataNeeded message = new DataNeeded();
		message.setFieldValue(DataNeeded.Type, "1");
		message.setFieldValue(DataNeeded.Id, "1");
		message.setFieldValue(DataNeeded.Mode, "0");
		message.setFieldValue(DataNeeded.TopCaption1, caption);
		
		sendMessage(message);		
	}
	
	public void sendFinishWaitState() {
		DataNeeded message = new DataNeeded();
		message.setFieldValue(DataNeeded.Type, "0");
		message.setFieldValue(DataNeeded.Id, "0");
		message.setFieldValue(DataNeeded.Mode, "0");
		
		sendMessage(message);		
	}
	
	protected void loadConfiguration() {
		final String configurationFileName = "NCRPosConfiguration.xml";
		
		URL url = this.getClass().getClassLoader().getResource(configurationFileName);
						
		if (url != null) {
			File file = new File(url.getPath());
			log.debug("loadConfiguration() - Loading configuration file " + configurationFileName + " from " + file.getAbsolutePath());
			
			try {
				byte[] fileContent = Files.readAllBytes(file.toPath());
				ncrPosConfiguration = (NCRPosConfiguration) MarshallUtil.leerXML(fileContent, NCRPosConfiguration.class);			
			} catch (Exception e) {
				log.error("loadConfiguration() - Error loading configuration " + e.getMessage() + ". Default values loaded", e);
				
				ncrPosConfiguration = new NCRPosConfiguration();
			}
		} else {
			log.warn("loadConfiguration() - Configuration file " + configurationFileName + " not found. Default values loaded");
			ncrPosConfiguration = new NCRPosConfiguration();
		}
		

		// log loaded configuration to console
		try {
			JAXBContext jaxbContext     = JAXBContext.newInstance(NCRPosConfiguration.class);
			Marshaller jaxbMarshaller   = jaxbContext.createMarshaller();
			jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);			
			jaxbMarshaller.marshal( ncrPosConfiguration, System.out);
		} catch (Exception e) {
			log.error("Error creating configuration file: " + e.getMessage(), e);
		}
		
	}
	
	public NCRPosConfiguration getConfiguration() {
		return this.ncrPosConfiguration;
	}
}
