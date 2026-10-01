package com.oussamaksantini.insightstudio.savedreport;

import com.oussamaksantini.insightstudio.report.ReportCsv;
import com.oussamaksantini.insightstudio.report.ReportFiles;
import com.oussamaksantini.insightstudio.report.pdf.ReportPdf;
import com.oussamaksantini.insightstudio.report.pdf.ReportPdfDetails;
import com.oussamaksantini.insightstudio.savedreport.SavedReportService.Run;
import com.oussamaksantini.insightstudio.savedreport.dto.SavedReportRequest;
import com.oussamaksantini.insightstudio.savedreport.dto.SavedReportResponse;
import com.oussamaksantini.insightstudio.savedreport.dto.SavedReportRunResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved report definitions of the current business and their exports
 * (docs/saved-reports-contract.md §3). Members read and run them; ADMIN and OWNER manage them. Not
 * served for the public demo (anonymous callers get a 401 from the security rules).
 */
@RestController
@RequestMapping("/api/saved-reports")
class SavedReportController {

    private final SavedReportService savedReports;
    private final ReportPdf pdf;

    SavedReportController(SavedReportService savedReports, ReportPdf pdf) {
        this.savedReports = savedReports;
        this.pdf = pdf;
    }

    @GetMapping
    List<SavedReportResponse> list() {
        return savedReports.list();
    }

    @PostMapping
    ResponseEntity<SavedReportResponse> create(@RequestBody(required = false) SavedReportRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(savedReports.create(body));
    }

    @GetMapping("/{id}")
    SavedReportResponse get(@PathVariable long id) {
        return savedReports.get(id);
    }

    @PutMapping("/{id}")
    SavedReportResponse update(@PathVariable long id, @RequestBody(required = false) SavedReportRequest body) {
        return savedReports.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable long id) {
        savedReports.delete(id);
    }

    @GetMapping("/{id}/report")
    SavedReportRunResponse report(@PathVariable long id) {
        Run run = savedReports.run(id);
        return new SavedReportRunResponse(run.savedReport(), run.monthly(), run.categories());
    }

    @GetMapping("/{id}/report.csv")
    ResponseEntity<String> csv(@PathVariable long id) {
        Run run = savedReports.run(id);
        String body = run.monthly() != null ? ReportCsv.monthly(run.monthly()) : ReportCsv.categories(run.categories());
        return ReportFiles.csv(filename(run, "csv"), body);
    }

    @GetMapping("/{id}/report.pdf")
    ResponseEntity<byte[]> pdf(@PathVariable long id) {
        Run run = savedReports.run(id);
        SavedReportQueries.SavedReportRow definition = run.definition();
        ReportPdfDetails details = ReportPdfDetails.of(
                definition.name(),
                run.business(),
                ReportPdfDetails.storeLabel(definition.storeName(), definition.storeCode()),
                definition.range().description(run.business().zoneId()));
        byte[] body = run.monthly() != null
                ? pdf.monthly(run.monthly(), details)
                : pdf.categories(run.categories(), details);
        return ReportFiles.pdf(filename(run, "pdf"), body);
    }

    private static String filename(Run run, String extension) {
        return ReportFiles.filename(run.business().getSlug(), run.definition().kind().code(), run.period(), extension);
    }
}
