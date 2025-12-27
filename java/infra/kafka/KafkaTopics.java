package infra.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.kafka.topics")
public class KafkaTopics {
    private String telemetry;
    private String inferenceLogs;

    public String telemetry() { return telemetry; }
    public String inferenceLogs() { return inferenceLogs; }

    public String getTelemetry() { return telemetry; }
    public void setTelemetry(String telemetry) { this.telemetry = telemetry; }

    public String getInferenceLogs() { return inferenceLogs; }
    public void setInferenceLogs(String inferenceLogs) { this.inferenceLogs = inferenceLogs; }
}