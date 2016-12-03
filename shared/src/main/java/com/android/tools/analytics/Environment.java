/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.analytics;

/**
 * Helper class to create indirection reading environment variables
 * as Java doesn't allow overwriting environment variables for the current process.
 * This is needed to allow overriding the environment variables in tests.
 */
abstract class Environment {
    abstract String getVariable(String name);

    static final Environment SYSTEM =
            new Environment() {
                @Override
                public String getVariable(String name) {
                    return System.getenv(name);
                }
            };

    static Environment sInstance = SYSTEM;

    static Environment getInstance() {
        return sInstance;
    }

    static void setInstance(Environment environment) {
        sInstance = environment;
    }
}
