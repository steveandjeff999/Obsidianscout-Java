/**
 * Interactive Feature Tour & Zachary Assistant Module - ObsidianScout
 * 1-feature-per-tutorial architecture with multi-page workflows, permission adaptation,
 * hands-on action requirements, safe practice interception, professional know-it-all persona,
 * Web Speech TTS engine, and Zachary error diagnostics.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from '../base/storage.js';
import { request } from '../base/http.js';
import { showToast } from './toast.js';
import { t } from '../base/i18n.js';
import { isAdmin, isSuperAdmin } from '../base/auth.js';
import { isPageAccessible } from '../layout/navigation.js';

// ==========================================================================
// ZACHARY QUIPS & EASTER EGGS (Professional Know-It-All Persona)
// ==========================================================================
export const ZACHARY_QUIPS = [
    "Scouting protocol requires your undivided attention. Clean quantitative data correlates directly with high playoff seed performance.",
    "Did you know that 87.4% of scouting variance stems from inattentiveness during Teleop? Keep your visual tracking locked on the robot.",
    "I can provide an exhaustive technical breakdown of optimal autonomous trajectory curves, but let's complete this step first.",
    "Over 10,000 matches analyzed, and mathematically speaking, empirical cycle timing precision remains the highest predictor of alliance match success.",
    "If a robot experiences a stoppage, record the exact mechanism failure: radio brownout, tripped breaker, or mechanical binding. Details matter.",
    "I reside in your browser memory to enforce schema validation and preserve your team's statistical integrity.",
    "Rigorous quantitative scouting wins alliance selections. Let's ensure our telemetry remains pristine—don't let the team down.",
    "Are you measuring precise cycle durations or merely estimating? Milliseconds dictate optimal alliance composition.",
    "Always verify your target team number. A single transposed digit introduces significant skew into our regression models.",
    "Strategic recommendation: Always verify local IndexedDB database snapshots before entering elimination rounds."
];

// ==========================================================================
// ZACHARY SPEECH SYNTHESIS (TTS) ENGINE
// ==========================================================================
let maleEnglishVoice = null;
let voicesLoaded = false;

function loadVoices() {
    if (typeof window === 'undefined' || !window.speechSynthesis) return;
    const voices = window.speechSynthesis.getVoices() || [];
    if (!voices.length) return;
    voicesLoaded = true;

    const maleKeywords = ['david', 'guy', 'george', 'james', 'alex', 'daniel', 'mark', 'richard', 'tom', 'fred', 'male', 'microsoft david', 'google us english male'];
    const enVoices = voices.filter(v => v.lang && v.lang.toLowerCase().startsWith('en'));

    // Prioritize known English male voices
    const matched = enVoices.find(v => {
        const name = v.name.toLowerCase();
        return maleKeywords.some(kw => name.includes(kw));
    });

    maleEnglishVoice = matched || enVoices[0] || voices[0] || null;
}

if (typeof window !== 'undefined' && window.speechSynthesis) {
    loadVoices();
    if (window.speechSynthesis.onvoiceschanged !== undefined) {
        window.speechSynthesis.onvoiceschanged = loadVoices;
    }
}

export function isZacharyMuted() {
    return safeGetItem('obsidianscout:zachary_voice_muted') === 'true';
}

export function setZacharyMuted(muted) {
    safeSetItem('obsidianscout:zachary_voice_muted', muted ? 'true' : 'false');
    if (muted) {
        stopZacharySpeech();
    }
}

export function stopZacharySpeech() {
    if (typeof window !== 'undefined' && window.speechSynthesis) {
        try {
            window.speechSynthesis.cancel();
        } catch (e) {
            // ignore
        }
    }
}

export function speakZachary(text) {
    if (typeof window === 'undefined' || !window.speechSynthesis) return;
    if (isZacharyMuted() || getTutorialMode() !== 'zachary') return;

    try {
        stopZacharySpeech();
        if (!text) return;
        const cleanText = text.replace(/<[^>]*>?/gm, '').trim();
        if (!cleanText) return;

        if (!maleEnglishVoice) {
            loadVoices();
        }

        const utterance = new SpeechSynthesisUtterance(cleanText);
        if (maleEnglishVoice) {
            utterance.voice = maleEnglishVoice;
            utterance.lang = maleEnglishVoice.lang || 'en-US';
        } else {
            utterance.lang = 'en-US';
        }

        utterance.pitch = 1.05; // Articulate, confident pitch
        utterance.rate = 1.08;  // Brisk, professional cadence
        utterance.volume = 1.0;

        setTimeout(() => {
            if (!isZacharyMuted() && getTutorialMode() === 'zachary') {
                window.speechSynthesis.speak(utterance);
            }
        }, 100);
    } catch (e) {
        console.warn('[Tour TTS] Speech synthesis error:', e);
    }
}

// ==========================================================================
// ZACHARY ERROR EXPLAINER (ONLY WHEN ZACHARY MODE IS ENABLED)
// ==========================================================================
let activeErrorPopup = null;
let lastErrorTimestamp = 0;

export function showZacharyErrorExplainer(errorMessage, errorContext = {}) {
    // Strictly verify Zachary mode is enabled
    if (getTutorialMode() !== 'zachary') return;
    if (!errorMessage || typeof errorMessage !== 'string') return;

    // Rate-limit error popups (minimum 3.5s cooldown)
    const now = Date.now();
    if (now - lastErrorTimestamp < 3500) return;
    lastErrorTimestamp = now;

    // Remove any existing error popup
    if (activeErrorPopup) {
        activeErrorPopup.remove();
        activeErrorPopup = null;
    }

    const cleanMsg = errorMessage.replace(/<[^>]*>?/gm, '').trim();
    const explanation = generateTechnicalErrorExplanation(cleanMsg);

    const popup = document.createElement('div');
    popup.className = 'zachary-error-popup';
    popup.innerHTML = `
        <div class="zachary-error-header">
            <div class="zachary-error-avatar-wrap">
                <img src="/assets/images/zachary.png" alt="Zachary" class="zachary-error-avatar" />
                <span class="zachary-error-badge">Diagnostic Assistant</span>
            </div>
            <div class="zachary-error-controls">
                <button type="button" class="btn-error-audio-toggle ${isZacharyMuted() ? 'muted' : ''}" title="${isZacharyMuted() ? 'Unmute voice' : 'Mute voice'}" aria-label="Toggle voice">
                    ${isZacharyMuted()
            ? '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><line x1="23" y1="9" x2="17" y2="15"></line><line x1="17" y1="9" x2="23" y2="15"></line></svg>'
            : '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><path d="M15.54 8.46a5 5 0 0 1 0 7.07"></path></svg>'}
                </button>
                <button type="button" class="zachary-error-close" aria-label="Dismiss error explanation">&times;</button>
            </div>
        </div>
        <div class="zachary-error-content">
            <div class="zachary-error-raw-alert">${cleanMsg}</div>
            <p class="zachary-error-speech">${explanation}</p>
        </div>
    `;

    document.body.appendChild(popup);
    activeErrorPopup = popup;

    // Speak explanation via TTS
    speakZachary(explanation);

    // Audio toggle
    const audioBtn = popup.querySelector('.btn-error-audio-toggle');
    if (audioBtn) {
        audioBtn.addEventListener('click', () => {
            const nextMuted = !isZacharyMuted();
            setZacharyMuted(nextMuted);
            audioBtn.classList.toggle('muted', nextMuted);
            if (!nextMuted) {
                speakZachary(explanation);
            }
        });
    }

    // Close button
    popup.querySelector('.zachary-error-close').addEventListener('click', () => {
        stopZacharySpeech();
        popup.remove();
        activeErrorPopup = null;
    });

    // Auto-dismiss after 9 seconds
    setTimeout(() => {
        if (activeErrorPopup === popup) {
            popup.classList.add('zachary-error-fadeout');
            setTimeout(() => {
                if (activeErrorPopup === popup) {
                    popup.remove();
                    activeErrorPopup = null;
                }
            }, 300);
        }
    }, 9000);
}

function generateTechnicalErrorExplanation(rawMsg) {
    const msg = rawMsg.toLowerCase();
    if (msg.includes("401") || msg.includes("unauthorized") || msg.includes("login") || msg.includes("session")) {
        return "Authentication failure detected. In HTTP client protocols, a 401 response indicates missing or expired session credentials. Please verify your authentication state.";
    }
    if (msg.includes("403") || msg.includes("forbidden") || msg.includes("permission") || msg.includes("access denied")) {
        return "Authorization violation. In role-based access control, this endpoint requires higher administrative privileges than your current profile is granted.";
    }
    if (msg.includes("404") || msg.includes("not found")) {
        return "Resource resolution failure. An HTTP 404 indicates the requested URI does not map to a registered resource or active event in the database.";
    }
    if (msg.includes("409") || msg.includes("conflict") || msg.includes("duplicate") || msg.includes("already")) {
        return "State conflict detected. In relational integrity, a 409 status indicates a unique constraint violation or an unresolvable concurrency collision.";
    }
    if (msg.includes("500") || msg.includes("502") || msg.includes("503") || msg.includes("server error")) {
        return "Internal server exception. The backend service encountered an unhandled execution fault. Check server diagnostics and container logs for stack details.";
    }
    if (msg.includes("network") || msg.includes("failed to fetch") || msg.includes("offline") || msg.includes("connection")) {
        return "Network socket interruption. The client could not establish a TCP connection with the host. Offline mode has been activated to protect your local data.";
    }
    if (msg.includes("json") || msg.includes("syntax") || msg.includes("parse")) {
        return "JSON deserialization failure. The payload contains malformed syntax, unbalanced braces, or invalid data types violating schema specifications.";
    }
    if (msg.includes("required") || msg.includes("empty") || msg.includes("missing") || msg.includes("valid") || msg.includes("invalid")) {
        return "Form validation constraint violated. HTML5 input validation requires all mandatory parameters to be completed with valid data formats prior to dispatch.";
    }
    return `Diagnostic alert: "${rawMsg}". In systems engineering, this signifies an abnormal execution condition. Verify your input parameters and retry.`;
}

// ==========================================================================
// 1-FEATURE MULTI-PAGE TUTORIAL CATALOG (WITH HANDS-ON ACTION REQUIREMENTS)
// ==========================================================================
export const FEATURE_TUTORIALS = [
    {
        id: "feature_event_setup",
        title: "Initial Setup: Event Configuration & API Keys",
        desc: "Essential first step: Configure season year, official event code (e.g. 2026oktu), TBA API key, and optional Statbotics EPA sync.",
        icon: "fa-solid fa-key",
        page: "admin-settings",
        pages: ["admin-settings"],
        duration: "3 min",
        steps: [
            {
                page: "admin-settings",
                target: ".main-content #tab-api, button[data-tab='api']",
                title: "Open API Keys & Event Configuration",
                standardDesc: "Switch to the API Keys tab in Admin Settings to configure competition year, event key, and API integrations.",
                zacharyDesc: "Before any scouting, match prediction, or team analytics can function, we must anchor our system to an official event. Click the 'API keys' tab to open event settings (or press Next if already open).",
                actionRequired: {
                    type: 'tab',
                    skippable: true,
                    instruction: "Click the 'API keys' tab (or press Next to skip)."
                }
            },
            {
                page: "admin-settings",
                target: ".main-content #settings-year",
                title: "Set Competition Season Year",
                standardDesc: "Enter the four-digit FRC/FTC season year (e.g. 2026). If already configured, you can click Next to continue.",
                zacharyDesc: "Enter the active season year into the input field (e.g. '2026'). If you already configured your season year, feel free to press Next to proceed!",
                actionRequired: {
                    type: 'input',
                    skippable: true,
                    instruction: "Type '2026' into Season Year (or press Next if already configured).",
                    dummyValue: '2026'
                }
            },
            {
                page: "admin-settings",
                target: ".main-content #settings-event-code",
                title: "Specify Official Event Code",
                standardDesc: "Enter the event key code (e.g. 'oktu' or 'week0') from The Blue Alliance or FIRST. Click Next to skip if already set.",
                zacharyDesc: "Now provide the official event code, such as 'oktu' or 'week0'. The full event key will combine year and code (e.g. '2026oktu'). Press Next to skip if your event is already saved.",
                actionRequired: {
                    type: 'input',
                    skippable: true,
                    instruction: "Type an event code like 'oktu' (or press Next if already set).",
                    dummyValue: 'oktu'
                }
            },
            {
                page: "admin-settings",
                target: ".main-content #settings-tba-key",
                title: "Enter The Blue Alliance (TBA) API Key",
                standardDesc: "Paste your TBA Read API key to enable live match schedules, rankings, and team roster synchronization. Click Next to skip if already added.",
                zacharyDesc: "Enter your The Blue Alliance Read API token here to pull official qualification schedules and OPR telemetry. If you already have a token saved, press Next to skip.",
                actionRequired: {
                    type: 'input',
                    skippable: true,
                    instruction: "Enter TBA API key (or press Next if already configured).",
                    dummyValue: 'test-tba-token'
                }
            },
            {
                page: "admin-settings",
                target: ".main-content #settings-tba-test, .main-content #settings-save",
                title: "Test Connection & Save API Settings",
                standardDesc: "Click Test Connection to verify your API credentials or Save API settings to commit changes. Click Finish if everything is already configured.",
                zacharyDesc: "Click 'Test Connection' or 'Save API settings' to verify your connection! If your event and keys are already verified, click Finish to conclude setup.",
                actionRequired: {
                    type: 'click',
                    skippable: true,
                    instruction: "Click 'Test Connection' / 'Save' (or press Finish to complete)."
                }
            }
        ]
    },
    {
        id: "feature_dashboard",
        title: "Dashboard & Live Overview",
        desc: "Master your home base: live match progress, scouting completion rate, active event status, and quick shortcuts.",
        icon: "fa-solid fa-gauge-high",
        page: "dashboard",
        pages: ["dashboard"],
        duration: "2 min",
        steps: [
            {
                page: "dashboard",
                target: ".main-content .dashboard-hero-greeting, .main-content h1",
                title: "Dashboard Telemetry",
                standardDesc: "The Dashboard consolidates real-time match scouting progress, logged entries, active events, and quick navigation actions.",
                zacharyDesc: "Welcome to the Dashboard! A well-structured dashboard consolidates mission-critical event telemetry into actionable metrics. Check your logged match counts and submission rates right here."
            },
            {
                page: "dashboard",
                target: ".main-content .dashboard-hero-meta",
                title: "Credentials & Event Sync",
                standardDesc: "Check your assigned team number, current competition program (FRC/FTC), active role, and connected event key.",
                zacharyDesc: "Here are your team credentials and role profile. As a scout, accuracy in data collection is paramount—don't let the team down! If your event key isn't synced, check with the lead scout immediately."
            },
            {
                page: "dashboard",
                target: ".main-content #btn-quick-scout, .main-content .dashboard-hero-actions a",
                title: "Launch Match Scouting",
                standardDesc: "Click the Quick Scout button to jump straight into active match scouting.",
                zacharyDesc: "Let's perform a live action test! Click the Quick Scout button right now to launch into match scouting mode.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click the 'Quick Scout' button to proceed."
                }
            }
        ]
    },
    {
        id: "feature_scout",
        title: "Match Scouting Form",
        desc: "Complete match data entry covering pre-match setup, autonomous scoring, teleop cycles, and endgame climbing.",
        icon: "fa-solid fa-clipboard-check",
        page: "scout",
        pages: ["scout"],
        duration: "3 min",
        steps: [
            {
                page: "scout",
                target: ".main-content #team-select",
                title: "Specify Robot Team Number",
                standardDesc: "Select the team number of the robot you are observing from the dropdown.",
                zacharyDesc: "Match scouting requires absolute precision. Let's begin: select a team number from the dropdown so our dataset associates telemetry with the correct robot.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a team from the dropdown."
                }
            },
            {
                page: "scout",
                target: ".main-content #match-select",
                title: "Select Match Number",
                standardDesc: "Select the qualification match number from the dropdown for this entry.",
                zacharyDesc: "Now select the qualification match from the dropdown to align timing curves with the official schedule.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a match from the dropdown."
                }
            },
            {
                page: "scout",
                target: ".main-content #scouting-tabs, .main-content #form-fields, .main-content .btn-counter, .main-content button[data-field]",
                title: "Log Game Scoring Points",
                standardDesc: "Click an autonomous scoring counter or toggle tabs to log match performance.",
                zacharyDesc: "Scouting is partitioned into Auto, Teleop, and Endgame phases. Click any scoring counter or toggle a phase tab right now to test live logging.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click any counter or phase tab to log points."
                }
            },
            {
                page: "scout",
                target: ".main-content #scout-submit, .main-content #scouting-form button[type='submit']",
                title: "Practice Submission (Safe Intercept)",
                standardDesc: "Click Save Entry to test form validation. In tutorial mode, submissions are safely simulated without writing test data to the live database.",
                zacharyDesc: "Once the match concludes, submit the entry. Click Save Entry now! Don't worry—I will safely intercept the submission so no dummy data pollutes the competition database.",
                actionRequired: {
                    type: 'submit-practice',
                    instruction: "Click 'Save entry' to test submission (safe simulated test)."
                }
            }
        ]
    },
    {
        id: "feature_pit_scout",
        title: "Pit Scouting & Robot Inspection",
        desc: "Conduct physical pit inspections: drivetrain dimensions, mechanism weight, intake speeds, and robot photo uploads.",
        icon: "fa-solid fa-toolbox",
        page: "pit-scout",
        pages: ["pit-scout"],
        duration: "2 min",
        steps: [
            {
                page: "pit-scout",
                target: ".main-content #team-select",
                title: "Select Inspection Target",
                standardDesc: "Select the team number you are inspecting from the dropdown.",
                zacharyDesc: "Pit scouting documents physical robot architecture and drivetrain dimensions. Select a team number from the dropdown to start the inspection log.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a team from the dropdown."
                }
            },
            {
                page: "pit-scout",
                target: ".main-content #form-fields, .main-content #pit-scouting-form, .main-content #form-blocked",
                title: "Document Mechanism Specifications",
                standardDesc: "Inspect the robot's physical configuration: drivetrain architecture, intake speed, shooter angle, and weight.",
                zacharyDesc: "Fill in the physical subsystem metrics—swerve vs tank drive, ground intake velocity, and climb mechanisms. Every detail matters for alliance partner synergy.",
                actionRequired: {
                    type: 'click',
                    instruction: "Inspect the pit form fields or click any input."
                }
            },
            {
                page: "pit-scout",
                target: ".main-content #pit-submit, .main-content #pit-scouting-form button[type='submit']",
                title: "Complete Inspection (Simulated)",
                standardDesc: "Click Save pit entry to complete your pit inspection workflow.",
                zacharyDesc: "Click Save Pit Entry to conclude the inspection practice routine. All test data will be safely verified in sandbox mode.",
                actionRequired: {
                    type: 'submit-practice',
                    instruction: "Click 'Save pit entry' to finish pit inspection test."
                }
            }
        ]
    },
    {
        id: "feature_qual_scout",
        title: "Qualitative Scouting & Subjective Notes",
        desc: "Evaluate subjective attributes like driver agility, defensive resilience, game awareness, and breakdown frequency.",
        icon: "fa-solid fa-star-half-stroke",
        page: "qual-scout",
        pages: ["qual-scout"],
        duration: "2 min",
        steps: [
            {
                page: "qual-scout",
                target: ".main-content #scope-selector-container, .main-content .scope-btn, .main-content #scope-btn-team",
                title: "Choose Qualitative Scouting Scope",
                standardDesc: "Select whether you are evaluating a single team, an entire alliance (Red/Blue), or both alliances simultaneously.",
                zacharyDesc: "Qualitative scouting captures nuance that raw numbers miss. Choose your scouting scope: evaluate a single robot target or an entire alliance at once.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click any scope button (e.g. Single Team, Red Alliance, or Blue Alliance)."
                }
            },
            {
                page: "qual-scout",
                target: ".main-content #match-select",
                title: "Select Match Number",
                standardDesc: "Select the match you are observing from the dropdown.",
                zacharyDesc: "Select the qualification match from the dropdown to link your subjective driver ratings to match video archives.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a match from the dropdown."
                }
            },
            {
                page: "qual-scout",
                target: ".main-content #team-select",
                title: "Specify Observed Robot",
                shouldSkip: () => {
                    const activeScopeBtn = document.querySelector('.scope-btn.active');
                    if (activeScopeBtn && activeScopeBtn.dataset.scope && activeScopeBtn.dataset.scope !== 'team') {
                        return true;
                    }
                    const teamFieldContainer = document.getElementById('team-field-container');
                    if (teamFieldContainer && (teamFieldContainer.classList.contains('hidden') || teamFieldContainer.style.display === 'none')) {
                        return true;
                    }
                    return false;
                },
                standardDesc: "Select the specific team from the dropdown being evaluated for driver agility and defense.",
                zacharyDesc: "Now select the robot team from the dropdown under evaluation. Quantitative counters track points; qualitative notes track driver poise and defense evasion.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a team from the dropdown."
                }
            },
            {
                page: "qual-scout",
                target: ".main-content #scout-submit, .main-content #scouting-form button[type='submit']",
                title: "Submit Qualitative Assessment",
                standardDesc: "Click Save entry to conclude logging your qualitative observations.",
                zacharyDesc: "Click Save Entry to submit your subjective evaluation. In alliance mode, all alliance robot entries are bundled and verified together!",
                actionRequired: {
                    type: 'submit-practice',
                    instruction: "Click 'Save entry' to complete qualitative practice."
                }
            }
        ]
    },
    {
        id: "feature_qr_scanner",
        title: "Offline QR Scanner & Sync",
        desc: "Sync match data across devices without internet access by displaying and scanning compressed, animated QR codes.",
        icon: "fa-solid fa-qrcode",
        page: "qr-scanner",
        pages: ["qr-scanner"],
        duration: "2 min",
        steps: [
            {
                page: "qr-scanner",
                target: ".main-content #btn-toggle-scan, .main-content .scanner-video-wrapper, .main-content #reader",
                title: "Optical QR Data Ingestion",
                standardDesc: "Point your device camera at another scout's animated QR code to ingest data completely offline.",
                zacharyDesc: "When venue Wi-Fi becomes congested or unavailable, our animated QR transfer protocol ensures uninterrupted telemetry flow. Click Start Scanning to test camera activation.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click 'Start Scanning' or the camera viewport."
                }
            },
            {
                page: "qr-scanner",
                target: ".main-content #paste-input, .main-content #btn-submit-paste",
                title: "Manual Clipboard & Raw Ingestion",
                standardDesc: "If camera access is unavailable, paste raw QR string tokens or JSON exports directly into the text box.",
                zacharyDesc: "Redundancy is critical in robotics. If optical scanning is obstructed, paste the compressed QR payload directly into this text field.",
                actionRequired: {
                    type: 'input',
                    instruction: "Click or type in the paste input area.",
                    dummyValue: 'OSC:DEMO_MATCH_DATA'
                }
            }
        ]
    },
    {
        id: "feature_cache_manager",
        title: "Cache Manager & Draft Recovery",
        desc: "Inspect offline cache storage, review unsubmitted form drafts, manage local database tables, and force re-syncs.",
        icon: "fa-solid fa-database",
        page: "cache-manager",
        pages: ["cache-manager"],
        duration: "2 min",
        steps: [
            {
                page: "cache-manager",
                target: ".main-content #tab-btn-pending, .main-content #tab-btn-history, .main-content .tab-nav",
                title: "Inspect Offline Cache & History Tabs",
                standardDesc: "Switch between device history logs and pending offline scouting entries waiting for sync.",
                zacharyDesc: "The Cache Manager audits your browser's IndexedDB and localStorage queues. Click between the Pending Offline Cache and Device History tabs to inspect queued entries.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click between the History and Pending tabs (or press Next to continue)."
                }
            },
            {
                page: "cache-manager",
                target: ".main-content #btn-export-history, .main-content #btn-sync-all, .main-content #btn-export-cache, .main-content #btn-import-history, .main-content .stats-grid, .main-content .card",
                title: "Upload & Export Queued Records",
                standardDesc: "Trigger batch synchronization or download cache backups to prevent data loss.",
                zacharyDesc: "Whenever network connectivity is restored, batch upload your offline queue or export a JSON backup snapshot. Click an export button or click Finish to complete the tour!",
                actionRequired: {
                    type: 'click',
                    instruction: "Click 'Export History Backup (JSON)', 'Upload All Caches', or press Finish."
                }
            }
        ]
    },
    {
        id: "feature_all_data",
        title: "All Data Table & Advanced Filtering",
        desc: "Browse, filter, search, sort, and export the master collection of all scouting submissions across the event.",
        icon: "fa-solid fa-table-list",
        page: "all-data",
        pages: ["all-data"],
        duration: "2 min",
        steps: [
            {
                page: "all-data",
                target: ".main-content #team-search",
                title: "Filter Master Spreadsheet",
                standardDesc: "Type a team number or name in the search bar to filter recorded match entries.",
                zacharyDesc: "This master table consolidates every recorded submission across the competition. Type '254' in the search field to filter the dataset instantly.",
                actionRequired: {
                    type: 'input',
                    instruction: "Type '254' in the search filter.",
                    dummyValue: '254'
                }
            },
            {
                page: "all-data",
                target: ".main-content #type-filter, .main-content #sort-select",
                title: "Filter by Entry Type & Sort Order",
                standardDesc: "Select an entry type or sort order from the dropdowns.",
                zacharyDesc: "Select an entry type or sort order from the dropdowns to isolate specific scouting categories.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select an entry type or sort option from the dropdown (or press Next to continue)."
                }
            },
            {
                page: "all-data",
                target: ".main-content #export-csv",
                title: "Export Master Dataset to CSV",
                standardDesc: "Click Export CSV to download the complete spreadsheet for external spreadsheet analysis.",
                zacharyDesc: "Click 'Export CSV' to generate a standardized CSV file compatible with Python, R, or Excel spreadsheet pipelines.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click the 'Export CSV' button."
                }
            }
        ]
    },
    {
        id: "feature_match_data",
        title: "Match Data & Breakdown",
        desc: "Review match-by-match timelines, autonomous breakdown statistics, and performance histories for any robot.",
        icon: "fa-solid fa-chart-line",
        page: "match-data",
        pages: ["match-data"],
        duration: "2 min",
        steps: [
            {
                page: "match-data",
                target: ".main-content #team-search, .main-content #event-filter",
                title: "Filter Match Breakdown by Team",
                standardDesc: "Search or filter by team number to isolate their historical match scores.",
                zacharyDesc: "Consistency across qualification matches is the hallmark of championship teams. Type a team number in the search field to isolate their match scoring timeline.",
                actionRequired: {
                    type: 'input',
                    instruction: "Type a team number (e.g. '1678') in the search bar.",
                    dummyValue: '1678'
                }
            },
            {
                page: "match-data",
                target: ".main-content #export-match-csv, .main-content #match-data-table",
                title: "Inspect Score Breakdowns & Export",
                standardDesc: "Review individual scouter entries, timestamps, and score breakdowns or export match CSV.",
                zacharyDesc: "Click any row or export the match table to analyze autonomous vs teleop point contributions across matches.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click any table row or the 'Export CSV' button."
                }
            }
        ]
    },
    {
        id: "feature_data_validation",
        title: "Data Validation & Anomaly Detection",
        desc: "Automatically flag duplicate entries, statistical outliers, missing match logs, and conflicting alliance scores.",
        icon: "fa-solid fa-shield-halved",
        page: "data-validation",
        pages: ["data-validation"],
        duration: "2 min",
        steps: [
            {
                page: "data-validation",
                target: ".main-content #event-select",
                title: "Select Event for Audit",
                standardDesc: "Select an active event from the dropdown to run comprehensive mathematical integrity checks.",
                zacharyDesc: "Data validation ensures our models aren't corrupted by typo errors. Select an active event from the dropdown to initiate the audit (or press Next if already selected).",
                actionRequired: {
                    type: 'change',
                    instruction: "Select an event from the dropdown (or press Next if already selected)."
                }
            },
            {
                page: "data-validation",
                target: ".main-content #threshold-select, .main-content #filter-status",
                title: "Set Anomaly Threshold Delta",
                standardDesc: "Select point delta thresholds from the dropdown to detect discrepancies between scouted totals and official scores.",
                zacharyDesc: "Select the anomaly delta threshold from the dropdown. A discrepancy of 15+ points between recorded scouting sums and official alliance scores flags human input error.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select an anomaly threshold option from the dropdown (or press Next if already selected)."
                }
            },
            {
                page: "data-validation",
                target: ".main-content button[data-tab='matches-view'], .main-content button[data-tab='teams-view']",
                title: "Toggle Score Validation Views",
                standardDesc: "Switch between Match Score Audits and Team EPA / OPR delta comparisons.",
                zacharyDesc: "Toggle between the Match Score Validation view and Team EPA/OPR comparisons to isolate anomalous scouter entries.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click between the Match and Team validation tabs (or press Finish to complete)."
                }
            }
        ]
    },
    {
        id: "feature_analytics",
        title: "Team Analytics & Performance Metrics",
        desc: "Deep-dive statistical analysis: empirical averages, standard deviations, cycle distributions, and synced Statbotics EPA ratings.",
        icon: "fa-solid fa-square-poll-vertical",
        page: "analytics",
        pages: ["analytics"],
        duration: "2 min",
        steps: [
            {
                page: "analytics",
                target: ".main-content #analytics-grid, .main-content .card",
                title: "Inspect Scouting Metrics & Statbotics EPA",
                standardDesc: "Analyze empirical match metrics (averages, standard deviations, cycle counts) alongside external EPA metrics synced from Statbotics.",
                zacharyDesc: "Here you can inspect our locally calculated scoring averages, cycle distributions, and standard deviations alongside EPA metrics synced from Statbotics. Remember: ObsidianScout calculates empirical scouting aggregates locally, while EPA originates from Statbotics' Elo-based models!"
            }
        ]
    },
    {
        id: "feature_compare",
        title: "Team Comparison Tool",
        desc: "Direct head-to-head comparison between 2 or 3 robots with side-by-side metric charts and strengths/weaknesses.",
        icon: "fa-solid fa-code-compare",
        page: "compare",
        pages: ["compare"],
        duration: "2 min",
        steps: [
            {
                page: "compare",
                target: ".main-content #team-1-select",
                title: "Select Primary Candidate Robot",
                standardDesc: "Select the first robot team from the dropdown for comparison.",
                zacharyDesc: "The head-to-head comparison tool lets strategists evaluate robots side-by-side for alliance pick selection. Select your first candidate team from the dropdown.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select Team 1 from the dropdown."
                }
            },
            {
                page: "compare",
                target: ".main-content #team-2-select",
                title: "Select Secondary Candidate Robot",
                standardDesc: "Select the second robot team from the dropdown for side-by-side comparison.",
                zacharyDesc: "Now select the second candidate team from the adjacent dropdown to generate comparative metric bars.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select Team 2 from the dropdown."
                }
            },
            {
                page: "compare",
                target: ".main-content #compare-results-container, .main-content #team-3-select",
                title: "Inspect Head-to-Head Comparison",
                standardDesc: "Review side-by-side metric breakdowns, best values highlighted in green, and optional third team comparison.",
                zacharyDesc: "Notice the instant comparative breakdown! Highest values are highlighted in green, letting you immediately spot superior scoring cycles or defense capabilities."
            }
        ]
    },
    {
        id: "feature_graphs",
        title: "Interactive Scatter Plots & Trend Charts",
        desc: "Generate interactive scatter plots, bubble charts, and radar graphs to visually spot high-performing robots.",
        icon: "fa-solid fa-chart-area",
        page: "graphs",
        pages: ["graphs"],
        duration: "2 min",
        steps: [
            {
                page: "graphs",
                target: ".main-content #select-top-teams, .main-content #event-filter, .main-content #team-search-input",
                title: "Select Teams for Graphing",
                standardDesc: "Click 'Top 8', 'Select all', or search teams to plot on the graph.",
                zacharyDesc: "Visual analytics reveal clusters and outliers that raw tables conceal. Click 'Top 8' or select teams to add them to the graphing canvas.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click 'Top 8' or 'Select all' to choose teams."
                }
            },
            {
                page: "graphs",
                target: ".main-content #graph-metric, .main-content #graph-view",
                title: "Configure Metric & Data View",
                standardDesc: "Select the scoring metric and data view from the dropdowns.",
                zacharyDesc: "Select which metric to analyze from the dropdown (e.g. Total Points or Auto Points) and toggle between aggregate team averages or match-by-match timelines.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a metric from the dropdown (or press Next to continue)."
                }
            },
            {
                page: "graphs",
                target: ".main-content .graph-type-checkbox[value='bar'], .main-content .graph-type-checkbox, .main-content #graph-type-grid",
                title: "Select Graph Visualization Type",
                standardDesc: "Check Bar, Line, Scatter, Box, or Radar chart checkboxes to render the visual graph.",
                zacharyDesc: "Check the Bar or Scatter plot checkbox to select visual graph types. High performers will immediately stand out!",
                actionRequired: {
                    type: 'click',
                    instruction: "Check any graph type checkbox (e.g. Bar or Scatter)."
                }
            },
            {
                page: "graphs",
                target: ".main-content #graph-generate, .graphs-page #graph-generate",
                title: "Generate Visual Graphs",
                standardDesc: "Click Generate graphs to compute and render the selected charts and statistical trends.",
                zacharyDesc: "Now click 'Generate graphs' to compute statistical distributions and render the high-resolution charts!",
                actionRequired: {
                    type: 'click',
                    instruction: "Click 'Generate graphs' to render the visual charts."
                }
            }
        ]
    },
    {
        id: "feature_predictor",
        title: "Match Outcome Predictor",
        desc: "Simulate any 3v3 alliance matchup with win probabilities, predicted final scores, and key deciding factors.",
        icon: "fa-solid fa-wand-magic-sparkles",
        page: "predictor",
        pages: ["predictor"],
        duration: "2 min",
        steps: [
            {
                page: "predictor",
                target: ".main-content #match-select",
                title: "Choose Match for Simulation",
                standardDesc: "Select any qualification or playoff match from the dropdown to load the competing Red and Blue alliances.",
                zacharyDesc: "Our match predictor calculates alliance win probabilities and projected score margins. Select an upcoming match from the dropdown to load competing alliances.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a match from the dropdown."
                }
            },
            {
                page: "predictor",
                target: ".main-content #datasource-select, .main-content #datasource-field",
                title: "Select Predictive Data Source",
                standardDesc: "Select whether to predict using Scouted Data, Statbotics EPA, TBA OPR, or all three combined from the dropdown.",
                zacharyDesc: "Select the calculation telemetry source from the dropdown: choose our empirical local scouted data, external Statbotics EPA ratings, or TBA OPR.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a data source from the dropdown."
                }
            },
            {
                page: "predictor",
                target: ".main-content #predictor-workspace, .main-content .card",
                title: "Inspect Projected Match Outcome",
                standardDesc: "Review the projected winning alliance, win percentage, and component score breakdown.",
                zacharyDesc: "Here are the simulated outcomes, win probabilities, and key deciding factors. Use this data to advise drive teams on match strategy before heading to the field!"
            }
        ]
    },
    {
        id: "feature_alliances",
        title: "Scouting Alliances & Shared Data",
        desc: "Establish real-time data sharing partnerships with other teams on this server to pool match, pit, and qualitative scouting.",
        icon: "fa-solid fa-people-group",
        page: "alliances",
        pages: ["alliances"],
        duration: "2 min",
        steps: [
            {
                page: "alliances",
                target: ".main-content .section-header, .main-content .sharing-notice, .main-content h1",
                title: "Collaborative Scouting Partnerships",
                standardDesc: "Scouting alliances allow multiple partner teams to pool match, pit, and qualitative data in real time without manual exports.",
                zacharyDesc: "Collaborative scouting expands our data collection throughput. Partner teams pool all scouting telemetry seamlessly in real time."
            },
            {
                page: "alliances",
                target: ".main-content #btn-create-alliance",
                title: "Create New Alliance Partnership",
                standardDesc: "Click '+ New Alliance' to open the alliance creation dialog.",
                zacharyDesc: "Click '+ New Alliance' to open the partnership creation modal and configure shared event settings.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click '+ New Alliance' to open the alliance dialog (or press Next to continue)."
                }
            },
            {
                page: "alliances",
                target: "#form-alliance #alliance-name, #modal-alliance, .main-content #alliances-grid, .main-content .card",
                title: "Configure Alliance & Invite Teams",
                standardDesc: "Name your alliance, specify event details, and send invitations to partner team numbers.",
                zacharyDesc: "Name your alliance, specify event details, and send invitations to partner team numbers. Once accepted, their scouting records sync automatically!",
                actionRequired: {
                    type: 'input',
                    instruction: "Type an alliance name (or press Finish to complete).",
                    dummyValue: 'Thunder Alliance'
                }
            }
        ]
    },
    {
        id: "feature_alliance_selection",
        title: "Playoff Alliance Selection & Draft",
        desc: "Live 8-alliance draft board during tournament playoffs with algorithmically ranked team recommendations, EPA, and OPR metrics.",
        icon: "fa-solid fa-trophy",
        page: "alliance-selection",
        pages: ["alliance-selection"],
        duration: "3 min",
        steps: [
            {
                page: "alliance-selection",
                target: ".main-content #team-search, .main-content #metric-select",
                title: "Search Candidates & Filter Ranking Metrics",
                standardDesc: "Filter available teams by number or toggle between Weighted, Scouted, EPA, and OPR ranking models from the dropdown.",
                zacharyDesc: "Type a team number in the search box or switch ranking metrics from the dropdown to find complementary partner robots for your playoff run.",
                actionRequired: {
                    type: 'input',
                    instruction: "Type a team number in the search box (or press Next to continue).",
                    dummyValue: '254',
                    skippable: true
                }
            },
            {
                page: "alliance-selection",
                target: ".main-content #recommendations-list-container, .main-content .recs-list-card",
                title: "Data-Driven Pick Recommendations",
                standardDesc: "Review dynamically ranked candidate robots based on weighted empirical scouting averages, Statbotics EPA, and TBA OPR.",
                zacharyDesc: "The recommendation engine dynamically ranks the best available robots using empirical scouting data and Statbotics EPA ratings.",
                actionRequired: {
                    type: 'click',
                    instruction: "Inspect the recommendation cards or click any team (or press Next to continue)."
                },
                skippable: true
            },
            {
                page: "alliance-selection",
                target: ".main-content #alliances-grid-container, .main-content .alliances-grid, .main-content .alliance-card",
                title: "Live 8-Alliance Draft Board",
                standardDesc: "Track all 8 captain seeds, first picks, second picks, and backup robots in real time during tournament alliance selection. Click any slot to assign a team.",
                zacharyDesc: "During tournament playoff selection, this 8-alliance draft board tracks captains and partner selections across all rounds with full undo and print capabilities.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click inside the 8-alliance draft board (or press Finish to complete)."
                },
                skippable: true
            }
        ]
    },
    {
        id: "feature_users",
        title: "User Accounts & Permission Roles",
        desc: "Administer scout accounts, assign Scout/Analytics/Admin roles, issue invite codes, and manage passkey credentials.",
        icon: "fa-solid fa-users-gear",
        page: "users",
        pages: ["users"],
        duration: "2 min",
        steps: [
            {
                page: "users",
                target: ".main-content #user-username, .main-content #user-form",
                title: "Provision New Scout Accounts",
                standardDesc: "Enter username, email, team number, and password to provision new scout accounts.",
                zacharyDesc: "Security and account auditing begin here. Type a sample username into the user creation field.",
                actionRequired: {
                    type: 'input',
                    instruction: "Type a sample username in the Username field.",
                    dummyValue: 'scout_lead'
                }
            },
            {
                page: "users",
                target: ".main-content #user-role",
                title: "Assign Role Permissions",
                standardDesc: "Select permission roles from the dropdown: Scout (data collection), Analytics (charts & strategy), or Admin (schema & system settings).",
                zacharyDesc: "Select the appropriate role from the dropdown: Scout for match data collectors, Analytics for strategists, and Admin for configuration managers.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select a role from the dropdown."
                }
            },
            {
                page: "users",
                target: ".main-content #search-username, .main-content #users-table",
                title: "Audit Active Team Accounts",
                standardDesc: "Search team members and manage role delegations.",
                zacharyDesc: "Search through current registered team members to audit access permissions.",
                actionRequired: {
                    type: 'input',
                    instruction: "Type in the search field to test account filtering.",
                    dummyValue: 'admin'
                }
            }
        ]
    },
    {
        id: "feature_admin_settings",
        title: "Admin Settings & Scouting Form Schema",
        desc: "Multi-page admin workflow: Configure event keys, customize scouting form fields in visual and raw JSON editor, and set role permissions.",
        icon: "fa-solid fa-gears",
        page: "admin-settings",
        pages: ["admin-settings"],
        duration: "4 min",
        steps: [
            {
                page: "admin-settings",
                target: ".main-content #tab-config, button[data-tab='config']",
                title: "Visual Form Customizer",
                standardDesc: "Click the Scouting Configs tab to customize form fields, counters, ratings, and sections.",
                zacharyDesc: "Every FIRST robotics game has distinct scoring objectives. Click the 'Scouting configs' tab to access the form builder.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click the 'Scouting configs' tab."
                }
            },
            {
                page: "admin-settings",
                target: ".main-content button[data-config-kind='game'], .main-content button[data-config-kind='pit'], .main-content button[data-config-kind='qual']",
                title: "Select Form Category",
                standardDesc: "Switch between Match Game form, Pit inspection form, and Qualitative observation form schemas.",
                zacharyDesc: "You can customize schemas independently: click between the Game form, Pit form, and Qualitative form buttons.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click any form kind tab (Game, Pit, or Qualitative)."
                }
            },
            {
                page: "admin-settings",
                target: ".main-content #btn-raw-editor, .main-content #visual-editor-container",
                title: "Raw JSON Schema Editor",
                standardDesc: "Click Raw JSON to edit the raw schema configuration directly with instant JSON validation.",
                zacharyDesc: "For advanced schema configuration and version control backups, click Raw JSON to inspect the raw schema editor.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click 'Raw JSON' to inspect the schema editor."
                }
            }
        ]
    },
    {
        id: "feature_backup",
        title: "Data Sharing & Database Backups",
        desc: "Export full database backups, merge datasets between team members, and generate JSON export bundles.",
        icon: "fa-solid fa-floppy-disk",
        page: "backup",
        pages: ["backup"],
        duration: "2 min",
        steps: [
            {
                page: "backup",
                target: ".main-content #export-type, .main-content #export-format",
                title: "Configure Database Backup Scope",
                standardDesc: "Select whether to export scouting entries only or the entire team database from the dropdowns.",
                zacharyDesc: "Data redundancy prevents catastrophe. Select your export type and format from the dropdowns.",
                actionRequired: {
                    type: 'change',
                    instruction: "Select an export format or data type from the dropdown."
                }
            },
            {
                page: "backup",
                target: ".main-content #btn-export",
                title: "Export & Download Backup Snapshot",
                standardDesc: "Click Export and Download to generate an encrypted snapshot file.",
                zacharyDesc: "Click 'Export and Download' to test backup bundle generation.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click 'Export and Download' to test backup."
                }
            },
            {
                page: "backup",
                target: ".main-content #drop-zone",
                title: "Import & Merge Database Files",
                standardDesc: "Drag and drop backup files into the import zone to merge records from other servers.",
                zacharyDesc: "To restore from a backup or merge records collected by other scouts, drop the database file into this upload zone.",
                actionRequired: {
                    type: 'click',
                    instruction: "Click or drag into the import drop zone."
                }
            }
        ]
    }
];

// ==========================================================================
// ADAPTIVE PERMISSION FILTERING
// ==========================================================================
export function getAccessibleTutorials(me) {
    if (!me) return [];
    return FEATURE_TUTORIALS.filter(tut => {
        return tut.pages.every(page => isPageAccessible(page, me.role));
    });
}

export function getTutorialById(tutId) {
    return FEATURE_TUTORIALS.find(t => t.id === tutId) || null;
}

// ==========================================================================
// TUTORIAL GUIDE MODE (ZACHARY VS STANDARD VS DISABLED)
// ==========================================================================
export function getTutorialMode() {
    const saved = safeGetItem('obsidianscout:tutorial_mode');
    if (saved) return saved;
    return 'standard';
}

export function setTutorialMode(mode) {
    safeSetItem('obsidianscout:tutorial_mode', mode);
    if (mode !== 'zachary') {
        stopZacharySpeech();
        if (activeErrorPopup) {
            activeErrorPopup.remove();
            activeErrorPopup = null;
        }
    }
    syncTourProgressToServer().catch(() => { });
}

// ==========================================================================
// PROGRESS TRACKING & SERVER SYNC
// ==========================================================================
export function getCompletedTutorials() {
    try {
        const raw = safeGetItem('obsidianscout:tour_completed_list');
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

export function isTutorialCompleted(tutId) {
    return getCompletedTutorials().includes(tutId);
}

export function markTutorialCompleted(tutId) {
    const list = getCompletedTutorials();
    if (!list.includes(tutId)) {
        list.push(tutId);
        safeSetItem('obsidianscout:tour_completed_list', JSON.stringify(list));
    }
    syncTourProgressToServer().catch(() => { });
}

export function resetAllTutorialProgress() {
    stopZacharySpeech();
    safeRemoveItem('obsidianscout:tour_active');
    safeRemoveItem('obsidianscout:tour_active_id');
    safeRemoveItem('obsidianscout:tour_step_index');
    safeSetItem('obsidianscout:tour_completed_list', JSON.stringify([]));
    syncTourProgressToServer().catch(() => { });
}

export function getNextIncompleteTutorial(me) {
    const accessible = getAccessibleTutorials(me);
    const completed = getCompletedTutorials();
    return accessible.find(t => !completed.includes(t.id)) || accessible[0] || null;
}

export async function syncTourProgressToServer() {
    if (!navigator.onLine) return;
    const active = safeGetItem('obsidianscout:tour_active') === 'true' ? safeGetItem('obsidianscout:tour_active_id') : null;
    const stepIndex = parseInt(safeGetItem('obsidianscout:tour_step_index') || '0', 10);
    const completed = getCompletedTutorials();
    const mode = getTutorialMode();

    try {
        await request('/api/user/tour-progress', {
            method: 'POST',
            json: { active, stepIndex, completed, mode }
        });
    } catch (e) {
        console.warn('[Tour] Failed to sync progress to server:', e);
    }
}

// ==========================================================================
// TOUR EXECUTION & LIFECYCLE
// ==========================================================================
export async function initTour(me) {
    if (!me) return;

    const currentPage = document.body ? document.body.dataset.page : null;
    const isHubOrAuthPage = currentPage === 'tutorials' || currentPage === 'login' || currentPage === 'reset-password';

    // Check if there is an active tour right away (only on feature pages, not the hub)
    const isActive = safeGetItem('obsidianscout:tour_active') === 'true';
    if (isActive && !isHubOrAuthPage) {
        setTimeout(() => {
            runActiveTourStep(me);
        }, 150);
    }

    // Background server sync
    const hasFetched = sessionStorage.getItem('obsidianscout:tour_fetched') === 'true';
    if (navigator.onLine && !hasFetched) {
        sessionStorage.setItem('obsidianscout:tour_fetched', 'true');
        try {
            const res = await request('/api/user/tour-progress');
            if (res) {
                if (res.active && !isActive && !isHubOrAuthPage) {
                    safeSetItem('obsidianscout:tour_active', 'true');
                    safeSetItem('obsidianscout:tour_active_id', res.active);
                    safeSetItem('obsidianscout:tour_step_index', String(res.stepIndex || 0));
                    setTimeout(() => runActiveTourStep(me), 150);
                }
                if (res.completed && Array.isArray(res.completed)) {
                    safeSetItem('obsidianscout:tour_completed_list', JSON.stringify(res.completed));
                }
                if (res.mode) {
                    safeSetItem('obsidianscout:tutorial_mode', res.mode);
                }
            }
        } catch (e) {
            console.warn('[Tour] Failed to load progress from server:', e);
        }
    }
}

export function startTour(me, tutorialId) {
    const mode = getTutorialMode();
    if (mode === 'disabled') {
        showToast('Tutorials are currently disabled in settings.', 'info');
        return;
    }

    const tut = getTutorialById(tutorialId);
    if (!tut) {
        console.warn('[Tour] Unknown tutorial ID:', tutorialId);
        return;
    }

    safeSetItem('obsidianscout:tour_active', 'true');
    safeSetItem('obsidianscout:tour_active_id', tutorialId);
    safeSetItem('obsidianscout:tour_step_index', '0');
    syncTourProgressToServer().catch(() => { });
    runActiveTourStep(me);
}

export function endTour(completedTutorialId = null) {
    stopZacharySpeech();
    clearTourDOM();
    safeRemoveItem('obsidianscout:tour_active');
    safeRemoveItem('obsidianscout:tour_active_id');
    safeRemoveItem('obsidianscout:tour_step_index');

    if (completedTutorialId) {
        markTutorialCompleted(completedTutorialId);
    } else {
        syncTourProgressToServer().catch(() => { });
    }
}

export function isStepVisible(step, me = null) {
    if (!step) return false;
    if (typeof step.shouldSkip === 'function') {
        try {
            if (step.shouldSkip(me)) return false;
        } catch (e) {
            console.warn('[Tour] Error in step shouldSkip:', e);
        }
    }
    return true;
}

export function getNextVisibleStepIndex(steps, fromIndex, direction = 1, me = null) {
    let idx = fromIndex;
    while (idx >= 0 && idx < steps.length) {
        if (isStepVisible(steps[idx], me)) {
            return idx;
        }
        idx += direction;
    }
    return idx;
}

export function runActiveTourStep(me) {
    const mode = getTutorialMode();
    if (mode === 'disabled') {
        endTour();
        return;
    }

    const tutorialId = safeGetItem('obsidianscout:tour_active_id');
    const tut = getTutorialById(tutorialId);
    if (!tut) {
        endTour();
        return;
    }

    let stepIndex = parseInt(safeGetItem('obsidianscout:tour_step_index') || '0', 10);
    if (isNaN(stepIndex)) stepIndex = 0;

    const steps = tut.steps || [];
    if (!steps.length || stepIndex >= steps.length) {
        endTour(tut.id);
        showToast(`Tutorial completed: ${tut.title}!`, 'success');
        return;
    }

    // Skip any hidden / conditional steps dynamically
    const validStepIndex = getNextVisibleStepIndex(steps, stepIndex, 1, me);
    if (validStepIndex >= steps.length) {
        endTour(tut.id);
        showToast(`Tutorial completed: ${tut.title}!`, 'success');
        return;
    }
    if (validStepIndex !== stepIndex) {
        safeSetItem('obsidianscout:tour_step_index', String(validStepIndex));
        stepIndex = validStepIndex;
    }

    const step = steps[stepIndex];
    const currentPage = document.body ? document.body.dataset.page : null;

    // Multi-page navigation check: if step is on another page, navigate there
    if (step.page && step.page !== currentPage) {
        stopZacharySpeech();
        const fallbackUrls = {
            "settings": "/config",
            "backup": "/backup",
            "migration": "/migration",
            "alliance-edit": "/alliance-edit",
            "alliance-selection": "/alliance-selection",
            "admin-settings": "/admin-settings",
            "cache-manager": "/cache-manager",
            "data-validation": "/data-validation",
            "qual-data": "/qual-data",
            "pit-data": "/pit-data",
            "all-data": "/all-data",
            "pit-scout": "/pit-scout",
            "qual-scout": "/qual-scout",
            "qr-scanner": "/qr-scanner",
            "dashboard": "/dashboard",
            "scout": "/scout",
            "analytics": "/analytics",
            "compare": "/compare",
            "graphs": "/graphs",
            "predictor": "/predictor",
            "event-predictor": "/event-predictor",
            "alliances": "/alliances",
            "events": "/events",
            "teams": "/teams",
            "users": "/users",
            "banners": "/banners",
            "chat": "/chat",
            "prescout": "/prescout",
            "prescout-scout": "/prescout-scout",
            "prescout-pit": "/prescout-pit",
            "prescout-qual": "/prescout-qual"
        };
        const targetUrl = fallbackUrls[step.page] || ("/" + step.page);
        if (window.location.pathname !== targetUrl && !window.location.pathname.endsWith(targetUrl)) {
            window.location.href = targetUrl;
            return;
        }
    }

    // Fast-path: Immediate synchronous check for already-rendered elements (0ms latency)
    const immediateEl = step.target ? findTourTargetElement(step.target) : null;
    if (immediateEl && isElementVisible(immediateEl)) {
        displayTourStepPopup(immediateEl, step, stepIndex, steps.length, tut, me);
        return;
    }

    // Wait for dynamic element to render in DOM with scoped resolution
    let retries = 0;
    const maxRetries = 30;
    const checkInterval = setInterval(() => {
        let el = null;
        if (step.target) {
            el = findTourTargetElement(step.target);
        }
        if (el && isElementVisible(el)) {
            clearInterval(checkInterval);
            displayTourStepPopup(el, step, stepIndex, steps.length, tut, me);
        } else {
            retries++;
            if (retries >= maxRetries) {
                clearInterval(checkInterval);
                const fallbackEl = el || document.querySelector('.main-content, .card, main') || targetElFallback();
                displayTourStepPopup(fallbackEl, step, stepIndex, steps.length, tut, me);
            }
        }
    }, 80);
}

export function findTourTargetElement(selector) {
    if (!selector) return null;
    const parts = selector.split(',').map(s => s.trim()).filter(Boolean);

    // First pass: try matching within .main-content, #main-content, main, .app-main
    for (const part of parts) {
        try {
            const matches = document.querySelectorAll(`.main-content ${part}, #main-content ${part}, main ${part}`);
            for (const el of matches) {
                if (!isExcludedElement(el) && isElementVisible(el)) {
                    return el;
                }
            }
        } catch (e) { }
    }

    // Second pass: try direct selector, strictly excluding sidebar/header elements
    for (const part of parts) {
        try {
            const matches = document.querySelectorAll(part);
            for (const el of matches) {
                if (!isExcludedElement(el) && isElementVisible(el)) {
                    return el;
                }
            }
        } catch (e) { }
    }

    // Third pass: find element even if currently display-none or hidden tab
    for (const part of parts) {
        try {
            const matches = document.querySelectorAll(`.main-content ${part}, #main-content ${part}, main ${part}, ${part}`);
            for (const el of matches) {
                if (!isExcludedElement(el)) {
                    return el;
                }
            }
        } catch (e) { }
    }

    return null;
}

function isExcludedElement(el) {
    if (!el) return true;
    return !!el.closest('.sidebar, .app-sidebar, #app-sidebar, #lang-select, .app-header, header, .top-nav');
}

function isElementVisible(el) {
    if (!el) return false;
    return !!(el.offsetWidth > 0 || el.offsetHeight > 0 || (el.getClientRects && el.getClientRects().length > 0));
}

function targetElFallback() {
    return document.querySelector('.main-content') || document.body;
}

// ==========================================================================
// POPUP & ZACHARY DOM RENDERING WITH INTERACTIVE VALIDATION
// ==========================================================================
let activePopup = null;
let activeBackdrop = null;

export function displayTourStepPopup(targetEl, step, stepIndex, totalSteps, tut, me) {
    stopZacharySpeech();
    clearTourDOM();

    // Dismiss any active backdrop modals if the new step is not targeting an element inside them
    document.querySelectorAll('.modal-backdrop.open, .modal.open, .modal-backdrop.show, .modal.show, [id$="-modal-backdrop"].open, #modal-alliance.open, #selector-modal-backdrop.open, #breakdown-modal-backdrop.open').forEach(modal => {
        if (!targetEl || !modal.contains(targetEl)) {
            modal.classList.remove('open');
            modal.classList.remove('show');
            if (modal.style.display && modal.style.display !== 'none' && !modal.classList.contains('tour-backdrop')) {
                modal.style.display = '';
            }
        }
    });

    const mode = getTutorialMode();
    if (mode === 'disabled') {
        endTour();
        return;
    }

    const backdrop = document.createElement('div');
    backdrop.className = 'tour-backdrop';
    document.body.appendChild(backdrop);
    activeBackdrop = backdrop;

    if (targetEl) {
        targetEl.classList.add('tour-highlighted');
        targetEl.classList.add('tour-target-active');
        targetEl.style.pointerEvents = 'auto';
        targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
        if (typeof targetEl.focus === 'function' && (targetEl.tagName === 'INPUT' || targetEl.tagName === 'SELECT' || targetEl.tagName === 'TEXTAREA')) {
            setTimeout(() => {
                try {
                    targetEl.focus();
                } catch (e) { }
            }, 80);
        }
    }

    const popup = document.createElement('div');
    popup.className = `tour-popup ${mode === 'zachary' ? 'zachary-mode' : 'standard-mode'}`;

    const visibleSteps = (tut.steps || []).filter(s => isStepVisible(s, me));
    const currentNum = Math.max(1, visibleSteps.indexOf(step) + 1);
    const visibleTotal = visibleSteps.length || totalSteps;
    const progressText = `Step ${currentNum} of ${visibleTotal} • ${tut.title}`;
    const isLast = currentNum >= visibleTotal;
    const muted = isZacharyMuted();
    const actionRequired = step.actionRequired || null;

    // Audio SVG Icons
    const audioIconPlaying = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><path d="M15.54 8.46a5 5 0 0 1 0 7.07"></path><path d="M19.07 4.93a10 10 0 0 1 0 14.14"></path></svg>`;
    const audioIconMuted = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><line x1="23" y1="9" x2="17" y2="15"></line><line x1="17" y1="9" x2="23" y2="15"></line></svg>`;

    const isActionLocked = actionRequired && !actionRequired.skippable && !step.skippable;
    const actionBadgeHtml = actionRequired ? `
        <div class="tour-action-pill ${actionRequired.skippable ? 'tour-action-pill-skippable' : ''}">
            <span class="tour-action-pill-icon">${actionRequired.skippable ? '💡 Optional / Skippable:' : '⚡ Action Required:'}</span>
            <span class="tour-action-pill-text">${actionRequired.instruction}</span>
        </div>
    ` : '';

    if (mode === 'zachary') {
        // Zachary Mode Card
        popup.innerHTML = `
            <div class="zachary-popup-container">
                <div class="zachary-avatar-column">
                    <img src="/assets/images/zachary.png" alt="Zachary" class="zachary-popup-avatar" id="tour-zachary-interactive-avatar" title="Click me for helpful advice!" />
                    <span class="zachary-avatar-badge">Zachary</span>
                </div>
                <div class="zachary-content-column">
                    <div class="zachary-popup-header-actions">
                        <button type="button" class="btn-tour-audio-toggle ${muted ? 'muted' : ''}" title="${muted ? 'Unmute Zachary voice' : 'Mute Zachary voice'}" aria-label="Toggle voice">
                            ${muted ? audioIconMuted : audioIconPlaying}
                        </button>
                        <button class="tour-popup-close" aria-label="Close" title="Exit tutorial">&times;</button>
                    </div>
                    <div class="zachary-speech-bubble-tail"></div>
                    <div class="zachary-popup-bubble">
                        <h3 class="zachary-popup-title">${step.title}</h3>
                        <p class="zachary-popup-desc" id="tour-step-dialogue">${step.zacharyDesc || step.standardDesc}</p>
                        ${actionBadgeHtml}
                        
                        <div class="tour-popup-footer">
                            <span class="tour-popup-progress">${progressText}</span>
                            <div class="tour-popup-nav">
                                ${stepIndex > 0 ? `<button type="button" class="btn-tour-back">Back</button>` : ''}
                                <button type="button" class="btn-tour-next ${isActionLocked ? 'btn-tour-next-disabled' : ''}" ${isActionLocked ? 'disabled title="Complete required action above to continue"' : 'title="Proceed to next step"'}>
                                    ${isLast ? 'Finish ✔' : 'Next >'}
                                </button>
                            </div>
                        </div>

                        <div class="zachary-popup-subfooter">
                            <button type="button" class="btn-toggle-standard-mode" title="Switch to clean standard tutorial">
                                Switch to Standard Mode
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        `;
    } else {
        // Standard Mode Card
        popup.innerHTML = `
            <button class="tour-popup-close" aria-label="Close" title="Exit tutorial">&times;</button>
            <h3>${step.title}</h3>
            <p>${step.standardDesc}</p>
            ${actionBadgeHtml}
            <div class="tour-popup-footer">
                <span class="tour-popup-progress">${progressText}</span>
                <div class="tour-popup-nav">
                    ${stepIndex > 0 ? `<button type="button" class="btn-tour-back">Back</button>` : ''}
                    <button type="button" class="btn-tour-next ${isActionLocked ? 'btn-tour-next-disabled' : ''}" ${isActionLocked ? 'disabled title="Complete required action above to continue"' : 'title="Proceed to next step"'}>
                        ${isLast ? 'Finish ✔' : 'Next >'}
                    </button>
                </div>
            </div>
            <div class="zachary-popup-subfooter mt-8">
                <button type="button" class="btn-toggle-zachary-mode" style="font-size: 11px; background: none; border: none; color: var(--accent); cursor: pointer; padding: 0;">
                    Enable Zachary Assistant
                </button>
            </div>
        `;
    }

    document.body.appendChild(popup);
    activePopup = popup;

    // Trigger TTS Speech for current step in Zachary mode
    if (mode === 'zachary') {
        const speechText = (step.zacharyDesc || step.standardDesc) + (actionRequired ? " " + actionRequired.instruction : "");
        speakZachary(speechText);
    }

    // Audio toggle
    const audioToggleBtn = popup.querySelector('.btn-tour-audio-toggle');
    if (audioToggleBtn) {
        audioToggleBtn.addEventListener('click', () => {
            const nextMuted = !isZacharyMuted();
            setZacharyMuted(nextMuted);
            audioToggleBtn.classList.toggle('muted', nextMuted);
            audioToggleBtn.title = nextMuted ? 'Unmute Zachary voice' : 'Mute Zachary voice';
            audioToggleBtn.innerHTML = nextMuted ? audioIconMuted : audioIconPlaying;
            if (!nextMuted) {
                speakZachary(step.zacharyDesc || step.standardDesc);
            }
        });
    }

    // Close button
    popup.querySelector('.tour-popup-close').addEventListener('click', () => {
        endTour();
    });

    // Easter egg avatar click
    const zacharyImg = popup.querySelector('#tour-zachary-interactive-avatar');
    if (zacharyImg) {
        zacharyImg.addEventListener('click', () => {
            zacharyImg.classList.add('zachary-wiggle');
            setTimeout(() => zacharyImg.classList.remove('zachary-wiggle'), 600);

            const randomQuip = ZACHARY_QUIPS[Math.floor(Math.random() * ZACHARY_QUIPS.length)];
            const dialogueEl = popup.querySelector('#tour-step-dialogue');
            if (dialogueEl) {
                dialogueEl.innerHTML = `<em>"${randomQuip}"</em>`;
            }
            speakZachary(randomQuip);
        });
    }

    // Mode toggles
    const switchStandardBtn = popup.querySelector('.btn-toggle-standard-mode');
    if (switchStandardBtn) {
        switchStandardBtn.addEventListener('click', () => {
            setTutorialMode('standard');
            showToast('Switched to Standard Tutorial mode', 'info');
            displayTourStepPopup(targetEl, step, stepIndex, totalSteps, tut, me);
        });
    }

    const switchZacharyBtn = popup.querySelector('.btn-toggle-zachary-mode');
    if (switchZacharyBtn) {
        switchZacharyBtn.addEventListener('click', () => {
            setTutorialMode('zachary');
            showToast('Zachary is back!', 'success');
            displayTourStepPopup(targetEl, step, stepIndex, totalSteps, tut, me);
        });
    }

    const nextBtn = popup.querySelector('.btn-tour-next');

    // ======================================================================
    // INTERACTIVE ACTION BINDINGS & SAFE PRACTICE INTERCEPTOR
    // ======================================================================
    const advanceToNext = (customSuccessMessage = null) => {
        stopZacharySpeech();
        if (customSuccessMessage) {
            showToast(customSuccessMessage, 'success');
        }
        if (isLast) {
            endTour(tut.id);
            showToast(`Tutorial complete: ${tut.title}!`, 'success');
            const nextTut = getNextIncompleteTutorial(me);
            if (nextTut && nextTut.id !== tut.id) {
                setTimeout(() => {
                    if (confirm(`Great job completing "${tut.title}"! Would you like to proceed to the next tutorial: "${nextTut.title}"?`)) {
                        startTour(me, nextTut.id);
                    }
                }, 400);
            }
        } else {
            const nextIdx = getNextVisibleStepIndex(tut.steps || [], stepIndex + 1, 1, me);
            safeSetItem('obsidianscout:tour_step_index', String(nextIdx));
            syncTourProgressToServer().catch(() => { });
            runActiveTourStep(me);
        }
    };

    if (actionRequired && targetEl) {
        // Unlock Next button handler
        const unlockNext = (autoAdvancing = false, customText = null) => {
            if (nextBtn) {
                nextBtn.removeAttribute('disabled');
                nextBtn.classList.remove('btn-tour-next-disabled');
                nextBtn.title = "Proceed to next step";
            }
            const pill = popup.querySelector('.tour-action-pill');
            if (pill) {
                pill.classList.add('tour-action-pill-completed');
                pill.innerHTML = autoAdvancing
                    ? `<span class="tour-action-pill-icon">✔ Completed!</span> <span class="tour-action-pill-text">Great job! Advancing...</span>`
                    : `<span class="tour-action-pill-icon">✔ Ready!</span> <span class="tour-action-pill-text">${customText || 'Press Next (or Enter) to continue'}</span>`;
            }
        };

        if (actionRequired.skippable || step.skippable) {
            unlockNext(false, 'Optional step — press Next to continue');
        }

        // Global checkbox pre-check detector (matches dropdown auto-select behavior)
        const checkCheckboxState = () => {
            if (!targetEl) return false;
            const isCheckbox = targetEl.type === 'checkbox' || targetEl.matches?.('input[type="checkbox"]');
            const innerChecked = targetEl.querySelector?.('input[type="checkbox"]:checked, .graph-type-checkbox:checked');
            const parentChecked = targetEl.closest?.('label')?.querySelector?.('input[type="checkbox"]:checked');
            const isGraphTypeGroup = targetEl.id === 'graph-type-grid' || targetEl.classList?.contains('graph-type-grid') || targetEl.classList?.contains('graph-type-checkbox') || targetEl.querySelector?.('.graph-type-checkbox');
            const anyGroupChecked = isGraphTypeGroup ? document.querySelector('.graph-type-checkbox:checked') : null;

            if ((isCheckbox && targetEl.checked) || innerChecked || parentChecked || anyGroupChecked) {
                unlockNext(false, 'Checkbox already selected — click Next to continue');
                return true;
            }
            return false;
        };

        if (checkCheckboxState()) {
            // Already unlocked
        } else {
            // Poll briefly in case checkbox state hydrates asynchronously
            let chkAttempts = 0;
            const chkTimer = setInterval(() => {
                chkAttempts++;
                if (checkCheckboxState() || chkAttempts > 20 || !activePopup) {
                    clearInterval(chkTimer);
                }
            }, 100);
        }

        if (actionRequired.type === 'click' || actionRequired.type === 'tab') {
            const clickHandler = (e) => {
                const link = targetEl.tagName === 'A' ? targetEl : targetEl.closest('a');
                if (link && link.href && !link.href.startsWith('javascript:')) {
                    const nextIdx = getNextVisibleStepIndex(tut.steps || [], stepIndex + 1, 1, me);
                    safeSetItem('obsidianscout:tour_step_index', String(nextIdx));
                    syncTourProgressToServer().catch(() => { });
                    unlockNext(true);
                    return; // Allow natural browser navigation with nextIdx already saved
                }
                unlockNext(true);
                setTimeout(() => {
                    advanceToNext();
                }, 120);
            };
            targetEl.addEventListener('click', clickHandler, { once: true });
            targetEl.addEventListener('change', checkCheckboxState);
            targetEl._tourActionHandler = clickHandler;
        } else if (actionRequired.type === 'input') {
            const inputHandler = (e) => {
                const val = (targetEl.value !== undefined ? targetEl.value : (e.target && e.target.value)) || '';
                if (val.trim().length > 0) {
                    unlockNext(false, 'Press Next (or Enter) when finished typing');
                }
            };
            const keydownHandler = (e) => {
                if (e.key === 'Enter') {
                    const val = (targetEl.value !== undefined ? targetEl.value : (e.target && e.target.value)) || '';
                    if (val.trim().length > 0) {
                        advanceToNext();
                    }
                }
            };
            // If already pre-filled with content
            if ((targetEl.value || '').trim().length > 0) {
                unlockNext(false, 'Press Next (or Enter) when finished typing');
            }
            targetEl.addEventListener('input', inputHandler);
            targetEl.addEventListener('keydown', keydownHandler);
            targetEl._tourActionHandler = inputHandler;
            targetEl._tourKeydownHandler = keydownHandler;
        } else if (actionRequired.type === 'change') {
            const changeHandler = (e) => {
                if (targetEl.value || (e.target && e.target.value) || targetEl.checked) {
                    unlockNext(true);
                    setTimeout(() => {
                        advanceToNext();
                    }, 150);
                }
            };
            targetEl.addEventListener('change', changeHandler);
            targetEl._tourActionHandler = changeHandler;

            const checkSelectedValue = () => {
                const val = (targetEl.value !== undefined ? targetEl.value : '') || '';
                const hasSelectedOption = targetEl.tagName === 'SELECT' && targetEl.selectedIndex >= 0 && (targetEl.options[targetEl.selectedIndex]?.value || '').trim().length > 0;
                if (val.trim().length > 0 || targetEl.checked || hasSelectedOption) {
                    unlockNext(false, 'Select an option or click Next to proceed');
                    return true;
                }
                return false;
            };

            // Check immediately on render
            if (!checkSelectedValue()) {
                // Poll for asynchronous data population (e.g. event list or team select loaded via API)
                let attempts = 0;
                const checkInterval = setInterval(() => {
                    attempts++;
                    if (checkSelectedValue() || attempts > 25 || !activePopup) {
                        clearInterval(checkInterval);
                    }
                }, 100);
            }

            // Also check on click/focus
            targetEl.addEventListener('click', checkSelectedValue);
            targetEl.addEventListener('focus', checkSelectedValue);
        } else if (actionRequired.type === 'submit-practice') {
            // Find closest form or target button
            const form = targetEl.closest('form') || targetEl;
            const submitHandler = (e) => {
                e.preventDefault();
                e.stopImmediatePropagation();
                unlockNext(true);
                speakZachary("Practice entry validated. No dummy data was written to the competition database.");
                advanceToNext("Practice data submitted successfully! (Sandbox mode - no real data saved)");
            };
            targetEl.addEventListener('click', submitHandler, { once: true });
            if (form && form !== targetEl) {
                form.addEventListener('submit', submitHandler, { once: true });
                form._tourSubmitHandler = submitHandler;
            }
            targetEl._tourActionHandler = submitHandler;
        }
    }

    if (nextBtn) {
        nextBtn.addEventListener('click', () => {
            advanceToNext();
        });
    }

    // Back button
    const backBtn = popup.querySelector('.btn-tour-back');
    if (backBtn) {
        backBtn.addEventListener('click', () => {
            stopZacharySpeech();
            const prevIdx = getNextVisibleStepIndex(tut.steps || [], Math.max(0, stepIndex - 1), -1, me);
            safeSetItem('obsidianscout:tour_step_index', String(Math.max(0, prevIdx)));
            syncTourProgressToServer().catch(() => { });
            runActiveTourStep(me);
        });
    }

    // Positioning with RAF throttle to prevent scroll lag
    if (targetEl) {
        positionPopupNextToElement(popup, targetEl);
        let ticking = false;
        const reposition = () => {
            if (!ticking) {
                window.requestAnimationFrame(() => {
                    if (activePopup && targetEl) {
                        positionPopupNextToElement(popup, targetEl);
                    }
                    ticking = false;
                });
                ticking = true;
            }
        };
        window.addEventListener('resize', reposition, { passive: true });
        window.addEventListener('scroll', reposition, { passive: true });
        popup.dataset.repositionListeners = 'true';
        popup._reposition = reposition;
    } else {
        popup.style.position = 'fixed';
        popup.style.top = '50%';
        popup.style.left = '50%';
        popup.style.transform = 'translate(-50%, -50%)';
    }
}

export function positionPopupNextToElement(popup, targetEl) {
    const rect = targetEl.getBoundingClientRect();
    const popupWidth = popup.offsetWidth || 380;
    const popupHeight = popup.offsetHeight || 180;
    const margin = 14;

    let top = rect.bottom + window.scrollY + margin;
    let left = rect.left + window.scrollX + (rect.width - popupWidth) / 2;

    if (rect.bottom + popupHeight + margin > window.innerHeight) {
        if (rect.top - popupHeight - margin > 0) {
            top = rect.top + window.scrollY - popupHeight - margin;
        } else {
            if (rect.right + popupWidth + margin < window.innerWidth) {
                top = rect.top + window.scrollY + (rect.height - popupHeight) / 2;
                left = rect.right + window.scrollX + margin;
            } else if (rect.left - popupWidth - margin > 0) {
                top = rect.top + window.scrollY + (rect.height - popupHeight) / 2;
                left = rect.left + window.scrollX - popupWidth - margin;
            }
        }
    }

    const viewportWidth = window.innerWidth;
    if (left < 12) left = 12;
    if (left + popupWidth > viewportWidth - 12) left = viewportWidth - popupWidth - 12;

    popup.style.top = `${top}px`;
    popup.style.left = `${left}px`;
}

export function clearTourDOM() {
    stopZacharySpeech();
    if (activePopup && activePopup.dataset.repositionListeners === 'true' && activePopup._reposition) {
        window.removeEventListener('resize', activePopup._reposition);
        window.removeEventListener('scroll', activePopup._reposition);
    }

    document.querySelectorAll('.tour-highlighted, .tour-target-active, [id^="tab-"], [data-config-kind], .sidebar-link').forEach(el => {
        if (el._tourActionHandler) {
            el.removeEventListener('click', el._tourActionHandler);
            el.removeEventListener('input', el._tourActionHandler);
            el.removeEventListener('change', el._tourActionHandler);
            delete el._tourActionHandler;
        }
        if (el._tourKeydownHandler) {
            el.removeEventListener('keydown', el._tourKeydownHandler);
            delete el._tourKeydownHandler;
        }
        if (el._tourSubmitHandler) {
            el.removeEventListener('submit', el._tourSubmitHandler);
            delete el._tourSubmitHandler;
        }
    });

    document.querySelectorAll('.tour-highlighted, .tour-target-active').forEach(el => {
        el.classList.remove('tour-highlighted');
        el.classList.remove('tour-target-active');
    });

    if (activePopup) {
        activePopup.remove();
        activePopup = null;
    }
    if (activeBackdrop) {
        activeBackdrop.remove();
        activeBackdrop = null;
    }
}

// Level selector modal for quick access if opened from navigation
export function showTourLevelSelector(me) {
    window.location.href = '/tutorials';
}
