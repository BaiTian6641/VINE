package dev.vineengine.vine.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A validated dotted path expression into a voxel data tree (sub-06 Stage C) —
 * what {@link VineArgumentTypes#VOXEL_PATH} parses to ({@code shared.hunt.phase}).
 *
 * <p>Validation is deliberately strict and local: segments are non-empty and
 * limited to lowercase letters, digits, {@code _} and {@code -}. Whether the
 * path resolves in a given tree is a data question (sub-03/sub-14), answered at
 * use time — a malformed *expression*, by contrast, is a typo the engine can
 * reject immediately, so it never reaches consumer code.
 */
public record VoxelPath(List<String> segments) {

    public VoxelPath {
        Objects.requireNonNull(segments, "segments");
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("voxel path needs at least one segment");
        }
    }

    /** Parses {@code a.b.c}; throws with the offending segment when malformed. */
    public static VoxelPath parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        List<String> segments = new ArrayList<>();
        for (String segment : raw.split("\\.", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("voxel path '" + raw + "' has an empty segment");
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
                if (!ok) {
                    throw new IllegalArgumentException("voxel path '" + raw + "' has an invalid segment '"
                        + segment + "' (lowercase letters, digits, _ and - only)");
                }
            }
            segments.add(segment);
        }
        return new VoxelPath(segments);
    }

    @Override
    public String toString() {
        return String.join(".", segments);
    }
}
