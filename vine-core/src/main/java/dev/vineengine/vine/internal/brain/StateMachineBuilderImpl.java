package dev.vineengine.vine.internal.brain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.vineengine.vine.brain.Action;
import dev.vineengine.vine.brain.BlackboardKey;
import dev.vineengine.vine.brain.Condition;
import dev.vineengine.vine.brain.Node;
import dev.vineengine.vine.brain.NodeStatus;
import dev.vineengine.vine.brain.StateMachineBuilder;

/**
 * The state-machine sugar of sub-08 Stage B, lowered to an ordinary {@link Action}.
 *
 * <p>It is an action rather than a node kind of its own because the engine's node
 * vocabulary is deliberately sealed: a machine is <em>composition</em> — a table of
 * states, transitions and a current-state cell — and composition is what the
 * permitted nodes already express. Keeping it out of the sealed set also keeps the
 * vocabulary honest: there is no "state machine node" a cell could special-case.
 *
 * <p>Semantics, fixed so golden traces can rely on them: the current state lives in
 * the blackboard under {@link #STATE}; on each tick the transitions declared
 * <em>from</em> the current state are tested in declaration order, the first whose
 * condition holds moves the machine, and the same tick then runs the new state's
 * node. A state declared with no node succeeds. A persisted state name the tree does
 * not declare (a reload from an older tree) restarts at the initial state instead of
 * running something that does not exist.
 */
public final class StateMachineBuilderImpl implements StateMachineBuilder {

    /** The reserved blackboard path holding the machine's current state. */
    public static final BlackboardKey<String> STATE = BlackboardKey.stringKey("brain.fsm.state");

    private final String name;
    private String initial;
    private final Map<String, Node> states = new LinkedHashMap<>();
    private final Map<String, List<Transition>> transitions = new LinkedHashMap<>();

    /** A builder producing an action named {@code fsm(<initial>)}. */
    public StateMachineBuilderImpl(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public StateMachineBuilder initial(String state) {
        this.initial = state;
        return this;
    }

    @Override
    public StateMachineBuilder state(String name, Node node) {
        Objects.requireNonNull(name, "name");
        if (states.containsKey(name)) {
            throw new IllegalStateException("state '" + name + "' is declared twice — one state, one behaviour");
        }
        states.put(name, node);
        return this;
    }

    @Override
    public StateMachineBuilder transition(String from, String to, Condition when) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(when, "when");
        transitions.computeIfAbsent(Objects.requireNonNull(from, "from"), key -> new ArrayList<>())
            .add(new Transition(to, when));
        return this;
    }

    @Override
    public Node build() {
        if (initial == null) {
            throw new IllegalStateException("state machine has no initial state");
        }
        if (!states.containsKey(initial)) {
            throw new IllegalStateException("initial state '" + initial + "' is not declared");
        }
        for (Map.Entry<String, List<Transition>> entry : transitions.entrySet()) {
            if (!states.containsKey(entry.getKey())) {
                throw new IllegalStateException("transition declared from undeclared state '" + entry.getKey() + "'");
            }
            for (Transition transition : entry.getValue()) {
                if (!states.containsKey(transition.to())) {
                    throw new IllegalStateException("transition from '" + entry.getKey()
                        + "' targets undeclared state '" + transition.to() + "'");
                }
            }
        }
        Map<String, Node> frozenStates = Map.copyOf(states);
        Map<String, List<Transition>> frozenTransitions = Map.copyOf(transitions);
        String start = initial;
        return Action.of("fsm(" + name + ")", ctx -> {
            String current = ctx.blackboard().get(STATE, start);
            if (!frozenStates.containsKey(current)) {
                current = start;
            }
            for (Transition transition : frozenTransitions.getOrDefault(current, List.of())) {
                if (transition.when().test(ctx)) {
                    current = transition.to();
                    break;
                }
            }
            ctx.blackboard().set(STATE, current);
            Node node = frozenStates.get(current);
            return node == null ? NodeStatus.SUCCESS : node.tick(ctx);
        });
    }

    private record Transition(String to, Condition when) {
    }
}
