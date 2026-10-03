package com.example.lms.service;

import com.example.lms.domain.ChatSession;
import com.example.lms.domain.enums.MemoryProfile;
import com.example.lms.dto.AttachmentDto;
import com.example.lms.repository.*;
import com.example.lms.service.rag.graph.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentGraphAuthorityTest {
    @TempDir Path directory;
    final String id="33333333-3333-3333-3333-333333333333";
    final ChatSession session=new ChatSession("fixture","fixture-owner","ANON");
    final ChatSessionRepository sessions=mock(ChatSessionRepository.class);
    final ChatMessageRepository messages=mock(ChatMessageRepository.class);
    final GeneralGraphSourceAuthority authority=new GeneralGraphSourceAuthority(sessions,messages,new ObjectMapper());
    Path sourcePath;
    GeneralGraphScope scope(){
        session.setId(42L);when(sessions.findByIdForUpdate(42L)).thenReturn(Optional.of(session));
        return authority.bindPolicy(GeneralGraphScope.authorize(session,null,"fixture-owner").orElseThrow(),MemoryProfile.LIGHT).orElseThrow();
    }
    long prepare(AttachmentSourceStore store,GeneralGraphScope scope)throws Exception{
        Path root=directory.resolve("bytes");var storage=new com.example.lms.storage.LocalFileStorageService();
        ReflectionTestUtils.setField(storage,"rootDir",root.toString());
        ReflectionTestUtils.setField(storage,"uploadPublicPrefix","/uploads/");
        ReflectionTestUtils.setField(storage,"maxBytes",1048576L);
        var upload=new org.springframework.mock.web.MockMultipartFile("files","report.md","text/markdown","fixture original".getBytes());
        String locator=storage.save(upload,"chat");sourcePath=root.resolve(locator.substring("/uploads/".length()));
        ReflectionTestUtils.setField(authority,"attachmentStorage",storage);
        String digest=org.apache.commons.codec.digest.DigestUtils.sha256Hex(Files.readAllBytes(sourcePath));
        store.create(new AttachmentDto(id,"report.md",Files.size(sourcePath),"text/markdown",locator),
            scope.ownerNamespace(),"42",digest,System.currentTimeMillis(),System.currentTimeMillis()+60000);
        long revision=store.recordText(id,1,digest,"fixture-v1","[{\"displayName\":\"report.md\",\"documentRole\":\"test_report\",\"locator\":\"L1\",\"text\":\"fixture original\"}]","TEXT_READY").orElseThrow().sourceRevision();
        assertTrue(store.grant(scope,id,revision));ReflectionTestUtils.setField(authority,"attachmentSources",store);return revision;
    }
    @Test void attachmentSourceUsesCurrentPrivateAuthorityAndActualByteIdentity()throws Exception{
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("authority"))){
            var scope=scope();long revision=prepare(db.store,scope);var ref=new KgChunk.SourceRef("attachment:"+id,revision);
            var evidence=authority.source(scope,ref).orElseThrow();
            assertEquals("ATTACHMENT",evidence.sourceRole());assertEquals("DOCUMENT_REPORTED",evidence.assertionType());
            assertEquals("attachment:"+id,evidence.sourceId());assertTrue(evidence.text().contains("report.md"));
            verifyNoInteractions(messages);
            assertTrue(authority.source(scope,new KgChunk.SourceRef("attachment:"+id,revision+1)).isEmpty());
            session.setOwnerKey("foreign");assertTrue(authority.source(scope,ref).isEmpty());session.setOwnerKey("fixture-owner");
            Files.writeString(sourcePath,"fixture modified");
            assertTrue(authority.source(scope,ref).isEmpty(),"an unchanged DB row cannot authorize replaced bytes");
            Files.writeString(sourcePath,"fixture original");db.store.tombstone(id);
            assertTrue(authority.source(scope,ref).isEmpty());
            AtomicInteger writes=new AtomicInteger();
            assertTrue(authority.withCurrentSource(scope,evidence,e->writes.incrementAndGet()).isEmpty());assertEquals(0,writes.get());
        }
    }
    @Test void consentEpochAndExtractionRevisionFenceDelayedGraphWrites()throws Exception{
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("policy"))){
            var scope=scope();long revision=prepare(db.store,scope);var ref=new KgChunk.SourceRef("attachment:"+id,revision);
            var evidence=authority.source(scope,ref).orElseThrow();
            assertTrue(authority.currentReadPolicy(scope));
            var off=authority.bindPolicy(scope,MemoryProfile.OFF).orElseThrow();
            assertFalse(authority.currentReadPolicy(scope));
            var renewed=authority.bindPolicy(off,MemoryProfile.LIGHT).orElseThrow();
            assertTrue(authority.source(scope,ref).isEmpty());assertTrue(authority.source(renewed,ref).isEmpty());
            assertTrue(db.store.grant(renewed,id,revision));assertTrue(authority.source(renewed,ref).isPresent());
            var snapshot=db.store.find(id).orElseThrow();
            db.store.recordText(id,revision,snapshot.contentSha256(),"fixture-v2",snapshot.unitsJson(),"TEXT_READY");
            AtomicInteger writes=new AtomicInteger();
            assertTrue(authority.withCurrentSource(renewed,evidence,e->writes.incrementAndGet()).isEmpty());assertEquals(0,writes.get());
        }
    }
    @Test void attachmentSourceIsTypedPrivateAndCannotMasqueradeAsTranscript(){
        var scope=scope();
        var chunk=assertDoesNotThrow(()->new KgChunk("chunk","42","fixture",List.of(),List.of(),"general",0.5,
            java.time.Instant.now(),"ATTACHMENT","BRAIN_STATE","fixture","brain_state",scope,"attachment:"+id,2));
        assertTrue(chunk.hasPrivateSource());assertFalse(chunk.isPublicManual());assertEquals(scope.indexNamespace(),chunk.indexScopeKey());
        assertEquals(-1,GeneralGraphSourceAuthority.sourceMessageId("attachment:"+id));
        assertFalse(new KgChunk("chunk","42","fixture",List.of(),List.of(),"general",0.5,
            java.time.Instant.now(),"ATTACHMENT","GRAPHDB_MANUAL_LEARNING","fixture","graphdb_manual_learning",null,"attachment:"+id,2).isPublicManual());
    }
}
