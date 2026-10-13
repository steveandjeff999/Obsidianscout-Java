/*
 * Battery rotation for the drive team's Battery Tracker (battery-tracker.html) and Match Checklist.
 *
 * The server only stores what people enter (BatteryTrackerService.kt): the batteries (inventory doc) and,
 * per event, the skips / pins / batteries actually used (plan doc). The match-by-match schedule is worked
 * out here from those docs plus our match list, so skips, new batteries and new matches show up at once.
 * The app's lib/services/battery_rotation.dart follows the same rules; both are checked against
 * src/test/resources/battery-rotation-cases.json.
 *
 * Inventory doc: { batteries: { [id]: { name, type: "match"|"pit", order, retired, notes, createdAt } } }
 * Plan doc:      { matches: { [matchKey]: { skipped: { [id]: { by, at } }, pinned, used } }, startAt, autoMarkUsed }
 *
 * Rotation: match batteries that aren't retired, by `order`. Walking our matches in order, each match gets
 *   - the battery recorded as `used` (history never changes), else
 *   - its pinned battery (unless that one is skipped), else
 *   - the next battery in the rotation after the previous match's battery that isn't skipped for it.
 * The rotation then carries on from whichever battery the match got, so skipping B1 for Q5 gives Q5 B2
 * and Q6 B3.
 */
