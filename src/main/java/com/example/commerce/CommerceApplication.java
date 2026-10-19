package com.example.commerce;

import com.example.commerce.integration.PartnerConnections;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PartnerConnections.class)
public class CommerceApplication {
    public static void main(String[] args) { SpringApplication.run(CommerceApplication.class, args); }
}
