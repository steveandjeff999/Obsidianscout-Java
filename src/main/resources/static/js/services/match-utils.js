/*
 * Match helpers shared by the drive-team tools (Match Checklist, Battery Tracker), so both pages put our
 * matches in the same order and the battery each match needs agrees between them.
 */
(function (root) {
    "use strict";

    function compLevelRank(compLevel) {
        const level = String(compLevel || "").trim().toLowerCase();
        if (["p", "pr", "pm", "practice"].includes(level)) return 0;
        if (["q", "qm", "qual", "qualification"].includes(level)) return 1;
        return 2;
    }

    /**
     * Practice, then qualifications, then playoffs; by set and match number within each. The app orders our
     * matches the same way (BatteryRotation.compareMatchOrder) so both pick the same battery per match.
     */
    function compareMatches(a, b) {
        return (compLevelRank(a.compLevel) - compLevelRank(b.compLevel)) ||
            ((a.setNumber || 1) - (b.setNumber || 1)) ||
            ((a.matchNumber || 0) - (b.matchNumber || 0)) ||
            (String(a.matchKey) < String(b.matchKey) ? -1 : String(a.matchKey) > String(b.matchKey) ? 1 : 0);
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
        return root.Obsidianscout?.localize?.(match.label) ||
            (match.compLevel ? `${String(match.compLevel).toUpperCase()} ${match.matchNumber || ""}`.trim() : match.matchKey);
    }

    const api = { compLevelRank, compareMatches, teamNumberOf, allianceOf, isTeamInMatch, matchLabel };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    root.ObsidianMatchUtils = api;
})(typeof window !== "undefined" ? window : globalThis);
