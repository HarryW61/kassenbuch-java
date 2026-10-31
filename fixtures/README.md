# Test fixtures

`anonymisiert/` is the fixture set the tests use, and the **only one that belongs in
this repository**. It carries no personal data: names, street, postal code, town,
business description, tax office and tax number are placeholders (`Testname`,
`Testweg 1`, `00000 Testort`, `Testfinanzamt`, `000/000/00000`). Booking amounts,
dates, tax keys and the whole report layout are unchanged, so the golden comparison
still proves byte-exact output.

| file | used by |
|------|---------|
| `anonymisiert/KABU.DBF`   | 32 bookings: unit tests, page-break tests, export/import round trips |
| `anonymisiert/JABU.DBF`   | yearly summary file |
| `anonymisiert/STAMM.MEM`  | master data with placeholder identity |
| `anonymisiert/golden/*.txt` | expected CP437 + CRLF report output |

## Real legacy data stays local

The original files from the 1990s program contain a real name, address and tax
number. They are **git-ignored** (`fixtures/legacy/`) and are not needed for any test:

```sh
cp /pfad/zu/daten/{KABU.DBF,JABU.DBF,STAMM.MEM} fixtures/legacy/
cp /pfad/zu/daten/{Kassenbuch.txt,Buchungsliste.txt} fixtures/legacy/golden/
```

With `fixtures/legacy/` present, `./test.sh` additionally runs the golden comparison
against the untouched original printouts — a check that nothing in the layout drifted.
Without it, `test.sh` runs exactly the same tests as on a fresh clone.

`./reset.sh legacy` copies the real data into `data/` for local work; `./reset.sh`
(default) uses the anonymised set, so a clone is immediately usable.
