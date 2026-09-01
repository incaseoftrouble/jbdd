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
import java.util.Map;

/** Obtained from {@link BinaryFactoryContext#bddSets()} - there's no standalone way to build one,
 * since every {@code BddSetFactory} needs a {@link Bdd} to share (see {@link BddContext}). */
public interface BddSetFactory {
    /** The empty set. */
    BddSet empty();

    /** The set of all valuations. */
    BddSet universe();

    /** The set of all valuations where {@code variable} is true. */
    BddSet var(int variable);

    /** {@link #universe()} if {@code true}, {@link #empty()} otherwise. */
    BddSet of(boolean booleanConstant);

    /** The single-element set containing {@code valuation} restricted to {@code support}. */
    BddSet of(BitSet valuation, BitSet support);

    /** The union of the single-element sets ({@link #of(BitSet, BitSet)}) given by {@code valuations}. */
    default BddSet of(Iterable<BitSet> valuations, BitSet support) {
        BddSet result = empty();
        for (BitSet valuation : valuations) {
            result = result.union(of(valuation, support));
        }
        return result;
    }

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

    Map<String, Object> statistics();
}
