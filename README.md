# Facility Module Update Agent

A small background agent that runs on each facility server. It keeps the Spring Boot application's modules on the latest released version, so IT staff don't have to install updates by hand.

It is a single JAR with no dependencies and runs on Java 8 or later. It uses the same module-upload REST API that the application's module screen uses.

```
 Release team                         Facility server (every 30 min, as SYSTEM)
 ─────────────                        ──────────────────────────────────────────
 jars/*.jar ─┐                        1. fetch manifest.json + .sig  ── or USB stick ── or cached copy
 manifest ───┼─ publish.bat ─► web ─► 2. verify RSA signature + sequence (no tampering, no downgrade)
 (signed)    └─ usb-bundle/           3. ask the app which module versions are installed
                                      4. download what's newer: resumable, retried, bandwidth-capped, SHA-256 checked
                                      5. in the maintenance window (or now, if urgent):
                                         upload ► install ► restart ► verify version is live
                                         └─ failed? re-install the previous version (rollback)
                                      6. save state + POST status to central server (optional)
```

## Why it works with poor connectivity

- **Downloads resume.** A dropped connection keeps the partial file, and the next attempt carries on with an HTTP Range request. This works across scheduled runs too.
- **Downloads and installs are separate.** JARs trickle down during the day, capped by `download.max-kbps`. They are installed at night from the local cache, so installing doesn't need the network.
- **USB fallback.** Plug in a stick with a `module-updates` folder and the next run picks it up. The bundle has to pass the same signature check.
- **Cached manifest.** If the server can't be reached, the agent keeps working from the last verified release list.

## Safety

| Risk | Protection |
|---|---|
| Tampered or fake release list | RSA-3072 signature check. Only the release team holds the private key. |
| Corrupt or half-downloaded JAR | SHA-256 from the signed manifest is checked before every install. |
| Replay of an old manifest (downgrade) | `sequence` must never decrease. Older manifests are rejected. |
| Bad release breaks the app | The agent checks that the new version is live after the restart. If it isn't, it re-installs the previous version from the cache. |
| Retry loop on a broken release | The agent gives up after `install.max-attempts` and waits for a newer release. |
| Updates during clinic hours | Installs happen only in `install.window` (default 20:00–06:00). Releases marked `urgent` skip the window. |
| Two runs overlap | A lock file lets only one run work at a time. |
| Credentials on disk | The installer restricts the folder to SYSTEM and Administrators. The password can also come from the `UPDATER_APP_AUTH_PASSWORD` environment variable. |

Modules a facility doesn't use are never installed unless `install.new-modules=true`.

## Matching your application's API (do this once before rollout)

The defaults follow a common JHipster/LAMIS+ layout, but **I couldn't see your application's code**. Check each line marked `VERIFY` in `updater.properties` against your core's module controller:

1. **Login.** `app.auth.path`, the request field names, and `app.auth.token-field` (the JSON field holding the JWT).
2. **List modules.** `app.api.list-modules` must return a JSON array of objects with name and version fields. Set `items-path` if the array is wrapped, and set `name-field`/`version-field` to the right names.
3. **Upload.** `app.api.upload` and the multipart field name (`file`).
4. **Install/activate.** `app.api.install` is the second call, if your UI does "upload" and then "install". Leave it blank if uploading alone installs the module.
5. **Restart.** `app.restart.command`, e.g. `net stop "LAMISPlus" && net start "LAMISPlus"`. Leave it blank if the app hot-loads modules.
6. **Module names.** Names in the manifest must match the names the API reports. Case doesn't matter.

Then, on a test machine, run `check.bat`. It should list the installed modules and the available updates without changing anything.

> Rollback needs the previous version's JAR in the local cache. The agent caches every version it installs, so rollback works from the second agent-managed update of each module onward.

## Release team workflow

First time only:
```
java -jar module-updater.jar keygen release-private.pem release-public.pem
```
Keep `release-private.pem` secret and backed up. Ship `release-public.pem` with every facility install.

For each release:
1. Put the new JARs in `release-team\jars\`. Include unchanged modules that are still current.
2. Edit `release-team\manifest.json`. List every current module with `name`, `version`, `url` (file name), and optionally `order`, `urgent`, `force_version`, `min_core_version` and `notes`.
3. Run `publish.bat`. It fills in `sha256`, `size`, `sequence` and `published_at`, then signs the manifest.
4. Upload `manifest.json`, `manifest.json.sig` and the JARs to the folder behind `manifest.url`. Any static host works: IIS, nginx, S3 or similar. For offline sites, copy `usb-bundle\module-updates` to a USB stick.

`force_version: true` makes facilities install exactly that version, even if they have a newer one. Use it to pull a bad release.

## Facility installation

1. Extract the facility package on the server.
2. Right-click `install-agent.bat` and choose **Run as administrator**. It installs to `C:\ModuleUpdater` and creates two SYSTEM scheduled tasks: one every 30 minutes and one at startup.
3. Edit `C:\ModuleUpdater\updater.properties`. At minimum set `facility.id`, `manifest.url` and `app.auth.password`.
4. Run `check.bat`.

After that, nothing needs doing. Tools for support staff:
- `status.bat` shows the last result, installed and pending versions, and recent history.
- `run-now.bat` runs one update cycle immediately.
- Logs are in `C:\ModuleUpdater\logs\updater.0.log`.

## Seeing which facilities are behind

Set `report.url` (and `report.token`). Every run then POSTs JSON with the facility ID, hostname, installed versions, pending updates, last result/error and recent events. Any endpoint that accepts JSON will do. Sites with no recent report, or with a non-empty `pending` list, need a follow-up.

## Commands

```
java -jar module-updater.jar once     one cycle (what the scheduled task runs)
java -jar module-updater.jar run      run continuously every check.interval-minutes
java -jar module-updater.jar check    dry run: show installed versions and updates
java -jar module-updater.jar status   print saved status
java -jar module-updater.jar keygen <private.pem> <public.pem>
java -jar module-updater.jar sign <private.pem> <manifest.json> [jars-folder]
```

## Building

`build.bat` or `./build.sh` (JDK 9+ to build; output runs on Java 8+) compiles the code, runs the end-to-end tests and writes `target/module-updater.jar`. `mvn package` also works.

The tests (`SelfTest`) run a fake release server and a fake module API. They cover:
- waiting for the window
- resuming after a dropped download
- install and verify
- rejecting a tampered manifest
- rollback and the retry limit
- urgent installs
- USB bundles
- downgrade protection
- the application being down
