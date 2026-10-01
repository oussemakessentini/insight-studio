package com.oussamaksantini.insightstudio.savedreport.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.oussamaksantini.insightstudio.report.dto.CategoryReportResponse;
import com.oussamaksantini.insightstudio.report.dto.MonthlyReportResponse;

/**
 * {@code GET /api/saved-reports/{id}/report}: the definition and its report for the resolved
 * period. Exactly one of {@code monthly} and {@code categories} is present (the other key is
 * omitted), depending on the definition's kind.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedReportRunResponse(
        SavedReportResponse savedReport, MonthlyReportResponse monthly, CategoryReportResponse categories) {
}
