package de.tum.in.jbdd;

import java.math.BigInteger;
import java.util.BitSet;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
    public int trueNode() {
        onEnter("trueNode");
        return onExit(delegate.trueNode());
    }

    @Override
    public int falseNode() {
        onEnter("falseNode");
        return onExit(delegate.falseNode());
    }

    @Override
    public int numberOfVariables() {
        onEnter("numberOfVariables");
        return onExit(delegate.numberOfVariables());
    }

    @Override
    public int high(int node) {
        onEnter("high");
        return onExit(delegate.high(node));
    }

    @Override
    public int low(int node) {
        onEnter("low");
        return onExit(delegate.low(node));
    }

    @Override
    public int variable(int node) {
        onEnter("variable");
        return onExit(delegate.variable(node));
    }

    @Override
    public int variableNode(int variableNumber) {
        onEnter("variableNode");
        return onExit(delegate.variableNode(variableNumber));
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
    public boolean isLeaf(int node) {
        onEnter("isNodeRoot");
        return onExit(delegate.isLeaf(node));
    }

    @Override
    public boolean isVariable(int node) {
        onEnter("isVariable");
        return onExit(delegate.isVariable(node));
    }

    @Override
    public boolean isVariableNegated(int node) {
        onEnter("isVariableNegated");
        return onExit(delegate.isVariableNegated(node));
    }

    @Override
    public boolean isVariableOrNegated(int node) {
        onEnter("isVariableOrNegated");
        return onExit(delegate.isVariableOrNegated(node));
    }

    @Override
    public int reference(int node) {
        onEnter("reference");
        return onExit(delegate.reference(node));
    }

    @Override
    public int dereference(int node) {
        onEnter("dereference");
        return onExit(delegate.dereference(node));
    }

    @Override
    public void dereference(int... nodes) {
        onEnter("dereference");
        delegate.dereference(nodes);
        onExit();
    }

    @Override
    public int referenceCount(int node) {
        onEnter("getReferenceCount");
        return onExit(delegate.referenceCount(node));
    }

    @Override
    public boolean evaluate(int node, boolean[] assignment) {
        onEnter("evaluate");
        return onExit(delegate.evaluate(node, assignment));
    }

    @Override
    public boolean evaluate(int node, BitSet assignment) {
        onEnter("evaluate");
        return onExit(delegate.evaluate(node, assignment));
    }

    @Override
    public BitSet getSatisfyingAssignment(int node) {
        onEnter("getSatisfyingAssignment");
        return onExit(delegate.getSatisfyingAssignment(node));
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node) {
        onEnter("countSatisfyingAssignments");
        return onExit(delegate.countSatisfyingAssignments(node));
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node, BitSet support) {
        onEnter("countSatisfyingAssignments");
        return onExit(delegate.countSatisfyingAssignments(node, support));
    }

    @Override
    public Iterator<BitSet> solutionIterator(int node) {
        onEnter("solutionIterator");
        return onExit(delegate.solutionIterator(node));
    }

    @Override
    public Iterator<BitSet> solutionIterator(int node, BitSet support) {
        onEnter("solutionIterator");
        return onExit(delegate.solutionIterator(node, support));
    }

    @Override
    public void forEachSolution(int node, Consumer<? super BitSet> action) {
        onEnter("forEachSolution");
        delegate.forEachSolution(node, action);
        onExit();
    }

    @Override
    public void forEachSolution(int node, BitSet support, Consumer<? super BitSet> action) {
        onEnter("forEachSolution");
        delegate.forEachSolution(node, support, action);
        onExit();
    }

    @Override
    public void forEachPath(int node, Consumer<? super BitSet> action) {
        onEnter("forEachPath");
        delegate.forEachPath(node, action);
        onExit();
    }

    @Override
    public void forEachPath(int node, BiConsumer<BitSet, BitSet> action) {
        onEnter("forEachPath");
        delegate.forEachPath(node, action);
        onExit();
    }

    @Override
    public BitSet support(int node) {
        onEnter("support");
        return onExit(delegate.support(node));
    }

    @Override
    public BitSet supportTo(int node, BitSet bitSet) {
        onEnter("support");
        return onExit(delegate.supportTo(node, bitSet));
    }

    @Override
    public BitSet supportFiltered(int node, BitSet filter) {
        onEnter("supportFiltered");
        return onExit(delegate.supportTo(node, filter));
    }

    @Override
    public BitSet supportFilteredTo(int node, BitSet bitSet, BitSet filter) {
        onEnter("support");
        return onExit(delegate.supportFilteredTo(node, bitSet, filter));
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
    public int consume(int result, int inputNode1, int inputNode2) {
        onEnter("consume");
        return onExit(delegate.consume(result, inputNode1, inputNode2));
    }

    @Override
    public int updateWith(int result, int inputNode) {
        onEnter("updateWith");
        return onExit(delegate.updateWith(result, inputNode));
    }

    @Override
    public int and(int node1, int node2) {
        onEnter("and");
        return onExit(delegate.and(node1, node2));
    }

    @Override
    public int compose(int node, int[] variableMapping) {
        onEnter("compose");
        return onExit(delegate.compose(node, variableMapping));
    }

    @Override
    public int equivalence(int node1, int node2) {
        onEnter("equivalence");
        return onExit(delegate.equivalence(node1, node2));
    }

    @Override
    public int exists(int node, BitSet quantifiedVariables) {
        onEnter("exists");
        return onExit(delegate.exists(node, quantifiedVariables));
    }

    @Override
    public int ifThenElse(int ifNode, int thenNode, int elseNode) {
        onEnter("ifThenElse");
        return onExit(delegate.ifThenElse(ifNode, thenNode, elseNode));
    }

    @Override
    public int implication(int node1, int node2) {
        onEnter("implication");
        return onExit(delegate.implication(node1, node2));
    }

    @Override
    public boolean implies(int node1, int node2) {
        onEnter("implies");
        return onExit(delegate.implies(node1, node2));
    }

    @Override
    public int not(int node) {
        onEnter("not");
        return onExit(delegate.not(node));
    }

    @Override
    public int notAnd(int node1, int node2) {
        onEnter("notAnd");
        return onExit(delegate.notAnd(node1, node2));
    }

    @Override
    public int or(int node1, int node2) {
        onEnter("or");
        return onExit(delegate.or(node1, node2));
    }

    @Override
    public int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        onEnter("restrict");
        return onExit(delegate.restrict(node, restrictedVariables, restrictedVariableValues));
    }

    @Override
    public int xor(int node1, int node2) {
        onEnter("xor");
        return onExit(delegate.xor(node1, node2));
    }

    @Override
    public String statistics() {
        onEnter("statistics");
        return onExit(delegate.statistics());
    }

    @Override
    public int placeholder() {
        return delegate.placeholder();
    }
}
