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
package de.tum.in.jbdd.collections;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link NatSet#boxed()}: {@code set} as a {@code Set<Integer>}, modifiable exactly if it is a {@link MutableNatSet}.
 * Its equality and hash code are {@link java.util.Set}'s, not {@link NatSet}'s.
 */
final class BoxedNatSet extends AbstractSet<Integer> {
    private final NatSet set;

    BoxedNatSet(NatSet set) {
        this.set = set;
    }

    /** The set this is a view of. */
    NatSet set() {
        return set;
    }

    // Unmodifiable views throw on every mutator, whether or not it would change anything, as the JDK's do.
    private MutableNatSet mutable() {
        if (set instanceof MutableNatSet) {
            return (MutableNatSet) set;
        }
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Object o) {
        return o instanceof Integer && set.contains((Integer) o);
    }

    @Override
    public int size() {
        return set.size();
    }

    @Override
    public boolean isEmpty() {
        return set.isEmpty();
    }

    @Override
    public Iterator<Integer> iterator() {
        return set.iterator();
    }

    @Override
    public void forEach(Consumer<? super Integer> action) {
        set.forEach(action::accept);
    }

    @Override
    public boolean add(Integer element) {
        MutableNatSet target = mutable();
        if (target.contains(element)) {
            return false;
        }
        target.set(element);
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends Integer> c) {
        MutableNatSet ours = mutable();
        int size = ours.size();
        if (c instanceof BoxedNatSet) {
            ours.or(((BoxedNatSet) c).set);
        } else {
            c.forEach(ours::set);
        }
        return ours.size() != size;
    }

    @Override
    public boolean remove(Object o) {
        MutableNatSet target = mutable();
        if (!contains(o)) {
            return false;
        }
        target.clear((Integer) o);
        return true;
    }

    // The iterator cannot remove, so the bulk removals go through the set.

    @Override
    public boolean removeIf(Predicate<? super Integer> filter) {
        return mutable().removeIf(filter::test);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        MutableNatSet ours = mutable();
        int size = ours.size();
        if (c instanceof BoxedNatSet) {
            ours.andNot(((BoxedNatSet) c).set);
        } else {
            c.forEach(i -> {
                if (i instanceof Integer && (Integer) i >= 0) {
                    ours.clear((Integer) i);
                }
            });
        }
        return ours.size() != size;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        MutableNatSet ours = mutable();
        int size = ours.size();

        NatSet other;
        if (c instanceof BoxedNatSet) {
            other = ((BoxedNatSet) c).set;
        } else {
            int[] array = new int[c.size()];
            int index = 0;
            for (Object o : c) {
                if (o instanceof Integer) {
                    array[index] = (Integer) o;
                    index += 1;
                }
            }
            // TODO double copy
            other = ImmutableNatSet.of(index == array.length ? array : Arrays.copyOf(array, index));
        }
        ours.and(other);
        return ours.size() != size;
    }

    @Override
    public void clear() {
        mutable().clear();
    }
}
