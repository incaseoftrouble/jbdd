/*
 * This file is part of JBDD (https://github.com/incaseoftrouble/jbdd).
 * Copyright (c) 2023 Tobias Meggendorfer.
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
 * A caller's object bound to every carrier ({@code S}) of one factory, created by
 * {@link BinaryFactoryContext#attachToSets}. Carriers are canonical, so the object is too.
 */
@FunctionalInterface
public interface Attachment<S, A> {
    /** The object bound to {@code carrier}, built on the first request and kept as long as the carrier lives. */
    A of(S carrier);
}
