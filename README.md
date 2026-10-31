# Kassenbuch (hb30) → Java port

Reverse-engineering + reimplementation of a Harbour/xBase "Kassenbuch" app
(Harbour 3.0, compiled with `hbmk2` to `KB.EXE`, a DOS/ANSI 80x25 ASCII-terminal
cash book for a small German business). The original `.PRG` sources and the legacy
data files are **not** part of this repository — see [Running it on your own data](#running-it-on-your-own-data).

**Now with a web UI** — a dependency-free single-page app served by the JDK's
built-in `com.sun.net.httpserver`, talking to a small JSON API that reads and
writes the *original* legacy files (`KABU.DBF`, `JABU.DBF`, `STAMM.MEM`).

**Getting started:** [docs/USAGE.md](docs/USAGE.md) — first start, entering
bookings, reports, backups, and the command line reference.

## What the original is

| file            | role                                                        |
|-----------------|-------------------------------------------------------------|
| `MENU.PRG`      | main menu / sub menus, ANSI screen chrome                   |
| `BUCHEN.PRG`    | booking entry + edit (`BROWSE`), tax calc, period calc      |
| `VORST.PRG`     | Umsatzsteuer-Voranmeldung (UST) + monthly Kassen-Info       |
| `PRINT.PRG`     | Buchungsliste / Kassenbuch report printers                  |
| `STAMM.PRG`     | master data entry (company, tax rates, period mode)         |
| `TOOL.PRG`      | master-data defaults, MEMO save/load, year-end closing      |
| `EUER.PRG`      | experiment in a different (non-Harbour) dialect - not part of KB.EXE |
| `KABU.DBF`      | dBase III bookings (32 records)                            |
| `JABU.DBF`      | dBase III yearly summaries                                  |
| `STAMM.MEM`     | Harbour MEMO file with `ST_*` master data                    |

## Data model (KABU.DBF)

| field       | type | width | meaning                                    |
|-------------|------|-------|--------------------------------------------|
| BU_NR       | N    | 4     | booking number                             |
| BU_DATUM    | D    | 8     | date, stored `19YYMMDD` (2-digit-year bug) |
| BU_ART      | C    | 4     | `E` Einnahme / `A` Ausgabe                 |
| BU_TEXT     | C    | 30    | booking text                               |
| BU_BETRAG   | N    | 8,2   | gross amount                               |
| BU_STEUER   | N    | 8,2   | tax amount                                 |
| BU_ST_SCHL  | N    | 1     | 0 = no tax, 1 = reduced, 2 = full          |
| BU_PERIODE  | C    | 4     | period key, e.g. `4416` = Q4 2016          |

## Business rules (from BUCHEN.PRG / PRINT.PRG / VORST.PRG)

* tax: `steuer = betrag / (100 + rate) * rate`, rate = `ST_STEUER1` (schl 1) or `ST_STEUER2` (schl 2), schl 0 → 0
* period: `ST_PERIODE` = `1111` yearly → `YYYY`, `0111` monthly → `MMYY`, `4111` quarterly → `4Q`+`YY`
* net: `netto = betrag - steuer`
* UST: `Bemessungsgrundlage = INT(sumNettoVoll) + INT(sumNettoVermindert)`;
  `UST = INT(sum1)/100*rate2 + INT(sum2)/100*rate1`; `zahlbar = UST - Vorsteuer`
* year end: sum net per year into `JABU.DBF`, copy `KABU.DBF` → `KABU.YY`, delete all records

## Layout of this port

```
kassenbuch-java/
  build.sh                     # javac build (no external deps)
  serve.sh                     # build + start the web app
  run.sh                       # CLI entry point
  test.sh                      # unit + golden layout + API + backend tests
  docs/USAGE.md                # how to use it: first start, bookings, reports
  docs/SPEC.md                 # behaviour spec extracted from the .PRG files
  docs/API.md                  # REST endpoints of the web backend
  docs/REPORTS.md              # exact legacy report layout + PDF rendering
  docs/BACKEND.md              # JSON / CSV interchange formats
  docs/WEB.md                  # web views and their legacy counterparts
  docs/IDEAS.md                # next steps: firststart assistant, Docker, TUI
  data/                        # working copy of KABU.DBF, JABU.DBF, STAMM.MEM
  fixtures/                    # frozen copy of the original legacy data (used by the tests)
  fixtures/golden/             # original Kassenbuch.txt / Buchungsliste.txt printouts
  web/                         # index.html, app.js, style.css (no build step)
  src/main/java/kb/
    DbfFile.java               # dBase III reader/writer (Harbour descriptor quirks)
    MemoFile.java              # Harbour MEMO reader
    MemoWriter.java            # Harbour MEMO writer (round-trips STAMM.MEM)
    LegacyCharset.java         # CP437 codepage used by the legacy files
    Buchung.java
    Stammdaten.java
    Steuer.java                # tax + period calculation
    Reports.java               # report facade (text / CP437 bytes / PDF)
    LegacyReport.java          # exact KB.EXE printer layout (DRKABU / DRBULI)
    PdfReport.java             # dependency-free PDF writer (A4 landscape, Courier)
    Store.java                 # repository used by the web API
    BookingsIo.java            # bookings as JSON / CSV, both directions
    Json.java                  # minimal JSON encoder/parser
    Api.java                   # REST routes
    WebServer.java             # static files + /api
    Cli.java                   # command line entry point
    Tests.java                 # assertions against the legacy data
    ReportTests.java           # golden-file tests for the printed reports
    ApiTests.java              # end-to-end CRUD/UST/year-end tests against the API
    BackendTests.java          # export/import tests over the current KABU.DBF
```

## Web app

```sh
./serve.sh                 # http://localhost:8090
KB_PORT=1234 ./serve.sh
KB_DATA=/pfad/zu/daten ./serve.sh    # operate directly on your own files
```

Views: **Buchungen** (add/edit/delete with live tax preview), **Umsatzsteuer**,
**Monatsübersicht**, **Jahresabschluss**, **Stammdaten**, **Berichte** (the
original ASCII report layouts, downloadable as `.txt`) and **Daten** (JSON/CSV
export and import).

Everything writes back to the legacy dBase/MEMO files, so the original `KB.EXE`
and this web app can work on the same data.

## CLI

```sh
./build.sh
./run.sh list 4416
./run.sh kassenbuch 4416
./run.sh ust 4416
./run.sh stamm
./run.sh backup        # tar.gz of all data files into out/bak (nothing is deleted)
./run.sh test          # smoke tests against the legacy data
```

Reports can be written in the two original formats:

```sh
./run.sh kabu 4416 --pdf out/Kassenbuch-4416.pdf        # A4 landscape PDF
./run.sh list 4416 --dos out/Buchungsliste-4416.txt     # CRLF + codepage 437, like KB.EXE
```

### Data interchange

`KABU.DBF` stays the only live database; JSON and CSV are export and import
formats that always read from / write back into it:

```sh
./run.sh export json                                # data/KABU-alle.json
./run.sh export csv out/KABU-4426.csv 4426          # one period, Excel friendly
./run.sh export dbf out/KABU.dbf                    # byte-identical copy of the live file
./run.sh import out/KABU-alle.json                  # append
./run.sh import out/KABU-alle.csv --replace         # replace, old file kept as KABU.BAK
```

Imported rows are validated as a whole (nothing is written if one row is bad) and
`steuer`, `netto`, `periode` are always recomputed from `STAMM.MEM`.
Details: [docs/BACKEND.md](docs/BACKEND.md).

## Reports

`LegacyReport` reproduces the KB.EXE printout byte for byte (113-column rule,
header on every page, 23 rows/page for the Kassenbuch, 27 for the Buchungsliste,
`Zwischensumme` blocks, CP437 + CRLF). `PdfReport` renders those same pages as a
PDF with no external dependencies. Details: [docs/REPORTS.md](docs/REPORTS.md).

## Tests

```sh
./test.sh
```

* unit assertions and golden-file report tests run against `fixtures/`
  (a frozen copy of the original legacy data),
* the API integration tests run against a scratch copy of the current `data/` in
  `/tmp`, and derive their expectations from the data itself,
* the backend tests read the current `data/KABU.DBF` and import into a scratch copy
  in `/tmp`,

so the real `data/` is never modified.

## Running it on your own data

`data/` is git-ignored: bookings, yearly summaries and master data (name, address,
tax number) stay on your machine. Create it from whatever you have:

```sh
./reset.sh fixtures     # demo data from fixtures/ — works straight after a clone
./reset.sh /pfad/zu/daten   # your own KABU.DBF / JABU.DBF / STAMM.MEM
```

`KB_DATA=/pfad/zu/daten ./serve.sh` also runs directly on another directory without
copying anything. After the first start, fill in your master data in the
**Stammdaten** tab (the shipped file still holds placeholders).
