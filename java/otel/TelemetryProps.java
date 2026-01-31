package otel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "otel")
public class TelemetryProps {
    private String serviceName = "src111-service";
    private Exporter exporter = new Exporter();

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public Exporter getExporter() { return exporter; }
    public void setExporter(Exporter exporter) { this.exporter = exporter; }

    public static class Exporter {
        private Otlp otlp = new Otlp();
        public Otlp getOtlp() { return otlp; }
        public void setOtlp(Otlp otlp) { this.otlp = otlp; }
    }
    public static class Otlp {
        private String endpoint = "http://localhost:4317";
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    }
}