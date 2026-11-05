// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Cli {

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.US);  // legacy reports print decimal points
        Path dataDir = Path.of(System.getenv().getOrDefault("KB_DATA", "data"));
        Path kabu = dataDir.resolve("KABU.DBF");
        Path jabu = dataDir.resolve("JABU.DBF");
        Path stammMem = dataDir.resolve("STAMM.MEM");

        if (!Files.exists(kabu)) {
            System.err.println("KABU.DBF not found in " + dataDir + " (run ./reset.sh to create it from fixtures/)");
            System.exit(1);
        }

        Stammdaten stamm = Stammdaten.from(MemoFile.read(stammMem));
        DbfFile db = new DbfFile(kabu);
        List<Buchung> bookings = new ArrayList<>();
        for (String[] rec : db.live()) bookings.add(Buchung.from(rec, db));

        String cmd = args.length > 0 ? args[0].toLowerCase() : "help";
        String periode = args.length > 1 ? args[1] : null;
        if (periode == null) periode = lastPeriode(bookings);

        Reports reports = new Reports(bookings, stamm);
        Path pdfOut = flag(args, "--pdf");
        Path dosOut = flag(args, "--dos");   // CP437 + CRLF, exactly like the old printer file

        switch (cmd) {
            case "stamm"      -> emit(reports, "stamm", periode, pdfOut, dosOut);
            case "list"       -> emit(reports, "buchungsliste", periode, pdfOut, dosOut);
            case "kabu", "kassenbuch" -> emit(reports, "kassenbuch", periode, pdfOut, dosOut);
            case "ust"        -> emit(reports, "ust", periode, pdfOut, dosOut);
            case "info"       -> emit(reports, "info", periode, pdfOut, dosOut);
            case "add"        -> add(db, bookings, stamm, args);
            case "export"     -> export(dataDir, args);
            case "import"     -> importData(dataDir, args);
            case "yearend"    -> yearEnd(db, dataDir, jabu, stamm, bookings);
            case "backup"     -> backup(dataDir);
            case "jabu"       -> printJabu(jabu);
            case "init"       -> init(dataDir, stamm, flag(args, "--yes") != null);
            case "test"       -> Tests.main(args);
            case "help", "-h" -> help();
            default -> help();
        }
    }

    /** print to stdout, or write the requested files (legacy .txt and/or PDF) */
    private static void emit(Reports reports, String kind, String periode, Path pdf, Path dos) throws Exception {
        if (pdf != null) Files.write(pdf, reports.pdfReport(kind, periode));
        if (dos != null) Files.write(dos, reports.dosReport(kind, periode));
        if (pdf == null && dos == null) {
            System.out.print(reports.report(kind, periode));
        } else {
            System.out.println("Report " + kind + " geschrieben: " + (pdf != null ? pdf : "") + (pdf != null && dos != null ? " | " : "") + (dos != null ? dos : ""));
        }
    }

    private static Path flag(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return Path.of(args[i + 1]);
        return null;
    }

    /**
     * export json|csv [DATEI] [PERIODE] - writes the bookings of KABU.DBF
     * into an interchange file. Without PERIODE every booking is exported.
     */
    private static void export(Path dataDir, String[] args) throws Exception {
        String fmt = args.length > 1 ? args[1].toLowerCase() : "";
        if (!fmt.equals("json") && !fmt.equals("csv") && !fmt.equals("dbf")) {
            System.out.println("usage: export json|csv|dbf [DATEI] [PERIODE]");
            return;
        }
        String file = null, periode = null;
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-")) continue;
            if (a.matches("\\d{4}")) periode = a;
            else if (a.equalsIgnoreCase("alle") || a.equals("-")) periode = null;
            else file = a;
        }
        Store store = new Store(dataDir);
        Path out = file == null ? Path.of(Store.exportFileName(fmt, periode)) : Path.of(file);
        if (out.getParent() != null) Files.createDirectories(out.toAbsolutePath().getParent());
        if (fmt.equals("dbf")) {                 // a copy of the live file, never filtered
            Files.write(out, store.exportDbf());
            System.out.println("KABU.DBF kopiert: " + out + " (" + Files.size(out) + " Bytes)");
            return;
        }
        int count = store.bookingsFor(periode).size();
        switch (fmt) {
            case "csv" -> Files.writeString(out, store.exportCsv(periode));
            default -> Files.writeString(out, store.exportJson(periode));
        }
        System.out.printf("%d Buchungen als %s geschrieben: %s%s%n", count, fmt, out,
                periode == null ? " (alle Perioden)" : " (Periode " + periode + ")");
    }

    /**
     * import DATEI [--replace|--append] - reads JSON or CSV back into KABU.DBF.
     * Default mode is --append (existing bookings stay). --replace saves the old file
     * as KABU.BAK first, like the legacy year-end does with KABU.YY.
     */
    private static void importData(Path dataDir, String[] args) throws Exception {
        String file = null, mode = "append";
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--replace")) mode = "replace";
            else if (a.equals("--append")) mode = "append";
            else file = a;
        }
        if (file == null) {
            System.out.println("usage: import DATEI [--replace|--append]   (.json, .csv)");
            return;
        }
        Store store = new Store(dataDir);
        Map<String, Object> res;
        try {
            res = store.importFile(Path.of(file), mode);
        } catch (Store.ValidationException e) {
            System.err.println("Import abgebrochen: " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println(res.get("nachricht") + "  [" + res.get("format") + ", " + res.get("mode")
                + (res.get("backup") != null ? ", Sicherungskopie " + res.get("backup") : "") + "]");
        System.out.println("Ziel: " + res.get("datenbank"));
    }

    private static String lastPeriode(List<Buchung> bookings) {
        if (bookings.isEmpty()) return "";
        return bookings.get(bookings.size() - 1).periode();
    }

    private static void add(DbfFile db, List<Buchung> bookings, Stammdaten stamm, String[] args) throws Exception {
        if (args.length < 6) {
            System.out.println("usage: add <DD.MM.YY> <E|A> <text> <betrag> <steuer-schluessel 0|1|2>");
            return;
        }
        LocalDate datum = LocalDate.parse(normaliseDate(args[1]));
        String art = args[2].toUpperCase().substring(0, 1);
        String text = args[3];
        double betrag = Double.parseDouble(args[4].replace(',', '.'));
        int schl = Integer.parseInt(args[5]);
        double steuer = Steuer.steuer(betrag, schl, stamm.rateReduced(), stamm.rateFull());
        String periode = Steuer.periode(datum, stamm.periodeMode());
        int nr = bookings.stream().mapToInt(Buchung::nr).max().orElse(0) + 1;

        Map<String, String> rec = new HashMap<>();
        rec.put("BU_NR", String.valueOf(nr));
        rec.put("BU_DATUM", String.format("%04d%02d%02d", datum.getYear(), datum.getMonthValue(), datum.getDayOfMonth()));
        rec.put("BU_ART", art);
        rec.put("BU_TEXT", text);
        rec.put("BU_BETRAG", String.format("%.2f", betrag));
        rec.put("BU_STEUER", String.format("%.2f", steuer));
        rec.put("BU_ST_SCHL", String.valueOf(schl));
        rec.put("BU_PERIODE", periode);
        db.append(rec);
        db.save();
        System.out.printf("BuNr %d %s %s EUR %.2f Steuer %.2f Periode %s gespeichert%n",
                nr, datum, art, betrag, steuer, periode);
    }

    private static String normaliseDate(String s) {
        String[] p = s.split("[./-]");
        int d = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
        if (y < 100) y += 2000;
        return String.format("%04d-%02d-%02d", y, m, d);
    }

    private static void yearEnd(DbfFile db, Path dataDir, Path jabuPath, Stammdaten stamm, List<Buchung> bookings) throws Exception {
        if (bookings.isEmpty()) { System.out.println("Keine Buchungen - nichts zu tun."); return; }
        double sumA = 0, sumE = 0;
        int jahr = bookings.get(0).datum().getYear();
        for (Buchung b : bookings) {
            if (b.istAusgabe()) sumA += b.netto(); else sumE += b.netto();
        }
        System.out.println("Jahresabschluss - Schritte:");
        String backup = "KABU." + String.format("%02d", jahr % 100);
        Files.copy(dataDir.resolve("KABU.DBF"), dataDir.resolve(backup), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("  1. KABU.DBF gesichert als %s%n", backup);

        Path csvDir = Store.ensureBackupDir();
        Path csvFile = csvDir.resolve("KABU-" + jahr + ".csv");
        Files.writeString(csvFile, BookingsIo.csv(bookings), java.nio.charset.StandardCharsets.UTF_8);
        System.out.printf("  2. CSV-Export %s (%d Buchungen)%n", csvFile, bookings.size());

        DbfFile jabu = new DbfFile(jabuPath);
        Map<String, String> rec = new HashMap<>();
        rec.put("JA_JAHR", String.valueOf(jahr));
        rec.put("JA_EIN_NET", String.format("%.2f", sumE));
        rec.put("JA_AUS_NET", String.format("%.2f", sumA));
        jabu.append(rec);
        jabu.save();
        System.out.printf("  3. Jahressumme %d nach JABU.DBF: Einnahmen netto %.2f, Ausgaben netto %.2f%n",
                jahr, sumE, sumA);

        int n = db.live().size();
        for (String[] r : db.live()) db.delete(r);
        db.save();
        System.out.printf("  4. %d Buchung(en) aus KABU.DBF entfernt%n", n);
    }

    /** a tar.gz of everything that makes up the cash book */
    /**
     * Guided first start. Only the identity fields a report header needs are asked; the
     * printer/terminal slots of STAMM.MEM are carried over untouched by MemoWriter. The
     * preview is shown before anything is written, and only "j" writes.
     */
    private static void init(Path dataDir, Stammdaten stamm, boolean assumeYes) throws Exception {
        List<String> missing = stamm.missingIdentity();
        System.out.println("Firststart-Assistent (Stammdaten für die Berichtskopfzeilen)");
        System.out.println("Datenordner: " + dataDir.toAbsolutePath());
        if (missing.isEmpty()) {
            System.out.println("Die Stammdaten sind bereits ausgefüllt. Nichts zu tun.");
            System.out.println(Firststart.preview(Firststart.known(stamm)));
            System.out.println("Ändern können Sie sie später in der Web-Maske (Tab „Stammdaten“)"
                    + " oder über PUT /api/stamm.");
            return;
        }

        Map<String, String> answers = new java.util.LinkedHashMap<>(Firststart.known(stamm));
        System.out.println();
        System.out.println("Bereits bekannt:");
        if (answers.isEmpty()) System.out.println("  (nichts)");
        for (Map.Entry<String, String> e : answers.entrySet())
            System.out.printf("  %-22s %s%n", Firststart.labelOf(e.getKey()), e.getValue());

        // In a real terminal, read line by line: System.console() gives echo and editing,
        // and each answer arrives before the next question is asked.
        java.io.Console con = System.console();

        // Piped or redirected input has no console: read everything at once (CP437 or
        // UTF-8, whichever decodes cleanly) and hand out the lines in order.
        String[] piped = con == null ? stdinLines() : null;
        int pos = 0;

        System.out.println();
        System.out.println("Bitte eingeben - leere Zeile bei optionalen Feldern lässt den alten Wert stehen.");
        for (Firststart.Field f : Firststart.missingFields(stamm)) {
            String prompt = f.label() + (f.required() ? " *" : " (optional)")
                    + (f.hint() == null ? "" : " [" + f.hint() + "]") + ":";
            while (true) {
                String raw;
                if (con != null) {
                    raw = con.readLine(prompt);
                    if (raw == null) { System.out.println("Abgebrochen - nichts geschrieben."); return; }
                } else {
                    System.out.println(prompt);
                    if (pos >= piped.length) { System.out.println("Abgebrochen - nichts geschrieben."); return; }
                    raw = piped[pos++];
                }
                if (raw.isBlank() && !f.required()) break;
                try {
                    answers.put(f.key(), Firststart.check(f, raw));
                    break;
                } catch (Store.ValidationException e) {
                    System.out.println("  " + e.getMessage() + " - bitte erneut");
                }
            }
        }

        Map<String, Object> body = Firststart.validated(answers);
        // a rate or mode left unanswered keeps the value from STAMM.MEM, so show it too
        Map<String, Object> shown = new java.util.LinkedHashMap<>(body);
        shown.putIfAbsent("rateFull", Firststart.valueOf(stamm, "rateFull"));
        shown.putIfAbsent("rateReduced", Firststart.valueOf(stamm, "rateReduced"));
        shown.putIfAbsent("periodeMode", stamm.periodeMode());
        System.out.println();
        System.out.println("Vorschau (wird in STAMM.MEM geschrieben):");
        System.out.print(Firststart.preview(shown));

        // The confirmation is the LAST non-empty line: piped input usually ends with a
        // blank line, and nothing can be read after the data ran out.
        String ok;
        if (assumeYes) ok = "j";
        else if (con != null) {
            String answer = con.readLine("Übernehmen? [j/N] ");
            ok = answer == null ? "" : answer;
        } else ok = lastNonBlank(piped);
        if (con == null) System.out.println("Übernehmen? [j/N]  ->  " + ok.trim());
        if (!ok.trim().toLowerCase().startsWith("j")) {
            System.out.println("Nichts geschrieben.");
            return;
        }

        Map<String, Object> after = new Store(dataDir).updateStamm(body);
        System.out.println("STAMM.MEM geschrieben.");
        System.out.print(Firststart.preview(after));
        System.out.println("Berichte prüfen:  ./run.sh kabu      ./run.sh list");
    }

    private static String lastNonBlank(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--)
            if (!lines[i].isBlank()) return lines[i];
        return "";
    }

    /**
     * Redirected stdin, decoded as UTF-8 or CP437 - whichever decodes without replacement
     * characters. Only used when there is no console to read from.
     */
    private static String[] stdinLines() throws java.io.IOException {
        byte[] raw = System.in.readAllBytes();
        String text = null;
        for (java.nio.charset.Charset cs : List.of(java.nio.charset.StandardCharsets.UTF_8,
                LegacyCharset.CODEPAGE)) {
            String t = new String(raw, cs);
            if (t.indexOf('\uFFFD') < 0) { text = t; break; }
        }
        if (text == null) text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        return text.split("\r?\n", -1);
    }

    private static void backup(Path dataDir) throws Exception {
        Map<String, Object> r = new Store(dataDir).createBackup();
        System.out.println("Datenbackup - Schritte:");
        for (Object s : (java.util.List<?>) r.get("schritte")) System.out.println("  " + s);
        System.out.printf("%d Datei(en), %d Bytes unkomprimiert -> %d Bytes Archiv%n",
                ((java.util.List<?>) r.get("dateien")).size(), r.get("unkomprimiert"), r.get("groesse"));
        System.out.println("HINWEIS: " + r.get("hinweis"));
    }

    private static void printJabu(Path jabuPath) throws Exception {
        if (!Files.exists(jabuPath)) { System.out.println("JABU.DBF fehlt."); return; }
        DbfFile jabu = new DbfFile(jabuPath);
        System.out.printf("%-6s %12s %12s%n", "Jahr", "Einnahmen", "Ausgaben");
        for (String[] r : jabu.live()) {
            System.out.printf("%-6s %12s %12s%n", jabu.get(r, "JA_JAHR"), jabu.get(r, "JA_EIN_NET"), jabu.get(r, "JA_AUS_NET"));
        }
    }

    private static void help() {
        System.out.println("""
        Kassenbuch CLI
          stamm                      Master data
          list [PERIODE]           Buchungsliste
          kabu [PERIODE]           Kassenbuch
          ust [PERIODE]            Umsatzsteuer-Voranmeldung
          info                     Monthly overview (net) - always all periods, no PERIODE argument
          add DD.MM.YY E|A TEXT BETRAG SCHLUessel
          yearend                  Sum year into JABU.DBF, backup KABU.YY, clear KABU.DBF
          backup                   tar.gz of KABU.DBF, JABU.DBF, STAMM.MEM and KABU.xx
                                   written to out/bak (or KB_BACKUP_DIR) - move it
                                   out of the program folder afterwards
          jabu                     Yearly summaries
          init [--yes]             Geführter Firststart: Name, Adresse, Finanzamt,
                                   Steuernummer, Steuersätze, Periodenmodus - nur die
                                   Felder, die noch Platzhalter oder leer sind; Vorschau
                                   vor dem Schreiben von STAMM.MEM. In einem Terminal wird
                                   Feld für Feld abgefragt; bei Pipe-Eingabe ist die letzte
                                   nicht leere Zeile die Bestätigung, --yes übernimmt sie
        Interchange formats (KABU.DBF stays the live store):
          export json|csv [DATEI] [PERIODE]
                                   without PERIODE every booking is exported
          export dbf [DATEI]       copy of the live KABU.DBF (not filtered by period)
          import DATEI [--replace|--append]
                                   .json or .csv; default mode is --append
                                   --replace writes the old file to KABU.BAK first
        Report output options:
          --pdf FILE               PDF (A4 landscape, Courier) - faithful to the layout
          --dos FILE               legacy .txt (CRLF, codepage 437)
        Examples:
          ./run.sh kabu 4416 --pdf out/Kassenbuch-4416.pdf
          ./run.sh list 4416 --dos out/Buchungsliste-4416.txt
          ./run.sh export csv out/KABU-alle.csv
          ./run.sh import out/KABU-alle.json --replace
        PERIODE examples: 4416 (Q4 2016), 1016 (Oct 2016), 2016 (yearly)

        Hinweis: Hobby-Software ohne Garantie jeglicher Art. Buchungs- und Steuerdaten
        für eine Steuererklärung müssen von Ihnen selbst geprüft werden; Nutzung
        geschieht auf eigene Verantwortung. Kopieren Sie KABU.DBF, JABU.DBF und
        STAMM.MEM, bevor das Programm zum ersten Mal in Ihre Dateien schreibt.
        Details: NOTICE.txt
        """);
    }
}
