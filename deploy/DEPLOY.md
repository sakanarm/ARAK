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
| Update | `docker compose up -d` | `git pull origin main` + `pm2 restart arak` |
| Routing | own port, own vhost | one `location /Arak/` in the shared nginx |
| Database | container | one schema of the host's PostgreSQL, owned by one role |

Serving the front end from the service is the part worth being explicit about.
It is one process rather than two, which is the convention every other
application on that host follows, and it means the API and the app are never
served from different origins. The cost is that a front-end change needs a
restart, which `start.sh` turns into a rebuild of the bundle alone.

## Layout on the host

`~/Arak` is a clone of `main`. Everything ARAK owns lives under it; nothing
outside it is touched except the single `include` line in nginx.

```
Arak/                    git clone https://github.com/sakanarm/ARAK.git
  .env                   secrets; gitignored, never leaves the host (chmod 600)
  conf/dac.yml           committed configuration, ${VAR} substituted at startup
  deploy/start.sh        what PM2 runs; builds, then execs the JVM
  deploy/nginx-arak.conf the routing, included by the shared nginx
  .tools/jdk/            a portable JDK 21            (gitignored)
  .tools/m2, npm-cache   build caches, kept out of ~  (gitignored)
  .run/dac-service.jar   the jar that is running      (gitignored)
  .run/web/              the bundle that is served    (gitignored)
```

## Deploying a change

The same two commands as every other application on the host:

```bash
cd ~/Arak && git pull origin main
pm2 restart arak
```

`start.sh` compares the git tree ids of the backend sources and of the front-end
sources (plus the JSON schemas the front end is generated from) against the
stamps in `.run/`. Whatever changed is rebuilt with `./mvnw package` or
`vite build`, copied into `.run/`, and then the JVM starts. A restart with
nothing new starts in seconds; one with a backend change spends a few minutes
building first, and the app is down for that time.

A build that fails does not take the app down: the previous jar or bundle in
`.run/` keeps running, and the log says so with `!!`. Always look:

```bash
pm2 logs arak --lines 50
```

The bundle is built with `VITE_BASE` taken from `APP_WEB_BASE_PATH`, so the
mount point the browser uses and the one the service checks at startup cannot
drift apart. (Building by hand on Windows, set it from PowerShell, not Git
Bash, which rewrites `/Arak/` into `/Program Files/Git/Arak/`.)

`./deploy/start.sh --build-only` runs the build step without starting anything,
for checking a pull before restarting the live process.

## The JDK

The host has no Java and installing one needs a password. A portable JDK 21
unpacked into `Arak/.tools/jdk` needs neither, and pins the runtime to the one
the jar was built against instead of to whatever the box happens to have.

```bash
mkdir -p ~/Arak/.tools && cd ~/Arak/.tools
curl -sSL -o jdk.tgz https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
tar xzf jdk.tgz && rm jdk.tgz && mv jdk-21* jdk && ./jdk/bin/java -version
```

## Configuration

`Arak/.env`, from `.env.example`. The keys this deployment decides:

```
APP_PORT=8090
APP_ADMIN_PORT=8091
APP_WEB_BASE_PATH=/Arak/
APP_DB_HOST=<the host's PostgreSQL>
APP_DB_NAME=<configuration database>
APP_DB_USER=<its role>
APP_DB_PASSWORD=...
APP_DB_POOL_MIN=2
APP_DB_POOL_MAX=8
```

8090 and 8091 because the lower ports on that host are taken by the
applications already on it. The admin connector is health checks and metrics; it
listens on loopback only (`APP_ADMIN_BIND_HOST`), because it has no
authentication. `APP_WEB_ROOT` defaults to `.run/web`.

## Living beside other applications

The host is shared, and nothing ARAK does may cost the applications beside it:

| What | How it is kept small |
|---|---|
| CPU during a build | `start.sh` runs Maven and Vite under `nice -n 15` |
| Memory | the JVM may take 15% of the machine (`JVM_OPTS` to change it) |
| Database connections | the pool is 2–8 (`APP_DB_POOL_*`); the server's limit is shared by everyone |
| A crash | pm2 backs off between restarts, so a broken build is not rerun every second |
| Disk | caches stay in `Arak/.tools`; nothing is written outside `Arak/` |

Generate `IDENTITY_JWT_SECRET`, `IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`,
`FERNET_KEY` and `OM_WEBHOOK_SECRET` for this host; never reuse a laptop's.
`FERNET_KEY` seals the credentials stored in the database: back it up with the
database, because losing it means re-entering every sealed secret.

## First start

```bash
pm2 start ~/Arak/deploy/start.sh --name arak --exp-backoff-restart-delay=3000
pm2 save            # so it survives a reboot
pm2 logs arak
```

`pm2 save` is not optional. Without it the process is gone after the next
restart of the machine and nobody finds out until somebody opens the app. It
writes every process on the account, not only this one, so run `pm2 ls` first
and save only while the others are in the state their owners left them.

The backoff matters more here than for a node app: without it a start that
fails is retried at once, and every retry that finds no jar starts a build.

Until the nginx route exists the app answers on its own port, at the same path:
`http://<host>:8090/Arak/`. The service accepts the prefix as well as the
stripped path, so the address does not change when the route is added — only
the port goes.

## The database

ARAK's configuration lives in one schema of the host's PostgreSQL, owned by
ARAK's role. Flyway creates it on first start (`migrateOnStartup`).

**Check the schema is empty before the first start.** Flyway runs with
`baselineOnMigrate`, so a schema that already has tables in it is baselined at
version 1 instead of migrated: V1 is skipped, and the service starts against a
schema that does not have its tables.

```sql
SELECT count(*) FROM pg_class
 WHERE relnamespace = 'public'::regnamespace AND relkind IN ('r','v');  -- must be 0
```

Take a `pg_dump` before any deploy that adds a migration.

## The one step that needs an administrator

**The nginx include.** One line inside the existing `server { ... }` block:

```nginx
include /home/<user>/Arak/deploy/nginx-arak.conf;
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

`nginx -t` before the reload, every time. A syntax error in a reload takes down
every application on that host, not just this one. Routing changes to
`deploy/nginx-arak.conf` need the same reload and no restart of ARAK.

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
