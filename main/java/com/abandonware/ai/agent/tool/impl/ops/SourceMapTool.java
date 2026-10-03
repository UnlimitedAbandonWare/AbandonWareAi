package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

@RequiresScopes({ToolScope.INTERNAL_READ})
public class SourceMapTool implements AgentTool {
    private static final int MAX_ROUTES = 250;
    private record RouteEntry(RequestMappingInfo info, HandlerMethod handler) { }
    private record Cursor(int offset, boolean stale) { }
    private static final Comparator<RequestMappingInfo> ROUTE_ORDER = Comparator
            .comparing((RequestMappingInfo info) -> info.getPatternValues().stream()
                    .sorted().toArray(String[]::new), Arrays::compare)
            .thenComparing(info -> info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name).sorted().toArray(String[]::new), Arrays::compare)
            .thenComparing(info -> info.getName() == null ? "" : info.getName());

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final ToolRegistry registry;

    public SourceMapTool(ObjectProvider<RequestMappingHandlerMapping> mappings, ToolRegistry registry) {
        this.mappings = mappings;
        this.registry = registry;
    }

    @Override
    public String id() {
        return "source.map";
    }

    @Override
    public String description() {
        return "List registered tool ids and Spring routes in a bounded redacted map.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request == null || request.input() == null ? Map.of() : request.input();
        String pattern = text(input.get("pattern"));
        String prefix = text(input.get("routePrefix"));
        String method = text(input.get("method")).toUpperCase(java.util.Locale.ROOT);
        if (!pattern.isBlank() && !prefix.isBlank()) throw ToolInvocationException.badRequest("ambiguous_route_filter");
        validatePattern(pattern);
        validatePattern(prefix);
        if (!method.isBlank()) {
            try { RequestMethod.valueOf(method); }
            catch (IllegalArgumentException ex) { throw ToolInvocationException.badRequest("invalid_method"); }
        }
        boolean targeted = !pattern.isBlank() || !prefix.isBlank() || !method.isBlank()
                || input.containsKey("cursor") || input.containsKey("limit");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolIds", registry.all().stream().map(AgentTool::id).sorted().toList());
        out.put("duplicateToolIds", registry.duplicateToolIdCounts());

        RequestMappingHandlerMapping mapping = mappings == null ? null : mappings.getIfAvailable();
        List<RouteEntry> all = mapping == null ? List.of() : new ArrayList<>(mapping.getHandlerMethods().entrySet()
                .stream().map(e -> new RouteEntry(e.getKey(), e.getValue())).toList());
        all = all.stream().sorted(Comparator.comparing(RouteEntry::info, ROUTE_ORDER)
                .thenComparing(e -> handlerName(e.handler()))).toList();
        List<RouteEntry> matched = all.stream().filter(e -> matches(e.info(), pattern, prefix, method)).toList();
        int limit = targeted ? boundedLimit(input.get("limit")) : MAX_ROUTES;
        String version = SafeRedactor.hashValue(all.stream().map(e -> e.info() + ":" + handlerName(e.handler()))
                .reduce("", (a, b) -> a + "\n" + b));
        String filter = SafeRedactor.hashValue(pattern + "\n" + prefix + "\n" + method);
        Cursor cursor = parseCursor(input.get("cursor"), version, filter);
        if (cursor.stale()) {
            out.put("routeCount", all.size());
            out.put("matchedRouteCount", matched.size());
            out.put("routes", List.of());
            out.put("routesTruncated", false);
            out.put("mappingStatus", mapping == null ? "unavailable" : "available");
            out.put("sourceStatus", "stale");
            out.put("reason", "stale_cursor");
            return ToolResponse.ok().put("sourceMap", out);
        }
        int offset = cursor.offset();
        if (offset > matched.size()) throw ToolInvocationException.badRequest("invalid_cursor");
        List<Map<String, Object>> routes = matched.stream().skip(offset).limit(limit)
                .map(e -> route(e.info(), e.handler(), targeted)).toList();
        out.put("routeCount", all.size());
        if (targeted) out.put("matchedRouteCount", matched.size());
        out.put("routes", routes);
        out.put("routesTruncated", matched.size() > offset + routes.size());
        out.put("mappingStatus", mapping == null ? "unavailable" : "available");
        if (targeted) {
            boolean hasMore = matched.size() > offset + routes.size();
            out.put("hasMore", hasMore);
            out.put("nextCursor", hasMore ? encodeCursor(version, filter, offset + routes.size()) : null);
        }
        return ToolResponse.ok().put("sourceMap", out);
    }

    private static Map<String, Object> route(RequestMappingInfo info, HandlerMethod handler, boolean targeted) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("patterns", new TreeSet<>(info.getPatternValues()));
        row.put("methods", info.getMethodsCondition().getMethods().stream().map(Enum::name).sorted().toList());
        row.put("name", info.getName() == null ? "" : info.getName());
        if (targeted) {
            String className = handler == null ? "" : handler.getMethod().getDeclaringClass().getName();
            row.put("handlerClass", className);
            row.put("handlerMethod", handler == null ? "" : handler.getMethod().getName());
            row.put("routeKind", className.startsWith("org.springframework.") || className.startsWith("java.")
                    ? "framework" : "application");
            row.put("sourceStatus", "source_not_resolved");
        }
        return row;
    }

    private static boolean matches(RequestMappingInfo info, String pattern, String prefix, String method) {
        if (!pattern.isBlank() && info.getPatternValues().stream().noneMatch(pattern::equals)) return false;
        if (!prefix.isBlank() && info.getPatternValues().stream().noneMatch(p -> p.startsWith(prefix))) return false;
        return method.isBlank() || info.getMethodsCondition().getMethods().isEmpty()
                || info.getMethodsCondition().getMethods().stream().anyMatch(m -> m.name().equals(method));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static void validatePattern(String value) {
        if (!value.isBlank() && (value.length() > 160 || !value.startsWith("/")
                || value.contains("?") || value.contains("#") || value.contains("\n") || value.contains("\r"))) {
            throw ToolInvocationException.badRequest("invalid_route_filter");
        }
    }

    private static int boundedLimit(Object value) {
        if (value == null) return MAX_ROUTES;
        try { return Math.max(1, Math.min(MAX_ROUTES, Integer.parseInt(String.valueOf(value)))); }
        catch (NumberFormatException ex) { throw ToolInvocationException.badRequest("invalid_limit"); }
    }

    private static String handlerName(HandlerMethod handler) {
        return handler == null ? "" : handler.getMethod().getDeclaringClass().getName() + "#" + handler.getMethod().getName();
    }

    private static String encodeCursor(String version, String filter, int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1\n" + version + "\n" + filter + "\n" + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor parseCursor(Object raw, String version, String filter) {
        if (raw == null) return new Cursor(0, false);
        String text = String.valueOf(raw).trim();
        if (text.length() > 256 || !text.matches("[A-Za-z0-9_-]+"))
            throw ToolInvocationException.badRequest("invalid_cursor");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\n", -1);
            if (fields.length != 4 || !"v1".equals(fields[0]) || !fields[3].matches("[0-9]{1,7}"))
                throw ToolInvocationException.badRequest("invalid_cursor");
            int offset = Integer.parseInt(fields[3]);
            if (!encodeCursor(fields[1], fields[2], offset).equals(text))
                throw ToolInvocationException.badRequest("invalid_cursor");
            return new Cursor(offset, !Objects.equals(version, fields[1]) || !Objects.equals(filter, fields[2]));
        } catch (IllegalArgumentException ex) {
            throw ToolInvocationException.badRequest("invalid_cursor");
        }
    }
}
