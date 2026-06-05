package com.muyulu.aisimtrans;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiSimTransApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSimTransApplication.class, args);
    }

}
