package io.github.ahrimjang.mail.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Public-facing REST API for the mail platform.
 */
@SpringBootApplication(scanBasePackages = "io.github.ahrimjang.mail")
public class MailApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailApiApplication.class, args);
    }
}
