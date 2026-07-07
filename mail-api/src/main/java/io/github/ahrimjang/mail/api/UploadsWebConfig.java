package io.github.ahrimjang.mail.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Serves uploaded template images at /uploads/** straight from the storage
 * directory (same {@code app.uploads.dir} the LocalFileStorage adapter writes
 * to). Public by design — recipients' mail clients fetch these without auth.
 */
@Configuration
public class UploadsWebConfig implements WebMvcConfigurer {

    private final String uploadsDir;

    public UploadsWebConfig(@Value("${app.uploads.dir:uploads}") String uploadsDir) {
        this.uploadsDir = uploadsDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(Path.of(uploadsDir).toUri().toString())
                .setCachePeriod(3600);
    }
}
