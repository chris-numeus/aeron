/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.aeron.mediadriver;

import uk.co.real_logic.aeron.mediadriver.buffer.BufferManagement;
import uk.co.real_logic.aeron.util.*;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.concurrent.OneToOneConcurrentArrayQueue;
import uk.co.real_logic.aeron.util.concurrent.broadcast.BroadcastBufferDescriptor;
import uk.co.real_logic.aeron.util.concurrent.broadcast.BroadcastTransmitter;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBuffer;
import uk.co.real_logic.aeron.util.concurrent.ringbuffer.RingBufferDescriptor;
import uk.co.real_logic.aeron.util.event.EventLogger;
import uk.co.real_logic.aeron.util.status.StatusBufferManager;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static java.lang.Integer.getInteger;
import static uk.co.real_logic.aeron.mediadriver.buffer.BufferManagement.newMappedBufferManager;
import static uk.co.real_logic.aeron.util.IoUtil.mapNewFile;

/**
 * Main class for JVM-based media driver
 * <p>
 * <p>
 * Usage:
 * <code>
 * $ java -jar aeron-mediadriver.jar
 * $ java -Doption=value -jar aeron-mediadriver.jar
 * </code>
 * Properties
 * <ul>
 * <li><code>aeron.rcv.buffer.size</code>: Use int value as size of buffer for receiving from network.</li>
 * <li><code>aeron.command.buffer.size</code>: Use int value as size of the command buffers between threads.</li>
 * <li><code>aeron.conductor.buffer.size</code>: Use int value as size of the conductor buffers between the media
 * driver and the client.</li>
 * </ul>
 */
public class MediaDriver implements AutoCloseable
{
    private static final EventLogger LOGGER = new EventLogger(MediaDriver.class);

    /**
     * Byte buffer size (in bytes) for reads
     */
    public static final String READ_BUFFER_SZ_PROP_NAME = "aeron.rcv.buffer.size";

    /**
     * Size (in bytes) of the command buffers between threads
     */
    public static final String COMMAND_BUFFER_SZ_PROP_NAME = "aeron.command.buffer.size";

    /**
     * Size (in bytes) of the conductor buffers between the media driver and the client
     */
    public static final String CONDUCTOR_BUFFER_SZ_PROP_NAME = "aeron.conductor.buffer.size";

    /**
     * Size (in bytes) of the broadcast buffers from the media driver to the clients
     */
    public static final String TO_CLIENTS_BUFFER_SZ_PROP_NAME = "aeron.clients.buffer.size";

    /**
     * Name of the default multicast interface
     */
    public static final String MULTICAST_DEFAULT_INTERFACE_PROP_NAME = "aeron.multicast.default.interface";

    /**
     * Property name for size of the memory mapped buffers for the counters file
     */
    public static final String COUNTER_BUFFERS_SZ_PROP_NAME = "aeron.dir.counters.size";

    /**
     * Default byte buffer size for reads
     */
    public static final int READ_BYTE_BUFFER_SZ_DEFAULT = 4096;

    /**
     * Default buffer size for command buffers between threads
     */
    public static final int COMMAND_BUFFER_SZ_DEFAULT = 65536;

    /**
     * Default buffer size for conductor buffers between the media driver and the client
     */
    public static final int CONDUCTOR_BUFFER_SZ_DEFAULT = 65536 + RingBufferDescriptor.TRAILER_LENGTH;

    /**
     * Default buffer size for broadcast buffers from the media driver to the clients
     */
    public static final int TO_CLIENTS_BUFFER_SZ_DEFAULT = 65536 + BroadcastBufferDescriptor.TRAILER_LENGTH;

    /**
     * Size of the memory mapped buffers for the counters file
     */
    public static final int COUNTERS_BUFFER_SZ_DEFAULT = 65536;

