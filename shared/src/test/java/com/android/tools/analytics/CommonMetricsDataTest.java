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

import com.android.testutils.SystemPropertyOverrides;
import com.google.wireless.android.sdk.stats.AndroidStudioStats;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.DeviceInfo.ApplicationBinaryInterface;
import com.google.wireless.android.sdk.stats.AndroidStudioStats.ProductDetails.CpuArchitecture;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static junit.framework.TestCase.assertEquals;

/** Tests for {@link CommonMetricsData}. */
public class CommonMetricsDataTest {
    @Test
    public void cpuArchitectureFromStringTest() {
        assertEquals(
                CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                CommonMetricsData.cpuArchitectureFromString(null));
        assertEquals(
                CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                CommonMetricsData.cpuArchitectureFromString(""));
        assertEquals(CpuArchitecture.X86_64, CommonMetricsData.cpuArchitectureFromString("x86_64"));
        assertEquals(CpuArchitecture.X86_64, CommonMetricsData.cpuArchitectureFromString("ia64"));
        assertEquals(CpuArchitecture.X86_64, CommonMetricsData.cpuArchitectureFromString("amd64"));
        assertEquals(CpuArchitecture.X86, CommonMetricsData.cpuArchitectureFromString("i486"));
        assertEquals(CpuArchitecture.X86, CommonMetricsData.cpuArchitectureFromString("i586"));
        assertEquals(CpuArchitecture.X86, CommonMetricsData.cpuArchitectureFromString("i686"));
        assertEquals(CpuArchitecture.X86, CommonMetricsData.cpuArchitectureFromString("x86"));
        assertEquals(
                CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                CommonMetricsData.cpuArchitectureFromString("x96"));
        assertEquals(
                CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                CommonMetricsData.cpuArchitectureFromString("i6869"));
    }

    @Test
    public void getJvmArchitectureTest() throws Exception {
        try (SystemPropertyOverrides systemPropertyOverrides = new SystemPropertyOverrides()) {
            systemPropertyOverrides.setProperty("os.arch", null);
            assertEquals(
                    CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                    CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "");
            assertEquals(
                    CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                    CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "x86_64");
            assertEquals(CpuArchitecture.X86_64, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "ia64");
            assertEquals(CpuArchitecture.X86_64, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "amd64");
            assertEquals(CpuArchitecture.X86_64, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "i486");
            assertEquals(CpuArchitecture.X86, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "i586");
            assertEquals(CpuArchitecture.X86, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "i686");
            assertEquals(CpuArchitecture.X86, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "x86");
            assertEquals(CpuArchitecture.X86, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "x96");
            assertEquals(
                    CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                    CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "i6869");
            assertEquals(
                    CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                    CommonMetricsData.getJvmArchitecture());
        }
    }

    @Test
    public void getOsArchitectureTest() throws Exception {
        // Override system properties for 'os.arch' and 'os.name'.
        try (SystemPropertyOverrides systemPropertyOverrides = new SystemPropertyOverrides()) {
            systemPropertyOverrides.setProperty("os.arch", null);
            assertEquals(
                    CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                    CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "");
            assertEquals(
                    CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                    CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "x86_64");
            assertEquals(CpuArchitecture.X86_64, CommonMetricsData.getJvmArchitecture());
            systemPropertyOverrides.setProperty("os.arch", "i6869");
            assertEquals(
                    CpuArchitecture.UNKNOWN_CPU_ARCHITECTURE,
                    CommonMetricsData.getJvmArchitecture());

            systemPropertyOverrides.setProperty("os.arch", "x86");
            systemPropertyOverrides.setProperty("os.name", "Windows 10");
            EnvironmentFakes.setSingleProperty("PROCESSOR_ARCHITEW6432", "AMD64");
            assertEquals(CpuArchitecture.X86_64, CommonMetricsData.getOsArchitecture());
            systemPropertyOverrides.setProperty("os.name", "Linux");
            EnvironmentFakes.setSingleProperty("HOSTTYPE", "x86_64");
            assertEquals(CpuArchitecture.X86_64, CommonMetricsData.getOsArchitecture());

        } finally {
            Environment.setInstance(Environment.SYSTEM);
        }
    }

