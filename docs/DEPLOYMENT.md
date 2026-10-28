# Single-host deployment reference

This is a reference for an existing Linux host with systemd, nginx, PostgreSQL17.11, OpenSSL and full Temurin21.0.12.1+1 already available. A domain, matching trusted TLS certificate, database/role, backup storage and host/network access controls must already be configured by the operator. Nothing here provisions a host, purchases resources, installs packages or obtains/renews certificates. The nginx/systemd examples have not been installed as a public deployment. See [startup verification and its exact boundaries](STARTUP_VERIFICATION.md).

Use one API and one worker from the same JAR, PostgreSQL on loopback, and nginx serving the built frontend plus same-origin `/api/` traffic. Only nginx's HTTPS listener should be reachable by customers. Keep the API, database and operator access private. The API holds sessions in memory: restarting it signs users out; additional API replicas require session coordination that this project does not implement. Vite dev/preview are local tools, not the production frontend server.

## Build and stage a release

Use a reviewed revision that passed both [CI jobs](CI.md). On a build machine with pinned Java/Node and the test prerequisites, from its checkout root:

```sh
set -eu
(cd backend && ./mvnw -B -ntp clean verify)
(cd frontend && npm ci && npm run build)
```

The release consists of `backend/target/relay-0.0.1-SNAPSHOT.jar` and the contents of `frontend/dist/`, identified by source revision and recorded hashes. Node/npm are needed to build the frontend, not to serve the resulting files. Do not copy `.env`, `work/`, test reports, dependencies or source credentials into the served directory.

The examples assume an existing unprivileged `relay` service account and a pinned JDK at `/opt/relay/jdk`. As the host administrator, stage a new immutable directory; replace the revision placeholder with the reviewed commit SHA first:

```sh
set -eu
revision='REPLACE_WITH_REVIEWED_COMMIT_SHA'
case "$revision" in
  ''|*[!0-9a-f]*) printf '%s\n' 'Set a reviewed lowercase hexadecimal commit SHA.' >&2; exit 1 ;;
esac
test "${#revision}" -eq 40
release="/opt/relay/releases/$revision"
test ! -e "$release"
install -d -o root -g root -m 0755 "$release" "$release/frontend"
install -o root -g root -m 0644 backend/target/relay-0.0.1-SNAPSHOT.jar "$release/relay.jar"
cp -R frontend/dist/. "$release/frontend/"
chown -R root:root "$release"
chmod -R go-w "$release"
```

The service account and nginx need read/traverse access to their release files; they must not be able to replace them. `/opt/relay/current` will be a symlink to one staged release. These commands assume trusted, reviewed build output, not an arbitrary uploaded archive.

## Persistent secret configuration

The database role must own the application database/schema and be able to apply Flyway migrations. Use a role without cluster superuser privileges. The API and worker require the **same persistent signing master**: canonical Base64 of 32 random bytes. Retain it with database backups; replacing it prevents decryption of existing endpoint signing secrets. Automatic master-key rotation is not implemented.

