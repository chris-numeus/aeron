/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.agent;

import io.aeron.driver.exceptions.ConfigurationException;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.EnumSet;
import java.util.Set;

import static io.aeron.agent.EventConfiguration.ALL_LOGGER_EVENT_CODES;
import static io.aeron.agent.EventConfiguration.getEnabledEventCodes;
import static io.aeron.driver.Configuration.parseSize;
import static io.aeron.driver.Configuration.parseDuration;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;

public class EventConfigurationTest
{
    @Test
    public void nullPropertyShouldDefaultToProductionEventCodes()
    {
        assertThat(getEnabledEventCodes(null), Matchers.is(EnumSet.noneOf(EventCode.class)));
    }

    @Test
    public void malformedPropertyShouldDefaultToProductionEventCodes()
    {
        final PrintStream err = System.err;
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(stderr));
        try
        {
            final Set<EventCode> enabledEventCodes = getEnabledEventCodes("list of invalid options");
            assertThat(enabledEventCodes.size(), is(0));
            assertThat(stderr.toString(), startsWith("Unknown event code: list of invalid options"));
        }
        finally
        {
            System.setErr(err);
        }
    }

    @Test
    public void allPropertyShouldReturnAllEventCodes()
    {
        assertThat(getEnabledEventCodes("all"), is(ALL_LOGGER_EVENT_CODES));
    }

    @Test
    public void eventCodesPropertyShouldBeParsedAsListOfEventCodes()
    {
        final Set<EventCode> expectedCodes = EnumSet.of(EventCode.FRAME_OUT, EventCode.FRAME_IN);
        assertThat(getEnabledEventCodes("FRAME_OUT,FRAME_IN"), is(expectedCodes));
    }

    @Test
    public void makeTagBitSet()
    {
        final Set<EventCode> eventCodes = EnumSet.of(EventCode.FRAME_OUT, EventCode.FRAME_IN);
        final long bitSet = EventConfiguration.makeTagBitSet(eventCodes);
        assertThat(bitSet, Matchers.is(EventCode.FRAME_OUT.tagBit() | EventCode.FRAME_IN.tagBit()));
    }

    @Test
    public void shouldParseSizesWithSuffix()
    {
        assertThat(parseSize("", "1"), Matchers.equalTo(1L));
        assertThat(parseSize("", "1k"), Matchers.equalTo(1024L));
        assertThat(parseSize("", "1K"), Matchers.equalTo(1024L));
        assertThat(parseSize("", "1m"), Matchers.equalTo(1024L * 1024));
        assertThat(parseSize("", "1M"), Matchers.equalTo(1024L * 1024));
        assertThat(parseSize("", "1g"), Matchers.equalTo(1024L * 1024 * 1024));
        assertThat(parseSize("", "1G"), Matchers.equalTo(1024L * 1024 * 1024));
    }

    @Test
    public void shouldParseTimesWithSuffix()
    {
        assertThat(parseDuration("", "1"), Matchers.equalTo(1L));
        assertThat(parseDuration("", "1ns"), Matchers.equalTo(1L));
        assertThat(parseDuration("", "1NS"), Matchers.equalTo(1L));
        assertThat(parseDuration("", "1us"), Matchers.equalTo(1000L));
        assertThat(parseDuration("", "1US"), Matchers.equalTo(1000L));
        assertThat(parseDuration("", "1ms"), Matchers.equalTo(1000L * 1000));
        assertThat(parseDuration("", "1MS"), Matchers.equalTo(1000L * 1000));
        assertThat(parseDuration("", "1s"), Matchers.equalTo(1000L * 1000 * 1000));
        assertThat(parseDuration("", "1S"), Matchers.equalTo(1000L * 1000 * 1000));
        assertThat(parseDuration("", "12s"), Matchers.equalTo(12L * 1000 * 1000 * 1000));
    }

    @Test(expected = ConfigurationException.class)
    public void shouldThrowWhenParseTimeHasBadSuffix()
    {
        parseDuration("", "1g");
    }

    @Test(expected = ConfigurationException.class)
    public void shouldThrowWhenParseTimeHasBadTwoLetterSuffix()
    {
        parseDuration("", "1zs");
    }

    @Test(expected = ConfigurationException.class)
    public void shouldThrowWhenParseSizeOverflows()
    {
        parseSize("", 8589934592L + "g");
    }
}
