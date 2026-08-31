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

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.TestWatcher;

final class FailFastExtension implements TestWatcher, ExecutionCondition {
    private static final Namespace NAMESPACE = Namespace.create(FailFastExtension.class);

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        context.getRoot().getStore(NAMESPACE).put(context.getRequiredTestClass(), true);
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        Boolean failed = context.getRoot().getStore(NAMESPACE).getOrDefault(testClass, Boolean.class, Boolean.FALSE);
        if (failed) {
            return ConditionEvaluationResult.disabled(String.format(
                    "A previous test in %s failed; the shared structure is likely broken, skipping the rest",
                    testClass.getSimpleName()));
        }
        return ConditionEvaluationResult.enabled("No previous failure in this class");
    }
}
