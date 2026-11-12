# Documentation Index

Every `.md` file directly in this folder appears in the in-app Docs page (`/docs`), so keep it to user and operator guides. Design notes and plans go in `docs/dev/`, which the Docs page doesn't list.

## Start here

- [Feature Tour](FEATURE_TOUR.md): every page, with a screenshot of each. Pinned first on the Docs page.
- [Getting Started](GETTING_STARTED.md): set up a team, from the setup wizard to the first scouted match. Pinned second.

## Guides

- [Predictions and Rankings](PREDICTIONS_AND_RANKINGS.md): the Match Predictor, the Event Predictor, Projected Rankings, the match predictions game and Recency Bias.
- [Data Validation](DATA_VALIDATION.md): find missing scouting and entries that don't match the official scores.
- [Custom Analytics](CUSTOM_ANALYTICS.md): build, save and share your own dashboards.
- [Scouting Alliances](SCOUTING_ALLIANCES.md): pool scouting with partner teams.
- [Public Data](PUBLIC_DATA.md): share your team's data read-only with anyone.
- [Local AI Assistant](LOCAL_AI_ASSISTANT.md): ask questions about your data with an AI that runs on your device (also in Spanish, Turkish and Hebrew).
- [Event Codes and Keys](EVENT_KEYS.md): how events are identified, including custom events and the mobile API.

## For server operators

- [Blob Storage (SeaweedFS)](BLOB_STORAGE.md): where photos and files are kept, and how to watch it.
- `MOBILE_API_SERVER_DOCS.md` (repository root): the mobile app API under `/api/mobile/*`.

## Writing docs

- Translations sit next to the English file with a language suffix (`LOCAL_AI_ASSISTANT_es.md`, `_tr`, `_he`). The Docs page shows them when the user's language matches.
- Link to another doc by its filename (`[Data Validation](DATA_VALIDATION.md)`); the Docs page opens it in place. Headings get GitHub-style anchors (`PREDICTIONS_AND_RANKINGS.md#recency-bias`).
- Screenshots live in `src/main/resources/static/assets/images/docs/` and are referenced as `/assets/images/docs/<page>.webp`. To regenerate them, with the server running locally:

  ```
  npm install --prefix scripts/.doc-shots playwright-core@1.55.0   # one time
  node scripts/capture-doc-screenshots.js [accounts.json] [page,page,...]
  ```

  `accounts.json` defaults to `data/test-accounts.local.json` and needs a local test admin account. The script uses your installed Chrome.
