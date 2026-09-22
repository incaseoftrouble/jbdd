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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reordering must leave every function meaning exactly what it did, with the very same id - only the
 * shape of the diagram, and {@link ReorderableDd#levelOfVariable}, may change.
 */
class ReorderTest {
    private static final BddConfiguration CONFIG =
            ImmutableBddConfiguration.builder().build();

    /** Every valuation of {@code variables}, as the assignment arrays {@code evaluate} takes. */
    private static List<BitSet> valuations(int variables) {
        List<BitSet> all = new ArrayList<>();
        for (int mask = 0; mask < (1 << variables); mask++) {
            BitSet assignment = new BitSet(variables);
            for (int i = 0; i < variables; i++) {
                if ((mask & (1 << i)) != 0) {
                    assignment.set(i);
                }
            }
            all.add(assignment);
        }
        return all;
    }

    /** The truth table of {@code function}, which reordering must preserve exactly. */
    private static List<Boolean> truthTable(Bdd bdd, int function, int variables) {
        List<Boolean> table = new ArrayList<>();
        for (BitSet assignment : valuations(variables)) {
            table.add(bdd.evaluate(function, assignment));
        }
        return table;
    }

    private static List<Integer> randomFunctions(BddImpl bdd, int variables, int count, long seed) {
        Random random = new Random(seed);
        int[] v = bdd.createVariables(variables);
        List<Integer> functions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int function = random.nextBoolean() ? bdd.trueFunction() : bdd.falseFunction();
            for (int term = 0; term < 3; term++) {
                int literal = v[random.nextInt(variables)];
                if (random.nextBoolean()) {
                    literal = bdd.not(literal);
                }
                int other = v[random.nextInt(variables)];
                int clause = bdd.reference(bdd.or(literal, other));
                function = bdd.consume(
                        random.nextBoolean() ? bdd.and(function, clause) : bdd.or(function, clause), function, clause);
            }
            functions.add(bdd.reference(function));
        }
        return functions;
    }

    @Test
    void testSingleSwapPreservesEveryFunction() {
        int variables = 6;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 12, 20_260_902L);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }

        for (int level = 0; level + 1 < variables; level++) {
            int lower = bdd.variableAtLevel(level);
            int upper = bdd.variableAtLevel(level + 1);

            context.variableOrder().siftDown(level);

            assertEquals(level + 1, bdd.levelOfVariable(lower));
            assertEquals(level, bdd.levelOfVariable(upper));
            assertTrue(bdd.check(), "table is inconsistent after swapping level " + level);

            for (int i = 0; i < functions.size(); i++) {
                // Same id, same meaning - the whole point of rewriting in place.
                assertEquals(
                        before.get(i),
                        truthTable(bdd, functions.get(i), variables),
                        "function " + functions.get(i) + " changed after swapping level " + level);
            }
        }
    }

    @Test
    void testSwappingBackAndForthIsTheIdentity() {
        int variables = 5;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 8, 4242L);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }
        int sizeBefore = bdd.nodeCount();

        context.variableOrder().siftDown(2);
        context.variableOrder().siftDown(2);

        for (int variable = 0; variable < variables; variable++) {
            assertEquals(variable, bdd.levelOfVariable(variable));
        }
        bdd.gc();
        assertEquals(sizeBefore, bdd.nodeCount(), "swapping back should restore the original diagram");
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables));
        }
        assertTrue(bdd.check());
    }

    @Test
    void testReorderPreservesEveryFunctionAndTheirIds() {
        int variables = 8;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 20, 987_654_321L);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }

        bdd.variableOrder().reorder();

        assertTrue(bdd.check());
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(
                    before.get(i),
                    truthTable(bdd, functions.get(i), variables),
                    "function " + functions.get(i) + " changed under reordering");
        }

        // level() is a bijection over the variables
        Set<Integer> levels = new HashSet<>();
        for (int variable = 0; variable < variables; variable++) {
            assertTrue(levels.add(bdd.levelOfVariable(variable)));
            assertEquals(variable, bdd.variableAtLevel(bdd.levelOfVariable(variable)));
        }
        assertEquals(variables, levels.size());
    }

    @Test
    void testReorderingCostIsReportedSeparately() {
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        List<Integer> functions = randomFunctions(bdd, 8, 20, 123_456_789L);
        assertEquals(0L, statistic(context, "bdd_node_table_reorder_created_nodes"));

        long createdBefore = statistic(context, "bdd_created_nodes");
        bdd.variableOrder().reorder();
        long createdByReordering = statistic(context, "bdd_created_nodes") - createdBefore;
        assertTrue(createdByReordering > 0, "sifting eight variables rewrote nothing");
        assertEquals(createdByReordering, statistic(context, "bdd_node_table_reorder_created_nodes"));

        // Operations after the reordering count toward the total only.
        bdd.and(functions.get(0), functions.get(1));
        assertEquals(createdByReordering, statistic(context, "bdd_node_table_reorder_created_nodes"));
        assertTrue(
                statistic(context, "bdd_node_table_reorder_gc_count") <= statistic(context, "bdd_node_table_gc_count"));
    }

    private static long statistic(DdContextImpl context, String key) {
        Object value = context.statistics().get(key);
        assertNotNull(value, key);
        return Long.parseLong(value.toString());
    }

    @Test
    void testSupportAndEvaluateSurviveReordering() {
        // Everything that indexes *by variable* - a support set, an evaluation's assignment - has to keep
        // naming variables, not their positions. Reordering is where the two come apart, and confusing
        // them is silent corruption rather than a crash.
        int variables = 7;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 10, 13_579L);

        bdd.variableOrder().reorder();
        assertTrue(bdd.check());
        // Sifting may well land back on the identity; force a non-trivial order so the test is about the
        // translation rather than about what sifting happened to pick.
        if (identityOrder(variables).equals(currentOrder(bdd, variables))) {
            context.variableOrder().siftDown(0);
            context.variableOrder().siftDown(2);
        }
        assertNotEquals(identityOrder(variables), currentOrder(bdd, variables));

        for (int function : functions) {
            BitSet dependsOn = new BitSet();
            for (BitSet assignment : valuations(variables)) {
                for (int variable = 0; variable < variables; variable++) {
                    BitSet flipped = (BitSet) assignment.clone();
                    flipped.flip(variable);
                    if (bdd.evaluate(function, assignment) != bdd.evaluate(function, flipped)) {
                        dependsOn.set(variable);
                    }
                }
            }
            assertEquals(dependsOn, bdd.support(function), "support of " + function);

            boolean[] array = new boolean[variables];
            for (BitSet assignment : valuations(variables)) {
                for (int variable = 0; variable < variables; variable++) {
                    array[variable] = assignment.get(variable);
                }
                assertEquals(
                        bdd.evaluate(function, assignment),
                        bdd.evaluate(function, array),
                        "the two evaluate overloads disagree for " + function);
            }
        }
    }

    @Test
    void testSolutionEnumerationAfterReordering() {
        int variables = 7;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 10, 13_579L);

        bdd.variableOrder().reorder();

        for (int function : functions) {
            Set<BitSet> expected = new HashSet<>();
            for (BitSet assignment : valuations(variables)) {
                if (bdd.evaluate(function, assignment)) {
                    expected.add((BitSet) assignment.clone());
                }
            }

            BitSet support = bdd.support(function);
            Set<BitSet> enumerated = new HashSet<>();
            bdd.forEachSolution(function, solution -> {
                // Solutions only fix the support; expand the rest to compare against the truth table.
                BitSet fixed = (BitSet) solution.clone();
                fixed.and(support);
                for (BitSet assignment : valuations(variables)) {
                    BitSet candidate = (BitSet) assignment.clone();
                    candidate.and(support);
                    if (candidate.equals(fixed)) {
                        enumerated.add((BitSet) assignment.clone());
                    }
                }
            });
            assertEquals(expected, enumerated, "solutions of " + function);
        }
    }

    @Test
    void testPathEnumerationAfterReordering() {
        int variables = 6;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 8, 24_680L);

        bdd.variableOrder().reorder();
        // Sifting may well land back on the identity; force a non-trivial order so the test is about the
        // translation rather than about what sifting happened to pick.
        if (identityOrder(variables).equals(currentOrder(bdd, variables))) {
            context.variableOrder().siftDown(0);
            context.variableOrder().siftDown(2);
        }
        assertNotEquals(identityOrder(variables), currentOrder(bdd, variables));

        for (int function : functions) {
            Set<BitSet> expected = new HashSet<>();
            for (BitSet assignment : valuations(variables)) {
                if (bdd.evaluate(function, assignment)) {
                    expected.add((BitSet) assignment.clone());
                }
            }

            // Every path is a cube: the assignments it covers are exactly those agreeing with it on its
            // own support. Together the paths must cover the solutions exactly.
            Set<BitSet> covered = new HashSet<>();
            bdd.forEachPath(function, path -> {
                BitSet pathSupport = path.copySupport();
                BitSet pathAssignment = path.copyAssignment();
                for (BitSet assignment : valuations(variables)) {
                    BitSet masked = (BitSet) assignment.clone();
                    masked.and(pathSupport);
                    if (masked.equals(pathAssignment)) {
                        assertTrue(bdd.evaluate(function, assignment), "path covers a non-solution");
                        covered.add((BitSet) assignment.clone());
                    }
                }
            });
            assertEquals(expected, covered, "paths of " + function);
        }
    }

    @Test
    void testRegisteredComposeFollowsAReordering() {
        // A registered compose keeps the deepest level it must descend to, so a reordering invalidates it.
        int variables = 6;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        int[] v = bdd.createVariables(variables);
        int f = bdd.reference(bdd.and(bdd.or(v[0], v[3]), bdd.xor(v[1], v[4])));

        int[] mapping = new int[variables];
        for (int i = 0; i < variables; i++) {
            mapping[i] = bdd.placeholder();
        }
        mapping[0] = bdd.reference(bdd.and(v[2], v[5]));
        RegisteredOperation.Unary compose = bdd.registerCompose(mapping);

        int expected = bdd.reference(compose.applyAsInt(f));
        List<Boolean> before = truthTable(bdd, expected, variables);

        context.variableOrder().siftDown(0);
        context.variableOrder().siftDown(3);
        context.variableOrder().siftDown(1);

        int after = compose.applyAsInt(f);
        assertEquals(before, truthTable(bdd, after, variables), "registered compose went stale on reordering");
        assertEquals(expected, after, "and it should find the very same node");
        assertTrue(bdd.check());
    }

    @Test
    void testRegisteredExistsFollowsAReordering() {
        // A registered exists keeps its quantified set indexed by level, so a reordering moves it.
        int variables = 6;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        int[] v = bdd.createVariables(variables);
        int f = bdd.reference(bdd.and(bdd.or(v[0], v[3]), bdd.xor(v[1], v[4])));

        BitSet quantified = BitSets.of(1, 3);
        RegisteredOperation.Unary exists = bdd.registerExists(quantified);

        int expected = bdd.reference(exists.applyAsInt(f));
        List<Boolean> before = truthTable(bdd, expected, variables);

        context.variableOrder().siftDown(0);
        context.variableOrder().siftDown(3);
        context.variableOrder().siftDown(1);

        int after = exists.applyAsInt(f);
        assertEquals(before, truthTable(bdd, after, variables), "registered exists went stale on reordering");
        assertEquals(expected, after, "and it should find the very same node");
        assertEquals(bdd.exists(f, quantified), after);
        assertTrue(bdd.check());
    }

    @Test
    void testReorderReportsWhatItSaved() {
        int pairs = 6;
        int variables = 2 * pairs;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        int[] v = bdd.createVariables(variables);

        // The split order is exponential here, the interleaved one linear, so there is plenty to save.
        int function = bdd.falseFunction();
        for (int i = 0; i < pairs; i++) {
            int conjunct = bdd.reference(bdd.and(v[i], v[i + pairs]));
            function = bdd.consume(bdd.or(function, conjunct), function, conjunct);
        }
        bdd.reference(function);
        bdd.gc();
        int before = bdd.nodeCount();

        int saved = bdd.variableOrder().reorder();
        bdd.gc();
        int after = bdd.nodeCount();

        assertTrue(saved > 0, "expected the reordering to save something");
        assertEquals(before - after, saved, "the reported saving must be the actual one");
        assertEquals(String.valueOf(saved), bdd.statistics().get("reorder_saved_nodes"));
        assertEquals("1", bdd.statistics().get("reorder_count"));
        // The cost side is recorded too, and a sift that saved something must have swapped something.
        assertTrue(Long.parseLong((String) bdd.statistics().get("reorder_swaps")) > 0);
        assertTrue(Long.parseLong((String) bdd.statistics().get("reorder_rewritten_nodes")) > 0);
        assertNotNull(bdd.statistics().get("reorder_collections"));
        assertNotNull(bdd.statistics().get("reorder_abandoned_directions"));
        assertNotNull(bdd.statistics().get("reorder_time_milliseconds"));
        assertNotNull(bdd.statistics().get("reorder_work_per_saved_node"));

        // A second reordering has nothing left to find, and says so rather than going backwards.
        int again = bdd.variableOrder().reorder();
        assertEquals(0, again);
        assertEquals("2", bdd.statistics().get("reorder_count"));
        assertEquals(String.valueOf(saved), bdd.statistics().get("reorder_saved_nodes"));
    }

    @Test
    void testKeepReorderingStructuresIsInvisibleApartFromCost() {
        // Whether the bookkeeping survives a reorder is a cost decision, never a semantic one.
        BddConfiguration keeping = ImmutableBddConfiguration.builder()
                .keepReorderingStructures(true)
                .build();
        int variables = 8;

        List<Integer> orders = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (BddConfiguration config : List.of(CONFIG, keeping)) {
            BddImpl bdd = new DdContextImpl(config).bdd();
            List<Integer> functions = randomFunctions(bdd, variables, 15, 777L);

            List<List<Boolean>> before = new ArrayList<>();
            for (int function : functions) {
                before.add(truthTable(bdd, function, variables));
            }

            bdd.variableOrder().reorder();
            assertTrue(bdd.check());
            for (int i = 0; i < functions.size(); i++) {
                assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables));
            }
            // A second reorder must work whether the structures were kept or have to be rebuilt.
            bdd.variableOrder().reorder();
            assertTrue(bdd.check());

            bdd.gc();
            sizes.add(bdd.nodeCount());
            for (int level = 0; level < variables; level++) {
                orders.add(bdd.variableAtLevel(level));
            }
        }
        assertEquals(orders.subList(0, variables), orders.subList(variables, 2 * variables));
        assertEquals(sizes.get(0), sizes.get(1));
    }

    @Test
    void testGroupedReorderKeepsVariablesInTheirBlock() {
        int variables = 8;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 15, 555L);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }

        // Three blocks, in level order: {0,1,2} on top, then {3,4}, then {5,6,7}.
        List<BitSet> groups = List.of(BitSets.of(0, 1, 2), BitSets.of(3, 4), BitSets.of(5, 6, 7));
        bdd.variableOrder().reorder(groups);

        assertTrue(bdd.check());
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables), "function changed");
        }

        // Every variable is still somewhere inside the run of levels its group started with.
        int blockStart = 0;
        for (BitSet group : groups) {
            int blockEnd = blockStart + group.cardinality() - 1;
            for (int variable = group.nextSetBit(0); variable >= 0; variable = group.nextSetBit(variable + 1)) {
                int level = bdd.levelOfVariable(variable);
                assertTrue(
                        blockStart <= level && level <= blockEnd,
                        "variable " + variable + " left its block: level " + level + " not in [" + blockStart + ", "
                                + blockEnd + "]");
            }
            blockStart = blockEnd + 1;
        }
    }

    @Test
    void testGroupedReorderLeavesUngroupedVariablesAlone() {
        int variables = 7;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 12, 909L);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }

        // Only levels 2..4 may move; 0, 1, 5 and 6 are in no group and must stay put.
        bdd.variableOrder().reorder(List.of(BitSets.of(2, 3, 4)));

        assertTrue(bdd.check());
        for (int variable : new int[] {0, 1, 5, 6}) {
            assertEquals(variable, bdd.levelOfVariable(variable), "ungrouped variable " + variable + " moved");
        }
        for (int variable : new int[] {2, 3, 4}) {
            int level = bdd.levelOfVariable(variable);
            assertTrue(2 <= level && level <= 4, "grouped variable " + variable + " left its block");
        }
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables));
        }
    }

    @Test
    void testGroupedReorderRejectsMalformedGroups() {
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        bdd.createVariables(4);
        assumeTrue(assertionsEnabled());

        // Checked through the same assert-plus-checkState idiom the rest of the library validates with,
        // so these only fire with -ea.
        // Overlapping
        assertThrows(
                IllegalStateException.class,
                () -> bdd.variableOrder().reorder(List.of(BitSets.of(0, 1, 2), BitSets.of(2, 3))));
        // Not a contiguous run of levels
        assertThrows(
                IllegalStateException.class,
                () -> bdd.variableOrder().reorder(List.of(BitSets.of(0, 2), BitSets.of(1, 3))));
        // ... including when the straddled variable is simply left out
        assertThrows(IllegalStateException.class, () -> bdd.variableOrder().reorder(List.of(BitSets.of(0, 2))));
    }

    @Test
    void testCreateVariableAtLevelInsertsWithoutTouchingTheDiagram() {
        int variables = 6;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 12, 31_337L);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }
        bdd.gc();
        int nodesBefore = bdd.nodeCount();

        int inserted = context.createVariableAtLevel(2);
        int insertedVariable = bdd.decisionVariable(inserted);

        assertTrue(bdd.check());
        assertEquals(variables, insertedVariable, "a new variable is appended, only its level is chosen");
        assertEquals(2, bdd.levelOfVariable(insertedVariable));
        // Exactly one node was added - the variable node itself. Nothing existing was rewritten.
        assertEquals(nodesBefore + 1, bdd.nodeCount());

        // Everything that sat at level 2 or below moved down exactly one; the rest did not move.
        for (int variable = 0; variable < variables; variable++) {
            assertEquals(variable < 2 ? variable : variable + 1, bdd.levelOfVariable(variable));
        }

        // The old functions do not mention the new variable, so their truth tables over the old variables
        // are untouched.
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables));
        }
        assertFalse(bdd.support(functions.get(0)).get(insertedVariable));

        // ... and the new variable behaves like any other
        int combined = bdd.reference(bdd.and(functions.get(0), inserted));
        assertTrue(bdd.support(combined).get(insertedVariable));
        assertTrue(bdd.check());
    }

    @Test
    void testCreateVariableAtLevelAtBothEnds() {
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        bdd.createVariables(3);

        int top = context.createVariableAtLevel(0);
        assertEquals(0, bdd.levelOfVariable(bdd.decisionVariable(top)));
        assertEquals(List.of(3, 0, 1, 2), currentOrder(bdd, 4));

        int bottom = context.createVariableAtLevel(4);
        assertEquals(4, bdd.levelOfVariable(bdd.decisionVariable(bottom)));
        assertEquals(List.of(3, 0, 1, 2, 4), currentOrder(bdd, 5));
        assertTrue(bdd.check());
    }

    private static boolean assertionsEnabled() {
        boolean[] enabled = new boolean[1];
        assert setTrue(enabled);
        return enabled[0];
    }

    private static boolean setTrue(boolean[] flag) {
        flag[0] = true;
        return true;
    }

    private static List<Integer> identityOrder(int variables) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < variables; i++) {
            order.add(i);
        }
        return order;
    }

    private static List<Integer> currentOrder(Bdd bdd, int variables) {
        List<Integer> order = new ArrayList<>();
        for (int level = 0; level < variables; level++) {
            order.add(bdd.variableAtLevel(level));
        }
        return order;
    }

    @Test
    void testReorderShrinksAPathologicalOrder() {
        // The textbook case: x0*x1 + x2*x3 + ... is linear in the interleaved order and exponential in
        // the order that separates each pair, which is the one it gets built in here.
        int pairs = 7;
        int variables = 2 * pairs;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        int[] v = bdd.createVariables(variables);

        int function = bdd.falseFunction();
        for (int i = 0; i < pairs; i++) {
            int conjunct = bdd.reference(bdd.and(v[i], v[i + pairs]));
            function = bdd.consume(bdd.or(function, conjunct), function, conjunct);
        }
        bdd.reference(function);

        List<Boolean> before = truthTable(bdd, function, variables);
        bdd.gc();
        int sizeBefore = bdd.nodeCount();

        bdd.variableOrder().reorder();
        bdd.gc();
        int sizeAfter = bdd.nodeCount();

        assertEquals(before, truthTable(bdd, function, variables), "reordering changed the function");
        assertTrue(bdd.check());
        assertTrue(
                sizeAfter < sizeBefore,
                "expected reordering to shrink the diagram, got " + sizeBefore + " -> " + sizeAfter);
        // The interleaved order is linear; anything close to it is a large win over the split one.
        assertTrue(sizeAfter <= sizeBefore / 2, "expected a big win, got " + sizeBefore + " -> " + sizeAfter);
        assertNotEquals(0, sizeAfter);
    }

    @Test
    void testRegisteredMtBddComposeFollowsAReordering() {
        /* As testRegisteredComposeFollowsAReordering, for the MTBDD engine. Nothing else reaches it under a
         * reorder: MtBddAsBinaryDd routes compose through the unregistered path, and MtBddTheories does not
         * register operations - so without this the cut-off it caches is never re-derived under test. */
        int variables = 6;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        MtBddImpl mtbdd = bdd.mtbdd();
        int[] v = bdd.createVariables(variables);

        int condition = bdd.reference(bdd.and(v[0], v[3]));
        int other = bdd.reference(bdd.xor(v[1], v[4]));
        int inner = mtbdd.reference(mtbdd.ifThenElse(other, mtbdd.of(3), mtbdd.of(1)));
        int f = mtbdd.reference(mtbdd.ifThenElse(condition, mtbdd.of(7), inner));
        mtbdd.dereference(inner);
        bdd.dereference(condition, other);

        int[] mapping = new int[variables];
        for (int i = 0; i < variables; i++) {
            mapping[i] = bdd.placeholder();
        }
        mapping[0] = bdd.reference(bdd.and(v[2], v[5]));
        RegisteredOperation.Unary compose = mtbdd.registerCompose(mapping);

        int expected = mtbdd.reference(compose.applyAsInt(f));
        List<Integer> before = valueTable(mtbdd, expected, variables);

        context.variableOrder().siftDown(0);
        context.variableOrder().siftDown(3);
        context.variableOrder().siftDown(1);

        int after = compose.applyAsInt(f);
        assertEquals(before, valueTable(mtbdd, after, variables), "registered MTBDD compose went stale on reordering");
        assertEquals(expected, after, "and it should find the very same node");
        assertTrue(bdd.check());
        assertTrue(mtbdd.check());
    }

    private static List<Integer> valueTable(MtBddImpl mtbdd, int function, int variables) {
        List<Integer> table = new ArrayList<>();
        for (BitSet assignment : valuations(variables)) {
            table.add(mtbdd.evaluate(function, assignment));
        }
        return table;
    }

    @Test
    void testReorderKeepsTheCompanionMtBddConsistent() {
        int variables = 6;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        BddSetFactoryImpl sets = new BddSetFactoryImpl(bdd);
        Values<String> values = new BddMapFactoryImpl(sets).create();
        MtBddImpl mtbdd = bdd.mtbdd();
        bdd.createVariables(variables);

        // A map whose structure spans several levels, so the MTBDD really has nodes to swap.
        BddMap<String> map = values.of("base");
        for (int i = 0; i < variables; i++) {
            map = map.update(sets.make(bdd.variableFunction(i)), "v" + i);
        }

        List<String> before = new ArrayList<>();
        for (BitSet assignment : valuations(variables)) {
            before.add(map.evaluate(assignment));
        }

        bdd.variableOrder().reorder();

        assertTrue(bdd.check());
        assertTrue(mtbdd.check());
        List<String> after = new ArrayList<>();
        for (BitSet assignment : valuations(variables)) {
            after.add(map.evaluate(assignment));
        }
        assertEquals(before, after, "the companion MTBDD did not follow the BDD's reordering");
    }

    private static BitSet block(int... variables) {
        BitSet set = new BitSet();
        for (int variable : variables) {
            set.set(variable);
        }
        return set;
    }

    /** The levels {@code block} occupies right now, smallest first. */
    private static List<Integer> levelsOf(Bdd bdd, BitSet block) {
        List<Integer> levels = new ArrayList<>();
        for (int variable = block.nextSetBit(0); variable >= 0; variable = block.nextSetBit(variable + 1)) {
            levels.add(bdd.levelOfVariable(variable));
        }
        levels.sort(null);
        return levels;
    }

    /** Each block a contiguous run of levels, and the blocks in the order they were listed. */
    private static void assertBlocksInOrder(Bdd bdd, List<BitSet> blocks) {
        int previousMaxLevel = -1;
        for (BitSet block : blocks) {
            List<Integer> levels = levelsOf(bdd, block);
            assertEquals(
                    levels.get(levels.size() - 1) - levels.get(0) + 1,
                    levels.size(),
                    "block " + block + " does not occupy a contiguous run of levels: " + levels);
            assertTrue(levels.get(0) > previousMaxLevel, "block " + block + " is not below the one before it");
            previousMaxLevel = levels.get(levels.size() - 1);
        }
    }

    @Test
    void testReorderToPlacesTheBlocksItIsGiven() {
        int variables = 7;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 10, 271_828L);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }

        // Deliberately out of order and with two don't-cares (1 and 4) left over.
        List<BitSet> blocks = List.of(block(5, 2), block(0), block(6, 3));
        bdd.variableOrder().reorderTo(blocks);

        assertTrue(bdd.check());
        assertBlocksInOrder(bdd, blocks);
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(
                    before.get(i),
                    truthTable(bdd, functions.get(i), variables),
                    "function " + functions.get(i) + " changed while being rearranged");
        }
    }

    @Test
    void testReorderToMakesReorderLegalOnTheSameBlocks() {
        /* The contract that makes the two compose: reorderTo fixes the block structure, reorder then
         * optimises inside it. Before the call these groups straddle each other, so reorder would reject
         * them. */
        int variables = 7;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        List<Integer> functions = randomFunctions(bdd, variables, 10, 141_421L);

        List<BitSet> blocks = List.of(block(6, 1), block(4, 0, 5));
        assertThrows(
                IllegalStateException.class,
                () -> bdd.variableOrder().reorder(blocks),
                "the blocks should not be legal yet");

        bdd.variableOrder().reorderTo(blocks);
        assertBlocksInOrder(bdd, blocks);

        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }
        bdd.variableOrder().reorder(blocks);

        assertTrue(bdd.check());
        // Sifting inside the blocks may permute them, but may not break them open.
        assertBlocksInOrder(bdd, blocks);
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables));
        }
    }

    @Test
    void testReorderToWithSingletonsFixesTheExactOrder() {
        int variables = 5;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        randomFunctions(bdd, variables, 8, 161_803L);

        List<BitSet> exact = List.of(block(3), block(1), block(4), block(0), block(2));
        bdd.variableOrder().reorderTo(exact);

        assertEquals(List.of(3, 1, 4, 0, 2), currentOrder(bdd, variables));
        assertTrue(bdd.check());
    }

    @Test
    void testReorderToIsIdempotent() {
        // It asks for the cheapest permutation satisfying the request, so an order already satisfying it
        // must cost nothing at all - otherwise it is moving variables it was never asked to move.
        int variables = 6;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        randomFunctions(bdd, variables, 8, 173_205L);

        List<BitSet> blocks = List.of(block(4, 2), block(0, 5));
        bdd.variableOrder().reorderTo(blocks);
        List<Integer> order = currentOrder(bdd, variables);
        Object swaps = bdd.statistics().get("reorder_swaps");

        bdd.variableOrder().reorderTo(blocks);

        assertEquals(order, currentOrder(bdd, variables));
        assertEquals(swaps, bdd.statistics().get("reorder_swaps"), "the second call should have moved nothing");
    }

    @Test
    void testReorderToLeavesDontCaresWhereTheyAre() {
        /* A don't-care is not pushed anywhere - not below the blocks, not above them. Here the one block
         * is already contiguous and in place, so the whole call must be a no-op even though four of the
         * six variables were never mentioned. */
        int variables = 6;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        randomFunctions(bdd, variables, 8, 223_606L);

        List<BitSet> blocks = List.of(block(3, 4));
        bdd.variableOrder().reorderTo(blocks);

        assertEquals(identityOrder(variables), currentOrder(bdd, variables), "don't-cares were moved");
        assertBlocksInOrder(bdd, blocks);
        assertTrue(bdd.check());
    }

    @Test
    void testReorderToOnlyClosesTheGapsItHasTo() {
        // The block straddles variable 2, so 2 has to move - but only out of the block, not below it.
        int variables = 6;
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        randomFunctions(bdd, variables, 8, 449_489L);

        List<BitSet> blocks = List.of(block(1, 3));
        bdd.variableOrder().reorderTo(blocks);

        assertBlocksInOrder(bdd, blocks);
        assertTrue(
                bdd.levelOfVariable(0) < bdd.levelOfVariable(1),
                "variable 0 was above the block and should have stayed there");
        assertTrue(
                bdd.levelOfVariable(5) > bdd.levelOfVariable(3),
                "variable 5 was below the block and should have stayed there");
        assertTrue(bdd.check());
    }

    @Test
    void testReorderToRejectsOverlappingBlocks() {
        BddImpl bdd = new DdContextImpl(CONFIG).bdd();
        bdd.createVariables(4);
        assertThrows(
                IllegalStateException.class, () -> bdd.variableOrder().reorderTo(List.of(block(0, 1), block(1, 2))));
    }

    @Test
    void testReorderToMovesTheCompanionMtBdd() {
        int variables = 5;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        MtBddImpl mtbdd = context.mtBdd();
        int[] v = bdd.createVariables(variables);

        int inner = mtbdd.reference(mtbdd.ifThenElse(bdd.xor(v[1], v[4]), mtbdd.of(3), mtbdd.of(1)));
        int f = mtbdd.reference(mtbdd.ifThenElse(bdd.and(v[0], v[3]), mtbdd.of(7), inner));
        mtbdd.dereference(inner);

        List<Integer> before = valueTable(mtbdd, f, variables);
        List<BitSet> blocks = List.of(block(4, 3), block(0));
        bdd.variableOrder().reorderTo(blocks);

        assertTrue(bdd.check());
        assertTrue(mtbdd.check());
        assertBlocksInOrder(bdd, blocks);
        assertEquals(before, valueTable(mtbdd, f, variables), "the companion MTBDD did not follow");
    }

    @Test
    void testReorderToIdentityUndoesReordering() {
        int variables = 6;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        MtBddImpl mtbdd = context.mtBdd();
        List<Integer> functions = randomFunctions(bdd, variables, 10, 314_159L);

        int mt = mtbdd.reference(mtbdd.ifThenElse(functions.get(0), mtbdd.of(5), mtbdd.of(2)));
        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }
        List<Integer> mtBefore = valueTable(mtbdd, mt, variables);

        bdd.variableOrder().reorderTo(List.of(block(5, 4, 3), block(0)));
        assertNotEquals(identityOrder(variables), currentOrder(bdd, variables));

        bdd.variableOrder().reorderToIdentity();

        assertTrue(bdd.check());
        assertTrue(mtbdd.check());
        assertEquals(identityOrder(variables), currentOrder(bdd, variables));
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables));
        }
        assertEquals(mtBefore, valueTable(mtbdd, mt, variables), "the companion MTBDD did not follow");
    }

    @Test
    void testReorderToIdentityGoesBackToTheImplicitOrder() {
        /* Landing on the identity is not just a permutation that happens to be sorted - the order stops
         * being stored at all, and the enumeration fast path comes back with it. The statistic is how a
         * caller sees that happen. */
        int variables = 5;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        randomFunctions(bdd, variables, 8, 271_828L);

        assertEquals("0", bdd.statistics().get("reorder_identity_reverts"), "nothing has reordered yet");

        context.variableOrder().siftDown(1);
        context.variableOrder().siftDown(3);
        bdd.variableOrder().reorderToIdentity();

        assertEquals(identityOrder(variables), currentOrder(bdd, variables));
        assertEquals("1", bdd.statistics().get("reorder_identity_reverts"));

        // Already implicit, so there is nothing to permute and nothing to revert.
        bdd.variableOrder().reorderToIdentity();
        assertEquals("1", bdd.statistics().get("reorder_identity_reverts"));
    }

    @Test
    void testCreateVariablesAtLevelMatchesRepeatedSingleInsertion() {
        /* The block form only earns its keep by moving the tail once; it has to leave exactly the order
         * the one-at-a-time form does, so build both and compare. */
        int variables = 5;
        for (int level = 0; level <= variables; level++) {
            DdContextImpl blockwiseContext = new DdContextImpl(CONFIG);
            BddImpl blockwise = blockwiseContext.bdd();
            blockwise.createVariables(variables);
            int[] inserted = blockwiseContext.createVariablesAtLevel(level, 3);

            DdContextImpl oneByOneContext = new DdContextImpl(CONFIG);
            BddImpl oneByOne = oneByOneContext.bdd();
            oneByOne.createVariables(variables);
            for (int index = 0; index < 3; index++) {
                oneByOneContext.createVariableAtLevel(level + index);
            }

            assertEquals(
                    currentOrder(oneByOne, variables + 3),
                    currentOrder(blockwise, variables + 3),
                    "inserting a block at level " + level + " differs from inserting one at a time");
            assertEquals(3, inserted.length);
            for (int index = 0; index < 3; index++) {
                assertEquals(level + index, blockwise.levelOfVariable(blockwise.decisionVariable(inserted[index])));
            }
            assertTrue(blockwise.check());
        }
    }

    @Test
    void testCreateVariablesAtLevelKeepsFunctionsAndTheCompanion() {
        int variables = 5;
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        MtBddImpl mtbdd = context.mtBdd();
        List<Integer> functions = randomFunctions(bdd, variables, 8, 161_803L);

        int mt = mtbdd.reference(mtbdd.ifThenElse(functions.get(1), mtbdd.of(9), mtbdd.of(4)));
        List<List<Boolean>> before = new ArrayList<>();
        for (int function : functions) {
            before.add(truthTable(bdd, function, variables));
        }
        List<Integer> mtBefore = valueTable(mtbdd, mt, variables);

        int[] inserted = context.createVariablesAtLevel(2, 4);

        assertTrue(bdd.check());
        assertTrue(mtbdd.check());
        assertEquals(variables + 4, bdd.numberOfVariables());
        for (int index = 0; index < inserted.length; index++) {
            assertEquals(2 + index, bdd.levelOfVariable(bdd.decisionVariable(inserted[index])));
        }
        // The new variables are unconstrained, so the old functions are unchanged over the old ones.
        for (int i = 0; i < functions.size(); i++) {
            assertEquals(before.get(i), truthTable(bdd, functions.get(i), variables));
        }
        assertEquals(mtBefore, valueTable(mtbdd, mt, variables), "the companion MTBDD did not follow");
    }

    @Test
    void testCreateVariablesAtTheBottomKeepsTheOrderImplicit() {
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        bdd.createVariables(3);

        context.createVariablesAtLevel(3, 2);
        assertFalse(
                context.variableOrder().isExplicitOrder(), "appending a block at the bottom made the order explicit");
        assertEquals(identityOrder(5), currentOrder(bdd, 5));
    }

    @Test
    void testCreateVariableAtTheBottomKeepsTheOrderImplicit() {
        // Appending is what createVariable does, so it must not force the order to be materialised.
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        bdd.createVariables(4);

        context.createVariableAtLevel(4);
        assertFalse(context.variableOrder().isExplicitOrder(), "appending at the bottom made the order explicit");

        context.createVariableAtLevel(2);
        assertTrue(context.variableOrder().isExplicitOrder());
        assertEquals(2, bdd.levelOfVariable(5));
        assertEquals(List.of(0, 1, 5, 2, 3, 4), currentOrder(bdd, 6));

        bdd.variableOrder().reorderToIdentity();
        assertFalse(context.variableOrder().isExplicitOrder());
        assertEquals(identityOrder(6), currentOrder(bdd, 6));
    }

    @Test
    void testContextStatisticsCarryBothDiagramsAndTheOrder() {
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        randomFunctions(bdd, 6, 8, 991_337L);
        bdd.variableOrder().reorder();

        Map<String, Object> statistics = context.statistics();
        // The BDD's table, the MTBDD's table, and the order the two share.
        assertTrue(
                statistics.containsKey("bdd_node_table_size"),
                statistics.keySet().toString());
        assertTrue(
                statistics.containsKey("mtbdd_node_table_size"),
                statistics.keySet().toString());
        assertEquals(bdd.statistics().get("reorder_swaps"), statistics.get("reorder_swaps"));

        // Nothing either diagram reports on its own may be dropped on the way.
        assertTrue(statistics.entrySet().containsAll(bdd.statistics().entrySet()));
        assertTrue(
                statistics.entrySet().containsAll(context.mtBdd().statistics().entrySet()));
    }

    /** Records every order change it is told about, so a test can count them and read their content. */
    private static final class OrderRecorder implements VariableOrderObserver {
        private final List<BitSet> moved = new ArrayList<>();
        private final List<int[]> previous = new ArrayList<>();
        private final List<int[]> current = new ArrayList<>();

        @Override
        public void orderChanged(int[] previousVariableToLevel, int[] currentVariableToLevel, BitSet movedVariables) {
            moved.add((BitSet) movedVariables.clone());
            previous.add(previousVariableToLevel.clone());
            current.add(currentVariableToLevel.clone());
        }
    }

    @Test
    void testReorderingNotifiesOnceForHoweverManySwapsItTook() {
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        randomFunctions(bdd, 8, 10, 27_644_437L);

        OrderRecorder recorder = new OrderRecorder();
        bdd.variableOrder().registerOwnedObserver(recorder);

        bdd.variableOrder().reorder();
        assertEquals(1, recorder.moved.size(), "a sifting pass is one order change, not one per swap");
        long swaps = Long.parseLong((String) bdd.statistics().get("reorder_swaps"));
        assertTrue(swaps > 1, "the pass should have made several swaps");
        assertEquals("1", bdd.statistics().get("reorder_notifications"));

        // What the listener was told has to be the order before and the order after, in full.
        int[] previous = recorder.previous.get(0);
        int[] current = recorder.current.get(0);
        BitSet moved = recorder.moved.get(0);
        for (int variable = 0; variable < bdd.numberOfVariables(); variable++) {
            assertEquals(bdd.levelOfVariable(variable), current[variable]);
            assertEquals(previous[variable] != current[variable], moved.get(variable));
        }
        assertFalse(moved.isEmpty(), "the pass changed the order, so something moved");

        // A caller driving swaps itself still hears about each call.
        BitSet swapped = BitSets.of(bdd.variableAtLevel(0), bdd.variableAtLevel(1));
        context.variableOrder().siftDown(0);
        assertEquals(2, recorder.moved.size());
        assertEquals(swapped, recorder.moved.get(1));
    }

    @Test
    void testAnOrderChangeThatMovesNothingIsNotReported() {
        DdContextImpl context = new DdContextImpl(CONFIG);
        BddImpl bdd = context.bdd();
        randomFunctions(bdd, 6, 8, 15_485_863L);
        bdd.variableOrder().reorderTo(List.of(block(1, 3)));

        OrderRecorder recorder = new OrderRecorder();
        bdd.variableOrder().registerOwnedObserver(recorder);

        // The request is already satisfied, so nothing moves and there is nothing to say.
        Object before = bdd.statistics().get("reorder_notifications");
        bdd.variableOrder().reorderTo(List.of(block(1, 3)));
        assertTrue(recorder.moved.isEmpty());
        assertEquals(before, bdd.statistics().get("reorder_notifications"));
    }
}
