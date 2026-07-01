package io.github.ahrimjang.mail.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background worker: processes the send queue and scheduled mail jobs.
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "io.github.ahrimjang.mail")
public class MailWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailWorkerApplication.class, args);
    }
}
