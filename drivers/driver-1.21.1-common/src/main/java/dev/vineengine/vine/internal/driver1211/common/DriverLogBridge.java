package dev.vineengine.vine.internal.driver1211.common;

import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges vine-core's dependency-free {@code System.Logger} output onto the
 * loader's Log4J setup via SLF4J. vine-core logs through {@code System.Logger}
 * (whose default backend is JUL); a {@code System.LoggerFinder} service inside a
 * mod jar is never resolved under loader classloaders, so the driver attaches a
 * forwarding JUL handler to each named vine-core logger instead — one mechanism
 * carrying phase lines, networking, and command diagnostics into the server log
 * exactly once ({@code useParentHandlers(false)} suppresses the raw JUL console
 * fallback).
 *
 * <p>Names are enumerated, not wildcarded (JUL has no prefix handlers): a new
 * {@code vine.*} logger in vine-core must be added here — its lines otherwise
 * vanish from the loader log. Current owners: {@code PhaseMachine}/
 * {@code VineEngineImpl} ({@code vine.boot}), {@code VineNetImpl}/
 * {@code ChannelImpl} ({@code vine.net}), {@code CommandBridge}/
 * {@code CommandService} ({@code vine.commands}).
 */
public final class DriverLogBridge {

    private static final String[] CORE_LOGGERS = { "vine.boot", "vine.net", "vine.commands" };

    private static boolean installed;

    private DriverLogBridge() {
    }

    /** Idempotent; must run before the engine boots (first {@code VineEngine.get()}). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        for (String name : CORE_LOGGERS) {
            java.util.logging.Logger jul = java.util.logging.Logger.getLogger(name);
            jul.setUseParentHandlers(false);
            jul.setLevel(Level.ALL);
            jul.addHandler(new Slf4jHandler(LoggerFactory.getLogger(name)));
        }
    }

    private static final class Slf4jHandler extends java.util.logging.Handler {

        private final Logger target;

        private Slf4jHandler(Logger target) {
            this.target = target;
        }

        @Override
        public void publish(LogRecord record) {
            String message = record.getMessage();
            Object[] parameters = record.getParameters();
            if (message != null && parameters != null && parameters.length > 0) {
                message = MessageFormat.format(message, parameters);
            }
            Throwable thrown = record.getThrown();
            int level = record.getLevel().intValue();
            if (level >= Level.SEVERE.intValue()) {
                target.error(message, thrown);
            } else if (level >= Level.WARNING.intValue()) {
                target.warn(message, thrown);
            } else if (level >= Level.INFO.intValue()) {
                target.info(message, thrown);
            } else {
                target.debug(message, thrown);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
