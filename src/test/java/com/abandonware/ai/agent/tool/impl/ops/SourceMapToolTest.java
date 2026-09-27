package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SourceMapToolTest {
    private static final HandlerMethod HANDLER = new HandlerMethod(
            new Object(), ReflectionUtils.findMethod(Object.class, "toString"));

    @Test
    void registrationOrderDoesNotChangeTheSelectedRoutes() {
        List<RequestMappingInfo> infos = numberedRoutes(251);
        Map<?, ?> forward = invoke(new SnapshotMapping(infos));
        Collections.reverse(infos);
        Map<?, ?> reverse = invoke(new SnapshotMapping(infos));

        assertThat(reverse).isEqualTo(forward);
        List<Map<?, ?>> routes = routes(forward);
        assertThat(routes).hasSize(250);
        assertThat(routes.get(0).get("patterns")).isEqualTo(Set.of("/route/000"));
        assertThat(routes.get(249).get("patterns")).isEqualTo(Set.of("/route/249"));
    }

    @Test
    void sortsByPatternsThenMethodsThenName() {
        Map<?, ?> result = invoke(new SnapshotMapping(List.of(
                route("last", "z", new String[]{"/z"}, RequestMethod.GET),
                route("post", "p", new String[]{"/a"}, RequestMethod.POST),
                route("zeta", "z", new String[]{"/a"}, RequestMethod.GET),
                route("alpha", "a", new String[]{"/a"}, RequestMethod.GET),
                route(null, "empty", new String[]{"/a"}, RequestMethod.GET))));

        assertThat(routes(result).stream().map(row -> (String) row.get("name")).toList())
                .containsExactly("", "alpha", "zeta", "post", "last");
        routes(result).forEach(row -> assertThat(row.keySet())
                .isEqualTo(Set.of("patterns", "methods", "name")));
    }

    @Test
    void ordersMultiplePatternsAndMethodsByTheirElements() {
        Map<?, ?> result = invoke(new SnapshotMapping(List.of(
                route("literal-comma", "comma", new String[]{"/a, /b"}, RequestMethod.GET),
                route("two-patterns", "paths", new String[]{"/b", "/a"}, RequestMethod.GET),
                route("two-methods", "methods", new String[]{"/a"}, RequestMethod.POST, RequestMethod.GET),
                route("single", "single", new String[]{"/a"}, RequestMethod.GET))));

        List<Map<?, ?>> rows = routes(result);
        assertThat(rows.stream().map(row -> (String) row.get("name")).toList())
                .containsExactly("single", "two-methods", "two-patterns", "literal-comma");
        assertThat(rows.get(1).get("methods")).isEqualTo(List.of("GET", "POST"));
        assertThat(new ArrayList<>((Set<?>) rows.get(2).get("patterns")))
                .isEqualTo(List.of("/a", "/b"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 250, 251})
    void reportsTheTotalAndTruncationAtTheLimit(int count) {
        Map<?, ?> result = invoke(new SnapshotMapping(numberedRoutes(count)));

        assertThat(result.get("routeCount")).isEqualTo(count);
        assertThat(routes(result)).hasSize(Math.min(count, 250));
        assertThat(result.get("routesTruncated")).isEqualTo(count > 250);
    }

    @Test
    void routesAndCountsUseTheSameMappingSnapshot() {
        SnapshotMapping mapping = new SnapshotMapping(numberedRoutes(2)) {
            private int reads;

            @Override
            public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
                return reads++ == 0 ? super.getHandlerMethods() : Map.of();
            }
        };

        Map<?, ?> result = invoke(mapping);

        assertThat(routes(result)).hasSize(2);
        assertThat(result.get("routeCount")).isEqualTo(2);
        assertThat(result.get("routesTruncated")).isEqualTo(false);
    }

    @Test
    void returnsAnEmptyMapWithoutAMappingProvider() {
        assertEmptyRoutes(new SourceMapTool(null, new ToolRegistry()));
    }

    @Test
    void returnsAnEmptyMapWhenNoServletMappingIsAvailable() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        assertEmptyRoutes(new SourceMapTool(
                beans.getBeanProvider(RequestMappingHandlerMapping.class), new ToolRegistry()));
    }

    private static void assertEmptyRoutes(SourceMapTool tool) {
        Map<?, ?> result = (Map<?, ?>) tool.execute(new ToolRequest(Map.of(), null))
                .data().get("sourceMap");
        assertThat(routes(result)).isEmpty();
        assertThat(result.get("routeCount")).isEqualTo(0);
        assertThat(result.get("routesTruncated")).isEqualTo(false);
    }

    private static Map<?, ?> invoke(SnapshotMapping mapping) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("mapping", mapping));
        SourceMapTool tool = new SourceMapTool(
                beans.getBeanProvider(RequestMappingHandlerMapping.class), new ToolRegistry());
        return (Map<?, ?>) tool.execute(new ToolRequest(Map.of(), null)).data().get("sourceMap");
    }

    private static List<Map<?, ?>> routes(Map<?, ?> result) {
        return ((List<?>) result.get("routes")).stream().<Map<?, ?>>map(row -> (Map<?, ?>) row).toList();
    }

    private static List<RequestMappingInfo> numberedRoutes(int count) {
        List<RequestMappingInfo> infos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            infos.add(RequestMappingInfo.paths(String.format(Locale.ROOT, "/route/%03d", i)).build());
        }
        return infos;
    }

    private static RequestMappingInfo route(String name, String variant, String[] patterns,
                                            RequestMethod... methods) {
        return RequestMappingInfo.paths(patterns).methods(methods).mappingName(name)
                .headers("X-Variant=" + variant).build();
    }

    private static class SnapshotMapping extends RequestMappingHandlerMapping {
        private final Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();

        SnapshotMapping(List<RequestMappingInfo> infos) {
            infos.forEach(info -> handlers.put(info, HANDLER));
        }

        @Override
        public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
            return Collections.unmodifiableMap(handlers);
        }
    }
}
