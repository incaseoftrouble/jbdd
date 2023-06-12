/*
 * Copyright (C) 2017 (See AUTHORS)
 *
 * This file is part of JBDD.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JBDD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tum.in.jbdd;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
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
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests various logical functions of BDDs and checks invariants.
 */
@SuppressWarnings({
    "checkstyle:javadoc",
    "AssignmentOrReturnOfFieldWithMutableType",
    "AccessingNonPublicFieldOfAnotherObject",
    "StaticCollection",
    "NewClassNamingConvention",
    "PMD.ClassNamingConventions"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BddTheories {
    private static final Comparator<BitSet> LEXICOGRAPHIC = new BitSetComparator();
    private static final Logger logger = Logger.getLogger(BddTheories.class.getName());

    private static final Map<BddImpl, ExtendedInfo> infoMap = new HashMap<>();
    private static final int SKIP_CHECK_RANDOM_BOUND = 500;
    private static final int binaryCount = 10_000;
    private static final int ternaryCount = 5_000;
    private static final int treeDepth = 15;
    private static final int treeWidth = 30;
    private static final int unaryCount = 10_000;
    private static final int variableCount = 10;
    private static final int[] EMPTY_INTS = new int[0];
    private static final Iterable<boolean[]> valuations;
    private static final Collection<Generator.UnaryDataPoint<BddImpl>> unary;
    private static final Collection<Generator.BinaryDataPoint<BddImpl>> binary;
    private static final Collection<Generator.TernaryDataPoint<BddImpl>> ternary;
    private final Random skipCheckRandom = new Random(0L);

    static {
        /* The @DataPoints annotated methods are called multiple times - which would create
         * new variables each time, exploding the runtime of the tests. Hence, we create the
         * structure once. */

        BddConfiguration config = ImmutableBddConfiguration.builder()
                .logStatisticsOnShutdown(false)
                .build();
        List<BddImpl> bdds = List.of(new BddImpl(false, config), new BddImpl(true, config));

        int bddCount = bdds.size();
        List<Set<Generator.UnaryDataPoint<BddImpl>>> unaryPoints = new ArrayList<>(bddCount);
        List<Set<Generator.BinaryDataPoint<BddImpl>>> binaryPoints = new ArrayList<>(bddCount);
        List<Set<Generator.TernaryDataPoint<BddImpl>>> ternaryPoints = new ArrayList<>(bddCount);

        for (BddImpl bdd : bdds) {
            Generator.Info<BddImpl> bddInfo =
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
        valuations = () -> new SimplePowerSetIterator(variableCount);

        logger.log(Level.INFO, "Finished initialization");
    }

    @SuppressWarnings("UseOfClone")
    static BitSet copyBitSet(BitSet bitSet) {
        return (BitSet) bitSet.clone();
    }

    @SuppressWarnings("TypeMayBeWeakened")
    private static Set<Integer> doBddOperations(BddImpl bdd, int node1, int node2) {
        List<Integer> nodes = new ArrayList<>();
        nodes.add(bdd.reference(bdd.and(node1, node2)));
        nodes.add(bdd.reference(bdd.or(node1, node2)));
        nodes.add(bdd.reference(bdd.xor(node1, node2)));
        nodes.add(bdd.reference(bdd.implication(node1, node2)));
        nodes.add(bdd.reference(bdd.equivalence(node1, node2)));
        nodes.add(bdd.reference(bdd.not(node1)));
        nodes.add(bdd.reference(bdd.not(node2)));
        nodes.forEach(bdd::dereference);
        return new HashSet<>(nodes);
    }

    private static void doCheckInvariants() {
        infoMap.keySet().forEach(BddImpl::check);
    }

    private static Iterator<BitSet> getBitSetIterator(BitSet enabledVariables) {
        if (enabledVariables.cardinality() == 0) {
            return Collections.singleton(new BitSet()).iterator();
        }
        return new RestrictedPowerSetIterator(enabledVariables);
    }

    private static Iterator<boolean[]> getArrayIterator(BitSet enabledVariables) {
        boolean[] base = new boolean[variableCount];
        enabledVariables.stream().forEach(i -> base[i] = true);
        return new PowerSetIterator(base);
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

    public static Stream<Generator.BinaryDataPoint<BddImpl>> binary() {
        return binary.stream();
    }

    public static Stream<Generator.TernaryDataPoint<BddImpl>> ternary() {
        return ternary.stream();
    }

    public static Stream<Generator.UnaryDataPoint<BddImpl>> unary() {
        return unary.stream();
    }

    public static Collection<BddImpl> bdds() {
        return infoMap.keySet();
    }

    @BeforeAll
    public static void dummy() {
        // Dummy method to separate static initialization from actual test running times
        logger.log(Level.FINE, "Before class");
    }

    @AfterAll
    public static void check() {
        doCheckInvariants();
    }

    @AfterAll
    public static void statistics() {
        for (BddImpl bdd : infoMap.keySet()) {
            logger.log(Level.INFO, bdd.statistics());
        }
    }

    @AfterEach
    public void clearCaches() {
        for (BddImpl bdd : infoMap.keySet()) {
            if (skipCheckRandom.nextInt(100) == 0) {
                bdd.invalidateCache();
            }
        }
    }

    @AfterEach
    public void checkInvariants() {
        if (skipCheckRandom.nextInt(SKIP_CHECK_RANDOM_BOUND) == 0) {
            doCheckInvariants();
        }
        /*
        infoMap.forEach((bdd, info) -> {
          assertThat("Work stack not empty", bdd.isWorkStackEmpty(), is(true));
          assertThat("Initial nodes mismatch", bdd.nodeCount(), is(info.initialNodeCount));
          assertThat("Referenced nodes mismatch", bdd.referencedNodeCount(),
              is(info.initialReferencedNodeCount));
        }); */
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testAnd(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        int and = bdd.reference(bdd.and(node1, node2));

        Iterable<boolean[]> valuations = () -> new SimplePowerSetIterator(variableCount);
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node1, valuation)) {
                assertThat(bdd.evaluate(and, valuation), is(bdd.evaluate(node2, valuation)));
            } else {
                assertThat(bdd.evaluate(and, valuation), is(false));
            }
        }

        int notNode1 = bdd.reference(bdd.not(node1));
        int notNode2 = bdd.reference(bdd.not(node2));
        int notNode1orNotNode2 = bdd.reference(bdd.or(notNode1, notNode2));
        int andDeMorganConstruction = bdd.not(notNode1orNotNode2);
        assertThat(and, is(andDeMorganConstruction));
        bdd.dereference(notNode1, notNode2, notNode1orNotNode2);

        int andIteConstruction = bdd.ifThenElse(node1, node2, bdd.falseNode());
        assertThat(and, is(andIteConstruction));

        bdd.dereference(and);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testComposeTree(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        Generator.Info<BddImpl> bddInfo = infoMap.get(bdd).bddInfo;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        SyntaxTree syntaxTree = dataPoint.tree;
        Set<Integer> containedVariables = syntaxTree.containedVariables();
        assumeTrue(containedVariables.size() <= 7);

        Random selectionRandom = new Random(node);
        List<Integer> availableNodes = new ArrayList<>(bddInfo.syntaxTreeMap.keySet());
        availableNodes.addAll(Arrays.asList(bdd.trueNode(), bdd.falseNode()));

        int[] composeArray = new int[variableCount];
        for (int i = 0; i < variableCount; i++) {
            if (containedVariables.contains(i)) {
                int replacementBddIndex = selectionRandom.nextInt(availableNodes.size());
                composeArray[i] = availableNodes.get(replacementBddIndex);
            } else {
                composeArray[i] = bddInfo.variableList.get(i);
            }
        }
        int[] composeNegativeArray = new int[variableCount];
        for (int i = 0; i < variableCount; i++) {
            if (bdd.isVariable(composeArray[i]) && bdd.variable(composeArray[i]) == i) {
                composeNegativeArray[i] = bdd.placeholder();
            } else {
                composeNegativeArray[i] = composeArray[i];
            }
        }

        int[] composeCutoffArray = EMPTY_INTS;
        for (int i = composeArray.length - 1; i >= 0; i--) {
            if (composeNegativeArray[i] != bdd.placeholder()) {
                composeCutoffArray = Arrays.copyOf(composeArray, i + 1);
                break;
            }
        }

        Map<Integer, SyntaxTree> replacementMap = new HashMap<>();
        for (int i = 0; i < composeArray.length; i++) {
            int variableReplacement = composeArray[i];
            if (variableReplacement != bdd.placeholder()) {
                replacementMap.put(i, bddInfo.syntaxTreeMap.get(variableReplacement));
            }
        }
        int composeNode = bdd.reference(bdd.compose(node, composeArray));
        SyntaxTree composeTree = SyntaxTree.buildReplacementTree(syntaxTree, replacementMap);

        Iterator<boolean[]> iterator = getArrayIterator(bdd.support(node));
        while (iterator.hasNext()) {
            boolean[] valuation = iterator.next();
            assertThat(bdd.evaluate(composeNode, valuation), is(composeTree.evaluate(valuation)));
        }

        int composeNegativeNode = bdd.compose(node, composeNegativeArray);
        assertThat(composeNegativeNode, is(composeNode));
        int composeCutoffNode = bdd.compose(node, composeCutoffArray);
        assertThat(composeCutoffNode, is(composeNode));
        bdd.dereference(composeNode);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testComposeSimple(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        Set<Integer> variables = dataPoint.tree.containedVariables();
        boolean[] baseArray = new boolean[variableCount];
        variables.forEach(i -> baseArray[i] = true);

        int trueNode = bdd.trueNode();
        int falseNode = bdd.falseNode();
        int[] composeArray = new int[variableCount];

        new PowerSetIterator(baseArray).forEachRemaining(valuation -> {
            for (int i = 0; i < variableCount; i++) {
                composeArray[i] = valuation[i] ? trueNode : falseNode;
            }
            int composeNode = bdd.compose(node, composeArray);
            boolean value = bdd.evaluate(node, valuation);

            if (value) {
                assertThat(composeNode, is(trueNode));
            } else {
                assertThat(composeNode, is(falseNode));
            }
        });
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testComposeRepeated(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        Generator.Info<BddImpl> bddInfo = infoMap.get(bdd).bddInfo;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        SyntaxTree syntaxTree = dataPoint.tree;
        Set<Integer> containedVariables = syntaxTree.containedVariables();
        assumeTrue(containedVariables.size() <= 4);

        Random selectionRandom = new Random(node);
        List<Integer> availableNodes = new ArrayList<>(bddInfo.syntaxTreeMap.keySet());
        availableNodes.addAll(Arrays.asList(bdd.trueNode(), bdd.falseNode()));

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

        int composeNode = bdd.reference(bdd.compose(node, composeArray));
        int repeatedNode = bdd.reference(bdd.compose(node, composeArray));
        assertThat(composeNode, is(repeatedNode));
        bdd.dereference(repeatedNode);

        int selfComposeNode = bdd.reference(bdd.compose(composeNode, composeArray));
        bdd.dereference(composeNode);

        SyntaxTree composeTree = SyntaxTree.buildReplacementTree(
                SyntaxTree.buildReplacementTree(syntaxTree, replacementMap), replacementMap);

        Iterator<boolean[]> iterator = getArrayIterator(bdd.support(node));
        while (iterator.hasNext()) {
            boolean[] valuation = iterator.next();
            assertThat(bdd.evaluate(selfComposeNode, valuation), is(composeTree.evaluate(valuation)));
        }

        bdd.dereference(selfComposeNode);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testConsume(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        // This test simply tests if the semantics of consume are as specified, i.e.
        // consume(result, input1, input2) reduces the reference count of the inputs and increases that
        // of result
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeFalse(node1 == node2);
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));
        assumeFalse(bdd.isNodeSaturated(node1));
        assumeFalse(bdd.isNodeSaturated(node2));

        bdd.reference(node1);
        bdd.reference(node2);
        int node1referenceCount = bdd.referenceCount(node1);
        int node2referenceCount = bdd.referenceCount(node2);

        for (int operationNode : doBddOperations(bdd, node1, node2)) {
            if (bdd.isNodeSaturated(operationNode)) {
                continue;
            }
            int operationRefCount = bdd.referenceCount(operationNode);
            assertThat(bdd.consume(operationNode, node1, node2), is(operationNode));

            if (operationNode == node1) {
                assertThat(bdd.referenceCount(node1), is(node1referenceCount));
                assertThat(bdd.referenceCount(node2), is(node2referenceCount - 1));
            } else if (operationNode == node2) {
                assertThat(bdd.referenceCount(node1), is(node1referenceCount - 1));
                assertThat(bdd.referenceCount(node2), is(node2referenceCount));
            } else {
                assertThat(bdd.referenceCount(node1), is(node1referenceCount - 1));
                assertThat(bdd.referenceCount(node2), is(node2referenceCount - 1));
                assertThat(bdd.referenceCount(operationNode), is(operationRefCount + 1));
            }

            bdd.reference(node1);
            bdd.reference(node2);
            bdd.dereference(operationNode);
        }

        assertThat(bdd.referenceCount(node1), is(node1referenceCount));
        assertThat(bdd.referenceCount(node2), is(node2referenceCount));
        bdd.dereference(node1);
        bdd.dereference(node2);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testCountSatisfyingAssignments(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        long satisfyingAssignments = 0L;
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node, valuation)) {
                satisfyingAssignments += 1L;
            }
        }

        //noinspection MagicNumber
        assertThat(bdd.countSatisfyingAssignments(node).longValueExact(), is(satisfyingAssignments));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testCountSatisfyingAssignmentsRestrictedSimple(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        BitSet set = new BitSet();
        set.set(0, bdd.numberOfVariables());

        assertThat(
                bdd.countSatisfyingAssignments(node, set).longValueExact(),
                is(bdd.countSatisfyingAssignments(node).longValueExact()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testCountSatisfyingAssignmentsRestricted(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        Random random = new Random(node);
        BitSet set = new BitSet();
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (random.nextBoolean()) {
                set.set(i);
            }
        }
        bdd.supportTo(node, set);

        AtomicLong satisfyingAssignments = new AtomicLong();
        bdd.forEachSolution(node, set, path -> satisfyingAssignments.incrementAndGet());

        assertThat(bdd.countSatisfyingAssignments(node, set).longValueExact(), is(satisfyingAssignments.get()));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testEquivalence(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        int equivalence = bdd.reference(bdd.equivalence(node1, node2));

        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node1, valuation)) {
                assertThat(bdd.evaluate(equivalence, valuation), is(bdd.evaluate(node2, valuation)));
            } else {
                assertThat(bdd.evaluate(equivalence, valuation), is(!bdd.evaluate(node2, valuation)));
            }
        }

        int node1andNode2 = bdd.reference(bdd.and(node1, node2));
        int notNode1 = bdd.reference(bdd.not(node1));
        int notNode2 = bdd.reference(bdd.not(node2));
        int notNode1andNotNode2 = bdd.reference(bdd.and(notNode1, notNode2));
        int equivalenceAndOrConstruction = bdd.or(node1andNode2, notNode1andNotNode2);
        assertThat(equivalence, is(equivalenceAndOrConstruction));
        bdd.dereference(node1andNode2, notNode1, notNode2, notNode1andNotNode2);

        int equivalenceIteConstruction = bdd.ifThenElse(node1, node2, notNode2);
        assertThat(equivalence, is(equivalenceIteConstruction));

        int node1ImpliesNode2 = bdd.reference(bdd.implication(node1, node2));
        int node2ImpliesNode1 = bdd.reference(bdd.implication(node2, node1));
        int equivalenceBiImplicationConstruction = bdd.and(node1ImpliesNode2, node2ImpliesNode1);
        assertThat(equivalence, is(equivalenceBiImplicationConstruction));
        bdd.dereference(node1ImpliesNode2, node2ImpliesNode1);

        bdd.dereference(equivalence);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testEvaluateTree(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));
        assumeTrue(dataPoint.tree.depth() <= 5);

        for (boolean[] valuation : valuations) {
            assertThat(
                    Arrays.toString(valuation) + "\n" + bdd.treeToString(node),
                    bdd.evaluate(node, valuation),
                    is(dataPoint.tree.evaluate(valuation)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testExists(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        BitSet quantificationBitSet = new BitSet(bdd.numberOfVariables());
        Random quantificationRandom = new Random(node);
        for (int i = 0; i < bdd.numberOfVariables(); i++) {
            if (quantificationRandom.nextInt(bdd.numberOfVariables()) < 5) {
                quantificationBitSet.set(i);
            }
        }
        assumeTrue(quantificationBitSet.cardinality() <= 5);

        int existsNode = bdd.exists(node, quantificationBitSet);
        BitSet supportIntersection = bdd.support(existsNode);
        supportIntersection.and(quantificationBitSet);
        assertThat(supportIntersection.isEmpty(), is(true));

        BitSet unquantifiedVariables = copyBitSet(quantificationBitSet);
        unquantifiedVariables.flip(0, bdd.numberOfVariables());

        assertThat(
                Iterators.all(getBitSetIterator(unquantifiedVariables), unquantifiedAssignment -> {
                    boolean bddEvaluation = bdd.evaluate(existsNode, unquantifiedAssignment);
                    boolean setEvaluation = Iterators.any(getBitSetIterator(quantificationBitSet), bitSet -> {
                        BitSet actualBitSet = copyBitSet(bitSet);
                        actualBitSet.or(unquantifiedAssignment);
                        return bdd.evaluate(node, actualBitSet);
                    });
                    return bddEvaluation == setEvaluation;
                }),
                is(true));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testForEachPathSimple(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        BitSet support = bdd.support(node);
        assumeTrue(support.cardinality() <= 7);

        BitSet supportFromSolutions = new BitSet(bdd.numberOfVariables());
        BitSet supportFromPathSupport = new BitSet(bdd.numberOfVariables());

        List<BitSet> paths = new ArrayList<>();
        bdd.forEachPath(node, (solution, pathSupport) -> {
            paths.add(copyBitSet(solution));
            supportFromPathSupport.or(pathSupport);
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
        Set<BitSet> assignments = new BddPathExplorer(bdd, node).getAssignments();
        assertThat(solutionBitSets, is(assignments));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testForEachPathWithRelevantSet(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        BitSet support = bdd.support(node);
        assumeTrue(support.cardinality() <= 7);

        List<BitSet> minimalSolutions = new ArrayList<>();
        long[] solutionCount = {0L};
        int variableCount = bdd.numberOfVariables();
        bdd.forEachPath(node, (solution, solutionSupport) -> {
            minimalSolutions.add(copyBitSet(solution));
            BitSet nonRelevantVariables = copyBitSet(solutionSupport);
            nonRelevantVariables.flip(0, variableCount);
            assertThat(nonRelevantVariables.intersects(solution), is(false));
            assertThat(bdd.evaluate(node, solution), is(true));

            Iterator<BitSet> iterator = new PowerBitSetIterator(nonRelevantVariables);
            while (iterator.hasNext()) {
                BitSet next = copyBitSet(iterator.next());
                next.or(solution);
                assertThat(bdd.evaluate(node, next), is(true));
            }
            solutionCount[0] += (1L << nonRelevantVariables.cardinality());
        });
        assertThat(solutionCount[0], is(bdd.countSatisfyingAssignments(node).longValueExact()));

        List<BitSet> otherMinimalSolutions = new ArrayList<>();
        bdd.forEachPath(node, solution -> otherMinimalSolutions.add(copyBitSet(solution)));
        assertThat(minimalSolutions, is(otherMinimalSolutions));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testForEach(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        Set<BitSet> satisfyingAssignments = new HashSet<>();
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node, valuation)) {
                satisfyingAssignments.add(asSet(valuation));
            }
        }

        bdd.forEachSolution(node, valuation -> {
            assertThat("Invalid solution", bdd.evaluate(node, valuation), is(true));
            assertThat("Duplicate solution", satisfyingAssignments.remove(valuation), is(true));
        });
        assertThat("Missing solution", satisfyingAssignments, empty());
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testGetLowAndHigh(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValid(node));

        int low = bdd.low(node);
        int high = bdd.high(node);
        if (bdd.isVariableOrNegated(node)) {
            if (bdd.isVariable(node)) {
                assertThat(low, is(bdd.falseNode()));
                assertThat(high, is(bdd.trueNode()));
            } else {
                assertThat(low, is(bdd.trueNode()));
                assertThat(high, is(bdd.falseNode()));
            }
        } else {
            Collection<Integer> rootNodes = ImmutableSet.of(bdd.falseNode(), bdd.trueNode());
            assumeFalse(rootNodes.contains(low) && rootNodes.contains(high));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("ternary")
    public void testIfThenElse(Generator.TernaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int ifNode = dataPoint.first;
        int thenNode = dataPoint.second;
        int elseNode = dataPoint.third;
        assumeTrue(bdd.isNodeValidOrLeaf(ifNode));
        assumeTrue(bdd.isNodeValidOrLeaf(thenNode));
        assumeTrue(bdd.isNodeValidOrLeaf(elseNode));

        int ifThenElse = bdd.reference(bdd.ifThenElse(ifNode, thenNode, elseNode));

        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(ifNode, valuation)) {
                assertThat(bdd.evaluate(ifThenElse, valuation), is(bdd.evaluate(thenNode, valuation)));
            } else {
                assertThat(bdd.evaluate(ifThenElse, valuation), is(bdd.evaluate(elseNode, valuation)));
            }
        }

        int notIf = bdd.reference(bdd.not(ifNode));
        int ifImpliesThen = bdd.reference(bdd.implication(ifNode, thenNode));
        int notIfImpliesThen = bdd.reference(bdd.implication(notIf, elseNode));
        int ifThenElseImplicationConstruction = bdd.and(ifImpliesThen, notIfImpliesThen);
        assertThat(
                String.format("ITE construction failed for %d,%d,%d", ifNode, thenNode, elseNode),
                ifThenElse,
                is(ifThenElseImplicationConstruction));
        bdd.dereference(notIf, ifImpliesThen, notIfImpliesThen);

        bdd.dereference(ifThenElse);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testImplication(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        int implication = bdd.reference(bdd.implication(node1, node2));

        for (boolean[] valuation : valuations) {
            boolean implies = !bdd.evaluate(node1, valuation) || bdd.evaluate(node2, valuation);
            assertThat(bdd.evaluate(implication, valuation), is(implies));
        }

        int notNode1 = bdd.reference(bdd.not(node1));
        int implicationConstruction = bdd.or(notNode1, node2);
        assertThat(implication, is(implicationConstruction));
        bdd.dereference(notNode1);

        bdd.dereference(implication);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testImplies(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        boolean implies = bdd.implies(node1, node2);
        int implication = bdd.implication(node1, node2);

        if (implies) {
            for (boolean[] valuation : valuations) {
                assertThat(!bdd.evaluate(node1, valuation) || bdd.evaluate(node2, valuation), is(true));
            }
            assertThat(implication, is(bdd.trueNode()));
        } else {
            assertThat(implication, is(not(bdd.trueNode())));
            bdd.forEachSolution(
                    bdd.not(implication),
                    valuation ->
                            assertThat(bdd.evaluate(node1, valuation) && !bdd.evaluate(node2, valuation), is(true)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testIsVariable(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        SyntaxTree.SyntaxTreeNode rootNode = dataPoint.tree.getRootNode();
        int node = dataPoint.node;

        if (rootNode instanceof SyntaxTree.SyntaxTreeLiteral) {
            assertThat(bdd.isVariable(node), is(true));
            assertThat(bdd.isVariableOrNegated(node), is(true));
        } else if (rootNode instanceof SyntaxTree.SyntaxTreeNot) {
            SyntaxTree.SyntaxTreeNode child = ((SyntaxTree.SyntaxTreeNot) rootNode).getChild();
            if (child instanceof SyntaxTree.SyntaxTreeLiteral) {
                assertThat(bdd.isVariable(node), is(false));
                assertThat(bdd.isVariableOrNegated(node), is(true));
            }
        }
        BitSet support = bdd.support(node);
        assertThat(bdd.isVariableOrNegated(node), is(support.cardinality() == 1));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testIterator(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        Set<BitSet> satisfyingAssignments = new HashSet<>();
        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node, valuation)) {
                satisfyingAssignments.add(asSet(valuation));
            }
        }

        bdd.solutionIterator(node).forEachRemaining(valuation -> {
            assertThat("Invalid solution", bdd.evaluate(node, valuation), is(true));
            assertThat("Duplicate solution", satisfyingAssignments.remove(valuation), is(true));
        });
        assertThat("Missing solution", satisfyingAssignments, empty());
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testNot(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        int not = bdd.reference(bdd.not(node));

        for (boolean[] valuation : valuations) {
            assertThat(bdd.evaluate(not, valuation), is(!bdd.evaluate(node, valuation)));
        }

        assertThat(bdd.not(not), is(node));

        int notIteConstruction = bdd.ifThenElse(node, bdd.falseNode(), bdd.trueNode());
        assertThat(not, is(notIteConstruction));

        bdd.dereference(not);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testNotAnd(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        int notAnd = bdd.reference(bdd.notAnd(node1, node2));

        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node1, valuation)) {
                assertThat(bdd.evaluate(notAnd, valuation), is(!bdd.evaluate(node2, valuation)));
            } else {
                assertThat(bdd.evaluate(notAnd, valuation), is(true));
            }
        }

        int node1andNode2 = bdd.reference(bdd.and(node1, node2));
        int notNode1AndNode2 = bdd.not(node1andNode2);
        assertThat(notAnd, is(notNode1AndNode2));
        bdd.dereference(node1andNode2);

        int notNode2 = bdd.reference(bdd.not(node2));
        int notAndIteConstruction = bdd.ifThenElse(node1, notNode2, bdd.trueNode());
        assertThat(notAnd, is(notAndIteConstruction));
        bdd.dereference(notNode2);

        bdd.dereference(notAnd);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testOr(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        int or = bdd.reference(bdd.or(node1, node2));

        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node1, valuation)) {
                assertThat(bdd.evaluate(or, valuation), is(true));
            } else {
                assertThat(bdd.evaluate(or, valuation), is(bdd.evaluate(node2, valuation)));
            }
        }

        int notNode1 = bdd.reference(bdd.not(node1));
        int notNode2 = bdd.reference(bdd.not(node2));
        int notNode1andNotNode2 = bdd.reference(bdd.and(notNode1, notNode2));
        int orDeMorganConstruction = bdd.not(notNode1andNotNode2);
        assertThat(or, is(orDeMorganConstruction));
        bdd.dereference(notNode1, notNode2, notNode1andNotNode2);

        int orIteConstruction = bdd.ifThenElse(node1, bdd.trueNode(), node2);
        assertThat(or, is(orIteConstruction));

        bdd.dereference(or);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testReferenceAndDereference(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));
        assumeFalse(bdd.isNodeSaturated(node));

        int referenceCount = bdd.referenceCount(node);
        for (int i = referenceCount; i > 0; i--) {
            bdd.dereference(node);
            assertThat(bdd.referenceCount(node), is(i - 1));
        }
        for (int i = 0; i < referenceCount; i++) {
            bdd.reference(node);
            assertThat(bdd.referenceCount(node), is(i + 1));
        }
        assertThat(bdd.referenceCount(node), is(referenceCount));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    @SuppressWarnings("PMD.ExceptionAsFlowControl")
    public void testReferenceGuard(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));
        assumeFalse(bdd.isNodeSaturated(node));

        int referenceCount = bdd.referenceCount(node);
        try {
            //noinspection NestedTryStatement
            try (Bdd.ReferenceGuard guard = new Bdd.ReferenceGuard(node, bdd)) {
                assertThat(bdd.referenceCount(node), is(referenceCount + 1));
                assertThat(guard.diagram, is(bdd));
                assertThat(guard.node, is(node));
                //noinspection ThrowCaughtLocally - We deliberately want to test the exception handling here
                throw new IllegalArgumentException("Bogus");
            }
        } catch (IllegalArgumentException ignored) {
            assertThat(bdd.referenceCount(node), is(referenceCount));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testRestrict(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        Random restrictRandom = new Random(node);
        BitSet restrictedVariables = new BitSet(bdd.numberOfVariables());
        BitSet restrictedVariableValues = new BitSet(bdd.numberOfVariables());
        int[] composeArray = new int[bdd.numberOfVariables()];
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < bdd.numberOfVariables(); j++) {
                if (restrictRandom.nextBoolean()) {
                    restrictedVariables.set(j);
                    if (restrictRandom.nextBoolean()) {
                        restrictedVariableValues.set(j);
                        composeArray[j] = bdd.trueNode();
                    } else {
                        composeArray[j] = bdd.falseNode();
                    }
                } else {
                    composeArray[j] = bdd.placeholder();
                }
            }

            int restrictNode = bdd.reference(bdd.restrict(node, restrictedVariables, restrictedVariableValues));
            int composeNode = bdd.compose(node, composeArray);
            assertThat(restrictNode, is(composeNode));
            bdd.dereference(restrictNode);

            BitSet restrictSupport = bdd.support(restrictNode);
            restrictSupport.and(restrictedVariables);
            assertThat(restrictSupport.isEmpty(), is(true));

            restrictedVariables.clear();
            restrictedVariableValues.clear();
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testSupportTree(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));
        Set<Integer> containedVariables = dataPoint.tree.containedVariables();
        assumeTrue(containedVariables.size() <= 5);

        // For each variable, we iterate through all possible valuations and check if there ever is any
        // difference.
        if (containedVariables.isEmpty()) {
            assertThat(bdd.support(node).isEmpty(), is(true));
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
                    boolean negative = bdd.evaluate(node, valuation);
                    valuation.set(checkedVariable);
                    boolean positive = bdd.evaluate(node, valuation);
                    if (negative != positive) {
                        support.set(checkedVariable);
                        break;
                    }
                }
            }
            assertThat(bdd.support(node), is(support));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testSupportUnion(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        BitSet node1Support = bdd.support(node1);
        BitSet node2Support = bdd.support(node2);
        BitSet supportUnion = copyBitSet(node1Support);
        supportUnion.or(node2Support);

        for (int operationNode : doBddOperations(bdd, node1, node2)) {
            BitSet operationSupport = bdd.support(operationNode);
            operationSupport.stream().forEach(setBit -> assertThat(supportUnion.get(setBit), is(true)));
        }
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("unary")
    public void testSupportCutoff(Generator.UnaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node = dataPoint.node;
        assumeTrue(bdd.isNodeValidOrLeaf(node));

        BitSet support = bdd.support(node);
        BitSet supportRestrict = new BitSet(bdd.numberOfVariables());
        for (int i = 0; i < bdd.numberOfVariables(); i += 2) {
            supportRestrict.set(i);
        }
        BitSet cutoffSupport = bdd.supportFiltered(node, supportRestrict);
        support.and(supportRestrict);
        assertThat(cutoffSupport, is(support));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testUpdateWith(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        // This test simply tests if the semantics of updateWith are as specified, i.e.
        // updateWith(result, input) reduces the reference count of the input and increases that of
        // result
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeFalse(node1 == node2);
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));
        assumeFalse(bdd.isNodeSaturated(node1));
        assumeFalse(bdd.isNodeSaturated(node2));

        bdd.reference(node1);
        bdd.reference(node2);
        int node1referenceCount = bdd.referenceCount(node1);
        int node2referenceCount = bdd.referenceCount(node2);
        bdd.updateWith(node1, node1);
        assertThat(bdd.referenceCount(node1), is(node1referenceCount));

        for (int operationNode : doBddOperations(bdd, node1, node2)) {
            if (bdd.isNodeSaturated(operationNode)) {
                continue;
            }
            int operationRefCount = bdd.referenceCount(operationNode);
            assertThat(bdd.updateWith(operationNode, node1), is(operationNode));

            if (operationNode == node1) {
                assertThat(bdd.referenceCount(node1), is(node1referenceCount));
                assertThat(bdd.referenceCount(node2), is(node2referenceCount));
            } else if (operationNode == node2) {
                assertThat(bdd.referenceCount(node1), is(node1referenceCount - 1));
                assertThat(bdd.referenceCount(node2), is(node2referenceCount + 1));
            } else {
                assertThat(bdd.referenceCount(node1), is(node1referenceCount - 1));
                assertThat(bdd.referenceCount(node2), is(node2referenceCount));
                assertThat(bdd.referenceCount(operationNode), is(operationRefCount + 1));
            }

            bdd.reference(node1);
            bdd.dereference(operationNode);
        }

        assertThat(bdd.referenceCount(node1), is(node1referenceCount));
        assertThat(bdd.referenceCount(node2), is(node2referenceCount));
        bdd.dereference(node1);
        bdd.dereference(node2);
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("binary")
    public void testXor(Generator.BinaryDataPoint<BddImpl> dataPoint) {
        BddImpl bdd = dataPoint.bdd;
        int node1 = dataPoint.left;
        int node2 = dataPoint.right;
        assumeTrue(bdd.isNodeValidOrLeaf(node1));
        assumeTrue(bdd.isNodeValidOrLeaf(node2));

        int xor = bdd.reference(bdd.xor(node1, node2));

        for (boolean[] valuation : valuations) {
            if (bdd.evaluate(node1, valuation)) {
                assertThat(bdd.evaluate(xor, valuation), is(!bdd.evaluate(node2, valuation)));
            } else {
                assertThat(bdd.evaluate(xor, valuation), is(bdd.evaluate(node2, valuation)));
            }
        }

        int notNode1 = bdd.reference(bdd.not(node1));
        int notNode2 = bdd.reference(bdd.not(node2));
        int notNode1AndNode2 = bdd.reference(bdd.and(notNode1, node2));
        int node1andNotNode2 = bdd.reference(bdd.and(node1, notNode2));
        int xorConstruction = bdd.or(node1andNotNode2, notNode1AndNode2);
        assertThat(xor, is(xorConstruction));
        bdd.dereference(notNode1, notNode2, notNode1AndNode2, node1andNotNode2);

        bdd.dereference(xor);
    }

    private static final class ExtendedInfo {
        final BddImpl bdd;
        final int initialNodeCount;
        final int initialReferencedNodeCount;
        final Generator.Info<BddImpl> bddInfo;

        ExtendedInfo(BddImpl bdd, Generator.Info<BddImpl> bddInfo) {
            this.bdd = bdd;
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

        BddPathExplorer(Bdd bdd, int startingNode) {
            this.bdd = bdd;
            this.assignments = new HashSet<>();
            if (startingNode == bdd.trueNode()) {
                assignments.add(new BitSet(bdd.numberOfVariables()));
            } else if (startingNode != bdd.falseNode()) {
                List<Integer> path = new ArrayList<>();
                path.add(startingNode);
                recurse(path, new BitSet(bdd.numberOfVariables()));
            }
        }

        Set<BitSet> getAssignments() {
            return assignments;
        }

        private void recurse(List<Integer> currentPath, BitSet currentAssignment) {
            int pathLeaf = currentPath.get(currentPath.size() - 1);
            int low = bdd.low(pathLeaf);
            int high = bdd.high(pathLeaf);

            if (low == bdd.trueNode()) {
                assignments.add(copyBitSet(currentAssignment));
            } else if (low != bdd.falseNode()) {
                List<Integer> recursePath = new ArrayList<>(currentPath);
                recursePath.add(low);
                recurse(recursePath, currentAssignment);
            }

            if (high != bdd.falseNode()) {
                BitSet assignment = copyBitSet(currentAssignment);
                assignment.set(bdd.variable(pathLeaf));
                if (high == bdd.trueNode()) {
                    assignments.add(assignment);
                } else {
                    List<Integer> recursePath = new ArrayList<>(currentPath);
                    recursePath.add(high);
                    recurse(recursePath, assignment);
                }
            }
        }
    }

    private static final class RestrictedPowerSetIterator implements Iterator<BitSet> {
        private final BitSet bitSet;
        private final int[] restrictionPositions;
        private final BitSet variableRestriction;
        private int assignment;

        RestrictedPowerSetIterator(BitSet restriction) {
            this(restriction.length(), restriction);
        }

        RestrictedPowerSetIterator(int size, BitSet restriction) {
            assert restriction.cardinality() > 0;
            this.bitSet = new BitSet(size);
            this.variableRestriction = restriction;
            this.assignment = 0;
            this.restrictionPositions = new int[restriction.cardinality()];

            restrictionPositions[0] = restriction.nextSetBit(0);
            for (int i = 1; i < restrictionPositions.length; i++) {
                restrictionPositions[i] = restriction.nextSetBit(restrictionPositions[i - 1] + 1);
            }
        }

        @Override
        public boolean hasNext() {
            return !Objects.equals(bitSet, variableRestriction);
        }

        @Override
        public BitSet next() {
            if (assignment == 1 << restrictionPositions.length) {
                throw new NoSuchElementException("No next element");
            }

            bitSet.clear();

            for (int restrictionPosition = 0;
                    restrictionPosition < restrictionPositions.length;
                    restrictionPosition++) {
                if (((assignment >>> restrictionPosition) & 1) == 1) {
                    bitSet.set(restrictionPositions[restrictionPosition]);
                }
            }
            assignment += 1;
            return bitSet;
        }
    }

    private static final class PowerBitSetIterator implements Iterator<BitSet> {
        private final BitSet baseSet;

        @Nullable
        private BitSet next = new BitSet();

        PowerBitSetIterator(BitSet baseSet) {
            this.baseSet = baseSet;
        }

        @Override
        public boolean hasNext() {
            return (next != null);
        }

        @Override
        public BitSet next() {
            if (next == null) {
                throw new NoSuchElementException("No next element");
            }
            @SuppressWarnings("UseOfClone")
            BitSet current = (BitSet) next.clone();

            for (int i = baseSet.nextSetBit(0); i >= 0; i = baseSet.nextSetBit(i + 1)) {
                if (next.get(i)) {
                    next.clear(i);
                } else {
                    next.set(i);
                    break;
                }
            }

            if (next.isEmpty()) {
                next = null;
            }

            return current;
        }
    }

    private static final class SimplePowerSetIterator implements Iterator<boolean[]> {
        private final int length;
        private final boolean[] next;
        private boolean hasNext = true;

        SimplePowerSetIterator(int length) {
            this.length = length;
            this.next = new boolean[length];
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        @SuppressWarnings("PMD.MethodReturnsInternalArray")
        public boolean[] next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            for (int i = 0; i < length; i++) {
                if (next[i]) {
                    next[i] = false;
                } else {
                    next[i] = true;
                    return next;
                }
            }
            hasNext = false;
            return next;
        }
    }

    private static final class PowerSetIterator implements Iterator<boolean[]> {
        private final int[] indices;
        private final boolean[] next;
        private boolean hasNext = true;

        PowerSetIterator(boolean[] base) {
            int length = base.length;

            int[] indices = new int[length];
            int count = 0;
            for (int i = 0; i < length; i++) {
                if (base[i]) {
                    indices[count] = i;
                    count += 1;
                }
            }
            this.indices = Arrays.copyOf(indices, count);
            this.next = new boolean[length];
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        @SuppressWarnings("PMD.MethodReturnsInternalArray")
        public boolean[] next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            for (int index : indices) {
                if (next[index]) {
                    next[index] = false;
                } else {
                    next[index] = true;
                    return next;
                }
            }
            hasNext = false;
            return next;
        }
    }
}
