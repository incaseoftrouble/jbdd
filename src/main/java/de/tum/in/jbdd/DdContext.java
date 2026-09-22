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

import java.util.Map;

/**
 * A {@link Bdd} and the {@link MtBdd} over it.
 */
public interface DdContext {
    static DdContext create() {
        return create(ImmutableBddConfiguration.builder().build());
    }

    static DdContext create(BddConfiguration configuration) {
        return new DdContextImpl(configuration);
    }

    /** Like {@link #create()}, with {@code variables} many variables already declared. */
    static DdContext create(BddConfiguration configuration, int variables) {
        return new DdContextImpl(configuration, variables);
    }

    /** The unique {@link Bdd}. */
    Bdd bdd();

    /** The unique {@link MtBdd}, over the same variables and the same order. */
    MtBdd mtBdd();

    /** The order both diagrams are laid out in, and the only thing that may move it. */
    DdVariableOrder variableOrder();

    /**
     * Creates a variable and places it at {@code level}, pushing whatever sits there and below down one.
     *
     * <p>Generally cheap, but makes the {@code level <-> variable} mapping non-identity, which does
     * create some overhead.</p>
     *
     * @return The {@link #bdd()} function representing the new variable.
     */
    int createVariableAtLevel(int level);

    /**
     * Creates {@code count} variables occupying levels {@code level} to {@code level + count - 1},
     * pushing whatever sat there and below down by {@code count}.
     *
     * <p>The same order {@link #createVariableAtLevel} would produce called {@code count} times at
     * {@code level}, {@code level + 1}, ..., but everything below the insertion point moves once instead
     * of once per variable.
     *
     * @return The {@link #bdd()} functions representing the new variables, top to bottom.
     */
    int[] createVariablesAtLevel(int level, int count);

    /**
     * A snapshot of the statistics of both diagrams, their tables and caches, and the order they share.
     * The content may change between versions; the values are primitives.
     */
    Map<String, Object> statistics();

    /**
     * Renders a statistics map - this one's, or an {@link Mdd}'s - as sorted {@code key=value} lines,
     * which is what one is read as.
     */
    static String formatStatistics(Map<String, Object> statistics) {
        return Util.formatStatistics(statistics);
    }
}
