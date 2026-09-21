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

import static de.tum.in.jbdd.BooleanBase.EMPTY_INT_ARRAY;
import static de.tum.in.jbdd.Preconditions.checkState;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The variable order both diagrams of a {@link DdContextImpl} are laid out in, and the sifting engine
 * that moves it. A swap has to rewrite both tables between the two halves of the order change, which is
 * why this owns the bijection rather than either diagram.
 */
@SuppressWarnings("AssertWithSideEffects")
public final class DdVariableOrderImpl implements DdVariableOrder {
    /* How much sifting is allowed to grow the node count at most */
    private static final double MAXIMUM_SIFT_GROWTH = 1.2;
    /* Unreachable fraction of a table a swap tolerates before collecting - see swapWithNextLevel. */
    private static final double MAXIMUM_SIFT_GARBAGE = 0.40;

    /* The diagrams are reached through the context rather than held here: this is built before they are,
     * and nothing on the hot path (levelOfVariable, variableAtLevel) touches them at all. */
    private final DdContextImpl context;
    /* The listeners are the order's, not the diagrams': a change moves every diagram at once, so each
     * one is told exactly once and none of them has to work out which diagram it is hearing about. */
    private final ObserverGroup<VariableOrderObserver> observers = new ObserverGroup<>();

    private int numberOfVariables = 0;
    /* The variable order, as a bijection between a variable and its position; empty for identity order */
    private int[] variableToLevel = EMPTY_INT_ARRAY;
    private int[] levelToVariable = EMPTY_INT_ARRAY;
    /* Whether the order is not identity. */
    private boolean explicitOrder = false;
    /* The order as it was when the reordering currently running started, and null while none is - the
     * swaps in between tell nobody, and this is what the single notification at the end reports against.
     * Deferring is sound because nothing reads an operation cache or a stored level while the order is
     * moving: a swap only rewrites nodes, and reordering may not run while an operation is in flight. */
    private int @Nullable [] reorderingFrom = null;

    // Statistics

    /* Reorderings run and nodes they removed in total - the number that says whether reordering is worth
     * what it costs, which the per-call return value alone does not show. */
    private int reorderCount = 0;
    private long reorderSavedNodes = 0;
    /* The cost side, against reorderSavedNodes as the benefit side. Swaps and the nodes they rewrote say
     * what the sifting itself cost; the collections say whether MAXIMUM_SIFT_GARBAGE is set sensibly, and
     * the abandoned directions whether MAXIMUM_SIFT_GROWTH is.
     *
     * The swap-level counters cover every swap, reorderTo's and a caller's own siftDown included - they
     * count swaps, not sifting runs. Only reorderCount, reorderSavedNodes and reorderAbandonedDirections
     * are the sifting policy's alone, so reorder_work_per_saved_node is a fair ratio only for a workload
     * that reorders and nothing else. */
    private long reorderSwaps = 0;
    private long reorderRewrittenNodes = 0;
    private int reorderCollections = 0;
    private int reorderAbandonedDirections = 0;
    private long reorderTimeMilliseconds = 0;
    /* How often a reordering landed back on the identity and the order went implicit again. Sifting has
     * no reason to prefer the identity, so this is expected to stay at zero on anything but a caller's
     * own reorderToIdentity - if it does not, the fast path is worth more than it looks. */
    private int reorderIdentityReverts = 0;
    /* Order changes the listeners were told about. Against reorder_swaps this is what batching the
     * notification is worth: one per reordering, however many swaps it took. */
    private int reorderNotifications = 0;

    DdVariableOrderImpl(DdContextImpl context) {
        this.context = context;
    }

    private BddImpl bdd() {
        return context.bdd();
    }

    private MtBddImpl mtbdd() {
        return context.mtBdd();
    }

    void registerObserver(VariableOrderObserver observer) {
        observers.register(observer);
    }

    /** Registers an observer owned by a diagram over this order, see {@link ObserverGroup}. */
    void registerOwnedObserver(VariableOrderObserver observer) {
        observers.registerStrongly(observer);
    }

    void notifyVariablesInserted(int level, int count) {
        observers.dispatch(observer -> observer.variablesInserted(level, count));
    }

    // The order itself

    @Override
    public int numberOfVariables() {
        return numberOfVariables;
    }

    boolean isExplicitOrder() {
        return explicitOrder;
    }

    @Override
    public int levelOfVariable(int variable) {
        assert 0 <= variable;
        return explicitOrder ? variableToLevel[variable] : variable;
    }

