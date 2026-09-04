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

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/* Implementation notes:
 * - Variable numbers increase while descending the tree of a particular node.
 */
@SuppressWarnings({
    "PMD.AvoidReassigningParameters",
    "ReassignedVariable",
    "AssignmentToMethodParameter",
    "SameParameterValue",
    "DuplicatedCode",
    "AssertWithSideEffects"
})
public class BddImpl extends BooleanBase<BitSet, BinaryPath> implements Bdd {
    private static final BitSet EMPTY_BIT_SET = new BitSet(0);

    /* The variable order and everything else the BDD shares with its MTBDD, reordering included. */
    private final BddContextImpl context;
    private final BooleanCache cache;
    private int[] variableNodes;
    private final BddConfiguration configuration;
    private final NodeTable.Binary table;

    BddImpl(BddContextImpl context) {
        this.context = context;
        this.configuration = context.configuration();
        this.table = new BddTable(this, configuration.bddInitialSize());

        cache = new BooleanCache(this);
        variableNodes = new int[32];
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
        return context.numberOfVariables();
    }

    @Override
    public int variableFunction(int variableNumber) {
        assert 0 <= variableNumber && variableNumber < numberOfVariables();
        return variableNodes[variableNumber];
    }

    void ensureVariableNodeCapacity(int variables) {
        if (variables > variableNodes.length) {
            variableNodes = Arrays.copyOf(variableNodes, Math.max(variableNodes.length * 2, variables));
        }
    }

