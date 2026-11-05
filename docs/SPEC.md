# Kassenbuch (KB.EXE) — Reverse-Engineering Spec

Source: the original `.PRG` files (Harbour 3.0 / xBase, compiled with `hbmk2`),
not part of this repository — the behaviour was read off them and is described here.
Purpose: cash book (Kassenbuch) for a small German sole proprietorship,
run in an 80x25 DOS/ANSI terminal. Author of the original: the owner of this repository, 1994-2016.

## 1. Program structure

```
Main (MENU.PRG)
  1 Buchen  -> NEUBUCH (new bookings), ABUCH (BROWSE edit)
  2 Steuer  -> VORST (UST-Voranmeldung), KASINF (monthly info)
  3 Berichte-> DRBULI (Buchungsliste), DRKABU (Kassenbuch), DRSTAM (not implemented)
  4 Jahresabschluss -> jahrend()
  5 Stammdaten -> STFI (company), STSCH (tax rates), STPER (period), STPR (printer)
  6 Ende    -> RDOS/QUIT
```

Global break flags: `X1_abbruch` (main), `X2_abbruch` (sub-menu), `X3_abbruch` (mask/entry).
Function keys: F4 = abort/exit, F12 = save master data (in STAMM).

Screen chrome: header `KASSENBUCH` + date/time, `CHR(196)` rules (─) on lines 2 and 22/23,
status line on the bottom, `MENU TO` for numbered prompts, `@ row,col SAY/GET` forms.
The port keeps this structure and the 80-column width, but **not** the original
colours: the terminal UI follows the web UI palette (dark background, green/amber
accents) instead of Harbour's blue field with white text - see §6.

## 2. Data storage

| file      | contents                                              |
|-----------|-------------------------------------------------------|
| `KABU.DBF`| current-year bookings (dBase III, 68-byte records)     |
| `JABU.DBF`| yearly net sums                                        |
| `STAMM.MEM`| Harbour MEMO of `ST_*` master data                    |
| `KABU.YY` | backup created at year end (YY = 2-digit year)         |

### KABU.DBF fields

| field       | type | len | dec | notes |
|-------------|------|-----|-----|-------|
| BU_NR       | N    | 4   | 0   | sequential booking number |
| BU_DATUM    | D    | 8   | 0   | `YYYYMMDD`; years < 1971 get +100 (2-digit-year artefact) |
| BU_ART      | C    | 4   | 0   | `E` = Einnahme, `A` = Ausgabe |
| BU_TEXT     | C    | 30  | 0   | from the text catalogue, or free text |
| BU_BETRAG   | N    | 8   | 2   | gross amount |
| BU_STEUER   | N    | 8   | 2   | tax amount |
| BU_ST_SCHL  | N    | 1   | 0   | 0 none, 1 reduced, 2 full |
| BU_PERIODE  | C    | 4   | 0   | period key |

### STAMM.MEM keys

`ST_NAME1, ST_NAME2, ST_STNR, ST_FIAMT, ST_STRASSE, ST_PLZ, ST_ORT, ST_TELEFON,
ST_TELEFAX, ST_GEWERBE, ST_STEUER0/1/2 (rates as "19", "07"), ST_PERIODE (mode),
ST_KTO1..ST_KTO16 (booking texts), ST_DRUCKER, ST_DATUM, ST_NACHKOM(2)`

Text catalogue: `ST_KTO1..8` = Einnahme texts, `ST_KTO9..16` = Ausgabe texts.

### Interchange formats (this port)

`KABU.DBF` stays the **only live store** - nothing at runtime reads bookings from
JSON or CSV. Those two are export targets and import sources that convert
with the legacy field semantics; imports always write back into `KABU.DBF`
(`BookingsIo`). See [BACKEND.md](BACKEND.md).

## 3. Business rules

