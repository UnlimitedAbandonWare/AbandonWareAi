// 경로: com/example/lms/domain/Hyperparameter.java
package com.example.lms.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class Hyperparameter {
    @Id
    private String paramKey; // e.g., "boltzmannTemperature"
    private double paramValue;
    private String description;
}