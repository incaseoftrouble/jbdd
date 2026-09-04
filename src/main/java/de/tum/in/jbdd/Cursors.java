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

import java.util.BitSet;

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

    /**
     * Counts through every assignment of {@code variables} over their own domains - the multi-valued
     * counterpart of {@link #powerSet(BitSet)}. The array handed out is the counter itself.
     */
    static Cursor<int[]> powerSet(int[] domains, BitSet variables) {
        return new ArrayPowerCursor(domains, variables);
    }

    /**
     * Counts through every assignment of {@code variables}. The set handed out is the counter itself.
     */
    static Cursor<BitSet> powerSet(BitSet variables) {
        return new PowerCursor(variables);
    }

    private static final class SingletonCursor<E> implements Cursor<E> {
        private final E element;
        private boolean valid;

        public SingletonCursor(E element) {
            this.element = element;
            valid = true;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public E current() {
            assert valid : "current() is only defined while the cursor is valid";
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

    private static final class ArrayPowerCursor implements Cursor<int[]> {
        private final int[] assignment;
        private final int[] domains;
        private final BitSet variables;
        private boolean valid;

        public ArrayPowerCursor(int[] domains, BitSet variables) {
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
            for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
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

    private static final class PowerCursor implements Cursor<BitSet> {
        private final BitSet assignment;
        private final BitSet variables;
        private boolean valid;

        public PowerCursor(BitSet variables) {
            this.variables = variables;
            assignment = new BitSet(variables.length());
            valid = true;
        }

        @Override
        public boolean valid() {
            return valid;
        }

        @Override
        public BitSet current() {
            assert valid : "current() is only defined while the cursor is valid";
            return assignment;
        }

        @Override
        public boolean advance() {
            if (!valid) {
                return false;
            }
            for (int variable = variables.nextSetBit(0); variable >= 0; variable = variables.nextSetBit(variable + 1)) {
                if (assignment.get(variable)) {
                    assignment.clear(variable);
                } else {
                    assignment.set(variable);
                    return true;
                }
            }
            valid = false;
            return false;
        }
    }
}
