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
import java.util.stream.Collectors;

/** Helpers over the maps a {@link StatisticsSource} reports. */
public final class Statistics {
    private Statistics() {}

    /** Sorted {@code key=value} lines, which is what a statistics map is read as. */
    public static String formatStatistics(Map<String, Object> statistics) {
        return statistics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> String.format("%s=%s", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    /** Puts {@code name} in front of every key, so several structures can be read side by side. */
    static Map<String, Object> prefixStatistics(String name, Map<String, Object> statistics) {
        if (name.isEmpty()) {
            return statistics;
        }
        return statistics.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        e -> String.format("%s_%s", name, e.getKey()), Map.Entry::getValue));
    }
}
