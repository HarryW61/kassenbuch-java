// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Report facade. The two printable reports (Kassenbuch / Buchungsliste) are rendered
 * in the exact layout of the original KB.EXE printer output - see LegacyReport and
 * docs/REPORTS.md. UST and the monthly overview are screen masks in the original
 * program, so they keep a simple ASCII layout.
 */
public final class Reports {

    private static final String RULE = "-".repeat(LegacyReport.LINE_WIDTH);

    private final List<Buchung> bookings;
    private final Stammdaten stamm;
    /** year label shown when a report covers all periods; null = derive from bookings */
    private final String fallbackYear;

    public Reports(List<Buchung> bookings, Stammdaten stamm) {
        this(bookings, stamm, null);
    }

    public Reports(List<Buchung> bookings, Stammdaten stamm, String bookingYear) {
        this.bookings = bookings;
        this.stamm = stamm;
        this.fallbackYear = bookingYear == null || bookingYear.isEmpty() ? null : bookingYear;
    }

    /* --------------------------------------------------------- unified report API */

    /** kind: kassenbuch | buchungsliste | ust | info | stamm */
    public List<List<String>> pages(String kind, String periode) {
        LegacyReport lr = new LegacyReport(bookings, stamm, fallbackYear);
        switch (kind) {
            case "kassenbuch":        return lr.kassenbuchPages(periode);
            case "buchungsliste":     return lr.buchungslistePages(periode);
            case "ust":               return chunk(ust(periode), 30);
            case "info":              return chunk(monatsInfo(), 30);
            case "stamm":             return chunk(stamm(), 30);
            default:                  return chunk("Unbekannter Bericht: " + kind, 30);
        }
    }

    /** Plain text (UTF-8, trailing blanks removed) - used by the web UI and the CLI. */
    public String report(String kind, String periode) {
        StringBuilder sb = new StringBuilder();
        for (List<String> page : pages(kind, periode)) {
            for (String line : page) sb.append(rtrim(line)).append('\n');
        }
        return sb.toString();
    }

    /** Exactly what KB.EXE wrote to the "printer" file: CRLF + codepage 437. */
    public byte[] dosReport(String kind, String periode) {
        return LegacyReport.dosBytes(pages(kind, periode));
    }

    public byte[] pdfReport(String kind, String periode) throws java.io.IOException {
        String title = cap(kind) + (periode == null || periode.isEmpty() ? "" : " " + periode);
        return PdfReport.render(pages(kind, periode), title);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String rtrim(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ' ') e--;
        return s.substring(0, e);
    }

