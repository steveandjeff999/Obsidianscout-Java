# Server Performance & Robustness Report

Audit date: 2026-10-29. Scope: the Ktor/Exposed server in `src/main/kotlin` (about 61k lines), its startup path, background jobs, and how the web client loads it.
Nothing was changed. Every finding below comes from reading the code. None was measured under load, so the impact ratings are estimates.

---

## TL;DR: the 10 changes with the best payoff

| # | Change | Gain | Effort |
|---|--------|------|--------|
| 1 | Stop loading `profile_picture` (up to 1 MB each) on every authenticated request and every chat poll | Big drop in memory, DB I/O and response size | S |
| 2 | Replace short-interval client polling (chat 2s, alliance 3s, pick-list 4s, assignments 5s, connection 6s, banners 10s, theme 15s) with WebSocket "something changed" pushes | Removes most idle request traffic | M |
| 3 | Fix `CallLogging` so it doesn't log every `/api` call when logging is **off**, and make the console appender async | Takes blocking stdout writes off every request | S |
| 4 | Cache rendered HTML pages in memory (today each page view reads 2 files and runs 3 regex passes) | Faster page loads, less CPU | S |
| 5 | Make versioned `?v=` JS/CSS `immutable` and serve them pre-compressed | Fewer revalidation round trips; no gzip CPU per asset | S |
| 6 | Size the DB pool and thread pools to the hardware (Cockroach: 64 fixed connections that never recycle) | Steadier under bursts, less DB memory, recovers after failover | S |
| 7 | Add a short TTL cache for `SettingsService.getSettings` (DB read + JSON decode, 31 call sites) | Fewer queries per request | S |
| 8 | Run the scouting reminder job on one cluster node only, scoped to current events | No duplicate pushes or emails; bounded query | S |
| 9 | Rewrite deduplication so it doesn't pull every scouting entry (with JSON) into memory every 30 min | Removes the main OOM risk | M |
| 10 | Make the quorum-fallback mirror incremental instead of delete-all/re-insert every 30s | Big cut in background DB load when it's enabled | M |

---

## 1. Request hot path (runs on every request)

### 1.1 Each authenticated request reads the full user row, including the profile picture (high)
[Session.kt:144](src/main/kotlin/com/obsidianscout/auth/Session.kt:144) runs `Users.selectAll()`, so every `requireSession()` reads `profile_picture`. That column is a base64 data URL capped at **1,000,000 chars** ([AuthService.kt:1065](src/main/kotlin/com/obsidianscout/auth/AuthService.kt:1065)). The value is read at [Session.kt:191](src/main/kotlin/com/obsidianscout/auth/Session.kt:191) and then never used.

**Fix:** use `Users.select(Users.id, Users.role, Users.teamNumber, Users.username, Users.program, …)` and leave the picture out. Then go further: cache the result of the user + session check for about 5–10 s per `sessionId`, in a small map with a size limit. Clear an entry when that user's role or team changes, or when they log out. Most polling requests would then need no DB query at all.

### 1.2 Chat polling re-sends 200 messages plus every sender's profile picture every 2 seconds (high)
- [chat.js:1044](src/main/resources/static/js/chat.js:1044) calls `loadMessages()` and `loadGroupUnreads()` every **2 s**.
- [ChatService.kt:104-145](src/main/kotlin/com/obsidianscout/db/ChatService.kt:104) re-reads the last 200 messages every time. Messages already link to avatars by URL, but building those URLs loaded every sender's full picture (up to 1 MB each) from the database just to hash it, on every poll. The ETag saves bandwidth only when nothing changed, and the server still does the full query, serialization and CRC.
- `getMessages` calls `ensureDefaultGroup` (which may INSERT) inside a `readTransaction`, on every poll.
- [ChatService.kt:671-700](src/main/kotlin/com/obsidianscout/db/ChatService.kt:671) `getUnreadStatus` makes 2 queries **per group** and fetches the full text of every unread message so Kotlin can search it for `@mentions`.

