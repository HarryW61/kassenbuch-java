// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Integration tests over the HTTP layer: starts the real API on an ephemeral port
 * against a scratch copy of the current KABU.DBF (KB_DATA_SRC, default `data/`).
 * Every expectation is derived from that data set, so the tests keep working when
 * bookings, texts or tax numbers change.
 */
public final class ApiTests {

    private static int passed = 0;
    private static int failed = 0;
    private static java.net.http.HttpClient client;
    private static String base;
    private static Path work;
    private static Store ref;
    private static int total;
    private static String period;
    private static List<Buchung> inPeriod;

    public static void main(String[] args) throws Exception {
        Path src = Path.of(System.getenv().getOrDefault("KB_DATA_SRC", "data"));
        work = Path.of(System.getenv().getOrDefault("KB_TEST_DATA", "/tmp/kbtest-data"));
        // never let a misconfigured scratch directory point back at the real data
        if (work.toAbsolutePath().equals(src.toAbsolutePath()))
            throw new IllegalStateException("KB_TEST_DATA must differ from KB_DATA_SRC: " + work);
        if (Files.exists(work)) deleteTree(work);
        copyTree(src, work);

        Store store = new Store(work);
        ref = store;
        total = store.bookings().size();
        List<String> periods = store.periods();
        period = periods.get(periods.size() - 1);
        inPeriod = store.bookingsFor(period);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new Api(store));
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
        client = java.net.http.HttpClient.newHttpClient();

        try {
            meta();
            bookingsAndReports();
            crud();
            stamm();
            catalogue();
            interchange();
            dataBackup();
            yearEnd();
        } finally {
            server.stop(0);
        }

