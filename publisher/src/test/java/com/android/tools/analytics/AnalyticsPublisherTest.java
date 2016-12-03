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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.testutils.SystemPropertyOverrides;
import com.android.testutils.VirtualTimeDateProvider;
import com.android.testutils.VirtualTimeFuture;
import com.android.testutils.VirtualTimeScheduler;
import com.android.utils.DateProvider;
import com.android.utils.StdLogger;
import com.google.wireless.android.play.playlog.proto.ClientAnalytics;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.MetaMetrics;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.StudioCrash;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


/**
 * Tests for {@link AnalyticsPublisher} and {@link GoogleAnalyticsPublisher}.
 */
public class AnalyticsPublisherTest {
    @Rule public final TemporaryFolder testSpoolDir = new TemporaryFolder();
    @Rule public final TemporaryFolder testConfigDir = new TemporaryFolder();

    @Test
    public void testInitialValues() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        // Start a stub webserver to publish to.
        try (ServerStub stub = new ServerStub()) {
            // Create helpers used to instantiate the publisher.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();

            // Instantiate the publisher
            GoogleAnalyticsPublisher googleAnalyticsPublisher =
                    new GoogleAnalyticsPublisher(
                            analyticsSettings, vs, testSpoolDir.getRoot().toPath());
            googleAnalyticsPublisher.setServerUrl(stub.getUrl());

            // Ensure the publisher's initial values are as expected.
            assertEquals(stub.getUrl(), googleAnalyticsPublisher.getServerUrl());
            assertEquals(
                    TimeUnit.MINUTES.toNanos(10), googleAnalyticsPublisher.getPublishInterval());

            // Ensure that the first publish job has been scheduled.
            assertEquals(1, vs.getQueue().size());
            assertEquals(TimeUnit.MINUTES.toNanos(10), vs.getQueue().peek().getTick());
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    /**
     * Creates an instance of {@link AnalyticsSettings} for use in tests.
     */
    private static AnalyticsSettings getTestAnalyticsSettings() {
        AnalyticsSettings analyticsSettings = new AnalyticsSettings();
        analyticsSettings.setHasOptedIn(true);
        String uid = UUID.randomUUID().toString();
        analyticsSettings.setUserId(uid);
        return analyticsSettings;
    }

    @Test
    public void testBasics() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try (ServerStub stub = new ServerStub();
                SystemPropertyOverrides systemPropertyOverrides = new SystemPropertyOverrides()) {

            // Create an event to log.
            AndroidStudioEvent.Builder logged = createAndroidStudioEvent(5);

            // Use the JournalingUsageTracker to place some .trk files with events in the spool
            // directory.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.log(logged);
            vs.advanceBy(0);
            journalingUsageTracker.close();

            // Create helpers used to instantiate the publisher.
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();

            // Override the date provider to the publisher so we can reliably check if date based
            // properties are set correctly.
            VirtualTimeDateProvider dateProvider = new VirtualTimeDateProvider(vs);
            GoogleAnalyticsPublisher.sDateProvider = dateProvider;
            // move the scheduler ahead so we get non zero values for the date provider.
            vs.advanceBy(1, TimeUnit.MINUTES);

            // override the os.* system properties so the test runs reliably no matter which
            // it is run on.
            systemPropertyOverrides.setProperty("os.name", "Linux");
            systemPropertyOverrides.setProperty("os.version", "3.13.0-85-generic");

            GoogleAnalyticsPublisher googleAnalyticsPublisher =
                    new GoogleAnalyticsPublisher(
                            analyticsSettings, vs, testSpoolDir.getRoot().toPath());
            googleAnalyticsPublisher.setServerUrl(stub.getUrl());

            // advance time to make the publisher run its first publishing job.
            vs.advanceBy(10, TimeUnit.MINUTES);
            googleAnalyticsPublisher.close();

            // retrieve results from the webserver stub.
            List<Future<ClientAnalytics.LogRequest>> results = stub.getResults();
            assertEquals(1, results.size());
            Future<ClientAnalytics.LogRequest> result = results.get(0);
            assertEquals(true, result.isDone());
            ClientAnalytics.LogRequest request = result.get();

            // verify the retrieved proto is shaped as expected.
            assertEquals(660000, request.getRequestTimeMs());
            assertEquals(600000, request.getRequestUptimeMs());

            assertEquals(
                    ClientAnalytics.LogRequest.LogSource.ANDROID_STUDIO, request.getLogSource());
            assertEquals(
                    ClientAnalytics.ClientInfo.ClientType.DESKTOP,
                    request.getClientInfo().getClientType());
            ClientAnalytics.DesktopClientInfo cdi = request.getClientInfo().getDesktopClientInfo();
            assertEquals(analyticsSettings.getUserId(), cdi.getLoggingId());
            assertEquals("linux", cdi.getOs());
            assertEquals("3.13", cdi.getOsMajorVersion());
            assertEquals("3.13.0-85-generic", cdi.getOsFullVersion());

            assertEquals(2, request.getLogEventCount());
            ClientAnalytics.LogEvent metaEvent = request.getLogEvent(0);
            AndroidStudioEvent metaStudioEvent =
                    AndroidStudioEvent.parseFrom(metaEvent.getSourceExtension());
            assertEquals(
                    AndroidStudioEvent.newBuilder()
                            .setCategory(AndroidStudioEvent.EventCategory.META)
                            .setKind(AndroidStudioEvent.EventKind.META_METRICS)
                            .setMetaMetrics(
                                    MetaMetrics.newBuilder()
                                            .setFailedConnections(0)
                                            .setFailedServerReplies(0)
                                            .setBytesSentInLastUpload(0)
                                            .build())
                            .build(),
                    metaStudioEvent);

            ClientAnalytics.LogEvent userEvent = request.getLogEvent(1);
            AndroidStudioEvent retrieved =
                    AndroidStudioEvent.parseFrom(userEvent.getSourceExtension());
            assertEquals(logged.build(), retrieved);
        } finally {
            GoogleAnalyticsPublisher.sDateProvider = DateProvider.SYSTEM;
            EnvironmentFakes.setSystemEnvironment();
        }
        // ensure the spool directory is empty after succesfully publishing the analytics.
        assertEquals(0, testSpoolDir.getRoot().listFiles().length);
    }

