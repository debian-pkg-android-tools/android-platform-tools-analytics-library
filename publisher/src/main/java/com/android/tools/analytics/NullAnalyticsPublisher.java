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

import java.util.concurrent.ScheduledExecutorService;

/**
 * A publisher that never publishes metrics. Used to ensure opt-out users never publish metrics.
 */
public class NullAnalyticsPublisher extends AnalyticsPublisher {

    public NullAnalyticsPublisher(
            AnalyticsSettings analyticsSettings, ScheduledExecutorService scheduler) {
        super(analyticsSettings, scheduler);
    }
    @Override
    public void close() throws Exception {}
}
