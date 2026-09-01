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

/**
 * Scales how much data the generated suites ({@link BddTheories}, {@link MtBddTheories}) produce, through
 * the system property {@code jbdd.test.scale} (default {@code 1.0}, forwarded by {@code build.gradle.kts}
 * from {@code -Pjbdd.test.scale}).
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases") // A helper
final class TestProfile {
    private static final String PROPERTY = "jbdd.test.scale";
    private static final double SCALE = readScale();

    private TestProfile() {
        // empty
    }

    private static double readScale() {
        String property = System.getProperty(PROPERTY);
        if (property == null) {
            return 1.0;
        }
        double scale;
        try {
            scale = Double.parseDouble(property);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PROPERTY + " is not a number: " + property, e);
        }
        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException(PROPERTY + " must be finite and positive, got " + property);
        }
        return scale;
    }

    static double scale() {
        return SCALE;
    }

    static int scaled(int count) {
        return scaled(count, 1);
    }

    static int scaled(int count, int minimum) {
        assert 0 < minimum && minimum <= count;
        return Math.max(minimum, (int) (count * SCALE));
    }
}
