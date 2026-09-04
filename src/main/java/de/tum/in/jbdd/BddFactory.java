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
        return BddContext.create(configuration).bdd();
    }

    public static MtBdd buildMtBdd() {
        return buildMtBdd(ImmutableBddConfiguration.builder().build());
    }

    public static MtBdd buildMtBdd(BddConfiguration configuration) {
        return BddContext.create(configuration).mtBdd();
    }

    public static Mdd buildMdd() {
        return buildMdd(ImmutableBddConfiguration.builder().build());
    }

    /**
     * An MDD stands alone - unlike the MTBDD it shares no variable order with anything - so it needs no
     * {@link BddContext}, and there is nothing to hand out but the diagram itself.
     */
    public static Mdd buildMdd(BddConfiguration configuration) {
        return new MddImpl(configuration);
    }
}
