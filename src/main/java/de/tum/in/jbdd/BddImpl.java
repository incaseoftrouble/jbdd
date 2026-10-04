/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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

import static de.tum.in.jbdd.Preconditions.*;

import de.tum.in.jbdd.collections.Cube;
import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.IntIntHashMap;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import de.tum.in.jbdd.collections.NatSets;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/* Implementation notes:
 * - Variable numbers increase while descending the tree of a particular node.
 */
@SuppressWarnings({
    "PMD.AvoidReassigningParameters",
    "PMD.CouplingBetweenObjects",
    "ReassignedVariable",
    "AssignmentToMethodParameter",
    "SameParameterValue",
    "DuplicatedCode",
    "AssertWithSideEffects"
})
public class BddImpl extends BooleanBase<NatSet, Cube> implements Bdd {

    /* The variable order and everything else the BDD shares with its MTBDD, reordering included. */
    private final DdContextImpl context;
    private final BooleanCache cache;
    // Where computeSatisfyingFraction and computeSatisfyingFractionIn leave the fraction of the function they were
    // called on and of its complement, and where the latter leaves the binary exponent both are scaled by.
    private static final int FRACTION = 0;
    private static final int COMPLEMENT_FRACTION = 1;
    private static final int EXPONENT = 2;
    /*
     * The n-ary recursion pays for every operand at every node it builds, which pays off only when the tuple shrinks
     * as the path decides variables - operands that share their top variables, like cubes over the same variables or
     * clauses over few variables, drop out or split level by level.
     * Operands over distinct variables (a formula's conjuncts, each a different sub-formula's set) stay in the tuple
     * all the way down, and pairwise is cheaper by a factor of their number: the operands per top level decide, at
     * every step, since operands sharing only their first variables are independent below them.
     */
    private static final int NARY_MINIMUM_OPERANDS_PER_TOP_LEVEL = 4;
    private static final long[] NO_KEYS = new long[0];
    // One cube per literal, for the joint composition's restrictions.
    private Cube[] literalCubes = new Cube[0];
    private int[] literalCubeHashes = EMPTY_INT_ARRAY;

    private int[] variableNodes;
    private final DdVariableOrderImpl order;
    private final BddConfiguration configuration;
    private final NodeTable.Binary table;

    BddImpl(DdContextImpl context) {
        this.context = context;
        // Store the reference for speed
        this.order = context.variableOrder();
        this.configuration = context.configuration();
        this.table = new BddTable(this, configuration.initialSize());

        cache = new BooleanCache(this);
        variableNodes = new int[32];

        // Strongly: the cache is this diagram's, and the order it listens to is held by the same context.
        order.registerOwnedObserver(cache);
    }

    @Override
    public BddConfiguration configuration() {
        return configuration;
    }

    MtBddImpl mtbdd() {
        return context.mtBdd();
    }

    @Override
    protected NodeTable table() {
        return table;
    }

    @Override
    BooleanCache cache() {
        return cache;
    }

    // Nodes

    @Override
    public int highOf(int function) {
        assert isValidNonConstantFunction(function);
        return high(function);
    }

    @Override
    public int lowOf(int function) {
        assert isValidNonConstantFunction(function);
        return low(function);
    }

    int high(int function) {
        int node = positive(function);
        return complementIf(table.highUnchecked(node), node != function);
    }

    int low(int function) {
        int node = positive(function);
        return complementIf(table.lowUnchecked(node), node != function);
    }

    int highIf(int function, boolean condition) {
        return condition ? high(function) : function;
    }

    int lowIf(int function, boolean condition) {
        return condition ? low(function) : function;
    }

    // Variables and base nodes

    @Override
    public int numberOfVariables() {
        return order.numberOfVariables();
    }

    @Override
    public int variableFunction(int variableNumber) {
        assert isValidVariable(variableNumber);
        return variableNodes[variableNumber];
    }

    private boolean isValidVariable(int variable) {
        return 0 <= variable && variable < numberOfVariables();
    }

    void ensureVariableNodeCapacity(int variables) {
        if (variables > variableNodes.length) {
            variableNodes = Arrays.copyOf(variableNodes, Math.max(variableNodes.length * 2, variables));
        }
    }

    int makeVariableNode(int variable, int level) {
        ensureVariableNodeCapacity(variable + 1);
        int variableNode = table.saturateNode(makeFunction(level, FALSE, TRUE));
        variableNodes[variable] = variableNode;
        return variableNode;
    }

    @Override
    public int createVariable() {
        return context.createVariable();
    }

    @Override
    public int[] createVariables(int count) {
        return context.createVariables(count);
    }

    @Override
    public boolean isVariable(int function) {
        assert isValidFunction(function);
        return !isConstant(function)
                && isPositive(function)
                && table.low(function) == FALSE
                && table.high(function) == TRUE;
    }

    @Override
    public boolean isVariableNegated(int function) {
        assert isValidFunction(function);
        return isVariable(complement(function));
    }

    @Override
    public boolean isVariableOrNegated(int function) {
        assert isValidFunction(function);
        return isVariable(positive(function));
    }

    // Package-private to allow cross-access from MtBddImpl
    int makeFunction(int level, int lowFunction, int highFunction) {
        if (lowFunction == highFunction) {
            return lowFunction;
        }
        int highEdge = positive(highFunction);
        boolean isHighComplement = isComplementFunction(highFunction);
        int lowEdge = complementIf(lowFunction, isHighComplement);
        return complementIf(table.makeNode(variableAtLevel(level), lowEdge, highEdge), isHighComplement);
    }

    int decisionLevel(int function) {
        assert isValidNonConstantFunction(function);
        return levelOfVariable(table.variable(positive(function)));
    }

    int decisionLevelOrMax(int function) {
        return isConstant(function) ? Integer.MAX_VALUE : decisionLevel(function);
    }

    boolean isReordered() {
        return order.isExplicitOrder();
    }

    @Override
    boolean isReordering() {
        return order.isReordering();
    }

    @Override
    String statisticsPrefix() {
        return "bdd_";
    }

    @Override
    Map<String, Object> ownStatistics() {
        return order.reorderStatistics();
    }

    @Override
    public DdVariableOrderImpl variableOrder() {
        return order;
    }

    @Override
    public int levelOfVariable(int variable) {
        return order.levelOfVariable(variable);
    }

    @Override
    public int variableAtLevel(int level) {
        return order.variableAtLevel(level);
    }

    /** The greatest level any variable of {@code variables} sits at, or -1 if there is none. */
    int maxLevel(NatSet variables) {
        int max = -1;
        PrimitiveIterator.OfInt iterator = variables.iterator();
        while (iterator.hasNext()) {
            int variable = iterator.nextInt();
            max = Math.max(max, levelOfVariable(variable));
        }
        return max;
    }

    private boolean decidesOn(int function, int level) {
        return !isConstant(function) && decisionLevel(function) == level;
    }

    @SuppressWarnings("GrazieInspection")
    void rewriteLevelAfterSwap(int[] nodes, int count, int level, int variable) {
        int rewriteCount = 0;

        // Gather all the nodes where (at least) one child is in the lower level
        // The nodes array holds all nodes of this level, so it suffices to hold these
        for (int index = 0; index < count; index++) {
            int node = nodes[index];
            if (decidesOn(table.low(node), level) || decidesOn(table.high(node), level)) {
                nodes[rewriteCount] = node;
                rewriteCount += 1;
                table.rewriteHideAndUnlink(node);
            } else {
                // Neither child mentions the variable moving up, so this node just descends a level
                table.addToVariableList(node, table.variable(node));
            }
        }

        /* For all rewritten nodes, build the new structure
         * Suppose we currently have:
         *
         *        l1
         *       /  \
         *     l2     l2
         *    / \    / \
         *   ll lh  hl  hh
         *
         * Where l1 and l2 swap; we need
         *
         *        l2
         *       /  \
         *     l1    l1
         *    / \    / \
         *   ll lh  hl  hh
         */
        for (int index = 0; index < rewriteCount; index++) {
            int node = nodes[index];
            int lowFunction = table.low(node);
            int highFunction = table.high(node);
            boolean lowDecides = decidesOn(lowFunction, level);
            boolean highDecides = decidesOn(highFunction, level);

            int lowLow = lowIf(lowFunction, lowDecides);
            int lowHigh = highIf(lowFunction, lowDecides);
            int highLow = lowIf(highFunction, highDecides);
            int highHigh = highIf(highFunction, highDecides);

            int newLow = table.pushToWorkStack(makeFunction(level + 1, lowLow, highLow));
            int newHigh = makeFunction(level + 1, lowHigh, highHigh);
            table.popFromWorkStack();
            // The new node must be positive -- however this always holds as already before the node
            // itself was positive and its high child is always positive, hence it remains such.
            assert !isComplementFunction(newHigh);
            table.rewriteNode(node, variable, newLow, newHigh);
        }
    }

    // Reading

    @Override
    public boolean evaluate(int function, boolean[] assignment) {
        assert isValidFunction(function);
        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;
        while (currentNode != TRUE) {
            assert table.isValidDecisionNode(currentNode);
            if (assignment[decisionVariable(currentNode)]) {
                currentNode = table.high(currentNode);
            } else {
                int low = table.low(currentNode);
                currentNode = positive(low);
                if (currentNode != low) {
                    lookingFor = !lookingFor;
                }
            }
        }
        return lookingFor;
    }

    @Override
    public boolean evaluate(int function, NatSet assignment) {
        assert isValidFunction(function);

        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;
        while (currentNode != TRUE) {
            assert table.isValidDecisionNode(currentNode);
            if (assignment.contains(table.variable(currentNode))) {
                currentNode = table.high(currentNode);
            } else {
                int low = table.low(currentNode);
                currentNode = positive(low);
                if (currentNode != low) {
                    lookingFor = !lookingFor;
                }
            }
        }
        return lookingFor;
    }

    @Override
    public MutableNatSet satisfyingAssignment(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            throw new NoSuchElementException("False has no solution");
        }

