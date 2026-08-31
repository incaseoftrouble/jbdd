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

public interface BddContext {
    static BddContext create() {
        return create(ImmutableBddConfiguration.builder().build());
    }

    static BddContext create(BddConfiguration configuration) {
        return new BddContextImpl(configuration);
    }

    /** Like {@link #create()}, with {@code variables} many variables already declared. */
    static BddContext create(BddConfiguration configuration, int variables) {
        return new BddContextImpl(configuration, variables);
    }

    /** The unique {@link BddSetFactory}. */
    BddSetFactory bddSets();

    /** A fresh {@link BddMapFactory}. */
    <V> BddMapFactory<V> bddMaps();
}
