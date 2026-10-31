package kb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Golden-file tests: the legacy layout must reproduce the original printer output
 * (fixtures/golden/Kassenbuch.txt, fixtures/golden/Buchungsliste.txt) byte for byte.
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
        golden("Kassenbuch", all, stamm, 31, fixtures.resolve("golden/Kassenbuch.txt"));
        golden("Buchungsliste", all, stamm, 30, fixtures.resolve("golden/Buchungsliste.txt"));

        // --- PDF ---------------------------------------------------------------
        byte[] pdf = PdfReport.render(kabu, "Kassenbuch 4416");
        check("pdf starts with %PDF", true, pdf.length > 0 && new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"));
        check("pdf page count", 2, pdfPages(pdf));
        Files.write(Path.of("out/Kassenbuch-4416.pdf"), pdf);
        Files.write(Path.of("out/Kassenbuch-4416.txt"), LegacyReport.dosBytes(kabu));

        System.out.println(failed == 0 ? "\nAlle Report-Tests bestanden" : "\n" + failed + " Report-Tests fehlgeschlagen");
        if (failed > 0) System.exit(1);
    }

    private static void golden(String name, List<Buchung> all, Stammdaten stamm,
                               int maxNr, Path goldenPath) throws Exception {
        List<Buchung> subset = all.stream().filter(b -> b.nr() <= maxNr).toList();
        LegacyReport rep = new LegacyReport(subset, stamm);
        byte[] actual = LegacyReport.dosBytes(name.equals("Kassenbuch")
                ? rep.kassenbuchPages("4416") : rep.buchungslistePages("4416"));
        byte[] expected = alignGolden(Files.readAllBytes(goldenPath));
        Files.createDirectories(Path.of("out"));
        Files.write(Path.of("out/" + name + "-generated.txt"), actual);
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
     * The original Kassenbuch prints "EUR" in front of every amount; the report is
     * currency neutral instead. The golden printout is compared with that marker
     * dropped, and the label column widened by one space for labels shorter than the
     * legacy 14 characters ("Unterschied    EUR") so the value columns stay aligned.
     */
    private static byte[] alignGolden(byte[] golden) {
        String s = new String(golden, LegacyCharset.CODEPAGE);
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\r\n", -1)) {
            int i = line.indexOf("EUR");
            if (i >= 0) {
                String label = line.substring(0, i).replaceAll(" +$", "");
                out.append(label).append(" ".repeat(Math.max(14, label.length()) + 1))
                   .append(line.substring(i + 3));
            } else out.append(line);
            out.append("\r\n");
        }
        if (out.length() >= 2) out.setLength(out.length() - 2);   // the file ends without a trailing CRLF
        return out.toString().getBytes(LegacyCharset.CODEPAGE);
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
