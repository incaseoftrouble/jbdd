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

/**
 * Owns everything the BDD and its MTBDD share: the variable order and the sifting that changes it.
 *
 * <p>Both diagrams index their nodes by variable but recurse by level, so there is exactly one order
 * and exactly one place that may move it. A swap has to rewrite both tables between the two halves of
 * the order change, which is why {@link #siftDown} lives here rather than on either diagram - and
 * {@link Bdd#reorder()} and {@link MtBdd#reorder()} are the same call, seen from either side.
 */
@SuppressWarnings("AssertWithSideEffects")
final class BddContextImpl implements BddContext {
    /* How much sifting is allowed to grow the node count at most */
    private static final double MAXIMUM_SIFT_GROWTH = 1.2;
    /* Unreachable fraction of a table a swap tolerates before collecting - see collectIfGarbagePiledUp.
     * Swept over the reordering benchmarks: the curve is a shallow basin, flat from 0.30 to 0.45 and
     * within noise across it, ~8% worse at 0.25, and worse again above 0.45 - where the garbage a swap
     * has to walk starts costing more than the collections saved, and by 0.60 the table doubles rather
     * than being collected at all (with no bound it runs out of memory). 0.40 sits mid-basin, two steps
     * from that edge. */
    private static final double MAXIMUM_SIFT_GARBAGE = 0.40;

    private final BddConfiguration configuration;
    private final BddImpl bdd;
    private final MtBddImpl mtbdd;

    private int numberOfVariables = 0;
    /* The variable order, as a bijection between a variable and its position. */
    private int[] variableToLevel = new int[32];
    private int[] levelToVariable = new int[32];
    /* Whether the order is not identity. */
    private boolean reordered = false;

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

    BddContextImpl(BddConfiguration configuration) {
        this.configuration = configuration;
        // The MTBDD hangs its caches and observers off the BDD, so the BDD has to exist first.
        this.bdd = new BddImpl(this);
        this.mtbdd = new MtBddImpl(this);
    }

    BddContextImpl(BddConfiguration configuration, int variables) {
        this(configuration);
        bdd.createVariables(variables);
    }

    BddConfiguration configuration() {
        return configuration;
    }

    @Override
    public BddImpl bdd() {
        return bdd;
    }

    @Override
    public MtBddImpl mtBdd() {
        return mtbdd;
    }

    // Variables and the order

    int numberOfVariables() {
        return numberOfVariables;
    }

    boolean reordered() {
        return reordered;
    }

    public int level(int variable) {
        assert 0 <= variable;
        return reordered && variable < numberOfVariables ? variableToLevel[variable] : variable;
    }

    public int variableAtLevel(int level) {
        assert 0 <= level;
        return reordered && level < numberOfVariables ? levelToVariable[level] : level;
    }

    private void ensureVariableCapacity(int variables) {
        if (variables > variableToLevel.length) {
            int length = Math.max(variableToLevel.length * 2, variables);
            variableToLevel = Arrays.copyOf(variableToLevel, length);
            levelToVariable = Arrays.copyOf(levelToVariable, length);
        }
    }

    /** Both caches key on the number of variables, so both have to hear about a new one. */
    private void notifyVariablesChanged() {
        bdd.cache().variablesChanged();
        mtbdd.cache().variablesChanged();
    }

    /* No blanket invalidation: the caches and the registered operations are told exactly what moved and
     * decide for themselves what that costs them. A swap keeps every entry that is only a statement about
     * node ids, which reordering preserves; an insertion keeps every entry at all, since it preserves
     * every level comparison, and moves only the levels registered operations store. */
    private void notifyLevelsSwapped(int level) {
        bdd.notifyLevelsSwapped(level);
        mtbdd.notifyLevelsSwapped(level);
    }

    private void notifyVariableInserted(int level) {
        bdd.notifyVariableInserted(level);
        mtbdd.notifyVariableInserted(level);
    }

    int createVariable() {
        assert bdd.accessGuard.acquire();
        // Manage the variable <-> level mapping eagerly, even if its identity, as its cheap
        ensureVariableCapacity(numberOfVariables + 1);

        // Need to set before makeVariableNode, which asks for the level
        variableToLevel[numberOfVariables] = numberOfVariables;
        levelToVariable[numberOfVariables] = numberOfVariables;
        int variableNode = bdd.makeVariableNode(numberOfVariables, numberOfVariables);
        numberOfVariables++;

        notifyVariablesChanged();

        assert bdd.accessGuard.release();
        return variableNode;
    }

