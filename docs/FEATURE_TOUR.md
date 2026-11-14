# Feature Tour: Every Page at a Glance

This tour walks through every page in ObsidianScout, in the same order as the sidebar. Each section says what the page is for, what you can do there, and who normally uses it, with a screenshot. Setting up a team for the first time? Start with [Getting Started](GETTING_STARTED.md).

> **Note:** Screenshots were taken on a local server with a test team scouting the 2026 Cow Town ThrowDown (`2026cttd`). Only a few matches were scouted, so some pages show partial data or empty states where a real event would have more. Pages you can't see in your sidebar have either been hidden by your team admin (Admin Settings → Permissions) or are turned off for your team.

**Jump to:** [Public pages](#public-pages) · [Home](#home) · [Scouting](#scouting) · [Data & Analytics](#data--analytics) · [Strategy](#strategy) · [Non-Scouting Tools](#non-scouting-tools) · [Admin & System](#admin--system)

---

## Public pages

### Home page (`/`)

The landing page anyone can open without signing in. It sums up what ObsidianScout does (FRC and FTC support, offline QR sync, The Blue Alliance and Statbotics sync) and links to sign-in, the capability overview, and Public Data.

![Home page](/assets/images/docs/index.webp)

### Sign in (`/login`)

Sign in with your username, team number, program (FRC or FTC) and password, or with a passkey. The **Create account** tab registers a new user for a team. "Keep me logged in" keeps the session across browser restarts.

![Sign in page](/assets/images/docs/login.webp)

### Public Data (`/public-data`)

Scouting data that teams have chosen to share publicly, grouped by team and event. Each event links to read-only views of Graphs, Analytics, Compare Teams, Custom Analytics, Rankings, Projected Rankings, Match Predictor, Event Predictor, Match Data, Pit Data, Qual Data and Qual Rankings. See [Public Data](PUBLIC_DATA.md) for how sharing works.

![Public Data page](/assets/images/docs/public-data.webp)

---

## Home

### Dashboard (`/dashboard`)

Your starting point after signing in. It shows:

- **Quick actions:** Scout Match, Pit Scout, QR Scanner and Predictor.
- **Scouting Overview:** counts of match entries, pit forms, qualitative entries, teams, matches and events.
- **Event context:** the current season, event key, timezone and data source, with links to The Blue Alliance, Statbotics and FIRST.
- **Quick Team Lookup** and **Quick sync** (teams and matches sync automatically while the server is running).

![Dashboard](/assets/images/docs/dashboard.webp)

### Event Info (`/event-info`)

A team noticeboard for the current event. Admins post announcements with optional images and files, pin them to the top, or show them on every event. Everyone else on the team can read them.

![Event Info](/assets/images/docs/event-info.webp)

---

## Scouting

### Cache & History (`/cache-manager`)

Shows what this device has stored for offline use and every entry it has submitted. Use it to check offline readiness, see entries waiting to upload, re-send or export history, and clear the local cache.

![Cache & History](/assets/images/docs/cache-manager.webp)

### Match Predictions (`/match-predictions`)

Team members pick the winner of upcoming matches before they're played. Results feed the Prediction Leaderboard. This page only works when a team admin turns on **Enable Match Predictions** in Admin Settings → Permissions; otherwise it says "Match predictions are off". See [Predictions and Rankings](PREDICTIONS_AND_RANKINGS.md#match-predictions-game).

![Match Predictions](/assets/images/docs/match-predictions.webp)

### My Assignments (`/my-assignments`)

The scout's to-do list: the matches, pit visits and qualitative tasks assigned to you, split into Active & Upcoming, Completed, Passed and All. Assignments are created by admins on [Scout Assignments](#scout-assignments-assignments).

![My Assignments](/assets/images/docs/my-assignments.webp)

### Pit Scout (`/pit-scout`)

The pit scouting form. Pick a team at the current event and fill in robot details such as dimensions, drivetrain and mechanisms, plus photos. The fields come from your team's pit scouting config.

![Pit Scout](/assets/images/docs/pit-scout.webp)

### Prescouting (`/prescout`)

Collect data on teams before an event, from other events or match videos. It has three modes: **Match Prescout**, **Pit Prescout** and **Qual Prescout**. Prescout data is kept separate and can be included in analytics with the "Include Prescouting Data" toggle.

![Prescouting](/assets/images/docs/prescout.webp)

### Prediction Leaderboard (`/prediction-leaderboard`)

Ranks team members by how many match predictions they got right. Like Match Predictions, it needs **Enable Match Predictions** turned on in Admin Settings.

![Prediction Leaderboard](/assets/images/docs/prediction-leaderboard.webp)

### QR / JAB Scanner (`/qr-scanner`)

Imports entries from devices that scouted offline. Scan QR or JAB codes with the camera, or upload or paste JSON exports. Scanned entries collect in a queue and are uploaded together.

![QR / JAB Scanner](/assets/images/docs/qr-scanner.webp)

### Qual Scout (`/qual-scout`)

The qualitative (subjective) scouting form. Rate driving, defense, strategy and other judgment-based traits for a single team or a whole alliance in a match. The fields come from your qualitative scouting config.

![Qual Scout](/assets/images/docs/qual-scout.webp)

### Scout (`/scout`)

The main match scouting form. Pick a team and match, then record auto, teleop and endgame actions. The form is built from your team's scouting config and shows a live points preview. It works offline and syncs when the device reconnects.

![Scout](/assets/images/docs/scout.webp)

---

## Data & Analytics

### All Data (`/all-data`)

Match, pit and qualitative entries in one searchable list. Filter by event, team, scout, match or entry type, open any entry to see its full payload, and export to CSV.

![All Data](/assets/images/docs/all-data.webp)

### Analytics (`/analytics`)

Summary widgets generated from the `analytics` section of your scouting config: totals, averages and breakdowns such as climb levels. Turn on "Include Prescouting Data" to mix in prescout entries.

![Analytics](/assets/images/docs/analytics.webp)

### Compare Teams (`/compare`)

Pick two or three teams to compare their scouting averages side by side.

![Compare Teams](/assets/images/docs/compare.webp)

### Custom Analytics (`/custom-analytics`)

A dashboard builder. Add visuals (stat cards, bar, pie, line and more), create calculated metrics, filter by event, match type and teams, then save, share, export or show it in kiosk mode. It also has saved reports and templates. See [Custom Analytics](CUSTOM_ANALYTICS.md).

![Custom Analytics](/assets/images/docs/custom-analytics.webp)

### Data Validation (`/data-validation`)

Finds bad or suspicious data. It compares scouted alliance totals against official match scores and team averages against EPA, xP and OPR, and flags matches that are missing scouting or have large differences. See [Data Validation](DATA_VALIDATION.md).

![Data Validation](/assets/images/docs/data-validation.webp)

### Graphs (`/graphs`)

Pick teams, a metric and a graph type to chart scouting data, such as total points per match or averages across teams.

![Graphs](/assets/images/docs/graphs.webp)

### Match Data (`/match-data`)

Browse match scouting entries only, with filters by event, team, scout and match number, a detail view for each entry, and CSV export.

![Match Data](/assets/images/docs/match-data.webp)

### Matches (`/matches`)

The match schedule for the selected event, synced from The Blue Alliance or FIRST, or added by hand with **Add Match Manually**.

![Matches](/assets/images/docs/matches.webp)

### Pit Data (`/pit-data`)

Pit scouting results and coverage. See which teams have been pit scouted, then open a team to read its pit entry.

![Pit Data](/assets/images/docs/pit-data.webp)

### Projected Rankings (`/projected-rankings`)

Simulates the rest of the qualification schedule to project final rankings and ranking points. Choose the event and the number of simulation runs. See [Predictions and Rankings](PREDICTIONS_AND_RANKINGS.md#projected-rankings).

![Projected Rankings](/assets/images/docs/projected-rankings.webp)

### Qual Data (`/qual-data`)

Qualitative entries, with a team ranking built from numeric qualitative fields and filters by event, team, scout and aggregation method.

![Qual Data](/assets/images/docs/qual-data.webp)

### Qual Rankings (`/qual-rankings`)

Ranks teams by qualitative scores. Choose the metric and aggregation, and filter by robot role.

![Qual Rankings](/assets/images/docs/qual-rankings.webp)

### Rankings (`/rankings`)

Ranks teams at an event by a chosen metric, such as scouted average points, alongside EPA, xP and OPR.

![Rankings](/assets/images/docs/rankings.webp)

### Teams (`/teams`)

Every team at the selected event. Sync from the API or add teams by hand. Click a team to open its profile with all of its scouting data.

![Teams](/assets/images/docs/teams.webp)

---

## Strategy

### Alliance Selection (`/alliance-selection`)

A live board for alliance selection. Fill in each alliance's captain, picks and backup as selections happen. Recommendations rank the teams still available, adjusted by your **Want / Avoid / Do Not Pick** lists. You can export the board to CSV or print it.

![Alliance Selection](/assets/images/docs/alliance-selection.webp)

### Alliances (`/alliances`)

Scouting alliances let partner teams share scouting data. Create an alliance, invite teams, accept invites, and choose what to share. See [Scouting Alliances](SCOUTING_ALLIANCES.md).

![Alliances](/assets/images/docs/alliances.webp)

### Event Predictor (`/event-predictor`)

Predicts every scheduled match at an event in one list. Choose the prediction model: scouting data, Statbotics EPA, Match 13 xP, TBA OPR, or all of them. See [Predictions and Rankings](PREDICTIONS_AND_RANKINGS.md#event-predictor).

![Event Predictor](/assets/images/docs/event-predictor.webp)

### Match Planning (`/match-planning`)

A whiteboard for planning a match. Load a match to see both alliances on the field image, draw auto, teleop and endgame paths, and look at each team's stats and performance graph.

![Match Planning](/assets/images/docs/match-planning.webp)

### Pick List (`/pick-list`)

Your team's ranked pick list. Drag teams into order, keep unranked teams to one side, and keep a Do Not Pick list. This list feeds Alliance Selection.

![Pick List](/assets/images/docs/pick-list.webp)

### Predictor (`/predictor`)

Predicts a single match. Pick a scheduled match to compare the alliances' expected scores from scouting data and from each outside rating, with the predicted winner and margin. See [Predictions and Rankings](PREDICTIONS_AND_RANKINGS.md#match-predictor).

![Predictor](/assets/images/docs/predictor.webp)

---

## Non-Scouting Tools

These tools are for the drive team, not scouts. Each user turns them on under **Non-Scouting Tools** in Personal Settings.

### Battery Tracker (`/battery-tracker`)

Keeps track of your robot's batteries. Add match and pit batteries, and the tracker rotates match batteries through your schedule. It's shared live with the team.

![Battery Tracker](/assets/images/docs/battery-tracker.webp)

### Match Checklist (`/match-checklist`)

A pre-match checklist for the drive team. It can mark the match's battery as used when the checklist is finished, and you can edit the checklist items.

![Match Checklist](/assets/images/docs/match-checklist.webp)

---

## Admin & System

### Admin Settings (`/admin-settings`)

Team-wide configuration (admins only): team theme, the setup wizard, scouting, pit and qualitative form builders, permissions (which pages each role sees, and feature toggles such as Match predictions), and integrations such as The Blue Alliance, FIRST API, Statbotics and Match 13.

![Admin Settings](/assets/images/docs/admin-settings.webp)

### Assistant (`/assistant`)

Ask questions about your scouting data in plain English, for example "Top 5 teams by auto points". The AI runs on the device, so nothing is sent to an outside AI service. Tables and charts are computed from your data. Turn it on under Local AI in Personal Settings. It is an experiment rather than a core feature, so don't rely on it at events. See [Local AI Assistant](LOCAL_AI_ASSISTANT.md).

![Assistant](/assets/images/docs/assistant.webp)

### Banners (`/banners`)

Create notice banners that show at the top of pages for your team. Set the style, audience and expiry, choose whether users can dismiss or expand them, and choose whether they appear on the login page.

![Banners](/assets/images/docs/banners.webp)

### Chat (`/chat`)

Team chat channels, starting with `#general`. Admins can create groups and change channel settings.

![Chat](/assets/images/docs/chat.webp)

### Data Sharing (`/backup`)

Export and import your team's data. The `.obsidiandb` backup includes photos and restores on any server. The CSV archive keeps photos as links, so it only restores fully on the same server.

![Data Sharing](/assets/images/docs/backup.webp)

### Docs (`/docs`)

This documentation library, including this tour.

![Docs](/assets/images/docs/docs.webp)

### Events (`/events`)

The events your team has synced or created. Sync events from the API or create a custom event for scrimmages and off-season competitions.

![Events](/assets/images/docs/events.webp)

### Scout Assignments (`/assignments`)

Plan who scouts what. Create single assignments or use the bulk match wizard, plan shifts, auto-assign pit scouters, resolve conflicts and set reminder notifications. The match matrix shows coverage across the schedule.

![Scout Assignments](/assets/images/docs/assignments.webp)

### Scout Report (`/scouter-report`)

Per-scout activity for a chosen period: match, pit and qualitative entries, matches covered, assignment completion and late or missed assignments. It can export to CSV.

![Scout Report](/assets/images/docs/scouter-report.webp)

### Settings (`/config`)

Your personal settings: profile picture, team number display, notifications, scouting preferences (haptic feedback, keep the screen on, review before submitting, slow connection mode), navigation layout, the tutorial guide, the Local AI Assistant, Non-Scouting Tools, account details, passkeys and biometrics, logged-in devices, and the danger zone.

![Settings](/assets/images/docs/config.webp)

### Shared Links (`/shared-links`)

Manage the public links your team has created for dashboards and reports. See views and expiry, show a link's QR code, and revoke links.

![Shared Links](/assets/images/docs/shared-links.webp)

### Tutorials (`/tutorials`)

Interactive walkthroughs that guide you through features step by step, such as initial setup, the dashboard, match scouting, pit scouting and offline QR sync. Progress is tracked so you can pick up where you left off.

![Tutorials](/assets/images/docs/tutorials.webp)

### Users (`/users`)

Team user management (admins only): create users, set roles (Scout, Custom, Analytics, Admin), search and filter users, and edit or remove accounts.

![Users](/assets/images/docs/users.webp)

---

### Superadmin-only pages

Server operators with the Superadmin role also see Default Configs, Server Management, Storage Manager, Error Reports, Load Profiler, Server Insights, FCM Push Settings, Migration, Redis Cache Status and Blob Storage. These manage the server itself rather than a team, and aren't covered in this tour.
