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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * a UsageTracker that uses a spool to journal the logs tracked. This tracker can be used in both
 * long-running  as well as short-lived command-line tooling to track usage analytics. All
 * analytics get written out to a well-known spool location and will be processed by a separate
 * service in Android Studio for publication. Normal usage calls {@link UsageTracker#getInstance()}
 * to get access to the UsageTracker. This will automatically be set to the correct instance based
 * on the user choosing to opt-in to reporting usage analytics to Google or not.
 * <p>
 * Spool files are binary files protobuf using delimited streams
 * https://developers.google.com/protocol-buffers/docs/techniques#streaming
 */
public class JournalingUsageTracker extends UsageTracker {

    private final Path mSpoolLocation;
    private final Object mGate = new Object();
    private FileLock mLock = null;
    private FileChannel mChannel = null;
    private OutputStream mOutputStream = null;
    private int mCurrentLogCount = 0;
    private ScheduledFuture<?> mJournalTimeout;
    private int mScheduleVersion = 0;
    private boolean mClosed;

    /**
     * Creates an instance of JournalingUsageTracker. Ensures spool location is available and locks
     * the first journaling file.
     *
     * @param spoolLocation location to use for spool files.
     * @param scheduler used for scheduling writing logs and closing & starting new files on
     *     timeout/size limits.
     */
    JournalingUsageTracker(
            AnalyticsSettings analyticsSettings,
            ScheduledExecutorService scheduler,
            Path spoolLocation) {
        super(analyticsSettings, scheduler);
        this.mSpoolLocation = spoolLocation;
        try {
            newTrackFile();
        } catch (IOException e) {
            throw new RuntimeException("Unable to initialize first usage tracking spool file", e);
        }
    }

    /**
     * Creates a new track file with a guid name (for uniqueness) and locks it for writing.
     */
    private void newTrackFile() throws IOException {
        Path spoolFile =
                Paths.get(mSpoolLocation.toString(), UUID.randomUUID().toString() + ".trk");
        Files.createDirectories(spoolFile.getParent());
        FileOutputStream fileOutputStream = new FileOutputStream(spoolFile.toFile());
        mChannel = fileOutputStream.getChannel();

        try {
            mLock = mChannel.tryLock();
            mOutputStream = Channels.newOutputStream(mChannel);
        } catch (OverlappingFileLockException e) {
            throw new IOException("Unable to lock usage tracking spool file", e);
        }
        if (mLock == null) {
            throw new IOException("Unable to lock usage tracking spool file, file already locked");
        }
        mCurrentLogCount = 0;
    }

    /**
     * Closes the track file currently open for writing.
     */
    private void closeTrackFile() throws IOException {
        if (mLock != null) {
            mLock.release();
            mLock = null;
        }

        if (mChannel != null) {
            mChannel.close();
            mChannel = null;
        }
    }

    @Override
    public void logDetails(@NonNull ClientAnalytics.LogEvent.Builder logEvent) {
        if (mClosed) {
            throw new RuntimeException("UsageTracker already closed.");
        }
        getScheduler()
                .execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                synchronized (mGate) {
                                    try {
                                        logEvent.build().writeDelimitedTo(mOutputStream);
                                        mOutputStream.flush();
                                        mChannel.force(false);
                                    } catch (IOException e) {
                                        throw new RuntimeException(
                                                "Failure writing logDetails to usage tracking spool file",
                                                e);
                                    }
                                    mCurrentLogCount++;
                                    if (getMaxJournalSize() > 0
                                            && mCurrentLogCount >= getMaxJournalSize()) {
                                        switchTrackFile();
                                        if (mJournalTimeout != null) {
                                            // Reset the max journal time as we just reset the logs.
                                            scheduleJournalTimeout(getMaxJournalTime());
                                        }
                                    }
                                }
                            }
                        });
    }

    /**
     * Closes the trackfile currently used for writing and creates a brand new one and opens that
     * one for writing.
     */
    private void switchTrackFile() {
        try {
            closeTrackFile();
            newTrackFile();
        } catch (IOException e) {
            throw new RuntimeException("Failure switching to new usage tracking spool file", e);
        }
    }

    /**
     *
     * Closes the UsageTracker (closes current tracker file, disables scheduling of timeout &amp;
     * disables new logs from
     * being posted).
     */
    @Override
    public void close() throws Exception {
        synchronized (mGate) {
            mClosed = true;
            closeTrackFile();
            if (this.mJournalTimeout != null) {
                this.mJournalTimeout.cancel(false);
            }
        }
    }

    @Override
    public void setMaxJournalTime(long duration, TimeUnit unit) {
        synchronized (mGate) {
            super.setMaxJournalTime(duration, unit);
            scheduleJournalTimeout(getMaxJournalTime());
        }
    }

    /** Schedules a timeout at which point the journal will be */
    private void scheduleJournalTimeout(long maxJournalTime) {
        final int currentScheduleVersion = ++mScheduleVersion;
        if (mJournalTimeout != null) {
            mJournalTimeout.cancel(false);
        }
        mJournalTimeout =
                getScheduler()
                        .schedule(
                                () -> {
                                    synchronized (mGate) {
                                        if (mCurrentLogCount > 0) {
                                            switchTrackFile();
                                        }
                                        // only schedule next beat if we're still the authority.
                                        if (mScheduleVersion == currentScheduleVersion) {
                                            scheduleJournalTimeout(maxJournalTime);
                                        }
                                    }
                                },
                                maxJournalTime,
                                TimeUnit.NANOSECONDS);
    }
}
