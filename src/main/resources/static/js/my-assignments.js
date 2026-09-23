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
    let activeTab = "active"; // "active" | "completed" | "all"
    let countdownInterval = null;

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
    const countAll = document.getElementById("count-all");
    const assignmentsGrid = document.getElementById("assignments-grid");

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
                Obsidianscout.request(`/api/assignments/my?eventKey=${encodeURIComponent(currentEventKey)}`).catch(async () => {
                    // Fallback to /api/assignments/my without query param if cached
                    const fallback = await Obsidianscout.request("/api/assignments/my").catch(() => []);
                    return fallback;
                }),
                Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => []),
                Obsidianscout.request(`/api/teams?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => [])
            ]);

            let rawAsgns = Array.isArray(myAsgnsRes) ? myAsgnsRes : (myAsgnsRes.assignments || []);
            if (rawAsgns.length === 0) {
                // Secondary offline fallback check
                const cachedRaw = localStorage.getItem(`cache:/api/assignments/my?eventKey=${encodeURIComponent(currentEventKey)}`) ||
                                  localStorage.getItem("cache:/api/assignments/my");
                if (cachedRaw) {
                    try {
                        const parsed = JSON.parse(cachedRaw);
                        rawAsgns = Array.isArray(parsed) ? parsed : (parsed.assignments || []);
                    } catch (_) {}
                }
            }
            assignments = rawAsgns.filter(a => !currentEventKey || !a.eventKey || a.eventKey.trim().toLowerCase() === currentEventKey.trim().toLowerCase());

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

            // Sort: Pending/In Progress first (sorted by scheduled time or match number), then completed
            assignments.sort((a, b) => {
                if (a.status === "COMPLETED" && b.status !== "COMPLETED") return 1;
                if (a.status !== "COMPLETED" && b.status === "COMPLETED") return -1;
                
                const timeA = parseTimestampMs(a.scheduledTime);
                const timeB = parseTimestampMs(b.scheduledTime);
                if (timeA && timeB) {
                    return timeA - timeB;
                }
                if (timeA && !timeB) return -1;
                if (!timeA && timeB) return 1;
                return (Number(a.matchNumber) || 0) - (Number(b.matchNumber) || 0);
            });

            updateTabCounts();
            renderHeroBanner();
            renderAssignments();
        } catch (err) {
            console.error("Failed to load assignments data:", err);
            Obsidianscout.showToast(Obsidianscout.t ? Obsidianscout.t("my_assignments.error_load", "Failed to fetch assignment list") : "Failed to fetch assignment list", "error");
        }
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

    function getMatchDisplayLabel(mKey, mNum) {
        const matchObj = matches.find(m => m.matchKey === mKey);
        if (matchObj && matchObj.label && !matchObj.label.startsWith("undefined") && !matchObj.label.startsWith("null")) {
            return matchObj.label;
        }
        const key = (mKey || "").toLowerCase();
        const comp = (matchObj && matchObj.compLevel ? matchObj.compLevel : "").toLowerCase();
        const num = mNum || (matchObj ? matchObj.matchNumber : null);
        const set = matchObj ? matchObj.setNumber : null;

        if (comp === "qm" || comp === "qual" || key.includes("_qm")) {
            const qmNum = num || key.replace(/.*_qm/i, "");
            return `Qualification ${qmNum}`;
        } else if (comp === "sf" || key.includes("_sf")) {
            return `Semifinal ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "qf" || key.includes("_qf")) {
            return `Quarterfinal ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "f" || key.includes("_f")) {
            return `Finals ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "pr" || comp === "practice" || key.includes("_pr")) {
            return `Practice Match ${num || 1}`;
        } else if (num) {
            return `Match ${num}`;
        }
        return mKey || "Match";
    }

    function updateTabCounts() {
        const activeCount = assignments.filter(a => a.status !== "COMPLETED" && a.status !== "CANCELLED").length;
        const compCount = assignments.filter(a => a.status === "COMPLETED").length;

        countActive.textContent = activeCount;
        countCompleted.textContent = compCount;
        countAll.textContent = assignments.length;
    }

    function renderHeroBanner() {
        // Find next pending or in_progress match assignment
        const pendingMatches = assignments.filter(a => 
            (a.status === "PENDING" || a.status === "IN_PROGRESS") && 
            parseTimestampMs(a.scheduledTime)
        );

        if (pendingMatches.length === 0) {
            heroBanner.classList.add("hidden");
            return;
        }

        // Check earliest
        const next = pendingMatches[0];
        const targetMs = parseTimestampMs(next.scheduledTime);
        const diffMs = targetMs - Date.now();
        const diffMins = Math.round(diffMs / (60 * 1000));

        // Show hero banner if scheduled within the next 2 hours or within 12 hours in the past
        if (diffMins > -720 && diffMins < 120) {
            heroBanner.classList.remove("hidden");
            heroTypeBadge.textContent = next.assignmentType === "MATCH" ? "Match Scouting" : "Qualitative Scouting";
            
            const mLabel = getMatchDisplayLabel(next.matchKey, next.matchNumber);
            const tNum = next.targetTeamNumber ? ` &bull; Team ${next.targetTeamNumber}` : "";
            heroTarget.innerHTML = `<strong>${mLabel}</strong>${tNum}`;
            heroTime.textContent = `Scheduled for ${formatTime(next.scheduledTime)}`;

            const scoutUrl = getScoutUrl(next);
            heroScoutBtn.setAttribute("href", scoutUrl);

            updateHeroCountdownDisplay(next.scheduledTime);
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
        // Update hero countdown
        const pendingMatches = assignments.filter(a => 
            (a.status === "PENDING" || a.status === "IN_PROGRESS") && 
            parseTimestampMs(a.scheduledTime)
        );
        if (pendingMatches.length > 0 && !heroBanner.classList.contains("hidden")) {
            const next = pendingMatches[0];
            updateHeroCountdownDisplay(next.scheduledTime);
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
            filtered = assignments.filter(a => a.status !== "COMPLETED" && a.status !== "CANCELLED");
        } else if (activeTab === "completed") {
            filtered = assignments.filter(a => a.status === "COMPLETED");
        }

        if (filtered.length === 0) {
            const emptyMsg = activeTab === "completed" 
                ? (Obsidianscout.t ? Obsidianscout.t("my_assignments.empty_completed", "You have not completed any assignments yet.") : "You have not completed any assignments yet.")
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
                const mLabel = getMatchDisplayLabel(a.matchKey, a.matchNumber);
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
                const mLabel = getMatchDisplayLabel(a.matchKey, a.matchNumber);
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
            if (a.scheduledTime) {
                const timeStr = formatTime(a.scheduledTime);
                timeInfoHtml = `
                    <div class="row justify-between items-center" style="font-size:12px;">
                        <span style="color:var(--muted); display:inline-flex; align-items:center; gap:4px;">
                            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                            ${timeStr}
                        </span>
                        ${!isCompleted ? `<span class="countdown-chip card-countdown-chip" data-scheduled-time="${a.scheduledTime}">--</span>` : ''}
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

    function formatTime(isoString) {
        const ms = parseTimestampMs(isoString);
        if (!ms) return "";
        try {
            const d = new Date(ms);
            return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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
