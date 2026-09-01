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

/**
 * A {@link Bdd} and the {@link MtBdd} over it, sharing one variable order.
 *
 * <p>The order is the context's, not either diagram's: reordering through {@link Bdd#reorder()} and
 * through {@link MtBdd#reorder()} is the same act, and moves the nodes of both. Everything else - the
 * node tables, the caches, reference counting - is per diagram.
 */
public interface BddContext {
    static BddContext create() {
        return create(ImmutableBddConfiguration.builder().build());
    }

    static BddContext create(BddConfiguration configuration) {
        return new BddContextImpl(configuration);
    }

    /** Like {@link #create()}, with {@code variables} many variables already declared. */
    static BddContext create(BddConfiguration configuration, int variables) {
        return new BddContextImpl(configuration, variables);
    }

    /** The unique {@link Bdd}. */
    Bdd bdd();

    /** The unique {@link MtBdd}, over the same variables and the same order. */
    MtBdd mtBdd();

    /**
     * Moves the variable at {@code level} one position deeper, exchanging it with the one at
     * {@code level + 1} and rewriting exactly the nodes that have to change - in both diagrams, which is
     * why it lives here and not on either of them.
     *
     * <p>The primitive every reordering policy is built from: {@link Bdd#reorder()} and
     * {@link Bdd#reorderTo(java.util.List)} are sequences of these. It is deliberately not on the
     * diagrams - those say <em>what</em> order is wanted, this says <em>how</em> to move, and a caller
     * only needs it to drive a policy of its own.
     *
     * <p>Every function keeps its id and its meaning; only the shape of the diagrams changes. Like every
     * reordering it must run while no operation is in flight, and never from inside a callback.
     */
    void siftDown(int level);
}