**Fix:**
- Serve avatars from their own cacheable endpoint (`/api/users/{id}/avatar`, with an ETag and `max-age`) and return only an avatar URL or hash in messages.
- Add `?after=<createdAt|id>` so polls return only new messages.
- Push new messages over the existing `NotificationWebSocketManager` and keep polling only as a 30–60 s fallback.
- Compute unread counts with one grouped query (`GROUP BY group_name`). Detect mentions with a SQL `LIKE '%@user%'` count, or set a `mentions` flag when the message is written.
- Call `ensureDefaultGroup` once, when a team or program is created.

### 1.3 Two Cache-Control systems both set headers (medium)
[App.kt:250-278](src/main/kotlin/com/obsidianscout/App.kt:250) installs `CachingHeaders`, and the interceptor at [App.kt:307-339](src/main/kotlin/com/obsidianscout/App.kt:307) also calls `headers.append("Cache-Control", …)`. Most responses probably go out with **two** `Cache-Control` headers that disagree slightly. That's extra bytes, and caches handle the conflict differently. `respondStaticHtml` adds a third ([StaticWebRoutes.kt:136](src/main/kotlin/com/obsidianscout/routes/StaticWebRoutes.kt:136)).
**Fix:** keep a single source, either the `CachingHeaders` block or the interceptor, not both. Check with `curl -I http://localhost:8080/api/settings`.

### 1.4 `DoubleReceive` is installed globally (medium)
[App.kt:209](src/main/kotlin/com/obsidianscout/App.kt:209) buffers **every** request body in memory, including large photo uploads. Only the peer-forwarding path needs to read a body twice.
**Fix:** install it only on the routes that need it, or read the body once in `PeerLoadRouter` and pass the bytes along.

### 1.5 Peer load router does slow work on every request when enabled (medium)
With load balancing on, each request calls `ClusterManagementService.getLocalTailscaleIp()` **twice** ([App.kt:426](src/main/kotlin/com/obsidianscout/App.kt:426), [PeerLoadRouter.kt:357](src/main/kotlin/com/obsidianscout/admin/PeerLoadRouter.kt:357)). That function walks every network interface ([ClusterManagementService.kt:147](src/main/kotlin/com/obsidianscout/admin/ClusterManagementService.kt:147)), which is slow on Windows. Each request also reads MXBeans to score the local node. Forwarded requests and their responses are fully buffered as `ByteArray` ([PeerLoadRouter.kt:421](src/main/kotlin/com/obsidianscout/admin/PeerLoadRouter.kt:421), [:465](src/main/kotlin/com/obsidianscout/admin/PeerLoadRouter.kt:465)).
**Fix:** cache the local IP (refresh every minute) and compute the local `NodeLoad` in the existing probe loop. The hot path then only reads a `@Volatile` field. Stream proxied responses with `BodyHandlers.ofInputStream()` and `respondOutputStream`.

### 1.6 Smaller per-request costs (low)
- `ContentNegotiation` registers JSON for `ContentType.Any`, `Text.Plain`, `Text.Html` and others ([App.kt:489-496](src/main/kotlin/com/obsidianscout/App.kt:489)). The JSON converter can then pick up bodies that aren't JSON. Registering `Application.Json` alone is enough.
- ETag/304 ([App.kt:287](src/main/kotlin/com/obsidianscout/App.kt:287)) runs only after the handler has done all its work. That's fine. The real savings come from 1.1, 2.1 and 3.1.
- `requireSession` logs a WARN with cookie details on **every** 401 ([Session.kt:79](src/main/kotlin/com/obsidianscout/auth/Session.kt:79)). An expired tab that keeps polling floods the log. Rate-limit it or lower it to DEBUG.

---

## 2. Client polling (the largest source of idle load)

