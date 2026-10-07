/**
 * Built-in ObsidianScout documentation answers for the read_docs tool.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import AI from "../local-ai.js";
import Data from "../ai-data.js";
import Features from "../ai-features.js";
import { evaluate } from "./shared.js";

export function getDocGuide(rawQuery = "") {
    const q = String(rawQuery).toLowerCase();
    if (q.includes("pit")) {
        return {
            topic: "pit_scouting",
            title: "Pit Scouting Guide",
            summary: "How to inspect robot mechanisms, record specifications, take robot photos, and evaluate pit interviews.",
            keySteps: [
                "Navigate to Pit Scouting from the sidebar menu (/pit-scout).",
                "Select the target team number from the team list or search bar.",
                "Inspect the robot drivetrain (Swerve, Tank, Mecanum), dimensions, weight, intake, and scoring mechanisms.",
                "Take or upload robot photos directly from your device camera.",
                "Fill in qualitative notes regarding driver experience and auto routines, then press 'Save Entry'."
            ],
            markdown: [
                "# 🛠️ ObsidianScout Pit Scouting User Guide",
                "Pit Scouting collects mechanical specifications, dimensions, drivetrain architecture, and qualitative notes prior to qualification matches.",
                "",
                "### Step-by-Step Workflow:",
                "1. **Access Pit Scouting:** Click **Pit Scouting** in the navigation sidebar or visit [`/pit-scout`](/pit-scout).",
                "2. **Select Team:** Type or select the team number you are currently visiting in the pit area.",
                "3. **Record Drivetrain & Dimensions:** Document motor types, gearbox ratios, frame perimeter, and weight.",
                "4. **Robot Photos:** Use the built-in camera capture button to attach high-resolution intake and mechanism photos.",
                "5. **Auto Capabilities:** Ask the drive team about starting positions, pre-loaded scoring, and autonomous paths.",
                "6. **Save & Sync:** Tap **Save Entry**. If offline, the entry is safely stored in IndexedDB and syncs automatically when online."
            ].join("\n")
        };
    }
    if (q.includes("offline") || q.includes("sync") || q.includes("cache")) {
        return {
            topic: "offline_sync",
            title: "Offline Mode & Data Synchronization Guide",
            summary: "How ObsidianScout operates 100% offline in stadium venues with zero Wi-Fi, using IndexedDB and automatic syncing.",
            keySteps: [
                "ObsidianScout installs as a Progressive Web App (PWA) and caches all app assets.",
                "All match, pit, and qualitative entries saved while offline are stored locally in IndexedDB.",
                "When internet connectivity is detected, the background sync service automatically uploads pending entries.",
                "Use the Cache & Storage Manager (/cache-manager) to view pending queue status, download backups, or force sync."
            ],
            markdown: [
                "# 📶 Offline Mode & Sync Protocol Guide",
                "ObsidianScout is engineered to work reliably in arenas with jammed or restricted Wi-Fi.",
                "",
                "### Key Offline Features:",
                "- **IndexedDB Local Storage:** Every scouting form submission is instantly committed to local browser database storage.",
                "- **Automatic Background Sync:** As soon as Wi-Fi or cellular data reconnects, pending entries upload seamlessly.",
                "- **Conflict Resolution:** If two scouts submit data for the same team match, the conflict resolver lets you compare side-by-side.",
                "- **Cache Manager:** Visit [`/cache-manager`](/cache-manager) to inspect queued entries, export JSON/CSV backups, or trigger manual sync."
            ].join("\n")
        };
    }
    if (q.includes("qr") || q.includes("scanner") || q.includes("transfer")) {
        return {
            topic: "qr_transfer",
            title: "QR Code Data Transfer Guide",
            summary: "Air-gapped data transmission using high-density QR codes to transfer scouting entries from stands to the lead scout station.",
            keySteps: [
                "Scouts complete match entries on offline phones or tablets.",
                "On the completed form or cache manager, tap 'Generate QR Code'.",
                "The lead scout or data lead opens the QR Scanner (/qr-scanner) on their connected laptop.",
                "Scan the QR code with the camera to instantly ingest and verify the match entry into the server database."
            ],
            markdown: [
                "# 📷 QR Code Air-Gapped Transfer Guide",
                "When venue regulations prohibit Wi-Fi hotspots, ObsidianScout uses animated high-density QR codes to transfer scouting entries.",
                "",
                "### How to Use QR Transfer:",
                "1. **On Scout Device:** Complete a match form and click **Generate QR Code** (or open it from Cache Manager).",
                "2. **On Lead Scout Station:** Open **QR Scanner** in the sidebar or go to [`/qr-scanner`](/qr-scanner).",
                "3. **Scan Code:** Hold the scout device up to the webcam/camera. The scanner ingests and parses the entry in milliseconds.",
                "4. **Verification:** A green confirmation banner confirms data receipt and updates the central leaderboard."
            ].join("\n")
        };
    }
    if (q.includes("gamepad") || q.includes("controller")) {
        return {
            topic: "gamepad_setup",
            title: "Gamepad & Controller Scouting Guide",
            summary: "Configure USB or Bluetooth gamepads (Xbox, PlayStation, Logitech) for tactile, rapid match scouting without looking at the screen.",
            keySteps: [
                "Connect a gamepad via USB or Bluetooth to your device.",
                "Go to Settings (/settings) -> Gamepad Profiles.",
                "Press any button on the controller to detect the device.",
                "Map controller buttons (A, B, X, Y, Bumpers, Triggers, D-Pad) to scouting counters and scoring phases.",
                "Open Live Scouting (/scout) to record game cycles using the controller with haptic feedback vibration."
            ],
            markdown: [
                "# 🎮 Gamepad Controller Scouting Setup",
                "Gamepad support allows scouts to keep their eyes on the field at all times while recording cycles via controller buttons.",
                "",
                "### Setup Instructions:",
                "1. **Connect Gamepad:** Pair an Xbox, DualShock, Switch Pro, or generic USB controller.",
                "2. **Configure Mappings:** Open [`/settings`](/settings) and navigate to **Gamepad Profiles**.",
                "3. **Button Assignment:** Bind actions like *Speaker Cycle*, *Amp Note*, *Coral Placement*, or *Undo* to specific buttons.",
                "4. **Haptics:** Enable vibration feedback so the controller buzzes on every successful increment.",
                "5. **Live Scouting:** Open [`/scout`](/scout) and start recording without touching the screen."
            ].join("\n")
        };
    }
    if (q.includes("passkey") || q.includes("auth") || q.includes("biometric") || q.includes("login")) {
        return {
            topic: "passkeys_auth",
            title: "Passkey & Biometric Authentication Guide",
            summary: "Passwordless authentication using WebAuthn, Face ID, Touch ID, or Windows Hello for instant secure login.",
            keySteps: [
                "Sign in to your ObsidianScout account.",
                "Go to Settings (/settings) -> Security & Passkeys.",
                "Click 'Register New Passkey' and authenticate with your device fingerprint or facial recognition.",
                "On future logins, click 'Sign in with Passkey' for instant one-touch access."
            ],
            markdown: [
                "# 🔑 Passkey & Security Guide",
                "ObsidianScout supports modern WebAuthn Passkeys for fast, phishing-resistant, passwordless authentication.",
                "",
                "### How to Set Up Passkeys:",
                "1. **Navigate to Settings:** Visit [`/settings`](/settings) and scroll to **Passkeys & Security**.",
                "2. **Register Device:** Click **Register New Passkey**.",
                "3. **Biometric Prompt:** Follow your browser prompt (Touch ID, Face ID, Windows Hello, or YubiKey).",
                "4. **Passwordless Login:** You can now sign in from the login screen with a single touch."
            ].join("\n")
        };
    }
    if (q.includes("strategy") || q.includes("plan") || q.includes("alliance") || q.includes("pick")) {
        return {
            topic: "strategy_planning",
            title: "Match Strategy & Alliance Selection Guide",
            summary: "Pre-match tactical planning, partner synergy analysis, opponent defensive counters, and draft pick sheets.",
            keySteps: [
                "Open Match Planning (/match-planning) and select your upcoming match.",
                "Inspect Red vs Blue projected score breakdowns, auto routing conflicts, and teleop cycle ceilings.",
                "Generate custom Pre-Match Strategy Brief artifacts with defense assignments and key objectives.",
                "For elimination rounds, open Alliance Selection (/alliances) to build tiered pick lists, draft anchors, and specialist rankings."
            ],
            markdown: [
                "# 🎯 Match Strategy & Alliance Selection Guide",
                "Turn quantitative scouting telemetry into match wins and optimal alliance draft strategies.",
                "",
                "### Strategy Tools:",
                "- **Pre-Match Briefing ([`/match-planning`](/match-planning)):** Compares all 6 robots, highlights opponent weak points, and checks autonomous path conflicts.",
                "- **Alliance Selection Board ([`/alliances`](/alliances)):** Organizes teams into 1st-pick anchors, 2nd-pick defense/endgame specialists, and Do-Not-Pick lists.",
                "- **Strategy Brief Artifacts:** Ask the AI assistant (*'create strategy brief for match 12'*) to generate a printable tactical PDF-ready document."
            ].join("\n")
        };
    }
    // General default site guide
    return {
        topic: "general_guide",
        title: "ObsidianScout Complete User Guide & Site Manual",
        summary: "Comprehensive manual of all ObsidianScout features: match scouting, pit scouting, offline sync, analytics, predictor, gamepads, and AI.",
        keySteps: [
            "1. Live Match Scouting (/scout, /qual-scout): Record robot auto/teleop/endgame cycles.",
            "2. Pit Scouting (/pit-scout): Robot dimensions, drive specs, and mechanism photos.",
            "3. Analytics & Graphs (/graphs, /predictor, /rankings): Multi-metric scatter plots, EPA/xP trends, and Monte Carlo win simulations.",
            "4. Match Planning & Alliances (/match-planning, /alliances): Pre-match game plans and alliance selection pick lists.",
            "5. Local AI Assistant: 100% on-device private LLM for voice/text scouting intelligence and automated visual chart creation."
        ],
        markdown: [
            "# 📖 ObsidianScout Complete User Manual",
            "Welcome to ObsidianScout, the premier quantitative scouting and tactical analytics platform for FIRST Robotics (FRC & FTC).",
            "",
            "---",
            "",
            "### 🧭 Core Features & Quick Links:",
            "- **📊 Live Match Scouting ([`/scout`](/scout) / [`/qual-scout`](/qual-scout)):** Real-time cycle tracking with gamepad support, auto-calculation, and offline draft protection.",
            "- **🛠️ Pit Scouting ([`/pit-scout`](/pit-scout)):** In-depth mechanical specifications, drive types, weight, and camera photo capture.",
            "- **📈 Analytics & Graphs ([`/graphs`](/graphs) / [`/predictor`](/predictor) / [`/rankings`](/rankings)):** 2D scatter plots, radar skills charts, phase stacked bars, and official standings.",
            "- **🎯 Match Planning & Alliances ([`/match-planning`](/match-planning) / [`/alliances`](/alliances)):** Pre-match strategy briefs, alliance win probabilities, and draft pick sheets.",
            "- **📶 Offline Mode & QR Sync ([`/cache-manager`](/cache-manager) / [`/qr-scanner`](/qr-scanner)):** Full zero-connectivity arena support with IndexedDB and air-gapped QR scanning.",
            "- **🤖 Local AI Assistant:** Runs 100% on your device (WebLLM / WebGPU) to analyze trends, generate multi-series line charts, simulate Monte Carlo projections, and produce strategic dossiers."
        ].join("\n")
    };
}
