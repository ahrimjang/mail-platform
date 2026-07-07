package io.github.ahrimjang.mail.core.service;

import io.github.ahrimjang.mail.core.port.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private FileStorage storage;

    private UploadService service() {
        return new UploadService(storage, BASE_URL);
    }

    @Test
    void storeImage_returnsPublicUrlUnderUploads() {
        when(storage.store(eq("png"), any())).thenReturn("abc.png");

        String url = service().storeImage("image/png", new byte[]{1, 2, 3});

        assertThat(url).isEqualTo(BASE_URL + "/uploads/abc.png");
    }

    @Test
    void storeImage_mapsJpegToJpgExtension() {
        when(storage.store(eq("jpg"), any())).thenReturn("x.jpg");

        assertThat(service().storeImage("image/jpeg", new byte[]{1}))
                .endsWith("/uploads/x.jpg");
    }

    @Test
    void storeImage_rejectsUnsupportedType() {
        assertThatThrownBy(() -> service().storeImage("image/svg+xml", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported image type");
        assertThatThrownBy(() -> service().storeImage(null, new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void storeImage_rejectsEmptyAndOversizeContent() {
        assertThatThrownBy(() -> service().storeImage("image/png", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> service().storeImage("image/png", new byte[UploadService.MAX_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
        verifyNoInteractions(storage);
    }
}
