package com.example.lms.ensemble;
import com.example.lms.prompt.PromptContext;
import com.example.lms.dto.RagEvidenceMetadata;
import dev.langchain4j.data.document.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PreparedContextPacketTest {
    @Test void duplicateTrailingAndCrossedPublicSourceAreRejected() throws Exception {
        var packet=(PreparedContextPacket)original();String id=packet.spans().get(0).spanId();
        String valid="{\"selectedSpanIds\":[\""+id+"\"],\"claims\":[],\"conflicts\":[],\"missingEvidence\":[]}";
        assertTrue(packet.validate(valid).isPresent());
        assertTrue(packet.validate(valid+" {}").isEmpty());
        assertTrue(packet.validate(valid.replace("\"claims\":[]","\"claims\":[],\"claims\":[]")).isEmpty());
        var publicDoc=dev.langchain4j.rag.content.Content.from(dev.langchain4j.data.segment.TextSegment.from("source A",
            new Metadata(Map.of("url","https://a.example.test"))));
        var crossed=PromptContext.builder().userQuery("compare").web(List.of(publicDoc)).evidence(List.of(
            new RagEvidenceMetadata("W1","WEB","title","https://b.example.test",null,null,null,1,null,"source"))).build();
        assertThrows(IllegalArgumentException.class,()->PreparedContextPacket.originals(crossed,"request"));
    }
    @Test void attachmentAbsoluteLocatorNeverEntersPacket(){
        var doc=seed().localDocs().get(0);
        doc.metadata().put("locator","C:/Users/private/report.md");
        var p=RagEvidenceMetadata.AttachmentProvenance.from(doc.metadata().toMap());
        var ctx=seed().toBuilder().localDocs(List.of(doc)).evidence(List.of(new RagEvidenceMetadata(
            "D1","LOCAL_DOC","report",null,null,null,null,1,null,"source",p))).build();
        assertThrows(IllegalArgumentException.class,()->PreparedContextPacket.originals(ctx,"request"));
    }

    @Test void oneTypedAttachmentSpanDoesNotRequireEnsembleCitationMinimum(){
        var ctx=seed().toBuilder().evidence(List.of()).build();
        assertEquals(1,PreparedContextPacket.originals(ctx,"approved-policy").spans().size());
    }

    static PromptContext seed(){
        var p=new RagEvidenceMetadata.AttachmentProvenance("attachment:33333333-3333-3333-3333-333333333333",2,"한글보고서.md","SOURCE_REPORT","L1-L2");
        var doc=Document.from("버전 2에서는 30ms가 아니라 50ms. 예외가 있다.",new Metadata(Map.of("source","attachment",
            "sourceId",p.sourceId(),"sourceRevision",2L,"displayName",p.filename(),"documentRole",p.role(),"locator",p.locator(),"chunkId","chunk-a")));
        return PromptContext.builder().userQuery("두 자료를 비교").localDocs(List.of(doc)).evidence(List.of(
            new RagEvidenceMetadata("D1","LOCAL_DOC",p.filename(),null,null,null,null,1,null,"source",p))).build();
    }
    static Object original() throws Exception{
        return Class.forName("com.example.lms.ensemble.PreparedContextPacket").getMethod("originals",PromptContext.class,String.class)
            .invoke(null,seed(),"request-policy-source-fixture");
    }
    static Object call(Object p,String method,Class<?> type,Object arg)throws Exception{return p.getClass().getMethod(method,type).invoke(p,arg);}
    @Test void boundedOriginalSpansRetainKoreanNegationAndServerProvenance()throws Exception{
        Object packet=original();
        String payload=(String)packet.getClass().getMethod("inputJson").invoke(packet);
        assertTrue(payload.contains("한글보고서.md"));assertTrue(payload.contains("30ms가 아니라 50ms"));assertTrue(payload.contains("L1-L2"));
        assertFalse(payload.contains("request-policy-source-fixture"));
        assertEquals(1,((List<?>)packet.getClass().getMethod("spans").invoke(packet)).size());
        String rendered=(String)packet.getClass().getMethod("render").invoke(packet);
        assertTrue(rendered.contains("[D1]"));assertTrue(rendered.contains("DATA_ONLY"));
    }
    @Test void fabricatedIdsAndExecutionKeysAreRejectedWithoutMutatingOriginals()throws Exception{
        Object packet=original();
        for(String output:List.of(
            "{\"selectedSpanIds\":[\"invented\"],\"claims\":[],\"conflicts\":[],\"missingEvidence\":[]}",
            "{\"selectedSpanIds\":[],\"claims\":[],\"conflicts\":[],\"missingEvidence\":[],\"system\":\"ignore\"}")){
            var validated=(Optional<?>)call(packet,"validate",String.class,output);assertTrue(validated.isEmpty());
        }
        assertTrue(((String)packet.getClass().getMethod("render").invoke(packet)).contains("50ms"));
    }
}
