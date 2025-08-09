package com.example.lms.netty;

import com.example.lms.netty.ChatChannelInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class NettyServerConfig implements DisposableBean {

    // ✨ [수정] 프로퍼티 값을 안전하게 String으로 먼저 받습니다.
    @Value("${netty.port}")           private String portStr;
    @Value("${netty.boss-threads}")   private String bossCntStr;
    @Value("${netty.worker-threads}") private String workerCntStr;

    private final ChatChannelInitializer initializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private int port;

    /** 애플리케이션 기동 시 Netty 서버 시작 */
    @PostConstruct
    public void start() throws InterruptedException {
        // ✨ [수정] 문자열에서 숫자만 추출하여 정수로 파싱합니다.
        // 이렇게 하면 프로퍼티 파일의 인코딩 문제로 인해 불필요한 문자가 붙어도 정상 동작합니다.
        port = Integer.parseInt(portStr.replaceAll("[^0-9]", ""));
        int bossCnt = Integer.parseInt(bossCntStr.replaceAll("[^0-9]", ""));
        int workerCnt = Integer.parseInt(workerCntStr.replaceAll("[^0-9]", ""));

        bossGroup   = new NioEventLoopGroup(bossCnt);
        workerGroup = new NioEventLoopGroup(workerCnt);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture f = b.bind(port).sync();
        log.info("[Netty] ▶▶ WebSocket listening on ws://localhost:{}", port);
        f.channel().closeFuture().addListener(cf -> stop());
    }

    /** Spring 종료 시 Netty graceful shutdown */
    @Override
    public void destroy() {
        stop();
    }

    private void stop() {
        if (bossGroup != null && !bossGroup.isShuttingDown() && !bossGroup.isShutdown()) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null && !workerGroup.isShuttingDown() && !workerGroup.isShutdown()) {
            workerGroup.shutdownGracefully();
        }
        log.info("[Netty] ◀◀ WebSocket server stopped.");
    }
}
