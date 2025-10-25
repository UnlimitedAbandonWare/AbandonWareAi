
package com.example.lms.config;

import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



/**
 * JDBC batch size 설정으로 saveAll / deleteAllByIdInBatch 성능을 극대화한다.
 */
@Configuration
public class JpaBatchConfig {

    @Bean
    public HibernatePropertiesCustomizer hibernateBatchCustomizer() {
        return props -> props.put("hibernate.jdbc.batch_size", 50);
    }
}