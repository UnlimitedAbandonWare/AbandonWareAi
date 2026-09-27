package com.example.lms.api;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.codec.digest.DigestUtils;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;

/** Bounded canonical input hashing. No raw input is retained, logged or stored. */
final class SemanticRequestFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build());
    static String body(byte[] input) throws IOException {
        JsonNode tree = JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
        if (tree == null || !tree.isObject()) throw new IOException("invalid_request_object");
        return DigestUtils.sha256Hex(JSON.writeValueAsBytes(canonical(tree)));
    }
    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(value.get(name)))); return result;
        }
        if (value.isArray()) { ArrayNode result = JSON.createArrayNode(); value.forEach(v -> result.add(canonical(v))); return result; }
        if (value.isTextual()) return TextNode.valueOf(Normalizer.normalize(value.textValue().replace("\r\n", "\n"), Normalizer.Form.NFC));
        return value;
    }
    static String request(HttpServletRequest request, String path, String bodyHash) {
        var input = new TreeMap<String,Object>();
        input.put("method", request.getMethod()); input.put("operation", path); input.put("body", bodyHash);
        input.put("query", new TreeMap<>(request.getParameterMap()));
        var headers = new TreeMap<String,String>();
        for (String name : List.of("X-Session-Id", "X-Conversation-Id", "X-Chat-Run-Token", "X-Chat-Run-Ack-Required",
                "X-Jammini-Mode", "X-Guard-Level", "X-Budget-Ms", "X-AWX-Selection-Replay"))
            if (request.getHeader(name) != null) headers.put(name, request.getHeader(name));
        input.put("executionHeaders", headers);
        try { return DigestUtils.sha256Hex(JSON.writeValueAsBytes(input)); }
        catch (IOException invalid) { throw new IllegalArgumentException("invalid_request_fingerprint"); }
    }
}