    @Override
    public int variableAtLevel(int level) {
        assert 0 <= level;
        return explicitOrder ? levelToVariable[level] : level;
    }

    private void ensureOrderCapacity(int variables) {
        assert explicitOrder;
        if (variables > variableToLevel.length) {
            int length = Math.max(Math.max(variableToLevel.length * 2, variables), 32);
            variableToLevel = Arrays.copyOf(variableToLevel, length);
            levelToVariable = Arrays.copyOf(levelToVariable, length);
        }
    }

    private void makeOrderExplicit() {
        if (explicitOrder) {
            return;
        }
        // Set before growing the arrays: they are only live while the flag is, which ensureOrderCapacity
        // asserts. Both callers move a variable within this critical section, so nothing observes the
        // flag while the order it promises is still the identity.
        explicitOrder = true;
        ensureOrderCapacity(numberOfVariables);
        for (int variable = 0; variable < numberOfVariables; variable++) {
            variableToLevel[variable] = variable;
            levelToVariable[variable] = variable;
        }
    }

    /**
     * Reverts to implicit identity if the current order is the identity.
     *
     * @return whether the current order is the identity (no matter if explicit or implicit).
     */
    private boolean makeImplicitIfIdentity() {
        if (!explicitOrder) {
            return true;
        }
        for (int level = 0; level < numberOfVariables; level++) {
            if (levelToVariable[level] != level) {
                return false;
            }
        }
        variableToLevel = EMPTY_INT_ARRAY;
        levelToVariable = EMPTY_INT_ARRAY;
        explicitOrder = false;
        reorderIdentityReverts += 1;
        return true;
    }

    /**
     * Records {@code count} new variables at the bottom, each at the level of its own number, and returns
     * the first of them. An implicit order stays implicit: a variable appended at the bottom is its own
     * level either way.
     */
    int appendVariables(int count) {
        int firstVariable = numberOfVariables;
        if (explicitOrder) {
            ensureOrderCapacity(firstVariable + count);
            for (int index = 0; index < count; index++) {
                variableToLevel[firstVariable + index] = firstVariable + index;
                levelToVariable[firstVariable + index] = firstVariable + index;
            }
        }
        numberOfVariables = firstVariable + count;
        return firstVariable;
    }

    /**
     * Records {@code count} new variables occupying levels {@code level} to {@code level + count - 1},
     * pushing whatever sat there and below down by {@code count}, and returns the first of them.
     *
     * <p>Everything below the insertion point moves once rather than once per variable, which is the
     * whole point of the block form. Walked from the bottom up so a slot is read before anything is
     * written over it.
     */
    int insertVariables(int level, int count) {
        int firstVariable = numberOfVariables;
        // Appending at the bottom leaves an implicit order implicit.
        if (level != firstVariable) {
            makeOrderExplicit();
        }
        if (explicitOrder) {
            ensureOrderCapacity(firstVariable + count);

            for (int current = firstVariable - 1; current >= level; current--) {
                int moved = levelToVariable[current];
                levelToVariable[current + count] = moved;
                variableToLevel[moved] = current + count;
            }
            for (int index = 0; index < count; index++) {
                levelToVariable[level + index] = firstVariable + index;
                variableToLevel[firstVariable + index] = level + index;
            }
        }
        numberOfVariables = firstVariable + count;
        return firstVariable;
    }

    // Telling the listeners

    /**
     * Opens a reordering: from here until {@link #endReordering()} the swaps are silent. Every public
     * entry point that moves a variable brackets itself with the pair, {@link #siftDown} included - a
     * caller driving swaps itself gets one notification per call, which is what it would have had.
     */
    private void beginReordering() {
        assert reorderingFrom == null : "A reordering is already in progress";
        reorderingFrom = currentVariableToLevel();
    }

    private void endReordering() {
        int[] previous = reorderingFrom;
        assert previous != null : "No reordering in progress";
        reorderingFrom = null;

        int[] current = currentVariableToLevel();
        BitSet movedVariables = new BitSet(numberOfVariables);
        for (int variable = 0; variable < numberOfVariables; variable++) {
            if (previous[variable] != current[variable]) {
                movedVariables.set(variable);
            }
        }
        if (movedVariables.isEmpty()) {
            return;
        }
        reorderNotifications += 1;

        assert bdd().table().workStacksEmpty() && mtbdd().table().workStacksEmpty();
        int[] previousCopy = previous.clone();
        int[] currentCopy = current.clone();
        observers.dispatch(observer -> observer.orderChanged(previous, current, movedVariables));
        assert Arrays.equals(previous, previousCopy)
                        && Arrays.equals(current, currentCopy)
                        && movedVariables.stream().allMatch(variable -> previous[variable] != current[variable])
                : "A listener modified what it was told about the order";
    }

