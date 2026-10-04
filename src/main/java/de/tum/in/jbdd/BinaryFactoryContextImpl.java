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

final class BinaryFactoryContextImpl implements BinaryFactoryContext, StatisticsReporter.Source {
    private final DdContextImpl context;
    private final BddSetFactoryImpl bddSets;
    private final BddMapFactoryImpl bddMaps;

    BinaryFactoryContextImpl(DdContextImpl context) {
        this.context = context;
        bddSets = new BddSetFactoryImpl(context.bdd());
        bddMaps = new BddMapFactoryImpl(bddSets);
    }

    @Override
    public Bdd bdd() {
        return context.bdd();
    }

    @Override
    public MtBdd mtBdd() {
        return context.mtBdd();
    }

    @Override
    public DdVariableOrder variableOrder() {
        return context.variableOrder();
    }

    @Override
    public int createVariableAtLevel(int level) {
        return context.createVariableAtLevel(level);
    }

    @Override
    public int[] createVariablesAtLevel(int level, int count) {
        return context.createVariablesAtLevel(level, count);
    }

    @Override
    public void report(StatisticsReport report, StatisticsDetail detail) {
        context.report(report, detail);
        bddSets.report(report.about("", "set"), detail);
        bddMaps.report(report.about("", "map"), detail);
    }

    @Override
    public BddSetFactory bddSets() {
        return bddSets;
    }

    @Override
    public BddMapFactory bddMaps() {
        return bddMaps;
    }

    @Override
    public <A> Attachment<BddSet, A> attachToSets(Function<? super BddSet, ? extends A> constructor) {
        return bddSets.attach(constructor);
    }

    @Override
    public String toString() {
        return String.format("FactoryContext{%s}", context);
    }
}
