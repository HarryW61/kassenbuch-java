// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Faithful reproduction of the KB.EXE "printer" output (PRINT.PRG -> DRBULI / DRKABU).
 *
 * Layout facts taken from the original source and the golden files
 * fixtures/anonymisiert/golden/{Buchungsliste,Kassenbuch}.txt:
 *
 *  - every line is at most 113 columns wide ("-" * 113 rule)
 *  - the header (dr_head) repeats on every page
 *  - page break: >26 rows for the Buchungsliste, >22 rows for the Kassenbuch
 *  - the Kassenbuch prints cumulative "Zwischensumme" totals at every page break
 *  - column positions:
 *      Bu.Nr.        0..3   (number, right justified in 4)
 *      B.Datum       7..14  (dd.mm.yy - legacy 2 digit year)
 *      Bu.Art        18..21 (dBase C(4) field, printed padded)
 *      Text          25..44 (SUBSTR(BU_TEXT,1,20))
 *      Einnahme      49..56 (BU_BETRAG, N(8,2))
 *      Ausgabe.Brt.  65..72
 *      Ausgabe.Nto   77..89 (computed, printed in 13 columns)
 *      Steuer        94..101
 *      Steuerschluessel 107..121 (header) / 110 (value)
 *
 * Deliberate improvement over KB.EXE: rows with Schluessel 0 print the gross
 * amount in the "Ausgabe.Nto" column of the original in a wrong column
 * (SPACE(5) + BU_BETRAG); we keep it right-justified in the 13 column net slot.
 */
public final class LegacyReport {

    public static final int LINE_WIDTH = 113;
    private static final String RULE = "-".repeat(LINE_WIDTH);
    private static final String FINAL_RULE = " ".repeat(10) + "-".repeat(73);
    private static final int MAX_ROWS_LIST = 27;   // IF nZeile > 26
    private static final int MAX_ROWS_KABU = 23;   // IF nZeile > 22

    private final List<Buchung> bookings;
    private final Stammdaten stamm;
    /** year label shown when a report covers all periods; null = derive from bookings */
    private final String fallbackYear;

    public LegacyReport(List<Buchung> bookings, Stammdaten stamm) {
        this(bookings, stamm, null);
    }

    public LegacyReport(List<Buchung> bookings, Stammdaten stamm, String fallbackYear) {
        this.bookings = bookings;
        this.stamm = stamm;
        this.fallbackYear = fallbackYear == null || fallbackYear.isEmpty() ? null : fallbackYear;
    }

    /* ---------------------------------------------------------------- public API */

    /** One entry per printed page, each entry is the list of lines of that page. */
    public List<List<String>> kassenbuchPages(String periode) {
        return build("Kassenbuch", inPeriode(periode), MAX_ROWS_KABU, true, periode);
    }

    public List<List<String>> buchungslistePages(String periode) {
        return build("Buchungsliste", inPeriode(periode), MAX_ROWS_LIST, false, periode);
    }

    /** Whole report as plain text (LF lines). */
    public String kassenbuchText(String periode) { return join(kassenbuchPages(periode)); }
    public String buchungslisteText(String periode) { return join(buchungslistePages(periode)); }

