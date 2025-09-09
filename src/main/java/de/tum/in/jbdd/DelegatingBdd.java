/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2018-2023 Tobias Meggendorfer.
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
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

public class DelegatingBdd implements Bdd {
    private final Bdd delegate;

    public DelegatingBdd(Bdd delegate) {
        this.delegate = delegate;
    }

    protected void onEnter(String name) {
        // Empty
    }

    protected void onExit() {
        // Empty
    }

    private int onExit(int value) {
        onExit();
        return value;
    }

    private boolean onExit(boolean value) {
        onExit();
        return value;
    }

    private <V> V onExit(V value) {
        onExit();
        return value;
    }

    @Override
    public int trueFunction() {
        onEnter("true");
        return onExit(delegate.trueFunction());
    }

    @Override
    public int falseFunction() {
        onEnter("false");
        return onExit(delegate.falseFunction());
    }

    @Override
    public int numberOfVariables() {
        onEnter("numberOfVariables");
        return onExit(delegate.numberOfVariables());
    }

    @Override
    public int highOf(int function) {
        onEnter("highOf");
        return onExit(delegate.highOf(function));
    }

    @Override
    public int lowOf(int function) {
        onEnter("lowOf");
        return onExit(delegate.lowOf(function));
    }

    @Override
    public int decisionVariable(int function) {
        onEnter("topVariable");
        return onExit(delegate.decisionVariable(function));
    }

    @Override
    public int variableFunction(int variableNumber) {
        onEnter("variableFunction");
        return onExit(delegate.variableFunction(variableNumber));
    }

    @Override
    public int createVariable() {
        onEnter("createVariable");
        return onExit(delegate.createVariable());
    }

    @Override
    public int[] createVariables(int count) {
        onEnter("createVariables");
        return onExit(delegate.createVariables(count));
    }

    @Override
    public boolean isConstant(int function) {
        onEnter("isConstant");
        return onExit(delegate.isConstant(function));
    }

    @Override
    public boolean isVariable(int function) {
        onEnter("isVariable");
        return onExit(delegate.isVariable(function));
    }

    @Override
    public boolean isVariableNegated(int function) {
        onEnter("isVariableNegated");
        return onExit(delegate.isVariableNegated(function));
    }

    @Override
    public boolean isVariableOrNegated(int function) {
        onEnter("isVariableOrNegated");
        return onExit(delegate.isVariableOrNegated(function));
    }

    @Override
    public int reference(int function) {
        onEnter("reference");
        return onExit(delegate.reference(function));
    }

    @Override
    public int dereference(int function) {
        onEnter("dereference");
        return onExit(delegate.dereference(function));
    }

    @Override
    public void dereference(int... functions) {
        onEnter("dereference");
        delegate.dereference(functions);
        onExit();
    }

    @Override
    public int updateWith(int result, int input) {
        onEnter("updateWith");
        return onExit(delegate.updateWith(result, input));
    }

    @Override
    public int consume(int result, int input1, int input2) {
        onEnter("consume");
        return onExit(delegate.consume(result, input1, input2));
    }

    @Override
    public boolean evaluate(int function, boolean[] assignment) {
        onEnter("evaluate");
        return onExit(delegate.evaluate(function, assignment));
    }

    @Override
    public boolean evaluate(int function, BitSet assignment) {
        onEnter("evaluate");
        return onExit(delegate.evaluate(function, assignment));
    }

    @Override
    public BitSet satisfyingAssignment(int function) {
        onEnter("satisfyingAssignment");
        return onExit(delegate.satisfyingAssignment(function));
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function) {
        onEnter("countSatisfyingAssignments");
        return onExit(delegate.countSatisfyingAssignments(function));
    }

    @Override
    public BigInteger countSatisfyingAssignments(int function, BitSet support) {
        onEnter("countSatisfyingAssignments");
        return onExit(delegate.countSatisfyingAssignments(function, support));
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function) {
        onEnter("solutionIterator");
        return onExit(delegate.solutionIterator(function));
    }

    @Override
    public Iterator<BitSet> solutionIterator(int function, BitSet support) {
        onEnter("solutionIterator");
        return onExit(delegate.solutionIterator(function, support));
    }

    @Override
    public void forEachSolution(int function, Consumer<? super BitSet> action) {
        onEnter("forEachSolution");
        delegate.forEachSolution(function, action);
        onExit();
    }

    @Override
    public void forEachSolution(int function, BitSet support, Consumer<? super BitSet> action) {
        onEnter("forEachSolution");
        delegate.forEachSolution(function, support, action);
        onExit();
    }

    @Override
    public Iterator<BinaryPath> pathIterator(int function) {
        onEnter("pathIterator");
        return onExit(delegate.pathIterator(function));
    }

