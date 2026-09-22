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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BddSetTest {
    @Test
    void testBddSetConstrainAndSimplify() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        BddSet x2 = sets.var(2);

        BddSet set = x0.intersection(x1).union(x0.complement().intersection(x2));
        BddSet domain = x0;

        for (BddSet reduced : List.of(set.constrain(domain), set.simplify(domain))) {
            // The contract is agreement on the domain and nothing else, so that is all that is checked.
            assertEquals(set.intersection(domain), reduced.intersection(domain));
        }

        assertEquals(set, set.constrain(sets.universe()));
        assertEquals(set, set.simplify(sets.universe()));
        assertTrue(set.simplify(x0.complement()).intersection(x0.complement()).subsetOf(set));
    }

    @Test
    void testBddSetRestrictAndIfThenElse() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        BddSet x2 = sets.var(2);
        BitSet support = BitSets.range(0, 3);

        BddSet set = x0.intersection(x1).union(x0.complement().intersection(x2));

        // Fixing a variable drops it from the support and agrees with the set wherever it held.
        BddSet fixedTrue = set.restrict(Cube.of(BitSets.of(0), BitSets.of(0)));
        assertFalse(fixedTrue.support().get(0));
        assertEquals(x1.restrict(Cube.of(BitSets.of(0), BitSets.of(0))), fixedTrue);
        assertEquals(set.intersection(x0), fixedTrue.intersection(x0));

        BddSet fixedFalse = set.restrict(Cube.of(BitSets.of(), BitSets.of(0)));
        assertEquals(set.intersection(x0.complement()), fixedFalse.intersection(x0.complement()));

        // Restricting everything leaves a constant that says whether the valuation is an element.
        for (BitSet valuation : List.of(BitSets.of(0, 1), BitSets.of(2), BitSets.of(0))) {
            BddSet point = set.restrict(Cube.of(valuation, support));
            assertEquals(set.contains(valuation), point.isUniverse());
        }

        // if-then-else agrees with the union of the two intersections it stands for.
        assertEquals(x0.intersection(x1).union(x0.complement().intersection(x2)), sets.ifThenElse(x0, x1, x2));
        assertEquals(set, sets.ifThenElse(x0, x1, x2));
        assertEquals(x1, sets.ifThenElse(sets.universe(), x1, x2));
        assertEquals(x2, sets.ifThenElse(sets.empty(), x1, x2));
    }

    @Test
    void testBddSetForallIsDualToExists() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        BddSet x2 = sets.var(2);
        BddSet set = x0.intersection(x1).union(x2);
        BitSet quantified = BitSets.of(1);

        // x0 & x1 | x2 holds for both values of x1 exactly where x2 holds.
        assertEquals(x2, set.forall(quantified));
        assertEquals(set.complement().exists(quantified).complement(), set.forall(quantified));
    }

    @Test
    void testBddSetOfIgnoresValuationOutsideSupport() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x2 = sets.var(2);

        // Only the support is fixed; what the valuation says about x1 and x3 is not part of the set.
        BddSet cube = sets.of(Cube.of(BitSets.of(0, 1, 3), BitSets.of(0, 2)));
        assertEquals(x0.intersection(x2.complement()), cube);
    }

    @Test
    void testBddSetUnionOfCubes() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Cube x0 = Cube.literal(0, true);
        Cube notX1AndX3 = Cube.literal(1, false).with(3, true);

        assertEquals(sets.empty(), sets.union(List.<Cube>of()));
        assertEquals(sets.of(x0).union(sets.of(notX1AndX3)), sets.union(List.of(x0, notX1AndX3)));
        // Stops at the universe, and creates the variables it names.
        assertEquals(sets.universe(), sets.union(List.of(Cube.empty(), Cube.literal(7, true))));
        assertEquals(
                sets.of(Cube.of(BitSets.of(0), BitSets.of(0, 1)))
                        .union(sets.of(Cube.of(BitSets.of(1), BitSets.of(0, 1)))),
                sets.of(List.of(BitSets.of(0), BitSets.of(1)), BitSets.of(0, 1)));
    }

    @Test
    void testBddSetPaths() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);

        // Each path is copied, since the walk hands out its own working state.
        Set<BddSet> cubes = new HashSet<>();
        BddSet set = x0.union(x1);
        set.forEachPath(path -> cubes.add(sets.of(path.copy())));
        assertEquals(Set.of(x0, x0.complement().intersection(x1)), cubes);

        int[] visited = {0};
        assertTrue(set.anyPathMatches(path -> {
            visited[0] += 1;
            return true;
        }));
        assertEquals(1, visited[0]);
        assertFalse(set.anyPathMatches(path -> false));
        assertFalse(sets.empty().anyPathMatches(path -> true));
    }

    @Test
    void testBddSetNaryOperations() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        BddSet x2 = sets.var(2);

        assertEquals(sets.universe(), sets.intersection());
        assertEquals(sets.empty(), sets.union());
        assertEquals(x1, sets.intersection(x1));
        assertEquals(x0.intersection(x1).intersection(x2), sets.intersection(x0, x1, x2));
        assertEquals(x0.union(x1).union(x2), sets.union(x0, x1, x2));
        assertEquals(sets.empty(), sets.intersection(x0, x0.complement(), x2));
        assertEquals(sets.universe(), sets.union(x0, x0.complement(), x2));
        assertEquals(sets.empty(), sets.intersection(x2, x1.union(x2), sets.empty(), x0));
        assertEquals(sets.universe(), sets.union(x2, x1.intersection(x2), sets.universe(), x0));
        assertEquals(x0.intersection(x2.complement()), sets.intersection(x2.complement(), sets.universe(), x0));
        assertEquals(x0.complement().union(x2), sets.union(x2, sets.empty(), x0.complement()));
    }

    @Test
    void testImplicantsOfSmallExamples() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);

        assertEquals(List.of(Cube.empty()), sets.universe().implicants());
        assertEquals(List.of(), sets.empty().implicants());
        assertEquals(List.of(Cube.literal(0, true)), x0.implicants());

        // The two documented examples: a monotone union keeps one literal per cube, xor keeps both.
        assertEquals(
                Set.of(Cube.literal(0, true), Cube.literal(1, true)),
                Set.copyOf(x0.union(x1).implicants()));
        assertEquals(
                Set.of(
                        Cube.literal(0, true).with(1, false),
                        Cube.literal(0, false).with(1, true)),
                Set.copyOf(x0.symmetricDifference(x1).implicants()));
    }

    @Test
    void testImplicantsCoverEveryFunctionOfThreeVariables() {
        int variables = 3;
        int valuations = 1 << variables;

        for (int truthTable = 0; truthTable < (1 << valuations); truthTable++) {
            BinaryFactoryContext ctx = BinaryFactoryContext.create();
            BddSetFactory sets = ctx.bddSets();
            BddSet set = sets.empty();
            for (int valuation = 0; valuation < valuations; valuation++) {
                if ((truthTable & (1 << valuation)) != 0) {
                    set = set.union(
                            sets.of(Cube.of(BitSets.of(bits(valuation, variables)), BitSets.range(0, variables))));
                }
            }

            BddSet function = set;
            List<Cube> implicants = function.implicants();

            for (int valuation = 0; valuation < valuations; valuation++) {
                BitSet bitSet = BitSets.of(bits(valuation, variables));
                boolean covered = implicants.stream().anyMatch(cube -> cube.contains(bitSet));
                assertEquals(function.contains(bitSet), covered, () -> "valuation " + bitSet + " of " + function);
            }

            // Antichain: no cube's literals are a superset of another's.
            for (Cube a : implicants) {
                for (Cube b : implicants) {
                    if (a != b) { // NOPMD - identity is the point
                        assertFalse(b.implies(a), () -> a + " subsumes " + b);
                    }
                }
            }

            // Complementing gives a CNF cover of the same function.
            for (Cube clause : function.complement().implicants()) {
                assertFalse(function.intersects(sets.of(clause.copy())), clause::toString);
            }
        }
    }

    private static int[] bits(int valuation, int variables) {
        return java.util.stream.IntStream.range(0, variables)
                .filter(i -> (valuation & (1 << i)) != 0)
                .toArray();
    }

    @Test
    void testBddSetSplit() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        BddSet x2 = sets.var(2);
        BddSet x3 = sets.var(3);
        Values<BddSet> residuals = ctx.bddMaps().create();

        // Split variables on top: x0 | x1 leads to x2, everything else to x3.
        BddSet set = sets.ifThenElse(x0.union(x1), x2, x3);
        assertEquals(
                Map.of(x2, x0.union(x1), x3, x0.union(x1).complement()),
                set.split(BitSets.of(0, 1), residuals).inverse());

        // A split variable below one that stays: the residuals keep x0, the preimages are over x1 alone.
        BddSet interleaved = sets.ifThenElse(x0, x1, x2);
        assertEquals(
                Map.of(x0.union(x2), x1, x0.complement().intersection(x2), x1.complement()),
                interleaved.split(BitSets.of(1), residuals).inverse());

        // Assignments leading to the same residual along different paths end up in one preimage.
        BddSet merging =
                sets.ifThenElse(x0, x1.intersection(x2), x1.complement().intersection(x2));
        assertEquals(
                Map.of(
                        x2,
                        x0.intersection(x1).union(x0.complement().intersection(x1.complement())),
                        sets.empty(),
                        x0.intersection(x1.complement()).union(x0.complement().intersection(x1))),
                merging.split(BitSets.of(0, 1), residuals).inverse());

        // Nothing to split on, or nothing to split: one residual, reached by everything.
        assertEquals(
                Map.of(set, sets.universe()), set.split(BitSets.of(), residuals).inverse());
        assertEquals(
                Map.of(interleaved, sets.universe()),
                interleaved.split(BitSets.of(3), residuals).inverse());
        assertEquals(
                Map.of(sets.empty(), sets.universe()),
                sets.empty().split(BitSets.of(0), residuals).inverse());
        assertEquals(
                Map.of(x0, x1, x0.complement(), x1.complement()),
                sets.ifThenElse(x1, x0, x0.complement())
                        .split(BitSets.of(1), residuals)
                        .inverse());
    }

    /** Every residual is the restriction it stands for, also under a reordered context. */
    @Test
    void testBddSetSplitAgreesWithRestriction() {
        Random random = new Random(42);
        for (boolean reorder : List.of(false, true)) {
            BinaryFactoryContext ctx = BinaryFactoryContext.create();
            BddSetFactory sets = ctx.bddSets();
            int variables = 7;
            List<BddSet> literals = new ArrayList<>();
            for (int variable = 0; variable < variables; variable++) {
                literals.add(sets.var(variable));
            }
            List<BddSet> functions = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                BddSet function = sets.empty();
                for (int clause = 0; clause < 4; clause++) {
                    BddSet term = sets.universe();
                    for (int literal = 0; literal < 3; literal++) {
                        BddSet variable = literals.get(random.nextInt(variables));
                        term = term.intersection(random.nextBoolean() ? variable : variable.complement());
                    }
                    function = function.union(term);
                }
                functions.add(function);
            }
            if (reorder) {
                ctx.variableOrder().reorderTo(List.of(BitSets.of(6, 4, 2), BitSets.of(0, 1)));
            }
            Values<BddSet> residuals = ctx.bddMaps().create();
            for (BddSet function : functions) {
                BitSet splitVariables = new BitSet();
                for (int variable = 0; variable < variables; variable++) {
                    if (random.nextBoolean()) {
                        splitVariables.set(variable);
                    }
                }
                BddMap<BddSet> split = function.split(splitVariables, residuals);
                BitSet outside = BitSets.copyOf(split.support());
                outside.andNot(splitVariables);
                assertTrue(outside.isEmpty(), "the map decides only split variables");
                // The int layer's pieces, referenced before any further allocating call.
                Bdd bdd = ctx.bdd();
                MtBdd mtBdd = ctx.mtBdd();
                int raw = ((GcReferenceManager.DdContainer) function).function();
                MultiTerminalDecisionDiagram.FunctionToFunctionMap pieces = mtBdd.splitBdd(raw, splitVariables);
                int meta = mtBdd.reference(pieces.function());
                int[] residualFunctions = pieces.codomain().stream()
                        .map(index -> bdd.reference(pieces.functionFor(index)))
                        .toArray();
                for (Iterator<BitSet> assignments = BitSets.powerSetIterator(splitVariables); assignments.hasNext(); ) {
                    BitSet assignment = assignments.next();
                    Cube restriction = Cube.of(assignment, splitVariables);
                    BddSet restricted = function.restrict(restriction);
                    assertEquals(restricted, split.evaluate(assignment));
                    int index = mtBdd.evaluate(meta, assignment);
                    assertEquals(((GcReferenceManager.DdContainer) restricted).function(), residualFunctions[index]);
                }
                mtBdd.dereference(meta);
                for (int residual : residualFunctions) {
                    bdd.dereference(residual);
                }
            }
        }
    }
}
