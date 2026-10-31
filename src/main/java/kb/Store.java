// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/** Repository: everything the web API needs from the legacy dBase files. */
public final class Store {

    public static class ValidationException extends RuntimeException {
        ValidationException(String message) { super(message); }
    }

    private final Path dataDir;
    private final Path kabuPath;
    private final Path jabuPath;
    private final Path stammPath;

    public Store(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        this.kabuPath = dataDir.resolve("KABU.DBF");
        this.jabuPath = dataDir.resolve("JABU.DBF");
        this.stammPath = dataDir.resolve("STAMM.MEM");
        if (!Files.exists(kabuPath)) throw new IOException("KABU.DBF fehlt in " + dataDir);
    }

    public Stammdaten stamm() throws IOException {
        return Stammdaten.from(MemoFile.read(stammPath));
    }

    /** Update master data in STAMM.MEM (the MEMO layout is rewritten 1:1). */
    public Map<String, Object> updateStamm(Map<String, Object> body) throws IOException {
        Map<String, String> memo = MemoFile.read(stammPath);
        Map<String, String> keyMap = new LinkedHashMap<>();
        keyMap.put("name1", "ST_NAME1");
        keyMap.put("name2", "ST_NAME2");
        keyMap.put("strasse", "ST_STRASSE");
        keyMap.put("plz", "ST_PLZ");
        keyMap.put("ort", "ST_ORT");
        keyMap.put("telefon", "ST_TELEFON");
        keyMap.put("telefax", "ST_TELEFAX");
        keyMap.put("email", "ST_EMAIL");
        keyMap.put("gewerbe", "ST_GEWERBE");
        keyMap.put("finanzamt", "ST_FIAMT");
        keyMap.put("steuernummer", "ST_STNR");
        keyMap.put("stnr", "ST_STNR");          // alias: the web form posts the tax number as "stnr"
        keyMap.put("rateFull", "ST_STEUER2");
        keyMap.put("rateReduced", "ST_STEUER1");
        keyMap.put("periodeMode", "ST_PERIODE");
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = keyMap.get(e.getKey());
            if (key == null) continue;
            if (e.getValue() == null) continue;
            String value = String.valueOf(e.getValue()).trim();
            if (key.equals("ST_EMAIL")) value = value.length() > Stammdaten.EMAIL_MAX ? value.substring(0, Stammdaten.EMAIL_MAX) : value;
            memo.put(key, value);
        }
        List<String> ein = textList(body.get("einnahmeTexte"));
        List<String> aus = textList(body.get("ausgabeTexte"));
        if (ein != null || aus != null) {
            applyCatalogue(memo,
                    ein != null ? ein : new ArrayList<>(Arrays.asList(stamm().textEinnahme())),
                    aus != null ? aus : new ArrayList<>(Arrays.asList(stamm().textAusgabe())));
        }
        MemoWriter.write(stammPath, memo);
        // keep the stored BU_PERIODE values in sync with the (possibly changed) period mode
        DbfFile db = open();
        String mode = memo.getOrDefault("ST_PERIODE", "1111");
        for (String[] r : db.live()) {
            LocalDate d = Buchung.parseDbfDate(db.get(r, "BU_DATUM"));
            r[db.fieldIndex("BU_PERIODE")] = Steuer.periode(d, mode);
        }
        db.save();
        return meta();
    }

    private DbfFile open() throws IOException { return new DbfFile(kabuPath); }

    public List<Buchung> bookings() throws IOException {
        return all();
    }

    /**
     * Year the bookings belong to, for a report printed over all periods. Dates are
     * stored as 19YYMMDD, so YY is read back into the window 2001..2080 - a booking
     * entered as 01.01.26 for the 2026 tax year stays 2026, while 01.01.99 from the
     * old data stays 1999. Falls back to the date in the DBF header, then to today.
     */
    public String bookingYear() throws IOException {
        java.util.TreeSet<Integer> years = new java.util.TreeSet<>();
        for (Buchung b : all()) years.add(LegacyReport.legacyYear(b.datum().getYear()));
        if (years.isEmpty()) {
            int[] d = open().modificationDate();
            if (d[0] > 0) return String.valueOf(LegacyReport.legacyYear(d[0]));
            return String.valueOf(java.time.LocalDate.now().getYear());
        }
        int first = years.first(), last = years.last();
        return first == last ? String.valueOf(first) : first + " - " + last;
    }

    public List<Buchung> all() throws IOException {
        DbfFile db = open();
        List<Buchung> out = new ArrayList<>();
        for (String[] rec : db.live()) out.add(Buchung.from(rec, db));
        return out;
    }

    public List<String> periods() throws IOException {
        String mode = stamm().periodeMode();
        TreeSet<String> set = new TreeSet<>();
        for (Buchung b : all()) set.add(Steuer.periode(b.datum(), mode));
        return new ArrayList<>(set);
    }

    public List<Buchung> filter(String period) throws IOException {
        if (period == null || period.isEmpty()) return all();
        String mode = stamm().periodeMode();
        List<Buchung> out = new ArrayList<>();
        for (Buchung b : all()) {
            // the period key is derived from the booking date and the master period mode
            if (Steuer.periode(b.datum(), mode).equals(period)) out.add(b);
        }
        return out;
    }

    public Map<String, Object> summary(String period) throws IOException {
        List<Buchung> rows = filter(period);
        double eNet = 0, aNet = 0, ust = 0, mwst = 0, bruttoE = 0, bruttoA = 0;
        for (Buchung b : rows) {
            if (b.istAusgabe()) { aNet += b.netto(); mwst += b.steuer(); bruttoA += b.betrag(); }
            else { eNet += b.netto(); ust += b.steuer(); bruttoE += b.betrag(); }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", period == null ? "" : period);
        m.put("periodLabel", Steuer.periodeLabel(period == null ? "" : period));
        m.put("count", rows.size());
        m.put("einnahmenBrutto", Buchung.round(bruttoE));
        m.put("einnahmenNetto", Buchung.round(eNet));
        m.put("ausgabenBrutto", Buchung.round(bruttoA));
        m.put("ausgabenNetto", Buchung.round(aNet));
        m.put("umsatzsteuer", Buchung.round(ust));
        m.put("mehrwertsteuer", Buchung.round(mwst));
        m.put("saldoNetto", Buchung.round(eNet - aNet));
        m.put("saldoSteuer", Buchung.round(ust - mwst));
        return m;
    }

    public Map<String, Object> ust(String period) throws IOException {
        Stammdaten s = stamm();
        double sum1 = 0, steu1 = 0, sum2 = 0, steu2 = 0, vorsteuer = 0;
        for (Buchung b : filter(period)) {
            if (b.istAusgabe()) vorsteuer += b.steuer();
            else if (b.stSchluessel() == 1) { sum2 += b.netto(); steu2 += b.steuer(); }
            else if (b.stSchluessel() == 2) { sum1 += b.netto(); steu1 += b.steuer(); }
        }
        double ust = Math.floor(sum1) / 100.0 * s.rateFull() + Math.floor(sum2) / 100.0 * s.rateReduced();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sumFullNetto", Buchung.round(sum1));
        m.put("sumReducedNetto", Buchung.round(sum2));
        m.put("ustFull", Buchung.round(steu1));
        m.put("ustReduced", Buchung.round(steu2));
        m.put("bemessungsgrundlage", Math.floor(sum1) + Math.floor(sum2));
        m.put("ustSteuer", Buchung.round(ust));
        m.put("vorsteuer", Buchung.round(vorsteuer));
        m.put("zahlbar", Buchung.round(ust - vorsteuer));
        return m;
    }

    public List<Map<String, Object>> monthly() throws IOException {
        double[] e = new double[13];
        double[] a = new double[13];
        for (Buchung b : all()) {
            int m = b.datum().getMonthValue();
            if (b.istAusgabe()) a[m] += b.netto(); else e[m] += b.netto();
        }
        String[] names = {"Januar","Februar","März","April","Mai","Juni","Juli","August","September","Oktober","November","Dezember"};
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", names[i - 1]);
            row.put("einnahmen", Buchung.round(e[i]));
            row.put("ausgaben", Buchung.round(a[i]));
            row.put("ergebnis", Buchung.round(e[i] - a[i]));
            out.add(row);
        }
        return out;
    }

    public List<Map<String, Object>> jabu() throws IOException {
        if (!Files.exists(jabuPath)) return List.of();
        DbfFile db = new DbfFile(jabuPath);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] rec : db.live()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jahr", db.get(rec, "JA_JAHR"));
            row.put("einnahmen", db.num(rec, "JA_EIN_NET"));
            row.put("ausgaben", db.num(rec, "JA_AUS_NET"));
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> add(Map<String, Object> body) throws IOException {
        Stammdaten s = stamm();
        Buchung b = build(body, s, 0);
        DbfFile db = open();
        int nr = db.live().stream().mapToInt(r -> (int) db.num(r, "BU_NR")).max().orElse(0) + 1;
        db.append(toRow(b, nr));
        db.save();
        return toMap(Buchung.from(rowOf(db, nr), db));
    }

    public Map<String, Object> update(int nr, Map<String, Object> body) throws IOException {
        Stammdaten s = stamm();
        DbfFile db = open();
        String[] rec = null;
        for (String[] r : db.live()) if ((int) db.num(r, "BU_NR") == nr) { rec = r; break; }
        if (rec == null) throw new ValidationException("Buchung " + nr + " nicht gefunden");
        double betrag = body.containsKey("betrag") ? asDouble(body.get("betrag")) : db.num(rec, "BU_BETRAG");
        int schl = body.containsKey("steuerSchluessel") ? (int) asDouble(body.get("steuerSchluessel")) : (int) db.num(rec, "BU_ST_SCHL");
        LocalDate datum = body.containsKey("datum") ? asDate(body.get("datum")) : Buchung.parseDbfDate(db.get(rec, "BU_DATUM"));
        String art = body.containsKey("art") ? body.get("art").toString().trim().toUpperCase() : db.get(rec, "BU_ART");
        String text = body.containsKey("text") ? body.get("text").toString() : db.get(rec, "BU_TEXT");
        validate(art, schl, betrag);
        rec[db.fieldIndex("BU_NR")] = String.valueOf(nr);
        rec[db.fieldIndex("BU_DATUM")] = String.format("%04d%02d%02d", datum.getYear(), datum.getMonthValue(), datum.getDayOfMonth());
        rec[db.fieldIndex("BU_ART")] = art;
        rec[db.fieldIndex("BU_TEXT")] = text;
        rec[db.fieldIndex("BU_BETRAG")] = String.format("%.2f", betrag);
        rec[db.fieldIndex("BU_STEUER")] = String.format("%.2f", Steuer.steuer(betrag, schl, s.rateReduced(), s.rateFull()));
        rec[db.fieldIndex("BU_ST_SCHL")] = String.valueOf(schl);
        rec[db.fieldIndex("BU_PERIODE")] = Steuer.periode(datum, s.periodeMode());
        db.save();
        return toMap(Buchung.from(rec, db));
    }

    public boolean delete(int nr) throws IOException {
        DbfFile db = open();
        boolean found = false;
        for (String[] r : db.live()) if ((int) db.num(r, "BU_NR") == nr) { db.delete(r); found = true; }
        if (found) db.save();
        return found;
    }

    public Map<String, Object> yearEnd() throws IOException {
        List<Buchung> rows = all();
        Map<String, Object> m = new LinkedHashMap<>();
        if (rows.isEmpty()) { m.put("ok", false); m.put("message", "Keine Buchungen im aktuellen Jahr"); return m; }
        double ein = 0, aus = 0;
        int jahr = rows.get(0).datum().getYear();
        for (Buchung b : rows) {
            if (b.istAusgabe()) aus += b.netto(); else ein += b.netto();
        }
        String backup = "KABU." + String.format("%02d", jahr % 100);
        Files.copy(kabuPath, dataDir.resolve(backup), StandardCopyOption.REPLACE_EXISTING);

        // readable CSV copy in front of the archive: out/bak/KABU-<jahr>.csv
        Path csvDir = backupDir();
        Files.createDirectories(csvDir);
        Path csvFile = csvDir.resolve("KABU-" + jahr + ".csv");
        Files.writeString(csvFile, BookingsIo.csv(rows), StandardCharsets.UTF_8);

        DbfFile jabu = new DbfFile(jabuPath);
        Map<String, String> rec = new LinkedHashMap<>();
        rec.put("JA_JAHR", String.valueOf(jahr));
        rec.put("JA_EIN_NET", String.format("%.2f", ein));
        rec.put("JA_AUS_NET", String.format("%.2f", aus));
        jabu.append(rec);
        jabu.save();

        DbfFile db = open();
        int n = db.live().size();
        for (String[] r : db.live()) db.delete(r);
        db.save();
        m.put("ok", true);
        m.put("jahr", jahr);
        m.put("einnahmen", Buchung.round(ein));
        m.put("ausgaben", Buchung.round(aus));
        m.put("geloescht", n);
        m.put("backup", backup);
        m.put("csvBackup", csvFile.toString());
        m.put("schritte", List.of(
                "Jahressumme " + jahr + ": Einnahmen netto " + Buchung.round(ein)
                        + ", Ausgaben netto " + Buchung.round(aus) + " nach JABU.DBF geschrieben",
                "KABU.DBF als " + backup + " gesichert",
                "CSV-Export " + csvFile + " (" + n + " Buchungen)",
                n + " Buchung(en) aus KABU.DBF entfernt"));
        return m;
    }

    /**
     * Where generated files go: the year-end CSV archive and the tar.gz backup.
     * KB_BACKUP_DIR names the directory exactly and wins. KB_OUT names an output
     * volume (the Docker image mounts /out); backups go into its "bak" subdirectory
     * so they do not sit loose next to other generated files. Default is out/bak
     * next to the working directory. Absolute paths are used as given, relative
     * ones resolved against the working directory.
     */
    public static Path backupDir() {
        String exact = System.getenv("KB_BACKUP_DIR");
        if (exact != null && !exact.isBlank()) return Path.of(exact.trim());
        String out = System.getenv("KB_OUT");
        if (out != null && !out.isBlank()) return Path.of(out.trim()).resolve("bak");
        return Path.of("out/bak");
    }

    /**
     * One archive of everything that makes up the cash book: KABU.DBF, JABU.DBF,
     * STAMM.MEM and every year-end copy KABU.xx. The files are packed as a tar and
     * compressed with gzip (both written here, so no dependency and no external tool).
     *
     * @return the archive path plus what went into it - the caller shows this to the user
     */
    public Map<String, Object> createBackup() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String name : List.of("KABU.DBF", "JABU.DBF", "STAMM.MEM")) {
            Path p = dataDir.resolve(name);
            if (Files.exists(p)) files.add(p);
        }
        List<String> yearCopies = new ArrayList<>();
        try (var list = Files.list(dataDir)) {
            list.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("KABU\\.[0-9]{2}"))
                .sorted()
                .forEach(p -> { yearCopies.add(p.getFileName().toString()); files.add(p); });
        }

        LocalDateTime now = LocalDateTime.now();
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String archiveName = "KB_DATA_BACK_" + stamp + ".tar.gz";
        Path dir = backupDir();
        Files.createDirectories(dir);
        Path archive = dir.resolve(archiveName);

        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Path f : files) writeTarEntry(tar, f.getFileName().toString(), f);
        byte[] tarBytes = tar.toByteArray();

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) { out.write(tarBytes); }
        Files.write(archive, gz.toByteArray());

        long uncompressed = files.stream().mapToLong(p -> {
            try { return Files.size(p); } catch (IOException e) { return 0L; }
        }).sum();

        List<String> steps = new ArrayList<>();
        steps.add("KABU.DBF, JABU.DBF und STAMM.MEM gelesen");
        if (!yearCopies.isEmpty()) steps.add("Jahreskopien einbezogen: " + String.join(", ", yearCopies));
        steps.add(files.size() + " Datei(en) als tar gepackt und mit gzip komprimiert");
        steps.add("Archiv geschrieben: " + archive);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("archiv", archive.toString());
        m.put("name", archiveName);
        m.put("dateien", files.stream().map(p -> p.getFileName().toString()).toList());
        m.put("jahreskopien", yearCopies);
        m.put("groesse", Files.size(archive));
        m.put("unkomprimiert", uncompressed);
        m.put("schritte", steps);
        m.put("hinweis", "Diese Datei außerhalb des Programmordners aufbewahren - erst dann ist sie ein Backup.");
        return m;
    }

    /** one POSIX ustar member; the name is short enough to fit the 100-byte field */
    private static void writeTarEntry(ByteArrayOutputStream out, String name, Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        final int BLOCK = 512;
        byte[] header = new byte[BLOCK];
        put(header, 0, 100, name.getBytes(StandardCharsets.US_ASCII));          // name
        put(header, 100, 8, oct(0644, 7));                                      // mode
        put(header, 108, 8, oct(0, 7));                                         // uid
        put(header, 116, 8, oct(0, 7));                                         // gid
        put(header, 124, 12, oct(data.length, 11));                             // size
        put(header, 136, 12, oct(Math.min(Files.getLastModifiedTime(file).toMillis() / 1000, 07777777777L), 11)); // mtime
        header[156] = '0';                                                      // typeflag: regular file
        put(header, 257, 6, "ustar\0".getBytes(StandardCharsets.US_ASCII));     // magic
        put(header, 263, 2, "00".getBytes(StandardCharsets.US_ASCII));          // version
        // the checksum field is filled with spaces while summing, then written back
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int sum = 0;
        for (byte b : header) sum += b & 0xff;
        put(header, 148, 7, oct(sum, 7));                                       // 6 octal digits + NUL
        header[155] = ' ';
        out.write(header);
        out.write(data);
        int pad = (BLOCK - data.length % BLOCK) % BLOCK;
        out.write(new byte[pad]);
    }

    private static void put(byte[] target, int off, int len, byte[] src) {
        for (int i = 0; i < len && i < src.length; i++) target[off + i] = src[i];
    }

    /** octal, zero padded, NUL terminated - as ustar expects it */
    private static byte[] oct(long value, int width) {
        String s = Long.toString(value, 8);
        if (s.length() > width - 1)
            throw new IllegalStateException("Wert passt nicht in das tar-Feld: " + value);
        byte[] out = new byte[width];
        for (int i = 0; i < s.length(); i++) out[i] = (byte) s.charAt(i);
        return out;
    }

    private Buchung build(Map<String, Object> body, Stammdaten s, int nr) {
        String art = asString(body.get("art"), "E").toUpperCase();
        int schl = (int) asDouble(body.getOrDefault("steuerSchluessel", 0));
        double betrag = asDouble(body.getOrDefault("betrag", 0));
        LocalDate datum = asDate(body.get("datum"));
        validate(art, schl, betrag);
        double steuer = Steuer.steuer(betrag, schl, s.rateReduced(), s.rateFull());
        String text = asString(body.get("text"), "");
        return new Buchung(nr, datum, art, text, betrag, steuer, schl, Steuer.periode(datum, s.periodeMode()));
    }

    private static void validate(String art, int schl, double betrag) {
        if (!art.equals("E") && !art.equals("A"))
            throw new ValidationException("Nur 'A' für Ausgabe oder 'E' für Einnahme möglich");
        if (schl < 0 || schl > 2)
            throw new ValidationException("Nur '0' ohne Steuer, '1' verminderter Satz oder '2' voller Satz");
        if (betrag <= 0) throw new ValidationException("Betrag muss größer 0.0 sein");
    }

    private static Map<String, String> toRow(Buchung b, int nr) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("BU_NR", String.valueOf(nr));
        m.put("BU_DATUM", String.format("%04d%02d%02d", b.datum().getYear(), b.datum().getMonthValue(), b.datum().getDayOfMonth()));
        m.put("BU_ART", b.art());
        m.put("BU_TEXT", b.text());
        m.put("BU_BETRAG", String.format("%.2f", b.betrag()));
        m.put("BU_STEUER", String.format("%.2f", b.steuer()));
        m.put("BU_ST_SCHL", String.valueOf(b.stSchluessel()));
        m.put("BU_PERIODE", b.periode());
        return m;
    }

    private static String[] rowOf(DbfFile db, int nr) {
        for (String[] r : db.live()) if ((int) db.num(r, "BU_NR") == nr) return r;
        return new String[db.fields().size()];
    }

    private static String asString(Object o, String def) { return o == null ? def : o.toString(); }

    private static List<String> textList(Object o) {
        if (o == null) return null;
        if (!(o instanceof List<?> l)) throw new ValidationException("Buchungstexte als Liste erwartet");
        List<String> out = new ArrayList<>();
        for (Object x : l) {
            String s = x == null ? "" : x.toString().trim();
            if (s.length() > 30) s = s.substring(0, 30);   // BU_TEXT is 30 chars wide
            out.add(s);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** Rewrite the ST_KTO catalogue (8 slots per group in the legacy layout, extras as ST_KTOE/ST_KTOA). */
    private static void applyCatalogue(Map<String, String> memo, List<String> ein, List<String> aus) {
        for (String k : new ArrayList<>(memo.keySet())) if (k.startsWith("ST_KTO")) memo.remove(k);
        for (int i = 0; i < ein.size(); i++) {
            memo.put(i < 8 ? "ST_KTO" + (i + 1) : "ST_KTOE" + (i + 1), ein.get(i));
        }
        for (int i = 0; i < aus.size(); i++) {
            memo.put(i < 8 ? "ST_KTO" + (9 + i) : "ST_KTOA" + (i + 1), aus.get(i));
        }
    }


    private static double asDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString().replace(',', '.'));
    }

    private static LocalDate asDate(Object o) {
        if (o == null) throw new ValidationException("Datum fehlt");
        String s = o.toString().trim();
        String[] parts = s.split("[./-]");
        if (parts.length != 3) throw new ValidationException("Datum im Format DD.MM.YYYY erwartet");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        int c = Integer.parseInt(parts[2]);
        int d, m, y;
        if (parts[0].length() == 4) { y = a; m = b; d = c; }   // ISO: 2016-12-01
        else { d = a; m = b; y = c; if (y < 100) y += 2000; }  // legacy: 01.12.16
        return LocalDate.of(y, m, d);
    }

    private Map<String, Object> toMap(Buchung b) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nr", b.nr());
        m.put("datum", b.datum().toString());
        m.put("datumDisplay", String.format("%02d.%02d.%04d", b.datum().getDayOfMonth(), b.datum().getMonthValue(), b.datum().getYear()));
        m.put("art", b.art());
        m.put("text", b.text());
        m.put("betrag", b.betrag());
        m.put("steuer", b.steuer());
        m.put("netto", b.netto());
        m.put("steuerSchluessel", b.stSchluessel());
        m.put("periode", Steuer.periode(b.datum(), stamm().periodeMode()));
        m.put("periodeLabel", Steuer.periodeLabel(b.periode()));
        return m;
    }

    public List<Map<String, Object>> bookingMaps(String period) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Buchung b : filter(period)) out.add(toMap(b));
        return out;
    }

    public Map<String, Object> meta() throws IOException {
        Stammdaten s = stamm();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name1", s.name1());
        m.put("name2", s.name2());
        m.put("adresse", s.strasse() + ", " + s.plz() + " " + s.ort());
        m.put("strasse", s.strasse());
        m.put("plz", s.plz());
        m.put("ort", s.ort());
        m.put("gewerbe", s.gewerbe());
        m.put("finanzamt", s.finanzamt());
        m.put("steuernummer", s.stnr());
        m.put("telefon", s.telefon());
        m.put("telefax", s.telefax());
        m.put("email", s.email());
        m.put("rateFull", s.rateFull());
        m.put("rateReduced", s.rateReduced());
        m.put("periodeMode", s.periodeMode());
        m.put("periodeModeLabel", switch (s.periodeMode()) {
            case "1111" -> "jährlich";
            case "0111" -> "monatlich";
            case "4111" -> "quartalsweise";
            default -> "unbekannt";
        });
        m.put("einnahmeTexte", java.util.Arrays.asList(s.textEinnahme()));
        m.put("ausgabeTexte", java.util.Arrays.asList(s.textAusgabe()));
        m.put("periods", periods());
        return m;
    }

    /* ------------------------------------------------* interchange formats *
     *
     * KABU.DBF remains the only live store. JSON and CSV are export/import
     * formats: written out of the DBF and read back into it. Nothing reads its
     * bookings from those files at runtime.
     */

    /** Bookings for an export, optionally filtered by period key ("" or null = all). */
    public List<Buchung> bookingsFor(String period) throws IOException {
        String mode = stamm().periodeMode();
        List<Buchung> out = new ArrayList<>();
        for (Buchung b : all()) {
            String p = Steuer.periode(b.datum(), mode);      // derived, like the reports do
            if (period != null && !period.isEmpty() && !p.equals(period)) continue;
            out.add(new Buchung(b.nr(), b.datum(), b.art(), b.text(), b.betrag(), b.steuer(), b.stSchluessel(), p));
        }
        return out;
    }

    public String exportCsv(String period) throws IOException {
        return BookingsIo.csv(bookingsFor(period));
    }

    public String exportJson(String period) throws IOException {
        return BookingsIo.json(bookingsFor(period), stamm(), period);
    }

    /** A faithful copy of the live legacy file - unfiltered, not an exchange format. */
    public byte[] exportDbf() throws IOException {
        return Files.readAllBytes(kabuPath);
    }

    public static String exportFileName(String format, String period) {
        String ext = switch (format) {
            case "json" -> "json";
            case "csv" -> "csv";
            case "dbf" -> "dbf";
            default -> format;
        };
        if (ext.equals("dbf")) return "KABU.dbf";   // the live file copy is not filtered by period
        return "KABU" + (period == null || period.isEmpty() ? "-alle" : "-" + period) + "." + ext;
    }

    /**
     * Import bookings into KABU.DBF.
     *
     * mode "append"  keeps the existing bookings (default, non-destructive)
     * mode "replace" marks every live record deleted first; the previous KABU.DBF is
     *              copied to KABU.BAK, like the legacy year-end copies it to KABU.YY
     *
     * Tax and period are always recalculated from STAMM.MEM, so an imported file can
     * never disagree with the master data (the same rule as add/update).
     */
    public Map<String, Object> importBookings(String format, byte[] content, String mode) throws IOException {
        List<Buchung> rows = BookingsIo.parse(format, content);
        if (rows.isEmpty()) throw new ValidationException("Keine Buchungen in der Datei");
        Stammdaten s = stamm();

        List<String> errors = new ArrayList<>();
        List<Buchung> fixed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Buchung r = rows.get(i);
            try {
                Map<String, Object> body = importBody(r);
                Buchung b = build(body, s, r.nr());
                if (b.text().length() > 30)
                    throw new ValidationException("Text passt nicht in BU_TEXT (30 Zeichen): " + b.text());
                fixed.add(b);
            } catch (ValidationException e) {
                errors.add("Zeile " + (i + 1) + " (nr " + r.nr() + "): " + e.getMessage());
            } catch (RuntimeException e) {
                errors.add("Zeile " + (i + 1) + " (nr " + r.nr() + "): " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            String shown = String.join(" | ", errors.subList(0, Math.min(5, errors.size())));
            throw new ValidationException(errors.size() + " fehlerhafte Zeile(n) - " + shown
                    + (errors.size() > 5 ? " ..." : "") + " - es wurde nichts geschrieben");
        }

        boolean replace = "replace".equalsIgnoreCase(mode);
        DbfFile db = open();
        List<Integer> liveNrs = new ArrayList<>();
        for (String[] rec : db.live()) {
            String v = db.get(rec, "BU_NR");
            if (!v.isEmpty()) liveNrs.add(Integer.parseInt(v));
        }

        String backup = null;
        if (replace) {
            backup = "KABU.BAK";
            Files.copy(kabuPath, dataDir.resolve(backup), StandardCopyOption.REPLACE_EXISTING);
            for (String[] rec : db.live()) db.delete(rec);
        }

        java.util.Set<Integer> used = new java.util.HashSet<>();
        int next = 1;
        if (!replace) {                       // replace starts from scratch, so old numbers are free again
            used.addAll(liveNrs);
            next = liveNrs.isEmpty() ? 1 : java.util.Collections.max(liveNrs) + 1;
        }
        int imported = 0;
        for (Buchung r : fixed) {
            int nr = r.nr();
            if (nr <= 0 || used.contains(nr)) { while (used.contains(next)) next++; nr = next; }
            used.add(nr);
            if (nr >= next) next = nr + 1;
            db.append(toRow(r, nr));
            imported++;
        }
        db.save();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("format", format);
        res.put("mode", replace ? "replace" : "append");
        res.put("importiert", imported);
        res.put("gesamt", all().size());
        res.put("backup", backup);
        res.put("datenbank", kabuPath.toString());
        res.put("nachricht", imported + " Buchung(en) importiert (" + (replace ? "ersetzt" : "angehängt")
                + "), Bestand danach: " + all().size());
        return res;
    }

    /** Import a file; format comes from the extension (.json, .csv). */
    public Map<String, Object> importFile(Path file, String mode) throws IOException {
        String format = BookingsIo.formatOf(file.getFileName().toString());
        if (format == null)
            throw new ValidationException("Unbekannte Dateiendung: " + file.getFileName() + " (.json, .csv)");
        return importBookings(format, Files.readAllBytes(file), mode);
    }

    /** An imported row becomes an ordinary booking body, so import uses the same rules as POST /api/bookings. */
    private static Map<String, Object> importBody(Buchung r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datum", r.datum().toString());
        body.put("art", r.art());
        body.put("text", r.text());
        body.put("betrag", r.betrag());
        body.put("steuerSchluessel", r.stSchluessel());
        return body;
    }
}
