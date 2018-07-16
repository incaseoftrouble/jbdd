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

import java.util.Random;

final class HashUtil {
  // Murmur3 adapted from https://github.com/tnm/murmurHash-java/

  private static final int BYTE_MASK = 0xff;
  private static final int FNV_OFFSET = 0x811c9dc5;
  private static final int FNV_PRIME = 0x1000193;

  private static final int MURMUR3_C2 = 0x1b873593;
  private static final int MURMUR3_C1 = 0xcc9e2d51;
  static final int MURMUR3_SEED = new Random().nextInt();

  private HashUtil() {
  }

  static int hash(int key) {
    return murmur3Hash(key);
  }

  static int hash(long key) {
    return murmur3Hash(key);
  }

  static int hash(long firstKey, int secondKey) {
    return murmur3Hash(firstKey, secondKey);
  }

  static int hash(long firstKey, long secondKey) {
    return murmur3Hash(firstKey, secondKey);
  }

  static int hash(int key, int[] keys) {
    return murmur3Hash(key, keys);
  }


  // MURMUR3

  static int murmur3Hash(int key) {
    int h = MURMUR3_SEED;
    h = murmur3round(h, key);
    h ^= 4;
    return murmur3mix(h);
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  static int murmur3Hash(long key) {
    int h = MURMUR3_SEED;
    h = murmur3round(h, (int) (key >>> 32));
    h = murmur3round(h, (int) key);
    h ^= 8;
    return murmur3mix(h);
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  static int murmur3Hash(long firstKey, int secondKey) {
    int h = MURMUR3_SEED;
    h = murmur3round(h, (int) (firstKey >>> 32));
    h = murmur3round(h, (int) firstKey);
    h = murmur3round(h, secondKey);
    h ^= 12;
    return murmur3mix(h);
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  static int murmur3Hash(long firstKey, long secondKey) {
    int h = MURMUR3_SEED;
    h = murmur3round(h, (int) (firstKey >>> 32));
    h = murmur3round(h, (int) firstKey);
    h = murmur3round(h, (int) (secondKey >>> 32));
    h = murmur3round(h, (int) secondKey);
    h ^= 16;
    return murmur3mix(h);
  }

  static int murmur3Hash(int key, int[] keys) {
    int h = MURMUR3_SEED;
    h = murmur3round(h, key);
    for (int k : keys) {
      h = murmur3round(h, k);
    }
    h ^= 4 * keys.length + 4;
    return murmur3mix(h);
  }


  private static int murmur3round(int h, int k) {
    int key = k;
    key *= MURMUR3_C1;
    key = (key << 15) | (key >>> 17);
    key *= MURMUR3_C2;

    int hash = h;
    hash ^= key;
    hash = (hash << 13) | (hash >>> 19);
    return hash * 5 + 0xe6546b64;
  }

  private static int murmur3mix(int h) {
    int mix = h;
    mix ^= mix >>> 16;
    mix *= 0x85ebca6b;
    mix ^= mix >>> 13;
    mix *= 0xc2b2ae35;
    mix ^= mix >>> 16;
    return mix;
  }


  // FNV1A

  static int fnv1aHash(int key) {
    return fnv1aRound(FNV_OFFSET, key);
  }

  static int fnv1aHash(long key) {
    return fnv1aRound(FNV_OFFSET, key);
  }

  static int fnv1aHash(long firstKey, int secondKey) {
    return fnv1aRound(fnv1aRound(FNV_OFFSET, firstKey), secondKey);
  }

  static int fnv1aHash(long firstKey, long secondKey) {
    return fnv1aRound(fnv1aRound(FNV_OFFSET, firstKey), secondKey);
  }

  static int fnv1aHash(int key, int[] keys) {
    int hash = fnv1aRound(FNV_OFFSET, key);
    for (int arrayKey : keys) {
      hash = fnv1aRound(hash, arrayKey);
    }
    return hash;
  }


  private static int fnv1aRound(int h, int k) {
    return ((((h ^ ((k >>> 24) & BYTE_MASK)) * FNV_PRIME
        ^ ((k >>> 16) & BYTE_MASK)) * FNV_PRIME
        ^ ((k >>> 8) & BYTE_MASK)) * FNV_PRIME
        ^ (k & BYTE_MASK)) * FNV_PRIME;
  }

  @SuppressWarnings("NumericCastThatLosesPrecision")
  private static int fnv1aRound(int h, long k) {
    return fnv1aRound(fnv1aRound(h, (int) (k >>> Integer.SIZE)), (int) k);
  }
}