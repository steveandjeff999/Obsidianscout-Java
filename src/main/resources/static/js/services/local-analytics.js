/**
 * Service Local-Analytics Module - ObsidianScout
 * Computes `/api/analytics`, `/api/analytics/compare` and `/api/custom-analytics/dataset` on this device from
 * stored entries, so analytics keep working offline or on a slow link. A line-for-line port of AnalyticsService,
 * RecencyWeighting and AnalyticsReportService.generateDataset on the server; keep them in step.
 */

// ── Value readers (kotlinx JsonPrimitive semantics) ─────────────────────────

function isPrimitive(v) {
    return v === null || typeof v === "string" || typeof v === "number" || typeof v === "boolean";
}

/** JsonPrimitive.content: JSON null reads as "null". */
function content(v) {
    return v === null ? "null" : String(v);
}

/** Kotlin/JVM String.toDoubleOrNull(): surrounding whitespace is ignored (as Java's parser does), empty is null. */
function toDoubleOrNull(raw) {
    if (typeof raw !== "string") return null;
    const text = raw.trim();
    if (text === "") return null;
    if (text === "NaN") return NaN;
    if (!/^[+-]?(\d+\.?\d*([eE][+-]?\d+)?|\.\d+([eE][+-]?\d+)?|Infinity)$/.test(text)) return null;
    return Number(text);
}

function readNumber(v) {
    if (v === undefined || !isPrimitive(v)) return null;
    return toDoubleOrNull(content(v));
}

function readBoolean(v) {
    if (v === undefined || !isPrimitive(v)) return null;
    const c = content(v).toLowerCase();
    return c === "true" ? true : c === "false" ? false : null;
}

function readLabel(v) {
    if (v === undefined || !isPrimitive(v)) return null;
    return content(v);
}

/** Config labels may arrive translated (`{ en: "Auto" }`); the server works with the plain string. */
export function labelText(label) {
    if (label && typeof label === "object") {
        if (typeof label.en === "string") return label.en;
        const first = Object.values(label).find((x) => typeof x === "string");
        return first || "";
    }
    return label === undefined || label === null ? "" : String(label);
}

function ifBlank(text, fallback) {
    return text && text.trim() !== "" ? text : fallback;
}

/** Kotlin Double.toString() for the values configs use (2.0 -> "2.0", 0.5 -> "0.5"). */
function kotlinDouble(n) {
    return Number.isInteger(n) ? n.toFixed(1) : String(n);
}

const options = (field) => (Array.isArray(field.options) ? field.options : []);
const fieldType = (field) => String(field.type || "").toLowerCase();

// ── Scoring (AnalyticsService) ──────────────────────────────────────────────

export function fieldScore(field, element) {
    if (element === undefined) return 0;
    const pointsPer = typeof field.pointsPer === "number" ? field.pointsPer : null;
    switch (fieldType(field)) {
        case "counter":
        case "number":
        case "rating": {
            const value = readNumber(element);
            return value === null ? 0 : (pointsPer || 0) * value;
        }
        case "checkbox":
            return readBoolean(element) ? (pointsPer || 0) : 0;
        case "select": {
            const label = readLabel(element);
            if (label === null) return 0;
            const option = options(field).find((o) => o.value === label || labelText(o.label) === label);
            return option && typeof option.points === "number" ? option.points : 0;
        }
        default:
            return 0;
    }
}

export function scoreEntry(config, entry) {
    const data = entry.data || {};
    return (config.fields || []).reduce((sum, field) => sum + fieldScore(field, data[field.id]), 0);
}

function collectNumbers(widget, entries) {
    if (!widget.fieldId) return [];
    const out = [];
    for (const entry of entries) {
        const el = (entry.data || {})[widget.fieldId];
        const n = readNumber(el);
        if (n !== null) { out.push(n); continue; }
        const b = readBoolean(el);
        if (b !== null) out.push(b ? 1 : 0);
    }
    return out;
}

const sumOf = (values) => values.reduce((a, b) => a + b, 0);

