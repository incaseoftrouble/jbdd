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

import java.util.BitSet;

public interface BddSetFactory {
    static BddSetFactory create() {
        return new BddSetFactoryImpl();
    }

    static BddSetFactory create(int variables) {
        return new BddSetFactoryImpl(variables);
    }

    BddSet empty();

    BddSet universe();

    BddSet var(int variable);

    BddSet of(boolean booleanConstant);

    BddSet of(BitSet valuation, BitSet support);

    default BddSet union(BddSet... sets) {
        if (sets.length == 0) {
            return empty();
        }
        BddSet set = sets[0];
        for (int i = 1; i < sets.length; i++) {
            set = set.union(sets[i]);
        }
        return set;
    }

    default BddSet intersection(BddSet... sets) {
        if (sets.length == 0) {
            return universe();
        }
        BddSet set = sets[0];
        for (int i = 1; i < sets.length; i++) {
            set = set.intersection(sets[i]);
        }
        return set;
    }

    String statistics();
}
