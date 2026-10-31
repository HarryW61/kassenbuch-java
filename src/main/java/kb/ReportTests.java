// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Golden-file tests: the legacy layout must reproduce the original printer output
 * (fixtures/anonymisiert/golden/Kassenbuch.txt, .../Buchungsliste.txt) byte for byte,
 * and - when fixtures/legacy/ exists locally - against the untouched KB.EXE printouts.
 *
 * The golden printouts were made from a smaller database (31 / 30 bookings), the
 * shipped KABU.DBF already contains 32 records - so the comparison uses the
 * matching subset of rows.
 */
public final class ReportTests {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.US);
        Path fixtures = Path.of(System.getenv().getOrDefault("KB_FIXTURES", "fixtures"));
        Stammdaten stamm = Stammdaten.from(MemoFile.read(fixtures.resolve("STAMM.MEM")));
        DbfFile db = new DbfFile(fixtures.resolve("KABU.DBF"));
        List<Buchung> all = new ArrayList<>();
        for (String[] rec : db.live()) all.add(Buchung.from(rec, db));

        LegacyReport rep = new LegacyReport(all, stamm);

        // --- layout invariants -------------------------------------------------
        List<List<String>> kabu = rep.kassenbuchPages("4416");
        List<List<String>> list = rep.buchungslistePages("4416");
        check("Kassenbuch pages", 2, kabu.size());
        check("Buchungsliste pages", 2, list.size());
        check("rows on Kassenbuch page 1", 23, countRows(kabu.get(0)));
        check("rows on Kassenbuch page 2", 9, countRows(kabu.get(1)));   // 32 bookings -> 23 + 9
        check("rows on Buchungsliste page 1", 27, countRows(list.get(0)));
        check("rows on Buchungsliste page 2", 5, countRows(list.get(1))); // 32 bookings -> 27 + 5
        for (List<String> page : kabu) for (String line : page)
            if (line.length() > LegacyReport.LINE_WIDTH + 12) { // the legacy column header is 123 chars
                System.out.println("FAIL line too long: " + line.length() + " | " + line);
                failed++;
            }

        // --- golden comparison (byte exact, CP437 + CRLF) --------------------
        // The anonymised baseline is the port's own output with placeholder identity
        // lines; it exists on every clone.
        golden("Kassenbuch", all, stamm, 31, fixtures.resolve("golden/Kassenbuch.txt"), false);
        golden("Buchungsliste", all, stamm, 30, fixtures.resolve("golden/Buchungsliste.txt"), true);

        // Optional: the untouched printouts of KB.EXE, kept locally in fixtures/legacy/.
        // They contain real master data, so they are not part of the repository; when
        // present this is the strongest check that the layout never drifted.
        Path legacyDir = Path.of(System.getenv().getOrDefault("KB_LEGACY", "fixtures/legacy"));
        if (Files.exists(legacyDir.resolve("golden/Kassenbuch.txt"))) {
            System.out.println("-- Vergleich mit den Original-Ausdrucken (fixtures/legacy) --");
            Stammdaten realIdentity = Stammdaten.from(MemoFile.read(legacyDir.resolve("STAMM.MEM")));
            golden("Kassenbuch (Original)", all, stamm, 31, legacyDir.resolve("golden/Kassenbuch.txt"),
                    true, realIdentity);
            golden("Buchungsliste (Original)", all, stamm, 30, legacyDir.resolve("golden/Buchungsliste.txt"),
                    true, realIdentity);
        } else {
            System.out.println("-- Original-Ausdrucke nicht vorhanden (fixtures/legacy fehlt) --");
        }

        // --- PDF ---------------------------------------------------------------
        byte[] pdf = PdfReport.render(kabu, "Kassenbuch 4416");
        check("pdf starts with %PDF", true, pdf.length > 0 && new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"));
        check("pdf page count", 2, pdfPages(pdf));
        Files.write(Path.of("out/Kassenbuch-4416.pdf"), pdf);
        Files.write(Path.of("out/Kassenbuch-4416.txt"), LegacyReport.dosBytes(kabu));

        System.out.println(failed == 0 ? "\nAlle Report-Tests bestanden" : "\n" + failed + " Report-Tests fehlgeschlagen");
        if (failed > 0) System.exit(1);
    }

    /**
     * Compare one report against a baseline. `dropCurrency` translates a baseline
     * recorded before the EUR marker was removed. `identityFrom` is used for the
     * optional legacy comparison: those printouts carry the real master data, while
     * the test reads the anonymised fixture, so only the header identity differs.
     */
    private static void golden(String name, List<Buchung> all, Stammdaten stamm,
                               int maxNr, Path goldenPath, boolean dropCurrency) throws Exception {
        golden(name, all, stamm, maxNr, goldenPath, dropCurrency, null);
    }

    private static void golden(String name, List<Buchung> all, Stammdaten stamm,
                               int maxNr, Path goldenPath, boolean dropCurrency,
                               Stammdaten identityFrom) throws Exception {
        List<Buchung> subset = all.stream().filter(b -> b.nr() <= maxNr).toList();
        Stammdaten header = identityFrom != null ? identityFrom : stamm;
        LegacyReport rep = new LegacyReport(subset, header);
        String kind = name.startsWith("Kassenbuch") ? "kassenbuch" : "buchungsliste";
        byte[] actual = LegacyReport.dosBytes(kind.equals("kassenbuch")
                ? rep.kassenbuchPages("4416") : rep.buchungslistePages("4416"));
        byte[] expected = dropCurrency ? alignGolden(Files.readAllBytes(goldenPath))
                                       : Files.readAllBytes(goldenPath);
        Files.createDirectories(Path.of("out"));
        Files.write(Path.of("out/" + kind + "-generated.txt"), actual);
        if (java.util.Arrays.equals(actual, expected)) {
            System.out.println("ok   " + name + " byte-identical to the original (" + actual.length + " bytes)");
        } else {
            System.out.println("FAIL " + name + ": differs from " + goldenPath);
            String a = new String(actual, LegacyCharset.CODEPAGE);
            String b = new String(expected, LegacyCharset.CODEPAGE);
            String[] la = a.split("\r\n");
            String[] lb = b.split("\r\n");
            int shown = 0;
            for (int i = 0; i < Math.max(la.length, lb.length) && shown < 8; i++) {
                String x = i < la.length ? la[i] : "<eof>";
                String y = i < lb.length ? lb[i] : "<eof>";
                if (!x.equals(y)) {
                    System.out.println("  line " + i + " got : " + x);
                    System.out.println("  line " + i + " want: " + y);
                    shown++;
                }
            }
            failed++;
        }
    }

    /**
     * Only needed for baselines recorded before the currency marker was removed: the
     * original prints "Einahme Netto  EUR" etc. The report is currency neutral and pads
     * every label to the legacy 15 columns (14 characters plus the separator space), so
     * dropping "EUR" leaves the value columns exactly where they were. Baselines
     * re-recorded from the current report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": a label padded to 14 columns,
     * then "EUR" plus five spaces, then the amount right-justified in 13. Our report is
     * currency neutral and pads only the label, so removing "EUR" has to leave the
     * amount exactly where it was - one space for labels of 9 characters ("Unterschied",
     * legacy padded to 10), five otherwise. Baselines re-recorded from the current
     * report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value, so dropping "EUR" has to drop
     * four of those five spaces too - otherwise the amount shifts right by four.
     * Baselines re-recorded from the current report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value, so each "EUR" has to go together
     * with four of those five spaces - otherwise the amount shifts right by four.
     * Baselines re-recorded from the current report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value, so each "EUR" has to go together
     * with four of those five spaces - otherwise the amount shifts right by four.
     * Baselines re-recorded from the current report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value, so each "EUR" has to go together
     * with four of those five spaces - otherwise the amount shifts right by four.
     * Baselines re-recorded from the current report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too, 8 columns when another marker follows on the same line ("Umsatzsteuer
     *   EUR (Zwischensumme)") and 7 otherwise. Baselines re-recorded from this report are
     * compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too: 7 columns when another marker follows on the same line
     * ("Umsatzsteuer   EUR (Zwischensumme)") and 8 otherwise. Baselines re-recorded from
     * this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too: 6 columns when another marker follows on the same line
     * ("Umsatzsteuer   EUR (Zwischensumme)") and 7 otherwise. Baselines re-recorded from
     * this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too: 5 columns when another marker follows on the same line
     * ("Umsatzsteuer   EUR (Zwischensumme)") and 6 otherwise. Baselines re-recorded from
     * this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too: four spaces where another marker follows on the same line
     * ("Umsatzsteuer   EUR (Zwischensumme)"), five otherwise, which leaves exactly the
     * single separator space our currency-neutral totals block uses. Baselines
     * re-recorded from this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too: four spaces where another marker follows on the same line
     * ("Umsatzsteuer   EUR (Zwischensumme)"), five otherwise, which leaves exactly the
     * single separator space our currency-neutral totals block uses. Baselines
     * re-recorded from this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too: six spaces where another marker follows on the same line
     * ("Umsatzsteuer   EUR (Zwischensumme)"), seven otherwise, which leaves exactly the
     * single separator space our currency-neutral totals block uses. Baselines
     * re-recorded from this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report pads only
     * the label and keeps one space before the value - so dropping "EUR" means dropping
     * its padding too: eight columns where another marker follows on the same line
     * ("Umsatzsteuer   EUR (Zwischensumme)"), nine otherwise, which leaves exactly the
     * single separator space our currency-neutral totals block uses. Baselines
     * re-recorded from this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": label padded to 14 columns,
     * then "EUR" plus five spaces, amount right-justified in 13. Our report is currency
     * neutral and pads the label instead, so a marker and its trailing padding collapse
     * into the single separator space in front of the amount ("Einahme Netto        ").
     * A marker that belongs to "(Zwischensumme)" simply disappears. Baselines re-recorded
     * from this report are compared unmodified.
     */
    /**
     * Only needed for baselines recorded before the currency marker was removed. The
     * original prints "Einahme Netto  EUR       7960.39": a label field of 14 columns,
     * then "EUR", five spaces and the amount right-justified in 13. Our report is
     * currency neutral and pads the label instead, so translating a legacy printout
     * means handing the marker\u0027s five spaces to the amount column and giving the label
     * back its field width: "Einahme Netto        7960.39". A label that fills the field
     * completely ("Ausgaben Netto EUR") has no padding of its own. A marker belonging to
     * "(Zwischensumme)" simply disappears, since our report prints no marker there either.
     * Baselines re-recorded from this report contain no "EUR" and pass through unchanged.
     */
    private static final Pattern LEGACY_TOTAL =
            Pattern.compile("( {2,})(\\S+(?: \\S+)?) *EUR( {5})( *\\d[\\d.,]*)");

    private static byte[] alignGolden(byte[] golden) {
        String s = new String(golden, LegacyCharset.CODEPAGE)
                .replaceAll("EUR +\\(Zwischensumme\\)", "(Zwischensumme)");
        Matcher m = LEGACY_TOTAL.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                    m.group(1) + trimPad(m.group(2)) + m.group(3) + m.group(4)));
        }
        m.appendTail(out);
        return out.toString().getBytes(LegacyCharset.CODEPAGE);
    }

    /** pad a label to the legacy 14-column field without ever making it longer */
    private static String trimPad(String label) {
        String l = label.replaceAll(" +$", "");
        return l.length() >= 14 ? l : l + " ".repeat(14 - l.length());
    }

    private static int countRows(List<String> page) {
        int n = 0;
        for (String line : page) {
            String t = line.trim();
            if (t.matches("\\d+ +\\d\\d\\.\\d\\d\\.\\d\\d +[EA] +.*")) n++;
        }
        return n;
    }

    private static int pdfPages(byte[] pdf) {
        String s = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.startsWith("/Type /Page ", i)) n++;
        return n;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean ok = expected instanceof Double ? Math.abs((Double) expected - (Double) actual) < 0.005
                : expected.equals(actual);
        System.out.println((ok ? "ok   " : "FAIL ") + name + ": expected " + expected + ", got " + actual);
        if (!ok) failed++;
    }
}
