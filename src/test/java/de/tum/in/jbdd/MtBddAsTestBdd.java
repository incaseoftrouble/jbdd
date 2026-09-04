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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Emulates a {@link Bdd} on top of an {@link MtBddImpl}, restricting the MTBDD's terminal values to
 * {@code 0} (false) and {@code 1} (true). This lets {@link BddTheories} exercise MtBddImpl's own
 * operations (apply/map/restrict/compose/mapBoolean/...) through the same boolean-algebra property
 * tests used for {@link BddImpl} and {@link MddImpl}, in the spirit of {@link MddAsTestBdd}.
 *
 * <p>Unlike MDD (whose terminals are already boolean, {@code true}/{@code false}, independent of each
 * variable's domain size), MtBdd's terminals are arbitrary caller-chosen values with no built-in boolean
 * algebra, and {@code 0}/{@code 1} here are not structurally special the way {@code TRUE}/{@code FALSE}
 * are in {@link BddImpl}/{@link MddImpl} - they are ordinary MTBDD constants, fully subject to normal
 * reference counting. Most methods below are single, self-contained calls into {@code mt} and need no
 * extra care (an operation's own inputs are the caller's responsibility to keep alive, exactly as for
 * every other decision diagram operation in this codebase). Methods that chain <em>several</em>
 * MtBddImpl-constructing calls together (across separate statements, not one nested expression) do need
 * explicit {@code reference}/{@code dereference}/{@code consume} bookkeeping in between, since a later
 * call in the chain can trigger a GC that would otherwise reclaim an earlier, still-needed intermediate
 * result - see {@link #exists}, {@link #conjunction}, {@link #disjunction}, {@link #ifThenElse} and
 * {@link #compose} for that pattern.</p>
 */
class MtBddAsTestBdd implements TestBdd, ReorderableDd {
    private static final int TRUE = 1;
    private static final int FALSE = 0;

    private final MtBddImpl mt;

    MtBddAsTestBdd(MtBddImpl mt) {
        this.mt = mt;
    }

    @Override
    public int placeholder() {
        return mt.placeholder();
    }

    @Override
    public int trueFunction() {
        return mt.of(TRUE);
    }

    @Override
    public int falseFunction() {
        return mt.of(FALSE);
    }

    @Override
    public int highOf(int function) {
        return mt.highOf(function);
    }

    @Override
    public int lowOf(int function) {
        return mt.lowOf(function);
    }

    @Override
    public int nodeFor(int function) {
        return mt.nodeFor(function);
    }

    @Override
    public int nodeReferenceCount(int node) {
        return mt.nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        return mt.isSaturatedNode(node);
    }

    @Override
    public int referencedNodeCount() {
        return mt.referencedNodeCount();
    }

    @Override
    public int nodeCount() {
        return mt.nodeCount();
    }

    @Override
    public int gc() {
        return mt.gc();
    }

    @Override
    public int reference(int function) {
        return mt.reference(function);
    }

    @Override
    public int dereference(int function) {
        return mt.dereference(function);
    }

    @Override
    public boolean isConstant(int function) {
        return mt.isConstant(function);
    }

    @Override
    public boolean isUnmanaged(int function) {
        return mt.isUnmanaged(function);
    }

    @Override
    public boolean isVariable(int function) {
        return !mt.isConstant(function)
                && mt.lowOf(function) == falseFunction()
                && mt.highOf(function) == trueFunction();
    }

    @Override
    public boolean isVariableNegated(int function) {
        return !mt.isConstant(function)
                && mt.lowOf(function) == trueFunction()
                && mt.highOf(function) == falseFunction();
    }

    @Override
    public boolean isVariableOrNegated(int function) {
        return isVariable(function) || isVariableNegated(function);
    }

    @Override
    public int numberOfVariables() {
        return mt.numberOfVariables();
    }

    @Override
    public int createVariable() {
        int variableNode = mt.bdd().createVariable();
        int variable = mt.bdd().decisionVariable(variableNode);
        int variableFunction = variableFunction(variable);
        mt.table().saturateNode(mt.nodeFor(variableFunction));
        return variableFunction;
    }

    @Override
    public int variableFunction(int variableNumber) {
        return mt.of(variableNumber, mt.of(TRUE), mt.of(FALSE));
    }

    /* The MTBDD shares its companion BDD's variable order, so reordering through either moves the nodes
     * of both - which is exactly what makes this adapter worth reordering: it puts MTBDD enumeration,
     * apply and compose under a non-identity order, which nothing else does. */

    @Override
    public int level(int variable) {
        return mt.level(variable);
    }

    @Override
    public int variableAtLevel(int level) {
        return mt.variableAtLevel(level);
    }

    @Override
    public int reorder() {
        return mt.reorder();
    }

    @Override
    public int reorder(List<BitSet> groups) {
        return mt.reorder(groups);
    }

    @Override
    public void reorderTo(List<BitSet> blocks) {
        mt.reorderTo(blocks);
    }

    @Override
    public void reorderToIdentity() {
        mt.reorderToIdentity();
    }

    @Override
    public int createVariableAtLevel(int level) {
        // As createVariable(), but placed: MtBdd#createVariableAtLevel hands back the companion BDD's
        // function, and this adapter's currency is MTBDD functions.
        int variableNode = mt.bdd().createVariableAtLevel(level);
        int variable = mt.bdd().decisionVariable(variableNode);
        int variableFunction = variableFunction(variable);
        mt.table().saturateNode(mt.nodeFor(variableFunction));
        return variableFunction;
    }

    @Override
    public int[] createVariablesAtLevel(int level, int count) {
        // As above, one MTBDD function per variable the companion BDD just created.
        int[] variableNodes = mt.bdd().createVariablesAtLevel(level, count);
        int[] variableFunctions = new int[variableNodes.length];
        for (int index = 0; index < variableNodes.length; index++) {
            int variable = mt.bdd().decisionVariable(variableNodes[index]);
            variableFunctions[index] = variableFunction(variable);
            mt.table().saturateNode(mt.nodeFor(variableFunctions[index]));
        }
        return variableFunctions;
    }

    @Override
    public void dropReorderStructures() {
        mt.dropReorderStructures();
    }

    @Override
    public int decisionVariable(int function) {
        return mt.decisionVariable(function);
    }

    @Override
    public boolean isValidFunction(int function) {
        return mt.isValidFunction(function);
    }

    @Override
    public boolean isValidNonConstantFunction(int function) {
        return mt.isValidNonConstantFunction(function);
    }

    @Override
    public boolean evaluate(int function, boolean[] assignment) {
        return mt.evaluate(function, assignment) != FALSE;
    }

    @Override
    public boolean evaluate(int function, BitSet assignment) {
        return mt.evaluate(function, assignment) != FALSE;
    }

    @Override
    public BitSet satisfyingAssignment(int function) {
        return mt.anyAssignment(function, v -> v != FALSE).orElseThrow();
    }

    @Override
    public Optional<BitSet> satisfyingAssignmentIn(int function, int domain) {
        return mt.anyAssignment(and(function, domain), v -> v != FALSE);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        return mt.countAssignments(function, v -> v != FALSE);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        return mt.countAssignments(function, v -> v != FALSE, support);
    }

    @Override
    public BigInteger countSatisfyingAssignmentsIn(int function, int domain) {
        return mt.countAssignments(and(function, domain), v -> v != FALSE);
    }

    @Override
    public Cursor<BitSet> solutionCursor(int function) {
        return mt.assignmentCursor(function, v -> v != FALSE);
    }

    @Override
    public Cursor<BitSet> solutionCursor(int function, BitSet support) {
        return mt.assignmentCursor(function, v -> v != FALSE, support);
    }

    @Override
    public Cursor<BitSet> solutionCursorIn(int function, int domain) {
        return mt.assignmentCursor(and(function, domain), v -> v != FALSE);
    }

    @Override
    public Cursor<BitSet> solutionCursorIn(int function, int domain, BitSet support) {
        return mt.assignmentCursor(and(function, domain), v -> v != FALSE, support);
    }

    @Override
    public Cursor<BinaryPath> pathCursor(int function) {
        List<BinaryPath> paths = new ArrayList<>();
        forEachPath(function, path -> paths.add(path.copy()));
        Iterator<BinaryPath> iterator = paths.iterator();
        // A collected list, so this one really does own each element it hands out.
        return new Cursor<BinaryPath>() {
            private @Nullable BinaryPath current = iterator.hasNext() ? iterator.next() : null;

            @Override
            public boolean valid() {
                return current != null;
            }

            @Override
            public BinaryPath current() {
                BinaryPath path = current;
                assert path != null;
                return path;
            }

            @Override
            public boolean advance() {
                current = iterator.hasNext() ? iterator.next() : null;
                return current != null;
            }
        };
    }

    @Override
    public void forEachPath(int function, Consumer<? super BinaryPath> action) {
        mt.forEachPath(function, (path, terminal) -> {
            if (terminal == TRUE) {
                action.accept(path);
            }
        });
    }

    @Override
    public void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super BinaryPath> action) {
        if (function == falseFunction()) {
            return;
        }
        if (function == trueFunction() || relevantSet.isEmpty()) {
            action.accept(new BinaryPath(new BitSet(0), new BitSet(0)));
            return;
        }
        /* By level, not by variable: the cut-off is "the walk is past everything relevant", which is a
         * statement about the order. The two coincide only until something reorders. */
        int maxRelevantLevel = -1;
        for (int v = relevantSet.nextSetBit(0); v >= 0; v = relevantSet.nextSetBit(v + 1)) {
            maxRelevantLevel = Math.max(maxRelevantLevel, mt.level(v));
        }
        int variables = mt.numberOfVariables();
        BinaryPath path = new BinaryPath(new BitSet(variables), new BitSet(variables));
        forEachPathRecursive(function, relevantSet, maxRelevantLevel, path, action);
    }

    private void forEachPathRecursive(
            int node, BitSet relevantSet, int depthLimit, BinaryPath path, Consumer<? super BinaryPath> action) {
        if (node == trueFunction()) {
            action.accept(path);
            return;
        }
        int variable = mt.decisionVariable(node);
        if (mt.level(variable) > depthLimit) {
            // There must exist at least one satisfying completion beyond depthLimit.
            action.accept(path);
            return;
        }

        int low = mt.lowOf(node);
        int high = mt.highOf(node);
        boolean relevant = relevantSet.get(variable);

        if (relevant) {
            path.support.set(variable);
        }
        if (low != falseFunction()) {
            forEachPathRecursive(low, relevantSet, depthLimit, path, action);
        }
        if (high != falseFunction()) {
            if (relevant) {
                path.assignment.set(variable);
                forEachPathRecursive(high, relevantSet, depthLimit, path, action);
                path.assignment.clear(variable);
            } else {
                forEachPathRecursive(high, relevantSet, depthLimit, path, action);
            }
        }
        if (relevant) {
            path.support.clear(variable);
        }
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super BinaryPath> predicate) {
        if (function == falseFunction()) {
            return false;
        }
        if (function == trueFunction()) {
            return predicate.test(new BinaryPath(new BitSet(0), new BitSet(0)));
        }
        int numberOfVariables = mt.numberOfVariables();
        BinaryPath path = new BinaryPath(new BitSet(numberOfVariables), new BitSet(numberOfVariables));
        return anyPathMatchesRecursive(function, path, predicate);
    }

    private boolean anyPathMatchesRecursive(int node, BinaryPath path, Predicate<? super BinaryPath> predicate) {
        if (node == trueFunction()) {
            return predicate.test(path);
        }
        int variable = mt.decisionVariable(node);
        int low = mt.lowOf(node);
        int high = mt.highOf(node);

        path.support.set(variable);
        if (low != falseFunction() && anyPathMatchesRecursive(low, path, predicate)) {
            path.support.clear(variable);
            return true;
        }
        boolean matched = false;
        if (high != falseFunction()) {
            path.assignment.set(variable);
            matched = anyPathMatchesRecursive(high, path, predicate);
            path.assignment.clear(variable);
        }
        path.support.clear(variable);
        return matched;
    }

    @Override
    public void forEachSupportVariableFiltered(int function, BitSet filter, IntConsumer action) {
        mt.forEachSupportVariableFiltered(function, filter, action);
    }

    @Override
    public int size(int function) {
        return mt.size(function);
    }

    @Override
    public int conjunction(BitSet variables) {
        int result = mt.reference(trueFunction());
        for (int var = variables.nextSetBit(0); var >= 0; var = variables.nextSetBit(var + 1)) {
            int varFunction = variableFunction(var);
            result = mt.consume(and(result, varFunction), result, varFunction);
        }
        mt.dereference(result);
        return result;
    }

    @Override
    public int disjunction(BitSet variables) {
        int result = mt.reference(falseFunction());
        for (int var = variables.nextSetBit(0); var >= 0; var = variables.nextSetBit(var + 1)) {
            int varFunction = variableFunction(var);
            result = mt.consume(or(result, varFunction), result, varFunction);
        }
        mt.dereference(result);
        return result;
    }

    @Override
    public int and(int function1, int function2) {
        // Genuine commutative monoid (neutral=TRUE) with an absorbing element (FALSE) - exercises the
        // shortcut-taking apply variants from real boolean usage, not just MtBddTheories' synthetic ops.
        return mt.applyMonoid(function1, function2, (a, b) -> a == FALSE || b == FALSE ? FALSE : TRUE, TRUE, FALSE);
    }

    @Override
    public int andNot(int function1, int function2) {
        // NOT commutative (andNot(a,b) != andNot(b,a)), and FALSE plays different roles depending on
        // position (right-neutral, left-absorbing) - incompatible with applyMonoid/applyAbsorbing's
        // two-sided (either-position) contract, so this stays plain apply.
        return mt.apply(function1, function2, (a, b) -> a != FALSE && b == FALSE ? TRUE : FALSE);
    }

    @Override
    public int equivalence(int function1, int function2) {
        // Commutative monoid (neutral=TRUE), but no absorbing element - equivalence is invertible (it's a
        // group operation on {TRUE,FALSE}), so no constant ever forces a fixed result regardless of the
        // other operand.
        return mt.applyMonoid(function1, function2, (a, b) -> (a != FALSE) == (b != FALSE) ? TRUE : FALSE, TRUE);
    }

    @Override
    public int not(int function) {
        return mt.map(function, v -> v == FALSE ? TRUE : FALSE);
    }

    @Override
    public int notAnd(int function1, int function2) {
        // Commutative (NOT(AND(a,b)) == NOT(AND(b,a))), but no neutral or absorbing element for either
        // constant - stays plain apply.
        return mt.apply(function1, function2, (a, b) -> a == FALSE || b == FALSE ? TRUE : FALSE);
    }

    @Override
    public int or(int function1, int function2) {
        return mt.applyMonoid(function1, function2, (a, b) -> a == FALSE && b == FALSE ? FALSE : TRUE, FALSE, TRUE);
    }

    @Override
    public int xor(int function1, int function2) {
        // Commutative monoid (neutral=FALSE), no absorbing element - same reasoning as equivalence (also a
        // group operation on {TRUE,FALSE}).
        return mt.applyMonoid(function1, function2, (a, b) -> (a == FALSE) == (b != FALSE) ? TRUE : FALSE, FALSE);
    }

    @Override
    public int implication(int function1, int function2) {
        // NOT commutative, and TRUE plays different roles depending on position (left-neutral,
        // right-absorbing) - same incompatibility as andNot, stays plain apply.
        return mt.apply(function1, function2, (a, b) -> a == FALSE || b != FALSE ? TRUE : FALSE);
    }

    @Override
    public boolean implies(int function1, int function2) {
        return and(function1, not(function2)) == falseFunction();
    }

    @Override
    public boolean intersects(int function1, int function2) {
        return and(function1, function2) != falseFunction();
    }

    @Override
    public int exists(int function, BitSet quantifiedVariables) {
        if (quantifiedVariables.isEmpty()) {
            return function;
        }
        int result = mt.reference(falseFunction());
        Iterator<BitSet> iterator = BitSets.powerSetIterator(quantifiedVariables);
        while (iterator.hasNext()) {
            BitSet assignment = iterator.next();
            int restricted = mt.reference(mt.restrict(function, quantifiedVariables, assignment));
            result = mt.consume(or(result, restricted), result, restricted);
        }
        mt.dereference(result);
        return result;
    }

    @Override
    public int forall(int function, BitSet quantifiedVariables) {
        int notFunction = mt.reference(not(function));
        int existsResult = mt.reference(exists(notFunction, quantifiedVariables));
        mt.dereference(notFunction);
        int result = not(existsResult);
        mt.dereference(existsResult);
        return result;
    }

    @Override
    public int ifThenElse(int ifFunction, int thenFunction, int elseFunction) {
        Bdd bdd = mt.bdd();
        int bddCondition = bdd.reference(mt.mapBoolean(ifFunction, v -> v != FALSE));
        int result = mt.ifThenElse(bddCondition, thenFunction, elseFunction);
        bdd.dereference(bddCondition);
        return result;
    }

    @Override
    public RegisteredOperation.Unary registerCompose(int[] variableMapping) {
        // Registered compose is tied to a real BddImpl's own compose; this adapter routes compose through
        // the MTBDD engine instead (see #compose below), which has no equivalent registered form (yet).
        throw new UnsupportedOperationException("registerCompose is not supported on an MTBDD-backed TestBdd");
    }

    @Override
    public RegisteredOperation.Binary registerComposeSimplify(int[] variableMapping) {
        throw new UnsupportedOperationException("registerComposeSimplify is not supported on an MTBDD-backed TestBdd");
    }

    @Override
    public int compose(int function, int[] variableMapping) {
        Bdd bdd = mt.bdd();
        int[] bddMapping = new int[variableMapping.length];
        for (int i = 0; i < variableMapping.length; i++) {
            bddMapping[i] = variableMapping[i] == mt.placeholder()
                    ? mt.placeholder()
                    : bdd.reference(mt.mapBoolean(variableMapping[i], v -> v != FALSE));
        }
        int result = mt.compose(function, bddMapping);
        for (int i = 0; i < variableMapping.length; i++) {
            if (variableMapping[i] != mt.placeholder()) {
                bdd.dereference(bddMapping[i]);
            }
        }
        return result;
    }

    @Override
    public int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        return mt.restrict(function, restrictedVariables, restrictedVariableValues);
    }

    @Override
    public int simplify(int function, int domain) {
        // Same bridging as compose(): MtBdd.simplify's domain is a real Bdd function.
        Bdd bdd = mt.bdd();
        int bddDomain = bdd.reference(mt.mapBoolean(domain, v -> v != FALSE));
        int result = mt.simplify(function, bddDomain);
        bdd.dereference(bddDomain);
        return result;
    }

    @Override
    public int constrain(int function, int domain) {
        // Same bridging as simplify(): MtBdd.constrain's domain is a real Bdd function.
        Bdd bdd = mt.bdd();
        int bddDomain = bdd.reference(mt.mapBoolean(domain, v -> v != FALSE));
        int result = mt.constrain(function, bddDomain);
        bdd.dereference(bddDomain);
        return result;
    }

    @Override
    public Map<String, Object> statistics() {
        return mt.statistics();
    }

    @Override
    public String toString() {
        String name = mt.bddImpl().configuration().name();
        return name.isEmpty() ? "mtbdd" : name;
    }

    @Override
    public void invalidateCache() {
        mt.invalidateCache();
    }

    @Override
    public boolean check() {
        return mt.check();
    }

    @Override
    public String treeToString(int function) {
        return mt.table().treeToString(function);
    }
}
