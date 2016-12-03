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

import com.google.wireless.android.play.playlog.proto.ClientAnalytics;
import com.google.wireless.android.sdk.stats.AndroidStudioStats;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Tool to inspect the contents of .trk files used for usage analytics reporting. */
public class AnalyticsInspector {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: AnalyticsInspector <.trk-file>");
            System.exit(1);
        }

        // This logic allows specifying wildcards and ~ in paths w/o using the shell
        // (so it can be launched from within the IDE).
        Path pattern = new File(args[0].replace("~", System.getProperty("user.home"))).toPath();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(pattern.getParent(), pattern.getFileName().toString())) {
            for (Path path : stream) {
                System.out.println(path.toString());
                System.out.println("===");
                try (FileChannel channel = new RandomAccessFile(path.toFile(), "rw").getChannel()) {
                    InputStream inputStream = Channels.newInputStream(channel);
                    ClientAnalytics.LogEvent event = null;

                    // read all LogEvents from the trackFile.
                    while ((event = ClientAnalytics.LogEvent.parseDelimitedFrom(inputStream))
                            != null) {
                        AndroidStudioStats.AndroidStudioEvent studioEvent =
                                AndroidStudioStats.AndroidStudioEvent.parseFrom(
                                        event.getSourceExtension());
                        System.out.println(studioEvent);
                        System.out.println("---");
                    }
                }
            }
        }
    }
}
