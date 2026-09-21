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

import static de.tum.in.jbdd.BooleanBase.EMPTY_INT_ARRAY;
import static de.tum.in.jbdd.Preconditions.checkState;

import java.util.HashMap;
import java.util.Map;

/**
 * The two diagrams sharing one variable universe, and the creation of the variables in it. The order
 * those variables are laid out in is {@link DdVariableOrderImpl}'s; everything that moves it lives there.
 */
@SuppressWarnings("AssertWithSideEffects")
public final class DdContextImpl implements DdContext, StatisticsSource {
    private final BddConfiguration configuration;
    private final DdVariableOrderImpl order;
    private final BddImpl bdd;
    private final MtBddImpl mtbdd;

    DdContextImpl(BddConfiguration configuration) {
        this.configuration = configuration;
        this.order = new DdVariableOrderImpl(this);
        this.bdd = new BddImpl(this);
        this.mtbdd = new MtBddImpl(this);

        if (configuration.logStatisticsOnShutdown()) {
            // Weakly held, which is sound because either diagram reaches back here through the order.
            Util.registerForCleanupStatistics(this, configuration.name());
        }
    }

    DdContextImpl(BddConfiguration configuration, int variables) {
        this(configuration);
        bdd.createVariables(variables);
    }

    BddConfiguration configuration() {
        return configuration;
    }

    @Override
    public BddImpl bdd() {
        return bdd;
    }

    @Override
    public MtBddImpl mtBdd() {
        return mtbdd;
    }

    @Override
    public DdVariableOrderImpl variableOrder() {
        return order;
    }

    // Variables

    int createVariable() {
        assert bdd.accessGuard.acquire();
        int variable = order.appendVariables(1);
        int variableNode = bdd.makeVariableNode(variable, variable);

        // Appending at the bottom is an insertion at the level the variable ends up at, with nothing
        // below to push down - which is what makes one event enough for all four of these.
        order.notifyVariablesInserted(variable, 1);

        assert bdd.accessGuard.release();
        return variableNode;
    }

    int[] createVariables(int count) {
        if (count == 0) {
            return EMPTY_INT_ARRAY;
        }
        if (count == 1) {
            return new int[] {createVariable()};
        }

        assert bdd.accessGuard.acquire();
        int firstVariable = order.appendVariables(count);
        bdd.ensureVariableNodeCapacity(firstVariable + count);

        int[] newVariableNodes = new int[count];
        for (int index = 0; index < count; index++) {
            int variable = firstVariable + index;
            newVariableNodes[index] = bdd.makeVariableNode(variable, variable);
        }

        order.notifyVariablesInserted(firstVariable, count);

        assert bdd.accessGuard.release();
        return newVariableNodes;
    }

    @Override
    public int createVariableAtLevel(int level) {
        checkState(0 <= level && level <= order.numberOfVariables(), "Level %s out of range", level);
        assert bdd.accessGuard.acquire();
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();

        int variable = order.insertVariables(level, 1);
        int variableNode = bdd.makeVariableNode(variable, level);

        order.notifyVariablesInserted(level, 1);

        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();
        assert bdd.check();
        assert bdd.accessGuard.release();
        return variableNode;
    }

    @Override
    public int[] createVariablesAtLevel(int level, int count) {
        checkState(0 <= level && level <= order.numberOfVariables(), "Level %s out of range", level);
        checkState(0 <= count, "Negative count %s", count);
        if (count == 0) {
            return EMPTY_INT_ARRAY;
        }
        if (count == 1) {
            return new int[] {createVariableAtLevel(level)};
        }
        assert bdd.accessGuard.acquire();
        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();

        int firstVariable = order.insertVariables(level, count);
        bdd.ensureVariableNodeCapacity(firstVariable + count);

        int[] newVariableNodes = new int[count];
        for (int index = 0; index < count; index++) {
            newVariableNodes[index] = bdd.makeVariableNode(firstVariable + index, level + index);
        }

        order.notifyVariablesInserted(level, count);

        assert bdd.table().workStacksEmpty() && mtbdd.table().workStacksEmpty();
        assert bdd.check();
        assert bdd.accessGuard.release();
        return newVariableNodes;
    }

    @Override
    public Map<String, Object> statistics() {
        Map<String, Object> statistics = new HashMap<>(bdd.statistics());
        Map<String, Object> mtbddStatistics = mtbdd.statistics();
        assert mtbddStatistics.keySet().stream().noneMatch(statistics::containsKey)
                : "The two diagrams report overlapping statistics";
        statistics.putAll(mtbddStatistics);
        return Map.copyOf(statistics);
    }

    @Override
    public String toString() {
        return String.format("Context{%s}", bdd);
    }
}
