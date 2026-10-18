/*
 * Match helpers shared by the drive-team tools (Match Checklist, Battery Tracker), so both pages put our
 * matches in the same order and the battery each match needs agrees between them.
 */
(function (root) {
    "use strict";

    function normalizeCompLevel(raw) {
        const compact = String(raw || "").trim().toLowerCase().replace(/[\s_-]+/g, "");
        if (!compact) return "";
        if (["p", "pr", "pm", "prac", "prc", "practice", "practicematch"].includes(compact)) return "practice";
        if (["q", "qm", "qual", "quals", "qualification", "qualificationmatch", "m", "match", "matches"].includes(compact)) return "qm";
        if (["qf", "quarterfinal", "quarterfinals"].includes(compact)) return "qf";
        if (["sf", "semifinal", "semifinals"].includes(compact)) return "sf";
        if (["f", "final", "finals"].includes(compact)) return "f";
        if (["ef", "einstein", "eighthfinal", "eighthfinals"].includes(compact)) return "ef";
        if (["po", "playoff", "playoffs", "playoffmatch"].includes(compact)) return "playoff";
        return compact;
    }

    function compLevelRank(compLevel) {
        switch (normalizeCompLevel(compLevel)) {
            case "practice": return 0;
            case "qm": return 1;
            case "qf": return 2;
            case "sf": return 3;
            case "f": return 4;
            case "ef": return 5;
            case "playoff": return 6;
            default: return 7;
        }
    }

    const LEGACY_KEY_RE = /_([a-z]+)_s(\d+)_m(\d+)$/i;
    const TBA_SUFFIX_RE = /^([a-z]+)(\d+)(?:m(\d+))?$/i;
    const CUSTOM_MATCH_RE = /^(practice|prac|pr|pm|p|qualification|quals|qual|qm|q|quarterfinals|quarterfinal|qf|semifinals|semifinal|sf|finals|final|f|einstein|ef|playoffs|playoff|po)(?:\s+match)?(?:[\s#:-]+(\d+)(?:[\s/-]+(\d+))?)?$/i;

    function parseMatchKey(matchKey) {
        const key = String(matchKey || "").trim();
        if (!key) return { compLevel: "", setNumber: 0, matchNumber: 0 };

        const legacy = key.match(LEGACY_KEY_RE);
        if (legacy) {
            return {
                compLevel: normalizeCompLevel(legacy[1]),
                setNumber: Number(legacy[2]) || 1,
                matchNumber: Number(legacy[3]) || 0
            };
        }

        if (/^custom_/i.test(key)) {
            const customText = key.slice(7).replace(/_/g, " ").trim();
            const customMatch = customText.match(CUSTOM_MATCH_RE);
            if (customMatch) {
                const compLevel = normalizeCompLevel(customMatch[1]);
                const hasSetAndMatch = customMatch[3] !== undefined;
                return {
                    compLevel,
                    setNumber: hasSetAndMatch ? (Number(customMatch[2]) || 1) : 1,
                    matchNumber: hasSetAndMatch ? (Number(customMatch[3]) || 0) : (Number(customMatch[2]) || 0)
                };
            }
            return { compLevel: "", setNumber: 1, matchNumber: 0 };
        }

        const suffix = key.includes("_") ? key.slice(key.lastIndexOf("_") + 1) : key;
        const tba = suffix.match(TBA_SUFFIX_RE);
        if (tba) {
            const compLevel = normalizeCompLevel(tba[1]);
            const hasSetAndMatch = tba[3] !== undefined;
            return {
                compLevel,
                setNumber: hasSetAndMatch ? (Number(tba[2]) || 1) : 1,
                matchNumber: hasSetAndMatch ? (Number(tba[3]) || 0) : (Number(tba[2]) || 0)
            };
        }

        return { compLevel: "", setNumber: 1, matchNumber: 0 };
    }

    function extractMatchIdentity(match) {
        const matchKey = String(match?.matchKey || "").trim();
        const parsed = parseMatchKey(matchKey);
        let compLevel = normalizeCompLevel(match?.compLevel);
        if (!compLevel || (compLevel === "playoff" && parsed.compLevel && parsed.compLevel !== "playoff") || (compLevel === "qm" && parsed.compLevel && parsed.compLevel !== "qm")) {
            compLevel = parsed.compLevel || compLevel;
        }
        const rawSet = Number(match?.setNumber);
        const setNumber = (Number.isFinite(rawSet) && rawSet > 0) ? rawSet : (parsed.setNumber || 1);
        const rawMatch = Number(match?.matchNumber);
        const matchNumber = (Number.isFinite(rawMatch) && rawMatch > 0) ? rawMatch : (parsed.matchNumber || 0);
        return { compLevel, setNumber, matchNumber, matchKey };
    }

    /**
     * Practice, then qualifications, then playoffs (QF, SF, F); by set and match number within each.
     * The app orders our matches the same way (BatteryRotation.compareMatchOrder) so both pick the same battery per match.
     */
    function compareMatches(a, b) {
        const ia = extractMatchIdentity(a);
        const ib = extractMatchIdentity(b);
        return (compLevelRank(ia.compLevel) - compLevelRank(ib.compLevel)) ||
            (ia.setNumber - ib.setNumber) ||
            (ia.matchNumber - ib.matchNumber) ||
            (ia.matchKey < ib.matchKey ? -1 : ia.matchKey > ib.matchKey ? 1 : 0);
    }

    function teamNumberOf(teamKey) {
        const m = String(teamKey || "").match(/\d+/);
        return m ? Number(m[0]) : NaN;
    }

    /** "Red", "Blue" or "" for the alliance `teamNumber` plays on. */
    function allianceOf(match, teamNumber) {
        if (!match) return "";
        const ours = Number(teamNumber);
        if ((match.redTeams || []).some((k) => teamNumberOf(k) === ours)) return "Red";
        if ((match.blueTeams || []).some((k) => teamNumberOf(k) === ours)) return "Blue";
        return "";
    }

    function isTeamInMatch(match, teamNumber) {
        return allianceOf(match, teamNumber) !== "";
    }

    function matchLabel(match) {
        if (!match) return "";
        const localized = root.Obsidianscout?.localize?.(match.label) || (typeof match.label === "string" ? match.label.trim() : "");
        if (localized) return localized;
        const id = extractMatchIdentity(match);
        if (id.compLevel) {
            const abbrev = id.compLevel === "practice" ? "Practice" : id.compLevel.toUpperCase();
            if (["qf", "sf", "f", "ef", "playoff"].includes(id.compLevel)) {
                return `${abbrev} ${id.setNumber}-${id.matchNumber || 1}`;
            }
            return `${abbrev} ${id.matchNumber || ""}`.trim();
        }
        return match.matchKey || "";
    }

    const api = { normalizeCompLevel, compLevelRank, extractMatchIdentity, compareMatches, teamNumberOf, allianceOf, isTeamInMatch, matchLabel };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    root.ObsidianMatchUtils = api;
})(typeof window !== "undefined" ? window : globalThis);
