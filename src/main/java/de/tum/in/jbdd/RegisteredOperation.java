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

public interface RegisteredOperation {
    /**
     * Releases this operation's resources, if it holds any. After releasing, the operation cannot be used further.
     * However, this is not necessarily checked under all circumstances. This is an opportunistic operation and
     * not required by the caller.
     */
    default void release() {
        // Default: nothing to release.
    }

    @FunctionalInterface
    interface Unary extends RegisteredOperation, IntUnaryOperator {}

    @FunctionalInterface
    interface Binary extends RegisteredOperation, IntBinaryOperator {}

    /**
     * A three-argument operation. There is no JDK functional interface for this shape, so it declares its
     * own {@code applyAsInt}, mirroring {@link IntBinaryOperator}'s naming.
     */
    @FunctionalInterface
    interface Ternary extends RegisteredOperation {
        int applyAsInt(int operand1, int operand2, int operand3);
    }
}
