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
package de.tum.in.jbdd.collections;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/** Operations on {@link NatSet}s beyond the types' own; the sets they build are the caller's own. */
public final class NatSets {

    private NatSets() {}

    /** The indices of the elements of {@code elements} under {@code mapping}. */
    public static <S> MutableNatSet copyOf(Collection<? extends S> elements, ToIntFunction<? super S> mapping) {
        MutableNatSet indices = MutableNatSet.create();
        for (S element : elements) {
            indices.set(mapping.applyAsInt(element));
        }
        return indices;
    }

    /** The elements {@code mapping} maps the elements of {@code set} to. */
    public static <S> Set<S> asSet(NatSet set, IntFunction<? extends S> mapping) {
        return set.intStream().mapToObj(mapping).collect(Collectors.toSet());
    }

    /** {@code set} as the bits of an int; its elements must be below 31. */
    public static int toInt(NatSet set) {
        if (set.isEmpty()) {
            return 0;
        }
        long[] bits = set.toLongArray();
        if (bits.length != 1) {
            throw new IllegalArgumentException("Not an int: " + set);
        }
        return Math.toIntExact(bits[0]);
    }

    /** The set of the bits of {@code bits}, which is non-negative (the inverse of {@link #toInt}). */
    public static MutableNatSet fromInt(int bits) {
        assert bits >= 0 : "negative: " + bits;
        return MutableNatSet.valueOf(bits);
    }

    /** The union, as a set of the caller's own. */
    public static MutableNatSet union(NatSet first, NatSet second) {
        MutableNatSet union = MutableNatSet.copyOf(first);
        union.or(second);
        return union;
    }

    /** The intersection, as a set of the caller's own. */
    public static MutableNatSet intersection(NatSet first, NatSet second) {
        MutableNatSet intersection = MutableNatSet.copyOf(first);
        intersection.and(second);
        return intersection;
    }

    /** The union of all {@code sets}, as a set of the caller's own; empty for none. */
    public static MutableNatSet union(Iterable<? extends NatSet> sets) {
        MutableNatSet union = MutableNatSet.create();
        for (NatSet set : sets) {
            union.or(set);
        }
        return union;
    }

    /**
     * The union of all {@code sets} without building one where none is needed: the empty set for none, the one
     * non-empty operand itself (whatever it is) where the others are empty, and otherwise one built once and frozen.
     */
    public static NatSet lazyUnion(Iterable<? extends NatSet> sets) {
        NatSet single = null;
        MutableNatSet union = null;
        for (NatSet set : sets) {
            if (set.isEmpty()) {
                continue;
            }
            if (union != null) {
                union.or(set);
            } else if (single == null) {
                single = set;
            } else {
                union = MutableNatSet.copyOf(single);
                union.or(set);
            }
        }
        if (union != null) {
            return union.freezeAndClear();
        }
        return single == null ? NatSet.of() : single;
    }

    /**
     * The intersection of all {@code sets}, as a set of the caller's own.
     *
     * @throws IllegalArgumentException for no sets, whose intersection would be every natural.
     */
    public static MutableNatSet intersection(Iterable<? extends NatSet> sets) {
        Iterator<? extends NatSet> iterator = sets.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalArgumentException("The intersection of no sets");
        }
        MutableNatSet intersection = MutableNatSet.copyOf(iterator.next());
        while (iterator.hasNext() && !intersection.isEmpty()) {
            intersection.and(iterator.next());
        }
        return intersection;
    }

    /** {@code first} without the elements of {@code second}. */
    public static MutableNatSet without(NatSet first, NatSet second) {
        MutableNatSet difference = MutableNatSet.copyOf(first);
        difference.andNot(second);
        return difference;
    }

    /** Replaces {@code target} with the elements of {@code from} that are not in {@code minus}. */
    public static void difference(MutableNatSet target, NatSet from, NatSet minus) {
        target.clear();
        target.or(from);
        target.andNot(minus);
    }

    /**
     * Whether {@code first} and {@code second} hold the same elements of {@code scope}: {@code first ∩ scope = second
     * ∩ scope}, i.e. for every {@code e ∈ scope}, {@code e ∈ first ⇔ e ∈ second}.
     */
    public static boolean equalOn(NatSet first, NatSet second, NatSet scope) {
        return NatSetUtil.equalOn(first, second, scope);
    }

    /**
     * Whether {@code first} and {@code second} hold the same elements of the intersection of the two scopes:
     * {@code equalOn(first, second, scope ∩ otherScope)}, i.e. for every {@code e ∈ scope ∩ otherScope},
     * {@code e ∈ first ⇔ e ∈ second}.
     */
    public static boolean equalOnIntersection(NatSet first, NatSet second, NatSet scope, NatSet otherScope) {
        return NatSetUtil.equalOnIntersection(first, second, scope, otherScope);
    }

    /** The image of {@code source} under {@code mapping}. */
    public static MutableNatSet map(NatSet source, IntUnaryOperator mapping) {
        MutableNatSet target = MutableNatSet.create();
        map(source, target, mapping);
        return target;
    }

    /** Replaces {@code target} with the image of {@code source} under {@code mapping}. */
    public static void map(NatSet source, MutableNatSet target, IntUnaryOperator mapping) {
        target.clear();
        source.forEach((int element) -> target.set(mapping.applyAsInt(element)));
    }

    /**
     * Counts {@code number} up by one over the elements of {@code positions}, read as a binary number with the lowest
     * position least significant. Every other element is left alone.
     *
     * @return Whether it counted up. {@code false} means it wrapped, and every position is clear again.
     */
    public static boolean increment(MutableNatSet number, NatSet positions) {
        int first = positions.first();
        if (first < 0) {
            return false;
        }
        int end = positions.length();
        if (end - first == positions.size()) {
            // Contiguous positions: the carry runs through the ones at the bottom in one step.
            int carry = number.nextClearBit(first);
            if (carry >= end) {
                number.clear(first, end);
                return false;
            }
            number.clear(first, carry);
            number.set(carry);
            return true;
        }
        for (int position = first; position >= 0; position = NatSetUtil.nextAfter(positions, position)) {
            if (!number.contains(position)) {
                number.set(position);
                return true;
            }
            number.clear(position);
        }
        return false;
    }

    /** Hands each element with its position among the elements (from 0) to {@code action}, ascending. */
    public static void forEachWithIndex(NatSet set, IndexConsumer action) {
        int[] index = {0};
        set.forEach((int element) -> {
            action.accept(element, index[0]);
            index[0] += 1;
        });
    }

    /** Receives an element and its position among the elements. */
    @FunctionalInterface
    public interface IndexConsumer {
        void accept(int element, int index);
    }

    /** Every subset of {@code [0, size)}; see {@link #powerSet(NatSet)}. */
    public static Cursor<NatSet> powerSet(int size) {
        return powerSet(NatSet.range(0, size));
    }

    /**
     * Every subset of {@code basis}, from the empty set on, as {@link #increment} counts. The set handed out is the
     * counter itself: copy it to keep it.
     */
    public static Cursor<NatSet> powerSet(NatSet basis) {
        return new SubsetCursor(NatSet.copyOf(basis));
    }

    private static final class SubsetCursor implements Cursor<NatSet> {
        private final NatSet basis;
        private final MutableNatSet counter;
        private boolean valid = true;

        SubsetCursor(NatSet basis) {
            this.basis = basis;
            this.counter = MutableNatSet.dense(basis.length());
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public NatSet current() {
            assert valid; // current() is only defined while the cursor is valid
            return counter;
        }

        @Override
        public boolean advance() {
            valid = valid && increment(counter, basis);
            return valid;
        }
    }
}
