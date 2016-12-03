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
import com.android.utils.ILogger;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Anonymizes strings for analytics reporting. Each string is sha256 encoded with a salt that is
 * unique per user and rotated every 28 days with a predictable time window.
 */
public class Anonymizer {
    /** Anonymizes a utf8 string. Logs any issues reading the salt for anonymizing. */
    public static String anonymizeUtf8(@NonNull ILogger logger, @NonNull String data)
            throws IOException {
        return anonymize(logger, data, Charsets.UTF_8);
    }

    /**
     * Anonymizes a string based on provided charset. Logs any issues reading the salt for
     * anonymizing.
     */
    public static String anonymize(
            @NonNull ILogger logger, @NonNull String data, @NonNull Charset charset)
            throws IOException {
        if (Strings.isNullOrEmpty(data)) {
            return "";
        }
        Hasher hasher = Hashing.sha256().newHasher();
        hasher.putBytes(AnalyticsSettings.getInstance(logger).getSalt());
        hasher.putString(data, charset);
        return hasher.hash().toString();
    }
}
