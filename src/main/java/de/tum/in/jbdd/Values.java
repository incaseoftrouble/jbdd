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

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A shared co-domain for {@link BddMap<V>}.
 *
 * <p>Every map carries the {@link Values} it was built over, and every operation combining two maps
 * requires knowledge of it - an MTBDD terminal only means anything relative to a numbering. This makes
 * the numbering the unit of structure sharing: two maps over the same {@link Values} share subtrees
 * wherever they agree, and are equal exactly when their functions are. If their co-domain is different,
 * we cannot do an O(1) check and instead would need to traverse both.
 */
public interface Values<V> {
    /** The factory this numbering belongs to. */
    BddMapFactory factory();

    /** The constant map. */
    BddMap<V> of(V value);

    /**
     * Builds a map from an explicit value-to-domain assignment: {@code value} wherever {@code
     * domains.get(value)} holds, {@code defaultValue} on whatever's left uncovered. Mappings
     * are applied in iteration order of {@code map}, so later entries win where domains overlap.
     */
    default BddMap<V> of(Map<? extends V, BddSet> map, V defaultValue) {
        BddMap<V> result = of(defaultValue);
        for (Map.Entry<? extends V, BddSet> entry : map.entrySet()) {
            result = result.update(entry.getValue(), entry.getKey());
        }
        return result;
    }

    /** {@code then}'s value where {@code condition} holds, {@code otherwise}'s value elsewhere. Both maps
     * must be over this numbering. */
    BddMap<V> ifThenElse(BddSet condition, BddMap<V> then, BddMap<V> otherwise);

    /** The map yielding, at each valuation, the list of {@code maps}' values there - see {@link
     * MtBdd#cartesianProduct}. All {@code maps} must be over this numbering. */
    BddMap<List<V>> cartesianProduct(List<? extends BddMap<V>> maps, Values<List<V>> destination);

    /**
     * {@link #cartesianProduct(List, Values)} with {@code map} applied to every tuple on its way into
     * {@code destination}.
     *
     * <p>{@code map} is called on the tuples the traversal reaches, and - like a {@link Cursor}'s {@code
     * current()} - the list it gets is one buffer rewritten between calls: it may be read, but keeping it
     * (or handing it back) is not allowed. {@link #cartesianProduct(List, Values)} is the identity case
     * and copies for you, but only for the tuples that turn out to be new.
     */
    <W> BddMap<W> cartesianProductMap(
            List<? extends BddMap<V>> maps, Values<W> destination, Function<? super List<V>, ? extends W> map);

    /**
     * Creates a {@link BddMap.Relabeler} to {@code O} via {@code injection}. Build this once and reuse it (via
     * {@link BddMap.Relabeler#relabel}) for every map that should end up under the same mapping. {@code
     * injection} must be injective over all values in this numbering.
     */
    <O> BddMap.Relabeler<V, O> createRelabeling(Function<? super V, ? extends O> injection);

    /**
     * Binds {@code operator} to this numbering once - see {@link BddMap.Operator}.
     */
    BddMap.Operator<V> registerApply(BddMapBinaryOperator<V> operator);

    /**
     * Binds {@code function} and {@code destination} once - see {@link BddMap.Mapper}.
     */
    <O> BddMap.Mapper<V, O> registerMap(Function<? super V, ? extends O> function, Values<O> destination);

    /**
     * Binds a cross-numbering combination once - see {@link BddMap.Combiner}. {@code other} is the
     * numbering of the right operand, {@code destination} that of the result.
     */
    <W, O> BddMap.Combiner<V, W, O> registerCombine(
            Values<W> other, BiFunction<? super V, ? super W, ? extends O> combiner, Values<O> destination);

    /**
     * Binds {@code predicate} to this numbering once - see {@link BddMap.Selector}.
     */
    BddMap.Selector<V> registerWhere(Predicate<? super V> predicate);

    /**
     * Binds {@code predicate} to this numbering once - see {@link BddMap.Relation}.
     */
    BddMap.Relation<V> registerWhere(BddMapBinaryPredicate<V> predicate);

    /**
     * Inject {@code map} into <em>this</em> numbering via {@code injection}. {@code injection} must be
     * injective over the values {@code map} actually takes.
     */
    <O> BddMap<V> relabelInto(BddMap<O> map, Function<? super O, ? extends V> injection);

    /**
     * Re-encodes {@code map} into this numbering, appending whichever values it takes that are not already in
     * this numbering.
     */
    BddMap<V> adopt(BddMap<? extends V> map);
}
