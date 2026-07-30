package com.github.hgdcoder.flowershow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfiguration {

    @Bean
    public Long pushScheduleIntervalMillis(PushProperties properties) {
        return properties.scheduleInterval().toMillis();
    }
}