(function (root) {
    "use strict";

    function isPlainObject(v) {
        return v !== null && typeof v === "object" && !Array.isArray(v);
    }

    /** "B2" before "B10"; plain code-unit comparison otherwise so the app sorts the same way. */
    function naturalCompare(a, b) {
        const ca = String(a).toLowerCase().match(/\d+|\D+/g) || [];
        const cb = String(b).toLowerCase().match(/\d+|\D+/g) || [];
        for (let i = 0; i < Math.min(ca.length, cb.length); i++) {
            const x = ca[i], y = cb[i];
            const nx = /^\d/.test(x), ny = /^\d/.test(y);
            if (nx && ny) {
                const d = Number(x) - Number(y);
                if (d) return d < 0 ? -1 : 1;
            } else if (x !== y) {
                return x < y ? -1 : 1;
            }
        }
        return ca.length - cb.length;
    }

    function compareIds(a, b) {
        return a < b ? -1 : a > b ? 1 : 0;
    }

    /** Every battery in the inventory, sorted by `order` then name. */
    function batteriesOf(inventory) {
        const map = isPlainObject(inventory?.batteries) ? inventory.batteries : {};
        return Object.entries(map)
            .filter(([, b]) => isPlainObject(b))
            .map(([id, b]) => ({
                id,
                name: typeof b.name === "string" ? b.name : "",
                type: b.type === "pit" ? "pit" : "match",
                order: Number.isFinite(b.order) ? b.order : 0,
                retired: b.retired === true,
                notes: typeof b.notes === "string" ? b.notes : "",
                createdAt: Number.isFinite(b.createdAt) ? b.createdAt : 0
            }))
            .sort((a, b) => (a.order - b.order) || naturalCompare(a.name, b.name) || compareIds(a.id, b.id));
    }

    /** The batteries that take turns in matches. */
    function rotationOf(inventory) {
        return batteriesOf(inventory).filter((b) => b.type === "match" && !b.retired);
    }

    function entryOf(plan, matchKey) {
        const e = isPlainObject(plan?.matches) ? plan.matches[matchKey] : null;
        return isPlainObject(e) ? e : {};
    }

    function skippedOf(entry) {
        return isPlainObject(entry.skipped) ? Object.keys(entry.skipped) : [];
    }

    /**
     * The battery for each of `matchKeys` (our matches, already in play order).
     * Returns { [matchKey]: { batteryId, source: "used"|"pinned"|"auto"|"none", skipped: [ids] } }.
     */
    function computeSchedule(inventory, plan, matchKeys) {
        const all = new Map(batteriesOf(inventory).map((b) => [b.id, b]));
        const rotation = rotationOf(inventory);
        const index = new Map(rotation.map((b, i) => [b.id, i]));
        let pointer = index.has(plan?.startAt) ? index.get(plan.startAt) : 0;
        const result = {};

        (matchKeys || []).forEach((matchKey) => {
            const entry = entryOf(plan, matchKey);
            const skipped = skippedOf(entry);
            const skip = new Set(skipped);
            let batteryId = null;
            let source = "none";

            if (typeof entry.used === "string" && all.has(entry.used)) {
                batteryId = entry.used;
                source = "used";
            } else if (typeof entry.pinned === "string" && all.has(entry.pinned) && !all.get(entry.pinned).retired && !skip.has(entry.pinned)) {
                batteryId = entry.pinned;
                source = "pinned";
            } else {
                for (let step = 0; step < rotation.length; step++) {
                    const candidate = rotation[(pointer + step) % rotation.length];
                    if (!skip.has(candidate.id)) {
                        batteryId = candidate.id;
                        source = "auto";
                        break;
                    }
                }
            }

            if (batteryId !== null && index.has(batteryId)) pointer = (index.get(batteryId) + 1) % rotation.length;
            result[matchKey] = { batteryId, source, skipped };
        });
        return result;
    }

    // ------------------------------------------------------------------ plan edits (mutate the doc in place)

    function ensureEntry(plan, matchKey) {
        if (!isPlainObject(plan.matches)) plan.matches = {};
        if (!isPlainObject(plan.matches[matchKey])) plan.matches[matchKey] = {};
        return plan.matches[matchKey];
    }

    function tidy(plan, matchKey) {
        const e = plan.matches?.[matchKey];
        if (!e) return;
        if (isPlainObject(e.skipped) && !Object.keys(e.skipped).length) delete e.skipped;
        if (!Object.keys(e).length) delete plan.matches[matchKey];
    }

    /** Skips `batteryId` for one match; the next battery in the rotation takes its place. */
    function skip(plan, matchKey, batteryId, by) {
        const e = ensureEntry(plan, matchKey);
        if (!isPlainObject(e.skipped)) e.skipped = {};
        e.skipped[batteryId] = { by: by || "", at: Date.now() };
        if (e.pinned === batteryId) delete e.pinned;
        if (e.used === batteryId) delete e.used;
        return plan;
    }

    /** Clears every skip for a match. */
    function clearSkips(plan, matchKey) {
        const e = ensureEntry(plan, matchKey);
        delete e.skipped;
        tidy(plan, matchKey);
        return plan;
    }

    /** Pins a battery to a match (null/"" un-pins). */
    function pin(plan, matchKey, batteryId) {
        const e = ensureEntry(plan, matchKey);
        if (batteryId) {
            e.pinned = batteryId;
            if (isPlainObject(e.skipped)) delete e.skipped[batteryId];
        } else {
            delete e.pinned;
        }
        tidy(plan, matchKey);
        return plan;
    }

    /** Records which battery ran in a match, freezing it (null/"" un-freezes). */
    function markUsed(plan, matchKey, batteryId) {
        const e = ensureEntry(plan, matchKey);
        if (batteryId) e.used = batteryId;
        else delete e.used;
        tidy(plan, matchKey);
        return plan;
    }

    function usedOf(plan, matchKey) {
        const e = entryOf(plan, matchKey);
        return typeof e.used === "string" ? e.used : "";
    }

    /** Matches each battery has run at this event (from `used`), in match order. */
    function usageByBattery(plan, matchKeys) {
        const out = {};
        (matchKeys || []).forEach((k) => {
            const id = usedOf(plan, k);
            if (id) (out[id] = out[id] || []).push(k);
        });
        return out;
    }

    /** Whether matches completed on the checklist record their battery automatically (default on). */
    function autoMarkUsed(plan) {
        return plan?.autoMarkUsed !== false;
    }

    /**
     * Live-room settings for js/services/collab-rooms.js: the inventory is ("", "") and an event's plan is
     * (eventKey, ""). Both pages use the same draft store, so offline edits resume from either one.
     */
    function roomConfig(me) {
        const userKey = `${me.teamNumber}:${me.program || "FRC"}:${me.userId || me.id || me.username}`;
        return {
            draftKey: () => `obsidianscout:battery-drafts:${userKey}`,
            roomId: (eventKey) => (eventKey ? `event|${eventKey}` : "inventory"),
            endpoints: (eventKey) => {
                if (!eventKey) {
                    return {
                        url: "/api/battery-tracker/collab?inventory=1",
                        get: () => root.Obsidianscout.request("/api/battery-tracker/inventory"),
                        put: (dataJson) => root.Obsidianscout.request("/api/battery-tracker/inventory", { method: "PUT", json: { dataJson } })
                    };
                }
                const q = `eventKey=${encodeURIComponent(eventKey)}`;
                return {
                    url: `/api/battery-tracker/collab?${q}`,
                    get: () => root.Obsidianscout.request(`/api/battery-tracker/event?${q}`),
                    put: (dataJson) => root.Obsidianscout.request("/api/battery-tracker/event", { method: "PUT", json: { eventKey, dataJson } })
                };
            }
        };
    }

    /** Font Awesome Free 6 "battery-full" (CC BY 4.0) as inline SVG, colored by `currentColor`. */
    const BATTERY_ICON = '<svg class="battery-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 576 512" width="1.15em" height="1em" fill="currentColor" aria-hidden="true" focusable="false"><path d="M464 160c8.8 0 16 7.2 16 16V336c0 8.8-7.2 16-16 16H80c-8.8 0-16-7.2-16-16V176c0-8.8 7.2-16 16-16H464zM80 96C35.8 96 0 131.8 0 176V336c0 44.2 35.8 80 80 80H464c44.2 0 80-35.8 80-80V320c17.7 0 32-14.3 32-32V224c0-17.7-14.3-32-32-32V176c0-44.2-35.8-80-80-80H80zm368 96H96V320H448V192z"/></svg>';

    function displayName(battery) {
        return battery ? (battery.name.trim() || battery.id) : "";
    }

    const api = {
        batteriesOf, rotationOf, computeSchedule,
        skip, clearSkips, pin, markUsed, usedOf, usageByBattery, autoMarkUsed, displayName, roomConfig, BATTERY_ICON
    };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    root.ObsidianBattery = api;
})(typeof window !== "undefined" ? window : globalThis);
