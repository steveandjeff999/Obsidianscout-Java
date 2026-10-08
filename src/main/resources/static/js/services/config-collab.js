/*
 * Live (multi-editor) config editing for the web config editors.
 *
 * Engine: the same operation rules as the server's ConfigSyncEngine.kt and the app's
 * config_sync_engine.dart (src/test/resources/config-collab-vectors.json checks all three).
 * Fields are addressed by per-room keys, so edits to different fields — or different properties of one
 * field — made at the same time merge instead of overwriting each other.
 *
 * Session: keeps an editor in sync over a WebSocket. Local edits apply immediately and are sent in
 * batches; edits from others arrive as ops. While the socket is down edits keep buffering locally and
 * are merged in on reconnect. If the server can't be reached, saveViaRest() merges with the latest saved
 * config and saves it the way the editors always have.
 */
(function (root) {
    'use strict';

    const FIELDS = 'fields';

    // ── JSON helpers ─────────────────────────────────────────────────────────

    function isPlainObject(v) {
        return v !== null && typeof v === 'object' && !Array.isArray(v);
    }

    function clone(v) {
        return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
    }

    function deepEqual(a, b) {
        if (a === b) return true;
        if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
            return typeof a === 'number' && typeof b === 'number' ? a === b : false;
        }
        if (Array.isArray(a) !== Array.isArray(b)) return false;
        if (Array.isArray(a)) {
            if (a.length !== b.length) return false;
            for (let i = 0; i < a.length; i++) if (!deepEqual(a[i], b[i])) return false;
            return true;
        }
        const ka = Object.keys(a);
        if (ka.length !== Object.keys(b).length) return false;
        for (const k of ka) {
            if (!Object.prototype.hasOwnProperty.call(b, k) || !deepEqual(a[k], b[k])) return false;
        }
        return true;
    }

    function canonical(v) {
        if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
        if (isPlainObject(v)) {
            return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
        }
        return JSON.stringify(v === undefined ? null : v);
    }

    function fieldsOf(doc) {
        return doc && Array.isArray(doc[FIELDS]) ? doc[FIELDS] : [];
    }

    function ensureFields(doc) {
        const d = isPlainObject(doc) ? doc : {};
        return Array.isArray(d[FIELDS]) ? d : Object.assign({}, d, { [FIELDS]: [] });
    }

    function keyed(doc, newKey) {
        const d = ensureFields(clone(doc));
        return { doc: d, keys: d[FIELDS].map(() => newKey()) };
    }

    // ── Applying ─────────────────────────────────────────────────────────────

    function positionAfter(keys, after) {
        if (after === null || after === undefined) return 0;
        const idx = keys.indexOf(after);
        return idx >= 0 ? idx + 1 : keys.length;
    }

    function setIn(obj, path, value) {
        const out = Object.assign({}, obj);
        const head = path[0];
        if (path.length === 1) {
            out[head] = clone(value);
            return out;
        }
        const child = isPlainObject(obj[head]) ? obj[head] : {};
        out[head] = setIn(child, path.slice(1), value);
        return out;
    }

    function unsetIn(obj, path) {
        const head = path[0];
        if (path.length === 1) {
            if (!Object.prototype.hasOwnProperty.call(obj, head)) return obj;
            const out = Object.assign({}, obj);
            delete out[head];
            return out;
        }
        if (!isPlainObject(obj[head])) return obj;
        const out = Object.assign({}, obj);
        out[head] = unsetIn(obj[head], path.slice(1));
        return out;
    }

    function withFields(doc, fields) {
        return Object.assign({}, doc, { [FIELDS]: fields });
    }

    function applyOp(state, op) {
        if (!isPlainObject(op)) return state;
        const keys = state.keys;
        const fields = fieldsOf(state.doc);
        switch (op.op) {
            case 'set':
            case 'unset': {
                const unset = op.op === 'unset';
                const path = Array.isArray(op.path) ? op.path.filter((p) => typeof p === 'string') : [];
                if (!path.length) return state;
                if (path[0] !== FIELDS) {
                    return { doc: unset ? unsetIn(state.doc, path) : setIn(state.doc, path, op.value === undefined ? null : op.value), keys };
                }
                if (path.length < 2) return state;
                const idx = keys.indexOf(path[1]);
                if (idx < 0) return state;
                const next = fields.slice();
                if (path.length === 2) {
                    if (unset) return state;
                    next[idx] = clone(op.value === undefined ? null : op.value);
                } else {
                    if (!isPlainObject(next[idx])) return state;
                    const rest = path.slice(2);
                    next[idx] = unset ? unsetIn(next[idx], rest) : setIn(next[idx], rest, op.value === undefined ? null : op.value);
                }
                return { doc: withFields(state.doc, next), keys };
            }
            case 'insert': {
                if (typeof op.key !== 'string' || keys.includes(op.key)) return state;
                const pos = positionAfter(keys, typeof op.after === 'string' ? op.after : null);
                const nk = keys.slice();
                const nf = fields.slice();
                nk.splice(pos, 0, op.key);
                nf.splice(pos, 0, clone(op.value === undefined ? null : op.value));
                return { doc: withFields(state.doc, nf), keys: nk };
            }
            case 'delete': {
                const idx = keys.indexOf(op.key);
                if (idx < 0) return state;
                const nk = keys.slice();
                const nf = fields.slice();
                nk.splice(idx, 1);
                nf.splice(idx, 1);
                return { doc: withFields(state.doc, nf), keys: nk };
            }
            case 'move': {
                const idx = keys.indexOf(op.key);
                const after = typeof op.after === 'string' ? op.after : null;
                if (idx < 0 || after === op.key) return state;
                const nk = keys.slice();
                const nf = fields.slice();
                nk.splice(idx, 1);
                const [value] = nf.splice(idx, 1);
                const pos = positionAfter(nk, after);
                nk.splice(pos, 0, op.key);
                nf.splice(pos, 0, value);
                return { doc: withFields(state.doc, nf), keys: nk };
            }
            default:
                return state;
        }
    }

    function applyOps(state, ops) {
        let s = state;
        for (const op of ops || []) s = applyOp(s, op);
        return s;
    }

    // ── Diffing ──────────────────────────────────────────────────────────────

    function fieldId(f) {
        return isPlainObject(f) && typeof f.id === 'string' && f.id.length ? f.id : null;
    }

    function matchFields(base, target) {
        const match = new Array(target.length).fill(-1);
        const used = new Array(base.length).fill(false);
        const byContent = new Map();
        base.forEach((f, i) => {
            const c = canonical(f);
            if (!byContent.has(c)) byContent.set(c, []);
            byContent.get(c).push(i);
        });
        for (let j = 0; j < target.length; j++) {
            const queue = byContent.get(canonical(target[j]));
            while (queue && queue.length) {
                const i = queue.shift();
                if (!used[i]) { match[j] = i; used[i] = true; break; }
            }
        }
        for (let j = 0; j < target.length; j++) {
            if (match[j] >= 0) continue;
            const id = fieldId(target[j]);
            if (id === null) continue;
            for (let i = 0; i < base.length; i++) {
                if (!used[i] && fieldId(base[i]) === id) { match[j] = i; used[i] = true; break; }
            }
        }
        for (let j = 0; j < target.length; j++) {
            if (match[j] >= 0 || j >= base.length || used[j]) continue;
            if (isPlainObject(base[j]) && isPlainObject(target[j])) { match[j] = j; used[j] = true; }
        }
        return match;
    }

    function longestIncreasing(seq) {
        if (!seq.length) return [];
        const tails = [];
        const prev = new Array(seq.length).fill(-1);
        for (let i = 0; i < seq.length; i++) {
            let lo = 0;
            let hi = tails.length;
            while (lo < hi) {
                const mid = (lo + hi) >>> 1;
                if (seq[tails[mid]] < seq[i]) lo = mid + 1; else hi = mid;
            }
            if (lo > 0) prev[i] = tails[lo - 1];
            if (lo === tails.length) tails.push(i); else tails[lo] = i;
        }
        const out = [];
        let k = tails[tails.length - 1];
        while (k >= 0) { out.push(k); k = prev[k]; }
        return out.reverse();
    }

    function diffValue(path, a, b, out) {
        if (b === undefined) {
            if (a !== undefined) out.push({ op: 'unset', path });
        } else if (a === undefined) {
            out.push({ op: 'set', path, value: clone(b) });
        } else if (deepEqual(a, b)) {
            // unchanged
        } else if (isPlainObject(a) && isPlainObject(b)) {
            const keys = Object.keys(a);
            for (const k of Object.keys(b)) if (!Object.prototype.hasOwnProperty.call(a, k)) keys.push(k);
            for (const k of keys) diffValue(path.concat([k]), a[k], b[k], out);
        } else {
            out.push({ op: 'set', path, value: clone(b) });
        }
    }

    /** Ops turning keyed `base` into plain `target`, plus target's field keys. */
    function diff(base, target, newKey) {
        const baseFields = fieldsOf(base.doc);
        const newFields = fieldsOf(target);
        const match = matchFields(baseFields, newFields);
        const newKeys = [];
        const inserted = [];
        for (let j = 0; j < newFields.length; j++) {
            if (match[j] >= 0) { newKeys.push(base.keys[match[j]]); inserted.push(false); }
            else { newKeys.push(newKey()); inserted.push(true); }
        }

        const ops = [];
        const usedBase = new Array(baseFields.length).fill(false);
        match.forEach((i) => { if (i >= 0) usedBase[i] = true; });
        for (let i = 0; i < baseFields.length; i++) if (!usedBase[i]) ops.push({ op: 'delete', key: base.keys[i] });

        const matchedNewIdx = [];
        for (let j = 0; j < newFields.length; j++) if (match[j] >= 0) matchedNewIdx.push(j);
        const stable = new Set(longestIncreasing(matchedNewIdx.map((j) => match[j])).map((k) => matchedNewIdx[k]));
        for (let j = 0; j < newFields.length; j++) {
            const after = j === 0 ? null : newKeys[j - 1];
            if (inserted[j]) ops.push({ op: 'insert', key: newKeys[j], after, value: clone(newFields[j]) });
            else if (!stable.has(j)) ops.push({ op: 'move', key: newKeys[j], after });
        }

        const topKeys = Object.keys(base.doc);
        for (const k of Object.keys(target)) if (!Object.prototype.hasOwnProperty.call(base.doc, k)) topKeys.push(k);
        for (const k of topKeys) {
            if (k === FIELDS) continue;
            diffValue([k], base.doc[k], target[k], ops);
        }
        for (let j = 0; j < newFields.length; j++) {
            if (match[j] >= 0) diffValue([FIELDS, newKeys[j]], baseFields[match[j]], newFields[j], ops);
        }
        return { ops, keys: newKeys };
    }

    /** Plays `ops` (written against `base`) onto plain `theirs`; keys stay in base's key space. */
    function rebase(base, theirs, ops, newKey) {
        const t = ensureFields(clone(theirs));
        return applyOps({ doc: t, keys: diff(base, t, newKey).keys }, ops);
    }

    const engine = { applyOp, applyOps, diff, rebase, keyed, deepEqual, canonical, matchFields, ensureFields, clone };

    // ── Session ──────────────────────────────────────────────────────────────

    const PROTOCOL = 1;
    const FLUSH_MS = 60;
    const CONNECT_TIMEOUT_MS = 6000;
    const PING_MS = 20000;
    const SILENCE_MS = 45000;
    const MAX_BATCH = 400;

    function randomId(len) {
        const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
        const bytes = new Uint8Array(len);
        (root.crypto || {}).getRandomValues ? root.crypto.getRandomValues(bytes) : bytes.forEach((_, i) => { bytes[i] = Math.floor(Math.random() * 256); });
        return Array.from(bytes, (b) => chars[b % chars.length]).join('');
    }

    /**
     * options:
     *  url              WebSocket URL (relative paths are resolved against the page)
     *  projectField(f)  editor's form of a stored field, or null to hide it
     *  projectRest(doc) editor's form of the non-field properties (an object without `fields`)
     *  onView(view, info)   the editor must show `view` ({doc, keys}); info.source: remote|rebase|reject|server
     *  onStatus(status, session)   status: connecting | live | reconnecting | offline | readonly
     *  onPresence(editors), onNotice(level, message)
     */
    class ConfigCollabSession {
        constructor(options) {
            this.options = Object.assign({
                projectField: (f) => f,
                projectRest: (doc) => { const r = Object.assign({}, doc); delete r[FIELDS]; return r; },
                onView: () => {},
                onStatus: () => {},
                onPresence: () => {},
                onNotice: () => {}
            }, options || {});
            this.sid = randomId(16);
            this.keyCounter = 0;
            this.seqCounter = 0;
            this.newKey = () => `${this.sid}.${(this.keyCounter++).toString(36)}`;
            this.status = 'offline';
            this.canEdit = true;
            this.epoch = null;
            this.version = 0;
            this.confirmed = keyed({}, this.newKey);
            this.local = this.confirmed;
            this.inflight = [];
            this.unsent = [];
            this.lastView = this.project(this.local);
            this.editors = [];
            this.ws = null;
            this.closedByUser = false;
            this.retry = 0;
            this.lastMessageAt = 0;
            this.pendingCommits = new Map();
            this.focusKey = null;
            this._flushTimer = null;
            this._reconnectTimer = null;
            this._pingTimer = null;
            this._connectTimer = null;
            this._onOnline = () => { if (!this.ws && !this.closedByUser) this.connect(); };
            if (root.addEventListener) root.addEventListener('online', this._onOnline);
        }

        // Editor-facing state ------------------------------------------------

        get isLive() { return this.status === 'live' || this.status === 'readonly'; }
        get hasUnsavedChanges() { return this.inflight.length > 0 || this.unsent.length > 0; }

        project(state) {
            const fields = [];
            const keys = [];
            fieldsOf(state.doc).forEach((f, i) => {
                const p = this.options.projectField(clone(f));
                if (p !== null && p !== undefined) { fields.push(p); keys.push(state.keys[i]); }
            });
            const rest = this.options.projectRest(clone(state.doc)) || {};
            delete rest[FIELDS];
            return { doc: Object.assign(rest, { [FIELDS]: fields }), keys };
        }

        /** Replaces everything with a config loaded the old way (REST); returns the view to show. */
        load(doc) {
            this.confirmed = keyed(doc || {}, this.newKey);
            this.local = this.confirmed;
            this.inflight = [];
            this.unsent = [];
            this.epoch = null;
            this.lastView = this.project(this.local);
            return this.lastView;
        }

        /** Field key shown at `index` in the current view. */
        keyAt(index) { return this.lastView.keys[index]; }

        /**
         * Records an edit. `next` is the editor's whole config; `base` is the view it was derived from
         * (defaults to the last view handed to the editor). Returns `next` keyed.
         */
        commit(next, base) {
            const from = base || this.lastView;
            const target = ensureFields(clone(next));
            const result = diff(from, target, this.newKey);
            if (result.ops.length) {
                if (!this.canEdit && this.isLive) {
                    this.options.onNotice('warning', 'You can view this config but not edit it.');
                    this._emitView({ source: 'reject' });
                    return { doc: target, keys: result.keys };
                }
                this.local = applyOps(this.local, result.ops);
                this.unsent.push(...result.ops);
                this._scheduleFlush();
            }
            const view = this.project(this.local);
            this.lastView = view;
            if (!deepEqual(view.doc, target)) this.options.onView(view, { source: 'normalize' });
            return { doc: target, keys: result.keys };
        }

        setFocus(key) {
            const k = key || null;
            if (k === this.focusKey) return;
            this.focusKey = k;
            this._send({ type: 'focus', key: k });
        }

        /** "Save version": checkpoints the live config (revision history, migration check). */
        saveVersion() {
            if (!this.isLive) return Promise.reject(new Error('Not connected'));
            this._flush();
            const req = randomId(10);
            return new Promise((resolve, reject) => {
                const timer = setTimeout(() => {
                    this.pendingCommits.delete(req);
                    reject(new Error('Timed out waiting for the server'));
                }, 15000);
                this.pendingCommits.set(req, { resolve, reject, timer });
                this._send({ type: 'commit', req });
            });
        }

        /**
         * Fallback save when the live connection is down: merges unsaved edits into the latest saved
         * config (from fetchCurrent) and stores it with put(doc). Resolves with put's result.
         */
        async saveViaRest(fetchCurrent, put) {
            const theirs = ensureFields(clone(await fetchCurrent()) || {});
            const base = applyOps(this.confirmed, this.inflight.flatMap((b) => b.ops));
            const pending = this.inflight.flatMap((b) => b.ops).concat(this.unsent);
            const merged = rebase(base, theirs, pending, this.newKey);
            const result = await put(clone(merged.doc));
            this.confirmed = merged;
            this.local = merged;
            this.inflight = [];
            this.unsent = [];
            this.epoch = null;
            this._emitView({ source: 'rebase' });
            return result;
        }

        // Connection ---------------------------------------------------------

        connect() {
            if (this.ws || this.closedByUser || !this.options.url) return;
            clearTimeout(this._reconnectTimer);
            // Once retries have failed the editor stays "offline" until a connection actually succeeds.
            if (this.status !== 'offline' || this.retry === 0) this._setStatus(this.epoch ? 'reconnecting' : 'connecting');
            let url = this.options.url;
            if (!/^wss?:/i.test(url) && root.location) {
                const proto = root.location.protocol === 'https:' ? 'wss:' : 'ws:';
                url = `${proto}//${root.location.host}${url.startsWith('/') ? '' : '/'}${url}`;
            }
            url += (url.includes('?') ? '&' : '?') + 'sid=' + encodeURIComponent(this.sid);
            let ws;
            try {
                ws = new WebSocket(url);
            } catch (e) {
                this._scheduleReconnect();
                return;
            }
            this.ws = ws;
            this._connectTimer = setTimeout(() => { if (this.ws === ws && !this.isLive) ws.close(); }, CONNECT_TIMEOUT_MS);
            ws.onmessage = (event) => {
                if (this.ws !== ws) return;
                this.lastMessageAt = Date.now();
                let msg;
                try { msg = JSON.parse(event.data); } catch (e) { return; }
                this._handle(msg);
            };
            ws.onclose = () => {
                if (this.ws !== ws) return;
                this.ws = null;
                clearTimeout(this._connectTimer);
                clearInterval(this._pingTimer);
                if (this.closedByUser) return;
                for (const [, p] of this.pendingCommits) { clearTimeout(p.timer); p.reject(new Error('Connection lost')); }
                this.pendingCommits.clear();
                this._scheduleReconnect();
            };
            ws.onerror = () => {};
        }

        close() {
            this.closedByUser = true;
            clearTimeout(this._reconnectTimer);
            clearTimeout(this._flushTimer);
            clearInterval(this._pingTimer);
            clearTimeout(this._connectTimer);
            if (root.removeEventListener) root.removeEventListener('online', this._onOnline);
            if (this.ws) { const ws = this.ws; this.ws = null; try { ws.close(); } catch (e) { /* ignore */ } }
        }

        _scheduleReconnect() {
            this.retry += 1;
            // After a couple of failed attempts, tell the editor it is working offline (Save falls back to REST).
            this._setStatus(this.retry >= 2 || !this.epoch ? 'offline' : 'reconnecting');
            const delay = Math.min(30000, 1000 * Math.pow(2, Math.min(this.retry - 1, 5))) * (0.75 + Math.random() * 0.5);
            this._reconnectTimer = setTimeout(() => this.connect(), delay);
        }

        _setStatus(status) {
            if (this.status === status) return;
            this.status = status;
            this.options.onStatus(status, this);
        }

        _send(msg) {
            if (!this.ws || this.ws.readyState !== 1 || !this.isLive) return false;
            try { this.ws.send(JSON.stringify(msg)); return true; } catch (e) { return false; }
        }

        _scheduleFlush() {
            if (this._flushTimer) return;
            this._flushTimer = setTimeout(() => { this._flushTimer = null; this._flush(); }, FLUSH_MS);
        }

        _flush() {
            clearTimeout(this._flushTimer);
            this._flushTimer = null;
            if (!this.unsent.length || !this.isLive || !this.canEdit) return;
            while (this.unsent.length) {
                const batch = { seq: ++this.seqCounter, ops: this.unsent.splice(0, MAX_BATCH) };
                this.inflight.push(batch);
                this._send({ type: 'ops', seq: batch.seq, ops: batch.ops });
            }
        }

        _emitView(info) {
            this.lastView = this.project(this.local);
            this.options.onView(this.lastView, info || {});
        }

        _handle(msg) {
            switch (msg.type) {
                case 'init': return this._onInit(msg);
                case 'ops': return this._onOps(msg);
                case 'ack': {
                    this.inflight = this.inflight.filter((b) => b.seq > msg.seq);
                    return;
                }
                case 'reject': {
                    this.inflight = this.inflight.filter((b) => b.seq !== msg.seq);
                    this.local = applyOps(this.confirmed, this.inflight.flatMap((b) => b.ops).concat(this.unsent));
                    this._emitView({ source: 'reject' });
                    this.options.onNotice('error', msg.reason || 'A change was not accepted');
                    return;
                }
                case 'presence':
                    this.editors = Array.isArray(msg.editors) ? msg.editors : [];
                    this.options.onPresence(this.editors, this);
                    return;
                case 'committed': {
                    const p = this.pendingCommits.get(msg.req);
                    if (!p) return;
                    this.pendingCommits.delete(msg.req);
                    clearTimeout(p.timer);
                    if (msg.ok) p.resolve(msg); else p.reject(new Error(msg.error || 'Save failed'));
                    return;
                }
                case 'notice':
                    this.options.onNotice(msg.level || 'info', msg.message || '');
                    return;
                case 'error':
                    this.options.onNotice('error', msg.message || 'Live editing error');
                    return;
                default:
                    return;
            }
        }

        _onInit(msg) {
            if (msg.proto !== PROTOCOL) {
                this.options.onNotice('warning', 'Live editing is unavailable until this page is refreshed.');
                this.close();
                this._setStatus('offline');
                return;
            }
            clearTimeout(this._connectTimer);
            const server = { doc: ensureFields(msg.doc || {}), keys: Array.isArray(msg.keys) ? msg.keys.slice() : [] };
            if (server.keys.length !== server.doc[FIELDS].length) server.keys = server.doc[FIELDS].map(() => this.newKey());

            if (msg.epoch === this.epoch) {
                // Same room as before the drop: resend only what the server hasn't applied.
                this.inflight = this.inflight.filter((b) => b.seq > (msg.ackSeq || 0));
            } else {
                // New room (first connect, or the server restarted it): merge our unsaved edits into its state.
                const base = applyOps(this.confirmed, this.inflight.flatMap((b) => b.ops));
                const pending = this.inflight.flatMap((b) => b.ops).concat(this.unsent);
                const mapped = diff(base, server.doc, this.newKey).keys;
                const keyMap = new Map();
                mapped.forEach((k, j) => keyMap.set(k, server.keys[j]));
                const tr = (k) => (typeof k === 'string' && keyMap.has(k) ? keyMap.get(k) : k);
                const translated = pending.map((op) => {
                    const o = clone(op);
                    if (o.key !== undefined) o.key = tr(o.key);
                    if (o.after !== undefined) o.after = tr(o.after);
                    if (Array.isArray(o.path) && o.path[0] === FIELDS && o.path.length > 1) o.path[1] = tr(o.path[1]);
                    return o;
                });
                this.inflight = [];
                this.unsent = translated;
            }
            this.epoch = msg.epoch;
            this.version = msg.version || 0;
            this.confirmed = server;
            this.canEdit = msg.canEdit !== false;
            this.retry = 0;
            this.editors = Array.isArray(msg.editors) ? msg.editors : [];
            if (!this.canEdit) { this.inflight = []; this.unsent = []; }
            this.local = applyOps(this.confirmed, this.inflight.flatMap((b) => b.ops).concat(this.unsent));
            this._setStatus(this.canEdit ? 'live' : 'readonly');
            this._emitView({ source: 'rebase' });
            this.options.onPresence(this.editors, this);

            for (const batch of this.inflight) this._send({ type: 'ops', seq: batch.seq, ops: batch.ops });
            this._flush();
            if (this.focusKey) this._send({ type: 'focus', key: this.focusKey });
            clearInterval(this._pingTimer);
            this._pingTimer = setInterval(() => {
                if (Date.now() - this.lastMessageAt > SILENCE_MS && this.ws) { try { this.ws.close(); } catch (e) { /* ignore */ } return; }
                this._send({ type: 'ping' });
            }, PING_MS);
        }

        _onOps(msg) {
            if (msg.version !== this.version + 1) {
                // Missed something: reconnect and resync from the server's state.
                if (this.ws) { try { this.ws.close(); } catch (e) { /* ignore */ } }
                return;
            }
            this.version = msg.version;
            this.confirmed = applyOps(this.confirmed, msg.ops || []);
            if (msg.sid === this.sid && this.inflight.length && this.inflight[0].seq === msg.seq) {
                this.inflight.shift();
                return; // already showing it
            }
            this.local = applyOps(this.confirmed, this.inflight.flatMap((b) => b.ops).concat(this.unsent));
            const before = this.lastView;
            this.lastView = this.project(this.local);
            if (!deepEqual(before.doc, this.lastView.doc) || before.keys.join() !== this.lastView.keys.join()) {
                this.options.onView(this.lastView, { source: msg.sid === 'server' ? 'server' : 'remote', user: msg.user });
            }
        }
    }

    // ── DOM helpers for the web editors ──────────────────────────────────────

    /**
     * Re-renders a list of field cards without losing the user's place: the focused input (found again
     * by its card's data-collab-key and its position in the card), its selection and the scroll position.
     */
    function preserveFocus(container, render) {
        const doc = root.document;
        const active = doc && doc.activeElement;
        let saved = null;
        if (container && active && container.contains(active)) {
            const card = active.closest('[data-collab-key]');
            if (card) {
                const inputs = Array.from(card.querySelectorAll('input, select, textarea'));
                saved = {
                    key: card.getAttribute('data-collab-key'),
                    index: inputs.indexOf(active),
                    start: typeof active.selectionStart === 'number' ? active.selectionStart : null,
                    end: typeof active.selectionEnd === 'number' ? active.selectionEnd : null,
                    value: active.value
                };
            }
        }
        const scrollY = root.scrollY;
        render();
        if (root.scrollTo && Math.abs(root.scrollY - scrollY) > 1) root.scrollTo(root.scrollX, scrollY);
        if (!saved || saved.index < 0) return;
        const card = Array.from(container.querySelectorAll('[data-collab-key]')).find((c) => c.getAttribute('data-collab-key') === saved.key);
        if (!card) return;
        const el = card.querySelectorAll('input, select, textarea')[saved.index];
        if (!el) return;
        // Editors store trimmed text; keep the space the user just typed.
        if (typeof saved.value === 'string' && el.value !== saved.value && el.value === saved.value.trim()) el.value = saved.value;
        try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
        if (saved.start !== null && typeof el.setSelectionRange === 'function') {
            const len = (el.value || '').length;
            const s = Math.min(len, saved.start);
            const e = Math.max(s, Math.min(len, saved.end));
            try { el.setSelectionRange(s, e); } catch (err) { /* not a text input */ }
        }
    }

    /** Marks cards that another editor is working in. */
    function decorateCards(container, editors, mySid) {
        if (!container) return;
        container.querySelectorAll('.collab-focus-badges').forEach((n) => n.remove());
        const byKey = new Map();
        (editors || []).forEach((ed) => {
            if (!ed || !ed.focus || ed.sid === mySid) return;
            if (!byKey.has(ed.focus)) byKey.set(ed.focus, []);
            byKey.get(ed.focus).push(ed);
        });
        container.querySelectorAll('[data-collab-key]').forEach((card) => {
            const list = byKey.get(card.getAttribute('data-collab-key'));
            card.classList.toggle('collab-focused', !!list);
            if (!list) return;
            const wrap = root.document.createElement('div');
            wrap.className = 'collab-focus-badges';
            list.forEach((ed) => {
                const b = root.document.createElement('span');
                b.className = 'collab-focus-badge';
                b.textContent = `${ed.username} is editing`;
                b.title = `${ed.username} (Team ${ed.teamNumber})`;
                wrap.appendChild(b);
            });
            card.insertBefore(wrap, card.firstChild);
        });
    }

    /** Status pill + avatars. Returns {update(status), setEditors(editors, mySid), element}. */
    function createStatusBar(host) {
        injectStyles();
        const bar = root.document.createElement('div');
        bar.className = 'collab-status-bar';
        bar.innerHTML = '<span class="collab-status-dot"></span><span class="collab-status-text"></span><div class="collab-status-editors"></div>';
        if (host) host.appendChild(bar);
        const text = bar.querySelector('.collab-status-text');
        const avatars = bar.querySelector('.collab-status-editors');
        const labels = {
            connecting: 'Connecting to live editing…',
            live: 'Live — changes save automatically',
            readonly: 'Live — view only',
            reconnecting: 'Reconnecting… your changes are kept',
            offline: 'Offline — your changes are kept; click Save to save them'
        };
        return {
            element: bar,
            update(status) {
                bar.dataset.status = status;
                text.textContent = labels[status] || status;
            },
            setEditors(editors, mySid) {
                avatars.innerHTML = '';
                (editors || []).forEach((ed) => {
                    const a = root.document.createElement('span');
                    a.className = 'collab-avatar-chip' + (ed.sid === mySid ? ' me' : '');
                    a.textContent = String(ed.username || '?').substring(0, 2).toUpperCase();
                    a.title = `${ed.username} (Team ${ed.teamNumber})${ed.sid === mySid ? ' — you' : ''}${ed.canEdit === false ? ' — viewing' : ''}`;
                    avatars.appendChild(a);
                });
            }
        };
    }

    let stylesInjected = false;
    function injectStyles() {
        if (stylesInjected || !root.document) return;
        stylesInjected = true;
        const style = root.document.createElement('style');
        style.textContent = `
            .collab-status-bar { display:flex; align-items:center; gap:10px; padding:8px 14px; margin:10px 0; border-radius:var(--radius-sm, 8px);
                border:1px solid var(--border, rgba(127,127,127,.25)); background:var(--surface, transparent); font-size:13px; color:var(--muted, inherit); }
            .collab-status-dot { width:8px; height:8px; border-radius:50%; background:#95a5a6; flex:none; }
            .collab-status-bar[data-status="live"] .collab-status-dot,
            .collab-status-bar[data-status="readonly"] .collab-status-dot { background:#2ecc71; box-shadow:0 0 0 3px rgba(46,204,113,.2); }
            .collab-status-bar[data-status="connecting"] .collab-status-dot,
            .collab-status-bar[data-status="reconnecting"] .collab-status-dot { background:#f39c12; }
            .collab-status-bar[data-status="offline"] .collab-status-dot { background:#e74c3c; }
            .collab-status-editors { display:flex; gap:4px; margin-left:auto; }
            .collab-avatar-chip { width:24px; height:24px; border-radius:50%; display:inline-flex; align-items:center; justify-content:center;
                font-size:10px; font-weight:700; color:#fff; background:var(--accent, #6a00ff); border:2px solid var(--surface, #fff); }
            .collab-avatar-chip.me { opacity:.6; }
            [data-collab-key].collab-focused { box-shadow:0 0 0 2px rgba(243,156,18,.55); }
            .collab-focus-badges { display:flex; gap:4px; margin:-4px 0 6px; }
            .collab-focus-badge { font-size:11px; font-weight:600; padding:2px 8px; border-radius:999px; background:rgba(243,156,18,.15); color:#d68910; }
            @media (max-width: 600px) { .collab-status-bar { flex-wrap:wrap; } }
        `;
        root.document.head.appendChild(style);
    }

    const api = { engine, ConfigCollabSession, preserveFocus, decorateCards, createStatusBar };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    root.ObsidianscoutConfigCollab = api;
})(typeof window !== 'undefined' ? window : globalThis);
