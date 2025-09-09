/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2024 Tobias Meggendorfer.
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

import com.google.common.collect.Iterators;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

class MddAsTestBdd implements TestBdd {
    private final MddImpl mdd;
    private static final int TRUE = 1;
    private static final int FALSE = 0;

    public MddAsTestBdd(MddImpl mdd) {
        this.mdd = mdd;
    }

    @Override
    public int placeholder() {
        return mdd.placeholder();
    }

    @Override
    public int trueFunction() {
        return mdd.trueFunction();
    }

    @Override
    public int falseFunction() {
        return mdd.falseFunction();
    }

    @Override
    public int highOf(int function) {
        return mdd.follow(function, TRUE);
    }

    @Override
    public int lowOf(int function) {
        return mdd.follow(function, FALSE);
    }

    @Override
    public int nodeFor(int function) {
        return mdd.nodeFor(function);
    }

    @Override
    public int nodeReferenceCount(int node) {
        return mdd.nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        return mdd.isSaturatedNode(node);
    }

    @Override
    public int referencedNodeCount() {
        return mdd.referencedNodeCount();
    }

    @Override
    public int nodeCount() {
        return mdd.nodeCount();
    }

    @Override
    public int reference(int function) {
        return mdd.reference(function);
    }

    @Override
    public int dereference(int function) {
        return mdd.dereference(function);
    }

    @Override
    public boolean isConstant(int function) {
        return mdd.isConstant(function);
    }

    @Override
    public boolean isVariable(int function) {
        return !isConstant(function)
                && mdd.follow(function, FALSE) == falseFunction()
                && mdd.follow(function, TRUE) == trueFunction();
    }

    @Override
    public boolean isVariableNegated(int function) {
        return !isConstant(function)
                && mdd.follow(function, FALSE) == trueFunction()
                && mdd.follow(function, TRUE) == falseFunction();
    }

    @Override
    public boolean isVariableOrNegated(int function) {
        return isVariable(function) || isVariableNegated(function);
    }

    @Override
    public int numberOfVariables() {
        return mdd.numberOfVariables();
    }

    @Override
    public int createVariable() {
        int variable = mdd.declareVariable(2);
        int variableFunction = mdd.makeVariableFunction(variable, new boolean[] {false, true});
        assert isVariable(variableFunction);
        mdd.table().saturateNode(mdd.nodeFor(variableFunction));
        return variableFunction;
    }

    @Override
    public int variableFunction(int variableNumber) {
        return mdd.makeVariableFunction(variableNumber, new boolean[] {false, true});
    }

    @Override
    public int decisionVariable(int function) {
        return mdd.decisionVariable(function);
    }

    @Override
    public boolean isValidFunction(int function) {
        return mdd.isValidFunction(function);
    }

    @Override
    public boolean isValidNonConstantFunction(int function) {
        return mdd.isValidNonConstantFunction(function);
    }

    @Override
    public boolean evaluate(int function, boolean[] assignment) {
        int[] values = new int[assignment.length];
        Arrays.setAll(values, i -> assignment[i] ? TRUE : FALSE);
        return mdd.evaluate(function, values);
    }

    @Override
    public boolean evaluate(int function, BitSet assignment) {
        int[] values = new int[mdd.numberOfVariables()];
        Arrays.setAll(values, i -> assignment.get(i) ? TRUE : FALSE);
        return mdd.evaluate(function, values);
    }

    @Override
    public BitSet satisfyingAssignment(int function) {
        int[] values = mdd.satisfyingAssignment(function);
        BitSet set = new BitSet(mdd.numberOfVariables());
        for (int i = 0; i < values.length; i++) {
            if (values[i] == TRUE) {
                set.set(i);
            }
        }
        return set;
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        return mdd.countSatisfyingAssignments(function);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        return mdd.countSatisfyingAssignments(function, support);
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function) {
        BitSet set = new BitSet(mdd.numberOfVariables());
        return Iterators.transform(mdd.solutionIterator(function), a -> {
            for (int i = 0; i < a.length; i++) {
                assert a[i] == TRUE || a[i] == FALSE;
                set.set(i, a[i] == TRUE);
            }
            return set;
        });
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function, BitSet support) {
        BitSet set = new BitSet(mdd.numberOfVariables());
        return Iterators.transform(mdd.solutionIterator(function, support), a -> {
            for (int i = 0; i < a.length; i++) {
                assert a[i] == TRUE || a[i] == FALSE;
                set.set(i, a[i] == TRUE);
            }
            return set;
        });
    }

    @Override
    public Iterator<BinaryPath> pathIterator(int function) {
        BitSet assignment = new BitSet(mdd.numberOfVariables());
        BitSet support = new BitSet(mdd.numberOfVariables());
        return Iterators.transform(mdd.pathIterator(function), a -> {
            for (int i = 0; i < a.length; i++) {
                assert a[i] == TRUE || a[i] == FALSE || a[i] == -1;
                if (a[i] == -1) {
                    support.clear(i);
                    assignment.clear(i);
                } else {
                    support.set(i);
                    assignment.set(i, a[i] == TRUE);
                }
            }
            return new BinaryPath(assignment, support);
        });
    }

    @Override
    public void forEachPath(int function, Consumer<? super BinaryPath> action) {
        BitSet everything = new BitSet();
        everything.set(0, mdd.numberOfVariables());
        forEachPartialPath(function, everything, action);
    }

