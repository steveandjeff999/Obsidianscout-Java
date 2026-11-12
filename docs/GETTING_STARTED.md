# Getting Started: Setting Up Your Team

This guide takes a team admin from a new account to a team that's ready to scout an event. Every step links to the page you'll use. For a quick look at every page, see the [Feature Tour](FEATURE_TOUR.md).

**Before you start:** you need an **Admin** account for your team. Register on the sign-in page (**Create account**) and choose the Admin role; the first account on a team can pick any role. After that, new accounts are created on **Users**, or people register themselves with the roles your team allows.

---

## 1. Run the setup wizard

Open **Admin Settings** and press **Run Setup Wizard** on the **Team Setup Wizard** card. It walks through the season year, event, API keys and scouting forms in one pass, then offers the tutorial. Everything the wizard sets can be changed later on the Admin Settings tabs below.

![Admin Settings](/assets/images/docs/admin-settings.webp)

## 2. Pick your event and connect the data sources

On **Admin Settings → API keys**:

1. Set **Season year** and **Event code**, for example `2026` and `okok`. ObsidianScout builds the event key from them (`2026okok`). See [Event Codes and Keys](EVENT_KEYS.md) for the details.
2. Set the **Timezone** of the event, so match times show correctly.
3. Add a **TBA key** (The Blue Alliance) and/or **FIRST API** credentials. With either one, teams and matches sync automatically while the server is running. With both, the sync merges the two (for example "QM 4" and "Qualification 4" become one match).
4. Under **Optional stats**, choose which outside ratings to show next to your scouting data: **Statbotics EPA**, **Match 13 xP** and **TBA OPR**. They also feed predictions, rankings and data validation.

Events that aren't in the APIs (scrimmages, off-season events) can be created by hand on **Events → Create Custom Event**. Teams and matches can then be added by hand on **Teams** and **Matches**.

![Events](/assets/images/docs/events.webp)

## 3. Build your scouting forms

On **Admin Settings → Scouting configs**, edit the three forms your scouts fill in:

| Form | Used by | What it records |
|---|---|---|
| **Match scouting** | [Scout](FEATURE_TOUR.md#scout-scout) | What one robot did in one match: auto, teleop, endgame, with point values |
| **Pit scouting** | [Pit Scout](FEATURE_TOUR.md#pit-scout-pit-scout) | Robot details from the pits: drivetrain, dimensions, mechanisms, photos |
| **Qualitative scouting** | [Qual Scout](FEATURE_TOUR.md#qual-scout-qual-scout) | Judgment calls: driving, defense, strategy ratings and notes |

Each form has a visual builder (add fields and section headers) and a raw JSON editor. **Reset to Default** loads a preset for the current game. Point values set on match fields drive every score in the app, from Analytics to predictions, so check them before the event.

If you're in a [scouting alliance](SCOUTING_ALLIANCES.md), the alliance's shared forms replace your own while the alliance is active.

## 4. Add your scouts

Open **Users** to create accounts, or let people register themselves from the sign-in page (you can lock registration or limit which roles people may pick for themselves). Each user has a role:

| Role | Sees |
|---|---|
| **Scout** | The pages in Admin Settings → Permissions → **Scout Role Access** (by default the dashboard, the three scouting forms, the QR scanner, My Assignments, Event Info and Chat) |
| **Analytics** | The pages in **Analytics Role Access**: data, analytics and strategy pages |
| **Custom** | Only the pages an admin picks for that one user |
| **Admin** | Everything for the team, including Admin Settings and Users |

![Users](/assets/images/docs/users.webp)

Use **Admin Settings → Permissions** to change which pages each role sees and to turn team features on or off, such as **Enable Match Predictions** (see [Predictions and Rankings](PREDICTIONS_AND_RANKINGS.md#match-predictions-game)) and **public data** (see [Public Data](PUBLIC_DATA.md)).

## 5. Assign who scouts what

On **Scout Assignments**, plan coverage before the matches start:

- **Bulk match wizard:** pick scouters to rotate, a match range and how many consecutive matches each scout takes, then **Generate Assignments**.
- **Shifts:** plan by time (for example, morning and afternoon crews). Shifts are applied to matches as the schedule syncs.
- **Pit groups:** scouts who walk the pits together share each pit assignment. **Assign Pit Teams** spreads the event's teams across them.
- **Reminders:** set the lead time for push and email reminders before each assigned match.

The match matrix shows which robots in each match are covered. Scouts see their own list on **My Assignments**.

![Scout Assignments](/assets/images/docs/assignments.webp)

## 6. Scout the event

Scouts open **Scout** (match), **Pit Scout** or **Qual Scout**, pick the team and match, and submit. The forms work offline: entries are kept on the device and upload when it reconnects. **Cache & History** shows what's waiting to upload.

![Scout](/assets/images/docs/scout.webp)

**No Wi-Fi at the venue?** A scout can press **Generate QR** on a finished form. Someone with a connected device opens **QR / JAB Scanner**, scans the codes (or pastes or uploads JSON exports) into the queue, and presses **Upload Queue**.

![QR / JAB Scanner](/assets/images/docs/qr-scanner.webp)

## 7. Check the data

Once matches have been played, open **Data Validation**. It shows matches that are missing scouting and scouted totals that don't match the official score, so you can fix entries while the scouts still remember the match. See [Data Validation](DATA_VALIDATION.md).

![Data Validation](/assets/images/docs/data-validation.webp)

## 8. Use it

- **During quals:** [Predictor](PREDICTIONS_AND_RANKINGS.md#match-predictor) and [Match Planning](FEATURE_TOUR.md#match-planning-match-planning) to prepare for your next match, and [Projected Rankings](PREDICTIONS_AND_RANKINGS.md#projected-rankings) to see where the event is heading.
- **For picking:** Rankings, Compare Teams and [Custom Analytics](CUSTOM_ANALYTICS.md) to build your **Pick List**, then **Alliance Selection** during the draft.
- **After the event:** **Data Sharing** (`/backup`) to export a `.obsidiandb` backup or CSV archive.
