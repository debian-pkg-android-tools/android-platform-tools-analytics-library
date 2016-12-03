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

import com.android.annotations.VisibleForTesting;
import com.google.wireless.android.sdk.stats.AndroidStudioStats;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.DeviceInfo.ApplicationBinaryInterface;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.ProductDetails;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Calculates common pieces of metrics data, used in various Android DevTools. */
public class CommonMetricsData {

    @VisibleForTesting
    /**
     * Detects and returns the OS architecture: x86, x86_64, ppc. This may differ or be equal to the
     * JVM architecture in the sense that a 64-bit OS can run a 32-bit JVM.
     */
    public static ProductDetails.CpuArchitecture getOsArchitecture() {
        ProductDetails.CpuArchitecture jvmArchitecture = getJvmArchitecture();
        if (jvmArchitecture == ProductDetails.CpuArchitecture.X86) {
            // This is the misleading case: the JVM is 32-bit but the OS
            // might be either 32 or 64. We can't tell just from this
            // property.
            // Macs are always on 64-bit, so we just need to figure it
            // out for Windows and Linux.

            String os = System.getProperty("os.name").toLowerCase();
            if (os.startsWith("win")) { //$NON-NLS-1$
                // When WOW64 emulates a 32-bit environment under a 64-bit OS,
                // it sets PROCESSOR_ARCHITEW6432 to AMD64 or IA64 accordingly.
                // Ref: http://msdn.microsoft.com/en-us/library/aa384274(v=vs.85).aspx

                String w6432 = Environment.getInstance().getVariable("PROCESSOR_ARCHITEW6432");
                if (w6432 != null && w6432.contains("64")) {
                    return ProductDetails.CpuArchitecture.X86_64;
                }
            } else if (os.startsWith("linux")) {
                // Let's try the obvious. This works in Ubuntu and Debian
                String s = Environment.getInstance().getVariable("HOSTTYPE");
                return cpuArchitectureFromString(s);
            }
        }
        return jvmArchitecture;
    }

    /**
     * Gets the JVM Architecture, NOTE this might not be the same as OS architecture. See {@link
     * #getOsArchitecture()} if OS architecture is needed.
     */
    public static ProductDetails.CpuArchitecture getJvmArchitecture() {
        String arch = System.getProperty("os.arch");
        return cpuArchitectureFromString(arch);
    }

    /**
     * Builds a {@link ProductDetails.CpuArchitecture} instance based on the provided string (e.g.
     * "x86_64").
     */
    public static ProductDetails.CpuArchitecture cpuArchitectureFromString(String cpuArchitecture) {
        if (cpuArchitecture == null || cpuArchitecture.length() == 0) {
            return ProductDetails.CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE;
        }

        if (cpuArchitecture.equalsIgnoreCase("x86_64")
                || cpuArchitecture.equalsIgnoreCase("ia64")
                || cpuArchitecture.equalsIgnoreCase("amd64")) {
            return ProductDetails.CpuArchitecture.X86_64;
        }

        if (cpuArchitecture.equalsIgnoreCase("x86")) {
            return ProductDetails.CpuArchitecture.X86;
        }

        if (cpuArchitecture.length() == 4
                && cpuArchitecture.charAt(0) == 'i'
                && cpuArchitecture.indexOf("86") == 2) {
            // Any variation of iX86 counts as x86 (i386, i486, i686).
            return ProductDetails.CpuArchitecture.X86;
        }
        return ProductDetails.CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE;
    }

    /** Gets a normalized version of the os name that this code is running on. */
    public static String getOsName() {
        String os = System.getProperty("os.name");

        if (os == null || os.length() == 0) {
            return "unknown";
        }

        String osLower = os.toLowerCase(Locale.US);

        if (osLower.startsWith("mac")) {
            os = "macosx";
        } else if (osLower.startsWith("win")) { //$NON-NLS-1$
            os = "windows";
        } else if (osLower.startsWith("linux")) { //$NON-NLS-1$
            os = "linux";

        } else if (os.length() > 32) {
            // Unknown -- send it verbatim so we can see it
            // but protect against arbitrarily long values
            os = os.substring(0, 32);
        }
        return os;
    }

    /**
     * Extracts the major os version that this code is running on in the form of '[0-9]+\.[0-9]+'
     */
    public static String getMajorOsVersion() {
        Pattern p = Pattern.compile("(\\d+)\\.(\\d+).*");
        String osVers = System.getProperty("os.version");
        if (osVers != null && osVers.length() > 0) {
            Matcher m = p.matcher(osVers);
            if (m.matches()) {
                return m.group(1) + '.' + m.group(2);
            }
        }
        return null;
    }

    public static ApplicationBinaryInterface applicationBinaryInterfaceFromString(String value) {
        if (value == null) {
            return ApplicationBinaryInterface.UNKNOWN_ABI;
        }
        switch (value) {
            case "armeabi-v6j":
                return ApplicationBinaryInterface.ARME_ABI_V6J;
            case "armeabi-v6l":
                return ApplicationBinaryInterface.ARME_ABI_V6L;
            case "armeabi-v7a":
                return ApplicationBinaryInterface.ARME_ABI_V7A;
            case "armeabi":
                return ApplicationBinaryInterface.ARME_ABI;
            case "arm64-v8a":
                return ApplicationBinaryInterface.ARM64_V8A_ABI;
            case "mips":
                return ApplicationBinaryInterface.MIPS_ABI;
            case "mips-r2":
                return ApplicationBinaryInterface.MIPS_R2_ABI;
            case "x86":
                return ApplicationBinaryInterface.X86_ABI;
            case "x86_64":
                return ApplicationBinaryInterface.X86_64_ABI;
            default:
                return ApplicationBinaryInterface.UNKNOWN_ABI;
        }
    }
}
