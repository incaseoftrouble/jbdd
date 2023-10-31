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

import com.google.common.collect.Iterators;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.function.BiConsumer;

class MddAsBdd implements Bdd {
    private final Mdd mdd;
    private static final int TRUE = 1;
    private static final int FALSE = 0;

    public MddAsBdd(BddConfiguration configuration) {
        this.mdd = new MddImpl(configuration);
    }

    @Override
    public int high(int node) {
        return mdd.follow(node, TRUE);
    }

    @Override
    public int low(int node) {
        return mdd.follow(node, FALSE);
    }

    @Override
    public int variableNode(int variableNumber) {
        return mdd.makeNode(variableNumber, new boolean[] {false, true});
    }

    @Override
    public int createVariable() {
        int variable = mdd.declareVariable(2);
        int node = mdd.saturateNode(mdd.makeNode(variable, new boolean[] {false, true}));
        mdd.saturateNode(mdd.makeNode(variable, new boolean[] {true, false}));
        return node;
    }

    @Override
    public boolean evaluate(int node, boolean[] assignment) {
        int[] values = new int[assignment.length];
        Arrays.setAll(values, i -> assignment[i] ? TRUE : FALSE);
        return mdd.evaluate(node, values);
    }

    @Override
    public boolean evaluate(int node, BitSet assignment) {
        int[] values = new int[mdd.numberOfVariables()];
        Arrays.setAll(values, i -> assignment.get(i) ? TRUE : FALSE);
        return mdd.evaluate(node, values);
    }

    @Override
    public BitSet getSatisfyingAssignment(int node) {
        int[] values = mdd.getSatisfyingAssignment(node);
        BitSet set = new BitSet(mdd.numberOfVariables());
        for (int i = 0; i < values.length; i++) {
            if (values[i] == TRUE) {
                set.set(i);
            }
        }
        return set;
    }

    @Override
    public int ifThenElse(int ifNode, int thenNode, int elseNode) {
        return mdd.ifThenElse(ifNode, thenNode, elseNode);
    }

    @Override
    public int implication(int node1, int node2) {
        return mdd.implication(node1, node2);
    }

    @Override
    public boolean implies(int node1, int node2) {
        return mdd.implies(node1, node2);
    }

    @Override
    public int compose(int node, int[] variableMapping) {
        int[] constantReplacements = new int[mdd.numberOfVariables()];
        Arrays.fill(constantReplacements, -1);
        BitSet replaced = new BitSet(mdd.numberOfVariables());
        for (int var = 0; var < variableMapping.length; var++) {
            int replacement = variableMapping[var];
            if (replacement == mdd.placeholder() || replacement == variableNode(var)) {
                continue;
            }
            if (replacement == trueNode()) {
                constantReplacements[var] = TRUE;
            } else if (replacement == falseNode()) {
                constantReplacements[var] = FALSE;
            } else {
                replaced.set(var);
            }
        }
        int base = mdd.reference(mdd.restrict(node, constantReplacements));

        if (replaced.isEmpty()) {
            mdd.dereference(base);
            return base;
        }

        var iterator = new PowerIteratorBitSet(replaced);
        int result = falseNode();
        while (iterator.hasNext()) {
            var assigment = iterator.next();
            int restrict = mdd.reference(restrict(base, replaced, assigment));

            int assigmentNode = trueNode();
            for (int var = replaced.nextSetBit(0); var >= 0; var = replaced.nextSetBit(var + 1)) {
                int replacement = variableMapping[var];
                int varNode = assigment.get(var) ? replacement : not(replacement);
                assigmentNode = mdd.updateWith(mdd.and(assigmentNode, varNode), assigmentNode);
            }

            int value = mdd.consume(mdd.and(assigmentNode, restrict), assigmentNode, restrict);
            result = mdd.consume(mdd.or(result, value), result, value);
        }
        mdd.dereference(result);
        return result;
    }

    @Override
    public int restrict(int node, BitSet restrictedVariables, BitSet restrictedVariableValues) {
        int[] restriction = new int[mdd.numberOfVariables()];
        for (int var = 0; var < restriction.length; var++) {
            if (restrictedVariables.get(var)) {
                restriction[var] = restrictedVariableValues.get(var) ? TRUE : FALSE;
            } else {
                restriction[var] = -1;
            }
        }
        return mdd.restrict(node, restriction);
    }

