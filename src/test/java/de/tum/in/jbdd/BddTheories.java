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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final Comparator<BitSet> LEXICOGRAPHIC = new BitSetComparator();
    private static final Logger logger = Logger.getLogger(BddTheories.class.getName());

    private static final Map<TestBdd, ExtendedInfo> infoMap = new HashMap<>();
    private static final int SKIP_CHECK_RANDOM_BOUND = 500;
    private static final double FACTOR = 0.25;
    private static final int binaryCount = (int) (4_000 * FACTOR);
    private static final int ternaryCount = (int) (3_000 * FACTOR);
    private static final int unaryCount = (int) (1_000 * FACTOR);
    private static final int treeDepth = 20;
    private static final int treeWidth = 35;
    private static final int variableCount = 10;
    private static final int MAX_ASSIGNMENT_VARIABLES = 8;
    private static final int[] EMPTY_INTS = new int[0];
    private static final Iterable<boolean[]> valuations;
    private static final Collection<UnaryDataPoint<TestBdd>> unary;
    private static final Collection<BinaryDataPoint<TestBdd>> binary;
    private static final Collection<TernaryDataPoint<TestBdd>> ternary;
    private final Random skipCheckRandom = new Random(0L);

    static {
        /* The @DataPoints annotated methods are called multiple times - which would create
         * new variables each time, exploding the runtime of the tests. Hence, we create the
         * structure once. */

        BddConfiguration config = ImmutableBddConfiguration.builder().build();
        List<TestBdd> bdds = List.of(
                new TestBddImpl(new BddImpl(config)),
                new MddAsTestBdd(new MddImpl(config)),
                new MtBddAsTestBdd(new BddImpl(config).mtbdd()));

        int bddCount = bdds.size();
        List<Set<UnaryDataPoint<TestBdd>>> unaryPoints = new ArrayList<>(bddCount);
        List<Set<BinaryDataPoint<TestBdd>>> binaryPoints = new ArrayList<>(bddCount);
        List<Set<TernaryDataPoint<TestBdd>>> ternaryPoints = new ArrayList<>(bddCount);

        for (TestBdd bdd : bdds) {
            Info<TestBdd> bddInfo =
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

    @SuppressWarnings("TypeMayBeWeakened")
    private static Set<Integer> doBddOperations(TestBdd bdd, int function1, int function2) {
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
        infoMap.keySet().forEach(TestBdd::check);
    }

    private static Iterator<boolean[]> getArrayIterator(BitSet enabledVariables) {
        boolean[] base = new boolean[variableCount];
        enabledVariables.stream().forEach(i -> base[i] = true);
        return new ScopedAssignments.PowerSetIterator(base);
    }

    private static Iterable<boolean[]> assignmentsOver(BitSet... variableSets) {
        return ScopedAssignments.of(variableCount, MAX_ASSIGNMENT_VARIABLES, variableSets);
    }

    private static BitSet asSet(boolean[] array) {
        BitSet set = new BitSet(array.length);
        for (int i = 0; i < array.length; i++) {
            if (array[i]) {
                set.set(i);
            }
        }
        return set;
    }

    static Stream<BinaryDataPoint<TestBdd>> binary() {
        return binary.stream();
    }

    static Stream<TernaryDataPoint<TestBdd>> ternary() {
        return ternary.stream();
    }

    static Stream<UnaryDataPoint<TestBdd>> unary() {
        return unary.stream();
    }

    private void testSimplify(Bdd bdd, int direct, int indirect, int domain) {
        int directOnDomain = bdd.reference(bdd.and(direct, domain));
        int indirectOnDomain = bdd.reference(bdd.and(indirect, domain));
        assertThat(directOnDomain, is(indirectOnDomain));
        bdd.dereference(directOnDomain, indirectOnDomain);
    }

    @SuppressWarnings("unused")
    static Collection<TestBdd> bdds() {
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
    }

    @AfterAll
    static void statistics() {
        for (TestBdd bdd : infoMap.keySet()) {
            logger.log(Level.INFO, DecisionDiagram.formatStatistics(bdd.statistics()));
        }
    }

    @AfterEach
    void clearCaches() {
        for (TestBdd bdd : infoMap.keySet()) {
            if (skipCheckRandom.nextInt(100) == 0) {
                bdd.invalidateCache();
            }
        }
    }

    @AfterEach
    void checkInvariants() {
        if (skipCheckRandom.nextInt(SKIP_CHECK_RANDOM_BOUND) == 0) {
            doCheckInvariants();
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testAnd(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testAndSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testAndNot(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testAndNotSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    private static int[] buildComposeArray(TestBdd bdd, int function, SyntaxTree syntaxTree) {
        Info<TestBdd> bddInfo = infoMap.get(bdd).bddInfo;
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
    private static SyntaxTree buildComposeTree(TestBdd bdd, SyntaxTree syntaxTree, int[] composeArray) {
        Info<TestBdd> bddInfo = infoMap.get(bdd).bddInfo;
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
    void testComposeTree(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testComposeSimple(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testComposeRepeated(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        Info<TestBdd> bddInfo = infoMap.get(bdd).bddInfo;
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
    void testComposeRelabel(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        Info<TestBdd> bddInfo = infoMap.get(bdd).bddInfo;
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

        BitSet pathMap = new BitSet();
        bdd.forEachPath(composeNode, path -> {
            pathMap.clear();
            BitSets.forEach(path.assignment, i -> pathMap.set(inverse[i]));
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
    void testComposeTreeSimplify(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testConsume(BinaryDataPoint<TestBdd> dataPoint) {
        // This test simply tests if the semantics of consume are as specified, i.e.
        // consume(result, input1, input2) reduces the reference count of the inputs and increases that
        // of result
        TestBdd bdd = dataPoint.bdd;
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
    void testCountSatisfyingAssignments(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    @MethodSource("binary")
    void testCountSatisfyingAssignmentsIn(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    @MethodSource("unary")
    void testCountSatisfyingAssignmentsRestrictedSimple(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet set = new BitSet();
        set.set(0, bdd.numberOfVariables());

        assertThat(
                bdd.countSatisfyingAssignments(function, set).longValueExact(),
                is(bdd.countSatisfyingAssignments(function).longValueExact()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testCountSatisfyingAssignmentsRestricted(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Random random = new Random(function);
        BitSet set = new BitSet();
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
    void testEquivalence(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testEquivalenceSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testEvaluateTree(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));
        assumeTrue(dataPoint.tree.depth() <= 5);

        for (boolean[] valuation : assignmentsOver(bdd.support(function))) {
            assertThat(bdd.evaluate(function, valuation), is(dataPoint.tree.evaluate(valuation)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testExists(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet quantificationBitSet = new BitSet(bdd.numberOfVariables());
        Random quantificationRandom = new Random(function);
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (quantificationRandom.nextInt(bdd.numberOfVariables()) < 5) {
                quantificationBitSet.set(i);
            }
        }
        assumeTrue(quantificationBitSet.cardinality() <= 5);

        int exists = bdd.exists(function, quantificationBitSet);
        BitSet supportIntersection = bdd.support(exists);
        supportIntersection.and(quantificationBitSet);
        assertThat(supportIntersection.isEmpty(), is(true));

        BitSet unquantifiedVariables = BitSets.copyOf(quantificationBitSet);
        unquantifiedVariables.flip(0, bdd.numberOfVariables());

        assertThat(
                Iterators.all(BitSets.powerSetIterator(unquantifiedVariables), unquantifiedAssignment -> {
                    boolean bddEvaluation = bdd.evaluate(exists, Objects.requireNonNull(unquantifiedAssignment));
                    boolean setEvaluation = Iterators.any(BitSets.powerSetIterator(quantificationBitSet), bitSet -> {
                        BitSet actualBitSet = BitSets.copyOf(Objects.requireNonNull(bitSet));
                        actualBitSet.or(unquantifiedAssignment);
                        return bdd.evaluate(function, actualBitSet);
                    });
                    return bddEvaluation == setEvaluation;
                }),
                is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForall(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet quantificationBitSet = new BitSet(bdd.numberOfVariables());
        Random quantificationRandom = new Random(function);
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (quantificationRandom.nextInt(bdd.numberOfVariables()) < 5) {
                quantificationBitSet.set(i);
            }
        }
        assumeTrue(quantificationBitSet.cardinality() <= 5);

        int forall = bdd.forall(function, quantificationBitSet);
        BitSet supportIntersection = bdd.support(forall);
        supportIntersection.and(quantificationBitSet);
        assertThat(supportIntersection.isEmpty(), is(true));

        BitSet unquantifiedVariables = BitSets.copyOf(quantificationBitSet);
        unquantifiedVariables.flip(0, bdd.numberOfVariables());

        assertThat(
                Iterators.all(BitSets.powerSetIterator(unquantifiedVariables), unquantifiedAssignment -> {
                    boolean bddEvaluation = bdd.evaluate(forall, Objects.requireNonNull(unquantifiedAssignment));
                    boolean setEvaluation = Iterators.all(BitSets.powerSetIterator(quantificationBitSet), bitSet -> {
                        BitSet actualBitSet = BitSets.copyOf(Objects.requireNonNull(bitSet));
                        actualBitSet.or(unquantifiedAssignment);
                        return bdd.evaluate(function, actualBitSet);
                    });
                    return bddEvaluation == setEvaluation;
                }),
                is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForEachPathSimple(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet support = bdd.support(function);
        assumeTrue(support.cardinality() <= 7);

        BitSet supportFromSolutions = new BitSet(bdd.numberOfVariables());
        BitSet supportFromPathSupport = new BitSet(bdd.numberOfVariables());

        List<BitSet> paths = new ArrayList<>();
        bdd.forEachPath(function, path -> {
            paths.add(path.copyAssignment());
            supportFromPathSupport.or(path.copySupport());
        });
        assertThat(supportFromPathSupport, is(support));

        Iterator<BitSet> solutionIterator = paths.iterator();
        BitSet previous = null;
        Set<BitSet> solutionBitSets = new HashSet<>();

        while (solutionIterator.hasNext()) {
            BitSet next = solutionIterator.next();
            if (previous != null) {
                assertThat(LEXICOGRAPHIC.compare(previous, next), is(-1));
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
        Set<BitSet> assignments = new BddPathExplorer(bdd, function).getAssignments();
        assertThat(solutionBitSets, is(assignments));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForEachPathWithSupportSimple(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet support = bdd.support(function);
        assumeTrue(support.cardinality() <= 7);

        BitSet supportRestriction = new BitSet();
        Random mixer = new Random(bdd.hashCode() + function);
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            supportRestriction.set(i, mixer.nextBoolean());
        }

        BitSet supportFromPathSupport = new BitSet(bdd.numberOfVariables());

        Set<BitSet> paths = new HashSet<>();
        bdd.forEachPartialPath(function, supportRestriction, path -> {
            assertThat(BitSets.isSubset(path.support(), supportRestriction), is(true));
            paths.add(path.copyAssignment());
            supportFromPathSupport.or(path.support());
        });
        var supportCopy = BitSets.copyOf(support);
        supportCopy.and(supportRestriction);
        assertThat(supportFromPathSupport, is(supportCopy));

        for (BitSet path : paths) {
            assertThat(BitSets.isSubset(path, supportRestriction), is(true));
        }

        // Build up all minimal solutions using a naive algorithm
        Set<BitSet> assignments = new HashSet<>();
        for (BitSet assignment : new BddPathExplorer(bdd, function).getAssignments()) {
            var copy = BitSets.copyOf(assignment);
            copy.and(supportRestriction);
            assignments.add(copy);
        }
        assertThat(paths, is(assignments));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForEachPathWithRelevantSet(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet support = bdd.support(function);
        assumeTrue(support.cardinality() <= 7);

        List<BitSet> minimalSolutions = new ArrayList<>();
        int variableCount = bdd.numberOfVariables();
        bdd.forEachPath(function, path -> {
            minimalSolutions.add(path.copyAssignment());
            BitSet nonRelevantVariables = path.copySupport();
            nonRelevantVariables.flip(0, variableCount);
            assertThat(nonRelevantVariables.intersects(path.assignment()), is(false));
            assertThat(bdd.evaluate(function, path.assignment()), is(true));

            Iterator<BitSet> iterator = BitSets.powerSetIterator(nonRelevantVariables);
            while (iterator.hasNext()) {
                BitSet next = BitSets.copyOf(iterator.next());
                next.or(path.assignment());
                assertThat(bdd.evaluate(function, next), is(true));
            }
        });

        List<BitSet> otherMinimalSolutions = new ArrayList<>();
        bdd.forEachPath(function, path -> otherMinimalSolutions.add(path.copyAssignment()));
        assertThat(minimalSolutions, is(otherMinimalSolutions));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testPathIterator(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet support = bdd.support(function);
        assumeTrue(support.cardinality() <= 7);

        List<BinaryPath> paths = new ArrayList<>();
        bdd.forEachPath(function, path -> paths.add(path.copy()));

        List<BinaryPath> iteratorPaths = new ArrayList<>();
        bdd.pathIterator(function).forEachRemaining(path -> iteratorPaths.add(path.copy()));

        assertThat(iteratorPaths, is(paths));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testForEach(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Set<BitSet> satisfyingAssignments = new HashSet<>();
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
    void testGetLowAndHigh(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidNonConstantFunction(function));

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
        BitSet support = bdd.support(function);
        assertThat(support.get(variable), is(true));

        BitSet lowSupport = bdd.support(low);
        assertThat(lowSupport.get(variable), is(false));
        assertThat(BitSets.isSubset(lowSupport, support), is(true));
        BitSet highSupport = bdd.support(high);
        assertThat(highSupport.get(variable), is(false));
        assertThat(BitSets.isSubset(highSupport, support), is(true));

        Set<BitSet> lowSolutions = new HashSet<>();
        Set<BitSet> highSolutions = new HashSet<>();

        // The low and high functions will be insensitive to the variable's value
        bdd.forEachSolution(function, support, assignment -> {
            BitSet copy = BitSets.copyOf(assignment);
            copy.clear(variable);
            (assignment.get(variable) ? highSolutions : lowSolutions).add(copy);
        });

        Set<BitSet> solutionOfLow = new HashSet<>();
        Set<BitSet> solutionOfHigh = new HashSet<>();
        bdd.forEachSolution(low, support, assignment -> {
            BitSet copy = BitSets.copyOf(assignment);
            copy.clear(variable);
            assertThat(bdd.evaluate(function, copy), is(true));
            solutionOfLow.add(copy);
        });
        bdd.forEachSolution(high, support, assignment -> {
            BitSet copy = BitSets.copyOf(assignment);
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
    void testIfThenElse(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testIfThenElseSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testImplication(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testImplicationSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testImplies(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testIntersects(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testIsVariable(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
        BitSet support = bdd.support(function);
        assertThat(bdd.isVariableOrNegated(function), is(support.cardinality() == 1));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testIterator(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Set<BitSet> satisfyingAssignments = new HashSet<>();
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(function, valuation)) {
                satisfyingAssignments.add(asSet(valuation));
            }
        }

        bdd.solutionIterator(function).forEachRemaining(valuation -> {
            assertThat("Invalid solution", bdd.evaluate(function, valuation), is(true));
            assertThat("Duplicate solution", satisfyingAssignments.remove(valuation), is(true));
        });
        assertThat("Missing solution", satisfyingAssignments, empty());
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testNot(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testNotAnd(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testNotAndSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testOr(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testOrSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testReferenceAndDereference(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testReferenceGuard(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testRestrict(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        Random restrictRandom = new Random(function);
        BitSet restrictedVariables = new BitSet(bdd.numberOfVariables());
        BitSet restrictedVariableValues = new BitSet(bdd.numberOfVariables());
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

            int restricted = bdd.reference(bdd.restrict(function, restrictedVariables, restrictedVariableValues));
            int composed = bdd.compose(function, composeArray);
            assertThat(restricted, is(composed));
            bdd.dereference(restricted);

            BitSet restrictSupport = bdd.support(restricted);
            restrictSupport.and(restrictedVariables);
            assertThat(restrictSupport.isEmpty(), is(true));

            restrictedVariables.clear();
            restrictedVariableValues.clear();
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testSatisfyingAssignment(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testSatisfyingAssignmentIn(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testSupportTree(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
            BitSet valuation = new BitSet(variableCount);
            BitSet support = new BitSet(variableCount);

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
    void testSupportUnion(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        BitSet function1Support = bdd.support(function1);
        BitSet function2Support = bdd.support(function2);
        BitSet supportUnion = BitSets.copyOf(function1Support);
        supportUnion.or(function2Support);

        for (int resultFunction : doBddOperations(bdd, function1, function2)) {
            BitSet operationSupport = bdd.support(resultFunction);
            operationSupport.stream().forEach(setBit -> assertThat(supportUnion.get(setBit), is(true)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    void testSupportCutoff(UnaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function = dataPoint.function;
        assumeTrue(bdd.isValidFunction(function));

        BitSet support = bdd.support(function);
        BitSet supportRestrict = new BitSet(bdd.numberOfVariables());
        for (int i = 0; i < bdd.numberOfVariables(); i += 2) {
            supportRestrict.set(i);
        }
        BitSet cutoffSupport = bdd.supportFiltered(function, supportRestrict);
        support.and(supportRestrict);
        assertThat(cutoffSupport, is(support));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    void testSimplify(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testUpdateWith(BinaryDataPoint<TestBdd> dataPoint) {
        // This test simply tests if the semantics of updateWith are as specified, i.e.
        // updateWith(result, input) reduces the reference count of the input and increases that of
        // result
        TestBdd bdd = dataPoint.bdd;
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
    void testXor(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testXorSimplify(TernaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
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
    void testCanonical(BinaryDataPoint<TestBdd> dataPoint) {
        TestBdd bdd = dataPoint.bdd;
        int function1 = dataPoint.left;
        int function2 = dataPoint.right;
        assumeTrue(bdd.isValidFunction(function1));
        assumeTrue(bdd.isValidFunction(function2));

        BitSet support = bdd.support(function1);
        bdd.supportTo(function2, support);

        boolean anyDistinct = false;
        var iterator = BitSets.powerSetIterator(support);
        while (iterator.hasNext()) {
            BitSet next = iterator.next();
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
        final Info<TestBdd> bddInfo;

        ExtendedInfo(TestBdd bdd, Info<TestBdd> bddInfo) {
            initialNodeCount = bdd.nodeCount();
            initialReferencedNodeCount = bdd.referencedNodeCount();
            this.bddInfo = bddInfo;
        }
    }

    private static final class BitSetComparator implements Comparator<BitSet> {
        @Override
        public int compare(BitSet one, BitSet other) {
            int oneLength = one.length();
            int otherLength = other.length();
            for (int i = 0; i < oneLength; i++) {
                if (one.get(i)) {
                    if (!other.get(i)) {
                        return 1;
                    }
                } else if (other.get(i)) {
                    return -1;
                }
            }
            return otherLength > oneLength ? -1 : 0;
        }
    }

    private static final class BddPathExplorer {
        private final Set<BitSet> assignments;
        private final Bdd bdd;

        BddPathExplorer(Bdd bdd, int startingFunction) {
            this.bdd = bdd;
            this.assignments = new HashSet<>();
            if (startingFunction == bdd.trueFunction()) {
                assignments.add(new BitSet(bdd.numberOfVariables()));
            } else if (startingFunction != bdd.falseFunction()) {
                List<Integer> path = new ArrayList<>();
                path.add(startingFunction);
                recurse(path, new BitSet(bdd.numberOfVariables()));
            }
        }

        Set<BitSet> getAssignments() {
            return assignments;
        }

        private void recurse(List<Integer> currentPath, BitSet currentAssignment) {
            int pathLeaf = currentPath.get(currentPath.size() - 1);
            int low = bdd.lowOf(pathLeaf);
            int high = bdd.highOf(pathLeaf);

            if (low == bdd.trueFunction()) {
                assignments.add(BitSets.copyOf(currentAssignment));
            } else if (low != bdd.falseFunction()) {
                List<Integer> recursePath = new ArrayList<>(currentPath);
                recursePath.add(low);
                recurse(recursePath, currentAssignment);
            }

            if (high != bdd.falseFunction()) {
                BitSet assignment = BitSets.copyOf(currentAssignment);
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
