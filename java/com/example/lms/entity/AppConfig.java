// src/main/java/com/example/lms/entity/AppConfig.java
package com.example.lms.entity;

import jakarta.persistence.*;    // javax → jakarta 로 변경
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_config")
@Getter
@Setter
@NoArgsConstructor
public class AppConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "default_model", nullable = false)
    private String defaultModel;

}