function barSeries(config, widget, entries) {
    if (!widget.fieldId) return [];
    const field = (config.fields || []).find((f) => f.id === widget.fieldId);
    const counts = new Map();
    if (field) {
        for (const opt of options(field)) counts.set(ifBlank(labelText(opt.label), opt.value), 0);
    }
    for (const entry of entries) {
        const raw = readLabel((entry.data || {})[widget.fieldId]);
        if (raw === null) continue;
        const opt = field ? options(field).find((o) => o.value === raw || labelText(o.label) === raw) : null;
        const label = opt ? ifBlank(labelText(opt.label), opt.value) : raw;
        counts.set(label, (counts.get(label) || 0) + 1);
    }
    const hasOptions = !!field && options(field).length > 0;
    return Array.from(counts.entries())
        .filter(([, value]) => hasOptions || value > 0)
        .sort((a, b) => b[1] - a[1])
        .map(([label, value]) => ({ label, value }));
}

export function generateDefaultWidgets(config) {
    const widgets = [
        { id: "entryCount", title: "Entries Collected", type: "count", fieldId: null },
        { id: "avgScore", title: "Average Score", type: "score_avg", fieldId: null },
        { id: "totalScore", title: "Total Points", type: "score_total", fieldId: null }
    ];
    for (const field of config.fields || []) {
        const type = fieldType(field);
        if (["section", "header", "label", "spacer"].includes(type)) continue;
        const title = ifBlank(labelText(field.label), field.id);
        if (["counter", "number", "rating"].includes(type)) {
            widgets.push({ id: `avg_${field.id}`, title: `Avg ${title}`, type: "avg", fieldId: field.id });
            if (type === "counter" || (field.pointsPer !== undefined && field.pointsPer !== null)) {
                widgets.push({ id: `sum_${field.id}`, title: `Total ${title}`, type: "sum", fieldId: field.id });
            }
        } else if (type === "checkbox") {
            widgets.push({ id: `rate_${field.id}`, title: `${title} Rate`, type: "rate", fieldId: field.id });
        } else if (type === "select" || type === "radio") {
            widgets.push({ id: `bar_${field.id}`, title: `${title} Breakdown`, type: "bar", fieldId: field.id });
        }
    }
    return widgets;
}

export function generate(config, entries) {
    const list = Array.isArray(config.analytics) && config.analytics.length ? config.analytics : generateDefaultWidgets(config);
    const widgets = list.map((widget) => {
        const type = String(widget.type || "").toLowerCase();
        const result = (value, series = []) => ({ id: widget.id, title: widget.title, type, value, series });
        const values = () => collectNumbers(widget, entries);
        switch (type) {
            case "count": return result(entries.length);
            case "avg": { const v = values(); return result(v.length ? sumOf(v) / v.length : 0); }
            case "sum": return result(sumOf(values()));
            case "min": { const v = values(); return result(v.length ? Math.min(...v) : 0); }
            case "max": { const v = values(); return result(v.length ? Math.max(...v) : 0); }
            case "rate": case "pct": case "percentage": {
                if (!entries.length) return result(0);
                const v = values();
                return result(v.length ? (sumOf(v) / entries.length) * 100 : 0);
            }
            case "bar": return result(null, barSeries(config, widget, entries));
            case "score_total": return result(sumOf(entries.map((e) => scoreEntry(config, e))));
            case "score_avg": return result(entries.length ? sumOf(entries.map((e) => scoreEntry(config, e))) / entries.length : 0);
            default: return result(0);
        }
    });
    return { generatedAt: new Date().toISOString(), widgets };
}

export function mergePrescoutEntries(currentEventEntries, prescoutEntries, currentEventKey, forceUsePrescout) {
    const countByTeam = new Map();
    for (const e of currentEventEntries) {
        if (e.eventKey === currentEventKey && !e.isPrescout) {
            countByTeam.set(e.targetTeamNumber, (countByTeam.get(e.targetTeamNumber) || 0) + 1);
        }
    }
    const prescoutByTeam = new Map();
    for (const e of prescoutEntries) {
        if (!e.isPrescout) continue;
        if (!prescoutByTeam.has(e.targetTeamNumber)) prescoutByTeam.set(e.targetTeamNumber, []);
        prescoutByTeam.get(e.targetTeamNumber).push(e);
    }
    const result = currentEventEntries.slice();
    for (const [team, list] of prescoutByTeam) {
        if (forceUsePrescout || (countByTeam.get(team) || 0) < 3) result.push(...list);
    }
    return result;
}

/** `/api/analytics`: `regular` and `prescout` are the resolved (one per match+team) lists the server would use. */
export function buildAnalytics({ config, regular, prescout, currentEventKey, forcePrescout }) {
    return generate(config, mergePrescoutEntries(regular, prescout, currentEventKey, forcePrescout));
}

