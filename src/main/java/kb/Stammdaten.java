// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record Stammdaten(

    // E-Mail slot in STAMM.MEM: 40 characters (legacy files store it as ST_EMAIL)
        String name1, String name2, String stnr, String finanzamt,
        String strasse, String plz, String ort, String telefon, String telefax,
        String email,
        String gewerbe, double rateFull, double rateReduced, String periodeMode,
        String[] textEinnahme, String[] textAusgabe) {

    public static final int EMAIL_MAX = 40;

    /**
     * Placeholder identity of the file this port ships with. The original Harbour
     * program asked its first user to type these in, so they are the values a fresh
     * copy carries - and proof that nobody has filled in real master data yet.
     */
    private static final List<String> PRISTINE_MARKERS = List.of(
            "DEINNAME", "ZWEITNAME", "IregendWoInBayern", "BayernWeg",
            "Was machst du so", "Wo geht die Kohle hin", "123/123/12345",
            // the anonymised fixture set uses generic placeholders instead
            "Testname", "Testfirma", "Testgewerbe", "Testfinanzamt",
            "Testweg 1", "00000", "Testort", "000/000/00000");

    /** identity fields a report header needs: without them every printout is wrong */
    private static final List<String> IDENTITY_KEYS =
            List.of("name1", "name2", "strasse", "plz", "ort", "gewerbe", "finanzamt", "steuernummer");

    /**
     * True when the master data still holds placeholders or is simply empty, i.e. when
     * a guided first start makes sense. Deliberately ignores the printer and terminal
     * fields (ST_QFO, ST_HOF, ST_FETON/OFF, ST_COMPON/OFF, ST_DRUCKER, ST_DATUM), which
     * this port does not use.
     */
    public boolean isPristine() {
        for (String v : List.of(name1, name2, strasse, plz, ort, gewerbe, finanzamt, stnr)) {
            if (v == null || v.isBlank()) return true;
            if (PRISTINE_MARKERS.stream().anyMatch(m -> m.equalsIgnoreCase(v))) return true;
        }
        return false;
    }

    /** identity fields still missing or placeholder - what the assistant must ask for */
    public List<String> missingIdentity() {
        Map<String, String> values = Map.of(
                "name1", name1, "name2", name2, "strasse", strasse, "plz", plz, "ort", ort,
                "gewerbe", gewerbe, "finanzamt", finanzamt, "steuernummer", stnr);
        List<String> out = new ArrayList<>();
        for (String key : IDENTITY_KEYS) {
            String v = values.get(key);
            if (v == null || v.isBlank()
                    || PRISTINE_MARKERS.stream().anyMatch(m -> m.equalsIgnoreCase(v))) out.add(key);
        }
        return out;
    }

    /** the rates and period mode carry usable defaults, so the assistant never insists on them */
    public boolean ratesUsable() {
        return rateFull > 0 && rateReduced >= 0 && !periodeMode.isBlank();
    }

    /** true when this field holds nothing usable: empty or one of the shipped placeholders */
    public boolean isPlaceholder(String key) {
        String v = valueOfKey(key);
        if (v == null || v.isBlank()) return true;
        return PRISTINE_MARKERS.stream().anyMatch(m -> m.equalsIgnoreCase(v));
    }

    /** raw value of an assistant field, "" for keys this check does not cover */
    private String valueOfKey(String key) {
        return switch (key) {
            case "name1" -> name1;
            case "name2" -> name2;
            case "strasse" -> strasse;
            case "plz" -> plz;
            case "ort" -> ort;
            case "gewerbe" -> gewerbe;
            case "finanzamt" -> finanzamt;
            case "steuernummer" -> stnr;
            default -> "";
        };
    }

    public static Stammdaten from(Map<String, String> m) {
        String rate2 = m.getOrDefault("ST_STEUER2", "19").trim();
        String rate1 = m.getOrDefault("ST_STEUER1", "7").trim();
        // Einnahme texts: ST_KTO1..ST_KTO8 (legacy), extras as ST_KTOE9, ST_KTOE10 ...
        // Ausgabe  texts: ST_KTO9..ST_KTO16 (legacy), extras as ST_KTOA9, ST_KTOA10 ...
        List<String> vk = new ArrayList<>();
        for (int i = 1; i <= 8; i++) vk.add(trim(m.getOrDefault("ST_KTO" + i, "")));
        vk.addAll(extras(m, "ST_KTOE"));
        List<String> ek = new ArrayList<>();
        for (int i = 9; i <= 16; i++) ek.add(trim(m.getOrDefault("ST_KTO" + i, "")));
        ek.addAll(extras(m, "ST_KTOA"));
        while (!vk.isEmpty() && vk.get(vk.size() - 1).isEmpty()) vk.remove(vk.size() - 1);
        while (!ek.isEmpty() && ek.get(ek.size() - 1).isEmpty()) ek.remove(ek.size() - 1);
        return new Stammdaten(
                trim(m.getOrDefault("ST_NAME1", "")),
                trim(m.getOrDefault("ST_NAME2", "")),
                trim(m.getOrDefault("ST_STNR", "")),
                trim(m.getOrDefault("ST_FIAMT", "")),
                trim(m.getOrDefault("ST_STRASSE", "")),
                trim(m.getOrDefault("ST_PLZ", "")),
                trim(m.getOrDefault("ST_ORT", "")),
                trim(m.getOrDefault("ST_TELEFON", "")),
                trim(m.getOrDefault("ST_TELEFAX", "")),
                clamp(trim(m.getOrDefault("ST_EMAIL", "")), EMAIL_MAX),
                trim(m.getOrDefault("ST_GEWERBE", "")),
                Double.parseDouble(rate2.isEmpty() ? "0" : rate2),
                Double.parseDouble(rate1.isEmpty() ? "0" : rate1),
                m.getOrDefault("ST_PERIODE", "4111").trim(),
                vk.toArray(new String[0]), ek.toArray(new String[0]));
    }

    private static List<String> extras(Map<String, String> m, String prefix) {
        TreeMap<Integer, String> idx = new TreeMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(prefix)) continue;
            String digits = k.substring(prefix.length());
            if (digits.isEmpty()) continue;
            try {
                idx.put(Integer.parseInt(digits), trim(e.getValue()));
            } catch (NumberFormatException ignore) {
                // not a catalogue entry
            }
        }
        List<String> out = new ArrayList<>();
        int i = 9;
        while (idx.containsKey(i)) { out.add(idx.get(i)); i++; }
        return out;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static String clamp(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
