/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2018-2023 Tobias Meggendorfer.
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

import java.math.BigInteger;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Symbolic representation of a {@code Set<BitSet>}.
 */
public interface BddSet {
    boolean isEmpty();

    boolean isUniverse();

    boolean contains(BitSet valuation);

    boolean containsAll(BddSet valuationSet);

    Optional<BitSet> element();

    BddSet complement();

    BddSet union(BddSet other);

    BddSet intersection(BddSet other);

    BddSet exists(BitSet quantifiedVariables);

    BddSet relabelVariables(IntUnaryOperator mapping);

    BddSet replaceVariables(IntFunction<BddSet> mapping);

    BitSet support();

    Iterator<BitSet> iterator(BitSet support);

    BigInteger size(BitSet support);

    void forEach(BitSet support, Consumer<? super BitSet> consumer);
}
