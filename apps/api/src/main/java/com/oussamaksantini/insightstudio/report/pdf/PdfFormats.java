package com.oussamaksantini.insightstudio.report.pdf;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Locale;

/**
 * How values are written in report PDFs. English, US-style grouping; money always with the two
 * decimals of the JSON values (so the PDF shows exactly the API's figures) and the business
 * currency's symbol. Public so tests can expect the same text.
 */
public final class PdfFormats {

    private static final Locale LOCALE = Locale.US;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ENGLISH);

    private PdfFormats() {
    }

    /** {@code €1,234.50}, {@code CHF 1,234.50}; {@code currency} is an ISO 4217 code. */
    public static String money(BigDecimal value, String currency) {
        Currency unit = Currency.getInstance(currency);
        DecimalFormat format = (DecimalFormat) NumberFormat.getCurrencyInstance(LOCALE);
        format.setCurrency(unit);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        String symbol = unit.getSymbol(LOCALE);
        if (Character.isLetter(symbol.charAt(symbol.length() - 1))) {
            // A code such as CHF reads better apart from the number.
            format.setPositivePrefix(symbol + " ");
            format.setNegativePrefix("-" + symbol + " ");
        }
        return format.format(value == null ? BigDecimal.ZERO : value);
    }

    /** {@code 12,345}. */
    public static String count(long value) {
        return NumberFormat.getIntegerInstance(LOCALE).format(value);
    }

    /** {@code 47.1%}. */
    public static String percent(BigDecimal value) {
        return value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** {@code +7.1%} / {@code −3.2%} (with a minus sign), or an em dash when there is no comparison. */
    public static String change(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        String digits = value.abs().setScale(1, java.math.RoundingMode.HALF_UP).toPlainString();
        return switch (value.signum()) {
            case 1 -> "+" + digits + "%";
            case -1 -> "−" + digits + "%";
            default -> digits + "%";
        };
    }

    /** {@code 1 Jul 2026}. */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    /** {@code July 2026}. */
    public static String month(LocalDate firstDay) {
        return MONTH.format(firstDay);
    }

    /** {@code 1 Jul 2026 – 30 Sep 2026 (92 days)}. */
    public static String period(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        return "%s – %s (%s %s)".formatted(day(from), day(to), count(days), days == 1 ? "day" : "days");
    }

    /** {@code 1 Oct 2026, 11:00}. */
    public static String dateTime(LocalDateTime value) {
        return DATE_TIME.format(value);
    }
}
