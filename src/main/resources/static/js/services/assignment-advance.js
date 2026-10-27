/**
 * Service Assignment Advance Module - ObsidianScout
 * After a scout saves an entry, picks their next unfinished assignment so the form can
 * jump to it. Manual selection always stays available; this only pre-fills.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { request } from '../base/http.js';
import { faIcon } from '../utilities/fa-icons.js';
import { getPendingEntries } from './offline-sync.js';

const SETTING_KEY = "obsidianscout:auto_advance_assignments";
const LOCAL_DONE_KEY = "obsidianscout:assignments_local_done";

/** Assignment type -> offline queue type (see CACHE_CONFIGS). */
const QUEUE_TYPE = {
    MATCH: "match-scouting",
    PIT: "pit-scouting",
    QUALITATIVE: "qual-scouting"
};


let _assignments = [];
let _eventKey = null;

function norm(value) {
    return value == null ? "" : String(value).trim().toLowerCase();
}

function escapeHtml(value) {
    return String(value == null ? "" : value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
}

export function isAutoAdvanceEnabled() {
    return safeGetItem(SETTING_KEY) !== "0";
}

export function setAutoAdvanceEnabled(enabled) {
    safeSetItem(SETTING_KEY, enabled ? "1" : "0");
}

function readLocalDone() {
    try {
        const parsed = JSON.parse(safeGetItem(LOCAL_DONE_KEY) || "{}");
        return parsed && typeof parsed === "object" ? parsed : {};
    } catch (e) {
        return {};
    }
}

/**
 * Loads the signed-in scout's assignments for an event. Uses the normal request cache,
 * so it also works offline once the list has been fetched.
 */
export async function initAssignmentAdvance(eventKey) {
    _eventKey = eventKey || null;
    // Assignments belong to one event; without an event there is nothing to advance through.
    if (!eventKey) {
        _assignments = [];
        return _assignments;
    }
    let raw = [];
    try {
        raw = await request(`/api/assignments/my?eventKey=${encodeURIComponent(eventKey)}`);
    } catch (e) {
        raw = [];
    }
    const list = Array.isArray(raw) ? raw : ((raw && raw.assignments) || []);
    _assignments = list.filter(a => norm(a.eventKey) === norm(eventKey));
    return _assignments;
}

export function hasAssignments(type) {
    return _assignments.some(a => a.assignmentType === type);
}

function sameMatch(a, matchKey, matchNumber) {
    if (a.matchKey && matchKey) return norm(a.matchKey) === norm(matchKey);
    if (a.matchNumber != null && matchNumber != null) return Number(a.matchNumber) === Number(matchNumber);
    return false;
}

function matchesSelection(a, { teamNumber, matchKey, matchNumber, alliance } = {}) {
    if (a.assignmentType === "PIT") {
        return teamNumber != null && Number(a.targetTeamNumber) === Number(teamNumber);
    }
    if (!sameMatch(a, matchKey, matchNumber)) return false;
    // Alliance-wide qualitative assignments have no single team; only an alliance save completes them.
    if (a.targetTeamNumber == null) {
        if (teamNumber != null) return false;
        const color = a.allianceColor || a.targetAlliance;
        return !alliance || alliance === "both" || !color || norm(color) === norm(alliance);
    }
    return teamNumber == null || Number(a.targetTeamNumber) === Number(teamNumber);
}

/** Records that the assignment(s) for this selection are done on this device, even before the server knows. */
export function markAssignmentLocallyDone(type, selection) {
    const done = readLocalDone();
    let changed = false;
    for (const a of _assignments) {
        if (a.assignmentType === type && matchesSelection(a, selection) && !done[a.id]) {
            done[a.id] = new Date().toISOString();
            changed = true;
        }
    }
    if (changed) {
        // Keep the map small: drop ids that no longer exist in the loaded list for this event.
        const known = new Set(_assignments.map(a => a.id));
        for (const id of Object.keys(done)) {
            if (!known.has(id) && Date.now() - Date.parse(done[id]) > 3 * 24 * 3600 * 1000) delete done[id];
        }
        safeSetItem(LOCAL_DONE_KEY, JSON.stringify(done));
    }
}

function isDone(a, localDone, pending) {
    if (a.status === "COMPLETED" || a.status === "SKIPPED") return true;
    if (localDone[a.id]) return true;
    const queueType = QUEUE_TYPE[a.assignmentType];
    return pending.some(entry => {
        if (entry.type !== queueType) return false;
        const d = entry.data || {};
        return matchesSelection(a, {
            teamNumber: d.targetTeamNumber != null ? d.targetTeamNumber : null,
            matchKey: d.matchKey,
            matchNumber: d.matchNumber
        });
    });
}

/**
 * Event order: practice, then qualifications, then playoffs (same ranking as the server's
 * MatchCanonical.compLevelRank). Unknown levels sort after playoffs.
 */
const COMP_LEVEL_RANK = {
    practice: 0, prac: 0, pr: 0, prc: 0, p: 0,
    qm: 1, q: 1, qual: 1, quals: 1, qualification: 1,
    qf: 2, quarterfinal: 2, quarterfinals: 2,
    sf: 3, semifinal: 3, semifinals: 3,
    f: 4, final: 4, finals: 4,
    ef: 5, einstein: 5,
    playoff: 6, playoffs: 6
};

/** Parses "2026abc_qm12" / "2026abc_sf2m1" / "2026abc_practice3" into level, set and match numbers. */
function parseMatchKey(matchKey) {
    const suffix = norm(matchKey).split("_").pop();
    const m = /^([a-z]+?)(\d+)(?:m(\d+))?$/.exec(suffix || "");
    if (!m) return null;
    return m[3] != null
        ? { level: m[1], set: Number(m[2]), match: Number(m[3]) }
        : { level: m[1], set: 0, match: Number(m[2]) };
}

function levelRank(level) {
    const rank = COMP_LEVEL_RANK[norm(level)];
    return rank == null ? 7 : rank;
}

/** Sortable [levelRank, set, match] for an assignment or a match record. */
function orderKey(item) {
    const parsed = parseMatchKey(item.matchKey);
    const level = item.compLevel || (parsed && parsed.level) || "qm";
    const set = item.setNumber != null ? Number(item.setNumber) : (parsed ? parsed.set : 0);
    const match = item.matchNumber != null ? Number(item.matchNumber) : (parsed ? parsed.match : 0);
    return [levelRank(level), set || 0, match || 0];
}

function compareOrder(x, y) {
    for (let i = 0; i < 3; i++) {
        if (x[i] !== y[i]) return x[i] - y[i];
    }
    return 0;
}

function buildMatchIndex(matches) {
    const index = new Map();
    (matches || []).forEach((m) => {
        if (m && m.matchKey) index.set(norm(m.matchKey), m);
    });
    return index;
}

/** Order of an assignment, using the full match record when we have it. */
function assignmentOrder(a, matchIndex) {
    const match = a.matchKey ? matchIndex.get(norm(a.matchKey)) : null;
    return orderKey(match ? { ...a, ...match } : a);
}

function isPlayed(a, matchIndex) {
    const m = a.matchKey ? matchIndex.get(norm(a.matchKey)) : null;
    if (!m) return false;
    const scored = (s) => typeof s === "number" && s >= 0;
    return (m.actualTime != null && m.actualTime > 0) || (scored(m.redScore) && scored(m.blueScore));
}

/**
 * The next unfinished assignment of `type` after the selection that was just scouted, in event
 * order (practice, quals, playoffs). Keying off the scouted match (not "first unfinished") means a
 * skipped or swapped match doesn't send the scout backwards. Already-played matches are passed over
 * only when an unplayed one is still ahead; "no more assignments" only ever means none are left.
 * Returns { assignment, skippedPast, remaining } or null when the scout has no assignments of this type.
 */
export function getNextAssignment({ type, afterMatchKey = null, afterMatchNumber = null, afterTeam = null, matches = [], excludeIds = [], noWrap = false } = {}) {
    const ofType = _assignments.filter(a => a.assignmentType === type);
    if (!ofType.length) return null;

    const localDone = readLocalDone();
    const pending = getPendingEntries(_eventKey);
    const open = ofType.filter(a => !isDone(a, localDone, pending) && !excludeIds.includes(a.id));
    if (!open.length) return { assignment: null, skippedPast: 0, remaining: 0 };

    if (type === "PIT") {
        const sorted = open.slice().sort((x, y) => (Number(x.targetTeamNumber) || 0) - (Number(y.targetTeamNumber) || 0));
        const after = afterTeam != null ? sorted.find(a => Number(a.targetTeamNumber) > Number(afterTeam)) : null;
        if (!after && afterTeam != null && noWrap) return { assignment: null, skippedPast: 0, remaining: sorted.length };
        return { assignment: after || sorted[0], skippedPast: 0, remaining: sorted.length };
    }

    const matchIndex = buildMatchIndex(matches);
    const sorted = open.slice().sort((x, y) => compareOrder(assignmentOrder(x, matchIndex), assignmentOrder(y, matchIndex)));

    let candidates = sorted;
    if (afterMatchKey || afterMatchNumber != null) {
        const known = afterMatchKey ? matchIndex.get(norm(afterMatchKey)) : null;
        const from = orderKey(known || { matchKey: afterMatchKey, matchNumber: afterMatchNumber });
        const ahead = sorted.filter(a => compareOrder(assignmentOrder(a, matchIndex), from) >= 0);
        if (ahead.length) candidates = ahead;
        else if (noWrap) return { assignment: null, skippedPast: 0, remaining: sorted.length };
    }

    const firstUnplayed = candidates.findIndex(a => !isPlayed(a, matchIndex));
    if (firstUnplayed >= 0) {
        return { assignment: candidates[firstUnplayed], skippedPast: firstUnplayed, remaining: sorted.length };
    }
    // Everything ahead is already played (e.g. catching up from video): still go in order.
    return { assignment: candidates[0], skippedPast: 0, remaining: sorted.length };
}

function describeAssignment(a, matches) {
    if (!a) return "";
    const parts = [];
    if (a.assignmentType !== "PIT") {
        const hit = a.matchKey ? (matches || []).find(m => norm(m.matchKey) === norm(a.matchKey)) : null;
        const label = (hit && hit.label) || (a.compLevel ? `${String(a.compLevel).toUpperCase()} ${a.matchNumber || ""}` : (a.matchNumber ? `Match ${a.matchNumber}` : ""));
        if (label) parts.push(label.trim());
    }
    if (a.targetTeamNumber != null) parts.push(`Team ${a.targetTeamNumber}`);
    const color = a.allianceColor || a.targetAlliance;
    if (color) parts.push(`${color.charAt(0).toUpperCase()}${color.slice(1).toLowerCase()} alliance`);
    return parts.join(" · ");
}

/**
 * "Change" on the next-assignment chip: bring the selector into view, highlight it and open its
 * option list so the scout can pick a different team or match straight away.
 */
export function openSelectPicker(select) {
    if (!select) return;
    select.scrollIntoView({ behavior: "smooth", block: "center" });
    select.classList.remove("select-attention");
    void select.offsetWidth; // restart the highlight animation
    select.classList.add("select-attention");
    setTimeout(() => select.classList.remove("select-attention"), 1600);
    select.focus({ preventScroll: true });
    try {
        // Opens the native dropdown (needs the click's user activation; Chromium, Firefox, Safari 18+).
        if (typeof select.showPicker === "function") select.showPicker();
    } catch (_) { /* focus + highlight is the fallback */ }
}

/** The scout's unfinished assignment for exactly this selection, if there is one. */
export function getCurrentAssignment(type, selection = {}) {
    const localDone = readLocalDone();
    const pending = getPendingEntries(_eventKey);
    return _assignments.find(a =>
        a.assignmentType === type &&
        !isDone(a, localDone, pending) &&
        (selection.matchKey || selection.matchNumber != null || selection.teamNumber != null) &&
        matchesSelection(a, selection)) || null;
}

/**
 * The strip above a scouting form's selectors. It describes the form's current selection:
 * "Current assignment" when the scout is on one of theirs, plus what's "Up next" after it.
 * When the selection isn't one of their assignments it offers to jump to the next one.
 *
 * `selection` is { matchKey, matchNumber, teamNumber, alliance } read from the form.
 * `skippedPast` is shown after an auto-advance passed over already-played matches.
 * `onChange()` opens the selectors; `onGoTo(assignment)` jumps the form to an assignment.
 */
export function renderNextAssignmentChip(anchor, { type, selection = {}, matches = [], skippedPast = 0, onChange = null, onGoTo = null, onToggle = null } = {}) {
    if (!anchor || !anchor.parentNode) return;
    let chip = anchor.parentNode.querySelector(".next-assignment-chip");
    if (!hasAssignments(type)) {
        if (chip) chip.remove();
        return;
    }
    if (!chip) {
        chip = document.createElement("div");
        chip.className = "next-assignment-chip";
        anchor.after(chip);
        chip.addEventListener("click", (e) => {
            const btn = e.target.closest("[data-assignment-action]");
            if (!btn) return;
            if (btn.dataset.assignmentAction === "change" && typeof chip._onChange === "function") chip._onChange();
            if (btn.dataset.assignmentAction === "goto" && typeof chip._onGoTo === "function" && chip._goTo) chip._onGoTo(chip._goTo);
        });
        chip.addEventListener("change", (e) => {
            const toggle = e.target.closest("input[data-assignment-action='toggle']");
            if (!toggle) return;
            setAutoAdvanceEnabled(toggle.checked);
            if (typeof chip._onToggle === "function") chip._onToggle(toggle.checked);
        });
    }
    chip._onChange = onChange;
    chip._onGoTo = onGoTo;
    chip._onToggle = onToggle;
    chip._goTo = null;

    const current = getCurrentAssignment(type, selection);
    const upcoming = getNextAssignment({
        type,
        afterMatchKey: selection.matchKey || null,
        afterMatchNumber: selection.matchNumber != null ? selection.matchNumber : null,
        afterTeam: type === "PIT" ? selection.teamNumber : null,
        matches,
        excludeIds: current ? [current.id] : [],
        noWrap: true
    });
    const next = upcoming && upcoming.assignment;
    // Unfinished assignments earlier in the event than the current one.
    const earlierOpen = !next && upcoming ? upcoming.remaining : 0;
    const label = (a) => `<strong>${escapeHtml(describeAssignment(a, matches))}</strong>`;

    const lines = [];
    let action = "";
    if (current) {
        lines.push(`${faIcon("forward-step", 13)} Current assignment: ${label(current)}` +
            (skippedPast > 0 ? ` <span class="next-assignment-note">(skipped ${skippedPast} already played)</span>` : ""));
        lines.push(next
            ? `<span class="next-assignment-note">Up next: ${label(next)}</span>`
            : `<span class="next-assignment-note">Up next: nothing after this one${earlierOpen > 0
                ? `. You still have ${earlierOpen} earlier ${earlierOpen === 1 ? "assignment" : "assignments"} open.`
                : ", this is your last assignment."}</span>`);
        action = `<button type="button" class="btn ghost" data-assignment-action="change">Change</button>`;
    } else if (next || earlierOpen > 0) {
        const target = next || getNextAssignment({ type, matches }).assignment;
        lines.push(`${faIcon("forward-step", 13)} Your next assignment: ${label(target)}`);
        chip._goTo = target;
        action = onGoTo ? `<button type="button" class="btn ghost" data-assignment-action="goto">Go to it</button>` : "";
    } else {
        lines.push(`${faIcon("circle-check", 14)} No more assignments. Pick a team and match manually.`);
    }

    chip.innerHTML = `
        <span class="next-assignment-text">${lines.map(l => `<span class="next-assignment-line">${l}</span>`).join("")}</span>
        <span class="next-assignment-actions">
            ${action}
            <label class="next-assignment-toggle"><input type="checkbox" data-assignment-action="toggle" ${isAutoAdvanceEnabled() ? "checked" : ""}> Auto-advance</label>
        </span>`;
}
