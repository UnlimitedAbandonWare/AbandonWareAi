package com.example.lms.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScoringRunnerJsonContractTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    void write(String path,String text)throws Exception{
        Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);
    }
    String run(String... options)throws Exception{
        Path out=root.resolve("verification/report.out");
        var args=new ArrayList<>(List.of("--root",root.toString(),"--output",out.toString()));
        args.addAll(Arrays.asList(options));
        var stdout=new ByteArrayOutputStream();var original=System.out;
        try(var capture=new PrintStream(stdout,true,StandardCharsets.UTF_8)){
            System.setOut(capture);ScoringRunner.main(args.toArray(String[]::new));
        }finally{System.setOut(original);}
        String file=Files.readString(out);
        assertEquals(file.strip(),stdout.toString(StandardCharsets.UTF_8).strip());
        return file;
    }
    JsonNode report()throws Exception{return json.readTree(run("--format=json"));}
    @Test void jsonProjectsExistingScoresWithoutWeakeningEvidence()throws Exception{
        write("main/java/Synthetic.java","class Synthetic {}");
        var expected=ScoringRunner.score(root);var actual=report();
        var fields=new HashSet<String>();actual.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("schemaVersion","sourceIdentityHash","uiAssetHash","executionObserved",
                "evidenceProvenance","totalScore","checks","structuralPenalty"),fields);
        assertEquals(1,actual.path("schemaVersion").asInt());
        assertEquals(expected.sourceIdentityHash(),actual.path("sourceIdentityHash").asText());
        assertTrue(actual.path("uiAssetHash").asText().matches("[a-f0-9]{64}"));
        assertFalse(actual.path("executionObserved").asBoolean(true));
        assertEquals("local-artifact-consistency",actual.path("evidenceProvenance").asText());
        assertEquals(expected.total(),actual.path("totalScore").asInt());
        assertEquals(expected.structuralPenaltyPoints(),actual.path("structuralPenalty").asInt());
        assertEquals(json.valueToTree(expected.checks()),actual.path("checks"));
    }
    @Test void cssAndDisplayOnlyEditsChangeUiIdentityNotScoreOrSource()throws Exception{
        write("main/java/Synthetic.java","class Synthetic {}");
        write("main/resources/static/css/chat-style.css","body{color:black}");
        write("main/resources/static/assets/display/diagnostics.js","const status=1;");
        var before=report();
        for(String path:List.of("main/resources/static/css/chat-style.css","main/resources/static/assets/display/diagnostics.js")){
            write(path,"/* changed "+path+" */");
            var after=report();
            assertNotEquals(before.path("uiAssetHash"),after.path("uiAssetHash"));
            for(String field:List.of("sourceIdentityHash","totalScore","checks","structuralPenalty"))
                assertEquals(before.path(field),after.path(field),field);
            before=after;
        }
        write("main/java/Synthetic.java","class Synthetic { int value; }");
        var sourceChanged=report();
        assertNotEquals(before.path("sourceIdentityHash"),sourceChanged.path("sourceIdentityHash"));
        assertEquals(before.path("uiAssetHash"),sourceChanged.path("uiAssetHash"));
    }
    @Test void hashIsDeterministicAndIncludesAssetPaths()throws Exception{
        String a="main/resources/static/css/a.css",b="main/resources/static/css/b.css";
        write(b,"b");write(a,"a");var first=report();
        Files.delete(root.resolve(a));Files.delete(root.resolve(b));
        write(a,"a");write(b,"b");
        assertEquals(first,report());
        Files.move(root.resolve(b),root.resolve("main/resources/static/css/c.css"));
        assertNotEquals(first.path("uiAssetHash"),report().path("uiAssetHash"));
    }
    @Test void defaultAndExplicitTextKeepLegacyBytesAndUnknownFormatFails()throws Exception{
        var expected=ScoringRunner.score(root).render();
        assertEquals(expected,run());assertEquals(expected,run("--format=text"));
        assertEquals(report(),json.readTree(run("--format","json")));
        assertThrows(IllegalArgumentException.class,()->run("--format=invalid"));
    }
}
