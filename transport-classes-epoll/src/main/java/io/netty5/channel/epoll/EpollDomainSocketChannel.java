/*
 * Copyright 2015 The Netty Project
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
package io.netty5.channel.epoll;

import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.EventLoop;
import io.netty5.channel.socket.DomainSocketAddress;
import io.netty5.channel.unix.DomainSocketReadMode;
import io.netty5.channel.unix.FileDescriptor;
import io.netty5.channel.unix.PeerCredentials;
import io.netty5.channel.unix.UnixServerSocketChannel;
import io.netty5.channel.unix.UnixSocketChannel;
import io.netty5.util.internal.UnstableApi;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Set;

import static io.netty5.channel.ChannelOption.SO_RCVBUF;
import static io.netty5.channel.ChannelOption.SO_SNDBUF;
import static io.netty5.channel.epoll.LinuxSocket.newSocketDomain;
import static io.netty5.channel.unix.UnixChannelOption.DOMAIN_SOCKET_READ_MODE;
import static java.util.Objects.requireNonNull;

/**
 * {@link UnixSocketChannel} implementation for Unix Domain Sockets that uses linux EPOLL Edge-Triggered Mode for
 * maximal performance.
 *
 * <h3>Available options</h3>
 *
 * In addition to the options provided by {@link UnixSocketChannel},
 * {@link EpollDomainSocketChannel} allows the following options in the option map:
 *
 * <table border="1" cellspacing="0" cellpadding="6">
 * <tr>
 * <th>Name</th>
 * </tr><tr>
 * <td>{@link EpollChannelOption#DOMAIN_SOCKET_READ_MODE}</td>
 * </tr>
 * </table>
 */
public final class EpollDomainSocketChannel
        extends AbstractEpollStreamChannel<UnixServerSocketChannel, DomainSocketAddress, DomainSocketAddress>
        implements UnixSocketChannel {

    private static final Set<ChannelOption<?>> SUPPORTED_OPTIONS = supportedOptions();
    private volatile DomainSocketAddress local;
    private volatile DomainSocketAddress remote;

    public EpollDomainSocketChannel(EventLoop eventLoop) {
        super(eventLoop, newSocketDomain(), false);
    }

    EpollDomainSocketChannel(UnixServerSocketChannel parent, EventLoop eventLoop, FileDescriptor fd) {
        super(parent, eventLoop, new LinuxSocket(fd.intValue()));
    }

    public EpollDomainSocketChannel(EventLoop eventLoop, int fd) {
        super(eventLoop, fd);
    }

    public EpollDomainSocketChannel(UnixServerSocketChannel parent, EventLoop eventLoop, LinuxSocket fd) {
        super(parent, eventLoop, fd);
    }

    public EpollDomainSocketChannel(int fd, EventLoop eventLoop, boolean active) {
        super(eventLoop, new LinuxSocket(fd), active);
    }

    private volatile DomainSocketReadMode mode = DomainSocketReadMode.BYTES;

    @SuppressWarnings("unchecked")
    @Override
    protected <T> T getExtendedOption(ChannelOption<T> option) {
        if (option == DOMAIN_SOCKET_READ_MODE) {
            return (T) getReadMode();
        }
        if (option == SO_SNDBUF) {
            return (T) Integer.valueOf(getSendBufferSize());
        }
        if (option == SO_RCVBUF) {
            return (T) Integer.valueOf(getReceiveBufferSize());
        }
        return super.getExtendedOption(option);
    }

    @Override
    protected <T> void setExtendedOption(ChannelOption<T> option, T value) {
        if (option == DOMAIN_SOCKET_READ_MODE) {
            setReadMode((DomainSocketReadMode) value);
        } else if (option == SO_SNDBUF) {
            setSendBufferSize((Integer) value);
        } else if (option == SO_RCVBUF) {
            setReceiveBufferSize((Integer) value);
        } else {
            super.setExtendedOption(option, value);
        }
    }

    @Override
    protected boolean isExtendedOptionSupported(ChannelOption<?> option) {
        if (SUPPORTED_OPTIONS.contains(option)) {
            return true;
        }
        return super.isExtendedOptionSupported(option);
    }

    private static Set<ChannelOption<?>> supportedOptions() {
        return newSupportedIdentityOptionsSet(DOMAIN_SOCKET_READ_MODE, SO_SNDBUF, SO_RCVBUF);
    }

    private void setReadMode(DomainSocketReadMode mode) {
        requireNonNull(mode, "mode");
        this.mode = mode;
    }

    private DomainSocketReadMode getReadMode() {
        return mode;
    }

    private int getSendBufferSize() {
        try {
            return socket.getSendBufferSize();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setSendBufferSize(int sendBufferSize) {
        try {
            socket.setSendBufferSize(sendBufferSize);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int getReceiveBufferSize() {
        try {
            return socket.getReceiveBufferSize();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setReceiveBufferSize(int receiveBufferSize) {
        try {
            socket.setReceiveBufferSize(receiveBufferSize);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    @Override
    protected DomainSocketAddress localAddress0() {
        return local;
    }

    @Override
    protected DomainSocketAddress remoteAddress0() {
        return remote;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        socket.bind(localAddress);
        local = (DomainSocketAddress) localAddress;
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (super.doConnect(remoteAddress, localAddress)) {
            local = (DomainSocketAddress) localAddress;
            remote = (DomainSocketAddress) remoteAddress;
            return true;
        }
        return false;
    }

    @Override
    protected int doWriteSingle(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg instanceof FileDescriptor && socket.sendFd(((FileDescriptor) msg).intValue()) > 0) {
            // File descriptor was written, so remove it.
            in.remove();
            return 1;
        }
        return super.doWriteSingle(in);
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof FileDescriptor) {
            return msg;
        }
        return super.filterOutboundMessage(msg);
    }

    /**
     * Returns the unix credentials (uid, gid, pid) of the peer
     * <a href=https://man7.org/linux/man-pages/man7/socket.7.html>SO_PEERCRED</a>
     */
    @UnstableApi
    public PeerCredentials peerCredentials() throws IOException {
        return socket.getPeerCredentials();
    }

    @Override
    void epollInReady() {
        switch (getReadMode()) {
            case BYTES:
                super.epollInReady();
                break;
            case FILE_DESCRIPTORS:
                epollInReadFd();
                break;
            default:
                throw new Error();
        }
    }

    private void epollInReadFd() {
        if (socket.isInputShutdown()) {
            clearEpollIn0();
            return;
        }
        final EpollRecvBufferAllocatorHandle allocHandle = recvBufAllocHandle();

        final ChannelPipeline pipeline = pipeline();
        allocHandle.reset();
        epollInBefore();

        try {
            readLoop: do {
                // lastBytesRead represents the fd. We use lastBytesRead because it must be set so that the
                // EpollRecvBufferAllocatorHandle knows if it should try to read again or not when autoRead is
                // enabled.
                allocHandle.lastBytesRead(socket.recvFd());
                switch(allocHandle.lastBytesRead()) {
                case 0:
                    break readLoop;
                case -1:
                    closeTransport(newPromise());
                    return;
                default:
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    pipeline.fireChannelRead(new FileDescriptor(allocHandle.lastBytesRead()));
                    break;
                }
            } while (allocHandle.continueReading(isAutoRead()) && !isShutdown(ChannelShutdownDirection.Inbound));

            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
        } catch (Throwable t) {
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireChannelExceptionCaught(t);
        } finally {
            readIfIsAutoRead();
            epollInFinally();
        }
    }
}
