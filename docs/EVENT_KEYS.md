# Event Codes and Keys

Every event, match and scouting entry in ObsidianScout is filed under an **event key**: the season year followed by the event code, in lowercase. This is the same format The Blue Alliance uses.

| Season year | Event code | Event key |
|---|---|---|
| `2026` | `okok` | `2026okok` |
| `2025` | `okok` | `2025okok` |
| `2026` | `txhou` | `2026txhou` |

Because the year is part of the key, the same event in two seasons is two separate events, and last season's data never mixes into this season's.

## Setting your event

On **Admin Settings → API keys**, set **Season year** and **Event code**. You can also pick the event on the **Events** page after syncing the season's events.

- Type the code in any case, with or without the year: `OKOK`, `okok` and `2026okok` all become `2026okok` for season 2026.
- **FTC:** the season year is the year the season *started*. The 2025–2026 season is season `2025`.
- **Alternate event code (FIRST API):** a few off-season events are listed under a different code by FIRST than by The Blue Alliance and Match 13 (for example TBA `cttd`, FIRST `molee`). Enter FIRST's code here only for those events, and leave it blank otherwise.
- **Championship divisions** are handled for you: FIRST's division names (`archimedes`, `curie`, `daly`, `galileo`, `hopper`, `johnson`, `milstein`, `newton`) are stored under the TBA codes (`arc`, `cur`, `dal`, `gal`, `hop`, `joh`, `mil`, `new`).

If your team is in an active [scouting alliance](SCOUTING_ALLIANCES.md) that has its own event, the alliance's event is used instead of yours while the alliance is active.

## Custom events

Events created by hand on **Events → Create Custom Event** keep the key you type (it must be unique and lowercase), so off-season and scrimmage events can use any key. If the same event later arrives from an API sync under its official key, ObsidianScout moves the custom event's matches, teams and scouting across to the official key and merges any duplicates.

## Mobile app and API

Endpoints that take an event (`event_id` in the mobile API) accept:

1. the event's ID from the `/events` list;
2. a full event key such as `2026okok`, used as is;
3. a bare code such as `okok`, which gets the season from your scouting config (`season`), or from the season year in your settings, prefixed.

With no event given, your team's current event is used.

## For developers

- Building keys: `canonicalTbaEventKey` and `canonicalTbaEventCode` (`integrations/SettingsService.kt`, `integrations/IntegrationService.kt`); `ApiSettings.resolvedEventKey()` gives a team's current key.
- Custom events joining their API copy: `integrations/EventTeamCanonical.kt` (`moveEventData`).
- Mobile resolution: `resolveEventKey` in `routes/MobileRoutes.kt`.
- Alliance events: `AllianceService.getEffectiveSettings`.
