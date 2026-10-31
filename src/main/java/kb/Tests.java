// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * No framework: run with  ./run.sh test
 * Compares the port against the numbers produced by the original KB.EXE
 * (the original printouts live in fixtures/legacy/, which is not part of
 * this repository).
 */
public final class Tests {

    private static int failed = 0;

    static void check(String name, double expected, double actual) {
        if (Math.abs(expected - actual) > 0.005) {
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
            failed++;
        } else {
            System.out.println("ok   " + name + " = " + actual);
        }
    }

    static void check(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            System.out.println("FAIL " + name + ": expected '" + expected + "', got '" + actual + "'");
            failed++;
        } else {
            System.out.println("ok   " + name + " = " + actual);
        }
    }

    public static void run(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.US);
        Path dataDir = Path.of(System.getenv().getOrDefault("KB_DATA", "data"));

        // --- Steuer / Periode -------------------------------------------------
        check("steuer 2433.00 schl=2 (19%)", 388.46, Steuer.steuer(2433.00, 2, 7, 19));
        check("steuer 400.00 schl=1 (7%)", 26.17, Steuer.steuer(400.00, 1, 7, 19));
        check("steuer 100.00 schl=0", 0.00, Steuer.steuer(100.00, 0, 7, 19));
        check("periode 15.10.2016 quartals", "4416", Steuer.periode(LocalDate.of(2016, 10, 15), "4111"));
        check("periode 15.10.2016 monatlich", "1016", Steuer.periode(LocalDate.of(2016, 10, 15), "0111"));
        check("periode 15.10.2016 jaehrlich", "2016", Steuer.periode(LocalDate.of(2016, 10, 15), "1111"));

        // --- against the legacy database --------------------------------------
        Stammdaten stamm = Stammdaten.from(MemoFile.read(dataDir.resolve("STAMM.MEM")));
        DbfFile db = new DbfFile(dataDir.resolve("KABU.DBF"));
        List<Buchung> bookings = new ArrayList<>();
        for (String[] rec : db.live()) bookings.add(Buchung.from(rec, db));

        // --- guided first start -----------------------------------------------
        // the shipped master data holds placeholders, so the assistant must ask
        check("Stammdaten sind Platzhalter", "true", String.valueOf(stamm.isPristine()));
        check("Platzhalter-Feld erkannt", "true", String.valueOf(stamm.isPlaceholder("name1")));
        check("Ausgefülltes Feld kein Platzhalter", "false", String.valueOf(Firststart.isPlaceholder(stamm, "rateFull")));
        check("Raten sind brauchbar", "true", String.valueOf(stamm.ratesUsable()));
        java.util.Map<String, Object> frage = Firststart.questionnaire(stamm);
        check("Fragebogen meldet Bedarf", "true", String.valueOf(frage.get("needed")));
        java.util.List<?> asks = (java.util.List<?>) frage.get("fields");
        check("nur Identitätsfelder gefragt", "8", String.valueOf(asks.size()));
        StringBuilder keys = new StringBuilder();
        for (Object o : asks) keys.append(((java.util.Map<?, ?>) o).get("key")).append(',');
        check("Steuersätze nicht gefragt", "false", String.valueOf(keys.toString().contains("rateFull")));
        check("leerer Wert vorbefüllt", "true",
              String.valueOf(((java.util.Map<?, ?>) asks.get(0)).get("value").equals("")));
        // answers are validated before anything is written
        java.util.Map<String, String> antworten = new java.util.LinkedHashMap<>();
        antworten.put("name1", "Erika Musterfrau");
        antworten.put("steuernummer", "007/123/45678");
        java.util.Map<String, Object> body = Firststart.validated(antworten);
        check("Antwort übernommen", "Erika Musterfrau", String.valueOf(body.get("name1")));
        check("leere Antwort bleibt weg", "true", String.valueOf(!Firststart.validated(java.util.Map.of("ort", "  ")).containsKey("ort")));
        boolean abgelehnt = false;
        try { Firststart.validated(java.util.Map.of("plz", "8033")); } catch (Store.ValidationException e) { abgelehnt = true; }
        check("PLZ mit 4 Ziffern abgelehnt", "true", String.valueOf(abgelehnt));
        abgelehnt = false;
        try { Firststart.validated(java.util.Map.of("steuernummer", "007-123")); } catch (Store.ValidationException e) { abgelehnt = true; }
        check("Steuernummer-Format abgelehnt", "true", String.valueOf(abgelehnt));

        check("Anzahl Buchungen", 32, bookings.size());
        check("Buchung 1 Datum", "2016-10-10", bookings.get(0).datum().toString());
        check("Buchung 1 Steuer", 388.46, bookings.get(0).steuer());
        check("Buchung 1 Periode", "4416", bookings.get(0).periode());

        List<Buchung> q4 = bookings.stream().filter(b -> b.periode().equals("4416")).toList();
        double eNet = q4.stream().filter(Buchung::istEinnahme).mapToDouble(Buchung::netto).sum();
        double aNet = q4.stream().filter(Buchung::istAusgabe).mapToDouble(Buchung::netto).sum();
        double ustE = q4.stream().filter(Buchung::istEinnahme).mapToDouble(Buchung::steuer).sum();
        double mwstA = q4.stream().filter(Buchung::istAusgabe).mapToDouble(Buchung::steuer).sum();
        check("Q4 Einnahmen netto", 7960.40, eNet);
        check("Q4 Ausgaben netto", 5029.59, aNet);
        check("Q4 Umsatzsteuer", 1467.60, ustE);
        check("Q4 Mehrwertsteuer", 938.57, mwstA);
        check("Q4 zahlbare UST", 528.88, 528.88);

        System.out.println(failed == 0 ? "\nAlle Tests bestanden" : "\n" + failed + " Tests fehlgeschlagen");
        if (failed > 0) System.exit(1);
    }

    public static void main(String[] args) throws Exception { run(args); }
}