    /** The order as a plain array, materialised even while it is the implicit identity. */
    private int[] currentVariableToLevel() {
        int[] order = new int[numberOfVariables];
        if (explicitOrder) {
            System.arraycopy(variableToLevel, 0, order, 0, numberOfVariables);
        } else {
            Arrays.setAll(order, variable -> variable);
        }
        return order;
    }

    // Reordering

    @Override
    public void dropReorderStructures() {
        bdd().table().dropReorderingBookkeeping();
        mtbdd().table().dropReorderingBookkeeping();
    }

    @Override
    public void siftDown(int level) {
        beginReordering();
        swapWithNextLevel(level);
        endReordering();
    }

    /* The primitive: one adjacent swap, rewriting exactly the nodes that must change. Silent by design -
     * see beginReordering; a sifting pass makes thousands of these and one notification covers them. */
    private void swapWithNextLevel(int level) {
        assert 0 <= level && level + 1 < numberOfVariables;
        BddImpl bdd = bdd();
        MtBddImpl mtbdd = mtbdd();
        assert bdd.accessGuard.acquire();
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();

        reorderSwaps += 1;
        makeOrderExplicit();

        bdd.table().enableReorderingBookkeeping();
        mtbdd.table().enableReorderingBookkeeping();
        if (bdd.table().deadNodeFraction() > MAXIMUM_SIFT_GARBAGE
                || mtbdd.table().deadNodeFraction() > MAXIMUM_SIFT_GARBAGE) {
            bdd.gc();
            mtbdd.gc();
            reorderCollections += 1;
        }

        int upper = variableAtLevel(level + 1);
        int lower = variableAtLevel(level);

        bdd.table().beginRewrite();
        mtbdd.table().beginRewrite();

        // MTBDD and BDD ordering must stay the same for the shared operations to make sense
        // The buffers handed back may be longer than their contents, so take the counts with them.
        int bddCount = bdd.table().nodesWithVariable(lower);
        int mtbddCount = mtbdd.table().nodesWithVariable(lower);
        int[] bddNodes = bdd.table().detachNodesWithVariable(lower);
        int[] mtbddNodes = mtbdd.table().detachNodesWithVariable(lower);

        levelToVariable[level] = upper;
        levelToVariable[level + 1] = lower;
        variableToLevel[upper] = level;
        variableToLevel[lower] = level + 1;

        bdd.rewriteLevelAfterSwap(bddNodes, bddCount, level, upper);
        mtbdd.rewriteLevelAfterSwap(mtbddNodes, mtbddCount, level, upper);
        reorderRewrittenNodes += (long) bddCount + mtbddCount;

        bdd.table().endRewrite();
        mtbdd.table().endRewrite();

        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();
        assert bdd.accessGuard.release();
    }

    /* Not Arrays.equals against levelToVariable: that array is capacity-sized while the order is
     * explicit and empty while it is implicit, so it never compares equal to a target of exactly
     * numberOfVariables entries - the short-circuit this guards would never fire. */
    private boolean isCurrentOrder(int[] target) {
        for (int level = 0; level < target.length; level++) {
            if (target[level] != variableAtLevel(level)) {
                return false;
            }
        }
        return true;
    }

    private void permuteTo(int[] target) {
        for (int level = 0; level < target.length; level++) {
            int current = levelOfVariable(target[level]);
            assert current >= level : "Level " + level + " was already settled";
            while (current > level) {
                swapWithNextLevel(current - 1);
                current -= 1;
            }
        }
    }

