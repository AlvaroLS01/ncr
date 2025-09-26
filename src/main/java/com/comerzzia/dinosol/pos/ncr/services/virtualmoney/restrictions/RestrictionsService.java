package com.comerzzia.dinosol.pos.ncr.services.virtualmoney.restrictions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.comerzzia.api.virtualmoney.client.RestrictionsApi;
import com.comerzzia.api.virtualmoney.client.model.Restriction;
import com.comerzzia.api.virtualmoney.client.model.RestrictionCheckResult;
import com.comerzzia.core.servicios.api.ComerzziaApiManager;
import com.comerzzia.core.servicios.empresas.EmpresaException;
import com.comerzzia.core.servicios.sesion.DatosSesionBean;
import com.comerzzia.dinosol.pos.ncr.services.virtualmoney.restrictions.RestrictionGroups.RestrictionGroup;
import com.comerzzia.pos.persistence.articulos.ArticuloBean;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.util.config.AppConfig;
import com.google.gson.Gson;

@Component
public class RestrictionsService {

	private Logger log = Logger.getLogger(RestrictionsService.class);

	@Autowired
	private Sesion sesion;

	@Autowired
	private ComerzziaApiManager apiManager;

	@Autowired
	private VariablesServices variablesServices;

	private Restriction ageRestriction;

	private Restriction itemsRestriction;

	public void searchActiveRestrictions() {
		log.debug("searchActiveRestrictions() - Buscando restricciones activas");
		
		String ageRestrictionCode = variablesServices.getVariableAsString("X_SCO.RESTRICCION_EDAD");
		if(StringUtils.isNotBlank(ageRestrictionCode)) {
			log.debug("searchActiveRestrictions() - Buscando la restriccion en VM: " + ageRestrictionCode);
			ageRestriction = searchRestriction(ageRestrictionCode);
		}

		String itemsRestrictionCode = variablesServices.getVariableAsString("X_SCO.RESTRICCION_VENTA");
		if(StringUtils.isNotBlank(itemsRestrictionCode)) {
			log.debug("searchActiveRestrictions() - Buscando la restriccion en VM: " + itemsRestrictionCode);
			itemsRestriction = searchRestriction(itemsRestrictionCode);
		}
	}

	public boolean checkItemsRestriction(LineaTicket linea) {
		if (checkRestriction(itemsRestriction, linea) != null) {
			return true;
		}
		return false;
	}

	public boolean checkAgeRestriction(LineaTicket linea) {
		if (checkRestriction(ageRestriction, linea) != null) {
			return true;
		}
		return false;
	}

	private RestrictionCheckResult checkRestriction(Restriction restriction, LineaTicket linea) {
		RestrictionCheckResult restrictionCheckResult = null;
		
		RestrictionGroups groups = new RestrictionGroups();
		groups.parse(restriction.getRestrictionData().getRestrictionText());

		RestrictionGroup restrictionGroupItems = groups.getGroup(RestrictionGroups.ITEMS_KEY);
		RestrictionGroup restrictionGroupCategories = groups.getGroup(RestrictionGroups.CATEGORIES_KEY);
		RestrictionGroup restrictionGroupFamilies = groups.getGroup(RestrictionGroups.FAMILIES_KEY);

		ArticuloBean item = linea.getArticulo();
		
		if (restrictionGroupItems != null && restrictionGroupItems.getExclude().contains(item.getCodArticulo())) {
			return null;
		}

		if (restrictionGroupCategories != null) {
			boolean excluded = false;
			for (String category : restrictionGroupCategories.getExclude()) {
				if (StringUtils.startsWith(item.getCodCategorizacion(), category)) {
					excluded = true;
					break;
				}
			}

			if (excluded) {
				return null;
			}
		}

		if (restrictionGroupFamilies != null && restrictionGroupFamilies.getExclude().contains(item.getCodFamilia())) {
			return null;
		}

		// check if in include list
		String reason = "";

		if (restrictionGroupItems != null && restrictionGroupItems.getInclude().contains(item.getCodArticulo())) {
			reason = "Artículo restringido";
		}

		if (restrictionGroupCategories != null) {
			for (String category : restrictionGroupCategories.getInclude()) {
				if (StringUtils.equals("*", category) || StringUtils.startsWith(item.getCodCategorizacion(), category)) {
					reason = "Categoría excluída";
					break;
				}
			}
		}

		if (restrictionGroupFamilies != null && restrictionGroupFamilies.getInclude().contains(item.getCodFamilia())) {
			reason = "Familia excluida";
		}

		if (!reason.isEmpty()) {
			restrictionCheckResult = new RestrictionCheckResult();
			restrictionCheckResult.setItemCode(item.getCodArticulo());
			restrictionCheckResult.setDescription(item.getDesArticulo());
			restrictionCheckResult.setError(reason);
		}

		return restrictionCheckResult;
	}

