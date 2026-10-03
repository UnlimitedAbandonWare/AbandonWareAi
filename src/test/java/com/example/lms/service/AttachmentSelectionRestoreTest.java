package com.example.lms.service;

import com.example.lms.service.rag.graph.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentSelectionRestoreTest {
    @TempDir Path directory;
    @Test void durableSelectionUsesOnlyApprovedCurrentSourcesAndProtectsServerKeys() throws Exception {
        var fixture=new AttachmentGraphAuthorityTest();fixture.directory=directory;
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("selection"))){
            var scope=fixture.scope();long revision=fixture.prepare(db.store,scope);
            var select=GeneralGraphSourceAuthority.class.getMethod("selectedAttachmentIds",GeneralGraphScope.class);
            var remember=GeneralGraphSourceAuthority.class.getMethod("rememberAttachmentSelection",
                GeneralGraphScope.class,List.class,boolean.class);
            assertEquals(List.of(),select.invoke(fixture.authority,scope),"uploaded is not selected");
            var refs=List.of(new KgChunk.SourceRef("attachment:"+fixture.id,revision));
            remember.invoke(fixture.authority,scope,refs,true);
            assertEquals(List.of(fixture.id),select.invoke(fixture.authority,scope));
            var saved=GeneralGraphSourceAuthority.readMetadata(new com.fasterxml.jackson.databind.ObjectMapper(),fixture.session.getSessionMeta());
            var protectedMeta=GeneralGraphSourceAuthority.preserveEpoch(saved,Map.of("attachmentLastUsed",List.of(),"attachmentActive",List.of()));
            assertEquals(saved.get("attachmentLastUsed"),protectedMeta.get("attachmentLastUsed"));
            assertEquals(saved.get("attachmentActive"),protectedMeta.get("attachmentActive"));
            fixture.session.setSessionMeta(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(protectedMeta));
            var restarted=new GeneralGraphSourceAuthority(fixture.sessions,fixture.messages,new com.fasterxml.jackson.databind.ObjectMapper());
            org.springframework.test.util.ReflectionTestUtils.setField(restarted,"attachmentSources",db.store);
            assertEquals(List.of(fixture.id),select.invoke(restarted,scope));
            var row=db.store.find(fixture.id).orElseThrow();
            db.store.recordText(fixture.id,revision,row.contentSha256(),"parser-new",row.unitsJson(),"TEXT_READY");
            assertEquals(List.of(),select.invoke(restarted,scope),"changed revision must not silently restore");
            fixture.session.setOwnerKey("foreign");
            assertEquals(List.of(),select.invoke(restarted,scope));
            db.store.tombstone(fixture.id);
            assertEquals(List.of(),select.invoke(restarted,scope));
        }
    }
}
