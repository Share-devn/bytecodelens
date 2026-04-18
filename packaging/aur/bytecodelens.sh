#!/bin/sh
# BytecodeLens launcher for AUR. Picks up Java from the user's installed runtime
# (java-runtime>=21 is declared as a dependency). The JavaFX toolkit lives inside
# the fat jar, so no further environment setup is needed.
exec java -jar /usr/share/java/bytecodelens/bytecodelens.jar "$@"
