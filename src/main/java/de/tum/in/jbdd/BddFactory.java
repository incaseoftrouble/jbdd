/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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
