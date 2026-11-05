// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HarryW61 <wilhar61@gmail.com>
// See LICENSE for the full license text.

package kb;

import java.time.LocalDate;

/** Steuer- und Periodenberechnung, entspricht st_berech() / per_berech() aus BUCHEN.PRG. */
public final class Steuer {

    /** steuer = betrag / (100 + rate) * rate  (Schluessel 0 = keine Steuer) */
    public static double steuer(double betrag, int schluessel, double rateReduced, double rateFull) {
        double rate = switch (schluessel) {
            case 1 -> rateReduced;
            case 2 -> rateFull;
            default -> 0.0;
        };
        return Buchung.round(betrag / (100.0 + rate) * rate);
    }

    /** netto aus brutaler Betrag und Steuerschluessel (wie in PRINT.PRG) */
    public static double netto(double betrag, int schluessel, double rateReduced, double rateFull) {
        return Buchung.round(betrag - steuer(betrag, schluessel, rateReduced, rateFull));
    }

    /** ST_PERIODE: 1111 = jaehrlich, 0111 = monatlich, 4111 = quartalsweise */
    public static String periode(LocalDate datum, String mode) {
        int y = datum.getYear();
        int m = datum.getMonthValue();
        switch (mode) {
            case "1111":
                return String.valueOf(y);
            case "0111":
                return String.format("%02d%02d", m, y % 100);
            case "4111":
                int q = (m - 1) / 3 + 1;
                return String.format("4%d%02d", q, y % 100);
            default:
                return String.valueOf(y);
        }
    }

    /**
     * The period mode as an operator reads it. One place for CLI, web API and terminal,
     * because all three show the same Stammdaten screen and must not drift apart.
     */
    public static String periodLabel(String mode) {
        return switch (mode == null ? "" : mode.trim()) {
            case "1111" -> "jährlich";
            case "0111" -> "monatlich";
            case "4111" -> "quartalsweise";
            default -> "unbekannt";
        };
    }

    public static String periodeLabel(String periode) {
        if (periode.length() == 4 && periode.charAt(0) == '4') {
            return "Q" + periode.charAt(1) + " " + fullYear(periode.substring(2));
        }
        if (periode.length() == 4) {
            return monthName(Integer.parseInt(periode.substring(0, 2))) + " " + fullYear(periode.substring(2));
        }
        return periode;
    }

    /** two-digit years, matching the "<= 1970 -> +100" rule of the original */
    private static int fullYear(String yy) {
        int y = Integer.parseInt(yy);
        return y <= 70 ? 2000 + y : 1900 + y;
    }

    private static String monthName(int m) {
        String[] names = {"Januar","Februar","Maerz","April","Mai","Juni","Juli","August","September","Oktober","November","Dezember"};
        return m >= 1 && m <= 12 ? names[m - 1] : "";
    }
}
