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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

@SuppressWarnings({
    "MethodOnlyUsedFromInnerClass",
    "ObjectEquality",
    "OverlyStrongTypeCast",
    "PMD.CouplingBetweenObjects"
})
final class BddMapFactoryImpl<V> extends GcReferenceManager<BddMapFactoryImpl.BddMapImpl<V>, MtBddImpl>
        implements BddMapFactory<V> {
    private final BddSetFactoryImpl bddSets;
    private final ValueIndex<V> valueIndex;

    BddMapFactoryImpl(BddSetFactoryImpl bddSets) {
        this(bddSets.dd.mtbdd(), bddSets, new ValueIndex<>());
    }

    private BddMapFactoryImpl(MtBddImpl dd, BddSetFactoryImpl bddSets, ValueIndex<V> valueIndex) {
        super(dd);
        this.bddSets = bddSets;
        this.valueIndex = valueIndex;
        dd.registerObserver(valueIndex);
    }

    BddMapImpl<V> make(int node) {
        return protect(new BddMapImpl<>(this, node));
    }

    private int mtbddFunction(BddMap<?> map) {
        assert (map instanceof BddMapImpl<?>) && (this == ((BddMapImpl<?>) map).factory); // NOPMD
        return ((BddMapImpl<?>) map).function;
    }

    private <O> BddMapFactoryImpl<O> otherFactory(BddMap<O> map) {
        return otherFactory(map.factory());
    }

    private <O> BddMapFactoryImpl<O> otherFactory(BddMapFactory<O> other) {
        assert (other instanceof BddMapFactoryImpl<?>) && (dd == ((BddMapFactoryImpl<?>) other).dd); // NOPMD
        return (BddMapFactoryImpl<O>) other;
    }

    /** Mirrors {@code BddSetFactoryImpl}'s own private helper of the same name - auto-creates {@code
     * variable} (and everything below it) if it doesn't exist yet. */
    private int variableFunction(int variable) {
        Bdd bdd = dd.bdd();
        int variables = bdd.numberOfVariables();
        if (variable >= variables) {
            bdd.createVariables(variable - variables + 1);
        }
        return bdd.variableFunction(variable);
    }

    @Override
    public BddMap<V> of(V value) {
        return make(dd.of(valueIndex.indexOf(value)));
    }

    @Override
    public BddMap<V> ifThenElse(BddSet condition, BddMap<V> then, BddMap<V> otherwise) {
        return make(dd.ifThenElse(bddSets.bddFunction(condition), mtbddFunction(then), mtbddFunction(otherwise)));
    }

    @Override
    public BddMap<List<V>> cartesianProduct(List<BddMap<V>> maps, BddMapFactory<List<V>> destination) {
        BddMapFactoryImpl<List<V>> resultFactory = otherFactory(destination);

        int[] functions = new int[maps.size()];
        for (int i = 0; i < maps.size(); i++) {
            functions[i] = mtbddFunction(maps.get(i));
        }

        int result = dd.apply(functions, values -> {
            List<V> combined = new ArrayList<>(values.length);
            for (int rawValue : values) {
                combined.add(valueIndex.valueOf(rawValue));
            }
            return resultFactory.valueIndex.indexOf(List.copyOf(combined));
        });
        return resultFactory.make(result);
    }

    @Override
    public <O> BddMap.Relabeler<V, O> createRelabeling(Function<V, O> bijection) {
        BddMapFactoryImpl<O> resultFactory = new BddMapFactoryImpl<>(dd, bddSets, valueIndex.relabel(bijection));
        return new RelabelerImpl<>(this, resultFactory);
    }

    @Override
    public <O> BddMap<V> relabelInto(BddMap<O> map, Function<O, V> injection) {
        assert isInjective(map.values(), injection);
        return map.map(injection, this);
    }

    @Override
    public Map<String, Object> statistics() {
        return dd.statistics();
    }

    private static <A, B> boolean isInjective(Collection<A> values, Function<A, B> function) {
        Set<B> seen = new HashSet<>(values.size());
        for (A value : values) {
            if (!seen.add(function.apply(value))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("MtBddF{%s}", dd);
    }

    private static final class ValueIndex<V> implements NodeLifecycleObserver {
        private final Map<V, Integer> toIndex;
        private final List<@Nullable V> toValue;
        private final Deque<Integer> freeSlots;
        private int biggestAliveIndex = -1;

        ValueIndex() {
            this.toIndex = new HashMap<>();
            this.toValue = new ArrayList<>();
            this.freeSlots = new ArrayDeque<>();
        }

        private ValueIndex(Map<V, Integer> toIndex, List<@Nullable V> toValue, Deque<Integer> freeSlots) {
            this.toIndex = toIndex;
            this.toValue = toValue;
            this.freeSlots = freeSlots;
        }

        int indexOf(V value) {
            //noinspection ConstantValue
            assert value != null : "BddMap values must not be null";
            return toIndex.computeIfAbsent(value, v -> {
                Integer reused = freeSlots.poll();
                int index;
                if (reused == null) {
                    index = toValue.size();
                    toValue.add(value);
                    assert biggestAliveIndex < index;
                    assert toValue.subList(0, biggestAliveIndex + 1).stream().allMatch(Objects::nonNull);
                    biggestAliveIndex = index;
                } else {
                    index = reused;
                    assert toValue.get(index) == null;
                    toValue.set(index, value);
                    if (index > biggestAliveIndex) {
                        biggestAliveIndex = index;
                    }
                }
                return index;
            });
        }

        @Nullable
        Integer peek(V value) {
            return toIndex.get(value);
        }

        V valueOf(int index) {
            V value = toValue.get(index);
            assert value != null;
            return value;
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
            sweepValues(reclaimedValues);
        }

        void sweepValues(BitSet reclaimedValues) {
            int first = reclaimedValues.nextSetBit(0);
            if (first < 0) {
                return;
            }
            for (int index = first;
                    index >= 0 && index <= biggestAliveIndex;
                    index = reclaimedValues.nextSetBit(index + 1)) {
                V value = toValue.get(index);
                if (value != null) {
                    toValue.set(index, null);
                    toIndex.remove(value);
                    freeSlots.add(index);
                }
            }
            while (biggestAliveIndex >= 0 && toValue.get(biggestAliveIndex) == null) {
                toValue.remove(toValue.size() - 1);
                biggestAliveIndex -= 1;
            }
            assert toValue.size() == biggestAliveIndex + 1;
            freeSlots.removeIf(i -> i > biggestAliveIndex);
            assert new HashSet<>(freeSlots).size() == freeSlots.size();
            // freeSlots is exactly the set of indices whose slot is currently vacant.
            assert new HashSet<>(freeSlots)
                    .equals(IntStream.range(0, biggestAliveIndex + 1)
                            .filter(i -> toValue.get(i) == null)
                            .boxed()
                            .collect(Collectors.toSet()));
        }

        <O> ValueIndex<O> relabel(Function<V, O> injection) {
            List<@Nullable O> newToValue = new ArrayList<>(toValue.size());
            Map<O, Integer> newToIndex = new HashMap<>(toValue.size());
            for (int index = 0; index < toValue.size(); index++) {
                V value = toValue.get(index);
                if (value == null) {
                    newToValue.add(null);
                    continue;
                }
                O relabeled = injection.apply(value);
                //noinspection ConstantValue
                assert relabeled != null : "BddMap values must not be null";
                newToValue.add(relabeled);
                Integer collision = newToIndex.put(relabeled, index);
                assert collision == null
                        : String.format(
                                "Function is not injective: %s and %s both map to %s",
                                toValue.get(collision), value, relabeled);
            }
            return new ValueIndex<>(newToIndex, newToValue, new ArrayDeque<>(freeSlots));
        }
    }

    private static final class RelabelerImpl<V, O> implements BddMap.Relabeler<V, O> {
        private final BddMapFactoryImpl<V> source;
        private final BddMapFactoryImpl<O> destination;

        RelabelerImpl(BddMapFactoryImpl<V> source, BddMapFactoryImpl<O> destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public BddMapFactory<O> into() {
            return destination;
        }

        @Override
        public BddMap<O> relabel(BddMap<V> map) {
            return destination.make(source.mtbddFunction(map));
        }
    }

    @SuppressWarnings("PMD.CouplingBetweenObjects")
    static final class BddMapImpl<V> implements BddMap<V>, DdContainer {
        private final BddMapFactoryImpl<V> factory;
        private final int function;

        @Nullable
        private BitSet supportCache;

        @Nullable
        private Set<V> valueCache;

        BddMapImpl(BddMapFactoryImpl<V> factory, int function) {
            this.factory = factory;
            this.function = function;
        }

        private BddMapImpl<V> make(int node) {
            return node == this.function ? this : factory.make(node);
        }

        @Override
        public int function() {
            return function;
        }

        @Override
        public BddMapFactory<V> factory() {
            return factory;
        }

        @Override
        public V evaluate(BitSet assignment) {
            return factory.valueIndex.valueOf(factory.dd.evaluate(function, assignment));
        }

        @Override
        public BitSet support() {
            if (supportCache == null) {
                supportCache = factory.dd.support(function);
            }
            assert supportCache.equals(factory.dd.support(function));
            return supportCache;
        }

        @Override
        public BitSet supportAt(BitSet assignment) {
            return factory.dd.supportAt(function, assignment);
        }

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        @Override
        public Set<V> values() {
            if (valueCache == null) {
                Set<V> values = new HashSet<>();
                factory.dd.forEachValue(function, raw -> values.add(factory.valueIndex.valueOf(raw)));
                valueCache = Set.copyOf(values);
            }
            assert factory.dd.valuesOf(function).stream()
                    .mapToObj(factory.valueIndex::valueOf)
                    .collect(Collectors.toSet())
                    .equals(valueCache);
            return valueCache;
        }

        @Override
        public boolean isConstant() {
            return factory.dd.isConstant(function);
        }

        @Override
        public BddSet domainOf(V value) {
            Integer index = factory.valueIndex.peek(value);
            if (index == null) {
                // value never occurred in any map from this factory - can't be this map's either.
                return factory.bddSets.empty();
            }
            return factory.bddSets.make(factory.dd.where(function, index));
        }

        @Override
        public BddSet where(Predicate<V> predicate) {
            return factory.bddSets.make(
                    factory.dd.mapBoolean(function, raw -> predicate.test(factory.valueIndex.valueOf(raw))));
        }

        @Override
        public BddMap<V> update(BddSet domain, V value) {
            int bddFunction = factory.bddSets.bddFunction(domain);
            int rawValue = factory.valueIndex.indexOf(value);
            return make(factory.dd.update(function, bddFunction, rawValue));
        }

        @Override
        public BddMap<V> apply(BddMap<V> other, BinaryOperator<V> combiner) {
            int result = factory.dd.apply(
                    function,
                    factory.mtbddFunction(other),
                    (rawV, rawW) -> factory.valueIndex.indexOf(
                            combiner.apply(factory.valueIndex.valueOf(rawV), factory.valueIndex.valueOf(rawW))));
            return make(result);
        }

        @Override
        public BddMap<V> apply(BddMap<V> other, BddMapBinaryOperator<V> operator) {
            int otherFunction = factory.mtbddFunction(other);

            // operator.neutral/absorbing only translate into a real raw shortcut once that V has actually
            // been indexed before - see BddMapBinaryOperator's javadoc for why that's not a gap: if it
            // never has, no operand's raw terminal could equal it anyway.
            Integer neutralRaw = operator.neutral == null ? null : factory.valueIndex.peek(operator.neutral);
            Integer absorbingRaw = operator.absorbing == null ? null : factory.valueIndex.peek(operator.absorbing);
            int rawNeutral = neutralRaw == null ? -1 : neutralRaw;
            int rawAbsorbing = absorbingRaw == null ? -1 : absorbingRaw;

            IntBinaryOperator rawOp = (rawV, rawW) -> {
                V v1 = factory.valueIndex.valueOf(rawV);
                V v2 = factory.valueIndex.valueOf(rawW);
                return factory.valueIndex.indexOf(operator.apply(v1, v2));
            };
            MtBddBinaryOperator rawOperator = operator.commutative
                    ? MtBddBinaryOperator.monoid(rawOp, rawNeutral, rawAbsorbing)
                    : MtBddBinaryOperator.of(rawOp, rawNeutral, rawAbsorbing);

            int result = factory.dd.apply(function, otherFunction, rawOperator);
            return make(result);
        }

        @Override
        public <W, O> BddMap<O> apply(BddMap<W> other, BiFunction<V, W, O> combiner, BddMapFactory<O> destination) {
            BddMapFactoryImpl<W> otherFactory = factory.otherFactory(other);
            BddMapFactoryImpl<O> resultFactory = factory.otherFactory(destination);

            int result = factory.dd.apply(
                    function,
                    otherFactory.mtbddFunction(other),
                    (rawV, rawW) -> resultFactory.valueIndex.indexOf(
                            combiner.apply(factory.valueIndex.valueOf(rawV), otherFactory.valueIndex.valueOf(rawW))));
            return resultFactory.make(result);
        }

        @Override
        public BddMap<V> map(UnaryOperator<V> mapper) {
            return make(factory.dd.map(
                    function, raw -> factory.valueIndex.indexOf(mapper.apply(factory.valueIndex.valueOf(raw)))));
        }

        @Override
        public <O> BddMap<O> map(Function<V, O> mapper, BddMapFactory<O> destination) {
            BddMapFactoryImpl<O> resultFactory = factory.otherFactory(destination);
            int result = factory.dd.map(
                    function, raw -> resultFactory.valueIndex.indexOf(mapper.apply(factory.valueIndex.valueOf(raw))));
            return resultFactory.make(result);
        }

        @Override
        public BddSet agreement(BddMap<V> other) {
            return factory.bddSets.make(factory.dd.agreement(function, factory.mtbddFunction(other)));
        }

        @Override
        public BddMap<V> restrict(BitSet restrictedVariables, BitSet restrictedVariableValues) {
            return make(factory.dd.restrict(function, restrictedVariables, restrictedVariableValues));
        }

        @Override
        public BddMap<V> replaceVariables(@Nullable BddSet[] variableMapping) {
            int[] rawMapping = new int[variableMapping.length];
            for (int i = 0; i < variableMapping.length; i++) {
                BddSet mapping = variableMapping[i];
                rawMapping[i] = mapping == null ? factory.dd.placeholder() : factory.bddSets.bddFunction(mapping);
            }
            return make(factory.dd.compose(function, rawMapping));
        }

        @Override
        public BddMap<V> relabelVariables(IntUnaryOperator mapping) {
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
        public BddMap<V> constrain(BddSet domain) {
            return make(factory.dd.constrain(function, factory.bddSets.bddFunction(domain)));
        }

        @Override
        public BddMap<V> simplify(BddSet domain) {
            return make(factory.dd.simplify(function, factory.bddSets.bddFunction(domain)));
        }

        @Override
        public BddMap<BddMap<V>> split(BitSet splitVariables, BddMapFactory<BddMap<V>> destination) {
            BddMapFactoryImpl<BddMap<V>> resultFactory = factory.otherFactory(destination);
            int result = factory.dd.splitRelabeled(
                    function, splitVariables, sub -> resultFactory.valueIndex.indexOf(factory.make(sub)));
            return resultFactory.make(result);
        }

        @Override
        public boolean equals(Object o) {
            assert (this == o)
                    == (o instanceof BddMapFactoryImpl.BddMapImpl && function == ((BddMapImpl<?>) o).function);
            assert !(o instanceof BddMapFactoryImpl.BddMapImpl) || factory == ((BddMapImpl<?>) o).factory;
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
