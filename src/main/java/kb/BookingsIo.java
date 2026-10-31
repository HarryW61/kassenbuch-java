package kb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interchange formats for the cash book: JSON and CSV.
 *
 * KABU.DBF stays the only live store - these formats are written *from* the DBF
 * and read *back into* it. Nothing in the app reads its bookings from JSON or
 * CSV at runtime.
 *
 * Column order / names are the same in both formats:
 *   nr, datum, art, text, betrag, steuer, netto, steuerSchluessel, periode
 *
 * CSV uses ';' with comma decimals (German Excel) and dd.mm.yyyy dates, exactly
 * like the legacy DBF fields. JSON uses ISO dates and dot decimals.
 */
public final class BookingsIo {

    private static final String[] CSV_HEADER =
            {"nr", "datum", "art", "text", "betrag", "steuer", "netto", "steuerSchluessel", "periode"};

    /* ------------------------------------------------------------------ JSON */

    public static String json(List<Buchung> rows, Stammdaten stamm, String period) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "kassenbuch-buchungen/1");
        root.put("quelle", "KABU.DBF");
        root.put("exportiert", LocalDate.now().toString());
        root.put("periode", period == null ? "" : period);
        root.put("periodeLabel", Steuer.periodeLabel(period == null ? "" : period));
        root.put("anzahl", rows.size());
        if (stamm != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name1", stamm.name1());
            s.put("name2", stamm.name2());
            s.put("steuernummer", stamm.stnr());
            s.put("rateFull", stamm.rateFull());
            s.put("rateReduced", stamm.rateReduced());
            s.put("periodeMode", stamm.periodeMode());
            root.put("stamm", s);
        }
        root.put("buchungen", rows.stream().map(BookingsIo::jsonRow).toList());
        return Json.encodePretty(root, 2) + "\n";
    }

    private static Map<String, Object> jsonRow(Buchung b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nr", (long) b.nr());
        m.put("datum", b.datum().toString());
        m.put("art", b.art());
        m.put("text", b.text());
        m.put("betrag", b.betrag());
        m.put("steuer", b.steuer());
        m.put("netto", b.netto());
        m.put("steuerSchluessel", (long) b.stSchluessel());
        m.put("periode", b.periode());
        return m;
    }

    /** Accepts either a plain array of bookings or an object with a "buchungen" list. */
    public static List<Buchung> parseJson(String text) {
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException e) {
            throw new Store.ValidationException("JSON konnte nicht gelesen werden: " + e.getMessage());
        }
        List<Object> list;
        if (parsed instanceof List<?> l) list = new ArrayList<>(l);
        else if (parsed instanceof Map<?, ?> m) {
            Object rows = m.containsKey("buchungen") ? m.get("buchungen") : m.get("bookings");
            if (!(rows instanceof List<?> l)) throw new Store.ValidationException("Feld \"buchungen\" (Liste) erwartet");
            list = new ArrayList<>(l);
        } else throw new Store.ValidationException("JSON-Liste oder -Objekt erwartet");

        List<Buchung> out = new ArrayList<>();
        int i = 0;
        for (Object o : list) {
            i++;
            if (!(o instanceof Map<?, ?> m)) throw new Store.ValidationException("Zeile " + i + ": Objekt erwartet");
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) row.put(String.valueOf(e.getKey()), e.getValue());
            out.add(new Buchung(
                    (int) num(row.getOrDefault("nr", row.get("buchungsnr")), 0),
                    date(row.get("datum"), "Zeile " + i),
                    text(row.get("art"), "").toUpperCase(Locale.ROOT),
                    text(row.get("text"), ""),
                    num(row.get("betrag"), 0),
                    num(row.get("steuer"), 0),
                    (int) num(row.getOrDefault("steuerSchluessel", row.get("steuer_schl")), 0),
                    text(row.get("periode"), "")));
        }
        return out;
    }

    /* ------------------------------------------------------------------- CSV */

    public static String csv(List<Buchung> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');                       // so Excel on German locale reads the umlauts
        sb.append(String.join(";", CSV_HEADER)).append("\r\n");
        for (Buchung b : rows) {
            sb.append(b.nr()).append(';')
              .append(String.format(Locale.US, "%02d.%02d.%04d",
                      b.datum().getDayOfMonth(), b.datum().getMonthValue(), b.datum().getYear())).append(';')
              .append(b.art()).append(';')
              .append(csvField(b.text())).append(';')
              .append(de(b.betrag())).append(';')
              .append(de(b.steuer())).append(';')
              .append(de(b.netto())).append(';')
              .append(b.stSchluessel()).append(';')
              .append(csvField(b.periode())).append("\r\n");
        }
        return sb.toString();
    }

    private static String csvField(String s) {
        if (s == null) return "";
        if (s.indexOf(';') >= 0 || s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.startsWith(" ")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String de(double v) { return String.format(Locale.GERMAN, "%.2f", v); }

    /** Tolerant CSV reader: ';' or ',' separated, quoted fields, header optional. */
    public static List<Buchung> parseCsv(String text) {
        List<List<String>> lines = new ArrayList<>();
        for (List<String> line : splitCsv(text)) if (!line.isEmpty()) lines.add(line);
        if (lines.isEmpty()) return List.of();

        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> first = lines.get(0);
        // a header is recognised by its field names, so a headerless file may start with any column
        boolean hasHeader = first.stream().anyMatch(f -> switch (normaliseKey(f)) {
            case "nr", "datum", "art", "text", "betrag", "steuer", "netto", "steuerschluessel", "periode", "buchungsnr" -> true;
            default -> false;
        });
        int start = 0;
        if (hasHeader) {
            for (int i = 0; i < first.size(); i++) col.put(normaliseKey(first.get(i)), i);
            start = 1;
        }
        int nr = col.getOrDefault("nr", hasHeader ? -1 : 0);
        int datum = col.getOrDefault("datum", hasHeader ? -1 : 1);
        int art = col.getOrDefault("art", hasHeader ? -1 : 2);
        int txt = col.getOrDefault("text", hasHeader ? -1 : 3);
        int betrag = col.getOrDefault("betrag", hasHeader ? -1 : 4);
        int steuer = col.getOrDefault("steuer", hasHeader ? -1 : 5);
        int schl = col.getOrDefault("steuerschluessel", hasHeader ? -1 : 7);
        int periode = col.getOrDefault("periode", hasHeader ? -1 : 8);
        if (datum < 0 || art < 0 || betrag < 0)
            throw new Store.ValidationException("CSV-Spalten datum/art/betag fehlen (Kopfzeile oder Reihenfolge nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode)");

        List<Buchung> out = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            List<String> row = lines.get(i);
            out.add(new Buchung(
                    nr >= 0 ? (int) num(at(row, nr), 0) : 0,
                    date(at(row, datum), "Zeile " + (i + 1)),
                    text(at(row, art), "").toUpperCase(Locale.ROOT),
                    text(at(row, txt), ""),
                    num(at(row, betrag), 0),
                    steuer < 0 ? 0 : num(at(row, steuer), 0),
                    schl < 0 ? 0 : (int) num(at(row, schl), 0),
                    text(at(row, periode), "")));
        }
        return out;
    }

    private static String at(List<String> row, int i) { return i >= 0 && i < row.size() ? row.get(i) : ""; }

    /** Header names are matched loosely: umlauts are transliterated, '_' and case ignored. */
    private static String normaliseKey(String k) {
        String s = k.trim().toLowerCase(Locale.ROOT);
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ä', 'Ä' -> t.append("ae");
                case 'ö', 'Ö' -> t.append("oe");
                case 'ü', 'Ü' -> t.append("ue");
                case 'ß' -> t.append("ss");
                default -> { if (c != '_' && c != '-' && c != ' ') t.append(c); }
            }
        }
        return switch (t.toString()) {
            case "buchungsnr", "nummer", "bunr", "buchungnr", "nr" -> "nr";
            case "steuerschluessel", "schluessel", "schlussel", "steuerkey", "stschl", "steuerschl", "schluesselkey" -> "steuerschluessel";
            case "buchungstext", "bezeichnung" -> "text";
            default -> t.toString();
        };
    }

    /** Split into rows of fields, honouring quoted cells and both separators. */
    private static List<List<String>> splitCsv(String text) {
        String s = text.startsWith("\uFEFF") ? text.substring(1) : text;
        char sep = firstLine(s).indexOf(';') >= 0 ? ';' : ',';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(c);
                continue;
            }
            if (c == '"' && cell.isEmpty()) { quoted = true; continue; }
            if (c == sep) { row.add(cell.toString()); cell.setLength(0); continue; }
            if (c == '\r') continue;
            if (c == '\n') { row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>(); continue; }
            cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /* ----------------------------------------------------------------- shared */

    public static List<Buchung> parse(String format, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        switch (format) {
            case "json" -> { return parseJson(text); }
            case "csv" -> { return parseCsv(text); }
            default -> throw new Store.ValidationException("Unbekanntes Format: " + format + " (json, csv)");
        }
    }

    /** .json / .csv -> format name, or null when unknown. */
    public static String formatOf(String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        if (f.endsWith(".json")) return "json";
        if (f.endsWith(".csv") || f.endsWith(";")) return "csv";
        return null;
    }

    static LocalDate date(Object o, String where) {
        if (o == null || String.valueOf(o).isBlank()) throw new Store.ValidationException(where + ": Datum fehlt");
        String s = String.valueOf(o).trim();
        String[] p = s.split("[./-]");
        if (p.length != 3) throw new Store.ValidationException(where + ": Datum im Format DD.MM.YYYY oder YYYY-MM-DD: " + s);
        int a = (int) num(p[0], 0), b = (int) num(p[1], 0), c = (int) num(p[2], 0);
        int d, m, y;
        if (p[0].length() == 4) { y = a; m = b; d = c; }
        else { d = a; m = b; y = c; if (y < 100) y += 2000; }
        try {
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            throw new Store.ValidationException(where + ": ungültiges Datum " + s);
        }
    }

    static double num(Object o, double def) {
        if (o == null || String.valueOf(o).isBlank()) return def;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new Store.ValidationException("Zahl erwartet: " + o);
        }
    }

    static String text(Object o, String def) { return o == null ? def : String.valueOf(o); }
}
