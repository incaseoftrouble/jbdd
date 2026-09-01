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
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

@SuppressWarnings({"MethodOnlyUsedFromInnerClass", "ObjectEquality", "PMD.CouplingBetweenObjects"})
final class BddMapFactoryImpl extends GcReferenceManager<BddMapFactoryImpl.BddMapImpl<?>, MtBddImpl>
        implements BddMapFactory {
    private final BddSetFactoryImpl bddSets;

    BddMapFactoryImpl(BddSetFactoryImpl bddSets) {
        super(bddSets.dd.mtbdd());
        this.bddSets = bddSets;
    }

    @Override
    public <V> Values<V> create() {
        return new ValuesImpl<>(this);
    }

    @SuppressWarnings("unchecked")
    <V> BddMapImpl<V> make(int function, ValuesImpl<V> values) {
        return (BddMapImpl<V>) protect(new BddMapImpl<>(this, function, values));
    }

    /** The raw MTBDD function of {@code map}, which must be over {@code values}. */
    private <V> int functionOf(BddMap<V> map, ValuesImpl<V> values) {
        assert (map instanceof BddMapImpl<?>) && (this == ((BddMapImpl<?>) map).factory); // NOPMD
        assert values == ((BddMapImpl<?>) map).values
                : "Maps over different value numberings cannot be combined; relabel one into the other";
        return ((BddMapImpl<?>) map).function;
    }

    /** As above, for the cross-typed operations, which are inherently over two numberings. */
    private int functionOf(BddMap<?> map) {
        assert (map instanceof BddMapImpl<?>) && (this == ((BddMapImpl<?>) map).factory); // NOPMD
        return ((BddMapImpl<?>) map).function;
    }

    private <O> ValuesImpl<O> valuesOf(BddMap<O> map) {
        return valuesOf(map.valueDomain());
    }

    private <O> ValuesImpl<O> valuesOf(Values<O> values) {
        assert (values instanceof ValuesImpl<?>) && (this == ((ValuesImpl<?>) values).factory); // NOPMD
        return (ValuesImpl<O>) values;
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
    public Map<String, Object> statistics() {
        return dd.statistics();
    }

    private static <A, B> boolean isInjective(
            Collection<? extends A> values, Function<? super A, ? extends B> function) {
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

    private static final class MapKey {
        private final int function;
        private final ValuesImpl<?> values;

        MapKey(int function, ValuesImpl<?> values) {
            this.function = function;
            this.values = values;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MapKey)) {
                return false;
            }
            MapKey that = (MapKey) o;
            return function == that.function && values == that.values;
        }

        @Override
        public int hashCode() {
            return HashUtil.hash(function, System.identityHashCode(values));
        }
    }

    static final class ValuesImpl<V> implements Values<V>, NodeTableObserver {
        private final BddMapFactoryImpl factory;
        private final Map<V, Integer> toIndex;
        private final List<@Nullable V> toValue;
        private final Deque<Integer> freeGaps;
        private int biggestAliveIndex = -1;

        ValuesImpl(BddMapFactoryImpl factory) {
            this(factory, new HashMap<>(), new ArrayList<@Nullable V>(), new ArrayDeque<>());
        }

        private ValuesImpl(
                BddMapFactoryImpl factory,
                Map<V, Integer> toIndex,
                List<@Nullable V> toValue,
                Deque<Integer> freeGaps) {
            this.factory = factory;
            this.toIndex = toIndex;
            this.toValue = toValue;
            this.freeGaps = freeGaps;
            factory.dd.registerObserver(this);
        }

        int getOrAssignIndex(V value) {
            //noinspection ConstantValue
            assert value != null : "BddMap values must not be null";
            return toIndex.computeIfAbsent(value, v -> {
                Integer reused = freeGaps.poll();
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
        public BddMapFactory factory() {
            return factory;
        }

        @Override
        public BddMap<V> of(V value) {
            return factory.make(factory.dd.of(getOrAssignIndex(value)), this);
        }

        @Override
        public BddMap<V> ifThenElse(BddSet condition, BddMap<V> then, BddMap<V> otherwise) {
            int result = factory.dd.ifThenElse(
                    factory.bddSets.functionOf(condition),
                    factory.functionOf(then, this),
                    factory.functionOf(otherwise, this));
            return factory.make(result, this);
        }

        @Override
        public BddMap<List<V>> cartesianProduct(List<? extends BddMap<V>> maps, Values<List<V>> destination) {
            ValuesImpl<List<V>> resultValues = factory.valuesOf(destination);

            int[] functions = new int[maps.size()];
            for (int i = 0; i < maps.size(); i++) {
                functions[i] = factory.functionOf(maps.get(i), this);
            }

            // TODO Use "native" cartesian product?
            int result = factory.dd.apply(functions, rawValues -> {
                // TODO Avoid the copy? Raw array + Arrays.asList(...)?
                List<V> combined = new ArrayList<>(rawValues.length);
                for (int rawValue : rawValues) {
                    combined.add(valueOf(rawValue));
                }
                return resultValues.getOrAssignIndex(List.copyOf(combined));
            });
            return factory.make(result, resultValues);
        }

        @Override
        public <O> BddMap.Relabeler<V, O> createRelabeling(Function<? super V, ? extends O> injection) {
            return new RelabelerImpl<>(this, relabel(injection));
        }

        @Override
        public <O> BddMap<V> relabelInto(BddMap<O> map, Function<? super O, ? extends V> injection) {
            assert isInjective(map.values(), injection);
            return map.map(injection, this);
        }

        @SuppressWarnings("unchecked")
        @Override
        public BddMap<V> adopt(BddMap<? extends V> map) {
            if (map.valueDomain() == this) {
                // Already over this numbering, hence already a BddMap<V> - the wildcard is only static.
                return (BddMap<V>) map;
            }
            return map.map(value -> value, this);
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
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
                    freeGaps.add(index);
                }
            }
            while (biggestAliveIndex >= 0 && toValue.get(biggestAliveIndex) == null) {
                toValue.remove(toValue.size() - 1);
                biggestAliveIndex -= 1;
            }
            assert toValue.size() == biggestAliveIndex + 1;
            freeGaps.removeIf(i -> i > biggestAliveIndex);
            assert new HashSet<>(freeGaps).size() == freeGaps.size();
            // freeSlots is exactly the set of indices whose slot is currently vacant.
            assert new HashSet<>(freeGaps)
                    .equals(IntStream.range(0, biggestAliveIndex + 1)
                            .filter(i -> toValue.get(i) == null)
                            .boxed()
                            .collect(Collectors.toSet()));
        }

        <O> ValuesImpl<O> relabel(Function<? super V, ? extends O> injection) {
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
            ValuesImpl<O> relabeled = new ValuesImpl<>(factory, newToIndex, newToValue, new ArrayDeque<>(freeGaps));
            // The numbering is copied verbatim, so the watermark has to come along - sweepValues asserts
            // toValue.size() == biggestAliveIndex + 1, and would trip on the first reclaimed value without it.
            relabeled.biggestAliveIndex = biggestAliveIndex;
            return relabeled;
        }

        @Override
        public String toString() {
            return String.format("Values@%x{%d}", System.identityHashCode(this), toIndex.size());
        }
    }

    private static final class RelabelerImpl<V, O> implements BddMap.Relabeler<V, O> {
        private final ValuesImpl<V> source;
        private final ValuesImpl<O> destination;

        RelabelerImpl(ValuesImpl<V> source, ValuesImpl<O> destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public Values<O> into() {
            return destination;
        }

        @Override
        public BddMap<O> relabel(BddMap<V> map) {
            // The numbering is identical by construction, so the MTBDD carries over verbatim.
            return destination.factory.make(destination.factory.functionOf(map, source), destination);
        }
    }

    @SuppressWarnings("PMD.CouplingBetweenObjects")
    static final class BddMapImpl<V> implements BddMap<V>, DdContainer {
        private final BddMapFactoryImpl factory;
        private final int function;
        private final ValuesImpl<V> values;

        @Nullable
        private BitSet supportCache;

        @Nullable
        private Set<V> valueCache;

        BddMapImpl(BddMapFactoryImpl factory, int function, ValuesImpl<V> values) {
            this.factory = factory;
            this.function = function;
            this.values = values;
        }

        private BddMapImpl<V> make(int node) {
            return node == this.function ? this : factory.make(node, values);
        }

        @Override
        public int function() {
            return function;
        }

        @Override
        public Object canonicalKey() {
            return new MapKey(function, values);
        }

        @Override
        public BddMapFactory factory() {
            return factory;
        }

        @Override
        public Values<V> valueDomain() {
            return values;
        }

        @Override
        public V evaluate(BitSet assignment) {
            return values.valueOf(factory.dd.evaluate(function, assignment));
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
                Set<V> collected = new HashSet<>();
                factory.dd.forEachValue(function, raw -> collected.add(values.valueOf(raw)));
                valueCache = Set.copyOf(collected);
            }
            assert factory.dd.valuesOf(function).stream()
                    .mapToObj(values::valueOf)
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
            Integer index = values.peek(value);
            if (index == null) {
                // value never occurred in any map over this numbering - can't be this map's either.
                return factory.bddSets.empty();
            }
            return factory.bddSets.make(factory.dd.where(function, index));
        }

        @Override
        public BddSet where(Predicate<? super V> predicate) {
            return factory.bddSets.make(factory.dd.mapBoolean(function, raw -> predicate.test(values.valueOf(raw))));
        }

        @Override
        public BddMap<V> update(BddSet domain, V value) {
            int bddFunction = factory.bddSets.functionOf(domain);
            int rawValue = values.getOrAssignIndex(value);
            return make(factory.dd.update(function, bddFunction, rawValue));
        }

        @Override
        public BddMap<V> apply(BddMap<V> other, BiFunction<? super V, ? super V, ? extends V> combiner) {
            int otherFunction = factory.functionOf(other, values);
            int result = factory.dd.apply(
                    function,
                    otherFunction,
                    (rawV, rawW) ->
                            values.getOrAssignIndex(combiner.apply(values.valueOf(rawV), values.valueOf(rawW))));
            return make(result);
        }

        @Override
        public BddMap<V> apply(BddMap<V> other, BddMapBinaryOperator<V> operator) {
            int otherFunction = factory.functionOf(other, values);

            // operator.neutral/absorbing only translate into a real raw shortcut once that V has actually
            // been indexed before: if it never has, no operand's raw terminal could equal it anyway.
            Integer neutralRaw = operator.neutral == null ? null : values.peek(operator.neutral);
            Integer absorbingRaw = operator.absorbing == null ? null : values.peek(operator.absorbing);
            int rawNeutral = neutralRaw == null ? -1 : neutralRaw;
            int rawAbsorbing = absorbingRaw == null ? -1 : absorbingRaw;

            IntBinaryOperator rawOp = (rawV, rawW) -> {
                V v1 = values.valueOf(rawV);
                V v2 = values.valueOf(rawW);
                return values.getOrAssignIndex(operator.apply(v1, v2));
            };
            MtBddBinaryOperator rawOperator = operator.commutative
                    ? MtBddBinaryOperator.monoid(rawOp, rawNeutral, rawAbsorbing)
                    : MtBddBinaryOperator.of(rawOp, rawNeutral, rawAbsorbing);

            int result = factory.dd.apply(function, otherFunction, rawOperator);
            return make(result);
        }

        @Override
        public <W, O> BddMap<O> apply(
                BddMap<W> other, BiFunction<? super V, ? super W, ? extends O> combiner, Values<O> destination) {
            ValuesImpl<W> otherValues = factory.valuesOf(other);
            ValuesImpl<O> resultValues = factory.valuesOf(destination);

            int result = factory.dd.apply(
                    function,
                    factory.functionOf(other),
                    (rawV, rawW) -> resultValues.getOrAssignIndex(
                            combiner.apply(values.valueOf(rawV), otherValues.valueOf(rawW))));
            return factory.make(result, resultValues);
        }

        @Override
        public BddMap<V> map(Function<? super V, ? extends V> mapper) {
            return make(factory.dd.map(function, raw -> values.getOrAssignIndex(mapper.apply(values.valueOf(raw)))));
        }

        @Override
        public <O> BddMap<O> map(Function<? super V, ? extends O> mapper, Values<O> destination) {
            ValuesImpl<O> resultValues = factory.valuesOf(destination);
            int result =
                    factory.dd.map(function, raw -> resultValues.getOrAssignIndex(mapper.apply(values.valueOf(raw))));
            return factory.make(result, resultValues);
        }

        @Override
        public BddSet agreement(BddMap<V> other) {
            return factory.bddSets.make(factory.dd.agreement(function, factory.functionOf(other, values)));
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
                rawMapping[i] = mapping == null ? factory.dd.placeholder() : factory.bddSets.functionOf(mapping);
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
            return make(factory.dd.constrain(function, factory.bddSets.functionOf(domain)));
        }

        @Override
        public BddMap<V> simplify(BddSet domain) {
            return make(factory.dd.simplify(function, factory.bddSets.functionOf(domain)));
        }

        @Override
        public BddMap<BddMap<V>> split(BitSet splitVariables, Values<BddMap<V>> destination) {
            ValuesImpl<BddMap<V>> resultValues = factory.valuesOf(destination);
            int result = factory.dd.splitRelabeled(
                    function, splitVariables, sub -> resultValues.getOrAssignIndex(factory.make(sub, values)));
            return factory.make(result, resultValues);
        }

        @Override
        public boolean equals(Object o) {
            // Canonicalized on (function, values) - see BddMapFactoryImpl.MapKey.
            assert (this == o)
                    == (o instanceof BddMapFactoryImpl.BddMapImpl
                            && function == ((BddMapImpl<?>) o).function
                            && values == ((BddMapImpl<?>) o).values);
            assert !(o instanceof BddMapFactoryImpl.BddMapImpl) || factory == ((BddMapImpl<?>) o).factory;
            return this == o;
        }

        @Override
        public int hashCode() {
            return function;
        }

        @Override
        public String toString() {
            return String.format("%d@%s", function, values);
        }
    }
}