// ── Match keys (MatchCanonical) ─────────────────────────────────────────────

export function normalizeCompLevel(raw) {
    const c = String(raw || "").trim().toLowerCase().replace(/ /g, "").replace(/_/g, "");
    if (!c) return "";
    if (["qm", "qual", "qualification", "quals", "q", "m", "match", "matches"].includes(c)) return "qm";
    if (["qf", "quarterfinal", "quarterfinals"].includes(c)) return "qf";
    if (["sf", "semifinal", "semifinals"].includes(c)) return "sf";
    if (["f", "final", "finals"].includes(c)) return "f";
    if (["ef", "einstein"].includes(c)) return "ef";
    if (["practice", "prac", "pr", "prc", "pm"].includes(c)) return "practice";
    if (c === "playoff" || c === "playoffs") return "playoff";
    return c.slice(0, 16);
}

export function compLevelRank(compLevel) {
    const ranks = { practice: 0, qm: 1, qf: 2, sf: 3, f: 4, ef: 5, playoff: 6 };
    const r = ranks[normalizeCompLevel(compLevel)];
    return r === undefined ? 7 : r;
}

function playoffSetToCompLevel(setNumber) {
    return setNumber === 1 ? "qf" : setNumber === 2 ? "sf" : setNumber === 3 ? "f" : "playoff";
}

function toMatchKey(id) {
    return id.compLevel === "qm" || id.compLevel === "practice"
        ? `${id.eventKey}_${id.compLevel}${id.matchNumber}`
        : `${id.eventKey}_${id.compLevel}${id.setNumber}m${id.matchNumber}`;
}

export function parseTbaMatchKey(matchKey) {
    const key = String(matchKey);
    const cut = key.lastIndexOf("_");
    const eventKey = cut >= 0 ? key.slice(0, cut).toLowerCase() : "";
    if (!eventKey.trim()) return null;
    const m = /^([a-z]+)(\d+)(?:m(\d+))?$/i.exec(key.slice(cut + 1));
    if (!m) return null;
    const compLevel = normalizeCompLevel(m[1]);
    return m[3] !== undefined && m[3] !== ""
        ? { eventKey, compLevel, setNumber: parseInt(m[2], 10) || 1, matchNumber: parseInt(m[3], 10) || 1 }
        : { eventKey, compLevel, setNumber: 1, matchNumber: parseInt(m[2], 10) || 1 };
}

export function parseLegacyManualKey(matchKey) {
    const m = /^(.+)_([a-z]+)_s(\d+)_m(\d+)$/i.exec(String(matchKey).trim());
    if (!m) return null;
    const setNumber = Math.max(parseInt(m[3], 10) || 1, 1);
    let compLevel = normalizeCompLevel(m[2]);
    if (compLevel === "playoff") compLevel = playoffSetToCompLevel(setNumber);
    return { eventKey: m[1].toLowerCase(), compLevel, setNumber, matchNumber: Math.max(parseInt(m[4], 10) || 1, 1) };
}

// ── Recency weighting (RecencyWeighting) ────────────────────────────────────

function normalizeRecency(raw) {
    const s = raw || {};
    const num = (v, d) => (typeof v === "number" && Number.isFinite(v) ? v : d);
    const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);
    return {
        enabled: !!s.enabled,
        mode: s.mode === "lastN" ? "lastN" : "exponential",
        halfLifeMatches: clamp(num(s.halfLifeMatches, 4), 1, 20),
        windowSize: clamp(Math.trunc(num(s.windowSize, 6)), 1, 30),
        minMatches: clamp(Math.trunc(num(s.minMatches, 4)), 1, 15),
        prescoutWeight: clamp(num(s.prescoutWeight, 0.5), 0, 1)
    };
}

function matchId(entry, index) {
    const event = String(entry.eventKey || "").toLowerCase();
    const key = entry.matchKey ? String(entry.matchKey).trim().toLowerCase() : "";
    if (key) {
        const legacy = parseLegacyManualKey(key);
        return `${event}|${legacy ? toMatchKey(legacy) : key}`;
    }
    if (entry.matchNumber !== null && entry.matchNumber !== undefined) return `${event}|#${entry.matchNumber}`;
    return `entry|${entry.id ? entry.id : String(index)}`;
}

function distinctEventMatches(entries) {
    const ids = new Set();
    entries.forEach((e, i) => { if (!e.isPrescout) ids.add(matchId(e, i)); });
    return ids.size;
}

