/*
 * Live document rooms for the drive-team tools (Match Checklist, Battery Tracker).
 *
 * A room is one live document (a ConfigCollabSession from config-collab.js) that several parts of a page can
 * share. Edits that can't reach the server are kept on this device (localStorage drafts) and saved once it
 * is reachable again: over the live connection when it comes back, or over plain HTTP when only that works.
 *
 *   const rooms = ObsidianscoutCollabRooms.create({
 *       draftKey: () => "obsidianscout:...",            // where this device keeps unsaved edits
 *       roomId: (eventKey, docKey) => "...",
 *       endpoints: (eventKey, docKey) => ({ url, get, put }), // socket URL + REST fallback
 *       keepOpen: (room) => false,                        // rooms that stay open even when unused
 *       onChange(room), onStatus(room, status), onPresence(room, editors)
 *   });
 *   const room = await rooms.acquire(eventKey, docKey);  ...  rooms.release(room);
 *   rooms.edit(room, (doc) => { ... });
 *   setInterval(rooms.tick, 1000);
 */
(function (root) {
    "use strict";

    const REST_RETRY_MS = 20000;

    function clone(v) {
        return JSON.parse(JSON.stringify(v ?? null));
    }

    function parseJson(text, fallback) {
        try {
            const v = JSON.parse(text || "");
            return v && typeof v === "object" && !Array.isArray(v) ? v : fallback;
        } catch (_) {
            return fallback;
        }
    }

    function create(options) {
        const opts = Object.assign({
            keepOpen: () => false,
            onChange: () => {},
            onStatus: () => {},
            onPresence: () => {},
            onNotice: (level, message) => {
                if (message) root.Obsidianscout?.showToast(message, level === "error" ? "error" : level === "success" ? "success" : "info");
            }
        }, options);
        const rooms = new Map(); // room id -> { id, eventKey, docKey, session, uses, ready, lastRestTry, endpoints }

        function readDrafts() {
            return parseJson(root.Obsidianscout.safeGetItem(opts.draftKey()), {});
        }

        function writeDrafts(drafts) {
            if (Object.keys(drafts).length) root.Obsidianscout.safeSetItem(opts.draftKey(), JSON.stringify(drafts));
            else root.Obsidianscout.safeRemoveItem(opts.draftKey());
        }

        /** Keeps a room's unsaved edits on this device until the server has them. */
        function persistDraft(room) {
            const drafts = readDrafts();
            const session = room.session;
            if (session.hasUnsavedChanges) {
                drafts[room.id] = {
                    eventKey: room.eventKey,
                    docKey: room.docKey,
                    base: clone(session.confirmed?.doc || {}),
                    local: clone(session.lastView.doc),
                    at: Date.now()
                };
            } else if (drafts[room.id]) {
                delete drafts[room.id];
            } else {
                return;
            }
            writeDrafts(drafts);
        }

        /** Opens (or reuses) a live document. Resumes any draft left on this device. */
        async function acquire(eventKey, docKey) {
            const id = opts.roomId(eventKey, docKey);
            let room = rooms.get(id);
            if (room) {
                room.uses++;
                await room.ready;
                return room;
            }
            const endpoints = opts.endpoints(eventKey, docKey);
            room = { id, eventKey, docKey, uses: 1, lastRestTry: 0, endpoints };
            rooms.set(id, room);
            room.session = new root.ObsidianscoutConfigCollab.ConfigCollabSession({
                url: endpoints.url,
                onView: () => { persistDraft(room); opts.onChange(room); },
                onStatus: (status) => opts.onStatus(room, status),
                onPresence: (editors) => opts.onPresence(room, editors),
                onNotice: opts.onNotice
            });
            room.ready = (async () => {
                const draft = readDrafts()[id];
                if (draft) {
                    // Unsaved edits from an earlier visit: replay them; they merge into the server's copy on connect.
                    room.session.load(draft.base || {});
                    room.session.commit(draft.local || {});
                } else {
                    let doc = {};
                    try {
                        doc = parseJson((await endpoints.get())?.dataJson, {});
                    } catch (_) { /* offline: start empty, the server copy arrives on connect */ }
                    room.session.load(doc);
                }
                room.session.connect();
            })();
            await room.ready;
            return room;
        }

        /** Stops showing a room. It stays open in the background until any unsaved edits reach the server. */
        function release(room) {
            if (!room) return;
            room.uses = Math.max(0, room.uses - 1);
            closeIdle(room);
        }

        function closeIdle(room) {
            if (room.uses > 0 || room.session.hasUnsavedChanges || opts.keepOpen(room)) return;
            room.session.close();
            rooms.delete(room.id);
        }

        /** Edits a live document and shares it. */
        function edit(room, mutate) {
            if (!room) return;
            const next = clone(room.session.lastView.doc);
            mutate(next);
            room.session.commit(next);
            persistDraft(room);
        }

        async function saveViaRest(room) {
            try {
                await room.session.saveViaRest(
                    async () => parseJson((await room.endpoints.get())?.dataJson, {}),
                    (doc) => room.endpoints.put(JSON.stringify(doc))
                );
                persistDraft(room);
            } catch (_) { /* still unreachable; retried later */ }
        }

        /**
         * Housekeeping (call every second): keeps drafts current, saves over plain HTTP when the live
         * connection can't be made but the network is up, and closes background rooms once saved.
         */
        function tick() {
            const now = Date.now();
            for (const room of [...rooms.values()]) {
                const s = room.session;
                persistDraft(room);
                if (s.hasUnsavedChanges && !s.isLive && navigator.onLine && now - room.lastRestTry > REST_RETRY_MS) {
                    room.lastRestTry = now;
                    saveViaRest(room);
                }
                closeIdle(room);
            }
        }

        /** Opens any drafts left from an earlier visit so they save as soon as the server is reachable. */
        async function resumeDrafts() {
            for (const [id, draft] of Object.entries(readDrafts())) {
                if (rooms.has(id)) continue;
                const room = await acquire(draft.eventKey || "", draft.docKey ?? draft.matchKey ?? "");
                release(room);
            }
        }

        function persistAll() {
            for (const room of rooms.values()) persistDraft(room);
        }

        /** Rooms with edits that can't reach the server right now. */
        function pendingOffline() {
            return [...rooms.values()].filter((r) => r.session.hasUnsavedChanges && !r.session.isLive);
        }

        function hasUnsaved() {
            return [...rooms.values()].some((r) => r.session.hasUnsavedChanges);
        }

        return { rooms, acquire, release, edit, tick, resumeDrafts, persistDraft, persistAll, pendingOffline, hasUnsaved };
    }

    const api = { create, parseJson, clone };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    root.ObsidianscoutCollabRooms = api;
})(typeof window !== "undefined" ? window : globalThis);