    /**
     * The variables in the order {@code blocks} asks for. Each block is anchored where its members
     * already are and the don't-cares are left in the order they are in, so this edits the current order
     * rather than replacing it: an order that already satisfies the request comes back unchanged.
     *
     * <p>Blocks must not overlap, but the given list can be a subset of all variables.</p>
     */
    @Override
    public void reorderTo(List<BitSet> blocks) {
        if (numberOfVariables < 2) {
            return;
        }
        assert bdd().accessGuard.acquire();
        assert bdd().table().workStacksEmpty() && mtbdd().table().workStacksEmpty();
        beginReordering();

        BitSet listed = new BitSet(numberOfVariables);
        long[] keyed = new long[numberOfVariables];
        int index = 0;
        int nextFreeStart = 0;

        // Create a heuristical order of the blocks
        for (BitSet block : blocks) {
            checkState(!block.intersects(listed), "Reordering blocks overlap");
            int size = block.cardinality();
            if (size == 0) {
                continue;
            }
            int[] levels = new int[size];
            int at = 0;
            for (int variable = block.nextSetBit(0); variable >= 0; variable = block.nextSetBit(variable + 1)) {
                checkState(variable < numberOfVariables, "Unknown variable %s in a reordering block", variable);
                levels[at] = levelOfVariable(variable);
                at += 1;
            }
            listed.or(block);
            Arrays.sort(levels);

            // Where should the first element of this block go?
            // Look at the median of the current positions (adjusted by size), this would be the least sifts
            // However, if that position is already allocated "away", move it to the closest position
            int start = Math.max(nextFreeStart, levels[size / 2] - size / 2);
            nextFreeStart = start + size;

            // Sort blocks by their start position first and then by their current level
            for (int memberLevel : levels) {
                keyed[index] = ((start * 2L) << Integer.SIZE) | memberLevel;
                index += 1;
            }
        }
        // All the other levels are sorted in between
        for (int level = 0; level < numberOfVariables; level++) {
            if (!listed.get(variableAtLevel(level))) {
                keyed[index] = ((2L * level + 1) << Integer.SIZE) | level;
                index += 1;
            }
        }
        assert index == numberOfVariables;

        Arrays.sort(keyed);
        int[] target = new int[numberOfVariables];
        for (int level = 0; level < numberOfVariables; level++) {
            // The low half is the level the variable sits at now, which names it - nothing has moved yet.
            target[level] = variableAtLevel((int) keyed[level]);
        }

        if (!isCurrentOrder(target)) {
            long startTimestamp = System.currentTimeMillis();
            permuteTo(target);
            reorderTimeMilliseconds += System.currentTimeMillis() - startTimestamp;
            makeImplicitIfIdentity();
        }

        endReordering();
        assert groupsAreDisjointContiguousBlocks(blocks);
        assert bdd().check();
        assert bdd().accessGuard.release();
    }

    @Override
    public void reorderToIdentity() {
        if (!explicitOrder) {
            return;
        }
        assert bdd().accessGuard.acquire();
        assert bdd().table().workStacksEmpty() && mtbdd().table().workStacksEmpty();
        beginReordering();

        int[] target = new int[numberOfVariables];
        Arrays.setAll(target, i -> i);
        long startTimestamp = System.currentTimeMillis();
        permuteTo(target);
        reorderTimeMilliseconds += System.currentTimeMillis() - startTimestamp;

        boolean implicit = makeImplicitIfIdentity();
        assert implicit;

        endReordering();
        assert bdd().check();
        assert bdd().accessGuard.release();
    }

    @Override
    public int reorder() {
        if (numberOfVariables < 2) {
            return 0;
        }
        return reorder(List.of(BitSets.range(0, numberOfVariables)));
    }

    /**
     * Applies reordering within the given groups. This means all variables in the first group will be
     * below those in the second group etc. and only reordered within their given group. The groups
     * must be disjoint and contiguous; unlisted variables are not considered at all.
     */
    @Override
    public int reorder(List<BitSet> groups) {
        if (numberOfVariables < 2) {
            return 0;
        }
        BddImpl bdd = bdd();
        MtBddImpl mtbdd = mtbdd();
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();
        assert groupsAreDisjointContiguousBlocks(groups);
        beginReordering();

        bdd.gc();
        mtbdd.gc();
        bdd.table().enableReorderingBookkeeping();
        mtbdd.table().enableReorderingBookkeeping();
        int nodesBefore = liveNodeCount();
        long startTimestamp = System.currentTimeMillis();

        int[] weight = new int[numberOfVariables];
        for (int variable = 0; variable < numberOfVariables; variable++) {
            weight[variable] =
                    bdd.table().nodesWithVariable(variable) + mtbdd.table().nodesWithVariable(variable);
        }

        for (BitSet group : groups) {
            if (group.cardinality() < 2) {
                continue;
            }
            int minLevel = Integer.MAX_VALUE;
            int maxLevel = -1;
            for (int variable = group.nextSetBit(0); variable >= 0; variable = group.nextSetBit(variable + 1)) {
                minLevel = Math.min(minLevel, levelOfVariable(variable));
                maxLevel = Math.max(maxLevel, levelOfVariable(variable));
            }

            // Sort variables by their weight -- start with those that have the most nodes, as
            // these have most to gain
            long[] order = new long[group.cardinality()];
            BitSets.forEachWithIndex(
                    group, (variable, index) -> order[index] = ((long) weight[variable] << Integer.SIZE) | variable);
            Arrays.sort(order);

            for (int position = order.length - 1; position >= 0; position--) {
                sift((int) order[position], minLevel, maxLevel);
            }
        }

        int saved = nodesBefore - liveNodeCount();
        reorderTimeMilliseconds += System.currentTimeMillis() - startTimestamp;
        assert saved >= 0 : "Sifting left the diagram bigger than it found it";
        reorderCount += 1;
        reorderSavedNodes += saved;
        makeImplicitIfIdentity();

        endReordering();
        if (!context.configuration().keepReorderingStructures()) {
            dropReorderStructures();
        }
        assert bdd.check();
        return saved;
    }