With a page open and the user doing nothing, each tab sends roughly 6 banner requests, 10 connection probes and 4 `/api/settings` calls per minute, plus chat-unread polling. On top of that, by page:

| Page | Interval | Requests |
|------|----------|----------|
| Chat | 2 s | messages + group unreads |
| Alliance selection | 3 s | poll |
| Pick list | 4 s | poll |
| Admin Slack | 4 s | status |
| Assignments | 5 s | assignments + conflicts |

With 30 scouts at an event that is **thousands of requests per minute** while nobody is doing anything. Each one runs session lookup, DB queries and JSON encoding.

### 2.1 Fix: push invalidations over the WebSocket you already have
`NotificationWebSocketManager` already keeps a socket per user. Add a small `{"type":"invalidate","topic":"assignments:<event>"}` message, sent wherever data changes (the code already calls `WidgetPushService.notifyTeam` / `notifyUsers` in those places, so reuse those hooks). Clients refetch only when they receive an invalidation, and fall back to a 60 s poll. Banners and theme (settings) can use the same channel.

Also pause all polling while `document.hidden`. `dashboard.js` and `cache-manager.js` already do; `chat.js`, `assignments.js`, `pick-list.js`, `alliance-selection.js` and `banners.js` do not.

---

## 3. Static content

### 3.1 HTML pages are rebuilt from disk on every view (medium)
[StaticWebRoutes.kt:105-139](src/main/kotlin/com/obsidianscout/routes/StaticWebRoutes.kt:105) does all of the following on every page view:
- reads the page and `base.html` from disk or the classpath,
- compiles a new `Regex` for the sidebar,
- runs two regex passes over the whole document,
- gzips the result.

**Fix:** keep a `ConcurrentHashMap<fileName, Rendered(html, gzipBytes, etag)>` keyed by file name and version. Recheck file mtimes only in dev mode. Send an ETag so reloads get a 304.

### 3.2 Versioned assets are still revalidated and re-gzipped (medium)
HTML rewrites every JS/CSS URL to `?v=<version>`, but `.js` and `.css` are still served `no-cache` ([App.kt:268](src/main/kotlin/com/obsidianscout/App.kt:268), [:336](src/main/kotlin/com/obsidianscout/App.kt:336)). Every page load therefore revalidates about 64 JS files, and each full response goes through gzip again.
**Fix:**
- When the request has `v=`, send `Cache-Control: public, max-age=31536000, immutable`.
- Generate `.br` and `.gz` files at build time and use Ktor's `preCompressed(CompressedFileType.BROTLI, CompressedFileType.GZIP)` in `staticFiles` / `staticResources`, so the `Compression` plugin skips them.
- `vendor/plotly-2.32.0.min.js` (3.6 MB) should be lazy-loaded only on graph pages, if it isn't already.

---

## 4. Database layer

### 4.1 Pool sizing and connection lifetime (high for Cockroach)
[DatabaseFactory.kt:164-189](src/main/kotlin/com/obsidianscout/db/DatabaseFactory.kt:164):
- **Cockroach/Postgres:** 64 fixed connections with `maxLifetime = 0` and `idleTimeout = 0`. CockroachDB's guidance is about **4 × vCPU** connections in total across all app nodes. Every extra connection costs memory on the DB node. Connections that never recycle also keep pointing at a gateway that may have restarted. The comment says this helps during failover, but in practice dead sockets only get noticed when they're borrowed.
- On startup the pool opens all 64 connections in a row before anything else can run ([DatabaseFactory.kt:195-207](src/main/kotlin/com/obsidianscout/db/DatabaseFactory.kt:195)).
- All request handling runs on `Dispatchers.IO` ([App.kt:203](src/main/kotlin/com/obsidianscout/App.kt:203)), and so do roughly 20 background scopes. `Dispatchers.IO` has 64 threads by default. With 64 DB connections, a burst of slow queries can tie up **every** IO thread, which stalls WebSocket sends, schedulers and health probes.

