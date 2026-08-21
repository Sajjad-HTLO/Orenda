package com.aitp.orenda.tripadvisor.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads image binaries from a given URL using the JDK's built-in
 * {@link HttpClient} (no extra dependencies). Returns the raw bytes plus a
 * best-effort mime type, or {@code null} when the download fails or the
 * response is not an image.
 */
@Slf4j
@Component
public class ImageDownloader {

    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024; // 10 MB cap
    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;

    public ImageDownloader() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches the image at {@code url}. Returns the image data and mime type,
     * or {@code null} if the download failed or the response wasn't an image.
     */
    public ImageData download(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .header("User-Agent",
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.debug("Image download returned HTTP {}. url={}", status, url);
                return null;
            }

            byte[] body = response.body();
            if (body == null || body.length == 0) {
                log.debug("Image download returned empty body. url={}", url);
                return null;
            }
            if (body.length > MAX_IMAGE_BYTES) {
                log.debug("Image download exceeded {} bytes cap; skipped. size={} url={}",
                        MAX_IMAGE_BYTES, body.length, url);
                return null;
            }

            String mime = response.headers().firstValue("Content-Type")
                    .map(v -> v.split(";")[0].trim().toLowerCase())
                    .orElse(null);
            if (mime != null && !mime.startsWith("image/")) {
                log.debug("Response is not an image ({}). url={}", mime, url);
                return null;
            }

            return new ImageData(body, mime);
        } catch (Exception e) {
            log.debug("Image download failed for url={}: {}", url, e.getMessage());
            return null;
        }
    }

    public record ImageData(byte[] data, String mimeType) {
    }
}