    /**
     * Helper that builds a {@link AndroidStudioEvent} with a marker to
     * distinguish this message.
     */
    private static AndroidStudioEvent.Builder createAndroidStudioEvent(long marker) {
        return AndroidStudioEvent.newBuilder()
                .setStudioSessionId(UsageTracker.sSessionId)
                .setCategory(AndroidStudioEvent.EventCategory.PING)
                .setKind(AndroidStudioEvent.EventKind.STUDIO_PING)
                .setStudioCrash(StudioCrash.newBuilder().setActions(marker));
    }

    @Test
    public void testBadConnection() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        // Create a server
        try (ServerStub stub = new ServerStub()) {

            // Create an event to log.
            AndroidStudioEvent.Builder logged = createAndroidStudioEvent(3);

            // Use the JournalingUsageTracker to place some .trk files with events in the spool
            // directory.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.log(logged);
            vs.advanceBy(0);
            journalingUsageTracker.close();

            // Create helpers used to instantiate the publisher.
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();
            GoogleAnalyticsPublisher googleAnalyticsPublisher =
                    new GoogleAnalyticsPublisher(
                            analyticsSettings, vs, testSpoolDir.getRoot().toPath());

            // set the url to publish to to a reserved port which we know the server cannot connect to.
            // https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.txt
            googleAnalyticsPublisher.setServerUrl(new URL("http://localhost:1023/"));

            // Execute the first publish job.
            vs.advanceBy(10, TimeUnit.MINUTES);
            // Ensure no files were published.
            assertEquals(1, testSpoolDir.getRoot().listFiles().length);
            // Ensure that the next job is scheduled at 20 mins
            // (2 * the normal time because of backoff).
            assertEquals(1, vs.getQueue().size());
            assertEquals(20, vs.getQueue().peek().getDelay(TimeUnit.MINUTES));

            // Configure the publisher to use our stub server instead.
            googleAnalyticsPublisher.setServerUrl(stub.getUrl());
            // Move scheduler to run to the delayed job
            vs.advanceBy(20, TimeUnit.MINUTES);
            googleAnalyticsPublisher.close();

            // Ensure that the results do come in now.
            List<Future<ClientAnalytics.LogRequest>> results = stub.getResults();
            assertEquals(1, results.size());
            Future<ClientAnalytics.LogRequest> result = results.get(0);
            assertEquals(true, result.isDone());
            ClientAnalytics.LogRequest request = result.get();

            assertEquals(2, request.getLogEventCount());
            ClientAnalytics.LogEvent metaEvent = request.getLogEvent(0);
            AndroidStudioEvent metaStudioEvent =
                    AndroidStudioEvent.parseFrom(metaEvent.getSourceExtension());
            assertEquals(
                    AndroidStudioEvent.newBuilder()
                            .setCategory(AndroidStudioEvent.EventCategory.META)
                            .setKind(AndroidStudioEvent.EventKind.META_METRICS)
                            .setMetaMetrics(
                                    MetaMetrics.newBuilder()
                                            // ensure that the previous failure is reported in the
                                            // meta metrics.
                                            .setFailedConnections(1)
                                            .setFailedServerReplies(0)
                                            .setBytesSentInLastUpload(0)
                                            .build())
                            .build(),
                    metaStudioEvent);
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void testBadServer() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try (ServerStub stub = new ServerStub();
                SystemPropertyOverrides systemPropertyOverrides = new SystemPropertyOverrides()) {

            // Create an event to log.
            AndroidStudioEvent.Builder logged = createAndroidStudioEvent(3);

            // Use the JournalingUsageTracker to place some .trk files with events in the spool
            // directory.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.log(logged);
            vs.advanceBy(0);
            journalingUsageTracker.close();

            // Create helpers used to instantiate the publisher.
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();

            // As we're checking upload byte sizes and the size varies by the value for
            // time, we need to fix the time in this test.
            VirtualTimeDateProvider dateProvider = new VirtualTimeDateProvider(vs);
            GoogleAnalyticsPublisher.sDateProvider = dateProvider;

            // override the os.* system properties so the test runs reliably no matter which
            // it is run on.
            systemPropertyOverrides.setProperty("os.name", "Linux");
            systemPropertyOverrides.setProperty("os.version", "3.13.0-85-generic");

            GoogleAnalyticsPublisher googleAnalyticsPublisher =
                    new GoogleAnalyticsPublisher(
                            analyticsSettings, vs, testSpoolDir.getRoot().toPath());
            googleAnalyticsPublisher.setServerUrl(stub.getUrl());

            // Instruct to make the server stub fail the http request in the next call.
            stub.makeNextResponseServerError(true);

            // Execute the first publish job.
            vs.advanceBy(10, TimeUnit.MINUTES);

            // Ensure no files were published.
            assertEquals(1, testSpoolDir.getRoot().listFiles().length);

            // Ensure that the next job is scheduled at 20 mins
            // (2 * the normal time because of backoff).
            assertEquals(1, vs.getQueue().size());
            assertEquals(20, vs.getQueue().peek().getDelay(TimeUnit.MINUTES));

            // Move scheduler to run to the delayed job
            vs.advanceBy(20, TimeUnit.MINUTES);

            // Ensure that the results do come in now.
            List<Future<ClientAnalytics.LogRequest>> results = stub.getResults();
            assertEquals(1, results.size());
            Future<ClientAnalytics.LogRequest> result = results.get(0);
            assertEquals(true, result.isDone());
            ClientAnalytics.LogRequest request = result.get();

            assertEquals(2, request.getLogEventCount());
            ClientAnalytics.LogEvent metaEvent = request.getLogEvent(0);
            AndroidStudioEvent metaStudioEvent =
                    AndroidStudioEvent.parseFrom(metaEvent.getSourceExtension());
            assertEquals(
                    AndroidStudioEvent.newBuilder()
                            .setCategory(AndroidStudioEvent.EventCategory.META)
                            .setKind(AndroidStudioEvent.EventKind.META_METRICS)
                            .setMetaMetrics(
                                    MetaMetrics.newBuilder()
                                            .setFailedConnections(0)
                                            // ensure that the previous failure is reported in the
                                            // meta metrics.
                                            .setFailedServerReplies(1)
                                            .setBytesSentInLastUpload(166)
                                            .build())
                            .build(),
                    metaStudioEvent);

            // Send more metrics to test behavior after successful upload
            journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.log(logged);
            vs.advanceBy(0);
            journalingUsageTracker.close();

            // Next publishing should be back at 10 minutes as last job
            vs.advanceBy(10, TimeUnit.MINUTES);

            // Metrics should be published
            results = stub.getResults();
            assertEquals(2, results.size());
            result = results.get(1);
            assertEquals(true, result.isDone());
            request = result.get();

            assertEquals(2, request.getLogEventCount());
            // Another metric event should be present and it should have no failures counted.
            metaEvent = request.getLogEvent(0);
            metaStudioEvent = AndroidStudioEvent.parseFrom(metaEvent.getSourceExtension());
            assertEquals(
                    AndroidStudioEvent.newBuilder()
                            .setCategory(AndroidStudioEvent.EventCategory.META)
                            .setKind(AndroidStudioEvent.EventKind.META_METRICS)
                            .setMetaMetrics(
                                    MetaMetrics.newBuilder()
                                            .setFailedConnections(0)
                                            .setFailedServerReplies(0)
                                            .setBytesSentInLastUpload(167)
                                            .build())
                            .build(),
                    metaStudioEvent);
            googleAnalyticsPublisher.close();
        } finally {
            GoogleAnalyticsPublisher.sDateProvider = DateProvider.SYSTEM;
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void testEmptySpoolFile() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try (ServerStub stub = new ServerStub()) {
            // Use the JournalingUsageTracker to place an empty .trk file in the spool directory.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.close();

            // Create helpers used to instantiate the publisher.
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();
            GoogleAnalyticsPublisher googleAnalyticsPublisher =
                    new GoogleAnalyticsPublisher(
                            analyticsSettings, vs, testSpoolDir.getRoot().toPath());
            googleAnalyticsPublisher.setServerUrl(stub.getUrl());

            // Execute the first publish job.
            vs.advanceBy(10, TimeUnit.MINUTES);

            // Ensure the .trk file got removed.
            assertEquals(0, testSpoolDir.getRoot().listFiles().length);
            googleAnalyticsPublisher.close();

            // Ensure no events were published.
            List<Future<ClientAnalytics.LogRequest>> results = stub.getResults();
            assertEquals(0, results.size());
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void testMultipleEvents() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try {
            // Create a few events to log.
            AndroidStudioEvent.Builder logged1 = createAndroidStudioEvent(1);
            AndroidStudioEvent.Builder logged2 = createAndroidStudioEvent(2);
            AndroidStudioEvent.Builder logged3 = createAndroidStudioEvent(3);
            AndroidStudioEvent.Builder logged4 = createAndroidStudioEvent(4);

            Set<AndroidStudioEvent> expected = new HashSet<>();
            expected.add(logged1.build());
            expected.add(logged2.build());
            expected.add(logged3.build());
            expected.add(logged4.build());

            // Use the JournalingUsageTracker to place several .trk files with events in the spool
            // directory.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.setMaxJournalSize(2);
            journalingUsageTracker.log(logged1);
            journalingUsageTracker.log(logged2);
            vs.advanceBy(0);
            journalingUsageTracker.log(logged3);
            journalingUsageTracker.log(logged4);
            vs.advanceBy(0);
            journalingUsageTracker.close();

            // Create helpers used to instantiate the publisher.
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();
            try (ServerStub stub = new ServerStub()) {
                GoogleAnalyticsPublisher googleAnalyticsPublisher =
                        new GoogleAnalyticsPublisher(
                                analyticsSettings, vs, testSpoolDir.getRoot().toPath());
                googleAnalyticsPublisher.setServerUrl(stub.getUrl());

                // Execute the first publish job.
                vs.advanceBy(10, TimeUnit.MINUTES);
                googleAnalyticsPublisher.close();

                // check that two requests were made
                List<Future<ClientAnalytics.LogRequest>> results = stub.getResults();
                assertEquals(2, results.size());

                Set<AndroidStudioEvent> actual = new HashSet<>();

                // each request contains 3 events (1 meta and two data events).
                Future<ClientAnalytics.LogRequest> result1 = results.get(0);
                ClientAnalytics.LogRequest request1 = result1.get();
                assertEquals(3, request1.getLogEventCount());
                AndroidStudioEvent received1 =
                        AndroidStudioEvent.parseFrom(request1.getLogEvent(1).getSourceExtension());
                actual.add(received1);
                AndroidStudioEvent received2 =
                        AndroidStudioEvent.parseFrom(request1.getLogEvent(2).getSourceExtension());
                actual.add(received2);

                Future<ClientAnalytics.LogRequest> result2 = results.get(1);
                ClientAnalytics.LogRequest request2 = result2.get();
                assertEquals(3, request2.getLogEventCount());
                AndroidStudioEvent received3 =
                        AndroidStudioEvent.parseFrom(request2.getLogEvent(1).getSourceExtension());
                actual.add(received3);
                AndroidStudioEvent received4 =
                        AndroidStudioEvent.parseFrom(request2.getLogEvent(2).getSourceExtension());
                actual.add(received4);

                // ensure all events that were sent are received, but don't care about the order.
                assertEquals(expected, actual);
            }
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void testUpdateInterval() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try (ServerStub stub = new ServerStub()) {
            // Create an event to log.
            AndroidStudioEvent.Builder logged = createAndroidStudioEvent(5);

            // Use the JournalingUsageTracker to place some .trk files with events in the spool
            // directory.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.log(logged);
            vs.advanceBy(0);
            journalingUsageTracker.close();

            // Create helpers used to instantiate the publisher.
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();
            GoogleAnalyticsPublisher googleAnalyticsPublisher =
                    new GoogleAnalyticsPublisher(
                            analyticsSettings, vs, testSpoolDir.getRoot().toPath());
            googleAnalyticsPublisher.setServerUrl(stub.getUrl());

            // Ensure a job is queued to publish analytics.
            assertEquals(1, vs.getQueue().size());
            assertEquals(10, vs.getQueue().peek().getDelay(TimeUnit.MINUTES));

            // Move time but not enough to trigger the job
            vs.advanceBy(5, TimeUnit.MINUTES);
            assertEquals(1, vs.getQueue().size());
            assertEquals(5, vs.getQueue().peek().getDelay(TimeUnit.MINUTES));

            // Update the publish interval
            googleAnalyticsPublisher.setPublishInterval(12, TimeUnit.MINUTES);

            // Ensure that the publish job has been updated to match the new interval.
            assertEquals(1, vs.getQueue().size());
            assertEquals(12, vs.getQueue().peek().getDelay(TimeUnit.MINUTES));

            // Move time forward by new interval
            vs.advanceBy(12, TimeUnit.MINUTES);
            googleAnalyticsPublisher.close();

            // Ensure that analytics are published after the interval.
            List<Future<ClientAnalytics.LogRequest>> results = stub.getResults();
            assertEquals(1, results.size());
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void testCustomConnection() throws Exception {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try (ServerStub stub = new ServerStub()) {
            // Create an event to log.
            AndroidStudioEvent.Builder logged = createAndroidStudioEvent(5);

            // Use the JournalingUsageTracker to place some .trk files with events in the spool
            // directory.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            JournalingUsageTracker journalingUsageTracker =
                    new JournalingUsageTracker(
                            new AnalyticsSettings(), vs, testSpoolDir.getRoot().toPath());
            journalingUsageTracker.log(logged);
            vs.advanceBy(0);
            journalingUsageTracker.close();

            // Create helpers used to instantiate the publisher.
            AnalyticsSettings analyticsSettings = getTestAnalyticsSettings();
            // Create an instance of the publisher with a customized connection creation function.
            GoogleAnalyticsPublisher googleAnalyticsPublisher =
                    new GoogleAnalyticsPublisher(
                            analyticsSettings, vs, testSpoolDir.getRoot().toPath());
            googleAnalyticsPublisher.setCreateConnection(
                    () -> (HttpURLConnection) stub.getUrl().openConnection());
            // set the url to publish to to a reserved port which we know the server cannot connect
            // to.
            // https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.txt
            googleAnalyticsPublisher.setServerUrl(new URL("http://localhost:1023/bad"));

            // Move time forward to schedule the upload.
            vs.advanceBy(10, TimeUnit.MINUTES);
            googleAnalyticsPublisher.close();

            // Ensure that analytics are published after the interval.
            List<Future<ClientAnalytics.LogRequest>> results = stub.getResults();
            assertEquals(1, results.size());
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

    @Test
    public void testUpdatePublisher() {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try {
            // Create helpers used to instantiate the publisher.
            VirtualTimeScheduler vs = new VirtualTimeScheduler();
            assertNull(AnalyticsPublisher.getInstance());
            AnalyticsSettings settings = getTestAnalyticsSettings();

            // update the publisher, first call will initialize.
            AnalyticsPublisher.updatePublisher(new StdLogger(StdLogger.Level.ERROR), settings, vs);
            AnalyticsPublisher afterFirstUpdate = AnalyticsPublisher.getInstance();
            assertTrue(afterFirstUpdate instanceof GoogleAnalyticsPublisher);
            assertEquals(settings, afterFirstUpdate.getAnalyticsSettings());
            assertEquals(vs, afterFirstUpdate.getScheduler());

            // ensure a job is scheduled for the first publisher.
            VirtualTimeFuture<?> job = vs.getQueue().peek();
            assertNotNull(job);

            // update again, but now opt-ed out.
            settings.setHasOptedIn(false);
            AnalyticsPublisher.updatePublisher(new StdLogger(StdLogger.Level.ERROR), settings, vs);
            AnalyticsPublisher afterSecondUpdate = AnalyticsPublisher.getInstance();
            assertTrue(afterSecondUpdate instanceof NullAnalyticsPublisher);
            assertEquals(settings, afterFirstUpdate.getAnalyticsSettings());
            assertEquals(vs, afterFirstUpdate.getScheduler());

            // ensure job from first publisher has been canceled as part of update.
            assertTrue(job.isCancelled());
        } finally {
            EnvironmentFakes.setSystemEnvironment();
        }
    }

}
