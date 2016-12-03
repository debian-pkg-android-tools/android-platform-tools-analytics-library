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

import com.android.annotations.VisibleForTesting;
import com.android.utils.DateProvider;
import com.android.utils.ILogger;
import com.android.utils.StdLogger;
import com.google.wireless.android.play.playlog.proto.ClientAnalytics;
import com.google.wireless.android.sdk.stats.AndroidStudioStats;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/*
 * Publish collected analytics to Google's servers.
 * Uses the provided {@link ScheduleExecutorService} to periodically (10 mins by default),
 * scan the provided spool location for new .trk files. If it finds any it parses the .trk files
 * and upload the parsed events to Google's servers along with additional metadata such as meta
 * metrics, client info and timing information..
 */
public class GoogleAnalyticsPublisher extends AnalyticsPublisher {
    // While access to fields is atomic (due to volatile on long & doubles), this code has many
    // methods that operate on various variables at once. We synchronize any method that operates
    // on multiple variables or access members of those variables (e.g. method calls).
    private static final Object mGate = new Object();

    @VisibleForTesting static DateProvider sDateProvider = DateProvider.SYSTEM;

    private final Path mSpoolLocation;
    private final ClientAnalytics.LogRequest mBaseLogRequest;

    private ScheduledFuture<?> mPublishJob;
    private int mScheduleVersion = 0;
    private URL mServerUrl = getDefaultServerUrl();
    private volatile long mStartTime = sDateProvider.now().getTime();
    private volatile long mBytesSentInLastPublish = 0;
    private int mFailedConnections = 0;
    private int mFailedServerReplies = 0;
    private int mBackoffRatio = 1;
    Callable<HttpURLConnection> mCreateConnection = this::defaultCreateConnection;
    private ILogger mLogger = new StdLogger(StdLogger.Level.WARNING);

    /**
     * Creates a new instance for publishing metrics
     *
     * @param analyticsSettings used for sending pseudoanonymous ID along with the analytics.
     * @param spoolLocation location to look for .trk files to upload.
     * @param scheduler used for scheduling periodic checks of the spool location.
     */
    GoogleAnalyticsPublisher(
            AnalyticsSettings analyticsSettings,
            ScheduledExecutorService scheduler,
            Path spoolLocation) {
        super(analyticsSettings, scheduler);
        this.mSpoolLocation = spoolLocation;
        // Create a LogRequest to use as a template for all LogRequest objects.
        this.mBaseLogRequest =
                ClientAnalytics.LogRequest.newBuilder()
                        .setClientInfo(
                                ClientAnalytics.ClientInfo.newBuilder()
                                        .setClientType(
                                                ClientAnalytics.ClientInfo.ClientType.DESKTOP)
                                        .setDesktopClientInfo(
                                                ClientAnalytics.DesktopClientInfo.newBuilder()
                                                        .setLoggingId(analyticsSettings.getUserId())
                                                        .setOs(CommonMetricsData.getOsName())
                                                        .setOsMajorVersion(
                                                                CommonMetricsData
                                                                        .getMajorOsVersion())
                                                        .setOsFullVersion(
                                                                System.getProperty("os.version"))))
                        // Set the log source for the Clearcut service. This will be always the
                        // same no matter what Android devtool we're logging from.
                        .setLogSource(ClientAnalytics.LogRequest.LogSource.ANDROID_STUDIO)
                        .build();

        // Schedule the first publish of logs from the spool directory.
        schedulePublish(getPublishInterval());
    }

    @Override
    public void close() throws Exception {
        synchronized (mGate) {
            mScheduleVersion++;
            mPublishJob.cancel(false);
        }
    }

    @Override
    public void setPublishInterval(long interval, TimeUnit unit) {
        synchronized (mGate) {
            super.setPublishInterval(interval, unit);
            schedulePublish(getPublishInterval());
        }
    }

    /**
     * Changes the logger used by this publisher.
     */
    public void setLogger(ILogger logger) {
        mLogger = logger;
    }

