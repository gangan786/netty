/*
 * Copyright 2016 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.UnstableApi;

/**
 * Factory that creates new {@link EventExecutorChooser}s.
 * 当客户端连接完成三次握手后，Main Reactor会创建客户端连接NioSocketChannel，
 * 并将其绑定到Sub Reactor Group中的一个固定Reactor，那么具体要绑定到哪个Sub Reactor上呢？
 * 这个绑定策略就是由chooserFactory来创建的。默认为DefaultEventExecutorChooserFactory。
 */
@UnstableApi
public interface EventExecutorChooserFactory {

    /**
     * Returns a new {@link EventExecutorChooser}.
     */
    EventExecutorChooser newChooser(EventExecutor[] executors);

    /**
     * Chooses the next {@link EventExecutor} to use.
     */
    @UnstableApi
    interface EventExecutorChooser {

        /**
         * Returns the new {@link EventExecutor} to use.
         * 选择一个EventExecutor用于绑定Channel
         */
        EventExecutor next();
    }
}
