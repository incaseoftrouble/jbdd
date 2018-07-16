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

package de.tum.in.jbdd;

public final class BddFactory {
  private BddFactory() {
  }

  public static Bdd buildBdd(int nodeSize) {
    return new BddIterative(nodeSize, ImmutableBddConfiguration.builder().build());
  }

  public static Bdd buildBddRecursive(int nodeSize, BddConfiguration configuration) {
    BddRecursive bdd = new BddRecursive(nodeSize, configuration);
    return configuration.threadSafetyCheck()
        ? new CheckedBdd(bdd)
        : bdd;
  }

  public static Bdd buildBddIterative(int nodeSize, BddConfiguration configuration) {
    BddIterative bdd = new BddIterative(nodeSize, configuration);
    return configuration.threadSafetyCheck()
        ? new CheckedBdd(bdd)
        : bdd;
  }
}
