package integrations.mcp;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class McpClient {
  private final String serverUrl;
  public McpClient(String serverUrl){ this.serverUrl=serverUrl; }
  public String call(String tool, String json) {
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(serverUrl+"/tools/"+tool))
        .header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
        .build();
      return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).body();
    } catch (Exception e){ throw new RuntimeException(e); }
  }
}