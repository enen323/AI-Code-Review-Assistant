package com.ai.code.review;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.ai.code.review.memory")
public class AiCodeReviewAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodeReviewAssistantApplication.class, args);
    }
}
