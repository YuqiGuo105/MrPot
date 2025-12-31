package com.example.MrPot.service;

import com.example.MrPot.model.DownloadedFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Service
public class RemoteFileService {

    // English comments: Keep conservative limits to avoid memory issues.
    private static final long MAX_BYTES = 20L * 1024 * 1024; // 20MB
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebClient webClient;

    public RemoteFileService(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public Mono<DownloadedFile> download(String url) {
        URI uri = URI.create(url);
        validateUriForSsrf(uri);

        return webClient.get()
                .uri(uri)
                .accept(MediaType.ALL)
                .retrieve()
                .toEntity(byte[].class)
                .timeout(TIMEOUT)
                .map(entity -> {
                    byte[] bytes = Optional.ofNullable(entity.getBody()).orElse(new byte[0]);
                    if (bytes.length > MAX_BYTES) {
                        throw new IllegalArgumentException("File too large (>" + MAX_BYTES + " bytes)");
                    }

                    HttpHeaders h = entity.getHeaders();
                    String mime = Optional.ofNullable(h.getContentType()).map(MediaType::toString)
                            .orElse("application/octet-stream");

                    long size = h.getContentLength();
                    if (size < 0) size = bytes.length;

                    String filename = guessFilename(uri);
                    return new DownloadedFile(uri, filename, mime, size, bytes);
                });
    }

    private static String guessFilename(URI uri) {
        String path = uri.getPath();
        if (!StringUtils.hasText(path)) return "file";
        int idx = path.lastIndexOf('/');
        String name = (idx >= 0) ? path.substring(idx + 1) : path;
        return StringUtils.hasText(name) ? name : "file";
    }

    /**
     * English comments: Basic SSRF guard:
     * - allow only http/https
     * - block localhost & private network IPs (best-effort)
     */
    private static void validateUriForSsrf(URI uri) {
        String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("Only http/https URLs are allowed");
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("Invalid URL host");
        }

        if (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")) {
            throw new IllegalArgumentException("Blocked host");
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isAnyLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress()) {
                throw new IllegalArgumentException("Blocked private/local address");
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Host validation failed: " + ex.getMessage(), ex);
        }
    }
}

