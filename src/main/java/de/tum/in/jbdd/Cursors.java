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

import de.tum.in.jbdd.collections.Cursor;
import de.tum.in.jbdd.collections.NatSet;
import de.tum.in.jbdd.collections.NatSets;
import java.util.PrimitiveIterator;

/** Cursors that are not a diagram's own walks. */
public final class Cursors {
    private Cursors() {}

    /** A cursor over nothing. */
    public static <E> Cursor<E> empty() {
        return new EmptyCursor<>();
    }

    /** A cursor over exactly one element. */
    static <E> Cursor<E> singleton(E element) {
        return new SingletonCursor<>(element);
    }

    /** A valued cursor over nothing. */
    public static <E> ValuedCursor<E> emptyValued() {
        return new EmptyValuedCursor<>();
    }

    /** A valued cursor over exactly one element, leading to {@code value}. */
    static <E> ValuedCursor<E> singletonValued(E element, int value) {
        return new SingletonValuedCursor<>(element, value);
    }

    /** {@code cursor}'s elements, each leading to {@code value} - what a constant function enumerates. */
    static <E> ValuedCursor<E> constantValued(Cursor<E> cursor, int value) {
        return new ConstantValuedCursor<>(cursor, value);
    }

    /**
     * Counts through every assignment of {@code variables} over their own domains - the multi-valued
     * counterpart of {@link NatSets#powerSet(NatSet)}. The array handed out is the counter itself.
     */
    static Cursor<int[]> powerSet(int[] domains, NatSet variables) {
        return new ArrayPowerCursor(domains, variables);
    }

    private static final class SingletonCursor<E> implements Cursor<E> {
        private final E element;
        private boolean valid;

        SingletonCursor(E element) {
            this.element = element;
            valid = true;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public E current() {
            assert valid; // current() is only defined while the cursor is valid
            return element;
        }

        @Override
        public boolean advance() {
            valid = false;
            return false;
        }
    }

    private static final class EmptyCursor<E> implements Cursor<E> {
        @Override
        public boolean valid() {
            return false;
        }

        @Override
        public E current() {
            throw new IllegalStateException("Cursor is not valid");
        }

        @Override
        public boolean advance() {
            return false;
        }
    }

    private static final class EmptyValuedCursor<E> implements ValuedCursor<E> {
        @Override
        public boolean valid() {
            return false;
        }

        @Override
        public E current() {
            throw new IllegalStateException("Cursor is not valid");
        }

        @Override
        public int value() {
            throw new IllegalStateException("Cursor is not valid");
        }

        @Override
        public boolean advance() {
            return false;
        }
    }

    private static final class SingletonValuedCursor<E> implements ValuedCursor<E> {
        private final E element;
        private final int value;
        private boolean valid = true;

        SingletonValuedCursor(E element, int value) {
            this.element = element;
            this.value = value;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public E current() {
            assert valid; // current() is only defined while the cursor is valid
            return element;
        }

        @Override
        public int value() {
            assert valid; // value() is only defined while the cursor is valid
            return value;
        }

        @Override
        public boolean advance() {
            valid = false;
            return false;
        }
    }

    private static final class ConstantValuedCursor<E> implements ValuedCursor<E> {
        private final Cursor<E> cursor;
        private final int value;

        ConstantValuedCursor(Cursor<E> cursor, int value) {
            this.cursor = cursor;
            this.value = value;
        }

        @Override
        public boolean valid() {
            return cursor.valid();
        }

        @Override
        public E current() {
            return cursor.current();
        }

        @Override
        public int value() {
            assert cursor.valid(); // value() is only defined while the cursor is valid
            return value;
        }

        @Override
        public boolean advance() {
            return cursor.advance();
        }
    }

    private static final class ArrayPowerCursor implements Cursor<int[]> {
        private final int[] assignment;
        private final int[] domains;
        private final NatSet variables;
        private boolean valid;

        ArrayPowerCursor(int[] domains, NatSet variables) {
            this.domains = domains;
            this.variables = variables;
            assignment = new int[domains.length];
            valid = true;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @SuppressWarnings("AssignmentOrReturnOfFieldWithMutableType")
        @Override
        public int[] current() {
            assert valid;
            return assignment;
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }
            PrimitiveIterator.OfInt iterator = variables.iterator();
            while (iterator.hasNext()) {
                int variable = iterator.nextInt();
                if (assignment[variable] == domains[variable] - 1) {
                    assignment[variable] = 0;
                } else {
                    assignment[variable] += 1;
                    return true;
                }
            }
            valid = false;
            return false;
        }
    }
}
