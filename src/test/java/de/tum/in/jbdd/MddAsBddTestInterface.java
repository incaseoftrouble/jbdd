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

public class MddAsBddTestInterface extends MddAsBdd implements BddWithTestInterface {
    public MddAsBddTestInterface(BddConfiguration configuration) {
        super(configuration);
    }

    @Override
    MddImpl mdd() {
        return (MddImpl) super.mdd();
    }

    @Override
    public boolean isNodeValid(int node) {
        return mdd().isNodeValid(node);
    }

    @Override
    public boolean check() {
        return mdd().check();
    }

    @Override
    public void invalidateCache() {
        mdd().invalidateCache();
    }

    @Override
    public String treeToString(int node) {
        return mdd().treeToString(node);
    }
}
