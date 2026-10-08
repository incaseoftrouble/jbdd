/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import de.tum.in.jbdd.Generator.BinaryDataPoint;
import de.tum.in.jbdd.Generator.Info;
import de.tum.in.jbdd.Generator.TernaryDataPoint;
import de.tum.in.jbdd.Generator.UnaryDataPoint;
import de.tum.in.jbdd.SyntaxTree.SyntaxTreeLiteral;
import de.tum.in.jbdd.SyntaxTree.SyntaxTreeNode;
import de.tum.in.jbdd.SyntaxTree.SyntaxTreeNot;
import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests various logical functions of BDDs and checks invariants.
 */
@SuppressWarnings({
    "AssignmentOrReturnOfFieldWithMutableType",
    "AccessingNonPublicFieldOfAnotherObject",
    "StaticCollection",
    "NewClassNamingConvention",
    "PMD.ClassNamingConventions",
    "PMD.CouplingBetweenObjects",
    "PMD.VariableDeclarationUsageDistance"
})
@TestInstance(Lifecycle.PER_CLASS)
@ExtendWith(FailFastExtension.class)
class BddTheories {
    private static final Comparator<NatSet> LEXICOGRAPHIC = new BitSetComparator();
    private static final Logger logger = Logger.getLogger(BddTheories.class.getName());

    private static final Map<BinaryDd, ExtendedInfo> infoMap = new LinkedHashMap<>();
    /* Scaled so that the expected number of checks, not their frequency, stays what it is at full scale:
     * the bound is a per-theory probability, so leaving it fixed would thin the checks out exactly in
     * proportion to a shrunk run. */
    private static final int SKIP_CHECK_RANDOM_BOUND = TestProfile.scaled(500);

    private static final double FACTOR = 0.25;
    private static final int binaryCount = TestProfile.scaled((int) (4_000 * FACTOR));
    private static final int ternaryCount = TestProfile.scaled((int) (3_000 * FACTOR));
    private static final int unaryCount = TestProfile.scaled((int) (1_000 * FACTOR));
    private static final int treeDepth = 20;
    private static final int treeWidth = 35;
    private static final int variableCount = 10;
    private static final int MAX_ASSIGNMENT_VARIABLES = 8;
    private static final int[] EMPTY_INTS = new int[0];
    private static final Iterable<boolean[]> valuations;
    private static final Collection<UnaryDataPoint<BinaryDd>> unary;
    private static final Collection<BinaryDataPoint<BinaryDd>> binary;
    private static final Collection<TernaryDataPoint<BinaryDd>> ternary;
    private final Random skipCheckRandom = new Random(0L);

    /* Stresses the variable order against everything the theories hold: a swap rewrites nodes in place,
     * so every data point's function id has to keep denoting the same function afterwards - which the next
     * theory, and doCheckInvariants, then verify. What matters is *having* a non-identity order, which is
     * what catches level/variable confusions; a handful of swaps gives that for a fraction of what a full
     * reorder() costs. reorder() itself is covered by ReorderTest, on diagrams small enough to be quick.
     *
     * Every so many theories, not at random: skipCheckRandom is a per-instance field and JUnit builds a
     * fresh instance per test, so drawing from it gives the same answer every time - "occasionally" would
     * come out as "always". Only the diagrams in reorderStressed are touched; see the static block. */
    /* Scaled, because this counts theories: left at 200, a sufficiently small run would finish before
     * ever reordering, which check() then reports as "never left the identity order" - a red build that
     * says nothing about the library. The floor is what makes that guarantee hold (any run of at least
     * that many theories reorders), and it is not lower because rounds past the first buy little on their
     * own: once the order is non-identity, every later theory runs against it either way. */
    private static final int REORDER_EVERY = TestProfile.scaled(200, 20);
    private static final int REORDER_SWAPS = 4;

    /* The swaps are cheap and are what the stress is for - they leave the theories running against a
     * non-identity order, which is what catches level/variable confusions. Verifying the node tables
     * afterwards is not cheap: doCheckInvariants walks every table in full, so doing it on each round
     * costs several times what the whole rest of the suite does. Every fifth round still pins a
     * corruption to within a few hundred theories, and checkInvariants samples in between. */
    private static final int REORDER_CHECK_EVERY = 5;
    private static final AtomicInteger THEORIES_RUN = new AtomicInteger();
    private static final Random reorderRandom = new Random(1L);

    /** The subset of {@link #infoMap}'s diagrams the stress hook reorders; the rest stay at the identity. */
    /* The contexts of the stressed diagrams, not the diagrams: siftDown is the context's, and one
     * context is one order - a BDD and its MTBDD move together. */
    private static final List<DdContextImpl> reorderStressed;

    static {
        /* The @DataPoints annotated methods are called multiple times - which would create
         * new variables each time, exploding the runtime of the tests. Hence, we create the
         * structure once. */

        /* Three variants per reorderable engine, because the three fail differently:
         *   - plain: never reordered, so level == variable throughout. The control - a failure here is an
         *     ordinary bug, a failure only in the other two is a level/variable confusion.
         *   - reordered: default config, so the reordering bookkeeping is rebuilt per reorder. Exercises
         *     that rebuild and the "reordered" fast-path switches.
         *   - reorderedKeeping: keeps the bookkeeping across operations, so its incremental maintenance in
         *     makeNode, rewriteNode and the collections is exercised, not only the one-off rebuild.
         * The MTBDD adapter shares its companion BDD's order, so reordering it reorders both; it is the
         * only thing that puts MTBDD enumeration, apply and compose under a non-identity order. MddImpl
         * does not implement ReorderableDd (MDDs do not reorder), so it has one variant. */
        DdContextImpl bddReorderedContext = new DdContextImpl(named("bdd-reordered", false));
        DdContextImpl bddReorderedKeepingContext = new DdContextImpl(named("bdd-reordered-keeping", true));
        DdContextImpl mtReorderedContext = new DdContextImpl(named("mtbdd-reordered", false));
        DdContextImpl mtReorderedKeepingContext = new DdContextImpl(named("mtbdd-reordered-keeping", true));

        BinaryDd bddPlain = new DdContextImpl(named("bdd", false)).bdd();
        BinaryDd bddReordered = bddReorderedContext.bdd();
        BinaryDd bddReorderedKeeping = bddReorderedKeepingContext.bdd();
        BinaryDd mdd = new MddAsBinaryDd(new MddImpl(named("mdd", false)));
        BinaryDd mtPlain = new MtBddAsBinaryDd(new DdContextImpl(named("mtbdd", false)).mtBdd());
        BinaryDd mtReordered = new MtBddAsBinaryDd(mtReorderedContext.mtBdd());
        BinaryDd mtReorderedKeeping = new MtBddAsBinaryDd(mtReorderedKeepingContext.mtBdd());

        List<BinaryDd> bdds =
                List.of(bddPlain, bddReordered, bddReorderedKeeping, mdd, mtPlain, mtReordered, mtReorderedKeeping);
        reorderStressed =
                List.of(bddReorderedContext, bddReorderedKeepingContext, mtReorderedContext, mtReorderedKeepingContext);

        int bddCount = bdds.size();
        List<Set<UnaryDataPoint<BinaryDd>>> unaryPoints = new ArrayList<>(bddCount);
        List<Set<BinaryDataPoint<BinaryDd>>> binaryPoints = new ArrayList<>(bddCount);
        List<Set<TernaryDataPoint<BinaryDd>>> ternaryPoints = new ArrayList<>(bddCount);

        for (BinaryDd bdd : bdds) {
            Info<BinaryDd> bddInfo =
                    Generator.fill(bdd, 0, variableCount, treeDepth, treeWidth, unaryCount, binaryCount, ternaryCount);
            ExtendedInfo extended = new ExtendedInfo(bdd, bddInfo);
            infoMap.put(bdd, extended);
            unaryPoints.add(bddInfo.unaryDataPoints);
            binaryPoints.add(bddInfo.binaryDataPoints);
            ternaryPoints.add(bddInfo.ternaryDataPoints);

            logger.log(
                    Level.INFO,
                    "Filled BDD {0}: {1} nodes ({2} referenced), {3} unary, {4} binary "
                            + "and {5} ternary data points",
                    new Object[] {
                        bdd,
                        extended.initialNodeCount,
                        extended.initialReferencedNodeCount,
                        bddInfo.unaryDataPoints.size(),
                        bddInfo.binaryDataPoints.size(),
                        bddInfo.ternaryDataPoints.size()
                    });
        }
        unary = unaryPoints.stream().flatMap(Collection::stream).collect(Collectors.toList());
        binary = binaryPoints.stream().flatMap(Collection::stream).collect(Collectors.toList());
        ternary = ternaryPoints.stream().flatMap(Collection::stream).collect(Collectors.toList());
        valuations = () -> new ScopedAssignments.SimplePowerSetIterator(variableCount);

        logger.log(Level.INFO, "Finished initialization");
    }

    /** A configuration whose {@link BddConfiguration#name()} labels the diagram in logs and failures. */
    private static BddConfiguration named(String name, boolean keepReorderingStructures) {
        return ImmutableBddConfiguration.builder()
                .name(name)
                .keepReorderingStructures(keepReorderingStructures)
                .build();
    }

    @SuppressWarnings("TypeMayBeWeakened")
    private static Set<Integer> doBddOperations(BinaryDd bdd, int function1, int function2) {
        List<Integer> function = new ArrayList<>();
        function.add(bdd.reference(bdd.and(function1, function2)));
        function.add(bdd.reference(bdd.or(function1, function2)));
        function.add(bdd.reference(bdd.xor(function1, function2)));
        function.add(bdd.reference(bdd.implication(function1, function2)));
        function.add(bdd.reference(bdd.equivalence(function1, function2)));
        function.add(bdd.reference(bdd.not(function1)));
        function.add(bdd.reference(bdd.not(function2)));
        function.forEach(bdd::dereference);
        return new HashSet<>(function);
    }

    private static void doCheckInvariants() {
        infoMap.keySet().forEach(BinaryDd::check);
    }

    private static Iterator<boolean[]> getArrayIterator(NatSet enabledVariables) {
        boolean[] base = new boolean[variableCount];
        enabledVariables.intStream().forEach(i -> base[i] = true);
        return new ScopedAssignments.PowerSetIterator(base);
    }

