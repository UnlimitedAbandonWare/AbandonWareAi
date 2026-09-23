package com.example.lms.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.io.File;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Separate JVMs, real shared database and loopback HTTP; inference remains a counted fixture. */
class JdbcJobProcessRecoveryTest {
    @TempDir Path directory;
    @Test void forceKilledApplicationAndTwoJvmWorkersKeepOneDurableResult() throws Exception {
        Class<?> serverType=Class.forName("org.h2.tools.Server");
        Object dbServer=serverType.getMethod("createTcpServer",String[].class).invoke(null,(Object)new String[]{"-tcpPort","0","-tcpDaemon","-ifNotExists","-baseDir",directory.toString()});
        serverType.getMethod("start").invoke(dbServer);
        int port=(Integer)serverType.getMethod("getPort").invoke(dbServer);
        String url="jdbc:h2:tcp://127.0.0.1:"+port+"/jobs;MODE=MySQL";
        var children=new ArrayList<Process>();
        try {
            var ds=new DriverManagerDataSource(url,"sa","");var jdbc=new JdbcTemplate(ds);
            new ResourceDatabasePopulator(new FileSystemResource("main/resources/db/migration/V20260912__durable_jobs.sql")).execute(ds);
            jdbc.execute("CREATE TABLE fixture_calls (calls INT NOT NULL)");jdbc.update("INSERT INTO fixture_calls VALUES(0)");
            Node a=start("a",url,children),b=start("b",url,children);
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String id=send(http,a.port(),"/enqueue",true);
            a.process().destroyForcibly();assertTrue(a.process().waitFor(10,TimeUnit.SECONDS));
            assertTrue(send(http,b.port(),"/jobs/"+id,false).contains("PENDING"));
            Node c=start("c",url,children);
            var first=http.sendAsync(request(b.port(),"/run",true),HttpResponse.BodyHandlers.ofString());
            var second=http.sendAsync(request(c.port(),"/run",true),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,first.get(10,TimeUnit.SECONDS).statusCode());assertEquals(200,second.get(10,TimeUnit.SECONDS).statusCode());
            var mapper=new ObjectMapper();var result=mapper.readTree(send(http,b.port(),"/jobs/"+id,false));
            assertEquals("SUCCEEDED",result.path("state").asText());assertEquals("persisted-fixture",result.path("result").asText());
            assertEquals(result,mapper.readTree(send(http,c.port(),"/jobs/"+id,false)));
            b.process().destroyForcibly();assertTrue(b.process().waitFor(10,TimeUnit.SECONDS));
            assertEquals(result,mapper.readTree(send(http,c.port(),"/jobs/"+id,false)));
            assertEquals(1,jdbc.queryForObject("SELECT calls FROM fixture_calls",Integer.class));
            System.out.println("durableProcessFixture applicationJvmCount=3 forcedKills=2 inferenceCalls=1 sharedResult=true");
        }finally{
            for(Process child:children){if(child.isAlive())child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}
            serverType.getMethod("stop").invoke(dbServer);
        }
    }
    private Node start(String name,String url,List<Process> children) throws Exception {
        var entries=new LinkedHashSet<String>();
        for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())
            if(loader instanceof java.net.URLClassLoader urls)
                for(URL entry:urls.getURLs())if("file".equals(entry.getProtocol()))entries.add(Path.of(entry.toURI()).toString());
        entries.addAll(Arrays.asList(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        Path args=directory.resolve(name+".args"),ready=directory.resolve(name+".port");
        String classpath=String.join(File.pathSeparator,entries).replace("\\","\\\\").replace("\"","\\\"");
        Files.writeString(args,"-cp\n\""+classpath+"\"\n",StandardCharsets.UTF_8);
        Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+args,
                Fixture.class.getName(),url,ready.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        children.add(process);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!Files.exists(ready)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
        assertTrue(Files.exists(ready),"fixture_start_failed; readiness marker absent");
        return new Node(process,Integer.parseInt(Files.readString(ready)));
    }
    private static HttpRequest request(int port,String path,boolean post){
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        return post?builder.POST(HttpRequest.BodyPublishers.noBody()).build():builder.GET().build();
    }
    private static String send(HttpClient client,int port,String path,boolean post) throws Exception {
        var response=client.send(request(port,path,post),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());return response.body();
    }
    private record Node(Process process,int port){}
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            var ds=new DriverManagerDataSource(args[0],"sa","");var mapper=new ObjectMapper();
            var store=new JdbcJobService(ds,mapper,Clock.systemUTC());
            store.registerHandler("task_ask",input->{new JdbcTemplate(ds).update("UPDATE fixture_calls SET calls=calls+1");return "persisted-fixture";});
            var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                try {
                    String path=exchange.getRequestURI().getPath(),body;
                    if(path.equals("/enqueue"))body=store.enqueue("task_ask",Map.of("message","fixture"),Map.of("ownerHash","fixture-owner"),null);
                    else if(path.equals("/run")){store.runPendingOnce();body="ok";}
                    else {String id=path.substring("/jobs/".length());body=mapper.writeValueAsString(Map.of("state",store.status(id),"result",store.result(id,"fixture-owner").orElse("")));}
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Exception failure){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
            });server.start();Files.writeString(Path.of(args[1]),Integer.toString(server.getAddress().getPort()));
            new CountDownLatch(1).await();
        }
    }
}
