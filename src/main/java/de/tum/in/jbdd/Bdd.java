/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2017-2023 Tobias Meggendorfer.
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
 * A binary decision diagram which can also be reordered.
 *
 * <p>This, not {@link BinaryDecisionDiagram}, is what the library actually hands out: the functional
 * interface plus the two implementation-side ones. Everything reachable through {@link NodeBasedDd} is
 * offered because it is genuinely useful to a structure-aware caller, not because it is stable - it
 * describes how this implementation happens to represent functions, and may change between versions.
 */
public interface Bdd extends BinaryDecisionDiagram, ReorderableDd, NodeBasedDd {}
