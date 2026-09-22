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

import static de.tum.in.jbdd.RegisteredOperation.*;

import de.tum.in.jbdd.RegisteredOperation.Forwarding;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
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

    BddMap<BddSet> split(BddSetFactoryImpl.BddSetImpl set, BitSet splitVariables, Values<BddSet> destination) {
        assert set.factory() == bddSets : "Splitting into another context"; // NOPMD
        ValuesImpl<BddSet> residuals = valuesOf(destination);
        int result = dd.splitBddRelabeled(
                bddSets.functionOf(set),
                splitVariables,
                residual -> residuals.getOrAssignIndex(bddSets.make(residual)));
        return make(result, residuals);
    }

    @Override
    public BddMap.VariableReplacer registerReplaceVariables(@Nullable BddSet[] variableMapping) {
        int[] rawMapping = new int[variableMapping.length];
        for (int i = 0; i < variableMapping.length; i++) {
            BddSet mapping = variableMapping[i];
            rawMapping[i] = mapping == null ? dd.placeholder() : bddSets.functionOf(mapping);
        }
        return new RegisteredReplacer(this, dd.registerCompose(rawMapping));
    }

    @SuppressWarnings("unchecked")
    <V> BddMapImpl<V> make(int function, ValuesImpl<V> values) {
        return (BddMapImpl<V>) protect(new BddMapImpl<>(this, function, values));
    }

    private <V> int functionOf(BddMap<V> map, ValuesImpl<V> values) {
        assert (map instanceof BddMapImpl<?>) && (this == ((BddMapImpl<?>) map).factory); // NOPMD
        assert values == ((BddMapImpl<?>) map).values // NOPMD
                : "Maps over different value numberings cannot be combined; relabel one into the other";
        return ((BddMapImpl<?>) map).function;
    }

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

    private int variableFunction(int variable) {
        Bdd bdd = dd.bdd();
        int variables = bdd.numberOfVariables();
        if (variable >= variables) {
            bdd.createVariables(variable - variables + 1);
        }
        return bdd.variableFunction(variable);
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
        /* Value indices assigned since the last collection. The raw terminal space is shared - e.g. with
         * every other numbering - so a fresh index we allocate can name a raw terminal an unrelated, dead
         * structure still has allocated. If that dead structure is reclaimed before the value we hand out
         * is protected, the value would be cleared. */
        // TODO This happens only in operations that don't protect their intermediate results (like split?)
        //      It would be cleaner if they did
        private final BitSet assignedSinceCollection = new BitSet();

        ValuesImpl(BddMapFactoryImpl factory) {
            this(factory, new HashMap<>(), new ArrayList<@Nullable V>(), new ArrayDeque<>()); // NOPMD
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
                    assert !Assertions.COSTLY_ASSERTIONS
                            || toValue.subList(0, biggestAliveIndex + 1).stream()
                                    .allMatch(Objects::nonNull);
                    biggestAliveIndex = index;
                } else {
                    index = reused;
                    assert toValue.get(index) == null;
                    toValue.set(index, value);
                    if (index > biggestAliveIndex) {
                        biggestAliveIndex = index;
                    }
                }
                assignedSinceCollection.set(index);
                return index;
            });
        }

        int getOrAssignIndex(V value, UnaryOperator<V> copy) {
            Integer existing = toIndex.get(value);
            if (existing != null) {
                return existing;
            }
            V stable = copy.apply(value);
            assert stable.equals(value) : "A copy has to be equal to what it copies";
            return getOrAssignIndex(stable);
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
            return factory.make(
                    factory.dd.ifThenElse(
                            factory.bddSets.functionOf(condition),
                            factory.functionOf(then, this),
                            factory.functionOf(otherwise, this)),
                    this);
        }

        @Override
        public BddMap<V> ifThenElse(int variable, BddMap<V> then, BddMap<V> otherwise) {
            int variableFunction = factory.variableFunction(variable);
            int thenFunction = factory.functionOf(then, this);
            int otherwiseFunction = factory.functionOf(otherwise, this);
            MtBddImpl dd = factory.dd;
            int level = dd.levelOfVariable(variable);
            // TODO Is this special-casing worth it? Shouldn't ifThenElse take care of that?
            int result = level < dd.decisionLevelOrMax(thenFunction) && level < dd.decisionLevelOrMax(otherwiseFunction)
                    ? dd.of(variable, thenFunction, otherwiseFunction)
                    : dd.ifThenElse(variableFunction, thenFunction, otherwiseFunction);
            return factory.make(result, this);
        }

        @Override
        public BddMap<V> apply(List<? extends BddMap<V>> maps, BddMapBinaryOperator<V> operator) {
            int size = maps.size();
            if (size == 0) {
                V neutral = operator.neutral;
                if (neutral == null) {
                    throw new IllegalArgumentException("Folding no maps needs a neutral value");
                }
                return of(neutral);
            }
            if (size == 1) {
                return maps.get(0);
            }
            if (size == 2) {
                return maps.get(0).apply(maps.get(1), operator);
            }

            int[] functions = new int[maps.size()];
            for (int i = 0; i < functions.length; i++) {
                functions[i] = factory.functionOf(maps.get(i), this);
            }
            // As the binary apply: a declared value becomes a raw shortcut only once it has been indexed.
            Integer neutralRaw = operator.neutral == null ? null : peek(operator.neutral);
            Integer absorbingRaw = operator.absorbing == null ? null : peek(operator.absorbing);
            ToIntFunction<int[]> rawOp = rawValues -> {
                V folded = valueOf(rawValues[0]);
                for (int i = 1; i < rawValues.length; i++) {
                    folded = operator.apply(folded, valueOf(rawValues[i]));
                }
                return getOrAssignIndex(folded);
            };
            MtBddNaryOperator rawOperator;
            if (!operator.commutative) {
                rawOperator = MtBddNaryOperator.of(functions.length, rawOp);
            } else if (neutralRaw == null && absorbingRaw == null) {
                rawOperator = MtBddNaryOperator.commutative(functions.length, rawOp);
            } else {
                rawOperator = MtBddNaryOperator.monoid(
                        functions.length,
                        rawOp,
                        neutralRaw == null ? -1 : neutralRaw,
                        absorbingRaw == null ? -1 : absorbingRaw);
            }
            return factory.make(factory.dd.apply(functions, rawOperator), this);
        }

        @Override
        public BddMap<List<V>> cartesianProduct(List<? extends BddMap<V>> maps, Values<List<V>> destination) {
            return cartesianProduct(maps, destination, Function.identity(), List::copyOf);
        }

        @Override
        public <W> BddMap<W> cartesianProductMap(
                List<? extends BddMap<V>> maps, Values<W> destination, Function<? super List<V>, ? extends W> map) {
            return cartesianProduct(
                    maps,
                    destination,
                    tuple -> {
                        W value = map.apply(tuple);
                        assert value != tuple; // NOPMD - map must not hand back the tuple buffer
                        return value;
                    },
                    UnaryOperator.identity());
        }

        private <W> BddMap<W> cartesianProduct(
                List<? extends BddMap<V>> maps,
                Values<W> destination,
                Function<? super List<V>, ? extends W> map,
                UnaryOperator<W> copyOnInsert) {
            ValuesImpl<W> resultValues = factory.valuesOf(destination);

            int[] functions = new int[maps.size()];
            for (int i = 0; i < maps.size(); i++) {
                functions[i] = factory.functionOf(maps.get(i), this);
            }
            //noinspection unchecked
            V[] scratch = (V[]) new Object[functions.length];

            //noinspection Java9CollectionFactory
            List<V> unmodifiableView = Collections.unmodifiableList(Arrays.asList(scratch));

            // TODO Use "native" cartesian product?
            int result = factory.dd.apply(functions, rawValues -> {
                for (int i = 0; i < rawValues.length; i++) {
                    scratch[i] = valueOf(rawValues[i]);
                }
                return resultValues.getOrAssignIndex(map.apply(unmodifiableView), copyOnInsert);
            });
            return factory.make(result, resultValues);
        }

        @Override
        public <O> BddMap.Relabeler<V, O> createRelabeling(Function<? super V, ? extends O> injection) {
            return new RelabelerImpl<>(this, relabel(injection));
        }

        @Override
        public BddMap.Operator<V> registerApply(BddMapBinaryOperator<V> operator) {
            return new RegisteredApply<>(this, operator);
        }

        @Override
        public <O> BddMap.Mapper<V, O> registerMap(Function<? super V, ? extends O> function, Values<O> destination) {
            ValuesImpl<O> resultValues = factory.valuesOf(destination);
            return new RegisteredMapper<>(
                    this,
                    resultValues,
                    factory.dd.registerMap(raw -> resultValues.getOrAssignIndex(function.apply(valueOf(raw)))));
        }

        @Override
        public <W, O> BddMap.Combiner<V, W, O> registerCombine(
                Values<W> other, BiFunction<? super V, ? super W, ? extends O> combiner, Values<O> destination) {
            ValuesImpl<W> otherValues = factory.valuesOf(other);
            ValuesImpl<O> resultValues = factory.valuesOf(destination);
            // TODO if (otherValues == thisValue == resultValues) { specialize }
            // Different numberings means we cannot use any property
            IntBinaryOperator rawOp = (rawV, rawW) ->
                    resultValues.getOrAssignIndex(combiner.apply(valueOf(rawV), otherValues.valueOf(rawW)));
            return new RegisteredCombiner<>(
                    this, otherValues, resultValues, factory.dd.registerApply(MtBddBinaryOperator.of(rawOp)));
        }

        @Override
        public BddMap.Selector<V> registerWhere(Predicate<? super V> predicate) {
            return new RegisteredSelector<>(this, factory.dd.registerMapBoolean(raw -> predicate.test(valueOf(raw))));
        }

        @Override
        public BddMap.Relation<V> registerWhere(BddMapBinaryPredicate<V> predicate) {
            if (predicate == BddMapBinaryPredicate.<V>equality()) { // NOPMD
                // Where with equality is just agreement, which uses a global cache
                return BddMap::agreement;
            }
            IntBinaryPredicate raw = (rawV, rawW) -> predicate.test(valueOf(rawV), valueOf(rawW));
            return new RegisteredRelation<>(
                    this,
                    factory.dd.registerApplyBoolean(
                            MtBddBinaryPredicate.of(raw, predicate.symmetric, predicate.reflexive)));
        }

        @Override
        public <O> BddMap<V> relabelInto(BddMap<O> map, Function<? super O, ? extends V> injection) {
            assert isInjective(map.values(), injection);
            return map.map(injection, this);
        }

        @SuppressWarnings("unchecked")
        @Override
        public BddMap<V> adopt(BddMap<? extends V> map) {
            if (map.valueDomain() == this) { // NOPMD
                // Already over this numbering, hence already a BddMap<V> - the wildcard is only static.
                return (BddMap<V>) map;
            }
            return map.map(value -> value, this);
        }

        @Override
        public void afterGc(DecisionDiagram origin, int reclaimedNodes, BitSet reclaimedValues) {
            forgetReclaimed(reclaimedValues);
        }

        @Override
        public void afterTableGrowth(DecisionDiagram origin, int invalidatedNodes, BitSet reclaimedValues) {
            forgetReclaimed(reclaimedValues);
        }

        private void forgetReclaimed(BitSet reclaimedValues) {
            int first = reclaimedValues.nextSetBit(0);
            // Nothing reclaimed or first reclaim beyond what we use -> Nothing to do
            if (first < 0 || first > biggestAliveIndex) {
                assignedSinceCollection.clear();
                return;
            }

            for (int index = first;
                    index >= 0 && index <= biggestAliveIndex;
                    index = reclaimedValues.nextSetBit(index + 1)) {
                V value = toValue.get(index);
                if (value != null && !assignedSinceCollection.get(index)) {
                    toValue.set(index, null);
                    toIndex.remove(value);
                }
            }

            int alive = biggestAliveIndex;
            while (alive >= 0 && toValue.get(alive) == null) {
                alive -= 1;
            }
            if (alive < biggestAliveIndex) {
                toValue.subList(alive + 1, toValue.size()).clear();
                biggestAliveIndex = alive;
            }

            freeGaps.clear();
            for (int index = 0; index <= biggestAliveIndex; index++) {
                if (toValue.get(index) == null) {
                    freeGaps.add(index);
                }
            }

            assignedSinceCollection.clear();

            assert toValue.size() == biggestAliveIndex + 1;
            assert IntStream.range(0, toValue.size())
                    .allMatch(index ->
                            toValue.get(index) == null || Objects.equals(toIndex.get(toValue.get(index)), index));
            assert toIndex.size() == toValue.size() - freeGaps.size();
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
            relabeled.biggestAliveIndex = biggestAliveIndex;
            return relabeled;
        }

        @Override
        public String toString() {
            return String.format("Values@%x{%d}", System.identityHashCode(this), toIndex.size());
        }
    }

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private static final class RegisteredApply<V> implements BddMap.Operator<V> {
        private final ValuesImpl<V> values;
        private final Binary operation;
        // Hold references to these two -- as long as this RegisteredApply is alive, they won't be collected
        private final @Nullable BddMap<V> pinnedNeutral;
        private final @Nullable BddMap<V> pinnedAbsorbing;

        RegisteredApply(ValuesImpl<V> values, BddMapBinaryOperator<V> operator) {
            this.values = values;

            V neutral = operator.neutral;
            V absorbing = operator.absorbing;
            this.pinnedNeutral = neutral == null ? null : values.of(neutral);
            this.pinnedAbsorbing = absorbing == null ? null : values.of(absorbing);
            int rawNeutral = neutral == null ? -1 : values.getOrAssignIndex(neutral);
            int rawAbsorbing = absorbing == null ? -1 : values.getOrAssignIndex(absorbing);
            IntBinaryOperator rawOp =
                    (rawV, rawW) -> values.getOrAssignIndex(operator.apply(values.valueOf(rawV), values.valueOf(rawW)));
            MtBddBinaryOperator rawOperator = operator.commutative
                    ? MtBddBinaryOperator.monoid(rawOp, rawNeutral, rawAbsorbing)
                    : MtBddBinaryOperator.of(rawOp, rawNeutral, rawAbsorbing);
            this.operation = values.factory.dd.registerApply(rawOperator);
        }

        @Override
        public BddMap<V> apply(BddMap<V> left, BddMap<V> right) {
            BddMapFactoryImpl factory = values.factory;
            return factory.make(
                    operation.applyAsInt(factory.functionOf(left, values), factory.functionOf(right, values)), values);
        }

        @Override
        public void release() {
            operation.release();
        }
    }

    private static final class RegisteredMapper<V, O> extends Forwarding<Unary> implements BddMap.Mapper<V, O> {
        private final ValuesImpl<V> values;
        private final ValuesImpl<O> destination;

        RegisteredMapper(ValuesImpl<V> values, ValuesImpl<O> destination, Unary operation) {
            super(operation);
            this.values = values;
            this.destination = destination;
        }

        @Override
        public BddMap<O> apply(BddMap<V> map) {
            BddMapFactoryImpl factory = values.factory;
            return factory.make(operation.applyAsInt(factory.functionOf(map, values)), destination);
        }
    }

    private static final class RegisteredCombiner<V, W, O> extends Forwarding<Binary>
            implements BddMap.Combiner<V, W, O> {
        private final ValuesImpl<V> values;
        private final ValuesImpl<W> otherValues;
        private final ValuesImpl<O> destination;

        RegisteredCombiner(
                ValuesImpl<V> values, ValuesImpl<W> otherValues, ValuesImpl<O> destination, Binary operation) {
            super(operation);
            this.values = values;
            this.otherValues = otherValues;
            this.destination = destination;
        }

        @Override
        public BddMap<O> apply(BddMap<V> left, BddMap<W> right) {
            BddMapFactoryImpl factory = values.factory;
            return factory.make(
                    operation.applyAsInt(factory.functionOf(left, values), factory.functionOf(right, otherValues)),
                    destination);
        }
    }

    private static final class RegisteredSelector<V> extends Forwarding<Unary> implements BddMap.Selector<V> {
        private final ValuesImpl<V> values;

        RegisteredSelector(ValuesImpl<V> values, Unary operation) {
            super(operation);
            this.values = values;
        }

        @Override
        public BddSet apply(BddMap<V> map) {
            BddMapFactoryImpl factory = values.factory;
            return factory.bddSets.make(operation.applyAsInt(factory.functionOf(map, values)));
        }
    }

    private static final class RegisteredRelation<V> extends Forwarding<Binary> implements BddMap.Relation<V> {
        private final ValuesImpl<V> values;

        RegisteredRelation(ValuesImpl<V> values, Binary operation) {
            super(operation);
            this.values = values;
        }

        @Override
        public BddSet apply(BddMap<V> left, BddMap<V> right) {
            BddMapFactoryImpl factory = values.factory;
            int result = operation.applyAsInt(factory.functionOf(left, values), factory.functionOf(right, values));
            return factory.bddSets.make(result);
        }
    }

    private static final class RegisteredReplacer extends Forwarding<Unary> implements BddMap.VariableReplacer {
        private final BddMapFactoryImpl factory;

        RegisteredReplacer(BddMapFactoryImpl factory, Unary operation) {
            super(operation);
            this.factory = factory;
        }

        @Override
        public <V> BddMap<V> replace(BddMap<V> map) {
            ValuesImpl<V> values = factory.valuesOf(map);
            return factory.make(operation.applyAsInt(factory.functionOf(map, values)), values);
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
            assert !Assertions.COSTLY_ASSERTIONS
                    || factory.dd.valuesOf(function).stream()
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
        public Map<V, BddSet> inverse() {
            MultiTerminalDecisionDiagram.Inverse inverse = factory.dd.invert(function);
            // The domains are unreferenced until wrapped, and wrapping allocates no node.
            BitSet codomain = inverse.codomain();
            Map<V, BddSet> domains = new LinkedHashMap<>();
            for (int raw = codomain.nextSetBit(0); raw >= 0; raw = codomain.nextSetBit(raw + 1)) {
                domains.put(values.valueOf(raw), factory.bddSets.make(inverse.functionFor(raw)));
            }
            return Collections.unmodifiableMap(domains);
        }

        @Override
        public OptionalInt decisionVariable() {
            return factory.dd.isConstant(function)
                    ? OptionalInt.empty()
                    : OptionalInt.of(factory.dd.decisionVariable(function));
        }

        @Override
        public BddMap<V> high() {
            if (factory.dd.isConstant(function)) {
                throw new IllegalStateException("Constant map has no decision");
            }
            return factory.make(factory.dd.highOf(function), values);
        }

        @Override
        public BddMap<V> low() {
            if (factory.dd.isConstant(function)) {
                throw new IllegalStateException("Constant map has no decision");
            }
            return factory.make(factory.dd.lowOf(function), values);
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

            IntBinaryOperator rawOp =
                    (rawV, rawW) -> values.getOrAssignIndex(operator.apply(values.valueOf(rawV), values.valueOf(rawW)));
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
        public <W> BddSet where(BddMap<W> other, BiPredicate<? super V, ? super W> predicate) {
            ValuesImpl<W> otherValues = factory.valuesOf(other);
            IntBinaryPredicate raw = (rawV, rawW) -> predicate.test(values.valueOf(rawV), otherValues.valueOf(rawW));
            return where(other, MtBddBinaryPredicate.of(raw));
        }

        @Override
        public <W extends V> BddSet where(BddMap<W> other, BddMapBinaryPredicate<? super V> predicate) {
            ValuesImpl<W> otherValues = factory.valuesOf(other);
            if (otherValues != values) { // NOPMD
                // Over different values, symmetry etc. do not make sense anymore
                return where(other, (BiPredicate<? super V, ? super W>) predicate);
            }
            if (predicate == BddMapBinaryPredicate.equality()) { // NOPMD
                return factory.bddSets.make(factory.dd.agreement(function, factory.functionOf(other)));
            }
            IntBinaryPredicate raw = (rawV, rawW) -> predicate.test(values.valueOf(rawV), values.valueOf(rawW));
            return where(other, MtBddBinaryPredicate.of(raw, predicate.symmetric, predicate.reflexive));
        }

        @Override
        public <W> boolean allMatch(BddMap<W> other, BiPredicate<? super V, ? super W> predicate) {
            ValuesImpl<W> otherValues = factory.valuesOf(other);
            IntBinaryPredicate raw = (rawV, rawW) -> predicate.test(values.valueOf(rawV), otherValues.valueOf(rawW));
            return factory.dd.allMatch(function, factory.functionOf(other), MtBddBinaryPredicate.of(raw));
        }

        @Override
        public <W extends V> boolean allMatch(BddMap<W> other, BddMapBinaryPredicate<? super V> predicate) {
            ValuesImpl<W> otherValues = factory.valuesOf(other);
            if (otherValues != values) { // NOPMD
                // Over different values, symmetry etc. do not make sense anymore
                return allMatch(other, (BiPredicate<? super V, ? super W>) predicate);
            }
            if (predicate == BddMapBinaryPredicate.equality()) { // NOPMD
                // Canonical over one numbering: equal functions are the same id.
                return function == factory.functionOf(other);
            }
            IntBinaryPredicate raw = (rawV, rawW) -> predicate.test(values.valueOf(rawV), values.valueOf(rawW));
            return factory.dd.allMatch(
                    function,
                    factory.functionOf(other),
                    MtBddBinaryPredicate.of(raw, predicate.symmetric, predicate.reflexive));
        }

        private BddSet where(BddMap<?> other, MtBddBinaryPredicate predicate) {
            int otherFunction = factory.functionOf(other);
            return factory.bddSets.make(factory.dd.applyBoolean(function, otherFunction, predicate));
        }

        @Override
        public BddMap<V> restrict(Cube restriction) {
            return make(factory.dd.restrict(function, restriction));
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
            return splitMap(splitVariables, destination, Function.identity());
        }

        @Override
        public <W> BddMap<W> splitMap(
                BitSet splitVariables, Values<W> destination, Function<? super BddMap<V>, ? extends W> residual) {
            ValuesImpl<W> resultValues = factory.valuesOf(destination);
            int result = factory.dd.splitRelabeled(
                    function,
                    splitVariables,
                    sub -> resultValues.getOrAssignIndex(residual.apply(factory.make(sub, values))));
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
