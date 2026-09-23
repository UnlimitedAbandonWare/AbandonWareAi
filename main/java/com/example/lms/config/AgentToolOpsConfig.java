package com.example.lms.config;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.consent.BasicConsentService;
import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.contract.ContractValidator;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.integrations.HybridRetriever;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.AgentToolArtifactWriter;
import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.impl.ops.CausalProbeEvaluateTool;
import com.abandonware.ai.agent.tool.impl.ops.ConfigInspectTool;
import com.abandonware.ai.agent.tool.impl.ops.DebugTraceLookupTool;
import com.abandonware.ai.agent.tool.impl.ops.CounterEvidenceRetrieveTool;
import com.abandonware.ai.agent.tool.impl.ops.CounterEvidencePacketStore;
import com.abandonware.ai.agent.tool.impl.ops.OperatorProbeAuthorityStore;
import com.abandonware.ai.agent.tool.impl.ops.EvidenceCoherenceVerifyTool;
import com.abandonware.ai.agent.tool.impl.ops.FailurePatternRecallTool;
import com.abandonware.ai.agent.tool.impl.ops.FailurePatternRecordTool;
import com.abandonware.ai.agent.tool.impl.ops.FailurePatternScanTool;
import com.abandonware.ai.agent.tool.impl.ops.KnowledgeGraphQueryTool;
import com.abandonware.ai.agent.tool.impl.ops.MoEStrategyQueryTool;
import com.abandonware.ai.agent.tool.impl.ops.OpsSnapshotTool;
import com.abandonware.ai.agent.tool.impl.ops.RepoScanTool;
import com.abandonware.ai.agent.tool.impl.ops.SourceMapTool;
import com.abandonware.ai.agent.tool.impl.ops.TraceSnapshotTool;
import com.abandonware.ai.agent.tool.impl.ops.VerifyContractTool;
import com.abandonware.ai.agent.tool.impl.ops.safe.DbEvidenceScanTool;
import com.example.lms.artplate.ArtPlateRegistry;
import com.example.lms.artplate.NineArtPlateGate;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.moe.RgbStrategySelector;
import com.example.lms.search.probe.CausalProbeTriggerService;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Collection;

@Configuration
@Import(ContractValidator.class)
public class AgentToolOpsConfig {

