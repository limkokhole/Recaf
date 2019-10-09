package me.coley.recaf.ui;

import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import me.coley.recaf.command.impl.Export;
import me.coley.recaf.command.impl.LoadWorkspace;
import me.coley.recaf.config.ConfBackend;
import me.coley.recaf.control.gui.GuiController;
import me.coley.recaf.ui.controls.ActionMenuItem;
import me.coley.recaf.ui.controls.ExceptionAlert;
import me.coley.recaf.workspace.WorkspaceIO;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

import static me.coley.recaf.util.LangUtil.translate;
import static org.tinylog.Logger.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Primary menu.
 *
 * @author Matt
 */
public class MainMenu extends MenuBar {
	private final FileChooser fcLoad = new FileChooser();
	private final FileChooser fcSaveApp = new FileChooser();
	private final FileChooser fcSaveWorkspace = new FileChooser();
	private final GuiController controller;
	private final Menu mFile;
	private final Menu mFileRecent;
	private final Menu mConfig;
	private final Menu mSearch;
	private final Menu mHistory;
	private final Menu mAttach;
	private final Menu mPlugins;
	private final Menu mHelp;

	/**
	 * @param controller
	 * 		Controller context.
	 */
	public MainMenu(GuiController controller) {
		this.controller = controller;
		//
		mFile = new Menu(translate("ui.menubar.file"));
		mFileRecent = new Menu(translate("ui.menubar.file.recent"));
		config().getRecentFiles().forEach(this::addRecentItem);
		mFile.getItems().addAll(
				new ActionMenuItem(translate("ui.menubar.file.load"), this::load),
				new ActionMenuItem(translate("ui.menubar.file.saveapp"), this::saveApplication),
				new ActionMenuItem(translate("ui.menubar.file.saveworkspace"), this::saveWorkspace),
				mFileRecent);
		// TODO: These
		mConfig = new Menu(translate("ui.menubar.config"));
		mSearch = new Menu(translate("ui.menubar.search"));
		mHistory = new Menu(translate("ui.menubar.history"));
		mAttach = new Menu(translate("ui.menubar.attach"));
		mPlugins = new Menu(translate("ui.menubar.plugins"));
		mHelp = new Menu(translate("ui.menubar.help"));
		//
		mConfig.setDisable(true);
		mSearch.setDisable(true);
		mHistory.setDisable(true);
		mAttach.setDisable(true);
		mPlugins.setDisable(true);
		mHelp.setDisable(true);
		//
		getMenus().addAll(mFile, mConfig, mSearch, mHistory, mAttach, mPlugins, mHelp);
		// Setup file-choosers
		ExtensionFilter filter = new ExtensionFilter(translate("ui.fileprompt.open.extensions"), "*.jar", "*.class");
		fcLoad.setTitle(translate("ui.filepropt.open"));
		fcLoad.getExtensionFilters().add(filter);
		fcLoad.setSelectedExtensionFilter(filter);
		fcLoad.setInitialDirectory(config().getRecentLoadDir());
		fcSaveApp.setTitle(translate("ui.filepropt.export"));
		fcSaveApp.getExtensionFilters().add(filter);
		fcSaveApp.setSelectedExtensionFilter(filter);
		fcSaveApp.setInitialDirectory(config().getRecentSaveAppDir());
		filter = new ExtensionFilter(translate("ui.fileprompt.open.extensions"), "*.json");
		fcSaveWorkspace.setTitle(translate("ui.filepropt.export"));
		fcSaveWorkspace.getExtensionFilters().add(filter);
		fcSaveWorkspace.setSelectedExtensionFilter(filter);
		fcSaveWorkspace.setInitialDirectory(config().getRecentSaveWorkspaceDir());
	}

	private void load() {
		File file = fcLoad.showOpenDialog(null);
		if (file != null)
			if (loadWorkspace(file))
				config().recentFiles.add(file.getAbsolutePath());
	}

	private void saveApplication() {
		File file = fcSaveApp.showSaveDialog(null);
		if (file != null) {
			Export exporter = new Export();
			exporter.setWorkspace(controller.getWorkspace());
			exporter.output = file;
			try {
				exporter.call();
				config().recentSaveApp = file.getAbsolutePath();
			} catch(Exception ex) {
				error(ex, "Failed to save application to file: {}", file.getName());
				ExceptionAlert.show(ex, "Failed to save application to file: " + file.getName());
			}
		}
	}

	private void saveWorkspace() {
		File file = fcSaveWorkspace.showSaveDialog(null);
		if (file != null) {
			String json = WorkspaceIO.toJson(controller.getWorkspace());
			try {
				FileUtils.write(file, json, UTF_8);
				config().recentSaveWorkspace = file.getAbsolutePath();
			} catch(IOException ex) {
				error(ex, "Failed to save workspace to file: {}", file.getName());
				ExceptionAlert.show(ex, "Failed to save workspace to file: " + file.getName());
			}
		}
	}

	private void addRecentItem(String path) {
		File file = new File(path);
		if(file.isFile()) {
			String name = file.getName();
			Node graphic = Icons.getFileIcon(file);
			mFileRecent.getItems().add(new ActionMenuItem(name, graphic, () -> loadWorkspace(file)));
		} else {
			// Not a valid file, so we remove it from the files list
			config().recentFiles.remove(path);
		}
	}

	private boolean loadWorkspace(File file) {
		LoadWorkspace loader = new LoadWorkspace();
		loader.input = file;
		try {
			controller.setWorkspace(loader.call());
			return true;
		} catch(Exception ex) {
			error(ex, "Failed to open file: {}", file.getName());
			ExceptionAlert.show(ex, "Failed to open file: " + file.getName());
			return false;
		}
	}

	private ConfBackend config() {
		return controller.config().backend();
	}
}