    /** Exactly the bytes KB.EXE would have written: CRLF line endings, codepage 437. */
    public static byte[] dosBytes(List<List<String>> pages) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (List<String> page : pages) {
            for (String line : page) {
                if (!first) sb.append("\r\n");
                sb.append(line);
                first = false;
            }
        }
        return sb.toString().getBytes(LegacyCharset.CODEPAGE);
    }

    private static String join(List<List<String>> pages) {
        StringBuilder sb = new StringBuilder();
        for (List<String> page : pages) for (String line : page) sb.append(rtrim(line)).append('\n');
        return sb.toString();
    }

    /** xBase pads lines with spaces; we keep the padding but do not store trailing blanks. */
    private static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ' ') e--;
        return s.substring(0, e);
    }

    /* ---------------------------------------------------------------- internals */

    private List<Buchung> inPeriode(String periode) {
        if (periode == null || periode.isEmpty()) {
            return bookings;
        }
        return bookings.stream().filter(b -> b.periode().equals(periode)).toList();
    }

    private static String num8(double v) { return String.format(Locale.US, "%8.2f", v); }

    /** KB.EXE never stores the net amount - it computes betrag / (100 + rate) * 100 while printing. */
    private double legacyNetto(Buchung b) {
        if (b.stSchluessel() == 0) return b.betrag();
        double rate = b.stSchluessel() == 1 ? stamm.rateReduced() : stamm.rateFull();
        return b.betrag() / (100.0 + rate) * 100.0;
    }
    private static String num13(double v) { return String.format(Locale.US, "%13.2f", v); }

    private static String legacyDate(LocalDate d) {
        return String.format("%02d.%02d.%02d", d.getDayOfMonth(), d.getMonthValue(), d.getYear() % 100);
    }

    private static String text20(String t) {
        if (t == null) t = "";
        if (t.length() > 20) t = t.substring(0, 20);
        return String.format("%-20s", t);
    }

    private String labelPeriod(String periode) {
        if (periode != null && !periode.isEmpty()) return periode;
        // "alle Perioden" prints the booking year, as KB.EXE did with an empty period
        return bookingYear();
    }

    /**
     * The year the entered bookings belong to. Dates are stored as 19YYMMDD, so YY is
     * mapped into the window 2001..2080: a booking entered as 01.01.26 for the 2026
     * tax year stays 2026, while 01.01.99 from the old data stays 1999.
     */
    String bookingYear() {
        java.util.TreeSet<Integer> years = new java.util.TreeSet<>();
        for (Buchung b : bookings) years.add(legacyYear(b.datum().getYear()));
        if (!years.isEmpty()) return firstOrRange(years);
        if (fallbackYear != null) return fallbackYear;
        return String.valueOf(java.time.LocalDate.now().getYear());
    }

    private static String firstOrRange(java.util.TreeSet<Integer> years) {
        int first = years.first(), last = years.last();
        return first == last ? String.valueOf(first) : first + " - " + last;
    }

    static int legacyYear(int y) {
        return y <= 100 ? 2000 + y : (y < 2001 ? 1900 + y : y);
    }

    private List<List<String>> build(String title, List<Buchung> rows, int rowsPerPage,
                                     boolean withTotals, String requestedPeriod) {
        List<List<String>> pages = new ArrayList<>();
        List<String> page = new ArrayList<>();
        String periode = requestedPeriod != null && !requestedPeriod.isEmpty()
                ? requestedPeriod : labelPeriod(null);

        double sumENet = 0, sumVorst = 0, sumANet = 0, sumMwst = 0;
        int inPage = 0, pageNo = 1;

        for (Buchung b : rows) {
            if (inPage == 0) {
                header(page, title, periode, pageNo == 1, withTotals);
                page.add(" ".repeat(100) + "Seite " + pageNo);
                page.add(columnHeader(pageNo == 1, withTotals));
                page.add(RULE);
            }
            page.add(row(b));
            inPage++;
            if (withTotals) {
                double net = legacyNetto(b);
                if (b.istAusgabe()) {
                    sumANet += net;
                    sumMwst += b.steuer();
                } else {
                    sumENet += net;
                    sumVorst += b.steuer();
                }
            }
            if (inPage >= rowsPerPage) {
                if (withTotals) {
                    page.add("");
                    page.add(RULE);
                    addTotals(page, sumENet, sumVorst, sumANet, sumMwst, true);
                }
                pages.add(page);
                page = new ArrayList<>();
                inPage = 0;
                pageNo++;
            }
        }

        if (page.isEmpty()) {                      // no bookings at all -> still print a header
            header(page, title, periode, true, withTotals);
            page.add(" ".repeat(100) + "Seite 1");
            page.add(columnHeader(true, withTotals));
            page.add(RULE);
        }

        if (withTotals) {
            page.add("");
            page.add(RULE);
            addTotals(page, sumENet, sumVorst, sumANet, sumMwst, false);
            page.add(FINAL_RULE);
            // same value column as addTotals(): the label keeps its legacy width
            String diff = " ".repeat(10) + padLabel("Unterschied") + " ";
            page.add(diff + num13(sumENet - sumANet) + diff + num13(sumVorst - sumMwst));
        }
        page.add("");
        page.add("   >>>>>> Anzahl Buchungen " + rows.size() + "  <<<<<<  ");
        page.add("   >>>>>> Ende " + title + (withTotals ? " " + periode : "") + " <<<<<<  ");
        pages.add(page);
        return pages;
    }

    /**
     * Header block as printed by dr_head(). The blank-line behaviour differs between
     * the two procedures in the original source and is reproduced literally:
     *   - DRKABU page 1: two blank lines (PR_ON preamble + "Querformat" preamble)
     *   - DRKABU page N>1: one blank line (the "?" before dr_head in the page break)
     *   - DRBULI page 1: one blank line (PR_ON preamble), page N>1: none
     */
    private void header(List<String> out, String title, String periode, boolean firstPage, boolean withTotals) {
        if (firstPage) out.add("");
        else if (withTotals) out.add("");
        if (firstPage && title.equals("Kassenbuch")) out.add(""); // DRKABU "Querformat" preamble
        out.add(RULE);
        out.add(title + " für " + stamm.name1() + " " + stamm.name2()
                + " ".repeat(30) + "Zeitraum:  " + periode);
        out.add(stamm.strasse() + "   " + stamm.plz() + " " + stamm.ort());
        out.add("Gewerbe: " + stamm.gewerbe() + "               Finanzamt: " + stamm.finanzamt()
                + "                          Steuernummer: " + stamm.stnr());
        out.add(RULE);
    }

    /**
     * The original PRINT.PRG contains two different spellings of the last column:
     * DRKABU uses "Steuerschlüssel" on page 1 and "Steuerschluessel" on the page
     * break, DRBULI the other way round. Reproduced literally so the output is
     * byte-identical to the golden files.
     */
    private String columnHeader(boolean firstPage, boolean withTotals) {
        boolean umlaut = withTotals ? firstPage : !firstPage;
        return "Bu.Nr.  B.Datum   Bu.Art   Text                   "
                + "Einnahme     Ausgabe.Brt.    Ausgabe.Nto     Steuer      "
                + (umlaut ? "Steuerschlüssel" : "Steuerschluessel");
    }

    private String row(Buchung b) {
        String head = String.format("%4d", b.nr()) + "   " + legacyDate(b.datum())
                + "   " + String.format("%-4s", b.art()) + "   " + text20(b.text());
        if (b.istAusgabe()) {
            double net = legacyNetto(b);
            return head + "                    " + num8(b.betrag()) + "    " + num13(net)
                    + "    " + num8(b.steuer()) + "        " + b.stSchluessel();
        }
        return head + "    " + num8(b.betrag()) + "                                     "
                + num8(b.steuer()) + "        " + b.stSchluessel();
    }

    /**
     * Totals block of the Kassenbuch. The original PRINT.PRG prints "EUR" in front of
     * every amount; it is left out here so the report stays currency neutral - the
     * label column keeps its width and the value column starts at the same offset.
     */
    /**
     * Label column of the totals block. The legacy report wrote a fixed 14-character
     * label ("Einahme Netto  EUR") followed by one space; the currency marker is gone,
     * so the label is padded to the same 14 characters and every value keeps its offset.
     */
    private static String padLabel(String label) {
        return label.length() >= 14 ? label : label + " ".repeat(14 - label.length());
    }

    private static void addTotals(List<String> out, double eNet, double ust, double aNet, double mwst, boolean interim) {
        // legacy: "Einahme" is spelled like that. One space instead of the removed
        // currency marker, so the label column keeps its width of 14 plus one space.
        String tag = interim ? " (Zwischensumme)" : " ";
        out.add(" ".repeat(10) + padLabel("Einahme Netto") + tag + num13(eNet)
                + " ".repeat(10) + padLabel("Umsatzsteuer") + tag + num13(ust));
        out.add(" ".repeat(10) + padLabel("Ausgaben Netto") + tag + num13(aNet)
                + " ".repeat(10) + padLabel("Mehrwertsteuer") + tag + num13(mwst));
    }
}