    @Bean
    @ConditionalOnMissingBean
    public ToolManifestCatalog toolManifestCatalog() {
        return new ToolManifestCatalog();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper agentToolObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolArtifactWriter agentToolArtifactWriter() {
        return new AgentToolArtifactWriter();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolPolicyEnforcer toolPolicyEnforcer() {
        return new ToolPolicyEnforcer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConsentService consentService() {
        return new BasicConsentService();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry() {
        return new ToolRegistry();
    }

    @Bean
    public SmartInitializingSingleton agentToolRegistryInitializer(ToolRegistry registry, ObjectProvider<AgentTool> tools) {
        return () -> tools.orderedStream().forEach(registry::register);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolInvoker agentToolInvoker(ToolRegistry registry,
                                             ToolManifestCatalog catalog,
                                             ToolPolicyEnforcer policy,
                                             ObjectProvider<ConsentService> consentService,
                                             ObjectProvider<DebugEventStore> debugEvents,
                                             AgentToolArtifactWriter artifactWriter,
                                             ObjectProvider<FailurePatternMemoryService> failurePatternMemory) {
        return new AgentToolInvoker(registry, catalog, policy, consentService, debugEvents, artifactWriter,
                failurePatternMemory);
    }

    @Bean
    public VerifyContractTool verifyContractTool(ToolManifestCatalog catalog, ToolRegistry registry) {
        return new VerifyContractTool(catalog, registry);
    }

    @Bean
    public OpsSnapshotTool opsSnapshotTool(ToolRegistry registry,
                                           ToolManifestCatalog catalog,
                                           Environment environment,
                                           ObjectProvider<DebugEventStore> debugEvents,
                                           ObjectProvider<DebugAiMetricsService> debugAiMetrics) {
        return new OpsSnapshotTool(registry, catalog, environment, debugEvents, debugAiMetrics);
    }

    @Bean
    public RepoScanTool repoScanTool() {
        return new RepoScanTool();
    }

    @Bean
    public SourceMapTool sourceMapTool(ObjectProvider<RequestMappingHandlerMapping> mappings,
                                       ToolRegistry registry) {
        return new SourceMapTool(mappings, registry);
    }

    @Bean
    public ConfigInspectTool configInspectTool(Environment environment) {
        return new ConfigInspectTool(environment);
    }

    @Bean
    public DebugTraceLookupTool debugTraceLookupTool(ObjectProvider<DebugEventStore> debugEvents,
                                                     ObjectProvider<DebugAiMetricsService> debugAiMetrics) {
        return new DebugTraceLookupTool(debugEvents, debugAiMetrics);
    }

    @Bean
    public TraceSnapshotTool traceSnapshotTool() {
        return new TraceSnapshotTool();
    }

    @Bean
    public KnowledgeGraphQueryTool knowledgeGraphQueryTool(ObjectProvider<KnowledgeGraphHandler> kgHandler) {
        return new KnowledgeGraphQueryTool(kgHandler);
    }

    @Bean
    public MoEStrategyQueryTool moeStrategyQueryTool(ObjectProvider<RgbStrategySelector> selector,
                                                     ObjectProvider<NineArtPlateGate> plateGate,
                                                     ObjectProvider<ArtPlateRegistry> plateRegistry) {
        return new MoEStrategyQueryTool(selector, plateGate, plateRegistry);
    }

    @Bean
    public DbEvidenceScanTool dbEvidenceScanTool(Environment environment) {
        return new DbEvidenceScanTool(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public FailurePatternMemoryService failurePatternMemoryService(ObjectMapper objectMapper,
                                                                   ToolManifestCatalog catalog) {
        return new FailurePatternMemoryService(objectMapper, catalog);
    }

    @Bean
    public FailurePatternScanTool failurePatternScanTool(FailurePatternMemoryService service) {
        return new FailurePatternScanTool(service);
    }

    @Bean
    public FailurePatternRecallTool failurePatternRecallTool(FailurePatternMemoryService service) {
        return new FailurePatternRecallTool(service);
    }

    @Bean
    public FailurePatternRecordTool failurePatternRecordTool(FailurePatternMemoryService service) {
        return new FailurePatternRecordTool(service);
    }

    @Bean
    public OperatorProbeAuthorityStore operatorProbeAuthorityStore() {
        return new OperatorProbeAuthorityStore();
    }

    @Bean
    public CausalProbeEvaluateTool causalProbeEvaluateTool(CausalProbeTriggerService service,
                                                           OperatorProbeAuthorityStore authorityStore) {
        return new CausalProbeEvaluateTool(service, authorityStore);
    }

    @Bean
    @ConditionalOnMissingBean(HybridRetriever.class)
    public HybridRetriever agentToolHybridRetriever() {
        return new HybridRetriever();
    }

    @Bean
    public CounterEvidencePacketStore counterEvidencePacketStore() {
        return new CounterEvidencePacketStore();
    }

    @Bean
    public CounterEvidenceRetrieveTool counterEvidenceRetrieveTool(HybridRetriever retriever,
                                                                   CounterEvidencePacketStore packetStore,
                                                                   ObjectProvider<ContradictionScorer> scorerProvider,
                                                                   OperatorProbeAuthorityStore authorityStore) {
        return new CounterEvidenceRetrieveTool(
                retriever,
                packetStore,
                scorerProvider.getIfAvailable(ContradictionScorer::new),
                authorityStore);
    }

    @Bean
    public EvidenceCoherenceVerifyTool evidenceCoherenceVerifyTool(CounterEvidencePacketStore packetStore) {
        return new EvidenceCoherenceVerifyTool(packetStore);
    }
}
