package dev.vineengine.vine;

/**
 * An open capability id a runtime cell may or may not have (sub-01 Stage D,
 * §5.12): each subsystem declares its own constants (e.g.
 * {@code Feature.of("hasDataComponents")}); consumers ask
 * {@link VineEngine#supports(Feature)}. Features are decided by the driver's
 * runtime probes — class/method presence and data-version windows, never parsed
 * version strings — and are identified by {@link #id()} alone (instances with
 * equal ids are the same feature).
 */
public interface Feature {

    /** The feature's stable id (lowerCamelCase by convention). */
    String id();

    /** A feature with the given id; no registration needed — ids are open. */
    static Feature of(String id) {
        java.util.Objects.requireNonNull(id, "id");
        return () -> id;
    }
}
