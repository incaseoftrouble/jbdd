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
import java.util.List;

/**
 * The order the variables of one or more decision diagrams are laid out in, and the only thing that may
 * move them.
 *
 * <p>A variable's <em>level</em> is its position in that order, which is the identity until something
 * reorders. Variables keep their numbers - only {@link #levelOfVariable(int)} moves - and reordering
 * rewrites nodes in place, so no function id is ever invalidated: a handle keeps denoting the same
 * function, only the diagram representing it changes shape.
 *
 * <p>Every diagram sharing this order moves with it, which is what makes it a thing of its own rather
 * than a diagram's method: two diagrams share a variable universe exactly when they hand out the same
 * {@code DdVariableOrder}.
 *
 * <p>Reordering is explicit and must happen while no operation is in flight. It is not triggered
 * automatically by table growth, and none of these methods may be called from inside a callback of
 * another operation.
 */
public interface DdVariableOrder {
    /** How many variables the order covers - levels run from {@code 0} to this, exclusive. */
    int numberOfVariables();

    /** The position of {@code variable} in the current order. */
    int levelOfVariable(int variable);

    /** The variable at position {@code level} - the inverse of {@link #levelOfVariable(int)}. */
    int variableAtLevel(int level);

    /**
     * Moves the variable at {@code level} one position deeper, exchanging it with the one at
     * {@code level + 1} and rewriting exactly the nodes that have to change.
     *
     * <p>Every function keeps its id and its meaning; only the shape of the diagrams changes.
     */
    void siftDown(int level);

    /**
     * Reorders variables, aiming to reduce the number of nodes.
     *
     * @return How many nodes fewer the diagrams hold afterwards, counted across every diagram over this
     *     order. Never negative - a variable is only moved somewhere that was at least as good as where
     *     it started.
     */
    int reorder();

    /**
     * As {@link #reorder()}, but no variable leaves its group: variables within a group may be permuted
     * freely, none crosses into another.
     *
     * <p>The groups must be disjoint and each must <em>already</em> occupy a contiguous run of levels;
     * this reorders within blocks, it does not form them. One group holding every variable is exactly
     * {@link #reorder()}.
     *
     * @return How many nodes fewer the diagrams hold afterwards.
     *
     * @see #reorder()
     */
    int reorder(List<BitSet> groups);

    /**
     * Rearranges the order into the requested shape: block {@code i} ends up entirely above block
     * {@code i + 1}, and each block occupies a contiguous run of levels.
     *
     * <p>The blocks must be disjoint, but need not cover every variable. A variable in no block is a
     * don't-care and is left alone: it may end up above, between or below the blocks, wherever it takes
     * least moving to put them where they were asked to go. The order <em>within</em> a block is
     * unspecified - a block is a set.
     *
     * <p>Unlike {@link #reorder()} this does not aim at a smaller diagram and may well produce a bigger
     * one; it does exactly what it is told, and only that. Afterwards {@code reorder(blocks)} is a legal
     * call, and is the natural follow-up: this fixes the block structure, that then optimises inside it.
     *
     * @see #reorder(List)
     */
    void reorderTo(List<BitSet> blocks);

    /**
     * Puts every variable back at the level of its own number, undoing whatever reordering happened.
     *
     * <p>The same thing as {@link #reorderTo} with every variable in a singleton block, in order.
     */
    void reorderToIdentity();

    /**
     * Releases bookkeeping structures potentially allocated for reordering needs.
     * Should be called once a workload has settled on an order, as maintaining the
     * bookkeeping is not free.
     */
    void dropReorderStructures();
}
