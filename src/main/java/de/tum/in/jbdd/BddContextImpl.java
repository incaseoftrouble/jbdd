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

final class BddContextImpl implements BddContext {
    private final BddImpl bdd;
    private final BddSetFactoryImpl bddSets;

    BddContextImpl(BddConfiguration configuration) {
        bdd = new BddImpl(configuration);
        bddSets = new BddSetFactoryImpl(bdd);
    }

    BddContextImpl(BddConfiguration configuration, int variables) {
        bdd = new BddImpl(configuration);
        bdd.createVariables(variables);
        bddSets = new BddSetFactoryImpl(bdd);
    }

    @Override
    public BddSetFactory bddSets() {
        return bddSets;
    }

    @Override
    public <V> BddMapFactory<V> bddMaps() {
        return new BddMapFactoryImpl<>(bddSets);
    }

    @Override
    public String toString() {
        return String.format("Context{%s}", bdd);
    }
}
