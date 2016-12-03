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
import com.android.utils.ILogger;

import java.nio.file.Paths;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
 * Base class for publishing analytics. This class has two subclasses, one that publishes
 * analytics to Google's servers for users who opted in to metrics and one that is a Noop to ensure
 * metrics never get published for users who opt out.
 */
public abstract class AnalyticsPublisher implements AutoCloseable {
    private static final Object sGate = new Object();
    private static AnalyticsPublisher sInstance;
    private long mPublishIntervalNanos = TimeUnit.MINUTES.toNanos(10);

    private final AnalyticsSettings mAnalyticsSettings;
    private final ScheduledExecutorService mScheduler;

    protected AnalyticsPublisher(
            AnalyticsSettings analyticsSettings, ScheduledExecutorService scheduler) {
        this.mAnalyticsSettings = analyticsSettings;
        this.mScheduler = scheduler;
    }

    /**
     * Initializes the publisher retrieved by {@link #getInstance()}
     *
     * @param analyticsSettings used to check opt-in vs opt-out status.
     * @param scheduler used to schedule jobs for publishing.
     */
    public static AnalyticsPublisher initialize(
            @NonNull AnalyticsSettings analyticsSettings,
            @NonNull ScheduledExecutorService scheduler) {
        synchronized (sGate) {
            if (analyticsSettings.hasOptedIn() && !analyticsSettings.hasDebugDisablePublishing()) {
                sInstance =
                        new GoogleAnalyticsPublisher(
                                analyticsSettings,
                                scheduler,
                                Paths.get(AnalyticsPaths.getSpoolDirectory()));
            } else {
                sInstance = new NullAnalyticsPublisher(analyticsSettings, scheduler);
            }
            return sInstance;
        }
    }

    /**
     * Retrieved the configured publisher based on a call to
     * {@link #initialize(AnalyticsSettings, ScheduledExecutorService)}
     */
    @NonNull
    public static AnalyticsPublisher getInstance() {
        synchronized (sGate) {
            return sInstance;
        }
    }

    /**
     * Sets the interval used for scheduling jobs to publish metrics.
     */
    public void setPublishInterval(long interval, TimeUnit unit) {
        mPublishIntervalNanos = unit.toNanos(interval);
    }

    /** Gets the analytics settings used by this tracker. */
    public AnalyticsSettings getAnalyticsSettings() {
        return mAnalyticsSettings;
    }

    /** Gets the interval in nano-seconds used for scheduling jobs to publish metrics. */
    public long getPublishInterval() {
        return mPublishIntervalNanos;
    }

    /** Gets the scheduler used by this publisher. */
    public ScheduledExecutorService getScheduler() {
        return mScheduler;
    }

    /** Closes the current publisher and creates a new instance. */
    public static void updatePublisher(
            ILogger logger, AnalyticsSettings settings, ScheduledExecutorService scheduler) {
        AnalyticsPublisher current = getInstance();
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                logger.error(e, "Unable to close existing analytics publisher");
            }
        }
        initialize(settings, scheduler);
    }
}