        System.out.println(failed == 0 ? "API-Tests: " + passed + " ok" : "API-Tests: " + failed + " Fehler");
        if (failed > 0) System.exit(1);
    }

    private static void meta() throws Exception {
        Map<String, Object> m = get("/api/meta");
        Stammdaten s = ref.stamm();
        eq("meta.name1", s.name1(), m.get("name1"));
        eq("meta.rateFull", s.rateFull(), m.get("rateFull"));
        eq("meta.rateReduced", s.rateReduced(), m.get("rateReduced"));
        eq("meta.periodeMode", s.periodeMode(), m.get("periodeMode"));
        eq("meta.steuernummer", s.stnr(), m.get("steuernummer"));
        eq("meta.periods.last", period, list(m, "periods").get(list(m, "periods").size() - 1));
        check("meta lists at least one period", !ref.periods().isEmpty());
    }

    private static void bookingsAndReports() throws Exception {
        eq("bookings in " + period, inPeriod.size(), getList("/api/bookings?period=" + period).size());
        check("die Periode enthält Buchungen", !inPeriod.isEmpty());

        double einNet = Buchung.round(inPeriod.stream().filter(b -> !b.istAusgabe()).mapToDouble(Buchung::netto).sum());
        double ausNet = Buchung.round(inPeriod.stream().filter(Buchung::istAusgabe).mapToDouble(Buchung::netto).sum());
        double ust = Buchung.round(inPeriod.stream().filter(b -> !b.istAusgabe()).mapToDouble(Buchung::steuer).sum());
        double mwst = Buchung.round(inPeriod.stream().filter(Buchung::istAusgabe).mapToDouble(Buchung::steuer).sum());
        Map<String, Object> s = get("/api/summary?period=" + period);
        eq("summary.einnahmenNetto", einNet, s.get("einnahmenNetto"));
        eq("summary.ausgabenNetto", ausNet, s.get("ausgabenNetto"));
        eq("summary.umsatzsteuer", ust, s.get("umsatzsteuer"));
        eq("summary.mehrwertsteuer", mwst, s.get("mehrwertsteuer"));
        eq("summary.count", inPeriod.size(), s.get("count"));
        eq("summary.saldoNetto", Buchung.round(einNet - ausNet), s.get("saldoNetto"));

        Stammdaten st = ref.stamm();
        double sumFull = 0, sumReduced = 0, vorsteuer = 0;
        for (Buchung b : inPeriod) {
            if (b.istAusgabe()) vorsteuer += b.steuer();
            else if (b.stSchluessel() == 1) sumReduced += b.netto();
            else if (b.stSchluessel() == 2) sumFull += b.netto();
        }
        double ustCalc = Math.floor(sumFull) / 100.0 * st.rateFull() + Math.floor(sumReduced) / 100.0 * st.rateReduced();
        Map<String, Object> u = get("/api/ust?period=" + period);
        eq("ust.bemessungsgrundlage", Math.floor(sumFull) + Math.floor(sumReduced), u.get("bemessungsgrundlage"));
        eq("ust.ustSteuer", Buchung.round(ustCalc), u.get("ustSteuer"));
        eq("ust.vorsteuer", Buchung.round(vorsteuer), u.get("vorsteuer"));
        eq("ust.zahlbar", Buchung.round(ustCalc - vorsteuer), u.get("zahlbar"));

        // the screen and the Kassen-Information report must agree: one row per month,
        // that month's own sums, and Gesamt over everything
        List<?> monthly = getList("/api/monthly");
        eq("monthly rows are always Jan..Dec", 12, monthly.size());
        double[] mE = new double[13], mA = new double[13];
        for (Buchung b : ref.bookings()) {
            int m = b.datum().getMonthValue();
            if (b.istAusgabe()) mA[m] += b.netto(); else mE[m] += b.netto();
        }
        boolean monthsMatch = true;
        double sumE = 0, sumA = 0;
        for (int i = 1; i <= 12; i++) {
            Map<String, Object> row = (Map<String, Object>) monthly.get(i - 1);
            if (Buchung.round(((Number) row.get("einnahmen")).doubleValue()) != Buchung.round(mE[i])) monthsMatch = false;
            if (Buchung.round(((Number) row.get("ausgaben")).doubleValue()) != Buchung.round(mA[i])) monthsMatch = false;
            sumE += mE[i]; sumA += mA[i];
        }
        check("monthly screen sums each month on its own", monthsMatch);
        String info = raw("/api/report/info");
        for (int i = 1; i <= 12; i++) {
            String want = String.format("%-8s %14s %14s %14s%n",
                    new String[]{"Jan","Feb","Mar","Apr","Mai","Jun","Jul","Aug","Sep","Oct","Nov","Dec"}[i - 1],
                    fmt2(mE[i]), fmt2(mA[i]), fmt2(Buchung.round(mE[i] - mA[i])));
            if (!info.contains(want.replace("%n", "\n"))) {
                check("monthly screen row " + i + " matches the report", false);
                break;
            }
        }
        String gesamt = String.format("%-8s %14s %14s %14s%n", "Gesamt", fmt2(sumE), fmt2(sumA),
                fmt2(Buchung.round(sumE - sumA))).replace("%n", "\n");
        check("monthly Gesamt is the sum over all months", info.contains(gesamt));

        String report = raw("/api/report/kassenbuch?period=" + period);
        int pages = (inPeriod.size() + 22) / 23;
        check("report contains header", report.contains("Kassenbuch"));
        check("report contains the tax number", report.contains("Steuernummer: " + ref.stamm().stnr()));
        check("report has legacy footer", report.contains(">>>>>> Ende Kassenbuch " + period));
        check("report counts its rows", report.contains("Anzahl Buchungen " + inPeriod.size()));
        check("report shows the first booking", report.contains(legacyDate(inPeriod.get(0).datum())));
        check("report shows the last booking", report.contains(legacyDate(inPeriod.get(inPeriod.size() - 1).datum())));
        check("report repeats the header per page", report.split("Kassenbuch für ", -1).length - 1 == pages);
        if (pages > 1) check("report has page totals", report.contains("(Zwischensumme)"));
        else check("single page has no Zwischenbsumme", !report.contains("(Zwischensumme)"));

        byte[] pdf = bytes("/api/report/kassenbuch?period=" + period + "&fmt=pdf");
        check("pdf endpoint returns a PDF", pdf.length > 500 && new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"));
        byte[] dos = bytes("/api/report/kassenbuch?period=" + period + "&fmt=dos");
        check("dos report is CP437/CRLF", dos.length > 1000 && new String(dos, LegacyCharset.CODEPAGE).contains("\r\n"));
        check("buchungsliste reachable", raw("/api/report/buchungsliste?period=" + period).contains("Buchungsliste"));

        // "Alle Perioden" names the booking year, not a quarter or month code
        String wantYear = expectedBookingYear(ref.bookings());
        for (String kind : new String[]{"buchungsliste", "kassenbuch", "ust"}) {
            String all = raw("/api/report/" + kind + "?period=");
            check(kind + " names the booking year for all periods",
                  all.contains("Zeitraum:  " + wantYear));
            check(kind + " keeps the period code when one is selected",
                  raw("/api/report/" + kind + "?period=" + period).contains("Zeitraum:  " + period));
        }
        check("kassen-information names the booking year",
              raw("/api/report/info?period=").contains("Zeitraum:  Jahr " + wantYear));
    }

    /** same 19YY/20YY window the reports use to read the stored date back */
    private static String expectedBookingYear(List<Buchung> bookings) {
        java.util.TreeSet<Integer> years = new java.util.TreeSet<>();
        for (Buchung b : bookings) {
            int y = b.datum().getYear();
            years.add(y <= 100 ? 2000 + y : (y < 2001 ? 1900 + y : y));
        }
        if (years.isEmpty()) return String.valueOf(java.time.LocalDate.now().getYear());
        return years.first() == years.last() ? String.valueOf(years.first())
                : years.first() + " - " + years.last();
    }

    private static String dmy(java.time.LocalDate d) {
        return String.format("%02d.%02d.%04d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    /** the legacy printout shows the date with a two-digit year */
    private static String legacyDate(java.time.LocalDate d) {
        return String.format("%02d.%02d.%02d", d.getDayOfMonth(), d.getMonthValue(), d.getYear() % 100);
    }

    private static void crud() throws Exception {
        double r1 = ref.stamm().rateReduced(), r2 = ref.stamm().rateFull();
        String iso = inPeriod.get(inPeriod.size() - 1).datum().toString();

        Map<String, Object> created = post("/api/bookings",
                "{\"datum\":\"" + iso + "\",\"art\":\"A\",\"text\":\"Büro/Arbeitsmittel\",\"betrag\":\"119.00\",\"steuerSchluessel\":2}");
        int nr = ((Number) created.get("nr")).intValue();
        eq("created.nr", total + 1, created.get("nr"));
        eq("created.steuer", Steuer.steuer(119.0, 2, r1, r2), created.get("steuer"));
        eq("created.netto", Buchung.round(119.0 - Steuer.steuer(119.0, 2, r1, r2)), created.get("netto"));
        eq("created.periode", period, created.get("periode"));
        check("umlaut survives CP437", "Büro/Arbeitsmittel".equals(created.get("text")));
        eq("booking visible in the list", total + 1, getList("/api/bookings").size());

        Map<String, Object> updated = put("/api/bookings/" + nr,
                "{\"datum\":\"" + dmy(java.time.LocalDate.parse(iso)) + "\",\"art\":\"A\",\"text\":\"Testbuchung\",\"betrag\":\"200.00\",\"steuerSchluessel\":1}");
        eq("updated.steuer", Steuer.steuer(200.0, 1, r1, r2), updated.get("steuer"));
        eq("updated.datum", iso, updated.get("datum"));
        eq("updated.text", "Testbuchung", updated.get("text"));

        check("delete ok", Boolean.TRUE.equals(delete("/api/bookings/" + nr).get("ok")));
        eq("bookings back to " + total, total, getList("/api/bookings").size());

        // the three live values shown in the "Buchen" form: Steuer, Netto, Periode
        Map<String, Object> preview = post("/api/bookings",
                "{\"datum\":\"" + iso + "\",\"art\":\"E\",\"text\":\"Erloese 19%\",\"betrag\":\"2433.00\",\"steuerSchluessel\":2}");
        eq("preview.steuer", Steuer.steuer(2433.0, 2, r1, r2), preview.get("steuer"));
        eq("preview.netto", Buchung.round(2433.0 - Steuer.steuer(2433.0, 2, r1, r2)), preview.get("netto"));
        eq("preview.periode", period, preview.get("periode"));
        delete("/api/bookings/" + preview.get("nr"));
        eq("preview booking removed", total, getList("/api/bookings").size());

        int status = status("/api/bookings",
                "{\"datum\":\"" + iso + "\",\"art\":\"X\",\"text\":\"x\",\"betrag\":\"10\",\"steuerSchluessel\":2}");
        eq("invalid art -> 400", 400, status);
        status = status("/api/bookings",
                "{\"datum\":\"" + iso + "\",\"art\":\"E\",\"text\":\"x\",\"betrag\":\"-5\",\"steuerSchluessel\":2}");
        eq("negative amount -> 400", 400, status);
        eq("data set still complete", total, getList("/api/bookings").size());
    }

    private static void stamm() throws Exception {
        Map<String, Object> m = put("/api/stamm", """
            {"name2":"Testfirma GmbH","rateFull":"19","rateReduced":"7","periodeMode":"0111"}""");
        eq("stamm.name2", "Testfirma GmbH", m.get("name2"));
        eq("stamm.mode", "0111", m.get("periodeMode"));
        m = put("/api/stamm", "{\"email\":\"kontakt@bw-deko.de\"}");
        eq("stamm.email", "kontakt@bw-deko.de", m.get("email"));
        String longMail = "sehr.lange.adresse@beispiel-irgendwer.com";   // 41 chars
        m = put("/api/stamm", "{\"email\":\"" + longMail + "\"}");
        eq("email clamped to 40", longMail.substring(0, 40), m.get("email"));
        // the tax number must survive an update - the web form posts it as "stnr",
        // the documented API key is "steuernummer"; both have to work
        m = put("/api/stamm", "{\"stnr\":\"000/000/00000\"}");
        eq("stamm.steuernummer via stnr alias", "000/000/00000", m.get("steuernummer"));
        m = put("/api/stamm", "{\"steuernummer\":\"000/000/00001\"}");
        eq("stamm.steuernummer", "000/000/00001", m.get("steuernummer"));
        eq("stnr persisted in STAMM.MEM", "000/000/00001", get("/api/meta").get("steuernummer"));
        check("ST_STNR written to disk",
            new String(Files.readAllBytes(work.resolve("STAMM.MEM")), StandardCharsets.ISO_8859_1).contains("000/000/00001"));
        // with monthly mode, the same bookings now group by month
        java.time.LocalDate tag = inPeriod.get(inPeriod.size() - 1).datum();
        String monthKey = String.format("%02d%02d", tag.getMonthValue(), tag.getYear() % 100);
        long inMonth = ref.bookings().stream()
                .filter(b -> Steuer.periode(b.datum(), "0111").equals(monthKey)).count();
        Map<String, Object> oct = get("/api/summary?period=" + monthKey);
        eq("monthly mode " + monthKey + " count", inMonth, oct.get("count"));
        put("/api/stamm", "{\"periodeMode\":\"4111\"}");
        eq("quarterly mode restored", "4111", get("/api/meta").get("periodeMode"));
    }

    private static void catalogue() throws Exception {
        Map<String, Object> before = get("/api/meta");
        List<Object> origE = list(before, "einnahmeTexte");
        List<Object> origA = list(before, "ausgabeTexte");

        Map<String, Object> m = put("/api/stamm", "{\"einnahmeTexte\":[\"Erloese 19%\",\"Erloese 7%\",\"Sondereinnahmen\",\"Reparaturen\",\"Nebenkosten 7%\"]}");
        List<Object> e = list(m, "einnahmeTexte");
        eq("catalogue slots", 5, e.size());
        eq("catalogue[3]", "Reparaturen", e.get(3));
        eq("catalogue[4]", "Nebenkosten 7%", e.get(4));

        // more than the legacy 8 slots per group: extras land in ST_KTOE9... / ST_KTOA9...
        String nine = "[\"E1\",\"E2\",\"E3\",\"E4\",\"E5\",\"E6\",\"E7\",\"E8\",\"Sondererlass\"]";
        String nineA = "[\"A1\",\"A2\",\"A3\",\"A4\",\"A5\",\"A6\",\"A7\",\"A8\",\"Spende\"]";
        m = put("/api/stamm", "{\"einnahmeTexte\":" + nine + ",\"ausgabeTexte\":" + nineA + "}");
        eq("einnahme 9th", "Sondererlass", list(m, "einnahmeTexte").get(8));
        eq("ausgabe 9th", "Spende", list(m, "ausgabeTexte").get(8));

        Map<String, Object> restored = put("/api/stamm",
                "{\"einnahmeTexte\":" + jsonArrayOf(origE) + ",\"ausgabeTexte\":" + jsonArrayOf(origA) + "}");
        eq("catalogue restored", origE.get(0), list(restored, "einnahmeTexte").get(0));
    }

    /** JSON / CSV export and import over the HTTP layer. */
    private static void interchange() throws Exception {
        byte[] json = bytes("/api/export?fmt=json&period=" + period);
        byte[] csv = bytes("/api/export?fmt=csv&period=" + period);
        byte[] csvAll = bytes("/api/export?fmt=csv");
        String jsonText = new String(json, StandardCharsets.UTF_8);
        String csvText = new String(csv, StandardCharsets.UTF_8);

        check("export json: quelle KABU.DBF", jsonText.contains("\"quelle\": \"KABU.DBF\""));
        eq("export json: anzahl der Periode", inPeriod.size(), asMap(Json.parse(jsonText)).get("anzahl"));
        check("export csv: BOM + kanonische kopfzeile",
              csvText.startsWith("\uFEFF")
              && csvText.substring(1).startsWith("nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode\r\n"));
        check("export csv: deutliche dezimalzahlen", csvText.matches("(?s).*,[0-9]{2};.*"));
        eq("export csv: zeilen der Periode", inPeriod.size(), BookingsIo.parseCsv(csvText).size());
        eq("export csv: alle perioden", total, BookingsIo.parseCsv(new String(csvAll, StandardCharsets.UTF_8)).size());
        byte[] dbf = bytes("/api/export?fmt=dbf");
        check("export dbf: dBase-magic 0x03", dbf.length > 0 && (dbf[0] & 0xff) == 0x03);
        eq("export dbf: grösse wie KABU.DBF", Files.size(work.resolve("KABU.DBF")), dbf.length);

        Map<String, Object> appended = postBytes("/api/import?mode=append", csvAll);
        eq("import csv append: importiert", total, appended.get("importiert"));
        eq("import csv append: bestand danach", total * 2, getList("/api/bookings").size());
        check("import csv append: ohne sicherungskopie", appended.get("backup") == null);

        Map<String, Object> replaced = postBytes("/api/import?fmt=csv&mode=replace", csvAll);
        eq("import csv: bestand danach", total, replaced.get("gesamt"));
        eq("import csv: sicherungskopie", "KABU.BAK", replaced.get("backup"));
        check("import csv: sicherungskopie liegt im datenordner", Files.exists(work.resolve("KABU.BAK")));
        eq("import csv: anzahl unveraendert", total, getList("/api/bookings").size());
        eq("import csv: steuer bleibt gleich", inPeriod.stream().mapToDouble(Buchung::steuer).sum(),
                BookingsIo.parseCsv(csvText).stream().mapToDouble(Buchung::steuer).sum());

        Map<String, Object> refused = postBytes("/api/import?fmt=csv",
                ("nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode\n"
                        + "1;01.01.2016;X;Falsche art;10;0;0;0;4116\n").getBytes(StandardCharsets.UTF_8));
        check("import: ungültige zeile bringt eine fehlermeldung", refused.containsKey("error"));
        eq("import: es wurde nichts geschrieben", total, getList("/api/bookings").size());
    }

    private static String jsonArrayOf(List<Object> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(String.valueOf(values.get(i)).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /** the tar.gz backup: everything the cash book consists of, restorable byte for byte */
    private static void dataBackup() throws Exception {
        Map<String, Object> r = post("/api/backup", "{}");
        check("backup ok", Boolean.TRUE.equals(r.get("ok")));
        String name = String.valueOf(r.get("name"));
        check("backup name has the date and time", name.matches("KB_DATA_BACK_[0-9]{8}_[0-9]{6}[.]tar[.]gz"));
        check("backup archive exists", Files.exists(Path.of(String.valueOf(r.get("archiv")))));
        List<?> files = (List<?>) r.get("dateien");
        check("backup contains the three data files",
              files.contains("KABU.DBF") && files.contains("JABU.DBF") && files.contains("STAMM.MEM"));
        check("backup is smaller than the raw files",
              ((Number) r.get("groesse")).longValue() < ((Number) r.get("unkomprimiert")).longValue());
        check("backup states where to move it", String.valueOf(r.get("hinweis")).contains("außerhalb"));

        // unpack with the system tar and compare the live file byte for byte
        Path dir = Files.createTempDirectory("kb-restore");
        Process p = new ProcessBuilder("tar", "-xzf", String.valueOf(r.get("archiv")), "-C", dir.toString())
                .start();
        check("tar can read the archive", p.waitFor() == 0);
        check("restored KABU.DBF is identical",
              Arrays.equals(Files.readAllBytes(dir.resolve("KABU.DBF")),
                            Files.readAllBytes(work.resolve("KABU.DBF"))));
        check("restored STAMM.MEM is identical",
              Arrays.equals(Files.readAllBytes(dir.resolve("STAMM.MEM")),
                            Files.readAllBytes(work.resolve("STAMM.MEM"))));
    }

    private static void yearEnd() throws Exception {
        int expectedYear = ref.bookings().get(0).datum().getYear();
        int live = getList("/api/bookings").size();
        Map<String, Object> r = post("/api/yearend", "{}");
        check("yearend ok", Boolean.TRUE.equals(r.get("ok")));
        eq("yearend year", expectedYear, r.get("jahr"));
        eq("yearend deleted", live, r.get("geloescht"));
        check("backup created", Files.exists(work.resolve(String.valueOf(r.get("backup")))));
        Object csvBackup = r.get("csvBackup");
        check("yearend writes a CSV archive before clearing", csvBackup != null
                && String.valueOf(csvBackup).endsWith(".csv")
                && Files.exists(Path.of(String.valueOf(csvBackup))));
        check("yearend reports its steps", r.get("schritte") instanceof List<?> l && l.size() == 4);
        eq("bookings empty after closing", 0, getList("/api/bookings").size());
        List<Object> jabu = getList("/api/jabu");
        check("jabu got a row", jabu.size() >= 1);
    }

    /* ---- helpers ---- */

    private static Object getAny(String path) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path)).build();
        java.net.http.HttpResponse<String> res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) throw new RuntimeException(res.statusCode() + " " + res.body());
        return Json.parse(res.body());
    }

    /** Typed helper: the JSON list endpoints are read as List<Object> without unchecked casts. */
    private static List<Object> getList(String path) throws Exception {
        Object o = getAny(path);
        if (!(o instanceof List)) return List.of();
        List<Object> out = new ArrayList<>();
        for (Object x : (List<?>) o) out.add(x);
        return out;
    }

    private static List<Object> list(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (!(o instanceof List)) return List.of();
        List<Object> out = new ArrayList<>();
        for (Object x : (List<?>) o) out.add(x);
        return out;
    }

    private static Map<String, Object> get(String path) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path)).build();
        java.net.http.HttpResponse<String> res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) throw new RuntimeException(res.statusCode() + " " + res.body());
        return asMap(Json.parse(res.body()));
    }

    /** same two-decimal formatting the report uses */
    private static String fmt2(double v) {
        return String.format(java.util.Locale.US, "%.2f", Buchung.round(v));
    }

    private static String raw(String path) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path)).build();
        return client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }

    private static byte[] bytes(String path) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path)).build();
        return client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private static Map<String, Object> post(String path, String body) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        java.net.http.HttpResponse<String> res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) throw new RuntimeException(res.statusCode() + " " + res.body());
        return asMap(Json.parse(res.body()));
    }

    /** POST an interchange file (raw bytes); the answer is parsed whatever its status is. */
    private static Map<String, Object> postBytes(String path, byte[] body) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/octet-stream")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build();
        java.net.http.HttpResponse<String> res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        return asMap(Json.parse(res.body()));
    }

    private static Map<String, Object> put(String path, String body) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        java.net.http.HttpResponse<String> res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) throw new RuntimeException(res.statusCode() + " " + res.body());
        return asMap(Json.parse(res.body()));
    }

    private static Map<String, Object> delete(String path) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path))
                .DELETE().build();
        java.net.http.HttpResponse<String> res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        return asMap(Json.parse(res.body()));
    }

    private static int status(String path, String body) throws Exception {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        return Map.of();
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok;
        if (expected instanceof Number && actual instanceof Number) {
            ok = Math.abs(((Number) expected).doubleValue() - ((Number) actual).doubleValue()) < 0.005;
        } else if (expected instanceof Integer && actual instanceof Long) {
            ok = ((Number) expected).intValue() == ((Number) actual).intValue();
        } else {
            ok = Objects.equals(expected, actual);
        }
        if (ok) { passed++; System.out.println("ok   " + name + " = " + actual); }
        else { failed++; System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual); }
    }

    private static void check(String name, boolean condition) {
        if (condition) { passed++; System.out.println("ok   " + name); }
        else { failed++; System.out.println("FAIL " + name); }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        Files.createDirectories(to);
        for (Path p : Files.list(from).toList()) {
            if (Files.isDirectory(p)) copyTree(p, to.resolve(p.getFileName().toString()));
            else Files.copy(p, to.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        for (Path p : Files.list(root).toList()) {
            if (Files.isDirectory(p)) deleteTree(p);
            else Files.delete(p);
        }
        Files.delete(root);
    }
}
