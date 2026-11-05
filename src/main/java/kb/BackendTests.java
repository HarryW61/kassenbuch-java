// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Backend / interchange tests: JSON and CSV are written out of KABU.DBF and
 * read back into it.
 *
 * Source of truth is the current data/KABU.DBF (KB_DATA_SRC). The destructive part -
 * importing - runs against a scratch copy in /tmp, so the real file is never modified.
 */
public final class BackendTests {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        Path work = Path.of(System.getenv().getOrDefault("KB_BACKEND_DATA", "/tmp/kbbackend-data"));

        // the directory whose KABU.DBF must survive this run untouched
        Path source = Path.of(System.getenv().getOrDefault("KB_DATA_SRC", "data"));

        // never let a misconfigured scratch directory point back at the real data
        if (work.toAbsolutePath().equals(source.toAbsolutePath()))
            throw new IllegalStateException("KB_BACKEND_DATA must differ from KB_DATA_SRC: " + work);

        Path src = source;
        // A fresh clone ships data/KABU.DBF empty, and an empty book has nothing to import
        // or round-trip. Fall back to the anonymised fixtures whenever there are no bookings
        // to work with - in the shipped data/, in a directory of your own that happens to be
        // empty, or when data/ is missing altogether. Read from there only: everything
        // destructive still happens in `work`.
        if (!hasBookings(src)) {
            src = Path.of(System.getenv().getOrDefault("KB_FIXTURES", "fixtures/anonymisiert"));
            System.out.println("keine Buchungen in " + source + " - Backend-Tests laufen lesend gegen "
                    + src.resolve("KABU.DBF"));
        }
        if (!Files.exists(src.resolve("KABU.DBF"))) {
            System.out.println("FAIL KABU.DBF fehlt in " + src);
            System.exit(1);
        }
        System.out.println("Quelle: " + src.resolve("KABU.DBF") + "  Arbeitsexemplar: " + work.resolve("KABU.DBF"));


        Files.createDirectories(work);
        for (String f : new String[]{"KABU.DBF", "JABU.DBF", "STAMM.MEM"})
            Files.copy(src.resolve(f), work.resolve(f), StandardCopyOption.REPLACE_EXISTING);

        Store live = new Store(src);
        List<Buchung> orig = live.bookingsFor(null);
        check("KABU.DBF gelesen", true, !orig.isEmpty());
        double sumOrig = sum(orig, Buchung::betrag);

        // ---------------------------------------------------------------- exports
        String csv = live.exportCsv(null);
        String json = live.exportJson(null);
        Files.writeString(Path.of("out/KABU-alle.csv"), csv);
        Files.writeString(Path.of("out/KABU-alle.json"), json);

        check("csv export: Zeilen", orig.size(), BookingsIo.parseCsv(csv).size());
        check("json export: Zeilen", orig.size(), BookingsIo.parseJson(json).size());

        sameRows(orig, BookingsIo.parseCsv(csv), "csv Round-Trip");
        sameRows(orig, BookingsIo.parseJson(json), "json Round-Trip");

        // JSON is hand-editable, so the metadata must be in there too
        check("json enthält quelle KABU.DBF", true, json.contains("\"quelle\": \"KABU.DBF\""));
        check("json enthält anzahl", true, json.contains("\"anzahl\": " + orig.size()));
        check("csv Kopfzeile", true, stripBom(csv).startsWith("nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode"));

