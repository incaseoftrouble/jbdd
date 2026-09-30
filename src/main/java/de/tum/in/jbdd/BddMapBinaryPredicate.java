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

import java.util.Objects;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;

/**
 * A predicate over two values of one map's co-domain, together with the algebraic properties
 * {@link BddMap#where(BddMap, BddMapBinaryPredicate)} may exploit - the predicate counterpart of
 * {@link BddMapBinaryOperator}.
 *
 * <p>{@code reflexive} means {@code test(v, v)} holds for every value, {@code symmetric} that
 * {@code test(a, b) == test(b, a)}. Both are claims about the <em>values</em>; whether they survive down
 * to the raw terminals the recursion sees depends on the two maps sharing a numbering, which is
 * {@link BddMap} 's business rather than the caller's - see {@link MtBddBinaryPredicate}.
 */
public final class BddMapBinaryPredicate<V> implements BiPredicate<V, V> {
    private static final BddMapBinaryPredicate<?> EQUALITY = equivalence(Objects::equals);

    final BiPredicate<? super V, ? super V> predicate;
    final boolean symmetric;
    final boolean reflexive;

    private BddMapBinaryPredicate(BiPredicate<? super V, ? super V> predicate, boolean symmetric, boolean reflexive) {
        this.predicate = predicate;
        this.symmetric = symmetric;
        this.reflexive = reflexive;
    }

    /** Claims nothing, which is always safe. */
    public static <V> BddMapBinaryPredicate<V> of(BiPredicate<? super V, ? super V> predicate) {
        return new BddMapBinaryPredicate<>(predicate, false, false);
    }

    public static <V> BddMapBinaryPredicate<V> symmetric(BiPredicate<? super V, ? super V> predicate) {
        return new BddMapBinaryPredicate<>(predicate, true, false);
    }

    public static <V> BddMapBinaryPredicate<V> reflexive(BiPredicate<? super V, ? super V> predicate) {
        return new BddMapBinaryPredicate<>(predicate, false, true);
    }

    /** Both, which is what an equivalence relation is - {@link #equality()} being the obvious one. */
    public static <V> BddMapBinaryPredicate<V> equivalence(BiPredicate<? super V, ? super V> predicate) {
        return new BddMapBinaryPredicate<>(predicate, true, true);
    }

    /**
     * Value equality, which is what {@link BddMap#agreement} asks for.
     *
     * <p>One shared instance, deliberately: it is what {@link BddMap#where(BddMap, BddMapBinaryPredicate)}
     * recognises to take the raw-equality shortcut, and a fresh {@code Objects::equals} from another call
     * site would not compare equal to this one.
     */
    @SuppressWarnings("unchecked")
    public static <V> BddMapBinaryPredicate<V> equality() {
        return (BddMapBinaryPredicate<V>) EQUALITY;
    }

    @Override
    public boolean test(V left, V right) {
        assert checkLaws(left, right);
        return predicate.test(left, right);
    }

    private boolean checkLaws(V v1, V v2) {
        assert !symmetric || predicate.test(v1, v2) == predicate.test(v2, v1)
                : String.format("claimed symmetric but test(%s,%s) != test(%s,%s)", v1, v2, v2, v1);
        assert !reflexive || predicate.test(v1, v1)
                : String.format("claimed reflexive but test(%s,%s) is false", v1, v1);
        assert !reflexive || predicate.test(v2, v2)
                : String.format("claimed reflexive but test(%s,%s) is false", v2, v2);
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BddMapBinaryPredicate)) {
            return false;
        }
        BddMapBinaryPredicate<?> other = (BddMapBinaryPredicate<?>) o;
        return symmetric == other.symmetric && reflexive == other.reflexive && predicate.equals(other.predicate);
    }

    @Override
    public int hashCode() {
        return HashUtil.hash(predicate.hashCode(), Boolean.hashCode(symmetric), Boolean.hashCode(reflexive));
    }
}
