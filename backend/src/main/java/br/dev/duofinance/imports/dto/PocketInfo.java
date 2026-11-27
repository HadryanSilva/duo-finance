package br.dev.duofinance.imports.dto;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What the planner knows about a pocket move: the pocket's name if the wording is known, and the matching goal. */
public record PocketInfo(@Nullable String name, Direction direction, @Nullable UUID goalId, Match match) {

    /** INTO is money into the pocket (positive savings leg, negative Current leg); OUT_OF the reverse. */
    public enum Direction {
        INTO,
        OUT_OF
    }

    public enum Match {
        LINKED,
        NAME_MATCH,
        NONE
    }
}
