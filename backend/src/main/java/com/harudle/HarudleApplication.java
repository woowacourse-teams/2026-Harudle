package com.harudle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HarudleApplication {

    public static void main(String[] args) {
        SpringApplication.run(HarudleApplication.class, args);
    }

}
