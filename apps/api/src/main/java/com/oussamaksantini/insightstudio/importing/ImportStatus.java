package com.oussamaksantini.insightstudio.importing;

/** Outcome of an upload. Only {@link #IMPORTED} uploads are stored as import batches. */
public enum ImportStatus {
    /** Dry run without errors: the file can be imported as is. */
    VALIDATED,
    /** The file was written: one batch, its receipts and their line items. */
    IMPORTED,
    /** At least one error; nothing was written. */
    REJECTED
}
