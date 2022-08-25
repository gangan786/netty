/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    /**
     * channel是由创建层次的，比如ServerSocketChannel 是 SocketChannel的 parent
     */
    private final Channel parent;
    /**
     * channel全局唯一ID machineId+processId+sequence+timestamp+random
     */
    private final ChannelId id;
    /**
     * unsafe用于封装对底层socket的相关操作
     */
    private final Unsafe unsafe;
    /**
     * 为channel分配独立的pipeline用于IO事件编排
     */
    private final DefaultChannelPipeline pipeline;
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);

    // 关闭channel操作的指定future，来判断关闭流程进度 每个channel对应一个CloseFuture
    // 连接关闭之后，netty 会通知这个CloseFuture
    private final CloseFuture closeFuture = new CloseFuture(this);

    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;
    private volatile EventLoop eventLoop;
    private volatile boolean registered;
    // channel的关闭流程是否已经开始
    private boolean closeInitiated;
    private Throwable initialCloseCause;

    /** Cache for the string representation of this channel */
    private boolean strValActive;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent) {
        this.parent = parent;
        // channel全局唯一ID machineId+processId+sequence+timestamp+random
        id = newId();
        // unsafe用于封装对底层socket的相关操作
        unsafe = newUnsafe();
        // 为channel分配独立的pipeline用于IO事件编排
        pipeline = newChannelPipeline();
    }

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, ChannelId id) {
        this.parent = parent;
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    protected final int maxMessagesPerWrite() {
        ChannelConfig config = config();
        if (config instanceof DefaultChannelConfig) {
            return ((DefaultChannelConfig) config).getMaxMessagesPerWrite();
        }
        Integer value = config.getOption(ChannelOption.MAX_MESSAGES_PER_WRITE);
        if (value == null) {
            return Integer.MAX_VALUE;
        }
        return value;
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    /**
     * Returns a new {@link DefaultChannelId} instance. Subclasses may override this method to assign custom
     * {@link ChannelId}s to {@link Channel}s that use the {@link AbstractChannel#AbstractChannel(Channel)} constructor.
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * Returns a new {@link DefaultChannelPipeline} instance.
     */
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this);
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        // 触发read事件
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     */
    protected abstract class AbstractUnsafe implements Unsafe {

        // 待发送数据缓冲队列  Netty是全异步框架，所以这里需要一个缓冲队列来缓存用户需要发送的数据
        // 每个客户端 NioSocketChannel 对应一个 ChannelOutboundBuffer 待发送数据缓冲队列
        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        private RecvByteBufAllocator.Handle recvHandle;
        //是否正在进行flush操作
        private boolean inFlush0;
        /** true if the channel has never been registered, false otherwise */
        private boolean neverRegistered = true;

        private void assertEventLoop() {
            assert !registered || eventLoop.inEventLoop();
        }

        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        /**
         * 注册channel到绑定的Reactor
         * @param eventLoop
         * @param promise
         */
        @Override
        public final void register(EventLoop eventLoop, final ChannelPromise promise) {
            ObjectUtil.checkNotNull(eventLoop, "eventLoop");
            if (isRegistered()) {
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }
            // EventLoop的类型要与Channel的类型一样  Nio Oio Aio
            if (!isCompatible(eventLoop)) {
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }

            // channel保存其绑定的Reactor实例
            AbstractChannel.this.eventLoop = eventLoop;

            /**
             * 执行channel注册的操作必须是Reactor线程来完成
             *
             * 1: 如果当前执行线程是Reactor线程，则直接执行register0进行注册
             * 2：如果当前执行线程是外部线程，则需要将register0注册操作 封装程异步Task 由Reactor线程执行
             *
             * 一般从ServerBootStrap启动走到这里的。是用户的main线程，所以通常是执行异步Task执行注册
             * */
            if (eventLoop.inEventLoop()) {
                register0(promise);
            } else {
                try {
                    // Reactor线程的启动是在向Reactor提交第一个异步任务的时候启动的
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            register0(promise);
                        }
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    closeFuture.setClosed();
                    safeSetFailure(promise, t);
                }
            }
        }

        private void register0(ChannelPromise promise) {
            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                // 查看注册操作是否已经取消，或者对应channel已经关闭
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }
                boolean firstRegistration = neverRegistered;
                // 执行真正的注册操作
                doRegister();
                // 修改注册状态
                neverRegistered = false;
                registered = true;

                // Ensure we call handlerAdded(...) before we actually notify the promise. This is needed as the
                // user may already fire events through the pipeline in the ChannelFutureListener.
                // 回调pipeline中添加的ChannelInitializer的handlerAdded方法，在这里初始化channelPipeline
                pipeline.invokeHandlerAddedIfNeeded();

                // 设置regFuture为success，触发operationComplete回调,将bind操作放入Reactor的任务队列中，等待Reactor线程执行
                safeSetSuccess(promise);
                // 触发channelRegister事件
                pipeline.fireChannelRegistered();
                // Only fire a channelActive if the channel has never been registered. This prevents firing
                // multiple channel actives if the channel is deregistered and re-registered.
                // 对于服务端ServerSocketChannel来说 只有绑定端口地址成功后 channel的状态才是active的。
                // 此时绑定操作作为异步任务在Reactor的任务队列中，绑定操作还没开始，所以这里的isActive()是false
                //
                // 对于客户端NioSocketChannel来说 判断是否激活的标准为是否处于Connected状态，所以这里的isActive返回true
                if (isActive()) {
                    /**
                     * 客户端SocketChannel注册成功后会走这里，在channelActive事件回调中注册OP_READ事件
                     * */
                    if (firstRegistration) {
                        // 触发channelActive事件
                        pipeline.fireChannelActive();
                    } else if (config().isAutoRead()) {
                        // This channel was registered before and autoRead() is set. This means we need to begin read
                        // again so that we process inbound data.
                        //
                        // See https://github.com/netty/netty/issues/4805
                        beginRead();
                    }
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                localAddress instanceof InetSocketAddress &&
                !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                        "is not bound to a wildcard address; binding to a non-wildcard " +
                        "address (" + localAddress + ") anyway as requested.");
            }

            // 判断Channel是否被激活
            boolean wasActive = isActive();
            try {
                // io.netty.channel.socket.nio.NioServerSocketChannel.doBind
                // 调用具体channel实现类
                doBind(localAddress);
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            // 绑定成功后 channel激活 触发channelActive事件传播
            // 使用异步线程触发channelActive事件，是因为假如用当前线程触发的话，
            // 会延迟注册在promise的ChannelFutureListener的回调
            if (!wasActive && isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        // pipeline中触发channelActive事件
                        pipeline.fireChannelActive();
                    }
                });
            }
            // 回调注册在promise上的ChannelFutureListener
            safeSetSuccess(promise);
        }

        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                doDisconnect();
                // Reset remoteAddress and localAddress
                remoteAddress = null;
                localAddress = null;
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            safeSetSuccess(promise);
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public void close(final ChannelPromise promise) {
            assertEventLoop();

            /**
             * 当 Channel 关闭之后，需要清理 Channel 写入缓冲队列 ChannelOutboundBuffer 中的待发送数据，
             * 这里会将异常 cause 传递给用户的 writePromise ，通知用户 Channel 已经关闭，write 操作失败。
             * 这里传入的异常类型为 StacklessClosedChannelException
             */
            ClosedChannelException closedChannelException =
                    StacklessClosedChannelException.newInstance(AbstractChannel.class, "close(ChannelPromise)");
            // notify = false: 由于当前是关闭操作，所以 notify = false ，不需要触发 ChannelWritabilityChanged 事件
            close(promise, closedChannelException, closedChannelException, false);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         */
        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         * @param cause The cause which may provide rational for the shutdown.
         */
        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            if (!promise.setUncancellable()) {
                return;
            }

            // 如果Channel已经close了，直接返回
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                promise.setFailure(new ClosedChannelException());
                return;
            }
            // 主动关闭方连接的写通道被关闭，不允许继续写入数据到Socket
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);

            // When a side enables SO_LINGER and calls showdownOutput(...) to start TCP half-closure
            // we can not call doDeregister here because we should ensure this side in fin_wait2 state
            // can still receive and process the data which is send by another side in the close_wait state。
            // See https://github.com/netty/netty/issues/11981
            try {
                // The shutdown function does not block regardless of the SO_LINGER setting on the socket
                // so we don't need to use GlobalEventExecutor to execute the shutdown
                doShutdownOutput();
                promise.setSuccess();
            } catch (Throwable err) {
                promise.setFailure(err);
            } finally {
                closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
            }
        }

        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            // shutdownOutput半关闭后需要清理channelOutboundBuffer中的待发送数据flushedEntry
            buffer.failFlushed(cause, false);
            // 循环清理channelOutboundBuffer中的unflushedEntry
            buffer.close(cause, true);
            // pipeline 中传播 ChannelOutputShutdownEvent 事件
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);
        }

        /**
         *
         * @param promise 服务端作为被动关闭方，这里传入的 ChannelPromise 类型为 VoidChannelPromise ，
         *                表示调用方对处理结果并不关心，VoidChannelPromise 不可添加 Listener ，不可修改操作结果状态
         * @param cause 将连接关闭的异常通知给 Channel 发送数据缓冲队列 ChannelOutboundBuffer 中的 flushedEntry 队列
         * @param closeCause 将连接关闭的异常通知给 ChannelOutboundBuffer 中的 unflushedEntry 队列
         * @param notify 由于在关闭 Channel 之后，会清理 Channel 对应的发送缓冲队列 ChannelOutboundBuffer 中存储的待发送数据，
         *               同时也会释放其中用于存储待发送数据用的 ByteBuffer ，当 ChannelOutboundBuffer 中的内存占用低于低水位线的时候，
         *               会触发 ChannelWritabilityChanged 事件。这里的参数 boolean notify 决定是否触发 ChannelWritabilityChanged 事件
         */
        private void close(final ChannelPromise promise, final Throwable cause,
                           final ClosedChannelException closeCause, final boolean notify) {
            if (!promise.setUncancellable()) {
                // 关闭操作如果被取消则直接返回
                return;
            }

            // 由于本close()方法的上游存在assertEventLoop()判断，即该变量只会被同一个线程操作，
            // 所以closeInitiated并不需要使用volatile保证可见性
            if (closeInitiated) {
                // 如果此时channel已经开始关闭流程，则进入这里
                if (closeFuture.isDone()) {
                    // Closed already.
                    // 如果channel已经关闭 则设置promise为success，如果promise是voidPromise类型则会跳过
                    safeSetSuccess(promise);
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    // 如果promise不是voidPromise，则会在关闭完成后 通过closeFuture设置promise success，
                    // 比如客户端主动调用 ctx.channel().close()，则这里的promise不是VoidChannelPromise
                    closeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }

            // 当前channel现在开始进入正在关闭状态
            closeInitiated = true;

            // 当前channel是否active
            final boolean wasActive = isActive();
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            // 将channel对应的写缓冲区channelOutboundBuffer设置为null 表示channel要关闭了，不允许继续发送数据
            // 此时如果还在write数据，则直接释放bytebuffer，并立马 fail 相关writeFuture 并抛出newClosedChannelException异常
            // 此时如果执行flush，则会直接返回
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
            // 如果开启了SO_LINGER，则需要先将channel从reactor中取消掉。避免reactor线程空转浪费cpu
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                // 当我们开启了 SO_LINGER 选项时，closeExecutor = GlobalEventExecutor.INSTANCE ，
                // 避免了 Reactor 线程的阻塞。
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // 在GlobalEventExecutor中执行channel的关闭任务,设置closeFuture,promise success
                            // Execute the close.
                            // 假如开启了SO_LINGER，在prepareToClose中对SelectionKey进行了java.nio.channels.SelectionKey.cancel()操作，在这里doClose0()也有cancel()操作，他两有什么不一样，为什么要cancel两次？
                            doClose0(promise);
                        } finally {
                            // reactor线程中执行
                            // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    if (outboundBuffer != null) {
                                        // Fail all the queued messages
                                        // cause = closeCause = ClosedChannelException, notify = false
                                        // 此时channel已经关闭，需要清理对应channelOutboundBuffer中的待发送数据flushedEntry
                                        outboundBuffer.failFlushed(cause, notify);
                                        // 循环清理channelOutboundBuffer中的unflushedEntry
                                        outboundBuffer.close(closeCause);
                                    }
                                    //这里的active = true
                                    // 关闭channel后，会将channel从reactor中注销，
                                    // 首先触发ChannelInactive事件，然后触发ChannelUnregistered
                                    fireChannelInactiveAndDeregister(wasActive);
                                }
                            });
                        }
                    }
                });
            } else {
                // 由当前的reactor线程执行关闭任务
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise);
                } finally {
                    if (outboundBuffer != null) {
                        // Fail all the queued messages.
                        // 清理channelOutboundBuffer中的flushedEntry到tailEntry之间的未发送&已发送数据
                        outboundBuffer.failFlushed(cause, notify);
                        // 清理channelOutboundBuffer中的unflushedEntry到tailEntry之间的未发送数据
                        outboundBuffer.close(closeCause);
                        /**
                         * 在关闭 Channel 之前，用户可能还会向 ChannelOutboundBuffer 中 write 数据，但还未来得及调用 flush 操作，
                         * 这就导致了 ChannelOutboundBuffer 中在 unflushedEntry 指针与 tailEntry 指针之间还可能会有数据。
                         * 所以需要再次调用outboundBuffer.close(closeCause)把这部分数据清理
                         */
                    }
                }
                if (inFlush0) {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        private void doClose0(ChannelPromise promise) {
            try {
                // 关闭channel，此时服务端向客户端发送fin2，服务端进入last_ack状态，客户端收到fin2进入time_wait状态
                doClose();
                // 设置clostFuture的状态为success，表示channel已经关闭
                // 调用shutdownOutput则不会通知closeFuture
                closeFuture.setClosed();
                // 通知用户promise success,关闭操作已经完成
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                // 通知用户线程关闭失败
                safeSetFailure(promise, t);
            }
        }

        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            // wasActive && !isActive() 条件表示 channel的状态第一次从active变为 inactive
            deregister(voidPromise(), wasActive && !isActive());
        }

        @Override
        public final void closeForcibly() {
            assertEventLoop();

            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();

            deregister(promise, false);
        }

        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is needed as for example,
            // we may be in the ByteToMessageDecoder.callDecode(...) method and so still try to do processing in
            // the old EventLoop while the user already registered the Channel to a new EventLoop. Without delay,
            // the deregister operation this could lead to have a handler invoked by different EventLoop and so
            // threads.
            // 上面的注释解释了为什么要提交到EventLoop延后执行deregister操作
            // 另外一个说法：这里延后 deRegister 操作的原因是用于处理一种极端的异常情况，前边我们提到 Channel 的 deregister() 操作是可以在用户的 ChannelHandler 中执行的，用户行为是不可预知的。
            // 我们想象一下这样的一个场景：假如当前 pipeline 中还有事件传播（比如正在处理编码解码），与此同时 deregister() 方法可能会在某个事件回调中被用户调用，导致其它事件在传播的过程中，Channel 被从 Reactor 上注销掉了。
            // 并且同时 channel 又注册到新的 Reactor 上。如果此时旧的 Reactor 正在处理 pipeline 上的事件而旧 Reactor 还未处理完的数据理应继续在旧的 Reactor 中处理，如果此时我们立马执行 deRegister ，未处理完的数据就会在新的 Reactor 上处理，这样就会导致一个 handler 被多个 Reactor 线程处理导致线程安全问题。所以需要延后 deRegister 的操作。
            // See:
            // https://github.com/netty/netty/issues/4435
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 将channel从reactor中注销，reactor不在监听channel上的事件
                        doDeregister();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                    } finally {
                        if (fireChannelInactive) {
                            // 当channel被关闭后，触发ChannelInactive事件
                            pipeline.fireChannelInactive();
                        }
                        // Some transports like local and AIO does not allow the deregistration of
                        // an open channel.  Their doDeregister() calls close(). Consequently,
                        // close() calls deregister() again - no need to fire channelUnregistered, so check
                        // if it was registered.
                        if (registered) {
                            // 如果channel没有注册，则不需要触发ChannelUnregistered
                            registered = false;
                            // 随后触发ChannelUnregistered
                            pipeline.fireChannelUnregistered();
                        }
                        // 通知deRegisterPromise
                        safeSetSuccess(promise);
                    }
                }
            });
        }

        @Override
        public final void beginRead() {
            assertEventLoop();

            try {
                // 触发在selector上注册channel感兴趣的监听事件
                doBeginRead();
            } catch (final Exception e) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        @Override
        public final void write(Object msg, ChannelPromise promise) {
            assertEventLoop();

            //获取当前channel对应的待发送数据缓冲队列（支持用户异步写入的核心关键）
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                // outboundBuffer == null说明channel准备关闭了，直接标记发送失败。
                // 在Channel准备关闭的时候，会将outboundBuffer = null
                try {
                    // release message now to prevent resource-leak
                    ReferenceCountUtil.release(msg);
                } finally {
                    // If the outboundBuffer is null we know the channel was closed and so
                    // need to fail the future right away. If it is not null the handling of the rest
                    // will be done in flush0()
                    // See https://github.com/netty/netty/issues/2362
                    safeSetFailure(promise,
                            newClosedChannelException(initialCloseCause, "write(Object, ChannelPromise)"));
                }
                return;
            }

            int size;
            try {
                // 过滤message类型 这里只会接受DirectBuffer或者fileRegion类型的msg
                msg = filterOutboundMessage(msg);
                // 计算当前msg的大小
                size = pipeline.estimatorHandle().size(msg);
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                try {
                    ReferenceCountUtil.release(msg);
                } finally {
                    safeSetFailure(promise, t);
                }
                return;
            }
            // 将msg 加入到Netty中的待写入数据缓冲队列ChannelOutboundBuffer中
            outboundBuffer.addMessage(msg, size, promise);
        }

        @Override
        public final void flush() {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            // channel已经关闭，在Channel准备关闭的时候，会将outboundBuffer = null
            if (outboundBuffer == null) {
                return;
            }

            // 将flushedEntry指针指向ChannelOutboundBuffer头结点，此时变为即将要flush进Socket的数据队列
            outboundBuffer.addFlush();
            flush0();
        }

        @SuppressWarnings("deprecation")
        protected void flush0() {
            if (inFlush0) {
                // Avoid re-entrance
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            // channel已经关闭或者outboundBuffer为空
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                // 如果 channel 已经关闭了或者对应写缓冲区中没有任何数据，那么就停止发送流程，直接 return
                return;
            }

            inFlush0 = true;

            // Mark all pending write requests as failure if the channel is inactive.
            if (!isActive()) {
                // 当前Channel并不是active或者connect状态的
                try {
                    // Check if we need to generate the exception at all.
                    if (!outboundBuffer.isEmpty()) {
                        if (isOpen()) {
                            // 当前channel处于disConnected状态
                            // 通知promise 写入失败 并触发channelWritabilityChanged事件
                            outboundBuffer.failFlushed(new NotYetConnectedException(), true);
                        } else {
                            // Do not trigger channelWritabilityChanged because the channel is closed already.
                            // 当前channel处于关闭状态 通知promise 写入失败 但不触发channelWritabilityChanged事件
                            outboundBuffer.failFlushed(newClosedChannelException(initialCloseCause, "flush0()"), false);
                        }
                    }
                } finally {
                    inFlush0 = false;
                }
                return;
            }

            try {
                // 写入Socket NioSocketChannel
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                handleWriteError(t);
            } finally {
                inFlush0 = false;
            }
        }

        protected final void handleWriteError(Throwable t) {
            if (t instanceof IOException && config().isAutoClose()) {
                /**
                 * Just call {@link #close(ChannelPromise, Throwable, boolean)} here which will take care of
                 * failing all flushed messages and also ensure the actual close of the underlying transport
                 * will happen before the promises are notified.
                 *
                 * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #isWritable()}
                 * may still return {@code true} even if the channel should be closed as result of the exception.
                 */
                initialCloseCause = t;
                close(voidPromise(), t, newClosedChannelException(t, "flush0()"), false);
            } else {
                try {
                    shutdownOutput(voidPromise(), t);
                } catch (Throwable t2) {
                    initialCloseCause = t;
                    close(voidPromise(), t2, newClosedChannelException(t, "flush0()"), false);
                }
            }
        }

        private ClosedChannelException newClosedChannelException(Throwable cause, String method) {
            ClosedChannelException exception =
                    StacklessClosedChannelException.newInstance(AbstractChannel.AbstractUnsafe.class, method);
            if (cause != null) {
                exception.initCause(cause);
            }
            return exception;
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();

            return unsafeVoidPromise;
        }

        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }

            safeSetFailure(promise, newClosedChannelException(initialCloseCause, "ensureOpen(ChannelPromise)"));
            return false;
        }

        /**
         * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        private void invokeLater(Runnable task) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //      -> channel.unsafe.close()
                //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }

        /**
         * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
         * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
         * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
         * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
         */
        protected Executor prepareToClose() {
            return null;
        }
    }

    /**
     * Return {@code true} if the given {@link EventLoop} is compatible with this instance.
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register process.
     *
     * Sub-classes may override this method
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Called when conditions justify shutting down the output portion of the channel. This may happen if a write
     * operation throws an exception.
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     *
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    protected void validateFileRegion(DefaultFileRegion region, long position) throws IOException {
        DefaultFileRegion.validate(region, position);
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
        }

        // Suppress a warning since this method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }
}
