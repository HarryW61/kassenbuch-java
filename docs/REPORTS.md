# Report layout (roadmap item: "PDF/text reports in the original layout")

The original program printed its reports by writing to a file with
`SET PRINTER TO "Kassenbuch.txt"` / `"Buchungsliste.txt"` (see `PRINT.PRG`,
procedures `DRKABU()` and `DRBULI()`). The printed artefacts are preserved in
the original printer files `Kassenbuch.txt` and `Buchungsliste.txt`
  (kept out of this repository in `fixtures/legacy/`; the tests compare against them
  when that directory exists locally).

This port reproduces that layout **byte for byte** and additionally renders the
same pages as PDF.

## Code

| file | role |
|------|------|
| `src/main/java/kb/LegacyReport.java` | builds the exact legacy pages (text) |
| `src/main/java/kb/PdfReport.java`    | dependency-free PDF writer (PDF 1.4, A4 landscape, Courier) |
| `src/main/java/kb/Reports.java`      | facade: `pages()`, `report()`, `dosReport()`, `pdfReport()` |
| `fixtures/anonymisiert/golden/*.txt` | golden baselines with placeholder identity (shipped) |
| `fixtures/legacy/golden/*.txt`         | untouched KB.EXE printouts, local only (git-ignored) |
| `src/main/java/kb/ReportTests.java`  | golden-file + page-break tests |

## Layout rules extracted from PRINT.PRG

* **Line width 113 columns.** Rules are `"-" * 113` (the original emitted
  `REPLICATE(CHR(196),113)`, i.e. the box-drawing char, which the printer
  driver rendered as a solid line — the surviving printouts contain `─`
  converted to `-`).
* **Header repeats on every page** (`dr_head()`): rule, company name line +
  `Zeitraum:`, address line, `Gewerbe: … Finanzamt: … Steuernummer: …`, rule.
* `Zeitraum:` shows the selected period code (`4326`, `1016`, …). When a report is
  printed over **all periods** (empty period), it shows the **booking year** instead —
  the year the entered dates belong to, read back through the 19YY/20YY window of the
  legacy date mask (`01.01.26` → 2026, `01.01.99` → 1999). If bookings span several
  years it reads `1996 - 2004`. The Kassen-Information header keeps its `Jahr <year>`
  form. An empty KABU.DBF falls back to the date in the DBF header, then to today.
* **Page break**: `IF nZeile > 26` for the Buchungsliste → **27 rows/page**;
  `IF nZeile > 22` for the Kassenbuch → **23 rows/page**.
* The Kassenbuch prints **cumulative `Zwischensumme` blocks** at each page break
  (`Einahme/Umsatzsteuer`, `Ausgaben/Mehrwertsteuer`) and the final totals plus
  the `Unterschied` line at the end.
* Footer: `   >>>>>> Anzahl Buchungen N  <<<<<<  ` and
  `   >>>>>> Ende Kassenbuch <periode> <<<<<<  `.
* Column positions (0-based):

  | column | start | width | content |
  |--------|-------|-------|---------|
  | Bu.Nr. | 0 | 4 | right justified |
  | B.Datum | 7 | 8 | `dd.mm.yy` |
  | Bu.Art | 18 | 4 | `E`/`A` |
  | Text | 25 | 20 | `SUBSTR(BU_TEXT,1,20)` |
  | Einnahme | 49 | 8 | `N(8,2)` gross |
  | Ausgabe.Brt. | 65 | 8 | gross |
  | Ausgabe.Nto | 77 | 13 | net, computed at print time |
  | Steuer | 94 | 8 | tax |
  | Steuerschluessel | 106 | 1 | key |

* **Encoding**: codepage 437 (`LegacyCharset.CODEPAGE`) + CRLF line endings.
  `dosBytes()` produces exactly those bytes.
* **Legacy quirks reproduced literally**:
  * the net is *not* read from `BU_NETTO` (it does not exist); it is computed as
    `betrag / (100 + rate) * 100` while printing. That is why the Kassenbuch
    totals differ by a cent from the stored net values:
    `7960.39` (printed) vs `7960.40` (sum of `BU_BETRAG - BU_STEUER`).
  * "Einahme" (not "Einnahme") in the totals.
  * the header column is spelled `Steuerschlüssel` on the first page of the
    Kassenbuch and `Steuerschluessel` on the follow-up pages (and vice versa in
    DRBULI) — a printing artefact in the source, kept as-is.
  * the header on page 1 of the Kassenbuch has one extra blank line
    ("Querformat" preamble).

