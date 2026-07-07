package io.github.ahrimjang.mail.core.port;

/**
 * Outbound port for persisting uploaded binary assets (template images).
 *
 * <p>The POC ships a local-disk adapter; swapping in S3/GCS is a drop-in
 * replacement of this interface.
 */
public interface FileStorage {

    /**
     * Store the content under a new unique name with the given extension.
     *
     * @return the stored file name (e.g. {@code "3f2a…b1.png"}), later served
     *         under the public uploads path
     */
    String store(String extension, byte[] content);
}
