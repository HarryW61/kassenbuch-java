# How to use the Kassenbuch

A cash book for a small German business: bookings in, tax figures out. This port
runs the original 1990s data files (`KABU.DBF`, `JABU.DBF`, `STAMM.MEM`) and adds
a web UI on top of them. Nothing needs to be installed except a JDK (17 or newer).

Two ways to work with it: **the web app** (recommended for daily use) and
**the command line** (for reports, exports and scripting). Both read and write the
same files, so they can be used side by side — and so can the original `KB.EXE`.

---

## 1. First start

```sh
./serve.sh                 # http://localhost:8090
```

That builds the project and starts the server. Open the URL in a browser.

Before booking anything, set your master data: open the **Stammdaten** tab and fill
in company name, address, tax office (`Finanzamt`), tax number, and the two tax
rates. Also pick the **period mode**, which decides how bookings are grouped:

| Period mode | Grouping  | Period key example       |
|-------------|-----------|--------------------------|
| `4111`      | quarterly | `4426` = Q4 2026         |
| `0111`      | monthly   | `1026` = October 2026    |
| `1111`      | yearly    | `2026`                   |

The default data directory is `data/`. To work on different files:

```sh
KB_DATA=/pfad/zu/meinen/daten ./serve.sh   # your own legacy directory
KB_PORT=1234    ./serve.sh              # a different port
```

---

## 2. Daily work: entering bookings

Open **Buchungen**. The form asks for four things:

| Field   | Meaning                                                    |
|---------|------------------------------------------------------------|
| Datum   | the date of the booking                                    |
| Art     | `E` = Einnahme (income), `A` = Ausgabe (expense)            |
| Text    | up to 30 characters — that is the legacy field width       |
| Betrag  | the **gross** amount, with cents                             |

The tax key (`Steuer` / `Mwst.`) selects the rate:

| Key | Meaning        | Tax                                                  |
|-----|----------------|------------------------------------------------------|
| `0` | no tax         | 0                                                    |
| `1` | reduced rate   | `betrag / (100 + rateReduced) * rateReduced`          |
| `2` | full rate      | `betrag / (100 + rateFull) * rateFull`                |

As you type, the preview shows tax and net (`netto = betrag - steuer`) immediately.
**Buchen** stores the booking; tax, net and period are always computed from the
date and your master data, so they can never contradict the master data.

Below the form is the list of bookings of the selected period. Click a row to edit
it, use delete to remove it. Editing recalculates tax and period — unlike the old
Harbour mask, which kept stale tax values.

The period selector in the top bar applies to **Buchungen**, **Umsatzsteuer** and
the reports; **Monatsübersicht** always shows the whole year. Changing it reloads
every open figure at once — the summary bar, the UST-Voranmeldung table and an open
report — so the two can never show different periods.

---

## 3. Checking the numbers

| Tab              | What it answers                                              |
|------------------|--------------------------------------------------------------|
| Umsatzsteuer     | UST-Voranmeldung: tax on income, deductible input tax (Vorsteuer), what you owe or get back |
| Berichte         | the four printouts in their original layout                  |
| Monatsübersicht  | net per month over the whole year — an overview, not a filing |
| Stammdaten       | your name, address, tax office, tax number, rates            |
| Jahresabschluss  | closes the year, plus the data backup                        |
| Daten            | JSON / CSV export and import                                 |

The UST figures follow the legacy rounding rule: the assessment base is the
**integer** part of the summed net amounts (`INT(sumNettoVoll) + INT(sumNettoVermindert)`),
so cents are dropped before the rate is applied. That is intentional — it matches
the original program and its printouts exactly.

### Year-end closing

**Jahresabschluss** (or `./run.sh yearend`) does four things, in this order:

1. copies the current bookings to `KABU.YY` (e.g. `KABU.26`) in the data directory,
2. writes **every booking as a CSV archive** to `out/bak/KABU-<year>.csv`
   (`out/bak/KABU-2026.csv`) — readable in Excel, and created *before* anything is
   deleted, so the year stays reconstructable even beyond the DBF copy,
3. writes the year's net totals (income / expenses) as a new row into `JABU.DBF`,
4. clears `KABU.DBF` so the new year starts empty.

The screen shows each step: the **Jahresabschluss** view lists them before you click
and reports what was actually done afterwards, including the path of the CSV archive.
The command line prints the same four steps numbered.

The archive directory is `out/bak` relative to where you run the program; set
`KB_BACKUP_DIR` to put it somewhere else, e.g. a backup share:

```sh
KB_BACKUP_DIR=/volume/backup/kassenbuch ./serve.sh
```

Run it once per year, after you have filed the last period. `./run.sh jabu` shows
the yearly summaries afterwards.

---

## 4. Reports

Open **Berichte** to see the report in its original ASCII layout on screen, then
download it:

| Report          | Content                                            | Download       |
|-----------------|----------------------------------------------------|----------------|
| Kassenbuch      | bookings with totals per page (the main printout)  | `.txt` or PDF  |
| Buchungsliste   | booking list, no totals                            | `.txt` or PDF  |
| Umsatzsteuer    | UST-Voranmeldung                                   | `.txt` or PDF  |
| Kassen-Information | monthly net overview, **always all periods**     | `.txt` or PDF  |

The `.txt` files are byte-identical to what `KB.EXE` printed: CRLF line endings,
codepage 437, the same column positions. The PDF is A4 landscape in Courier and
reproduces the same page breaks. Amounts carry **no currency label**, so the
reports work for any currency.