    private static List<List<String>> chunk(String text, int linesPerPage) {
        List<List<String>> pages = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String line : text.split("\n")) {
            cur.add(line);
            if (cur.size() >= linesPerPage) { pages.add(cur); cur = new ArrayList<>(); }
        }
        if (!cur.isEmpty()) pages.add(cur);
        if (pages.isEmpty()) pages.add(new ArrayList<>());
        return pages;
    }

    /* --------------------------------------------------------- legacy convenience */

    public String kassenbuch(String periode) { return report("kassenbuch", periode); }
    public String buchungsliste(String periode) { return report("buchungsliste", periode); }

    public String stamm() {
        StringBuilder sb = new StringBuilder();
        sb.append("STAMMDATEN\n").append(RULE).append('\n');
        sb.append(String.format("Name 1     : %s%n", stamm.name1()));
        sb.append(String.format("Name 2     : %s%n", stamm.name2()));
        sb.append(String.format("Adresse    : %s, %s %s%n", stamm.strasse(), stamm.plz(), stamm.ort()));
        sb.append(String.format("Gewerbe    : %s%n", stamm.gewerbe()));
        sb.append(String.format("Finanzamt  : %s%n", stamm.finanzamt()));
        sb.append(String.format("Steuernr.  : %s%n", stamm.stnr()));
        sb.append(String.format("Telefon    : %s%n", stamm.telefon()));
        if (!stamm.email().isEmpty()) sb.append(String.format("E-Mail     : %s%n", stamm.email()));
        sb.append(String.format(Locale.US, "Steuersatz voll  : %.2f%%%n", stamm.rateFull()));
        sb.append(String.format(Locale.US, "Steuersatz verm. : %.2f%%%n", stamm.rateReduced()));
        sb.append(String.format("Periodenmodus    : %s%n", stamm.periodeMode()));
        sb.append("Einnahmetexte: ");
        for (String t : stamm.textEinnahme()) sb.append('[').append(t).append(']');
        sb.append("\nAusgabentexte: ");
        for (String t : stamm.textAusgabe()) sb.append('[').append(t).append(']');
        sb.append('\n');
        return sb.toString();
    }

    /** Umsatzsteuer-Berechnung, entspricht ust_ber() aus VORST.PRG */
    public String ust(String periode) {
        List<Buchung> rows = inPeriode(periode);
        double sum1 = 0, steu1 = 0, sum2 = 0, steu2 = 0, vorsteuer = 0;
        for (Buchung b : rows) {
            if (b.istAusgabe()) {
                vorsteuer += b.steuer();
            } else if (b.stSchluessel() == 1) {
                sum2 += b.netto();
                steu2 += b.steuer();
            } else if (b.stSchluessel() == 2) {
                sum1 += b.netto();
                steu1 += b.steuer();
            }
        }
        double bemessung = Math.floor(sum1) + Math.floor(sum2);
        double ust = Math.floor(sum1) / 100.0 * stamm.rateFull() + Math.floor(sum2) / 100.0 * stamm.rateReduced();
        StringBuilder sb = new StringBuilder();
        sb.append(legacyHeader("Umsatzsteuerberechnung", periodeLabel(periode))).append('\n');
        // no currency label - the amounts are right-justified on a fixed column so
        // they line up whatever currency the book is kept in
        String col = "%17s%n";
        sb.append(String.format(Locale.US, "Summe Erlöse voll netto          " + col, money(sum1)));
        sb.append(String.format(Locale.US, "Umsatzsteuer voll                " + col, money(steu1)));
        sb.append(String.format(Locale.US, "Summe Erlöse ermäßigt netto      " + col, money(sum2)));
        sb.append(String.format(Locale.US, "Umsatzsteuer ermäßigt            " + col, money(steu2)));
        sb.append('\n');
        sb.append(String.format(Locale.US, "Bemessungsgrundlage (gerundet)   " + col, String.format(Locale.US, "%.0f", bemessung)));
        sb.append(String.format(Locale.US, "Verbleibende UST-Steuer          " + col, money(ust)));
        sb.append(String.format(Locale.US, "Abziehbare Vorsteuerbeträge      " + col, money(vorsteuer)));
        sb.append("                                      ------------\n");
        sb.append(String.format(Locale.US, "Zahlbare Umsatzsteuer            " + col, money(ust - vorsteuer)));
        sb.append("                                     =============\n");
        return sb.toString();
    }

    /** Monatsübersicht, entspricht KASINF() aus VORST.PRG */
    public String monatsInfo() {
        double[] e = new double[13];
        double[] a = new double[13];
        for (Buchung b : bookings) {
            int m = b.datum().getMonthValue();
            if (b.istAusgabe()) a[m] += b.netto(); else e[m] += b.netto();
        }
        String[] names = {"Jan", "Feb", "Mar", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        String year = fallbackYear != null ? fallbackYear : bookingYear();
        StringBuilder sb = new StringBuilder();
        sb.append(legacyHeader("Kassen-Information", "Jahr " + year)).append('\n');
        sb.append(String.format("%-8s %14s %14s %14s%n", "Monat", "Einnahmen", "Ausgaben", "Ergebnis"));
        sb.append(RULE).append('\n');
        double ge = 0, ga = 0;
        for (int i = 1; i <= 12; i++) {
            sb.append(monthRow(names[i - 1], e[i], a[i]));
            ge += e[i]; ga += a[i];
        }
        sb.append(RULE).append('\n');
        sb.append(monthRow("Gesamt", ge, ga));
        return sb.toString();
    }

    /** one line of the monthly overview; every amount goes through money() */
    private static String monthRow(String label, double einnahmen, double ausgaben) {
        return String.format("%-8s %14s %14s %14s%n",
                label, money(einnahmen), money(ausgaben), money(Buchung.round(einnahmen - ausgaben)));
    }

    /* ----------------------------------------------------------- helpers */

    private String legacyHeader(String title, String periode) {
        return RULE + "\n"
                + title + " für " + stamm.name1() + " " + stamm.name2() + " ".repeat(30) + "Zeitraum:  " + periode + "\n"
                + stamm.strasse() + "   " + stamm.plz() + " " + stamm.ort() + "\n"
                + "Gewerbe: " + stamm.gewerbe() + "               Finanzamt: " + stamm.finanzamt()
                + "                          Steuernummer: " + stamm.stnr() + "\n"
                + RULE;
    }

    /** header label: an empty period means all periods, which is the booking year */
    private String periodeLabel(String periode) {
        return periode == null || periode.isEmpty() ? bookingYear() : periode;
    }

    /** the year of the entered bookings, same 19YY/20YY reading as LegacyReport */
    private String bookingYear() {
        return new LegacyReport(bookings, stamm).bookingYear();
    }

    private List<Buchung> inPeriode(String periode) {
        return periode == null || periode.isEmpty()
                ? bookings
                : bookings.stream().filter(b -> b.periode().equals(periode)).toList();
    }

    /**
     * Amounts are shown with two decimals. The value is rounded to cents first, so a
     * binary sum that lands on 386.90999999999997 is displayed as 386.91 instead of
     * being truncated to 386.90.
     */
    private static String money(double v) {
        return String.format(java.util.Locale.US, "%.2f", Buchung.round(v));
    }
}
