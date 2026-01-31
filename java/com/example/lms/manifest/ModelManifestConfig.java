package com.example.lms.manifest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;




@Configuration
public class ModelManifestConfig {

    @Value("${agent.models.path:configs/models.manifest.yaml}")
    private String manifestPath;

    @Bean
    public ModelsManifest modelsManifest() {
        try {
            Path p = Path.of(manifestPath);
            try (InputStream in = Files.newInputStream(p)) {
                return new Yaml().loadAs(in, ModelsManifest.class);
            }
        } catch (Exception e) {
            try (InputStream in = getClass().getClassLoader()
                    .getResourceAsStream(manifestPath.replace("classpath:", ""))) {
                if (in == null) throw new IllegalStateException("Manifest not found: " + manifestPath);
                return new Yaml().loadAs(in, ModelsManifest.class);
            } catch (Exception ee) {
                throw new IllegalStateException("Failed to load models manifest: " + manifestPath, ee);
            }
        }
    }

    @Bean
    public ModelRegistry modelRegistry(ModelsManifest mf) {
        return new ModelRegistry(mf);
    }
}