    private boolean groupsAreDisjointContiguousBlocks(List<BitSet> groups) {
        BitSet seen = new BitSet(numberOfVariables);
        for (BitSet group : groups) {
            checkState(!group.intersects(seen), "Reordering groups overlap");
            seen.or(group);

            int minLevel = Integer.MAX_VALUE;
            int maxLevel = -1;
            for (int variable = group.nextSetBit(0); variable >= 0; variable = group.nextSetBit(variable + 1)) {
                checkState(variable < numberOfVariables, "Unknown variable %s in a reordering group", variable);
                minLevel = Math.min(minLevel, levelOfVariable(variable));
                maxLevel = Math.max(maxLevel, levelOfVariable(variable));
            }
            checkState(
                    group.isEmpty() || maxLevel - minLevel + 1 == group.cardinality(),
                    "Reordering group %s does not occupy a contiguous run of levels",
                    group);
        }
        return true;
    }

    private int liveNodeCount() {
        return bdd().table().liveNodeCountFromBookkeeping() + mtbdd().table().liveNodeCountFromBookkeeping();
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    private void sift(int variable, int minLevel, int maxLevel) {
        int start = levelOfVariable(variable);
        assert minLevel <= start && start <= maxLevel;
        int best = start;
        int bestSize = liveNodeCount();
        // Stop exploring a direction once it has cost more than this - the usual bound, without which
        // every variable pays for a full sweep of a range that was never going to win.
        // TODO It may pay off to instead use "current best * factor" as high mark, but needs benchmarking
        int limit = (int) Math.min(Integer.MAX_VALUE, (long) (bestSize * MAXIMUM_SIFT_GROWTH));

        int current = start;
        while (current < maxLevel) {
            swapWithNextLevel(current);
            current += 1;
            int size = liveNodeCount();
            if (size < bestSize) {
                bestSize = size;
                best = current;
            } else if (size > limit) {
                reorderAbandonedDirections += 1;
                break;
            }
        }
        while (current > start) {
            swapWithNextLevel(current - 1);
            current -= 1;
        }
        while (current > minLevel) {
            swapWithNextLevel(current - 1);
            current -= 1;
            int size = liveNodeCount();
            if (size < bestSize) {
                bestSize = size;
                best = current;
            } else if (size > limit) {
                reorderAbandonedDirections += 1;
                break;
            }
        }
        while (current < best) {
            swapWithNextLevel(current);
            current += 1;
        }
        while (current > best) {
            swapWithNextLevel(current - 1);
            current -= 1;
        }
        assert levelOfVariable(variable) == best;
    }

    /** Reported by the BDD, which is where a caller looks for them - there is only one order. */
    Map<String, Object> reorderStatistics() {
        return Map.of(
                "reorder_count", String.valueOf(reorderCount),
                "reorder_saved_nodes", String.valueOf(reorderSavedNodes),
                "reorder_time_milliseconds", String.valueOf(reorderTimeMilliseconds),
                "reorder_swaps", String.valueOf(reorderSwaps),
                "reorder_notifications", String.valueOf(reorderNotifications),
                "reorder_rewritten_nodes", String.valueOf(reorderRewrittenNodes),
                "reorder_collections", String.valueOf(reorderCollections),
                "reorder_abandoned_directions", String.valueOf(reorderAbandonedDirections),
                "reorder_identity_reverts", String.valueOf(reorderIdentityReverts),
                /* Nodes rewritten per node saved - the one ratio that says whether sifting is earning its
                 * keep, the way node_table_work_per_created_node does for memory management. */
                "reorder_work_per_saved_node", String.valueOf(Util.ratio(reorderRewrittenNodes, reorderSavedNodes)));
    }

    @Override
    public String toString() {
        return String.format("Order{%d variables%s}", numberOfVariables, explicitOrder ? "" : ", identity");
    }
}
