package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.port.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Use case for uploading template image assets.
 *
 * <p>Validates type/size, delegates persistence to the {@link FileStorage}
 * port, and returns the public URL. The URL is absolute (built on
 * {@code app.base-url}) because email clients fetch images from the recipient's
 * side — a relative path would dead-end outside the app.
 */
@Service
public class UploadService {

    /** POC guardrail: big enough for hero images, small enough to not care about disk. */
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp");

    private final FileStorage storage;
    private final String baseUrl;

    public UploadService(FileStorage storage,
                         @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.storage = storage;
        this.baseUrl = baseUrl;
    }

    /**
     * Store one image and return its public URL.
     *
     * @throws IllegalArgumentException on unsupported type or oversize content
     */
    public String storeImage(String contentType, byte[] content) {
        String extension = EXTENSION_BY_TYPE.get(contentType == null ? "" : contentType.toLowerCase());
        if (extension == null) {
            throw new IllegalArgumentException("unsupported image type: " + contentType
                    + " (allowed: " + String.join(", ", EXTENSION_BY_TYPE.keySet()) + ")");
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("file too large: " + content.length + " bytes (max " + MAX_BYTES + ")");
        }
        String storedName = storage.store(extension, content);
        return baseUrl + "/uploads/" + storedName;
    }
}
