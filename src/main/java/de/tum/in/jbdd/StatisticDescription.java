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

import java.util.List;
import java.util.Objects;

/**
 * What a statistics key means ({@link StatisticsSource#describeStatistics}): its kind, which says how readings
 * combine, a sentence, and for a ratio the keys of the same snapshot it is computed from.
 */
public final class StatisticDescription {
    private final Kind kind;
    private final String text;
    private final List<String> numerator;
    private final List<String> denominator;

    StatisticDescription(Kind kind, String text, List<String> numerator, List<String> denominator) {
        assert (kind == Kind.RATIO) == !denominator.isEmpty();
        this.kind = kind;
        this.text = text;
        this.numerator = List.copyOf(numerator);
        this.denominator = List.copyOf(denominator);
    }

    public Kind kind() {
        return kind;
    }

    /** One sentence, lower case and without a full stop. */
    public String text() {
        return text;
    }

    /** For a {@link Kind#RATIO}, the keys whose sum is divided; empty otherwise. */
    public List<String> numerator() {
        return numerator;
    }

    /** For a {@link Kind#RATIO}, the keys whose sum divides; empty otherwise. */
    public List<String> denominator() {
        return denominator;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StatisticDescription)) {
            return false;
        }
        StatisticDescription other = (StatisticDescription) o;
        return kind == other.kind
                && text.equals(other.text)
                && numerator.equals(other.numerator)
                && denominator.equals(other.denominator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind.name(), text, numerator, denominator);
    }

    @Override
    public String toString() {
        return kind == Kind.RATIO
                ? String.format("%s(%s / %s) %s", kind, numerator, denominator, text)
                : String.format("%s %s", kind, text);
    }

    /** How readings of a key combine - across the snapshots of several runs, say. */
    public enum Kind {
        /** A total since the structure was built: summed; two snapshots of one run differ by what happened between. */
        COUNTER,
        /** The value at the time of the snapshot: the last or the mean, never a sum over time. */
        GAUGE,
        /** The largest value so far: the maximum. */
        MAXIMUM,
        /** The numerator's sum over the denominator's: recomputed from the combined parts, never averaged. */
        RATIO
    }
}
