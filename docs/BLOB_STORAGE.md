# Blob storage (SeaweedFS)

Profile pictures, Event Info attachments and scouting photos are kept in a SeaweedFS object store instead of as
base64 inside database rows. Nothing needs to be installed by hand: on startup every server downloads the pinned
SeaweedFS release from GitHub (checked against its published MD5), runs it as child processes, and moves existing
base64 out of the database in the background.

MinIO was not used: its free edition stopped shipping binaries in October 2025 and its repository was archived in
2026, and it needs a fixed list of nodes, while this cluster lets nodes join on their own.

## Watching it after an update

Superadmins can open **Blob Storage** (`/blob-storage`, in the sidebar and under Server Management). It refreshes every
5 seconds and shows:

* overall and per-type progress (by size) of base64 still in the database versus what is in SeaweedFS,
* whether SeaweedFS is installed and reachable, which server runs the master, and each server's app version and
  volume server (so you can see the rollout reach every node),
* files waiting on local disk, single-copy objects, rows that couldn't be moved,
* the running or last maintenance pass, with a **Run now** button that starts one on the server you're viewing
  instead of waiting up to 10 minutes. Run now also sweeps match and qualitative entries, which are otherwise
  checked hourly.

Database counts are cached for 20 seconds, so they lag the activity line slightly.

### Firewall check

In a cluster, every server tests TCP connections to every other server's SeaweedFS ports (the master's 9333/19333
and the volume server's 9340/19340) and the page combines the results every 15 seconds:

* **Blocked**: the port is listening on that server, but no other server can connect. That's the server's firewall
  (or a Tailscale ACL). A red "Firewall changes needed" box appears at the top of the page and the status badge
  turns red. The box names each server and gives the command for its OS, with a copy button. The command opens all
  four ports, to Tailscale addresses (100.64.0.0/10) only.
* **Partly blocked**: some servers can connect and others can't, usually an ACL or a rule limited to some addresses.
* **Not in use**: nothing listens yet (the master ports on servers that aren't the master). The master can move to
  any server, so open these everywhere anyway; the "Firewall rules SeaweedFS needs on every server" section lists the
  commands.

With only one server online there is nothing to test between, so the page says so and shows the rules to open
before adding more servers. Single servers (not in a cluster) need no firewall changes.

## How it runs

| Mode | Master | Volume server |
| --- | --- | --- |
| CockroachDB cluster (`database_type: cockroach` + Google Sheet) | One node, chosen by the `seaweedfs-master` cluster lease. If it goes away, another node takes the lease within ~45 s and the volume servers re-register. | Every node, on its Tailscale IP |
| Single server (SQLite / Postgres) | `127.0.0.1` | `127.0.0.1` |

* Files live in `.seaweedfs/` next to the server binary (`master/`, `volume/`, logs, pid files). Updates replace the
  bundle but keep this folder.
* With two or more volume servers every object gets a second copy on another server (`001` replication). Objects
  written while there was only one server are copied again once a second server joins.
* If SeaweedFS can't run on a machine (macOS, Windows ARM) or is briefly down, writes go to `data/blobs/` and are moved
  into SeaweedFS once it is back. A node that can't run SeaweedFS still reads and writes through the other nodes.
* Leftover `weed` processes from a crash are stopped on the next start.
* A master that loses the database for longer than its lease (45 s) stops itself, so a network split can't leave two
  masters handing out file ids for the same volumes. Writes on that side go to local disk until it reconnects.

## What is stored where

| Data | Column now holds | Served by |
| --- | --- | --- |
| Profile pictures | `users.profile_picture = blob:<id>` | `GET /api/users/{id}/avatar` (same team or superadmin) |
| Event Info files | `event_info_files.data = blob:<id>` | `GET /api/event-info/files/{id}` (unchanged) |
| Pit / match / qualitative photos | `"/api/blobs/<id>"` inside `data_json` | `GET /api/blobs/{id}` |

`blob_objects` is the index: backend, SeaweedFS file id, size, SHA-256 and replication of every object. It is mirrored
into the quorum-fallback SQLite file, so photos keep loading during quorum loss.

Scouting photo URLs are capability URLs: the id is a random UUID that only appears inside entries the viewer could
already read (including public share links and alliance partners), so `/api/blobs/{id}` does not require a session,
matching the base64 it replaced. Only scouting-photo kinds are served there.

## Background work (every 10 minutes, one node at a time via the `blob-maintenance` lease)

* **Migration sweep**: rows still holding base64 (old app versions, backup/snapshot restores, alliance imports) are
  moved, a batch at a time, with compare-and-set updates. Pit entries every pass; match and qualitative entries hourly.
  Moved entries get a new `updated_at`, so delta-syncing clients drop their base64 copies.
* **Re-replication** of single-copy objects once there are two servers.
* **Garbage collection** (every 6 hours): objects older than 7 days that no row references are deleted. It reads the
  primary database only, and skips entirely during quorum loss.

## Config (`app-config.json` → `blob_storage`)

```json
"blob_storage": {
    "enabled": true,
    "seaweed_version": "4.48",
    "master_port": 9333,
    "volume_port": 9340,
    "local_directory": "data/blobs",
    "migrate_existing": true,
    "migration_batch_size": 50
}
```

SeaweedFS also listens on each port + 10000 for gRPC (19333, 19340). In a cluster, nodes must reach each other on
9333, 9340, 19333 and 19340 over Tailscale. The Blob Storage page tells you which servers need firewall changes and
gives the commands (see "Firewall check" above).

## Known limits

* Backups and snapshots still contain references, not bytes. Restoring one on the same cluster works; restoring it
  elsewhere, or after garbage collection removed an object, loses those images.
* A file written to local disk on one node during a SeaweedFS outage can only be read on that node until it is moved
  (at most one maintenance pass after SeaweedFS is back).
* SeaweedFS ports have no authentication; like CockroachDB in insecure mode they rely on binding to the Tailscale IP.
