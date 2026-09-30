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

/**
 * A {@link Cursor} that also reports the terminal the element it stands on leads to.
 */
public interface ValuedCursor<E> extends Cursor<E> {
    /** A walk with nothing in it, for a function no assignment satisfies. */
    static <E> ValuedCursor<E> emptyValued() {
        return new ValuedCursor<>() {
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
        };
    }

    /**
     * The value the underlying function takes on the element the cursor stands on. Defined exactly
     * while {@link #valid()} holds.
     */
    int value();

    final class SingletonValuedCursor<E> implements ValuedCursor<E> {
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

    /** A walk whose every element leads to the same terminal - what a constant function enumerates. */
    final class ConstantValuedCursor<E> implements ValuedCursor<E> {
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
}
