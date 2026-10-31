package kb;

import java.time.LocalDate;

public record Buchung(int nr, LocalDate datum, String art, String text,
                      double betrag, double steuer, int stSchluessel, String periode) {

    public boolean istEinnahme() { return art.startsWith("E"); }
    public boolean istAusgabe()  { return art.startsWith("A"); }
    public double netto()        { return round(betrag - steuer); }

    public static Buchung from(String[] rec, DbfFile dbf) {
        return new Buchung(
                (int) dbf.num(rec, "BU_NR"),
                parseDbfDate(dbf.get(rec, "BU_DATUM")),
                dbf.get(rec, "BU_ART"),
                dbf.get(rec, "BU_TEXT"),
                dbf.num(rec, "BU_BETRAG"),
                dbf.num(rec, "BU_STEUER"),
                (int) dbf.num(rec, "BU_ST_SCHL"),
                dbf.get(rec, "BU_PERIODE"));
    }

    /**
     * Harbour read dates with format "dd.mm.yy" and a 2-digit year, so the
     * stored value is 19YYMMDD. TOOL.PRG compensates with "IF jahr <= 1970 jahr += 100".
     */
    static LocalDate parseDbfDate(String s) {
        s = s.replaceAll("[^0-9]", "");
        if (s.length() < 6) return LocalDate.now();
        int y = Integer.parseInt(s.substring(0, 4));
        int m = Integer.parseInt(s.substring(4, 6));
        int d = Integer.parseInt(s.substring(6, 8));
        if (y <= 1970) y += 100;
        return LocalDate.of(y, m, d);
    }

    static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
