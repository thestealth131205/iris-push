#!/bin/sh
# Release tooling for irisd updates (needs OpenSSL 3, xxd).
#
#   sign-irisd.sh genkey <key.pem>
#       Creates the Ed25519 release key. KEEP IT OFFLINE AND BACKED UP: it is
#       the only thing that can ship daemon updates without an OS update, and
#       if it is lost the baked-in public key can only be changed by an OS
#       update. Prints the public key hex for -DIRISD_UPDATE_PUBKEY_HEX.
#
#   sign-irisd.sh sign <key.pem> <irisd-binary> <version> <out.sig>
#       Signs "irisd <version>\n" || binary (what irisd verifies). Put the
#       binary as assets/irisd/<abi>/irisd, the signature as .../irisd.sig
#       (raw 64 bytes) and the version in assets/irisd/VERSION in the Iris APK.
set -e

pubhex() { openssl pkey -in "$1" -pubout -outform DER | tail -c 32 | xxd -p -c64; }

case "$1" in
genkey)
    [ -e "$2" ] && { echo "$2 exists, refusing to overwrite" >&2; exit 1; }
    openssl genpkey -algorithm ed25519 -out "$2"
    chmod 600 "$2"
    echo "IRISD_UPDATE_PUBKEY_HEX=$(pubhex "$2")"
    ;;
sign)
    key=$2; bin=$3; ver=$4; out=$5
    tmp=$(mktemp)
    { printf 'irisd %s\n' "$ver"; cat "$bin"; } > "$tmp"
    openssl pkeyutl -sign -rawin -inkey "$key" -in "$tmp" -out "$out"
    rm -f "$tmp"
    ;;
*)
    echo "usage: $0 genkey <key.pem> | sign <key.pem> <binary> <version> <out.sig>" >&2
    exit 2
    ;;
esac
