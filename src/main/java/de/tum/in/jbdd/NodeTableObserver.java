/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2026 Tobias Meggendorfer.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * JBDD is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD. If not, see <http://www.gnu.org/licenses/>.
 */
package de.tum.in.jbdd;

import java.util.BitSet;

interface NodeTableObserver {
    default void beforeGc(DecisionDiagram origin) {
        // Default: nothing to release ahead of time.
    }

    default void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
        // Default: nothing depends on which nodes/values were reclaimed.
    }

    /**
     * Two adjacent levels exchanged their variables, and nothing else moved. Node ids and their meanings
     * survive, so anything stated purely about them still holds; what does not is anything that folded a
     * level comparison into a value, and anything holding a level.
     */
    default void levelsSwapped(DecisionDiagram origin, int level) {
        // Nothing by default
    }

    /**
     * A variable was created at {@code level}, pushing everything from there down one deeper. Every level
     * <em>comparison</em> survives this - both sides shift by the same rule - so only a stored level has
     * to move, by one if it was at or below {@code level}.
     */
    default void variableInserted(DecisionDiagram origin, int level) {
        // Nothing by default
    }

    default void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
        // Default: nothing depends on the table's size.
    }
}
