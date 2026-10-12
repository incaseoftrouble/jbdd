/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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
 * How to read a caller's propositional expressions, so that {@link BddSetFactory#of(Object, ExpressionStructure)}
 * builds their sets without knowing the caller's type: it walks an expression through these methods, memoizing
 * subexpressions by {@code equals}. They are called between the operations of the build, so, like every callback,
 * they must be pure and start no operation on the factory.
 *
 * @param <E> the caller's expression type
 */
public interface ExpressionStructure<E> {
    /** What an expression is; the arities are those of {@link #arity(Object)}. */
    enum Kind {
        FALSE,
        TRUE,
        VARIABLE,
        NOT,
        AND,
        OR,
        XOR,
        IFF
    }

    Kind kind(E expression);

    /** The variable of a {@link Kind#VARIABLE} expression; created if it does not exist yet. */
    int variable(E expression);

    /**
     * The number of operands: one for {@link Kind#NOT}, two for {@link Kind#XOR} and {@link Kind#IFF}, any for
     * {@link Kind#AND} and {@link Kind#OR} (none being the neutral element), none for the others.
     */
    int arity(E expression);

    E operand(E expression, int index);

    /**
     * A set of the factory the caller already has for {@code expression}, used instead of building it - from a
     * cache of the caller's own. null by default.
     */
    default @Nullable BddSet known(E expression) {
        return null;
    }
}
