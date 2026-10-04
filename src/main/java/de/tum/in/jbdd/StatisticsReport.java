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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where the structures write their {@link Statistic}s: one snapshot, and optionally the description of each key. A
 * scope - this report or one made from it - puts its prefix in front of every key written through it, says what
 * {@code {name}} in a text stands for, and holds the parts its ratios are computed from.
 * Public only because the structures implementing {@link StatisticsReporter} are; nothing outside the package can
 * make or write one.
 */
public final class StatisticsReport {
    private final Map<String, Object> values;
    private final @Nullable Map<String, StatisticDescription> descriptions;
    private final String prefix;
    private final String name;
    // What this scope wrote, for the ratios over it.
    private final Map<Statistic, Number> written = new HashMap<>();

    private StatisticsReport(
            Map<String, Object> values,
            @Nullable Map<String, StatisticDescription> descriptions,
            String prefix,
            String name) {
        this.values = values;
        this.descriptions = descriptions;
        this.prefix = prefix;
        this.name = name;
    }

    static StatisticsReport values() {
        return new StatisticsReport(new HashMap<>(), null, "", "");
    }

    static StatisticsReport describing() {
        return new StatisticsReport(new HashMap<>(), new HashMap<>(), "", "");
    }

    /** A scope whose keys also start with {@code prefix}, about the same thing. */
    StatisticsReport prefixed(String prefix) {
        return new StatisticsReport(values, descriptions, this.prefix + prefix, name);
    }

    /** A scope about the thing called {@code name}: its keys also start with {@code prefix}, the name and '_'. */
    StatisticsReport about(String prefix, String name) {
        return new StatisticsReport(values, descriptions, this.prefix + prefix + name + '_', name);
    }

    /** The scope of a configuration's {@code name}, which leads every key of its structures. */
    StatisticsReport named(String name) {
        return name.isEmpty() ? this : prefixed(name + '_');
    }

    void put(Statistic statistic, Number value) {
        assert statistic.kind != StatisticDescription.Kind.RATIO;
        written.put(statistic, value);
        write(statistic, value, List.of(), List.of());
    }

    void ratio(Statistic.Ratio ratio) {
        long numerator = sum(ratio.numerator);
        long denominator = sum(ratio.denominator);
        write(ratio, Util.ratio(numerator, denominator), keys(ratio.numerator), keys(ratio.denominator));
    }

    Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }

    Map<String, StatisticDescription> descriptions() {
        assert descriptions != null;
        return Map.copyOf(descriptions);
    }

    private void write(Statistic statistic, Object value, List<String> numerator, List<String> denominator) {
        String key = prefix + statistic.name;
        Object previous = values.put(key, value);
        assert previous == null : "reported twice: " + key;
        if (descriptions != null) {
            String text = statistic.text.replace("{name}", name);
            descriptions.put(key, new StatisticDescription(statistic.kind, text, numerator, denominator));
        }
    }

    private long sum(List<Statistic> parts) {
        long sum = 0;
        for (Statistic part : parts) {
            Number value = written.get(part);
            assert value != null : "ratio part not written before: " + prefix + part.name;
            sum += value.longValue();
        }
        return sum;
    }

    private List<String> keys(List<Statistic> parts) {
        List<String> keys = new ArrayList<>(parts.size());
        for (Statistic part : parts) {
            keys.add(prefix + part.name);
        }
        return keys;
    }
}
