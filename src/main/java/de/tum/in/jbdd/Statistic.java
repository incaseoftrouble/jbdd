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

import de.tum.in.jbdd.StatisticDescription.Kind;
import java.util.List;

/**
 * A statistics key, declared once next to what it reports and written only through a {@link StatisticsReport}, so
 * that a key and its description cannot part. The name is relative to the scope it is written in; {@code {name}} in
 * the text stands for the scope's name (the cache's, say).
 */
class Statistic {
    final String name;
    final Kind kind;
    final String text;

    private Statistic(String name, Kind kind, String text) {
        this.name = name;
        this.kind = kind;
        this.text = text;
    }

    static Statistic counter(String name, String text) {
        return new Statistic(name, Kind.COUNTER, text);
    }

    static Statistic gauge(String name, String text) {
        return new Statistic(name, Kind.GAUGE, text);
    }

    static Statistic maximum(String name, String text) {
        return new Statistic(name, Kind.MAXIMUM, text);
    }

    static Ratio ratio(String name, String text, List<Statistic> numerator, List<Statistic> denominator) {
        return new Ratio(name, text, numerator, denominator);
    }

    /** The numerator's sum over the denominator's, of the same scope; its value is computed from them, never given. */
    static final class Ratio extends Statistic {
        final List<Statistic> numerator;
        final List<Statistic> denominator;

        private Ratio(String name, String text, List<Statistic> numerator, List<Statistic> denominator) {
            super(name, Kind.RATIO, text);
            assert !denominator.isEmpty();
            this.numerator = List.copyOf(numerator);
            this.denominator = List.copyOf(denominator);
        }
    }
}
