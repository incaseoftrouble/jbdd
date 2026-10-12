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

/** What implementing a {@link RegisteredOperation} shares. */
public final class RegisteredOperations {
    /** The single instance behind {@link RegisteredOperation#identity()}; an enum so that it stays one. */
    static final RegisteredOperation.Unary IDENTITY = Identity.INSTANCE;

    private RegisteredOperations() {}

    private enum Identity implements RegisteredOperation.Unary {
        INSTANCE;

        @Override
        public int applyAsInt(int operand) {
            return operand;
        }
    }

    /**
     * A registered operation over another, to which {@link #release()} forwards - what an object-layer handle wrapping
     * an int-layer operation extends.
     */
    public static class Forwarding<V extends RegisteredOperation> implements RegisteredOperation {
        protected final V operation;

        public Forwarding(V operation) {
            this.operation = operation;
        }

        @Override
        public void release() {
            operation.release();
        }
    }
}
