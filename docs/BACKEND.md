# Datenaustausch: JSON und CSV

KABU.DBF ist und bleibt die **einzige Live-Datenbank** - das Originalprogramm
`KB.EXE` und diese Web-App arbeiten weiter auf derselben Datei. JSON und CSV sind
Austauschformate: sie werden aus `KABU.DBF` **exportiert** und können wieder
**in `KABU.DBF` importiert** werden. Zur Laufzeit liest die App keine Buchungen
aus JSON oder CSV.

```
KABU.DBF  ──export──▶  KABU-4426.json / .csv
KABU.DBF  ◀──import──  KABU-4426.json / .csv
```

## Gemeinsame Spalten

Beide Formate tragen dieselben Felder (in dieser Reihenfolge):

| Feld             | Bedeutung                       | Typ      | Bemerkung                                |
|------------------|---------------------------------|----------|------------------------------------------|
| `nr`             | `BU_NR`                         | Ganzzahl | eindeutig                                  |
| `datum`          | `BU_DATUM`                      | Datum    |                                         |
| `art`            | `BU_ART`                        | TEXT     | `A` Ausgabe, `E` Einnahme                |
| `text`           | `BU_TEXT`                       | TEXT     | max. 30 Zeichen                          |
| `betrag`         | `BU_BETRAG`                     | REAL     | brutto                                  |
| `steuer`         | `BU_STEUER`                     | REAL     |                                        |
| `netto`          | `BU_NETTO`                      | REAL     |                                        |
| `steuerSchluessel` / `steuer_schl` | `BU_STSCHL` | INTEGER | 0, 1 (ermäßigt), 2 (voll) |
| `periode`        | `BU_PERIODE`                    | TEXT     | z. B. `4426`, `0526`, `2026`             |

Der Export filtert nach Periode wie die Berichte: die Periode wird aus dem Datum
und `ST_PERIODE` berechnet, nicht aus dem (ggf. veralteten) Feld `BU_PERIODE`.

## JSON

ISO-Daten, Dezimalpunkt, menschenlesbar (2 Leerzeichen Einrückung), damit man
von Hand nachbearbeiten kann:

```json
{
  "format": "kassenbuch-buchungen/1",
  "quelle": "KABU.DBF",
  "exportiert": "2026-10-25",
  "periode": "4426",
  "periodeLabel": "Q4 2026",
  "anzahl": 8,
  "stamm": {
    "name1": "Vorname", "name2": "Dekoration",
    "steuernummer": "123/123/12345", "finanzamt": "Finanzamt",
    "rateFull": 19.0, "rateReduced": 7.0, "periodeMode": "4111"
  },
  "buchungen": [
    { "nr": 40, "datum": "2026-06-03", "art": "A", "text": "WEK 19%",
      "betrag": 560.0, "steuer": 89.41, "netto": 470.59,
      "steuerSchluessel": 2, "periode": "4226" }
  ]
}
```

Importiert wird sowohl dieses Objekt als auch eine reine Liste
`[{...}, {...}]`.

## CSV

Für Excel: UTF-8-BOM, Semikolon als Trenner, Komma als Dezimaltrennzeichen,
Datum `dd.mm.yyyy`, CRLF.

```
nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode
40;03.06.2026;A;WEK 19%;560,00;89,41;470,59;2;4226
```

Import akzeptiert:

- Semikolon **oder** Komma als Trenner, Komma **oder** Punkt als Dezimaltrennzeichen,
  Datum `dd.mm.yyyy` **oder** `yyyy-mm-dd`,
- eine Kopfzeile (Namen werden ohne Umlaute/Unterstriche verglichen:
  `Steuerschlüssel`, `steuer_schl` und `steuerSchluessel` meinen dasselbe Feld) oder
  die kanonische Reihenfolge `nr;datum;art;text;betrag;steuer;netto;steuerSchluessel;periode`
  ohne Kopfzeile,
- leere `nr` (Nummern werden vergeben), angeführte Texte mit `""`.