    @Override
    public void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super BinaryPath> action) {
        int variables = mdd.numberOfVariables();
        BitSet values = new BitSet(variables);
        BitSet support = new BitSet(variables);
        BinaryPath bddPath = new BinaryPath(values, support);
        mdd.forEachPartialPath(function, relevantSet, path -> {
            for (int var = 0; var < path.length; var++) {
                assert path[var] == -1 || path[var] == TRUE || path[var] == FALSE;
                if (relevantSet.get(var)) {
                    if (path[var] == -1) {
                        values.clear(var);
                        support.clear(var);
                    } else {
                        support.set(var);
                        values.set(var, path[var] == TRUE);
                    }
                }
            }
            action.accept(bddPath);
        });
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super BinaryPath> predicate) {
        int variables = mdd.numberOfVariables();
        BitSet values = new BitSet(variables);
        BitSet support = new BitSet(variables);
        BinaryPath bddPath = new BinaryPath(values, support);
        return mdd.anyPathMatches(function, path -> {
            for (int var = 0; var < path.length; var++) {
                assert path[var] == -1 || path[var] == TRUE || path[var] == FALSE;
                if (path[var] == -1) {
                    values.clear(var);
                    support.clear(var);
                } else {
                    support.set(var);
                    values.set(var, path[var] == TRUE);
                }
            }
            return predicate.test(bddPath);
        });
    }

    @Override
    public void forEachSupportFiltered(int function, BitSet filter, IntConsumer action) {
        mdd.forEachSupportFiltered(function, filter, action);
    }

    @Override
    public int conjunction(BitSet variables) {
        int function = TRUE;
        for (int var = variables.nextSetBit(0); var >= 0; var = variables.nextSetBit(var + 1)) {
            function = mdd.and(function, variableFunction(var));
        }
        return function;
    }

    @Override
    public int disjunction(BitSet variables) {
        int function = FALSE;
        for (int var = variables.nextSetBit(0); var >= 0; var = variables.nextSetBit(var + 1)) {
            function = mdd.or(function, variableFunction(var));
        }
        return function;
    }

    @Override
    public int and(int function1, int function2) {
        return mdd.and(function1, function2);
    }

    @Override
    public int andNot(int function1, int function2) {
        return mdd.andNot(function1, function2);
    }

    @Override
    public int equivalence(int function1, int function2) {
        return mdd.equivalence(function1, function2);
    }

    @Override
    public int exists(int function, BitSet quantifiedVariables) {
        return mdd.exists(function, quantifiedVariables);
    }

    @Override
    public int forall(int function, BitSet quantifiedVariables) {
        return mdd.forall(function, quantifiedVariables);
    }

    @Override
    public int not(int function) {
        return mdd.not(function);
    }

    @Override
    public int notAnd(int function1, int function2) {
        return mdd.notAnd(function1, function2);
    }

    @Override
    public int or(int function1, int function2) {
        return mdd.or(function1, function2);
    }

    @Override
    public int xor(int function1, int function2) {
        return mdd.xor(function1, function2);
    }

    @Override
    public int implication(int function1, int function2) {
        return mdd.implication(function1, function2);
    }

    @Override
    public boolean implies(int function1, int function2) {
        return mdd.implies(function1, function2);
    }

    @Override
    public boolean intersects(int function1, int function2) {
        return mdd.intersects(function1, function2);
    }

    @Override
    public int compose(int function, int[] variableMapping) {
        int[] constantReplacements = new int[mdd.numberOfVariables()];
        Arrays.fill(constantReplacements, -1);
        BitSet replaced = new BitSet(mdd.numberOfVariables());
        for (int var = 0; var < variableMapping.length; var++) {
            int replacement = variableMapping[var];
            if (replacement == mdd.placeholder() || (isVariable(replacement) && decisionVariable(replacement) == var)) {
                continue;
            }
            if (replacement == trueFunction()) {
                constantReplacements[var] = TRUE;
            } else if (replacement == falseFunction()) {
                constantReplacements[var] = FALSE;
            } else {
                replaced.set(var);
            }
        }
        int base = mdd.reference(mdd.restrict(function, constantReplacements));

        if (replaced.isEmpty()) {
            mdd.dereference(base);
            return base;
        }

        var iterator = BitSets.powerSetIterator(replaced);
        int result = falseFunction();
        while (iterator.hasNext()) {
            var assigment = iterator.next();
            int restrict = mdd.reference(restrict(base, replaced, assigment));

            int assignment = trueFunction();
            for (int var = replaced.nextSetBit(0); var >= 0; var = replaced.nextSetBit(var + 1)) {
                int replacement = variableMapping[var];
                int varFunction = assigment.get(var) ? replacement : not(replacement);
                assignment = mdd.updateWith(mdd.and(assignment, varFunction), assignment);
            }

            int value = mdd.consume(mdd.and(assignment, restrict), assignment, restrict);
            result = mdd.consume(mdd.or(result, value), result, value);
        }
        mdd.dereference(result);
        return result;
    }

    @Override
    public int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        int[] restriction = new int[mdd.numberOfVariables()];
        for (int var = 0; var < restriction.length; var++) {
            if (restrictedVariables.get(var)) {
                restriction[var] = restrictedVariableValues.get(var) ? TRUE : FALSE;
            } else {
                restriction[var] = -1;
            }
        }
        return mdd.restrict(function, restriction);
    }

    @Override
    public int ifThenElse(int ifFunction, int thenFunction, int elseFunction) {
        return mdd.ifThenElse(ifFunction, thenFunction, elseFunction);
    }

    @Override
    public int simplify(int function, int domain) {
        return mdd.simplify(function, domain);
    }

    @Override
    public String statistics() {
        return mdd.statistics();
    }

    @Override
    public void invalidateCache() {
        mdd.invalidateCache();
    }

    @Override
    public boolean check() {
        return mdd.check();
    }

    @Override
    public String treeToString(int function) {
        return mdd.table().treeToString(function);
    }
}