	private Restriction searchRestriction(String restrictionCode) {
		try {
			RestrictionsApi api = getRestrictionsApi();

			Restriction restriction = api.getRestriction(restrictionCode);

			saveRestrictionFile(restriction, restrictionCode);
		}
		catch (Exception e) {
			log.error("searchAgeRestriction() - Ha habido un error al consultar la restricción " + restrictionCode + ": " + e.getMessage(), e);
			log.warn("searchAgeRestriction() - Se usará el fichero ya existente si lo hubiese.");
		}

		return readRestrictionFile(restrictionCode);
	}

	protected RestrictionsApi getRestrictionsApi() throws EmpresaException, Exception {
		DatosSesionBean datosSesion = new DatosSesionBean();
		datosSesion.setUidActividad(sesion.getAplicacion().getUidActividad());
		datosSesion.setUidInstancia(sesion.getAplicacion().getUidInstancia());
		datosSesion.setLocale(new Locale(AppConfig.idioma, AppConfig.pais));
		RestrictionsApi restrictionsApi = apiManager.getClient(datosSesion, "RestrictionsApi");
		return restrictionsApi;
	}

	protected Restriction readRestrictionFile(String name) {
		String filePath = "entities/restriction_" + name + ".json";
		try {
			log.debug("readRestrictionFile() - Leyendo el fichero " + filePath);

			Method method = (java.lang.Thread.class).getMethod("getContextClassLoader", (Class<?>[]) null);
			ClassLoader classLoader = (ClassLoader) method.invoke(Thread.currentThread(), (Object[]) null);
			URL url = classLoader.getResource(filePath);

			BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));

			String json = "";
			String line = "";
			while ((line = in.readLine()) != null) {
				json = json + System.lineSeparator() + line;
			}
			in.close();

			Gson gson = new Gson();

			Restriction restriction = gson.fromJson(json, Restriction.class);

			return restriction;
		}
		catch (Exception e) {
			log.error("readRestrictionFile() - No se ha podido leer el fichero " + filePath + ": " + e.getMessage(), e);
			return null;
		}
	}

	public void saveRestrictionFile(Restriction restriction, String nameFile) throws Exception {
		String filePath = "restriction_" + nameFile + ".json";
		try {
			log.debug("saveRestrictionFile() - Guardando fichero " + filePath);

			Gson gson = new Gson();
			String json = gson.toJson(restriction);

			log.debug("saveRestrictionFile() - Contenido: " + json);

			Method method = (java.lang.Thread.class).getMethod("getContextClassLoader", (Class<?>[]) null);
			ClassLoader classLoader = (ClassLoader) method.invoke(Thread.currentThread(), (Object[]) null);
			URL url = classLoader.getResource("entities");

			String folderPath = url.getPath();

			File archivo = new File(folderPath + File.separator + filePath);

			FileWriter writer = new FileWriter(archivo);
			writer.write(json);
			writer.close();
		}
		catch (Exception e) {
			log.error("searchAgeRestriction() - Ha habido un error al guardar el fichero de restricción " + filePath + ": " + e.getMessage(), e);
		}
	}

}
