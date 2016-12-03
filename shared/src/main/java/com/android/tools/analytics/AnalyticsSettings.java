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
import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.utils.DateProvider;
import com.android.utils.ILogger;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Settings related to analytics reporting. These settings are stored in
 * ~/.android/analytics.settings as a json file.
 */
public class AnalyticsSettings {
    private static final LocalDate EPOCH = LocalDate.ofEpochDay(0);
    // the gate is used to ensure settings are only in process of loading once.
    private static final transient Object sGate = new Object();

    @VisibleForTesting static AnalyticsSettings sInstance;

    @VisibleForTesting static DateProvider sDateProvider = DateProvider.SYSTEM;

    @SerializedName("userId")
    private String mUserId;

    @SerializedName("hasOptedIn")
    private boolean mHasOptedIn;

    @SerializedName("debugDisablePublishing")
    private boolean mDebugDisablePublishing;

    @SerializedName("saltValue")
    private BigInteger mSaltValue;

    @SerializedName("saltSkew")
    private int mSaltSkew;

    /**
     * Gets a user id used for reporting analytics. This id is pseudo-anonymous.
     */
    public String getUserId() {
        return mUserId;
    }

    /**
     * Indicates whether the user has opted in to sending analytics reporting to Google.
     */
    public boolean hasOptedIn() {
        return mHasOptedIn;
    }

    /**
     * Sets a new user id to be used for reporting analytics. This id should be pseudo-anonymous.
     */
    public void setUserId(String userId) {
        this.mUserId = userId;
    }

    /**
     * Sets the user's choice for opting in to sending analytics reporting to Google or not.
     */
    public void setHasOptedIn(boolean mHasOptedIn) {
        this.mHasOptedIn = mHasOptedIn;
    }

    /** Indicates whether the user has disabled publishing for debugging purposes. */
    public boolean hasDebugDisablePublishing() {
        return mDebugDisablePublishing;
    }

    /**
     * Gets a binary blob to ensure per user anonymization. Gets automatically rotated every 28
     * days. Primarily used by {@link Anonymizer}.
     */
    public byte[] getSalt() throws IOException {
        synchronized (sGate) {
            int currentSaltSkew = currentSaltSkew();
            if (mSaltSkew != currentSaltSkew) {
                mSaltSkew = currentSaltSkew;
                SecureRandom random = new SecureRandom();
                byte[] data = new byte[24];
                random.nextBytes(data);
                mSaltValue = new BigInteger(data);
                saveSettings();
            }
            byte[] blob = mSaltValue.toByteArray();
            byte[] fullBlob = blob;
            if (blob.length < 24) {
                fullBlob = new byte[24];
                System.arraycopy(blob, 0, fullBlob, 0, blob.length);
            }
            return fullBlob;
        }
    }

    /**
     * Gets the current salt skew, this is used by {@link #getSalt()} to update the salt every 28
     * days with a consistent window. This window size allows 4 week and 1 week analyses.
     */
    @VisibleForTesting
    static int currentSaltSkew() {
        LocalDate now =
                LocalDate.from(
                        Instant.ofEpochMilli(sDateProvider.now().getTime()).atZone(ZoneOffset.UTC));
        // Unix epoch was on a Thursday, but we want Monday to be the day the salt is refreshed.
        long days = ChronoUnit.DAYS.between(EPOCH, now) + 3;
        return (int) (days / 28);
    }

    /**
     * Loads an existing settings file from disk, or creates a new valid settings object if none
     * exists. In case of the latter, will try to load uid.txt for maintaining the same uid with
     * previous metrics reporting.
     *
     * @throws IOException if there are any issues reading the settings file.
     */
    @VisibleForTesting
    @Nullable
    public static AnalyticsSettings loadSettings() throws IOException {
        File file = getSettingsFile();
        if (!file.exists()) {
            return null;
        }
        FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
        try (FileLock ignored = channel.tryLock()) {
            InputStream inputStream = Channels.newInputStream(channel);
            Gson gson = new GsonBuilder().create();
            AnalyticsSettings settings =
                    gson.fromJson(new InputStreamReader(inputStream), AnalyticsSettings.class);
            sInstance = settings;
            return settings;
        } catch (OverlappingFileLockException e) {
            throw new IOException("Unable to lock settings file " + file.toString(), e);
        } catch (JsonParseException e) {
            throw new IOException("Unable to parse settings file " + file.toString(), e);
        }
    }

    /**
     * Creates a new settings object and writes it to disk. Will try to load uid.txt for maintaining
     * the same uid with previous metrics reporting.
     *
     * @throws IOException if there are any issues writing the settings file.
     */
    @VisibleForTesting
    @NonNull
    public static AnalyticsSettings createNewAnalyticsSettings() throws IOException {
        AnalyticsSettings settings = new AnalyticsSettings();

        File uidFile = Paths.get(AnalyticsPaths.getAndroidSettingsHome(), "uid.txt").toFile();
        if (uidFile.exists()) {
            try {
                String uid = Files.readFirstLine(uidFile, Charsets.UTF_8);
                settings.setUserId(uid);
            } catch (IOException e) {
                // Ignore and set new UID.
            }
        }
        if (settings.getUserId() == null) {
            settings.setUserId(UUID.randomUUID().toString());
        }
        settings.saveSettings();
        return settings;
    }

    /**
     * Get or creates an instance of the settings. Uses the following strategies in order:
     *
     * <ul>
     * <li>Use existing instance
     * <li>Load existing 'analytics.settings' file from disk
     * <li>Create new 'analytics.settings' file
     * <li>Create instance without persistence
     * </ul>
     *
     * Any issues reading/writing the config file will be logged to the logger.
     */
    public static AnalyticsSettings getInstance(ILogger logger) {
        synchronized (sGate) {
            if (sInstance != null) {
                return sInstance;
            }
            try {
                sInstance = loadSettings();
            } catch (IOException e) {
                logger.error(e, "Unable to load analytics settings.");
            }
            if (sInstance == null) {
                try {
                    sInstance = createNewAnalyticsSettings();
                } catch (IOException e) {
                    logger.error(e, "Unable to create new analytics settings.");
                }
            }
            if (sInstance == null) {
                sInstance = new AnalyticsSettings();
                sInstance.setUserId(UUID.randomUUID().toString());
            }
            return sInstance;
        }
    }

    /**
     * Allows test to set a custom version of the AnalyticsSettings to test different setting
     * states.
     */
    @VisibleForTesting
    public static void setInstanceForTest(@Nullable AnalyticsSettings settings) {
        sInstance = settings;
    }

    /**
     * Helper to get the file to read/write settings from based on the configured android settings
     * home.
     */
    private static File getSettingsFile() {
        return Paths.get(AnalyticsPaths.getAndroidSettingsHome(), "analytics.settings").toFile();
    }

    /**
     * Writes this settings object to disk.
     * @throws IOException if there are any issues writing the settings file.
     */
    public void saveSettings() throws IOException {
        File file = getSettingsFile();
        try (RandomAccessFile settingsFile = new RandomAccessFile(file, "rw");
                FileChannel channel = settingsFile.getChannel();
                FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Unable to lock settings file " + file.toString());
            }
            channel.truncate(0);
            OutputStream outputStream = Channels.newOutputStream(channel);
            Gson gson = new GsonBuilder().create();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream);
            gson.toJson(this, writer);
            writer.flush();
            outputStream.flush();

        } catch (OverlappingFileLockException e) {
            throw new IOException("Unable to lock settings file " + file.toString(), e);
        }
    }
}
