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

import com.android.testutils.VirtualTimeScheduler;
import com.android.utils.StdLogger;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.wireless.android.play.playlog.proto.ClientAnalytics;
import com.google.wireless.android.sdk.stats.AndroidStudioStats;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Tests for {@link JournalingUsageTracker}.
 */
public class JournalingUsageTrackerTest {
    @Rule public TemporaryFolder testConfigDir = new TemporaryFolder();
    @Rule public TemporaryFolder testSpoolDir = new TemporaryFolder();

    @Test
    public void trackerBasicTest() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());

        try {
            // Setup up an instance of the JournalingUsageTracker using a temp spool directory and
            //  virtual time scheduler.
            VirtualTimeScheduler virtualTimeScheduler = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(),
                            virtualTimeScheduler,
                            testSpoolDir.getRoot().toPath());

            // Create a log entry and log it.
            AndroidStudioStats.AndroidStudioEvent.Builder logEntry = createAndroidStudioEvent(42);
            journalingUsageTracker.log(logEntry);
            // Ensure this triggers an action on the scheduler and run the action
            assertEquals(1, virtualTimeScheduler.getActionsQueued());
            virtualTimeScheduler.advanceBy(0);
            assertEquals(0, virtualTimeScheduler.getActionsQueued());

            // The action should have written to the still locked spool file.
            SpoolDetails beforeClose = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, beforeClose.getLockedFiles().size());
            assertEquals(0, beforeClose.getCompletedLogs().size());

            // Close the usage tracker
            journalingUsageTracker.close();

            // Ensure that closing the usage tracker released the spool file, and doesn't open a new
            // one.
            SpoolDetails afterClose = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(0, afterClose.getLockedFiles().size());
            assertEquals(1, afterClose.getCompletedLogs().size());

            // Check that there is exactly one spool file with one event logged that equals the event
            // we logged.
            for (Map.Entry<Path, List<ClientAnalytics.LogEvent>> entry :
                    afterClose.getCompletedLogs().entrySet()) {
                assertEquals(1, entry.getValue().size());
                ClientAnalytics.LogEvent logEvent = entry.getValue().get(0);
                AndroidStudioStats.AndroidStudioEvent actualEvent =
                        studioEventFromLogEvent(logEvent);
                assertEquals(logEntry.build(), actualEvent);
            }
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void trackerTimeoutTest() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());

        try {
            // Setup up an instance of the JournalingUsageTracker using a temp spool directory and
            // virtual time scheduler.
            VirtualTimeScheduler virtualTimeScheduler = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(),
                            virtualTimeScheduler,
                            testSpoolDir.getRoot().toPath());

            // Set a timeout of 1 minute for closing the current spool file.
            journalingUsageTracker.setMaxJournalTime(1, TimeUnit.MINUTES);
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // Write an event to the usage tracker
            AndroidStudioStats.AndroidStudioEvent.Builder logEntry1 = createAndroidStudioEvent(22);
            journalingUsageTracker.log(logEntry1);
            // Run the scheduler to write the log to the journal file
            virtualTimeScheduler.advanceBy(0);

            // Before the timeout there should be one spool file and it should be locked.
            SpoolDetails beforeTimeout = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, beforeTimeout.getLockedFiles().size());
            assertEquals(0, beforeTimeout.getCompletedLogs().size());

            // Advance the scheduler for the timeout to occur.
            long actionsExecuted = virtualTimeScheduler.advanceBy(1, TimeUnit.MINUTES);
            assertEquals(1, actionsExecuted);
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // After the timeout there should be one spool file that is locked and one that is ready for reading.
            // the latter should contain the event logged before the timeout.
            SpoolDetails afterTimeout = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, afterTimeout.getLockedFiles().size());
            assertEquals(1, afterTimeout.getCompletedLogs().size());

            for (Map.Entry<Path, List<ClientAnalytics.LogEvent>> entry :
                    afterTimeout.getCompletedLogs().entrySet()) {
                assertEquals(1, entry.getValue().size());
                ClientAnalytics.LogEvent logEvent = entry.getValue().get(0);
                AndroidStudioStats.AndroidStudioEvent actualEvent =
                        studioEventFromLogEvent(logEvent);
                assertEquals(logEntry1.build(), actualEvent);
            }

            // Log another event.
            AndroidStudioStats.AndroidStudioEvent.Builder logEntry2 = createAndroidStudioEvent(33);
            journalingUsageTracker.log(logEntry2);
            virtualTimeScheduler.advanceBy(0);

            // Close the scheduler for flushing any outstanding spool files.
            journalingUsageTracker.close();

            // Check that the expected jobs have been executed.
            assertEquals(3, virtualTimeScheduler.getActionsExecuted());
            assertEquals(0, virtualTimeScheduler.getActionsQueued());

            // After close we expect two seperate spool files, each containing one of the events.
            SpoolDetails afterClose = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(0, afterClose.getLockedFiles().size());
            assertEquals(2, afterClose.getCompletedLogs().size());

            for (Map.Entry<Path, List<ClientAnalytics.LogEvent>> afterTimeoutEntry :
                    afterTimeout.getCompletedLogs().entrySet()) {
                List<ClientAnalytics.LogEvent> existingAfterClose =
                        afterClose.getCompletedLogs().get(afterTimeoutEntry.getKey());
                assertEquals(existingAfterClose, afterTimeoutEntry.getValue());
                afterClose.getCompletedLogs().remove(afterTimeoutEntry.getKey());
            }

            for (Map.Entry<Path, List<ClientAnalytics.LogEvent>> afterCloseEntry :
                    afterClose.getCompletedLogs().entrySet()) {
                assertEquals(1, afterCloseEntry.getValue().size());
                ClientAnalytics.LogEvent logEvent = afterCloseEntry.getValue().get(0);
                AndroidStudioStats.AndroidStudioEvent actualEvent =
                        studioEventFromLogEvent(logEvent);
                assertEquals(logEntry2.build(), actualEvent);
            }

            // Closing again should be a noop.
            journalingUsageTracker.close();
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void trackerTimeoutNoLogsTest() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());

        try {
            // Setup up an instance of the JournalingUsageTracker using a temp spool directory and
            // virtual time scheduler.
            VirtualTimeScheduler virtualTimeScheduler = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(),
                            virtualTimeScheduler,
                            testSpoolDir.getRoot().toPath());

            // Set a timeout of 1 minute for closing the current spool file.
            journalingUsageTracker.setMaxJournalTime(1, TimeUnit.MINUTES);
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // Before the timeout there should be one spool file and it should be locked.
            SpoolDetails beforeTimeout = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, beforeTimeout.getLockedFiles().size());
            assertEquals(0, beforeTimeout.getCompletedLogs().size());

            // Advance the scheduler for the timeout to occur.
            long actionsExecuted = virtualTimeScheduler.advanceBy(1, TimeUnit.MINUTES);
            assertEquals(1, actionsExecuted);
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // After the timeout there should not be any changes to the spool files as
            // there was nothing to log.
            SpoolDetails afterTimeout = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, afterTimeout.getLockedFiles().size());
            assertEquals(0, afterTimeout.getCompletedLogs().size());

            journalingUsageTracker.close();
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void trackerMaxLogsTest() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());

        try {
            // Setup up an instance of the JournalingUsageTracker using a temp spool directory and
            // virtual time scheduler.
            VirtualTimeScheduler virtualTimeScheduler = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(),
                            virtualTimeScheduler,
                            testSpoolDir.getRoot().toPath());

            // Restrict the max amount of logs per spool file to 3.
            journalingUsageTracker.setMaxJournalSize(3);
            assertEquals(0, virtualTimeScheduler.getActionsQueued());

            // Write two events.
            AndroidStudioStats.AndroidStudioEvent.Builder event1 = createAndroidStudioEvent(1);
            journalingUsageTracker.log(event1);
            virtualTimeScheduler.advanceBy(0);

            AndroidStudioStats.AndroidStudioEvent.Builder event2 = createAndroidStudioEvent(2);
            journalingUsageTracker.log(event2);
            virtualTimeScheduler.advanceBy(0);

            // Ensure that given we haven't reach max, there is only one spool file and it is locked.
            SpoolDetails beforeMax = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, beforeMax.getLockedFiles().size());
            assertEquals(0, beforeMax.getCompletedLogs().size());

            // Write another event
            AndroidStudioStats.AndroidStudioEvent.Builder event3 = createAndroidStudioEvent(3);
            journalingUsageTracker.log(event3);
            virtualTimeScheduler.advanceBy(0);

            // Ensure we hit max that the original spool file has completed and a new one created and
            // locked.
            SpoolDetails afterMax = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, afterMax.getLockedFiles().size());
            assertEquals(1, afterMax.getCompletedLogs().size());

            for (Map.Entry<Path, List<ClientAnalytics.LogEvent>> entry :
                    afterMax.getCompletedLogs().entrySet()) {
                assertEquals(3, entry.getValue().size());
                AndroidStudioStats.AndroidStudioEvent actualEvent1 =
                        studioEventFromLogEvent(entry.getValue().get(0));
                assertEquals(event1.build(), actualEvent1);
                AndroidStudioStats.AndroidStudioEvent actualEvent2 =
                        studioEventFromLogEvent(entry.getValue().get(1));
                assertEquals(event2.build(), actualEvent2);
                AndroidStudioStats.AndroidStudioEvent actualEvent3 =
                        studioEventFromLogEvent(entry.getValue().get(2));
                assertEquals(event3.build(), actualEvent3);
            }

            // Write two more events.
            AndroidStudioStats.AndroidStudioEvent.Builder event4 = createAndroidStudioEvent(4);
            journalingUsageTracker.log(event4);
            virtualTimeScheduler.advanceBy(0);

            AndroidStudioStats.AndroidStudioEvent.Builder event5 = createAndroidStudioEvent(5);
            journalingUsageTracker.log(event5);
            virtualTimeScheduler.advanceBy(0);

            // Close the usage tracker.
            journalingUsageTracker.close();

            // After close we expect two spool files, the first with the first 3 events and the second
            // file the last 2 events.
            SpoolDetails afterClose = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(0, afterClose.getLockedFiles().size());
            assertEquals(2, afterClose.getCompletedLogs().size());

            for (Map.Entry<Path, List<ClientAnalytics.LogEvent>> afterMaxEntry :
                    afterMax.getCompletedLogs().entrySet()) {
                List<ClientAnalytics.LogEvent> existingAfterClose =
                        afterClose.getCompletedLogs().get(afterMaxEntry.getKey());
                assertEquals(existingAfterClose, afterMaxEntry.getValue());
                afterClose.getCompletedLogs().remove(afterMaxEntry.getKey());
            }

            for (Map.Entry<Path, List<ClientAnalytics.LogEvent>> afterCloseEntry :
                    afterClose.getCompletedLogs().entrySet()) {
                assertEquals(2, afterCloseEntry.getValue().size());
                AndroidStudioStats.AndroidStudioEvent actualEvent4 =
                        studioEventFromLogEvent(afterCloseEntry.getValue().get(0));
                assertEquals(event4.build(), actualEvent4);
                AndroidStudioStats.AndroidStudioEvent actualEvent5 =
                        studioEventFromLogEvent(afterCloseEntry.getValue().get(1));
                assertEquals(event5.build(), actualEvent5);
            }
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void trackerUpdateTimeoutTest() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());

        try {
            // Setup up an instance of the JournalingUsageTracker using a temp spool directory and
            // virtual time scheduler.
            VirtualTimeScheduler virtualTimeScheduler = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(),
                            virtualTimeScheduler,
                            testSpoolDir.getRoot().toPath());

            // Write an event to ensure track file switch is triggered.
            AndroidStudioStats.AndroidStudioEvent.Builder event = createAndroidStudioEvent(1);
            journalingUsageTracker.log(event);
            virtualTimeScheduler.advanceBy(0);

            // Set a timeout of 1 minute for closing the current spool file.
            journalingUsageTracker.setMaxJournalTime(1, TimeUnit.MINUTES);
            assertEquals(1, virtualTimeScheduler.getActionsExecuted());
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // Move time forward but not enough to trigger the timeout.
            virtualTimeScheduler.advanceBy(30, TimeUnit.SECONDS);
            assertEquals(1, virtualTimeScheduler.getActionsExecuted());
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // Update the timeout
            journalingUsageTracker.setMaxJournalTime(1, TimeUnit.MINUTES);
            assertEquals(1, virtualTimeScheduler.getActionsExecuted());
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // Move to the time of the original timeout
            virtualTimeScheduler.advanceBy(30, TimeUnit.SECONDS);
            // Ensure the original timeout is not triggered.
            assertEquals(1, virtualTimeScheduler.getActionsExecuted());
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // Move to the time of the new timeout
            virtualTimeScheduler.advanceBy(30, TimeUnit.SECONDS);
            // Ensure the new timeout is triggered.
            assertEquals(2, virtualTimeScheduler.getActionsExecuted());
            assertEquals(1, virtualTimeScheduler.getActionsQueued());

            // Ensure that the first spool file was closed and a new one created.
            SpoolDetails afterTimeout = getSpoolDetails(testSpoolDir.getRoot().toPath());
            assertEquals(1, afterTimeout.getLockedFiles().size());
            assertEquals(1, afterTimeout.getCompletedLogs().size());
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void updateSettingsAndTrackerTest() throws IOException {
        UsageTracker beforeUpdate = UsageTracker.getInstance();
        assertTrue(beforeUpdate instanceof NullUsageTracker);
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try {
            File settingsFile =
                    testConfigDir.getRoot().toPath().resolve("analytics.settings").toFile();
            assertFalse(settingsFile.exists());

            // Setup up an instance of the JournalingUsageTracker using a temp spool directory and
            // virtual time scheduler.
            VirtualTimeScheduler virtualTimeScheduler = new VirtualTimeScheduler();

            // updating to opt-in false from scratch should set NullUsageTracker
            // and initialize settings.
            AnalyticsSettings settings1 =
                    UsageTracker.updateSettingsAndTracker(
                            false, new StdLogger(StdLogger.Level.INFO), virtualTimeScheduler);
            assertNotNull(settings1);
            assertTrue(settingsFile.exists());
            UsageTracker afterFirstUpdate = UsageTracker.getInstance();
            assertTrue(afterFirstUpdate instanceof NullUsageTracker);
            assertNotEquals(beforeUpdate, afterFirstUpdate);
            assertEquals(settings1, afterFirstUpdate.getAnalyticsSettings());
            assertFalse(settings1.hasOptedIn());
            assertEquals(virtualTimeScheduler, UsageTracker.getInstance().getScheduler());

            // updating to opt-in true should update settings and initialize JournalingUsageTracker.
            AnalyticsSettings settings2 =
                    UsageTracker.updateSettingsAndTracker(
                            true, new StdLogger(StdLogger.Level.INFO), virtualTimeScheduler);
            assertNotNull(settings2);
            UsageTracker afterSecondUpdate = UsageTracker.getInstance();
            assertTrue(afterSecondUpdate instanceof JournalingUsageTracker);
            assertEquals(settings2, afterSecondUpdate.getAnalyticsSettings());
            assertTrue(settings2.hasOptedIn());
            assertEquals(virtualTimeScheduler, UsageTracker.getInstance().getScheduler());

            // updating to opt-in false should update settings and initialize NullUsageTracker.
            AnalyticsSettings settings3 =
                    UsageTracker.updateSettingsAndTracker(
                            false, new StdLogger(StdLogger.Level.INFO), virtualTimeScheduler);
            assertNotNull(settings3);
            UsageTracker afterThirdUpdate = UsageTracker.getInstance();
            assertTrue(afterThirdUpdate instanceof NullUsageTracker);
            assertEquals(settings3, afterThirdUpdate.getAnalyticsSettings());
            assertFalse(settings3.hasOptedIn());
            assertEquals(virtualTimeScheduler, UsageTracker.getInstance().getScheduler());

            // now that we have a NullTracker, no spool files should be locked.
            assertTrue(getSpoolDetails(testSpoolDir.getRoot().toPath()).getLockedFiles().isEmpty());
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    /**
     * Helper that builds a {@link AndroidStudioStats.AndroidStudioEvent} with a marker to
     * distinguish this message.
     */
    private AndroidStudioStats.AndroidStudioEvent.Builder createAndroidStudioEvent(long marker) {
        return AndroidStudioStats.AndroidStudioEvent.newBuilder()
                .setCategory(AndroidStudioStats.AndroidStudioEvent.EventCategory.META)
                .setKind(AndroidStudioStats.AndroidStudioEvent.EventKind.META_METRICS)
                .setMetaMetrics(
                        AndroidStudioStats.MetaMetrics.newBuilder()
                                .setBytesSentInLastUpload(marker)
                                .setFailedConnections(0)
                                .setFailedServerReplies(0));
    }

    /**
     * Helper that examins the provided spool directory and reports on locked vs completed spool
     * files. For completed spool files, it parses the contents and provides the protobuf messages
     * in that spool file.
     */
    private SpoolDetails getSpoolDetails(Path testSpoolDir) throws IOException {
        SpoolDetails spoolDetails = new SpoolDetails();
        DirectoryStream<Path> stream = Files.newDirectoryStream(testSpoolDir, "*.trk");
        for (Path trackFile : stream) {
            FileChannel channel = new RandomAccessFile(trackFile.toFile(), "rw").getChannel();
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) {
                    InputStream inputStream = Channels.newInputStream(channel);
                    ClientAnalytics.LogEvent event = null;
                    List<ClientAnalytics.LogEvent> entries = new ArrayList<>();
                    while ((event = ClientAnalytics.LogEvent.parseDelimitedFrom(inputStream))
                            != null) {
                        entries.add(event);
                    }
                    spoolDetails.getCompletedLogs().put(trackFile, entries);
                    lock.close();
                    channel.close();
                } else {
                    spoolDetails.getLockedFiles().add(trackFile);
                }
            } catch (OverlappingFileLockException e) {
                spoolDetails.getLockedFiles().add(trackFile);
            }
        }
        return spoolDetails;
    }

    /**
     * Helper that parses the binary blob of a {@link ClientAnalytics.LogEvent} into an
     * {@link AndroidStudioStats.AndroidStudioEvent}.
     */
    private AndroidStudioStats.AndroidStudioEvent studioEventFromLogEvent(
            ClientAnalytics.LogEvent logEvent) throws InvalidProtocolBufferException {
        return AndroidStudioStats.AndroidStudioEvent.parseFrom(logEvent.getSourceExtension());
    }
}
