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

import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

/**
 * The n-ary counterpart of {@link MtBddBinaryOperator}. {@code commutative} means invariant under any
 * permutation of the operand array - {@code computeNaryApply} has no operation cache yet to canonicalize
 * argument order against (unlike binary {@code computeApply}), so this doesn't help lookups today, but it
 * still lets the array be sorted once up front (cheap, O(n log n) on typically-small n) so that a future
 * cache keyed on the array gets the same benefit binary apply already has. {@code neutral} generalizes to
 * "all-but-one of the operands are constant-equal to {@code neutral}" - the survivor (or {@code of(neutral)}
 * if there is no survivor) is returned unchanged, exactly like the binary case's single "other operand."
 * {@code absorbing} generalizes to "any operand is constant-equal to {@code absorbing}."
 *
 * <p>The arity is fixed at construction, which lets the degenerate arities be materialized right here as
 * {@link Unary} / {@link Binary} instead of being rebuilt inside every {@code apply} call. {@code MtBddImpl}
 * unwraps those and dispatches to its unary/binary implementations - the ones that have an operation cache -
 * and since the adapter now belongs to the operator, a caller reusing one operator instance keeps that
 * cache warm across calls (it is ephemeral on operator <em>identity</em>).</p>
 */
public class MtBddNaryOperator implements ToIntFunction<int[]> {
    private static final int NONE = -1;

    final ToIntFunction<int[]> op;
    final int arity;
    final boolean commutative;
    final int neutral;
    final int absorbing;

    private MtBddNaryOperator(int arity, ToIntFunction<int[]> op, boolean commutative, int neutral, int absorbing) {
        assert arity >= 0;
        this.arity = arity;
        this.op = op;
        this.commutative = commutative;
        this.neutral = neutral;
        this.absorbing = absorbing;
    }

    private static MtBddNaryOperator create(
            int arity, ToIntFunction<int[]> op, boolean commutative, int neutral, int absorbing) {
        switch (arity) {
            case 1:
                return new Unary(op, commutative, neutral, absorbing);
            case 2:
                return new Binary(op, commutative, neutral, absorbing);
            default:
                return new MtBddNaryOperator(arity, op, commutative, neutral, absorbing);
        }
    }

    static MtBddNaryOperator of(int arity, ToIntFunction<int[]> op) {
        return create(arity, op, false, NONE, NONE);
    }

    static MtBddNaryOperator commutative(int arity, ToIntFunction<int[]> op) {
        return create(arity, op, true, NONE, NONE);
    }

    static MtBddNaryOperator monoid(int arity, ToIntFunction<int[]> op, int neutral) {
        return create(arity, op, true, neutral, NONE);
    }

    static MtBddNaryOperator absorbing(int arity, ToIntFunction<int[]> op, int absorbing) {
        return create(arity, op, true, NONE, absorbing);
    }

    static MtBddNaryOperator monoid(int arity, ToIntFunction<int[]> op, int neutral, int absorbing) {
        return create(arity, op, true, neutral, absorbing);
    }

    @Override
    public int applyAsInt(int[] values) {
        assert values.length == arity : "Expected " + arity + " operands, got " + values.length;
        assert checkLaws(values);
        return op.applyAsInt(values);
    }

    /**
     * See {@link MtBddBinaryOperator}'s equivalent - same idea, generalized to n operands, using only the
     * concrete values actually encountered at a leaf. Best-effort: only the laws that can be probed at this
     * operator's own arity are checked. In particular there is no neutral-element check - probing it would
     * mean calling the operator with one operand more than it declared, which is not a legal call.
     */
    private boolean checkLaws(int[] values) {
        if (commutative && values.length >= 2) {
            int[] swapped = values.clone();
            int swap = swapped[0];
            swapped[0] = swapped[1];
            swapped[1] = swap;
            assert op.applyAsInt(values) == op.applyAsInt(swapped)
                    : "claimed commutative but swapping two operands changed the result";
        }
        if (absorbing != NONE && values.length > 0) {
            int[] replaced = values.clone();
            replaced[0] = absorbing;
            assert op.applyAsInt(replaced) == absorbing
                    : "claimed absorbing=" + absorbing + " but the result wasn't absorbing";
        }
        return true;
    }

    /**
     * A one-operand operator, i.e. a {@code map}. There is nothing to compare commutativity or a
     * neutral/absorbing element against at this arity, so those properties degenerate away.
     */
    static final class Unary extends MtBddNaryOperator implements IntUnaryOperator {
        private final int[] operands = new int[1];

        private Unary(ToIntFunction<int[]> op, boolean commutative, int neutral, int absorbing) {
            super(1, op, commutative, neutral, absorbing);
        }

        @Override
        public int applyAsInt(int value) {
            operands[0] = value;
            return applyAsInt(operands);
        }
    }

    /** A two-operand operator, i.e. a plain {@link MtBddBinaryOperator} carrying the same properties. */
    static final class Binary extends MtBddNaryOperator implements IntBinaryOperator {
        private final int[] operands = new int[2];

        private Binary(ToIntFunction<int[]> op, boolean commutative, int neutral, int absorbing) {
            super(2, op, commutative, neutral, absorbing);
        }

        @Override
        public int applyAsInt(int value1, int value2) {
            operands[0] = value1;
            operands[1] = value2;
            return applyAsInt(operands);
        }
    }
}
