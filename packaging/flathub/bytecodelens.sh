#!/bin/sh
# Flatpak launcher for BytecodeLens. The openjdk21 SDK extension is mounted
# under /usr/lib/sdk/openjdk21 inside the sandbox; we use its java binary.
exec /usr/lib/sdk/openjdk21/bin/java -jar /app/lib/bytecodelens/bytecodelens.jar "$@"
