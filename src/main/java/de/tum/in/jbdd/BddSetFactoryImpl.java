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

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("ObjectEquality")
final class BddSetFactoryImpl extends BddGcReferenceManager<BddSetFactoryImpl.BddSetImpl> implements BddSetFactory {
    private final BddSet empty;
    private final BddSet universe;

    public BddSetFactoryImpl() {
        this(64);
    }

    public BddSetFactoryImpl(int variables) {
        super(BddFactory.buildBdd());
        bdd.createVariables(variables);
        assert bdd.numberOfVariables() == variables;

        empty = make(bdd.falseFunction());
        universe = make(bdd.trueFunction());
    }

    private BddSetImpl make(int node) {
        return protect(new BddSetImpl(this, node));
    }

    private int variableFunction(int variable) {
        int variables = bdd.numberOfVariables();
        if (variable >= variables) {
            bdd.createVariables(variable - variables + 1);
        }
        return bdd.variableFunction(variable);
    }

    private int createBddUpdateHelper(BitSet set, int variable, int node) {
        int variableNode = variableFunction(variable);
        return bdd.and(node, set.get(variable) ? variableNode : bdd.not(variableNode));
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
        return make(booleanConstant ? bdd.trueFunction() : bdd.falseFunction());
    }

    @Override
    public BddSet of(BitSet valuation, BitSet support) {
        int node = bdd.trueFunction();
        for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
            node = createBddUpdateHelper(valuation, i, node);
        }
        return make(node);
    }

    @Override
    public Map<String, Object> statistics() {
        return bdd.statistics();
    }

    @Override
    public BddSet var(int variable) {
        return make(variableFunction(variable));
    }

    @SuppressWarnings("MethodOnlyUsedFromInnerClass")
    private int function(BddSet set) {
        assert (set instanceof BddSetImpl) && (this == ((BddSetImpl) set).factory); // NOPMD
        // assert bdd.nodeReferenceCount(node) > 0 || bdd.nodeReferenceCount(node) == -1;
        return ((BddSetImpl) set).function;
    }

    @Override
    public String toString() {
        return String.format("F{%s}", bdd);
    }

    static final class BddSetImpl implements BddSet, BddContainer {
        private final BddSetFactoryImpl factory;
        private final int function;

        @Nullable
        private BitSet supportCache;

        public BddSetImpl(BddSetFactoryImpl factory, int function) {
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
            return factory.bdd.evaluate(function, o);
        }

        @Override
        public boolean containsAll(BddSet collection) {
            assert collection instanceof BddSetImpl;
            BddSetImpl other = (BddSetImpl) collection;
            //noinspection ObjectEquality
            assert factory == other.factory; // NOPMD
            return factory.bdd.implies(other.function, function);
        }

        @Override
        public Optional<BitSet> element() {
            return isEmpty() ? Optional.empty() : Optional.of(factory.bdd.satisfyingAssignment(this.function));
        }

        @Override
        public BddSet union(BddSet other) {
            return make(factory.bdd.or(function, factory.function(other)));
        }

        @Override
        public boolean intersects(BddSet other) {
            return factory.bdd.intersects(function, factory.function(other));
        }

        @Override
        public BddSet intersection(BddSet other) {
            return make(factory.bdd.and(function, factory.function(other)));
        }

        @Override
        public BddSet exists(BitSet quantifiedVariables) {
            return make(factory.bdd.exists(function, quantifiedVariables));
        }

        @Override
        public BddSet symmetricDifference(BddSet other) {
            return make(factory.bdd.xor(function, factory.function(other)));
        }

        @Override
        public BddSet difference(BddSet other) {
            return make(factory.bdd.andNot(function, factory.function(other)));
        }

        @Override
        public BddSet relabelVariables(IntUnaryOperator mapping) {
            BitSet support = getSupport();
            int[] substitutions = new int[support.length()];
            Arrays.fill(substitutions, -1);

            for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                int j = mapping.applyAsInt(i);

                if (j == -1) {
                    substitutions[i] = -1;
                } else if (j >= 0) {
                    substitutions[i] = factory.variableFunction(j);
                } else {
                    throw new IllegalArgumentException(String.format("Invalid mapping %s -> %s", i, j));
                }
            }

            return make(factory.bdd.compose(function, substitutions));
        }

        @Override
        public BddSet replaceVariables(IntFunction<BddSet> mapping) {
            BitSet support = getSupport();
            int[] substitutions = new int[support.length()];
            Arrays.fill(substitutions, -1);
            for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                substitutions[i] = factory.function(mapping.apply(i));
            }
            return make(factory.bdd.compose(function, substitutions));
        }

        private BitSet getSupport() {
            if (supportCache == null) {
                supportCache = factory.bdd.support(function);
            }
            return supportCache;
        }

        @Override
        public BitSet support() {
            return BitSets.copyOf(getSupport());
        }

        @Override
        public Iterator<BitSet> iterator(BitSet support) {
            return factory.bdd.solutionIterator(function, support);
        }

        @Override
        public BigInteger size(BitSet support) {
            return factory.bdd.countSatisfyingAssignments(function, support);
        }

        @Override
        public void forEach(BitSet support, Consumer<? super BitSet> consumer) {
            factory.bdd.forEachSolution(function, support, consumer);
        }

        @Override
        public BddSet complement() {
            return make(factory.bdd.not(function));
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
