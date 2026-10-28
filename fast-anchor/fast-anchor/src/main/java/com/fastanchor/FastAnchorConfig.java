package com.fastanchor;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Tiny settings holder, saved to config/fast-anchor-optimizer.properties. */
public final class FastAnchorConfig {

	/** Highest delay = vanilla speed (4 ticks between right clicks). 0 = every tick. */
	public static final int MAX_DELAY = 4;

	public static boolean enabled = true;
	/** Ticks to wait between right clicks while the optimizer is active. */
	public static int delay = 2;
	/**
	 * true  = when no solid block touches the target spot, click empty space itself (true air place;
	 *         strict servers may refuse it and the anchor vanishes).
	 * false = only place next to a solid block (never ghosts).
	 */
	public static boolean pureAir = false;

	private FastAnchorConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("fast-anchor-optimizer.properties");
	}

	public static void load() {
		Path path = file();
		if (!Files.exists(path)) {
			save();
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			Properties props = new Properties();
			props.load(reader);
			enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
			delay = clamp(Integer.parseInt(props.getProperty("delay", "2").trim()));
			pureAir = Boolean.parseBoolean(props.getProperty("pureAir", "false"));
		} catch (IOException | NumberFormatException e) {
			enabled = true;
			delay = 2;
			pureAir = false;
		}
	}

	public static void save() {
		Properties props = new Properties();
		props.setProperty("enabled", Boolean.toString(enabled));
		props.setProperty("delay", Integer.toString(delay));
		props.setProperty("pureAir", Boolean.toString(pureAir));
		try (Writer writer = Files.newBufferedWriter(file())) {
			props.store(writer, "Fast Anchor Optimizer. delay: 0 (fastest) to 4 (vanilla speed)");
		} catch (IOException ignored) {
		}
	}

	public static int clamp(int value) {
		return Math.max(0, Math.min(MAX_DELAY, value));
	}
}
