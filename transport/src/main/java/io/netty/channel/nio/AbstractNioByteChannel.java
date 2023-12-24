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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    // true 表示Input已经shutdown了，再次对channel进行读取返回-1  设置该标志
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            // 这里的 isInputShutdown0 方法是用来判断服务端 TCP 连接上的读通道是否关闭，这里肯定是没有关闭的，
            // 因为从io.netty.channel.nio.AbstractNioByteChannel.NioByteUnsafe.read方法进来的时候，Netty还没调用任何关闭连接的系统调用
            if (!isInputShutdown0()) {
                if (isAllowHalfClosure(config())) {
                    // isAllowHalfClosure判断服务端是否支持半关闭 isAllowHalfClosure
                    // TCP半连接关闭处理,关闭读通道并不会向对端发送 FIN
                    shutdownInput();
                    /**
                     * 在 pipeline 中触发 ChannelInputShutdownEvent 事件，我们可以在 ChannelInputShutdownEvent 事件的回调方法中，
                     * 向客户端发送遗留的数据，做到真正的优雅关闭。
                     * 这里就是处于 CLOSE_WAIT 状态下的服务端在半关闭场景下可以继续向处于 FIN_WAIT2 状态下的客户端发送数据的地方
                     */
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    // 如果不支持半关闭，则服务端直接调用close方法向客户端发送fin,结束close_wait状态进如last_ack状态
                    // VoidChannelPromise表示调用方对处理结果不关心
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                // 此事件的触发标志着服务端在 CLOSE_WAIT 状态下已经将所有遗留的数据发送给了客户端，
                // 服务端可以在该事件的回调中关闭 Channel ，结束 CLOSE_WAIT 进入 LAST_ACK 状态。
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    // 如果发生异常时，已经读取到了部分数据，则触发ChannelRead事件
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            final ChannelConfig config = config();
            if (shouldBreakReadReady(config)) {
                // 当前连接处以半关闭状态，并且已经将所有遗留的数据发送给了客户端
                // 所以为了防止Reactor 线程就会在半关闭期间内一直在这里空转，导致 CPU 100%
                // 这里需要把OP_READ事件移除，但是这里并没有发起close发送Fin，
                // 所以用户可以监听ChannelInputShutdownReadComplete事件，
                // 手动调用io.netty.channel.AbstractChannelHandlerContext.close()方法关闭
                clearReadPending();
                return;
            }
            // 获取NioSocketChannel的pipeline
            final ChannelPipeline pipeline = pipeline();

            /**
             * 总结一下allocator与allocHandle的关系是：
             * allocator负责申请用于接受数据的ByteBuffer内存，
             * allocHandle用于每次动态调整ByteBuffer的大小
             */
            // PooledByteBufAllocator 具体用于实际分配ByteBuf的分配器
            // 需要与具体的ByteBuf分配器配合使用 比如这里的PooledByteBufAllocator
            final ByteBufAllocator allocator = config.getAllocator();
            //allocHandler用于统计每次读取数据的大小，方便下次分配合适大小的ByteBuf
            // 自适应ByteBuf分配器 AdaptiveRecvByteBufAllocator ,用于动态调节ByteBuf容量
            // 获得到的RecvByteBufAllocator为AdaptiveRecvByteBufAllocator
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            // 重置清除上次的统计指标
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    // 利用PooledByteBufAllocator分配合适大小的byteBuf 初始大小为2048
                    byteBuf = allocHandle.allocate(allocator);
                    // 当服务端收到RST包后，doReadBytes 方法从 Channel 中读取数据的时候会抛出 IOException 异常
                    //读取数据、记录本次读取了多少字节数
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    if (allocHandle.lastBytesRead() <= 0) {
                        // 如果本次没有读取到任何字节，则退出循环 进行下一轮事件轮询
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        // 当客户端主动关闭连接时（客户端发送fin1），会触发read就绪事件，
                        // 这里从channel读取的数据会是-1，此时服务器的状态是CLOSE_WAIT，客户端的状态是FIN_WAIT2
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // lastBytesRead < 0：表示客户端主动发起了连接关闭流程，Netty开始连接关闭处理流程
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        // lastBytesRead = 0：表示当前NioSocketChannel上的数据已经全部读取完毕，
                        // 没有数据可读了。本次OP_READ事件圆满处理完毕，可以开开心心的退出read loop
                        break;
                    }

                    // 记录循环次数
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    // 触发ChannelRead事件，由对应的ChannelHandle处理改IO数据
                    pipeline.fireChannelRead(byteBuf);
                    // 置为null方便垃圾回收？但是已经通过事件把引用传播出去了，这可能是答案：当执行到这里的时候就跳出循环了，需要将其置为null
                    byteBuf = null;
                } while (allocHandle.continueReading()); // 断是否应该继续read loop，默认最多循环16次

                // 根据本轮read loop总共读取到的字节数totalBytesRead来决定是否对用于接收下一轮OP_READ事件数据的ByteBuffer进行扩容或者缩容
                allocHandle.readComplete();
                // pipeline中触发ChannelReadComplete事件,通知ChannelHandler本次OP_READ事件已经处理完毕
                // 但这并不表示 客户端发送来的数据已经全部读完，因为如果数据太多的话，这里只会读取16次，剩下的会等到下次read事件到来后在处理
                pipeline.fireChannelReadComplete();

                if (close) {
                    // 连接关闭处理
                    // 此时客户端发送fin1（fin_wait_1状态）主动关闭连接，服务端接收到fin，并回复ack进入close_wait状态
                    // 在服务端进入close_wait状态 需要调用close 方法向客户端发送fin，服务端才能结束close_wait状态
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                // 处理TCP异常关闭的情况
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            final int localFlushedAmount = doWriteBytes(buf);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            // 文件已经传输完毕
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            // 零拷贝的方式传输文件
            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        // 走到这里表示 此时Socket已经写不进去了 退出writeLoop，注册OP_WRITE事件
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                // 判断buf是否使用的是堆外内存模式
                return msg;
            }

            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            // 这里处理还没写满16次 但是socket缓冲区已满写不进去的情况 注册write事件
            // 什么时候socket可写了， epoll会通知reactor线程继续写
            // 向 Reactor 注册 OP_WRITE 事件
            setOpWrite();
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            //这里处理的是socket缓冲区依然可写，但是写了16次还没写完，这时就不能在写了，reactor线程需要处理其他channel上的io事件
            //因为此时socket是可写的，必须清除op_write事件，否则会一直不停地被通知
            clearOpWrite();
            //如果本次writeLoop还没写完，则提交flushTask到reactor

            // Schedule flush again later so other tasks can be picked up in the meantime
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            // 向 Reactor 注册 OP_WRITE 事件
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
