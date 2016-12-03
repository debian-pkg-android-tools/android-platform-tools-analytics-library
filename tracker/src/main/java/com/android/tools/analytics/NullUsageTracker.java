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

import com.android.annotations.NonNull;
import com.google.wireless.android.play.playlog.proto.ClientAnalytics;

import java.util.concurrent.ScheduledExecutorService;

/**
 * A {@link UsageTracker} that does not report any logs. Used when the user opts-out of reporting
 * usage analytics to Google.
 */
public class NullUsageTracker extends UsageTracker {
    public NullUsageTracker(
            AnalyticsSettings analyticsSettings, ScheduledExecutorService scheduler) {
        super(analyticsSettings, scheduler);
    }

    @Override
    public void logDetails(@NonNull ClientAnalytics.LogEvent.Builder studioEvent) {}

    @Override
    public void close() {}
}
