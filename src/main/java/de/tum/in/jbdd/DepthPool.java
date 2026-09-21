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

import java.util.Arrays;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

final class DepthPool<V> {
    private final Supplier<V> factory;
    private @Nullable Object[] layers = new Object[8];

    DepthPool(Supplier<V> factory) {
        this.factory = factory;
    }

    @SuppressWarnings("unchecked")
    V get(int depth) {
        if (depth >= layers.length) {
            layers = Arrays.copyOf(layers, Math.max(depth + 1, layers.length * 2));
        }
        V value = (V) layers[depth];
        if (value == null) {
            value = factory.get();
            layers[depth] = value;
        }
        return value;
    }
}
