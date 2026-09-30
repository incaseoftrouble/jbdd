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

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A snapshot of a diagram below some roots, as a list of entries: one per distinct function, each after the entries it
 * refers to. A fold over the diagram is then one loop over the entries, running outside any operation - it may build
 * sets and maps - and visiting each function once. Entries are indices into the snapshot, not nodes: it holds no
 * references and stays valid whatever happens to the diagram afterwards.
 *
 * <p>The order is fixed: the roots in the given order, each depth first with the high child before the low one, an
 * entry appended once its children are. Built with complement sharing (sets only), a function whose complement already
 * has an entry becomes a {@link Kind#COMPLEMENT} entry of it, and the walk does not descend into it.
 *
 * @param <V> the values of the {@link Kind#VALUE} entries: {@code Boolean} for a set, the map's values for a map
 */
public final class Dag<V> {
    /** What an entry is. */
    public enum Kind {
        /** A constant function: a set's {@code true} or {@code false}, or one value of a map. */
        VALUE,
        /** A decision on {@link #variable}, with {@link #high} where it holds and {@link #low} where not. */
        DECISION,
        /** The complement of the function of entry {@link #complementOf}. */
        COMPLEMENT
    }

    private final Kind[] kinds;
    // DECISION: variable, high, low; COMPLEMENT: -, complemented entry, -.
    private final int[] variables;
    private final int[] highs;
    private final int[] lows;
    private final @Nullable Object[] values;
    private final int[] roots;

    private Dag(Builder<V> builder) {
        int size = builder.kinds.size();
        this.kinds = builder.kinds.toArray(new Kind[0]);
        this.variables = toArray(builder.variables, size);
        this.highs = toArray(builder.highs, size);
        this.lows = toArray(builder.lows, size);
        this.values = builder.values.toArray();
        this.roots = toArray(builder.roots, builder.roots.size());
    }

    private static int[] toArray(IntArrayList list, int size) {
        int[] array = new int[size];
        for (int i = 0; i < size; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    /** The number of entries. */
    public int size() {
        return kinds.length;
    }

    /** The number of roots, as given when building. */
    public int numberOfRoots() {
        return roots.length;
    }

    /** The entry of the {@code index}-th root. */
    public int root(int index) {
        return roots[index];
    }

    public Kind kind(int entry) {
        return kinds[entry];
    }

    /** The value of a {@link Kind#VALUE} entry. */
    @SuppressWarnings("unchecked") // Only VALUE entries hold a value, and the builder took it as a V.
    public V value(int entry) {
        check(entry, Kind.VALUE);
        Object value = values[entry];
        assert value != null;
        return (V) value;
    }

    /** The decision variable of a {@link Kind#DECISION} entry. */
    public int variable(int entry) {
        check(entry, Kind.DECISION);
        return variables[entry];
    }

    /** The entry of a {@link Kind#DECISION} entry's function where its variable holds - an earlier one. */
    public int high(int entry) {
        check(entry, Kind.DECISION);
        return highs[entry];
    }

    /** The entry of a {@link Kind#DECISION} entry's function where its variable does not hold - an earlier one. */
    public int low(int entry) {
        check(entry, Kind.DECISION);
        return lows[entry];
    }

    /** The entry a {@link Kind#COMPLEMENT} entry is the complement of - an earlier one. */
    public int complementOf(int entry) {
        check(entry, Kind.COMPLEMENT);
        return highs[entry];
    }

    private void check(int entry, Kind kind) {
        if (kinds[entry] != kind) {
            throw new IllegalArgumentException("Entry " + entry + " is a " + kind(entry) + ", not a " + kind);
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(32 * kinds.length);
        for (int entry = 0; entry < kinds.length; entry++) {
            builder.append(entry).append(": ");
            switch (kinds[entry]) {
                case VALUE:
                    builder.append(values[entry]);
                    break;
                case DECISION:
                    builder.append(variables[entry])
                            .append(" ? ")
                            .append(highs[entry])
                            .append(" : ")
                            .append(lows[entry]);
                    break;
                case COMPLEMENT:
                    builder.append('!').append(highs[entry]);
                    break;
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /** Appends entries in the order the snapshot promises; the walks in the factories drive it. */
    static final class Builder<V> {
        private final List<Kind> kinds = new ArrayList<>();
        private final IntArrayList variables = new IntArrayList();
        private final IntArrayList highs = new IntArrayList();
        private final IntArrayList lows = new IntArrayList();
        private final List<@Nullable V> values = new ArrayList<>();
        private final IntArrayList roots = new IntArrayList();

        int addValue(V value) {
            return add(Kind.VALUE, -1, -1, -1, value);
        }

        int addDecision(int variable, int high, int low) {
            return add(Kind.DECISION, variable, high, low, null);
        }

        int addComplement(int entry) {
            return add(Kind.COMPLEMENT, -1, entry, -1, null);
        }

        private int add(Kind kind, int variable, int high, int low, @Nullable V value) {
            int entry = kinds.size();
            assert high < entry && low < entry;
            kinds.add(kind);
            variables.add(variable);
            highs.add(high);
            lows.add(low);
            values.add(value);
            return entry;
        }

        void addRoot(int entry) {
            roots.add(entry);
        }

        Dag<V> build() {
            return new Dag<>(this);
        }
    }
}