For first setup only, the following creates a root-only environment file and writes a freshly generated master directly to it. It refuses to overwrite existing configuration and does not print the generated value. Replace the database password placeholder using a private administrator editor before starting either service. Do not regenerate the master for an upgrade or restart. [OpenSSL random-byte generation](https://docs.openssl.org/master/man1/openssl-rand/)

```sh
set -euC
umask 077
install -d -o root -g root -m 0700 /etc/relay
test ! -e /etc/relay/relay.env
{
  printf '%s\n' 'DB_URL=jdbc:postgresql://127.0.0.1:5432/relay' 'DB_USERNAME=relay'
  printf '%s\n' 'DB_PASSWORD=EDIT_IN_PRIVATE_EDITOR_BEFORE_STARTING'
  printf '%s\n' 'RELAY_OPERATIONS_ENABLED=false' 'RELAY_DELIVERY_ALLOW_LOCAL_RECEIVER=false'
  printf '%s' 'RELAY_SIGNING_MASTER_KEY='
  openssl rand -base64 32
} > /etc/relay/relay.env
chmod 0600 /etc/relay/relay.env
```

This is systemd `EnvironmentFile` syntax, not a shell script; quote/escape edited values according to [systemd's environment-file rules](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html#EnvironmentFile=). Do not source it, put secrets in command arguments, print it in logs, commit it or serve it as frontend content. A dedicated service identity and restricted host access protect the processes that receive these environment values. Leave the development profile and local-receiver URL unset in this deployment.

## API and worker services

Save this candidate as `/etc/systemd/system/relay-api.service`:

```ini
[Unit]
Description=Relay API
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=relay
Group=relay
WorkingDirectory=/opt/relay/current
EnvironmentFile=/etc/relay/relay.env
EnvironmentFile=-/etc/relay/api-operations.env
Environment=SESSION_COOKIE_SECURE=true
Environment=SERVER_FORWARD_HEADERS_STRATEGY=framework
ExecStart=/opt/relay/jdk/bin/java -Xms64m -Xmx256m -jar /opt/relay/current/relay.jar
Restart=on-failure
RestartSec=5s
KillSignal=SIGTERM
TimeoutStopSec=45s
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Save this candidate as `/etc/systemd/system/relay-worker.service`:

```ini
[Unit]
Description=Relay delivery worker
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=relay
Group=relay
WorkingDirectory=/opt/relay/current
EnvironmentFile=/etc/relay/relay.env
ExecStart=/opt/relay/jdk/bin/java -Xms64m -Xmx256m -jar /opt/relay/current/relay.jar --spring.profiles.active=worker
Restart=on-failure
RestartSec=5s
KillSignal=SIGTERM
TimeoutStopSec=45s
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

No readiness notification is implied by `Type=simple`; check the API probe and worker's fixed startup record separately. Both processes require PostgreSQL to be available. Flyway applies/validates migrations at startup. Start the API and confirm migration/readiness before starting the worker.

Ordinary systemd stop sends SIGTERM. The worker defaults to a 10s admission-close/drain grace, configurable up to20s plus its bounded cancellation wait. The 45s service stop timeout leaves room for framework cleanup, then systemd may force termination; it is not a guarantee that every request completed. Unfinished attempts rely on lease recovery and may be delivered again. [Shutdown behavior](WORKER_SHUTDOWN.md), [systemd stop/restart semantics](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html)

## HTTPS and the same-origin frontend

Adapt this nginx server configuration to the existing host. Replace `relay.example.com`, both certificate paths and any conflicting default virtual-host configuration. Certificate issuance/renewal and nginx installation are prerequisites, not automated here.

```nginx
server {
    listen 80;
    server_name relay.example.com;
    return 301 https://relay.example.com$request_uri;
}

server {
    listen 443 ssl;
    server_name relay.example.com;
    ssl_certificate /etc/ssl/relay/fullchain.pem;
    ssl_certificate_key /etc/ssl/relay/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    root /opt/relay/current/frontend;
    index index.html;
    client_max_body_size 256k;
    client_body_timeout 15s;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_cache off;
        proxy_connect_timeout 5s;
        proxy_read_timeout 30s;
        proxy_send_timeout 30s;
        proxy_set_header Host relay.example.com;
        proxy_set_header Forwarded "";
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Host relay.example.com;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Port 443;
        proxy_set_header X-Forwarded-Prefix "";
        proxy_set_header X-Forwarded-Ssl "";
    }

    location = /actuator { return 404; }
    location /actuator/ { return 404; }
    location / { try_files $uri $uri/ =404; }
}
```

The `proxy_pass` has no URI suffix, preserving `/api/...` paths. Dashboard routes use URL fragments, so `/#/workspaces/...` still requests `/` from nginx. Keep the API on its default loopback address; do not expose8080 or accept forwarded headers from an untrusted direct caller. The proxy replaces client-supplied forwarded metadata, and the API explicitly uses framework handling. Secure session cookies remain mandatory for HTTPS; session/CSRF authentication stays same-origin. [nginx proxy directives](https://nginx.org/en/docs/http/ngx_http_proxy_module.html), [Boot proxy handling](https://docs.spring.io/spring-boot/how-to/webserver.html), [forwarded-header trust boundaries](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html)

Validate the adapted configuration with `nginx -t` before reloading. Configure access-log retention and keep credentials out of URLs/log formats. Adding a proxy does not add application authentication throttling or storage quotas. Restrict the portfolio deployment to its intended users; registration, retention and operational policies need an operator decision before broader exposure.

## Activate and verify

During a maintenance window, with the existing services stopped, select the staged release and validate the candidate units before starting:

```sh
set -eu
revision='REPLACE_WITH_REVIEWED_COMMIT_SHA'
case "$revision" in
  ''|*[!0-9a-f]*) printf '%s\n' 'Set a reviewed lowercase hexadecimal commit SHA.' >&2; exit 1 ;;
esac
test "${#revision}" -eq 40
release="/opt/relay/releases/$revision"
test -f "$release/relay.jar"
test -f "$release/frontend/index.html"
test ! -e /opt/relay/current || test -L /opt/relay/current
ln -sfnT "$release" /opt/relay/current
systemd-analyze verify /etc/systemd/system/relay-api.service /etc/systemd/system/relay-worker.service
systemctl daemon-reload
systemctl start relay-api
curl --fail --silent --show-error --retry-connrefused --retry 20 --retry-delay 1 --retry-max-time 60 --connect-timeout 2 --max-time 3 http://127.0.0.1:8080/actuator/health/readiness
systemctl start relay-worker
systemctl is-active relay-api relay-worker
```

The API probe should return `{"status":"UP"}`. Readiness retries are bounded; if they fail, the command block stops before worker start. Inspect API/migration status before retrying. Confirm the fixed worker-ready record in restricted service logs and verify it opens no HTTP listener. Enable service startup at boot only after validation. Check the real HTTPS origin: register/login, confirm the session cookie is Secure/HttpOnly/SameSite=Lax, create a workspace/key/endpoint, submit an event to an authorized HTTPS destination, inspect attempts, revoke the key and log out. Never paste revealed keys or signing secrets into a shared log. Keep rollback access available until these checks pass.

Actuator stays off the public proxy. Local liveness/readiness probes need no credential. Optional Prometheus access requires a separate canonical43-character operator token and API-only configuration; see [operator setup](OBSERVABILITY.md). Put optional API settings in root-only `/etc/relay/api-operations.env`, without changing the worker's configuration. Use a private scraper or root-only curl config file for its Authorization header, rather than exposing the token in command arguments. Readiness describes the API/database, not destination or worker health.

## Backups, upgrades and rollback

Keep versioned backups of the database and its original signing master, with restricted/encrypted storage and a defined retention/restore policy. Backups contain customer payloads, response previews and credential hashes. A consistent `pg_dump -Fc` covers one database; it excludes cluster roles/tablespaces and the signing master. Retain required role/grant setup through the database administrator's separate backup process. [PostgreSQL dump semantics](https://www.postgresql.org/docs/17/app-pgdump.html)

For a maintenance backup, stop customer ingress, stop/drain worker and API, then use the PostgreSQL17 client. This example prompts through the existing authentication setup; it never puts a password in arguments:

```sh
set -eu
umask 077
backup="/srv/relay-private/relay-$(date -u +%Y%m%dT%H%M%SZ).dump"
test ! -e "$backup"
pg_dump --host=127.0.0.1 --port=5432 --username=relay --format=custom --file="$backup" relay
```

The private backup directory must already exist with restricted permissions. Do not overwrite your only recovery copy. Retain the matching private signing configuration separately. Test restoration into a newly created, isolated database with `pg_restore --exit-on-error --no-owner --dbname=...`, then verify V1–V7/Flyway state, expected row counts and signing-key decryption with the original master. Keep workers stopped and outbound traffic blocked during this test: a restored ledger may resend events whose receiver effects already happened. A dump alone does not establish recovery time or point-in-time recovery.

For each upgrade: validate a backup/restore path; stage matching frontend/JAR artifacts; stop ingress and services; select the release; start API for migrations/readiness; start worker; verify HTTPS customer and operator flows; then restore ingress. In-memory sessions end on API restart. Do not regenerate the signing master. Keep the previous release and database backup until validation is complete.

An existing browser tab can still refer to the previous release's hashed assets. Hard-refresh or reopen the dashboard after an upgrade; retain the previous release for rollback. This simple maintenance-window switch does not implement asset retention across live releases or zero-downtime frontend rollout.

Switching a symlink is a code rollback only. Use an older artifact only when its schema compatibility is established; Flyway does not automatically reverse migrations. Otherwise restore a tested database backup together with its matching key/configuration, accepting the chosen recovery point and possible duplicate deliveries. Stable event IDs enable receiver-side deduplication; the sample's memory-only state does not survive receiver restart. No exactly-once effect or guaranteed eventual delivery is claimed.
