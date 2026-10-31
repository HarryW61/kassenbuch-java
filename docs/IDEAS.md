# Ideen für die nächsten Schritte

Erledigte Punkte bleiben als Eintrag stehen und beschreiben, was davon tatsächlich
gemacht wurde. Reihenfolge nach Nutzen/Aufwand.

## 1. Firststart-Assistent (STAMM.MEM leer oder Platzhalter) — erledigt

Der Auslöser ist nicht "Datei fehlt", sondern **leere bzw. Platzhalter-Werte**:
die ausgelieferte `data/STAMM.MEM` enthält `ST_NAME1 = DEINNAME`,
`ST_NAME2 = ZWEITNAME`, `ST_GEWERBE = Was machst du so`,
`ST_FIAMT = Wo geht die Kohle hin`, `ST_STNR = 123/123/12345`.

- Gemacht: `Stammdaten.isPristine()` (leere Werte ODER Platzhalter), dazu
  `missingIdentity()`, `isPlaceholder(key)` und `ratesUsable()`. Raten und Periodenmodus
  werden nur gefragt, wenn STAMM.MEM unbrauchbare Werte enthält - sie haben sinnvolle
  Voreinstellungen (19 / 7 / quartalsweise).
- **Web:** `GET/POST /api/firststart` plus Dialog „Erste Einrichtung“ beim Seitenaufruf.
  Er fragt nur die Identitätsfelder ab; „Später in Stammdaten“ verwirft nichts.
- **CLI:** `./run.sh init` (und `--yes`) - geführte Abfrage über stdin oder `System.console()`,
  Vorschau und Bestätigung, dann `MemoWriter` im Originalformat (CP437, feste Schlüssellängen).
- Die Reports nutzen `ST_NAME1/2` in der Kopfzeile - nach dem Assistenten sehen die
  Ausdrucke sofort korrekt aus, deshalb ist das der eigentliche Nutzen.
- Nicht tun: `ST_QFO`, `ST_HOF`, `ST_FETON/OFF`, `ST_COMPON/OFF`, `ST_DRUCKER`,
  `ST_DATUM` abfragen - das sind Drucker-/Terminal-Steuersequenzen aus den 90ern,
  die im Port keine Rolle spielen (unverändert mitschreiben).

## 2. Kassenbuch im Docker-Container (erledigt)

- `Dockerfile` (Multi-Stage: `eclipse-temurin:17-jdk` als Builder, `17-jre` als Runtime),
  `docker-compose.yml` mit Port 8090 und Volume `/data`; Datenüberleben den Container.
- Keine Abhängigkeiten im Image: nur `javac`/`java -cp out kb.WebServer`, keine Netzwerk-
  Downloads zur Laufzeit.
- Umgebungsvariablen unverändert: `KB_DATA=/data`, `KB_PORT=8090`, `KB_WEB=/app/web`.
- Bestätigt im Test: Image gebaut, Container gestartet, `/api/bookings`, `/api/summary`
  und CSV-Export liefern Daten aus dem Volume; statische Seite antwortet mit 200.
- Nicht getan: Healthcheck gegen `/api/meta` (der Endpoint existiert nicht, `/api/summary`
  wäre der Kandidat), `.dockerignore` (der Build-Kontext ist klein, `data/` und `out/`
  werden nicht kopiert, weil nur `src`, `web`, `LICENSE`, `NOTICE.txt`, `README.md`
  im Dockerfile referenziert werden). Reports werden direkt als Response geschrieben,
  kein Schreibzugriff auf `out/` nötig.

## 3. TUI (80 Spalten, Theme der Web-UI)

Steht in `docs/SPEC.md` §6. Dunkles Terminal-Theme statt Harbour-Blau, plain ANSI,
keine neuen Abhängigkeiten. Screens: `BUCHEN`, `ABUCH`, `STAMM`, `DRUCKEN`, Menü wie
in `MENU.PRG`.

## 4. Aufräumen (klein, opportunistisch)

* Die Tests schreiben aktuell in `data/STAMM.MEM` zurück (Steuerschlüssel-Katalog,
  Periodenmodus-Restaurierung) - md5 ändert sich nach `./test.sh`. Besser: auch
  `STAMM.MEM` nur in einer `/tmp`-Kopie anfassen, wie es bei `KABU.DBF` schon der Fall ist.
* `data/KABU.16` (Jahresabschluss-Archiv) ist unverändert - klarstellen, dass nur
  `KABU.DBF` live ist und `KABU.YY`/`KABU.BAK` Archive sind.
