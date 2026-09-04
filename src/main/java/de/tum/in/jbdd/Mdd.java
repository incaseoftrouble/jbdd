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
 * What {@link MddImpl} offers: the functional interface plus the node view. Unlike {@link Bdd} and
 * {@link MtBdd} it is not {@link ReorderableDd} - MDDs do not reorder, so a variable is its own level
 * and there is nothing to say about the order.
 *
 * <p>Everything reachable through {@link NodeBasedDd} describes how this implementation happens to
 * represent functions, and may change between versions.
 */
public interface Mdd extends MultiValuedDecisionDiagram, NodeBasedDd {}
