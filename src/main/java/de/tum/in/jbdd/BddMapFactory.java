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
import java.util.function.Function;

/**
 * Creates and canonicalizes {@link BddMap}s; {@code V} needs {@code equals}/{@code hashCode}.
 */
public interface BddMapFactory<V> {
    /** The constant map. */
    BddMap<V> of(V value);

    /**
     * Builds a map from an explicit value-to-domain assignment: {@code value} wherever {@code
     * domains.get(value)} holds, {@code defaultValue} on whatever's left uncovered. Domains are applied in
     * iteration order of {@code domains}, so later entries win where domains overlap.
     */
    default BddMap<V> of(Map<V, BddSet> domains, V defaultValue) {
        BddMap<V> result = of(defaultValue);
        for (Map.Entry<V, BddSet> entry : domains.entrySet()) {
            result = result.update(entry.getValue(), entry.getKey());
        }
        return result;
    }

    /** {@code then}'s value where {@code condition} holds, {@code otherwise}'s value elsewhere. */
    BddMap<V> ifThenElse(BddSet condition, BddMap<V> then, BddMap<V> otherwise);

    /** The map yielding, at each valuation, the list of {@code maps}' values there - see {@link MtBdd#cartesianProduct}. */
    BddMap<List<V>> cartesianProduct(List<BddMap<V>> maps, BddMapFactory<List<V>> destination);

    /**
     * Creates a {@link BddMap.Relabeler} to {@code O} via {@code injection}. Build this once and reuse it (via
     * {@link BddMap.Relabeler#relabel}) for every map that should end up under the same mapping. {@code
     * injection} must be injective over all values this factory has seen.
     */
    <O> BddMap.Relabeler<V, O> createRelabeling(Function<V, O> injection);

    /**
     * Inject {@code map} into <em>this</em> already-existing factory via {@code injection}. {@code
     * injection} must be injective over the values {@code map} actually takes.
     */
    <O> BddMap<V> relabelInto(BddMap<O> map, Function<O, V> injection);

    Map<String, Object> statistics();
}
