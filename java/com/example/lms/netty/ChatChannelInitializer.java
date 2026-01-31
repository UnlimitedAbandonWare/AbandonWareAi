package com.example.lms.netty;

import com.example.lms.netty.ChatWebSocketHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



@Component
@RequiredArgsConstructor
public class ChatChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final ChatWebSocketHandler chatHandler;

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
                // websocket 핸드셰이크 경로 설정
                .addLast(new WebSocketServerProtocolHandler("/ws", null, true))
                .addLast(chatHandler);      // ↙ 실제 비즈니스 로직
    }
}