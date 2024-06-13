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

public interface NodeBasedDecisionDiagram extends DecisionDiagram {
    /**
     * Returns the <em>node</em> which is used to represent the given {@code function} internally or {}
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

    int referencedNodeCount();

    int nodeCount();
}