**Tax** (`st_berech`):
```
schl 0: steuer = 0
schl 1: steuer = betrag / (100 + ST_STEUER1) * ST_STEUER1
schl 2: steuer = betrag / (100 + ST_STEUER2) * ST_STEUER2
```
Amount must be > 0. Tax is recalculated whenever the amount/schlüssel changes.

**Period** (`per_berech`), driven by `ST_PERIODE`:
| mode   | meaning   | key format | example |
|--------|-----------|------------|---------|
| `1111` | yearly    | `YYYY`     | `2016`  |
| `0111` | monthly   | `MMYY`     | `1016`  |
| `4111` | quarterly | `4Q` + `YY`| `4416`  |

**Net**: `netto = betrag - steuer`.

**UST (Voranmeldung)** (`ust_ber`), for one period:
```
sum1 = Σ net of Einnahmen with schl 2
sum2 = Σ net of Einnahmen with schl 1
Bemessungsgrundlage = INT(sum1) + INT(sum2)
UST   = INT(sum1)/100*rateFull + INT(sum2)/100*rateReduced
Vorsteuer = Σ steuer of all Ausgaben in the period
zahlbare Umsatzsteuer = UST - Vorsteuer
```

**Year end** (`jahrend`): sum net E/A for the year → append to `JABU.DBF`
(`JA_JAHR, JA_EIN_NET, JA_AUS_NET`), copy `KABU.DBF` → `KABU.YY`, then delete
all records (`PACK`).

## 4. Reports

* `DRBULI` — Buchungsliste: one line per booking, 27 lines per page, header repeated per page.
* `DRKABU` — Kassenbuch: like above plus `Ausgabe.Nto`, page totals per page, final totals:
  Einnahmen netto, Umsatzsteuer, Ausgaben netto, Mehrwertsteuer, differences.
* `KASINF` — 12 monthly rows (net E/A/result) + grand total.
* Output "printer" is actually a text file (`ST_DRUCKER` default `LPT1:`, in practice
  `Buchungsliste.txt` / `Kassenbuch.txt`).

## 5. Quirks worth keeping (or fixing deliberately)

1. Dates are stored as 19YYMMDD because the input mask is `99.99.99`.
2. `ABUCH` edits rows in a `BROWSE` grid but does **not** recalculate tax.
3. `ST_KTO8` is empty in this data set, and the text-picker only appears when the
   text field is blank (`txttest`).
4. `DRSTAM` is a stub ("Das gibts noch nicht !!!").
5. `JABU.DBF` contains a row with year 1906 (the 1970 correction was applied
   inconsistently in older versions).

## 6. Port roadmap

* [x] DBF reader/writer, MEMO reader, tax/period logic, CLI reports, smoke tests
* [x] PDF/text report generator with the original layout - see [REPORTS.md](REPORTS.md)
* [x] booking edit + validation (A/E, schl 0..2, amount > 0) - `Store.build()`, API and UI
* [x] umlaut-safe encoding (CP437 for the legacy files, UTF-8 with de-DE formatting in
      the UI, ISO dates and dot decimals in JSON)
* [x] JSON and CSV export/import with DBF as the live store - see [BACKEND.md](BACKEND.md)
* [x] Docker packaging: multi-stage `Dockerfile` (JDK build, JRE runtime) and
      `docker-compose.yml`, data in a `/data` volume via `KB_DATA`
* [ ] ~~interactive TUI reproducing the original 80-column screens~~ - dropped: raw-mode
      input, CSI parsing and cursor control are not in the JDK, and what could be built was
      more fragile than the web UI that already covers the same functions. See
      [IDEAS.md](IDEAS.md) §3 for how a second attempt would start differently
* [ ] a genuine SQLite-backed live store (would replace DBF, not just exchange with it)
      - the dependency-free SQLite writer was removed: hand-rolling the file format
      proved unreliable for larger datasets. A real store would need a driver or an
      external `sqlite3` process, both of which break the no-dependency rule


## Web UI

See [WEB.md](WEB.md) and [API.md](API.md).
