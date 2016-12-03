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
import com.android.annotations.VisibleForTesting;
import com.android.utils.ILogger;
import com.google.wireless.android.sdk.stats.AndroidStudioStats;
import com.google.wireless.android.play.playlog.proto.ClientAnalytics;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UsageTracker is an api to report usage of features. This data is used to improve
 * future versions of Android Studio and related tools.
 *
 * The tracker has an API to logDetails usage (in the form of protobuf messages).
 * A separate system called the Analytics Publisher takes the logs and sends them
 * to Google's servers for analysis.
 */
public abstract class UsageTracker implements AutoCloseable {
    private static final Object sGate = new Object();

    @VisibleForTesting static final String sSessionId = UUID.randomUUID().toString();
    private static UsageTracker sInstance = new NullUsageTracker(new AnalyticsSettings(), null);

    private final AnalyticsSettings mAnalyticsSettings;
    private final ScheduledExecutorService mScheduler;

    private int mMaxJournalSize;
    private long mMaxJournalTime;

    protected UsageTracker(
            AnalyticsSettings analyticsSettings, ScheduledExecutorService scheduler) {
        this.mAnalyticsSettings = analyticsSettings;
        this.mScheduler = scheduler;
    }
    /**
     * Indicates whether this UsageTracker has a maximum size at which point logs need to be flushed.
     * Zero or less indicates no maximum size at which to flush.
     */
    public int getMaxJournalSize() {
        return mMaxJournalSize;
    }

    /*
     * Sets a maximum size at which point logs need to be flushed. Zero or less indicates no
     * flushing until @{link #close()} is called.
     */
    public void setMaxJournalSize(int maxJournalSize) {
        this.mMaxJournalSize = maxJournalSize;
    }

    /**
     * Indicates whether this UsageTracker has a timeout at which point logs need to be flushed.
     * Zero or less indicates no timeout is set.
     *
     * @return timeout in nano-seconds.
     */
    public long getMaxJournalTime() {
        return mMaxJournalTime;
    }

    /**
     * Sets a timeout at which point logs need to be flushed. Zero or less indicates no timeout
     * should be used.
     */
    public void setMaxJournalTime(long duration, TimeUnit unit) {
        this.mMaxJournalTime = unit.toNanos(duration);
    }

    /** Gets the analytics settings used by this tracker. */
    public AnalyticsSettings getAnalyticsSettings() {
        return mAnalyticsSettings;
    }

    /** Gets the scheduler used by this tracker. */
    public ScheduledExecutorService getScheduler() {
        return mScheduler;
    }

    /** Logs usage data provided in the @{link AndroidStudioStats.AndroidStudioEvent}. */
    public void log(@NonNull AndroidStudioStats.AndroidStudioEvent.Builder studioEvent) {
        studioEvent.setStudioSessionId(sSessionId);
        logDetails(
                ClientAnalytics.LogEvent.newBuilder()
                        .setEventTimeMs(new Date().getTime())
                        .setSourceExtension(studioEvent.build().toByteString()));
    }

    /**
     * Logs usage data provided in the @{link ClientAnalytics.LogEvent}. Normally using {#log} is
     * preferred please talk to this code's author if you need {@link #logDetails} instead.
     */
    public abstract void logDetails(@NonNull ClientAnalytics.LogEvent.Builder logEvent);

    /**
     * Gets an instance of the {@link UsageTracker} that has been initialized correctly for this process.
     */
    @NonNull
    public static UsageTracker getInstance() {
        synchronized (sGate) {
            return sInstance;
        }
    }

    /**
     * Initializes a {@link UsageTracker} for use throughout this process based on user opt-in and
     * other settings.
     */
    public static UsageTracker initialize(
            @NonNull AnalyticsSettings analyticsSettings,
            @NonNull ScheduledExecutorService scheduler) {
        synchronized (sGate) {
            if (analyticsSettings.hasOptedIn()) {
                sInstance =
                        new JournalingUsageTracker(
                                analyticsSettings,
                                scheduler,
                                Paths.get(AnalyticsPaths.getSpoolDirectory()));
            } else {
                sInstance = new NullUsageTracker(analyticsSettings, scheduler);
            }
            return sInstance;
        }
    }

    public static AnalyticsSettings updateSettingsAndTracker(
            boolean optIn, @NonNull ILogger logger, @NonNull ScheduledExecutorService scheduler) {
        UsageTracker current = getInstance();
        AnalyticsSettings settings = AnalyticsSettings.getInstance(logger);

        if (optIn != settings.hasOptedIn()) {
            settings.setHasOptedIn(optIn);
            try {
                settings.saveSettings();
            } catch (IOException e) {
                logger.error(e, "Unable to save analytics settings");
            }
        }
        try {
            current.close();
        } catch (Exception e) {
            logger.error(e, "Unable to close existing analytics tracker");
        }
        initialize(settings, scheduler);
        return settings;
    }
}
