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

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link AnalyticsPaths}.
 */
public class AnalyticsPathsTest {

    @After
    public void setSystemEnvironment() throws Exception {
        EnvironmentFakes.setSystemEnvironment();
    }

    @Test
    public void getAndroidSettingsHomeTest() throws Exception {
        // Test picking the default path ~/.android/ when no environment variable exists.
        EnvironmentFakes.setNoEnvironmentVariable();
        assertEquals(
                System.getProperty("user.home") + "/.android",
                AnalyticsPaths.getAndroidSettingsHome());

        // Test using the ANDROID_SDK_HOME environment variable.
        String customRoot = "/a/b/c";
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(customRoot);
        assertEquals(customRoot, AnalyticsPaths.getAndroidSettingsHome());
    }

    @Test
    public void getSpoolDirectoryTest() throws Exception {
        // Test picking the default path under ~/.android/ when no environment variable exists.
        EnvironmentFakes.setNoEnvironmentVariable();
        assertEquals(
                System.getProperty("user.home") + "/.android/metrics/spool",
                AnalyticsPaths.getSpoolDirectory());

        // Test using the ANDROID_SDK_HOME environment variable.
        String customRoot = "/a/b/c";
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(customRoot);
        assertEquals(customRoot + "/metrics/spool", AnalyticsPaths.getSpoolDirectory());
    }
}
