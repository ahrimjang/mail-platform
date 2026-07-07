package io.github.ahrimjang.mail.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background worker: processes the send queue and scheduled mail jobs.
 */
@SpringBootApplication(scanBasePackages = "io.github.ahrimjang.mail")
@EnableScheduling // drives ScheduledCampaignReleaser (scheduled-campaign queue release)
public class MailWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailWorkerApplication.class, args);
    }
}