## Import in KABU.DBF

- **`append`** (Standard): Zeilen werden angehängt. Nummern, die schon existieren
  (oder in derselben Datei doppelt vorkommen), bekommen eine freie `BU_NR`.
- **`replace`**: alle bisherigen Datensätze werden als gelöscht markiert und die
  importierten Nummern bleiben erhalten. Vorher wird `KABU.DBF` als **`KABU.BAK`**
  gesichert – analog zum Jahresabschluss, der `KABU.YY` schreibt.

Regeln:

- **Erst prüfen, dann schreiben.** Alle Zeilen werden validiert; ist eine einzige
  ungültig, wird gar nichts geschrieben (`Nur 'A' für Ausgabe oder 'E' für Einnahme
  möglich - es wurde nichts geschrieben`).
- **`steuer`, `netto` und `periode` werden immer aus `STAMM.MEM` neu berechnet**
  (`Steuer.steuer(betrag, schlüssel)`, `Steuer.periode(datum, ST_PERIODE)`). Ein
  Import kann den Stammdaten also nie widersprechen – Fremdwerte in der Datei
  werden ignoriert.
- `BU_TEXT` bleibt bei 30 Zeichen (Feldbreite im DBF).
- Der Import schreibt `KABU.DBF` mit `DbfFile.write()` (DBF-Header + Rekordmarken).

## CLI

```sh
./run.sh export json                       # data/KABU-alle.json
./run.sh export csv out/KABU-4426.csv 4426 # nur Periode 4426
./run.sh export dbf out/KABU.dbf           # Kopie der Live-Datei (unfilteriert)
./run.sh import out/KABU-alle.json          # anhängen
./run.sh import out/KABU-alle.csv --replace # ersetzen, mit KABU.BAK
```

Die Formatwahl kommt aus der Dateiendung (`.json`, `.csv`).

## API

```
GET  /api/export?fmt=json|csv|dbf&period=4426             (period leer = alle)
POST /api/import?mode=append|replace     Rohbytes der Datei als Request-Body
```

`fmt=csv` liefert `text/csv`, `fmt=dbf` eine bytegleiche Kopie der Live-Datei
(`application/x-dbase`, ohne Periodenfilter). Bei `json` und `csv` kommt
`Content-Disposition: attachment; filename=KABU-<periode>.<fmt>`.
Beim Import erkennt die API das Format anhand der Bytes (`[`/`{` → JSON, sonst CSV);
`?fmt=` optional. Antwort:

```json
{"ok": true, "format": "csv", "mode": "replace", "importiert": 28, "gesamt": 28,
 "backup": "KABU.BAK", "datenbank": "/path/KABU.DBF",
 "nachricht": "28 Buchung(en) importiert (ersetzt), Bestand danach: 28"}
```

Fehler (400) enthalten den Grund und den Hinweis `es wurde nichts geschrieben`.

Im Web-UI sitzt das unter dem Tab **Daten** (Downloads + Datei-Import).

## Tests

`kb.BackendTests` (in `./test.sh`) benutzt die **aktuelle `data/KABU.DBF`** als
Quelle und importiert nur in ein Arbeitsexemplar unter `/tmp`. Abgedeckt: Round-Trips
über beide Formate, `append`-Nummernvergabe, `replace` mit `KABU.BAK`, Neuberechnung
von Steuer und Periode, Umlaute, Ablehnung ungültiger Zeilen ohne Schreibeffekt,
Toleranz der CSV-Kopfzeile und die Automatik-Erkennung.

Die API-Integrationstests (`kb.ApiTests`) laufen ebenfalls gegen eine Arbeitskopie der
aktuellen `data/KABU.DBF`; ihre Erwartungen werden aus den Dateien berechnet, die
Assertions hängen also nicht an festen Buchungszahlen oder Perioden. Die Goldtests der
Report-Layouts bleiben bei den eingefrorenen `fixtures/` und den Original-Drucken.
