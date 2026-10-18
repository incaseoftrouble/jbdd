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

import org.jspecify.annotations.Nullable;

/**
 * A predicate over two terminal values, together with the algebraic properties
 * {@link MultiTerminalDecisionDiagram#applyBoolean} may exploit - the predicate counterpart of
 * {@link MtBddBinaryOperator}.
 *
 * <p>Both properties are claims about <em>this</em> predicate over raw terminal values, and each buys a
 * shortcut that is wrong without it:
 *
 * <ul>
 *   <li>{@code reflexive} - {@code test(v, v)} holds for every {@code v} - lets the recursion answer
 *       {@code true} the moment the two operands are the same node, without descending at all.
 *   <li>{@code symmetric} - {@code test(a, b) == test(b, a)} - lets it order the operand pair before
 *       looking it up, so a pair and its mirror image share one cache entry.
 * </ul>
 *
 * <p>Neither is inferable from the caller's own predicate alone: a caller comparing two maps over
 * <em>different</em> numberings has a raw predicate that is generally neither, since a raw terminal
 * means one value on the left and another on the right. Claim a property only about the raw predicate
 * actually handed over; both are checked under assertions.
 *
 * <p>There is deliberately no irreflexive counterpart. A predicate that is false on the diagonal is the
 * negation of a reflexive one, and negating the result is one complement edge on the resulting BDD.
 */
public final class MtBddBinaryPredicate implements IntBinaryPredicate {
    final IntBinaryPredicate predicate;
    final boolean symmetric;
    final boolean reflexive;

    private MtBddBinaryPredicate(IntBinaryPredicate predicate, boolean symmetric, boolean reflexive) {
        this.predicate = predicate;
        this.symmetric = symmetric;
        this.reflexive = reflexive;
    }

    /** Claims nothing, which is always safe. */
    static MtBddBinaryPredicate of(IntBinaryPredicate predicate) {
        return new MtBddBinaryPredicate(predicate, false, false);
    }

    static MtBddBinaryPredicate of(IntBinaryPredicate predicate, boolean symmetric, boolean reflexive) {
        return new MtBddBinaryPredicate(predicate, symmetric, reflexive);
    }

    /** Raw terminal equality: an equivalence, so both properties hold. */
    static MtBddBinaryPredicate equality() {
        return new MtBddBinaryPredicate((left, right) -> left == right, true, true);
    }

    @Override
    public boolean test(int left, int right) {
        assert checkLaws(left, right);
        return predicate.test(left, right);
    }

    private boolean checkLaws(int v1, int v2) {
        assert !symmetric || predicate.test(v1, v2) == predicate.test(v2, v1)
                : String.format("claimed symmetric but test(%d,%d) != test(%d,%d)", v1, v2, v2, v1);
        assert !reflexive || predicate.test(v1, v1)
                : String.format("claimed reflexive but test(%d,%d) is false", v1, v1);
        assert !reflexive || predicate.test(v2, v2)
                : String.format("claimed reflexive but test(%d,%d) is false", v2, v2);
        return true;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MtBddBinaryPredicate)) {
            return false;
        }
        MtBddBinaryPredicate other = (MtBddBinaryPredicate) o;
        return symmetric == other.symmetric && reflexive == other.reflexive && predicate.equals(other.predicate);
    }

    @Override
    public int hashCode() {
        return HashUtil.hash(predicate.hashCode(), Boolean.hashCode(symmetric), Boolean.hashCode(reflexive));
    }
}
