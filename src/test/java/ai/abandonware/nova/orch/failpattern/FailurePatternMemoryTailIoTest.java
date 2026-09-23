package ai.abandonware.nova.orch.failpattern;

import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailurePatternMemoryTailIoTest {
    @TempDir Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void equalTailsReadComparableBytesDespiteLargeDiscardedPrefix() throws Exception {
        String tail = rows(100_000, 1_000, "\n", true);
        Path small = write("small", rows(0, 10, "\n", true) + tail);
        Path large = write("large", rows(0, 100_000, "\n", true) + tail);
        // Warm only the existing synchronous read path; fixture generation is outside measurement.
        for (int i = 0; i < 3; i++) {
            assertEquals(1_000, read(small).size());
            assertEquals(1_000, read(large).size());
        }
        ReadProof first = measuredRead(small);
        ReadProof second = measuredRead(large);
        assertEquals(first.rows(), second.rows());
        assertEquals(1_000, second.rows().size());
        assertEquals(100_000, second.rows().get(0).get("row"));
        assertTrue(first.bytes() > 0 && second.bytes() > 0, "JFR must observe actual target file reads");
        System.out.println("tailIo smallBytes=" + first.bytes() + " largeBytes=" + second.bytes()
                + " largeFileBytes=" + Files.size(large));
        assertTrue(first.allocatedBytes() > 0 && second.allocatedBytes() > 0, "thread allocation counters must be observed");
        assertTrue(first.elapsedNanos() > 0 && second.elapsedNanos() > 0, "synchronous elapsed samples must be observed");
        System.out.println("R11_READ_METRICS smallAllocatedBytes=" + first.allocatedBytes()
                + " largeAllocatedBytes=" + second.allocatedBytes()
                + " smallElapsedNanos=" + first.elapsedNanos() + " largeElapsedNanos=" + second.elapsedNanos()
                + " smallReadBytes=" + first.bytes() + " largeReadBytes=" + second.bytes()
                + " smallFileBytes=" + Files.size(small) + " largeFileBytes=" + Files.size(large)
                + " selectedRows=" + second.rows().size());
        assertTrue(second.bytes() <= first.bytes() + 16_384,
                "discarded prefix must not determine I/O: small=" + first.bytes() + " large=" + second.bytes());
        assertTrue(second.bytes() <= 2L * tail.getBytes(StandardCharsets.UTF_8).length + 16_384);
    }

    @ParameterizedTest
    @CsvSource({"LF,true", "LF,false", "CRLF,true", "CRLF,false", "CR,true", "CR,false"})
    void physicalLineWindowPreservesBlanksMalformedRowsAndFinalLine(String ending, boolean terminal) throws Exception {
        String separator = switch (ending) { case "CRLF" -> "\r\n"; case "CR" -> "\r"; default -> "\n"; };
        List<String> lines = new ArrayList<>();
        for (int i=0; i<1_007; i++) lines.add("{\"row\":"+i+",\"text\":\"한글🧪\"}");
        lines.set(25, "");
        lines.set(26, "   ");
        lines.set(27, "not-json");
        String body = String.join(separator, lines) + (terminal ? separator : "");
        List<Map<String,Object>> retained = read(write("physical", body));
        assertEquals(expected(body), retained);
        assertEquals(997, retained.size(), "blank and malformed lines consume physical-line slots");
        assertEquals(7, retained.get(0).get("row"));
        assertEquals(1_006, retained.get(retained.size()-1).get("row"));
    }

    @Test
    void longUtf8LineAndBlockSplitCrLfPreserveWholeRows() throws Exception {
        String last = "{\"row\":1001,\"text\":\"" + "한글🧪".repeat(1_100) + "\"}";
        String body = rows(0, 1_001, "\r\n", true) + last;
        assertEquals(expected(body), read(write("long", body)));
        // Place a CR/LF pair on either side of an 8192-byte backward block boundary.
        String padding = "x".repeat(8_191 - "{\"text\":\"\"}\r\n".getBytes(StandardCharsets.UTF_8).length);
        body = rows(0, 1_005, "\r\n", true) + "{\"text\":\"" + padding + "\"}\r\n";
        byte[] boundaryBytes = body.getBytes(StandardCharsets.UTF_8);
        assertEquals('\n', boundaryBytes[boundaryBytes.length - 8_192]);
        assertEquals('\r', boundaryBytes[boundaryBytes.length - 8_193]);
        assertEquals(expected(body), read(write("boundary", body)));
    }

    @Test
    void malformedUtf8InsideSelectedTailStillFailsSoft() throws Exception {
        Path p=write("bad-tail", rows(0, 1_005, "\n", true));
        Files.write(p, new byte[]{(byte)0xC3, (byte)0x28}, java.nio.file.StandardOpenOption.APPEND);
        assertTrue(read(p).isEmpty());
    }

    @Test
    void discardedPrefixIsNotDecodedAsPartOfSelectedTail() throws Exception {
        Path p=tempDir.resolve("bad-prefix.jsonl");
        byte[] tail=rows(0, 1_000, "\n", true).getBytes(StandardCharsets.UTF_8);
        byte[] all=new byte[tail.length+3];
        all[0]=(byte)0xC3; all[1]=(byte)0x28; all[2]='\n';
        System.arraycopy(tail, 0, all, 3, tail.length);
        Files.write(p, all);
        assertEquals(1_000, read(p).size(), "only the selected suffix participates in decoding");
    }

    @Test
    void missingEmptyBlankAndUnreadableInputsStayEmpty() throws Exception {
        assertTrue(read(tempDir.resolve("absent.jsonl")).isEmpty());
        assertTrue(read(write("empty", "")).isEmpty());
        assertTrue(read(write("blank", "\r\n\n\r")).isEmpty());
        assertTrue(read(Files.createDirectory(tempDir.resolve("directory.jsonl"))).isEmpty());
    }

    @Test
    void publicRecallScoresOnlySelectedTailAndCanReadAfterAppend() throws Exception {
        Path p=write("recall", "{\"kind\":\"wanted\",\"patchAction\":\"old\"}\n" + rows(0,1_000,"\n",true));
        FailurePatternMemoryService service=service(p);
        assertEquals(0, service.recall(Map.of("kind","wanted")).get("matchCount"));
        Files.writeString(p,"{\"kind\":\"wanted\",\"patchAction\":\"latest\"}\n",java.nio.file.StandardOpenOption.APPEND);
        Map<String,Object> result=service.recall(Map.of("kind","wanted"));
        assertEquals(1,result.get("matchCount"));
        assertEquals("latest",result.get("recommendedAction"));
    }

    private ReadProof measuredRead(Path p) throws Exception {
        Path recordingPath=tempDir.resolve(p.getFileName()+".jfr");
        List<Map<String,Object>> result;
        com.sun.management.ThreadMXBean allocation = java.lang.management.ManagementFactory
                .getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        assertNotNull(allocation, "current JVM must expose the thread allocation measurement surface");
        assertTrue(allocation.isThreadAllocatedMemorySupported() && allocation.isThreadAllocatedMemoryEnabled(),
                "thread allocation evidence must be available without changing JVM policy");
        long threadId = Thread.currentThread().getId();
        long allocatedBytes;
        long elapsedNanos;
        try(Recording recording=new Recording()) {
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace();
            recording.start();
            long allocatedBefore = allocation.getThreadAllocatedBytes(threadId);
            long started = System.nanoTime();
            result=read(p);
            elapsedNanos = System.nanoTime() - started;
            allocatedBytes = allocation.getThreadAllocatedBytes(threadId) - allocatedBefore;
            recording.stop();
            recording.dump(recordingPath);
        }
        long bytes=0;
        try(RecordingFile events=new RecordingFile(recordingPath)) {
            while(events.hasMoreEvents()) {
                var event=events.readEvent();
                if(event.getEventType().getName().equals("jdk.FileRead")
                        && Path.of(event.getString("path")).toAbsolutePath().normalize().equals(p.toAbsolutePath().normalize()))
                    bytes+=Math.max(0,event.getLong("bytesRead"));
            }
        }
        return new ReadProof(result,bytes,allocatedBytes,elapsedNanos);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> read(Path p) throws Exception {
        Method method=FailurePatternMemoryService.class.getDeclaredMethod("readRows");
        method.setAccessible(true);
        return (List<Map<String,Object>>)method.invoke(service(p));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> expected(String body) throws Exception {
        List<String> physical=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new StringReader(body))) {
            for(String line; (line=reader.readLine())!=null;) physical.add(line);
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(String line:physical.subList(Math.max(0,physical.size()-1_000),physical.size())) {
            if(line.isBlank()) continue;
            try { result.add(mapper.readValue(line,Map.class)); } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        }
        return result;
    }

    private FailurePatternMemoryService service(Path p) {
        return new FailurePatternMemoryService(mapper,new ToolManifestCatalog(),tempDir,p);
    }
    private Path write(String name,String body) throws Exception {
        return Files.writeString(tempDir.resolve(name+".jsonl"),body,StandardCharsets.UTF_8);
    }
    private static String rows(int start,int count,String separator,boolean terminal) {
        StringBuilder result=new StringBuilder();
        for(int i=start;i<start+count;i++) {
            if(i>start) result.append(separator);
            result.append("{\"row\":").append(i).append('}');
        }
        if(terminal && count>0) result.append(separator);
        return result.toString();
    }
    private record ReadProof(List<Map<String,Object>> rows,long bytes,long allocatedBytes,long elapsedNanos) { }
}
