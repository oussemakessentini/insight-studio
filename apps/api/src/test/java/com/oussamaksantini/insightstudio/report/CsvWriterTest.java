package com.oussamaksantini.insightstudio.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvWriterTest {

    @Test
    void writesHeaderAndRowsWithCrlf() {
        String csv = new CsvWriter()
                .header("name", "amount")
                .text("Tops").number(new BigDecimal("12.50")).endRow()
                .text("Shoes").number(3).endRow()
                .toString();
        assertThat(csv).isEqualTo("name,amount\r\nTops,12.50\r\nShoes,3\r\n");
    }

    @Test
    void quotesFieldsWithCommasQuotesAndLineBreaks() {
        String csv = new CsvWriter()
                .text("Home, Garden")
                .text("12\" pots")
                .text("two\nlines")
                .text("carriage\rreturn")
                .text("plain")
                .endRow()
                .toString();
        assertThat(csv).isEqualTo("\"Home, Garden\",\"12\"\" pots\",\"two\nlines\",\"carriage\rreturn\",plain\r\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1)", "\tcmd", "\rcmd"})
    void prefixesTextThatASpreadsheetWouldEvaluate(String value) {
        assertThat(CsvWriter.neutralizeFormula(value)).isEqualTo("'" + value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tops", "a=b", "'quoted", " =padded", ""})
    void leavesOrdinaryTextAlone(String value) {
        assertThat(CsvWriter.neutralizeFormula(value)).isEqualTo(value);
    }

    @Test
    void neutralizedTextIsStillQuotedWhenNeeded() {
        String csv = new CsvWriter().text("=HYPERLINK(\"http://x\",\"y\")").text("-5").endRow().toString();
        assertThat(csv).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\",'-5\r\n");
    }

    @Test
    void numbersAreNeverEscapedAndNullIsEmpty() {
        String csv = new CsvWriter()
                .number(new BigDecimal("-12.5"))
                .number(new BigDecimal("1E+3"))
                .number((BigDecimal) null)
                .text(null)
                .endRow()
                .toString();
        assertThat(csv).isEqualTo("-12.5,1000,,\r\n");
    }
}
