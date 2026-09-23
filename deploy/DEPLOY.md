# Deploying ARAK to a shared PM2 host

This describes the deployment the platform is actually going to: a Linux box
that hosts several applications side by side, each one a PM2 process on its own
port, all of them behind a single nginx that routes by path prefix. There is no
Docker on that host, no system JVM, and no unattended `sudo`.

`deploy/docker-compose.yml` is still the right thing for a laptop, where it
brings up PostgreSQL, SQL Server and the rest in one command. It is not what
runs in the estate. Nothing below uses it.

## What the shape costs us, and what it buys

| | Docker image | PM2 on the shared host |
|---|---|---|
| Runtime | supplied by the image | a JDK unpacked into `Arak/.tools/jdk` |
| Front end | separate nginx container | served by the service itself |
| Restart | `docker compose up -d` | `pm2 restart arak` |
| Routing | own port, own vhost | one `location /Arak/` in the shared nginx |
| Database | container | the host's PostgreSQL, one role and one database |

Serving the front end from the service is the part worth being explicit about.
It is one process rather than two, which is the convention every other
application on that host follows, and it means the API and the app are never
served from different origins. The cost is that a front-end change is a file
copy plus a restart rather than a file copy alone.

## Layout on the host

Everything ARAK owns lives under one directory. Nothing outside it is touched
except the single `include` line in nginx.

```
Arak/
  dac-service.jar        the shaded service (API + front end)
  conf/dac.yml           committed configuration, ${VAR} substituted at startup
  .env                   secrets; never committed, never leaves the host
  web/                   the built front end (frontend/app/dist)
  .tools/jdk/            a portable JDK 21
  deploy/start.sh        what PM2 runs
  deploy/nginx-arak.conf the routing, included by the shared nginx
  logs/                  PM2's stdout/stderr
```

## Build here, copy across

The host has Node but no Maven and no JDK, so both artefacts are built locally.

```bash
# Backend — the shaded jar, from the repository root
JAVA_HOME="$PWD/.tools/jdk-21.0.12.1+1" \
  ./mvnw -q -am -pl backend/dac-service -DskipTests package

# Front end — built FOR ITS MOUNT POINT, which is not the default
cd frontend/app && VITE_BASE=/Arak/ npx vite build
```

Building on Windows, set `VITE_BASE` from PowerShell (`$env:VITE_BASE='/Arak/'`)
and not from Git Bash. Git Bash rewrites anything that looks like a Unix path
on its way to a native program, and `/Arak/` arrives at Vite as
`/Program Files/Git/Arak/` — which builds cleanly, deploys, and serves a blank
page.

`VITE_BASE` is the one that is easy to forget and silent when forgotten: a
bundle built for `/` loads `index.html` from `/Arak/` and then asks for
`/assets/index-*.js`, which is outside ARAK's prefix and belongs to whatever
else is routed at the root. The page comes up blank. The service compares the
bundle's mount point against `APP_WEB_BASE_PATH` at startup and logs an error
when they differ, so the log says what the browser cannot.

Then copy: `backend/dac-service/target/dac-service.jar` → `Arak/dac-service.jar`,
`frontend/app/dist/` → `Arak/web/`, `conf/dac.yml` → `Arak/conf/dac.yml`, and
`deploy/` → `Arak/deploy/`.

## The JDK

The host has no Java and installing one needs a password. A portable JDK 21
unpacked into `Arak/.tools/jdk` needs neither, and pins the runtime to the one
the jar was built against instead of to whatever the box happens to have.

```bash
tar xzf <jdk-21-linux-x64.tar.gz> -C ~/Arak/.tools
mv ~/Arak/.tools/jdk-21* ~/Arak/.tools/jdk
~/Arak/.tools/jdk/bin/java -version
```

## Configuration

`Arak/.env`, from `.env.example`. The three that this deployment shape decides:

```
APP_PORT=8090
APP_ADMIN_PORT=8091
APP_WEB_ROOT=/home/<user>/Arak/web
APP_WEB_BASE_PATH=/Arak/
```

8090 and 8091 because the lower ports on that host are taken by the
applications already on it. The admin connector is health checks and metrics; it
is deliberately not routed from outside.

## Running it

```bash
pm2 start ~/Arak/deploy/start.sh --name arak
pm2 save            # so it survives a reboot
pm2 logs arak
```

`pm2 save` is not optional. Without it the process is gone after the next
restart of the machine and nobody finds out until somebody opens the app.

## Two steps that need an administrator

Both are one-time, and neither can be done by the account the application runs
as.

**1. The database.** ARAK needs its own role and database in the host's
PostgreSQL; it must not share another application's.

```sql
CREATE ROLE dac LOGIN PASSWORD '<generated>';
CREATE DATABASE dac OWNER dac;
```

Flyway creates the schema on first start (`migrateOnStartup`), so nothing else
is needed. Put the password in `Arak/.env` as `APP_DB_PASSWORD`.

**2. The nginx include.** One line inside the existing `server { ... }` block:

```nginx
include /home/<user>/Arak/deploy/nginx-arak.conf;
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

`nginx -t` before the reload, every time. A syntax error in a reload takes down
every application on that host, not just this one.

## Updating

```bash
pm2 stop arak
# copy the new jar and/or web/ across
pm2 start arak
```

Routing changes are the exception: `deploy/nginx-arak.conf` is read by nginx,
not by the application, so those need `sudo nginx -t && sudo systemctl reload
nginx` and no restart at all.

## Known consequence of running behind a shared proxy

Every request reaches the service from `127.0.0.1`, because nginx is the client.
`QueryResource` records `clientIp` from `getRemoteAddr()` and deliberately not
from `X-Forwarded-For` — a header the caller controls is not evidence of where a
query came from, and an audit trail that can be written by the thing being
audited is worse than one that admits it does not know.

So the query audit will record `127.0.0.1` for everyone. The real client address
does arrive, in `X-Real-IP` and `X-Forwarded-For` set by nginx. Trusting it is
only safe once the service also knows that the only thing that can reach its
port is that nginx, which on this host means binding the connector to the
loopback interface. That is a change to make deliberately, with the binding in
place first; it is not made here.
