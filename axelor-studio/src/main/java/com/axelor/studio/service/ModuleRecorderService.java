package com.axelor.studio.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.validation.ValidationException;

import org.apache.xmlbeans.impl.common.JarHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.app.AppSettings;
import com.axelor.common.FileUtils;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.i18n.I18n;
import com.axelor.meta.db.MetaModel;
import com.axelor.meta.db.repo.MetaModelRepository;
import com.axelor.studio.db.ModuleRecorder;
import com.axelor.studio.db.StudioConfiguration;
import com.axelor.studio.db.repo.ModuleRecorderRepository;
import com.axelor.studio.db.repo.StudioConfigurationRepository;
import com.axelor.studio.service.builder.ModelBuilderService;
import com.axelor.studio.service.builder.ViewBuilderService;
import com.axelor.studio.service.wkf.WkfService;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

/**
 * Service class use to build application and restart tomcat server.
 * 
 * @author axelor
 *
 */
public class ModuleRecorderService {

	private final Logger log = LoggerFactory.getLogger(getClass());

	@Inject
	private StudioConfigurationRepository configRepo;
	
	@Inject
	private ModuleRecorderRepository moduleRecorderRepo;
	
	@Inject
	private ConfigurationService configService;
	
	@Inject
	private WkfService wkfService;
	
	@Inject
	private MetaModelRepository metaModelRepo;
	
	@Inject
	private ModelBuilderService modelBuilderService;
	
	@Inject
	private ViewBuilderService viewBuilderService;
	
	public String update(ModuleRecorder recorder) throws AxelorException{
		
		String wkfProcess = wkfService.processWkfs();
		if (wkfProcess != null) {
			return I18n.get(String.format("Error in workflow processing: \n%s", wkfProcess));
		}
		
		MetaModel metaModel = metaModelRepo.all()
				.filter("self.edited = true and self.customised = true")
				.fetchOne();
		
		boolean record = metaModel != null || !recorder.getLastRunOk();
		if (record) {
			configService.config();
			File domainDir = configService.getDomainDir();

			if (!modelBuilderService.build(domainDir)) {
				return I18n.get("Error in model recording. Please check the log");
			}
			
			if (!buildApp(recorder)) {
				return I18n.get("Error in build. Please check the log");
			}
		}
		
		String viewUpdate =  viewBuilderService.build(configService.getViewDir(), 
				!record, recorder.getAutoCreate(), recorder.getAllViewUpdate());
		if (viewUpdate != null) {
			updateModuleRecorder(recorder, viewUpdate, true);
			return I18n.get("Error in view update. Please check the log");
		}
		
		if (record) {
			return updateApp(false);
		}
		
		updateModuleRecorder(recorder, null, true);
		
		return I18n.get("Views updated successfuly");
		
	}
	
	public String reset(ModuleRecorder moduleRecorder) throws IOException, AxelorException {
		
		configService.config();
		
		File moduleDir = configService.getModuleDir();
		log.debug("Deleting directory: {}",moduleDir.getPath());
		
		if (moduleDir.exists()) {
			FileUtils.deleteDirectory(moduleDir);
		}
		if (!buildApp(moduleRecorder)) {
			return I18n.get("Error in build. Please check the log");
		}
		
		return updateApp(true);
	}
	
	/**
	 * Method call process to build application.
	 * 
	 * @param moduleRecorder
	 *            ModuleRecorder record containing reference to build directory
	 *            and AxelorHome path..
	 * @return String array with first element as '0' if success and '-1' for
	 *         error. Second element is log from build process.
	 */
	public boolean buildApp(ModuleRecorder moduleRecorder) {

		String logText = "";
		boolean build = true;
		try {
			AppSettings settings = AppSettings.get();
			String buildDir = checkParams("Build directory",
					settings.get("build.dir"), true);
			String axelorHome = checkParams("Axelor home",
					settings.get("axelor.home"), true);
			File buildDirFile = new File(buildDir);

			StudioConfiguration config = configRepo.all().fetchOne();
			ProcessBuilder processBuilder = null;
			if (config != null) {
				String buildCmd = config.getBuildCmd();
				if (buildCmd != null) {
					processBuilder = new ProcessBuilder(buildCmd.split(" "));
				}
			}
			if (processBuilder == null) {
				processBuilder = new ProcessBuilder("./gradlew", "clean", "-x",
						"test", "build");
			}
			processBuilder.directory(buildDirFile);
			processBuilder.environment().put("AXELOR_HOME", axelorHome);

			Process process = processBuilder.start();

			BufferedReader reader = new BufferedReader(new InputStreamReader(
					process.getInputStream()));

			String line = "";
			while ((line = reader.readLine()) != null) {
				logText +=  line + "\n";
			}

			process.waitFor();

			Integer exitStatus = process.exitValue();
			
//			log.debug("Exit status: {}, Log text: {}", exitStatus, logText);

			if (exitStatus != 0) {
				build =  false;
			}
			
		} catch (ValidationException | IOException | InterruptedException e) {
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			logText = sw.toString();
			build =  false;
		}
		
		updateModuleRecorder(moduleRecorder, logText, build);
		
		return build;
	}

