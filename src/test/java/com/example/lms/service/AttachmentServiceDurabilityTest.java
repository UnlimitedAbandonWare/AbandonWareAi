package com.example.lms.service;

import com.example.lms.file.FileIngestionService;
import com.example.lms.storage.LocalFileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentServiceDurabilityTest {

    @Test void preparationSnapshotRechecksOwnerSessionBytesRevisionAndDeletion()throws Exception{
        var storage=storage(directory.resolve("context-bytes"));
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("context-authority"))){
            var active=service(db.store,storage);
            String id=active.saveAll(List.of(new MockMultipartFile("files","report.md","text/markdown",
                "Version 2: not 30ms but 50ms. Exception retained.".getBytes())),"42",owner).get(0).id();
            var docs=active.asDocumentsForSession(List.of(id),"42",owner);
            var context=com.example.lms.prompt.PromptContext.builder().userQuery("compare").localDocs(docs).build();
            var packet=com.example.lms.ensemble.PreparedContextPacket.originals(context,"owned-run");
            assertTrue(active.contextSourcesCurrent(packet,owner,"42"));
            assertFalse(active.contextSourcesCurrent(packet,AttachmentOwnerIdentity.forAnonymous("foreign"),"42"));
            assertFalse(active.contextSourcesCurrent(packet,owner,"43"));
            var snapshot=db.store.find(id).orElseThrow();
            Path bytes=storage.resolveStoredPath(snapshot.dto().url()).orElseThrow();byte[] original=Files.readAllBytes(bytes);
            Files.writeString(bytes,"replaced bytes");
            assertFalse(active.contextSourcesCurrent(packet,owner,"42"));
            Files.write(bytes,original);
            assertTrue(active.contextSourcesCurrent(packet,owner,"42"));
            db.store.recordText(id,snapshot.sourceRevision(),snapshot.contentSha256(),"parser-revised",snapshot.unitsJson(),"TEXT_READY");
            assertFalse(active.contextSourcesCurrent(packet,owner,"42"));
            db.store.tombstone(id);
            assertFalse(active.contextSourcesCurrent(packet,owner,"42"));
        }
    }

    @TempDir Path directory;
    final AttachmentOwnerIdentity owner=AttachmentOwnerIdentity.forAnonymous("fixture-owner");
    AttachmentService service(AttachmentSourceStore store,LocalFileStorageService storage){
        var service=new AttachmentService(storage,new FileIngestionService());
        ReflectionTestUtils.setField(service,"durableStore",store);return service;
    }
    LocalFileStorageService storage(Path root){
        var storage=new LocalFileStorageService();
        ReflectionTestUtils.setField(storage,"rootDir",root.toString());
        ReflectionTestUtils.setField(storage,"uploadPublicPrefix","/uploads/");
        ReflectionTestUtils.setField(storage,"maxBytes",1048576L);return storage;
    }
    @Test void durableCreateFailureRemovesBytesSavedBeforeTheFailedRow(){
        var storage=mock(LocalFileStorageService.class);var store=mock(AttachmentSourceStore.class);
        when(storage.save(any(),eq("chat"))).thenReturn("/uploads/chat/failed.md");
        doThrow(new IllegalStateException("fixture_db_unavailable")).when(store).create(any(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        assertThrows(IllegalStateException.class,()->service(store,storage).saveAll(
            List.of(new MockMultipartFile("files","failed.md","text/markdown","fixture".getBytes())),"42",owner));
        verify(storage).delete("/uploads/chat/failed.md");
    }
    @Test void configuredStorageRootSurvivesRestartAndAuthorizedPhysicalDeletion()throws Exception{
        Path root=directory.resolve("configured-upload");String id;
        var upload=new MockMultipartFile("files","report.md","text/markdown","fixture source".getBytes());
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("real-storage"))){
            var active=service(db.store,storage(root));id=active.saveAll(List.of(upload),"42",owner).get(0).id();
            assertFalse(active.asDocumentsForSession(List.of(id),"42",owner).isEmpty());
        }
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("real-storage"))){
            var restartedStorage=storage(root);var restored=service(db.store,restartedStorage);
            assertFalse(restored.asDocumentsForSession(List.of(id),"42",owner).isEmpty());
            assertTrue(restored.deleteForSession(id,"42",owner));
            try(var files=Files.walk(root)){assertEquals(0,files.filter(Files::isRegularFile).count());}
        }
    }
    @Test void uploadExtractionAndOwnerBindingSurviveRestart()throws Exception{
        var upload=new MockMultipartFile("files","report.md","text/markdown","# Result\nA fixture source span.\n".getBytes());
        var storage=storage(directory.resolve("upload-bytes"));
        String id;long revision;
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("upload"))){
            var first=service(db.store,storage);id=first.saveAll(List.of(upload),"42",owner).get(0).id();
            assertTrue(db.store.find(id).isPresent(),"upload must create durable owner-bound metadata");
            var docs=first.asDocumentsForSession(List.of(id),"42",owner);assertFalse(docs.isEmpty());
            for(var document:docs){
                assertEquals("TOKEN_COUNT_UNVERIFIED",document.metadata().getString("semanticExtractionReason"));
                assertEquals(0,document.metadata().toMap().get("semanticAttempts"));
            }
            revision=docs.get(0).metadata().getLong("sourceRevision");
            assertEquals(revision,db.store.find(id).orElseThrow().sourceRevision());
        }
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("upload"))){
            var restored=service(db.store,storage);
            assertEquals("report.md",restored.find(id,owner).orElseThrow().name());
            assertTrue(restored.find(id,AttachmentOwnerIdentity.forAnonymous("foreign")).isEmpty());
            assertEquals(List.of(id),restored.findIdsBySession("42",16,owner));
            var docs=restored.asDocumentsForSession(List.of(id),"42",owner);assertFalse(docs.isEmpty());
            for(var document:docs){
                assertEquals("TOKEN_COUNT_UNVERIFIED",document.metadata().getString("semanticExtractionReason"));
                assertEquals(0,document.metadata().toMap().get("semanticAttempts"));
            }
            assertEquals(revision,docs.get(0).metadata().getLong("sourceRevision"));
            assertTrue(restored.asDocumentsForSession(List.of(id),"43",owner).isEmpty());
            assertFalse(restored.attachToSession("43",List.of(id),owner));
            assertTrue(restored.deleteForSession(id,"42",owner));
            assertTrue(db.store.find(id).isEmpty());
            assertTrue(restored.asDocumentsForSession(List.of(id),"42",owner).isEmpty());
        }
    }
    @Test void durableDeletionAndChangedBytesInvalidateCachedService()throws Exception{
        Path root=directory.resolve("revocation-bytes");var storage=storage(root);
        var upload=new MockMultipartFile("files","source.md","text/markdown","fixture original".getBytes());
        String locator=storage.save(upload,"chat");Path bytes=root.resolve(locator.substring("/uploads/".length()));
        String id="22222222-2222-2222-2222-222222222222";
        var dto=new com.example.lms.dto.AttachmentDto(id,"source.md",Files.size(bytes),"text/markdown",locator);
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("revocation"))){
            db.store.create(dto,owner.hash(),"42",org.apache.commons.codec.digest.DigestUtils.sha256Hex(Files.readAllBytes(bytes)),
                System.currentTimeMillis(),System.currentTimeMillis()+60000);
            var active=service(db.store,storage);
            assertTrue(active.find(id,owner).isPresent(),"hydrate metadata from the current relational authority");
            assertFalse(active.asDocumentsForSession(List.of(id),"42",owner).isEmpty());
            Files.writeString(bytes,"fixture modified");
            assertTrue(active.asDocumentsForSession(List.of(id),"42",owner).isEmpty());
            db.store.tombstone(id);
            assertTrue(active.find(id,owner).isEmpty());assertTrue(active.findIdsBySession("42",16,owner).isEmpty());
        }
    }
}
