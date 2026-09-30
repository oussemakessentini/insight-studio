package com.oussamaksantini.insightstudio.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oussamaksantini.insightstudio.importing.CsvParser.CsvRecord;
import com.oussamaksantini.insightstudio.importing.CsvParser.CsvSyntaxException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvParserTest {

    private static List<CsvRecord> parse(String text) {
        return CsvParser.parse(text, Integer.MAX_VALUE);
    }

    @Test
    void splitsPlainFieldsAndRecords() {
        assertThat(parse("a,b,c\n1,2,3\n")).containsExactly(
                new CsvRecord(1, List.of("a", "b", "c")),
                new CsvRecord(2, List.of("1", "2", "3")));
    }

    @Test
    void lastRecordWithoutTrailingNewline() {
        assertThat(parse("a,b\n1,2")).containsExactly(
                new CsvRecord(1, List.of("a", "b")),
                new CsvRecord(2, List.of("1", "2")));
    }

    @Test
    void crlfAndLoneCrEndRecords() {
        assertThat(parse("a,b\r\n1,2\r\n3,4\r5,6\r\n")).containsExactly(
                new CsvRecord(1, List.of("a", "b")),
                new CsvRecord(2, List.of("1", "2")),
                new CsvRecord(3, List.of("3", "4")),
                new CsvRecord(4, List.of("5", "6")));
    }

    @Test
    void keepsEmptyFields() {
        assertThat(parse(",x,\n")).containsExactly(new CsvRecord(1, List.of("", "x", "")));
        assertThat(parse("\"\",\"\"\n")).containsExactly(new CsvRecord(1, List.of("", "")));
    }

    @Test
    void quotedFieldsMayContainCommasAndEscapedQuotes() {
        assertThat(parse("\"a,b\",\"say \"\"hi\"\"\",c\n")).containsExactly(
                new CsvRecord(1, List.of("a,b", "say \"hi\"", "c")));
    }

    @Test
    void quotedNewlinesKeepPhysicalLineNumbers() {
        List<CsvRecord> records = parse("h1,h2\n\"multi\nline\",x\r\n\"two\r\nmore\nlines\",y\nlast,z\n");
        assertThat(records).containsExactly(
                new CsvRecord(1, List.of("h1", "h2")),
                new CsvRecord(2, List.of("multi\nline", "x")),
                new CsvRecord(4, List.of("two\nmore\nlines", "y")),
                new CsvRecord(7, List.of("last", "z")));
    }

    @Test
    void skipsBlankLinesButCountsThem() {
        assertThat(parse("a\n\n\r\nb\n\n")).containsExactly(
                new CsvRecord(1, List.of("a")),
                new CsvRecord(4, List.of("b")));
    }

    @Test
    void keepsSpacesForTheCallerToTrim() {
        assertThat(parse(" a , b \n")).containsExactly(new CsvRecord(1, List.of(" a ", " b ")));
    }

    @Test
    void stopsAtTheRecordLimit() {
        assertThat(CsvParser.parse("1\n2\n3\n4\n", 2)).extracting(CsvRecord::line).containsExactly(1, 2);
    }

    @Test
    void emptyInput() {
        assertThat(parse("")).isEmpty();
        assertThat(parse("\n\r\n")).isEmpty();
    }

    @Test
    void unterminatedQuoteReportsTheLineWhereItOpened() {
        assertThatThrownBy(() -> parse("a,b\n1,\"open\n2,3\n"))
                .isInstanceOfSatisfying(CsvSyntaxException.class, e -> assertThat(e.line()).isEqualTo(2))
                .hasMessageContaining("never closed");
    }

    @Test
    void textAfterClosingQuoteIsAnError() {
        assertThatThrownBy(() -> parse("a\n\"x\"y\n"))
                .isInstanceOfSatisfying(CsvSyntaxException.class, e -> assertThat(e.line()).isEqualTo(2));
    }

    @Test
    void quoteInsideUnquotedValueIsAnError() {
        assertThatThrownBy(() -> parse("a\n\nab\"c\n"))
                .isInstanceOfSatisfying(CsvSyntaxException.class, e -> assertThat(e.line()).isEqualTo(3));
    }
}
