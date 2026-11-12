# Scouting Alliances

**Page:** Strategy → **Alliances** (`/alliances`)

A scouting alliance lets several teams on the same ObsidianScout server pool their scouting. Each team scouts some of the robots, and every team in the alliance sees all of the data in real time, with no exports or imports.

![Alliances](/assets/images/docs/alliances.webp)

## Setting one up

1. **Create the alliance.** A team admin presses **New Alliance** and gives it a name. Optionally, add the **Event Year** and **Event Code** (for example `2026` and `okok`) to tie the alliance to one event. Your team becomes the alliance's admin.
2. **Invite partners.** Under **Invite Partner Team**, enter each partner's team number. The partner team must already be registered on this server.
3. **Partners accept.** Invites show up on the partner's Alliances page, with a badge in their sidebar. The partner team accepts or declines.
4. **Activate it.** A team can belong to several alliances, but only one is active at a time (per program, FRC or FTC). Activating an alliance switches your team to its event and shared forms, and starts sharing.

## What changes while an alliance is active

- **Shared data:** match, pit and qualitative entries from every accepted partner appear in your Analytics, Graphs, Rankings, data pages, predictions and team profiles, alongside your own.
- **Shared event:** if the alliance has an event set, it replaces each member's own event setting, so everyone syncs and scouts the same schedule.
- **Shared forms:** the alliance's scouting forms replace each member's own match, pit and qualitative forms, so everyone records the same fields. Edit them under **Shared Scouting Configurations** with the visual editor or as raw JSON. Changes reach the other members in real time. **Import Team Config** copies your team's own forms as a starting point.
- **Shared API access:** if your team has no TBA, FIRST or Match 13 key, the sync uses a partner's.

Your own team settings are untouched. Deactivate or leave the alliance and everything goes back to them.

## When something isn't set up

If the active alliance has no event, or one of its shared forms has no fields, a banner says what's missing and how to fix it. Until then, members fall back to their own event or form for that part.

## Managing entries and members

- **Shared Alliance Data:** view, edit or delete the entries partners submitted. Alliance admins can edit any entry. Other members can edit their own team's entries.
- **Conflicting entries:** when two teams scout the same robot in the same match and their entries disagree, the robot is flagged as having a discrepancy on Data Validation and the predictors. See [Data Validation](DATA_VALIDATION.md).
- **Import Existing Data:** an alliance admin can copy scouting that a team already has on this server into the alliance's shared pool. Choose the source team, the event and the data types to include (match, pit, qualitative).
- **Members:** alliance admins can promote a member to admin or remove a member. Any member can leave. An alliance admin can delete the alliance.

## Good to know

- Users and team settings always stay with each team.
- The active alliance also decides whose **Alliance Selection** board your team sees.
- When a team shares its data publicly, partners' entries are left out of its public pages. See [Public Data](PUBLIC_DATA.md).
