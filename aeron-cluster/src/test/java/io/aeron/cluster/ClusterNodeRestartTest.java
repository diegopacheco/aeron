/*
 * Copyright 2014-2020 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.Counter;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.test.Tests;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Duration.ofSeconds;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ClusterNodeRestartTest
{
    private static final long MAX_CATALOG_ENTRIES = 1024;
    private static final int MESSAGE_LENGTH = SIZE_OF_INT;
    private static final int TIMER_MESSAGE_LENGTH = SIZE_OF_INT + SIZE_OF_LONG + SIZE_OF_LONG;
    private static final int MESSAGE_VALUE_OFFSET = 0;
    private static final int TIMER_MESSAGE_ID_OFFSET = MESSAGE_VALUE_OFFSET + SIZE_OF_INT;
    private static final int TIMER_MESSAGE_DELAY_OFFSET = TIMER_MESSAGE_ID_OFFSET + SIZE_OF_LONG;

    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer container;
    private AeronCluster aeronCluster;

    private final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
    private final AtomicReference<String> serviceState = new AtomicReference<>();
    private final AtomicLong snapshotCount = new AtomicLong();
    private final Counter mockSnapshotCounter = mock(Counter.class);

    @BeforeEach
    public void before()
    {
        when(mockSnapshotCounter.incrementOrdered()).thenAnswer((inv) -> snapshotCount.getAndIncrement());
        when(mockSnapshotCounter.increment()).thenAnswer((inv) -> snapshotCount.getAndIncrement());

        launchClusteredMediaDriver(true);
    }

    @AfterEach
    public void after()
    {
        CloseHelper.closeAll(aeronCluster, container, clusteredMediaDriver);

        if (null != clusteredMediaDriver)
        {
            clusteredMediaDriver.consensusModule().context().deleteDirectory();
            clusteredMediaDriver.archive().context().deleteDirectory();
            clusteredMediaDriver.mediaDriver().context().deleteDirectory();
        }
    }

    @Test
    public void shouldRestartServiceWithReplay()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);
            final AtomicLong restartServiceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            sendCountedMessageIntoCluster(0);
            TestCluster.awaitCount(serviceMsgCounter, 1);

            forceCloseForRestart();

            launchClusteredMediaDriver(false);
            launchService(restartServiceMsgCounter);
            connectClient();

            TestCluster.awaitCount(restartServiceMsgCounter, 1);

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldRestartServiceWithReplayAndContinue()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            sendCountedMessageIntoCluster(0);
            TestCluster.awaitCount(serviceMsgCounter, 1);

            forceCloseForRestart();

            final AtomicLong restartServiceMsgCounter = new AtomicLong(0);

            launchClusteredMediaDriver(false);
            launchService(restartServiceMsgCounter);
            connectClient();

            sendCountedMessageIntoCluster(1);
            TestCluster.awaitCount(restartServiceMsgCounter, 1);

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldRestartServiceFromEmptySnapshot()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            final CountersReader counters = container.context().aeron().countersReader();
            final AtomicCounter controlToggle = ClusterControl.findControlToggle(counters);
            assertNotNull(controlToggle);
            assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));

            TestCluster.awaitCount(snapshotCount, 1);

            forceCloseForRestart();

            serviceState.set(null);
            launchClusteredMediaDriver(false);
            launchService(serviceMsgCounter);
            connectClient();

            while (null == serviceState.get())
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            assertEquals("0", serviceState.get());

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldRestartServiceFromSnapshot()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            sendCountedMessageIntoCluster(0);
            sendCountedMessageIntoCluster(1);
            sendCountedMessageIntoCluster(2);

            TestCluster.awaitCount(serviceMsgCounter, 3);

            final CountersReader counters = aeronCluster.context().aeron().countersReader();
            final AtomicCounter controlToggle = ClusterControl.findControlToggle(counters);
            assertNotNull(controlToggle);
            assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));

            TestCluster.awaitCount(snapshotCount, 1);

            forceCloseForRestart();

            serviceState.set(null);
            launchClusteredMediaDriver(false);
            launchService(serviceMsgCounter);
            connectClient();

            while (null == serviceState.get())
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            assertEquals("3", serviceState.get());

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldRestartServiceFromSnapshotWithFurtherLog()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            sendCountedMessageIntoCluster(0);
            sendCountedMessageIntoCluster(1);
            sendCountedMessageIntoCluster(2);

            TestCluster.awaitCount(serviceMsgCounter, 3);

            final CountersReader counters = aeronCluster.context().aeron().countersReader();
            final AtomicCounter controlToggle = ClusterControl.findControlToggle(counters);
            assertNotNull(controlToggle);
            assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));

            TestCluster.awaitCount(snapshotCount, 1);

            sendCountedMessageIntoCluster(3);

            TestCluster.awaitCount(serviceMsgCounter, 4);

            forceCloseForRestart();

            serviceMsgCounter.set(0);
            launchClusteredMediaDriver(false);
            launchService(serviceMsgCounter);
            connectClient();

            TestCluster.awaitCount(serviceMsgCounter, 1);

            assertEquals("4", serviceState.get());

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldTakeMultipleSnapshots()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            final CountersReader counters = aeronCluster.context().aeron().countersReader();
            final AtomicCounter controlToggle = ClusterControl.findControlToggle(counters);
            assertNotNull(controlToggle);

            for (int i = 0; i < 3; i++)
            {
                assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));

                while (controlToggle.get() != ClusterControl.ToggleState.NEUTRAL.code())
                {
                    Tests.sleep(1);
                    Tests.checkInterruptStatus();
                }
            }

            assertEquals(3L, snapshotCount.get());

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldRestartServiceWithTimerFromSnapshotWithFurtherLog()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            sendCountedMessageIntoCluster(0);
            sendCountedMessageIntoCluster(1);
            sendCountedMessageIntoCluster(2);
            sendTimerMessageIntoCluster(3, 1, TimeUnit.HOURS.toMillis(10));

            TestCluster.awaitCount(serviceMsgCounter, 4);

            final CountersReader counters = aeronCluster.context().aeron().countersReader();
            final AtomicCounter controlToggle = ClusterControl.findControlToggle(counters);
            assertNotNull(controlToggle);
            assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));

            TestCluster.awaitCount(snapshotCount, 1);

            sendCountedMessageIntoCluster(4);

            TestCluster.awaitCount(serviceMsgCounter, 5);

            forceCloseForRestart();

            serviceMsgCounter.set(0);
            launchClusteredMediaDriver(false);
            launchService(serviceMsgCounter);
            connectClient();

            TestCluster.awaitCount(serviceMsgCounter, 1);

            assertEquals("5", serviceState.get());

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldTriggerRescheduledTimerAfterReplay()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicInteger triggeredTimersCounter = new AtomicInteger();

            launchReschedulingService(triggeredTimersCounter);
            connectClient();

            sendCountedMessageIntoCluster(0);

            while (triggeredTimersCounter.get() < 2)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            forceCloseForRestart();

            final int triggeredSinceStart = triggeredTimersCounter.getAndSet(0);

            launchClusteredMediaDriver(false);
            launchReschedulingService(triggeredTimersCounter);

            while (triggeredTimersCounter.get() <= triggeredSinceStart)
            {
                Thread.yield();
                Tests.checkInterruptStatus();
            }

            ClusterTests.failOnClusterError();
        });
    }

    @Test
    public void shouldRestartServiceTwiceWithInvalidSnapshotAndFurtherLog()
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            final AtomicLong serviceMsgCounter = new AtomicLong(0);

            launchService(serviceMsgCounter);
            connectClient();

            sendCountedMessageIntoCluster(0);
            sendCountedMessageIntoCluster(1);
            sendCountedMessageIntoCluster(2);

            TestCluster.awaitCount(serviceMsgCounter, 3);

            final CountersReader counters = container.context().aeron().countersReader();
            final AtomicCounter controlToggle = ClusterControl.findControlToggle(counters);
            assertNotNull(controlToggle);
            assertTrue(ClusterControl.ToggleState.SNAPSHOT.toggle(controlToggle));

            TestCluster.awaitCount(snapshotCount, 1);

            sendCountedMessageIntoCluster(3);

            TestCluster.awaitCount(serviceMsgCounter, 4);

            forceCloseForRestart();

            final PrintStream mockOut = mock(PrintStream.class);
            assertTrue(ClusterTool.invalidateLatestSnapshot(
                mockOut, clusteredMediaDriver.consensusModule().context().clusterDir()));

            verify(mockOut).println(" invalidate latest snapshot: true");

            serviceMsgCounter.set(0);
            launchClusteredMediaDriver(false);
            launchService(serviceMsgCounter);

            TestCluster.awaitCount(serviceMsgCounter, 4);

            assertEquals("4", serviceState.get());

            connectClient();
            sendCountedMessageIntoCluster(4);
            TestCluster.awaitCount(serviceMsgCounter, 5);

            forceCloseForRestart();

            serviceMsgCounter.set(0);
            launchClusteredMediaDriver(false);
            launchService(serviceMsgCounter);

            connectClient();
            assertEquals("5", serviceState.get());

            ClusterTests.failOnClusterError();
        });
    }

    private void sendCountedMessageIntoCluster(final int value)
    {
        msgBuffer.putInt(MESSAGE_VALUE_OFFSET, value);

        sendMessageIntoCluster(aeronCluster, msgBuffer, MESSAGE_LENGTH);
    }

    private void sendTimerMessageIntoCluster(final int value, final long timerCorrelationId, final long delayMs)
    {
        msgBuffer.putInt(MESSAGE_VALUE_OFFSET, value);
        msgBuffer.putLong(TIMER_MESSAGE_ID_OFFSET, timerCorrelationId);
        msgBuffer.putLong(TIMER_MESSAGE_DELAY_OFFSET, delayMs);

        sendMessageIntoCluster(aeronCluster, msgBuffer, TIMER_MESSAGE_LENGTH);
    }

    private static void sendMessageIntoCluster(final AeronCluster cluster, final DirectBuffer buffer, final int length)
    {
        while (true)
        {
            final long result = cluster.offer(buffer, 0, length);
            if (result > 0)
            {
                break;
            }

            checkResult(result);
            Thread.yield();
            Tests.checkInterruptStatus();
        }
    }

    private void launchService(final AtomicLong msgCounter)
    {
        final ClusteredService service = new StubClusteredService()
        {
            private int nextCorrelationId = 0;
            private int counterValue = 0;

            public void onStart(final Cluster cluster, final Image snapshotImage)
            {
                super.onStart(cluster, snapshotImage);

                if (null != snapshotImage)
                {
                    final FragmentHandler fragmentHandler = (buffer, offset, length, header) ->
                    {
                        nextCorrelationId = buffer.getInt(offset);
                        offset += SIZE_OF_INT;

                        counterValue = buffer.getInt(offset);
                        offset += SIZE_OF_INT;

                        serviceState.set(buffer.getStringAscii(offset));
                    };

                    while (true)
                    {
                        final int fragments = snapshotImage.poll(fragmentHandler, 1);
                        if (fragments == 1 || snapshotImage.isEndOfStream())
                        {
                            break;
                        }

                        idleStrategy.idle();
                    }
                }
            }

            public void onSessionMessage(
                final ClientSession session,
                final long timestamp,
                final DirectBuffer buffer,
                final int offset,
                final int length,
                final Header header)
            {
                final int sentValue = buffer.getInt(offset + MESSAGE_VALUE_OFFSET);
                assertEquals(counterValue, sentValue);

                counterValue++;
                serviceState.set(Integer.toString(counterValue));
                msgCounter.getAndIncrement();

                if (TIMER_MESSAGE_LENGTH == length)
                {
                    final long correlationId = serviceCorrelationId(nextCorrelationId++);
                    final long deadlineMs = timestamp + buffer.getLong(offset + TIMER_MESSAGE_DELAY_OFFSET);

                    while (!cluster.scheduleTimer(correlationId, deadlineMs))
                    {
                        idleStrategy.idle();
                    }
                }
            }

            public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
            {
                final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();

                int length = 0;
                buffer.putInt(length, nextCorrelationId);
                length += SIZE_OF_INT;

                buffer.putInt(length, counterValue);
                length += SIZE_OF_INT;

                length += buffer.putStringAscii(length, Integer.toString(counterValue));

                snapshotPublication.offer(buffer, 0, length);
            }
        };

        container = null;

        container = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .clusteredService(service)
                .terminationHook(ClusterTests.TERMINATION_HOOK)
                .errorHandler(ClusterTests.errorHandler(0)));
    }

    private void launchReschedulingService(final AtomicInteger triggeredTimersCounter)
    {
        final ClusteredService service = new StubClusteredService()
        {
            public void onSessionMessage(
                final ClientSession session,
                final long timestamp,
                final DirectBuffer buffer,
                final int offset,
                final int length,
                final Header header)
            {
                scheduleNext(serviceCorrelationId(7), timestamp + 100);
            }

            public void onTimerEvent(final long correlationId, final long timestamp)
            {
                triggeredTimersCounter.getAndIncrement();
                scheduleNext(correlationId, timestamp + 100);
            }

            public void onStart(final Cluster cluster, final Image snapshotImage)
            {
                super.onStart(cluster, snapshotImage);

                if (null != snapshotImage)
                {
                    final FragmentHandler fragmentHandler =
                        (buffer, offset, length, header) -> triggeredTimersCounter.set(buffer.getInt(offset));

                    while (true)
                    {
                        final int fragments = snapshotImage.poll(fragmentHandler, 1);
                        if (fragments == 1 || snapshotImage.isEndOfStream())
                        {
                            break;
                        }

                        idleStrategy.idle();
                    }
                }
            }

            public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
            {
                final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();

                buffer.putInt(0, triggeredTimersCounter.get());

                while (snapshotPublication.offer(buffer, 0, SIZE_OF_INT) < 0)
                {
                    idleStrategy.idle();
                }
            }

            private void scheduleNext(final long correlationId, final long deadlineMs)
            {
                idleStrategy.reset();
                while (!cluster.scheduleTimer(correlationId, deadlineMs))
                {
                    idleStrategy.idle();
                }
            }
        };

        container = null;

        container = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .clusteredService(service)
                .terminationHook(ClusterTests.TERMINATION_HOOK)
                .errorHandler(ClusterTests.errorHandler(0)));
    }

    private AeronCluster connectToCluster()
    {
        return AeronCluster.connect();
    }

    private void forceCloseForRestart()
    {
        CloseHelper.closeAll(aeronCluster, container, clusteredMediaDriver);
    }

    private void connectClient()
    {
        CloseHelper.close(aeronCluster);
        aeronCluster = null;
        aeronCluster = connectToCluster();
    }

    private void launchClusteredMediaDriver(final boolean initialLaunch)
    {
        clusteredMediaDriver = null;

        clusteredMediaDriver = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .warnIfDirectoryExists(initialLaunch)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .errorHandler(ClusterTests.errorHandler(0))
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .shouldFreeBuffersOnClose(true)
                .deleteArchiveOnStart(initialLaunch),
            new ConsensusModule.Context()
                .errorHandler(ClusterTests.errorHandler(0))
                .snapshotCounter(mockSnapshotCounter)
                .terminationHook(ClusterTests.TERMINATION_HOOK)
                .deleteDirOnStart(initialLaunch));
    }

    private static void checkResult(final long result)
    {
        if (result == Publication.NOT_CONNECTED ||
            result == Publication.CLOSED ||
            result == Publication.MAX_POSITION_EXCEEDED)
        {
            throw new IllegalStateException("unexpected publication state: " + result);
        }
    }
}
