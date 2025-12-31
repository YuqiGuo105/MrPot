package com.example.MrPot.service;

import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Service
public class TikaUniversalExtractor {

    // English comments: Keep extraction small to save tokens & latency.
    private static final int MAX_CHARS = 10_000;

    private final AutoDetectParser parser = new AutoDetectParser();

    public TikaResult extract(byte[] bytes, String filename, String declaredMimeOrNull) throws Exception {
        Metadata md = new Metadata();

        // English comments: In Tika 2+/3+, use TikaCoreProperties.RESOURCE_NAME_KEY.
        if (filename != null && !filename.isBlank()) {
            md.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        if (declaredMimeOrNull != null && !declaredMimeOrNull.isBlank()) {
            md.set(Metadata.CONTENT_TYPE, declaredMimeOrNull);
        }

        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        ParseContext ctx = new ParseContext();

        // English comments: Disable embedded extraction to reduce risk/latency.
        ctx.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                return false;
            }

            @Override
            public void parseEmbedded(InputStream stream,
                                      org.xml.sax.ContentHandler handler,
                                      Metadata metadata,
                                      boolean outputHtml) {
                // no-op
            }
        });

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            try {
                parser.parse(is, handler, md, ctx);
            } catch (SAXException sax) {
                // English comments: BodyContentHandler throws SAXException when write limit reached.
                String msg = sax.getMessage() == null ? "" : sax.getMessage().toLowerCase();
                boolean isLimit = msg.contains("write limit") || msg.contains("your document contained more than");
                if (!isLimit) throw sax;
            }
        }

        String text = handler.toString();
        String detected = md.get(Metadata.CONTENT_TYPE);
        return new TikaResult(text, detected);
    }

    public record TikaResult(String text, String detectedMime) {}
}
