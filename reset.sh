#!/usr/bin/env bash
# Restore the working data/ copy.
#   ./reset.sh            -> fixtures/ (the demo data shipped with this repo)
#   ./reset.sh fixtures   -> same, explicit
#   ./reset.sh /mein/pfad -> your own KABU.DBF / JABU.DBF / STAMM.MEM
SRC="${1:-fixtures}"
[ -d "$SRC" ] || SRC=fixtures
mkdir -p data
for f in KABU.DBF JABU.DBF STAMM.MEM; do
  if [ -f "$SRC/$f" ]; then cp "$SRC/$f" data/"$f"; echo "restored data/$f from $SRC"; fi
done
