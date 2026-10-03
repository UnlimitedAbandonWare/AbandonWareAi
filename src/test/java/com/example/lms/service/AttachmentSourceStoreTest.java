package com.example.lms.service;

import com.example.lms.domain.AttachmentSource;
import com.example.lms.dto.AttachmentDto;
import com.example.lms.repository.AttachmentSourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentSourceStoreTest {
    @TempDir Path directory;
    final String id="11111111-1111-1111-1111-111111111111", owner="a".repeat(64), digest="b".repeat(64);
    final AttachmentDto dto=new AttachmentDto(id,"report.md",17L,"text/markdown","/uploads/chat/fixture.md");
    static final class Database implements AutoCloseable {
        final LocalContainerEntityManagerFactoryBean factory=new LocalContainerEntityManagerFactoryBean();
        final AttachmentSourceStore store;
        Database(Path path){
            factory.setDataSource(new DriverManagerDataSource("jdbc:h2:file:"+path+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE","sa",""));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setManagedTypes(PersistenceManagedTypes.of(AttachmentSource.class.getName()));
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","update","hibernate.show_sql","false"));
            factory.afterPropertiesSet();
            var emf=Objects.requireNonNull(factory.getObject());
            var repository=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(AttachmentSourceRepository.class);
            store=new AttachmentSourceStore(repository,new JpaTransactionManager(emf));
        }
        public void close(){factory.destroy();}
    }
    @Test void sourceIdentityAndExtractionSurviveDatabaseAndStoreRestart(){
        long now=System.currentTimeMillis(),revision;
        try(var db=new Database(directory.resolve("source"))){
            db.store.create(dto,owner,null,digest,now,now+60000);
            assertTrue(db.store.bind(id,owner,"42"));
            assertFalse(db.store.bind(id,"c".repeat(64),"42"));
            assertFalse(db.store.bind(id,owner,"43"));
            var parsed=db.store.recordText(id,1,digest,"fixture-v1","[{\"text\":\"fixture span\"}]","TEXT_READY").orElseThrow();
            revision=parsed.sourceRevision();assertTrue(revision>1);
        }
        try(var db=new Database(directory.resolve("source"))){
            var restored=db.store.find(id).orElseThrow();
            assertEquals(dto,restored.dto());assertEquals(owner,restored.ownerNamespace());assertEquals("42",restored.sessionId());
            assertEquals(revision,restored.sourceRevision());assertEquals(digest,restored.contentSha256());
            assertEquals("[{\"text\":\"fixture span\"}]",restored.unitsJson());
            assertEquals("NOT_INDEXED",restored.graphState());assertEquals(0,restored.consentEpoch());
            assertTrue(db.store.recordText(id,1,digest,"fixture-v1","[]","TEXT_READY").isEmpty(),"old worker cannot replace newer revision");
            assertEquals(revision,db.store.recordText(id,revision,digest,"fixture-v1",restored.unitsJson(),"TEXT_READY").orElseThrow().sourceRevision());
            db.store.tombstone(id);
        }
        try(var db=new Database(directory.resolve("source"))){
            assertTrue(db.store.find(id).isEmpty());assertTrue(db.store.forSession("42").isEmpty());
            assertTrue(db.store.recordText(id,revision,digest,"fixture-v1","[]","TEXT_READY").isEmpty());
        }
    }
    @Test void expiredAndCorruptDigestSourcesCannotBeRevived(){
        long now=System.currentTimeMillis();
        try(var db=new Database(directory.resolve("expired"))){
            db.store.create(dto,owner,"42",digest,now-2000,now-1000);
            assertTrue(db.store.find(id).isEmpty());assertEquals(List.of(id),db.store.expiredIds(now));
            assertFalse(db.store.bind(id,owner,"42"));
            assertTrue(db.store.recordText(id,1,digest,"v1","[]","TEXT_READY").isEmpty());
        }
    }
    @Test void ownedMarkdownUploadReachesPromptDocumentsAfterSessionBinding() throws Exception {
        var storage=new com.example.lms.storage.LocalFileStorageService();
        org.springframework.test.util.ReflectionTestUtils.setField(storage,"rootDir",directory.resolve("uploads").toString());
        org.springframework.test.util.ReflectionTestUtils.setField(storage,"uploadPublicPrefix","/uploads/");
        org.springframework.test.util.ReflectionTestUtils.setField(storage,"maxBytes",1048576L);
        try(var db=new Database(directory.resolve("markdown"))){
            var service=new AttachmentService(storage,new com.example.lms.file.FileIngestionService());
            org.springframework.test.util.ReflectionTestUtils.setField(service,"durableStore",db.store);
            var owner=AttachmentOwnerIdentity.forAnonymous("synthetic-markdown-owner");
            var file=new org.springframework.mock.web.MockMultipartFile("files","A_project_codename.md",
                "text/markdown",("# Synthetic fixture\n코드명은 은하수-7. 첫 시연일은 2026-10-15.\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var resolver=org.mockito.Mockito.mock(com.example.lms.web.ClientOwnerKeyResolver.class);
            org.mockito.Mockito.when(resolver.ownerKey()).thenReturn("synthetic-markdown-owner");
            var uploads=new com.example.lms.api.AttachmentController(service,null,null,null,resolver);
            var operational=new org.springframework.security.authentication.TestingAuthenticationToken(
                "proto-open",null,"ROLE_ADMIN");
            var saved=uploads.upload(List.of(file),null,operational).get(0);
            assertTrue(service.attachToSession("132",List.of(saved.id()),owner));
            var documents=service.asDocumentsForSession(List.of(saved.id()),"132",owner,"코드명과 첫 시연일은?");
            String text=documents.stream().map(dev.langchain4j.data.document.Document::text)
                .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(text.contains("은하수-7"));assertTrue(text.contains("2026-10-15"));
            String prompt = new com.example.lms.prompt.StandardPromptBuilder().build(
                List.of(com.example.lms.prompt.PromptContext.builder().localDocs(documents).build()), "코드명과 첫 시연일은?");
            assertTrue(prompt.contains("은하수-7"));assertTrue(prompt.contains("2026-10-15"));
            assertTrue(prompt.contains("A_project_codename.md"));
            assertTrue(documents.stream().allMatch(d->d.metadata().getString("sourceLocator").startsWith("attachment:")));
            assertTrue(service.asDocumentsForSession(List.of(saved.id()),"133",owner,"코드명은?").isEmpty());
            assertTrue(service.asDocumentsForSession(List.of(saved.id()),"132",
                AttachmentOwnerIdentity.forAnonymous("other-synthetic-owner"),"코드명은?").isEmpty());
        }
    }
}
