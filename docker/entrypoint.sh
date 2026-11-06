#!/bin/sh
# Bring the data and output volumes to the ownership the app needs, then run as that user.
#
# Why this exists: Docker creates a named volume root-owned on its first use and copies the
# image directory's mode (0755) but not its ownership into it. A container that runs as
# uid 1000 therefore cannot create /out/bak, and the backup fails with AccessDeniedException.
#
# Compose starts this container as root on purpose (see docker-compose.yml) so the chown
# below is allowed; the capability CAP_CHOWN is dropped immediately afterwards and the real
# command is re-executed as KB_RUN_UID:KB_RUN_GID - the uid the app must run as, which is
# the invoking user's id passed in by Compose. Everything after this line runs unprivileged.
#
# A volume that was created before the image shipped seed files (or whose contents were
# deleted) arrives empty, and the server would exit on "KABU.DBF fehlt in /data". The seed
# directory is filled only into an EMPTY data directory - never over a book that already has
# bookings or real master data.

set -eu

uid="${KB_RUN_UID:-${UID:-1000}}"
gid="${KB_RUN_GID:-${GID:-1000}}"
data="${KB_DATA:-/data}"

if [ "$(id -u)" = "0" ]; then
    if [ -d /seed ] && [ -z "$(ls -A "$data" 2>/dev/null)" ]; then
        cp /seed/* "$data"/ 2>/dev/null || \
            echo "kb-entrypoint: /seed konnte nicht nach $data kopiert werden" >&2
        # the copies were made while still root; hand them over before becoming the app user,
        # otherwise a host-mounted directory would hold root-owned seed files
        chown -R "$uid:$gid" "$data" 2>/dev/null || \
            echo "kb-entrypoint: $data kann nicht an $uid:$gid uebergeben werden" >&2
    fi
    for dir in "$data" "${KB_OUT:-/out}"; do
        mkdir -p "$dir"
        # -c instead of -R: only correct the mount point itself. Anything below it either
        # already belongs to the target user or was placed there by a previous run, and a
        # recursive chown would follow that reasoning into files we did not create.
        chown -c "$uid:$gid" "$dir" 2>/dev/null || \
            echo "kb-entrypoint: $dir kann nicht an $uid:$gid uebergeben werden" >&2
    done
    # Drop the only privilege this container ever needed, then become the real user.
    # --bounding-set=-cap_chown removes CAP_CHOWN from the last capability set that
    # survives into the re-execed process. Capability names are written without the
    # "cap_" prefix: setpriv(1) accepts CHOWN, not cap_chown.
    exec setpriv --reuid "$uid" --regid "$gid" --clear-groups \
        --bounding-set=-CHOWN --inh-caps=-CHOWN --ambient-caps=-CHOWN -- "$@"
fi

exec "$@"