    /**
     * Default group size estimate for NAK delay randomization
     */
    public static final int NAK_GROUPSIZE_DEFAULT = 10;
    /**
     * Default group RTT estimate for NAK delay randomization in msec
     */
    public static final int NAK_GRTT_DEFAULT = 10;
    /**
     * Default max backoff for NAK delay randomization in msec
     */
    public static final int NAK_MAX_BACKOFF_DEFAULT = 60;

    /**
     * Default Unicast NAK delay in nanoseconds
     */
    public static final long NAK_UNICAST_DELAY_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(60);

    /**
     * Default group size estimate for retransmit delay randomization
     */
    public static final int RETRANS_GROUPSIZE_DEFAULT = 10;
    /**
     * Default group RTT estimate for retransmit delay randomization in msec
     */
    public static final int RETRANS_GRTT_DEFAULT = 10;
    /**
     * Default max backoff for retransmit delay randomization in msec
     */
    public static final int RETRANS_MAX_BACKOFF_DEFAULT = 60;
    /**
     * Default delay for retransmission of data for unicast
     */
    public static final long RETRANS_UNICAST_DELAY_DEFAULT_NS = TimeUnit.NANOSECONDS.toNanos(0);
    /**
     * Default delay for linger for unicast
     */
    public static final long RETRANS_UNICAST_LINGER_DEFAULT_NS = TimeUnit.MILLISECONDS.toNanos(60);

    /**
     * Default max number of active retransmissions per Term
     */
    public static final int MAX_RETRANSMITS_DEFAULT = 16;

    /**
     * Estimated RTT in nanoseconds.
     */
    public static final long ESTIMATED_RTT_NS = TimeUnit.MICROSECONDS.toNanos(100);

    /**
     * Estimated max throughput in bytes.
     */
    public static final long ESTIMATED_MAX_THROUGHPUT_IN_BYTES = 10 * 1000 * 1000 * 1000; // 10 Gbps

    public static final int READ_BYTE_BUFFER_SZ = getInteger(READ_BUFFER_SZ_PROP_NAME, READ_BYTE_BUFFER_SZ_DEFAULT);
    public static final int COMMAND_BUFFER_SZ = getInteger(COMMAND_BUFFER_SZ_PROP_NAME, COMMAND_BUFFER_SZ_DEFAULT);
    public static final int CONDUCTOR_BUFFER_SZ = getInteger(CONDUCTOR_BUFFER_SZ_PROP_NAME, CONDUCTOR_BUFFER_SZ_DEFAULT);
    public static final int TO_CLIENTS_BUFFER_SZ = getInteger(TO_CLIENTS_BUFFER_SZ_PROP_NAME, TO_CLIENTS_BUFFER_SZ_DEFAULT);
    public static final int COUNTER_BUFFERS_SZ = getInteger(COUNTER_BUFFERS_SZ_PROP_NAME, COUNTERS_BUFFER_SZ_DEFAULT);

    /**
     * ticksPerWheel for TimerWheel in conductor thread
     */
    public static final int MEDIA_CONDUCTOR_TICKS_PER_WHEEL = 1024;

    /**
     * tickDuration (in MICROSECONDS) for TimerWheel in conductor thread
     */
    public static final int MEDIA_CONDUCTOR_TICK_DURATION_US = 10 * 1000;

    public static final long AGENT_IDLE_MAX_SPINS = 100;
    public static final long AGENT_IDLE_MAX_YIELDS = 100;
    public static final long AGENT_IDLE_MIN_PARK_NS = TimeUnit.NANOSECONDS.toNanos(10);
    public static final long AGENT_IDLE_MAX_PARK_NS = TimeUnit.MICROSECONDS.toNanos(100);

    private final File adminDirFile;
    private final File dataDirFile;

    private final BufferManagement bufferManagement;

    private final Receiver receiver;
    private final Sender sender;
    private final MediaConductor conductor;
    private final MediaDriverContext ctx;

    private ExecutorService executor;