function parseMillis(value) {
    const t = Date.parse(value || "");
    return Number.isNaN(t) ? 0 : t;
}

export function recencyWeights(entries, rawSettings) {
    const settings = normalizeRecency(rawSettings);
    if (!(settings.enabled && distinctEventMatches(entries) >= settings.minMatches)) {
        return entries.map(() => 1);
    }
    const createdAt = entries.map((e) => parseMillis(e.createdAt));
    const eventFirstSeen = new Map();
    entries.forEach((e, i) => {
        const k = String(e.eventKey || "").toLowerCase();
        eventFirstSeen.set(k, Math.min(eventFirstSeen.has(k) ? eventFirstSeen.get(k) : Infinity, createdAt[i]));
    });
    const ids = entries.map((e, i) => matchId(e, i));
    const groups = new Map();
    ids.forEach((id, i) => { if (!groups.has(id)) groups.set(id, []); groups.get(id).push(i); });

    const orders = Array.from(groups.entries()).map(([id, idx]) => {
        const entry = entries[idx[0]];
        const key = entry.matchKey && String(entry.matchKey).trim() ? entry.matchKey : null;
        const identity = key ? (parseLegacyManualKey(key) || parseTbaMatchKey(key)) : null;
        const hasNumber = entry.matchNumber !== null && entry.matchNumber !== undefined;
        return {
            id,
            isPrescout: !!entry.isPrescout,
            eventFirstSeen: eventFirstSeen.get(String(entry.eventKey || "").toLowerCase()) || 0,
            compRank: identity ? compLevelRank(identity.compLevel) : (hasNumber ? 1 : -1),
            setNumber: identity ? identity.setNumber : 1,
            matchNumber: identity ? identity.matchNumber : (hasNumber ? entry.matchNumber : 0),
            createdAt: Math.min(...idx.map((i) => createdAt[i]))
        };
    });
    const cmp = (a, b) =>
        (Number(!a.isPrescout) - Number(!b.isPrescout)) ||
        (a.eventFirstSeen - b.eventFirstSeen) ||
        (a.compRank - b.compRank) ||
        (a.setNumber - b.setNumber) ||
        (a.matchNumber - b.matchNumber) ||
        (a.createdAt - b.createdAt);
    const newestFirst = orders.slice().sort(cmp).reverse();
    const ageById = new Map(newestFirst.map((o, age) => [o.id, age]));

    return entries.map((e, i) => {
        const age = ageById.get(ids[i]);
        const base = settings.mode === "lastN"
            ? (age < settings.windowSize ? 1 : 0)
            : Math.pow(0.5, age / settings.halfLifeMatches);
        return e.isPrescout ? base * settings.prescoutWeight : base;
    });
}

// ── Team comparison (AnalyticsService.compareTeams) ─────────────────────────

