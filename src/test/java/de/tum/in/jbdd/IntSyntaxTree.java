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

import java.util.BitSet;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;

/**
 * Utility class to represent int-valued formulas.
 */
public final class IntSyntaxTree {
    private final IntSyntaxTreeNode root;
    private final BitSet containedVariables;
    private final int depth;

    private IntSyntaxTree(IntSyntaxTreeNode root) {
        this.root = root;
        this.containedVariables = new BitSet();
        root.gatherVariables(containedVariables);
        this.depth = root.depth();
    }

    static IntSyntaxTree constant(int value) {
        assert value >= 0;
        return new IntSyntaxTree(new Constant(value));
    }

    static IntSyntaxTree variableSplit(int variable, IntSyntaxTree trueChild, IntSyntaxTree falseChild) {
        return new IntSyntaxTree(new VariableSplit(variable, trueChild.root, falseChild.root));
    }

    static IntSyntaxTree apply(IntSyntaxTree left, IntSyntaxTree right, IntBinaryOperator op, String label) {
        return new IntSyntaxTree(new Apply(left.root, right.root, op, label));
    }

    static IntSyntaxTree map(IntSyntaxTree child, IntUnaryOperator op, String label) {
        return new IntSyntaxTree(new Map(child.root, op, label));
    }

    // condition is a real boolean SyntaxTree - every MTBDD operation taking a Bdd condition reuses that
    // machinery as-is rather than a new oracle language (MTBDD_THEORIES_NOTES.md §3).
    static IntSyntaxTree ifThenElse(SyntaxTree condition, IntSyntaxTree thenTree, IntSyntaxTree elseTree) {
        return new IntSyntaxTree(new IfThenElse(condition, thenTree.root, elseTree.root));
    }

    // update(f, condition, value) is exactly ifThenElse(condition, constant(value), f) - no dedicated node kind.
    static IntSyntaxTree update(SyntaxTree condition, int value, IntSyntaxTree fallback) {
        return ifThenElse(condition, constant(value), fallback);
    }

    int evaluate(boolean[] assignment) {
        return root.evaluate(assignment);
    }

    int evaluate(BitSet assignment) {
        return root.evaluate(assignment);
    }

    int depth() {
        return depth;
    }

    BitSet containedVariables() {
        return BitSets.copyOf(containedVariables);
    }

    @Override
    public String toString() {
        return root.toString();
    }

    private abstract static class IntSyntaxTreeNode {
        abstract int evaluate(boolean[] assignment);

        abstract int evaluate(BitSet assignment);

        abstract int depth();

        abstract void gatherVariables(BitSet set);
    }

    private static final class Constant extends IntSyntaxTreeNode {
        private final int value;

        Constant(int value) {
            this.value = value;
        }

        @Override
        int evaluate(boolean[] assignment) {
            return value;
        }

        @Override
        int evaluate(BitSet assignment) {
            return value;
        }

        @Override
        int depth() {
            return 1;
        }

        @Override
        void gatherVariables(BitSet set) {
            // No variables in this leaf.
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    private static final class VariableSplit extends IntSyntaxTreeNode {
        private final int depth;
        private final int variable;
        private final IntSyntaxTreeNode trueChild;
        private final IntSyntaxTreeNode falseChild;

        VariableSplit(int variable, IntSyntaxTreeNode trueChild, IntSyntaxTreeNode falseChild) {
            this.variable = variable;
            this.trueChild = trueChild;
            this.falseChild = falseChild;
            this.depth = Math.max(trueChild.depth(), falseChild.depth()) + 1;
        }

        @Override
        int evaluate(boolean[] assignment) {
            return assignment[variable] ? trueChild.evaluate(assignment) : falseChild.evaluate(assignment);
        }

        @Override
        int evaluate(BitSet assignment) {
            return assignment.get(variable) ? trueChild.evaluate(assignment) : falseChild.evaluate(assignment);
        }

        @Override
        int depth() {
            return depth;
        }

        @Override
        void gatherVariables(BitSet set) {
            set.set(variable);
            trueChild.gatherVariables(set);
            falseChild.gatherVariables(set);
        }

        @Override
        public String toString() {
            return String.format("VAR%d[%s,%s]", variable, trueChild, falseChild);
        }
    }

    private static final class Apply extends IntSyntaxTreeNode {
        private final int depth;
        private final IntSyntaxTreeNode left;
        private final IntSyntaxTreeNode right;
        private final IntBinaryOperator op;
        private final String label;

        Apply(IntSyntaxTreeNode left, IntSyntaxTreeNode right, IntBinaryOperator op, String label) {
            this.left = left;
            this.right = right;
            this.op = op;
            this.label = label;
            this.depth = Math.max(left.depth(), right.depth()) + 1;
        }

        @Override
        int evaluate(boolean[] assignment) {
            return op.applyAsInt(left.evaluate(assignment), right.evaluate(assignment));
        }

        @Override
        int evaluate(BitSet assignment) {
            return op.applyAsInt(left.evaluate(assignment), right.evaluate(assignment));
        }

        @Override
        int depth() {
            return depth;
        }

        @Override
        void gatherVariables(BitSet set) {
            left.gatherVariables(set);
            right.gatherVariables(set);
        }

        @Override
        public String toString() {
            return String.format("%s[%s,%s]", label, left, right);
        }
    }

    private static final class Map extends IntSyntaxTreeNode {
        private final int depth;
        private final IntSyntaxTreeNode child;
        private final IntUnaryOperator op;
        private final String label;

        Map(IntSyntaxTreeNode child, IntUnaryOperator op, String label) {
            this.child = child;
            this.op = op;
            this.label = label;
            this.depth = child.depth() + 1;
        }

        @Override
        int evaluate(boolean[] assignment) {
            return op.applyAsInt(child.evaluate(assignment));
        }

        @Override
        int evaluate(BitSet assignment) {
            return op.applyAsInt(child.evaluate(assignment));
        }

        @Override
        int depth() {
            return depth;
        }

        @Override
        void gatherVariables(BitSet set) {
            child.gatherVariables(set);
        }

        @Override
        public String toString() {
            return String.format("%s[%s]", label, child);
        }
    }

    private static final class IfThenElse extends IntSyntaxTreeNode {
        private final int depth;
        private final SyntaxTree condition;
        private final IntSyntaxTreeNode thenChild;
        private final IntSyntaxTreeNode elseChild;

        IfThenElse(SyntaxTree condition, IntSyntaxTreeNode thenChild, IntSyntaxTreeNode elseChild) {
            this.condition = condition;
            this.thenChild = thenChild;
            this.elseChild = elseChild;
            this.depth = Math.max(thenChild.depth(), elseChild.depth()) + 1;
        }

        @Override
        int evaluate(boolean[] assignment) {
            return condition.evaluate(assignment) ? thenChild.evaluate(assignment) : elseChild.evaluate(assignment);
        }

        @Override
        int evaluate(BitSet assignment) {
            return condition.evaluate(assignment) ? thenChild.evaluate(assignment) : elseChild.evaluate(assignment);
        }

        @Override
        int depth() {
            return depth;
        }

        @Override
        void gatherVariables(BitSet set) {
            condition.containedVariables().forEach(set::set);
            thenChild.gatherVariables(set);
            elseChild.gatherVariables(set);
        }

        @Override
        public String toString() {
            return String.format("ITE[%s,%s,%s]", condition, thenChild, elseChild);
        }
    }
}
