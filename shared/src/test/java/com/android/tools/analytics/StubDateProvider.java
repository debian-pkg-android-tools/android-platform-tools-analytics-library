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

import com.android.utils.DateProvider;

import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * A {@link DateProvider} that can be set to a specific date for use in tests. NOTE: months are 0-11
 * while days are 1-31 range.
 *
 * Uses UTC as time zone.
 */
public class StubDateProvider implements DateProvider {
    private final int year;
    private final int month;
    private final int day;

    public StubDateProvider(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    @Override
    public Date now() {
        Calendar calendar = new GregorianCalendar(year, month, day);
        calendar.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
        return calendar.getTime();
    }
}
