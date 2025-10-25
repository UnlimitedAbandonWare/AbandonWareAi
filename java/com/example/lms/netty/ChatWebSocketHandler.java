// src/main/java/com/example/lms/netty/ChatWebSocketHandler.java
package com.example.lms.netty;

import com.example.lms.service.ChatService;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



@Component
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class ChatWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final ChatService chatService;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String userMsg = frame.text();

        /* ChatService → ChatResult 구조로 변경됨 */
        ChatService.ChatResult result = chatService.ask(userMsg);

        String answer    = result.content();  // GPT 응답
        String usedModel = result.modelUsed();

        /* 기본 : 답변 내용만 클라이언트로 */
        ctx.channel().writeAndFlush(new TextWebSocketFrame(answer));

        /* ▼ 필요하면 모델명을 포함한 JSON 형식으로 전송 가능
        String payload = "{\"model\":\"" + usedModel + "\","
                       + "\"content\":\"" + answer.replace("\"","\\\"") + "\"}";
        ctx.channel().writeAndFlush(new TextWebSocketFrame(payload));
        */
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}