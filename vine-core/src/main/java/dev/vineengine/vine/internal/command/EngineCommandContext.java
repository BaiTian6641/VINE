package dev.vineengine.vine.internal.command;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.command.CommandSourceRef;
import dev.vineengine.vine.command.VineCommandContext;

/**
 * The engine-side {@link VineCommandContext}: adapts a driver's
 * {@link CommandBridge.NativeSource} plus the parsed argument values into the
 * consumer-facing context. Argument lookup failures are explicit — an
 * undeclared name or a wrong {@code Class} is an authoring bug, never a silent
 * {@code null}.
 */
final class EngineCommandContext implements VineCommandContext {

    private final CommandBridge.NativeSource source;
    private final Map<String, ?> arguments;
    private final CommandSourceRef sourceRef;

    EngineCommandContext(CommandBridge.NativeSource source, Map<String, ?> arguments) {
        this.source = source;
        this.arguments = arguments;
        this.sourceRef = new EngineSourceRef(source);
    }

    @Override
    public CommandSourceRef source() {
        return sourceRef;
    }

    @Override
    public <T> T argument(String name, Class<T> type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Object value = arguments.get(name);
        if (value == null) {
            throw new IllegalArgumentException("unknown command argument '" + name
                + "' — declared on this path: " + arguments.keySet());
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("command argument '" + name + "' delivered as "
                + value.getClass().getName() + ", requested as " + type.getName());
        }
        return type.cast(value);
    }

    @Override
    public void feedback(String message) {
        source.sendFeedback(Objects.requireNonNull(message, "message"));
    }

    @Override
    public void error(String message) {
        source.sendError(Objects.requireNonNull(message, "message"));
    }

    /** Source facade over the driver's native adapter. */
    private static final class EngineSourceRef implements CommandSourceRef {

        private final CommandBridge.NativeSource source;

        private EngineSourceRef(CommandBridge.NativeSource source) {
            this.source = source;
        }

        @Override
        public String name() {
            return source.name();
        }

        @Override
        public boolean isPlayer() {
            return source.isPlayer();
        }

        @Override
        public Optional<VinePlayer> player() {
            if (!source.isPlayer()) {
                return Optional.empty();
            }
            return Optional.of(new CommandPlayerRef(
                Objects.requireNonNull(source.playerUniqueId(), "player source without a UUID"),
                source.name()));
        }
    }

    /** Minimal {@link VinePlayer} view of the executing player — identity only. */
    private record CommandPlayerRef(UUID uniqueId, String name) implements VinePlayer {
    }
}
