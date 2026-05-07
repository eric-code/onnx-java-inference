package com.kudosol.ai.inference;

import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.config.S3Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({InferenceProperties.class, S3Properties.class})
public class InferenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InferenceApplication.class, args);
    }
}
