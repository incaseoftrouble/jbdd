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