    @Override
    public void forEachPath(int function, Consumer<? super BinaryPath> action) {
        onEnter("forEachPath");
        delegate.forEachPath(function, action);
        onExit();
    }

    @Override
    public void forEachPartialPath(int function, BitSet relevantSet, Consumer<? super BinaryPath> action) {
        onEnter("forEachPartialPath");
        delegate.forEachPartialPath(function, relevantSet, action);
        onExit();
    }

    @Override
    public boolean anyPathMatches(int function, Predicate<? super BinaryPath> predicate) {
        onEnter("anyPathMatches");
        return onExit(delegate.anyPathMatches(function, predicate));
    }

    @Override
    public BitSet support(int function) {
        onEnter("support");
        return onExit(delegate.support(function));
    }

    @Override
    public BitSet supportTo(int function, BitSet bitSet) {
        onEnter("supportTo");
        return onExit(delegate.supportTo(function, bitSet));
    }

    @Override
    public void forEachSupportVariable(int function, IntConsumer action) {
        onEnter("forEachSupport");
        delegate.forEachSupportVariable(function, action);
        onExit();
    }

    @Override
    public BitSet supportFiltered(int function, BitSet filter) {
        onEnter("supportFiltered");
        return onExit(delegate.supportTo(function, filter));
    }

    @Override
    public void forEachSupportFiltered(int function, BitSet filter, IntConsumer action) {
        onEnter("forEachSupportFiltered");
        delegate.forEachSupportFiltered(function, filter, action);
        onExit();
    }

    @Override
    public int conjunction(int... variables) {
        onEnter("conjunction");
        return onExit(delegate.conjunction(variables));
    }

    @Override
    public int conjunction(BitSet variables) {
        onEnter("conjunction");
        return onExit(delegate.conjunction(variables));
    }

    @Override
    public int disjunction(int... variables) {
        onEnter("disjunction");
        return onExit(delegate.disjunction(variables));
    }

    @Override
    public int disjunction(BitSet variables) {
        onEnter("disjunction");
        return onExit(delegate.disjunction(variables));
    }

    @Override
    public int and(int function1, int function2) {
        onEnter("and");
        return onExit(delegate.and(function1, function2));
    }

    @Override
    public int andNot(int function1, int function2) {
        onEnter("andNot");
        return onExit(delegate.andNot(function1, function2));
    }

    @Override
    public int compose(int function, int[] variableMapping) {
        onEnter("compose");
        return onExit(delegate.compose(function, variableMapping));
    }

    @Override
    public int equivalence(int function1, int function2) {
        onEnter("equivalence");
        return onExit(delegate.equivalence(function1, function2));
    }

    @Override
    public int exists(int function, BitSet quantifiedVariables) {
        onEnter("exists");
        return onExit(delegate.exists(function, quantifiedVariables));
    }

    @Override
    public int forall(int function, BitSet quantifiedVariables) {
        onEnter("forall");
        return onExit(delegate.forall(function, quantifiedVariables));
    }

    @Override
    public int ifThenElse(int ifFunction, int thenFunction, int elseFunction) {
        onEnter("ifThenElse");
        return onExit(delegate.ifThenElse(ifFunction, thenFunction, elseFunction));
    }

    @Override
    public int simplify(int function, int domain) {
        onEnter("constrain");
        return onExit(delegate.simplify(function, domain));
    }

    @Override
    public int implication(int function1, int function2) {
        onEnter("implication");
        return onExit(delegate.implication(function1, function2));
    }

    @Override
    public boolean implies(int function1, int function2) {
        onEnter("implies");
        return onExit(delegate.implies(function1, function2));
    }

    @Override
    public boolean intersects(int function1, int function2) {
        onEnter("intersects");
        return onExit(delegate.intersects(function1, function2));
    }

    @Override
    public int not(int function) {
        onEnter("not");
        return onExit(delegate.not(function));
    }

    @Override
    public int notAnd(int function1, int function2) {
        onEnter("notAnd");
        return onExit(delegate.notAnd(function1, function2));
    }

    @Override
    public int or(int function1, int function2) {
        onEnter("or");
        return onExit(delegate.or(function1, function2));
    }

    @Override
    public int restrict(int function, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        onEnter("restrict");
        return onExit(delegate.restrict(function, restrictedVariables, restrictedVariableValues));
    }

    @Override
    public int xor(int function1, int function2) {
        onEnter("xor");
        return onExit(delegate.xor(function1, function2));
    }

    @Override
    public String statistics() {
        onEnter("statistics");
        return onExit(delegate.statistics());
    }

    @Override
    public int placeholder() {
        onEnter("placeholder");
        return onExit(delegate.placeholder());
    }
}