    private Thread conductorThread;
    private Thread senderThread;
    private Thread receiverThread;

    private Future conductorFuture;
    private Future senderFuture;
    private Future receiverFuture;

    /**
     * Start Media Driver as a stand-alone process.
     *
     * @param args command line arguments
     * @throws Exception
     */
    public static void main(final String[] args) throws Exception
    {
        try (final MediaDriver mediaDriver = new MediaDriver())
        {
            mediaDriver.invokeDaemonized();

            while (true)
            {
                Thread.sleep(1000);
            }
        }
    }

    /**
     * Initiatlize a media driver with default parameters.
     *
     * @throws Exception
     */
    public MediaDriver() throws Exception
    {
        this(new MediaDriverContext());
    }

    /**
     * Initialize a media driver with the given paramters.
     *
     * @param context for the media driver parameters
     * @throws Exception
     */
    public MediaDriver(final MediaDriverContext context) throws Exception
    {
        ctx = context
            .driverCommandBuffer(COMMAND_BUFFER_SZ)
            .receiverCommandBuffer(COMMAND_BUFFER_SZ)
            .receiverNioSelector(new NioSelector())
            .conductorNioSelector(new NioSelector())
            .unicastSenderFlowControl(UnicastSenderControlStrategy::new)
            .multicastSenderFlowControl(UnicastSenderControlStrategy::new)
            .connectedSubscriptions(new AtomicArray<>())
            .publications(new AtomicArray<>())
            .conductorTimerWheel(new TimerWheel(MEDIA_CONDUCTOR_TICK_DURATION_US,
                    TimeUnit.MICROSECONDS,
                    MEDIA_CONDUCTOR_TICKS_PER_WHEEL))
            .newConnectedSubscriptionEventQueue(new OneToOneConcurrentArrayQueue<>(1024))
            .conductorIdleStrategy(new AgentIdleStrategy(AGENT_IDLE_MAX_SPINS, AGENT_IDLE_MAX_YIELDS,
                    AGENT_IDLE_MIN_PARK_NS, AGENT_IDLE_MAX_PARK_NS))
            .senderIdleStrategy(new AgentIdleStrategy(AGENT_IDLE_MAX_SPINS, AGENT_IDLE_MAX_YIELDS,
                    AGENT_IDLE_MIN_PARK_NS, AGENT_IDLE_MAX_PARK_NS))
            .receiverIdleStrategy(new AgentIdleStrategy(AGENT_IDLE_MAX_SPINS, AGENT_IDLE_MAX_YIELDS,
                    AGENT_IDLE_MIN_PARK_NS, AGENT_IDLE_MAX_PARK_NS))
            .conclude();

        this.adminDirFile = new File(ctx.adminDirName());
        this.dataDirFile = new File(ctx.dataDirName());

        ensureDirectoriesExist();

        this.bufferManagement = ctx.bufferManagement;
        this.receiver = new Receiver(ctx);
        this.sender = new Sender(ctx);
        this.conductor = new MediaConductor(ctx);
    }

    /**
     * Spin up all {@link Agent}s as Daemon threads.
     */
    public void invokeDaemonized()
    {
        conductorThread = new Thread(conductor);
        invokeDaemonized(conductorThread, "media-driver-conductor");

        senderThread = new Thread(sender);
        invokeDaemonized(senderThread, "media-driver-sender");

        receiverThread = new Thread(receiver);
        invokeDaemonized(receiverThread, "media-driver-receiver");
    }

    /**
     * Spin up specific thread as a Daemon thread.
     *
     * @param agentThread thread to Daemonize
     * @param name        to associate with thread
     */
    public void invokeDaemonized(final Thread agentThread, final String name)
    {
        agentThread.setName(name);
        agentThread.setDaemon(true);
        agentThread.start();
    }

