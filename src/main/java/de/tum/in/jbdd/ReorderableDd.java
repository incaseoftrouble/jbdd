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
 * A diagram whose variable order can possibly be changed.
 *
 * <p>A variable's <em>level</em> is its position in the order, which is the identity until something
 * reorders. Reordering rewrites nodes in place, so it never invalidates a function id: a handle keeps
 * denoting the same function, only the diagram representing it changes shape. Variables keep their
 * numbers too - only {@link #level(int)} moves - so nothing a caller holds has to be translated.
 *
 * <p>Reordering is explicit and must happen while no operation is in flight. It is not triggered
 * automatically by table growth, and none of these methods may be called from inside a callback of
 * another operation.
 */
public interface ReorderableDd extends NodeBasedDd {
    /** The position of {@code variable} in the current order. */
    int level(int variable);

    /** The variable at position {@code level} - the inverse of {@link #level(int)}. */
    int variableAtLevel(int level);

    /**
     * Reorders variables, aiming to reduce the number of nodes.
     *
     * @return How many nodes fewer the diagram holds afterwards. Never negative - a variable is only moved
     *     somewhere that was at least as good as where it started.
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
     * @return How many nodes fewer the diagram holds afterwards.
     *
     * @see #reorder()
     */
    int reorder(List<BitSet> groups);

    /**
     * Rearranges the order into the requested shape: block {@code i} ends up entirely above block
     * {@code i + 1}, and each block occupies a contiguous run of levels. Says what the order should be,
     * not how to get there.
     *
     * <p>The blocks must be disjoint, but need not cover every variable. A variable in no block is a
     * don't-care and is left alone: it may end up above, between or below the blocks, wherever it takes
     * least moving to put them where they were asked to go. The order <em>within</em> a block is
     * unspecified - a block is a set - and in practice its members keep the relative order they had. To
     * constrain the rest, list it as a block of its own; the blocks are the whole specification, and an
     * order already satisfying them is left exactly as it is.
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
     * <p>The same thing as {@code reorderTo} with every variable in a singleton block, in order, but
     * said in one word and done without building the list. Like {@link #reorderTo(List)} it does not aim
     * at a smaller diagram and will usually produce a bigger one.
     *
     * <p>Worth knowing: an identity order is the cheap case. The mapping between variables and levels is
     * then not stored at all and enumeration writes its results directly rather than translating them,
     * so a workload that reorders, finishes with the natural order and then runs is faster for having
     * said so than for having arrived there by accident.
     */
    void reorderToIdentity();

    /**
     * Creates a variable and places it at {@code level}, pushing whatever sits there and below down one.
     *
     * <p>Generally cheap, but makes the {@code level <-> variable} mapping non-identity, which does
     * create some overhead.</p>
     *
     * @return The function representing the new variable.
     */
    int createVariableAtLevel(int level);

    /**
     * Creates {@code count} variables occupying levels {@code level} to {@code level + count - 1},
     * pushing whatever sat there and below down by {@code count}.
     *
     * <p>The same order {@code createVariableAtLevel} would produce called {@code count} times at
     * {@code level}, {@code level + 1}, ..., but everything below the insertion point moves once instead
     * of once per variable.
     *
     * @return The functions representing the new variables, top to bottom.
     */
    int[] createVariablesAtLevel(int level, int count);

    /**
     * Releases bookkeeping structures potentially allocated for reordering needs.
     * Should be called once a workload has settled on an order, as maintaining the
     * bookkeeping is not free.
     */
    void dropReorderStructures();
}
