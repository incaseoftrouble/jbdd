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
 * Reports the statistics of one structure. Not the public way to ask - that is {@link DdContext} for a
 * BDD/MTBDD pair and {@link Mdd} for an MDD, both of which report a complete key space. This is what those
 * are assembled from, and what {@link Util#registerForCleanupStatistics} logs.
 */
@FunctionalInterface
interface StatisticsSource {
    Map<String, Object> statistics();
}