    /**
     * Invoke and start all {@link uk.co.real_logic.aeron.util.Agent}s internal to the media driver using
     * a fixed size thread pool internal to the media driver.
     */
    public void invokeEmbedded()
    {
        executor = Executors.newFixedThreadPool(3);

        conductorFuture = executor.submit(conductor);
        senderFuture = executor.submit(sender);
        receiverFuture = executor.submit(receiver);
    }

    /**
     * Stop running {@link uk.co.real_logic.aeron.util.Agent}s. Waiting for each to finish.
     *
     * @throws Exception
     */
    public void shutdown() throws Exception
    {
        if (null != senderThread)
        {
            shutdown(senderThread, sender);
        }

        if (null != receiverThread)
        {
            shutdown(receiverThread, receiver);
        }

        if (null != conductorThread)
        {
            shutdown(conductorThread, conductor);
        }

        if (null != executor)
        {
            shutdownExecutorThread(senderFuture, sender);
            shutdownExecutorThread(receiverFuture, receiver);
            shutdownExecutorThread(conductorFuture, conductor);

            executor.shutdown();
        }
    }

    /**
     * Close and cleanup all resources for media driver
     *
     * @throws Exception
     */
    public void close() throws Exception
    {
        receiver.close();
        receiver.nioSelector().selectNowWithoutProcessing();
        sender.close();
        conductor.close();
        conductor.nioSelector().selectNowWithoutProcessing();
        bufferManagement.close();
        ctx.close();
        deleteDirectories();
    }

    private void ensureDirectoriesExist() throws Exception
    {
        final BiConsumer<String, String> callback = (path, name) ->
        {
            // TODO: replace with logging?
            System.err.println("WARNING: " + name + " directory already exists: " + path);
        };

        IoUtil.ensureDirectoryExists(adminDirFile, "conductor", callback);
        IoUtil.ensureDirectoryExists(dataDirFile, "data", callback);
    }

    private void deleteDirectories() throws Exception
    {
        if (ctx.dirsDeleteOnExit())
        {
            IoUtil.delete(adminDirFile, false);
            IoUtil.delete(dataDirFile, false);
        }
    }

    private void shutdown(final Thread thread, final Agent agent)
    {
        agent.stop();
        thread.interrupt();

        do
        {
            try
            {
                thread.join(100);

                if (!thread.isAlive())
                {
                    break;
                }
            }
            catch (final InterruptedException ex)
            {
                System.err.println("Daemon Thread <" + thread.getName() + "> interrupted stop. Retrying...");
                thread.interrupt();
            }
        }
        while (true);
    }

    private void shutdownExecutorThread(final Future future, final Agent agent)
    {
        agent.stop();

        try
        {
            future.get(100, TimeUnit.MILLISECONDS);
        }
        catch (final TimeoutException ex)
        {
            future.cancel(true);
        }
        catch (final Exception ex)
        {
            LOGGER.logException(ex);
        }
    }

    public static class MediaDriverContext extends CommonContext
    {
        private RingBuffer driverCommandBuffer;
        private RingBuffer receiverCommandBuffer;
        private BufferManagement bufferManagement;
        private NioSelector receiverNioSelector;
        private NioSelector conductorNioSelector;
        private Supplier<SenderControlStrategy> unicastSenderFlowControl;
        private Supplier<SenderControlStrategy> multicastSenderFlowControl;
        private TimerWheel conductorTimerWheel;
        private Queue<NewConnectedSubscriptionEvent> newConnectedSubscriptionEventQueue;
        private ReceiverProxy receiverProxy;
        private MediaConductorProxy mediaConductorProxy;
        private AgentIdleStrategy conductorIdleStrategy;
        private AgentIdleStrategy senderIdleStrategy;
        private AgentIdleStrategy receiverIdleStrategy;
        private AtomicArray<DriverConnectedSubscription> connectedSubscriptions;
        private AtomicArray<DriverPublication> publications;
        private ClientProxy clientProxy;
        private RingBuffer fromClientCommands;

