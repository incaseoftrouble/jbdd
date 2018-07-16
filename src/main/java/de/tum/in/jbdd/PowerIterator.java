package de.tum.in.jbdd;

import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

final class PowerIterator implements Iterator<BitSet> {
  private final BitSet iteration;
  private final int size;
  private int numSetBits = -1;

  PowerIterator(int size) {
    this.size = size;
    this.iteration = new BitSet(size);
  }

  @Override
  public boolean hasNext() {
    return numSetBits < size;
  }

  @Override
  public BitSet next() {
    if (numSetBits == -1) {
      numSetBits = 0;
      return iteration;
    }

    if (numSetBits == size) {
      throw new NoSuchElementException("No next element");
    }

    for (int index = 0; index < size; index++) {
      if (iteration.get(index)) {
        iteration.clear(index);
        numSetBits -= 1;
      } else {
        iteration.set(index);
        numSetBits += 1;
        break;
      }
    }

    return iteration;
  }
}
