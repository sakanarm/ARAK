#!/usr/bin/env bash
# Point JAVA_HOME at a project-local JDK 21 for the current shell only.
#
#   source scripts/use-jdk21.sh
#
# This exists because the build needs Java 21 and a machine may have an older
# JDK on PATH. It changes nothing outside the shell you run it in: no profile
# edits, no system PATH, no installer. Deleting .tools/ undoes it entirely.
#
# If .tools/ has no JDK, the script fetches Temurin 21 (~196 MB) from Adoptium.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tools="$root/.tools"

find_jdk() {
  find "$tools" -maxdepth 1 -type d -name 'jdk-21*' 2>/dev/null | sort -r | head -1
}

jdk="$(find_jdk)"
if [ -z "$jdk" ]; then
  echo 'No JDK 21 under .tools — downloading Temurin 21 from Adoptium...'
  mkdir -p "$tools"
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) os=windows; ext=zip ;;
    Darwin)               os=mac;     ext=tar.gz ;;
    *)                    os=linux;   ext=tar.gz ;;
  esac
  arch=x64
  [ "$(uname -m)" = 'arm64' ] || [ "$(uname -m)" = 'aarch64' ] && arch=aarch64
  url="https://api.adoptium.net/v3/binary/latest/21/ga/${os}/${arch}/jdk/hotspot/normal/eclipse"
  curl -fsSL -o "$tools/temurin21.$ext" "$url"
  if [ "$ext" = zip ]; then unzip -q "$tools/temurin21.zip" -d "$tools"
  else tar -xzf "$tools/temurin21.tar.gz" -C "$tools"; fi
  rm -f "$tools/temurin21.$ext"
  jdk="$(find_jdk)"
fi

# macOS bundles the JDK under Contents/Home.
[ -d "$jdk/Contents/Home" ] && jdk="$jdk/Contents/Home"

export JAVA_HOME="$jdk"
export PATH="$JAVA_HOME/bin:$PATH"

"$JAVA_HOME/bin/java" -version
echo "JAVA_HOME = $JAVA_HOME  (this shell only)"