        private MappedByteBuffer toClientsBuffer;
        private MappedByteBuffer toDriverBuffer;
        private StatusBufferManager statusBufferManager;

        public MediaDriverContext()
        {
        }

        public MediaDriverContext conclude() throws IOException
        {
            super.conclude();

            toClientsBuffer = mapNewFile(toClientsFile(), TO_CLIENTS_BUFFER_SZ);

            clientProxy(new ClientProxy(new BroadcastTransmitter(new AtomicBuffer(toClientsBuffer))));

            toDriverBuffer = mapNewFile(toDriverFile(), CONDUCTOR_BUFFER_SZ);

            fromClientCommands(new ManyToOneRingBuffer(new AtomicBuffer(toDriverBuffer)));

            receiverProxy(new ReceiverProxy(receiverCommandBuffer(), newConnectedSubscriptionEventQueue()));
            mediaConductorProxy(new MediaConductorProxy(driverCommandBuffer()));

            bufferManagement(newMappedBufferManager(dataDirName()));

            if (statusBufferManager() == null)
            {
                if (counterLabelsBuffer() == null)
                {
                    final MappedByteBuffer buffer = mapNewFile(new File(countersDirName(), LABELS_FILE), COUNTER_BUFFERS_SZ);
                    counterLabelsBuffer(new AtomicBuffer(buffer));
                }

                if (counterValuesBuffer() == null)
                {
                    final MappedByteBuffer buffer = mapNewFile(new File(countersDirName(), VALUES_FILE), COUNTER_BUFFERS_SZ);
                    counterValuesBuffer(new AtomicBuffer(buffer));
                }

                statusBufferManager(new StatusBufferManager(counterLabelsBuffer(), counterValuesBuffer()));
            }

            return this;
        }

        private RingBuffer createNewCommandBuffer(final int size)
        {
            final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(size + RingBufferDescriptor.TRAILER_LENGTH);
            final AtomicBuffer atomicBuffer = new AtomicBuffer(byteBuffer);

            return new ManyToOneRingBuffer(atomicBuffer);
        }

        public MediaDriverContext driverCommandBuffer(final int size)
        {
            this.driverCommandBuffer = createNewCommandBuffer(size);
            return this;
        }

        public MediaDriverContext receiverCommandBuffer(final int size)
        {
            this.receiverCommandBuffer = createNewCommandBuffer(size);
            return this;
        }

        public MediaDriverContext bufferManagement(final BufferManagement bufferManagement)
        {
            this.bufferManagement = bufferManagement;
            return this;
        }

        public MediaDriverContext receiverNioSelector(final NioSelector nioSelector)
        {
            this.receiverNioSelector = nioSelector;
            return this;
        }

        public MediaDriverContext conductorNioSelector(final NioSelector nioSelector)
        {
            this.conductorNioSelector = nioSelector;
            return this;
        }

        public MediaDriverContext unicastSenderFlowControl(final Supplier<SenderControlStrategy> senderFlowControl)
        {
            this.unicastSenderFlowControl = senderFlowControl;
            return this;
        }

        public MediaDriverContext multicastSenderFlowControl(final Supplier<SenderControlStrategy> senderFlowControl)
        {
            this.multicastSenderFlowControl = senderFlowControl;
            return this;
        }

        public MediaDriverContext conductorTimerWheel(final TimerWheel wheel)
        {
            this.conductorTimerWheel = wheel;
            return this;
        }

        public MediaDriverContext newConnectedSubscriptionEventQueue(
            final Queue<NewConnectedSubscriptionEvent> newConnectedSubscriptionEventQueue)
        {
            this.newConnectedSubscriptionEventQueue = newConnectedSubscriptionEventQueue;
            return this;
        }

        public MediaDriverContext receiverProxy(final ReceiverProxy receiverProxy)
        {
            this.receiverProxy = receiverProxy;
            return this;
        }

