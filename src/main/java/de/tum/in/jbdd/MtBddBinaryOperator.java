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
import org.jspecify.annotations.Nullable;

public final class MtBddBinaryOperator implements IntBinaryOperator {
    private static final int NONE = -1;

    final IntBinaryOperator op;
    final boolean commutative;
    final int neutral;
    final int absorbing;

    private MtBddBinaryOperator(IntBinaryOperator op, boolean commutative, int neutral, int absorbing) {
        this.op = op;
        this.commutative = commutative;
        this.neutral = neutral;
        this.absorbing = absorbing;
    }

    static MtBddBinaryOperator of(IntBinaryOperator op) {
        return new MtBddBinaryOperator(op, false, NONE, NONE);
    }

    static MtBddBinaryOperator commutative(IntBinaryOperator op) {
        return new MtBddBinaryOperator(op, true, NONE, NONE);
    }

    static MtBddBinaryOperator monoid(IntBinaryOperator op, int neutral) {
        return new MtBddBinaryOperator(op, true, neutral, NONE);
    }

    static MtBddBinaryOperator absorbing(IntBinaryOperator op, int absorbing) {
        return new MtBddBinaryOperator(op, true, NONE, absorbing);
    }

    static MtBddBinaryOperator monoid(IntBinaryOperator op, int neutral, int absorbing) {
        return new MtBddBinaryOperator(op, true, neutral, absorbing);
    }

    /** Non-commutative neutral/absorbing, for {@link MtBddImpl}'s n-ary apply to delegate its length-2 case
     * to the binary path without asserting a commutativity property n-ary apply doesn't track. */
    static MtBddBinaryOperator of(IntBinaryOperator op, int neutral, int absorbing) {
        return new MtBddBinaryOperator(op, false, neutral, absorbing);
    }

    @Override
    public int applyAsInt(int a, int b) {
        assert checkLaws(a, b);
        return op.applyAsInt(a, b);
    }

    private boolean checkLaws(int v1, int v2) {
        assert !commutative || op.applyAsInt(v1, v2) == op.applyAsInt(v2, v1)
                : String.format("claimed commutative but op(%d,%d) != op(%d,%d)", v1, v2, v2, v1);
        assert neutral == NONE || (op.applyAsInt(v1, neutral) == v1 && op.applyAsInt(neutral, v1) == v1)
                : String.format("claimed neutral=%d but the law failed for %d", neutral, v1);
        assert neutral == NONE || (op.applyAsInt(v2, neutral) == v2 && op.applyAsInt(neutral, v2) == v2)
                : String.format("claimed neutral=%d but the law failed for %d", neutral, v2);
        assert absorbing == NONE
                        || (op.applyAsInt(v1, absorbing) == absorbing && op.applyAsInt(absorbing, v1) == absorbing)
                : String.format("claimed absorbing=%d but the law failed for %d", absorbing, v1);
        assert absorbing == NONE
                        || (op.applyAsInt(v2, absorbing) == absorbing && op.applyAsInt(absorbing, v2) == absorbing)
                : String.format("claimed absorbing=%d but the law failed for %d", absorbing, v2);
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MtBddBinaryOperator)) {
            return false;
        }
        MtBddBinaryOperator other = (MtBddBinaryOperator) o;
        return commutative == other.commutative
                && neutral == other.neutral
                && absorbing == other.absorbing
                && op.equals(other.op);
    }

    @Override
    public int hashCode() {
        return HashUtil.hash(op.hashCode(), Boolean.hashCode(commutative), neutral, absorbing);
    }
}