    /**
     * Builds the saturated node representing {@code variable}, which the context has already placed at
     * {@code level} - the one part of declaring a variable that is the BDD's rather than the order's.
     */
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
        return level(table.variable(positive(function)));
    }

    boolean reordered() {
        return context.reordered();
    }

    @Override
    Map<String, Object> ownStatistics() {
        return context.reorderStatistics();
    }

    @Override
    public int level(int variable) {
        return context.level(variable);
    }

    @Override
    public int variableAtLevel(int level) {
        return context.variableAtLevel(level);
    }

    @Override
    public void dropReorderStructures() {
        context.dropReorderStructures();
    }

    @Override
    public int reorder() {
        return context.reorder();
    }

    @Override
    public int reorder(List<BitSet> groups) {
        return context.reorder(groups);
    }

    @Override
    public void reorderTo(List<BitSet> blocks) {
        context.reorderTo(blocks);
    }

    @Override
    public void reorderToIdentity() {
        context.reorderToIdentity();
    }

    @Override
    public int createVariableAtLevel(int level) {
        return context.createVariableAtLevel(level);
    }

    @Override
    public int[] createVariablesAtLevel(int level, int count) {
        return context.createVariablesAtLevel(level, count);
    }

    /** The greatest level any variable of {@code variables} sits at, or -1 if there is none. */
    int maxLevel(BitSet variables) {
        int max = -1;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            max = Math.max(max, level(variable));
        }
        return max;
    }

    private boolean decidesOn(int function, int level) {
        return !isConstant(function) && decisionLevel(function) == level;
    }

    void rewriteLevelAfterSwap(int[] nodes, int count, int level, int variable) {
        /* Decide first, and take the nodes that will change out of the unique table before building
         * anything: until a node is rewritten it still carries the old variable, so makeNode below could
         * hand it out as a fresh child and it would then be rewritten out from under that parent. The
         * ones that do not change stay in the table on purpose - they are genuine nodes of the variable
         * moving down, and a new child that matches one of them must find it rather than duplicate it. */
        int rewriteCount = 0;
        for (int index = 0; index < count; index++) {
            int node = nodes[index];
            if (decidesOn(table.low(node), level) || decidesOn(table.high(node), level)) {
                nodes[rewriteCount] = node;
                rewriteCount += 1;
                table.hideForRewrite(node);
            } else {
                // Neither child mentions the variable moving up, so this node just descends a level.
                table.addToVariableList(node, table.variable(node));
            }
        }

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

            /* The rewritten node stays positive, which it has to - references to it carry their own
             * polarity. That holds because a node's high edge is never complemented, so neither is the
             * high cofactor of it, so neither is the node makeFunction builds from it. */
            int newLow = table.pushToWorkStack(makeFunction(level + 1, lowLow, highLow));
            int newHigh = makeFunction(level + 1, lowHigh, highHigh);
            table.popFromWorkStack();
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
    public boolean evaluate(int function, BitSet assignment) {
        assert isValidFunction(function);

        int currentNode = positive(function);
        boolean lookingFor = currentNode == function;
        while (currentNode != TRUE) {
            assert table.isValidDecisionNode(currentNode);
            if (assignment.get(table.variable(currentNode))) {
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
    public BitSet satisfyingAssignment(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            throw new NoSuchElementException("False has no solution");
        }

        BitSet path = new BitSet(numberOfVariables());
        satisfyingAssignment(function, path);
        return path;
    }

    @Override
    public Optional<BitSet> satisfyingAssignmentIn(int function, int domain) {
        assert isValidFunction(function);

        if (function == FALSE || domain == FALSE) {
            return Optional.empty();
        }

        BitSet path = new BitSet(numberOfVariables());
        return satisfyingAssignmentInRecursive(function, domain, path) ? Optional.of(path) : Optional.empty();
    }

    /** Clears every variable sitting at or below {@code level} - not a contiguous range once reordered. */
    private void clearBelowLevel(BitSet path, int level) {
        if (reordered()) {
            for (int current = level; current < numberOfVariables(); current++) {
                path.clear(variableAtLevel(current));
            }
        } else {
            path.clear(level, numberOfVariables());
        }
    }

    private boolean satisfyingAssignment(int function, BitSet path) {
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

    private boolean satisfyingAssignmentInRecursive(int function1, int function2, BitSet path) {
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
    public void forEachSolutionIn(int function, int domain, Consumer<? super BitSet> action) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (function == FALSE || domain == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        forEachSolutionInRecursive(
                function, domain, null, 0, new BitSet(numberOfVariables()), new int[numberOfVariables()], 0, action);
        assert accessGuard.release();
    }

    @Override
    public void forEachSolutionIn(int function, int domain, BitSet support, Consumer<? super BitSet> action) {
        assert isValidFunction(function) && isValidFunction(domain);
        assert BitSets.isSubset(support(function), support) && BitSets.isSubset(support(domain), support);

        if (function == FALSE || domain == FALSE) {
            return;
        }

        assert accessGuard.acquire();
        // The recursion descends by level, so the support has to be handed to it in level order.
        int[] variables;
        if (reordered()) {
            variables = support.stream()
                    .boxed()
                    .sorted(java.util.Comparator.comparingInt(this::level))
                    .mapToInt(Integer::intValue)
                    .toArray();
        } else {
            variables = support.stream().toArray();
        }
        forEachSolutionInRecursive(
                function, domain, variables, 0, new BitSet(numberOfVariables()), new int[variables.length], 0, action);
        assert accessGuard.release();
    }

    private void forEachSolutionInRecursive(
            int function1,
            int function2,
            int @Nullable [] support,
            int index,
            BitSet assignment,
            int[] freeVariables,
            int freeCount,
            Consumer<? super BitSet> action) {
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
        int level = support == null ? index : level(variable);

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
            BitSet assignment, int[] freeVariables, int freeCount, Consumer<? super BitSet> action) {
        action.accept(assignment);
        while (true) {
            int index = 0;
            while (index < freeCount && assignment.get(freeVariables[index])) {
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
    public Cursor<BitSet> solutionCursor(int function) {
        BitSet support = new BitSet(numberOfVariables());
        support.set(0, numberOfVariables());
        return solutionCursorIn(function, TRUE, support);
    }

    @Override
    public Cursor<BitSet> solutionCursor(int function, BitSet support) {
        return solutionCursorIn(function, TRUE, support);
    }

    @Override
    public Cursor<BitSet> solutionCursorIn(int function, int domain) {
        BitSet support = new BitSet(numberOfVariables());
        support.set(0, numberOfVariables());
        return solutionCursorIn(function, domain, support);
    }

    @Override
    public Cursor<BitSet> solutionCursorIn(int function, int domain, BitSet support) {
        assert isValidFunction(function) && isValidFunction(domain);

        if (function == FALSE || domain == FALSE) {
            return Cursors.empty();
        }
        if (function == TRUE && domain == TRUE) {
            return Cursors.powerSet(support);
        }
        return new SolutionCursor(this, function, domain, support);
    }

    @Override
    public Cursor<BinaryPath> pathCursor(int function) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return Cursors.empty();
        }
        if (function == TRUE) {
            return Cursors.singleton(new BinaryPath(new BitSet(0), new BitSet(0)));
        }
        return new PathCursor(this, function);
    }

    @Override
    public void forEachPath(int function, Consumer<? super BinaryPath> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        if (function == TRUE) {
            action.accept(new BinaryPath(new BitSet(0), new BitSet(0)));
            assert accessGuard.release();
            return;
        }

        int numberOfVariables = numberOfVariables();
        BinaryPath path = new BinaryPath(new BitSet(numberOfVariables), new BitSet(numberOfVariables));
        forEachPathRecursive(positive(function), null, numberOfVariables, path, action, isPositive(function));
        assert accessGuard.release();
    }

    @Override
    public void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super BinaryPath> action) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return;
        }
        assert accessGuard.acquire();
        if (function == TRUE || relevantSet.isEmpty()) {
            action.accept(new BinaryPath(new BitSet(0), new BitSet(0)));
            assert accessGuard.release();
            return;
        }

        int maxRelevantLevel = maxLevel(relevantSet);
        BinaryPath path = new BinaryPath(new BitSet(maxRelevantLevel + 1), new BitSet(maxRelevantLevel + 1));
        forEachPathRecursive(positive(function), relevantSet, maxRelevantLevel, path, action, isPositive(function));
        assert accessGuard.release();
    }

    private void forEachPathRecursive(
            int node,
            @Nullable BitSet support,
            int depthLimit,
            BinaryPath path,
            Consumer<? super BinaryPath> action,
            boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            action.accept(path);
            return;
        }
        assert table.isValidDecisionNode(node);
        assert !isConstant(node);

        int variable = table.variable(node);
        if (level(variable) > depthLimit) {
            // There must exist at least one satisfying path
            action.accept(path);
            return;
        }

        int lowEdge = table.low(node);
        int highNode = table.high(node);
        boolean relevant = support == null || support.get(variable);

        if (relevant) {
            path.support.set(variable);
        }

        if (!isFalse(lowEdge, lookingFor)) {
            forEachPathRecursive(
                    positive(lowEdge), support, depthLimit, path, action, isPositive(lowEdge) == lookingFor);
        }
        if (!isFalse(highNode, lookingFor)) {
            if (relevant) {
                path.assignment.set(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, action, lookingFor);
                assert path.assignment.get(variable);
                path.assignment.clear(variable);
            } else {
                assert !path.assignment.get(variable);
                forEachPathRecursive(highNode, support, depthLimit, path, action, lookingFor);
            }
        }

        assert relevant == path.support.get(variable);
        if (relevant) {
            path.support.clear(variable);
        }
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super BinaryPath> predicate) {
        assert isValidFunction(function);

        if (function == FALSE) {
            return false;
        }
        assert accessGuard.acquire();
        if (function == TRUE) {
            boolean result = predicate.test(new BinaryPath(new BitSet(0), new BitSet(0)));
            assert accessGuard.release();
            return result;
        }

        int numberOfVariables = numberOfVariables();
        BinaryPath path = new BinaryPath(new BitSet(numberOfVariables), new BitSet(numberOfVariables));
        boolean result = anyPathMatchesRecursive(positive(function), path, predicate, isPositive(function));
        assert accessGuard.release();
        return result;
    }

    private boolean anyPathMatchesRecursive(
            int node, BinaryPath path, Predicate<? super BinaryPath> predicate, boolean lookingFor) {
        if (node == TRUE) {
            assert lookingFor;
            return predicate.test(path);
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
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        assert BitSets.isSubset(support(function), support);
        return countSatisfyingAssignments(function).divide(TWO.pow(numberOfVariables() - support.cardinality()));
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
        ComposeAnalysis analysis = analyzeCompose(variableMapping);
        if (analysis.maxReplacedLevel == -1) {
            int result = simplify(function, domain);
            assert accessGuard.release();
            return result;
        }
        if (analysis.isRestrict) {
            // TODO Native
            int result = simplify(restrict(function, analysis.restrictSupport, analysis.restrictValues), domain);
            assert accessGuard.release();
            return result;
        }

        assert table.workStacksEmpty();

        int arrayWorkStackCount = 0;
        for (int j : variableMapping) {
            assert isValidFunction(j);
            int node = positive(j);
            if (node != TRUE && !table.isSaturatedNode(node)) {
                table.pushToWorkStack(j);
                arrayWorkStackCount++;
            }
        }

        cache.initCompose(variableMapping);
        int result = composeGeneral(
                function,
                domain,
                variableMapping,
                analysis.maxReplacedLevel,
                cache.composeCache(),
                cache.composeSimplifyCache());
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
            return function -> function;
        }
        if (analysis.isRestrict) {
            BitSet restrictSupport = analysis.restrictSupport;
            BitSet restrictValues = analysis.restrictValues;
            return function -> restrict(function, restrictSupport, restrictValues);
        }
        return new BddOperations.Compose(
                this, resolved, analysis.maxReplacedLevel, Util.protectNodes(this, resolved), false);
    }

    @Override
    public RegisteredOperation.Binary registerComposeSimplify(int[] variableMapping) {
        int[] resolved = variableMapping.clone();
        ComposeAnalysis analysis = analyzeCompose(resolved);
        if (analysis.maxReplacedLevel == -1) {
            return this::simplify;
        }
        if (analysis.isRestrict) {
            BitSet restrictSupport = analysis.restrictSupport;
            BitSet restrictValues = analysis.restrictValues;
            return (function, domain) -> simplify(restrict(function, restrictSupport, restrictValues), domain);
        }
        return new BddOperations.Compose(
                this, resolved, analysis.maxReplacedLevel, Util.protectNodes(this, resolved), true);
    }

    /** The greatest level a resolved mapping touches, or -1 if it replaces nothing. */
    int maxReplacedLevel(int[] resolvedMapping) {
        int max = -1;
        for (int variable = 0; variable < resolvedMapping.length; variable++) {
            if (resolvedMapping[variable] != this.variableNodes[variable]) {
                max = Math.max(max, level(variable));
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
                maxReplacedLevel = Math.max(maxReplacedLevel, level(i));
            }
        }
        if (maxReplacedLevel == -1) {
            return new ComposeAnalysis(-1, false, EMPTY_BIT_SET, EMPTY_BIT_SET);
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
            BitSet restrictValues = new BitSet(variableMapping.length + 1);
            BitSet restrictSupport = new BitSet(variableMapping.length + 1);
            for (int i = 0; i < variableMapping.length; i++) {
                if (isConstant(variableMapping[i])) {
                    restrictSupport.set(i);
                    restrictValues.set(i, variableMapping[i] == TRUE);
                }
            }
            return new ComposeAnalysis(maxReplacedLevel, true, restrictSupport, restrictValues);
        }
        return new ComposeAnalysis(maxReplacedLevel, false, EMPTY_BIT_SET, EMPTY_BIT_SET);
    }

    int composeGeneral(
            int function,
            int domain,
            int[] variableMapping,
            int maxReplacedLevel,
            BooleanCache.UnaryToIntCache composeCache,
            BooleanCache.@Nullable BinaryToIntCache composeSimplifyCache) {
        assert domain != FALSE;
        assert domain == TRUE || composeSimplifyCache != null;
        table.pushToWorkStack(function);
        int workStackCount = 1;
        if (domain != TRUE) {
            table.pushToWorkStack(domain);
            workStackCount++;
        }
        int result = computeComposeSimplify(
                function, variableMapping, maxReplacedLevel, domain, composeCache, composeSimplifyCache);
        table.popFromWorkStack(workStackCount);
        return result;
    }

    static final class ComposeAnalysis {
        final int maxReplacedLevel;
        final boolean isRestrict;

        final BitSet restrictSupport;
        final BitSet restrictValues;

        ComposeAnalysis(int maxReplacedLevel, boolean isRestrict, BitSet restrictSupport, BitSet restrictValues) {
            this.maxReplacedLevel = maxReplacedLevel;
            this.isRestrict = isRestrict;
            this.restrictSupport = restrictSupport;
            this.restrictValues = restrictValues;
        }
    }

    @SuppressWarnings("NullAway")
    private int computeComposeSimplify(
            int function,
            int[] replacements,
            int maxReplacedLevel,
            int domain,
            BooleanCache.UnaryToIntCache composeCache,
            BooleanCache.@Nullable BinaryToIntCache composeSimplifyCache) {
        assert domain != FALSE;
        assert domain == TRUE || composeSimplifyCache != null;

        boolean func = isComplementFunction(function);
        int node = positive(function);

        if (node == TRUE) {
            return function;
        }

        /* Two different things, and compose needs both: the level orders the descent against the domain,
         * while replacements is indexed by the variable itself. */
        int nodeVariable = table.variable(node);
        int nodeLevel = level(nodeVariable);
        if (nodeLevel > maxReplacedLevel) {
            return computeSimplify(function, domain);
        }

        int lookup;
        int hash;
        if (domain == TRUE) {
            lookup = composeCache.lookup(node);
            hash = composeCache.lookupHash();
        } else {
            lookup = composeSimplifyCache.lookup(node, domain);
            hash = composeSimplifyCache.lookupHash();
        }
        if (lookup != placeholder()) {
            return complementIf(lookup, func);
        }

        int domainLevel = domain == TRUE ? Integer.MAX_VALUE : decisionLevel(domain);
        int domainLow = lowIf(domain, domainLevel <= nodeLevel);
        int domainHigh = highIf(domain, domainLevel <= nodeLevel);

        int result;
        if (domainLevel < nodeLevel) {
            if (domainLow == FALSE) {
                result = computeComposeSimplify(
                        node, replacements, maxReplacedLevel, domainHigh, composeCache, composeSimplifyCache);
            } else if (domainHigh == FALSE) {
                result = computeComposeSimplify(
                        node, replacements, maxReplacedLevel, domainLow, composeCache, composeSimplifyCache);
            } else {
                result = computeComposeSimplify(
                        node,
                        replacements,
                        maxReplacedLevel,
                        table.pushToWorkStack(computeOr(domainLow, domainHigh)),
                        composeCache,
                        composeSimplifyCache);
                table.popFromWorkStack();
            }
        } else {
            /* A mapping shorter than the variable count leaves the rest unchanged - and under a
             * non-identity order such a variable can well sit above maxReplacedLevel's variable, so
             * the recursion reaches it. */
            int replacement =
                    nodeVariable < replacements.length ? replacements[nodeVariable] : this.variableNodes[nodeVariable];
            // Short-circuit constant replacements.

            if (replacement == TRUE) {
                result = computeComposeSimplify(
                        table.high(node), replacements, maxReplacedLevel, domain, composeCache, composeSimplifyCache);
            } else if (replacement == FALSE) {
                result = computeComposeSimplify(
                        table.low(node), replacements, maxReplacedLevel, domain, composeCache, composeSimplifyCache);
            } else {
                boolean aligned = domainLevel == nodeLevel && replacement == this.variableNodes[nodeVariable];
                int lowDomain = aligned ? domainLow : domain;
                int highDomain = aligned ? domainHigh : domain;

                if (lowDomain == FALSE) {
                    // The domain forces this nodeLevel, so only one branch is reachable within it.
                    result = computeComposeSimplify(
                            table.high(node),
                            replacements,
                            maxReplacedLevel,
                            highDomain,
                            composeCache,
                            composeSimplifyCache);
                } else if (highDomain == FALSE) {
                    result = computeComposeSimplify(
                            table.low(node),
                            replacements,
                            maxReplacedLevel,
                            lowDomain,
                            composeCache,
                            composeSimplifyCache);
                } else {
                    int low = table.pushToWorkStack(computeComposeSimplify(
                            table.low(node),
                            replacements,
                            maxReplacedLevel,
                            lowDomain,
                            composeCache,
                            composeSimplifyCache));
                    int high = table.pushToWorkStack(computeComposeSimplify(
                            table.high(node),
                            replacements,
                            maxReplacedLevel,
                            highDomain,
                            composeCache,
                            composeSimplifyCache));
                    result = computeIfThenElseSimplify(replacement, high, low, domain);
                    table.popFromWorkStack(2);
                }
            }
        }
        if (domain == TRUE) {
            composeCache.put(hash, node, result);
        } else {
            composeSimplifyCache.put(hash, node, domain, result);
        }
        return complementIf(result, func);
    }

    @Override
    public int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        assert isValidFunction(function);

        if (restrictedVariables.isEmpty()) {
            return function;
        }
        if (isConstant(function)) {
            return function;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        int maxRestrictedLevel = maxLevel(restrictedVariables);
        table.pushToWorkStack(function);
        cache.initRestrict(restrictedVariables, restrictedVariableValues);
        int result = computeRestrict(function, restrictedVariables, restrictedVariableValues, maxRestrictedLevel);
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    private int computeRestrict(
            int function, BitSet restrictedVariables, BitSet restrictedVariableValues, int maxRestrictedLevel) {
        boolean func = isComplementFunction(function);
        int node = positive(function);

        if (node == TRUE) {
            return function;
        }
        /* The level orders the descent and is what makeFunction wants; the two BitSets are indexed by
         * the variable itself. */
        int nodeVariable = table.variable(node);
        int nodeLevel = level(nodeVariable);
        if (nodeLevel > maxRestrictedLevel) {
            return function;
        }

        int lookup = cache.lookupRestrict(node);
        if (lookup != placeholder()) {
            return complementIf(lookup, func);
        }
        int hash = cache.lookupHash();

        int result;
        if (restrictedVariables.get(nodeVariable)) {
            int child = restrictedVariableValues.get(nodeVariable) ? table.high(node) : table.low(node);
            result = computeRestrict(child, restrictedVariables, restrictedVariableValues, maxRestrictedLevel);
        } else {
            int low = table.pushToWorkStack(computeRestrict(
                    table.low(node), restrictedVariables, restrictedVariableValues, maxRestrictedLevel));
            int high = table.pushToWorkStack(computeRestrict(
                    table.high(node), restrictedVariables, restrictedVariableValues, maxRestrictedLevel));
            result = makeFunction(nodeLevel, low, high);
            table.popFromWorkStack(2);
        }

        cache.putRestrict(hash, node, result);
        return complementIf(result, func);
    }

    // Bdd operations

    @Override
    public int conjunction(int... variables) {
        assert accessGuard.acquire();
        int node = TRUE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            node = computeAnd(table.pushToWorkStack(node), variableNodes[variable]);
            table.popFromWorkStack();
        }
        assert accessGuard.release();
        return node;
    }

    @Override
    public int conjunction(BitSet variables) {
        assert accessGuard.acquire();
        int node = TRUE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            node = computeAnd(table.pushToWorkStack(node), variableNodes[variable]);
            table.popFromWorkStack();
        }
        assert accessGuard.release();
        return node;
    }

    @Override
    public int disjunction(int... variables) {
        assert accessGuard.acquire();
        int node = FALSE;
        for (int variable : variables) {
            // Variable nodes are saturated, no need to guard them
            int node1 = table.pushToWorkStack(node);
            node = computeOr(node1, variableNodes[variable]);
            table.popFromWorkStack();
        }
        assert accessGuard.release();
        return node;
    }

    @Override
    public int disjunction(BitSet variables) {
        assert accessGuard.acquire();
        int node = FALSE;
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            // Variable nodes are saturated, no need to guard them
            int node1 = table.pushToWorkStack(node);
            node = computeOr(node1, variableNodes[variable]);
            table.popFromWorkStack();
        }
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
            // The domain decides above both operands - widen it and retry. No expansion happens here, so
            // the operands' cofactors are not needed on this path at all.
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
    public int exists(int function, BitSet quantifiedVariables) {
        assert isValidFunction(function);
        assert quantifiedVariables.length() - 1 <= numberOfVariables();

        if (isConstant(function)) {
            return function;
        }
        if (quantifiedVariables.cardinality() == numberOfVariables()) {
            return TRUE;
        }

        assert accessGuard.acquire();
        assert table.workStacksEmpty();
        cache.initExists(quantifiedVariables);
        table.pushToWorkStack(function);
        // The recursion descends by level, so it needs the quantified set indexed the same way.
        int result = existsRecursive(function, toLevels(quantifiedVariables));
        table.popFromWorkStack();
        assert table.workStacksEmpty();
        assert accessGuard.release();
        return result;
    }

    /** {@code variables}, re-indexed by the level each sits at. */
    private BitSet toLevels(BitSet variables) {
        if (!reordered()) {
            return variables;
        }
        BitSet levels = new BitSet(numberOfVariables());
        for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
            levels.set(level(variable));
        }
        return levels;
    }

    private int existsRecursive(int function, BitSet quantifiedLevels) {
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

        int lookup = cache.lookupExists(function);
        if (lookup != placeholder()) {
            return lookup;
        }
        int hash = cache.lookupHash();

        int lowExists = table.pushToWorkStack(existsRecursive(low(function), quantifiedLevels));
        int highExists = table.pushToWorkStack(existsRecursive(high(function), quantifiedLevels));
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
        cache.putExists(hash, function, result);
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
                    // or(domainLow, domainHigh) is exactly "exists domainLevel . domain" - the standard
                    // Coudert-Madre restrict step, and what distinguishes it from constrain above:
                    // rather than building a node on a variable the function does not test, drop that
                    // variable from the care set. Not an approximation - since the function is blind to
                    // domainLevel, every point of the quantified domain is one where some setting of
                    // domainLevel lands inside the domain, so the result is pinned there either way.
                    //
                    // TODO A cheap over-approximation of the union would be sound (agreeing on a larger
                    //   care set implies agreeing on this one) and would avoid the exact computeOr; an
                    //   *under*-approximation - e.g. picking one branch and setting the other to FALSE -
                    //   is not: it drops points of the domain and the result then disagrees with the
                    //   function inside it
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
     * <p>One thing does not carry over from a single diagram. There, any node that is not {@code FALSE}
     * has a path to {@code TRUE}, so a descent that never steps into {@code FALSE} always arrives
     * somewhere - the traversal only ever backtracks to find the <em>next</em> path. A pair of nodes that
     * are both non-{@code FALSE} can still have no assignment satisfying both, so here a descent can dead
     * end, and {@link #advance()} has to be able to retract one and carry on. With the domain at
     * {@code TRUE} that never happens, and this behaves exactly like the single-diagram walk.
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
        private final BitSet levelAssignment;
        private final BitSet pathSupportLevels;
        /* The levels of the current path, deepest last - the recursion's call stack, made explicit.
         * pathSupportLevels holds the same set for the cursors to read; this is what the walk itself
         * navigates by, because popping a frame has to be O(1) and scanning a bit set backwards is not. */
        private final int[] levelStack;
        /* Variable-indexed mirrors of the two sets above, maintained as the walk writes them, or null
         * when the caller reads levels directly. A step changes a handful of levels while the sets hold
         * the whole path, so mirroring the writes beats rebuilding the image of the set afterwards.
         * The one place the walk knows about variables at all - see the class comment. */
        private final @Nullable BitSet variableAssignment;
        private final @Nullable BitSet variableSupport;
        /* The order, snapshotted rather than asked for per write: a mirrored write is one array load
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
                @Nullable BitSet variableAssignment,
                @Nullable BitSet variableSupport) {
            assert bdd.isValidFunction(function) && bdd.isValidFunction(domain);
            assert function != FALSE && domain != FALSE;
            assert function != TRUE || domain != TRUE : "Nothing to walk - every assignment is a solution";

            int variableCount = bdd.numberOfVariables();
            this.bdd = bdd;
            this.rootFunction = function;
            this.rootDomain = domain;
            this.highFunctionPath = new int[variableCount];
            this.highDomainPath = new int[variableCount];
            this.levelAssignment = new BitSet(variableCount);
            this.pathSupportLevels = new BitSet(variableCount);
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

        /* The four writers of the two sets. Every write goes through one of them, which is what the
         * mirrors rest on: a write that bypassed them would leave the mirror silently stale. */

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

        BitSet pathSupportLevels() {
            return pathSupportLevels;
        }

        BitSet levelAssignment() {
            return levelAssignment;
        }

        int function() {
            return rootFunction;
        }

        int domain() {
            return rootDomain;
        }

        /** Whether the cursor is on a path: false once the enumeration is over, or if it never began. */
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
            //noinspection WhileLoopSpinsOnField -- Its not spinning, the depth is modified in descend
            while (stackDepth > 0) {
                int level = levelStack[stackDepth - 1];
                if (!levelAssignment.get(level)) {
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

        private int levelOf(int function) {
            return bdd.isConstant(function) ? Integer.MAX_VALUE : bdd.decisionLevel(function);
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

                int functionLevel = levelOf(function);
                int domainLevel = levelOf(domain);
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
                return false;
            }
            return true;
        }

        /** Drops the deepest level of the path, leaving the cursor on the one above it. */
        private void pop() {
            stackDepth -= 1;
            int level = levelStack[stackDepth];
            popSupport(level);
            assign(level, false);
        }
    }

    /**
     * Walks the solutions of a function: every path, and for each of them every way of filling in the
     * support variables that path leaves free.
     *
     * <p>Hands out the walk's own assignment, so nothing is copied per solution - see {@link Cursor}. The
     * one exception is a diagram that has been reordered, where the walk is by level and the caller wants
     * variables, and a translation buffer is unavoidable.
     */
    static final class SolutionCursor implements Cursor<BitSet> {
        private final BddImpl bdd;
        private final PathWalk path;
        private final BitSet supportLevels;
        /* The support levels the current path leaves free, recomputed whenever the path moves - once per
         * path, not per solution. There are far more solutions than paths, and rescanning the whole
         * support each time to skip what the path fixes is what puts this off the recursion's pace. */
        private final BitSet freeLevels;
        private final @Nullable BitSet translated;
        private boolean valid;

        private static BitSet levelsOf(BddImpl bdd, BitSet variables) {
            BitSet levels = new BitSet(bdd.numberOfVariables());
            BitSets.map(variables, levels, bdd::level);
            return levels;
        }

        SolutionCursor(BddImpl bdd, int function, int domain, BitSet support) {
            int variableCount = bdd.numberOfVariables();
            assert variableCount > 0 && support.length() <= variableCount;
            assert BitSets.isSubset(bdd.support(function), support);
            assert BitSets.isSubset(bdd.support(domain), support);

            this.bdd = bdd;
            boolean translating = bdd.reordered();
            this.supportLevels = translating ? levelsOf(bdd, support) : support;
            this.freeLevels = new BitSet(variableCount);
            this.translated = translating ? new BitSet(variableCount) : null;
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
        public BitSet current() {
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

        /**
         * Counts the free levels up by one, mirroring every bit it flips into the translation buffer as
         * it goes - amortized two of them. {@link #translate()} would rebuild the whole buffer instead,
         * which is the support per solution against a constant, and there are far more solutions than
         * paths. Identical to {@link BitSets#increment} otherwise, and delegates to it when there is no
         * buffer to keep up to date.
         */
        private boolean increment() {
            BitSet levelAssignment = path.levelAssignment();
            if (translated == null) {
                return BitSets.increment(levelAssignment, freeLevels);
            }
            for (int level = freeLevels.nextSetBit(0); level >= 0; level = freeLevels.nextSetBit(level + 1)) {
                int variable = bdd.variableAtLevel(level);
                if (levelAssignment.get(level)) {
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
            BitSets.difference(freeLevels, supportLevels, path.pathSupportLevels());
        }

        /**
         * Holds whenever {@link #current()} is defined. Rebuilding the buffer and comparing is what makes
         * the incremental mirroring checkable rather than merely argued: a write to the walk's assignment
         * that forgot to mirror itself shows up here, not as a wrong answer somewhere downstream.
         */
        private boolean currentIsConsistent() {
            assert BitSets.isSubset(path.pathSupportLevels(), supportLevels);
            assert bdd.evaluate(path.function(), current()) && bdd.evaluate(path.domain(), current());
            if (translated != null) {
                BitSet rebuilt = new BitSet(bdd.numberOfVariables());
                BitSets.map(path.levelAssignment(), rebuilt, bdd::variableAtLevel);
                assert rebuilt.equals(translated) : "Incremental translation drifted from the walk";
            }
            return true;
        }
    }

    /**
     * Walks the paths to {@code true} of a function - the same walk as
     * {@link SolutionCursor}, stopping at each path instead of filling in the variables it
     * leaves free.
     */
    static final class PathCursor implements Cursor<BinaryPath> {
        private final BddImpl bdd;
        private final PathWalk path;
        /** Only on a reordered diagram, where the walk is by level and the caller wants variables. */
        private final @Nullable BinaryPath translated;
        /** What {@link #current()} hands out: the translation buffer, or the walk's own sets wrapped. */
        private final BinaryPath current;

        private boolean valid;

        PathCursor(BddImpl bdd, int function) {
            int variableCount = bdd.numberOfVariables();
            this.bdd = bdd;
            this.translated =
                    bdd.reordered() ? new BinaryPath(new BitSet(variableCount), new BitSet(variableCount)) : null;
            // Both halves of a path are maintained by the walk itself, so a step rebuilds nothing.
            this.path = translated == null
                    ? new PathWalk(bdd, function, TRUE)
                    : new PathWalk(bdd, function, TRUE, translated.assignment, translated.support);
            this.valid = path.onPath();
            this.current =
                    translated == null ? new BinaryPath(path.levelAssignment(), path.pathSupportLevels()) : translated;
            assert !valid || currentIsConsistent();
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public BinaryPath current() {
            assert valid : "current() is only defined while the cursor is valid";
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

        /** Rebuilds both halves and compares - see {@link SolutionCursor#currentIsConsistent()}. */
        private boolean currentIsConsistent() {
            assert bdd.evaluate(path.function(), current.assignment);
            if (translated != null) {
                int variableCount = bdd.numberOfVariables();
                BitSet assignment = new BitSet(variableCount);
                BitSet support = new BitSet(variableCount);
                BitSets.map(path.levelAssignment(), assignment, bdd::variableAtLevel);
                BitSets.map(path.pathSupportLevels(), support, bdd::variableAtLevel);
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
        protected int level(int variable) {
            return bdd.level(variable);
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
        public boolean isValidConstant(int pointer) {
            return bdd.isConstant(pointer);
        }

        @Override
        public boolean isValidPointer(int pointer) {
            return bdd.isValidFunction(pointer);
        }

        @Override
        protected boolean recurseNoneMarkedBelow(int node) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE || doIsNoneMarkedBelow(low)) && (high == TRUE || doIsNoneMarkedBelow(high));
        }

        @Override
        protected boolean recurseIsAllMarkedBelow(int node, boolean includeLeaves) {
            int low = positive(low(node));
            int high = high(node);
            return (low == TRUE || doIsAllMarkedBelow(low, includeLeaves))
                    && (high == TRUE || doIsAllMarkedBelow(high, includeLeaves));
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
        protected void recurseForEachVariable(int node, IntConsumer action, @Nullable BitSet filter, int depthLimit) {
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
        int treeNodeFor(int pointer) {
            return bdd.nodeFor(pointer);
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
        protected void notifyAfterGc(int reclaimedNodes, BitSet reclaimedValues) {
            bdd.notifyAfterGc(reclaimedNodes);
        }

        @Override
        protected void notifyAfterTableGrowth(int invalidatedNodes, BitSet reclaimedValues) {
            bdd.notifyAfterTableGrow(invalidatedNodes);
        }

        @Override
        protected BitSet sweepManagedLeaves() {
            return BitSets.of();
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
        String format(int pointer) {
            return bdd.format(pointer);
        }
    }
}
