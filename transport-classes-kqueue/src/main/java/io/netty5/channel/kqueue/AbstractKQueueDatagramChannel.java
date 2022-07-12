/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.channel.kqueue;

import io.netty5.channel.ChannelMetadata;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.EventLoop;
import io.netty5.channel.FixedRecvBufferAllocator;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.unix.UnixChannel;
import io.netty5.util.concurrent.Future;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;

import static java.util.Objects.requireNonNull;

abstract class AbstractKQueueDatagramChannel<P extends UnixChannel, L extends SocketAddress, R extends SocketAddress>
        extends AbstractKQueueChannel<P, L, R> implements DatagramChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(true);
    private volatile boolean inputShutdown;
    private volatile boolean outputShutdown;

    AbstractKQueueDatagramChannel(P parent, EventLoop eventLoop, BsdSocket fd, boolean active) {
        super(parent, eventLoop, METADATA, new FixedRecvBufferAllocator(2048), fd, active);
    }

    protected abstract boolean doWriteMessage(Object msg) throws Exception;

    private <V> Future<V> newMulticastNotSupportedFuture() {
        return newFailedFuture(new UnsupportedOperationException("Multicast not supported"));
    }

    @Override
    public final Future<Void> joinGroup(InetAddress multicastAddress) {
        requireNonNull(multicastAddress, "multicast");
        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> joinGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(networkInterface, "networkInterface");

        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> leaveGroup(InetAddress multicastAddress) {
        requireNonNull(multicastAddress, "multicast");
        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> leaveGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(networkInterface, "networkInterface");

        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> block(
            InetAddress multicastAddress, NetworkInterface networkInterface,
            InetAddress sourceToBlock) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(sourceToBlock, "sourceToBlock");
        requireNonNull(networkInterface, "networkInterface");

        return newMulticastNotSupportedFuture();
    }

    @Override
    public final Future<Void> block(InetAddress multicastAddress, InetAddress sourceToBlock) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(sourceToBlock, "sourceToBlock");

        return newMulticastNotSupportedFuture();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int maxMessagesPerWrite = getMaxMessagesPerWrite();
        while (maxMessagesPerWrite > 0) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }

            try {
                boolean done = false;
                for (int i = getWriteSpinCount(); i > 0; --i) {
                    if (doWriteMessage(msg)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                    maxMessagesPerWrite--;
                } else {
                    break;
                }
            } catch (IOException e) {
                maxMessagesPerWrite--;

                // Continue on write error as a DatagramChannel can write to multiple remote peers
                //
                // See https://github.com/netty/netty/issues/2665
                in.remove(e);
            }
        }

        // Whether all messages were written or not.
        writeFilter(!in.isEmpty());
    }

    @Override
    protected void doShutdown(ChannelShutdownDirection direction) {
        switch (direction) {
            case Inbound:
                inputShutdown = true;
                break;
            case Outbound:
                outputShutdown = true;
                break;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public boolean isShutdown(ChannelShutdownDirection direction) {
        if (!isActive()) {
            return true;
        }
        switch (direction) {
            case Inbound:
                return inputShutdown;
            case Outbound:
                return outputShutdown;
            default:
                throw new AssertionError();
        }
    }
}
