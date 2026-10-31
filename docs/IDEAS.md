# Ideen für die nächsten Schritte

Nicht angefangen, nur skizziert. Reihenfolge nach Nutzen/Aufwand.

## 1. Firststart-Assistent (STAMM.MEM leer oder Platzhalter)

Der Auslöser ist nicht "Datei fehlt", sondern **leere bzw. Platzhalter-Werte**:
die ausgelieferte `data/STAMM.MEM` enthält `ST_NAME1 = DEINNAME`,
`ST_NAME2 = ZWEITNAME`, `ST_GEWERBE = Was machst du so`,
`ST_FIAMT = Wo geht die Kohle hin`, `ST_STNR = 123/123/12345`.

- Neue Methode `Stammdaten.isPristine()` (leere Werte ODER diese Platzhalter).
- **Web:** beim Start, wenn `isPristine()` true ist, einen geführten Dialog zeigen
  (Name 1/2, Steuernummer, Finanzamt, Adresse, Gewerbe, Steuersätze, Periodenmodus)
  statt der Maske mit `DEINNAME`. Abbrechen muss möglich bleiben, damit die
  bestehende Stammdaten-Maske erreichbar bleibt.
- **CLI:** `./run.sh init` - geführte Abfrage über stdin, schreibt STAMM.MEM im
  Originalformat (CP437, feste Schlüssellängen, `MemoWriter`). Vor dem Schreiben eine
  Vorschau zeigen und bestätigen lassen.
- Die Reports nutzen `ST_NAME1/2` in der Kopfzeile - nach dem Assistenten sehen die
  Ausdrucke sofort korrekt aus, deshalb ist das der eigentliche Nutzen.
- Nicht tun: `ST_QFO`, `ST_HOF`, `ST_FETON/OFF`, `ST_COMPON/OFF`, `ST_DRUCKER`,
  `ST_DATUM` abfragen - das sind Drucker-/Terminal-Steuersequenzen aus den 90ern,
  die im Port keine Rolle spielen (unverändert mitschreiben).

## 2. Kassenbuch im Docker-Container

- Basis: ein JDK 17+ Image (z. B. `eclipse-temurin:17-jre`), Build separat oder
  Multi-Stage; die App ist dependency-free, also reicht `javac` + `java -cp out kb.WebServer`.
- Daten liegen außerhalb des Images: Volume für `/data` (`KABU.DBF`, `JABU.DBF`,
  `STAMM.MEM`) - `KB_DATA` zeigt schon jetzt auf das Verzeichnis, `KB_PORT` und
  `KB_WEB` sind ebenfalls Umgebungsvariablen. Also kein Image-Change nötig, nur Env + Volume.
- `Dockerfile` (Multi-Stage: builder mit JDK, runtime nur JRE), `.dockerignore`
  (`out/`, `/tmp`-Artefakte, `*.pdf`), `docker-compose.yml` mit
  `ports: "8090:8090"`, `volumes: ./data:/data`, `restart: unless-stopped`.
- Healthcheck gegen `/api/meta` (die Endpoint existiert bereits).
- Test in Container: `./test.sh` läuft im Builder-Stage durch, damit der Container
  bewiesenermaßen grün ist, bevor er ausgeliefert wird.
- Achtung: die Reports schreiben nach `out/` - im Container nach `/data/reports` oder
  einen tmpfs-Pfad, sonst scheitert ein Download an fehlenden Rechten.
- Optional später: `/data` als Backup-Volumen dokumentieren (`docs/DEPLOY.md`).

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
