document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    let settings = {};
    let currentEventKey = "";
    let assignments = [];
    let matches = [];
    let teams = [];
    let activeTab = "active"; // "active" | "completed" | "passed" | "all"
    let countdownInterval = null;
    let passedSignature = "";

    // Unfinished assignments move to the Passed tab this long after their start time.
    const PASSED_GRACE_MS = 3 * 60 * 1000;

    // DOM Elements
    const eventBadge = document.getElementById("event-badge");
    const refreshBtn = document.getElementById("refresh-btn");
    const heroBanner = document.getElementById("hero-banner");
    const heroCountdown = document.getElementById("hero-countdown");
    const heroTarget = document.getElementById("hero-target");
    const heroTime = document.getElementById("hero-time");
    const heroScoutBtn = document.getElementById("hero-scout-btn");
    const heroTypeBadge = document.getElementById("hero-type-badge");

    const tabBtns = document.querySelectorAll(".tab-btn");
    const countActive = document.getElementById("count-active");
    const countCompleted = document.getElementById("count-completed");
    const countPassed = document.getElementById("count-passed");
    const countAll = document.getElementById("count-all");
    const assignmentsGrid = document.getElementById("assignments-grid");

    const COMP_LEVEL_RANK = {
        practice: 0, prac: 0, pr: 0, prc: 0, pm: 0, p: 0,
        qm: 1, q: 1, qual: 1, quals: 1, qualification: 1,
        qf: 2, quarterfinal: 2, quarterfinals: 2,
        sf: 3, semifinal: 3, semifinals: 3,
        f: 4, final: 4, finals: 4,
        ef: 5, einstein: 5,
        playoff: 6, playoffs: 6
    };

    await init();

    async function init() {
        try {
            let settingsRes = null;
            try {
                settingsRes = await Obsidianscout.request("/api/settings");
            } catch (err) {
                console.warn("Network request for settings failed, checking offline cache...", err);
                const cachedSettingsText = localStorage.getItem("cache:/api/settings");
                if (cachedSettingsText) {
                    try { settingsRes = JSON.parse(cachedSettingsText); } catch (_) {}
                }
            }
            settings = (settingsRes && settingsRes.settings) || settingsRes || {};
            currentEventKey = Obsidianscout.resolveEventKey(settings) || localStorage.getItem("obsidian_cached_event") || "";

            eventBadge.textContent = currentEventKey ? `Event: ${currentEventKey}` : "No Active Event";

            await loadData();
            wireEventListeners();

            // Start countdown timer
            if (countdownInterval) clearInterval(countdownInterval);
            countdownInterval = setInterval(updateCountdowns, 1000);
        } catch (err) {
            console.error("Failed to initialize my assignments:", err);
            Obsidianscout.showToast("Failed to load your assignments", "error");
        }
    }

    async function loadData() {
        if (!currentEventKey) {
            assignmentsGrid.innerHTML = `
                <p style="color: var(--muted); padding: 24px; text-align: center; grid-column: 1 / -1;">
                    No active event configured. Please select an active event in Settings or Events.
                </p>
            `;
            return;
        }

        try {
            const [myAsgnsRes, matchesRes, teamsRes] = await Promise.all([
                Obsidianscout.request(`/api/assignments/my?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => []),
                Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => []),
                Obsidianscout.request(`/api/teams?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => [])
            ]);

            let rawAsgns = Array.isArray(myAsgnsRes) ? myAsgnsRes : (myAsgnsRes.assignments || []);
            if (rawAsgns.length === 0) {
                // Secondary offline fallback check (this event's cache only)
                const cachedRaw = localStorage.getItem(`cache:/api/assignments/my?eventKey=${encodeURIComponent(currentEventKey)}`);
                if (cachedRaw) {
                    try {
                        const parsed = JSON.parse(cachedRaw);
                        rawAsgns = Array.isArray(parsed) ? parsed : (parsed.assignments || []);
                    } catch (_) {}
                }
            }
            const eventNorm = currentEventKey.trim().toLowerCase();
            assignments = rawAsgns.filter(a => String(a.eventKey || "").trim().toLowerCase() === eventNorm);

            // Deduplicate matches & teams
            const rawMatches = Array.isArray(matchesRes) ? matchesRes : (matchesRes.matches || []);
            const matchMap = new Map();
            rawMatches.forEach(m => {
                const k = (m.matchKey || "").trim().toLowerCase();
                if (k && !matchMap.has(k)) matchMap.set(k, m);
            });
            matches = Array.from(matchMap.values());

            const rawTeams = Array.isArray(teamsRes) ? teamsRes : (teamsRes.teams || []);
            const teamMap = new Map();
            rawTeams.forEach(t => {
                const n = Number(t.teamNumber);
                if (n && !teamMap.has(n)) teamMap.set(n, t);
            });
            teams = Array.from(teamMap.values());

            // Sort in event stage order: Practice > Qualification > Playoff (QF > SF > F), then Pit by team number
            assignments.sort((a, b) => compareAssignments(a, b, matchMap));

            updateTabCounts();
            renderHeroBanner();
            renderAssignments();
        } catch (err) {
            console.error("Failed to load assignments data:", err);
            Obsidianscout.showToast(Obsidianscout.t ? Obsidianscout.t("my_assignments.error_load", "Failed to fetch assignment list") : "Failed to fetch assignment list", "error");
        }
    }

    function normalizeStageLevel(raw) {
        if (!raw) return "";
        const s = String(raw).trim().toLowerCase().replace(/[\s_]+/g, "");
        if (!s) return "";
        if (Object.prototype.hasOwnProperty.call(COMP_LEVEL_RANK, s)) {
            const r = COMP_LEVEL_RANK[s];
            if (r === 0) return "pr";
            if (r === 1) return "qm";
            if (r === 2) return "qf";
            if (r === 3) return "sf";
            if (r === 4) return "f";
            if (r === 5) return "ef";
            if (r === 6) return "playoff";
        }
        return s;
    }

    function getAssignmentOrderTuple(a, matchMap) {
        if (String(a.assignmentType || "").toUpperCase() === "PIT") {
            return [10, 0, Number(a.targetTeamNumber) || 999999];
        }

        const kLower = String(a.matchKey || "").trim().toLowerCase();
        const matchObj = (matchMap && kLower) ? matchMap.get(kLower) : null;
        const rawKey = kLower || (matchObj && matchObj.matchKey ? String(matchObj.matchKey).trim().toLowerCase() : "");

        let keyLevel = "";
        let keySet = null;
        let keyMatch = null;

        if (rawKey) {
            const legacy = /^.+_([a-z]+)_s(\d+)_m(\d+)$/i.exec(rawKey);
            if (legacy) {
                keyLevel = legacy[1].toLowerCase();
                keySet = Number(legacy[2]) || 1;
                keyMatch = Number(legacy[3]) || 0;
            } else {
                const suffix = rawKey.includes("_") ? rawKey.split("_").pop() : rawKey;
                const tba = /^([a-z]+?)(\d+)(?:m(\d+))?$/i.exec(suffix || "");
                if (tba) {
                    keyLevel = tba[1].toLowerCase();
                    if (tba[3] !== undefined) {
                        keySet = Number(tba[2]) || 1;
                        keyMatch = Number(tba[3]) || 0;
                    } else {
                        keySet = 1;
                        keyMatch = Number(tba[2]) || 0;
                    }
                }
            }
        }

        const explicitRaw = (a.compLevel && String(a.compLevel).trim())
            ? a.compLevel
            : (matchObj ? matchObj.compLevel : "");
        let resolvedLevel = normalizeStageLevel(explicitRaw);
        const parsedKeyLevel = (keyLevel && keyLevel !== "m") ? normalizeStageLevel(keyLevel) : "";

        if (parsedKeyLevel && (!resolvedLevel || ((resolvedLevel === "qm" || resolvedLevel === "playoff") && parsedKeyLevel !== "qm"))) {
            resolvedLevel = parsedKeyLevel;
        }

        const setNum = (a.setNumber != null && Number(a.setNumber) > 0)
            ? Number(a.setNumber)
            : ((matchObj && matchObj.setNumber != null && Number(matchObj.setNumber) > 0)
                ? Number(matchObj.setNumber)
                : (keySet || 1));

        if (resolvedLevel === "playoff") {
            if (setNum === 1) resolvedLevel = "qf";
            else if (setNum === 2) resolvedLevel = "sf";
            else if (setNum === 3) resolvedLevel = "f";
        }

        const stageRank = Object.prototype.hasOwnProperty.call(COMP_LEVEL_RANK, resolvedLevel || "qm")
            ? COMP_LEVEL_RANK[resolvedLevel || "qm"]
            : 7;

        const matchNum = (a.matchNumber != null && Number(a.matchNumber) > 0)
            ? Number(a.matchNumber)
            : (keyMatch || (matchObj && Number(matchObj.matchNumber)) || 0);

        return [stageRank, setNum, matchNum];
    }

    function compareAssignments(a, b, matchMap) {
        const ka = getAssignmentOrderTuple(a, matchMap);
        const kb = getAssignmentOrderTuple(b, matchMap);

        for (let i = 0; i < 3; i++) {
            if (ka[i] !== kb[i]) return ka[i] - kb[i];
        }

        const typeA = String(a.assignmentType || "");
        const typeB = String(b.assignmentType || "");
        if (typeA !== typeB) return typeA.localeCompare(typeB);

        const teamA = Number(a.targetTeamNumber) || 999999;
        const teamB = Number(b.targetTeamNumber) || 999999;
        if (teamA !== teamB) return teamA - teamB;

        const allianceA = String(a.allianceColor || a.targetAlliance || "").toUpperCase();
        const allianceB = String(b.allianceColor || b.targetAlliance || "").toUpperCase();
        if (allianceA !== allianceB) return allianceA.localeCompare(allianceB);

        return String(a.id || "").localeCompare(String(b.id || ""));
    }

    function parseTimestampMs(timeVal) {
        if (timeVal === null || timeVal === undefined || timeVal === "") return null;
        if (typeof timeVal === "number") {
            if (isNaN(timeVal) || timeVal <= 0) return null;
            return timeVal < 1e11 ? timeVal * 1000 : timeVal;
        }
        if (typeof timeVal === "string") {
            const trimmed = timeVal.trim();
            if (!trimmed) return null;
            if (/^\d+(\.\d+)?$/.test(trimmed)) {
                const num = Number(trimmed);
                if (isNaN(num) || num <= 0) return null;
                return num < 1e11 ? num * 1000 : num;
            }
            const parsed = Date.parse(trimmed);
            if (!isNaN(parsed) && parsed > 0) {
                return parsed;
            }
        }
        if (timeVal instanceof Date) {
            const t = timeVal.getTime();
            return isNaN(t) ? null : t;
        }
        return null;
    }

    function formatRelativeCountdown(targetTime, options = {}) {
        const targetMs = parseTimestampMs(targetTime);
        if (!targetMs) return "--";

        const diffMs = targetMs - Date.now();
        const isPast = diffMs < 0;
        const absDiffMs = Math.abs(diffMs);
        const totalSecs = Math.floor(absDiffMs / 1000);

        const d = Math.floor(totalSecs / 86400);
        const h = Math.floor((totalSecs % 86400) / 3600);
        const m = Math.floor((totalSecs % 3600) / 60);
        const s = totalSecs % 60;

        let parts = [];
        if (d > 0) parts.push(`${d}d`);
        if (h > 0) parts.push(`${h}h`);
        if (m > 0 || (d > 0 && h > 0)) parts.push(`${m}m`);
        if (options.includeSeconds && (d === 0 && h === 0)) {
            parts.push(`${s < 10 && m > 0 ? '0' : ''}${s}s`);
        }

        if (parts.length === 0) {
            if (options.includeSeconds) {
                parts.push(`${s}s`);
            } else {
                parts.push("<1m");
            }
        }

        const timeStr = parts.join(" ");
        if (isPast) {
            return `-${timeStr}`;
        } else {
            return `in ${timeStr}`;
        }
    }

    function getMatchDisplayLabel(mKey, mNum, compLvl) {
        const kLower = (mKey || "").trim().toLowerCase();
        const matchObj = matches.find(m => (m.matchKey || "").trim().toLowerCase() === kLower);
        if (matchObj && matchObj.label && !matchObj.label.startsWith("undefined") && !matchObj.label.startsWith("null")) {
            return matchObj.label;
        }
        const key = kLower;
        const comp = (compLvl || (matchObj && matchObj.compLevel ? matchObj.compLevel : "")).toLowerCase();
        const num = mNum || (matchObj ? matchObj.matchNumber : null);
        const set = matchObj ? matchObj.setNumber : null;

        if (comp === "pr" || comp === "practice" || comp === "pm" || key.includes("_pr") || key.includes("_pm")) {
            return `Practice Match ${num || 1}`;
        } else if (comp === "qm" || comp === "qual" || key.includes("_qm")) {
            const qmNum = num || key.replace(/.*_qm/i, "");
            return `Qualification ${qmNum}`;
        } else if (comp === "sf" || key.includes("_sf")) {
            return `Semifinal ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "qf" || key.includes("_qf")) {
            return `Quarterfinal ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "f" || key.includes("_f")) {
            return `Finals ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (num) {
            return `Match ${num}`;
        }
        return mKey || "Match";
    }

    function isOpen(a) {
        return a.status !== "COMPLETED" && a.status !== "CANCELLED";
    }

    function isPassed(a) {
        if (!isOpen(a)) return false;
        const startMs = parseTimestampMs(a.predictedTime || a.scheduledTime);
        return !!startMs && Date.now() > startMs + PASSED_GRACE_MS;
    }

    function computePassedSignature() {
        return assignments.filter(isPassed).map(a => a.id).join(",");
    }

    function updateTabCounts() {
        const passedCount = assignments.filter(isPassed).length;
        const activeCount = assignments.filter(isOpen).length - passedCount;
        const compCount = assignments.filter(a => a.status === "COMPLETED").length;

        countActive.textContent = activeCount;
        countCompleted.textContent = compCount;
        countPassed.textContent = passedCount;
        countAll.textContent = assignments.length;
        passedSignature = computePassedSignature();
    }

    function renderHeroBanner() {
        // Find next pending or in_progress match assignment
        const pendingMatches = assignments.filter(a => 
            (a.status === "PENDING" || a.status === "IN_PROGRESS") && 
            parseTimestampMs(a.predictedTime || a.scheduledTime) &&
            !isPassed(a)
        );

        if (pendingMatches.length === 0) {
            heroBanner.classList.add("hidden");
            return;
        }

        // Check earliest
        const next = pendingMatches[0];
        const displayTime = next.predictedTime || next.scheduledTime;
        const targetMs = parseTimestampMs(displayTime);
        const diffMs = targetMs - Date.now();
        const diffMins = Math.round(diffMs / (60 * 1000));

        // Show hero banner if scheduled within the next 2 hours or within 12 hours in the past
        if (diffMins > -720 && diffMins < 120) {
            heroBanner.classList.remove("hidden");
            heroTypeBadge.textContent = next.assignmentType === "MATCH" ? "Match Scouting" : "Qualitative Scouting";
            
            const mLabel = getMatchDisplayLabel(next.matchKey, next.matchNumber, next.compLevel);
            const tNum = next.targetTeamNumber ? ` &bull; Team ${next.targetTeamNumber}` : "";
            heroTarget.innerHTML = `<strong>${mLabel}</strong>${tNum}`;
            
            let timeLabel = `Scheduled for ${formatTime(next.scheduledTime || displayTime, next.eventTimezone || (settings && settings.timezone))}`;
            if (next.predictedTime && next.scheduledTime && next.predictedTime !== next.scheduledTime && next.scheduleOffsetSeconds) {
                const offsetMins = Math.round(Number(next.scheduleOffsetSeconds) / 60);
                if (Math.abs(offsetMins) >= 1) {
                    const isLate = offsetMins > 0;
                    const sign = isLate ? "+" : "";
                    const estTimeStr = formatTime(next.predictedTime, next.eventTimezone || (settings && settings.timezone));
                    timeLabel = `Est. ${estTimeStr} (${sign}${offsetMins}m)`;
                }
            }
            heroTime.textContent = timeLabel;

            const scoutUrl = getScoutUrl(next);
            heroScoutBtn.setAttribute("href", scoutUrl);

            updateHeroCountdownDisplay(displayTime);
        } else {
            heroBanner.classList.add("hidden");
        }
    }

    function updateHeroCountdownDisplay(scheduledTime) {
        const targetMs = parseTimestampMs(scheduledTime);
        if (!targetMs) {
            heroCountdown.textContent = "--";
            heroCountdown.classList.remove("imminent");
            return;
        }

        const diffMs = targetMs - Date.now();
        const formatted = formatRelativeCountdown(targetMs, { includeSeconds: true });

        if (diffMs < 0) {
            heroCountdown.textContent = formatted;
            heroCountdown.classList.remove("imminent");
        } else {
            heroCountdown.textContent = `Starts ${formatted}`;
            const totalSecs = Math.floor(diffMs / 1000);
            if (totalSecs <= 600) { // 10 minutes or less
                heroCountdown.classList.add("imminent");
            } else {
                heroCountdown.classList.remove("imminent");
            }
        }
    }

    function updateCountdowns() {
        // Move assignments into the Passed tab as their grace period runs out
        if (computePassedSignature() !== passedSignature) {
            updateTabCounts();
            renderHeroBanner();
            renderAssignments();
            return; // renderAssignments calls back into updateCountdowns
        }

        // Update hero countdown
        const pendingMatches = assignments.filter(a => 
            (a.status === "PENDING" || a.status === "IN_PROGRESS") && 
            parseTimestampMs(a.predictedTime || a.scheduledTime) &&
            !isPassed(a)
        );
        if (pendingMatches.length > 0 && !heroBanner.classList.contains("hidden")) {
            const next = pendingMatches[0];
            updateHeroCountdownDisplay(next.predictedTime || next.scheduledTime);
        }

        // Update card chips
        document.querySelectorAll(".card-countdown-chip").forEach(chip => {
            const schedIso = chip.dataset.scheduledTime;
            if (!schedIso) return;
            const targetMs = parseTimestampMs(schedIso);
            if (!targetMs) {
                chip.textContent = "--";
                chip.classList.remove("imminent", "past");
                return;
            }
            const diffMs = targetMs - Date.now();
            const formatted = formatRelativeCountdown(targetMs, { includeSeconds: false });
            chip.textContent = formatted;

            if (diffMs < 0) {
                chip.classList.remove("imminent");
                chip.classList.add("past");
            } else if (diffMs <= 600000) { // 10 minutes or less in future
                chip.classList.add("imminent");
                chip.classList.remove("past");
            } else {
                chip.classList.remove("imminent", "past");
            }
        });
    }

    function renderAssignments() {
        let filtered = assignments;
        if (activeTab === "active") {
            filtered = assignments.filter(a => isOpen(a) && !isPassed(a));
        } else if (activeTab === "completed") {
            filtered = assignments.filter(a => a.status === "COMPLETED");
        } else if (activeTab === "passed") {
            filtered = assignments.filter(isPassed);
        }

        if (filtered.length === 0) {
            const emptyMsg = activeTab === "completed" 
                ? (Obsidianscout.t ? Obsidianscout.t("my_assignments.empty_completed", "You have not completed any assignments yet.") : "You have not completed any assignments yet.")
                : activeTab === "passed"
                ? (Obsidianscout.t ? Obsidianscout.t("my_assignments.empty_passed", "No passed assignments. Unfinished assignments move here 3 minutes after their start time.") : "No passed assignments. Unfinished assignments move here 3 minutes after their start time.")
                : (Obsidianscout.t ? Obsidianscout.t("my_assignments.empty", "You have no active scouting assignments! Enjoy the matches or ask your scouting lead.") : "You have no active scouting assignments! Enjoy the matches or ask your scouting lead.");
            assignmentsGrid.innerHTML = `
                <div style="color: var(--muted); padding: 36px; text-align: center; grid-column: 1 / -1; display:flex; flex-direction:column; align-items:center; justify-content:center;">
                    <svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="opacity: 0.4; margin-bottom: 12px;">
                        <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/>
                        <rect x="8" y="2" width="8" height="4" rx="1" ry="1"/>
                        <polyline points="9 11 12 14 22 4"/>
                    </svg>
                    <p style="margin: 0; font-size: 15px;">${emptyMsg}</p>
                </div>
            `;
            return;
        }

        assignmentsGrid.innerHTML = filtered.map(a => {
            const isCompleted = a.status === "COMPLETED";
            const isMatch = a.assignmentType === "MATCH";
            const isPit = a.assignmentType === "PIT";
            const isQual = a.assignmentType === "QUALITATIVE";

            // Target title and subtitle
            let title = "";
            let subtitle = "";
            let typeBadge = "";
            let cardTypeClass = "";
            let btnLabel = Obsidianscout.t ? Obsidianscout.t("my_assignments.scout_match", "Scout Match") : "Scout Match";
            let btnSvg = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:6px;"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;

            if (isMatch) {
                cardTypeClass = "type-match";
                typeBadge = `<span class="asgn-type-badge match"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="22" y1="12" x2="18" y2="12"></line><line x1="6" y1="12" x2="2" y2="12"></line><line x1="12" y1="6" x2="12" y2="2"></line><line x1="12" y1="22" x2="12" y2="18"></line></svg>Match</span>`;
                const mLabel = getMatchDisplayLabel(a.matchKey, a.matchNumber, a.compLevel);
                title = `${mLabel} &bull; Team ${a.targetTeamNumber}`;
                const teamObj = teams.find(t => t.teamNumber === a.targetTeamNumber);
                subtitle = teamObj && teamObj.nickname ? teamObj.nickname : "Match Scouting";
                btnLabel = isCompleted 
                    ? (Obsidianscout.t ? Obsidianscout.t("my_assignments.edit_entry", "Edit Entry") : "Edit Entry") 
                    : (Obsidianscout.t ? Obsidianscout.t("my_assignments.scout_match", "Scout Match") : "Scout Match");
                btnSvg = isCompleted 
                    ? `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:6px;"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>` 
                    : `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:6px;"><polygon points="5 3 19 12 5 21 5 3"/></svg>`;
            } else if (isPit) {
                cardTypeClass = "type-pit";
                typeBadge = `<span class="asgn-type-badge pit"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>Pit</span>`;
                title = `Pit &bull; Team ${a.targetTeamNumber}`;
                const teamObj = teams.find(t => t.teamNumber === a.targetTeamNumber);
                subtitle = teamObj && teamObj.nickname ? teamObj.nickname : "Pit Inspection & Scouting";
                btnLabel = isCompleted 
                    ? (Obsidianscout.t ? Obsidianscout.t("my_assignments.edit_entry", "Edit Entry") : "Edit Entry") 
                    : (Obsidianscout.t ? Obsidianscout.t("my_assignments.scout_pit", "Scout Pit") : "Scout Pit");
                btnSvg = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:6px;"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>`;
            } else if (isQual) {
                cardTypeClass = "type-qual";
                typeBadge = `<span class="asgn-type-badge qual"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="3"/><line x1="22" y1="12" x2="18" y2="12"/><line x1="6" y1="12" x2="2" y2="12"/><line x1="12" y1="6" x2="12" y2="2"/><line x1="12" y1="22" x2="12" y2="18"/></svg>Qualitative</span>`;
                const mLabel = getMatchDisplayLabel(a.matchKey, a.matchNumber, a.compLevel);
                const targetText = a.targetAlliance ? `${a.targetAlliance.toUpperCase()} Alliance` : (a.targetTeamNumber ? `Team ${a.targetTeamNumber}` : "All");
                title = `Qual &bull; ${mLabel}`;
                subtitle = `Target: ${targetText}`;
                btnLabel = isCompleted 
                    ? (Obsidianscout.t ? Obsidianscout.t("my_assignments.edit_entry", "Edit Entry") : "Edit Entry") 
                    : (Obsidianscout.t ? Obsidianscout.t("my_assignments.scout_qual", "Scout Qual") : "Scout Qual");
                btnSvg = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:6px;"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="3"/><line x1="22" y1="12" x2="18" y2="12"/><line x1="6" y1="12" x2="2" y2="12"/><line x1="12" y1="6" x2="12" y2="2"/><line x1="12" y1="22" x2="12" y2="18"/></svg>`;
            }

            // Time & countdown badge
            let timeInfoHtml = "";
            const displayEpoch = a.predictedTime || a.scheduledTime;
            if (displayEpoch) {
                const timeStr = formatTime(displayEpoch, a.eventTimezone || (settings && settings.timezone));
                let offsetBadgeHtml = "";
                if (a.scheduleOffsetSeconds && a.predictedTime && a.scheduledTime && a.predictedTime !== a.scheduledTime) {
                    const offsetMins = Math.round(Number(a.scheduleOffsetSeconds) / 60);
                    if (Math.abs(offsetMins) >= 1) {
                        const isLate = offsetMins > 0;
                        const sign = isLate ? "+" : "";
                        const origLocalStr = formatTime(a.scheduledTime, a.eventTimezone || (settings && settings.timezone));
                        const statusText = isLate ? `${offsetMins}m behind schedule` : `${Math.abs(offsetMins)}m ahead of schedule`;
                        const tooltip = `Est: ${timeStr} (${statusText} \u2022 Sched: ${origLocalStr})`;
                        offsetBadgeHtml = ` <span class="schedule-offset-badge ${isLate ? 'behind' : 'ahead'}" data-tooltip="${escapeHtml(tooltip)}" aria-label="${escapeHtml(statusText)}">${sign}${offsetMins}m</span>`;
                    }
                }
                timeInfoHtml = `
                    <div class="row justify-between items-center" style="font-size:12px;">
                        <span style="color:var(--muted); display:inline-flex; align-items:center; gap:4px; flex-wrap:wrap;">
                            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                            ${timeStr}${offsetBadgeHtml}
                        </span>
                        ${!isCompleted ? `<span class="countdown-chip card-countdown-chip" data-scheduled-time="${displayEpoch}">--</span>` : ''}
                    </div>
                `;
            }

            // Status badge
            let statusBadge = "";
            if (isCompleted) {
                statusBadge = `<span class="asgn-status-badge completed"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>Done</span>`;
            } else if (a.status === "IN_PROGRESS") {
                statusBadge = `<span class="asgn-status-badge in-progress"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>In Progress</span>`;
            } else {
                statusBadge = `<span class="asgn-status-badge pending"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/></svg>Pending</span>`;
            }

            const scoutUrl = getScoutUrl(a);
            const instructionsLabel = Obsidianscout.t ? Obsidianscout.t("my_assignments.instructions", "Instructions:") : "Instructions:";

            return `
                <div class="my-card ${cardTypeClass} ${isCompleted ? 'completed' : ''}">
                    <div>
                        <div class="card-header-row">
                            <div class="row items-center gap-6">
                                ${typeBadge}
                                ${statusBadge}
                            </div>
                        </div>

                        <div class="card-target-title mt-10">${title}</div>
                        <div class="card-target-subtitle">${subtitle}</div>

                        ${a.notes ? `
                            <div style="background:var(--surface-2); border-radius:6px; padding:8px 10px; margin-top:10px; font-size:12px; color:var(--text); border:1px solid var(--border-subtle);">
                                <strong style="color:var(--muted); font-size:10px; text-transform:uppercase; display:block; margin-bottom:2px;">${instructionsLabel}</strong>
                                ${escapeHtml(a.notes)}
                            </div>
                        ` : ''}
                    </div>

                    <div>
                        ${timeInfoHtml}
                        <div class="row gap-8 mt-12">
                            <a href="${scoutUrl}" class="btn ${isCompleted ? 'secondary' : ''}" style="flex:1; justify-content:center; display:inline-flex; align-items:center;">
                                ${btnSvg} <span>${btnLabel}</span>
                            </a>
                        </div>
                    </div>
                </div>
            `;
        }).join("");

        updateCountdowns();
    }

    function getScoutUrl(a) {
        if (a.assignmentType === "MATCH") {
            const params = new URLSearchParams();
            if (a.matchKey) params.set("matchKey", a.matchKey);
            if (a.matchNumber) params.set("matchNumber", String(a.matchNumber));
            if (a.targetTeamNumber) params.set("targetTeamNumber", String(a.targetTeamNumber));
            return `/scout?${params.toString()}`;
        } else if (a.assignmentType === "PIT") {
            const params = new URLSearchParams();
            if (a.targetTeamNumber) params.set("targetTeamNumber", String(a.targetTeamNumber));
            return `/pit-scout?${params.toString()}`;
        } else if (a.assignmentType === "QUALITATIVE") {
            const params = new URLSearchParams();
            if (a.matchKey) params.set("matchKey", a.matchKey);
            if (a.matchNumber) params.set("matchNumber", String(a.matchNumber));
            if (a.targetAlliance) params.set("scope", a.targetAlliance);
            if (a.targetTeamNumber) params.set("targetTeamNumber", String(a.targetTeamNumber));
            return `/qual-scout?${params.toString()}`;
        }
        return "/scout";
    }

    function wireEventListeners() {
        refreshBtn.addEventListener("click", async () => {
            await loadData();
            Obsidianscout.showToast(Obsidianscout.t ? Obsidianscout.t("assignments.updated", "Assignments updated") : "Assignments updated", "info");
        });

        tabBtns.forEach(btn => {
            btn.addEventListener("click", () => {
                activeTab = btn.dataset.tab;
                tabBtns.forEach(b => b.classList.toggle("active", b === btn));
                renderAssignments();
            });
        });

        window.addEventListener("online", () => {
            loadData();
        });

        window.addEventListener("obsidianscout:server-status-changed", (e) => {
            if (e.detail && e.detail.online) {
                loadData();
            }
        });

        window.addEventListener("obsidianscout:offline-entries-synced", () => {
            loadData();
        });

        document.addEventListener("visibilitychange", () => {
            if (!document.hidden) {
                loadData();
            }
        });
    }

    function formatTime(isoString, timezone) {
        const ms = parseTimestampMs(isoString);
        if (!ms) return "";
        try {
            const d = new Date(ms);
            const options = { hour: '2-digit', minute: '2-digit' };
            if (timezone) {
                try {
                    return new Intl.DateTimeFormat([], { ...options, timeZone: timezone }).format(d);
                } catch (_) {}
            }
            return d.toLocaleTimeString([], options);
        } catch {
            return String(isoString);
        }
    }

    function escapeHtml(str) {
        if (!str) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }
});
