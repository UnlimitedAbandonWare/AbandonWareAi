package com.example.lms.netty;

import com.example.lms.netty.ChatChannelInitializer;
import com.example.lms.trace.LogCorrelation;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@Configuration
@RequiredArgsConstructor
public class NettyServerConfig implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(NettyServerConfig.class);

    // ✨ [수정] 프로퍼티 값을 안전하게 String으로 먼저 받습니다.
    @Value("${netty.enabled:true}")   private boolean enabled;

    // Accept missing properties without failing boot; treat port<=0 as disabled.
    @Value("${netty.port:0}")           private String portStr;
    @Value("${netty.boss-threads:1}")   private String bossCntStr;
    @Value("${netty.worker-threads:4}") private String workerCntStr;

    private final ChatChannelInitializer initializer;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private int port;

    /** 애플리케이션 기동 시 Netty 서버 시작 */
    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[NETTY_BIND_SKIP] Netty disabled via netty.enabled=false{}", LogCorrelation.suffix());
            return;
        }

        // ✨ [수정] 문자열에서 숫자만 추출하여 정수로 파싱합니다.
        // 이렇게 하면 프로퍼티 파일의 인코딩 문제로 인해 불필요한 문자가 붙어도 정상 동작합니다.
        port = safeParseInt(portStr, 0);
        int bossCnt = safeParseInt(bossCntStr, 1);
        int workerCnt = safeParseInt(workerCntStr, 4);

        if (port <= 0) {
            log.info("[NETTY_BIND_SKIP] Netty disabled (port<=0){}", LogCorrelation.suffix());
            return;
        }

        bossGroup   = new NioEventLoopGroup(bossCnt);
        workerGroup = new NioEventLoopGroup(workerCnt);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        try {
            ChannelFuture f = b.bind(port).sync();
            log.info("[Netty] ▶▶ WebSocket listening on ws://localhost:{}{}", port, LogCorrelation.suffix());
            f.channel().closeFuture().addListener(cf -> stop());
        } catch (Exception e) {
            // IMPORTANT: do not hide/munge BindException strings; keep full stack trace.
            log.error("[Netty] Failed to bind port {}", port, e);
            log.warn("[NETTY_BIND_SKIP] Netty server start skipped (bind failure){}", LogCorrelation.suffix());
            stop();
        }
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

    private static int safeParseInt(String raw, int fallback) {
        try {
            if (raw == null) return fallback;
            String digits = raw.replaceAll("[^0-9]", "");
            if (digits.isBlank()) return fallback;
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return fallback;
        }
    }
}