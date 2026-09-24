package dev.vineengine.vine.internal;

/**
 * The engine's console-dispatch seam (sub-21 Stage B). Drivers install the cell's
 * "run this command line as the console" path at server start; anything that has
 * to execute a command without being on the command thread — the in-process TCK
 * harness, automation — goes through it.
 *
 * <p>It exists because the alternative is worse: a consumer (or a test harness)
 * cannot reach a command dispatcher through the public API, and inventing a
 * second execution path would make scenario output differ between harnesses.
 * Unset means "no dispatcher installed": callers fail loudly, never silently.
 */
public final class ConsoleDispatch {

    private static volatile Installer installer;

    private ConsoleDispatch() {
    }

    /** What a cell installs: dispatch one command line on the server thread. */
    public interface Installer {
        void dispatch(String command);
    }

    /** Installs the cell's dispatcher ({@code null} clears it — cell teardown, tests). */
    public static void install(Installer dispatch) {
        installer = dispatch;
    }

    /** Dispatches {@code command} as the console, or throws when no cell bound one. */
    public static void dispatch(String command) {
        Installer current = installer;
        if (current == null) {
            throw new IllegalStateException("no console dispatch installed (driver did not bind one)");
        }
        current.dispatch(command);
    }
}