    @Test
    public void getOsNameTest() throws Exception {
        // Override system properties for 'os.name'.
        try (SystemPropertyOverrides systemPropertyOverrides = new SystemPropertyOverrides()) {
            // Test no os specified.
            systemPropertyOverrides.setProperty("os.name", "");
            assertEquals("unknown", CommonMetricsData.getOsName());
            // Test our supported OSes.
            systemPropertyOverrides.setProperty("os.name", "Linux");
            assertEquals("linux", CommonMetricsData.getOsName());
            systemPropertyOverrides.setProperty("os.name", "Windows 10");
            assertEquals("windows", CommonMetricsData.getOsName());
            systemPropertyOverrides.setProperty("os.name", "Windows Vista");
            assertEquals("windows", CommonMetricsData.getOsName());
            systemPropertyOverrides.setProperty("os.name", "Mac OS X");
            assertEquals("macosx", CommonMetricsData.getOsName());
            // Test unknown Oses.
            systemPropertyOverrides.setProperty("os.name", "My Custom OS");
            assertEquals("My Custom OS", CommonMetricsData.getOsName());
            String customLong = "My Custom OS With a really realy long name";
            systemPropertyOverrides.setProperty("os.name", customLong);
            assertEquals(customLong.substring(0, 32), CommonMetricsData.getOsName());
        }
    }

    @Test
    public void getMajorOsVersionTest() throws Exception {
        // Override system properties for 'os.version'.
        try (SystemPropertyOverrides systemPropertyOverrides = new SystemPropertyOverrides()) {
            // Test no version specified.
            systemPropertyOverrides.setProperty("os.version", "3");
            assertEquals(null, CommonMetricsData.getMajorOsVersion());
            // Test supported os version numbers.
            systemPropertyOverrides.setProperty("os.version", "3.13.0-85-generic");
            assertEquals("3.13", CommonMetricsData.getMajorOsVersion());
            systemPropertyOverrides.setProperty("os.version", "10.7.4");
            assertEquals("10.7", CommonMetricsData.getMajorOsVersion());
            systemPropertyOverrides.setProperty("os.version", "10.0");
            assertEquals("10.0", CommonMetricsData.getMajorOsVersion());
            // Test unsupported os version numbers.
            systemPropertyOverrides.setProperty("os.version", "a.b.c");
            assertEquals(null, CommonMetricsData.getMajorOsVersion());
        }
    }

    @Test
    public void applicationBinaryInterfaceFromStringTest() {
        assertEquals(
                ApplicationBinaryInterface.ARME_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString("armeabi"));
        assertEquals(
                ApplicationBinaryInterface.ARME_ABI_V6J,
                CommonMetricsData.applicationBinaryInterfaceFromString("armeabi-v6j"));
        assertEquals(
                ApplicationBinaryInterface.ARME_ABI_V6L,
                CommonMetricsData.applicationBinaryInterfaceFromString("armeabi-v6l"));
        assertEquals(
                ApplicationBinaryInterface.ARME_ABI_V7A,
                CommonMetricsData.applicationBinaryInterfaceFromString("armeabi-v7a"));
        assertEquals(
                ApplicationBinaryInterface.ARM64_V8A_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString("arm64-v8a"));
        assertEquals(
                ApplicationBinaryInterface.MIPS_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString("mips"));
        assertEquals(
                ApplicationBinaryInterface.MIPS_R2_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString("mips-r2"));
        assertEquals(
                ApplicationBinaryInterface.X86_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString("x86"));
        assertEquals(
                ApplicationBinaryInterface.X86_64_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString("x86_64"));
        assertEquals(
                ApplicationBinaryInterface.UNKNOWN_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString(null));
        assertEquals(
                ApplicationBinaryInterface.UNKNOWN_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString(""));
        assertEquals(
                ApplicationBinaryInterface.UNKNOWN_ABI,
                CommonMetricsData.applicationBinaryInterfaceFromString("my_custom_abi"));
    }

}
