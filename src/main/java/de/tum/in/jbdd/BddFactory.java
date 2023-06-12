/*
 * Copyright (C) 2017 (See AUTHORS)
 *
 * This file is part of JBDD.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JBDD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tum.in.jbdd;

public final class BddFactory {
    private BddFactory() {}

    public static Bdd buildBdd() {
        return buildBdd(ImmutableBddConfiguration.builder().build());
    }

    public static Bdd buildBdd(BddConfiguration configuration) {
        return buildBddRecursive(configuration);
    }

    public static Bdd buildBddRecursive(BddConfiguration configuration) {
        return buildBdd(false, configuration);
    }

    public static Bdd buildBddIterative(BddConfiguration configuration) {
        return buildBdd(true, configuration);
    }

    public static Bdd buildBdd(boolean iterative, BddConfiguration configuration) {
        BddImpl bdd = new BddImpl(iterative, configuration);
        return configuration.threadSafetyCheck() ? new CheckedBdd(bdd) : bdd;
    }
}
