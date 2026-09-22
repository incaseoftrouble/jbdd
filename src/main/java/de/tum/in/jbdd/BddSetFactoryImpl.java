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

import java.lang.ref.Reference;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("ObjectEquality")
final class BddSetFactoryImpl extends GcReferenceManager<BddSetFactoryImpl.BddSetImpl, BddImpl>
        implements BddSetFactory {
    private final BddSet empty;
    private final BddSet universe;

    BddSetFactoryImpl(BddImpl dd) {
        super(dd);
        empty = make(dd.falseFunction());
        universe = make(dd.trueFunction());
    }

    BddSetImpl make(int node) {
        return protect(new BddSetImpl(this, node));
    }

    private int variableFunction(int variable) {
        int variables = dd.numberOfVariables();
        if (variable >= variables) {
            dd.createVariables(variable - variables + 1);
        }
        return dd.variableFunction(variable);
    }

    @Override
    public BddSet empty() {
        return empty;
    }

    @Override
    public BddSet universe() {
        return universe;
    }

    @Override
    public BddSet of(boolean booleanConstant) {
        return make(booleanConstant ? dd.trueFunction() : dd.falseFunction());
    }

    @Override
    public BddSet of(Cube cube) {
        if (!cube.isEmpty()) {
            variableFunction(cube.support.length() - 1);
        }
        return make(dd.of(cube));
    }

    @Override
    public BddSet union(Iterable<Cube> cubes) {
        int result = dd.falseFunction();
        for (Cube cube : cubes) {
            if (!cube.isEmpty()) {
                variableFunction(cube.support.length() - 1);
            }
            // The cube is unreferenced, but or protects its operands and nothing allocates in between.
            result = dd.updateWith(dd.or(result, dd.of(cube)), result);
            if (result == dd.trueFunction()) {
                break;
            }
        }
        BddSet union = make(result);
        dd.dereference(result);
        return union;
    }

    @Override
    public BddSet union(BddSet... sets) {
        return make(dd.or(functionsOf(sets)));
    }

    @Override
    public BddSet intersection(BddSet... sets) {
        return make(dd.and(functionsOf(sets)));
    }

    private int[] functionsOf(BddSet[] sets) {
        int[] functions = new int[sets.length];
        for (int i = 0; i < sets.length; i++) {
            functions[i] = functionOf(sets[i]);
        }
        return functions;
    }

    @Override
    public BddSet ifThenElse(BddSet condition, BddSet then, BddSet otherwise) {
        return make(dd.ifThenElse(functionOf(condition), functionOf(then), functionOf(otherwise)));
    }

    @Override
    public BddSet.Quantifier registerExists(BitSet quantifiedVariables) {
        return new RegisteredQuantifier(this, dd.registerExists(quantifiedVariables));
    }

    @Override
    public BddSet.VariableReplacer registerReplaceVariables(BitSet replacedVariables, IntFunction<BddSet> mapping) {
        int[] substitutions = new int[replacedVariables.length()];
        BddSet[] replacements = new BddSet[substitutions.length];
        Arrays.fill(substitutions, dd.placeholder());
        try {
            BitSets.forEach(replacedVariables, i -> {
                replacements[i] = mapping.apply(i);
                substitutions[i] = functionOf(replacements[i]);
            });
            return replacer(dd.registerCompose(substitutions));
        } finally {
            // A later mapping.apply can collect what an earlier one returned unless its wrapper stays alive.
            Reference.reachabilityFence(replacements);
        }
    }

    @Override
    public BddSet.VariableReplacer registerRelabelVariables(BitSet relabeledVariables, IntUnaryOperator mapping) {
        int[] substitutions = new int[relabeledVariables.length()];
        Arrays.fill(substitutions, dd.placeholder());
        for (int i = relabeledVariables.nextSetBit(0); i >= 0; i = relabeledVariables.nextSetBit(i + 1)) {
            int relabeled = mapping.applyAsInt(i);
            if (relabeled < 0) {
                throw new IllegalArgumentException(String.format("Invalid mapping %s -> %s", i, relabeled));
            }
            substitutions[i] = variableFunction(relabeled);
        }
        return replacer(dd.registerCompose(substitutions));
    }

    private BddSet.VariableReplacer replacer(RegisteredOperation.Unary operation) {
        return operation == RegisteredOperation.identity() // NOPMD - identity is the point of the check
                ? BddSet.VariableReplacer.identity()
                : new RegisteredReplacer(this, operation);
    }

    @Override
    public BddSet var(int variable) {
        return make(variableFunction(variable));
    }

    int functionOf(BddSet set) {
        assert (set instanceof BddSetImpl) && (this == ((BddSetImpl) set).factory); // NOPMD
        // assert bdd.nodeReferenceCount(node) > 0 || bdd.nodeReferenceCount(node) == -1;
        return ((BddSetImpl) set).function;
    }

    @Override
    public String toString() {
        return String.format("F{%s}", dd);
    }

    private static final class RegisteredQuantifier extends RegisteredOperation.Forwarding<RegisteredOperation.Unary>
            implements BddSet.Quantifier {
        private final BddSetFactoryImpl factory;

        RegisteredQuantifier(BddSetFactoryImpl factory, RegisteredOperation.Unary operation) {
            super(operation);
            this.factory = factory;
        }

        @Override
        public BddSet apply(BddSet set) {
            return factory.make(operation.applyAsInt(factory.functionOf(set)));
        }
    }

    private static final class RegisteredReplacer extends RegisteredOperation.Forwarding<RegisteredOperation.Unary>
            implements BddSet.VariableReplacer {
        private final BddSetFactoryImpl factory;

        RegisteredReplacer(BddSetFactoryImpl factory, RegisteredOperation.Unary operation) {
            super(operation);
            this.factory = factory;
        }

        @Override
        public BddSet apply(BddSet set) {
            return factory.make(operation.applyAsInt(factory.functionOf(set)));
        }
    }

    static final class BddSetImpl implements BddSet, DdContainer {
        private final BddSetFactoryImpl factory;
        private final int function;

        @Nullable
        private BitSet supportCache;

        BddSetImpl(BddSetFactoryImpl factory, int function) {
            this.factory = factory;
            this.function = function;
        }

        private BddSetImpl make(int node) {
            return node == this.function ? this : factory.make(node);
        }

        @Override
        public int function() {
            return function;
        }

        @Override
        public BddSetFactory factory() {
            return factory;
        }

        @Override
        public boolean isEmpty() {
            return this == factory.empty; // NOPMD
        }

        @Override
        public boolean isUniverse() {
            return this == factory.universe; // NOPMD
        }

        @Override
        public boolean contains(BitSet o) {
            return factory.dd.evaluate(function, o);
        }

        @Override
        public boolean containsAll(BddSet collection) {
            assert collection instanceof BddSetImpl;
            BddSetImpl other = (BddSetImpl) collection;
            //noinspection ObjectEquality
            assert factory == other.factory; // NOPMD
            return factory.dd.implies(other.function, function);
        }

        @Override
        public Optional<BitSet> element() {
            return isEmpty() ? Optional.empty() : Optional.of(factory.dd.satisfyingAssignment(this.function));
        }

        @Override
        public BddSet union(BddSet other) {
            return make(factory.dd.or(function, factory.functionOf(other)));
        }

        @Override
        public boolean intersects(BddSet other) {
            return factory.dd.intersects(function, factory.functionOf(other));
        }

        @Override
        public BddSet intersection(BddSet other) {
            return make(factory.dd.and(function, factory.functionOf(other)));
        }

        @Override
        public BddSet exists(BitSet quantifiedVariables) {
            return make(factory.dd.exists(function, quantifiedVariables));
        }

        @Override
        public BddSet forall(BitSet quantifiedVariables) {
            return make(factory.dd.forall(function, quantifiedVariables));
        }

        @Override
        public BddSet symmetricDifference(BddSet other) {
            return make(factory.dd.xor(function, factory.functionOf(other)));
        }

        @Override
        public BddSet difference(BddSet other) {
            return make(factory.dd.andNot(function, factory.functionOf(other)));
        }

        @Override
        public BddSet relabelVariables(IntUnaryOperator mapping) {
            BitSet support = support();
            int[] substitutions = new int[support.length()];
            Arrays.fill(substitutions, factory.dd.placeholder());
            for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                int j = mapping.applyAsInt(i);
                if (j < 0) {
                    throw new IllegalArgumentException(String.format("Invalid mapping %s -> %s", i, j));
                }
                substitutions[i] = factory.variableFunction(j);
            }
            return make(factory.dd.compose(function, substitutions));
        }

        @Override
        public BddSet restrict(Cube restriction) {
            return make(factory.dd.restrict(function, restriction));
        }

        @Override
        public BddSet constrain(BddSet domain) {
            return make(factory.dd.constrain(function, factory.functionOf(domain)));
        }

        @Override
        public BddSet simplify(BddSet domain) {
            return make(factory.dd.simplify(function, factory.functionOf(domain)));
        }

        @Override
        public BddSet replaceVariables(IntFunction<BddSet> mapping) {
            BitSet support = support();
            int[] substitutions = new int[support.length()];
            Arrays.fill(substitutions, factory.dd.placeholder());
            BddSet[] replacements = new BddSet[substitutions.length];
            try {
                for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                    replacements[i] = mapping.apply(i);
                    substitutions[i] = factory.functionOf(replacements[i]);
                }
                return make(factory.dd.compose(function, substitutions));
            } finally {
                // A later mapping.apply can collect what an earlier one returned unless its wrapper stays alive.
                Reference.reachabilityFence(replacements);
            }
        }

        @Override
        public List<Cube> implicants() {
            return factory.dd.implicants(function);
        }

        @Override
        public BddMap<BddSet> split(BitSet splitVariables, Values<BddSet> destination) {
            return ((BddMapFactoryImpl) destination.factory()).split(this, splitVariables, destination);
        }

        @Override
        public OptionalInt decisionVariable() {
            return factory.dd.isConstant(function)
                    ? OptionalInt.empty()
                    : OptionalInt.of(factory.dd.decisionVariable(function));
        }

        @Override
        public BddSet high() {
            Preconditions.checkState(!factory.dd.isConstant(function), "Constant set has no decision");
            return make(factory.dd.highOf(function));
        }

        @Override
        public BddSet low() {
            Preconditions.checkState(!factory.dd.isConstant(function), "Constant set has no decision");
            return make(factory.dd.lowOf(function));
        }

        @Override
        public BitSet support() {
            if (supportCache == null) {
                supportCache = factory.dd.support(function);
            }
            assert supportCache.equals(factory.dd.support(function));
            return supportCache; // Deliberately not returning a copy for performance
        }

        @Override
        public BitSet supportAt(BitSet valuation) {
            return factory.dd.supportAt(function, valuation);
        }

        @Override
        public Cursor<BitSet> cursor(BitSet support) {
            return factory.dd.solutionCursor(function, support);
        }

        @Override
        public BigInteger size(BitSet support) {
            return factory.dd.countSatisfyingAssignments(function, support);
        }

        @Override
        public void forEach(BitSet support, Consumer<? super BitSet> consumer) {
            factory.dd.forEachSolution(function, support, consumer);
        }

        @Override
        public void forEachPath(Consumer<? super Cube> action) {
            factory.dd.forEachPath(function, action);
        }

        @Override
        public boolean anyPathMatches(Predicate<? super Cube> predicate) {
            return factory.dd.anyPathMatches(function, predicate);
        }

        @Override
        public BddSet complement() {
            return make(factory.dd.not(function));
        }

        @Override
        public boolean equals(Object o) {
            assert (this == o) == (o instanceof BddSetImpl && this.function == ((BddSetImpl) o).function);
            assert !(o instanceof BddSetImpl) || (this.factory == ((BddSetImpl) o).factory);
            return this == o;
        }

        @Override
        public int hashCode() {
            return function;
        }

        @Override
        public String toString() {
            return String.format("%d@[%s]", function, factory);
        }
    }
}
