package integrations.mcp.tools;

import integrations.mcp.McpClient;

public class MCPPingTool {
  private final McpClient client;
  public MCPPingTool(McpClient c){ this.client=c; }
  public String ping(String echo){ return client.call("mcp.ping", "{\"echo\":\""+echo+"\"}"); }
}