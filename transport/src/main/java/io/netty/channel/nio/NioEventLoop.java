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

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    /**
     * 表示是否对jdk原生的Selector进行优化
     * 默认是false，表示需要优化
     * 为了遍历的效率 会对Selector中的SelectedKeys进行数据结构优化
     */
    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - https://bugs.openjdk.java.net/browse/JDK-6427854 for first few dev (unreleased) builds of JDK 7
    // - https://bugs.openjdk.java.net/browse/JDK-6527572 for JDK prior to 5.0u15-rev and 6u10
    // - https://github.com/netty/netty/issues/203
    static {
        if (PlatformDependent.javaVersion() < 7) {
            final String key = "sun.nio.ch.bugLevel";
            final String bugLevel = SystemPropertyUtil.get(key);
            if (bugLevel == null) {
                try {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                        @Override
                        public Void run() {
                            System.setProperty(key, "");
                            return null;
                        }
                    });
                } catch (final SecurityException e) {
                    logger.debug("Unable to get/set System Property: " + key, e);
                }
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     * 保存装饰后的Selector实现：SelectedSelectionKeySetSelector
     */
    private Selector selector;
    /**
     * 被优化后的JDK原生的Selector
     */
    private Selector unwrappedSelector;
    /**
     * 保存sun.nio.ch.SelectorImpl类中
     * selectedKeys和publicSelectedKeys关联好的 Netty 优化实现SelectedSelectionKeySet
     * 后续就可以直接从这个变量中获取IO就绪的SocketChannel
     */
    private SelectedSelectionKeySet selectedKeys;

    /**
     * SelectorProvider会根据操作系统的不同，
     * 选择JDK在不同操作系统版本下的对应Selector的实现。
     * Linux下会选择Epoll，Mac下会选择Kqueue。
     */
    private final SelectorProvider provider;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    /**
     * 即能表示Reactor当前的状态，也能表示Reactor线程阻塞超时的时间
     * nextWakeupNanos用来保存java.nio.channels.Selector#select(long)的超时时间
     * 这里使用原子变量的原因是，这个字段会在io.netty.channel.nio.NioEventLoop#wakeup(boolean)使用
     * 这个wakeup方法会在向EventLoop添加异步任务的时候被调用
     * 由于添加异步任务是多线程并发，所以这里设置成原子变量
     */
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);

    private final SelectStrategy selectStrategy;

    private volatile int ioRatio = 50;
    private int cancelledKeys;

    /**
     * 用于及时从selectedKeys中清除失效的selectKey 比如 socketChannel从selector上被用户移除
     */
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory taskQueueFactory, EventLoopTaskQueueFactory tailTaskQueueFactory) {
        super(parent, executor, false, newTaskQueue(taskQueueFactory), newTaskQueue(tailTaskQueueFactory),
                rejectedExecutionHandler);
        this.provider = ObjectUtil.checkNotNull(selectorProvider, "selectorProvider");
        this.selectStrategy = ObjectUtil.checkNotNull(strategy, "selectStrategy");
        final SelectorTuple selectorTuple = openSelector();
        this.selector = selectorTuple.selector;
        this.unwrappedSelector = selectorTuple.unwrappedSelector;
    }

    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        /**
         * 被 Netty 优化过的 JDK NIO 原生 Selector
         */
        final Selector unwrappedSelector;

        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    /**
     * 此函数主要用于Selector创建
     * 和对JDK原生的Selector中用于保存IO就绪的数据结构做优化：publicSelectedKeys和selectedKeys
     * 为什么：涉及publicSelectedKeys和selectedKeys的主要有两种操作类型：插入、遍历
     * 原生的publicSelectedKeys和selectedKeys是由HashSet实现，通过数组替换HashSet实现优化的目的
     * 由于Hash冲突这种情况的存在，所以导致对哈希表进行插入和遍历操作的性能不如对数组进行插入和遍历操作的性能好
     * 而且数组可以利用 CPU 缓存的优势来提高遍历的效率
     *
     * @return
     */
    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            // 通过SelectorProvider#openSelector创建JDK NIO原生的Selector。
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEY_SET_OPTIMIZATION) {
            // 不需要优化Selector，直接返回Java原生的Selector：unwrappedSelector
            return new SelectorTuple(unwrappedSelector);
        }

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        // 判断由SelectorProvider创建出来的Selector是否是 JDK NIO 原生的Selector实现。
        // 因为 Netty 优化针对的是 JDK NIO 原生Selector。
        // 判断标准为sun.nio.ch.SelectorImpl类是否为SelectorProvider创建出Selector的父类。
        // 如果不是则直接返回。不再继续下面的优化过程。
        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                // Throwable的判断来自maybeSelectorImplClass创建异常
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        // 用于代替SelectImpl里的publicSelectedKeys和selectedKeys（用于保存事件就绪的Channel）
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    // 反射获取sun.nio.ch.SelectorImpl类中selectedKeys和publicSelectedKeys
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");
                    // 对SelectImpl里的publicSelectedKeys和selectedKeys的进行替换优化
                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            // Java9版本以上通过sun.misc.Unsafe设置字段值的方式
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }
                    // Java8反射替换字段
                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        // 将优化后并关联了SelectImpl的SelectedSelectionKeySet，赋值给本NioEventLoop对象的成员变量selectedKeys
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        // 带maxPendingTasks参数的为有界队列，否则为无界队列
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        ObjectUtil.checkNotNull(ch, "ch");
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        ObjectUtil.checkNotNull(task, "task");

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    @Override
    public Iterator<Channel> registeredChannelsIterator() {
        assert inEventLoop();
        final Set<SelectionKey> keys = selector.keys();
        if (keys.isEmpty()) {
            return ChannelsReadOnlyIterator.empty();
        }
        return new Iterator<Channel>() {
            final Iterator<SelectionKey> selectionKeyIterator =
                    ObjectUtil.checkNotNull(keys, "selectionKeys")
                            .iterator();
            Channel next;
            boolean isDone;

            @Override
            public boolean hasNext() {
                if (isDone) {
                    return false;
                }
                Channel cur = next;
                if (cur == null) {
                    cur = next = nextOrDone();
                    return cur != null;
                }
                return true;
            }

            @Override
            public Channel next() {
                if (isDone) {
                    throw new NoSuchElementException();
                }
                Channel cur = next;
                if (cur == null) {
                    cur = nextOrDone();
                    if (cur == null) {
                        throw new NoSuchElementException();
                    }
                }
                next = nextOrDone();
                return cur;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }

            private Channel nextOrDone() {
                Iterator<SelectionKey> it = selectionKeyIterator;
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isValid()) {
                        Object attachment = key.attachment();
                        if (attachment instanceof AbstractNioChannel) {
                            return (AbstractNioChannel) attachment;
                        }
                    }
                }
                isDone = true;
                return null;
            }
        };
    }

    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * 这里就是NioEventLoop线程执行的最核心的方法
     * 1. 轮训所有注册在其上的Channel中的IO就绪事件
     * 2. 处理对应Channel上的IO事件
     * 3. 执行异步任务
     */
    @Override
    protected void run() {
        // 记录异常JDK NIO Epoll的空轮询次数 用于解决JDK epoll的空轮训bug
        int selectCnt = 0;
        for (;;) {
            try {
                int strategy;
                try {
                    // 根据轮询策略获取轮询结果 这里的hasTasks()主要检查的是普通队列和尾部队列中是否有异步任务等待执行
                    strategy = selectStrategy.calculateStrategy(selectNowSupplier, hasTasks());
                    /*
                    当前selectStrategy.calculateStrategy返回值仅有这三种情况
                    返回 -1： switch 逻辑分支进入SelectStrategy.SELECT分支，表示此时Reactor中没有异步任务需要执行，
                            Reactor线程可以安心的阻塞在Selector上等待IO就绪事件发生。
                    返回 0： switch 逻辑分支进入default分支，表示此时Reactor中没有IO就绪事件但是有异步任务需要执行，
                            流程通过default分支直接进入了处理异步任务的逻辑部分。
                    返回 > 0：switch 逻辑分支进入default分支，表示此时Reactor中既有IO就绪事件发生也有异步任务需要执行，
                            流程通过default分支直接进入了处理IO就绪事件和执行异步任务逻辑部分。
                     */
                    switch (strategy) {
                    case SelectStrategy.CONTINUE:
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        // 当前没有异步任务执行，Reactor线程可以放心的阻塞等待IO就绪事件

                        // 从定时任务队列中取出即将快要执行的定时任务deadline
                        long curDeadlineNanos = nextScheduledTaskDeadlineNanos();
                        if (curDeadlineNanos == -1L) {
                            // -1代表当前定时任务队列中没有定时任务
                            curDeadlineNanos = NONE; // nothing on the calendar
                        }
                        // 最早执行定时任务的deadline作为 select的阻塞时间，意思是到了定时任务的执行时间
                        // 不管有无IO就绪事件，必须唤醒selector，从而使reactor线程执行定时任务
                        nextWakeupNanos.set(curDeadlineNanos);
                        try {
                            if (!hasTasks()) {
                                // 再次检查普通任务队列中是否有异步任务，如果恰巧有异步任务提交，则停止IO就绪事件轮训去执行异步任务
                                // 没有的话开始select阻塞轮询IO就绪事件
                                // 这里由于JDK NIO Epoll的空轮询BUG存在，可能即使没有任何连接的事件就绪也会被意外唤醒，
                                // 导致select返回0
                                strategy = select(curDeadlineNanos);
                            }
                        } finally {
                            // This update is just to help block unnecessary selector wakeups
                            // so use of lazySet is ok (no race condition)
                            // 执行到这里说明Reactor已经从Selector上被唤醒了
                            // 设置Reactor的状态为苏醒状态AWAKE
                            // lazySet优化不必要的volatile操作，不使用内存屏障，不保证写操作的马上可见性（单线程不需要保证）
                            // lazySet这样是不是会导致在io.netty.channel.nio.NioEventLoop.wakeup方法里面，
                            // 不能及时看到AWAKE状态导致不必要的系统调用：java.nio.channels.Selector.wakeup
                            nextWakeupNanos.lazySet(AWAKE);
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    selectCnt = 0;
                    handleLoopException(e);
                    continue;
                }

                // 执行到这里说明满足了唤醒条件，Reactor线程从selector上被唤醒开始处理IO就绪事件和执行异步任务

                selectCnt++;
                cancelledKeys = 0;
                needsToSelectAgain = false;
                // 调整Reactor线程执行IO事件和执行异步任务的CPU时间比例 默认50，表示执行IO事件和异步任务的时间比例是一比一
                final int ioRatio = this.ioRatio;
                // 表示是否执行过至少一次异步任务
                boolean ranTasks;
                /*
                对于不同的ioRatio值，我们可以看到他都是优先处理就绪IO：processSelectedKeys
                ioRatio只是用来限制异步任务的相对时间
                 */
                if (ioRatio == 100) {
                    try {
                        if (strategy > 0) {
                            processSelectedKeys();
                        }
                    } finally {
                        // Ensure we always run tasks.
                        // 处理异步任务，无超时限制
                        ranTasks = runAllTasks();
                    }
                } else if (strategy > 0) {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 处理异步任务，有超时限制
                        final long ioTime = System.nanoTime() - ioStartTime;
                        // 限定在超时时间内 处理有限的异步任务 防止Reactor线程处理异步任务时间过长而导致 I/O 事件阻塞
                        ranTasks = runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                } else {
                    ranTasks = runAllTasks(0); // This will run the minimum number of tasks
                }


                //判断是否触发JDK Epoll BUG 触发空轮询
                if (ranTasks || strategy > 0) {
                    if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS && logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                                selectCnt - 1, selector);
                    }
                    // 进入到这里表示select被io事件唤醒或者执行了异步任务
                    // 这里就要把selectCnt归零
                    // 从这里也说明只有连续被空轮询512次，才会执行到下面的：unexpectedSelectorWakeup()去重建selector
                    selectCnt = 0;
                } else if (unexpectedSelectorWakeup(selectCnt)) { // Unexpected wakeup (unusual case)
                    /**
                     * 如果ranTasks = false 并且 strategy = 0
                     * 这代表Reactor线程本次既没有异步任务执行
                     * 也没有IO就绪的Channel需要处理却被意外的唤醒。等于是空转了一圈啥也没干
                     * Netty就这样通过计数Reactor被意外唤醒的次数，如果计数selectCnt达到了512次，
                     * 则通过重建Selector 巧妙的绕开了JDK NIO Epoll空轮询BUG
                     */
                    selectCnt = 0;
                }
            } catch (CancelledKeyException e) {
                // Harmless exception - log anyway
                if (logger.isDebugEnabled()) {
                    logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                            selector, e);
                }
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                handleLoopException(t);
            } finally {
                // Always handle shutdown even if the loop processing threw an exception.
                try {
                    // 判断当前Reactor是否被调用shutdownGracefully处于关闭的状态
                    if (isShuttingDown()) {
                        // 关闭Reactor上注册的所有Channel,停止处理IO事件，触发unActive以及unRegister事件
                        closeAll();
                        // 注销掉所有Channel停止处理IO事件之后，剩下的就需要执行Reactor中剩余的异步任务了
                        if (confirmShutdown()) {
                            // confirmShutdown() 方法，将剩余的异步任务执行完毕。
                            // 在该方法中只要有异步任务需要执行，就不能关闭，保证业务无损。
                            // 该方法返回值为 true 时表示可以进行关闭。返回 false 时表示不能马上关闭。
                            return;
                        }
                    }
                } catch (Error e) {
                    throw e;
                } catch (Throwable t) {
                    handleLoopException(t);
                }
            }
        }
    }

    // returns true if selectCnt should be reset
    private boolean unexpectedSelectorWakeup(int selectCnt) {
        if (Thread.interrupted()) {
            // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
            // As this is most likely a bug in the handler of the user or it's client library we will
            // also log it.
            //
            // See https://github.com/netty/netty/issues/2426
            if (logger.isDebugEnabled()) {
                logger.debug("Selector.select() returned prematurely because " +
                        "Thread.currentThread().interrupt() was called. Use " +
                        "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
            }
            return true;
        }
        /**
         * 走到这里的条件是 既没有IO就绪事件，也没有异步任务，Reactor线程从Selector上被异常唤醒
         * 这种情况可能是已经触发了JDK Epoll的空轮询BUG，
         * 如果这种情况持续512次 则认为可能已经触发BUG，于是重建Selector
         *
         * */
        if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
            // The selector returned prematurely many times in a row.
            // Rebuild the selector to work around the problem.
            logger.warn("Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                    selectCnt, selector);
            // 将之前注册的所有Channel重新注册到新的Selector上并关闭旧的Selector
            rebuildSelector();
            return true;
        }
        return false;
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        // 是否采用netty优化后的selectedKey集合类型 是由变量DISABLE_KEY_SET_OPTIMIZATION决定的 默认为false
        if (selectedKeys != null) {
            // 使用经Netty优化后的SelectedSelectionKeySet获取IO就绪的Channel
            processSelectedKeysOptimized();
        } else {
            // 使用Java原生的SelectorImpl获取IO就绪的Channel
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    /**
     * 将socketChannel从selector中移除 取消监听IO事件
     * @param key
     */
    void cancel(SelectionKey key) {
        // SelectionKey#cancel方法调用完毕后，此时调用SelectionKey#isValid将会返回false，
        // 在处理IO就绪事件之前会调用isValid进行判断（NioEventLoop.processSelectedKey(AbstractNioChannel)），
        // 从而保证在还没达到256之前不会错误处理已注销的channel
        key.cancel();
        cancelledKeys ++;
        // 当从selector中移除的socketChannel数量达到256个，设置needsToSelectAgain为true
        // 在io.netty.channel.nio.NioEventLoop.processSelectedKeysPlain 中重新做一次轮询，将失效的selectKey移除，
        // 以保证selectKeySet的有效性
        // 疑问：为什么是256呢，不过显然是攒够一波取消监听的channel再select，有利于减少系统调用
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            // key.cancel()只是将key打上标记，并不会从selector的就绪队列移除
            // 假如有大量的 Channel 从 Selector 中注销，
            // 就绪集合 selectedKeys 中依然会保存这些 Channel 对应 SelectionKey 直到下次轮询。
            // 那么当然会影响本次轮询结果 selectedKeys 的有效性，增加了许多不必要的遍历开销
            // 所以这里需要needsToSelectAgain来保证就绪队列的有效性
            needsToSelectAgain = true;
        }
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            // a来自这里：io.netty.channel.nio.AbstractNioChannel.doRegister
            final Object a = k.attachment();
            //注意每次迭代末尾的keyIterator.remove()调用。Selector不会自己从已选择键集中移除SelectionKey实例。
            //必须在处理完通道时自己移除。下次该通道变成就绪时，Selector会再次将其放入已选择键集中。
            i.remove();

            if (a instanceof AbstractNioChannel) {
                // a为Netty自定义的Channel，在io.netty.channel.nio.AbstractNioChannel.doRegister方法中设置了attachment属性

                // 对于客户端连接事件（OP_ACCEPT）活跃时，这里的Channel类型为NioServerSocketChannel。
                // 对于客户端读写事件（Read，Write）活跃时，这里的Channel类型为NioSocketChannel。
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                //用户自定的IO就绪事件处理，上面的是有Netty处理Channel上的IO就绪事件
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            // 目的是再次进入for循环 移除失效的selectKey(socketChannel可能从selector上移除)
            // 为什么需要needsToSelectAgain可以查看他被设置成true的地方：io.netty.channel.nio.NioEventLoop.cancel
            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        // 在openSelector的时候将JDK中selector实现类中得selectedKeys和publicSelectKeys字段类型
        // 由原来的HashSet类型替换为 Netty优化后的数组实现的SelectedSelectionKeySet类型
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            // 对应迭代器中得remove   selector不会自己清除selectedKey
            // selector只会往其中添加SelectionKey，所以需要手动清除，置为null方便垃圾回收
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                // 将从i+1位置到最后一个selectKey都置为null移除，为啥不对0到i的selectKey也都置为null呢？
                // 针对上面的这个疑问，可能是因为在reset调用中，将size赋值为0，然后再配合下面的selectAgain()
                // 将0到i值覆盖成新的，就可以实现原有的selectedKeys的清空
                selectedKeys.reset(i + 1);

                selectAgain();
                // i=-1，再自增，i就又从0开始循环了
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 获取Channel的底层操作类Unsafe
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            // java.nio.channels.SelectionKey.cancel方法的调用会导致这里的isValid返回false
            // 如果SelectionKey已经失效则关闭对应的Channel
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop == this) {
                // close the channel if the key is not valid anymore
                unsafe.close(unsafe.voidPromise());
            }
            return;
        }

        try {
            // 获取IO就绪事件
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // 处理Connect事件
                // 这里的Connect表示客户端发起连接并三次握手成功后的OP_CONNECT事件，属于客户端事件，对应的在服务端的事件就是OP_ACCEPT
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                // 移除该selectKey对connect事件的监听，否则Selector会一直通知
                int ops = k.interestOps();
                // 在ops事件合集中删除connect，通过取反+且运算
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                // 触发channelActive事件处理Connect事件，开始在pipeline中传播ChannelActive事件
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // 处理Write事件
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                // 处理Read事件或者Accept事件，或者连接关闭FIN事件、RST事件（Netty对于关闭连接、RST事件的响应是通过处理OP_READ事件完成的）
                // 服务端NioServerSocketChannel中的Read方法处理的是Accept事件
                // 客户端NioSocketChannel中的Read方法处理的是Read事件
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            default:
                 break;
            }
        }
    }

    private void closeAll() {
        // 这里的目的是清理selector中的一些无效key
        selectAgain();
        // 获取Selector上注册的所有Channel
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            // 获取NioSocketChannel
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            // 关闭Reactor上注册的所有Channel，并在pipeline中触发unActive事件和unRegister事件
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            // nextWakeupNanos = AWAKE时表示当前Reactor正处于苏醒状态，
            // 苏醒状态没必要重复唤醒selector，节省系统调用开销
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        // Note this is also correct for the nextWakeupNanos == -1 (AWAKE) case
        return deadlineNanos < nextWakeupNanos.get();
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    /**
     * 非阻塞查看当前是否有IO就绪事件发生
     * @return
     * @throws IOException
     */
    int selectNow() throws IOException {
        return selector.selectNow();
    }

    private int select(long deadlineNanos) throws IOException {
        if (deadlineNanos == NONE) {
            //无定时任务，无普通任务执行时，开始轮询IO就绪事件，没有就一直阻塞 直到唤醒条件成立
            return selector.select();
        }
        // Timeout will only be 0 if deadline is within 5 microsecs
        // 防止当deadline为5微秒的时候timeoutMillis被计算成0，导致该任务被错误的立即执行了（疑惑？当1微秒的时候也有可能被计算成0，为什么选择5微秒作为标准呢？）
        // 但是加上0.995毫秒会延迟任务执行，这又算不算一种错误呢？又或者说精确度只要到毫秒即可
        // 得出结论，除了timeoutMillis计算成0时被立即执行外，Reactor在有定时任务的情况下，至少要阻塞1毫秒
        long timeoutMillis = deadlineToDelayNanos(deadlineNanos + 995000L) / 1000000L;
        return timeoutMillis <= 0 ? selector.selectNow() : selector.select(timeoutMillis);
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
