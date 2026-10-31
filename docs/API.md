# Kassenbuch JSON API

Base URL: `http://localhost:8090` (set `KB_PORT` to change). All endpoints read
and write the legacy files in `KB_DATA` (default `data/`).

| method | route                       | purpose                                            |
|--------|-----------------------------|----------------------------------------------------|
| GET    | `/api/meta`                 | master data, tax rates, period mode, list of periods |
| GET    | `/api/bookings?period=4416` | bookings of a period (empty `period` = all)         |
| POST   | `/api/bookings`             | create a booking                                   |
| PUT    | `/api/bookings/{nr}`        | update a booking (tax + period recalculated)        |
| DELETE | `/api/bookings/{nr}`        | delete a booking                                    |
| GET    | `/api/summary?period=4416`  | totals: gross/net, UST, VAT, saldo                  |
| GET    | `/api/ust?period=4416`      | UST-Voranmeldung calculation                        |
| GET    | `/api/monthly`              | net per month                                       |
| GET    | `/api/jabu`                 | yearly summaries (JABU.DBF)                         |
| POST   | `/api/yearend`              | backup `KABU.YY` + CSV archive in `out/bak`, yearly totals into `JABU.DBF`, clear KABU |
| POST   | `/api/backup`               | tar.gz of `KABU.DBF`, `JABU.DBF`, `STAMM.MEM` and all `KABU.YY` into `out/bak` (nothing is deleted) |
| PUT    | `/api/stamm`                | update master data in STAMM.MEM                     |
| GET    | `/api/report/{kind}?period=` | legacy-layout report: `kassenbuch`, `buchungsliste`, `ust`, `info` — `info` (Kassen-Information) ignores `period` and always covers all periods |
| GET    | `/api/report/{kind}?period=&fmt=txt\|dos\|pdf` | `txt` = UTF-8, `dos` = original CRLF+CP437 bytes, `pdf` = A4 landscape PDF (see docs/REPORTS.md); for `info` the downloads cover all periods as well |
| GET    | `/api/export?fmt=json\|csv\|dbf&period=` | bookings as an interchange file (`dbf` = byte-identical copy of the live file; empty `period` = all) |
| POST   | `/api/import?mode=append\|replace` | read JSON/CSV back into `KABU.DBF` |

## Year-end closing response

`POST /api/yearend` answers with the four steps it performed, so the UI can show
what happened:

```json
{"ok": true, "jahr": 2026, "einnahmen": 18699.34, "ausgaben": 5695.45,
 "geloescht": 28, "backup": "KABU.26",
 "csvBackup": "/path/out/bak/KABU-2026.csv",
 "schritte": ["Jahressumme 2026: ... nach JABU.DBF geschrieben",
              "KABU.DBF als KABU.26 gesichert",
              "CSV-Export .../KABU-2026.csv (28 Buchungen)",
              "28 Buchung(en) aus KABU.DBF entfernt"]}
```

The CSV archive goes to `out/bak` relative to the working directory; override with
`KB_BACKUP_DIR`. It is written before `KABU.DBF` is cleared.

## Backup response

`POST /api/backup` packs the data files without touching them:

```json
{"ok": true, "name": "KB_DATA_BACK_20261029_131958.tar.gz",
 "archiv": "/path/out/bak/KB_DATA_BACK_20261029_131958.tar.gz",
 "dateien": ["KABU.DBF", "JABU.DBF", "STAMM.MEM", "KABU.16"],
 "jahreskopien": ["KABU.16"],
 "groesse": 2085, "unkomprimiert": 6812,
 "schritte": ["KABU.DBF, JABU.DBF und STAMM.MEM gelesen",
              "Jahreskopien einbezogen: KABU.16",
              "4 Datei(en) als tar gepackt und mit gzip komprimiert",
              "Archiv geschrieben: .../KB_DATA_BACK_20261029_131958.tar.gz"],
 "hinweis": "Diese Datei außerhalb des Programmordners aufbewahren - erst dann ist sie ein Backup."}
```

The archive is a plain gzip'd **tar** (ustar headers written by `Store`), so the
system `tar -xzf` restores it byte for byte.

## Data interchange

`KABU.DBF` is the only live store; JSON and CSV are export/import formats
only (details in [BACKEND.md](BACKEND.md)).

```
GET  /api/export?fmt=csv&period=4426
     -> text/csv, Content-Disposition: attachment; filename=KABU-4426.csv
POST /api/import?mode=replace          body: the raw file bytes
     -> {"ok":true,"format":"csv","mode":"replace","importiert":28,"gesamt":28,
         "backup":"KABU.BAK","datenbank":"/path/KABU.DBF","nachricht":"..."}
```

* the import format is detected from the bytes (`[`/`{` = JSON, else CSV);
  `?fmt=json|csv` overrides that
* `mode=append` (default) renumbers bookings whose number is already taken;
  `mode=replace` keeps the imported numbers and first copies the live file to `KABU.BAK`
* every row is validated before anything is written, and `steuer`, `netto`, `periode`
  are recalculated from `STAMM.MEM` - one bad row answers `400` with
  `"... - es wurde nichts geschrieben"`

## POST/PUT body

```json
{
  "datum": "2016-12-01",          // or "01.12.2016" / "01.12.16"
  "art": "A",                     // "E" Einnahme, "A" Ausgabe
  "text": "Büro/Arbeitsmittel",
  "betrag": 119.00,
  "steuerSchluessel": 2           // 0 none, 1 reduced, 2 full
}
```

`PUT /api/stamm` accepts the master data plus the editable text catalogue:

```json
{
  "name1": "Brigitte Wilczek",
  "email": "kontakt@bw-deko.de",          // ST_EMAIL, max 40 chars
  "rateFull": "19",
  "rateReduced": "7",
  "periodeMode": "4111",
  "einnahmeTexte": ["Erlöse 19%", "Erlöse 7%", "Sondereinnahmen", "Reparaturen"],
  "ausgabeTexte": ["WEK 19%", "WEK 7%", "Ausgaben ohne Steuer", "KFZ", "Spenden"]
}
```

The first 8 entries of each group are written to the legacy keys
`ST_KTO1..ST_KTO8` (Einnahme) and `ST_KTO9..ST_KTO16` (Ausgabe). Anything beyond
slot 8 gets stored as `ST_KTOE9, ST_KTOE10, ...` / `ST_KTOA9, ST_KTOA10, ...`
— the original `KB.EXE` ignores those keys, so the file stays readable by the
legacy program.

Keys are sent in the same spelling the API returns them in. `stnr` is accepted
as an alias for `steuernummer` (the form field is named `stnr`), and each key
maps to the legacy MEMO slot: `name1 -> ST_NAME1`, `steuernummer -> ST_STNR`,
`finanzamt -> ST_FIAMT`, `rateFull -> ST_STEUER2`, `rateReduced -> ST_STEUER1`,
`periodeMode -> ST_PERIODE`, and so on.

Validation mirrors the original: art must be `E`/`A`, schl 0–2, betrag > 0.
Errors return `400` with `{"error": "..."}`.

## Notes on faithfulness

* `BU_STEUER` is always recalculated from `betrag` and the schlüssel (the
  original edit mask did **not** do this — deliberate improvement).
* `BU_PERIODE` is derived from the booking date and `ST_PERIODE`; changing the
  period mode in Stammdaten rewrites all stored period keys.
* Numbers/dates are written back into the dBase file in the legacy widths, and
  text is stored in codepage 437, so the original `KB.EXE` can still open the files.
