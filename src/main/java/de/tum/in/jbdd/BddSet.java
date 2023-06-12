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
