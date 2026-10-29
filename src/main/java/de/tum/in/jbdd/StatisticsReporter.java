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

/** A structure writing its statistics into a {@link StatisticsReport}. */
@SuppressWarnings({"PMD.ImplicitFunctionalInterface", "InterfaceMayBeAnnotatedFunctional"
}) // implemented by the structures, never a lambda
interface StatisticsReporter {
    /** Writes this structure's statistics; {@code detail} matters only to what has a pass to offer. */
    void report(StatisticsReport report, StatisticsDetail detail);

    /** A reporter answering for a complete key space: its snapshot and its descriptions come from one report. */
    interface Source extends StatisticsSource, StatisticsReporter {
        @Override
        default Map<String, Object> statistics(StatisticsDetail detail) {
            StatisticsReport report = StatisticsReport.values();
            report(report, detail);
            return report.snapshot();
        }

        @Override
        default Map<String, StatisticDescription> describeStatistics() {
            StatisticsReport report = StatisticsReport.describing();
            report(report, StatisticsDetail.FULL);
            return report.descriptions();
        }
    }
}
