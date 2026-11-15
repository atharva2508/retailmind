package com.example.springbootpostgresapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SpringbootPostgresApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringbootPostgresApiApplication.class, args);
    }
}
