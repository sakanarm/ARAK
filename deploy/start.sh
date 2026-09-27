#!/usr/bin/env bash
#
# What PM2 runs. Registered once with:
#
#     pm2 start /home/<user>/Arak/deploy/start.sh --name arak --exp-backoff-restart-delay=3000
#     pm2 save
#
# After that a deploy is the same two commands as every other application on
# the host:
#
#     cd ~/Arak && git pull origin main
#     pm2 restart arak
#
# The other applications there are plain node, so pull-and-restart is all they
# need. ARAK has two halves that must be built first -- the shaded jar and the
# front-end bundle, neither of which is in git -- so this script builds them
# itself when it is restarted, and only when the sources they come from have
# changed. A restart with nothing new is as fast as theirs.
#
# A script rather than `pm2 start java -- -jar ...` so that the environment, the
# JVM flags and the config path are version-controlled next to the application
# instead of living in a pm2 dump that nobody can diff.
set -euo pipefail

APP_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_HOME"

BUILD_ONLY=false
if [[ "${1:-}" == "--build-only" ]]; then BUILD_ONLY=true; fi

# Secrets come from the environment, never from the committed yaml. This file is
# gitignored; see .env.example for what belongs in it.
if [[ -f "$APP_HOME/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$APP_HOME/.env"
  set +a
elif [[ "$BUILD_ONLY" == false ]]; then
  echo "start.sh: no .env in $APP_HOME -- refusing to start with defaults" >&2
  exit 1
fi

# The JDK ships with the deployment rather than being installed system-wide:
# the target host has no Java and no unattended sudo, and an application that
# needs an administrator before it can restart is an application that stays
# down. Override JAVA_HOME to use a system JDK where one exists.
export JAVA_HOME="${JAVA_HOME:-$APP_HOME/.tools/jdk}"
JAVA="$JAVA_HOME/bin/java"
if [[ ! -x "$JAVA" ]]; then
  echo "start.sh: no JVM at $JAVA -- unpack a JDK 21 there or set JAVA_HOME" >&2
  exit 1
fi

# Everything a build downloads stays under Arak/: the host is shared, and the
# Maven and npm caches in the home directory belong to whoever else uses them.
TOOLS="$APP_HOME/.tools"
export MAVEN_USER_HOME="$TOOLS/m2"
export npm_config_cache="$TOOLS/npm-cache"
export YARN_CACHE_FOLDER="$TOOLS/yarn-cache"

# The same reasoning for node: the front end needs 22, and the system node
# belongs to the other applications on the host, which may not want 22 at all.
if [[ -x "$TOOLS/node/bin/node" ]]; then export PATH="$TOOLS/node/bin:$PATH"; fi

# What runs is a copy, not the build output. Serving straight from target/ and
# dist/ means the next build overwrites a jar the JVM still has open, and a
# failed build leaves nothing to fall back to.
RUN="$APP_HOME/.run"
mkdir -p "$RUN"

# A stamp is the git tree ids of the sources an artefact is built from: it
# changes exactly when their content does, and costs nothing to compute.
tree_stamp() { git -C "$APP_HOME" rev-parse "${@/#/HEAD:}" | sha256sum | cut -d' ' -f1; }
stamp_of() { if [[ -f "$RUN/$1.stamp" ]]; then cat "$RUN/$1.stamp"; fi; }

# ---- backend ----
# The docs the assistant answers from are packed into the jar (HelpDocs).
want="$(tree_stamp pom.xml .mvn mvnw backend docs/user-guide.md docs/policy-conflict-resolution.md docs/policy-spec.md)"
if [[ "$want" != "$(stamp_of backend)" || ! -f "$RUN/dac-service.jar" ]]; then
  echo "-- backend sources changed · building the jar"
  # bash, not ./mvnw: the file was committed from Windows and may lack +x.
  # nice, because a build takes both cores for minutes and the applications
  # beside this one are serving people while it does.
  if nice -n 15 bash ./mvnw -B -q -am -pl backend/dac-service -DskipTests \
       -Dmaven.repo.local="$TOOLS/m2/repository" package; then
    cp backend/dac-service/target/dac-service.jar "$RUN/dac-service.jar.new"
    mv -f "$RUN/dac-service.jar.new" "$RUN/dac-service.jar"
    printf '%s' "$want" > "$RUN/backend.stamp"
  elif [[ -f "$RUN/dac-service.jar" ]]; then
    # Up on the previous build rather than down: a restart loop that rebuilds
    # every few seconds would take the rest of the host's CPU with it.
    echo "!! backend build FAILED -- still running the previous jar; fix and restart" >&2
  else
    echo "start.sh: backend build failed and there is no previous jar" >&2
    exit 1
  fi
fi

# ---- front end ----
# The bundle is generated from the JSON schemas as well as from its own
# sources (yarn parse-schema), so a schema change rebuilds it too.
want="$(tree_stamp frontend/app backend/dac-spec/src/main/resources/json/schema)"
if [[ "$want" != "$(stamp_of web)" || ! -f "$RUN/web/index.html" ]]; then
  echo "-- front-end sources changed · building the bundle"
  # The host has node but no yarn; npx fetches the version the lockfile is
  # written for. The type check is left to CI: here it is minutes of a shared
  # two-core machine spent proving what main already proved.
  if (renice -n 15 -p "$BASHPID" >/dev/null && cd frontend/app &&
      npx --yes yarn@1.22.22 install --frozen-lockfile --non-interactive --silent &&
      node scripts/parse-schema.mjs &&
      NODE_OPTIONS=--max-old-space-size=2048 VITE_BASE="${APP_WEB_BASE_PATH:-/Arak/}" \
        npx vite build --logLevel warn); then
    rm -rf "$RUN/web.new"
    cp -r frontend/app/dist "$RUN/web.new"
    rm -rf "$RUN/web.old"
    if [[ -d "$RUN/web" ]]; then mv "$RUN/web" "$RUN/web.old"; fi
    mv "$RUN/web.new" "$RUN/web"
    rm -rf "$RUN/web.old"
    printf '%s' "$want" > "$RUN/web.stamp"
  elif [[ -f "$RUN/web/index.html" ]]; then
    echo "!! front-end build FAILED -- still serving the previous bundle; fix and restart" >&2
  else
    echo "start.sh: front-end build failed and there is no previous bundle" >&2
    exit 1
  fi
fi

if [[ "$BUILD_ONLY" == true ]]; then
  echo "-- built $(git -C "$APP_HOME" log --oneline -1)"
  exit 0
fi

export APP_WEB_ROOT="${APP_WEB_ROOT:-$RUN/web}"
export APP_WEB_BASE_PATH="${APP_WEB_BASE_PATH:-/Arak/}"
export APP_PORT="${APP_PORT:-8090}"
export APP_ADMIN_PORT="${APP_ADMIN_PORT:-8091}"

# A share of the machine rather than -Xmx, and a small one: the box is shared
# with several other applications, and a JVM grows its heap to whatever it is
# allowed long before it needs to. 15% of a 6 GB host is about 900 MB, several
# times what the service uses; raise it in .env if a host is ARAK's alone.
JVM_OPTS="${JVM_OPTS:--XX:MaxRAMPercentage=15 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Bangkok}"

echo "-- ARAK $(git -C "$APP_HOME" log --oneline -1) on :$APP_PORT"
# exec, so that PM2 supervises the JVM itself. Without it PM2 watches this
# shell, and a JVM that dies leaves PM2 reporting a healthy process.
# shellcheck disable=SC2086
exec "$JAVA" $JVM_OPTS -jar "$RUN/dac-service.jar" server "$APP_HOME/conf/dac.yml"
