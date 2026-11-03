#!/usr/bin/env bash
# Restore the working data/ copy. The files are not in fixtures/ itself but in a
# subdirectory of it, so each name is resolved against the known layouts.
#   ./reset.sh              -> fixtures/anonymisiert/ (demo data shipped with this repo)
#   ./reset.sh anonymisiert -> same, explicit
#   ./reset.sh legacy       -> your own files in fixtures/legacy/ (git-ignored)
#   ./reset.sh /mein/pfad   -> your own KABU.DBF / JABU.DBF / STAMM.MEM from anywhere
set -u

FILES="KABU.DBF JABU.DBF STAMM.MEM"

# Candidates tried in order for a short name. "fixtures" alone used to be accepted and
# then copied nothing, because the data lives one level deeper.
candidates() {
  case "$1" in
    fixtures|anonymisiert) echo "fixtures/anonymisiert" ;;
    legacy)                echo "fixtures/legacy fixtures/anonymisiert" ;;
    *)                     echo "$1" ;;
  esac
}

src=""
for c in $(candidates "${1:-fixtures}"); do
  if [ -f "$c/KABU.DBF" ]; then src="$c"; break; fi
done

if [ -z "$src" ]; then
  echo "reset.sh: keine KABU.DBF gefunden fuer '${1:-fixtures}'" >&2
  echo "  erwartet in: fixtures/anonymisiert/ (demo), fixtures/legacy/ (eigene Daten)" >&2
  echo "  oder Pfad angeben:  ./reset.sh /pfad/zu/daten" >&2
  exit 1
fi

mkdir -p data
copied=0
for f in $FILES; do
  if [ -f "$src/$f" ]; then
    cp "$src/$f" data/"$f"
    echo "restored data/$f from $src"
    copied=$((copied + 1))
  else
    echo "reset.sh: $src/$f fehlt - data/$f bleibt unveraendert" >&2
  fi
done

if [ "$copied" -eq 0 ]; then
  echo "reset.sh: nichts kopiert, $src enthaelt keine der Dateien" >&2
  exit 1
fi
