/**
 * Base HTTP Module - ObsidianScout
 * Fetch wrapper, CSRF token handling, timeout management, offline cache fallbacks, and button loading states.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from './storage.js';

export const DEFAULT_REQUEST_TIMEOUT_MS = 20000;

export function getCsrfToken() {
    try {
        if (typeof document === 'undefined') return null;
        const meta = document.querySelector('meta[name="csrf-token"]');
        if (meta && meta.content) return meta.content;
        const value = `; ${document.cookie}`;
        const parts = value.split(`; XSRF-TOKEN=`);
        if (parts.length === 2) return parts.pop().split(';').shift();
    } catch (e) {}
    return null;
}

export function safeParse(text) {
    try {
        return JSON.parse(text);
    } catch (error) {
        return text;
    }
}

export function setButtonLoading(button, isLoading, loadingTextOrOptions = null) {
    if (!button) return;
    const btn = typeof button === 'string' ? document.getElementById(button) || document.querySelector(button) : button;
    if (!btn) return;

    let loadingText = null;
    let useDots = false;

    if (typeof loadingTextOrOptions === 'string') {
        loadingText = loadingTextOrOptions;
    } else if (loadingTextOrOptions && typeof loadingTextOrOptions === 'object') {
        loadingText = loadingTextOrOptions.text || null;
        useDots = !!loadingTextOrOptions.dots;
    }

    if (isLoading) {
        if (btn.dataset.loading === "true") return;

        if (btn.dataset.originalHtml === undefined) {
            btn.dataset.originalHtml = btn.innerHTML;
        }
        if (btn.dataset.originalDisabled === undefined) {
            btn.dataset.originalDisabled = btn.disabled ? "true" : "false";
        }

        const currentWidth = btn.getBoundingClientRect().width;
        if (currentWidth > 0 && !btn.style.minWidth) {
            btn.dataset.originalMinWidth = btn.style.minWidth || "";
            btn.style.minWidth = `${currentWidth}px`;
        }

        btn.disabled = true;
        btn.setAttribute("data-loading", "true");
        btn.classList.add("is-loading");

        const indicatorHtml = useDots
            ? `<span class="btn-dots" aria-hidden="true"><span></span><span></span><span></span></span>`
            : `<span class="btn-spinner" aria-hidden="true"></span>`;

        if (loadingText) {
            btn.innerHTML = `${indicatorHtml}<span>${loadingText}</span>`;
        } else {
            btn.innerHTML = `${indicatorHtml}${btn.dataset.originalHtml}`;
        }
    } else {
        if (btn.dataset.originalHtml !== undefined) {
            btn.innerHTML = btn.dataset.originalHtml;
            delete btn.dataset.originalHtml;
        }

        if (btn.dataset.originalDisabled !== undefined) {
            btn.disabled = btn.dataset.originalDisabled === "true";
            delete btn.dataset.originalDisabled;
        } else {
            btn.disabled = false;
        }

        if (btn.dataset.originalMinWidth !== undefined) {
            btn.style.minWidth = btn.dataset.originalMinWidth;
            delete btn.dataset.originalMinWidth;
        }

        btn.removeAttribute("data-loading");
        btn.classList.remove("is-loading");
    }
}

export async function withButtonLoading(button, asyncFn, loadingTextOrOptions = null) {
    setButtonLoading(button, true, loadingTextOrOptions);
    try {
        return await asyncFn();
    } finally {
        setButtonLoading(button, false);
    }
}

export async function request(path, options = {}) {
    const method = options.method || "GET";
    let isLoginPage = false;

    if (options.button) {
        setButtonLoading(options.button, true, options.loadingText);
    }

    try {
        isLoginPage = typeof document !== 'undefined' && document.body && document.body.getAttribute("data-page") === "login";
    } catch (e) {}

    if (method === "GET" && !navigator.onLine) {
        const cachedText = safeGetItem("cache:" + path);
        if (cachedText !== null) {
            console.log("[Offline Cache] Offline mode: Serving cached response for:", path);
            return safeParse(cachedText);
        }
    }

    const controller = new AbortController();
    const defaultTimeout = options.timeoutMs || DEFAULT_REQUEST_TIMEOUT_MS;
    const timeoutMs = defaultTimeout;
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);

    // Listen to option signal to cancel request
    if (options.signal) {
        options.signal.addEventListener("abort", () => {
            controller.abort();
        });
        if (options.signal.aborted) {
            controller.abort();
        }
    }

    const opts = {
        method: method,
        headers: options.headers || {},
        credentials: "same-origin",
        signal: controller.signal
    };
    opts.headers["X-Requested-With"] = "XMLHttpRequest";
    const csrfToken = getCsrfToken();
    if (csrfToken) {
        opts.headers["X-CSRF-Token"] = csrfToken;
    }

    if (method === "GET") {
        opts.cache = "no-cache";
    }
    if (options.json !== undefined) {
        opts.headers["Content-Type"] = "application/json";
        opts.body = JSON.stringify(options.json);
    } else if (options.body !== undefined) {
        opts.body = options.body;
    }

    try {
        const response = await fetch(path, opts);
        if (response.status >= 502 && response.status <= 504) {
            if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.setServerOnline === 'function') {
                window.Obsidianscout.setServerOnline(false);
            }
        } else {
            if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.setServerOnline === 'function') {
                window.Obsidianscout.setServerOnline(true);
            }
        }

        if (response.status === 204) {
            return null;
        }

        const text = await response.text();
        const data = text ? safeParse(text) : null;
        if (!response.ok) {
            if (response.status === 401) {
                const isAuthRequest = path.includes("/api/auth/login") || path.includes("/api/auth/register") || path.includes("/api/auth/status") || path.includes("/api/push");
                if (!isLoginPage && !isAuthRequest) {
                    if (window.Obsidianscout && typeof window.Obsidianscout.checkLoginStatus === 'function') {
                        window.Obsidianscout.checkLoginStatus().then(loggedIn => {
                            if (!loggedIn) {
                                safeRemoveItem("cache:/api/auth/me");
                                window.location.href = "/";
                            }
                        });
                    } else {
                        safeRemoveItem("cache:/api/auth/me");
                        window.location.href = "/";
                    }
                    const err = new Error("Session expired. Redirecting...");
                    err.status = 401;
                    throw err;
                }
            }
            const message = data && data.error ? data.error : "Request failed";
            const err = new Error(message);
            err.status = response.status;
            throw err;
        }

        if (opts.method === "GET") {
            safeSetItem("cache:" + path, text);
            if (path && (path === "/api/settings" || path.startsWith("/api/settings?"))) {
                try {
                    const settings = data.settings || data;
                    if (window.Obsidianscout && typeof window.Obsidianscout.applyCustomTheme === 'function') {
                        window.Obsidianscout.applyCustomTheme(settings);
                    }
                } catch (e) {
                    console.error("Failed to apply custom theme from settings fetch:", e);
                }
            }
        } else {
            safeRemoveItem("cache:" + path);
            const basePath = path.split("?")[0];
            safeRemoveItem("cache:" + basePath);
            if (basePath.includes("scouting")) {
                safeRemoveItem("cache:/api/summary");
            }
        }

        return data;
    } catch (error) {
        if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.setServerOnline === 'function') {
            window.Obsidianscout.setServerOnline(false);
        }

        if (method === "GET") {
            const cachedText = safeGetItem("cache:" + path);
            if (cachedText !== null) {
                console.warn("[Offline Cache] Network fetch failed, falling back to cache for:", path, error);
                return safeParse(cachedText);
            }
        }

        const isAbort = error && error.name === "AbortError";
        if (isAbort) {
            if (options.signal && options.signal.aborted) {
                throw error;
            }
            throw new Error("Request timed out. Try refreshing this page.");
        }
        throw error;
    } finally {
        window.clearTimeout(timeout);
        if (options.button) {
            setButtonLoading(options.button, false);
        }
    }
}
