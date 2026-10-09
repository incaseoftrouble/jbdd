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

import java.util.Map;

/**
 * Reports statistics of an object. The keys may change between versions; the values are numbers.
 */
public interface StatisticsSource {
    /**
     * A snapshot of the statistics, read to the given {@code detail}.
     */
    Map<String, Object> statistics(StatisticsDetail detail);

    /** {@link #statistics(StatisticsDetail)} in {@link StatisticsDetail#FULL full}. */
    default Map<String, Object> statistics() {
        return statistics(StatisticsDetail.FULL);
    }

    /**
     * What each key of {@link #statistics()} means: its kind, which says how readings combine, and a sentence.
     */
    Map<String, StatisticDescription> describeStatistics();
}
