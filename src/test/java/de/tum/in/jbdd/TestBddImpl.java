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

@SuppressWarnings("PMD.TestClassWithoutTestCases")
class TestBddImpl extends DelegatingBdd implements TestBdd {
    public TestBddImpl(BddImpl delegate) {
        super(delegate);
    }

    @Override
    protected BddImpl delegate() {
        return (BddImpl) super.delegate();
    }

    @Override
    public void invalidateCache() {
        delegate().invalidateCache();
    }

    @Override
    public boolean isValidFunction(int function) {
        return delegate().isValidFunction(function);
    }

    @Override
    public boolean isValidNonConstantFunction(int function) {
        return delegate().isValidNonConstantFunction(function);
    }

    @Override
    public boolean check() {
        return delegate().check();
    }

    @Override
    public String treeToString(int function) {
        return delegate().table().treeToString(function);
    }

    @Override
    public int nodeFor(int function) {
        return delegate().nodeFor(function);
    }

    @Override
    public int nodeReferenceCount(int node) {
        return delegate().nodeReferenceCount(node);
    }

    @Override
    public boolean isSaturatedNode(int node) {
        return delegate().isSaturatedNode(node);
    }

    @Override
    public int referencedNodeCount() {
        return delegate().referencedNodeCount();
    }

    @Override
    public int nodeCount() {
        return delegate().nodeCount();
    }
}