    int[] createVariables(int count) {
        if (count == 0) {
            return EMPTY_INT_ARRAY;
        }
        if (count == 1) {
            return new int[] {createVariable()};
        }

        assert bdd.accessGuard.acquire();
        int newSize = numberOfVariables + count;
        ensureVariableCapacity(newSize);
        bdd.ensureVariableNodeCapacity(newSize);

        int[] newVariableNodes = new int[count];

        for (int i = 0; i < count; i++) {
            int variable = numberOfVariables + i;
            variableToLevel[variable] = variable;
            levelToVariable[variable] = variable;

            newVariableNodes[i] = bdd.makeVariableNode(variable, variable);
        }
        numberOfVariables += count;

        notifyVariablesChanged();

        assert bdd.accessGuard.release();
        return newVariableNodes;
    }

    public int createVariableAtLevel(int level) {
        checkState(0 <= level && level <= numberOfVariables, "Level %s out of range", level);
        assert bdd.accessGuard.acquire();
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();

        int variable = numberOfVariables;
        ensureVariableCapacity(variable + 1);

        for (int current = variable; current > level; current--) {
            int moved = levelToVariable[current - 1];
            levelToVariable[current] = moved;
            variableToLevel[moved] = current;
        }
        levelToVariable[level] = variable;
        variableToLevel[variable] = level;
        numberOfVariables += 1;
        reordered = reordered || level != variable;

        int variableNode = bdd.makeVariableNode(variable, level);

        notifyVariablesChanged();
        notifyVariableInserted(level);

        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();
        assert bdd.check();
        assert bdd.accessGuard.release();
        return variableNode;
    }

    // Reordering

    public void dropReorderStructures() {
        bdd.table().dropReorderingBookkeeping();
        mtbdd.table().dropReorderingBookkeeping();
    }

    @Override
    public void siftDown(int level) {
        assert 0 <= level && level + 1 < numberOfVariables;
        assert bdd.accessGuard.acquire();
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();

        reorderSwaps += 1;

        // Sifting creates orphan nodes, clean them if they pile up.
        bdd.table().enableReorderingBookkeeping();
        mtbdd.table().enableReorderingBookkeeping();
        if (bdd.table().deadNodeFraction() > MAXIMUM_SIFT_GARBAGE
                || mtbdd.table().deadNodeFraction() > MAXIMUM_SIFT_GARBAGE) {
            bdd.forceGc();
            mtbdd.forceGc();
            reorderCollections += 1;
        }

        int upper = variableAtLevel(level + 1);
        int lower = variableAtLevel(level);

        /* From here the tables are rewritten in place. Nothing has to be reserved first: under the
         * bracket a table grows where it would otherwise collect, which is the only part a half-swapped
         * level cannot survive - the nodes being rewritten live in a plain int[], which is no root. */
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
        reordered = true;

        bdd.rewriteLevelAfterSwap(bddNodes, bddCount, level, upper);
        mtbdd.rewriteLevelAfterSwap(mtbddNodes, mtbddCount, level, upper);
        reorderRewrittenNodes += (long) bddCount + mtbddCount;

        bdd.table().endRewrite();
        mtbdd.table().endRewrite();

        /* Rewriting in place leaves every node meaning what it did, so a cache entry naming nodes is still
         * true - but not every entry is only about nodes. Which is which is the caches' business; see
         * BooleanCache#levelsSwapped. */
        notifyLevelsSwapped(level);

        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();
        assert bdd.accessGuard.release();
    }

    /**
     * Realises {@code target} - the variables in the order they should occupy levels 0, 1, ... - with
     * adjacent swaps. Selection from the top: whatever belongs at the next level is bubbled up to it,
     * which never disturbs the levels already settled above. That costs exactly the number of inversions
     * between the current order and the target, which is the least any sequence of swaps can do.
     */
    private void permuteTo(int[] target) {
        for (int level = 0; level < target.length; level++) {
            int current = level(target[level]);
            assert current >= level : "Level " + level + " was already settled";
            while (current > level) {
                siftDown(current - 1);
                current -= 1;
            }
        }
    }