**Fix:**
- Pool size `max(8, cores * 4)` with `minimumIdle` of about 4.
- `maxLifetime` of about 30 min, and `keepaliveTime` of about 2 min to detect dead sockets.
- Drop the warm-up loop (or warm up only `minimumIdle` connections).
- Run JDBC work on a dedicated `Dispatchers.IO.limitedParallelism(poolSize)` dispatcher so a backed-up DB can't starve the rest of the server.

### 4.2 SQLite settings (medium)
[DatabaseFactory.kt:1478](src/main/kotlin/com/obsidianscout/db/DatabaseFactory.kt:1478) defaults to `synchronous=FULL` in WAL mode. In WAL mode, `NORMAL` is crash-safe: it can lose only the last few commits on a **power** loss, never through corruption. Writes get several times faster.
Also consider `cache_size=-16000` (16 MB), `temp_store=MEMORY`, and `mmap_size` of about 64 MB.
SQLite allows only one writer at a time, but the pool holds 8 connections. Consider a single-connection write pool plus a read pool. `writeTransactionWithRetry` uses `Thread.sleep` ([DatabaseFactory.kt](src/main/kotlin/com/obsidianscout/db/DatabaseFactory.kt), at the end of the file), which blocks an IO thread. It also counts any message containing "busy" as retryable.

### 4.3 Settings are re-read and re-parsed on every call (medium)
[SettingsService.kt:315](src/main/kotlin/com/obsidianscout/integrations/SettingsService.kt:315) can run up to 2 queries plus a full JSON decode and normalize, and it has 31 call sites. One request often calls it several times, and `/api/settings` is polled every 15 s per tab.
**Fix:** use a `(team, program) → (ApiSettings, loadedAt)` cache with a 5–10 s TTL, cleared in `writeSettings`. The short TTL keeps other cluster nodes up to date.

### 4.4 Indexes (low)
Coverage is mostly good. Possible improvements:
- `chat_messages`: replace the two indexes with one on `(team_number, program, group_name, created_at)`. Every chat query filters on all four columns.
- `shared_links.token` has both a `uniqueIndex` and a plain `index` ([Tables.kt:776](src/main/kotlin/com/obsidianscout/db/Tables.kt:776), [:802](src/main/kotlin/com/obsidianscout/db/Tables.kt:802)). The plain index is redundant and only slows writes.
- `scouting_assignments`: the reminder job filters on `status` and `match_key IS NOT NULL`. A partial or composite `(status, event_key)` index would help once the table grows.

### 4.5 N+1 queries (low)
[MobileRoutes.kt:2638](src/main/kotlin/com/obsidianscout/routes/MobileRoutes.kt:2638) runs one count query per alliance. Replace it with a single `GROUP BY alliance_id`. The other list endpoints I checked already batch with `inList`.

---

## 5. Background jobs

About 20 loops start on every node. Problems, in priority order:

