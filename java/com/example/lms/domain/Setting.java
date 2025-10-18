package com.example.lms.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;



@Entity
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
public class Setting {
    @Id
    private String settingKey;
    private String settingValue;
}