package io.netty.example.p2p;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

public class P2PLocal {

    private static Channel channelOne;
    private static Channel channelTwo;
    private static final NioEventLoopGroup groupOne = new NioEventLoopGroup(1);
    private static final NioEventLoopGroup groupTwo = new NioEventLoopGroup(1);

    public static void main(String[] args) throws InterruptedException {
        try {
            testP2P();
            //testConnect();
            int b= 10;
            b+=19;
            int[] a = {};
            int[12]{1,2};

            System.out.println("Enter text (quit to end)");
            ChannelFuture lastWriteFutureOne = null;
            ChannelFuture lastWriteFutureTwo = null;
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (; ; ) {
                String line = in.readLine();
                if (line == null || "quit".equalsIgnoreCase(line)) {
                    break;
                }

                ByteBuf byteBuf = Unpooled.copiedBuffer(line, Charset.defaultCharset());
                lastWriteFutureOne = channelOne.writeAndFlush(byteBuf);
                //lastWriteFutureTwo = channelTwo.writeAndFlush(line);

                lastWriteFutureOne.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            System.out.println("channelOne发送成功");
                        } else {
                            System.out.println("channelOne发送失败：" + future.cause());
                        }
                    }
                });
            }

            // Wait until all messages are flushed before closing the channel.
            if (lastWriteFutureOne != null) {
                lastWriteFutureOne.awaitUninterruptibly();
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
        } finally {
            groupOne.shutdownGracefully();
            groupTwo.shutdownGracefully();
        }


    }

    private static Channel createSocketAndConnect(String localHost, int localPort, String remoteHost, int remotePort, NioEventLoopGroup group) throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap();
        InetSocketAddress localAddress = new InetSocketAddress(localHost, localPort);
        InetSocketAddress remoteAddress = new InetSocketAddress(remoteHost, remotePort);
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast(new LoggingHandler(LogLevel.INFO))
                                .addLast(new P2PHandler());

                    }
                })
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5 * 1000);
        ChannelFuture connect = bootstrap.connect(remoteAddress, localAddress);
        connect.awaitUninterruptibly();
        if (connect.isSuccess()) {
            System.out.println("连接成功");
            return connect.channel();
        } else {
            System.out.println("连接失败" + connect.cause());
            group.shutdownGracefully();
            return null;
        }
    }

    private static void testP2P() throws InterruptedException {
        Thread thread1 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    channelOne = createSocketAndConnect("0.0.0.0", 8989, "localhost", 8990, groupOne);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        });

        Thread thread2 = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    channelTwo = createSocketAndConnect("0.0.0.0", 8990, "localhost", 8989, groupTwo);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();
    }

    /**
     * 本地以0.0.0.0为IP连接baidu
     */
    private static void testConnect() {
        Bootstrap bootstrap = new Bootstrap();
        InetSocketAddress localAddress = new InetSocketAddress("0.0.0.0", 9090);
        InetSocketAddress remoteAddress = new InetSocketAddress("baidu.com", 80);
        bootstrap.group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast(new LoggingHandler(LogLevel.INFO))
                                .addLast(new P2PHandler());
                    }
                })
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5 * 1000);
        ChannelFuture connect = bootstrap.connect(remoteAddress, localAddress);
        connect.awaitUninterruptibly();
        if (connect.isSuccess()) {
            System.out.println("连接成功");
            channelOne = connect.channel();
        } else {
            System.out.println("连接失败" + connect.cause());
        }
    }

}