### 5.1 Deduplication loads every scouting entry into memory (high, OOM risk)
[DeduplicationScheduler.kt:76-81](src/main/kotlin/com/obsidianscout/scouting/DeduplicationScheduler.kt:76) runs `selectAll().toList()` on match, pit and qualitative entries, **including `data_json`**, every 30 minutes. That's the whole history across all teams and seasons. The scheduler catches `OutOfMemoryError` and calls `System.gc()`, which suggests this has already caused trouble.
**Fix:** first find duplicate groups in SQL:
```sql
SELECT owner_team_number, program, event_key, match_key, target_team_number, is_prescout, submitted_by_user_id
FROM scouting_entries GROUP BY 1,2,3,4,5,6,7 HAVING COUNT(*) > 1
```
Then load only those groups. Better still, prevent duplicates when entries are written (an idempotency key from the client's offline outbox, enforced with a unique index) and remove the job. Limit it to entries changed since the last run (`updated_at > lastRun`).

### 5.2 Reminder scheduler: runs on every node and scans an unbounded table (high)
[ScoutingReminderScheduler.kt](src/main/kotlin/com/obsidianscout/scouting/ScoutingReminderScheduler.kt):
- It has no cluster lock, unlike dedup and sync. In a cluster, every node sends the same push and email, then each marks it sent.
- Every 60 s it loads **every PENDING assignment ever created** ([:61](src/main/kotlin/com/obsidianscout/scouting/ScoutingReminderScheduler.kt:61)), including past events.
- Emails are sent in a blocking call inside the loop.
- **Bug:** email links use a hardcoded `http://localhost:8080` ([:153](src/main/kotlin/com/obsidianscout/scouting/ScoutingReminderScheduler.kt:153)). Use `AppConfigLoader.load().getEffectiveSiteUrl()`, as `AuthRoutes` does.

**Fix:** guard the job with `ClusterLease.tryAcquire("reminders", 90)`. Join to `api_matches` and filter `scheduled_time BETWEEN now-15m AND now+2h` in SQL. Claim each reminder with a conditional `UPDATE … WHERE push_reminder_sent_at IS NULL` *before* sending, so two senders can't both win.

### 5.3 Quorum fallback mirror rewrites whole tables every 30 s (high when enabled)
When `quorum_fallback.enabled` is on, [QuorumFallbackStore.kt:371-403](src/main/kotlin/com/obsidianscout/db/QuorumFallbackStore.kt:371) reads these tables in full from Cockroach every 30 s:
- users (with profile pictures),
- all sessions,
- all config revisions,
- all reported errors (with stack traces),
- alliances, and more.

It then runs `deleteAll()` and re-inserts everything into SQLite. On every node.
**Fix:** sync incrementally by `updated_at > lastSync`, plus tombstones for deletes. Skip `reported_errors` and `config_revisions`, which reads during an outage don't need. Leave out `profile_picture`. Raise the interval to 2–5 min, since outages are rare.

### 5.4 Other background jobs (low)
- `SlackNotificationRelay` queries the DB every **2 s** on every node, even when no team uses Slack ([SlackNotificationRelay.kt:57](src/main/kotlin/com/obsidianscout/slack/SlackNotificationRelay.kt:57)). Skip the query when `allReady()` is empty (cache that for a minute).
- `ConfigService.syncFromDatabaseToLocalDisk` (every 30 s) reads every default config's JSON from the DB and every file from disk to compare them. Compare `updated_at` or a hash column instead.
- `CockroachOrchestrator` health probe runs every 3 s, and `ClusterSecretService` syncs every 30 s. These are fine, but they add to the fixed background load on small boxes.
- `System.gc()` appears in 13 OOM handlers. It doesn't fix OOM and causes full stop-the-world pauses. Remove those calls and rely on `-XX:+ExitOnOutOfMemoryError` (already in the launch scripts) plus the updater's restart.

---

## 6. Logging (cheap fix, noticeable gain)

- [App.kt:279-284](src/main/kotlin/com/obsidianscout/App.kt:279): the filter is `if (logging) true else path.startsWith("/api")`. With logging **off**, it still logs every API call, which is most traffic. Logging should probably be off for `/api` unless `server.logging` is on, or limited to non-GET and errors.
- [logback.xml](src/main/resources/logback.xml) writes to `ConsoleAppender` synchronously. On Windows consoles, and anywhere stdout goes to a pipe or service wrapper, each line blocks the request thread. An `AsyncAppender` would fix that, **but** the native build initialises `ch.qos.logback.` and `org.slf4j.` at build time ([build.gradle.kts:718](build.gradle.kts:718)). An appender that starts a worker thread would then likely put a running thread into the image heap, which fails the native-image build. Only add it together with moving logback/slf4j initialisation to run time, and verify that with a native build.
- `ServerLogService` keeps its own in-memory buffer. That's fine, but a logback appender could feed it so log lines aren't kept in two places.

---

## 7. Threads, memory and JVM

- **Thread counts are hardcoded** ([App.kt:186-188](src/main/kotlin/com/obsidianscout/App.kt:186)): 4 accept threads, 32 workers, 64 call threads. The low-RAM launch profile passes `-Dio.netty.eventLoopThreads=2` and `-XX:MaxDirectMemorySize=32m` ([build.gradle.kts:201](build.gradle.kts:201)), but the explicit settings override the former. With 32 pooled-allocator event loops and only 32 MB of direct memory, an `OutOfDirectMemoryError` under load is plausible. Base these counts on `availableProcessors()` (workers = cores, call group = cores × 2) and make them configurable.
- **Small memory leak:** `AuthService.sessionTouchCache` ([AuthService.kt:762](src/main/kotlin/com/obsidianscout/auth/AuthService.kt:762)) never removes entries. Each session ID ever used stays in it until restart. Remove entries in `SessionCleanupScheduler`, or use a bounded/expiring map.
- `RankingProjectionService.simCache` and `GamepadProfileService.profileCache` have no size limit either. Check them, or cap them.
- `-XX:+AlwaysPreTouch` commits the whole `-Xms` heap at startup. That's fine on servers but wasteful on low-RAM nodes. Drop it from the low-RAM profile.
- `firebase-admin` pulls in gRPC and Guava. If FCM is only used to send pushes, calling the FCM HTTP v1 endpoint with the Ktor client you already have would make the jar and heap noticeably smaller.

---

## 8. Robustness

- **Startup retry loop** ([App.kt:615-700](src/main/kotlin/com/obsidianscout/App.kt:615)): if any step after `DatabaseFactory.init` throws, the loop closes the DB and runs everything again every 10 s, including migrations and the full `EventTeamCanonical.repairAll()` / `MatchCanonical.repairOrphanedMatchKeys()`. Schedulers started earlier keep running against a closed pool. Split it into "DB up" (retried) and "services started" (once), and give the repair passes a done-flag stored in the DB.
- **Reads that write:** `ChatService.getMessages` → `ensureDefaultGroup` writes inside `readTransaction`. Under quorum loss, reads go to the SQLite mirror, so the write lands in the mirror and is lost. Keep read paths free of writes.
- **Background jobs that should run on one node only**: see 5.2. Also check `WidgetPushService.sweepSchedules` (one FCM refresh per node), `SessionCleanupScheduler` and `AutoBackupScheduler` (does every node take a backup?). Use the existing `ClusterLease` for all of them.
- **Graceful shutdown:** `embeddedServer(...).start(wait = true)` uses Ktor's default grace period. Set `shutdownGracePeriod` and `shutdownTimeout` explicitly so in-flight scouting submissions finish during an update restart.
- **Request size limits:** set a server-wide maximum body size (Netty `maxContentLength` / a per-route check), so a single huge upload can't use up the heap. This matters even more with global `DoubleReceive` (1.4).

---

## Suggested order of work

1. **Quick wins (about a day):** 1.1 (select only the columns needed), 1.3, 3.1, 3.2 cache headers, 4.3, 6 (logging), 5.2 lock + URL bug, sessionTouchCache leak, Cockroach pool sizing (4.1).
2. **Next (a few days):** avatar endpoint plus incremental chat (1.2), WebSocket invalidations replacing polling (2.1), dedup rewrite (5.1), DB dispatcher (4.1).
3. **When clustering matters:** incremental quorum mirror (5.3), peer router caching and streaming (1.5), startup split (8).

To confirm the gains, set up a simple before/after: open 20–30 browser tabs on the dashboard and chat pages, then watch `ServerTimingPlugin` output, Hikari pool metrics (`activeConnections`, `threadsAwaitingConnection`), and heap after GC.
