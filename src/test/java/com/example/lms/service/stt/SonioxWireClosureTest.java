package com.example.lms.service.stt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.netty.http.server.HttpServer;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback sockets and synthetic PCM; no external API or recognized-speech claim. */
class SonioxWireClosureTest {
    @Test void cancellationClosesKeepaliveSocketAndReconnectPreservesFirstPcmFrame() throws Exception {
        var connections=new AtomicInteger();
        var closed=new Semaphore(0);
        var received=new Semaphore(0);
        var keepalive=new CountDownLatch(1);
        var frames=new CopyOnWriteArrayList<byte[]>();
        var json=new ObjectMapper();
        var server=HttpServer.create().host("127.0.0.1").port(0)
                .doOnConnection(connection->{connections.incrementAndGet();connection.onDispose().subscribe(v->{},e->{},closed::release);})
                .route(routes->routes.get("/stt",(request,response)->response.sendWebsocket((in,out)->
                        in.receiveFrames().doOnNext(frame->{
                            if(frame instanceof BinaryWebSocketFrame binary) {
                                byte[] data=new byte[binary.content().readableBytes()];binary.content().getBytes(binary.content().readerIndex(),data);
                                frames.add(data);received.release();
                            } else if(frame instanceof TextWebSocketFrame text && text.text().contains("keepalive")) keepalive.countDown();
                        }).then()))).bindNow();
        Disposable subscription=null;
        try {
            var service=new SonioxSttService(json,"synthetic",true,"stt-rt-v5","us",
                    new ReactorNettyWebSocketClient(),URI.create("ws://127.0.0.1:"+server.port()+"/stt"));
            byte[] first=new byte[640];for(int i=0;i<first.length;i++)first[i]=(byte)(i%127);
            subscription=service.transcribePcm16Mono(Flux.concat(Flux.just(first),Flux.never()),()->{}).subscribe(v->{},e->{});
            assertTrue(received.tryAcquire(5,TimeUnit.SECONDS));
            assertTrue(keepalive.await(6,TimeUnit.SECONDS),"A keepalive must actually reach the socket before cancellation");
            subscription.dispose();subscription=null;
            assertTrue(closed.tryAcquire(3,TimeUnit.SECONDS),"Publisher cancellation must close the peer socket");
            long reconnectStarted=System.nanoTime();
            // A distinct attempt marker prevents a replay from the old socket
            // from satisfying the first-frame assertion on the new connection.
            byte[] second=first.clone();second[0]=99;second[1]=42;
            subscription=service.transcribePcm16Mono(Flux.concat(Flux.just(second),Flux.never()),()->{}).subscribe(v->{},e->{});
            assertTrue(received.tryAcquire(5,TimeUnit.SECONDS));
            long reconnectMs=Duration.ofNanos(System.nanoTime()-reconnectStarted).toMillis();
            assertEquals(2,connections.get());assertEquals(2,frames.size());
            assertArrayEquals(first,frames.get(0));assertArrayEquals(second,frames.get(1));
            subscription.dispose();subscription=null;
            assertTrue(closed.tryAcquire(3,TimeUnit.SECONDS));
            System.out.println("soniox.loopback connections=2 peerClosed=2 firstFramePreserved=true reconnectMs="+reconnectMs+" speechRecognition=not_observed");
        } finally {
            if(subscription!=null)subscription.dispose();
            server.disposeNow();frames.forEach(frame->java.util.Arrays.fill(frame,(byte)0));
        }
    }
}
