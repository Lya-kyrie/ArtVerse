package com.artverse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArtVerseApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArtVerseApplication.class, args);
    }
}