    private static Iterable<boolean[]> assignmentsOver(NatSet... variableSets) {
        return ScopedAssignments.of(variableCount, MAX_ASSIGNMENT_VARIABLES, variableSets);
    }

    private static NatSet asSet(boolean[] array) {
        MutableNatSet set = MutableNatSet.dense(array.length);
        for (int i = 0; i < array.length; i++) {
            if (array[i]) {
                set.set(i);
            }
        }
        return set;
    }

    static Stream<BinaryDataPoint<BinaryDd>> binary() {
        return binary.stream();
    }

    static Stream<TernaryDataPoint<BinaryDd>> ternary() {
        return ternary.stream();
    }

    static Stream<UnaryDataPoint<BinaryDd>> unary() {
        return unary.stream();
    }

    /** {@code assignment} re-indexed by the level each variable sits at; the identity unless reordered. */
    private static NatSet byLevel(BinaryDecisionDiagram bdd, NatSet assignment) {
        if (!(bdd instanceof ReorderableDd)) {
            return assignment;
        }
        ReorderableDd reorderable = (ReorderableDd) bdd;
        MutableNatSet levels = MutableNatSet.dense(bdd.numberOfVariables());
        for (int variable = assignment.nextSetBit(0); variable >= 0; variable = assignment.nextSetBit(variable + 1)) {
            levels.set(reorderable.levelOfVariable(variable));
        }
        return levels;
    }

    private static void checkTree(BinaryDd bdd, int function, SyntaxTree tree, String what) {
        Iterator<boolean[]> all = getArrayIterator(fullSupport(bdd));
        while (all.hasNext()) {
            boolean[] point = all.next();
            assertThat(
                    what + " disagrees at " + Arrays.toString(point) + " for function " + function,
                    bdd.evaluate(function, point),
                    is(tree.evaluate(point)));
        }
    }

    private static NatSet fullSupport(BinaryDd bdd) {
        MutableNatSet all = MutableNatSet.dense(bdd.numberOfVariables());
        all.set(0, bdd.numberOfVariables());
        return all;
    }

    private void testSimplify(BinaryDecisionDiagram bdd, int direct, int indirect, int domain) {
        int directOnDomain = bdd.reference(bdd.and(direct, domain));
        int indirectOnDomain = bdd.reference(bdd.and(indirect, domain));
        assertThat(directOnDomain, is(indirectOnDomain));
        bdd.dereference(directOnDomain, indirectOnDomain);
    }

    @SuppressWarnings("unused")
    static Collection<BinaryDd> bdds() {
        return infoMap.keySet();
    }

    @BeforeAll
    static void dummy() {
        // Dummy method to separate static initialization from actual test running times
        logger.log(Level.FINE, "Before class");
    }

    @AfterAll
    static void check() {
        doCheckInvariants();
        /* The stress is only worth its runtime if it actually moved something - a swap that silently
         * became a no-op would leave every "reordered" variant running at the identity order, which is
         * exactly the blind spot these variants exist to close. */
        for (DdContextImpl context : reorderStressed) {
            assertThat(context + " never left the identity order", isReordered(context), is(true));
        }
    }

    private static boolean isReordered(DdContextImpl diagram) {
        for (int variable = 0; variable < diagram.variableOrder().numberOfVariables(); variable++) {
            if (diagram.variableOrder().levelOfVariable(variable) != variable) {
                return true;
            }
        }
        return false;
    }

    @AfterAll
    static void statistics() {
        for (BinaryDd bdd : infoMap.keySet()) {
            logger.log(Level.INFO, Statistics.formatStatistics(((StatisticsSource) bdd).statistics()));
        }
    }

    @AfterEach
    void clearCaches() {
        for (BinaryDd bdd : infoMap.keySet()) {
            if (skipCheckRandom.nextInt(100) == 0) {
                bdd.invalidateCache();
            }
        }
    }

    @AfterEach
    void occasionallyReorder() {
        if (THEORIES_RUN.incrementAndGet() % REORDER_EVERY != 0) {
            return;
        }
        int round = THEORIES_RUN.get() / REORDER_EVERY;
        for (DdContextImpl context : reorderStressed) {
            if (context.variableOrder().numberOfVariables() < 2) {
                continue;
            }
            for (int i = 0; i < REORDER_SWAPS; i++) {
                context.variableOrder()
                        .siftDown(reorderRandom.nextInt(context.variableOrder().numberOfVariables() - 1));
            }
        }
        // Once, after every diagram has been swapped - doCheckInvariants covers all of them, so calling
        // it per diagram would cost a multiple of what it verifies.
        if (round % REORDER_CHECK_EVERY == 0) {
            doCheckInvariants();
        }
    }

    @AfterEach
    void checkInvariants() {
        if (skipCheckRandom.nextInt(SKIP_CHECK_RANDOM_BOUND) == 0) {
            doCheckInvariants();
        }
    }

