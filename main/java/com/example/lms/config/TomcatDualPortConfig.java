package com.example.lms.config;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Set;

/**
 * Configure embedded Tomcat to:
 * - serve HTTPS as the primary connector on port 443 (SSL is taken from
 * application properties)
 * - expose an additional plain HTTP connector on port 80 for legacy URLs
 * (http://host/chat).
 * 
 * This avoids the previous misconfiguration where TLS was bound to port 80.
 * No application.yml/properties changes are required.
 * 
 * NOTE: This config is ONLY activated when server.ssl.enabled=true.
 * If SSL is disabled, this config does NOT run, preventing the issue
 * of opening port 443 without proper TLS configuration.
 */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "server.ssl.enabled", havingValue = "true")
public class TomcatDualPortConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TomcatDualPortConfig.class);

    private final Environment environment;

    @Value("${server.https-port:443}")
    private int httpsPort;

    @Value("${server.http-port:80}")
    private int httpPort;

    public TomcatDualPortConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        // Primary connector → HTTPS on 443 (SSL settings from application properties)
        // Spring Boot will apply keystore/SSL config; we only change the port.
        applyExplicitSsl(factory);
        factory.setPort(httpsPort);

        // Additional connector → plain HTTP on 80
        Connector http = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        if (httpPort <= 0 || httpPort == httpsPort) {
            return;
        }
        http.setScheme("http");
        http.setPort(httpPort);
        http.setSecure(false);
        factory.addAdditionalTomcatConnectors(http);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void applyExplicitSsl(TomcatServletWebServerFactory factory) {
        String keyStore = firstPresent("server.ssl.key-store", "SERVER_SSL_KEY_STORE");
        if (ConfigValueGuards.isMissing(keyStore)) {
            log.warn("[AWX][domain-start][ssl] explicitSsl=false keyStorePresent=false");
            return;
        }

        String normalizedKeyStore = normalizeKeyStoreLocation(keyStore);
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setKeyStore(normalizedKeyStore);
        String keyStoreType = firstPresent("server.ssl.key-store-type", "SERVER_SSL_KEY_STORE_TYPE");
        String keyStorePassword = firstPresent("server.ssl.key-store-password", "SERVER_SSL_KEY_STORE_PASSWORD");
        String keyPassword = firstPresent("server.ssl.key-password", "SERVER_SSL_KEY_PASSWORD");
        String keyAlias = firstPresent("server.ssl.key-alias", "SERVER_SSL_KEY_ALIAS");
        setIfPresent(keyStoreType, ssl::setKeyStoreType);
        setIfPresent(keyStorePassword, ssl::setKeyStorePassword);
        setIfPresent(keyPassword, ssl::setKeyPassword);
        setIfPresent(keyAlias, ssl::setKeyAlias);
        factory.setSsl(ssl);
        factory.addConnectorCustomizers(connector -> applyConnectorSsl(connector, normalizedKeyStore,
                keyStoreType, keyStorePassword, keyPassword, keyAlias));
        log.info("[AWX][domain-start][ssl] explicitSsl=true keyStorePresent=true keyStoreScheme={} keyStoreTypePresent={} keyAliasPresent={} keyStorePasswordPresent={} keyPasswordPresent={}",
                keyStoreScheme(normalizedKeyStore),
                !ConfigValueGuards.isMissing(keyStoreType),
                !ConfigValueGuards.isMissing(keyAlias),
                !ConfigValueGuards.isMissing(keyStorePassword),
                !ConfigValueGuards.isMissing(keyPassword));
    }

    private void applyConnectorSsl(Connector connector,
                                   String keyStore,
                                   String keyStoreType,
                                   String keyStorePassword,
                                   String keyPassword,
                                   String keyAlias) {
        if (connector == null || (connector.getPort() == httpPort && "http".equalsIgnoreCase(connector.getScheme()))) {
            return;
        }
        String tomcatKeyStoreFile = tomcatKeyStoreFile(keyStore);
        applySslHostConfig(connector, tomcatKeyStoreFile, keyStoreType, keyStorePassword, keyPassword, keyAlias);
    }

    private static void applySslHostConfig(Connector connector,
                                           String keyStoreFile,
                                           String keyStoreType,
                                           String keyStorePassword,
                                           String keyPassword,
                                           String keyAlias) {
        SSLHostConfig[] hostConfigs = connector.findSslHostConfigs();
        if (hostConfigs == null || hostConfigs.length == 0) {
            SSLHostConfig hostConfig = new SSLHostConfig();
            hostConfig.setHostName("_default_");
            configureCertificates(hostConfig, keyStoreFile, keyStoreType, keyStorePassword, keyPassword, keyAlias);
            connector.addSslHostConfig(hostConfig);
            return;
        }
        for (SSLHostConfig hostConfig : hostConfigs) {
            configureCertificates(hostConfig, keyStoreFile, keyStoreType, keyStorePassword, keyPassword, keyAlias);
        }
    }

    private static void configureCertificates(SSLHostConfig hostConfig,
                                              String keyStoreFile,
                                              String keyStoreType,
                                              String keyStorePassword,
                                              String keyPassword,
                                              String keyAlias) {
        Set<SSLHostConfigCertificate> certificates = hostConfig.getCertificates(true);
        if (certificates.isEmpty()) {
            SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
                    hostConfig,
                    SSLHostConfigCertificate.Type.UNDEFINED);
            hostConfig.addCertificate(certificate);
            certificates = hostConfig.getCertificates(true);
        }
        for (SSLHostConfigCertificate certificate : certificates) {
            certificate.setCertificateKeystoreFile(keyStoreFile);
            setCertificateValue(keyStoreType, certificate::setCertificateKeystoreType);
            setCertificateValue(keyStorePassword, certificate::setCertificateKeystorePassword);
            setCertificateValue(keyPassword, certificate::setCertificateKeyPassword);
            setCertificateValue(keyAlias, certificate::setCertificateKeyAlias);
        }
    }

    private String firstPresent(String... names) {
        for (String name : names) {
            String value = environment.getProperty(name);
            if (!ConfigValueGuards.isMissing(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static void setIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (!ConfigValueGuards.isMissing(value)) {
            setter.accept(value.trim());
        }
    }

    private static String normalizeKeyStoreLocation(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("file:") || trimmed.startsWith("classpath:")) {
            return trimmed;
        }
        if (trimmed.matches("^[A-Za-z]:[\\\\/].*") || trimmed.startsWith("\\\\")) {
            return Path.of(trimmed).toUri().toString();
        }
        return trimmed;
    }

    private static String tomcatKeyStoreFile(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("file:")) {
            try {
                return Path.of(java.net.URI.create(trimmed)).toString();
            } catch (IllegalArgumentException ex) {
                traceTomcatSslSuppressed("keystore.file", trimmed, ex);
                return trimmed;
            }
        }
        return trimmed;
    }

    private static void setCertificateValue(String value, java.util.function.Consumer<String> setter) {
        if (!ConfigValueGuards.isMissing(value)) {
            setter.accept(value.trim());
        }
    }

    private static String keyStoreScheme(String value) {
        if (value == null || value.isBlank()) {
            return "missing";
        }
        int idx = value.indexOf(':');
        if (idx > 0) {
            return value.substring(0, idx).toLowerCase();
        }
        return "path";
    }

    private static void traceTomcatSslSuppressed(String stage, String input, RuntimeException failure) {
        String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
        TraceStore.put("tomcat.ssl.suppressed.stage", safeStage);
        TraceStore.put("tomcat.ssl.suppressed.errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
        TraceStore.put("tomcat.ssl.suppressed." + safeStage, true);
        TraceStore.put("tomcat.ssl.suppressed.inputLength", input == null ? 0 : input.length());
        TraceStore.put("tomcat.ssl.suppressed.inputHash", SafeRedactor.hashValue(input));
    }
}