	/**
	 * Method call update application on given tomcat webapp path
	 * 
	 * @param moduleRecorder
	 *            Configuration record.
	 * @throws InterruptedException 
	 */
	public String updateApp(boolean reset){

		try {
			AppSettings settings = AppSettings.get();
			String buildDirPath = checkParams("Build directory",
					settings.get("build.dir"), true);
			String webappPath = checkParams("Tomcat webapp server path",
					settings.get("tomcat.webapp"), true);

			File warDir = new File(buildDirPath + File.separator + "build",
					"libs");
			log.debug("War directory path: {}", warDir.getAbsolutePath());
			if (!warDir.exists()) {
				return I18n
						.get("Error in application build. No build directory found");
			}
			File webappDir = new File(webappPath);
			File warFile = null;
			for (File file : warDir.listFiles()) {
				if (file.getName().endsWith(".war")) {
					warFile = file;
					break;
				}
			}

			if (warFile == null) {
				return I18n
						.get("Error in application build. No war file generated.");
			} else {
				String appName = warFile.getName();
				appName = appName.substring(0, appName.length() - 4);
				File appDir = new File(webappDir, appName);
				if (appDir.exists()) {
					FileUtils.deleteDirectory(appDir);
				}
				appDir.mkdir();
				log.debug("Webapp app directory: {}", appDir.getAbsolutePath());
				log.debug("War file: {}", warFile.getAbsolutePath());
				JarHelper jarHelper = new JarHelper();
				jarHelper.unjarDir(warFile, appDir);
			}
			
		} catch (ValidationException | IOException e) {
			e.printStackTrace();
			String msg = I18n.get("Error in update, please check the log.");
			if (reset) {
				msg = I18n.get("Error in reset, please check the log.");
			}
			return msg + e.getMessage();
		}
		
		if (reset) {
			String msg = I18n.get("App reset successfully");
			clearDatabase();
			return msg;
		}
		
		return I18n.get("App updated successfully");
	}

	/**
	 * Validate parameters to check if its null or not.
	 * 
	 * @param name
	 *            Name of parameter.
	 * @param param
	 *            Value of parameter.
	 * @param isFile
	 *            Boolean to check if its file.
	 * @return Value of parameter if its not null.
	 * @throws ValidationException
	 *             Throws validation exception if parameter value is null or if
	 *             parameter is file and file not exist.
	 */
	private String checkParams(String name, String param, boolean isFile)
			throws ValidationException {

		if (param == null) {
			throw new ValidationException(
					I18n.get("Required parameter is empty: ") + name);
		}

		if (isFile) {
			if (!(new File(param)).exists()) {
				throw new ValidationException(I18n.get("Path not exist: ")
						+ param);
			}
		}

		return param;

	}
	
	@Transactional
	public void clearDatabase() {
		
		JPA.em().createNativeQuery("drop schema public cascade").executeUpdate();
		JPA.em().createNativeQuery("create schema public").executeUpdate();
		
	}
	
	@Transactional
	public void updateModuleRecorder(ModuleRecorder moduleRecorder, String logText, boolean updateOk) {
		
		moduleRecorder = moduleRecorderRepo.find(moduleRecorder.getId());
		moduleRecorder.setLogText(logText);
		moduleRecorder.setLastRunOk(updateOk);
		
		moduleRecorderRepo.save(moduleRecorder);
	}
	
	
	
}