    /**
     * Enumerating in a domain walks the function and the domain together instead of conjoining them
     * first, so it has a failure mode the plain walk does not: two nodes that are both satisfiable on
     * their own need not be satisfiable together, and the descent has to be able to retract. Checked
     * against the conjunction it replaces, and against the callback form.
     */
    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testSolutionIteratorIn(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.left;
        int domain = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function));
        assumeTrue(bdd.isValidFunction(domain));

        int conjunction = bdd.reference(bdd.and(function, domain));
        Set<NatSet> expected = new HashSet<>();
        for (Cursor<NatSet> cursor = bdd.solutionCursor(conjunction); cursor.valid(); cursor.advance()) {
            expected.add(NatSetFixtures.copyOf(cursor.current()));
        }

        Set<NatSet> fromCursor = new HashSet<>();
        for (Cursor<NatSet> cursor = bdd.solutionCursorIn(function, domain); cursor.valid(); cursor.advance()) {
            fromCursor.add(NatSetFixtures.copyOf(cursor.current()));
        }
        assertThat(fromCursor, is(expected));

        Set<NatSet> fromCallback = new HashSet<>();
        bdd.forEachSolutionIn(function, domain, solution -> fromCallback.add(NatSetFixtures.copyOf(solution)));
        assertThat(fromCallback, is(expected));

        bdd.dereference(conjunction);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testAnd(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int and = bdd.reference(bdd.and(function1, function2));

        for (boolean[] valuation : assignmentsOver(bdd.support(function1), bdd.support(function2))) {
            if (bdd.evaluate(function1, valuation)) {
                assertThat(bdd.evaluate(and, valuation), is(bdd.evaluate(function2, valuation)));
            } else {
                assertThat(bdd.evaluate(and, valuation), is(false));
            }
        }

        int not1 = bdd.reference(bdd.not(function1));
        int not2 = bdd.reference(bdd.not(function2));
        int not1orNot2 = bdd.reference(bdd.or(not1, not2));
        int andDeMorganConstruction = bdd.not(not1orNot2);
        assertThat(and, is(andDeMorganConstruction));
        bdd.dereference(not1, not2, not1orNot2);

        int andIteConstruction = bdd.ifThenElse(function1, function2, bdd.falseFunction());
        assertThat(and, is(andIteConstruction));

        bdd.dereference(and);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testAndSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.first;
        int function2 = dataPoint.second;
        int domain = dataPoint.third;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        assumeTrue(bdd.isValidFunction(domain));

        int andSimplify = bdd.reference(bdd.andSimplify(function1, function2, domain));
        int andThenSimplify = bdd.reference(bdd.simplify(bdd.and(function1, function2), domain));

        testSimplify(bdd, andSimplify, andThenSimplify, domain);

        bdd.dereference(andSimplify, andThenSimplify);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testAndExists(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        // Densities from none to every variable, so both the plain conjunction and the full projection come up.
        Random quantificationRandom = new Random(31L * function1 + function2);
        int density = quantificationRandom.nextInt(bdd.numberOfVariables() + 1);
        MutableNatSet quantified = MutableNatSet.dense(bdd.numberOfVariables());
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (quantificationRandom.nextInt(bdd.numberOfVariables()) < density) {
                quantified.set(i);
            }
        }

        int conjunction = bdd.reference(bdd.and(function1, function2));
        int expected = bdd.reference(bdd.exists(conjunction, quantified));
        bdd.dereference(conjunction);

        int andExists = bdd.reference(bdd.andExists(function1, function2, quantified));
        assertThat(andExists, is(expected));
        assertThat(bdd.andExists(function2, function1, quantified), is(expected));
        RegisteredOperation.Binary registered = bdd.registerAndExists(quantified);
        assertThat(registered.applyAsInt(function1, function2), is(expected));
        assertThat(registered.applyAsInt(function2, function1), is(expected));

        int disjunction = bdd.reference(bdd.or(function1, function2));
        int expectedForall = bdd.reference(bdd.forall(disjunction, quantified));
        bdd.dereference(disjunction);
        assertThat(bdd.orForall(function1, function2, quantified), is(expectedForall));
        assertThat(bdd.orForall(function2, function1, quantified), is(expectedForall));
        bdd.dereference(expectedForall);

        // Against the syntax trees, wherever the quantified part of the support is small enough to enumerate.
        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function1));
        support.or(bdd.support(function2));
        MutableNatSet quantifiedSupport = MutableNatSet.copyOf(support);
        quantifiedSupport.and(quantified);
        if (quantifiedSupport.size() <= 5) {
            MutableNatSet unquantifiedSupport = MutableNatSet.copyOf(support);
            unquantifiedSupport.andNot(quantified);
            for (boolean[] valuation : assignmentsOver(unquantifiedSupport)) {
                boolean[] extended = valuation.clone();
                boolean someWitness = Iterators.any(NatSetFixtures.powerSetIterator(quantifiedSupport), witness -> {
                    Objects.requireNonNull(witness);
                    quantifiedSupport.forEach(i -> extended[i] = witness.contains(i));
                    return dataPoint.leftTree.evaluate(extended) && dataPoint.rightTree.evaluate(extended);
                });
                assertThat(bdd.evaluate(andExists, valuation), is(someWitness));
            }
        }
        bdd.dereference(andExists, expected);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testAndNot(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int andNot = bdd.reference(bdd.andNot(function1, function2));

        for (boolean[] valuation : assignmentsOver(bdd.support(function1), bdd.support(function2))) {
            if (bdd.evaluate(function1, valuation)) {
                assertThat(bdd.evaluate(andNot, valuation), is(!bdd.evaluate(function2, valuation)));
            } else {
                assertThat(bdd.evaluate(andNot, valuation), is(false));
            }
        }

        int not2 = bdd.reference(bdd.not(function2));
        int not2and1 = bdd.reference(bdd.and(function1, not2));
        assertThat(andNot, is(not2and1));
        bdd.dereference(not2, not2and1);

        int not1 = bdd.reference(bdd.not(function1));
        int not1or2 = bdd.reference(bdd.or(not1, function2));
        assertThat(andNot, is(bdd.not(not1or2)));
        bdd.dereference(not1, not1or2);

        bdd.dereference(andNot);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testAndNotSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.first;
        int function2 = dataPoint.second;
        int domain = dataPoint.third;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        assumeTrue(bdd.isValidFunction(domain));

        int direct = bdd.reference(bdd.andNotSimplify(function1, function2, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.andNot(function1, function2), domain));
        testSimplify(bdd, direct, indirect, domain);

        bdd.dereference(direct, indirect);
    }

    @SuppressWarnings("NullAway")
    private static int[] buildComposeArray(BinaryDd bdd, int function, SyntaxTree syntaxTree) {
        Info<BinaryDd> bddInfo = infoMap.get(bdd).bddInfo;
        Set<Integer> containedVariables = syntaxTree.containedVariables();
        assumeTrue(containedVariables.size() <= 7);

        Random selectionRandom = new Random(function);
        List<Integer> availableFunctions = new ArrayList<>(bddInfo.syntaxTreeMap.keySet());
        availableFunctions.addAll(Arrays.asList(bdd.trueFunction(), bdd.falseFunction()));

        int[] composeArray = new int[variableCount];
        for (int i = 0; i < variableCount; i++) {
            if (containedVariables.contains(i)) {
                int replacementBddIndex = selectionRandom.nextInt(availableFunctions.size());
                composeArray[i] = availableFunctions.get(replacementBddIndex);
            } else {
                composeArray[i] = bddInfo.variableList.get(i);
            }
        }
        return composeArray;
    }

    @SuppressWarnings("NullAway")
    private static SyntaxTree buildComposeTree(BinaryDd bdd, SyntaxTree syntaxTree, int[] composeArray) {
        Info<BinaryDd> bddInfo = infoMap.get(bdd).bddInfo;
        Map<Integer, SyntaxTree> replacementMap = new HashMap<>();
        for (int i = 0; i < composeArray.length; i++) {
            int variableReplacement = composeArray[i];
            if (variableReplacement != bdd.placeholder()) {
                replacementMap.put(i, bddInfo.syntaxTreeMap.get(variableReplacement));
            }
        }
        return SyntaxTree.buildReplacementTree(syntaxTree, replacementMap);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testComposeTree(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        SyntaxTree syntaxTree = dataPoint.tree;
        int[] composeArray = buildComposeArray(bdd, function, syntaxTree);
        SyntaxTree composeTree = buildComposeTree(bdd, syntaxTree, composeArray);
        assumeTrue(composeTree.depth() <= 25);
        int composed = bdd.reference(bdd.compose(function, composeArray));

        Iterator<boolean[]> iterator = getArrayIterator(bdd.support(function));
        while (iterator.hasNext()) {
            boolean[] valuation = iterator.next();
            assertThat(bdd.evaluate(composed, valuation), is(composeTree.evaluate(valuation)));
        }

        int[] composePlaceholderArray = new int[variableCount];
        for (int i = 0; i < variableCount; i++) { // NOPMD
            if (bdd.isVariable(composeArray[i]) && bdd.decisionVariable(composeArray[i]) == i) {
                composePlaceholderArray[i] = bdd.placeholder();
            } else {
                composePlaceholderArray[i] = composeArray[i];
            }
        }
        int composedWithPlaceholder = bdd.compose(function, composePlaceholderArray);
        assertThat(composedWithPlaceholder, is(composed));

        int[] composeCutoffArray = EMPTY_INTS;
        for (int i = composeArray.length - 1; i >= 0; i--) {
            if (composePlaceholderArray[i] != bdd.placeholder()) {
                composeCutoffArray = Arrays.copyOf(composeArray, i + 1);
                break;
            }
        }
        int composedWithCutoff = bdd.compose(function, composeCutoffArray);
        assertThat(composedWithCutoff, is(composed));

        bdd.dereference(composed);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testComposeSimple(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Set<Integer> variables = dataPoint.tree.containedVariables();
        boolean[] baseArray = new boolean[variableCount];
        variables.forEach(i -> baseArray[i] = true);

        int trueFunction = bdd.trueFunction();
        int falseFunction = bdd.falseFunction();
        int[] composeArray = new int[variableCount];

        new ScopedAssignments.PowerSetIterator(baseArray).forEachRemaining(valuation -> {
            for (int i = 0; i < variableCount; i++) {
                composeArray[i] = valuation[i] ? trueFunction : falseFunction;
            }
            int composed = bdd.compose(function, composeArray);
            boolean value = bdd.evaluate(function, valuation);

            if (value) {
                assertThat(composed, is(trueFunction));
            } else {
                assertThat(composed, is(falseFunction));
            }
        });
    }

    @SuppressWarnings("NullAway")
    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testComposeRepeated(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        Info<BinaryDd> bddInfo = infoMap.get(bdd).bddInfo;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        SyntaxTree syntaxTree = dataPoint.tree;
        Set<Integer> containedVariables = syntaxTree.containedVariables();
        assumeTrue(containedVariables.size() <= 4);

        Random selectionRandom = new Random(function);
        List<Integer> availableNodes = new ArrayList<>(bddInfo.syntaxTreeMap.keySet());
        availableNodes.addAll(Arrays.asList(bdd.trueFunction(), bdd.falseFunction()));

        int[] composeArray = new int[variableCount];
        for (int i = 0; i < variableCount; i++) {
            if (containedVariables.contains(i)) {
                int replacementBddIndex = selectionRandom.nextInt(availableNodes.size());
                composeArray[i] = availableNodes.get(replacementBddIndex);
            } else {
                composeArray[i] = bddInfo.variableList.get(i);
            }
        }

        Map<Integer, SyntaxTree> replacementMap = new HashMap<>();
        for (int i = 0; i < composeArray.length; i++) {
            int variableReplacement = composeArray[i];
            if (variableReplacement != bdd.placeholder()) {
                replacementMap.put(i, bddInfo.syntaxTreeMap.get(variableReplacement));
            }
        }

        int composeNode = bdd.reference(bdd.compose(function, composeArray));
        /* Exhaustive, not just over the support: composing has to agree with substituting into the
         * syntax tree everywhere. Guards the class of bug where a compose cache is reused across
         * mappings that only look compatible. */
        checkTree(bdd, composeNode, SyntaxTree.buildReplacementTree(syntaxTree, replacementMap), "one substitution");
        int repeatedNode = bdd.reference(bdd.compose(function, composeArray));
        assertThat(composeNode, is(repeatedNode));
        bdd.dereference(repeatedNode);

        int selfComposeNode = bdd.reference(bdd.compose(composeNode, composeArray));
        bdd.dereference(composeNode);

        SyntaxTree composeTree = SyntaxTree.buildReplacementTree(
                SyntaxTree.buildReplacementTree(syntaxTree, replacementMap), replacementMap);
        assumeTrue(composeTree.depth() <= 25);

        Iterator<boolean[]> iterator = getArrayIterator(bdd.support(function));
        while (iterator.hasNext()) {
            boolean[] valuation = iterator.next();
            assertThat(bdd.evaluate(selfComposeNode, valuation), is(composeTree.evaluate(valuation)));
        }

        bdd.dereference(selfComposeNode);
    }

    @SuppressWarnings("NullAway")
    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testComposeRelabel(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        Info<BinaryDd> bddInfo = infoMap.get(bdd).bddInfo;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        int variables = bdd.numberOfVariables();
        List<Integer> ordering = new ArrayList<>(variables);
        for (int i = 0; i < variables; i++) {
            ordering.add(i);
        }
        Collections.shuffle(ordering, new Random(function));
        int[] inverse = new int[variableCount];

        int[] composeArray = new int[variableCount];
        for (int i = 0; i < variableCount; i++) {
            int map = ordering.get(i);
            composeArray[i] = bdd.variableFunction(map);
            inverse[map] = i;
        }

        Map<Integer, SyntaxTree> replacementMap = new HashMap<>();
        for (int i = 0; i < composeArray.length; i++) {
            int variableReplacement = composeArray[i];
            if (variableReplacement != bdd.placeholder()) {
                replacementMap.put(i, bddInfo.syntaxTreeMap.get(variableReplacement));
            }
        }

        int composeNode = bdd.reference(bdd.compose(function, composeArray));

        MutableNatSet pathMap = MutableNatSet.create();
        bdd.forEachPath(composeNode, path -> {
            pathMap.clear();
            NatSetFixtures.forEach(path.assignment(), i -> pathMap.set(inverse[i]));
            assertThat(bdd.evaluate(function, pathMap), is(true));
        });

        SyntaxTree composeTree = SyntaxTree.buildReplacementTree(dataPoint.tree, replacementMap);
        assumeTrue(composeTree.depth() <= 25);

        Iterator<boolean[]> iterator = getArrayIterator(bdd.support(function));
        while (iterator.hasNext()) {
            boolean[] valuation = iterator.next();
            assertThat(bdd.evaluate(composeNode, valuation), is(composeTree.evaluate(valuation)));
        }

        bdd.dereference(composeNode);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testComposeTreeSimplify(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.left;
        int domain = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function));
        assumeTrue(bdd.isValidFunction(domain));

        SyntaxTree syntaxTree = dataPoint.leftTree;
        int[] composeArray = buildComposeArray(bdd, function, syntaxTree);
        SyntaxTree composeTree = buildComposeTree(bdd, syntaxTree, composeArray);
        assumeTrue(composeTree.depth() <= 25);
        int direct = bdd.reference(bdd.composeSimplify(function, composeArray, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.compose(function, composeArray), domain));
        testSimplify(bdd, direct, indirect, domain);

        int[] simplifiedArray = new int[variableCount];
        for (int i = 0; i < variableCount; i++) {
            simplifiedArray[i] = bdd.reference(bdd.simplify(composeArray[i], domain));
        }
        int withSimplifiedDirect = bdd.reference(bdd.composeSimplify(function, simplifiedArray, domain));
        int withSimplifiedIndirect = bdd.reference(bdd.simplify(bdd.compose(function, simplifiedArray), domain));
        testSimplify(bdd, withSimplifiedDirect, withSimplifiedIndirect, domain);
        testSimplify(bdd, direct, withSimplifiedDirect, domain);

        bdd.dereference(direct, indirect, withSimplifiedDirect, withSimplifiedIndirect);
        bdd.dereference(simplifiedArray);
    }

    /**
     * As {@link #buildComposeArray}, but leaves about half of the function's own variables alone, so that the
     * path through them carries their value down to the replacements that depend on them.
     */
    @SuppressWarnings("NullAway")
    private static int[] buildPartialComposeArray(BinaryDd bdd, int function, SyntaxTree syntaxTree) {
        int[] composeArray = buildComposeArray(bdd, function, syntaxTree);
        Info<BinaryDd> bddInfo = infoMap.get(bdd).bddInfo;
        Random keepRandom = new Random(~function);
        for (int variable : syntaxTree.containedVariables()) {
            if (keepRandom.nextBoolean()) {
                composeArray[variable] = bddInfo.variableList.get(variable);
            }
        }
        return composeArray;
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testComposePartialTree(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        int[] composeArray = buildPartialComposeArray(bdd, function, dataPoint.tree);
        SyntaxTree composeTree = buildComposeTree(bdd, dataPoint.tree, composeArray);
        assumeTrue(composeTree.depth() <= 25);
        int composed = bdd.reference(bdd.compose(function, composeArray));
        checkTree(bdd, composed, composeTree, "compose");

        if (bdd instanceof BddImpl) {
            RegisteredOperation.Unary registered = bdd.registerCompose(composeArray);
            // Twice: the second application reads the private cache the first filled.
            assertThat(registered.applyAsInt(function), is(composed));
            assertThat(registered.applyAsInt(function), is(composed));
            registered.release();
        }
        bdd.dereference(composed);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testComposePartialTreeSimplify(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.left;
        int domain = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function));
        assumeTrue(bdd.isValidFunction(domain));

        int[] composeArray = buildPartialComposeArray(bdd, function, dataPoint.leftTree);
        SyntaxTree composeTree = buildComposeTree(bdd, dataPoint.leftTree, composeArray);
        assumeTrue(composeTree.depth() <= 25);
        int composed = bdd.reference(bdd.compose(function, composeArray));
        int direct = bdd.reference(bdd.composeSimplify(function, composeArray, domain));
        testSimplify(bdd, direct, composed, domain);

        if (bdd instanceof BddImpl) {
            RegisteredOperation.Binary registered = bdd.registerComposeSimplify(composeArray);
            int viaRegistered = bdd.reference(registered.applyAsInt(function, domain));
            testSimplify(bdd, viaRegistered, composed, domain);
            assertThat(registered.applyAsInt(function, bdd.trueFunction()), is(composed));
            registered.release();
            bdd.dereference(viaRegistered);
        }
        bdd.dereference(composed, direct);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testConsume(BinaryDataPoint<BinaryDd> dataPoint) {
        // This test simply tests if the semantics of consume are as specified, i.e.
        // consume(result, input1, input2) reduces the reference count of the inputs and increases that
        // of result
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeFalse(function1 == function2);
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        int node1 = bdd.nodeFor(function1);
        int node2 = bdd.nodeFor(function2);
        assumeFalse(bdd.isSaturatedNode(node1));
        assumeFalse(bdd.isSaturatedNode(node2));

        bdd.reference(function1);
        bdd.reference(function2);
        int node1count = bdd.nodeReferenceCount(node1);
        int node2count = bdd.nodeReferenceCount(node2);

        for (int resultFunction : doBddOperations(bdd, function1, function2)) {
            int resultNode = bdd.nodeFor(resultFunction);
            if (bdd.isSaturatedNode(resultNode)) {
                continue;
            }
            int resultCount = bdd.nodeReferenceCount(resultNode);

            assertThat(bdd.consume(resultFunction, function1, function2), is(resultFunction));

            if (resultNode == node1) {
                if (resultNode == node2) {
                    assertThat(bdd.nodeReferenceCount(node1), is(node1count - 1));
                    assertThat(bdd.nodeReferenceCount(node2), is(node2count - 1));
                    assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount - 1));
                } else {
                    assertThat(bdd.nodeReferenceCount(node1), is(node1count));
                    assertThat(bdd.nodeReferenceCount(node2), is(node2count - 1));
                    assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount));
                }
            } else if (resultNode == node2) {
                assertThat(bdd.nodeReferenceCount(node1), is(node1count - 1));
                assertThat(bdd.nodeReferenceCount(node2), is(node2count));
                assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount));
            } else if (node1 == node2) {
                assertThat(bdd.nodeReferenceCount(node1), is(node1count - 2));
                assertThat(bdd.nodeReferenceCount(node2), is(node2count - 2));
                assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount + 1));
            } else {
                assertThat(bdd.nodeReferenceCount(node1), is(node1count - 1));
                assertThat(bdd.nodeReferenceCount(node2), is(node2count - 1));
                assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount + 1));
            }

            bdd.reference(node1);
            bdd.reference(node2);
            bdd.dereference(resultNode);

            assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount));
            assertThat(bdd.nodeReferenceCount(node1), is(node1count));
            assertThat(bdd.nodeReferenceCount(node2), is(node2count));
        }

        bdd.dereference(function1);
        bdd.dereference(function2);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testCountSatisfyingAssignments(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        long satisfyingAssignments = 0L;
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(function, valuation)) {
                satisfyingAssignments += 1L;
            }
        }

        assertThat(bdd.countSatisfyingAssignments(function).longValueExact(), is(satisfyingAssignments));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testSatisfyingFraction(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        // Exact over at most 53 variables: every intermediate is a multiple of 2^-n in [0, 1].
        assumeTrue(bdd.numberOfVariables() <= 53);
        double expected = Math.scalb(
                (double) bdd.countSatisfyingAssignments(function).longValueExact(), -bdd.numberOfVariables());
        assertThat(bdd.satisfyingFraction(function), is(expected));
        assertThat(bdd.satisfyingFraction(bdd.not(function)), is(1.0d - expected));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testInfluences(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        // By definition: the assignments on which flipping the variable flips the function. Exact over at most 53
        // variables, every reach probability and fraction being a multiple of 2^-n.
        assumeTrue(bdd.numberOfVariables() <= 53);
        int variables = bdd.numberOfVariables();
        long[] flips = new long[variables];
        for (boolean[] valuation : valuations) {
            boolean value = bdd.evaluate(function, valuation);
            for (int variable = 0; variable < variables; variable++) {
                valuation[variable] = !valuation[variable];
                if (bdd.evaluate(function, valuation) != value) {
                    flips[variable] += 1L;
                }
                valuation[variable] = !valuation[variable];
            }
        }
        double[] expected = new double[variables];
        for (int variable = 0; variable < variables; variable++) {
            expected[variable] = Math.scalb((double) flips[variable], -variables);
        }
        assertThat(bdd.influences(function), is(expected));
        int negation = bdd.reference(bdd.not(function));
        assertThat(bdd.influences(negation), is(expected));
        bdd.dereference(negation);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testImpliedAndImplyingLiterals(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        // By definition: f implies a literal iff no satisfying valuation falsifies it; a literal implies f iff every
        // valuation satisfying it satisfies f.
        int variables = bdd.numberOfVariables();
        boolean[][] impliedBy = new boolean[variables][2];
        boolean[][] implying = new boolean[variables][2];
        for (boolean[] row : impliedBy) {
            Arrays.fill(row, true);
        }
        for (boolean[] row : implying) {
            Arrays.fill(row, true);
        }
        boolean satisfiable = false;
        boolean valid = true;
        for (boolean[] valuation : valuations) {
            boolean value = bdd.evaluate(function, valuation);
            satisfiable |= value;
            valid &= value;
            for (int variable = 0; variable < variables; variable++) {
                int polarity = valuation[variable] ? 1 : 0;
                if (value) {
                    impliedBy[variable][1 - polarity] = false;
                } else {
                    implying[variable][polarity] = false;
                }
            }
        }
        assertThat(bdd.impliedLiterals(function), is(satisfiable ? Optional.of(cube(impliedBy)) : Optional.empty()));
        assertThat(bdd.implyingLiterals(function), is(valid ? Optional.empty() : Optional.of(cube(implying))));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testUnateness(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        // By definition: positive in v iff raising v never falsifies the function, negative iff lowering it never does.
        NatSet support = bdd.support(function);
        MutableNatSet positive = MutableNatSet.copyOf(support);
        MutableNatSet negative = MutableNatSet.copyOf(support);
        for (boolean[] valuation : valuations) {
            boolean value = bdd.evaluate(function, valuation);
            support.forEach((int variable) -> {
                boolean original = valuation[variable];
                valuation[variable] = !original;
                boolean flipped = bdd.evaluate(function, valuation);
                valuation[variable] = original;
                boolean low = original ? flipped : value;
                boolean high = original ? value : flipped;
                if (low && !high) {
                    positive.clear(variable);
                }
                if (high && !low) {
                    negative.clear(variable);
                }
            });
        }
        assertThat(bdd.unateness(function), is(new BinaryDecisionDiagram.Unateness(positive, negative)));
        int negation = bdd.reference(bdd.not(function));
        assertThat(bdd.unateness(negation), is(new BinaryDecisionDiagram.Unateness(negative, positive)));
        bdd.dereference(negation);
    }

    // The cube of the literals marked: literals[v][1] the positive one, literals[v][0] the negative one.
    private static Cube cube(boolean[][] literals) {
        MutableNatSet assignment = MutableNatSet.create();
        MutableNatSet support = MutableNatSet.create();
        for (int variable = 0; variable < literals.length; variable++) {
            if (literals[variable][1]) {
                assignment.set(variable);
                support.set(variable);
            } else if (literals[variable][0]) {
                support.set(variable);
            }
        }
        return Cube.of(assignment, support);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testCountSatisfyingAssignmentsIn(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        long satisfyingAssignments = 0L;
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(function2, valuation) && bdd.evaluate(function1, valuation)) {
                satisfyingAssignments += 1L;
            }
        }

        assertThat(bdd.countSatisfyingAssignmentsIn(function1, function2).longValueExact(), is(satisfyingAssignments));

        int and = bdd.reference(bdd.and(function1, function2));
        assertThat(bdd.countSatisfyingAssignmentsIn(function1, function2), is(bdd.countSatisfyingAssignments(and)));
        bdd.dereference(and);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testSatisfyingFractionIn(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.left;
        int domain = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function));
        assumeTrue(bdd.isValidFunction(domain));

        if (domain == bdd.falseFunction()) {
            assertThrowsExactly(IllegalArgumentException.class, () -> bdd.satisfyingFractionIn(function, domain));
            return;
        }

        // Correctly rounded over at most 53 variables: both counts are exact doubles, and the one division rounds.
        assumeTrue(bdd.numberOfVariables() <= 53);
        long inDomain = bdd.countSatisfyingAssignments(domain).longValueExact();
        long satisfying = bdd.countSatisfyingAssignmentsIn(function, domain).longValueExact();
        assertThat(bdd.satisfyingFractionIn(function, domain), is((double) satisfying / inDomain));
        int negation = bdd.reference(bdd.not(function));
        assertThat(bdd.satisfyingFractionIn(negation, domain), is((double) (inDomain - satisfying) / inDomain));
        bdd.dereference(negation);
        assertThat(bdd.satisfyingFractionIn(function, bdd.trueFunction()), is(bdd.satisfyingFraction(function)));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testCountSatisfyingAssignmentsRestrictedSimple(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        MutableNatSet set = MutableNatSet.create();
        set.set(0, bdd.numberOfVariables());

        assertThat(
                bdd.countSatisfyingAssignments(function, set).longValueExact(),
                is(bdd.countSatisfyingAssignments(function).longValueExact()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testCountSatisfyingAssignmentsRestricted(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Random random = new Random(function);
        MutableNatSet set = MutableNatSet.create();
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (random.nextBoolean()) {
                set.set(i);
            }
        }
        bdd.supportTo(function, set);

        AtomicLong satisfyingAssignments = new AtomicLong();
        bdd.forEachSolution(function, set, path -> satisfyingAssignments.incrementAndGet());

        assertThat(bdd.countSatisfyingAssignments(function, set).longValueExact(), is(satisfyingAssignments.get()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testEquivalence(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int equivalence = bdd.reference(bdd.equivalence(function1, function2));

        for (boolean[] valuation : assignmentsOver(bdd.support(function1), bdd.support(function2))) {
            if (bdd.evaluate(function1, valuation)) {
                assertThat(bdd.evaluate(equivalence, valuation), is(bdd.evaluate(function2, valuation)));
            } else {
                assertThat(bdd.evaluate(equivalence, valuation), is(!bdd.evaluate(function2, valuation)));
            }
        }

        int and = bdd.reference(bdd.and(function1, function2));
        int not1 = bdd.reference(bdd.not(function1));
        int not2 = bdd.reference(bdd.not(function2));
        int not1andNot2 = bdd.reference(bdd.and(not1, not2));
        int equivalenceAndOrConstruction = bdd.or(and, not1andNot2);
        assertThat(equivalence, is(equivalenceAndOrConstruction));
        bdd.dereference(and, not1, not2, not1andNot2);

        int equivalenceIteConstruction = bdd.ifThenElse(function1, function2, not2);
        assertThat(equivalence, is(equivalenceIteConstruction));

        int implies12 = bdd.reference(bdd.implication(function1, function2));
        int implies21 = bdd.reference(bdd.implication(function2, function1));
        int equivalenceBiImplicationConstruction = bdd.and(implies12, implies21);
        assertThat(equivalence, is(equivalenceBiImplicationConstruction));
        bdd.dereference(implies12, implies21);

        bdd.dereference(equivalence);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testEquivalenceSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.first;
        int function2 = dataPoint.second;
        int domain = dataPoint.third;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        assumeTrue(bdd.isValidFunction(domain));

        int direct = bdd.reference(bdd.equivalenceSimplify(function1, function2, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.equivalence(function1, function2), domain));
        testSimplify(bdd, direct, indirect, domain);

        bdd.dereference(direct, indirect);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testEvaluateTree(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));
        assumeTrue(dataPoint.tree.depth() <= 5);

        for (boolean[] valuation : assignmentsOver(bdd.support(function))) {
            assertThat(bdd.evaluate(function, valuation), is(dataPoint.tree.evaluate(valuation)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testExists(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        MutableNatSet quantificationBitSet = MutableNatSet.dense(bdd.numberOfVariables());
        Random quantificationRandom = new Random(function);
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (quantificationRandom.nextInt(bdd.numberOfVariables()) < 5) {
                quantificationBitSet.set(i);
            }
        }
        assumeTrue(quantificationBitSet.size() <= 5);

        int exists = bdd.exists(function, quantificationBitSet);
        MutableNatSet supportIntersection = MutableNatSet.copyOf(bdd.support(exists));
        supportIntersection.and(quantificationBitSet);
        assertThat(supportIntersection.isEmpty(), is(true));

        MutableNatSet unquantifiedVariables = NatSetFixtures.copyOf(quantificationBitSet);
        unquantifiedVariables.flip(0, bdd.numberOfVariables());

        assertThat(
                Iterators.all(NatSetFixtures.powerSetIterator(unquantifiedVariables), unquantifiedAssignment -> {
                    boolean bddEvaluation = bdd.evaluate(exists, Objects.requireNonNull(unquantifiedAssignment));
                    boolean setEvaluation =
                            Iterators.any(NatSetFixtures.powerSetIterator(quantificationBitSet), bitSet -> {
                                MutableNatSet actualBitSet = NatSetFixtures.copyOf(Objects.requireNonNull(bitSet));
                                actualBitSet.or(unquantifiedAssignment);
                                return bdd.evaluate(function, actualBitSet);
                            });
                    return bddEvaluation == setEvaluation;
                }),
                is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForall(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        MutableNatSet quantificationBitSet = MutableNatSet.dense(bdd.numberOfVariables());
        Random quantificationRandom = new Random(function);
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (quantificationRandom.nextInt(bdd.numberOfVariables()) < 5) {
                quantificationBitSet.set(i);
            }
        }
        assumeTrue(quantificationBitSet.size() <= 5);

        int forall = bdd.forall(function, quantificationBitSet);
        MutableNatSet supportIntersection = MutableNatSet.copyOf(bdd.support(forall));
        supportIntersection.and(quantificationBitSet);
        assertThat(supportIntersection.isEmpty(), is(true));

        MutableNatSet unquantifiedVariables = NatSetFixtures.copyOf(quantificationBitSet);
        unquantifiedVariables.flip(0, bdd.numberOfVariables());

        assertThat(
                Iterators.all(NatSetFixtures.powerSetIterator(unquantifiedVariables), unquantifiedAssignment -> {
                    boolean bddEvaluation = bdd.evaluate(forall, Objects.requireNonNull(unquantifiedAssignment));
                    boolean setEvaluation =
                            Iterators.all(NatSetFixtures.powerSetIterator(quantificationBitSet), bitSet -> {
                                MutableNatSet actualBitSet = NatSetFixtures.copyOf(Objects.requireNonNull(bitSet));
                                actualBitSet.or(unquantifiedAssignment);
                                return bdd.evaluate(function, actualBitSet);
                            });
                    return bddEvaluation == setEvaluation;
                }),
                is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForEachPathSimple(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function));
        assumeTrue(support.size() <= 7);

        MutableNatSet supportFromSolutions = MutableNatSet.dense(bdd.numberOfVariables());
        MutableNatSet supportFromPathSupport = MutableNatSet.dense(bdd.numberOfVariables());

        List<NatSet> paths = new ArrayList<>();
        bdd.forEachPath(function, path -> {
            paths.add(NatSet.copyOf(path.assignment()));
            supportFromPathSupport.or(path.support());
        });
        assertThat(supportFromPathSupport, is(support));

        Iterator<NatSet> pathIterator = paths.iterator();
        NatSet previous = null;
        Set<NatSet> solutionBitSets = new HashSet<>();

        while (pathIterator.hasNext()) {
            MutableNatSet next = MutableNatSet.copyOf(pathIterator.next());
            if (previous != null) {
                // Paths come out in the order the diagram is laid out in, which is by level - the same
                // thing as by variable index only while nothing has reordered.
                assertThat(LEXICOGRAPHIC.compare(byLevel(bdd, previous), byLevel(bdd, next)), is(-1));
            }
            previous = next;
            // No solution is generated twice
            assertThat(solutionBitSets.add(next), is(true));
            supportFromSolutions.or(next);
        }

        // supportFromSolutions has to be a subset of support (see ~a for example)
        supportFromSolutions.or(support);
        assertThat(supportFromSolutions, is(support));

        // Build up all minimal solutions using a naive algorithm
        Set<NatSet> assignments = new BddPathExplorer(bdd, function).getAssignments();
        assertThat(solutionBitSets, is(assignments));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForEachPathWithRelevantSet(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function));
        assumeTrue(support.size() <= 7);

        List<NatSet> minimalSolutions = new ArrayList<>();
        int variableCount = bdd.numberOfVariables();
        bdd.forEachPath(function, path -> {
            minimalSolutions.add(NatSet.copyOf(path.assignment()));
            MutableNatSet nonRelevantVariables = MutableNatSet.copyOf(path.support());
            nonRelevantVariables.flip(0, variableCount);
            assertThat(nonRelevantVariables.intersects(path.assignment()), is(false));
            assertThat(bdd.evaluate(function, path.assignment()), is(true));

            Iterator<NatSet> iterator = NatSetFixtures.powerSetIterator(nonRelevantVariables);
            while (iterator.hasNext()) {
                MutableNatSet next = NatSetFixtures.copyOf(iterator.next());
                next.or(path.assignment());
                assertThat(bdd.evaluate(function, next), is(true));
            }
        });

        List<NatSet> otherMinimalSolutions = new ArrayList<>();
        bdd.forEachPath(function, path -> otherMinimalSolutions.add(NatSet.copyOf(path.assignment())));
        assertThat(minimalSolutions, is(otherMinimalSolutions));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testPathIterator(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function));
        assumeTrue(support.size() <= 7);

        List<Cube> paths = new ArrayList<>();
        bdd.forEachPath(function, path -> paths.add(path.copy()));

        List<Cube> cursorPaths = new ArrayList<>();
        for (Cursor<Cube> cursor = bdd.pathCursor(function); cursor.valid(); cursor.advance()) {
            cursorPaths.add(cursor.current().copy());
        }

        assertThat(cursorPaths, is(paths));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testImplicants(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd binaryDd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(binaryDd.isValidFunction(function));
        BinaryDd bdd = binaryDd;

        List<Cube> implicants = BddUtil.implicants(bdd, function);

        // Each cube is contained in the function, and their union is exactly it - of(Cube) is what makes
        // that an identity rather than a per-assignment check.
        int union = bdd.falseFunction();
        bdd.reference(union);
        for (Cube cube : implicants) {
            int cubeFunction = bdd.reference(bdd.of(cube));
            assertThat(cube.toString(), bdd.implies(cubeFunction, function), is(true));
            union = bdd.updateWith(bdd.or(union, cubeFunction), union);
            bdd.dereference(cubeFunction);
        }
        assertThat(implicants.toString(), union, is(function));
        bdd.dereference(union);

        // No cube says the same as another one with more literals.
        for (Cube candidate : implicants) {
            for (Cube other : implicants) {
                if (other != candidate) { // NOPMD - identity is the point
                    assertThat(other + " subsumes " + candidate, other.implies(candidate), is(false));
                }
            }
        }

        // Complementing yields a CNF cover of the same function.
        int complement = bdd.reference(bdd.not(function));
        int conjunction = bdd.reference(bdd.trueFunction());
        for (Cube cube : BddUtil.implicants(bdd, complement)) {
            int clause = bdd.reference(bdd.not(bdd.of(cube)));
            conjunction = bdd.updateWith(bdd.and(conjunction, clause), conjunction);
            bdd.dereference(clause);
        }
        assertThat(conjunction, is(function));
        bdd.dereference(conjunction);
        bdd.dereference(complement);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testAdopt(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        // Into a fresh diagram with the variables reversed - so it has to restructure - and back: the same function.
        int variables = bdd.numberOfVariables();
        IntUnaryOperator reversed = variable -> variables - 1 - variable;
        BddImpl other = new DdContextImpl(ImmutableBddConfiguration.builder().build()).bdd();
        other.createVariables(variables);
        int adopted = other.reference(other.adopt(bdd, function, reversed));
        assertThat(other.satisfyingFraction(adopted), is(bdd.satisfyingFraction(function)));
        assertThat(bdd.adopt(other, adopted, reversed), is(function));
        other.dereference(adopted);

        // Order-preserving, where each node is rebuilt as one node, and within the diagram itself.
        int copied = other.reference(other.adopt(bdd, function, IntUnaryOperator.identity()));
        assertThat(other.satisfyingFraction(copied), is(bdd.satisfyingFraction(function)));
        assertThat(bdd.adopt(other, copied, IntUnaryOperator.identity()), is(function));
        other.dereference(copied);
        assertThat(bdd.adopt(bdd, function, IntUnaryOperator.identity()), is(function));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testShortestPath(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        // The first of the shortest paths in forEachPath order.
        Cube[] expected = {null};
        bdd.forEachPath(function, path -> {
            if (expected[0] == null || path.size() < expected[0].size()) {
                expected[0] = path.copy();
            }
        });
        assertThat(BddUtil.shortestPath(bdd, function).orElse(null), is(expected[0]));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testPrimeImplicants(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        List<Cube> primes = BddUtil.primeImplicants(bdd, function);

        // Every prime implies the function and stops doing so without any one of its literals; together they are it.
        int union = bdd.reference(bdd.falseFunction());
        for (Cube prime : primes) {
            int primeFunction = bdd.reference(bdd.of(prime));
            assertThat(prime.toString(), bdd.implies(primeFunction, function), is(true));
            union = bdd.updateWith(bdd.or(union, primeFunction), union);
            bdd.dereference(primeFunction);
            prime.forEachLiteral((variable, value) -> {
                int weaker = bdd.reference(bdd.of(prime.without(variable)));
                assertThat(prime + " without " + variable, bdd.implies(weaker, function), is(false));
                bdd.dereference(weaker);
            });
        }
        assertThat(primes.toString(), union, is(function));
        bdd.dereference(union);

        // Every implicant of the diagram's cover contains a prime.
        for (Cube implicant : BddUtil.implicants(bdd, function)) {
            assertThat(implicant.toString(), primes.stream().anyMatch(implicant::implies), is(true));
        }
        assertThat(primes.toString(), Set.copyOf(primes).size(), is(primes.size()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForEach(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Set<NatSet> satisfyingAssignments = new HashSet<>();
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(function, valuation)) {
                satisfyingAssignments.add(asSet(valuation));
            }
        }

        bdd.forEachSolution(function, valuation -> {
            assertThat("Invalid solution", bdd.evaluate(function, valuation), is(true));
            assertThat("Duplicate solution", satisfyingAssignments.remove(valuation), is(true));
        });
        assertThat("Missing solution", satisfyingAssignments, empty());
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testGetLowAndHigh(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function) && !bdd.isConstant(function));

        int low = bdd.lowOf(function);
        int high = bdd.highOf(function);
        if (bdd.isVariable(function)) {
            assertThat(low, is(bdd.falseFunction()));
            assertThat(high, is(bdd.trueFunction()));
        } else if (bdd.isVariableNegated(function)) {
            assertThat(low, is(bdd.trueFunction()));
            assertThat(high, is(bdd.falseFunction()));
        } else {
            Collection<Integer> rootNodes = ImmutableSet.of(bdd.falseFunction(), bdd.trueFunction());
            assumeFalse(rootNodes.contains(low) && rootNodes.contains(high));
        }

        int variable = bdd.decisionVariable(function);
        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function));
        assertThat(support.contains(variable), is(true));

        NatSet lowSupport = bdd.support(low);
        assertThat(lowSupport.contains(variable), is(false));
        assertThat(NatSetFixtures.isSubset(lowSupport, support), is(true));
        NatSet highSupport = bdd.support(high);
        assertThat(highSupport.contains(variable), is(false));
        assertThat(NatSetFixtures.isSubset(highSupport, support), is(true));

        Set<NatSet> lowSolutions = new HashSet<>();
        Set<NatSet> highSolutions = new HashSet<>();

        // The low and high functions will be insensitive to the variable's value
        bdd.forEachSolution(function, support, assignment -> {
            MutableNatSet copy = NatSetFixtures.copyOf(assignment);
            copy.clear(variable);
            (assignment.contains(variable) ? highSolutions : lowSolutions).add(copy);
        });

        Set<NatSet> solutionOfLow = new HashSet<>();
        Set<NatSet> solutionOfHigh = new HashSet<>();
        bdd.forEachSolution(low, support, assignment -> {
            MutableNatSet copy = NatSetFixtures.copyOf(assignment);
            copy.clear(variable);
            assertThat(bdd.evaluate(function, copy), is(true));
            solutionOfLow.add(copy);
        });
        bdd.forEachSolution(high, support, assignment -> {
            MutableNatSet copy = NatSetFixtures.copyOf(assignment);
            copy.set(variable);
            assertThat(bdd.evaluate(function, copy), is(true));
            copy.clear(variable);
            solutionOfHigh.add(copy);
        });

        assertThat(solutionOfLow, is(lowSolutions));
        assertThat(solutionOfHigh, is(highSolutions));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testIfThenElse(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int ifFunction = dataPoint.first;
        int thenFunction = dataPoint.second;
        int elseFunction = dataPoint.third;
        assumeTrue(bdd.isValidFunction(ifFunction));
        assumeTrue(bdd.isValidFunction(thenFunction));
        assumeTrue(bdd.isValidFunction(elseFunction));

        int ifThenElse = bdd.reference(bdd.ifThenElse(ifFunction, thenFunction, elseFunction));

        for (boolean[] valuation :
                assignmentsOver(bdd.support(ifFunction), bdd.support(thenFunction), bdd.support(elseFunction))) {
            if (bdd.evaluate(ifFunction, valuation)) {
                assertThat(bdd.evaluate(ifThenElse, valuation), is(bdd.evaluate(thenFunction, valuation)));
            } else {
                assertThat(bdd.evaluate(ifThenElse, valuation), is(bdd.evaluate(elseFunction, valuation)));
            }
        }

        int notIf = bdd.reference(bdd.not(ifFunction));
        int ifImpliesThen = bdd.reference(bdd.implication(ifFunction, thenFunction));
        int notIfImpliesThen = bdd.reference(bdd.implication(notIf, elseFunction));
        int ifThenElseImplicationConstruction = bdd.and(ifImpliesThen, notIfImpliesThen);
        assertThat(
                String.format("ITE construction failed for %d,%d,%d", ifFunction, thenFunction, elseFunction),
                ifThenElse,
                is(ifThenElseImplicationConstruction));
        bdd.dereference(notIf, ifImpliesThen, notIfImpliesThen);

        bdd.dereference(ifThenElse);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testNaryAndOr(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int first = dataPoint.first;
        int second = dataPoint.second;
        int third = dataPoint.third;
        assumeTrue(bdd.isValidFunction(first));
        assumeTrue(bdd.isValidFunction(second));
        assumeTrue(bdd.isValidFunction(third));

        int and = bdd.reference(bdd.and(new int[] {first, second, third}));
        int or = bdd.reference(bdd.or(new int[] {first, second, third}));
        for (boolean[] valuation : assignmentsOver(bdd.support(first), bdd.support(second), bdd.support(third))) {
            boolean firstValue = bdd.evaluate(first, valuation);
            boolean secondValue = bdd.evaluate(second, valuation);
            boolean thirdValue = bdd.evaluate(third, valuation);
            assertThat(bdd.evaluate(and, valuation), is(firstValue && secondValue && thirdValue));
            assertThat(bdd.evaluate(or, valuation), is(firstValue || secondValue || thirdValue));
        }
        bdd.dereference(and, or);

        assertThat(bdd.and(new int[0]), is(bdd.trueFunction()));
        assertThat(bdd.or(new int[0]), is(bdd.falseFunction()));
        assertThat(bdd.and(new int[] {first}), is(first));
        assertThat(bdd.or(new int[] {first}), is(first));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testIfThenElseSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int ifFunction = dataPoint.first;
        int thenFunction = dataPoint.second;
        int elseFunction = dataPoint.third;
        assumeTrue(bdd.isValidFunction(ifFunction));
        assumeTrue(bdd.isValidFunction(thenFunction));
        assumeTrue(bdd.isValidFunction(elseFunction));

        int domain = bdd.reference(bdd.xor(bdd.xor(ifFunction, thenFunction), elseFunction));
        int direct = bdd.reference(bdd.ifThenElseSimplify(ifFunction, thenFunction, elseFunction, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.ifThenElse(ifFunction, thenFunction, elseFunction), domain));

        testSimplify(bdd, direct, indirect, domain);

        bdd.dereference(domain, direct, indirect);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testImplication(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int implication = bdd.reference(bdd.implication(function1, function2));

        for (boolean[] valuation : assignmentsOver(bdd.support(function1), bdd.support(function2))) {
            boolean implies = !bdd.evaluate(function1, valuation) || bdd.evaluate(function2, valuation);
            assertThat(bdd.evaluate(implication, valuation), is(implies));
        }

        int not1 = bdd.reference(bdd.not(function1));
        int implicationConstruction = bdd.or(not1, function2);
        assertThat(implication, is(implicationConstruction));
        bdd.dereference(not1);

        bdd.dereference(implication);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testImplicationSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.first;
        int function2 = dataPoint.second;
        int domain = dataPoint.third;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        assumeTrue(bdd.isValidFunction(domain));

        int direct = bdd.reference(bdd.implicationSimplify(function1, function2, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.implication(function1, function2), domain));
        testSimplify(bdd, direct, indirect, domain);

        bdd.dereference(direct, indirect);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testImplies(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        boolean implies = bdd.implies(function1, function2);
        int implication = bdd.implication(function1, function2);

        if (implies) {
            for (boolean[] valuation : valuations) {
                assertThat(!bdd.evaluate(function1, valuation) || bdd.evaluate(function2, valuation), is(true));
            }
            assertThat(implication, is(bdd.trueFunction()));
        } else {
            assertThat(implication, is(not(bdd.trueFunction())));
            bdd.forEachSolution(
                    bdd.not(implication),
                    valuation -> assertThat(
                            bdd.evaluate(function1, valuation) && !bdd.evaluate(function2, valuation), is(true)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testIntersects(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        boolean intersects = bdd.intersects(function1, function2);
        int and = bdd.and(function1, function2);

        if (intersects) {
            assertThat(and, is(not(bdd.falseFunction())));
        } else {
            assertThat(and, is(bdd.falseFunction()));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testIsVariable(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        SyntaxTreeNode root = dataPoint.tree.getRootNode();
        int function = dataPoint.function;

        if (root instanceof SyntaxTreeLiteral) {
            assertThat(bdd.treeToString(function), bdd.isVariable(function), is(true));
            assertThat(bdd.isVariableOrNegated(function), is(true));
        } else if (root instanceof SyntaxTreeNot) {
            SyntaxTreeNode child = ((SyntaxTreeNot) root).getChild();
            if (child instanceof SyntaxTreeLiteral) {
                assertThat(bdd.isVariable(function), is(false));
                assertThat(bdd.isVariableOrNegated(function), is(true));
            }
        }
        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function));
        assertThat(bdd.isVariableOrNegated(function), is(support.size() == 1));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testIterator(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Set<NatSet> satisfyingAssignments = new HashSet<>();
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(function, valuation)) {
                satisfyingAssignments.add(asSet(valuation));
            }
        }

        for (Cursor<NatSet> cursor = bdd.solutionCursor(function); cursor.valid(); cursor.advance()) {
            NatSet valuation = cursor.current();
            assertThat("Invalid solution", bdd.evaluate(function, valuation), is(true));
            assertThat("Duplicate solution", satisfyingAssignments.remove(valuation), is(true));
        }
        assertThat("Missing solution", satisfyingAssignments, empty());
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testNot(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        int not = bdd.reference(bdd.not(function));

        for (boolean[] valuation : assignmentsOver(bdd.support(function))) {
            assertThat(bdd.evaluate(not, valuation), is(!bdd.evaluate(function, valuation)));
        }

        assertThat(bdd.not(not), is(function));

        int notIteConstruction = bdd.ifThenElse(function, bdd.falseFunction(), bdd.trueFunction());
        assertThat(not, is(notIteConstruction));

        bdd.dereference(not);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testNotAnd(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int notAnd = bdd.reference(bdd.notAnd(function1, function2));

        for (boolean[] valuation : assignmentsOver(bdd.support(function1), bdd.support(function2))) {
            if (bdd.evaluate(function1, valuation)) {
                assertThat(bdd.evaluate(notAnd, valuation), is(!bdd.evaluate(function2, valuation)));
            } else {
                assertThat(bdd.evaluate(notAnd, valuation), is(true));
            }
        }

        int and = bdd.reference(bdd.and(function1, function2));
        int not1andNot2 = bdd.not(and);
        assertThat(notAnd, is(not1andNot2));
        bdd.dereference(and);

        int not2 = bdd.reference(bdd.not(function2));
        int notAndIteConstruction = bdd.ifThenElse(function1, not2, bdd.trueFunction());
        assertThat(notAnd, is(notAndIteConstruction));
        bdd.dereference(not2);

        bdd.dereference(notAnd);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testNotAndSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.first;
        int function2 = dataPoint.second;
        int domain = dataPoint.third;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        assumeTrue(bdd.isValidFunction(domain));

        int direct = bdd.reference(bdd.notAndSimplify(function1, function2, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.notAnd(function1, function2), domain));
        testSimplify(bdd, direct, indirect, domain);

        bdd.dereference(direct, indirect);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testOr(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int or = bdd.reference(bdd.or(function1, function2));

        for (boolean[] valuation : assignmentsOver(bdd.support(function1), bdd.support(function2))) {
            if (bdd.evaluate(function1, valuation)) {
                assertThat(bdd.evaluate(or, valuation), is(true));
            } else {
                assertThat(bdd.evaluate(or, valuation), is(bdd.evaluate(function2, valuation)));
            }
        }

        int not1 = bdd.reference(bdd.not(function1));
        int not2 = bdd.reference(bdd.not(function2));
        int not1andNot2 = bdd.reference(bdd.and(not1, not2));
        int orDeMorganConstruction = bdd.not(not1andNot2);
        assertThat(or, is(orDeMorganConstruction));
        bdd.dereference(not1, not2, not1andNot2);

        int orIteConstruction = bdd.ifThenElse(function1, bdd.trueFunction(), function2);
        assertThat(or, is(orIteConstruction));

        bdd.dereference(or);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testOrSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.first;
        int function2 = dataPoint.second;
        int domain = dataPoint.third;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        assumeTrue(bdd.isValidFunction(domain));

        int direct = bdd.reference(bdd.orSimplify(function1, function2, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.or(function1, function2), domain));
        testSimplify(bdd, direct, indirect, domain);

        bdd.dereference(direct, indirect);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testReferenceAndDereference(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        int node = bdd.nodeFor(function);
        assumeFalse(bdd.isSaturatedNode(node));

        int referenceCount = bdd.nodeReferenceCount(node);
        for (int i = referenceCount; i > 0; i--) {
            bdd.dereference(function);
            assertThat(bdd.nodeReferenceCount(node), is(i - 1));
        }
        for (int i = 0; i < referenceCount; i++) {
            bdd.reference(function);
            assertThat(bdd.nodeReferenceCount(node), is(i + 1));
        }
        assertThat(bdd.nodeReferenceCount(node), is(referenceCount));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    @SuppressWarnings("PMD.ExceptionAsFlowControl")
    void testReferenceGuard(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));
        assumeFalse(bdd.isSaturatedNode(bdd.nodeFor(function)));

        int node = bdd.nodeFor(function);
        int referenceCount = bdd.nodeReferenceCount(node);
        try {
            //noinspection NestedTryStatement
            try (DecisionDiagram.ReferenceGuard guard = new DecisionDiagram.ReferenceGuard(function, bdd)) {
                assertThat(bdd.nodeReferenceCount(node), is(referenceCount + 1));
                assertThat(guard.diagram, is(bdd));
                assertThat(guard.function, is(function));
                //noinspection ThrowCaughtLocally - We deliberately want to test the exception handling here
                throw new IllegalArgumentException("Bogus");
            }
        } catch (IllegalArgumentException ignored) {
            assertThat(bdd.nodeReferenceCount(node), is(referenceCount));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testRestrict(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Random restrictRandom = new Random(function);
        MutableNatSet restrictedVariables = MutableNatSet.dense(bdd.numberOfVariables());
        MutableNatSet restrictedVariableValues = MutableNatSet.dense(bdd.numberOfVariables());
        int[] composeArray = new int[bdd.numberOfVariables()];
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < bdd.numberOfVariables(); j++) {
                if (restrictRandom.nextBoolean()) {
                    restrictedVariables.set(j);
                    if (restrictRandom.nextBoolean()) {
                        restrictedVariableValues.set(j);
                        composeArray[j] = bdd.trueFunction();
                    } else {
                        composeArray[j] = bdd.falseFunction();
                    }
                } else {
                    composeArray[j] = bdd.placeholder();
                }
            }

            int restricted =
                    bdd.reference(bdd.restrict(function, Cube.of(restrictedVariableValues, restrictedVariables)));
            int composed = bdd.compose(function, composeArray);
            assertThat(restricted, is(composed));
            bdd.dereference(restricted);

            MutableNatSet restrictSupport = MutableNatSet.copyOf(bdd.support(restricted));
            restrictSupport.and(restrictedVariables);
            assertThat(restrictSupport.isEmpty(), is(true));

            restrictedVariables.clear();
            restrictedVariableValues.clear();
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testSatisfyingAssignment(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        if (function == bdd.falseFunction()) {
            assertThrowsExactly(NoSuchElementException.class, () -> bdd.satisfyingAssignment(function));
        } else {
            assertThat(bdd.evaluate(function, bdd.satisfyingAssignment(function)), is(true));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testSatisfyingAssignmentIn(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int and = bdd.reference(bdd.and(function1, function2));

        if (and == bdd.falseFunction()) {
            assertThat(bdd.satisfyingAssignmentIn(function1, function2), is(Optional.empty()));
        } else {
            var assignment = bdd.satisfyingAssignmentIn(function1, function2);
            assertThat(assignment.isPresent(), is(true));
            assertThat(bdd.evaluate(function1, assignment.get()), is(true));
            assertThat(bdd.evaluate(function2, assignment.get()), is(true));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testSupportTree(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));
        Set<Integer> containedVariables = dataPoint.tree.containedVariables();
        assumeTrue(containedVariables.size() <= 5);

        // For each variable, we iterate through all possible valuations and check if there ever is any
        // difference.
        if (containedVariables.isEmpty()) {
            assertThat(bdd.support(function).isEmpty(), is(true));
        } else {
            // Have some arbitrary ordering
            List<Integer> containedVariableList = new ArrayList<>(containedVariables);
            MutableNatSet valuation = MutableNatSet.dense(variableCount);
            MutableNatSet support = MutableNatSet.dense(variableCount);

            for (int checkedVariable : containedVariableList) {
                int checkedContainedIndex = containedVariableList.indexOf(checkedVariable);
                // Only iterate over all possible valuations of involved variables, otherwise this test
                // might explode
                for (int i = 0; i < 1 << containedVariables.size(); i++) {
                    if (((i >>> checkedContainedIndex) & 1) == 1) {
                        continue;
                    }
                    valuation.clear();
                    for (int j = 0; j < containedVariables.size(); j++) {
                        if (((i >>> j) & 1) == 1) {
                            valuation.set(containedVariableList.get(j));
                        }
                    }

                    // Check if
                    boolean negative = bdd.evaluate(function, valuation);
                    valuation.set(checkedVariable);
                    boolean positive = bdd.evaluate(function, valuation);
                    if (negative != positive) {
                        support.set(checkedVariable);
                        break;
                    }
                }
            }
            assertThat(bdd.support(function), is(support));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testSupportUnion(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        NatSet function1Support = bdd.support(function1);
        NatSet function2Support = bdd.support(function2);
        MutableNatSet supportUnion = NatSetFixtures.copyOf(function1Support);
        supportUnion.or(function2Support);

        for (int resultFunction : doBddOperations(bdd, function1, function2)) {
            NatSet operationSupport = bdd.support(resultFunction);
            operationSupport.intStream().forEach(setBit -> assertThat(supportUnion.contains(setBit), is(true)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testSupportCutoff(UnaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function));
        MutableNatSet supportRestrict = MutableNatSet.dense(bdd.numberOfVariables());
        for (int i = 0; i < bdd.numberOfVariables(); i += 2) {
            supportRestrict.set(i);
        }
        NatSet cutoffSupport = bdd.supportFiltered(function, supportRestrict);
        support.and(supportRestrict);
        assertThat(cutoffSupport, is(support));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testSimplify(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function = dataPoint.left;
        int domain = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function));
        assumeTrue(bdd.isValidFunction(domain));

        int simplify = bdd.reference(bdd.simplify(function, domain));

        for (boolean[] valuation : assignmentsOver(bdd.support(function), bdd.support(domain))) {
            if (bdd.evaluate(domain, valuation)) {
                assertThat(bdd.evaluate(simplify, valuation), is(bdd.evaluate(function, valuation)));
            }
        }

        int ifThenElse = bdd.reference(bdd.ifThenElse(domain, simplify, function));
        assertThat(ifThenElse, is(function));
        bdd.dereference(ifThenElse);

        // SIMPLIFY(f, g) & g == f & g
        int simplifyAnd = bdd.reference(bdd.and(simplify, domain));
        int and = bdd.reference(bdd.and(function, domain));
        assertThat(simplifyAnd, is(and));
        bdd.dereference(simplifyAnd, and);

        // (SIMPLIFY(f, g) <-> f) & g == 0
        int xor = bdd.reference(bdd.xor(simplify, function));
        assertThat(bdd.intersects(domain, xor), is(false));

        bdd.dereference(simplify);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testUpdateWith(BinaryDataPoint<BinaryDd> dataPoint) {
        // This test simply tests if the semantics of updateWith are as specified, i.e.
        // updateWith(result, input) reduces the reference count of the input and increases that of
        // result
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeFalse(function1 == function2);
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        int node1 = bdd.nodeFor(function1);
        int node2 = bdd.nodeFor(function2);
        assumeFalse(bdd.isSaturatedNode(node1));
        assumeFalse(bdd.isSaturatedNode(node2));

        bdd.reference(function1);
        bdd.reference(function2);
        int node1count = bdd.nodeReferenceCount(node1);
        int node2count = bdd.nodeReferenceCount(node2);
        bdd.updateWith(function1, function1);
        assertThat(bdd.nodeReferenceCount(node1), is(node1count));

        for (int resultFunction : doBddOperations(bdd, function1, function2)) {
            int resultNode = bdd.nodeFor(resultFunction);
            if (bdd.isSaturatedNode(resultNode)) {
                continue;
            }
            int resultCount = bdd.nodeReferenceCount(resultNode);

            assertThat(bdd.updateWith(resultFunction, function1), is(resultFunction));

            if (resultNode == node1) {
                assertThat(bdd.nodeReferenceCount(node1), is(node1count));
                assertThat(bdd.nodeReferenceCount(node2), is(node2count));
                assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount));
            } else if (resultNode == node2) {
                assertThat(bdd.nodeReferenceCount(node1), is(node1count - 1));
                assertThat(bdd.nodeReferenceCount(node2), is(node2count + 1));
                assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount + 1));
            } else {
                assertThat(bdd.nodeReferenceCount(node1), is(node1count - 1));
                assertThat(bdd.nodeReferenceCount(node2), is(node2count));
                assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount + 1));
            }

            bdd.reference(function1);
            bdd.dereference(resultFunction);

            assertThat(bdd.nodeReferenceCount(node1), is(node1count));
            assertThat(bdd.nodeReferenceCount(node2), is(node2count));
            assertThat(bdd.nodeReferenceCount(resultNode), is(resultCount));
        }

        bdd.dereference(function1);
        bdd.dereference(function2);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testXor(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        int xor = bdd.reference(bdd.xor(function1, function2));

        for (boolean[] valuation : assignmentsOver(bdd.support(function1), bdd.support(function2))) {
            if (bdd.evaluate(function1, valuation)) {
                assertThat(bdd.evaluate(xor, valuation), is(!bdd.evaluate(function2, valuation)));
            } else {
                assertThat(bdd.evaluate(xor, valuation), is(bdd.evaluate(function2, valuation)));
            }
        }

        int not1 = bdd.reference(bdd.not(function1));
        int not2 = bdd.reference(bdd.not(function2));
        int not1and2 = bdd.reference(bdd.and(not1, function2));
        int not2and1 = bdd.reference(bdd.and(function1, not2));
        int xorConstruction = bdd.or(not2and1, not1and2);
        assertThat(xor, is(xorConstruction));
        bdd.dereference(not1, not2, not1and2, not2and1);

        bdd.dereference(xor);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    void testXorSimplify(TernaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.first;
        int function2 = dataPoint.second;
        int domain = dataPoint.third;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));
        assumeTrue(bdd.isValidFunction(domain));

        int direct = bdd.reference(bdd.xorSimplify(function1, function2, domain));
        int indirect = bdd.reference(bdd.simplify(bdd.xor(function1, function2), domain));
        testSimplify(bdd, direct, indirect, domain);

        bdd.dereference(direct, indirect);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testCanonical(BinaryDataPoint<BinaryDd> dataPoint) {
        BinaryDd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        MutableNatSet support = MutableNatSet.copyOf(bdd.support(function1));
        bdd.supportTo(function2, support);

        boolean anyDistinct = false;
        var iterator = NatSetFixtures.powerSetIterator(support);
        while (iterator.hasNext()) {
            MutableNatSet next = MutableNatSet.copyOf(iterator.next());
            if (bdd.evaluate(function1, next) != bdd.evaluate(function2, next)) {
                anyDistinct = true;
                break;
            }
        }
        assertThat(anyDistinct, is(function1 != function2));
    }

    private static final class ExtendedInfo {
        final int initialNodeCount;
        final int initialReferencedNodeCount;
        final Info<BinaryDd> bddInfo;

        ExtendedInfo(BinaryDd bdd, Info<BinaryDd> bddInfo) {
            initialNodeCount = bdd.nodeCount();
            initialReferencedNodeCount = bdd.referencedNodeCount();
            this.bddInfo = bddInfo;
        }
    }

    private static final class BitSetComparator implements Comparator<NatSet> {
        @Override
        public int compare(NatSet one, NatSet other) {
            int oneLength = one.length();
            int otherLength = other.length();
            for (int i = 0; i < oneLength; i++) {
                if (one.contains(i)) {
                    if (!other.contains(i)) {
                        return 1;
                    }
                } else if (other.contains(i)) {
                    return -1;
                }
            }
            return otherLength > oneLength ? -1 : 0;
        }
    }

    private static final class BddPathExplorer {
        private final Set<NatSet> assignments;
        private final BinaryDecisionDiagram bdd;

        BddPathExplorer(BinaryDecisionDiagram bdd, int startingFunction) {
            this.bdd = bdd;
            this.assignments = new HashSet<>();
            if (startingFunction == bdd.trueFunction()) {
                assignments.add(MutableNatSet.dense(bdd.numberOfVariables()));
            } else if (startingFunction != bdd.falseFunction()) {
                List<Integer> path = new ArrayList<>();
                path.add(startingFunction);
                recurse(path, MutableNatSet.dense(bdd.numberOfVariables()));
            }
        }

        Set<NatSet> getAssignments() {
            return assignments;
        }

        private void recurse(List<Integer> currentPath, NatSet currentAssignment) {
            int pathLeaf = currentPath.get(currentPath.size() - 1);
            int low = bdd.lowOf(pathLeaf);
            int high = bdd.highOf(pathLeaf);

            if (low == bdd.trueFunction()) {
                assignments.add(NatSetFixtures.copyOf(currentAssignment));
            } else if (low != bdd.falseFunction()) {
                List<Integer> recursePath = new ArrayList<>(currentPath);
                recursePath.add(low);
                recurse(recursePath, currentAssignment);
            }

            if (high != bdd.falseFunction()) {
                MutableNatSet assignment = NatSetFixtures.copyOf(currentAssignment);
                assignment.set(bdd.decisionVariable(pathLeaf));
                if (high == bdd.trueFunction()) {
                    assignments.add(assignment);
                } else {
                    List<Integer> recursePath = new ArrayList<>(currentPath);
                    recursePath.add(high);
                    recurse(recursePath, assignment);
                }
            }
        }
    }
}