Choose **Alle Perioden** in the top bar to print a whole year at once; the header
then reads `Zeitraum: 2026` — the year your bookings belong to — instead of a period
code like `4326`.

---

## 5. Backups and exchanging data

`KABU.DBF` is the only live database. JSON and CSV are exchange formats: exported
from it, imported back into it. Nothing at runtime reads bookings from them.

In the web UI, the **Daten** tab exports (optionally for one period) and imports.

On the command line:

```sh
./run.sh export json                          # data/KABU-alle.json
./run.sh export csv out/KABU-4426.csv 4426    # one period, Excel friendly
./run.sh export dbf out/KABU.dbf              # byte-identical copy of the live file
./run.sh import out/KABU-alle.json            # append
./run.sh import out/KABU-alle.csv --replace   # replace, old file kept as KABU.BAK
```

Rules worth knowing:

* **All rows are validated before anything is written.** If one row is bad, the
  whole import aborts and no file is touched.
* **Imported tax, net and period values are ignored** — they are recomputed from
  `STAMM.MEM`. A foreign file can therefore never corrupt your tax figures.
* `--replace` first writes the previous file as **`KABU.BAK`**, like the year-end
  closing writes `KABU.YY`.
* CSV is Excel-friendly: UTF-8 BOM, `;` separator, comma decimals, `dd.mm.yyyy`.
  Import also accepts `,` separators, dot decimals and ISO dates.

### Backup button

The **Backup** button (in the *Jahresabschluss* view) packs everything the cash book
consists of into one file, without deleting anything:

| packed into the archive |                                     |
|-------------------------|-------------------------------------|
| `KABU.DBF`              | the bookings                          |
| `JABU.DBF`              | the yearly summaries                  |
| `STAMM.MEM`             | your master data                      |
| `KABU.YY`               | every year-end copy that exists       |

They are packed as a tar and compressed with gzip, written to:

```
out/bak/KB_DATA_BACK_<date>_<time>.tar.gz     # e.g. KB_DATA_BACK_20261029_131958.tar.gz
```

The same thing on the command line:

```sh
./run.sh backup
```

Restore with any tool that reads `.tar.gz`:

```sh
tar -xzf KB_DATA_BACK_20261029_131958.tar.gz -C /pfad/zum/datenordner
```

> **Note:** copy the finished archive **out of the program folder** — to an external
> disk or a network share. While it sits in `out/bak` next to the live files it is
> not a backup: one failing drive, or one accidental delete, would take both. The
> screen repeats this reminder after each backup.

The archive directory defaults to `out/bak` (shared with the year-end CSV); change
it with `KB_BACKUP_DIR`. For a plain file copy you can also just copy the three
files above or use `export dbf`.

---

## 6. Command line reference

```sh
./build.sh                                   # compile only
./run.sh                                     # show usage

./run.sh stamm                # master data
./run.sh list   [PERIODE]     # Buchungsliste
./run.sh kabu   [PERIODE]     # Kassenbuch
./run.sh ust    [PERIODE]     # Umsatzsteuer-Voranmeldung
./run.sh info                 # monthly overview, always all periods
./run.sh add 03.06.26 E "WEK 19%" 560.00 2   # one booking
./run.sh yearend              # year-end closing (writes! see above)
./run.sh jabu                 # yearly summaries
./run.sh test                 # smoke tests against the legacy data

./run.sh kabu 4416 --pdf out/Kassenbuch-4416.pdf    # report as PDF
./run.sh list 4416 --dos  out/Buchungsliste.txt     # report as legacy .txt
```

Period keys: `4416` (Q4 2016), `1016` (October 2016), `2016` (a whole year).
Without a period, everything is used.

---

## 7. Running it as a service

```sh
KB_DATA=/pfad/zu/meinen/daten KB_PORT=8090 ./serve.sh
```

The server has no authentication — keep it on your own machine or behind a local
network. It writes to the data directory on every booking, so that directory needs
to be writable and backed up.

---

## 8. Troubleshooting

| Symptom                                   | Cause / fix                                                             |
|-------------------------------------------|-------------------------------------------------------------------------|
| `KABU.DBF fehlt`                          | wrong data directory — check `KB_DATA`, or run `./reset.sh` for demo data |
| Amount shows no tax                       | tax key is `0`; use `1` (reduced) or `2` (full)                          |
| Import aborts with one error              | fix that row; nothing was written, the file is untouched                  |
| Text is cut off                           | 30 characters is the legacy field width — shorten the text                |
| Wrong period for a booking                | the period comes from date + `ST_PERIODE`; check the period mode in Stammdaten |
| UST differs by cents from my own calc     | intended: the assessment base is rounded down to whole euros first        |
| German umlauts look wrong in the `.txt`   | open it with codepage 437 (that is what the legacy printer used)          |

Tests: `./test.sh` runs unit tests, golden-layout report tests and the API/backend
tests. Destructive parts run against scratch copies in `/tmp`, so your real `data/`
is never modified by a test run.

---

## Related documents

* [WEB.md](WEB.md) — the web UI in detail
* [API.md](API.md) — the JSON API behind it
* [BACKEND.md](BACKEND.md) — JSON/CSV formats, import rules
* [REPORTS.md](REPORTS.md) — report layout and PDF
* [SPEC.md](SPEC.md) — legacy data model and business rules
