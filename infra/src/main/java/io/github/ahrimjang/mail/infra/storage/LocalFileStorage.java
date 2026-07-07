package io.github.ahrimjang.mail.infra.storage;

import io.github.ahrimjang.mail.core.port.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Adapter: stores uploads on the local filesystem under {@code app.uploads.dir}
 * (created on first use). mail-api serves the same directory at /uploads/**.
 * Production would swap this for an S3/GCS implementation of the port.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path dir;

    public LocalFileStorage(@Value("${app.uploads.dir:uploads}") String dir) {
        this.dir = Path.of(dir);
    }

    @Override
    public String store(String extension, byte[] content) {
        // UUID name: no user input in the path, no collisions, no traversal surface.
        String name = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), content);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store upload " + name, e);
        }
        return name;
    }
}
