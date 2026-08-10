package com.harudle;

import org.springframework.boot.SpringApplication;

public class TestHarudleApplication {

    public static void main(String[] args) {
        SpringApplication.from(HarudleApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
