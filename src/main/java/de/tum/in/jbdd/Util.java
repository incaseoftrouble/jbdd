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

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

final class Util {
    private static final Logger logger = Logger.getLogger(Util.class.getName());

    @SuppressWarnings("StaticCollection")
    private static final Collection<CleanupStatisticsRef> registeredStatistics = new ConcurrentLinkedDeque<>();

    private Util() {}

    static int mod(int value, int modulus) {
        int val = value % modulus;
        return val < 0 ? val + modulus : val;
    }

    static boolean binarySymmetricWellOrdered(int node1, int node2) {
        return node1 <= node2;
    }

    /** Sorted {@code key=value} lines, which is what a statistics map is read as. */
    static String formatStatistics(Map<String, Object> statistics) {
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

    static void registerForCleanupStatistics(StatisticsSource owner, @Nullable String name) {
        if (!logger.isLoggable(Level.INFO)) {
            return;
        }
        ShutdownHookLazyHolder.init();
        registeredStatistics.add(new CleanupStatisticsRef(owner, name));
    }

    /** Prints statistics for every registered owner that is still reachable - useful on demand, not just
     * from the shutdown hook. Opportunistically drops entries whose owner has already been collected. */
    static void printAllRegisteredStatistics() {
        if (!logger.isLoggable(Level.INFO)) {
            return;
        }
        registeredStatistics.removeIf(ref -> !ref.log());
    }

    static int[] protectNodes(DecisionDiagram dd, int[] resolvedMapping) {
        int[] toProtect = new int[resolvedMapping.length];
        int count = 0;
        for (int node : resolvedMapping) {
            assert dd.isValidFunction(node);
            if (!dd.isUnmanaged(node)) {
                toProtect[count] = node;
                count++;
                dd.reference(node);
            }
        }
        return count == toProtect.length ? toProtect : Arrays.copyOf(toProtect, count);
    }

    static double ratio(long value, long total) {
        return total == 0 ? 0.0 : value / (double) total;
    }

    private static final class CleanupStatisticsRef extends WeakReference<StatisticsSource> {
        private final String label;

        CleanupStatisticsRef(StatisticsSource owner, @Nullable String name) {
            super(owner);
            this.label = String.format(
                    "%s@%s",
                    name == null || name.isEmpty() ? owner.getClass().getSimpleName() : name,
                    Integer.toHexString(System.identityHashCode(owner)));
        }

        boolean log() {
            StatisticsSource owner = get();
            if (owner == null) {
                return false;
            }
            Map<String, Object> statistics = owner.statistics();
            logger.info(() -> String.format("CACHE STATISTICS (%s):\n%s", label, formatStatistics(statistics)));
            return true;
        }
    }

    private static final class ShutdownHookLazyHolder {
        private static final AtomicBoolean registered = new AtomicBoolean();

        static void init() {
            if (registered.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(new Thread(Util::printAllRegisteredStatistics));
            }
        }
    }
}
