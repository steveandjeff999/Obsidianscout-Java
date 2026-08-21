/**
 * Utility Helpers Module - ObsidianScout
 * Formatting helpers, timezone calculators, team key formatters, program resolver, and async UI state placeholders.
 */

import { safeGetItem } from '../base/storage.js';
import { t } from '../base/i18n.js';

export function resolveEventKey(settings) {
    if (!settings) {
        return "";
    }
    const code = (settings.eventCode || "").trim();
    if (code) {
        return `${settings.year}${code}`.toLowerCase();
    }
    return (settings.eventKey || "").trim().toLowerCase();
}

export function getDeviceTimezone() {
    try {
        return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    } catch (e) {
        return "UTC";
    }
}

/**
 * Formats a UTC epoch-seconds timestamp into a human-readable string
 * using the browser device's local timezone by default.
 * Pass an explicit `timezone` to override (e.g. for event-venue time).
 */
export function formatTimestamp(epochSeconds, timezone) {
    if (!epochSeconds) {
        return "";
    }
    const date = new Date(epochSeconds * 1000);
    const tz = timezone || getDeviceTimezone();
    try {
        return new Intl.DateTimeFormat("en-US", {
            dateStyle: "medium",
            timeStyle: "short",
            timeZone: tz
        }).format(date);
    } catch (error) {
        return date.toLocaleString();
    }
}

/**
 * Converts a value from a `<input type="datetime-local">` element (local device time)
 * into a UTC epoch seconds integer, ready to send to the server.
 * Returns null if the input is empty or invalid.
 */
export function localToUtcEpoch(datetimeLocalValue) {
    if (!datetimeLocalValue) return null;
    const ms = new Date(datetimeLocalValue).getTime();
    if (isNaN(ms)) return null;
    return Math.floor(ms / 1000);
}

/**
 * Returns a <span> element containing the match time in device-local timezone.
 * If the event venue timezone differs from the device timezone, a tooltip badge
 * is appended showing the time in the event's venue timezone.
 *
 * @param {number|null} epochSeconds  UTC epoch seconds
 * @param {string|null} eventTimezone  IANA timezone for the event venue (e.g. "America/New_York")
 * @returns {HTMLElement}
 */
export function formatTimestampWithVenueTooltip(epochSeconds, eventTimezone) {
    const wrapper = document.createElement("span");
    wrapper.className = "time-cell";

    if (!epochSeconds) {
        wrapper.textContent = "";
        return wrapper;
    }

    const deviceTz = getDeviceTimezone();
    const localStr = formatTimestamp(epochSeconds, deviceTz);
    const localSpan = document.createElement("span");
    localSpan.textContent = localStr;
    wrapper.appendChild(localSpan);

    // Only show venue tooltip if eventTimezone is set AND differs from device tz
    if (eventTimezone && eventTimezone !== deviceTz) {
        try {
            const venueStr = formatTimestamp(epochSeconds, eventTimezone);
            // Quick sanity: if both strings are identical there's nothing to show
            if (venueStr !== localStr) {
                const badge = document.createElement("span");
                badge.className = "venue-tz-badge";
                badge.setAttribute("aria-label", `Venue time (${eventTimezone}): ${venueStr}`);
                badge.setAttribute("data-tooltip", `Venue (${eventTimezone}): ${venueStr}`);
                badge.textContent = "\uD83C\uDF0D"; // 🌍
                wrapper.appendChild(badge);
            }
        } catch (e) {
            // ignore invalid timezone strings
        }
    }

    return wrapper;
}

export function formatTeam(teamKey, teamNumber) {
    if (!teamKey) {
        return teamNumber !== undefined && teamNumber !== null ? String(teamNumber) : "";
    }
    
    // Remove 'frc' or 'ftc' prefix
    const cleanKey = teamKey.replace(/^(frc|ftc)/, "");
    
    // Split if it's already a slash-merged format (e.g. 254b/9999 or frc254b/9999)
    const parts = cleanKey.split("/");
    const keyPart = parts[0];
    const numPart = parts.length > 1 ? parts[1] : (teamNumber !== undefined && teamNumber !== null ? String(teamNumber).replace(/^(frc|ftc)/, "") : "");
    
    if (!numPart || keyPart === numPart) {
        return keyPart;
    }
    
    const displayPref = safeGetItem("obsidianscout:team_display") || "merged";
    if (displayPref === "number") {
        return numPart;
    } else if (displayPref === "key") {
        return keyPart;
    } else {
        // "merged" or fallback
        return `${keyPart}/${numPart}`;
    }
}

export function showLoadingSpinner(container, text) {
    if (!container) return;
    const spinnerText = text || (typeof t === 'function' ? t('status.loading', 'Loading data...') : 'Loading data...');
    container.innerHTML = `
        <div class="spinner-container">
            <div class="spinner"></div>
            <div class="spinner-text">${spinnerText}</div>
        </div>
    `;
}

export function showRetryButton(container, message, onRetry) {
    if (!container) return;
    const errMessage = message || (typeof t === 'function' ? t('status.load_failed', 'Failed to load data.') : 'Failed to load data.');
    const btnText = typeof t === 'function' ? t('btn.retry', 'Retry') : 'Retry';
    container.innerHTML = `
        <div class="retry-container">
            <div class="retry-error-text">${errMessage}</div>
            <button class="retry-btn" type="button">${btnText}</button>
        </div>
    `;
    const btn = container.querySelector(".retry-btn");
    if (btn && typeof onRetry === "function") {
        btn.addEventListener("click", onRetry);
    }
}

export function getProgram() {
    try {
        const meText = safeGetItem("cache:/api/auth/me");
        if (meText) {
            const parsed = JSON.parse(meText);
            if (parsed && parsed.user && parsed.user.program) {
                return parsed.user.program;
            }
        }
    } catch (e) {
        console.warn("Failed to get program from cache:", e);
    }
    return "FRC";
}

export function getProgramPrefix() {
    const prog = getProgram();
    return (prog || "FRC").toLowerCase();
}