    /**
     * The variables in the order {@code blocks} asks for. Each block is anchored where its members
     * already are and the don't-cares are left in the order they are in, so this edits the current order
     * rather than replacing it: an order that already satisfies the request comes back unchanged.
     *
     * <p>Built as a sort key per variable. A don't-care sitting at level {@code l} keys on {@code 2l + 1},
     * so the don't-cares keep their relative order; every member of a block keys on {@code 2s}, where
     * {@code s} is where that block should start, so a block sorts as one contiguous run and ahead of a
     * don't-care that wants the same position. Within a block the members keep their relative order too,
     * which is all a set of variables can ask for.
     */
    private int[] targetOrder(List<BitSet> blocks) {
        BitSet listed = new BitSet(numberOfVariables);
        long[] keyed = new long[numberOfVariables];
        int index = 0;
        int nextFreeStart = 0;
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
                levels[at] = level(variable);
                at += 1;
            }
            listed.or(block);
            Arrays.sort(levels);

            /* Where the block has to start so that making its members consecutive moves them as little as
             * possible - the median of the levels each member would have to be pulled to, which for a
             * block that is already consecutive is exactly where it already is. Never negative: the
             * levels are distinct and ascending, so levels[k] >= k. Then pushed past the block before it,
             * which is what puts the blocks in the order they were listed even when they currently sit
             * the other way round. */
            int start = Math.max(nextFreeStart, levels[size / 2] - size / 2);
            nextFreeStart = start + size;
            for (int memberLevel : levels) {
                keyed[index] = ((long) (2 * start) << Integer.SIZE) | memberLevel;
                index += 1;
            }
        }
        for (int level = 0; level < numberOfVariables; level++) {
            if (!listed.get(variableAtLevel(level))) {
                keyed[index] = ((long) (2 * level + 1) << Integer.SIZE) | level;
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
        return target;
    }

    public void reorderTo(List<BitSet> blocks) {
        if (numberOfVariables < 2) {
            return;
        }
        assert bdd.accessGuard.acquire();
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();

        /* Checked rather than asserted, unlike reorder's groups: overlapping blocks would not degrade the
         * result, they would build a target that is not a permutation at all. It is O(variables) against
         * a swap loop that is quadratic in them. */
        // An order that already satisfies the request produces itself, so this then swaps nothing at all.
        int[] target = targetOrder(blocks);
        long startTimestamp = System.currentTimeMillis();
        permuteTo(target);
        reorderTimeMilliseconds += System.currentTimeMillis() - startTimestamp;

        /* The point of the whole call: the blocks are now exactly what reorder(blocks) will accept, so
         * "shape it, then optimise inside the shape" is two calls with the same argument. */
        assert groupsAreDisjointContiguousBlocks(blocks);
        assert bdd.check();
        assert bdd.accessGuard.release();
    }

    public int reorder() {
        if (numberOfVariables < 2) {
            return 0;
        }
        BitSet all = new BitSet(numberOfVariables);
        all.set(0, numberOfVariables);
        return reorder(List.of(all));
    }

    public int reorder(List<BitSet> groups) {
        if (numberOfVariables < 2) {
            return 0;
        }
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();
        assert groupsAreDisjointContiguousBlocks(groups);

        /* Clear out whatever the workload left behind first - from here on the node counts are exact and
         * maintained, so nothing has to be collected to measure a candidate position. */
        bdd.forceGc();
        mtbdd.forceGc();
        bdd.table().enableReorderingBookkeeping();
        mtbdd.table().enableReorderingBookkeeping();
        int nodesBefore = exactLiveNodeCount();
        long startTimestamp = System.currentTimeMillis();

        /* Heaviest first: A variable with many nodes has the most to gain, and moving it while
         * the order around it is still loose is what makes sifting work at all. The bounds come from the
         * variable's own group, so a variable never leaves the block it was given. */
        int[] weight = new int[numberOfVariables];
        for (int variable = 0; variable < numberOfVariables; variable++) {
            weight[variable] =
                    bdd.table().nodesWithVariable(variable) + mtbdd.table().nodesWithVariable(variable);
        }

        for (BitSet group : groups) {
            if (group.cardinality() < 2) {
                continue;
            }
            int lowestLevel = Integer.MAX_VALUE;
            int highestLevel = -1;
            for (int variable = group.nextSetBit(0); variable >= 0; variable = group.nextSetBit(variable + 1)) {
                lowestLevel = Math.min(lowestLevel, level(variable));
                highestLevel = Math.max(highestLevel, level(variable));
            }

            long[] order = new long[group.cardinality()];
            int index = 0;
            for (int variable = group.nextSetBit(0); variable >= 0; variable = group.nextSetBit(variable + 1)) {
                order[index] = ((long) weight[variable] << Integer.SIZE) | variable;
                index += 1;
            }
            Arrays.sort(order);

            for (int position = order.length - 1; position >= 0; position--) {
                sift((int) order[position], lowestLevel, highestLevel);
            }
        }

        int saved = nodesBefore - exactLiveNodeCount();
        // After the final count, so the time covers everything the call did, collections included.
        reorderTimeMilliseconds += System.currentTimeMillis() - startTimestamp;
        assert saved >= 0 : "Sifting left the diagram bigger than it found it";
        reorderCount += 1;
        reorderSavedNodes += saved;

        if (!configuration.keepReorderingStructures()) {
            dropReorderStructures();
        }
        assert bdd.check();
        return saved;
    }

    /**
     * Disjoint, and each already a contiguous run of levels. Variables in no group are left where they
     * are, which is the same as giving each its own singleton group - so a group may not straddle one,
     * and the contiguity check is what catches that.
     */
    private boolean groupsAreDisjointContiguousBlocks(List<BitSet> groups) {
        BitSet seen = new BitSet(numberOfVariables);
        for (BitSet group : groups) {
            checkState(!group.intersects(seen), "Reordering groups overlap");
            seen.or(group);

            int lowest = Integer.MAX_VALUE;
            int deepest = -1;
            for (int variable = group.nextSetBit(0); variable >= 0; variable = group.nextSetBit(variable + 1)) {
                checkState(variable < numberOfVariables, "Unknown variable %s in a reordering group", variable);
                lowest = Math.min(lowest, level(variable));
                deepest = Math.max(deepest, level(variable));
            }
            checkState(
                    group.isEmpty() || deepest - lowest + 1 == group.cardinality(),
                    "Reordering group %s does not occupy a contiguous run of levels",
                    group);
        }
        return true;
    }

    private int exactLiveNodeCount() {
        // Free, because the parent counts are maintained anyway; the collection policy that keeps it
        // meaningful lives in siftDown, which every candidate position goes through.
        return bdd.table().liveNodeCount() + mtbdd.table().liveNodeCount();
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    private void sift(int variable, int highestLevel, int deepestLevel) {
        int start = level(variable);
        assert highestLevel <= start && start <= deepestLevel;
        int best = start;
        int bestSize = exactLiveNodeCount();
        // Stop exploring a direction once it has cost more than this - the usual bound, without which
        // every variable pays for a full sweep of a range that was never going to win.
        int limit = (int) Math.min(Integer.MAX_VALUE, (long) (bestSize * MAXIMUM_SIFT_GROWTH));

        int current = start;
        while (current < deepestLevel) {
            siftDown(current);
            current += 1;
            int size = exactLiveNodeCount();
            if (size < bestSize) {
                bestSize = size;
                best = current;
            } else if (size > limit) {
                reorderAbandonedDirections += 1;
                break;
            }
        }
        while (current > start) {
            siftDown(current - 1);
            current -= 1;
        }
        while (current > highestLevel) {
            siftDown(current - 1);
            current -= 1;
            int size = exactLiveNodeCount();
            if (size < bestSize) {
                bestSize = size;
                best = current;
            } else if (size > limit) {
                reorderAbandonedDirections += 1;
                break;
            }
        }
        while (current < best) {
            siftDown(current);
            current += 1;
        }
        while (current > best) {
            siftDown(current - 1);
            current -= 1;
        }
        assert level(variable) == best;
    }

    /** Reported by the BDD, which is where a caller looks for them - there is only one order. */
    Map<String, Object> reorderStatistics() {
        return Map.of(
                "reorder_count", String.valueOf(reorderCount),
                "reorder_saved_nodes", String.valueOf(reorderSavedNodes),
                "reorder_time_milliseconds", String.valueOf(reorderTimeMilliseconds),
                "reorder_swaps", String.valueOf(reorderSwaps),
                "reorder_rewritten_nodes", String.valueOf(reorderRewrittenNodes),
                "reorder_collections", String.valueOf(reorderCollections),
                "reorder_abandoned_directions", String.valueOf(reorderAbandonedDirections),
                /* Nodes rewritten per node saved - the one ratio that says whether sifting is earning its
                 * keep, the way node_table_work_per_created_node does for memory management. */
                "reorder_work_per_saved_node", String.valueOf(Util.ratio(reorderRewrittenNodes, reorderSavedNodes)));
    }

    @Override
    public String toString() {
        return String.format("Context{%s}", bdd);
    }
}
