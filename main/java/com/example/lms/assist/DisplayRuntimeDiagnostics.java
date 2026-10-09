package com.example.lms.assist;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** Bounded, text-free evidence of HTTP entry and client execution; never hardware visibility. */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+40)
@ConditionalOnProperty(name={"conversate.enabled","conversate.display.enabled"},havingValue="true")
public class DisplayRuntimeDiagnostics extends OncePerRequestFilter {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DisplayRuntimeDiagnostics.class);
    private static final Set<String> ASSETS=Set.of("/assets/display/meta/index.html","/assets/display/meta/receiver.js","/assets/display/meta/boot.js","/assets/display/meta/styles.css");
    private static final Set<String> EVENTS=Set.of("html_received","init_started","receiver_started","response_received","dom_updated","script_error","resource_error","async_error","transport_error","page_hidden","page_visible","capture_error");
    private static final Set<String> CAPTURE_STAGES=Set.of("precheck","mic_open","audio_graph","server_begin","transport");
    public record ClientEvent(String runtimeId,String event,long sequence,String code,boolean visible,String stage,Integer httpStatus,Long epoch,Boolean producerMismatch,Long lastFrameAgeMs,String errorCode){
        public ClientEvent(String runtimeId,String event,long sequence,String code,boolean visible){this(runtimeId,event,sequence,code,visible,null,null,null,null,null,null);}
        @com.fasterxml.jackson.annotation.JsonAnySetter public void rejectUnknown(String name,Object value){throw new IllegalArgumentException("invalid_display_diagnostic");}
    }
    private final Clock clock;
    private final ArrayDeque<Map<String,Object>> events=new ArrayDeque<>();
    private long windowAt;private int count;
    public DisplayRuntimeDiagnostics(){this(Clock.systemUTC());}
    DisplayRuntimeDiagnostics(Clock clock){this.clock=clock;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){String p=path(request);return !ASSETS.contains(p)&&!p.startsWith("/api/assist/display/relay/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        try{chain.doFilter(request,response);}finally{
            String p=path(request);
            if(ASSETS.contains(p)||response.getStatus()>=400)record(request,ASSETS.contains(p)?"http_asset":"http_error",0,"http_"+response.getStatus(),false);
        }
    }
    synchronized boolean client(HttpServletRequest request,ClientEvent event){
        if(event==null||event.runtimeId()==null||!event.runtimeId().matches("[a-f0-9]{32}")||!Objects.equals(event.runtimeId(),request.getHeader("X-Display-Runtime"))||event.event()==null||!EVENTS.contains(event.event())||event.sequence()<0||event.sequence()>9007199254740991L||event.code()==null||!event.code().matches("none|http_[1-5][0-9]{2}|network|timeout|contract|initialization|unsupported"))return false;
        if(event.stage()!=null&&!CAPTURE_STAGES.contains(event.stage())||event.httpStatus()!=null&&event.httpStatus()!=0&&(event.httpStatus()<100||event.httpStatus()>599)||event.epoch()!=null&&(event.epoch()<0||event.epoch()>9007199254740991L)||event.lastFrameAgeMs()!=null&&(event.lastFrameAgeMs()<0||event.lastFrameAgeMs()>600000)||event.errorCode()!=null&&!event.errorCode().matches("[a-z_][a-z0-9_]{0,63}"))return false;
        if(event.event().equals("capture_error")&&(event.stage()==null||event.errorCode()==null))return false;
        record(request,event.event(),event.sequence(),event.code(),event.visible(),event);return true;
    }
    private void record(HttpServletRequest request,String event,long sequence,String code,boolean visible){record(request,event,sequence,code,visible,null);}
    private synchronized void record(HttpServletRequest request,String event,long sequence,String code,boolean visible,ClientEvent capture){
        long now=clock.millis();if(now-windowAt>=60000){windowAt=now;count=0;}if(count++>=180)return;
        String runtime=request.getHeader("X-Display-Runtime");if(runtime==null||!runtime.matches("[a-f0-9]{32}"))runtime="not_observed";
        var row=new LinkedHashMap<String,Object>();row.put("at",now);row.put("event",event);row.put("stage",switch(event){case "html_received"->"HTML_RECEIVED";case "init_started"->"JS_START";case "response_received"->"POLL_OK";case "dom_updated"->"DOM_RENDERED";default->event;});row.put("pathname",safePath(path(request)));row.put("runtimeId",runtime);row.put("userAgent",userAgent(request.getHeader("User-Agent")));row.put("sequence",sequence);row.put("code",code);row.put("visible",visible);row.put("channel",request.getHeader("X-Display-Test-Channel")==null?"live":"test");
        if(capture!=null&&event.equals("capture_error")){
            row.put("stage",capture.stage());row.put("errorCode",capture.errorCode());
            if(capture.httpStatus()!=null)row.put("httpStatus",capture.httpStatus());if(capture.epoch()!=null)row.put("epoch",capture.epoch());
            if(capture.producerMismatch()!=null)row.put("producerMismatch",capture.producerMismatch());if(capture.lastFrameAgeMs()!=null)row.put("lastFrameAgeMs",capture.lastFrameAgeMs());
        }
        if(events.size()>=128)events.removeFirst();events.addLast(Map.copyOf(row));
        LOG.info("display.runtime stage={} event={} at={} path={} runtime={} ua={} seq={} code={} visible={}",row.get("stage"),event,now,row.get("pathname"),runtime,row.get("userAgent"),sequence,code,visible);
        if(event.equals("capture_error"))LOG.info("display.capture stage={} httpStatus={} epoch={} producerMismatch={} lastFrameAgeMs={} errorCode={}",row.get("stage"),row.get("httpStatus"),row.get("epoch"),row.get("producerMismatch"),row.get("lastFrameAgeMs"),row.get("errorCode"));
    }
    synchronized Map<String,Object> snapshot(){return Map.of("transport","HTTPS_POLL","hardwareRendered","not_observed","ackMeaning","dom_callback_only","events",List.copyOf(events));}
    static String userAgent(String raw){
        if(raw==null)return "not_observed";String family="other";for(String name:List.of("SamsungBrowser","Chrome","Firefox","Version")){var matcher=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name)+"/([0-9]{1,3})").matcher(raw);if(matcher.find()){family=name+"/"+matcher.group(1);break;}}
        return family+(raw.contains("Android")?";Android":"")+(raw.contains("; wv")?";WebView":"")+(raw.contains("MRBD")?";MRBD-claimed":"");
    }
    private static String path(HttpServletRequest request){String p=request.getRequestURI();return p.substring(Math.min(p.length(),request.getContextPath().length()));}
    private static String safePath(String path){return ASSETS.contains(path)?path:path.matches("/api/assist/display/relay/(poll|ack|diagnostics|test|settings)")?path:"/api/assist/display/relay/other";}
}
