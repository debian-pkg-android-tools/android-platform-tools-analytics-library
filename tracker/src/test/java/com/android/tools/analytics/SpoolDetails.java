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

import com.google.wireless.android.play.playlog.proto.ClientAnalytics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes a usage tracking spool directory's state.
 */
class SpoolDetails {
    private final List<Path> lockedFiles = new ArrayList<>();
    private final Map<Path, List<ClientAnalytics.LogEvent>> completedLogs = new HashMap<>();

    public List<Path> getLockedFiles() {
        return lockedFiles;
    }

    public Map<Path, List<ClientAnalytics.LogEvent>> getCompletedLogs() {
        return completedLogs;
    }
}
