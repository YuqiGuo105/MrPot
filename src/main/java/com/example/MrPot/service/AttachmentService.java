package com.example.MrPot.service;

import com.example.MrPot.model.AttachmentContext;
import com.example.MrPot.model.ExtractedFile;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@Service
public class AttachmentService {

    private static final int MAX_FILES = 3;

    private final RemoteFileService remote;
    private final FileExtractionService extractor;

    public AttachmentService(RemoteFileService remote, FileExtractionService extractor) {
        this.remote = remote;
        this.extractor = extractor;
    }

    public Mono<AttachmentContext> fetchAndExtract(List<String> urls, ChatClient visionClientOrNull) {
        if (urls == null || urls.isEmpty()) return Mono.just(AttachmentContext.empty());

        return Flux.fromIterable(urls)
                .filter(u -> u != null && !u.isBlank())
                .take(MAX_FILES)
                .concatMap(url ->
                        remote.download(url)
                                .flatMap(df -> extractor.extract(df, visionClientOrNull))
                                // English comments: One file failure should not kill the whole batch.
                                .onErrorResume(ex -> Mono.just(failed(url, ex)))
                )
                .collectList()
                // English comments: keep combinedText empty; budget & selection happen in RagAnswerService
                .map(files -> new AttachmentContext(files, ""))
                .onErrorReturn(AttachmentContext.empty());
    }

    // English comments: fallback record for a failed file.
    private static ExtractedFile failed(String url, Throwable ex) {
        // NOTE: adjust constructor params to match your ExtractedFile record fields.
        // This matches your usage: uri(), filename(), mimeType(), sizeBytes(), extractedText(), error()
        return new ExtractedFile(
                URI.create(url),
                "file",
                "application/octet-stream",
                0L,
                "",
                String.valueOf(ex)
        );
    }
}
