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

import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * An operator over two values together with the algebraic properties {@link BddMap#apply(BddMap, BddMapBinaryOperator)}
 * may exploit - the value-typed counterpart of {@link MtBddBinaryOperator}: {@code commutative} lets the operands be
 * ordered before a cache lookup, a {@code neutral} value returns the other operand unchanged, an {@code absorbing} one
 * ends the recursion. The claims are checked under assertions, and only translate to the raw terminals when both maps
 * share a numbering.
 */
public final class BddMapBinaryOperator<V> implements BinaryOperator<V> {
    final BiFunction<? super V, ? super V, ? extends V> op;
    final boolean commutative;
    final @Nullable V neutral;
    final @Nullable V absorbing;

    private BddMapBinaryOperator(
            BiFunction<? super V, ? super V, ? extends V> op,
            boolean commutative,
            @Nullable V neutral,
            @Nullable V absorbing) {
        this.op = op;
        this.commutative = commutative;
        this.neutral = neutral;
        this.absorbing = absorbing;
    }

    /** Claims nothing, which is always safe. */
    @SuppressWarnings("PMD.UseDiamondOperator")
    public static <V> BddMapBinaryOperator<V> of(BiFunction<? super V, ? super V, ? extends V> op) {
        //noinspection Convert2Diamond
        return new BddMapBinaryOperator<V>(op, false, null, null);
    }

    /** Claims {@code op(a, b).equals(op(b, a))}. */
    @SuppressWarnings("PMD.UseDiamondOperator")
    public static <V> BddMapBinaryOperator<V> commutative(BiFunction<? super V, ? super V, ? extends V> op) {
        //noinspection Convert2Diamond
        return new BddMapBinaryOperator<V>(op, true, null, null);
    }

    /** Claims commutativity and {@code neutral} as the neutral value: {@code op(a, neutral).equals(a)}. */
    @SuppressWarnings("PMD.UseDiamondOperator")
    public static <V> BddMapBinaryOperator<V> monoid(BiFunction<? super V, ? super V, ? extends V> op, V neutral) {
        //noinspection Convert2Diamond
        return new BddMapBinaryOperator<V>(op, true, neutral, null);
    }

    /**
     * Claims commutativity and {@code absorbing} as the absorbing value:
     * {@code op(a, absorbing).equals(absorbing)}.
     */
    @SuppressWarnings("PMD.UseDiamondOperator")
    public static <V> BddMapBinaryOperator<V> absorbing(BiFunction<? super V, ? super V, ? extends V> op, V absorbing) {
        //noinspection Convert2Diamond
        return new BddMapBinaryOperator<V>(op, true, null, absorbing);
    }

    /** {@link #monoid(BiFunction, Object)} and {@link #absorbing(BiFunction, Object)} combined. */
    public static <V> BddMapBinaryOperator<V> monoid(
            BiFunction<? super V, ? super V, ? extends V> op, V neutral, V absorbing) {
        return new BddMapBinaryOperator<>(op, true, neutral, absorbing);
    }

    @Override
    public V apply(V a, V b) {
        assert checkLaws(a, b);
        return op.apply(a, b);
    }

    private boolean checkLaws(V v1, V v2) {
        assert !commutative || op.apply(v1, v2).equals(op.apply(v2, v1))
                : String.format("claimed commutative but op(%s,%s) != op(%s,%s)", v1, v2, v2, v1);
        assert neutral == null
                        || (op.apply(v1, neutral).equals(v1)
                                && op.apply(neutral, v1).equals(v1))
                : String.format("claimed neutral=%s but the law failed for %s", neutral, v1);
        assert neutral == null
                        || (op.apply(v2, neutral).equals(v2)
                                && op.apply(neutral, v2).equals(v2))
                : String.format("claimed neutral=%s but the law failed for %s", neutral, v2);
        assert absorbing == null
                        || (op.apply(v1, absorbing).equals(absorbing)
                                && op.apply(absorbing, v1).equals(absorbing))
                : String.format("claimed absorbing=%s but the law failed for %s", absorbing, v1);
        assert absorbing == null
                        || (op.apply(v2, absorbing).equals(absorbing)
                                && op.apply(absorbing, v2).equals(absorbing))
                : String.format("claimed absorbing=%s but the law failed for %s", absorbing, v2);
        return true;
    }
}
