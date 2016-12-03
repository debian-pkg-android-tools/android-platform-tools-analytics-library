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

import java.util.Map;

/**
 * Used in tests to fake out the Environment code used in production to allow injecting custom
 * environment variable values.
 */
class EnvironmentFakes {
    /**
     * Helper to fake the ANDROID_SDK_HOME environment variable to be set to {@code path}.
     */
    public static void setCustomAndroidSdkHomeEnvironment(String path) {
        setSingleProperty("ANDROID_SDK_HOME", path);
    }

    public static void setMap(Map<String, String> map) {
        Environment.setInstance(
                new Environment() {
                    @Override
                    public String getVariable(String name) {
                        return map.get(name);
                    }
                });
    }

    public static void setSingleProperty(String key, String value) {
        Environment.setInstance(
                new Environment() {
                    @Override
                    public String getVariable(String name) {
                        if (key.equals(name)) {
                            return value;
                        }
                        return null;
                    }
                });
    }

    /** Helper to fake the ANDROID_SDK_HOME environment variable to be unset. */
    public static void setNoEnvironmentVariable() {
        Environment.setInstance(
                new Environment() {
                    @Override
                    String getVariable(String name) {
                        return null;
                    }
                });
    }

    /**
     * Helper to undo faking the environment variable reading.
     */
    public static void setSystemEnvironment() {
        Environment.setInstance(Environment.SYSTEM);
    }
}
