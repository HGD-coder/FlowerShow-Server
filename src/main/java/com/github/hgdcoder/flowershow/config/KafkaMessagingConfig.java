package com.github.hgdcoder.flowershow.config;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flower-show.messaging.kafka.enabled", havingValue = "true")
public class KafkaMessagingConfig {

    @Bean
    public KafkaAdmin.NewTopics flowerShowTopics(
            @Value("${flower-show.messaging.kafka.domain-topic:flower-show.domain-events.v1}") String domainTopic,
            @Value("${flower-show.messaging.kafka.fanout-topic:flower-show.feed-fanout.v1}") String fanoutTopic,
            @Value("${flower-show.messaging.kafka.partitions:12}") int partitions,
            @Value("${flower-show.messaging.kafka.replicas:1}") short replicas
    ) {
        int safePartitions = Math.max(1, partitions);
        short safeReplicas = (short) Math.max(1, replicas);
        String minInSyncReplicas = safeReplicas > 1 ? "2" : "1";
        Map<String, String> durableTopicConfig = Map.of(
                TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInSyncReplicas
        );
        NewTopic domain = topic(domainTopic, safePartitions, safeReplicas, durableTopicConfig);
        NewTopic fanout = topic(fanoutTopic, safePartitions, safeReplicas, durableTopicConfig);
        NewTopic domainDlt = topic(domainTopic + ".DLT", safePartitions, safeReplicas, durableTopicConfig);
        NewTopic fanoutDlt = topic(fanoutTopic + ".DLT", safePartitions, safeReplicas, durableTopicConfig);
        return new KafkaAdmin.NewTopics(domain, fanout, domainDlt, fanoutDlt);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${flower-show.messaging.kafka.listener-concurrency:3}") int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, concurrency));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", record.partition())
        );
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30000);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.setCommitRecovered(true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private static NewTopic topic(
            String name,
            int partitions,
            short replicas,
            Map<String, String> config
    ) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .configs(config)
                .build();
    }
}
