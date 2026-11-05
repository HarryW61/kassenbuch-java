// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guided first start: the fields a report header needs, asked one by one and checked
 * before anything is written. Used by `./run.sh init` (stdin) and by the web dialog, so
 * both accept exactly the same values and produce exactly the same STAMM.MEM.
 *
 * Deliberately limited to the identity fields. The printer and terminal slots of the
 * legacy file (ST_QFO, ST_HOF, ST_FETON/OFF, ST_COMPON/OFF, ST_DRUCKER, ST_DATUM) are
 * 1990s escape sequences this port does not use; MemoWriter carries them over untouched.
 */
public final class Firststart {

    /** one asked field: MEMO slot, German prompt, optional pattern and length limit */
    public record Field(String key, String memoKey, String label, String hint,
                        String pattern, int max, boolean required) {}

    /** the order the assistant asks in - name first because every report starts with it */
    public static final List<Field> FIELDS = List.of(
            new Field("name1", "ST_NAME1", "Name 1", "Inhaber, steht im Berichtskopf", null, 30, true),
            new Field("name2", "ST_NAME2", "Name 2", "Firma, steht im Berichtskopf", null, 30, false),
            new Field("strasse", "ST_STRASSE", "Straße und Hausnummer",
                    "Berichtskopfzeile", null, 30, true),
            new Field("plz", "ST_PLZ", "Postleitzahl", "5 Ziffern", "\\d{5}", 5, true),
            new Field("ort", "ST_ORT", "Ort", "Berichtskopfzeile", null, 25, true),
            new Field("gewerbe", "ST_GEWERBE", "Gewerbe", "z. B. Handel, Dienstleistung", null, 30, false),
            new Field("finanzamt", "ST_FIAMT", "Finanzamt",
                    "zuständiges Finanzamt", null, 30, true),
            new Field("steuernummer", "ST_STNR", "Steuernummer",
                    "Format NNN/NNN/NNNNN beim deutschen Finanzamt", "\\d{2,3}/\\d{2,3}/\\d{4,6}", 20, true),
            new Field("rateFull", "ST_STEUER2", "Steuersatz voll",
                    "Prozent, in Deutschland meist 19", "\\d{1,2}(\\.\\d{1,2})?", 6, false),
            new Field("rateReduced", "ST_STEUER1", "Steuersatz reduziert",
                    "Prozent, in Deutschland meist 7", "\\d{1,2}(\\.\\d{1,2})?", 6, false),
            new Field("periodeMode", "ST_PERIODE", "Periodenmodus",
                    "1111 jährlich, 0111 monatlich, 4111 quartalsweise", "[014]111", 4, false));

    private Firststart() {}

    /**
     * What the dialog should ask for: the identity fields that are still placeholder or
     * empty. Rates and period mode are only offered when STAMM.MEM holds unusable values -
     * they already default to 19 / 7 / quarterly, so asking every first start would be
     * noise.
     */
    public static List<Field> missingFields(Stammdaten s) {
        List<String> missing = s.missingIdentity();
        boolean askRates = !s.ratesUsable();
        List<Field> out = new ArrayList<>();
        for (Field f : FIELDS) {
            if (missing.contains(f.key())) out.add(f);
            else if (askRates && (f.key().equals("rateFull") || f.key().equals("rateReduced")
                    || f.key().equals("periodeMode"))) out.add(f);
        }
        return out;
    }

    /** fields that are already filled in - shown as a preview so nothing is typed twice */
    public static Map<String, String> known(Stammdaten s) {
        List<String> missing = s.missingIdentity();
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            if (missing.contains(f.key())) continue;
            out.put(f.key(), valueOf(s, f.key()));
        }
        return out;
    }

    public static String valueOf(Stammdaten s, String key) {
        return switch (key) {
            case "name1" -> s.name1();
            case "name2" -> s.name2();
            case "strasse" -> s.strasse();
            case "plz" -> s.plz();
            case "ort" -> s.ort();
            case "gewerbe" -> s.gewerbe();
            case "finanzamt" -> s.finanzamt();
            case "steuernummer" -> s.stnr();
            case "rateFull" -> String.valueOf(s.rateFull());
            case "rateReduced" -> String.valueOf(s.rateReduced());
            case "periodeMode" -> s.periodeMode();
            default -> "";
        };
    }

    /** same question as {@link Stammdaten#isPlaceholder}, also for the rate fields */
    public static boolean isPlaceholder(Stammdaten s, String key) {
        if (key.equals("rateFull")) return !(s.rateFull() > 0);
        if (key.equals("rateReduced")) return !(s.rateReduced() >= 0);
        if (key.equals("periodeMode")) return s.periodeMode().isBlank();
        return s.isPlaceholder(key);
    }

    public static String labelOf(String key) {
        for (Field f : FIELDS) if (f.key().equals(key)) return f.label();
        return key;
    }

    /** validate one answer; returns the trimmed value or throws with a German message */
    public static String check(Field f, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) throw new Store.ValidationException(f.label() + " ist erforderlich");
        if (v.length() > f.max())
            throw new Store.ValidationException(
                    f.label() + ": zu lang (" + v.length() + ", maximal " + f.max() + " Zeichen)");
        if (f.pattern() != null && !v.matches(f.pattern()))
            throw new Store.ValidationException(f.label() + ": unerwartetes Format (" + f.hint() + ")");
        return v;
    }

    /**
     * Validate a whole answer set and translate it into the keys `Store.updateStamm`
     * expects. Optional fields may be absent or empty - then the existing value is kept,
     * so the assistant never blanks out something the user did not type. Rates arrive as
     * "19" / "7", which updateStamm writes straight into ST_STEUER2 / ST_STEUER1.
     */
    public static Map<String, Object> validated(Map<String, String> answers) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            String raw = answers.get(f.key());
            if (raw == null || raw.isBlank()) continue;      // untouched: keep the old value
            body.put(f.key(), check(f, raw));
        }
        return body;
    }

    /** human-readable preview of an answer set, for confirmation before writing */
    public static String preview(Map<String, ?> values) {
        StringBuilder sb = new StringBuilder();
        for (Field f : FIELDS) {
            Object v = values.get(f.key());
            if (v == null) continue;
            String shown = "periodeMode".equals(f.key())
                    ? periodLabel(String.valueOf(v)) + " (" + v + ")" : String.valueOf(v);
            sb.append(String.format("  %-22s %s%n", f.label(), shown));
        }
        return sb.toString();
    }

    /**
     * What GET /api/firststart returns: whether a guided start is needed, the fields to
     * ask for (with their hints and limits) and the values already worth keeping.
     */
    public static Map<String, Object> questionnaire(Stammdaten s) {
        List<Map<String, Object>> asks = new ArrayList<>();
        for (Field f : missingFields(s)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("label", f.label());
            m.put("hint", f.hint());
            m.put("required", f.required());
            m.put("max", f.max());
            // a placeholder or empty value is not worth pre-filling - it would only be
            // typed over, so the field starts blank and keeps its old value if left blank
            String current = valueOf(s, f.key());
            m.put("value", isPlaceholder(s, f.key()) ? "" : current);
            asks.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needed", !s.missingIdentity().isEmpty());
        out.put("fields", asks);
        out.put("known", known(s));
        return out;
    }

    /** period mode as the user reads it - the same wording as the web API and terminal */
    public static String periodLabel(String mode) {
        return Steuer.periodLabel(mode);
    }
}
