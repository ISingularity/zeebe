package org.camunda.tngp.transport.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.camunda.tngp.transport.Channel;
import org.camunda.tngp.transport.ChannelManager;
import org.camunda.tngp.transport.SocketAddress;
import org.camunda.tngp.transport.impl.agent.Conductor;
import org.camunda.tngp.transport.spi.TransportChannelHandler;
import org.camunda.tngp.transport.util.GrowablePool;
import org.camunda.tngp.transport.util.GrowablePool.PoolIterator;
import org.camunda.tngp.util.DeferredCommandContext;
import org.camunda.tngp.util.LangUtil;
import org.camunda.tngp.util.PooledFuture;
import org.camunda.tngp.util.collection.ObjectPool;
import org.camunda.tngp.util.state.concurrent.SharedStateMachineBlueprint;
import org.camunda.tngp.util.time.ClockUtil;

public class ChannelManagerImpl implements ChannelManager
{
    protected static final int CHANNEL_RECONNECT_ATTEMPTS = 3;

    protected final DeferredCommandContext commandContext = new DeferredCommandContext();

    protected final ObjectPool<ChannelRequest> channelRequestPool;
    protected final ManyToOneConcurrentArrayQueue<ChannelRequest> pendingChannelRequests;
    protected final Conductor conductor;
    protected final GrowablePool<ChannelImpl> managedChannels;
    protected final TransportChannelHandler channelHandler;

    protected final long keepAlivePeriod;

    protected final SharedStateMachineBlueprint<ChannelImpl> channelLifecycle;

    public ChannelManagerImpl(
            Conductor conductor,
            TransportChannelHandler channelHandler,
            int initialCapacity,
            long keepAlivePeriod,
            boolean reopenChannelsOnException,
            SharedStateMachineBlueprint<ChannelImpl> defaultLifecycle)
    {
        this.keepAlivePeriod = keepAlivePeriod;
        this.channelRequestPool = new ObjectPool<>(64, ChannelRequest::new);
        this.pendingChannelRequests = new ManyToOneConcurrentArrayQueue<>(64);
        this.conductor = conductor;
        this.channelLifecycle = defaultLifecycle.copy()
                .onState(ChannelImpl.STATE_CLOSED | ChannelImpl.STATE_CLOSED_UNEXPECTEDLY, this::removeChannel);
        if (reopenChannelsOnException)
        {
            this.channelLifecycle.onState(ChannelImpl.STATE_INTERRUPTED, this::tryReopenChannel);
        }
        else
        {
            this.channelLifecycle.onState(ChannelImpl.STATE_INTERRUPTED, c -> c.setClosedUnexpectedly());
        }

        final ToIntFunction<PoolIterator<ChannelImpl>> evictionDecider = it ->
        {
            long minimalLastUsedTime = Long.MAX_VALUE;
            int channelToEvict = -1;

            while (it.hasNext())
            {
                final ChannelImpl channel = it.next();
                final long currentLastUsedTime = channel.getLastUsed();

                if (!channel.isInUse() && currentLastUsedTime < minimalLastUsedTime)
                {
                    channelToEvict = it.getCurrentElementIndex();
                    minimalLastUsedTime = currentLastUsedTime;
                }
            }

            return channelToEvict;
        };
        final Consumer<ChannelImpl> evictionHandler = (c) ->
        {
            conductor.closeChannel(c);
        };

        this.managedChannels = new GrowablePool<>(
                ChannelImpl.class,
                initialCapacity,
                evictionDecider,
                evictionHandler);

        this.channelHandler = channelHandler;
    }

    protected void removeChannel(ChannelImpl channel)
    {
        this.managedChannels.remove(channel);
    }

    protected void tryReopenChannel(ChannelImpl channel)
    {
        if (channel.isInUse() && channel.hasReconnectAttemptsLeft())
        {
            channel.consumeReconnectAttempt();
            channel.setLastKeepAlive(ClockUtil.getCurrentTimeInMillis());

            final boolean success = conductor.connectChannel(channel);

            if (success)
            {
                channel.setLastKeepAlive(ClockUtil.getCurrentTimeInMillis());
            }
            else
            {
                channel.interrupt();
            }
        }
        else
        {
            channel.setClosedUnexpectedly();
        }
    }

