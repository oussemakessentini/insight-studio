package com.oussamaksantini.insightstudio.report;

import com.oussamaksantini.insightstudio.reporting.DateRange;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Downloadable report files: content types, attachment headers and filenames. */
public final class ReportFiles {

    public static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private ReportFiles() {
    }

    /**
     * {@code <business-slug>-<kind>-<from>-to-<to>.<extension>}: only the slug (letters, digits and
     * dashes), the kind and ISO dates, never user text such as a saved report's name.
     */
    public static String filename(String businessSlug, String kind, DateRange period, String extension) {
        return "%s-%s-%s-to-%s.%s".formatted(businessSlug, kind, period.from(), period.to(), extension);
    }

    public static ResponseEntity<String> csv(String filename, String body) {
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .body(body);
    }

    public static ResponseEntity<byte[]> pdf(String filename, byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(filename))
                .body(body);
    }

    private static String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename).build().toString();
    }
}
