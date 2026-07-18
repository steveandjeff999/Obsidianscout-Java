# Security Audit Report

Scope: quick repository review for leaked secrets, frontend exposure, and SQL injection risk.

## Confirmed Findings

### 1. Team settings API returns integration secrets to the browser

The team settings endpoint requires an authenticated session, but it returns `apiKeys` in the payload. The frontend then writes those values into local storage and repopulates the password fields from the response. That means any authenticated user on the team can recover the stored TBA / FIRST credentials from the browser.

Relevant code:
- [Routes.kt](src/main/kotlin/com/obsidianscout/routes/Routes.kt#L699-L718)
- [Models.kt](src/main/kotlin/com/obsidianscout/routes/Models.kt#L95-L111)
- [common.js](src/main/resources/static/js/common.js#L646-L650)
- [common.js](src/main/resources/static/js/common.js#L903-L908)

### 2. Mobile config endpoints expose the same secrets to authenticated team members

Mobile config responses copy `tbaKey`, `firstUsername`, and `firstKey` into `ScoutingConfig`, and the `/config/game`, `/config/pit`, and `/config/qualitative` handlers return that config to any authenticated mobile user. In addition, alliance effective settings can pull keys from other active alliance members, which broadens the exposure beyond a single team.

Relevant code:
- [MobileRoutes.kt](src/main/kotlin/com/obsidianscout/routes/MobileRoutes.kt#L940-L983)
- [MobileRoutes.kt](src/main/kotlin/com/obsidianscout/routes/MobileRoutes.kt#L1754-L2021)
- [AllianceService.kt](src/main/kotlin/com/obsidianscout/scouting/AllianceService.kt#L930-L965)

### 3. Superadmin SMTP password is exposed back into the frontend

The superadmin email settings endpoint returns `passwordPlain`, and the admin UI writes it back into the password field. This is restricted to superadmins, but it still exposes plaintext credentials to the browser and any page scripts/extensions running in that session.

Relevant code:
- [SettingsService.kt](src/main/kotlin/com/obsidianscout/integrations/SettingsService.kt#L227-L235)
- [admin-settings.js](src/main/resources/static/js/admin-settings.js#L491-L536)

## SQL Injection Review

I did not find a concrete SQL injection path in this pass.

Observed mitigations:
- The user search path in [AuthService.kt](src/main/kotlin/com/obsidianscout/auth/AuthService.kt#L166-L193) uses Exposed query builders rather than raw string concatenation.
- The migration and database bootstrap code I checked use fixed SQL or validated identifiers rather than interpolating untrusted request data.

## Risk Summary

The primary issue is secret exposure, not SQL injection. The most important leak is the API key flow, which is available to authenticated users and can propagate across active alliance members. The SMTP password exposure is narrower but still undesirable because it places plaintext credentials into browser-managed state.

## Recommended Next Step

Mask secrets in API responses and keep them server-side only. For UI editing, return boolean flags or redacted placeholders instead of the stored secret values, and only overwrite a secret when the user explicitly enters a new one.