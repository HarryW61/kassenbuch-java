# Web UI

`./serve.sh` starts a single-process app (JDK built-in HTTP server, no frameworks):

* `web/index.html`, `web/app.js`, `web/style.css` — one page, no build step
* `kb.WebServer` — static files + `/api`
* `kb.Api` — routes (see `docs/API.md`)
* `kb.Store` — CRUD over `KABU.DBF` / `JABU.DBF` / `STAMM.MEM`

Views and how they map to the original program:

| web view       | legacy equivalent            | source           |
|----------------|----------------------------|------------------|
| Buchungen      | booking mask + `BROWSE`    | `BUCHEN.PRG`     |
| Umsatzsteuer   | UST-Voranmeldung           | `VORST.PRG`      |
| Berichte       | printed reports (ASCII)    | `PRINT.PRG`      |
| Monatsübersicht| Kassen-Information         | `VORST.PRG`      |
| Stammdaten     | master data entry          | `STAMM.PRG`      |
| Jahresabschluss| year-end closing           | `TOOL.PRG`       |
| Daten          | – (new: JSON/CSV)          | `docs/BACKEND.md` |

On page load the UI calls `GET /api/firststart`. While the identity fields are empty
or still carry the shipped placeholders, a **Erste Einrichtung** dialog asks for them
one at a time and writes STAMM.MEM through `POST /api/firststart`; dismissing it leaves
the file untouched. Tax rates and period mode are not part of it — they already hold
usable defaults and are edited in **Stammdaten**. The CLI equivalent is `./run.sh init`.

The **Umsatzsteuer** view follows the period selector in the top bar, exactly like
the summary bar above it: changing the period reloads the UST-Voranmeldung figures.
It names the period it is showing, so a stale figure cannot go unnoticed. The same
reload happens after booking, editing, deleting and importing.

The **Berichte** view shows the original ASCII layout on screen and downloads it
as `.txt` (CRLF + codepage 437, byte-identical to `KB.EXE`) or as A4-landscape PDF.
*Kassen-Information* is the yearly overview: it always shows **all periods**, so its
period label reads "alle Perioden" and the period selector in the top bar does not
apply to it. The other reports follow the selected period.
The **Monatsübersicht** view shows exactly what the *Kassen-Information* report
shows: one row per month with that month's own Einnahmen, Ausgaben and Ergebnis
(the difference), plus a `Gesamt` row that is the sum over all months. Selection
is fixed to "alle Perioden"; amounts are per month, not cumulative.
The **Daten** view exports the bookings (optionally one period) as JSON, CSV or DBF
and imports such a file back into `KABU.DBF` - either appended or as a full
replacement, which first writes `KABU.BAK`.

Deliberate deviations from the 1990s original:
1. Editing a booking recalculates tax and period (the old edit mask only
   re-read the amount, leaving stale tax values).
2. The period key is always derived from the date + period mode, instead of
   being frozen at entry time.
3. Amounts are validated (`betrag > 0`, schlüssel 0-2, art A/E) like the old
   `ixtest`/`stest`/`txttest` helpers, but with clear error messages.
4. The booking text catalogue (`ST_KTO1..16`) is editable in the UI — add,
   rename and remove slots; existing bookings keep the text they were entered
   with, only the suggestions change.
