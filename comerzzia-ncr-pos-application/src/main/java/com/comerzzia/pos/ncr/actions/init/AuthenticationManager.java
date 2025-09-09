package com.comerzzia.pos.ncr.actions.init;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.NCRController;
import com.comerzzia.pos.ncr.NCRPOSApplication;
import com.comerzzia.pos.ncr.actions.ActionManager;
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.EnterTrainingMode;
import com.comerzzia.pos.ncr.messages.ExitTrainingMode;
import com.comerzzia.pos.ncr.messages.PosState;
import com.comerzzia.pos.ncr.messages.SignOff;
import com.comerzzia.pos.ncr.messages.SignOn;
import com.comerzzia.pos.ncr.messages.TrainingModeEntered;
import com.comerzzia.pos.ncr.messages.TrainingModeExited;
import com.comerzzia.pos.ncr.messages.ValidateUserId;
import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.ncr.till.ScoTillManager;
import com.comerzzia.pos.persistence.core.perfiles.ParametrosBuscarPerfilesBean;
import com.comerzzia.pos.persistence.core.perfiles.PerfilBean;
import com.comerzzia.pos.services.core.perfiles.PerfilException;
import com.comerzzia.pos.services.core.perfiles.ServicioPerfiles;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.sesion.SesionInitException;
import com.comerzzia.pos.services.core.usuarios.UsuarioInvalidLoginException;
import com.comerzzia.pos.util.config.AppConfig;
import com.comerzzia.pos.util.i18n.I18N;

@Lazy(false)
@Service
public class AuthenticationManager implements ActionManager {
        private static final Logger log = Logger.getLogger(AuthenticationManager.class);

	@Autowired
	protected Sesion sesion;

	@Autowired
	NCRController ncrController;
		
	@Autowired
	protected ScoTicketManager ticketManager;
	
	@Autowired
	private ScoTillManager scotillManager;
	
        @Autowired
    protected ServicioPerfiles servicioPerfiles;

       private final AtomicBoolean signOnArrived = new AtomicBoolean(false);
       private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
       private ScheduledFuture<?> autoSignOnTask;
       private String lastUserId;
       private String lastPassword;
       private String lastRole;

	@Override
	public void processMessage(BasicNCRMessage message) {
               if (message instanceof ValidateUserId) {
                        validateUser((ValidateUserId)message);
                } else if (message instanceof SignOn) {
                        signOnArrived.set(true);
                        if (autoSignOnTask != null) {
                                autoSignOnTask.cancel(false);
                        }
                        log.debug("[NCRController] 'SignOn' recibido del SCO dentro del timeout. Continuamos flujo estándar.");
                       SignOn signOnMessage = (SignOn) message;
                       String role = (lastRole != null && !lastRole.isEmpty())
                                       ? lastRole
                                       : ncrController.getConfiguration().getDefaultRoleWhenMissing();
                       proceedAfterSignOn(signOnMessage.getFieldValue(SignOn.UserId),
                                       signOnMessage.getFieldValue(SignOn.Password),
                                       role,
                                       signOnMessage.getFieldValue(SignOn.LaneNumber), false);
               } else if (message instanceof SignOff) {
                        scotillManager.closeTill();
			
			SignOff response = new SignOff();
			response.setFieldValue(SignOff.LaneNumber, NCRPOSApplication.getSesion().getAplicacion().getCodCaja());

			ncrController.sendMessage(response);
		} else if (message instanceof EnterTrainingMode) {
			ticketManager.setTrainingMode(true);
			TrainingModeEntered response = new TrainingModeEntered();
			ncrController.sendMessage(response);
		} else if (message instanceof ExitTrainingMode) {
			ticketManager.setTrainingMode(false);
			TrainingModeExited response = new TrainingModeExited();
			ncrController.sendMessage(response);
		} else {
			log.warn("Message type not managed: " + message.getName());
		}

	}

	@PostConstruct
	public void init() {
		ncrController.registerActionManager(ValidateUserId.class, this);
		ncrController.registerActionManager(SignOn.class, this);
		ncrController.registerActionManager(SignOff.class, this);
		ncrController.registerActionManager(EnterTrainingMode.class, this);
		ncrController.registerActionManager(ExitTrainingMode.class, this);
	}