    @Override
    public Iterator<BitSet> solutionIterator(int node) {
        BitSet set = new BitSet(mdd.numberOfVariables());
        return Iterators.transform(mdd.solutionIterator(node), a -> {
            for (int i = 0; i < a.length; i++) {
                assert a[i] == TRUE || a[i] == FALSE;
                set.set(i, a[i] == TRUE);
            }
            return set;
        });
    }

    @Override
    public Iterator<BitSet> solutionIterator(int node, BitSet support) {
        BitSet set = new BitSet(mdd.numberOfVariables());
        return Iterators.transform(mdd.solutionIterator(node, support), a -> {
            for (int i = 0; i < a.length; i++) {
                assert a[i] == TRUE || a[i] == FALSE;
                set.set(i, a[i] == TRUE);
            }
            return set;
        });
    }

    @Override
    public void forEachPath(int node, BiConsumer<BitSet, BitSet> action) {
        BitSet support = new BitSet(mdd.numberOfVariables());
        BitSet path = new BitSet(mdd.numberOfVariables());
        mdd.forEachPath(node, a -> {
            support.clear();
            path.clear();
            for (int var = 0; var < a.length; var++) {
                assert a[var] == -1 || a[var] == TRUE || a[var] == FALSE;
                if (a[var] == -1) {
                    support.clear(var);
                } else {
                    support.set(var);
                    path.set(var, a[var] == TRUE);
                }
            }
            action.accept(path, support);
        });
    }

    @Override
    public int conjunction(BitSet variables) {
        int node = TRUE;
        for (int var = variables.nextSetBit(0); var >= 0; var = variables.nextSetBit(var + 1)) {
            node = mdd.and(node, variableNode(var));
        }
        return node;
    }

    @Override
    public int disjunction(BitSet variables) {
        int node = FALSE;
        for (int var = variables.nextSetBit(0); var >= 0; var = variables.nextSetBit(var + 1)) {
            node = mdd.or(node, variableNode(var));
        }
        return node;
    }

    @Override
    public int trueNode() {
        return mdd.trueNode();
    }

    @Override
    public int falseNode() {
        return mdd.falseNode();
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node) {
        return mdd.countSatisfyingAssignments(node);
    }

    @Override
    public BigInteger countSatisfyingAssignments(int node, BitSet support) {
        return mdd.countSatisfyingAssignments(node, support);
    }

    @Override
    public int and(int node1, int node2) {
        return mdd.and(node1, node2);
    }

    @Override
    public int equivalence(int node1, int node2) {
        return mdd.equivalence(node1, node2);
    }

    @Override
    public int exists(int node, BitSet quantifiedVariables) {
        return mdd.exists(node, quantifiedVariables);
    }

    @Override
    public int forall(int node, BitSet quantifiedVariables) {
        return mdd.forall(node, quantifiedVariables);
    }

    @Override
    public int not(int node) {
        return mdd.not(node);
    }

    @Override
    public int notAnd(int node1, int node2) {
        return mdd.notAnd(node1, node2);
    }

    @Override
    public int or(int node1, int node2) {
        return mdd.or(node1, node2);
    }

    @Override
    public int xor(int node1, int node2) {
        return mdd.xor(node1, node2);
    }

    @Override
    public int placeholder() {
        return mdd.placeholder();
    }

    @Override
    public boolean isLeaf(int node) {
        return mdd.isLeaf(node);
    }

    @Override
    public int variableOf(int node) {
        return mdd.variableOf(node);
    }

    @Override
    public int numberOfVariables() {
        return mdd.numberOfVariables();
    }

    @Override
    public int referenceCount(int node) {
        return mdd.referenceCount(node);
    }

    @Override
    public int saturateNode(int node) {
        return mdd.saturateNode(node);
    }

    @Override
    public boolean isNodeSaturated(int node) {
        return mdd.isNodeSaturated(node);
    }

    @Override
    public int reference(int node) {
        return mdd.reference(node);
    }

    @Override
    public int dereference(int node) {
        return mdd.dereference(node);
    }

    @Override
    public int referencedNodeCount() {
        return mdd.referencedNodeCount();
    }

    @Override
    public int activeNodeCount() {
        return mdd.activeNodeCount();
    }

    @Override
    public BitSet supportFilteredTo(int node, BitSet bitSet, BitSet filter) {
        return mdd.supportFilteredTo(node, bitSet, filter);
    }

    @Override
    public String statistics() {
        return mdd.statistics();
    }

    Mdd mdd() {
        return mdd;
    }
}
