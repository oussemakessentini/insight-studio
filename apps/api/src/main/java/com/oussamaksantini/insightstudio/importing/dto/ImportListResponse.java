package com.oussamaksantini.insightstudio.importing.dto;

import java.util.List;

/** One page of the current business's import history, newest first. */
public record ImportListResponse(int page, int size, long totalItems, int totalPages, List<ImportBatchSummary> items) {
}
