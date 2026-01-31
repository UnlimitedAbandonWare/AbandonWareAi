// src/main/java/com/example/lms/entity/AppConfig.java
package com.example.lms.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


import jakarta.persistence.*;    // javax → jakarta 로 변경

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