/** `/api/analytics/compare`. `entries` is every stored match entry (prescout included, not de-duplicated). */
export function buildComparison({ config, entries, teams, teamNumbers, eventKey: eventKeyParam, recencyBias }) {
    const eventKey = eventKeyParam && eventKeyParam.trim() ? eventKeyParam.trim() : null;
    const fields = config.fields || [];
    const teamInfo = new Map();
    for (const t of teams || []) {
        if (teamNumbers.includes(t.teamNumber) && (!eventKey || String(t.eventKey || "").toLowerCase() === eventKey.toLowerCase())) {
            teamInfo.set(t.teamNumber, t);
        }
    }

    const phaseOf = (f) => (f.phase ? String(f.phase).toLowerCase() : "");
    const autoFields = fields.filter((f) => phaseOf(f).includes("auto"));
    const endgameFields = fields.filter((f) => phaseOf(f).includes("end"));
    const teleopFields = fields.filter((f) => !phaseOf(f).includes("auto") && !phaseOf(f).includes("end"));
    const skip = ["label", "divider", "section", "header"];

    const teamsOut = {};
    for (const teamNum of teamNumbers) {
        const all = entries.filter((e) => e.targetTeamNumber === teamNum);
        const eventEntries = eventKey
            ? all.filter((e) => e.eventKey && e.eventKey.toLowerCase() === eventKey.toLowerCase() && !e.isPrescout)
            : all.filter((e) => !e.isPrescout);
        const prescout = all.filter((e) => e.isPrescout);
        const list = (!eventKey || eventEntries.length < 3) ? eventEntries.concat(prescout) : eventEntries;
        const weights = recencyWeights(list, recencyBias);
        const weightedMean = (value) => {
            let sum = 0, total = 0;
            list.forEach((e, i) => {
                const v = value(e);
                if (v === null || v === undefined) return;
                sum += v * weights[i];
                total += weights[i];
            });
            return total > 0 ? sum / total : null;
        };
        const phaseScore = (group) => (e) => group.reduce((s, f) => s + fieldScore(f, (e.data || {})[f.id]), 0);

        const avgTotal = weightedMean((e) => scoreEntry(config, e)) ?? 0;
        const maxTotal = list.length ? Math.max(...list.map((e) => scoreEntry(config, e))) : 0;
        const avgAuto = weightedMean(phaseScore(autoFields)) ?? 0;
        const avgEndgame = weightedMean(phaseScore(endgameFields)) ?? 0;
        const avgTeleop = weightedMean(phaseScore(teleopFields)) ?? 0;

        const metrics = {
            avg_total: avgTotal,
            avg_auto: avgAuto,
            avg_teleop: avgTeleop,
            avg_endgame: avgEndgame,
            max_points: maxTotal,
            matches_scouted: list.length
        };
        const metricSeries = {};
        for (const field of fields) {
            const type = fieldType(field);
            if (skip.includes(type)) continue;
            if (["counter", "number", "slider", "rating"].includes(type)) {
                metrics[field.id] = weightedMean((e) => readNumber((e.data || {})[field.id])) ?? 0;
            } else if (["checkbox", "toggle", "boolean"].includes(type)) {
                const share = weightedMean((e) => {
                    const b = readBoolean((e.data || {})[field.id]);
                    return b === null ? null : (b ? 1 : 0);
                });
                metrics[field.id] = (share ?? 0) * 100;
            } else if (type === "select" || type === "radio") {
                const counts = new Map();
                for (const e of list) {
                    const raw = readLabel((e.data || {})[field.id]);
                    if (raw === null) continue;
                    const opt = options(field).find((o) => o.value === raw || labelText(o.label) === raw);
                    const label = opt ? labelText(opt.label) : raw;
                    counts.set(label, (counts.get(label) || 0) + 1);
                }
                metricSeries[field.id] = Array.from(counts.entries())
                    .sort((a, b) => b[1] - a[1])
                    .map(([label, value]) => ({ label, value }));
            }
        }

        const row = teamInfo.get(teamNum);
        const num = (v) => (typeof v === "number" ? v : null);
        const epa = row ? num(row.epa) : null;
        const exp = row ? num(row.exp) : null;
        const opr = row ? num(row.opr) : null;
        if (epa !== null) metrics.epa = epa;
        if (exp !== null) metrics.exp = exp;
        if (opr !== null) metrics.opr = opr;

        teamsOut[String(teamNum)] = {
            teamNumber: teamNum,
            nickname: row ? (row.nickname ?? row.name ?? null) : null,
            matchesScouted: list.length,
            epa, exp, opr,
            avgTotalPoints: avgTotal,
            avgAutoPoints: avgAuto,
            avgTeleopPoints: avgTeleop,
            avgEndgamePoints: avgEndgame,
            maxPoints: maxTotal,
            metrics,
            metricSeries,
            widgets: generate(config, list).widgets
        };
    }

    const def = (id, title, phase = "overview", type = "number", unit = null, pointsPer = null) => ({ id, title, phase, type, unit, pointsPer });
    const metricDefinitions = [
        def("avg_total", "Average Total Points", "overview", "number", "pts", 1.0),
        def("avg_auto", "Auto Points (Avg)", "overview", "number", "pts"),
        def("avg_teleop", "Teleop Points (Avg)", "overview", "number", "pts"),
        def("avg_endgame", "Endgame Points (Avg)", "overview", "number", "pts"),
        def("max_points", "Max Match Points", "overview", "number", "pts"),
        def("matches_scouted", "Matches Scouted", "overview", "number", "matches"),
        def("epa", "Statbotics EPA", "overview", "number", "EPA"),
        def("exp", "Match 13 xP", "overview", "number", "xP"),
        def("opr", "TBA OPR", "overview", "number", "OPR")
    ];
    for (const field of fields) {
        const type = fieldType(field);
        if (skip.includes(type)) continue;
        const phase = field.phase ? String(field.phase).toLowerCase() : "teleop";
        const displayType = ["checkbox", "toggle", "boolean"].includes(type) ? "percentage" : (type === "select" || type === "radio") ? "bar" : "number";
        const pointsPer = typeof field.pointsPer === "number" ? field.pointsPer : null;
        const unit = displayType === "percentage" ? "%" : (pointsPer !== null && pointsPer > 0 ? `${kotlinDouble(pointsPer)} pts` : null);
        metricDefinitions.push(def(field.id, ifBlank(labelText(field.label), field.id), phase, displayType, unit, pointsPer));
    }
    for (const w of config.analytics || []) {
        metricDefinitions.push(def(`custom_${w.id}`, w.title, "custom", String(w.type || "").toLowerCase()));
    }

    return { eventKey, metricDefinitions, teams: teamsOut };
}

