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

import java.math.BigInteger;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

class TestBddImpl implements TestBdd {
    private final BddImpl delegate;

    public TestBddImpl(BddImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean check() {
        return delegate.check();
    }

    @Override
    public String treeToString(int function) {
        return delegate.table().treeToString(function);
    }

    @Override
    public int highOf(int function) {
        return delegate.highOf(function);
    }

    @Override
    public int lowOf(int function) {
        return delegate.lowOf(function);
    }

    @Override
    public int numberOfVariables() {
        return delegate.numberOfVariables();
    }

    @Override
    public int variableFunction(int variableNumber) {
        return delegate.variableFunction(variableNumber);
    }

    @Override
    public int createVariable() {
        return delegate.createVariable();
    }

    @Override
    public int[] createVariables(int count) {
        return delegate.createVariables(count);
    }

    @Override
    public boolean isVariable(int function) {
        return delegate.isVariable(function);
    }

    @Override
    public boolean isVariableNegated(int function) {
        return delegate.isVariableNegated(function);
    }

    @Override
    public boolean isVariableOrNegated(int function) {
        return delegate.isVariableOrNegated(function);
    }

    @Override
    public boolean evaluate(int function, boolean[] assignment) {
        return delegate.evaluate(function, assignment);
    }

    @Override
    public boolean evaluate(int function, BitSet assignment) {
        return delegate.evaluate(function, assignment);
    }

    @Override
    public BitSet satisfyingAssignment(int function) {
        return delegate.satisfyingAssignment(function);
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function) {
        return delegate.solutionIterator(function);
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function, BitSet support) {
        return delegate.solutionIterator(function, support);
    }

    @Override
    public void forEachPath(int function, BiConsumer<BitSet, BitSet> action) {
        delegate.forEachPath(function, action);
    }

    @Override
    public void forEachPath(int function, BitSet relevantSet, BiConsumer<BitSet, BitSet> action) {
        delegate.forEachPath(function, relevantSet, action);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        return delegate.countSatisfyingAssignments(function);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        return delegate.countSatisfyingAssignments(function, support);
    }

    @Override
    public int compose(int function, int[] variableMapping) {
        return delegate.compose(function, variableMapping);
    }

    @Override
    public int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        return delegate.restrict(function, restrictedVariables, restrictedVariableValues);
    }

    @Override
    public int conjunction(int... variables) {
        return delegate.conjunction(variables);
    }

    @Override
    public int conjunction(BitSet variables) {
        return delegate.conjunction(variables);
    }

    @Override
    public int disjunction(int... variables) {
        return delegate.disjunction(variables);
    }

    @Override
    public int disjunction(BitSet variables) {
        return delegate.disjunction(variables);
    }

    @Override
    public int and(int function1, int function2) {
        return delegate.and(function1, function2);
    }

    @Override
    public int xor(int function1, int function2) {
        return delegate.xor(function1, function2);
    }

    @Override
    public int exists(int function, BitSet quantifiedVariables) {
        return delegate.exists(function, quantifiedVariables);
    }

    @Override
    public int ifThenElse(int ifFunction, int thenFunction, int elseFunction) {
        return delegate.ifThenElse(ifFunction, thenFunction, elseFunction);
    }

    @Override
    public boolean implies(int function1, int function2) {
        return delegate.implies(function1, function2);
    }

    @Override
    public <V> MtBdd<V> createMtBdd(Class<V> clazz) {
        return delegate.createMtBdd(clazz);
    }

    @Override
    public <V> MtBdd<List<V>> intersect(List<MtBdd<? extends V>> mtBdds, Class<V> clazz) {
        return delegate.intersect(mtBdds, clazz);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public String statistics() {
        return delegate.statistics();
    }

    @Override
    public void invalidateCache() {
        delegate.invalidateCache();
    }

    @Override
    public boolean isValidNonConstantFunction(int function) {
        return delegate.isValidNonConstantFunction(function);
    }

    @Override
    public int reference(int function) {
        return delegate.reference(function);
    }

    @Override
    public int dereference(int function) {
        return delegate.dereference(function);
    }

    @Override
    public int updateWith(int result, int input) {
        return delegate.updateWith(result, input);
    }

    @Override
    public int consume(int result, int input1, int input2) {
        return delegate.consume(result, input1, input2);
    }

    @Override
    public int nodeReferenceCount(int node) {
        return delegate.nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        return delegate.isSaturatedNode(node);
    }

    @Override
    public void forEachSupport(int function, IntConsumer action) {
        delegate.forEachSupport(function, action);
    }

    @Override
    public void forEachSupportFiltered(int function, BitSet filter, IntConsumer action) {
        delegate.forEachSupportFiltered(function, filter, action);
    }

    @Override
    public int referencedNodeCount() {
        return delegate.referencedNodeCount();
    }

    @Override
    public int nodeCount() {
        return delegate.nodeCount();
    }

    @Override
    public int trueFunction() {
        return delegate.trueFunction();
    }

    @Override
    public int falseFunction() {
        return delegate.falseFunction();
    }

    @Override
    public boolean isValidFunction(int function) {
        return delegate.isValidFunction(function);
    }

    @Override
    public int nodeFor(int function) {
        return delegate.nodeFor(function);
    }

    @Override
    public int decisionVariable(int function) {
        return delegate.decisionVariable(function);
    }

    @Override
    public int placeholder() {
        return delegate.placeholder();
    }

    @Override
    public boolean isConstant(int function) {
        return delegate.isConstant(function);
    }

    @Override
    public int andNot(int function1, int function2) {
        return delegate.andNot(function1, function2);
    }

    @Override
    public int equivalence(int function1, int function2) {
        return delegate.equivalence(function1, function2);
    }

    @Override
    public int forall(int function, BitSet quantifiedVariables) {
        return delegate.forall(function, quantifiedVariables);
    }

    @Override
    public int implication(int function1, int function2) {
        return delegate.implication(function1, function2);
    }

    @Override
    public int not(int function) {
        return delegate.not(function);
    }

    @Override
    public int notAnd(int function1, int function2) {
        return delegate.notAnd(function1, function2);
    }

    @Override
    public int or(int function1, int function2) {
        return delegate.or(function1, function2);
    }

    @Override
    public void forEachSolution(int function, Consumer<? super BitSet> action) {
        delegate.forEachSolution(function, action);
    }

    @Override
    public void forEachSolution(int function, BitSet support, Consumer<? super BitSet> action) {
        delegate.forEachSolution(function, support, action);
    }

    @Override
    public void forEachPath(int function, Consumer<? super BitSet> action) {
        delegate.forEachPath(function, action);
    }

    @Override
    public void forEachPath(int function, BitSet relevantSet, Consumer<? super BitSet> action) {
        delegate.forEachPath(function, relevantSet, action);
    }

    @Override
    public void dereference(int... functions) {
        delegate.dereference(functions);
    }

    @Override
    public BitSet support(int function) {
        return delegate.support(function);
    }

    @Override
    public BitSet supportTo(int function, BitSet bitSet) {
        return delegate.supportTo(function, bitSet);
    }

    @Override
    public BitSet supportFiltered(int function, BitSet filter) {
        return delegate.supportFiltered(function, filter);
    }

    @Override
    public boolean nodeIsReferenced(int node) {
        return delegate.nodeIsReferenced(node);
    }
}
