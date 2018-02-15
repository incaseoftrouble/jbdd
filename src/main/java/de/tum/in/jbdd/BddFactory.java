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

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class BddFactory {
  private BddFactory() {
  }

  public static Bdd buildBdd(int nodeSize) {
    return new BddImpl(nodeSize);
  }

  public static Bdd buildBdd(int nodeSize, BddConfiguration configuration) {
    return new BddImpl(nodeSize, configuration);
  }

  public static SynchronizedBdd buildSynchronizedBdd(int nodeSize) {
    return synchronize(new BddImpl(nodeSize));
  }

  public static SynchronizedBdd buildSynchronizedBdd(int nodeSize, BddConfiguration configuration) {
    return synchronize(new BddImpl(nodeSize, configuration));
  }

  private static SynchronizedBdd synchronize(BddImpl bdd) {
    ReadWriteLock lock = new ReentrantReadWriteLock();
    return new SynchronizedBdd(bdd, lock);
  }
}
