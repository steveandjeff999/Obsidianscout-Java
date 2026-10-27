# Public Data

Teams can share their scouting data with anyone. Visitors don't need an account, and they can run the same analytics the team uses.

## Turning it on

1. Open **Admin Settings** (web) or **Settings** (app) as a team admin.
2. Check **Make data publicly available** and confirm.
3. Optional: check **Include notes and comments** to show free-text answers (textarea fields and any field named like a note or comment). They are hidden by default. Turning this on also adds **Qual Rankings** to your public pages.
4. Save.

To hide the data again, uncheck the box and save. The data disappears on the next request: every public request checks the setting, and public responses are never cached.

## What visitors see

The **Public Data** page (`/public-data`) lists every sharing team, by team or by event, with how many match, pit and qualitative entries each event has. From there, visitors can open these pages read-only:

Graphs, Analytics, Compare Teams, Custom Analytics, Rankings, Projected Rankings, Match Predictor, Event Predictor, Match Data, Pit Data, Qual Data, All Data and team pages.

The pages use the team's own scouting configs, so fields, scoring and graphs look the same as they do for the team.

If a team or event has only pit or qualitative entries, the pages that need match data are greyed out. There is no quantitative data for them to analyze.

Pages built on qualitative answers and notes (currently **Qual Rankings**) only appear for teams that also share notes. They're greyed out for events with no qualitative entries. The server lists these pages in `PUBLIC_NOTES_PAGES`, and `public-mode.js` lists them in `NOTES_PAGES`. Add a page to both to offer it the same way.

Signed-in visitors see **Go to your dashboard** instead of **Sign in**.

## What stays private

- **Scout names:** every name field shows as "Scout".
- **Alliance partners' data:** a public view only includes the team's own entries, even when the team is in a scouting alliance.
- **API keys and settings:** only the event, stat sources and theme are shown.
- **Other team features:** assignments, users, chat, checklists, pick lists, match plans, saved custom reports and share links aren't available on public data.
- **No changes:** visitors can't edit anything. Every write is rejected.

## For developers

- Public pages live at `/public/{team}/{page}?event={eventKey}`. In public mode, `request()` in `js/base/public-mode.js` sends API calls to `/api/public/{team}/{event}/...`.
- `routes/PublicDataRoutes.kt` mounts the normal read routes under that prefix, behind a gate. The gate:
  - only allows GET requests;
  - only allows the paths in `PUBLIC_DATA_ALLOWED_PATHS`;
  - checks the `dataPublic` setting;
  - limits each IP to 600 requests a minute;
  - strips names, credentials and (optionally) notes from responses.
- `PublicViewContext` keeps alliance partners out (`AllianceService.getAlliancePartnerTeams`) and replaces the event in settings with the one from the URL.
- To expose a new read endpoint, add it to both `PUBLIC_DATA_ALLOWED_PATHS` (server) and `ALLOWED_API` (`public-mode.js`).
