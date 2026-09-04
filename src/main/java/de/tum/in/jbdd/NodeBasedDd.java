/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2024 Tobias Meggendorfer.
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

public interface NodeBasedDd extends DecisionDiagram {
    /**
     * Returns the <em>node</em> which is used to represent the given {@code function} internally
     *
     * <p>This is an implementation detail of decision diagrams, exposed to allow for
     * interaction tailored towards the underlying structure.</p>
     */
    int nodeFor(int function);

    /**
     * Returns the reference count of the given node or {@literal -1} if this number can't be
     * accurately determined (e.g., when a node is saturated).
     *
     * <p>This is an implementation detail of decision diagrams, exposed to allow for
     * interaction tailored towards the underlying structure.</p>
     */
    int nodeReferenceCount(int node);

    boolean isSaturatedNode(int node);

    /**
     * Returns whether the given {@code node} is referenced.
     */
    default boolean nodeIsReferenced(int node) {
        return nodeReferenceCount(nodeFor(node)) > 0;
    }

    /**
     * Returns the number of referenced nodes.
     */
    int referencedNodeCount();

    int nodeCount();

    /**
     * Returns the number of decision nodes used to represent this function in the decision diagram
     */
    int size(int function);

    /**
     * Tells the diagram that now is a good time to reclaim what is no longer reachable from a
     * referenced function.
     *
     * <p>The other half of the contract {@link #reference(int)} opens: a dereferenced function is not
     * gone, only collectable, and the diagram decides on its own when to act on that. This is a hint at
     * that decision, not a command - an implementation is free to conclude that collecting now is not
     * worth it and reclaim nothing. It is in any case a semantic no-op: no function changes its
     * meaning, no handle is invalidated, so a caller who does not care about the memory never has to
     * call it.
     *
     * @return How many nodes were reclaimed - zero if the implementation declined.
     */
    int gc();
}
