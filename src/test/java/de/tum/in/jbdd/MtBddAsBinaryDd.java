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

import de.tum.in.jbdd.collections.Cube;
import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.MutableNatSet;
import de.tum.in.jbdd.collections.NatSet;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Emulates a {@link Bdd} on top of an {@link MtBddImpl}, restricting the MTBDD's terminal values to
 * {@code 0} (false) and {@code 1} (true). This lets {@link BddTheories} exercise MtBddImpl's own
 * operations (apply/map/restrict/compose/mapBoolean/...) through the same boolean-algebra property
 * tests used for {@link BddImpl} and {@link MddImpl}, in the spirit of {@link MddAsBinaryDd}.
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
// Coupled to many types because it implements the whole diagram interface over an MTBDD.
@SuppressWarnings("PMD.CouplingBetweenObjects")
class MtBddAsBinaryDd implements BinaryDd, ReorderableDd, StatisticsSource {
    private static final int TRUE = 1;
    private static final int FALSE = 0;

    private final MtBddImpl mt;

    MtBddAsBinaryDd(MtBddImpl mt) {
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
    public int levelOfVariable(int variable) {
        return mt.levelOfVariable(variable);
    }

    @Override
    public int variableAtLevel(int level) {
        return mt.variableAtLevel(level);
    }

    @Override
    public DdVariableOrder variableOrder() {
        return mt.variableOrder();
    }

    /* Not an override any more - the order-placing creation is the context's, and hands back a Bdd
     * function; this adapter's currency is MTBDD functions, so the tests go through here. */
    int createVariableAtLevel(int level) {
        // As createVariable(), but placed: MtBdd#createVariableAtLevel hands back the companion BDD's
        // function, and this adapter's currency is MTBDD functions.
        int variableNode = mt.context().createVariableAtLevel(level);
        int variable = mt.bdd().decisionVariable(variableNode);
        int variableFunction = variableFunction(variable);
        mt.table().saturateNode(mt.nodeFor(variableFunction));
        return variableFunction;
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
    public boolean evaluate(int function, boolean[] assignment) {
        return mt.evaluate(function, assignment) != FALSE;
    }

    @Override
    public boolean evaluate(int function, NatSet assignment) {
        return mt.evaluate(function, assignment) != FALSE;
    }

    @Override
    public MutableNatSet satisfyingAssignment(int function) {
        return mt.anyAssignment(function, v -> v != FALSE).orElseThrow();
    }

    @Override
    public Optional<NatSet> satisfyingAssignmentIn(int function, int domain) {
        return mt.anyAssignment(and(function, domain), v -> v != FALSE).map(NatSet.class::cast);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        return mt.countAssignments(function, v -> v != FALSE);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, NatSet support) {
        return mt.countAssignments(function, v -> v != FALSE, support);
    }

    @Override
    public BigInteger countSatisfyingAssignmentsIn(int function, int domain) {
        return mt.countAssignments(and(function, domain), v -> v != FALSE);
    }

    @Override
    public double satisfyingFraction(int function) {
        // The exact reference: 2^-n is a finite decimal, so only the conversion rounds.
        return new BigDecimal(countSatisfyingAssignments(function))
                .multiply(BigDecimal.valueOf(5, 1).pow(numberOfVariables()))
                .doubleValue();
    }

    @Override
    public double satisfyingFractionIn(int function, int domain) {
        if (domain == falseFunction()) {
            throw new IllegalArgumentException("Empty domain");
        }
        return Util.quotient(countSatisfyingAssignmentsIn(function, domain), countSatisfyingAssignments(domain));
    }

    @Override
    public Cursor<NatSet> solutionCursor(int function) {
        return mt.assignmentCursor(function, v -> v != FALSE);
    }

    @Override
    public Cursor<NatSet> solutionCursor(int function, NatSet support) {
        return mt.assignmentCursor(function, v -> v != FALSE, support);
    }

    @Override
    public Cursor<NatSet> solutionCursorIn(int function, int domain) {
        return mt.assignmentCursor(and(function, domain), v -> v != FALSE);
    }

    @Override
    public Cursor<NatSet> solutionCursorIn(int function, int domain, NatSet support) {
        return mt.assignmentCursor(and(function, domain), v -> v != FALSE, support);
    }

    @Override
    public Cursor<Cube> pathCursor(int function) {
        List<Cube> paths = new ArrayList<>();
        forEachPath(function, path -> paths.add(path.copy()));
        Iterator<Cube> iterator = paths.iterator();
        // A collected list, so this one really does own each element it hands out.
        return new Cursor<>() {
            private @Nullable Cube current = iterator.hasNext() ? iterator.next() : null;

            @Override
            public boolean valid() {
                return current != null;
            }

            @Override
            public Cube current() {
                Cube path = current;
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
    public void forEachPath(int function, Consumer<? super Cube> action) {
        mt.forEachPath(function, (path, terminal) -> {
            if (terminal == TRUE) {
                action.accept(path);
            }
        });
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super Cube> predicate) {
        if (function == falseFunction()) {
            return false;
        }
        if (function == trueFunction()) {
            return predicate.test(Cube.ofUnsafe(MutableNatSet.dense(0), MutableNatSet.dense(0)));
        }
        int numberOfVariables = mt.numberOfVariables();
        WalkCube path = new WalkCube(numberOfVariables);
        return anyPathMatchesRecursive(function, path, predicate);
    }

    private boolean anyPathMatchesRecursive(int node, WalkCube path, Predicate<? super Cube> predicate) {
        if (node == trueFunction()) {
            return predicate.test(path.cube);
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
    public void forEachSupportVariableFiltered(int function, NatSet filter, IntConsumer action) {
        mt.forEachSupportVariableFiltered(function, filter, action);
    }

    @Override
    public int size(int function) {
        return mt.size(function);
    }

    @Override
    public int conjunction(NatSet variables) {
        int result = mt.reference(trueFunction());
        for (int var = variables.nextSetBit(0); var >= 0; var = variables.nextSetBit(var + 1)) {
            int varFunction = variableFunction(var);
            result = mt.consume(and(result, varFunction), result, varFunction);
        }
        mt.dereference(result);
        return result;
    }

    @Override
    public int disjunction(NatSet variables) {
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
    public int exists(int function, NatSet quantifiedVariables) {
        if (quantifiedVariables.isEmpty()) {
            return function;
        }
        int result = mt.reference(falseFunction());
        Iterator<NatSet> iterator = NatSetFixtures.powerSetIterator(quantifiedVariables);
        while (iterator.hasNext()) {
            NatSet assignment = iterator.next();
            int restricted = mt.reference(mt.restrict(function, Cube.of(assignment, quantifiedVariables)));
            result = mt.consume(or(result, restricted), result, restricted);
        }
        mt.dereference(result);
        return result;
    }

    @Override
    public int forall(int function, NatSet quantifiedVariables) {
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
        throw new UnsupportedOperationException("registerCompose is not supported on an MTBDD-backed BinaryDd");
    }

    @Override
    public RegisteredOperation.Binary registerComposeSimplify(int[] variableMapping) {
        throw new UnsupportedOperationException("registerComposeSimplify is not supported on an MTBDD-backed BinaryDd");
    }

    @Override
    public RegisteredOperation.Unary registerExists(NatSet quantifiedVariables) {
        // As registerCompose: this adapter quantifies through the MTBDD engine (see #exists), which has
        // no equivalent registered form.
        throw new UnsupportedOperationException("registerExists is not supported on an MTBDD-backed BinaryDd");
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
    public int adopt(BinaryDecisionDiagram source, int function, IntUnaryOperator variableMapping) {
        return BddUtil.adopt(this, source, function, variableMapping);
    }

    @Override
    public int restrict(int function, Cube restriction) {
        return mt.restrict(function, restriction);
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
    public Map<String, Object> statistics(StatisticsDetail detail) {
        return mt.statistics(detail);
    }

    @Override
    public Map<String, StatisticDescription> describeStatistics() {
        return mt.describeStatistics();
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
