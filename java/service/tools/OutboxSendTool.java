package service.tools;

public class OutboxSendTool {
  public boolean store(String channel, String payload){
    // TODO: persist → "outbox" table/queue
    return true;
  }
}