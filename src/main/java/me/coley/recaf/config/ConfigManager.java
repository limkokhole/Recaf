package me.coley.recaf.config;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.tinylog.Logger.*;

/**
 * Config manager.
 *
 * @author Matt
 */
public class ConfigManager {
	private static final String KEY_DISPLAY = "display";
	private static final String KEY_BACKEND = "misc-backend";
	private static final File configDirectory = new File("rc-config");
	private final Map<String, Config> configs = new HashMap<>();

	/**
	 * Setup config instances.
	 */
	public void initialize() {
		// Setup each instance
		configs.put(KEY_DISPLAY, new ConfDisplay());
		configs.put(KEY_BACKEND, new ConfBackend());
		// Add shutdown save hook
		Runtime.getRuntime().addShutdownHook(new Thread(this::save));
		// Load initial values
		load();
	}
	// =============================================================- //

	/**
	 * @return Display configuration.
	 */
	public ConfDisplay display() {
		return (ConfDisplay) configs.get(KEY_DISPLAY);
	}

	/**
	 * @return Private configuration.
	 */
	public ConfBackend backend() {
		return (ConfBackend) configs.get(KEY_BACKEND);
	}

	// ============================================================== //

	private void load() {
		for (Config c : configs.values()) {
			File file = new File(configDirectory, c.getName() + ".json");
			try {
				if(file.exists())
					c.load(file);
			} catch(IOException ex) {
				error(ex, "Failed to load config: {}" + file);
			}
		}
	}

	private void save() {
		for (Config c : configs.values()) {
			File file = new File(configDirectory, c.getName() + ".json");
			try {
				c.save(file);
			} catch(IOException ex) {
				error(ex, "Failed to save config: {}" + file);
			}
		}
	}
}
