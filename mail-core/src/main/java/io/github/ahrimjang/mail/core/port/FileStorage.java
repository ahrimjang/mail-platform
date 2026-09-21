package io.github.ahrimjang.mail.core.port;

/**
 * Outbound port for persisting uploaded binary assets (template images).
 *
 * <p>현재 어댑터는 로컬 디스크 저장이다. S3/GCS 로 옮길 때는 이 인터페이스의
 * 구현만 바꾸면 된다.
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
