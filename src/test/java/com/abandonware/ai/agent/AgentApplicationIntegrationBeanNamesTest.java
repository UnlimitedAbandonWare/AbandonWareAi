package com.abandonware.ai.agent;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;

import java.beans.Introspector;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentApplicationIntegrationBeanNamesTest {
    static Stream<Arguments> overlappingComponentNames() {
        return Stream.of(
                new String[]{"GenericPlacesClient", "com.example.lms.location.places"},
                new String[]{"GenericReverseGeocodingClient", "com.example.lms.location.geo"},
                new String[]{"KakaoPlacesClient", "com.example.lms.location.places"},
                new String[]{"KakaoReverseGeocodingClient", "com.example.lms.location.geo"},
                new String[]{"N8nNotifier", "com.example.lms.integrations.n8n"}
        ).flatMap(pair -> Stream.of(false, true)
                .map(agentFirst -> Arguments.of(pair[0], pair[1], agentFirst)));
    }

    @ParameterizedTest(name = "{0}: agent package first={2}")
    @MethodSource("overlappingComponentNames")
    void keepsBothConcreteTypesUnderDistinctNames(String simpleName, String lmsPackage, boolean agentFirst) {
        String agentPackage = "com.abandonware.ai.agent.integrations";
        String lmsType = lmsPackage + "." + simpleName;
        String agentType = agentPackage + "." + simpleName;
        var factory = new DefaultListableBeanFactory();
        factory.setAllowBeanDefinitionOverriding(false);
        var scanner = new ClassPathBeanDefinitionScanner(factory, false);
        var types = Set.of(lmsType, agentType);
        scanner.addIncludeFilter((metadata, readerFactory) ->
                types.contains(metadata.getClassMetadata().getClassName()));

        scanner.scan(agentFirst ? agentPackage : lmsPackage, agentFirst ? lmsPackage : agentPackage);

        assertEquals(lmsType, factory.getBeanDefinition(Introspector.decapitalize(simpleName)).getBeanClassName());
        assertEquals(agentType, factory.getBeanDefinition("agent" + simpleName).getBeanClassName());
        assertEquals(0, factory.getSingletonCount(), "metadata scanning must not construct integrations");
    }
}