    /**
     * Looks for any .trk files queued up in the spool directory and if so tries to publish
     * them to Google's servers.
     */
    private void publishQueuedAnalytics() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mSpoolLocation, "*.trk")) {
            for (Path file : stream) {
                // TODO: consider maintaining a list of failed .trk files and skip them after n
                // failures.
                // if publishing any file fails, we stop processing for this cycle and try again
                // in the next cycle.
                if (!tryPublishAnalytics(file)) {
                    return;
                }
            }
        } catch (Exception e) {
            mLogger.error(e, "Failure reading analytics spool directory.");
        }
    }

    /**
     * Tries to publish analytics for the specified track file.
     * @return true if file was uploaded successfully, skipped as it was locked or had zero events,
     *  false if uploading failed (connection or server error).
     */
    private boolean tryPublishAnalytics(Path trackFile) {
        File file = trackFile.toFile();
        boolean success = false;
        try (FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
                FileLock lock = channel.tryLock()) {
            if (lock == null) {
                // Another process has the file open (e.g. a command-line tool writing analytics).
                // skip for now but continue publishing other track files.
                return true;
            }

            List<ClientAnalytics.LogEvent> entries = new ArrayList<>();
            // Try to lock the file, this ensures no other code (e.g. the usage tracker)
            // has a lock on the file.
            InputStream inputStream = Channels.newInputStream(channel);
            ClientAnalytics.LogEvent event = null;

            // read all LogEvents from the trackFile.
            while ((event = ClientAnalytics.LogEvent.parseDelimitedFrom(inputStream)) != null) {
                entries.add(event);
            }

            if (entries.isEmpty()) {
                // if this is an empty file, no need to publish, just delete the file and continue.
                success = true;
            } else {
                // Add the meta metric log and build a LogRequest.
                long now = sDateProvider.now().getTime();
                entries.add(0, getMetaMetric(now));
                ClientAnalytics.LogRequest request = buildLogRequest(entries, now);

                // Send the analytics to the specified server.
                int responseCode = trySendToServer(request);
                success = isSuccess(responseCode);
                if (success) {
                    // only if publishing succeeded, delete the file, otherwise we'll try again.
                    // successful publishing means we do no longer need to backoff.
                    mBackoffRatio = 1;
                    mFailedConnections = 0;
                    mFailedServerReplies = 0;
                } else {
                    // publishing failed with a server error, track and increase our backoff ratio.
                    mFailedServerReplies++;
                    mBackoffRatio *= 2;
                }
            }
        } catch (IOException e) {
            mLogger.error(e, "Failure publishing analytics, unable to connect to server");
            // publishing failed with a network error, track and increase our backoff ratio.
            mFailedConnections++;
            mBackoffRatio *= 2;
            // stop this publishing cycle, try again later.
            return false;
        } catch (OverlappingFileLockException e) {
            // Current process has the file open (e.g. JournalingUsageTracker).
            // skip for now but continue publishing other track files.
            return true;
        }

        // We need to delete the file outside of the lock as deleting inside the lock doesn't
        // work on Windows.
        if (success) {
            file.delete();
        }
        return success;
    }

    /**
     * Default value of mCreateConnection. Uses current url to create a connection.
     */
    private HttpURLConnection defaultCreateConnection() throws IOException {
        URLConnection connection = mServerUrl.openConnection();
        if (connection instanceof HttpURLConnection) {
            return (HttpURLConnection) connection;
        } else {
            mLogger.error(null, "Unexpected connection type %s", connection.getClass().getName());
            return null;
        }
    }

    /**
     * Allows hosts of the publisher to plug in custom connections. E.g. to configure the connection
     * to go over a proxy server specified in the host of the publisher.
     */
    public void setCreateConnection(Callable<HttpURLConnection> createConnection) {
        mCreateConnection = createConnection;
    }

    /**
     * Tries to upload metrics to the specified server using HTTP Post.
     * @return http status code from the request.
     */
    private int trySendToServer(ClientAnalytics.LogRequest request) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = mCreateConnection.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (connection == null) {
            // 'Method not allowed' as we don't have a valid connection.
            return 405;
        }
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        OutputStream body = connection.getOutputStream();
        byte[] requestBytes = request.toByteArray();
        body.write(requestBytes);
        mBytesSentInLastPublish = requestBytes.length;
        body.close();

        connection.connect();
        int responseCode = connection.getResponseCode();
        if (!isSuccess(responseCode)) {
            mLogger.error(
                    null,
                    "Failure publishing metrics. Server responded with status code "
                            + "'%d' and message '%s'",
                    responseCode,
                    connection.getResponseMessage());
        }
        return responseCode;
    }

    /** Builds a {@link ClientAnalytics.LogRequest} proto based on the provided entries and time. */
    private ClientAnalytics.LogRequest buildLogRequest(
            List<ClientAnalytics.LogEvent> entries, long time) {
        return ClientAnalytics.LogRequest.newBuilder(mBaseLogRequest)
                .setRequestTimeMs(time)
                .setRequestUptimeMs(time - mStartTime)
                .addAllLogEvent(entries)
                .build();
    }

    /**
     * Creates {@link ClientAnalytics.LogEvent} with meta metrics, used to measure the health
     * of our metrics reporting system.
     */
    private ClientAnalytics.LogEvent getMetaMetric(long time) {
        return ClientAnalytics.LogEvent.newBuilder()
                .setEventTimeMs(time)
                .setSourceExtension(
                        AndroidStudioStats.AndroidStudioEvent.newBuilder()
                                .setCategory(
                                        AndroidStudioStats.AndroidStudioEvent.EventCategory.META)
                                .setKind(
                                        AndroidStudioStats.AndroidStudioEvent.EventKind
                                                .META_METRICS)
                                .setMetaMetrics(
                                        AndroidStudioStats.MetaMetrics.newBuilder()
                                                .setBytesSentInLastUpload(mBytesSentInLastPublish)
                                                .setFailedConnections(mFailedConnections)
                                                .setFailedServerReplies(mFailedServerReplies))
                                .build()
                                .toByteString())
                .build();
    }

    /**
     * Schedules the job that looks for .trk files and publishes them to Google's servers.
     * Needs to be called while locked on {@link #mGate}.
     * NOTE: this method is self-rescheduling.
     */
    private void schedulePublish(long publishIntervalNanoSeconds) {
        final int currentScheduleVersion = ++mScheduleVersion;

        // if any existing publish is pending and it hasn't started yet, cancel it as we've been
        // provided a new interval to schedule on and it would be odd if the a job still got run
        // at the old schedule.
        if (mPublishJob != null) {
            mPublishJob.cancel(false);
        }
        mPublishJob =
                getScheduler()
                        .schedule(
                                () -> {
                                    synchronized (mGate) {
                                        publishQueuedAnalytics();
                                        // only schedule next beat if we're still the authority.
                                        if (mScheduleVersion == currentScheduleVersion) {
                                            schedulePublish(publishIntervalNanoSeconds);
                                        }
                                    }
                                },
                                // Next job is scheduled with exponential backoff with a max of 1 day.
                                // this is reset to 1 when the job successfully completes.
                                Math.min(
                                        publishIntervalNanoSeconds * mBackoffRatio,
                                        TimeUnit.DAYS.toNanos(1)),
                                TimeUnit.NANOSECONDS);
    }

    /**
     * A helper to set the default server URL in the constructor, removes exception from the
     * signature that we know cannot be thrown.
     */
    private static URL getDefaultServerUrl() {
        try {
            return new URL("https://play.google.com/log?format=raw");
        } catch (MalformedURLException e) {
            // NoOp, url is well-formed.
            throw new RuntimeException(e);
        }
    }

    /**
     * Gets the address of the server currently used to publish to.
     */
    public URL getServerUrl() {
        return mServerUrl;
    }

    /**
     * Updates the server used to publish analytics to.
     */
    public GoogleAnalyticsPublisher setServerUrl(URL serverUrl) {
        synchronized (mGate) {
            this.mServerUrl = serverUrl;
        }
        return this;
    }

    /**
     * Checks if the http status code indicates success or not.
     */
    private static boolean isSuccess(int statusCode) {
        return (200 <= statusCode && statusCode < 300);
    }
}
