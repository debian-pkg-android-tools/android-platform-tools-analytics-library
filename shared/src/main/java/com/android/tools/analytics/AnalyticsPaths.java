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

import com.google.common.base.Strings;

import java.nio.file.Paths;

/**
 * Helpers to get paths used to configure analytics reporting.
 */
public class AnalyticsPaths {
    /**
     * Gets the spooling directory used for temporary storage of analytics data.
     */
    public static String getSpoolDirectory() {
        return Paths.get(getAndroidSettingsHome(), "metrics", "spool").toString();
    }

    /**
     * Gets the directory used to store android related settings (usually ~/.android).
     */
    public static String getAndroidSettingsHome() {
        String env = Environment.getInstance().getVariable("ANDROID_SDK_HOME");
        if (!Strings.isNullOrEmpty(env)) {
            return env;
        }
        return Paths.get(System.getProperty("user.home"), ".android").toString();
    }
}