        public MediaDriverContext mediaConductorProxy(final MediaConductorProxy mediaConductorProxy)
        {
            this.mediaConductorProxy = mediaConductorProxy;
            return this;
        }

        public MediaDriverContext conductorIdleStrategy(final AgentIdleStrategy strategy)
        {
            this.conductorIdleStrategy = strategy;
            return this;
        }

        public MediaDriverContext senderIdleStrategy(final AgentIdleStrategy strategy)
        {
            this.senderIdleStrategy = strategy;
            return this;
        }

        public MediaDriverContext receiverIdleStrategy(final AgentIdleStrategy strategy)
        {
            this.receiverIdleStrategy = strategy;
            return this;
        }

        public MediaDriverContext connectedSubscriptions(
            final AtomicArray<DriverConnectedSubscription> connectedSubscriptions)
        {
            this.connectedSubscriptions = connectedSubscriptions;
            return this;
        }

        public MediaDriverContext publications(final AtomicArray<DriverPublication> publications)
        {
            this.publications = publications;
            return this;
        }

        public MediaDriverContext clientProxy(final ClientProxy clientProxy)
        {
            this.clientProxy = clientProxy;
            return this;
        }

        public MediaDriverContext fromClientCommands(final RingBuffer fromClientCommands)
        {
            this.fromClientCommands = fromClientCommands;
            return this;
        }

        public MediaDriverContext statusBufferManager(final StatusBufferManager statusBufferManager)
        {
            this.statusBufferManager = statusBufferManager;
            return this;
        }

        public RingBuffer driverCommandBuffer()
        {
            return driverCommandBuffer;
        }

        public RingBuffer receiverCommandBuffer()
        {
            return receiverCommandBuffer;
        }

        public BufferManagement bufferManagement()
        {
            return bufferManagement;
        }

        public NioSelector receiverNioSelector()
        {
            return receiverNioSelector;
        }

        public NioSelector conductorNioSelector()
        {
            return conductorNioSelector;
        }

        public Supplier<SenderControlStrategy> unicastSenderFlowControl()
        {
            return unicastSenderFlowControl;
        }

        public Supplier<SenderControlStrategy> multicastSenderFlowControl()
        {
            return multicastSenderFlowControl;
        }

        public TimerWheel conductorTimerWheel()
        {
            return conductorTimerWheel;
        }

        public Queue<NewConnectedSubscriptionEvent> newConnectedSubscriptionEventQueue()
        {
            return newConnectedSubscriptionEventQueue;
        }

        public ReceiverProxy receiverProxy()
        {
            return receiverProxy;
        }

        public MediaConductorProxy mediaConductorProxy()
        {
            return mediaConductorProxy;
        }

        public AgentIdleStrategy conductorIdleStrategy()
        {
            return conductorIdleStrategy;
        }

        public AgentIdleStrategy senderIdleStrategy()
        {
            return senderIdleStrategy;
        }

        public AgentIdleStrategy receiverIdleStrategy()
        {
            return receiverIdleStrategy;
        }

        public AtomicArray<DriverConnectedSubscription> connectedSubscriptions()
        {
            return connectedSubscriptions;
        }

        public AtomicArray<DriverPublication> publications()
        {
            return publications;
        }

        public ClientProxy clientProxy()
        {
            return clientProxy;
        }

        public RingBuffer fromClientCommands()
        {
            return fromClientCommands;
        }

        public StatusBufferManager statusBufferManager()
        {
            return statusBufferManager;
        }

        public void close() throws Exception
        {
            if (null != toClientsBuffer)
            {
                IoUtil.unmap(toClientsBuffer);
            }

            if (null != toDriverBuffer)
            {
                IoUtil.unmap(toDriverBuffer);
            }

            try
            {
                super.close();
            }
            catch (final Exception ex)
            {
                throw new RuntimeException(ex);
            }
        }
    }
}
