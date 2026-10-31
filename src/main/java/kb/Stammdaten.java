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
