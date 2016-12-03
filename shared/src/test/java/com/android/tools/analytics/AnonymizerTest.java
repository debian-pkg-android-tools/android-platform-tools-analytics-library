/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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
import com.android.utils.DateProvider;
import com.android.utils.ILogger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.*;

/** Tests for @{link Anonymizer}. */
public class AnonymizerTest {
    private static final String MY_RANDOM_TEXT1 = "My random text";
    private static final String MY_RANDOM_TEXT2 = "More random text";
    // In our case we already create an instance of AnalysisSettings so no logging should occur.
    private static final ILogger DO_NOT_LOG =
            new ILogger() {
                @Override
                public void error(
                        @Nullable Throwable t, @Nullable String msgFormat, Object... args) {
                    fail(String.format(msgFormat, args));
                }

                @Override
                public void warning(@NonNull String msgFormat, Object... args) {
                    fail(String.format(msgFormat, args));
                }

                @Override
                public void info(@NonNull String msgFormat, Object... args) {
                    fail(String.format(msgFormat, args));
                }

                @Override
                public void verbose(@NonNull String msgFormat, Object... args) {
                    fail(String.format(msgFormat, args));
                }
            };

    @Rule public final TemporaryFolder testConfigDir = new TemporaryFolder();

    @Test
    public void anonymizerTest() throws IOException {
        // Configure the paths to use a temp directory for reading from and writing to.
        EnvironmentFakes.setCustomAndroidSdkHomeEnvironment(
                testConfigDir.getRoot().toPath().toString());
        try {
            // Prepopulate AnalysisSettings.
            AnalyticsSettings.setInstanceForTest(new AnalyticsSettings());

            // Set date to a specific skew range.
            AnalyticsSettings.sDateProvider = new StubDateProvider(2016, 3, 18);

            // Ensure we get some form of anonymization.
            String data1 = Anonymizer.anonymizeUtf8(DO_NOT_LOG, MY_RANDOM_TEXT1);
            assertNotNull(data1);
            assertNotEquals(MY_RANDOM_TEXT1, data1);

            // Ensure different input gives different output.
            String other = Anonymizer.anonymizeUtf8(DO_NOT_LOG, MY_RANDOM_TEXT2);
            assertNotEquals(data1, other);

            // Ensure that anonymizing is stable with time stable.
            String data2 = Anonymizer.anonymizeUtf8(DO_NOT_LOG, MY_RANDOM_TEXT1);
            assertEquals(data1, data2);

            // Set date to different date in same skew range.
            AnalyticsSettings.sDateProvider = new StubDateProvider(2016, 4, 15);
            String data3 = Anonymizer.anonymizeUtf8(DO_NOT_LOG, MY_RANDOM_TEXT1);
            // Ensure that same input is stable for dates in same skew range.
            assertEquals(data1, data3);

            // Set date to new skew range
            AnalyticsSettings.sDateProvider = new StubDateProvider(2016, 4, 16);

            // Ensure that same input is different for different skew range.
            String data4 = Anonymizer.anonymizeUtf8(DO_NOT_LOG, MY_RANDOM_TEXT1);
            assertNotEquals(data1, data4);

            // Ensure that null and empty are reported as empty.
            String data6 = Anonymizer.anonymizeUtf8(DO_NOT_LOG, null);
            assertEquals("", data6);
            String data7 = Anonymizer.anonymizeUtf8(DO_NOT_LOG, "");
            assertEquals("", data7);
        } finally {
            // Undo stub of DateProvider.
            AnalyticsSettings.sDateProvider = DateProvider.SYSTEM;
            EnvironmentFakes.setSystemEnvironment();
        }
    }
}
