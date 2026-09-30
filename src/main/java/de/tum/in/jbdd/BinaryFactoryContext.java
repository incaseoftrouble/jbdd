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

import java.util.function.Function;

/**
 * A {@link DdContext} together with the object-oriented views over it - the {@link BddSetFactory} and
 * the {@link BddMapFactory}, which are one per context because they share its diagrams.
 */
public interface BinaryFactoryContext extends DdContext {
    static BinaryFactoryContext create() {
        return create(ImmutableBddConfiguration.builder().build());
    }

    static BinaryFactoryContext create(BddConfiguration configuration) {
        return new BinaryFactoryContextImpl(new DdContextImpl(configuration));
    }

    /** Like {@link #create()}, with {@code variables} many variables already declared. */
    static BinaryFactoryContext create(BddConfiguration configuration, int variables) {
        return new BinaryFactoryContextImpl(new DdContextImpl(configuration, variables));
    }

    /** The unique {@link BddSetFactory}. */
    BddSetFactory bddSets();

    /** The unique {@link BddMapFactory}. */
    BddMapFactory bddMaps();

    /**
     * Binds an object to every set of {@link #bddSets()}: {@link Attachment#of} builds it with
     * {@code constructor} the first time a set is asked for it and hands out that same object for as long as
     * the set lives. Sets are canonical per function, so the attachment is too; set and attachment may hold each
     * other, and are collected together. The constructor runs outside any operation and may start one, but must
     * not ask for the attachment of the set it is building.
     *
     * @throws IllegalStateException if the sets already have an attachment - there is one slot per set
     */
    <A> Attachment<BddSet, A> attachToSets(Function<? super BddSet, ? extends A> constructor);
}
