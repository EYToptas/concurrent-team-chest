package io.github.eytoptas.teamchest.application;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class ServiceTestConfiguration {

    @Bean
    AfterCommitRecorder afterCommitRecorder(DataSource dataSource) {
        return new AfterCommitRecorder(dataSource);
    }
}
