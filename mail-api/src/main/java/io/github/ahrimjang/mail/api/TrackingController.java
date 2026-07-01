package io.github.ahrimjang.mail.api;

import io.github.ahrimjang.mail.core.service.TrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Base64;

/**
 * Public tracking endpoints: a 1x1 GIF pixel records opens, and a redirect
 * endpoint records clicks before forwarding to the original URL.
 */
@RestController
public class TrackingController {

    private static final byte[] PIXEL =
            Base64.getDecoder().decode("R0lGODlhAQABAID/AP///wAAACwAAAAAAQABAAACAkQBADs=");

    private final TrackingService tracking;

    public TrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping(value = "/api/track/open/{token}", produces = MediaType.IMAGE_GIF_VALUE)
    public ResponseEntity<byte[]> open(@PathVariable String token) {
        tracking.recordOpen(token);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_GIF).body(PIXEL);
    }

    @GetMapping("/api/track/click/{token}")
    public ResponseEntity<Void> click(@PathVariable String token, @RequestParam("u") String url) {
        tracking.recordClick(token, url);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
