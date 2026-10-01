package com.oussamaksantini.insightstudio.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Reads generated PDFs back (PDFBox): the text of each page, in reading order. */
public final class PdfText {

    private PdfText() {
    }

    /** The text of every page, first page first. */
    public static List<String> pages(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The whole document's text. */
    public static String text(byte[] pdf) {
        return String.join("\n", pages(pdf));
    }
}