        MutableNatSet path = MutableNatSet.dense(numberOfVariables());
        satisfyingAssignment(function, path);
        return path;
    }

    @Override
    public Optional<NatSet> satisfyingAssignmentIn(int function, int domain) {
        assert isValidFunction(function);

        if (function == FALSE || domain == FALSE) {
            return Optional.empty();
        }

        MutableNatSet path = MutableNatSet.dense(numberOfVariables());
        return satisfyingAssignmentInRecursive(function, domain, path) ? Optional.of(path) : Optional.empty();
    }

    private void clearBelowLevel(MutableNatSet set, int level) {
        if (isReordered()) {
            for (int current = level; current < numberOfVariables(); current++) {
                set.clear(variableAtLevel(current));
            }
        } else {
            set.clear(level, numberOfVariables());
        }
    }

    private boolean satisfyingAssignment(int function, MutableNatSet path) {
        assert function != FALSE;

        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;

        while (currentNode != TRUE) {
            int low = table.low(currentNode);
            if (isFalse(low, lookingFor)) {
                int highNode = table.high(currentNode);
                int variable = table.variable(currentNode);

                path.set(variable);
                currentNode = highNode;
            } else {
                currentNode = positive(low);
                if (currentNode != low) {
                    lookingFor = !lookingFor;
                }
            }
        }
        assert lookingFor;
        return true;
    }

    private boolean satisfyingAssignmentInRecursive(int function1, int function2, MutableNatSet path) {
        if (function1 == FALSE || function2 == FALSE) {
            return false;
        }
        if (function1 == TRUE) {
            if (function2 == TRUE) {
                return true;
            }
            clearBelowLevel(path, decisionLevel(function2));
            return satisfyingAssignment(function2, path);
        }
        if (function2 == TRUE) {
            clearBelowLevel(path, decisionLevel(function1));
            return satisfyingAssignment(function1, path);
        }
        if (function1 == function2) {
            clearBelowLevel(path, decisionLevel(function1));
            return satisfyingAssignment(function1, path);
        }
        if (function1 == complement(function2)) {
            return false;
        }

        assert !isConstant(function1) && !isConstant(function2);

        int fun1Level = decisionLevel(function1);
        int fun2Level = decisionLevel(function2);
        int level = Math.min(fun1Level, fun2Level);

        if (satisfyingAssignmentInRecursive(
                lowIf(function1, fun1Level == level), lowIf(function2, fun2Level == level), path)) {
            path.clear(variableAtLevel(level));
            return true;
        }
        path.set(variableAtLevel(level));
        return satisfyingAssignmentInRecursive(
                highIf(function1, fun1Level == level), highIf(function2, fun2Level == level), path);
    }

    @Override
    public void forEachSolutionIn(int function, int domain, Consumer<? super NatSet> action) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (function == FALSE || domain == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        forEachSolutionInRecursive(
                function,
                domain,
                null,
                0,
                MutableNatSet.dense(numberOfVariables()),
                new int[numberOfVariables()],
                0,
                action);
        assert accessGuard.release();
    }

    @Override
    public void forEachSolutionIn(int function, int domain, NatSet support, Consumer<? super NatSet> action) {
        assert isValidFunction(function) && isValidFunction(domain);
        assert support.containsAll(support(function)) && support.containsAll(support(domain));

        if (function == FALSE || domain == FALSE) {
            return;
        }

        assert accessGuard.acquire();
        // The recursion descends by level, so the support has to be handed to it in level order.
        int[] variables;
        if (isReordered()) {
            long[] order = new long[support.size()];
            NatSets.forEachWithIndex(
                    support,
                    (variable, index) -> order[index] = ((long) levelOfVariable(variable) << Integer.SIZE) | variable);
            Arrays.sort(order);
            variables = new int[support.size()];
            Arrays.setAll(variables, index -> (int) order[index]);
        } else {
            variables = support.toIntArray();
        }
        forEachSolutionInRecursive(
                function,
                domain,
                variables,
                0,
                MutableNatSet.dense(numberOfVariables()),
                new int[variables.length],
                0,
                action);
        assert accessGuard.release();
    }

    private void forEachSolutionInRecursive(
            int function1,
            int function2,
            int @Nullable [] support,
            int index,
            MutableNatSet assignment,
            int[] freeVariables,
            int freeCount,
            Consumer<? super NatSet> action) {
        if (function1 == FALSE || function2 == FALSE) {
            return;
        }
        if (index == (support == null ? numberOfVariables() : support.length)) {
            assert function1 == TRUE && function2 == TRUE;
            forEachFreeExtension(assignment, freeVariables, freeCount, action);
            return;
        }

        /* The recursion descends the diagram, so it steps through *levels*; the assignment it fills is
         * indexed by variable. `support`, when given, is the caller's variables ordered by level. */
        int variable = support == null ? variableAtLevel(index) : support[index];
        int level = support == null ? index : levelOfVariable(variable);

        boolean decides1 = decidesOn(function1, level);
        boolean decides2 = decidesOn(function2, level);

        if (!decides1 && !decides2) {
            /* Neither side branches here, so both values of this variable lead to the very same
             * sub-problem. So, mark the variable as free (will "powerset" over it later) */
            freeVariables[freeCount] = variable;
            forEachSolutionInRecursive(
                    function1, function2, support, index + 1, assignment, freeVariables, freeCount + 1, action);
            return;
        }

        forEachSolutionInRecursive(
                lowIf(function1, decides1),
                lowIf(function2, decides2),
                support,
                index + 1,
                assignment,
                freeVariables,
                freeCount,
                action);
        assignment.set(variable);
        forEachSolutionInRecursive(
                highIf(function1, decides1),
                highIf(function2, decides2),
                support,
                index + 1,
                assignment,
                freeVariables,
                freeCount,
                action);
        assignment.clear(variable);
    }

    private static void forEachFreeExtension(
            MutableNatSet assignment, int[] freeVariables, int freeCount, Consumer<? super NatSet> action) {
        action.accept(assignment);
        while (true) {
            int index = 0;
            while (index < freeCount && assignment.contains(freeVariables[index])) {
                assignment.clear(freeVariables[index]);
                index++;
            }
            if (index == freeCount) {
                return;
            }
            assignment.set(freeVariables[index]);
            action.accept(assignment);
        }
    }

    @Override
    public Cursor<NatSet> solutionCursor(int function) {
        return solutionCursorIn(function, TRUE, NatSet.range(0, numberOfVariables()));
    }

    @Override
    public Cursor<NatSet> solutionCursor(int function, NatSet support) {
        return solutionCursorIn(function, TRUE, support);
    }

    @Override
    public Cursor<NatSet> solutionCursorIn(int function, int domain) {
        return solutionCursorIn(function, domain, NatSet.range(0, numberOfVariables()));
    }

    @Override
    public Cursor<NatSet> solutionCursorIn(int function, int domain, NatSet support) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (function == FALSE || domain == FALSE) {
            return Cursors.empty();
        }
        if (function == TRUE && domain == TRUE) {
            return NatSets.powerSet(support);
        }
        return new SolutionCursor(this, function, domain, support);
    }

    @Override
    public Cursor<Cube> pathCursor(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Cursors.empty();
        }
        if (function == TRUE) {
            return Cursors.singleton(Cube.empty());
        }
        return new PathCursor(this, function);
    }

    @Override
    public int of(Cube path) {
        assert path.support().allMatch(this::isValidVariable);
        assert accessGuard.acquire();
        int node = cubeFunction(path);
        assert accessGuard.release();
        return node;
    }

    // Deepest level first: each literal lands above everything built so far, so a step is one node.
    private int cubeFunction(Cube cube) {
        assert table.workStacksEmpty();
        NatSet support = cube.support();
        int[] levels = new int[support.size()];
        NatSets.forEachWithIndex(support, (value, index) -> levels[index] = levelOfVariable(value));
        Arrays.sort(levels);
        int node = TRUE;
        for (int index = levels.length - 1; index >= 0; index--) {
            int level = levels[index];
            table.pushToWorkStack(node);
            node = cube.assignment().contains(variableAtLevel(level))
                    ? makeFunction(level, FALSE, node)
                    : makeFunction(level, node, FALSE);
            table.popFromWorkStack();
        }
        assert table.workStacksEmpty();
        return node;
    }

    @Override
    public void forEachPath(int function, Consumer<? super Cube> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        if (function == TRUE) {
            action.accept(Cube.empty());
            assert accessGuard.release();
            return;
        }

        int numberOfVariables = numberOfVariables();
        WalkCube path = new WalkCube(numberOfVariables);
        forEachPathRecursive(positive(function), path, action, isPositive(function));
        assert accessGuard.release();
    }

    private void forEachPathRecursive(int node, WalkCube path, Consumer<? super Cube> action, boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            action.accept(path.cube);
            return;
        }
        assert table.isValidDecisionNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        int lowEdge = table.low(node);
        int highNode = table.high(node);
        path.support.set(variable);

        if (!isFalse(lowEdge, lookingFor)) {
            forEachPathRecursive(positive(lowEdge), path, action, isPositive(lowEdge) == lookingFor);
        }
        if (!isFalse(highNode, lookingFor)) {
            path.assignment.set(variable);
            forEachPathRecursive(highNode, path, action, lookingFor);
            assert path.assignment.contains(variable);
            path.assignment.clear(variable);
        }

        assert path.support.contains(variable);
        path.support.clear(variable);
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super Cube> predicate) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return false;
        }
        assert accessGuard.acquire();
        if (function == TRUE) {
            boolean result = predicate.test(Cube.empty());
            assert accessGuard.release();
            return result;
        }

        int numberOfVariables = numberOfVariables();
        WalkCube path = new WalkCube(numberOfVariables);
        boolean result = anyPathMatchesRecursive(positive(function), path, predicate, isPositive(function));
        assert accessGuard.release();
        return result;
    }

    private boolean anyPathMatchesRecursive(
            int node, WalkCube path, Predicate<? super Cube> predicate, boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            return predicate.test(path.cube);
        }
        assert table.isValidDecisionNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        int lowEdge = table.low(node);

        path.support.set(variable);
        if (!isFalse(lowEdge, lookingFor)
                && anyPathMatchesRecursive(positive(lowEdge), path, predicate, isPositive(lowEdge) == lookingFor)) {
            return true;
        }

        int highNode = table.high(node);
        if (!isFalse(highNode, lookingFor)) {
            path.assignment.set(variable);
            if (anyPathMatchesRecursive(highNode, path, predicate, lookingFor)) {
                return true;
            }
            path.assignment.clear(variable);
        }

        path.support.clear(variable);
        return false;
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        assert isValidFunction(function);

        assert accessGuard.acquire();
        BigInteger result = countSatisfyingAssignmentsRecursive(function, -1);
        assert accessGuard.release();
        return result;
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, NatSet support) {
        assert support.containsAll(support(function));
        return countSatisfyingAssignments(function).divide(TWO.pow(numberOfVariables() - support.size()));
    }

    @Override
    public BigInteger countSatisfyingAssignmentsIn(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        assert accessGuard.acquire();
        BigInteger result = countSatisfyingAssignmentsInRecursive(function, domain, -1);
        assert accessGuard.release();
        return result;
    }

    private BigInteger countSatisfyingAssignmentsRecursive(int function, int previousLevel) {
        assert isValidFunction(function);

        if (function == TRUE) {
            return TWO.pow(numberOfVariables() - previousLevel - 1);
        }
        if (function == FALSE) {
            return BigInteger.ZERO;
        }

        int node = positive(function);
        int rootLevel = decisionLevel(node);
        boolean complement = function != node;

        BigInteger cacheLookup = cache.lookupSatisfaction(node);
        if (cacheLookup != null) {
            return (complement ? TWO.pow(numberOfVariables() - rootLevel).subtract(cacheLookup) : cacheLookup)
                    .shiftLeft(rootLevel - previousLevel - 1);
        }
        int hash = cache.lookupHash();

        BigInteger result = countSatisfyingAssignmentsRecursive(low(function), rootLevel)
                .add(countSatisfyingAssignmentsRecursive(high(function), rootLevel));

        cache.putSatisfaction(
                hash,
                node,
                complement ? TWO.pow(numberOfVariables() - rootLevel).subtract(result) : result);
        return result.shiftLeft(rootLevel - previousLevel - 1);
    }

    private BigInteger countSatisfyingAssignmentsInRecursive(int function1, int function2, int previousLevel) {
        if (function1 == TRUE) {
            return countSatisfyingAssignmentsRecursive(function2, previousLevel);
        }
        if (function2 == TRUE) {
            return countSatisfyingAssignmentsRecursive(function1, previousLevel);
        }
        if (function1 == FALSE || function2 == FALSE) {
            return BigInteger.ZERO;
        }
        if (function1 == function2) {
            return countSatisfyingAssignmentsRecursive(function1, previousLevel);
        }
        if (function1 == complement(function2)) {
            return BigInteger.ZERO;
        }

        assert !isConstant(function1) && !isConstant(function2);

        if (function1 > function2) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;
        }

        int fun1Level = decisionLevel(function1);
        int fun2Level = decisionLevel(function2);
        int level = Math.min(fun1Level, fun2Level);

        BigInteger cacheLookup = cache.lookupSatisfactionIn(function1, function2);
        if (cacheLookup != null) {
            return cacheLookup.shiftLeft(level - previousLevel - 1);
        }
        int hash = cache.lookupHash();

        BigInteger result = countSatisfyingAssignmentsInRecursive(
                        lowIf(function1, fun1Level == level), lowIf(function2, fun2Level == level), level)
                .add(countSatisfyingAssignmentsInRecursive(
                        highIf(function1, fun1Level == level), highIf(function2, fun2Level == level), level));
        cache.putSatisfactionIn(hash, function1, function2, result);
        result = result.shiftLeft(level - previousLevel - 1);
        assert result.compareTo(BigInteger.ZERO) >= 0;
        return result;
    }

    @Override
    public double satisfyingFraction(int function) {
        assert isValidFunction(function);

        assert accessGuard.acquire();
        double[] fractions = new double[2];
        computeSatisfyingFraction(function, fractions);
        assert accessGuard.release();
        return fractions[FRACTION];
    }

    private void computeSatisfyingFraction(int function, double[] result) {
        if (function == TRUE || function == FALSE) {
            result[FRACTION] = function == TRUE ? 1.0d : 0.0d;
            result[COMPLEMENT_FRACTION] = 1.0d - result[FRACTION];
            return;
        }

        // Each side is the mean of the children's same side, a complemented edge swapping them: only sums of
        // non-negative terms, never 1 - x, so both keep their relative precision however close to 0 they get.
        int node = positive(function);
        BooleanCache.FractionCache fractions = cache.fractionCache();
        double fraction;
        double complementFraction;
        if (fractions.lookup(node)) {
            fraction = fractions.fraction();
            complementFraction = fractions.complementFraction();
        } else {
            int hash = fractions.lookupHash();
            computeSatisfyingFraction(low(node), result);
            double lowFraction = result[FRACTION];
            double lowComplementFraction = result[COMPLEMENT_FRACTION];
            computeSatisfyingFraction(high(node), result);
            fraction = (lowFraction + result[FRACTION]) * 0.5d;
            complementFraction = (lowComplementFraction + result[COMPLEMENT_FRACTION]) * 0.5d;
            fractions.put(hash, node, fraction, complementFraction);
        }
        boolean complement = function != node;
        result[FRACTION] = complement ? complementFraction : fraction;
        result[COMPLEMENT_FRACTION] = complement ? fraction : complementFraction;
    }

    @Override
    public double satisfyingFractionIn(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);
        if (domain == FALSE) {
            throw new IllegalArgumentException("No assignment to draw from the empty domain");
        }

        assert accessGuard.acquire();
        double[] fractions = new double[3];
        computeSatisfyingFractionIn(function, domain, fractions);
        assert accessGuard.release();

        // Both sides share their exponent, and the larger of them is at least 1/2: neither underflows the quotient.
        return fractions[FRACTION] / (fractions[FRACTION] + fractions[COMPLEMENT_FRACTION]);
    }

    /* Leaves the satisfying fractions of function AND domain and of NOT function AND domain in result, by the scheme
     * of computeSatisfyingFraction but scaled: result[FRACTION] * 2^result[EXPONENT], and alike for the complement,
     * with the larger side in [1, 2). Only the domain's valuations are counted, so their fraction may be as small as
     * 2^-numberOfVariables; the scale keeps it from underflowing, which a double alone would at 2^-1074. The
     * smaller side can still underflow relative to the larger, but then the probability it yields is below 2^-1022
     * itself. Both zero means the empty domain. */
    private void computeSatisfyingFractionIn(int function, int domain, double[] result) {
        if (domain == TRUE) {
            computeSatisfyingFraction(function, result);
            scaleFractions(result, 0);
            return;
        }
        if (domain == FALSE) {
            result[FRACTION] = 0.0d;
            result[COMPLEMENT_FRACTION] = 0.0d;
            result[EXPONENT] = 0.0d;
            return;
        }
        // A constant is the domain or nothing of it; both keep a function in the cache's key.
        int restricted = function == TRUE ? domain : function == FALSE ? complement(domain) : function;

        int node = positive(restricted);
        BooleanCache.FractionInCache fractions = cache.fractionInCache();
        double fraction;
        double complementFraction;
        int exponent;
        if (fractions.lookup(node, domain)) {
            fraction = fractions.fraction();
            complementFraction = fractions.complementFraction();
            exponent = fractions.exponent();
        } else {
            int hash = fractions.lookupHash();
            int nodeLevel = decisionLevel(node);
            int domainLevel = decisionLevel(domain);
            int level = Math.min(nodeLevel, domainLevel);
            computeSatisfyingFractionIn(lowIf(node, nodeLevel == level), lowIf(domain, domainLevel == level), result);
            double lowFraction = result[FRACTION];
            double lowComplementFraction = result[COMPLEMENT_FRACTION];
            int lowExponent = (int) result[EXPONENT];
            computeSatisfyingFractionIn(highIf(node, nodeLevel == level), highIf(domain, domainLevel == level), result);
            // The mean of both children: their sum, aligned to the larger exponent, with the halving in the exponent.
            // A child outside the domain adds nothing, and since the domain is not FALSE, one of them is in it.
            if (lowFraction == 0.0d && lowComplementFraction == 0.0d) {
                result[EXPONENT] -= 1;
            } else if (result[FRACTION] == 0.0d && result[COMPLEMENT_FRACTION] == 0.0d) {
                result[FRACTION] = lowFraction;
                result[COMPLEMENT_FRACTION] = lowComplementFraction;
                result[EXPONENT] = lowExponent - 1;
            } else {
                int highExponent = (int) result[EXPONENT];
                int maxExponent = Math.max(lowExponent, highExponent);
                result[FRACTION] = Math.scalb(lowFraction, lowExponent - maxExponent)
                        + Math.scalb(result[FRACTION], highExponent - maxExponent);
                result[COMPLEMENT_FRACTION] = Math.scalb(lowComplementFraction, lowExponent - maxExponent)
                        + Math.scalb(result[COMPLEMENT_FRACTION], highExponent - maxExponent);
                scaleFractions(result, maxExponent - 1);
            }
            fraction = result[FRACTION];
            complementFraction = result[COMPLEMENT_FRACTION];
            exponent = (int) result[EXPONENT];
            fractions.put(hash, node, domain, fraction, complementFraction, exponent);
        }
        boolean complement = restricted != node;
        result[FRACTION] = complement ? complementFraction : fraction;
        result[COMPLEMENT_FRACTION] = complement ? fraction : complementFraction;
        result[EXPONENT] = exponent;
    }

    /* Rescales the fractions in result, currently scaled by 2^exponent, so that the larger is in [1, 2). */
    private static void scaleFractions(double[] result, int exponent) {
        double larger = Math.max(result[FRACTION], result[COMPLEMENT_FRACTION]);
        assert larger >= Double.MIN_NORMAL;
        int shift = Math.getExponent(larger);
        result[FRACTION] = Math.scalb(result[FRACTION], -shift);
        result[COMPLEMENT_FRACTION] = Math.scalb(result[COMPLEMENT_FRACTION], -shift);
        result[EXPONENT] = exponent + shift;
    }

    // General operations

    @Override
    public int compose(int function, int[] variableMapping) {
        return composeSimplify(function, variableMapping, TRUE);
    }

    @Override
    public int composeSimplify(int function, int[] variableMapping, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);
        assert variableMapping.length <= numberOfVariables();

        if (isConstant(function)) {
            return function;
        }
        if (domain == FALSE) {
            return FALSE;
        }

        assert accessGuard.acquire();
        int[] resolved = variableMapping.clone();
        ComposeAnalysis analysis = analyzeCompose(resolved);
        if (analysis.maxReplacedLevel == -1) {
            int result = simplify(function, domain);
            assert accessGuard.release();
            return result;
        }
        if (analysis.isRestrict) {
            int result = restrictSimplify(function, analysis.restriction, domain);
            assert accessGuard.release();
            return result;
        }

        assert table.workStacksEmpty();

        int arrayWorkStackCount = 0;
        for (int j : resolved) {
            assert isValidFunction(j);
            int node = positive(j);
            if (node != TRUE && !table.isSaturatedNode(node)) {
                table.pushToWorkStack(j);
                arrayWorkStackCount++;
            }
        }

        int result = computeCompose(function, domain, resolved);
        table.popFromWorkStack(arrayWorkStackCount);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public RegisteredOperation.Unary registerCompose(int[] variableMapping) {
        int[] resolved = variableMapping.clone();
        ComposeAnalysis analysis = analyzeCompose(resolved);
        if (analysis.maxReplacedLevel == -1) {
            return RegisteredOperation.identity();
        }
        if (analysis.isRestrict) {
            Cube restriction = analysis.restriction;
            return function -> restrict(function, restriction);
        }
        return new BddOperations.Compose(this, resolved, Util.protectNodes(this, resolved));
    }

    @Override
    public RegisteredOperation.Binary registerComposeSimplify(int[] variableMapping) {
        int[] resolved = variableMapping.clone();
        ComposeAnalysis analysis = analyzeCompose(resolved);
        if (analysis.maxReplacedLevel == -1) {
            return this::simplify;
        }
        if (analysis.isRestrict) {
            Cube restriction = analysis.restriction;
            return (function, domain) -> restrictSimplify(function, restriction, domain);
        }
        return new BddOperations.Compose(this, resolved, Util.protectNodes(this, resolved));
    }

    /** The greatest level a resolved mapping touches, or -1 if it replaces nothing. */
    int maxReplacedLevel(int[] variableMapping) {
        int max = -1;
        for (int variable = 0; variable < variableMapping.length; variable++) {
            if (variableMapping[variable] != this.variableNodes[variable]) {
                max = Math.max(max, levelOfVariable(variable));
            }
        }
        return max;
    }

    ComposeAnalysis analyzeCompose(int[] variableMapping) {
        int maxReplacedLevel = -1;
        for (int i = 0; i < variableMapping.length; i++) {
            if (variableMapping[i] == placeholder()) {
                variableMapping[i] = this.variableNodes[i];
            } else if (variableMapping[i] != this.variableNodes[i]) {
                maxReplacedLevel = Math.max(maxReplacedLevel, levelOfVariable(i));
            }
        }
        if (maxReplacedLevel == -1) {
            return new ComposeAnalysis(-1, false, Cube.empty());
        }

        // Detect the simple case where every replacement is either the variable itself or a constant.
        // Primary advantage: Delegate to simpler caches, the effective code paths are pretty similar.
        //
        // Note there is deliberately no separate "everything is constant, so just evaluate" case: a
        // mapping shorter than numberOfVariables() leaves the remaining variables *unchanged*, so the
        // result is generally not a constant at all. restrict covers that case correctly - it only
        // touches the variables it is given - so an all-constant mapping simply lands here.
        boolean isRestrict = true;
        for (int i = 0; i < variableMapping.length; i++) {
            if (!isConstant(variableMapping[i]) && variableMapping[i] != this.variableNodes[i]) {
                isRestrict = false;
                break;
            }
        }
        if (isRestrict) {
            MutableNatSet restrictValues = MutableNatSet.dense(variableMapping.length + 1);
            MutableNatSet restrictSupport = MutableNatSet.dense(variableMapping.length + 1);
            for (int i = 0; i < variableMapping.length; i++) {
                if (isConstant(variableMapping[i])) {
                    restrictSupport.set(i);
                    restrictValues.set(i, variableMapping[i] == TRUE);
                }
            }
            return new ComposeAnalysis(maxReplacedLevel, true, Cube.ofUnsafe(restrictValues, restrictSupport));
        }
        return new ComposeAnalysis(maxReplacedLevel, false, Cube.empty());
    }

    // Joint composition

    private Cube literalCube(int variable, boolean value) {
        int index = 2 * variable + (value ? 1 : 0);
        Cube[] cubes = literalCubes;
        if (index >= cubes.length) {
            int size = 2 * Math.max(numberOfVariables(), variable + 1);
            cubes = Arrays.copyOf(cubes, size);
            literalCubeHashes = Arrays.copyOf(literalCubeHashes, size);
            literalCubes = cubes;
        }
        Cube cube = cubes[index];
        if (cube == null) {
            cube = Cube.literal(variable, value);
            cubes[index] = cube;
            literalCubeHashes[index] = cube.hashCode();
        }
        return cube;
    }

    /** {@code function} with one variable fixed, through the stable restrict cache. */
    int restrictLiteral(int function, int variable, boolean value) {
        if (isConstant(function) || decisionLevel(function) > levelOfVariable(variable)) {
            return function;
        }
        Cube cube = literalCube(variable, value);
        return computeRestrict(
                function, cube, literalCubeHashes[2 * variable + (value ? 1 : 0)], levelOfVariable(variable));
    }

    /**
     * The support of a function as ascending variables, cached per node and never to be modified - what composition
     * reads per subtree. Support queries walk the diagram instead and take a cached array where they meet one
     * ({@link BddTable#cachedSupport}): filling the cache costs an array per node below.
     */
    int[] supportArray(int function) {
        int node = positive(function);
        if (node == TRUE) {
            return EMPTY_INT_ARRAY;
        }
        BooleanCache.UnaryToObjectCache<int[]> supportCache = cache.supportCache();
        int[] cached = supportCache.lookup(node);
        if (cached != null) {
            return cached;
        }
        int hash = supportCache.lookupHash();
        int[] low = supportArray(table.low(node));
        int[] high = supportArray(table.high(node));
        int variable = table.variable(node);
        int[] merged = new int[low.length + high.length + 1];
        int size = 0;
        int lowIndex = 0;
        int highIndex = 0;
        boolean variablePlaced = false;
        while (lowIndex < low.length || highIndex < high.length || !variablePlaced) {
            int next = Integer.MAX_VALUE;
            if (lowIndex < low.length) {
                next = low[lowIndex];
            }
            if (highIndex < high.length) {
                next = Math.min(next, high[highIndex]);
            }
            if (!variablePlaced) {
                next = Math.min(next, variable);
            }
            merged[size] = next;
            size += 1;
            if (lowIndex < low.length && low[lowIndex] == next) {
                lowIndex++;
            }
            if (highIndex < high.length && high[highIndex] == next) {
                highIndex++;
            }
            if (variable == next) {
                variablePlaced = true;
            }
        }
        int[] result = size == merged.length ? merged : Arrays.copyOf(merged, size);
        supportCache.put(hash, node, result);
        return result;
    }

    /** The replaced variables in {@code support}, ascending. */
    private static int[] replacedIn(int[] support, NatSet replaced) {
        int count = 0;
        for (int variable : support) {
            if (replaced.contains(variable)) {
                count++;
            }
        }
        if (count == support.length) {
            return support;
        }

        int[] variables = new int[count];
        int index = 0;
        for (int variable : support) {
            if (replaced.contains(variable)) {
                variables[index] = variable;
                index += 1;
            }
        }
        return variables;
    }

    /** Replacements aligned with {@code from}, cut down to the variables of {@code to} (a subset). */
    private static int[] project(int[] from, int[] replacements, int[] to) {
        if (from.length == to.length) {
            return replacements;
        }
        int[] projected = new int[to.length];
        int source = 0;
        for (int index = 0; index < to.length; index++) {
            while (from[source] != to[index]) {
                source += 1;
            }
            projected[index] = replacements[source];
        }
        return projected;
    }

    /*
     * Composition as a joint descent over (F, D, R_1..R_k): the function and the domain restricted to the path,
     * with the path-restricted replacements of the replaced variables F reads, all keyed in one stable cache.
     * A replacement the path made constant is substituted into F right away. Otherwise the step splits on F's
     * top variable: left alone, it is a path literal - the domain and every replacement are restricted by it, a
     * branch outside the domain is skipped, and the children are reassembled over it; replaced, it is the
     * classic if-then-else over the replacement (descending directly where the domain decides it). The result
     * agrees with the composition wherever the domain holds, and equals it for a TRUE domain.
     */
    int computeCompose(int function, int domain, int[] resolvedMapping) {
        MutableNatSet replaced = MutableNatSet.create();
        for (int variable = 0; variable < resolvedMapping.length; variable++) {
            if (resolvedMapping[variable] != this.variableNodes[variable]) {
                replaced.set(variable);
            }
        }
        table.pushToWorkStack(function);
        table.pushToWorkStack(domain);
        int[] variables = replacedIn(supportArray(function), replaced);
        int[] replacements = new int[variables.length];
        for (int index = 0; index < variables.length; index++) {
            replacements[index] = resolvedMapping[variables[index]];
        }
        int result = computeComposeRecursive(function, domain, variables, replacements, replaced);
        table.popFromWorkStack(2);
        // Only composition fills the support cache, so it grows with composition's use, not with the table.
        cache.supportCache().growOnUsage();
        return result;
    }

    private int computeComposeRecursive(
            int function, int domain, int[] variables, int[] replacements, NatSet replaced) {
        int pushed = 0;
        while (true) {
            if (domain == FALSE) {
                table.popFromWorkStack(pushed);
                return FALSE;
            }
            if (isConstant(function)) {
                table.popFromWorkStack(pushed);
                return function;
            }
            // TODO [COMPOSE-GATHER] Maybe better to gather all decided replacements and replace / project once?
            //   Could save several support computations
            int decided = -1;
            for (int index = 0; index < replacements.length; index++) {
                if (isConstant(replacements[index])) {
                    decided = index;
                    break;
                }
            }
            if (decided < 0) {
                break;
            }
            int next =
                    table.pushToWorkStack(restrictLiteral(function, variables[decided], replacements[decided] == TRUE));
            pushed++;
            int[] nextVariables = replacedIn(supportArray(next), replaced);
            replacements = project(variables, replacements, nextVariables);
            variables = nextVariables;
            function = next;
        }

        boolean complement = isComplementFunction(function);
        int node = positive(function);
        // The node and the domain, then the variables, then their replacements.
        int[] key = new int[2 + 2 * variables.length];
        key[0] = node;
        key[1] = domain;
        System.arraycopy(variables, 0, key, 2, variables.length);
        System.arraycopy(replacements, 0, key, 2 + variables.length, replacements.length);
        BooleanCache.ComposeTupleCache tupleCache = cache.composeTupleCache();
        int lookup = tupleCache.lookup(key);
        if (lookup != placeholder()) {
            table.popFromWorkStack(pushed);
            return complementIf(lookup, complement);
        }
        int hash = tupleCache.lookupHash;

        int topVariable = table.variable(node);
        int topLevel = levelOfVariable(topVariable);
        int lowFunction = table.low(node);
        int highFunction = table.high(node);
        int result;
        if (replaced.contains(topVariable)) {
            int condition = replacements[Util.indexOfSorted(variables, topVariable)];
            // If the expression we replace the current variable with is not in the domain, we can pin the variable to
            // false
            if (!intersectsRecursive(domain, condition)) {
                condition = FALSE;
            } else if (!intersectsRecursive(domain, complement(condition))) {
                condition = TRUE;
            }

            if (condition == TRUE || condition == FALSE) {
                int child = condition == TRUE ? highFunction : lowFunction;
                int[] childVariables = replacedIn(supportArray(child), replaced);
                result = computeComposeRecursive(
                        child, domain, childVariables, project(variables, replacements, childVariables), replaced);
            } else {
                int[] lowVariables = replacedIn(supportArray(lowFunction), replaced);
                int low = table.pushToWorkStack(computeComposeRecursive(
                        lowFunction, domain, lowVariables, project(variables, replacements, lowVariables), replaced));
                int[] highVariables = replacedIn(supportArray(highFunction), replaced);
                int high = table.pushToWorkStack(computeComposeRecursive(
                        highFunction,
                        domain,
                        highVariables,
                        project(variables, replacements, highVariables),
                        replaced));
                result = computeIfThenElse(condition, high, low);
                table.popFromWorkStack(2);
            }
        } else {
            int lowDomain = table.pushToWorkStack(restrictLiteral(domain, topVariable, false));
            int highDomain = table.pushToWorkStack(restrictLiteral(domain, topVariable, true));
            // The domain is not FALSE, so it excludes at most one branch.
            if (lowDomain == FALSE) {
                result = computeComposeBranch(
                        highFunction, highDomain, variables, replacements, replaced, topVariable, true);
            } else if (highDomain == FALSE) {
                result = computeComposeBranch(
                        lowFunction, lowDomain, variables, replacements, replaced, topVariable, false);
            } else {
                int low = table.pushToWorkStack(computeComposeBranch(
                        lowFunction, lowDomain, variables, replacements, replaced, topVariable, false));
                int high = table.pushToWorkStack(computeComposeBranch(
                        highFunction, highDomain, variables, replacements, replaced, topVariable, true));
                result = decisionLevelOrMax(high) > topLevel && decisionLevelOrMax(low) > topLevel
                        ? makeFunction(topLevel, low, high)
                        : computeIfThenElse(this.variableNodes[topVariable], high, low);
                table.popFromWorkStack(2);
            }
            table.popFromWorkStack(2);
        }
        tupleCache.put(hash, key, result);
        table.popFromWorkStack(pushed);
        return complementIf(result, complement);
    }

    /** One branch of a variable left alone: the replacements restricted to it, cut down to what the child reads. */
    private int computeComposeBranch(
            int child,
            int childDomain,
            int[] variables,
            int[] replacements,
            NatSet replaced,
            int branchVariable,
            boolean branchValue) {
        int[] childVariables = replacedIn(supportArray(child), replaced);
        // TODO [COMPOSE-POOL] We don't reuse replacements later; we can use a depth-pool construction to avoid
        // reallocation
        int[] childReplacements = new int[childVariables.length];
        int source = 0;
        for (int index = 0; index < childVariables.length; index++) {
            while (variables[source] != childVariables[index]) {
                source += 1;
            }
            childReplacements[index] =
                    table.pushToWorkStack(restrictLiteral(replacements[source], branchVariable, branchValue));
        }
        int result = computeComposeRecursive(child, childDomain, childVariables, childReplacements, replaced);
        table.popFromWorkStack(childReplacements.length);
        return result;
    }

    static final class ComposeAnalysis {
        final int maxReplacedLevel;
        final boolean isRestrict;
        // The restriction this compose amounts to, if it is one.
        final Cube restriction;

        ComposeAnalysis(int maxReplacedLevel, boolean isRestrict, Cube restriction) {
            this.maxReplacedLevel = maxReplacedLevel;
            this.isRestrict = isRestrict;
            this.restriction = restriction;
        }
    }

    @Override
    public int adopt(BinaryDecisionDiagram source, int function, IntUnaryOperator variableMapping) {
        if (!(source instanceof BddImpl)) {
            return BddUtil.adopt(this, source, function, variableMapping);
        }
        BddImpl bddSource = (BddImpl) source;
        assert bddSource.isValidFunction(function);
        if (isConstant(function)) {
            return function;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        // Adopting from this diagram itself, the source must survive the collections the rebuilding may cause.
        boolean fromItself = bddSource == this; // NOPMD - identity is the point of the check
        if (fromItself) {
            table.pushToWorkStack(function);
        }
        // Every rebuilt node sits on the work stack until the end, so no collection in between invalidates the memo.
        IntIntHashMap adopted = new IntIntHashMap();
        int result = adoptRecursive(bddSource, function, variableMapping, adopted);
        table.popFromWorkStack(adopted.size() + (fromItself ? 1 : 0));
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    /* One memo entry per source node, a complemented edge adopting to the complement. A node whose mapped variable
     * lies above both rebuilt children is a single node here; otherwise the if-then-else restructures. */
    private int adoptRecursive(BddImpl source, int function, IntUnaryOperator variableMapping, IntIntHashMap adopted) {
        int node = positive(function);
        if (node == TRUE) {
            return function;
        }
        int rebuilt = adopted.get(node, NodeTable.PLACEHOLDER);
        if (rebuilt == NodeTable.PLACEHOLDER) {
            NodeTable.Binary sourceTable = source.table;
            int high = adoptRecursive(source, sourceTable.high(node), variableMapping, adopted);
            int low = adoptRecursive(source, sourceTable.low(node), variableMapping, adopted);
            int variable = variableMapping.applyAsInt(sourceTable.variable(node));
            if (variable < 0 || variable >= numberOfVariables()) {
                throw new IllegalArgumentException(String.format("Variable %d does not exist", variable));
            }
            int level = levelOfVariable(variable);
            rebuilt = level < decisionLevelOrMax(high) && level < decisionLevelOrMax(low)
                    ? makeFunction(level, low, high)
                    : computeIfThenElse(variableNodes[variable], high, low);
            table.pushToWorkStack(rebuilt);
            adopted.put(node, rebuilt);
        }
        return complementIf(rebuilt, node != function);
    }

    @Override
    public int restrict(int function, Cube restriction) {
        assert isValidFunction(function);
        // A level is read per fixed variable, so each must exist (as for quantification).
        checkVariablesExist(restriction.support(), numberOfVariables());

        if (restriction.isEmpty() || isConstant(function)) {
            return function;
        }

        int current = function;
        while (!isConstant(current) && restriction.support().contains(table.variable(positive(current)))) {
            current =
                    restriction.assignment().contains(table.variable(positive(current))) ? high(current) : low(current);
        }
        if (isConstant(current)) {
            return current;
        }
        int maxRestrictedLevel = maxLevel(restriction.support());
        if (decisionLevelOrMax(current) > maxRestrictedLevel) {
            return current;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        Cube remaining = order.literalsBelow(restriction, decisionLevel(current));
        table.pushToWorkStack(current);
        int result = computeRestrict(current, remaining, remaining.hashCode(), maxRestrictedLevel);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeRestrict(int function, Cube restriction, int cubeHash, int maxRestrictedLevel) {
        boolean func = isComplementFunction(function);
        int node = positive(function);
        if (node == TRUE) {
            return function;
        }
        int nodeVariable = table.variable(node);
        int nodeLevel = levelOfVariable(nodeVariable);
        if (nodeLevel > maxRestrictedLevel) {
            return function;
        }
        if (restriction.support().contains(nodeVariable)) {
            int child = restriction.assignment().contains(nodeVariable) ? table.high(node) : table.low(node);
            return complementIf(computeRestrict(child, restriction, cubeHash, maxRestrictedLevel), func);
        }

        BooleanCache.RestrictCubeCache restrictCache = cache.restrictCubeCache();
        int lookup = restrictCache.lookup(node, TRUE, restriction, cubeHash);
        if (lookup != placeholder()) {
            return complementIf(lookup, func);
        }
        int hash = restrictCache.lookupHash;
        int low = table.pushToWorkStack(computeRestrict(table.low(node), restriction, cubeHash, maxRestrictedLevel));
        int high = table.pushToWorkStack(computeRestrict(table.high(node), restriction, cubeHash, maxRestrictedLevel));
        int result = makeFunction(nodeLevel, low, high);
        table.popFromWorkStack(2);
        restrictCache.put(hash, node, TRUE, restriction, result);
        return complementIf(result, func);
    }

    /**
     * {@code simplify(restrict(function, restriction), domain)} in one recursion, the domain narrowed on the way
     * down.
     */
    int restrictSimplify(int function, Cube restriction, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);
        if (domain == FALSE) {
            return FALSE;
        }
        if (domain == TRUE) {
            return restrict(function, restriction);
        }
        if (restriction.isEmpty()) {
            return simplify(function, domain);
        }
        if (isConstant(function)) {
            return function;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        // A copy: the cube becomes a cache key and may be a walk's working state.
        Cube cube = restriction.copy();
        table.pushToWorkStack(function, domain);
        int result = computeRestrictSimplify(function, cube, cube.hashCode(), maxLevel(cube.support()), domain);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    /*
     * computeRestrict with the domain of computeConstrainSimplify carried along: narrowed where it excludes a branch,
     * widened to its disjunction where it decides above the function - and likewise on a restricted variable, since the
     * result no longer depends on it and so has to hold for both of the domain's branches there.
     */
    private int computeRestrictSimplify(
            int function, Cube restriction, int cubeHash, int maxRestrictedLevel, int domain) {
        assert domain != FALSE;
        if (domain == TRUE) {
            return computeRestrict(function, restriction, cubeHash, maxRestrictedLevel);
        }
        boolean func = isComplementFunction(function);
        int node = positive(function);
        if (node == TRUE) {
            return function;
        }
        int nodeVariable = table.variable(node);
        int nodeLevel = levelOfVariable(nodeVariable);
        if (nodeLevel > maxRestrictedLevel) {
            return computeSimplify(function, domain);
        }

        BooleanCache.RestrictCubeCache restrictCache = cache.restrictCubeCache();
        int lookup = restrictCache.lookup(node, domain, restriction, cubeHash);
        if (lookup != placeholder()) {
            return complementIf(lookup, func);
        }
        int hash = restrictCache.lookupHash;

        int domainLevel = decisionLevel(domain);
        int result;
        if (domainLevel < nodeLevel) {
            int domainLow = low(domain);
            int domainHigh = high(domain);
            if (domainLow == FALSE) {
                result = computeRestrictSimplify(node, restriction, cubeHash, maxRestrictedLevel, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeRestrictSimplify(node, restriction, cubeHash, maxRestrictedLevel, domainLow);
            } else {
                int widened = table.pushToWorkStack(computeOr(domainLow, domainHigh));
                result = computeRestrictSimplify(node, restriction, cubeHash, maxRestrictedLevel, widened);
                table.popFromWorkStack();
            }
        } else if (restriction.support().contains(nodeVariable)) {
            int child = restriction.assignment().contains(nodeVariable) ? table.high(node) : table.low(node);
            if (domainLevel == nodeLevel) {
                int domainLow = low(domain);
                int domainHigh = high(domain);
                if (domainLow == FALSE) {
                    result = computeRestrictSimplify(child, restriction, cubeHash, maxRestrictedLevel, domainHigh);
                } else if (domainHigh == FALSE) {
                    result = computeRestrictSimplify(child, restriction, cubeHash, maxRestrictedLevel, domainLow);
                } else {
                    int widened = table.pushToWorkStack(computeOr(domainLow, domainHigh));
                    result = computeRestrictSimplify(child, restriction, cubeHash, maxRestrictedLevel, widened);
                    table.popFromWorkStack();
                }
            } else {
                result = computeRestrictSimplify(child, restriction, cubeHash, maxRestrictedLevel, domain);
            }
        } else {
            boolean domainDecides = domainLevel == nodeLevel;
            int domainLow = lowIf(domain, domainDecides);
            int domainHigh = highIf(domain, domainDecides);
            if (domainLow == FALSE) {
                result = computeRestrictSimplify(
                        table.high(node), restriction, cubeHash, maxRestrictedLevel, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeRestrictSimplify(table.low(node), restriction, cubeHash, maxRestrictedLevel, domainLow);
            } else {
                int low = table.pushToWorkStack(
                        computeRestrictSimplify(table.low(node), restriction, cubeHash, maxRestrictedLevel, domainLow));
                int high = table.pushToWorkStack(computeRestrictSimplify(
                        table.high(node), restriction, cubeHash, maxRestrictedLevel, domainHigh));
                result = makeFunction(nodeLevel, low, high);
                table.popFromWorkStack(2);
            }
        }
        restrictCache.put(hash, node, domain, restriction, result);
        return complementIf(result, func);
    }

    @Override
    public int conjunction(NatSet variables) {
        assert variables.allMatch(this::isValidVariable);
        assert accessGuard.acquire();
        int node = cubeFunction(Cube.ofUnsafe(variables, variables));
        assert accessGuard.release();
        return node;
    }

    @Override
    public int disjunction(NatSet variables) {
        assert variables.allMatch(this::isValidVariable);
        assert accessGuard.acquire();
        // x1 | ... | xn is !(!x1 & ... & !xn)
        int node = not(cubeFunction(Cube.ofUnsafe(MutableNatSet.dense(0), variables)));
        assert accessGuard.release();
        return node;
    }

    @Override
    public int and(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function1, function2);
        int result = computeAnd(function1, function2);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public int and(int[] functions) {
        assert Arrays.stream(functions).allMatch(this::isValidFunction);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        int result = andAll(functions);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int andAll(int[] functions) {
        long[] keys = new long[functions.length];
        int count = 0;
        for (int function : functions) {
            if (function == FALSE) {
                return FALSE;
            }
            if (function != TRUE) {
                keys[count] = operandKey(function, decisionLevel(function));
                count += 1;
            }
        }
        int[] operands = canonicalOperands(keys, count, NO_KEYS);
        if (operands == null) {
            return FALSE;
        }
        if (operands.length == 1) {
            return operands[0];
        }
        table.pushToWorkStack(operands);
        int result;
        if (operands.length == 2) {
            result = computeAnd(operands[0], operands[1]);
        } else {
            result = computeAndAll(operands);
        }
        table.popFromWorkStack(operands.length);
        return result;
    }

    // Deepest top level first, which is from the end of the sorted operands: the accumulator stays in the lower levels
    // while it is built and each shallower operand adds on top, instead of being dragged through every level at each
    // step.
    private int computeAndAllPairwise(int[] operands) {
        int result = TRUE;
        for (int i = operands.length - 1; i >= 0; i--) {
            table.pushToWorkStack(result);
            result = computeAnd(result, operands[i]);
            table.popFromWorkStack();
            if (result == FALSE) {
                break;
            }
        }
        return result;
    }

    @Override
    public int or(int[] functions) {
        assert Arrays.stream(functions).allMatch(this::isValidFunction);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        int[] complemented = functions.clone();
        complementAll(complemented);
        int result = not(andAll(complemented));
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private static void complementAll(int[] functions) {
        for (int i = 0; i < functions.length; i++) {
            functions[i] = complement(functions[i]);
        }
    }

    // A node is below 2^31, so node and sign fill the low word and the level sorts above them.
    private static long operandKey(int function, int level) {
        return ((long) level << Integer.SIZE) | ((long) positive(function) << 1) | (function < 0 ? 1 : 0);
    }

    /*
     * The canonical operand tuple of a conjunction, which is what the n-ary cache keys on: no constants, no
     * duplicates, sorted by top level, then by node, then by sign - so that a complementary pair is adjacent, the
     * operands deciding the next step lead, and the deepest close the tuple. Made of the operands with keys[0, count),
     * sorted here in place, and those with the keys of sorted, which are in order already and merged in. Null for a
     * conjunction that is false (a complementary pair), an empty array for one that is true.
     */
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null says false; the empty tuple says true
    private static int @Nullable [] canonicalOperands(long[] keys, int count, long[] sorted) {
        int total = count + sorted.length;
        if (total == 0) {
            return EMPTY_INT_ARRAY;
        }
        Arrays.sort(keys, 0, count);
        int[] operands = new int[total];
        int size = 0;
        int previous = 0;
        int next = 0;
        int nextSorted = 0;
        while (next < count || nextSorted < sorted.length) {
            long key;
            if (nextSorted == sorted.length || (next < count && keys[next] < sorted[nextSorted])) {
                key = keys[next];
                next += 1;
            } else {
                key = sorted[nextSorted];
                nextSorted += 1;
            }
            // A node has one level, so equal nodes are equal above the sign bit.
            int functionReverse = (int) (key & Integer.MAX_VALUE);
            int node = functionReverse >>> 1;
            if (size > 0 && node == (previous >>> 1)) {
                if (functionReverse == previous) {
                    // Equal functions
                    continue;
                }
                // Complementary functions -- important short-circuit
                return null;
            }
            operands[size] = (functionReverse & 1) == 0 ? node : -node;
            size += 1;
            previous = functionReverse;
        }
        return size == total ? operands : Arrays.copyOf(operands, size);
    }

    /*
     * The true n-ary conjunction: one recursion over the operand tuple, expanding on the minimal top level, every
     * cofactored tuple canonicalized again so operands that became true drop out and a false one or a complementary
     * pair ends the branch. Nothing but the result is built, and the tuple cache shares a sub-conjunction reached
     * along several paths. The operands are protected by the caller; the cofactors are their children. Against a
     * pairwise fold (deepest top level first) on unions of hundreds to thousands of cubes over the same variables: a
     * quarter to an eighth of the nodes created.
     */
    @SuppressWarnings("PMD.VariableDeclarationUsageDistance") // the hash is read before the recursion overwrites it
    private int computeAndAll(int[] operands) {
        int count = operands.length;
        if (count == 0) {
            return TRUE;
        }
        if (count == 1) {
            return operands[0];
        }
        if (count == 2) {
            return computeAnd(operands[0], operands[1]);
        }

        BooleanCache.OperandTupleCache andAllCache = cache.andAllCache();
        int lookup = andAllCache.lookup(operands);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = andAllCache.lookupHash;

        // The tuple is sorted by top level: the operands deciding this step lead, the rest pass into both
        // cofactors unchanged and in order, so only the deciding ones' children are sorted, then merged in.
        int level = decisionLevel(operands[0]);
        int deciding = 1;
        while (deciding < count && decisionLevel(operands[deciding]) == level) {
            deciding += 1;
        }
        long[] passing = new long[count - deciding];
        for (int i = deciding; i < count; i++) {
            passing[i - deciding] = operandKey(operands[i], decisionLevel(operands[i]));
        }
        // Where the operands lie at mostly distinct levels, nothing shrinks along the path any more and pairwise is
        // cheaper - for the whole subtree, which never comes back to the n-ary.
        // TODO [NARY-SPLIT] Tune when to switch: a flat ratio, applied at every step, also switches deep tuples that
        //   would still have shrunk.
        int distinct = 1;
        for (int i = 0; i < passing.length; i++) {
            if (i == 0 || (passing[i] >>> Integer.SIZE) != (passing[i - 1] >>> Integer.SIZE)) {
                distinct += 1;
            }
        }
        if ((long) distinct * NARY_MINIMUM_OPERANDS_PER_TOP_LEVEL > count) {
            int pairwise = computeAndAllPairwise(operands);
            andAllCache.put(hash, operands, pairwise);
            return pairwise;
        }
        long[] lowKeys = new long[deciding];
        long[] highKeys = new long[deciding];
        int lowCount = 0;
        int highCount = 0;
        boolean lowIsFalse = false;
        boolean highIsFalse = false;
        for (int i = 0; i < deciding; i++) {
            if (!lowIsFalse) {
                int lowChild = low(operands[i]);
                if (lowChild == FALSE) {
                    lowIsFalse = true;
                } else if (lowChild != TRUE) {
                    lowKeys[lowCount] = operandKey(lowChild, decisionLevel(lowChild));
                    lowCount += 1;
                }
            }
            if (!highIsFalse) {
                int highChild = high(operands[i]);
                if (highChild == FALSE) {
                    highIsFalse = true;
                } else if (highChild != TRUE) {
                    highKeys[highCount] = operandKey(highChild, decisionLevel(highChild));
                    highCount += 1;
                }
            }
        }
        int[] lowTuple = lowIsFalse ? null : canonicalOperands(lowKeys, lowCount, passing);
        int low = lowTuple == null ? FALSE : table.pushToWorkStack(computeAndAll(lowTuple));
        int[] highTuple = highIsFalse ? null : canonicalOperands(highKeys, highCount, passing);
        int high = highTuple == null ? FALSE : table.pushToWorkStack(computeAndAll(highTuple));
        int result = makeFunction(level, low, high);
        //noinspection VariableNotUsedInsideIf
        table.popFromWorkStack((lowTuple == null ? 0 : 1) + (highTuple == null ? 0 : 1));
        andAllCache.put(hash, operands, result);
        return result;
    }

    @Override
    public int andSimplify(int function1, int function2, int domain) {
        assert isValidFunction(function1) && isValidFunction(function2) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function1, function2, domain);
        int result = computeAndSimplify(function1, function2, domain);
        table.popFromWorkStack(3);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeAnd(int function1, int function2) {
        if (function1 == TRUE) {
            return function2;
        }
        if (function2 == TRUE) {
            return function1;
        }
        if (function1 == FALSE || function2 == FALSE) {
            return FALSE;
        }
        if (function1 == function2) {
            return function1;
        }
        if (function1 == complement(function2)) {
            return FALSE;
        }

        assert !isConstant(function1) && !isConstant(function2);

        if (function1 > function2) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;
        }

        int lookup = cache.lookupAnd(function1, function2);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int fun1Level = decisionLevel(function1);
        int fun2Level = decisionLevel(function2);
        int level = Math.min(fun1Level, fun2Level);

        int low = table.pushToWorkStack(
                computeAnd(lowIf(function1, fun1Level == level), lowIf(function2, fun2Level == level)));
        int high = table.pushToWorkStack(
                computeAnd(highIf(function1, fun1Level == level), highIf(function2, fun2Level == level)));
        int result = makeFunction(level, low, high);
        table.popFromWorkStack(2);
        cache.putAnd(hash, function1, function2, result);
        return result;
    }

    private int computeAndSimplify(int function1, int function2, int domain) {
        assert domain != FALSE;
        if (domain == TRUE) {
            return computeAnd(function1, function2);
        }
        if (domain == function1) {
            return computeSimplify(function2, domain);
        }
        if (domain == function2) {
            return computeSimplify(function1, domain);
        }
        if (domain == complement(function1) || domain == complement(function2)) {
            return FALSE;
        }

        if (function1 == TRUE) {
            return computeSimplify(function2, domain);
        }
        if (function2 == TRUE) {
            return computeSimplify(function1, domain);
        }
        if (function1 == FALSE || function2 == FALSE) {
            return FALSE;
        }
        if (function1 == function2) {
            return computeSimplify(function1, domain);
        }
        if (function1 == complement(function2)) {
            return FALSE;
        }

        assert !isConstant(function1) && !isConstant(function2) && !isConstant(domain);

        if (function1 > function2) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;
        }

        int lookup = cache.lookupAndSimplify(function1, function2, domain);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int fun1Level = decisionLevel(function1);
        int fun2Level = decisionLevel(function2);
        int level = Math.min(fun1Level, fun2Level);
        int domainLevel = decisionLevel(domain);

        int result;
        if (domainLevel < level) {
            int domainLow = low(domain);
            int domainHigh = high(domain);
            if (domainLow == FALSE) {
                result = computeAndSimplify(function1, function2, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeAndSimplify(function1, function2, domainLow);
            } else {
                result = computeAndSimplify(
                        function1, function2, table.pushToWorkStack(computeOr(domainLow, domainHigh)));
                table.popFromWorkStack();
            }
        } else {
            int low1 = lowIf(function1, fun1Level == level);
            int high1 = highIf(function1, fun1Level == level);
            int low2 = lowIf(function2, fun2Level == level);
            int high2 = highIf(function2, fun2Level == level);

            if (domainLevel == level) {
                int domainLow = low(domain);
                int domainHigh = high(domain);

                if (domainLow == FALSE) {
                    result = computeAndSimplify(high1, high2, domainHigh);
                } else if (domainHigh == FALSE) {
                    result = computeAndSimplify(low1, low2, domainLow);
                } else {
                    result = makeFunction(
                            level,
                            table.pushToWorkStack(computeAndSimplify(low1, low2, domainLow)),
                            table.pushToWorkStack(computeAndSimplify(high1, high2, domainHigh)));
                    table.popFromWorkStack(2);
                }
            } else {
                result = makeFunction(
                        level,
                        table.pushToWorkStack(computeAndSimplify(low1, low2, domain)),
                        table.pushToWorkStack(computeAndSimplify(high1, high2, domain)));
                table.popFromWorkStack(2);
            }
        }

        cache.putAndSimplify(hash, function1, function2, domain, result);
        return result;
    }

    int computeOr(int function1, int function2) {
        return complement(computeAnd(complement(function1), complement(function2)));
    }

    private int computeOrSimplify(int function1, int function2, int domain) {
        return complement(computeAndSimplify(complement(function1), complement(function2), domain));
    }

    @Override
    public int xor(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);
        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function1, function2);
        int result = computeXor(function1, function2);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public int xorSimplify(int function1, int function2, int domain) {
        assert isValidFunction(function1) && isValidFunction(function2) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function1, function2, domain);
        int result = computeXorSimplify(function1, function2, domain);
        table.popFromWorkStack(3);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeXor(int function1, int function2) {
        boolean negate = isComplementFunction(function1) ^ isComplementFunction(function2);
        function1 = positive(function1);
        function2 = positive(function2);

        if (function1 == TRUE) {
            return complementIf(function2, !negate);
        }
        if (function2 == TRUE) {
            return complementIf(function1, !negate);
        }
        if (function1 == function2) {
            return negate ? TRUE : FALSE;
        }

        if (function1 > function2) {
            int functionSwap = function1;
            function1 = function2;
            function2 = functionSwap;
        }

        int lookup = cache.lookupXor(function1, function2);
        if (lookup != placeholder()) {
            return complementIf(lookup, negate);
        }
        int hash = cache.lookupHash();

        int fun1Level = decisionLevel(function1);
        int fun2Level = decisionLevel(function2);
        int level = Math.min(fun1Level, fun2Level);

        int low = table.pushToWorkStack(
                computeXor(lowIf(function1, fun1Level == level), lowIf(function2, fun2Level == level)));
        int high = table.pushToWorkStack(
                computeXor(highIf(function1, fun1Level == level), highIf(function2, fun2Level == level)));
        int result = makeFunction(level, low, high);
        table.popFromWorkStack(2);
        cache.putXor(hash, function1, function2, result);
        return complementIf(result, negate);
    }

    private int computeXorSimplify(int function1, int function2, int domain) {
        assert domain != FALSE;
        if (domain == TRUE) {
            return computeXor(function1, function2);
        }
        if (domain == function1) {
            return computeSimplify(complement(function2), domain);
        }
        if (domain == function2) {
            return computeSimplify(complement(function1), domain);
        }
        if (domain == complement(function1)) {
            return computeSimplify(function2, domain);
        }
        if (domain == complement(function2)) {
            return computeSimplify(function1, domain);
        }

        if (function1 == TRUE) {
            return computeSimplify(complement(function2), domain);
        }
        if (function1 == FALSE) {
            return computeSimplify(function2, domain);
        }
        if (function2 == TRUE) {
            return computeSimplify(complement(function1), domain);
        }
        if (function2 == FALSE) {
            return computeSimplify(function1, domain);
        }
        if (function1 == function2) {
            return FALSE;
        }
        if (function1 == complement(function2)) {
            return TRUE;
        }

        assert !isConstant(function1) && !isConstant(function2) && !isConstant(domain);

        boolean negate = isComplementFunction(function1) ^ isComplementFunction(function2);
        function1 = positive(function1);
        function2 = positive(function2);

        if (function1 > function2) {
            int functionSwap = function1;
            function1 = function2;
            function2 = functionSwap;
        }

        int lookup = cache.lookupXorSimplify(function1, function2, domain);
        if (lookup != placeholder()) {
            return complementIf(lookup, negate);
        }
        int hash = cache.lookupHash();

        int fun1Level = decisionLevel(function1);
        int fun2Level = decisionLevel(function2);
        int level = Math.min(fun1Level, fun2Level);
        int domainLevel = decisionLevel(domain);

        int result;
        if (domainLevel < level) {
            int domainLow = low(domain);
            int domainHigh = high(domain);
            if (domainLow == FALSE) {
                result = computeXorSimplify(function1, function2, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeXorSimplify(function1, function2, domainLow);
            } else {
                result = computeXorSimplify(
                        function1, function2, table.pushToWorkStack(computeOr(domainLow, domainHigh)));
                table.popFromWorkStack();
            }
        } else {
            int low1 = lowIf(function1, fun1Level == level);
            int high1 = highIf(function1, fun1Level == level);
            int low2 = lowIf(function2, fun2Level == level);
            int high2 = highIf(function2, fun2Level == level);

            if (domainLevel == level) {
                int domainLow = low(domain);
                int domainHigh = high(domain);

                if (domainLow == FALSE) {
                    result = computeXorSimplify(high1, high2, domainHigh);
                } else if (domainHigh == FALSE) {
                    result = computeXorSimplify(low1, low2, domainLow);
                } else {
                    result = makeFunction(
                            level,
                            table.pushToWorkStack(computeXorSimplify(low1, low2, domainLow)),
                            table.pushToWorkStack(computeXorSimplify(high1, high2, domainHigh)));
                    table.popFromWorkStack(2);
                }
            } else {
                result = makeFunction(
                        level,
                        table.pushToWorkStack(computeXorSimplify(low1, low2, domain)),
                        table.pushToWorkStack(computeXorSimplify(high1, high2, domain)));
                table.popFromWorkStack(2);
            }
        }
        cache.putXorSimplify(hash, function1, function2, domain, result);
        return complementIf(result, negate);
    }

    @Override
    public int exists(int function, NatSet quantifiedVariables) {
        assert isValidFunction(function);
        checkVariablesExist(quantifiedVariables, numberOfVariables());

        if (isConstant(function)) {
            return function;
        }
        if (quantifiedVariables.size() == numberOfVariables()) {
            return TRUE;
        }

        assert accessGuard.acquire();
        cache.initExists(quantifiedVariables);
        // The recursion descends by level, so it needs the quantified set indexed the same way.
        int result = existsGeneral(function, variablesToLevels(quantifiedVariables), cache.existsCache());
        assert accessGuard.release();
        return result;
    }

    @Override
    public RegisteredOperation.Unary registerExists(NatSet quantifiedVariables) {
        checkVariablesExist(quantifiedVariables, numberOfVariables());
        if (quantifiedVariables.isEmpty()) {
            return RegisteredOperation.identity();
        }
        return new BddOperations.Exists(this, MutableNatSet.copyOf(quantifiedVariables));
    }

    int existsGeneral(int function, NatSet quantifiedLevels, BooleanCache.UnaryToIntCache existsCache) {
        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function);
        int result = existsRecursive(function, quantifiedLevels, existsCache);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    NatSet variablesToLevels(NatSet variables) {
        return isReordered() ? NatSets.map(variables, this::levelOfVariable) : variables;
    }

    private int existsRecursive(int function, NatSet quantifiedLevels, BooleanCache.UnaryToIntCache existsCache) {
        assert isValidFunction(function);

        if (isConstant(function)) {
            return function;
        }

        int level = decisionLevel(function);
        int nextQuantifiedLevel = quantifiedLevels.nextSetBit(level);
        if (nextQuantifiedLevel == -1) {
            return function;
        }
        if (isVariableOrNegated(function)) {
            if (level == nextQuantifiedLevel) {
                return TRUE;
            }
            return function;
        }

        int lookup = existsCache.lookup(function);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = existsCache.lookupHash();

        int lowExists = table.pushToWorkStack(existsRecursive(low(function), quantifiedLevels, existsCache));
        int highExists = table.pushToWorkStack(existsRecursive(high(function), quantifiedLevels, existsCache));
        int result;
        if (nextQuantifiedLevel > level) {
            // The level of this node is smaller than the level looked for - only propagate the
            // quantification downward
            result = makeFunction(level, lowExists, highExists);
        } else {
            // level == nextVariable, i.e. "quantify out" the current node.
            result = computeOr(lowExists, highExists);
        }

        table.popFromWorkStack(2);
        existsCache.put(hash, function, result);
        return result;
    }

    @Override
    public int ifThenElse(int ifFunction, int thenFunction, int elseFunction) {
        assert isValidFunction(ifFunction) && isValidFunction(thenFunction) && isValidFunction(elseFunction);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(ifFunction, thenFunction, elseFunction);
        int result = computeIfThenElse(ifFunction, thenFunction, elseFunction);
        table.popFromWorkStack(3);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public int ifThenElseSimplify(int ifFunction, int thenFunction, int elseFunction, int domain) {
        assert isValidFunction(ifFunction)
                && isValidFunction(thenFunction)
                && isValidFunction(elseFunction)
                && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(ifFunction, thenFunction, elseFunction, domain);
        int result = computeIfThenElseSimplify(ifFunction, thenFunction, elseFunction, domain);
        table.popFromWorkStack(4);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeIfThenElse(int ifFunction, int thenFunction, int elseFunction) {
        if (ifFunction == TRUE) {
            return thenFunction;
        }
        if (ifFunction == FALSE) {
            return elseFunction;
        }

        if (thenFunction == TRUE || thenFunction == ifFunction) {
            return computeOr(ifFunction, elseFunction);
        }
        if (thenFunction == FALSE || thenFunction == complement(ifFunction)) {
            return computeAnd(complement(ifFunction), elseFunction);
        }

        if (elseFunction == TRUE || elseFunction == complement(ifFunction)) {
            return complement(computeAnd(ifFunction, complement(thenFunction)));
        }
        if (elseFunction == FALSE || ifFunction == elseFunction) {
            return computeAnd(ifFunction, thenFunction);
        }

        if (thenFunction == elseFunction) {
            return thenFunction;
        }
        if (thenFunction == complement(elseFunction)) {
            return computeXor(ifFunction, elseFunction);
        }

        // Normalize so that at most else is complemented
        int ifNormalized = positive(ifFunction);
        int thenSwap;
        int elseSwap;
        if (ifNormalized == ifFunction) {
            thenSwap = thenFunction;
            elseSwap = elseFunction;
        } else {
            thenSwap = elseFunction;
            elseSwap = thenFunction;
        }

        boolean complement = false;
        int thenNormalized = positive(thenSwap);
        int elseNormalized;
        if (thenNormalized == thenSwap) {
            elseNormalized = elseSwap;
        } else {
            elseNormalized = complement(elseSwap);
            complement = true;
        }
        assert isPositive(ifNormalized) && isPositive(thenNormalized);

        int lookup = cache.lookupIfThenElse(ifNormalized, thenNormalized, elseNormalized);
        if (lookup != placeholder()) {
            return complementIf(lookup, complement);
        }
        int hash = cache.lookupHash();
        int ifLevel = decisionLevel(ifNormalized);
        int thenLevel = decisionLevel(thenNormalized);
        int elseLevel = decisionLevel(elseNormalized);

        int minLevel = Math.min(ifLevel, Math.min(thenLevel, elseLevel));
        int ifLow = ifLevel == minLevel ? table.low(ifNormalized) : ifNormalized;
        int ifHigh = ifLevel == minLevel ? table.high(ifNormalized) : ifNormalized;
        int thenLow = thenLevel == minLevel ? table.low(thenNormalized) : thenNormalized;
        int thenHigh = thenLevel == minLevel ? table.high(thenNormalized) : thenNormalized;
        int elseLow = lowIf(elseNormalized, elseLevel == minLevel);
        int elseHigh = highIf(elseNormalized, elseLevel == minLevel);

        int low = table.pushToWorkStack(computeIfThenElse(ifLow, thenLow, elseLow));
        int high = table.pushToWorkStack(computeIfThenElse(ifHigh, thenHigh, elseHigh));
        int result = makeFunction(minLevel, low, high);
        table.popFromWorkStack(2);
        cache.putIfThenElse(hash, ifNormalized, thenNormalized, elseNormalized, result);
        return complementIf(result, complement);
    }

    private int computeIfThenElseSimplify(int ifFunction, int thenFunction, int elseFunction, int domain) {
        assert domain != FALSE;
        if (domain == TRUE) {
            return computeIfThenElse(ifFunction, thenFunction, elseFunction);
        }
        if (domain == ifFunction) {
            return computeSimplify(thenFunction, domain);
        }
        if (domain == complement(ifFunction)) {
            return computeSimplify(elseFunction, domain);
        }

        if (ifFunction == TRUE) {
            return computeSimplify(thenFunction, domain);
        }
        if (ifFunction == FALSE) {
            return computeSimplify(elseFunction, domain);
        }

        if (thenFunction == TRUE || thenFunction == ifFunction || thenFunction == domain) {
            return computeOrSimplify(ifFunction, elseFunction, domain);
        }
        if (thenFunction == FALSE || thenFunction == complement(ifFunction) || thenFunction == complement(domain)) {
            return computeAndSimplify(complement(ifFunction), elseFunction, domain);
        }

        if (elseFunction == TRUE || elseFunction == complement(ifFunction) || elseFunction == domain) {
            return complement(computeAndSimplify(ifFunction, complement(thenFunction), domain));
        }
        if (elseFunction == FALSE || ifFunction == elseFunction || elseFunction == complement(domain)) {
            return computeAndSimplify(ifFunction, thenFunction, domain);
        }

        if (thenFunction == elseFunction) {
            return computeSimplify(thenFunction, domain);
        }
        if (thenFunction == complement(elseFunction)) {
            return computeXorSimplify(ifFunction, elseFunction, domain);
        }

        // Normalize so that at most else is complemented
        int ifNormalized = positive(ifFunction);
        int thenSwap;
        int elseSwap;
        if (ifNormalized == ifFunction) {
            thenSwap = thenFunction;
            elseSwap = elseFunction;
        } else {
            thenSwap = elseFunction;
            elseSwap = thenFunction;
        }

        boolean complement = false;
        int thenNormalized = positive(thenSwap);
        int elseNormalized;
        if (thenNormalized == thenSwap) {
            elseNormalized = elseSwap;
        } else {
            elseNormalized = complement(elseSwap);
            complement = true;
        }
        assert isPositive(ifNormalized) && isPositive(thenNormalized);

        int lookup = cache.lookupIfThenElseSimplify(ifNormalized, thenNormalized, elseNormalized, domain);
        if (lookup != placeholder()) {
            return complementIf(lookup, complement);
        }
        int hash = cache.lookupHash();

        int ifLevel = decisionLevel(ifNormalized);
        int thenLevel = decisionLevel(thenNormalized);
        int elseLevel = decisionLevel(elseNormalized);
        int domainLevel = decisionLevel(domain);

        int minDecisionLevel = Math.min(ifLevel, Math.min(thenLevel, elseLevel));
        int minLevel = Math.min(domainLevel, minDecisionLevel);
        int ifLow = ifLevel == minLevel ? table.low(ifNormalized) : ifNormalized;
        int ifHigh = ifLevel == minLevel ? table.high(ifNormalized) : ifNormalized;
        int thenLow = thenLevel == minLevel ? table.low(thenNormalized) : thenNormalized;
        int thenHigh = thenLevel == minLevel ? table.high(thenNormalized) : thenNormalized;
        int elseLow = lowIf(elseNormalized, elseLevel == minLevel);
        int elseHigh = highIf(elseNormalized, elseLevel == minLevel);
        int domainLow = lowIf(domain, domainLevel == minLevel);
        int domainHigh = highIf(domain, domainLevel == minLevel);

        int result;
        if (domainLevel < minDecisionLevel) {
            if (domainLow == FALSE) {
                result = computeIfThenElseSimplify(ifHigh, thenHigh, elseHigh, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeIfThenElseSimplify(ifLow, thenLow, elseLow, domainLow);
            } else {
                result = computeIfThenElseSimplify(
                        ifLow, thenLow, elseLow, table.pushToWorkStack(computeOr(domainLow, domainHigh)));
                table.popFromWorkStack();
            }
        } else if (domainLevel == minLevel) {
            if (domainLow == FALSE) {
                result = computeIfThenElseSimplify(ifHigh, thenHigh, elseHigh, domainHigh);
            } else if (domainHigh == FALSE) {
                result = computeIfThenElseSimplify(ifLow, thenLow, elseLow, domainLow);
            } else {
                int low = table.pushToWorkStack(computeIfThenElseSimplify(ifLow, thenLow, elseLow, domainLow));
                int high = table.pushToWorkStack(computeIfThenElseSimplify(ifHigh, thenHigh, elseHigh, domainHigh));
                result = makeFunction(minLevel, low, high);
                table.popFromWorkStack(2);
            }
        } else {
            int low = table.pushToWorkStack(computeIfThenElseSimplify(ifLow, thenLow, elseLow, domain));
            int high = table.pushToWorkStack(computeIfThenElseSimplify(ifHigh, thenHigh, elseHigh, domain));
            result = makeFunction(minLevel, low, high);
            table.popFromWorkStack(2);
        }

        cache.putIfThenElseSimplify(hash, ifNormalized, thenNormalized, elseNormalized, domain, result);
        return complementIf(result, complement);
    }

    @Override
    public boolean implies(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        boolean result = !intersectsRecursive(function1, complement(function2));
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public boolean intersects(int function1, int function2) {
        assert isValidFunction(function1) && isValidFunction(function2);

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        boolean result = intersectsRecursive(function1, function2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private boolean intersectsRecursive(int function1, int function2) {
        if (function1 == FALSE || function2 == FALSE) {
            return false;
        }
        if (function1 == TRUE || function2 == TRUE) {
            return true;
        }
        if (function1 == function2) {
            return true;
        }
        if (function1 == complement(function2)) {
            return false;
        }

        assert !isConstant(function1) && !isConstant(function2);

        if (function1 > function2) {
            int nodeSwap = function1;
            function1 = function2;
            function2 = nodeSwap;
        }

        int lookup = cache.lookupIntersects(function1, function2);
        if (lookup != placeholder()) {
            return lookup == TRUE;
        }
        int hash = cache.lookupHash();

        int fun1Level = decisionLevel(function1);
        int fun2Level = decisionLevel(function2);
        int level = Math.min(fun1Level, fun2Level);

        boolean result = intersectsRecursive(lowIf(function1, fun1Level == level), lowIf(function2, fun2Level == level))
                || intersectsRecursive(highIf(function1, fun1Level == level), highIf(function2, fun2Level == level));
        cache.putIntersects(hash, function1, function2, result);
        return result;
    }

    @Override
    public int constrain(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }
        if (domain == TRUE) {
            return function;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function, domain);
        int result = computeConstrainSimplify(function, domain, true);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    @Override
    public int simplify(int function, int domain) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (domain == FALSE) {
            return FALSE;
        }
        if (domain == TRUE) {
            return function;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        table.pushToWorkStack(function, domain);
        int result = computeConstrainSimplify(function, domain, false);
        table.popFromWorkStack(2);
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeSimplify(int function, int domain) {
        return computeConstrainSimplify(function, domain, false);
    }

    private int computeConstrainSimplify(int function, int domain, boolean constrain) {
        assert domain != FALSE;
        if (function == TRUE || function == FALSE || domain == TRUE) {
            return function;
        }
        if (domain == function) {
            return TRUE;
        }
        if (domain == complement(function)) {
            return FALSE;
        }

        boolean func = isComplementFunction(function);
        int node = positive(function);

        int lookup = constrain ? cache.lookupConstrain(node, domain) : cache.lookupSimplify(node, domain);
        if (lookup != placeholder()) {
            return complementIf(lookup, func);
        }
        int hash = cache.lookupHash();

        int functionLevel = decisionLevel(node);
        int domainLevel = decisionLevel(domain);
        int domainLow = lowIf(domain, domainLevel <= functionLevel);
        int domainHigh = highIf(domain, domainLevel <= functionLevel);

        int result;
        if (domainLevel < functionLevel) {
            if (domainLow == FALSE) {
                result = computeConstrainSimplify(node, domainHigh, constrain);
            } else if (domainHigh == FALSE) {
                result = computeConstrainSimplify(node, domainLow, constrain);
            } else {
                if (constrain) {
                    int low = table.pushToWorkStack(computeConstrainSimplify(node, domainLow, true));
                    int high = table.pushToWorkStack(computeConstrainSimplify(node, domainHigh, true));
                    result = makeFunction(domainLevel, low, high);
                    table.popFromWorkStack(2);
                } else {
                    // We don't care about the value of the domain here, so we factor it out
                    // Note: We cannot use either low or high -- this would shrink the domain,
                    // only widening is sound, as we need to preserve values over the domain
                    // We could pass TRUE instead, this saves the "or" but widens a lot
                    // It's an open question whether we can find something in between
                    // Another option would be to track a list of domain sub-trees we care about,
                    // however this would increase the computational effort in each recursion
                    // and eliminate caching
                    result = computeConstrainSimplify(
                            node, table.pushToWorkStack(computeOr(domainLow, domainHigh)), false);
                    table.popFromWorkStack();
                }
            }
        } else {
            if (domainLow == FALSE) {
                result = computeConstrainSimplify(table.high(node), domainHigh, constrain);
            } else if (domainHigh == FALSE) {
                result = computeConstrainSimplify(table.low(node), domainLow, constrain);
            } else {
                int low = table.pushToWorkStack(computeConstrainSimplify(table.low(node), domainLow, constrain));
                int high = table.pushToWorkStack(computeConstrainSimplify(table.high(node), domainHigh, constrain));
                result = makeFunction(functionLevel, low, high);
                table.popFromWorkStack(2);
            }
        }
        if (constrain) {
            cache.putConstrain(hash, node, domain, result);
        } else {
            cache.putSimplify(hash, node, domain, result);
        }
        return complementIf(result, func);
    }

    // Statistics and Formatting

    @Override
    public String toString() {
        return String.format("BDD@%d(%d)", table.size(), System.identityHashCode(this));
    }

    // Utility

    /**
     * The traversal behind both cursors below: it walks the paths on which a function and a domain are
     * both true, one at a time, in the order the diagram is laid out in. A path enumeration reports each
     * one; a solution enumeration fills in the variables each leaves free.
     *
     * <p>One thing does not carry over from a single diagram (i.e. without domain): There, any node that
     * is not {@code FALSE} has a path to {@code TRUE}, so a descent that never steps into {@code FALSE}
     * always arrives somewhere - the traversal only ever backtracks to find the <em>next</em> path. A
     * pair of nodes that are both non-{@code FALSE} can still have no assignment satisfying both, so here
     * a descent can dead end, and {@link #advance()} has to be able to retract one and carry on. With the
     * domain at {@code TRUE} that never happens, and this behaves exactly like the single-diagram walk.
     */
    static final class PathWalk {
        private final BddImpl bdd;
        private final int rootFunction;
        private final int rootDomain;
        /* What flipping a level to high descends into, worked out while descending and kept here - the
         * high edges the recursion this mirrors holds in its stack frame. Storing them beats rederiving
         * them on the way back up, which costs a decision level and a table read per side. Signed
         * references, so a complemented edge needs no separate bookkeeping. */
        private final int[] highFunctionPath;
        private final int[] highDomainPath;
        private final MutableNatSet levelAssignment;
        private final MutableNatSet pathSupportLevels;
        /* The levels of the current path, deepest last - the recursion's call stack, made explicit. */
        private final int[] levelStack;
        /* Variable-indexed mirrors of the two sets above, maintained as the walk writes them, or null
         * when the caller reads levels directly. A step changes a handful of levels while the sets hold
         * the whole path, so mirroring the writes beats rebuilding the image of the set afterwards.
         * The one place the walk knows about variables at all. */
        private final @Nullable MutableNatSet variableAssignment;
        private final @Nullable MutableNatSet variableSupport;
        /* The order, snapshot rather than asked for per write: a mirrored write is one array load
         * instead of two hops into the context, and it cannot be invalidated under the walk by a
         * variable creation that resizes the context's own array. Only allocated when mirroring. */
        private final int[] levelToVariable;
        private int stackDepth = 0;
        private boolean onPath;

        PathWalk(BddImpl bdd, int function, int domain) {
            this(bdd, function, domain, null, null);
        }

        PathWalk(
                BddImpl bdd,
                int function,
                int domain,
                @Nullable MutableNatSet variableAssignment,
                @Nullable MutableNatSet variableSupport) {
            assert bdd.isValidFunction(function) && bdd.isValidFunction(domain);
            assert function != FALSE && domain != FALSE;
            assert function != TRUE || domain != TRUE : "Nothing to walk - every assignment is a solution";

            int variableCount = bdd.numberOfVariables();
            this.bdd = bdd;
            this.rootFunction = function;
            this.rootDomain = domain;
            this.highFunctionPath = new int[variableCount];
            this.highDomainPath = new int[variableCount];
            this.levelAssignment = MutableNatSet.dense(variableCount);
            this.pathSupportLevels = MutableNatSet.dense(variableCount);
            this.levelStack = new int[variableCount];
            this.variableAssignment = variableAssignment;
            this.variableSupport = variableSupport;
            if (variableAssignment == null && variableSupport == null) {
                this.levelToVariable = EMPTY_INT_ARRAY;
            } else {
                this.levelToVariable = new int[variableCount];
                for (int level = 0; level < variableCount; level++) {
                    this.levelToVariable[level] = bdd.variableAtLevel(level);
                }
            }
            // Positioned on the first path right away, so there is no "have we started yet" state to
            // carry: whoever holds the cursor asks onPath(), and advance() only ever means "the next one".
            // Even the first descent can dead end, hence the fallback into backtracking.
            this.onPath = descend(function, domain) || backtrack();
        }

        private void assign(int level, boolean value) {
            levelAssignment.set(level, value);
            if (variableAssignment != null) {
                variableAssignment.set(levelToVariable[level], value);
            }
        }

        private void pushSupport(int level) {
            pathSupportLevels.set(level);
            if (variableSupport != null) {
                variableSupport.set(levelToVariable[level]);
            }
        }

        private void popSupport(int level) {
            pathSupportLevels.clear(level);
            if (variableSupport != null) {
                variableSupport.clear(levelToVariable[level]);
            }
        }

        MutableNatSet pathSupportLevels() {
            return pathSupportLevels;
        }

        MutableNatSet levelAssignment() {
            return levelAssignment;
        }

        int function() {
            return rootFunction;
        }

        int domain() {
            return rootDomain;
        }

        /** Whether the cursor is on a path: false once the enumeration is over. */
        boolean onPath() {
            return onPath;
        }

        /** Moves to the next path. Returns {@code false} when there are none left. */
        boolean advance() {
            onPath = backtrack();
            return onPath;
        }

        private boolean backtrack() {
            /* Take the deepest branch still open - a level the path took low whose high side is not
             * immediately false - and descend from it. A descent that dead ends pops itself back off, so
             * this simply carries on from whatever is left on the stack. */
            while (stackDepth > 0) {
                int level = levelStack[stackDepth - 1];
                if (!levelAssignment.contains(level)) {
                    int high = highFunctionPath[level];
                    int highDomain = highDomainPath[level];
                    if (high != FALSE && highDomain != FALSE) {
                        assign(level, true);
                        if (descend(high, highDomain)) {
                            return true;
                        }
                        continue;
                    }
                }
                pop();
            }
            return false;
        }

        /**
         * Walks down from a pair, taking the low branch wherever both sides allow it. Returns whether it
         * reached a leaf; on a dead end it retracts the level it failed at, leaving the cursor where
         * {@link #advance()} should resume.
         */
        private boolean descend(int startFunction, int startDomain) {
            int function = startFunction;
            int domain = startDomain;

            while (function != TRUE || domain != TRUE) {
                assert function != FALSE && domain != FALSE;

                int functionLevel = bdd.decisionLevelOrMax(function);
                int domainLevel = bdd.decisionLevelOrMax(domain);
                int level = Math.min(functionLevel, domainLevel);
                boolean functionDecides = functionLevel == level;
                boolean domainDecides = domainLevel == level;

                int highFunction = bdd.highIf(function, functionDecides);
                int highDomain = bdd.highIf(domain, domainDecides);
                highFunctionPath[level] = highFunction;
                highDomainPath[level] = highDomain;
                pushSupport(level);
                levelStack[stackDepth] = level;
                stackDepth += 1;

                int lowFunction = bdd.lowIf(function, functionDecides);
                int lowDomain = bdd.lowIf(domain, domainDecides);
                if (lowFunction != FALSE && lowDomain != FALSE) {
                    assign(level, false);
                    function = lowFunction;
                    domain = lowDomain;
                    continue;
                }

                if (highFunction != FALSE && highDomain != FALSE) {
                    assign(level, true);
                    function = highFunction;
                    domain = highDomain;
                    continue;
                }

                pop();
                return false; // NOPMD
            }
            return true;
        }

        /** Drops the deepest level of the path, leaving the cursor on the one above it. */
        private void pop() {
            stackDepth -= 1;
            int level = levelStack[stackDepth];
            popSupport(level);
            // We only call pop after finishing a high branch; the next branch might not care about this level
            // so we need to clear it to maintain the invariant.
            assign(level, false);
        }
    }

    /**
     * Walks the solutions of a function: every path, and for each of them every way of filling in the
     * support variables that path leaves free.
     */
    static final class SolutionCursor implements Cursor<NatSet> {
        private final BddImpl bdd;
        private final PathWalk path;
        private final NatSet supportLevels;
        /* The support levels the current path leaves free, recomputed whenever the path moves - once per
         * path, not per solution. There (usually) are far more solutions than paths, and rescanning the whole
         * support each time to skip what the path fixes is what puts this off the recursion's pace. */
        private final MutableNatSet freeLevels;
        private final @Nullable MutableNatSet translated;
        private boolean valid;

        private static NatSet levelsOf(BddImpl bdd, NatSet variables) {
            MutableNatSet levels = MutableNatSet.dense(bdd.numberOfVariables());
            NatSets.map(variables, levels, bdd::levelOfVariable);
            return levels;
        }

        SolutionCursor(BddImpl bdd, int function, int domain, NatSet support) {
            int variableCount = bdd.numberOfVariables();
            assert variableCount > 0 && support.length() <= variableCount;
            assert support.containsAll(bdd.support(function));
            assert support.containsAll(bdd.support(domain));

            this.bdd = bdd;
            boolean translating = bdd.isReordered();
            this.supportLevels = translating ? levelsOf(bdd, support) : support;
            this.freeLevels = MutableNatSet.dense(variableCount);
            this.translated = translating ? MutableNatSet.dense(variableCount) : null;
            // The walk maintains the buffer for the levels it decides; the counter below maintains it for
            // the ones it leaves free. Between them nothing is ever rebuilt.
            this.path = new PathWalk(bdd, function, domain, translated, null);
            this.valid = path.onPath();
            if (valid) {
                refreshFreeLevels();
                assert currentIsConsistent();
            }
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public NatSet current() {
            assert valid : "current() is only defined while the cursor is valid";
            return translated == null ? path.levelAssignment() : translated;
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }

            /* Binary addition over the levels the current path leaves free: every combination of them
             * extends this path to a solution. Carrying past the last one leaves them all at zero and
             * means the path itself has to move on. */
            if (increment()) {
                assert currentIsConsistent();
                return true;
            }
            if (!path.advance()) {
                valid = false;
                return false;
            }
            refreshFreeLevels();
            assert currentIsConsistent();
            return true;
        }

        private boolean increment() {
            MutableNatSet levelAssignment = path.levelAssignment();
            if (translated == null) {
                return NatSets.increment(levelAssignment, freeLevels);
            }
            PrimitiveIterator.OfInt iterator = freeLevels.iterator();
            while (iterator.hasNext()) {
                int level = iterator.nextInt();
                int variable = bdd.variableAtLevel(level);
                if (levelAssignment.contains(level)) {
                    levelAssignment.clear(level);
                    translated.clear(variable);
                } else {
                    levelAssignment.set(level);
                    translated.set(variable);
                    return true;
                }
            }
            return false;
        }

        private void refreshFreeLevels() {
            NatSets.difference(freeLevels, supportLevels, path.pathSupportLevels());
        }

        private boolean currentIsConsistent() {
            assert supportLevels.containsAll(path.pathSupportLevels());
            assert bdd.evaluate(path.function(), current()) && bdd.evaluate(path.domain(), current());
            if (translated != null) {
                MutableNatSet rebuilt = MutableNatSet.dense(bdd.numberOfVariables());
                NatSets.map(path.levelAssignment(), rebuilt, bdd::variableAtLevel);
                assert rebuilt.equals(translated) : "Incremental translation drifted from the walk";
            }
            return true;
        }
    }

    static final class PathCursor implements Cursor<Cube> {
        private final BddImpl bdd;
        private final PathWalk path;
        /** Only on a reordered diagram, where the walk is by level and the caller wants variables. */
        private final @Nullable WalkCube translated;
        /** What {@link #current()} hands out: the translation buffer, or the walk's own sets wrapped. */
        private final Cube current;

        private boolean valid;

        PathCursor(BddImpl bdd, int function) {
            int variableCount = bdd.numberOfVariables();
            this.bdd = bdd;
            this.translated = bdd.isReordered() ? new WalkCube(variableCount) : null;
            // Both halves of a path are maintained by the walk itself, so a step rebuilds nothing.
            this.path = translated == null
                    ? new PathWalk(bdd, function, TRUE)
                    : new PathWalk(bdd, function, TRUE, translated.assignment, translated.support);
            this.valid = path.onPath();
            this.current = translated == null
                    ? Cube.ofUnsafe(path.levelAssignment(), path.pathSupportLevels())
                    : translated.cube;
            assert !valid || currentIsConsistent();
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public Cube current() {
            assert valid; // current() is only defined while the cursor is valid
            return current;
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }
            if (!path.advance()) {
                valid = false;
                return false;
            }
            assert currentIsConsistent();
            return true;
        }

        private boolean currentIsConsistent() {
            assert bdd.evaluate(path.function(), current.assignment());
            if (translated != null) {
                int variableCount = bdd.numberOfVariables();
                MutableNatSet assignment = MutableNatSet.dense(variableCount);
                MutableNatSet support = MutableNatSet.dense(variableCount);
                NatSets.map(path.levelAssignment(), assignment, bdd::variableAtLevel);
                NatSets.map(path.pathSupportLevels(), support, bdd::variableAtLevel);
                assert assignment.equals(translated.assignment) && support.equals(translated.support)
                        : "Incremental translation drifted from the walk";
            }
            return true;
        }
    }

    private static final class BddTable extends NodeTable.Binary {
        private final BddImpl bdd;

        BddTable(BddImpl bdd, int initialSize) {
            super(initialSize);
            this.bdd = bdd;
        }

        @Override
        protected int levelOfVariable(int variable) {
            return bdd.levelOfVariable(variable);
        }

        @Override
        protected int @Nullable [] cachedSupport(int node) {
            return bdd.cache.supportCache().lookup(node);
        }

        @Override
        boolean check() {
            super.check();
            for (int node = 1; node < size(); node++) {
                if (isValidDecisionNode(node)) {
                    checkState(!isComplementFunction(high(node)));
                }
            }
            return true;
        }

        @Override
        public boolean isValidConstant(int function) {
            return bdd.isConstant(function);
        }

        @Override
        public boolean isValidFunction(int function) {
            return bdd.isValidFunction(function);
        }

        @Override
        protected boolean recurseNoneMarkedBelow(int node, MutableNatSet visited) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE || doIsNoneMarkedBelow(low, visited))
                    && (high == TRUE || doIsNoneMarkedBelow(high, visited));
        }

        @Override
        protected boolean recurseIsAllMarkedBelow(int node, boolean includeLeaves, MutableNatSet visited) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE || doIsAllMarkedBelow(low, includeLeaves, visited))
                    && (high == TRUE || doIsAllMarkedBelow(high, includeLeaves, visited));
        }

        @Override
        protected void markLeafNodeIfManaged(int node, boolean mark) {
            // Nothing to do
        }

        @Override
        protected int recurseSetMarkBelow(int node, boolean mark, boolean includeLeaves) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE ? 0 : doSetMarkBelow(low, mark, includeLeaves))
                    + (high == TRUE ? 0 : doSetMarkBelow(high, mark, includeLeaves));
        }

        @Override
        protected void recurseForEachVariable(int node, IntConsumer action, @Nullable NatSet filter, int depthLimit) {
            int low = positive(low(node));
            int high = high(node);
            if (low != TRUE) {
                doForEachVariable(low, action, filter, depthLimit);
            }
            if (high != TRUE) {
                doForEachVariable(high, action, filter, depthLimit);
            }
        }

        @Override
        int nodeFor(int function) {
            return bdd.nodeFor(function);
        }

        @Override
        protected boolean isLeafNode(int node) {
            return node == TRUE;
        }

        @Override
        protected boolean isValidLeafNode(int node) {
            return node == TRUE;
        }

        @Override
        protected BddConfiguration configuration() {
            return bdd.configuration;
        }

        @Override
        protected void notifyBeforeGc() {
            bdd.notifyBeforeGc();
        }

        @Override
        protected void notifyAfterGc(int reclaimedNodes, NatSet reclaimedValues) {
            bdd.notifyAfterGc(reclaimedNodes);
        }

        @Override
        protected void notifyAfterTableGrowth(int invalidatedNodes, NatSet reclaimedValues) {
            bdd.notifyAfterTableGrow(invalidatedNodes);
        }

        @Override
        protected NatSet clearUnreferencedLeaves() {
            return NatSet.of();
        }

        @Override
        protected boolean checkOwner() {
            return bdd.check();
        }

        @Override
        protected boolean anyManagedLeafMarked() {
            return false;
        }

        @Override
        protected void unmarkAllManagedLeaves() {
            // Nothing to do
        }

        @Override
        protected boolean isLeafNodeMarkedOrUnmanaged(int leaf) {
            return true;
        }

        @Override
        protected boolean isLeafUnmarkedOrUnmanaged(int leaf) {
            return true;
        }

        @Override
        String format(int function) {
            return bdd.format(function);
        }
    }
}
