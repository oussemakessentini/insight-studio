package com.oussamaksantini.insightstudio.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.common.web.ApiException;
import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Decoding, header and file-name checks that turn an upload away with HTTP 400. */
class ImportFileReadingTest {

    private static final String HEADER = "store_code,receipt_number,sold_at,sku,quantity,unit_price";

    private static void assertBadRequest(byte[] content, String message) {
        assertThatThrownBy(() -> ImportService.readRows(content))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining(message);
    }

    @Test
    void readsBomAndCrlfFile() {
        // Built in code: git's autocrlf would normalise the line endings of a checked-in fixture.
        byte[] text = (HEADER + "\r\nBOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\r\nBOS,R-2,2026-09-01T10:00:00Z,TEE-1,1,1.00\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[text.length + 3];
        content[0] = (byte) 0xEF;
        content[1] = (byte) 0xBB;
        content[2] = (byte) 0xBF;
        System.arraycopy(text, 0, content, 3, text.length);

        List<CsvRecord> rows = ImportService.readRows(content);
        assertThat(rows).extracting(CsvRecord::line).containsExactly(2, 3);
        assertThat(rows.getFirst().fields()).containsExactly("BOS", "R-1", "2026-09-01T10:00:00Z", "TEE-1", "1", "1.00");
    }

    @Test
    void headerMayDifferInCaseAndSpacing() {
        byte[] content = (" Store_Code , RECEIPT_NUMBER,sold_at,SKU,quantity,unit_price\nBOS,R,x,y,1,1\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(ImportService.readRows(content)).hasSize(1);
    }

    @Test
    void rejectsEmptyFiles() {
        assertBadRequest(new byte[0], "empty");
        assertBadRequest("\uFEFF\r\n\n".getBytes(StandardCharsets.UTF_8), "empty");
        assertBadRequest((HEADER + "\n").getBytes(StandardCharsets.UTF_8), "no data rows");
    }

    @Test
    void rejectsMissingOrWrongHeader() {
        assertBadRequest("BOS,R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n".getBytes(StandardCharsets.UTF_8), "header");
        assertBadRequest("store,receipt,sold_at,sku,quantity,unit_price\nBOS,R,x,y,1,1\n".getBytes(StandardCharsets.UTF_8), "header");
        assertBadRequest(("\n" + HEADER + "\nBOS,R,x,y,1,1\n").getBytes(StandardCharsets.UTF_8), "header");
    }

    @Test
    void rejectsNonUtf8AndBinaryContent() {
        assertBadRequest(new byte[] {'a', ',', (byte) 0xE9, '\n'}, "UTF-8");
        assertBadRequest(new byte[] {'P', 'K', 3, 4, 0, 0, 0}, "not a CSV");
    }

    @Test
    void rejectsBrokenQuoting() {
        assertBadRequest((HEADER + "\nBOS,\"R-1,2026-09-01T10:00:00Z,TEE-1,1,1.00\n").getBytes(StandardCharsets.UTF_8),
                "line 2");
    }

    @Test
    void fileNames() {
        assertThat(ImportService.cleanFileName("sales.csv")).isEqualTo("sales.csv");
        assertThat(ImportService.cleanFileName("C:\\Users\\me\\Sales Sept.CSV")).isEqualTo("Sales Sept.CSV");
        assertThat(ImportService.cleanFileName("../../x/y.csv")).isEqualTo("y.csv");
        assertThat(ImportService.cleanFileName("a".repeat(300) + ".csv")).hasSize(255);
        assertThatThrownBy(() -> ImportService.cleanFileName("sales.xlsx")).hasMessageContaining(".csv");
        assertThatThrownBy(() -> ImportService.cleanFileName(null)).hasMessageContaining("no file name");
        assertThatThrownBy(() -> ImportService.cleanFileName("dir/")).hasMessageContaining("no file name");
    }

    @Test
    void sha256OfRawBytes() {
        assertThat(ImportService.sha256("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