    public PooledFuture<Channel> requestChannelAsync(SocketAddress remoteAddress)
    {
        return scheduleChannelRequest(remoteAddress, null);
    }

    public Channel requestChannel(SocketAddress remoteAddress)
    {
        final CompletableFuture<Channel> completionFuture = new CompletableFuture<>();
        final ChannelRequest channelRequest = scheduleChannelRequest(remoteAddress, completionFuture);

        if (channelRequest == null)
        {
            throw new RuntimeException("Could not schedule opening a channel; capacities are exhausted");
        }
        else
        {
            return completionFuture
                .whenComplete((c, t) ->
                {
                    channelRequest.release();
                })
                .join();
        }
    }

    public void returnChannel(Channel channel)
    {
        if (channel != null)
        {
            ((ChannelImpl) channel).countUsageEnd();
        }
    }


    protected ChannelRequest scheduleChannelRequest(SocketAddress remoteAddress, CompletableFuture<Channel> completionFuture)
    {
        final ChannelRequest channelRequest = channelRequestPool.request();

        if (channelRequest == null)
        {
            return null; // for backpressure
        }

        channelRequest.init(remoteAddress, completionFuture);
        pendingChannelRequests.add(channelRequest);

        return channelRequest;
    }

    public int maintainState()
    {
        int workCount = 0;
        workCount += commandContext.doWork();
        workCount += workOnChannelRequests();
        workCount += sendKeepAliveMessages();

        return workCount;
    }

    protected int workOnChannelRequests()
    {
        return pendingChannelRequests.drain(this::handleChannelRequest);
    }

    public CompletableFuture<Void> closeAllChannelsAsync()
    {
        return commandContext.runAsync((future) ->
        {
            final PoolIterator<ChannelImpl> iterator = managedChannels.iterator();
            final List<CompletableFuture<ChannelImpl>> closeFutures = new ArrayList<>();
            while (iterator.hasNext())
            {
                final ChannelImpl channel = iterator.next();
                final boolean closeInitiated = conductor.closeChannel(channel);

                if (closeInitiated)
                {
                    final CompletableFuture<ChannelImpl> closeFuture = new CompletableFuture<>();
                    channel.listenForClose(closeFuture);
                    closeFutures.add(closeFuture);
                }
                // else we assume everything is fine and don't wait
            }

            LangUtil.allOf(closeFutures)
                .whenComplete((v, t) ->
                {
                    if (t != null)
                    {
                        future.cancel(false);
                    }
                    else
                    {
                        future.complete(null);
                    }
                });
        });
    }

    protected void handleChannelRequest(ChannelRequest request)
    {
        final SocketAddress remoteAddress = request.getRemoteAddress();
        ChannelImpl channel = findExistingStream(remoteAddress);

        if (channel == null)
        {
            channel = conductor.newChannel(remoteAddress, channelHandler, CHANNEL_RECONNECT_ATTEMPTS, channelLifecycle);
            channel.setLastKeepAlive(ClockUtil.getCurrentTimeInMillis());
            managedChannels.add(channel);
        }

        channel.listenForReady(request);
    }

    protected ChannelImpl findExistingStream(SocketAddress remoteAddress)
    {
        final Iterator<ChannelImpl> it = managedChannels.iterator();

        while (it.hasNext())
        {
            final ChannelImpl channel = it.next();

            if (channel.getRemoteAddress().equals(remoteAddress))
            {
                return channel;
            }
        }

        return null;
    }

    protected int sendKeepAliveMessages()
    {
        final Iterator<ChannelImpl> it = managedChannels.iterator();
        final long now = ClockUtil.getCurrentTimeInMillis();
        int workCount = 0;

        while (it.hasNext())
        {
            final ChannelImpl channel = it.next();

            if (channel.isReady() && now > channel.getLastKeepAlive() + keepAlivePeriod)
            {
                final boolean keepAliveScheduled = channel.scheduleControlFrame(StaticControlFrames.KEEP_ALIVE_FRAME);

                if (keepAliveScheduled)
                {
                    channel.setLastKeepAlive(now);
                }

                workCount++;
            }
        }

        return workCount;
    }
}
