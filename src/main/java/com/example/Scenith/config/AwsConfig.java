package com.example.Scenith.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class AwsConfig {
    private static final Logger logger = LoggerFactory.getLogger(AwsConfig.class);

    @Value("${sqs.region}")
    private String region;

    @Bean
    public SqsClient sqsClient() {
        logger.info("Creating SqsClient with region: {}", region);
        return SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}