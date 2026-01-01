package com.example.MrPot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
public class UrlFileDownloader {

    private static final Logger log = LoggerFactory.getLogger(UrlFileDownloader.class);

    // 保护：避免有人传超大文件把内存打爆（可按需调大/调小）
    private static final long MAX_BYTES = 12L * 1024 * 1024; // 12MB

    private final WebClient client;

    public UrlFileDownloader(WebClient.Builder builder) {
        int maxInMem = (int) Math.min(Integer.MAX_VALUE, MAX_BYTES + 2L * 1024 * 1024); // +2MB buffer

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxInMem))
                .build();

        HttpClient httpClient = HttpClient.create()
                .followRedirect(true)
                .responseTimeout(Duration.ofSeconds(30));

        this.client = builder
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    public record DownloadedFile(String url, String filename, String contentType, byte[] bytes) {
        public long size() { return bytes == null ? 0 : bytes.length; }
    }

    /** HEAD 探测 Content-Type（失败则返回 empty） */
    public Mono<String> headContentType(String url, Duration timeout) {
        return client.head()
                .uri(url)
                .exchangeToMono(resp -> {
                    Optional<MediaType> ct = resp.headers().contentType();
                    if (ct.isPresent()) return Mono.just(ct.get().toString());
                    List<String> vals = resp.headers().header("Content-Type");
                    return vals.isEmpty() ? Mono.empty() : Mono.just(vals.get(0));
                })
                .timeout(timeout)
                .onErrorResume(ex -> Mono.empty());
    }

    /** GET 下载 bytes，并带上 content-type / filename */
    public Mono<DownloadedFile> download(String url, Duration timeout) {
        String filename = filenameFromUrl(url);

        return client.get()
                .uri(url)
                .exchangeToMono(resp -> handleDownloadResponse(resp, url, filename))
                .timeout(timeout);
    }

    private Mono<DownloadedFile> handleDownloadResponse(ClientResponse resp, String url, String filename) {
        if (!resp.statusCode().is2xxSuccessful()) {
            return resp.bodyToMono(String.class).defaultIfEmpty("")
                    .flatMap(body -> {
                        String msg = "Download HTTP " + resp.statusCode() + " body=" + cut(body, 400);
                        log.warn("[UrlFileDownloader] {} url={}", msg, url);
                        return Mono.error(new RuntimeException(msg));
                    });
        }

        long len = resp.headers().contentLength().orElse(-1L);
        if (len > MAX_BYTES) {
            return Mono.error(new RuntimeException("File too large by header: " + len + " > " + MAX_BYTES));
        }

        String ct = resp.headers().contentType().map(Object::toString)
                .orElseGet(() -> {
                    List<String> vals = resp.headers().header("Content-Type");
                    return vals.isEmpty() ? "" : vals.get(0);
                });

        int maxJoin = (int) Math.min(Integer.MAX_VALUE, MAX_BYTES + 1);

        return DataBufferUtils.join(resp.body(BodyExtractors.toDataBuffers()), maxJoin)
                .map(buf -> {
                    try {
                        int size = buf.readableByteCount();
                        if (size > MAX_BYTES) {
                            throw new RuntimeException("File too large after download: " + size + " > " + MAX_BYTES);
                        }
                        byte[] bytes = new byte[size];
                        buf.read(bytes);
                        return new DownloadedFile(url, filename, ct, bytes);
                    } finally {
                        DataBufferUtils.release(buf);
                    }
                });
    }

    private static String filenameFromUrl(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getPath();
            if (path == null || path.isBlank()) return "file";
            int idx = path.lastIndexOf('/');
            String name = idx >= 0 ? path.substring(idx + 1) : path;
            return (name == null || name.isBlank()) ? "file" : name;
        } catch (Exception e) {
            return "file";
        }
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
