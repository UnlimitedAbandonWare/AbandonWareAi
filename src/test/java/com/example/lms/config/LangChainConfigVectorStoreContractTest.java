package com.example.lms.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChainConfigVectorStoreContractTest {

    @Test
    void missingVectorStoreDoesNotRequestUnavailablePineconeAdapter() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/config/LangChainConfig.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("@ConditionalOnProperty(name = \"vector.store\", havingValue = \"pinecone\", matchIfMissing = true)"));
        assertTrue(source.contains("@ConditionalOnProperty(name = \"vector.store\", havingValue = \"pinecone\", matchIfMissing = false)"));
        assertTrue(source.contains("@Value(\"${vector.store:memory}\") String vectorStoreChoice"));
    }

    @Test
    void primaryFacadeReceiptCarriesActualNamespaceThroughFederation() throws Exception {
        var writer=org.mockito.Mockito.mock(com.example.lms.service.vector.UpstashVectorStoreAdapter.class);
        org.mockito.Mockito.when(writer.namespace()).thenReturn("fixture-physical");
        var facade=facade(writer);
        var federation=federation(facade);
        try {
            var receipt=receipt(federation,"requested");
            assertTrue(receipt.durable());
            var target=receipt.targets().keySet().iterator().next();
            org.junit.jupiter.api.Assertions.assertEquals("fixture-physical",target.namespace());
            org.junit.jupiter.api.Assertions.assertEquals("requested",target.id());
        } finally { org.springframework.test.util.ReflectionTestUtils.invokeMethod(federation,"shutdownPool"); }
    }
    @Test
    void generatedPrimaryIdsReplaceRequestedIdsInTheReceipt() throws Exception {
        var writer=org.mockito.Mockito.mock(com.example.lms.service.vector.UpstashVectorStoreAdapter.class);
        org.mockito.Mockito.when(writer.namespace()).thenReturn("fixture-generated");
        org.mockito.Mockito.doThrow(new dev.langchain4j.exception.UnsupportedFeatureException("synthetic"))
                .when(writer).addAll(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.when(writer.addAll(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.List.of("actual-generated"));
        var receipt=receipt(facade(writer),"requested");
        assertTrue(receipt.durable());
        var target=receipt.targets().keySet().iterator().next();
        org.junit.jupiter.api.Assertions.assertEquals("actual-generated",target.id());
        org.junit.jupiter.api.Assertions.assertEquals("fixture-generated",target.namespace());
    }
    @Test
    void missingGeneratedIdCannotConfirmAnAlreadyAttemptedWrite() throws Exception {
        var writer=org.mockito.Mockito.mock(com.example.lms.service.vector.UpstashVectorStoreAdapter.class);
        org.mockito.Mockito.doThrow(new dev.langchain4j.exception.UnsupportedFeatureException("synthetic"))
                .when(writer).addAll(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.when(writer.addAll(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList())).thenReturn(java.util.List.of());
        var receipt=receipt(facade(writer),"requested");
        assertFalse(receipt.durable());
        org.junit.jupiter.api.Assertions.assertEquals("UNKNOWN_COMMIT",receipt.reasonCode());
    }
    @Test
    void timeoutInThePrimaryFacadeSurvivesTheFederatedBoundary() throws Exception {
        var writer=org.mockito.Mockito.mock(com.example.lms.service.vector.UpstashVectorStoreAdapter.class);
        org.mockito.Mockito.doAnswer(a -> { throw new java.net.SocketTimeoutException("synthetic"); })
                .when(writer).addAll(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList());
        var federation=federation(facade(writer));
        try {
            var receipt=receipt(federation,"requested");
            assertFalse(receipt.durable());
            org.junit.jupiter.api.Assertions.assertEquals("UNKNOWN_COMMIT",receipt.reasonCode());
        } finally { org.springframework.test.util.ReflectionTestUtils.invokeMethod(federation,"shutdownPool"); }
    }
    @Test
    void fingerprintWrappedPineconeWriterKeepsItsPhysicalNamespace() throws Exception {
        var writer=org.mockito.Mockito.mock(com.example.lms.service.vector.PineconeVectorStoreAdapter.class);
        org.mockito.Mockito.when(writer.namespace()).thenReturn("fixture-wrapped");
        var wrapped=new com.example.lms.vector.FingerprintAwareEmbeddingStore(writer,new com.example.lms.vector.EmbeddingFingerprint());
        var receipt=receipt(facade(wrapped),"requested");
        assertTrue(receipt.durable());
        org.junit.jupiter.api.Assertions.assertEquals("fixture-wrapped",receipt.targets().keySet().iterator().next().namespace());
        org.junit.jupiter.api.Assertions.assertEquals("pinecone",receipt.targets().keySet().iterator().next().writerId());
    }
    @Test
    void optionalMirrorFailureCannotInvalidatePrimaryReceipt() throws Exception {
        var writer=org.mockito.Mockito.mock(com.example.lms.service.vector.PineconeVectorStoreAdapter.class);
        org.mockito.Mockito.when(writer.namespace()).thenReturn("fixture-primary");
        var mirror=org.mockito.Mockito.mock(com.example.lms.service.vector.UpstashVectorStoreAdapter.class);
        org.mockito.Mockito.when(mirror.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(mirror.isWriteEnabled()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic mirror failure")).when(mirror).add(
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var receipt=receipt(facade(writer,mirror,"pinecone"),"requested");
        assertTrue(receipt.durable()); org.junit.jupiter.api.Assertions.assertEquals(1,receipt.targets().size());
        org.junit.jupiter.api.Assertions.assertEquals("fixture-primary",receipt.targets().keySet().iterator().next().namespace());
        org.mockito.Mockito.verify(mirror).add(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
    @Test
    void selectedUpstashWriterSuppliesItsOwnPhysicalNamespace() throws Exception {
        var candidate=org.mockito.Mockito.mock(com.example.lms.service.vector.PineconeVectorStoreAdapter.class);
        org.mockito.Mockito.when(candidate.namespace()).thenReturn("unselected");
        var selected=org.mockito.Mockito.mock(com.example.lms.service.vector.UpstashVectorStoreAdapter.class);
        org.mockito.Mockito.when(selected.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(selected.isWriteEnabled()).thenReturn(true);
        org.mockito.Mockito.when(selected.namespace()).thenReturn("fixture-selected");
        var receipt=receipt(facade(candidate,selected,"upstash"),"requested");
        assertTrue(receipt.durable());
        org.junit.jupiter.api.Assertions.assertEquals("fixture-selected",receipt.targets().keySet().iterator().next().namespace());
        org.mockito.Mockito.verify(candidate,org.mockito.Mockito.never()).addAll(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList());
    }
    @SuppressWarnings("unchecked")
    private static dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> facade(
            dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> writer) {
        return facade(writer,null,"pinecone");
    }
    @SuppressWarnings("unchecked")
    private static dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> facade(
            dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> writer,
            com.example.lms.service.vector.UpstashVectorStoreAdapter upstash,String choice) {
        var provider=(org.springframework.beans.factory.ObjectProvider<dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment>>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(writer);
        return new LangChainConfig(null,null).embeddingStore(upstash,provider,new com.example.lms.vector.EmbeddingFingerprint(),null,choice);
    }
    @SuppressWarnings("unchecked")
    private static com.example.lms.vector.FederatedEmbeddingStore federation(
            dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> facade) {
        var provider=(org.springframework.beans.factory.ObjectProvider<java.util.List<com.example.lms.vector.FederatedEmbeddingStore.NamedStore>>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(new com.example.lms.vector.FederatedEmbeddingStore.NamedStore("composite",facade)));
        return new com.example.lms.vector.FederatedEmbeddingStore(provider,null,1000,1);
    }
    private static com.example.lms.service.VectorStoreService.VectorRecordReceipt receipt(
            dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> store,String id) {
        var model=org.mockito.Mockito.mock(dev.langchain4j.model.embedding.EmbeddingModel.class);
        org.mockito.Mockito.when(model.embedAll(org.mockito.ArgumentMatchers.anyList())).thenReturn(dev.langchain4j.model.output.Response.from(java.util.List.of(dev.langchain4j.data.embedding.Embedding.from(new float[]{1}))));
        var service=new com.example.lms.service.VectorStoreService(model,store);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"batchSize",1000);
        var receipt=service.enqueueWithReceipt(id,"fixture-logical","fixture",java.util.Map.of());
        service.flush(); return receipt;
    }
}
