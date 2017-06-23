/*
 * Copyright (C) 2017 (See AUTHORS)
 *
 * This file is part of JBDD.
 *
 * JBDD is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JBDD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JBDD.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.tum.in.jdd;

import org.junit.Test;

/**
 * A collection of tests motivated by regressions.
 */
public class BddRegressionTest {
  @Test
  public void testReferenceOverflow() {
    BddImpl bdd = new BddImpl(2);
    int v1 = bdd.createVariable();
    int v2 = bdd.createVariable();
    int and = bdd.and(v1, v2);

    for (int i = 0; i < Integer.MAX_VALUE; i++) {
      bdd.reference(and);
    }

    for (int i = 0; i < Integer.MAX_VALUE; i++) {
      bdd.dereference(and);
    }
  }
}
