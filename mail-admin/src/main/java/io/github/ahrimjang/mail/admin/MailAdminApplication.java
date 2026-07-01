package io.github.ahrimjang.mail.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Internal admin console for managing the mail platform.
 */
@SpringBootApplication(scanBasePackages = "io.github.ahrimjang.mail")
public class MailAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailAdminApplication.class, args);
    }
}