        // ------------------------------------------------------------ import: append
        Store scratch = new Store(work);
        String md5Before = md5(work.resolve("KABU.DBF"));
        Map<String, Object> appended = scratch.importBookings("csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "append");
        check("append: importiert", (long) orig.size(), appended.get("importiert"));
        check("append: Bestand danach", (long) (2 * orig.size()), appended.get("gesamt"));
        List<Buchung> after = scratch.bookingsFor(null);
        check("append: keine doppelten Nummern", true, uniqueNrs(after));
        check("append: Nummern über dem alten Maximum", true,
                after.stream().mapToInt(Buchung::nr).max().orElse(0) > orig.stream().mapToInt(Buchung::nr).max().orElse(0));
        check("append: Betragssumme verdoppelt", sumOrig * 2, sum(after, Buchung::betrag));
        check("append: keine Sicherungskopie", null, appended.get("backup"));
        check("append: Datei geändert", true, !md5(work.resolve("KABU.DBF")).equals(md5Before));

        // ----------------------------------------------------------- import: replace
        String md5BeforeReplace = md5(work.resolve("KABU.DBF"));
        Map<String, Object> replaced = scratch.importBookings("csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), "replace");
        check("replace: importiert", (long) orig.size(), replaced.get("importiert"));
        check("replace: Bestand danach", (long) orig.size(), replaced.get("gesamt"));
        check("replace: Sicherungskopie KABU.BAK", "KABU.BAK", replaced.get("backup"));
        check("replace: KABU.BAK = Stand vor dem Ersetzen", true, md5(work.resolve("KABU.BAK")).equals(md5BeforeReplace));
        sameRows(orig, scratch.bookingsFor(null), "replace Round-Trip über csv");
        check("replace: Betragssumme unverändert", sumOrig, sum(scratch.bookingsFor(null), Buchung::betrag));

        // ------------------------------------------------- Steuer wird neu berechnet
        String wrongTax = """
                [{"nr":500,"datum":"2026-04-01","art":"E","text":"Fremde Steuer","betrag":119.0,"steuer":99.99,"netto":19.01,"steuerSchluessel":2,"periode":"4226"}]
                """;
        Map<String, Object> imp = scratch.importBookings("json", wrongTax.getBytes(java.nio.charset.StandardCharsets.UTF_8), "append");
        Buchung neu = scratch.bookingsFor(null).stream().filter(b -> b.text().equals("Fremde Steuer")).findFirst().orElseThrow();
        double erwartet = Steuer.steuer(119.0, 2, live.stamm().rateReduced(), live.stamm().rateFull());
        check("import: Steuer aus STAMM.MEM neu berechnet", erwartet, neu.steuer());
        check("import: Periode aus Datum und Modus", Steuer.periode(neu.datum(), live.stamm().periodeMode()), neu.periode());
        check("import: Netto = Betrag - Steuer", 119.0 - erwartet, neu.netto());

        // -------------------------------------------------------- Umlaute & Textgrenzen
        String umlaut = "[{\"nr\":0,\"datum\":\"2026-08-01\",\"art\":\"E\",\"text\":\"Prüfung Straße Größe\",\"betrag\":10.5,\"steuer\":0,\"netto\":10.5,\"steuerSchluessel\":0,\"periode\":\"4326\"}]";
        scratch.importBookings("json", umlaut.getBytes(java.nio.charset.StandardCharsets.UTF_8), "append");
        Buchung u = scratch.bookingsFor(null).stream().filter(b -> b.text().startsWith("Prüfung")).findFirst().orElseThrow();
        check("Umlaute in JSON bleiben erhalten", "Prüfung Straße Größe", u.text());
        List<Buchung> viaCsv = BookingsIo.parseCsv(scratch.exportCsv(null));
        check("Umlaute in CSV bleiben erhalten", "Prüfung Straße Größe",
                viaCsv.stream().filter(b -> b.nr() == u.nr()).map(Buchung::text).findFirst().orElse(""));

        String longText = "[{\"nr\":0,\"datum\":\"2026-08-02\",\"art\":\"E\",\"text\":\""
                + "x".repeat(31) + "\",\"betrag\":5,\"steuer\":0,\"netto\":5,\"steuerSchluessel\":0,\"periode\":\"4326\"}]";
        String md5BeforeLong = md5(work.resolve("KABU.DBF"));
        boolean refused = false;
        try {
            scratch.importBookings("json", longText.getBytes(java.nio.charset.StandardCharsets.UTF_8), "append");
        } catch (Store.ValidationException e) {
            refused = e.getMessage().contains("BU_TEXT");
        }
        check("Text über 30 Zeichen wird abgelehnt", true, refused);
        check("ablehnung schreibt nichts in die Datei", true, md5(work.resolve("KABU.DBF")).equals(md5BeforeLong));

        // ----------------------------------------------------------- fehlerhafter Import
        String bad = "nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode\n"
                + "1;01.01.2026;X;Falsche Art;10;0;0;0;4126\n"
                + "2;02.01.2026;E;In Ordnung;10;0;0;0;4126\n";
        String md5BeforeBad = md5(work.resolve("KABU.DBF"));
        String message = "";
        try {
            scratch.importBookings("csv", bad.getBytes(java.nio.charset.StandardCharsets.UTF_8), "replace");
        } catch (Store.ValidationException e) {
            message = e.getMessage();
        }
        check("fehlerhafter csv-Import wird komplett abgebrochen", true, message.contains("1 fehlerhafte Zeile"));
        check("Import bricht ohne zu schreiben ab", true, md5(work.resolve("KABU.DBF")).equals(md5BeforeBad));

        String notBookings = "{\"irgendwas\":true}";
        boolean refusedEmpty = false;
        try { scratch.importBookings("json", notBookings.getBytes(java.nio.charset.StandardCharsets.UTF_8), "append"); }
        catch (Store.ValidationException e) { refusedEmpty = true; }
        check("json ohne buchungen wird abgelehnt", true, refusedEmpty);

        boolean refusedUnknownFormat = false;
        try { scratch.importBookings("sqlite", "x".getBytes(java.nio.charset.StandardCharsets.UTF_8), "append"); }
        catch (Store.ValidationException e) { refusedUnknownFormat = e.getMessage().contains("Unbekanntes Format"); }
        check("unbekanntes Format wird abgelehnt", true, refusedUnknownFormat);

        // ------------------------------------------------------------ csv Toleranz
        String de = "Nr;Datum;Art;Text;Betrag;Steuer;Netto;Steuerschlüssel;Periode\n"
                + ";05.05.2026;E;Deutsche Kopfzeile;99,90;16,65;83,25;2;4226\n";
        Buchung de1 = BookingsIo.parseCsv(de).get(0);
        check("csv mit deutscher Kopfzeile (Steuerschlüssel)", 2, de1.stSchluessel());
        check("csv Dezimalkomma", 99.90, de1.betrag());
        check("csv Datum dd.mm.yyyy", java.time.LocalDate.of(2026, 5, 5), de1.datum());

        String en = "nr,datum,art,text,betrag,steuer,netto,steuerSchluessel,periode\n"
                + "7,2026-05-06,A,Englisches Format,12.34,2.05,10.29,1,4226\n";
        Buchung en1 = BookingsIo.parseCsv(en).get(0);
        check("csv mit Komma als Trenner", 7, en1.nr());
        check("csv Punkt als Dezimaltrenner", 12.34, en1.betrag());
        check("csv ISO-Datum", java.time.LocalDate.of(2026, 5, 6), en1.datum());

        String headless = "9;07.05.2026;E;Ohne Kopfzeile;30,00;5,00;25,00;2;4226\n";
        Buchung hn = BookingsIo.parseCsv(headless).get(0);
        check("csv ohne Kopfzeile (kanonische Reihenfolge)", 9, hn.nr());
        check("csv ohne Kopfzeile: Spalten passen", "Ohne Kopfzeile", hn.text());

        String quoted = "nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode\n"
                + "10;08.05.2026;E;\"Rechnung; mit \"\"Zitat\"\"\";30,00;5,00;25,00;2;4226\n";
        check("csv mit Anführungszeichen", "Rechnung; mit \"Zitat\"", BookingsIo.parseCsv(quoted).get(0).text());

        // ------------------------------------------------------------- Auto-Erkennung
        check("Erkennung JSON", "json", Api.detectFormat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check("Erkennung csv", "csv", Api.detectFormat(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check("Erkennung csv (BOM)", "csv", Api.detectFormat(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check("Dateiendung .json", "json", BookingsIo.formatOf("KABU-alle.JSON"));
        check("Exportname ohne Periode", "KABU-alle.csv", Store.exportFileName("csv", null));
        check("Exportname mit Periode", "KABU-4426.json", Store.exportFileName("json", "4426"));

        // ---------------------------------------------------------------- KABU.DBF pur
        // the fixture fallback doubles as our scratch copy, so only a real data/ can be
        // checked for untouched files here; the fixtures themselves are read-only below
        // without a data/ there is nothing outside the scratch copy to protect, and the
        // fixture set was only read - both checks would compare a file against itself
        boolean sourceIsScratch = !Files.exists(source.resolve("KABU.DBF"))
                || source.toAbsolutePath().equals(work.toAbsolutePath());
        if (sourceIsScratch) {
            System.out.println("übersprungen: KABU.DBF-Unverändertheit (kein data/ als Quelle)");
        } else {
            check("Quelle KABU.DBF unverändert", true, md5(source.resolve("KABU.DBF")).equals(md5OfOriginal));
            check("Kein Austauschformat im Datenordner", true,
                    !Files.exists(source.resolve("KABU.sqlite")) && !Files.exists(source.resolve("KABU.csv")));
        }

        System.out.println(failed == 0 ? "\nAlle Backend-Tests bestanden" : "\n" + failed + " Backend-Tests fehlgeschlagen");
        if (failed > 0) System.exit(1);
    }

    /** checksum of the source file as it was before this test run */
    private static String md5OfOriginal;

    static {
        try {
            Path dir = Path.of(System.getenv().getOrDefault("KB_DATA_SRC", "data"));
            Path kabu = dir.resolve("KABU.DBF");
            md5OfOriginal = Files.exists(kabu) ? md5(kabu) : "";
        } catch (Exception e) {
            md5OfOriginal = "";
        }
    }

    private static String stripBom(String s) {
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static double sum(List<Buchung> rows, java.util.function.ToDoubleFunction<Buchung> f) {
        return Buchung.round(rows.stream().mapToDouble(f).sum());
    }

    private static boolean uniqueNrs(List<Buchung> rows) {
        Set<Integer> seen = new HashSet<>();
        for (Buchung b : rows) if (!seen.add(b.nr())) return false;
        return true;
    }

    private static void sameRows(List<Buchung> expected, List<Buchung> actual, String label) {
        if (expected.size() != actual.size()) {
            fail(label + ": " + expected.size() + " Zeilen erwartet, " + actual.size() + " gefunden");
            return;
        }
        List<String> diffs = new ArrayList<>();
        for (int i = 0; i < expected.size() && diffs.size() < 3; i++) {
            Buchung a = expected.get(i), b = actual.get(i);
            if (a.nr() != b.nr() || !a.datum().equals(b.datum()) || !a.art().equals(b.art())
                    || !a.text().equals(b.text()) || Math.abs(a.betrag() - b.betrag()) > 0.004
                    || Math.abs(a.steuer() - b.steuer()) > 0.004 || a.stSchluessel() != b.stSchluessel()
                    || !a.periode().equals(b.periode())) {
                diffs.add(i + ": " + a + " vs " + b);
            }
        }
        if (diffs.isEmpty()) System.out.println("ok   " + label + " (" + expected.size() + " Zeilen)");
        else fail(label + ": " + String.join(" | ", diffs));
    }

    private static String md5(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        failed++;
    }

    private static void check(String name, Object expected, Object actual) {
        boolean ok;
        if (expected instanceof Double && actual instanceof Number)
            ok = Math.abs((Double) expected - ((Number) actual).doubleValue()) < 0.005;
        else if (expected instanceof Long && actual instanceof Integer)
            ok = ((Long) expected).intValue() == (Integer) actual;
        else ok = expected == null ? actual == null : expected.equals(actual);
        System.out.println((ok ? "ok   " : "FAIL ") + name + (ok ? "" : ": erwartet " + expected + ", erhalten " + actual));
        if (!ok) failed++;
    }

    /** does this directory hold a KABU.DBF with at least one booking? */
    private static boolean hasBookings(Path dir) {
        Path dbf = dir.resolve("KABU.DBF");
        if (!Files.exists(dbf)) return false;
        try {
            return new DbfFile(dbf).live().size() > 0;
        } catch (Exception e) {
            return false;                     // unreadable counts as "no bookings to test with"
        }
    }
}
