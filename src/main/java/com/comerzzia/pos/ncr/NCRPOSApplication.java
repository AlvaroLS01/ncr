package com.comerzzia.pos.ncr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import org.apache.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.comerzzia.core.servicios.ContextHolder;
import com.comerzzia.pos.core.dispositivos.ConfigDispositivosLoadException;
import com.comerzzia.pos.core.dispositivos.Dispositivos;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.tiendas.cajas.TiendaCajaServiceException;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.util.config.AppConfig;
import com.comerzzia.pos.util.config.SpringContext;
import com.comerzzia.pos.util.config.TPVConfig;
import com.comerzzia.pos.util.format.FormatUtil;
import com.comerzzia.pos.util.i18n.I18N;

import it.sauronsoftware.junique.AlreadyLockedException;
import it.sauronsoftware.junique.JUnique;

public class NCRPOSApplication {
	public static String COMERZZIA_HOME = "COMERZZIA_HOME";

	protected Locale locale;

	protected Throwable initException;

	public static NCRPOSApplication instance;
	public static String cmzHomePath;
	private Sesion sesion;
	
    private NCRController ncrController;
    
	// Logger
	private static final Logger log = Logger.getLogger(NCRPOSApplication.class.getName());


	/**
	 * Método principal de la aplicación POS En lugar de llamar a Application.launch() que a su vez llama a
	 * LauncherImpl.launchApplication(Class application, args) llamamos a LauncherImpl.launchApplication(Class
	 * application, Class preloader, args); para que muestre nuestro Preloader.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {		
		boolean alreadyRunning=true;
		try {
			JUnique.acquireLock("comerzzia/jpos");
			alreadyRunning = false;
		} catch (AlreadyLockedException e) {
		}
		
		if (alreadyRunning) {
			log.error("comerzzia POS already running. Aborting.");
			System.exit(100);
		}
		
		try {
			Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler(){
				
				@Override
				public void uncaughtException(Thread t, Throwable e) {
					log.error("Error no controlado en thread: " + t + " Exception: " + e, e);
				}
			});
		
			instance = new NCRPOSApplication();			
        }		
        catch (Exception e) {
        	log.fatal("load() - Error al iniciar la aplicación : " + e.getMessage(), e);
        }
	}
	
	public NCRPOSApplication() {
		init();
		start();
	}


	

	/**
	 * Es llamado antes de start() y mientras se ejecuta el Preloader. No es llamado dentro del UI Thread.
	 * 
	 * @throws Exception
	 */
	private void init() {
		// Asignamos la instancia
		instance = this;

		try {			
			long time = System.currentTimeMillis();
			
			@SuppressWarnings({ "resource", "unused" })
			ApplicationContext rootContext = new ClassPathXmlApplicationContext("classpath*:comerzzia-*context.xml");
			
			log.debug(String.format("main() - Spring context inicializado en %d milisegundos", System.currentTimeMillis() - time));
			
			locale = new Locale(AppConfig.idioma, AppConfig.pais);
			FormatUtil formatUtil = FormatUtil.getInstance();
			formatUtil.init(locale);

			sesion = SpringContext.getBean(Sesion.class);
			sesion.initAplicacion();			

			// Cargamos dispositivos
			Dispositivos dispositivos = Dispositivos.getInstance();

			try {
				// Leemos los dispositivos disponibles
				dispositivos.leerDispositivosDisponibles();
			}
			catch (ConfigDispositivosLoadException e) {
				log.error(e.getMessage(), e);
			}
			try {
				// Cargamos la configuración de los dispositivos
				dispositivos.cargarConfiguracionDispositivos(sesion.getAplicacion().getTiendaCaja().getConfiguracion());
			}
			catch (ConfigDispositivosLoadException e) {
				if (sesion.getAplicacion().getTiendaCaja().getConfiguracion() == null) {
					e.getErrores().add(0, I18N.getTexto("Aún no se han configurado los dispositivos"));					
				}				
			}
			
			VariablesServices variablesServices = SpringContext.getBean(VariablesServices.class);
			
			// loyalty card device api key assign
			Dispositivos.getInstance().getFidelizacion().setApikey(variablesServices.getVariableAsString(VariablesServices.WEBSERVICES_APIKEY));		   
		}
		catch (Exception e) {
			// Capturamos la excepción y la mostraremos cuando se ejecute start()
			log.error("init() " + e.getMessage(), e);
			initException = e;
		}
	}


	private void start() {
			if (checkUidTpvInUse()) {
				return;
			}
					
			try {
				ncrController = (NCRController)ContextHolder.getBean("NCRController");
				ncrController.start(6696);
			} catch (ClassNotFoundException | IOException e) {
				log.error("Error loading NCR Controller: " + e.getMessage(), e);
			}
	}

	
	public void stop() {
		System.exit(0);
	}

	
	private boolean checkUidTpvInUse() {
		boolean close = false;
		try {
			//Obtenemos el archivo uid_pos.properties en la misma ruta que pos_config.xml
			URL resource = Thread.currentThread().getContextClassLoader().getResource(TPVConfig.POS_CONFIG_NAME);
			String path = resource.toURI().toString();
			log.debug("checkUidTpvInUse() - URI pos_config.xml: " + path);
			String separator = path.contains("/")? "/" : "\\";
			File uidPosFile = new File(new URL(path.substring(0, path.lastIndexOf(separator)) + separator + "uid_pos.properties").toURI());
			log.debug("checkUidTpvInUse() - URI uid_pos.properties: " + uidPosFile.toURI().toString());
			uidPosFile.createNewFile();
			Properties uidPosProperties = new Properties();
			uidPosProperties.load(new FileInputStream(uidPosFile));
			
			String uidTpv = uidPosProperties.getProperty("uid_pos");
			if(uidTpv == null){
				uidTpv = UUID.randomUUID().toString();
				uidPosProperties.put("uid_pos", uidTpv);
				uidPosProperties.store(new FileOutputStream(uidPosFile), "");
			}
			
			//Obtenemos el UID_TPV asociado a la caja actual
			String uidTpvActual = sesion.getAplicacion().getTiendaCaja().getUidTpv();
			
			//Comparamos los uidTpv y mostramos aviso
			if(uidTpvActual == null){
				sesion.getAplicacion().actualizarUidPos(uidTpv);
			}else if(!uidTpv.equals(uidTpvActual)){
				close = false;
			}
		
		} catch (IOException e) {
			log.error("checkUidTpvInUse() - Error al escribir el fichero uid_pos.properties - " + e.getClass().getName() + " - " + e.getLocalizedMessage(), e);
		} catch (TiendaCajaServiceException e) {
			log.error("checkUidTpvInUse() - Error al salvar en base de datos - " + e.getClass().getName() + " - " + e.getLocalizedMessage(), e);
		} catch (URISyntaxException e) {
			log.error("checkUidTpvInUse() - Error al escribir el fichero uid_pos.properties - " + e.getClass().getName() + " - " + e.getLocalizedMessage(), e);
		} catch (Exception e) {
			log.error("checkUidTpvInUse() - Error al escribir el fichero uid_pos.properties - " + e.getClass().getName() + " - " + e.getLocalizedMessage(), e);
		}
		
		return close;
	}
	
	public Locale getLocale() {
		return locale;
	}

	public void setLocale(Locale locale) {
		this.locale = locale;
	}
	
	public static Sesion getSesion() {
		return instance.sesion;
	}
	
}
