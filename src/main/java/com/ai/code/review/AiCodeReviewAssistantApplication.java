package com.ai.code.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiCodeReviewAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeReviewAssistantApplication.class, args);
    }
}
