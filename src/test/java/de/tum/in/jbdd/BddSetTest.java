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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tum.in.jbdd.collections.Cube;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
        NatSet support = NatSetFixtures.range(0, 3);

        BddSet set = x0.intersection(x1).union(x0.complement().intersection(x2));

        // Fixing a variable drops it from the support and agrees with the set wherever it held.
        BddSet fixedTrue = set.restrict(Cube.of(NatSetFixtures.of(0), NatSetFixtures.of(0)));
        assertFalse(fixedTrue.support().contains(0));
        assertEquals(x1.restrict(Cube.of(NatSetFixtures.of(0), NatSetFixtures.of(0))), fixedTrue);
        assertEquals(set.intersection(x0), fixedTrue.intersection(x0));

        BddSet fixedFalse = set.restrict(Cube.of(NatSetFixtures.of(), NatSetFixtures.of(0)));
        assertEquals(set.intersection(x0.complement()), fixedFalse.intersection(x0.complement()));

        // Restricting everything leaves a constant that says whether the valuation is an element.
        for (NatSet valuation : List.of(NatSetFixtures.of(0, 1), NatSetFixtures.of(2), NatSetFixtures.of(0))) {
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
        NatSet quantified = NatSetFixtures.of(1);

        // x0 & x1 | x2 holds for both values of x1 exactly where x2 holds.
        assertEquals(x2, set.forall(quantified));
        assertEquals(set.complement().exists(quantified).complement(), set.forall(quantified));
    }

    @Test
    void testPinnedSetSurvivesItsReferences() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactoryImpl sets = (BddSetFactoryImpl) ctx.bddSets();
        Bdd bdd = ctx.bdd();
        // A complement, so the pin has to reach the node through the complement edge.
        BddSet set = sets.var(0).intersection(sets.var(1)).complement();
        int function = sets.functionOf(set);

        assertFalse(bdd.isUnmanaged(function));
        sets.pin(set);
        sets.pin(set);
        sets.pin(sets.universe());
        assertTrue(bdd.isUnmanaged(function));

        // What the reference manager does once the object is collected: nothing, for a pinned node.
        bdd.dereference(function);
        bdd.gc();
        assertTrue(bdd.isValidFunction(function));
        assertTrue(bdd.isUnmanaged(function));
        assertEquals(
                function, sets.functionOf(sets.var(0).intersection(sets.var(1)).complement()));
    }

    @Test
    void testAttachmentIsBuiltOncePerFunction() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        List<BddSet> built = new ArrayList<>();
        Attachment<BddSet, List<BddSet>> attachment = ctx.attachToSets(set -> {
            built.add(set);
            return List.of(set);
        });

        BddSet set = sets.var(0).intersection(sets.var(1));
        List<BddSet> attached = attachment.of(set);
        assertEquals(List.of(set), attached);
        // The same function, reached another way, is the same set and so carries the same object.
        assertSame(attached, attachment.of(sets.var(1).intersection(sets.var(0))));
        assertSame(attached, attachment.of(set.complement().complement()));
        assertEquals(List.of(set), built);

        // The constants exist before the attachment does, and get theirs on request like any other set.
        assertEquals(List.of(sets.universe()), attachment.of(sets.universe()));
        assertEquals(2, built.size());

        assertThrows(IllegalStateException.class, () -> ctx.attachToSets(other -> List.of()));
    }

    @Test
    void testBddSetOfIgnoresValuationOutsideSupport() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x2 = sets.var(2);

        // Only the support is fixed; what the valuation says about x1 and x3 is not part of the set.
        BddSet cube = sets.of(Cube.of(NatSetFixtures.of(0, 1, 3), NatSetFixtures.of(0, 2)));
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
                sets.of(Cube.of(NatSetFixtures.of(0), NatSetFixtures.of(0, 1)))
                        .union(sets.of(Cube.of(NatSetFixtures.of(1), NatSetFixtures.of(0, 1)))),
                sets.of(List.of(NatSetFixtures.of(0), NatSetFixtures.of(1)), NatSetFixtures.of(0, 1)));
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

        // x3 implies both cofactors of x0, so it comes out once and without x0; the resolvent x1 & x2 does not.
        BddSet x2 = sets.var(2);
        BddSet x3 = sets.var(3);
        assertEquals(
                Set.of(
                        Cube.literal(0, true).with(1, true),
                        Cube.literal(0, false).with(2, true),
                        Cube.literal(3, true)),
                Set.copyOf(x0.intersection(x1)
                        .union(x0.complement().intersection(x2))
                        .union(x3)
                        .implicants()));
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
                    set = set.union(sets.of(Cube.of(
                            NatSetFixtures.of(bits(valuation, variables)), NatSetFixtures.range(0, variables))));
                }
            }

            BddSet function = set;
            List<Cube> implicants = function.implicants();

            for (int valuation = 0; valuation < valuations; valuation++) {
                NatSet bitSet = NatSetFixtures.of(bits(valuation, variables));
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

    @Test
    void testPrimeImplicantsOfSmallExamples() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        BddSet x0 = sets.var(0);
        BddSet x1 = sets.var(1);
        BddSet x2 = sets.var(2);

        assertEquals(List.of(Cube.empty()), sets.universe().primeImplicants());
        assertEquals(List.of(), sets.empty().primeImplicants());

        // The consensus x1 & x2 is prime but in no cover implicants() builds from the diagram.
        BddSet function = x0.intersection(x1).union(x0.complement().intersection(x2));
        assertEquals(
                Set.of(
                        Cube.literal(0, true).with(1, true),
                        Cube.literal(0, false).with(2, true),
                        Cube.literal(1, true).with(2, true)),
                Set.copyOf(function.primeImplicants()));
        assertEquals(2, function.implicants().size());
    }

    @Test
    void testPrimeImplicantsOfEveryFunctionOfThreeVariables() {
        int variables = 3;
        int valuations = 1 << variables;

        for (int truthTable = 0; truthTable < (1 << valuations); truthTable++) {
            BinaryFactoryContext ctx = BinaryFactoryContext.create();
            BddSetFactory sets = ctx.bddSets();
            BddSet function = sets.empty();
            for (int valuation = 0; valuation < valuations; valuation++) {
                if ((truthTable & (1 << valuation)) != 0) {
                    function = function.union(sets.of(Cube.of(
                            NatSetFixtures.of(bits(valuation, variables)), NatSetFixtures.range(0, variables))));
                }
            }

            // By brute force: the implicants among all cubes, and of those the ones no shorter implicant implies.
            List<Cube> implicants = new ArrayList<>();
            for (int support = 0; support < valuations; support++) {
                for (int assignment = 0; assignment < valuations; assignment++) {
                    if ((assignment & ~support) == 0) {
                        Cube cube = Cube.of(
                                NatSetFixtures.of(bits(assignment, variables)),
                                NatSetFixtures.of(bits(support, variables)));
                        if (sets.of(cube.copy()).subsetOf(function)) {
                            implicants.add(cube);
                        }
                    }
                }
            }
            Set<Cube> expected = new HashSet<>();
            for (Cube cube : implicants) {
                if (implicants.stream().noneMatch(other -> !other.equals(cube) && cube.implies(other))) {
                    expected.add(cube);
                }
            }

            List<Cube> primes = function.primeImplicants();
            BddSet printed = function;
            assertEquals(expected, Set.copyOf(primes), () -> "primes of " + printed);
            assertEquals(primes.size(), Set.copyOf(primes).size(), () -> "duplicates in " + primes);
        }
    }

    /** A propositional expression of a type JBDD does not know, read through {@link #STRUCTURE}. */
    private static final class Expression {
        final ExpressionStructure.Kind kind;
        final int variable;
        final List<Expression> operands;

        Expression(ExpressionStructure.Kind kind, int variable, Expression... operands) {
            this.kind = kind;
            this.variable = variable;
            this.operands = List.of(operands);
        }

        static Expression of(ExpressionStructure.Kind kind, Expression... operands) {
            return new Expression(kind, -1, operands);
        }

        static Expression var(int variable) {
            return new Expression(ExpressionStructure.Kind.VARIABLE, variable);
        }
    }

    private static final ExpressionStructure<Expression> STRUCTURE = new ExpressionStructure<>() {
        @Override
        public Kind kind(Expression expression) {
            return expression.kind;
        }

        @Override
        public int variable(Expression expression) {
            return expression.variable;
        }

        @Override
        public int arity(Expression expression) {
            return expression.operands.size();
        }

        @Override
        public Expression operand(Expression expression, int index) {
            return expression.operands.get(index);
        }
    };

    @Test
    void testExpressionsBuildTheirSets() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Expression x0 = Expression.var(0);
        Expression x1 = Expression.var(1);
        Expression x2 = Expression.var(2);

        // (x0 & !x1) | (x0 <-> x2) | (x1 ^ x2), sharing x0 and building x3 - which does not exist yet - on the way.
        Expression expression = Expression.of(
                ExpressionStructure.Kind.OR,
                Expression.of(ExpressionStructure.Kind.AND, x0, Expression.of(ExpressionStructure.Kind.NOT, x1)),
                Expression.of(ExpressionStructure.Kind.IFF, x0, x2),
                Expression.of(ExpressionStructure.Kind.XOR, x1, x2),
                Expression.of(ExpressionStructure.Kind.AND, Expression.var(3), x0));
        BddSet expected = sets.var(0)
                .intersection(sets.var(1).complement())
                .union(sets.var(0).symmetricDifference(sets.var(2)).complement())
                .union(sets.var(1).symmetricDifference(sets.var(2)))
                .union(sets.var(3).intersection(sets.var(0)));
        assertEquals(expected, sets.of(expression, STRUCTURE));

        assertEquals(sets.universe(), sets.of(Expression.of(ExpressionStructure.Kind.AND), STRUCTURE));
        assertEquals(sets.empty(), sets.of(Expression.of(ExpressionStructure.Kind.OR), STRUCTURE));
    }

    @Test
    void testExpressionsStopAtTheAbsorbingOperandAndUseKnownSets() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Expression unreadable = Expression.var(-1);
        Expression known = Expression.var(-2);
        BddSet knownSet = sets.var(4).union(sets.var(5));
        ExpressionStructure<Expression> structure = new ExpressionStructure<>() {
            @Override
            public Kind kind(Expression expression) {
                assertNotSame(unreadable, expression, "read past the absorbing operand");
                assertNotSame(known, expression, "built a known expression");
                return expression.kind;
            }

            @Override
            public int variable(Expression expression) {
                return expression.variable;
            }

            @Override
            public int arity(Expression expression) {
                return expression.operands.size();
            }

            @Override
            public Expression operand(Expression expression, int index) {
                return expression.operands.get(index);
            }

            @Override
            public @Nullable BddSet known(Expression expression) {
                return expression == known ? knownSet : null; // NOPMD - identity is the point
            }
        };

        Expression falseExpression = Expression.of(ExpressionStructure.Kind.FALSE);
        assertEquals(
                sets.empty(),
                sets.of(
                        Expression.of(ExpressionStructure.Kind.AND, Expression.var(0), falseExpression, unreadable),
                        structure));
        assertEquals(
                knownSet.intersection(sets.var(0)),
                sets.of(Expression.of(ExpressionStructure.Kind.AND, known, Expression.var(0)), structure));
    }

    @Test
    void testAdoptFromAnotherContext() {
        BddSetFactory source = BinaryFactoryContext.create().bddSets();
        BddSet set = source.var(0)
                .intersection(source.var(1))
                .union(source.var(0).complement().intersection(source.var(3)))
                .union(source.var(2));

        // The target has no variables yet: adopt creates them, in both an order-preserving and a reversing mapping.
        BddSetFactory target = BinaryFactoryContext.create().bddSets();
        BddSet spread = target.adopt(set, variable -> 2 * variable + 1);
        BddSet reversed = target.adopt(set, variable -> 3 - variable);

        assertEquals(
                target.var(1)
                        .intersection(target.var(3))
                        .union(target.var(1).complement().intersection(target.var(7)))
                        .union(target.var(5)),
                spread);
        assertEquals(
                target.var(3)
                        .intersection(target.var(2))
                        .union(target.var(3).complement().intersection(target.var(0)))
                        .union(target.var(1)),
                reversed);
        assertSame(set, source.adopt(reversed, variable -> 3 - variable));
        assertSame(set, source.adopt(target.adopt(set)));
        assertThrows(IllegalArgumentException.class, () -> target.adopt(set, variable -> -1));
    }

    private static List<BddSet> rebuild(BddSetFactory sets, Dag<Boolean> dag) {
        List<BddSet> entries = new ArrayList<>(dag.size());
        for (int entry = 0; entry < dag.size(); entry++) {
            switch (dag.kind(entry)) {
                case VALUE:
                    entries.add(sets.of(dag.value(entry)));
                    break;
                case DECISION:
                    assertTrue(dag.high(entry) < entry && dag.low(entry) < entry);
                    entries.add(sets.ifThenElse(
                            sets.var(dag.variable(entry)), entries.get(dag.high(entry)), entries.get(dag.low(entry))));
                    break;
                case COMPLEMENT:
                    assertTrue(dag.complementOf(entry) < entry);
                    entries.add(entries.get(dag.complementOf(entry)).complement());
                    break;
            }
        }
        return entries;
    }

    @Test
    void testDagOfEveryFunctionOfThreeVariables() {
        int variables = 3;
        int valuations = 1 << variables;
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();

        for (int truthTable = 0; truthTable < (1 << valuations); truthTable++) {
            BddSet function = sets.empty();
            for (int valuation = 0; valuation < valuations; valuation++) {
                if ((truthTable & (1 << valuation)) != 0) {
                    function = function.union(sets.of(Cube.of(
                            NatSetFixtures.of(bits(valuation, variables)), NatSetFixtures.range(0, variables))));
                }
            }

            // One entry per distinct function, and folding it back gives the set.
            Dag<Boolean> dag = function.dag();
            List<BddSet> entries = rebuild(sets, dag);
            assertEquals(1, dag.numberOfRoots());
            assertEquals(function, entries.get(dag.root(0)));
            assertEquals(dag.size(), new HashSet<>(entries).size());

            // With its complement as a second root, shared: the complement is one more entry, and sharing inside the
            // function can only save entries (a complemented subfunction's children are not visited).
            Dag<Boolean> shared = sets.dag(List.of(function, function.complement()), true);
            List<BddSet> sharedEntries = rebuild(sets, shared);
            assertEquals(function, sharedEntries.get(shared.root(0)));
            assertEquals(function.complement(), sharedEntries.get(shared.root(1)));
            if (!function.isEmpty() && !function.isUniverse()) {
                assertEquals(Dag.Kind.COMPLEMENT, shared.kind(shared.root(1)));
                assertEquals(shared.root(0), shared.complementOf(shared.root(1)));
                assertTrue(shared.size() <= dag.size() + 1);
            }
        }
    }

    @Test
    void testDagOrderIsHighFirstPostOrder() {
        BinaryFactoryContext ctx = BinaryFactoryContext.create();
        BddSetFactory sets = ctx.bddSets();
        Dag<Boolean> dag = sets.var(0).intersection(sets.var(1)).dag();

        // x0 ? (x1 ? true : false) : false - the high branch first, each child before its parent.
        assertEquals(4, dag.size());
        assertEquals(true, dag.value(0));
        assertEquals(false, dag.value(1));
        assertEquals(1, dag.variable(2));
        assertEquals(0, dag.variable(3));
        assertEquals(List.of(2, 1), List.of(dag.high(3), dag.low(3)));
        assertEquals(3, dag.root(0));
        assertThrows(IllegalArgumentException.class, () -> dag.variable(0));
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
                set.split(NatSetFixtures.of(0, 1), residuals).inverse());

        // A split variable below one that stays: the residuals keep x0, the preimages are over x1 alone.
        BddSet interleaved = sets.ifThenElse(x0, x1, x2);
        assertEquals(
                Map.of(x0.union(x2), x1, x0.complement().intersection(x2), x1.complement()),
                interleaved.split(NatSetFixtures.of(1), residuals).inverse());

        // Assignments leading to the same residual along different paths end up in one preimage.
        BddSet merging =
                sets.ifThenElse(x0, x1.intersection(x2), x1.complement().intersection(x2));
        assertEquals(
                Map.of(
                        x2,
                        x0.intersection(x1).union(x0.complement().intersection(x1.complement())),
                        sets.empty(),
                        x0.intersection(x1.complement()).union(x0.complement().intersection(x1))),
                merging.split(NatSetFixtures.of(0, 1), residuals).inverse());

        // Nothing to split on, or nothing to split: one residual, reached by everything.
        assertEquals(
                Map.of(set, sets.universe()),
                set.split(NatSetFixtures.of(), residuals).inverse());
        assertEquals(
                Map.of(interleaved, sets.universe()),
                interleaved.split(NatSetFixtures.of(3), residuals).inverse());
        assertEquals(
                Map.of(sets.empty(), sets.universe()),
                sets.empty().split(NatSetFixtures.of(0), residuals).inverse());
        assertEquals(
                Map.of(x0, x1, x0.complement(), x1.complement()),
                sets.ifThenElse(x1, x0, x0.complement())
                        .split(NatSetFixtures.of(1), residuals)
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
                ctx.variableOrder().reorderTo(List.of(NatSetFixtures.of(6, 4, 2), NatSetFixtures.of(0, 1)));
            }
            Values<BddSet> residuals = ctx.bddMaps().create();
            for (BddSet function : functions) {
                MutableNatSet splitVariables = MutableNatSet.create();
                for (int variable = 0; variable < variables; variable++) {
                    if (random.nextBoolean()) {
                        splitVariables.set(variable);
                    }
                }
                BddMap<BddSet> split = function.split(splitVariables, residuals);
                MutableNatSet outside = NatSetFixtures.copyOf(split.support());
                outside.andNot(splitVariables);
                assertTrue(outside.isEmpty(), "the map decides only split variables");
                // The int layer's pieces, referenced before any further allocating call.
                Bdd bdd = ctx.bdd();
                MtBdd mtBdd = ctx.mtBdd();
                int raw = ((GcReferenceManager.DdContainer) function).function();
                MultiTerminalDecisionDiagram.FunctionToFunctionMap pieces = mtBdd.splitBdd(raw, splitVariables);
                int meta = mtBdd.reference(pieces.function());
                int[] residualFunctions = pieces.codomain()
                        .intStream()
                        .map(index -> bdd.reference(pieces.functionFor(index)))
                        .toArray();
                for (Iterator<NatSet> assignments = NatSetFixtures.powerSetIterator(splitVariables);
                        assignments.hasNext(); ) {
                    NatSet assignment = assignments.next();
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
