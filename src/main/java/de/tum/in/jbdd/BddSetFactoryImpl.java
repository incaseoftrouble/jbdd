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
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
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

    private int createBddUpdateHelper(BitSet set, int variable, int node) {
        int variableNode = variableFunction(variable);
        return dd.and(node, set.get(variable) ? variableNode : dd.not(variableNode));
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
    public BddSet of(BitSet valuation, BitSet support) {
        int node = dd.trueFunction();
        for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
            node = createBddUpdateHelper(valuation, i, node);
        }
        return make(node);
    }

    @Override
    public Map<String, Object> statistics() {
        return dd.statistics();
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
        public BddSet replaceVariables(IntFunction<BddSet> mapping) {
            BitSet support = support();
            int[] substitutions = new int[support.length()];
            // As relabelVariables: a gap in the support means "leave this variable alone", which compose
            // spells placeholder() - not -1, which it would read as a replacement and protect as a node.
            Arrays.fill(substitutions, factory.dd.placeholder());
            for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                substitutions[i] = factory.functionOf(mapping.apply(i));
            }
            return make(factory.dd.compose(function, substitutions));
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
