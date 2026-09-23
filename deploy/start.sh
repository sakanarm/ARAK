#!/usr/bin/env bash
#
# What PM2 runs. Registered once with:
#
#     pm2 start /home/<user>/Arak/deploy/start.sh --name arak
#     pm2 save
#
# A script rather than `pm2 start java -- -jar ...` so that the environment, the
# JVM flags and the config path are version-controlled next to the application
# instead of living in a pm2 dump that nobody can diff.
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_HOME"

# Secrets come from the environment, never from the committed yaml. This file is
# gitignored; see .env.example for what belongs in it.
if [[ -f "$APP_HOME/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$APP_HOME/.env"
  set +a
else
  echo "start.sh: no .env in $APP_HOME -- refusing to start with defaults" >&2
  exit 1
fi

# The JDK ships with the deployment rather than being installed system-wide:
# the target host has no Java and no unattended sudo, and an application that
# needs an administrator before it can restart is an application that stays
# down. Override JAVA_HOME to use a system JDK where one exists.
JAVA_HOME="${JAVA_HOME:-$APP_HOME/.tools/jdk}"
JAVA="$JAVA_HOME/bin/java"
if [[ ! -x "$JAVA" ]]; then
  echo "start.sh: no JVM at $JAVA -- unpack a JDK 21 there or set JAVA_HOME" >&2
  exit 1
fi

# -XX:MaxRAMPercentage rather than -Xmx: the box is shared with several other
# applications, and a fixed heap is a number that is wrong as soon as anything
# else is deployed beside it.
JVM_OPTS="${JVM_OPTS:--XX:MaxRAMPercentage=40 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Bangkok}"

# exec, so that PM2 supervises the JVM itself. Without it PM2 watches this
# shell, and a JVM that dies leaves PM2 reporting a healthy process.
exec "$JAVA" $JVM_OPTS -jar "$APP_HOME/dac-service.jar" server "$APP_HOME/conf/dac.yml"