	public void validateUser(ValidateUserId message) {
                ValidateUserId response = new ValidateUserId();
                response.setFieldValue(ValidateUserId.UserId, message.getFieldValue(ValidateUserId.UserId));
		
		try {
                        if (ncrController.getConfiguration().isAuthenticationBypass() && AppConfig.modoDesarrollo) {
                                log.warn("Authentication bypass enabled. Ignoring user and password from message");
                                sesion.initUsuarioSesion(AppConfig.modoDesarrolloInfo.getUsuario(), AppConfig.modoDesarrolloInfo.getPassword());
                                lastUserId = AppConfig.modoDesarrolloInfo.getUsuario();
                                lastPassword = AppConfig.modoDesarrolloInfo.getPassword();
                                lastRole = message.getFieldValue(ValidateUserId.role);
                        } else {
                                lastUserId = message.getFieldValue(ValidateUserId.UserId);
                                lastPassword = message.getFieldValue(ValidateUserId.Password);
                                lastRole = message.getFieldValue(ValidateUserId.role);
                                sesion.initUsuarioSesion(lastUserId, lastPassword);
                        }
			
			sesion.getSesionUsuario().clearPermisos();
			
			response.setFieldValue(ValidateUserId.AuthenticationLevel, getAuthenticationLevel(sesion.getSesionUsuario().getUsuario().getIdUsuario()));
				        
	        // Nos aseguramos de que hay caja abierta
	        if (!scotillManager.ensureTillIsOpened()) {
	        	throw new Exception(I18N.getTexto("Error opening till"));
	        }
		} catch (SesionInitException ex) {
			//response.setFieldValue(ValidateUserId.AuthenticationLevel, "0");
			response.setFieldValue(ValidateUserId.Message, ex.getMessageDefault());
		} catch (UsuarioInvalidLoginException ex) {
			response.setFieldValue(ValidateUserId.AuthenticationLevel, "0");
			response.setFieldValue(ValidateUserId.Message, ex.getMessageI18N());
		} catch (Exception ex) {
			// response.setFieldValue(ValidateUserId.AuthenticationLevel, "0");
			response.setFieldValue(ValidateUserId.Message, ex.getMessage());			
		}		

                ncrController.sendMessage(response);

               if (ncrController.getConfiguration().isForceAutoSignOn()) {
                       log.debug("[NCRController] ValidateUserId enviado (AuthenticationLevel=2). Esperando 'SignOn' del SCO hasta "
                                       + ncrController.getConfiguration().getAutoSignOnTimeoutMs() + " ms...");
                       signOnArrived.set(false);
                       autoSignOnTask = scheduler.schedule(() -> {
                               if (!signOnArrived.get()) {
                                       log.warn("[NCRController] AutoSignOn: no se recibió 'SignOn' del SCO en "
                                                       + ncrController.getConfiguration().getAutoSignOnTimeoutMs()
                                                       + " ms; forzamos flujo de sign-on (config=forceAutoSignOn=true).");

                                       String effUser = (lastUserId != null && !lastUserId.isEmpty()) ? lastUserId
                                                       : ncrController.getConfiguration().getDefaultUserIdWhenMissing();
                                       String effPass = (lastPassword != null && !lastPassword.isEmpty()) ? lastPassword
                                                       : ncrController.getConfiguration().getDefaultPasswordWhenMissing();
                                       String effRole = (lastRole != null && !lastRole.isEmpty()) ? lastRole
                                                       : ncrController.getConfiguration().getDefaultRoleWhenMissing();
                                       proceedAfterSignOn(effUser, effPass, effRole,
                                                       ncrController.getConfiguration().getDefaultLaneNumber(), true);
                               }
                       }, ncrController.getConfiguration().getAutoSignOnTimeoutMs(), TimeUnit.MILLISECONDS);
               }
        }
	
        @SuppressWarnings("unchecked")
        protected String getAuthenticationLevel(Long userId) {
                ParametrosBuscarPerfilesBean param = new ParametrosBuscarPerfilesBean();
                param.setTamañoPagina(Integer.MAX_VALUE);
                param.setNumPagina(1);
                param.setIdUsuario(userId);

                boolean isSuperAdminUser = false;
                boolean isHeadCashierRole = false;
                final Set<Long> headCashierRoles = ncrController.getConfiguration().getHeadCashierRoles();

                try {
                        List<PerfilBean> perfiles = (List<PerfilBean>) servicioPerfiles.consultar(param).getPagina();
                        for (PerfilBean perfilBean : perfiles) {
                                if (perfilBean.getIdPerfil().equals(0l)) {
                                        isSuperAdminUser = true;
                                }

                                if (headCashierRoles.contains(perfilBean.getIdPerfil())) {
                                        isHeadCashierRole = true;
                                }
                        }
                } catch (PerfilException e) {
                        log.error("Error loading user roles: " + e.getMessage());
                }

                return (isSuperAdminUser || isHeadCashierRole ? ValidateUserId.AUTH_LEVEL_HEAD_CASHIER : ValidateUserId.AUTH_LEVEL_CASHIER);
        }

        private void proceedAfterSignOn(String userId, String password, String role, String lane, boolean fromAuto) {
                try {
                        sesion.initUsuarioSesion(userId, password);
                } catch (Exception e) {
                        log.error("Error initializing session: " + e.getMessage(), e);
                }

                if (fromAuto && ncrController.getConfiguration().isForceAutoSignOn()) {
                        log.warn("[LoginFlowGuard] Fallback de perfil/menú aplicado (forceAutoSignOn).");
                }

                PosState posState = new PosState();
                posState.setFieldValue(PosState.State, "active");
                ncrController.sendMessage(posState);

                SignOn response = new SignOn();
                ncrController.sendMessage(response);

               log.info("[NCRController] Login/ventas habilitado" + (fromAuto ? " (AUTO)" : "")
                               + " para userId='" + userId + "', role='" + role + "', lane='" + lane + "'.");
       }
}
