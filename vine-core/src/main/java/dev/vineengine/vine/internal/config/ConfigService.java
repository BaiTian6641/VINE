package dev.vineengine.vine.internal.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.vineengine.vine.internal.ConfigBackend;

/**
 * vine-core's config service (sub-01 Stage E): a minimal internal TOML-subset
 * reader over {@code config/vine/engine.toml} with typed accessors, lazy load,
 * and reload listeners. Missing file = defaults only; malformed lines are
 * skipped with a log line (config must never break boot).
 *
 * <p>The reader is deliberately small and swappable (§11.4): shading a full
 * TOML library later touches only this class, never the API.
 */
public final class ConfigService implements ConfigBackend {

    private static final System.Logger LOG = System.getLogger("vine.config");

    private final Path configFile;
    private final Map<String, String> values = new HashMap<>();
    private final List<Runnable> reloadListeners = new CopyOnWriteArrayList<>();
    private volatile boolean loaded;

    /** Production constructor: {@code config/vine/engine.toml} under the CWD. */
    public ConfigService() {
        this(Path.of("config", "vine", "engine.toml"));
    }

    /** Explicit-path constructor (acceptance harnesses). */
    public ConfigService(Path configFile) {
        this.configFile = configFile;
    }

    // ------------------------------------------------------------------
    // ConfigBackend
    // ------------------------------------------------------------------

    @Override
    public String getString(String key, String defaultValue) {
        ensureLoaded();
        synchronized (values) {
            return values.getOrDefault(key, defaultValue);
        }
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            LOG.log(System.Logger.Level.WARNING,
                "[VINE] config key " + key + " is not an int ('" + raw + "') — using default " + defaultValue);
            return defaultValue;
        }
    }

    @Override
    public long getLong(String key, long defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            LOG.log(System.Logger.Level.WARNING,
                "[VINE] config key " + key + " is not a long ('" + raw + "') — using default " + defaultValue);
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        String raw = getString(key, null);
        if (raw == null) {
            return defaultValue;
        }
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        LOG.log(System.Logger.Level.WARNING,
            "[VINE] config key " + key + " is not a boolean ('" + raw + "') — using default " + defaultValue);
        return defaultValue;
    }

    @Override
    public void reload() {
        synchronized (values) {
            values.clear();
            read();
            loaded = true;
        }
        for (Runnable listener : reloadListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "[VINE] config reload listener threw: " + e);
            }
        }
        LOG.log(System.Logger.Level.INFO,
            "[VINE] config reloaded from " + configFile + " (" + values.size() + " keys)");
    }

    @Override
    public void onReload(Runnable listener) {
        reloadListeners.add(listener);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (values) {
            if (loaded) {
                return;
            }
            read();
            loaded = true;
        }
    }

    /** Minimal TOML subset: [section], key = value, # comments, string quotes. */
    private void read() {
        if (!Files.exists(configFile)) {
            return; // defaults only
        }
        try {
            String section = "";
            for (String line : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim();
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    LOG.log(System.Logger.Level.WARNING,
                        "[VINE] config: skipping malformed line '" + line + "'");
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.startsWith("\"")) {
                    // Quoted string: take through the closing quote; a trailing
                    // comment after it is ignored, '#' inside the quotes is data.
                    int close = value.indexOf('"', 1);
                    if (close > 0) {
                        value = value.substring(1, close);
                    }
                } else {
                    int comment = value.indexOf('#');
                    if (comment >= 0) {
                        value = value.substring(0, comment).trim();
                    }
                }
                values.put(section.isEmpty() ? key : section + "." + key, value);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                "[VINE] config read failed (" + e + ") — defaults only");
        }
    }
}
