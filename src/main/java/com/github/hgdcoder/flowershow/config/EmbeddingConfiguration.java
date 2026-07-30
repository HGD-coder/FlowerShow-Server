package com.github.hgdcoder.flowershow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.hgdcoder.flowershow.recommendation.embedding.EmbeddingClient;
import com.github.hgdcoder.flowershow.recommendation.embedding.OllamaEmbeddingClient;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "flower-show.recommendation.embedding.enabled",
            havingValue = "true"
    )
    public EmbeddingClient embeddingClient(
            EmbeddingProperties properties,
            ObjectMapper objectMapper
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        return new OllamaEmbeddingClient(httpClient, objectMapper, properties);
    }

    @Bean(name = "embeddingTaskScheduler")
    @ConditionalOnProperty(
            name = "flower-show.recommendation.embedding.enabled",
            havingValue = "true"
    )
    public ThreadPoolTaskScheduler embeddingTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("flower-show-embedding-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