## PDF

`PdfReport.render(pages, title)` writes a PDF without any external library:

* A4 landscape (841.89 × 595.28 pt), one page per legacy report page,
* Courier (built-in Type 1 font, 600/1000 advance) at 9 pt, leading 10.8 pt,
  left margin 32 pt → 123 chars fit, so the ASCII table stays aligned,
* text is written with octal escapes so CP437/Latin-1 characters survive,
* valid xref (20-byte entries), trailer, Info dictionary with the title.

Verified with `pdftotext -layout` and Ghostscript (`gs -sDEVICE=png16m`).

## Usage

CLI:

```sh
./run.sh kabu 4416 --pdf out/Kassenbuch-4416.pdf
./run.sh list 4416 --dos out/Buchungsliste-4416.txt      # CP437 + CRLF, like the original
./run.sh ust  4416 --pdf out/Ust.pdf
```

API:

```
GET /api/report/kassenbuch?period=4416&fmt=txt     UTF-8 text (web display)
GET /api/report/kassenbuch?period=4416&fmt=dos     original bytes: CRLF + CP437
GET /api/report/kassenbuch?period=4416&fmt=pdf     PDF download
```

Web: the **Berichte** view has "Download (.txt · CP437)" and "Download (.pdf)".

## On-screen masks (not printed by KB.EXE)

`ust` (Umsatzsteuerberechnung) and `info` (Kassen-Information) were screen masks in
`VORST.PRG`, so they have no golden printout and their layout is the port's own:

* no currency label at all - the amounts carry no `EUR`, so the report reads the
  same in any currency; every amount is right-justified on one fixed column ending
  at character 40. The two rules are deliberately offset to the right of the amount
  column: `------------` (12 chars, characters 39-50) above *Zahlbare Umsatzsteuer*,
  `=============` (13 chars, characters 38-50) below it - the single rule starts one
  field further right than the double rule
* labels are spelled out (`Summe Erlöse voll netto`, `Summe Erlöse ermäßigt netto`,
  `Abziehbare Vorsteuerbeträge`) instead of the truncated screen abbreviations
* `Bemessungsgrundlage (gerundet)` - the value is floored to whole units, shown without
  decimals; the old wording was `(volle EUR)`
* every amount is rounded to cents before it is rendered with two decimals, so a
  sum of several tax values shows as `386.91` and never as `386.90999999999997`
  or, worse, truncated to `386.90`
* the subtotal rule `-------------` and the final `=============` follow `VORST.PRG`
* `info` always covers all periods (see [WEB.md](WEB.md))

## Tests

```sh
./test.sh
```

`kb.ReportTests` checks:

* page count and rows per page (23/9 and 27/5),
* byte-identical output against `fixtures/anonymisiert/golden/*.txt`, and — when
  `fixtures/legacy/` is present — against the untouched KB.EXE printouts in
  `fixtures/legacy/golden/*.txt`. That legacy comparison translates the pre-existing
  "EUR" marker into today's currency-neutral totals block; everything else must match
  byte for byte. The golden printouts were made from a
  31/30-row snapshot, so the comparison uses the matching row subset,
* `Buchungsliste.txt` is untouched original KB.EXE output. `Kassenbuch.txt` was
  **re-recorded** from this port after the currency marker (EUR) was dropped from the
  totals block, and is compared unmodified; the old EUR translation helper stays in
  `ReportTests.alignGolden()` for baselines recorded before that change,
* the generated PDF starts with `%PDF-` and has one page per report page.

Re-record a baseline deliberately, never as a side effect of a failing test:

```sh
./test.sh                                # writes out/{kassenbuch,buchungsliste}-generated.txt
cp out/kassenbuch-generated.txt   fixtures/anonymisiert/golden/Kassenbuch.txt
cp out/buchungsliste-generated.txt  fixtures/anonymisiert/golden/Buchungsliste.txt
```

`ReportTests` renders the same 31/30-row subset as the baselines, so those two files
are byte-identical to them. `./run.sh kabu 4416 --dos …` prints **all** fixture rows
(32) and is meant for eyeballing a printout, not for re-recording. Both paths write
CP437 + CRLF. Compare with `git diff` before committing — a baseline should only
change when the layout was changed on purpose.