// ── Custom-analytics dataset (AnalyticsReportService.generateDataset) ───────

function mapFieldType(raw) {
    const t = String(raw || "").toLowerCase();
    if (["counter", "number", "rating", "timer", "slider"].includes(t)) return "number";
    if (["checkbox", "toggle", "switch"].includes(t)) return "boolean";
    return "string";
}

function scoreSection(config, entry, ...subs) {
    return (config.fields || [])
        .filter((f) => {
            const key = f.phase && String(f.phase).trim() ? String(f.phase) : f.id;
            return subs.some((s) => key.toLowerCase().includes(s.toLowerCase()));
        })
        .reduce((sum, f) => sum + fieldScore(f, (entry.data || {})[f.id]), 0);
}

function meta(id, label, type, source, section = "General", pointsPer = null, opts = []) {
    return { id, label, type, source, section, pointsPer, options: opts };
}

/**
 * `/api/custom-analytics/dataset`. The entry lists are the resolved (one per match+team) lists the server would use,
 * already filtered by includePrescout.
 */
export function buildDataset({ matchConfig, pitConfig, qualConfig, matchEntries, pitEntries, qualEntries, teams, eventKeyFilter, program }) {
    const filter = (list) => (eventKeyFilter ? list.filter((e) => e.eventKey === eventKeyFilter) : list);
    const matches = filter(matchEntries);
    const pits = filter(pitEntries);
    const quals = filter(qualEntries);

    const seen = new Set();
    const teamsList = [];
    for (const t of teams || []) {
        if (eventKeyFilter && t.eventKey !== eventKeyFilter) continue;
        if (seen.has(t.teamNumber)) continue;
        seen.add(t.teamNumber);
        teamsList.push({
            teamNumber: t.teamNumber,
            name: t.name ?? null,
            nickname: t.nickname ?? null,
            city: t.city ?? null,
            state: t.state ?? null,
            opr: t.opr ?? null,
            epa: t.epa ?? null,
            exp: t.exp ?? null
        });
    }

    const fields = [
        meta("teamNumber", "Team Number", "number", "system", "Identification"),
        meta("matchNumber", "Match Number", "number", "system", "Identification"),
        meta("matchKey", "Match Key", "string", "system", "Identification"),
        meta("eventKey", "Event Key", "string", "system", "Identification"),
        meta("isPrescout", "Is Prescout", "boolean", "system", "Identification"),
        meta("createdAt", "Recorded At", "string", "system", "Metadata")
    ];
    for (const field of matchConfig.fields || []) {
        const phaseName = field.phase ?? "Match Scouting";
        const pointsPer = typeof field.pointsPer === "number" ? field.pointsPer : null;
        fields.push(meta(`match_${field.id}`, `${phaseName}: ${labelText(field.label)}`, mapFieldType(field.type), "match", phaseName, pointsPer, options(field).map((o) => labelText(o.label))));
        if (pointsPer !== null && pointsPer !== 0) {
            fields.push(meta(`calc_pts_${field.id}`, `${phaseName}: ${labelText(field.label)} Points`, "number", "calculated", `Scoring Elements (${phaseName})`, pointsPer));
        }
    }
    const isFtc = String(program || "").toUpperCase() === "FTC";
    fields.push(meta("calc_total_score", "Total Score", "number", "calculated", "Overall Scores"));
    fields.push(meta("calc_auto_score", "Auto Score", "number", "calculated", "Phase Scores"));
    fields.push(meta("calc_teleop_score", "Teleop Score", "number", "calculated", "Phase Scores"));
    fields.push(meta("calc_endgame_score", "Endgame Score", "number", "calculated", "Phase Scores"));
    if (!isFtc) {
        fields.push(meta("statbotics_epa", "EPA", "number", "calculated", "Statistics"));
        fields.push(meta("match13_exp", "EXP", "number", "calculated", "Statistics"));
    }
    fields.push(meta("tba_opr", isFtc ? "FTC Scout OPR" : "OPR", "number", "calculated", "Statistics"));
    for (const field of (pitConfig && pitConfig.fields) || []) {
        const phaseName = field.phase ?? "Pit Scouting";
        fields.push(meta(`pit_${field.id}`, `Pit: ${labelText(field.label)}`, mapFieldType(field.type), "pit", `Pit: ${phaseName}`, null, options(field).map((o) => labelText(o.label))));
    }
    for (const field of (qualConfig && qualConfig.fields) || []) {
        const phaseName = field.phase ?? "Qualitative";
        const pointsPer = typeof field.pointsPer === "number" ? field.pointsPer : null;
        fields.push(meta(`qual_${field.id}`, `Qual: ${labelText(field.label)}`, mapFieldType(field.type), "qual", `Qual: ${phaseName}`, pointsPer, options(field).map((o) => labelText(o.label))));
    }

    const byTeam = (key) => new Map(teamsList.map((t) => [t.teamNumber, t[key]]));
    const oprMap = byTeam("opr"), epaMap = byTeam("epa"), expMap = byTeam("exp");
    const configFields = matchConfig.fields || [];

    const flattenedMatches = matches.map((entry) => {
        const t = entry.targetTeamNumber ?? 0;
        const out = {
            id: entry.id,
            teamNumber: t,
            matchNumber: entry.matchNumber ?? 0,
            matchKey: entry.matchKey ?? "",
            eventKey: entry.eventKey ?? "",
            isPrescout: !!entry.isPrescout,
            createdAt: entry.createdAt,
            calc_total_score: scoreEntry(matchConfig, entry),
            calc_auto_score: scoreSection(matchConfig, entry, "auto"),
            calc_teleop_score: scoreSection(matchConfig, entry, "teleop"),
            calc_endgame_score: scoreSection(matchConfig, entry, "endgame", "climb"),
            statbotics_epa: epaMap.get(t) ?? 0,
            match13_exp: expMap.get(t) ?? 0,
            tba_opr: oprMap.get(t) ?? 0,
            hasDiscrepancy: !!entry.hasDiscrepancy
        };
        for (const [k, v] of Object.entries(entry.data || {})) {
            out[`match_${k}`] = v;
            const field = configFields.find((f) => f.id === k);
            if (field && typeof field.pointsPer === "number") {
                const c = isPrimitive(v) ? content(v) : null;
                const n = c === null ? null : toDoubleOrNull(c);
                out[`calc_pts_${k}`] = n !== null ? n * field.pointsPer : (c === "true" ? field.pointsPer : 0);
            }
        }
        return out;
    });
    const flattenedPits = pits.map((entry) => {
        const out = { id: entry.id, teamNumber: entry.targetTeamNumber ?? 0, eventKey: entry.eventKey ?? "", isPrescout: !!entry.isPrescout, createdAt: entry.createdAt };
        for (const [k, v] of Object.entries(entry.data || {})) out[`pit_${k}`] = v;
        return out;
    });
    const flattenedQuals = quals.map((entry) => {
        const out = {
            id: entry.id,
            teamNumber: entry.targetTeamNumber ?? 0,
            matchNumber: entry.matchNumber ?? 0,
            matchKey: entry.matchKey ?? "",
            eventKey: entry.eventKey ?? "",
            isPrescout: !!entry.isPrescout,
            createdAt: entry.createdAt
        };
        for (const [k, v] of Object.entries(entry.data || {})) out[`qual_${k}`] = v;
        return out;
    });

    const uniqueTeams = new Set(flattenedMatches.map((m) => m.teamNumber).concat(teamsList.map((t) => t.teamNumber)));
    return {
        generatedAt: new Date().toISOString(),
        eventKey: eventKeyFilter || null,
        fields,
        matchEntries: flattenedMatches,
        pitEntries: flattenedPits,
        qualEntries: flattenedQuals,
        teams: teamsList,
        totalMatches: flattenedMatches.length,
        totalTeams: uniqueTeams.size,
        computedOnDevice: true
    };
}
