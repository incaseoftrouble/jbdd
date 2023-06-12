package de.tum.in.jbdd;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import javax.annotation.Nullable;

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

        empty = make(bdd.falseNode());
        universe = make(bdd.trueNode());
    }

    @Override
    protected BddSetImpl construct(int node) {
        return new BddSetImpl(this, node);
    }

    private int variableNode(int variable) {
        int variables = bdd.numberOfVariables();
        if (variable >= variables) {
            bdd.createVariables(variable - variables + 1);
        }
        return bdd.variableNode(variable);
    }

    private int createBddUpdateHelper(BitSet set, int variable, int node) {
        int variableNode = variableNode(variable);
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
        return make(booleanConstant ? bdd.trueNode() : bdd.falseNode());
    }

    @Override
    public BddSet of(BitSet valuation, BitSet support) {
        int node = bdd.trueNode();
        for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
            node = createBddUpdateHelper(valuation, i, node);
        }
        return make(node);
    }

    @Override
    public String statistics() {
        return bdd.statistics();
    }

    @Override
    public BddSet var(int variable) {
        return make(variableNode(variable));
    }

    @SuppressWarnings("MethodOnlyUsedFromInnerClass")
    private int node(BddSet set) {
        assert (set instanceof BddSetImpl) && (this == ((BddSetImpl) set).factory);
        int node = ((BddSetImpl) set).node;
        assert bdd.referenceCount(node) > 0 || bdd.referenceCount(node) == -1;
        return node;
    }

    @Override
    public String toString() {
        return String.format("F{%s}", bdd);
    }

    static final class BddSetImpl implements BddSet, BddContainer {
        private final BddSetFactoryImpl factory;
        private final int node;

        @Nullable
        private BitSet supportCache;

        public BddSetImpl(BddSetFactoryImpl factory, int node) {
            this.factory = factory;
            this.node = node;
        }

        private BddSetImpl make(int node) {
            return node == this.node ? this : factory.make(node);
        }

        @Override
        public int node() {
            return node;
        }

        @Override
        public boolean isEmpty() {
            return this == factory.empty;
        }

        @Override
        public boolean isUniverse() {
            return this == factory.universe;
        }

        @Override
        public boolean contains(BitSet o) {
            return factory.bdd.evaluate(node, o);
        }

        @Override
        public boolean containsAll(BddSet collection) {
            assert collection instanceof BddSetImpl;
            BddSetImpl other = (BddSetImpl) collection;
            //noinspection ObjectEquality
            assert factory == other.factory; // NOPMD
            return factory.bdd.implies(other.node, node);
        }

        @Override
        public Optional<BitSet> element() {
            return isEmpty() ? Optional.empty() : Optional.of(factory.bdd.getSatisfyingAssignment(this.node));
        }

        @Override
        public BddSet union(BddSet other) {
            return make(factory.bdd.or(node, factory.node(other)));
        }

        @Override
        public BddSet intersection(BddSet other) {
            return make(factory.bdd.and(node, factory.node(other)));
        }

        @Override
        public BddSet exists(BitSet quantifiedVariables) {
            return make(factory.bdd.exists(node, quantifiedVariables));
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
                    substitutions[i] = factory.variableNode(j);
                } else {
                    throw new IllegalArgumentException(String.format("Invalid mapping %s -> %s", i, j));
                }
            }

            return make(factory.bdd.compose(node, substitutions));
        }

        @Override
        public BddSet replaceVariables(IntFunction<BddSet> mapping) {
            BitSet support = getSupport();
            int[] substitutions = new int[support.length()];
            Arrays.fill(substitutions, -1);
            for (int i = support.nextSetBit(0); i >= 0; i = support.nextSetBit(i + 1)) {
                substitutions[i] = factory.node(mapping.apply(i));
            }
            return make(factory.bdd.compose(node, substitutions));
        }

        private BitSet getSupport() {
            if (supportCache == null) {
                supportCache = factory.bdd.support(node);
            }
            return supportCache;
        }

        @Override
        public BitSet support() {
            return BitSets.copyOf(getSupport());
        }

        @Override
        public Iterator<BitSet> iterator(BitSet support) {
            return factory.bdd.solutionIterator(node, support);
        }

        @Override
        public BigInteger size(BitSet support) {
            return factory.bdd.countSatisfyingAssignments(node, support);
        }

        @Override
        public void forEach(BitSet support, Consumer<? super BitSet> consumer) {
            factory.bdd.forEachSolution(node, support, consumer);
        }

        @Override
        public BddSet complement() {
            return make(factory.bdd.not(node));
        }

        @Override
        public boolean equals(Object o) {
            assert (this == o) == (o instanceof BddSetImpl && this.node == ((BddSetImpl) o).node);
            assert !(o instanceof BddSetImpl) || (this.factory == ((BddSetImpl) o).factory); // NOPMD
            return this == o;
        }

        @Override
        public int hashCode() {
            return HashUtil.hash(node);
        }

        @Override
        public String toString() {
            return String.format("%d@[%s]", node, factory);
        }
    }
}
