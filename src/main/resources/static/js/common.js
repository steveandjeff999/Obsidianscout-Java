(function () {
    console.log("[CommonJS] Script initialized and loaded.");
    const toastRootId = "toast-root";
    const sidebarCollapseKey = "obsidian-sidebar-collapsed";
    const DEFAULT_REQUEST_TIMEOUT_MS = 20000;

    // Role hierarchy (lower index = higher privilege)
    const ROLE_HIERARCHY = ["SUPERADMIN", "ADMIN", "ANALYTICS", "SCOUT"];

    const CACHE_CONFIGS = {
        "match-scouting": {
            key: "pending_scouting_entries",
            endpoint: "/api/scouting",
            label: "Match Scouting",
            hasMatch: true
        },
        "pit-scouting": {
            key: "pending_pit_scouting_entries",
            endpoint: "/api/pit-scouting",
            label: "Pit Scouting",
            hasMatch: false
        },
        "qual-scouting": {
            key: "pending_qualitative_entries",
            endpoint: "/api/qual-scouting",
            label: "Qualitative Scouting",
            hasMatch: true
        },
        "prescout-scouting": {
            key: "pending_prescout_scouting_entries",
            endpoint: "/api/prescout/scouting",
            label: "Prescout Match",
            hasMatch: true
        },
        "prescout-pit-scouting": {
            key: "pending_prescout_pit_scouting_entries",
            endpoint: "/api/prescout/pit-scouting",
            label: "Prescout Pit",
            hasMatch: false
        },
        "prescout-qual-scouting": {
            key: "pending_prescout_qualitative_entries",
            endpoint: "/api/prescout/qual-scouting",
            label: "Prescout Qualitative",
            hasMatch: true
        }
    };

    function safeGetItem(key) {
        try {
            return localStorage.getItem(key);
        } catch (e) {
            console.warn("[Storage] Failed to read from localStorage:", e);
            return null;
        }
    }

    function safeSetItem(key, value) {
        try {
            localStorage.setItem(key, value);
        } catch (e) {
            console.warn("[Storage] Failed to write to localStorage:", e);
        }
    }

    function safeRemoveItem(key) {
        try {
            localStorage.removeItem(key);
        } catch (e) {
            console.warn("[Storage] Failed to remove from localStorage:", e);
        }
    }

    function clearAllCaches() {
        // Trigger background sync with instructions to clear old/stale keys only after successful downloads
        syncOfflineCache(true);
    }

    function saveScrollPositions() {
        try {
            const scrolls = {
                windowX: window.scrollX || window.pageXOffset,
                windowY: window.scrollY || window.pageYOffset,
                elements: []
            };
            document.querySelectorAll('.table-scroll, .main-content, .sidebar').forEach((el, index) => {
                if (el.scrollTop > 0 || el.scrollLeft > 0) {
                    scrolls.elements.push({
                        index: index,
                        class: el.className,
                        id: el.id,
                        top: el.scrollTop,
                        left: el.scrollLeft
                    });
                }
            });
            safeSetItem("obsidianscout:scroll_positions", JSON.stringify(scrolls));
        } catch (e) {
            console.warn("Failed to save scroll positions:", e);
        }
    }

    function restoreScrollPositions() {
        try {
            const saved = safeGetItem("obsidianscout:scroll_positions");
            if (!saved) return;
            safeRemoveItem("obsidianscout:scroll_positions");
            const scrolls = JSON.parse(saved);
            if (!scrolls) return;

            setTimeout(() => {
                window.scrollTo(scrolls.windowX || 0, scrolls.windowY || 0);
                
                scrolls.elements.forEach(item => {
                    let el = null;
                    if (item.id) {
                        el = document.getElementById(item.id);
                    } else {
                        const candidates = document.querySelectorAll('.table-scroll, .main-content, .sidebar');
                        if (candidates[item.index]) {
                            el = candidates[item.index];
                        }
                    }
                    if (el) {
                        el.scrollTop = item.top;
                        el.scrollLeft = item.left;
                    }
                });
            }, 100);
        } catch (e) {
            console.warn("Failed to restore scroll positions:", e);
        }
    }

    async function request(path, options = {}) {
        const method = options.method || "GET";
        let isLoginPage = false;
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
        if (method === "GET") {
            opts.cache = "no-cache";
        }
        if (options.json !== undefined) {
            opts.headers["Content-Type"] = "application/json";
            opts.body = JSON.stringify(options.json);
        }

        try {
            const response = await fetch(path, opts);
            if (response.status === 204) {
                return null;
            }

            const text = await response.text();
            const data = text ? safeParse(text) : null;
            if (!response.ok) {
                if (response.status === 401) {
                    const isAuthRequest = path.includes("/api/auth/login") || path.includes("/api/auth/register") || path.includes("/api/auth/status") || path.includes("/api/push");
                    if (!isLoginPage && !isAuthRequest) {
                        checkLoginStatus().then(loggedIn => {
                            if (!loggedIn) {
                                safeRemoveItem("cache:/api/auth/me");
                                window.location.href = "/";
                            }
                        });
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
                        applyCustomTheme(settings);
                    } catch (e) {
                        console.error("Failed to apply custom theme from settings fetch:", e);
                    }
                }
            } else {
                clearAllCaches();
            }

            return data;
        } catch (error) {
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
        }
    }

    function safeParse(text) {
        try {
            return JSON.parse(text);
        } catch (error) {
            return text;
        }
    }

    async function checkLoginStatus() {
        try {
            const response = await fetch("/api/auth/status", {
                method: "GET",
                credentials: "same-origin",
                headers: { "Accept": "application/json" }
            });
            if (response.status === 401) {
                return false;
            }
            if (!response.ok) {
                const cachedMe = safeGetItem("cache:/api/auth/me");
                if (cachedMe) {
                    console.warn("[Offline Cache] Server status check failed (not 401). Assuming logged in from cache.");
                    return true;
                }
                return false;
            }
            const data = await response.json();
            return !!(data && data.loggedIn);
        } catch (error) {
            const cachedMe = safeGetItem("cache:/api/auth/me");
            if (cachedMe) {
                console.warn("[Offline Cache] Network check failed. Assuming logged in from cache.", error);
                return true;
            }
            return false;
        }
    }

    async function getMe() {
        try {
            const result = await request("/api/auth/me");
            return result.user;
        } catch (error) {
            return null;
        }
    }

    // i18n support
    const DEFAULT_LANG = safeGetItem("obsidianscout:lang") || "en";
    let currentLang = DEFAULT_LANG;
    const i18nCache = {};

    let translationObserver = null;

    async function loadLocale(lang) {
        if (!lang) lang = "en";
        if (i18nCache[lang]) return i18nCache[lang];

        try {
            const res = await fetch(`/i18n/${lang}.json`);
            if (!res.ok) throw new Error("Locale fetch failed");
            const json = await res.json();
            i18nCache[lang] = json;
            return json;
        } catch (error) {
            if (lang !== "en") return loadLocale("en");
            return {};
        }
    }

    function t(key, fallback) {
        const dict = i18nCache[currentLang] || {};
        return dict[key] || (i18nCache['en'] && i18nCache['en'][key]) || fallback || key;
    }

    /**
     * Localize a dynamic value which may be:
     * - a string (either literal or an i18n key)
     * - an object mapping language codes to translations { en: 'Label', es: 'Etiqueta' }
     */
    function localize(value) {
        if (value === null || value === undefined) return '';
        if (typeof value === 'object') {
            // prefer exact language, fallback to en then any available
            if (value[currentLang]) return value[currentLang];
            if (value.en) return value.en;
            // return first available value
            const keys = Object.keys(value);
            if (keys.length) return value[keys[0]];
            return '';
        }
        if (typeof value === 'string') {
            // if key exists in i18n dicts, return that translation
            const dict = i18nCache[currentLang] || {};
            if (dict[value]) return dict[value];
            if (i18nCache['en'] && i18nCache['en'][value]) return i18nCache['en'][value];
            return value;
        }
        return String(value);
    }

    async function setLanguage(lang) {
        currentLang = lang || 'en';
        safeSetItem('obsidianscout:lang', currentLang);
        await loadLocale(currentLang);
        applyTranslations();
        window.dispatchEvent(new CustomEvent('obsidianscout:languagechange', { detail: { lang: currentLang } }));
    }

    function applyTranslations() {
        if (translationObserver) {
            translationObserver.disconnect();
        }

        // 1. Translate elements with data-i18n
        document.querySelectorAll('[data-i18n]').forEach((el) => {
            const key = el.dataset.i18n;
            if (!key) return;
            const text = t(key);
            if (el.textContent !== text) {
                el.textContent = text;
            }
        });

        document.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
            const key = el.dataset.i18nPlaceholder;
            if (!key) return;
            const text = t(key);
            if (el.getAttribute('placeholder') !== text) {
                el.setAttribute('placeholder', text);
            }
        });

        // 2. Scan for untagged elements containing English text matching en.json
        const enDict = i18nCache['en'] || {};
        const valToKey = {};
        for (const [k, v] of Object.entries(enDict)) {
            if (typeof v === 'string') {
                valToKey[v.trim()] = k;
            }
        }

        const selectors = 'h1, h2, h3, h4, h5, h6, p, span, label, button, option, td, th, div, a';
        document.querySelectorAll(selectors).forEach((el) => {
            if (el.hasAttribute('data-i18n')) return;
            if (el.children.length > 0) return;
            // Ignore sidebar links because they have dynamic page routing names
            if (el.classList.contains('sidebar-link')) return;

            const txt = (el.textContent || '').trim();
            if (!txt) return;

            if (valToKey[txt]) {
                const key = valToKey[txt];
                el.setAttribute('data-i18n', key);
                const translated = t(key);
                if (el.textContent !== translated) {
                    el.textContent = translated;
                }
            }
        });

        document.querySelectorAll('input[placeholder], textarea[placeholder]').forEach((el) => {
            if (el.hasAttribute('data-i18n-placeholder')) return;
            const plc = (el.getAttribute('placeholder') || '').trim();
            if (!plc) return;

            if (valToKey[plc]) {
                const key = valToKey[plc];
                el.setAttribute('data-i18n-placeholder', key);
                const translated = t(key);
                if (el.getAttribute('placeholder') !== translated) {
                    el.setAttribute('placeholder', translated);
                }
            }
        });

        // Sidebar links by data-page
        document.querySelectorAll('.sidebar-link[data-page]').forEach((link) => {
            const page = link.dataset.page;
            const key = `nav.${page}`;
            const text = t(key);
            if (link.textContent !== text) {
                link.textContent = text;
                link.title = text;
            }
        });

        // Theme toggle and logout
        const themeBtn = document.querySelector("[data-action='toggle-theme']");
        if (themeBtn) {
            const text = t('btn.toggle_theme', themeBtn.textContent);
            if (themeBtn.textContent !== text) themeBtn.textContent = text;
        }
        const logoutBtn = document.querySelector("[data-action='logout']");
        if (logoutBtn) {
            const text = t('btn.logout', logoutBtn.textContent);
            if (logoutBtn.textContent !== text) logoutBtn.textContent = text;
        }

        // Connection widget status text
        const widget = document.getElementById('connection-status-widget');
        if (widget) {
            const textEl = widget.querySelector('.status-text');
            if (textEl) {
                const isOnline = navigator.onLine;
                const text = isOnline ? t('connection.online') : t('connection.offline');
                if (textEl.textContent !== text) textEl.textContent = text;
            }
        }

        // Direction for RTL languages
        if (currentLang === 'he') {
            document.documentElement.dir = 'rtl';
        } else {
            document.documentElement.dir = 'ltr';
        }

        if (translationObserver) {
            translationObserver.observe(document.body, { childList: true, subtree: true });
        }
    }

    function injectLanguageSelector(sidebar) {
        try {
            const footer = sidebar.querySelector('.sidebar-footer');
            if (!footer) return;
            // Avoid duplicate
            if (document.getElementById('lang-select')) return;
            const wrap = document.createElement('div');
            wrap.className = 'field';
            wrap.style.marginTop = '8px';
            const sel = document.createElement('select');
            sel.id = 'lang-select';
            sel.style.padding = '6px';
            const opts = [
                { v: 'en', l: 'English' },
                { v: 'es', l: 'Español' },
                { v: 'tr', l: 'Türkçe' },
                { v: 'he', l: 'עברית' }
            ];
            opts.forEach((o) => {
                const opt = document.createElement('option');
                opt.value = o.v;
                opt.textContent = o.l;
                sel.appendChild(opt);
            });
            sel.value = safeGetItem('obsidianscout:lang') || 'en';
            sel.addEventListener('change', async (e) => {
                await setLanguage(e.target.value);
                updateConnectionStatus();
            });
            wrap.appendChild(sel);
            footer.insertBefore(wrap, footer.firstChild);
        } catch (e) {
            console.warn('Failed to inject language selector', e);
        }
    }

    async function renderServerVersion(sidebar) {
        try {
            const sb = sidebar || document.querySelector(".sidebar");
            if (!sb) return;
            const versionEl = sb.querySelector("#server-version") || document.getElementById("server-version");
            if (!versionEl) return;

            const cachedVersion = safeGetItem("obsidianscout:server_version");
            if (cachedVersion) {
                const displayVer = cachedVersion.startsWith("v") ? cachedVersion : `v${cachedVersion}`;
                versionEl.textContent = `Server ${displayVer}`;
            }

            if (navigator.onLine) {
                const data = await request("/api/version");
                if (data && data.version) {
                    safeSetItem("obsidianscout:server_version", data.version);
                    const displayVer = data.version.startsWith("v") ? data.version : `v${data.version}`;
                    versionEl.textContent = `Server ${displayVer}`;
                }
            }
        } catch (e) {
            console.warn("Failed to fetch server version:", e);
        }
    }

    function showSetupWizardModal(me, settings, forceOpen = false) {
        if (document.getElementById("setup-wizard-backdrop")) return;

        const backdrop = document.createElement("div");
        backdrop.id = "setup-wizard-backdrop";
        backdrop.className = "modal-backdrop show";

        const container = document.createElement("div");
        container.className = "modal-container";
        container.style.width = "min(650px, 95vw)";
        container.style.maxHeight = "95vh";
        backdrop.appendChild(container);
        document.body.appendChild(backdrop);

        let currentStep = 1;
        const totalSteps = 4;
        
        const localSettings = JSON.parse(JSON.stringify(settings));
        if (!localSettings.apiKeys) {
            localSettings.apiKeys = { tbaKey: "", firstUsername: "", firstKey: "" };
        }
        
        let uploadedGameConfigJson = null;

        function updateStepUI() {
            const dots = container.querySelectorAll(".wizard-step-dot");
            dots.forEach((dot, index) => {
                const stepNum = index + 1;
                dot.classList.remove("active", "completed");
                if (stepNum === currentStep) {
                    dot.classList.add("active");
                } else if (stepNum < currentStep) {
                    dot.classList.add("completed");
                    dot.innerHTML = "&#10003;";
                } else {
                    dot.innerHTML = stepNum;
                }
            });

            const progressPercent = ((currentStep - 1) / (totalSteps - 1)) * 100;
            const progressBar = container.querySelector(".wizard-progress-bar");
            if (progressBar) {
                progressBar.style.width = `${progressPercent}%`;
            }

            const contents = container.querySelectorAll(".wizard-step-content");
            contents.forEach((content) => {
                const stepNum = parseInt(content.dataset.step, 10);
                if (stepNum === currentStep) {
                    content.classList.add("active");
                } else {
                    content.classList.remove("active");
                }
            });

            const btnBack = container.querySelector(".btn-wizard-back");
            const btnNext = container.querySelector(".btn-wizard-next");
            
            if (btnBack) {
                btnBack.style.display = currentStep === 1 ? "none" : "block";
            }
            if (btnNext) {
                if (currentStep === totalSteps) {
                    btnNext.textContent = "Finish & Sync";
                    btnNext.className = "btn btn-wizard-next";
                } else {
                    btnNext.textContent = "Next";
                    btnNext.className = "btn secondary btn-wizard-next";
                }
            }
        }

        function validateStep() {
            if (currentStep === 2) {
                const yearInput = container.querySelector("#wizard-year").value.trim();
                if (yearInput) {
                    const yearVal = parseInt(yearInput, 10);
                    if (isNaN(yearVal) || yearVal < 2000 || yearVal > 2100) {
                        showToast("Please enter a valid season year (e.g. 2026)", "error");
                        return false;
                    }
                } else if (!localSettings.year) {
                    showToast("Please enter a valid season year (e.g. 2026)", "error");
                    return false;
                }

                const timezoneVal = container.querySelector("#wizard-timezone").value.trim();
                if (!timezoneVal && !localSettings.timezone) {
                    showToast("Please enter a timezone (e.g. America/New_York)", "error");
                    return false;
                }
            }
            return true;
        }

        function saveInputsToState() {
            if (currentStep === 2) {
                const yearInput = container.querySelector("#wizard-year");
                if (yearInput && yearInput.value) {
                    const yearVal = parseInt(yearInput.value, 10);
                    if (!isNaN(yearVal)) localSettings.year = yearVal;
                }
                const codeInput = container.querySelector("#wizard-event-code");
                if (codeInput) {
                    const codeVal = codeInput.value.trim();
                    if (codeVal) localSettings.eventCode = codeVal.toLowerCase();
                }
                const tzInput = container.querySelector("#wizard-timezone");
                if (tzInput) {
                    const tzVal = tzInput.value.trim();
                    if (tzVal) localSettings.timezone = tzVal;
                }
                const sourceInput = container.querySelector("#wizard-source");
                if (sourceInput && sourceInput.value) {
                    localSettings.preferredSource = sourceInput.value;
                }
            } else if (currentStep === 3) {
                const tbaInput = container.querySelector("#wizard-tba-key");
                if (tbaInput) {
                    const tbaVal = tbaInput.value.trim();
                    if (tbaVal) localSettings.apiKeys.tbaKey = tbaVal;
                }
                const firstUserInput = container.querySelector("#wizard-first-user");
                if (firstUserInput) {
                    const firstUserVal = firstUserInput.value.trim();
                    if (firstUserVal) localSettings.apiKeys.firstUsername = firstUserVal;
                }
                const firstKeyInput = container.querySelector("#wizard-first-key");
                if (firstKeyInput) {
                    const firstKeyVal = firstKeyInput.value.trim();
                    if (firstKeyVal) localSettings.apiKeys.firstKey = firstKeyVal;
                }
            }
        }

        function closeWizard() {
            backdrop.remove();
        }

        async function handleCancel() {
            if (confirm("Are you sure you want to exit the setup wizard? This will skip the initial setup (you can still configure settings manually in Admin Settings) and prevent this wizard from showing again on every page reload.")) {
                try {
                    localSettings.setupWizardCompleted = true;
                    const response = await request("/api/settings", {
                        method: "PUT",
                        json: localSettings
                    });
                    safeSetItem("cache:/api/settings", JSON.stringify(response.settings || response));
                    closeWizard();
                    showToast("Setup skipped. You can configure settings anytime in Admin Settings.", "info");
                    if (forceOpen) {
                        setTimeout(() => window.location.reload(), 1000);
                    }
                } catch (e) {
                    console.error("Failed to mark setup wizard as completed:", e);
                    closeWizard();
                }
            }
        }

        const isFtc = me && me.program === "FTC";
        const seasonTitle = isFtc ? "FTC Event & Season Details" : "FRC Event & Season Details";
        const seasonDesc = isFtc ? "Specify your current season year and the event code to sync teams and matches from the FTC Scout APIs." : "Specify your current season year and the event code to sync teams and matches from the FRC APIs.";
        const yearDesc = isFtc ? "The 4-digit FTC season year." : "The 4-digit FRC season year.";
        const sourceTbaLabel = isFtc ? "FTC Scout" : "The Blue Alliance";
        const sourceFirstLabel = isFtc ? "FIRST FTC API" : "FIRST API";
        const credentialsDesc = isFtc ? "Enter your API credentials. FTC Scout does not require a key, but you can optionally configure official FIRST FTC API credentials below." : "Enter your API keys to enable automatic schedule syncing. Leave blank if syncing offline via QR codes.";
        const tbaKeyStyle = isFtc ? "display: none;" : "margin-bottom: 16px;";
        const firstUsernameLabel = isFtc ? "FIRST FTC API Username" : "FIRST API Username";
        const firstKeyLabel = isFtc ? "FIRST FTC API Key" : "FIRST API Key";

        container.innerHTML = `
            <div class="modal-header">
                <h2 class="modal-title">Admin Setup Wizard</h2>
                <button class="modal-close btn-wizard-cancel" aria-label="Close">&times;</button>
            </div>
            
            <div class="wizard-progress">
                <div class="wizard-progress-bar"></div>
                <div class="wizard-step-dot active">1</div>
                <div class="wizard-step-dot">2</div>
                <div class="wizard-step-dot">3</div>
                <div class="wizard-step-dot">4</div>
            </div>
            
            <div class="wizard-body" style="margin-bottom: 24px; min-height: 250px;">
                <div class="wizard-step-content active" data-step="1">
                    <div class="wizard-welcome-card">
                        <span class="wizard-welcome-icon">🚀</span>
                        <h3 class="wizard-welcome-title">Welcome to ObsidianScout!</h3>
                        <p class="wizard-welcome-desc">Let's configure your team's scouting workspace in a few quick steps. We'll set up your event configurations, API keys, and confirm your scouting forms.</p>
                        
                        <div class="wizard-section-divider"></div>
                        
                        <p class="notice" style="margin-bottom: 16px;">
                            We highly recommend reviewing our Getting Started Guide first to learn how the database, syncing, and roles operate.
                        </p>
                        <a href="/docs" target="_blank" class="btn" style="display: inline-flex; align-items: center; justify-content: center; gap: 8px; text-decoration: none; width: 100%; max-width: 320px; margin: 0 auto 12px;">
                            📖 Read Getting Started Tutorial
                        </a>
                    </div>
                </div>
                
                <div class="wizard-step-content" data-step="2">
                    <h3 style="margin-top: 0; margin-bottom: 8px;">${seasonTitle}</h3>
                    <p class="notice" style="margin-bottom: 16px;">${seasonDesc}</p>
                    
                    <div class="form-grid" style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px;">
                        <div class="field">
                            <label for="wizard-year">Season Year</label>
                            <input id="wizard-year" type="number" value="${localSettings.year || new Date().getFullYear()}" />
                            <span class="wizard-field-desc">${yearDesc}</span>
                        </div>
                        <div class="field">
                            <label for="wizard-event-code">Event Code</label>
                            <input id="wizard-event-code" type="text" placeholder="e.g. okok" value="${localSettings.eventCode || ''}" />
                            <span class="wizard-field-desc">Short event code (e.g., 'okok' for Oklahoma Regional).</span>
                        </div>
                        <div class="field">
                            <label for="wizard-timezone">Timezone</label>
                            <input id="wizard-timezone" type="text" placeholder="America/New_York" value="${localSettings.timezone || 'America/New_York'}" />
                            <span class="wizard-field-desc">Database logs and schedule offsets use this timezone.</span>
                        </div>
                        <div class="field">
                            <label for="wizard-source">Preferred API Source</label>
                            <select id="wizard-source">
                                <option value="tba" ${localSettings.preferredSource === 'tba' ? 'selected' : ''}>${sourceTbaLabel}</option>
                                <option value="first" ${localSettings.preferredSource === 'first' ? 'selected' : ''}>${sourceFirstLabel}</option>
                            </select>
                            <span class="wizard-field-desc">The primary API to fetch event schedule.</span>
                        </div>
                    </div>
                </div>
                
                <div class="wizard-step-content" data-step="3">
                    <h3 style="margin-top: 0; margin-bottom: 8px;">API Credentials</h3>
                    <p class="notice" style="margin-bottom: 16px;">${credentialsDesc}</p>
                    
                    <div class="field" style="${tbaKeyStyle}">
                        <label for="wizard-tba-key">The Blue Alliance Read Key</label>
                        <input id="wizard-tba-key" type="password" placeholder="TBA Read API Key" value="${localSettings.apiKeys.tbaKey || ''}" />
                    </div>
                    
                    <div class="wizard-section-divider"></div>
                    
                    <div class="split" style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px;">
                        <div class="field">
                            <label for="wizard-first-user">${firstUsernameLabel}</label>
                            <input id="wizard-first-user" type="text" placeholder="FIRST Username" value="${localSettings.apiKeys.firstUsername || ''}" />
                        </div>
                        <div class="field">
                            <label for="wizard-first-key">${firstKeyLabel}</label>
                            <input id="wizard-first-key" type="password" placeholder="FIRST API Secret Key" value="${localSettings.apiKeys.firstKey || ''}" />
                        </div>
                    </div>
                </div>
                
                <div class="wizard-step-content" data-step="4">
                    <h3 style="margin-top: 0; margin-bottom: 8px;">Verify Scouting Forms</h3>
                    <p class="notice" style="margin-bottom: 16px;">ObsidianScout comes pre-loaded with default scouting forms. You can optionally upload a custom Game Form configuration JSON file below.</p>
                    
                    <div class="field">
                        <label class="btn ghost btn-file" style="display: inline-flex; width: 100%; justify-content: center; padding: 12px; margin-bottom: 12px; cursor: pointer;">
                            📁 Import Custom Game Form JSON
                            <input id="wizard-config-import" class="input-hidden" type="file" accept="application/json" />
                        </label>
                        <div id="wizard-import-status" class="notice" style="text-align: center; color: var(--accent-2); font-weight: 600;"></div>
                    </div>
                    
                    <div class="wizard-card-preview">
                        <h4 style="margin: 0 0 8px 0; font-size: 14px;">Forms status:</h4>
                        <ul style="margin: 0; padding-left: 20px; font-size: 13px; color: var(--muted); display: grid; gap: 4px;">
                            <li>✓ Default Match/Game Scouting Form loaded</li>
                            <li>✓ Default Pit Scouting Form loaded</li>
                            <li>✓ Default Qualitative Scouting Form loaded</li>
                        </ul>
                    </div>
                </div>
            </div>
            
            <div class="modal-footer">
                <button type="button" class="btn ghost btn-wizard-cancel" style="margin-right: auto;">Exit</button>
                <button type="button" class="btn ghost btn-wizard-back" style="display: none;">Back</button>
                <button type="button" class="btn secondary btn-wizard-next">Next</button>
            </div>
        `;

        const btnCancelList = container.querySelectorAll(".btn-wizard-cancel");
        btnCancelList.forEach(btn => btn.addEventListener("click", handleCancel));

        const btnBack = container.querySelector(".btn-wizard-back");
        btnBack.addEventListener("click", () => {
            if (currentStep > 1) {
                saveInputsToState();
                currentStep--;
                updateStepUI();
            }
        });

        const btnNext = container.querySelector(".btn-wizard-next");
        btnNext.addEventListener("click", async () => {
            if (!validateStep()) return;
            saveInputsToState();

            if (currentStep < totalSteps) {
                currentStep++;
                updateStepUI();
            } else {
                btnNext.disabled = true;
                btnNext.textContent = "Saving...";
                
                try {
                    localSettings.setupWizardCompleted = true;
                    
                    const code = localSettings.eventCode.trim();
                    if (code) {
                        localSettings.eventKey = `${localSettings.year}${code}`.toLowerCase();
                    }
                    
                    const response = await request("/api/settings", {
                        method: "PUT",
                        json: localSettings
                    });
                    
                    if (uploadedGameConfigJson) {
                        await request("/api/config", {
                            method: "PUT",
                            json: {
                                configJson: JSON.stringify(uploadedGameConfigJson)
                            }
                        });
                    }

                    safeSetItem("cache:/api/settings", JSON.stringify(response.settings || response));
                    
                    showToast("Configuration saved successfully!", "success");
                    closeWizard();

                    const eventKey = localSettings.eventKey;
                    if (eventKey) {
                        showToast(`Initiating data sync for ${eventKey}...`, "info");
                        request(`/api/prescout/sync-event?eventKey=${eventKey}`, { method: "POST" })
                            .then((counts) => {
                                showToast(`Sync finished! Cached ${counts.syncedTeams || counts.teams || 0} teams and ${counts.syncedMatches || counts.matches || 0} matches.`, "success");
                                window.dispatchEvent(new CustomEvent("obsidianscout:offline-entries-synced"));
                                if (window.location.pathname.includes("dashboard") || window.location.pathname.includes("admin-settings")) {
                                    setTimeout(() => window.location.reload(), 1500);
                                }
                            })
                            .catch(err => {
                                console.error("Sync failed:", err);
                                showToast("Initial sync failed: API keys might be invalid or rate limited.", "error");
                            });
                    }
                    
                    if (forceOpen) {
                        setTimeout(() => window.location.reload(), 1000);
                    }

                } catch (error) {
                    console.error("Setup Wizard save failed:", error);
                    showToast("Failed to save settings: " + error.message, "error");
                    btnNext.disabled = false;
                    btnNext.textContent = "Finish & Sync";
                }
            }
        });

        const configImportInput = container.querySelector("#wizard-config-import");
        if (configImportInput) {
            configImportInput.addEventListener("change", () => {
                const file = configImportInput.files[0];
                if (!file) return;
                const reader = new FileReader();
                reader.onload = () => {
                    const text = reader.result;
                    try {
                        const parsed = JSON.parse(text);
                        if (!parsed.fields) parsed.fields = [];
                        uploadedGameConfigJson = parsed;
                        
                        const statusEl = container.querySelector("#wizard-import-status");
                        if (statusEl) {
                            statusEl.textContent = `✓ Custom Game Form "${parsed.title || 'Imported'}" verified and ready to save.`;
                        }
                        showToast("Scouting config file loaded successfully!", "success");
                    } catch (e) {
                        showToast("Invalid config JSON file format.", "error");
                    }
                };
                reader.readAsText(file);
            });
        }

        updateStepUI();
    }

    async function requireAuth() {
        const loggedIn = await checkLoginStatus();
        if (!loggedIn) {
            window.location.href = "/";
            return null;
        }
        const me = await getMe();
        if (!me) {
            return null;
        }

        // Pre-fetch settings to ensure local cache is populated for role-based navigation checks
        let settings = null;
        try {
            const response = await request("/api/settings");
            settings = response.settings || response;
        } catch (e) {
            console.warn("Failed to pre-fetch settings for role adjustments:", e);
            try {
                const cachedText = safeGetItem("cache:/api/settings");
                if (cachedText) {
                    const parsed = JSON.parse(cachedText);
                    settings = parsed.settings || parsed;
                }
            } catch (err) {}
        }

        // Verify page-level access permissions
        const currentPage = typeof document !== 'undefined' && document.body && document.body.getAttribute("data-page");
        if (currentPage && settings && (me.role === "SCOUT" || me.role === "ANALYTICS" || me.role === "ADMIN")) {
            const allowedPages = me.role === "SCOUT" ? settings.scoutPages : (me.role === "ANALYTICS" ? settings.analyticsPages : settings.adminPages);
            if (allowedPages && Array.isArray(allowedPages)) {
                const bypassPages = ["settings", "login", "index", "dashboard", "theme-editor", "team", "cache-manager", "prescout", "prescout-scout", "prescout-pit", "prescout-qual", "reset-password"];
                if (!bypassPages.includes(currentPage) && !allowedPages.includes(currentPage)) {
                    showToast("You do not have access to this page", "error");
                    const fallback = allowedPages.includes("dashboard") ? "/dashboard" : "/config";
                    setTimeout(() => {
                        window.location.href = fallback;
                    }, 500);
                    return null;
                }
            }
        }

        try {
            initTour(me);
        } catch (e) {
            console.warn("Failed to initialize ObsidianScout Tour:", e);
        }

        // Setup Wizard Auto Trigger
        if (settings && isAdmin(me.role) && !settings.setupWizardCompleted) {
            const bypassPages = ["login", "index", "reset-password", "migration"];
            if (currentPage && !bypassPages.includes(currentPage)) {
                setTimeout(() => {
                    if (!document.getElementById("setup-wizard-backdrop")) {
                        showSetupWizardModal(me, settings);
                    }
                }, 400);
            }
        }

        return me;
    }

    /**
     * Checks if the user's role is at least the required level.
     * E.g. hasRole("ADMIN", "ADMIN") = true
     *      hasRole("SUPERADMIN", "ADMIN") = true
     *      hasRole("SCOUT", "ADMIN") = false
     */
    function hasRole(userRole, requiredRole) {
        const userIdx = ROLE_HIERARCHY.indexOf(userRole);
        const reqIdx = ROLE_HIERARCHY.indexOf(requiredRole);
        if (userIdx === -1 || reqIdx === -1) return false;
        return userIdx <= reqIdx;
    }

    function isAdmin(role) {
        return hasRole(role, "ADMIN");
    }

    function isSuperAdmin(role) {
        return role === "SUPERADMIN";
    }

    function canAccessAnalytics(role) {
        return hasRole(role, "ANALYTICS");
    }

    function triggerHaptic(type = "light") {
        if (typeof navigator !== "undefined" && navigator.vibrate) {
            const hapticSetting = safeGetItem("obsidianscout:haptic_feedback") || "enabled";
            if (hapticSetting !== "enabled") {
                return;
            }
            try {
                switch (type) {
                    case "light":
                    case "tap":
                    case "click":
                        navigator.vibrate(12);
                        break;
                    case "medium":
                        navigator.vibrate(30);
                        break;
                    case "success":
                        navigator.vibrate([20, 50, 20]);
                        break;
                    case "warning":
                    case "error":
                        navigator.vibrate([60, 50, 60]);
                        break;
                }
            } catch (e) {
                console.warn("Haptic feedback failed:", e);
            }
        }
    }

    function showToast(message, tone = "info") {
        let root = document.getElementById(toastRootId);
        if (!root) {
            root = document.createElement("div");
            root.id = toastRootId;
            document.body.appendChild(root);
        }
        const toast = document.createElement("div");
        toast.className = `toast ${tone}`;
        toast.textContent = message;
        root.appendChild(toast);
        setTimeout(() => {
            toast.remove();
        }, 2800);

        if (tone === "success") {
            triggerHaptic("success");
        } else if (tone === "error") {
            triggerHaptic("error");
        } else if (tone === "warning") {
            triggerHaptic("warning");
        }
    }

    function setUserBadge(user) {
        const badge = document.getElementById("nav-user");
        if (!badge || !user) {
            return;
        }
        const roleLabel = user.role === "SUPERADMIN" ? "Site Admin" : user.role.charAt(0) + user.role.slice(1).toLowerCase();

        // Update brand to show program type
        const brand = document.querySelector(".sidebar-brand");
        if (brand && !brand.textContent.endsWith(user.program)) {
            brand.textContent = `ObsidianScout ${user.program}`;
        }

        // Build avatar element
        const initials = (user.username || "?").slice(0, 2).toUpperCase();
        // Pick a deterministic hue from the username
        let hue = 0;
        for (let i = 0; i < (user.username || "").length; i++) {
            hue = (hue + (user.username || "").charCodeAt(i) * 37) % 360;
        }

        let avatarHtml;
        if (user.profilePicture) {
            avatarHtml = `<img class="nav-avatar" src="${user.profilePicture}" alt="${initials}" title="${user.username}">`;
        } else {
            avatarHtml = `<div class="nav-avatar nav-avatar-initials" style="--avatar-hue:${hue}deg" title="${user.username}">${initials}</div>`;
        }

        badge.innerHTML = `
            <a class="nav-avatar-link" href="/config" aria-label="Edit profile picture">${avatarHtml}</a>
            <div class="nav-user-text">
                <span class="nav-user-name" title="${user.username}">${user.username}</span>
                <span class="nav-user-meta">${user.program} Team ${user.teamNumber} • ${roleLabel}</span>
            </div>
        `;

        const apiAttribution = document.getElementById("api-attribution");
        if (apiAttribution) {
            if (user.program === "FTC") {
                apiAttribution.innerHTML = `Match data provided by:<br><a href="https://ftc-events.firstinspires.org/services/API" target="_blank" rel="noopener noreferrer">FIRST FTC API</a> and <a href="https://ftcscout.org/api" target="_blank" rel="noopener noreferrer">FTC Scout API</a>`;
            } else {
                apiAttribution.innerHTML = `Match data provided by:<br><a href="https://frc-events.firstinspires.org/services/api" target="_blank" rel="noopener noreferrer">FIRST FRC API</a> and <a href="https://www.thebluealliance.com/apidocs" target="_blank" rel="noopener noreferrer">The Blue Alliance API</a>.<br>EPA provided by <a href="https://www.statbotics.io/docs/rest" target="_blank" rel="noopener noreferrer">Statbotics</a>.`;
            }
        }
    }

    /**
     * Updates the sidebar avatar after a profile picture change without a full page reload.
     * @param {string|null} profilePicture - New picture data-URL, or null to revert to initials.
     */
    function refreshNavAvatar(profilePicture) {
        const badge = document.getElementById("nav-user");
        if (!badge) return;
        const link = badge.querySelector(".nav-avatar-link");
        if (!link) return;
        const existing = link.querySelector(".nav-avatar, .nav-avatar-initials");
        if (!existing) return;

        if (profilePicture) {
            const img = document.createElement("img");
            img.className = "nav-avatar";
            img.src = profilePicture;
            img.alt = "avatar";
            existing.replaceWith(img);
        } else {
            // Revert to initials bubble — read initials from current text
            const nameEl = badge.querySelector(".nav-user-name");
            const textEl = badge.querySelector(".nav-user-text");
            let username = "?";
            if (nameEl) {
                username = nameEl.textContent.trim();
            } else if (textEl) {
                username = textEl.textContent.split("|")[0].trim();
            }
            const initials = (username || "?").slice(0, 2).toUpperCase();
            let hue = 0;
            for (let i = 0; i < username.length; i++) {
                hue = (hue + username.charCodeAt(i) * 37) % 360;
            }
            const div = document.createElement("div");
            div.className = "nav-avatar nav-avatar-initials";
            div.style.setProperty("--avatar-hue", hue + "deg");
            div.title = username;
            div.textContent = initials;
            existing.replaceWith(div);
        }
    }

    function setActiveNav() {
        const page = document.body.dataset.page;
        if (!page) {
            return;
        }
        document.querySelectorAll(".nav-link, .sidebar-link").forEach((link) => {
            if (link.dataset.page === page) {
                link.classList.add("active");
            }
        });
    }

    /**
     * Adjusts sidebar navigation visibility based on user role.
     */
    function adjustNavForRole(user) {
        if (!user) return;
        const role = user.role;

        // Hide Users link for SCOUT and ANALYTICS
        if (!isAdmin(role)) {
            document.querySelectorAll('.sidebar-link[data-page="users"]').forEach((link) => {
                link.style.display = "none";
            });
            document.querySelectorAll('.sidebar-link[data-page="banners"]').forEach((link) => {
                link.style.display = "none";
            });
            document.querySelectorAll('.sidebar-link[data-page="admin-settings"]').forEach((link) => {
                link.style.display = "none";
            });
            document.querySelectorAll('.sidebar-link[data-page="default-configs"]').forEach((link) => {
                link.style.display = "none";
            });
        }

        // Show Migration link only for SUPERADMIN
        document.querySelectorAll('.sidebar-link[data-page="migration"]').forEach((link) => {
            link.style.display = isSuperAdmin(role) ? "block" : "none";
        });

        // Hide links based on dynamic role permissions list
        if (role === "SCOUT" || role === "ANALYTICS" || role === "ADMIN") {
            try {
                const settingsText = safeGetItem("cache:/api/settings");
                if (settingsText) {
                    const parsed = JSON.parse(settingsText);
                    const settings = parsed.settings || parsed;
                    const allowedPages = role === "SCOUT" ? settings.scoutPages : (role === "ANALYTICS" ? settings.analyticsPages : settings.adminPages);
                    if (allowedPages && Array.isArray(allowedPages)) {
                        document.querySelectorAll('.sidebar-link[data-page]').forEach((link) => {
                            const page = link.dataset.page;
                            const bypassPages = ["settings", "migration", "login", "index", "theme-editor", "team", "cache-manager", "prescout", "prescout-scout", "prescout-pit", "prescout-qual", "reset-password"];
                            if (!bypassPages.includes(page) && !allowedPages.includes(page)) {
                                link.style.display = "none";
                            }
                        });
                    }
                }
            } catch (err) {
                console.error("Failed to parse settings for dynamic nav adjust:", err);
            }
        }
    }

    function wireLogout() {
        const button = document.querySelector("[data-action='logout']");
        if (!button) {
            return;
        }
        button.addEventListener("click", async () => {
            try {
                await request("/api/auth/logout", { method: "POST" });
                safeRemoveItem("cache:/api/auth/me");
                window.location.href = "/";
            } catch (error) {
                showToast(error.message || "Failed to sign out", "error");
            }
        });
    }

    function applyCustomTheme(themeOrSettings) {
        if (!themeOrSettings) return;
        
        let theme = themeOrSettings;
        if (themeOrSettings.theme !== undefined) {
            theme = themeOrSettings.theme || {};
            safeSetItem("obsidian-custom-theme-config", JSON.stringify(theme));
        }
        
        const isDark = document.body.classList.contains("theme-dark");
        console.log("[Theme] Applying custom theme. Mode:", isDark ? "dark" : "light", "Custom theme object:", theme);
        const target = document.body;
        if (!target) return;
        
        if (theme.btnRadius) target.style.setProperty('--btn-radius', theme.btnRadius);
        else target.style.removeProperty('--btn-radius');
        
        if (isDark) {
            if (theme.darkAccent) target.style.setProperty('--accent', theme.darkAccent);
            else target.style.removeProperty('--accent');
            
            if (theme.darkAccent2) target.style.setProperty('--accent-2', theme.darkAccent2);
            else target.style.removeProperty('--accent-2');
            
            if (theme.darkAccent3) target.style.setProperty('--accent-3', theme.darkAccent3);
            else target.style.removeProperty('--accent-3');
            
            if (theme.darkInk) {
                target.style.setProperty('--ink', theme.darkInk);
                target.style.color = theme.darkInk;
            } else {
                target.style.removeProperty('--ink');
                target.style.color = '';
            }
            
            if (theme.darkMuted) target.style.setProperty('--muted', theme.darkMuted);
            else target.style.removeProperty('--muted');
            
            if (theme.darkBg) target.style.setProperty('--bg', theme.darkBg);
            else target.style.removeProperty('--bg');
        } else {
            if (theme.lightAccent) target.style.setProperty('--accent', theme.lightAccent);
            else target.style.removeProperty('--accent');
            
            if (theme.lightAccent2) target.style.setProperty('--accent-2', theme.lightAccent2);
            else target.style.removeProperty('--accent-2');
            
            if (theme.lightAccent3) target.style.setProperty('--accent-3', theme.lightAccent3);
            else target.style.removeProperty('--accent-3');
            
            if (theme.lightInk) {
                target.style.setProperty('--ink', theme.lightInk);
                target.style.color = theme.lightInk;
            } else {
                target.style.removeProperty('--ink');
                target.style.color = '';
            }
            
            if (theme.lightMuted) target.style.setProperty('--muted', theme.lightMuted);
            else target.style.removeProperty('--muted');
            
            if (theme.lightBg) target.style.setProperty('--bg', theme.lightBg);
            else target.style.removeProperty('--bg');
        }
    }

    function initTheme() {
        const saved = safeGetItem("obsidian-theme") || "light";
        const isDark = saved === "dark";
        document.body.classList.toggle("theme-dark", isDark);
        console.log("[Theme] Initialized theme:", saved, "| body classList has theme-dark:", document.body.classList.contains("theme-dark"));
        try {
            const cachedTheme = safeGetItem("obsidian-custom-theme-config");
            if (cachedTheme) {
                applyCustomTheme(JSON.parse(cachedTheme));
            } else {
                const cachedText = safeGetItem("cache:/api/settings");
                if (cachedText) {
                    const parsed = JSON.parse(cachedText);
                    applyCustomTheme(parsed.settings || parsed);
                }
            }
        } catch (e) {
            console.error("[Theme] Error in initTheme custom theme apply:", e);
        }
    }

    function toggleThemeMode() {
        console.log("[Theme] Toggle theme button clicked! Current theme-dark before toggle:", document.body.classList.contains("theme-dark"));
        const isDark = document.body.classList.toggle("theme-dark");
        const newThemeStr = isDark ? "dark" : "light";
        safeSetItem("obsidian-theme", newThemeStr);
        console.log("[Theme] Toggled theme to:", newThemeStr, "| body classList has theme-dark:", document.body.classList.contains("theme-dark"));
        try {
            const cachedTheme = safeGetItem("obsidian-custom-theme-config");
            if (cachedTheme) {
                applyCustomTheme(JSON.parse(cachedTheme));
            } else {
                const cachedText = safeGetItem("cache:/api/settings");
                if (cachedText) {
                    const parsed = JSON.parse(cachedText);
                    applyCustomTheme(parsed.settings || parsed);
                }
            }
        } catch (err) {
            console.error("[Theme] Error in click toggle custom theme apply:", err);
        }
    }

    function bindThemeToggleButtons(root = document) {
        root.querySelectorAll("[data-action='toggle-theme']").forEach((button) => {
            if (button.dataset.themeToggleBound === "true") return;
            button.dataset.themeToggleBound = "true";
            button.addEventListener("click", (e) => {
                e.preventDefault();
                e.stopPropagation();
                toggleThemeMode();
            });
        });
    }

    function wireThemeToggle(root = document) {
        bindThemeToggleButtons(root);

        if (window._themeToggleDelegated) return;
        window._themeToggleDelegated = true;
        console.log("[Theme] Registered global click handler for [data-action='toggle-theme']");

        document.addEventListener("click", (e) => {
            const toggle = e.target.closest("[data-action='toggle-theme']");
            if (!toggle || toggle.dataset.themeToggleBound === "true") return;

            e.preventDefault();
            toggleThemeMode();
        });
    }

    function resolveEventKey(settings) {
        if (!settings) {
            return "";
        }
        const code = (settings.eventCode || "").trim();
        if (code) {
            return `${settings.year}${code}`.toLowerCase();
        }
        return (settings.eventKey || "").trim().toLowerCase();
    }

    function getDeviceTimezone() {
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
    function formatTimestamp(epochSeconds, timezone) {
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
    function localToUtcEpoch(datetimeLocalValue) {
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
    function formatTimestampWithVenueTooltip(epochSeconds, eventTimezone) {
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

    function injectConnectionWidget(sidebar) {
        const brand = sidebar.querySelector(".sidebar-brand");
        if (!brand) return;

        const widget = document.createElement("div");
        widget.id = "connection-status-widget";
        widget.className = "connection-widget online";
        widget.innerHTML = `
            <span class="status-dot"></span>
            <span class="status-text">Online</span>
            <button id="btn-sync-offline" class="btn-sync-offline hidden">Sync (0)</button>
        `;

        const anchor = sidebar.querySelector(".sidebar-header") || brand;
        anchor.after(widget);

        const syncBtn = widget.querySelector("#btn-sync-offline");
        syncBtn.addEventListener("click", (e) => {
            e.stopPropagation();
            syncOfflineEntries();
        });

        updateConnectionStatus();
    }

    function wireSidebarToggle() {
        const sidebar = document.querySelector(".sidebar");
        if (!sidebar) {
            return;
        }

        if (window.innerWidth < 900) {
            const existingMobileToggle = sidebar.querySelector(".sidebar-toggle");
            if (existingMobileToggle) {
                existingMobileToggle.remove();
            }
            return;
        }

        const brand = sidebar.querySelector(".sidebar-brand");
        if (!brand) {
            return;
        }

        let header = sidebar.querySelector(".sidebar-header");
        if (!header) {
            header = document.createElement("div");
            header.className = "sidebar-header";
            brand.parentNode.insertBefore(header, brand);
            header.appendChild(brand);
        }

        if (!brand.dataset.short) {
            const brandText = (brand.textContent || "").trim();
            const compact = brandText.replace(/[^a-z0-9]/gi, "");
            brand.dataset.short = (compact.slice(0, 2) || brandText.slice(0, 2) || "OS").toUpperCase();
            brand.title = brandText;
        }

        sidebar.querySelectorAll(".sidebar-link").forEach((link) => {
            const label = (link.textContent || "").trim();
            if (!link.dataset.short) {
                const compact = label.replace(/[^a-z0-9]/gi, "");
                link.dataset.short = (compact.slice(0, 2) || label.slice(0, 2) || "?").toUpperCase();
            }
            if (!link.title) {
                link.title = label;
            }
        });

        let toggle = sidebar.querySelector(".sidebar-toggle");
        if (!toggle) {
            toggle = document.createElement("button");
            toggle.type = "button";
            toggle.className = "sidebar-toggle";
            header.appendChild(toggle);
        }

        const applyCollapsedState = (collapsed, persist = true) => {
            sidebar.classList.toggle("collapsed", collapsed);
            toggle.textContent = collapsed ? ">>" : "<<";
            toggle.setAttribute("aria-expanded", (!collapsed).toString());
            if (persist) {
                safeSetItem(sidebarCollapseKey, collapsed ? "1" : "0");
            }
        };

        const stored = safeGetItem(sidebarCollapseKey);
        // If user has previously chosen a state, respect it. Otherwise default to
        // collapsed on narrow viewports for better mobile UX.
        const initial = (stored !== null) ? (stored === "1") : (window.innerWidth < 900);
        applyCollapsedState(initial);

        toggle.addEventListener("click", () => {
            applyCollapsedState(!sidebar.classList.contains("collapsed"), true);
        });
    }

    function injectMobileTopBar() {
        if (window.innerWidth >= 900) {
            return;
        }

        const sidebar = document.querySelector(".sidebar");
        const appShell = document.querySelector(".app-shell");
        if (!sidebar || !appShell) {
            return;
        }

        if (document.querySelector(".mobile-topbar")) {
            return;
        }

        const topBar = document.createElement("header");
        topBar.className = "mobile-topbar";
        topBar.innerHTML = `
            <button type="button" class="mobile-menu-button" aria-label="Open menu" aria-expanded="false">
                <span class="hamburger-icon">☰</span>
            </button>
            <div class="mobile-topbar-brand">ObsidianScout</div>
        `;

        appShell.parentNode.insertBefore(topBar, appShell);

        let overlay = document.querySelector(".sidebar-overlay");
        if (!overlay) {
            overlay = document.createElement("div");
            overlay.className = "sidebar-overlay";
            document.body.appendChild(overlay);
        }

        const button = topBar.querySelector(".mobile-menu-button");

        const setMobileOpen = (open) => {
            sidebar.classList.toggle("mobile-open", open);
            overlay.classList.toggle("visible", open);
            button.setAttribute("aria-expanded", open.toString());
            button.setAttribute("aria-label", open ? "Close menu" : "Open menu");
        };

        button.addEventListener("click", () => {
            setMobileOpen(!sidebar.classList.contains("mobile-open"));
        });

        overlay.addEventListener("click", () => setMobileOpen(false));

        sidebar.querySelectorAll(".sidebar-link").forEach((link) => {
            link.addEventListener("click", () => setMobileOpen(false));
        });

        window.addEventListener("resize", () => {
            if (window.innerWidth >= 900) {
                setMobileOpen(false);
            }
        });
    }

    function updateConnectionStatus() {
        const widget = document.getElementById("connection-status-widget");
        if (!widget) return;

        const dot = widget.querySelector(".status-dot");
        const text = widget.querySelector(".status-text");
        const syncBtn = widget.querySelector("#btn-sync-offline");

        let count = 0;
        for (const type in CACHE_CONFIGS) {
            const config = CACHE_CONFIGS[type];
            const pending = JSON.parse(safeGetItem(config.key) || "[]");
            count += pending.length;
        }

        const isOnline = navigator.onLine;

        if (isOnline) {
            widget.className = "connection-widget online";
            text.textContent = (typeof t === 'function') ? t('connection.online', 'Online') : 'Online';
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `${(typeof t === 'function' ? t('connection.sync','Sync') : 'Sync')} (${count})`;
                syncBtn.disabled = false;
            } else {
                syncBtn.classList.add("hidden");
            }
        } else {
            widget.className = "connection-widget offline";
            text.textContent = (typeof t === 'function') ? t('connection.offline','Offline') : 'Offline';
            if (count > 0) {
                syncBtn.classList.remove("hidden");
                syncBtn.textContent = `${(typeof t === 'function' ? t('connection.pending','Pending') : 'Pending')} (${count})`;
                syncBtn.disabled = true;
            } else {
                syncBtn.classList.add("hidden");
            }
        }
    }

    async function syncOfflineEntries() {
        if (!navigator.onLine) return;

        let totalPending = 0;
        for (const type in CACHE_CONFIGS) {
            const config = CACHE_CONFIGS[type];
            const pending = JSON.parse(safeGetItem(config.key) || "[]");
            totalPending += pending.length;
        }
        if (totalPending === 0) return;

        const syncBtn = document.querySelector("#btn-sync-offline");
        if (syncBtn) {
            syncBtn.disabled = true;
            syncBtn.textContent = "Syncing...";
        }

        let successCount = 0;

        for (const type in CACHE_CONFIGS) {
            const config = CACHE_CONFIGS[type];
            const pending = JSON.parse(safeGetItem(config.key) || "[]");
            if (!pending.length) continue;

            const remaining = [];
            for (const item of pending) {
                try {
                    await request(config.endpoint, {
                        method: "POST",
                        json: item
                    });
                    successCount++;
                } catch (error) {
                    console.error(`[Offline Sync] Failed to sync ${config.label}:`, error);
                    remaining.push(item);
                }
            }
            safeSetItem(config.key, JSON.stringify(remaining));
        }

        if (successCount > 0) {
            showToast(`Successfully synced ${successCount} offline entries!`, "success");
            window.dispatchEvent(new CustomEvent("obsidianscout:offline-entries-synced"));
            window.dispatchEvent(new CustomEvent("obsidianscout:qualitative-entries-changed"));
        }

        updateConnectionStatus();
    }

    function downloadJson(payload, filename) {
        try {
            const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
            showToast("JSON exported successfully", "success");
        } catch (e) {
            console.error("JSON export failed:", e);
            showToast("Failed to export JSON", "error");
        }
    }

    async function compressData(dataStr) {
        if (typeof CompressionStream === 'undefined') {
            console.warn("CompressionStream is not supported in this browser. Falling back to raw JSON.");
            return dataStr;
        }
        try {
            const stream = new Blob([dataStr]).stream();
            const compressedStream = stream.pipeThrough(new CompressionStream("deflate"));
            const buffer = await new Response(compressedStream).arrayBuffer();
            const bytes = new Uint8Array(buffer);
            
            // Safe base64 encoding from Uint8Array
            let binary = "";
            const len = bytes.byteLength;
            const chunk = 8192;
            for (let i = 0; i < len; i += chunk) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
            }
            return "OSC:" + btoa(binary);
        } catch (e) {
            console.error("Compression failed, using raw data:", e);
            return dataStr;
        }
    }

    async function decompressData(compressedStr) {
        if (!compressedStr || !compressedStr.startsWith("OSC:")) {
            return compressedStr; // Not compressed, return raw
        }
        const base64 = compressedStr.substring(4);
        if (typeof DecompressionStream === 'undefined') {
            throw new Error("DecompressionStream is not supported by this browser.");
        }
        try {
            const binary = atob(base64);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            const stream = new Blob([bytes]).stream();
            const decompressedStream = stream.pipeThrough(new DecompressionStream("deflate"));
            return await new Response(decompressedStream).text();
        } catch (e) {
            console.error("Decompression failed:", e);
            throw e;
        }
    }

    async function compressAndChunkData(dataStr, chunkSize = 450) {
        const compressed = await compressData(dataStr);
        if (compressed.length <= chunkSize) {
            return [compressed];
        }
        const base64Payload = compressed.startsWith("OSC:") ? compressed.substring(4) : compressed;
        const chunks = [];
        const total = Math.ceil(base64Payload.length / chunkSize);
        for (let i = 0; i < total; i++) {
            const start = i * chunkSize;
            const end = Math.min(start + chunkSize, base64Payload.length);
            chunks.push(`OSC:PART:${i + 1}:${total}:${base64Payload.substring(start, end)}`);
        }
        return chunks;
    }

    async function showQrModal(payload, typeLabel, teamNum, matchKey) {
        if (typeof QRCode === 'undefined') {
            showToast("QR Library not loaded", "error");
            return;
        }

        let backdrop = document.getElementById("qr-modal-backdrop");
        if (!backdrop) {
            backdrop = document.createElement("div");
            backdrop.id = "qr-modal-backdrop";
            backdrop.className = "modal-backdrop";
            document.body.appendChild(backdrop);
        }

        const matchHtml = matchKey ? `<p><strong data-i18n="qr.match">Match:</strong> <span>${matchKey}</span></p>` : '';
        const qrPayload = {
            type: payload.type || typeLabel.toLowerCase().replace(/\s+/g, '-'),
            data: payload
        };
        const qrString = JSON.stringify(qrPayload);
        const qrChunks = await compressAndChunkData(qrString);

        const isMulti = qrChunks.length > 1;
        
        backdrop.innerHTML = `
            <div class="modal-container" style="max-width: ${isMulti ? '680px' : '480px'};">
                <div class="modal-header">
                    <h3 class="modal-title" data-i18n="qr.title">Scouting Entry QR Code ${isMulti ? `(Grid of ${qrChunks.length} Parts)` : ''}</h3>
                    <button class="modal-close" id="qr-modal-close-btn">&times;</button>
                </div>
                <div class="modal-body qr-modal-body">
                    <div class="qr-code-wrapper" id="qr-code-canvas-container" style="min-height: 320px; display: flex; flex-wrap: wrap; gap: 16px; align-items: center; justify-content: center; padding: 12px;"></div>
                    <div class="qr-details" style="margin-top: 16px;">
                        <p><strong data-i18n="qr.type">Type:</strong> <span>${typeLabel}</span></p>
                        <p><strong data-i18n="qr.team">Team:</strong> <span>${teamNum}</span></p>
                        ${matchHtml}
                    </div>
                </div>
                <div class="modal-footer">
                    <button class="btn ghost" id="qr-modal-close-footer-btn" data-i18n="qr.close">Close</button>
                </div>
            </div>
        `;

        const container = document.getElementById("qr-code-canvas-container");
        container.innerHTML = "";

        qrChunks.forEach((chunkText, idx) => {
            const qrCard = document.createElement("div");
            qrCard.style.display = "flex";
            qrCard.style.flexDirection = "column";
            qrCard.style.alignItems = "center";
            qrCard.style.background = "#ffffff";
            qrCard.style.padding = "12px";
            qrCard.style.borderRadius = "16px";
            qrCard.style.boxShadow = "0 8px 24px rgba(0,0,0,0.3)";

            if (isMulti) {
                const badge = document.createElement("div");
                badge.style.background = "var(--primary-accent, #6366f1)";
                badge.style.color = "#ffffff";
                badge.style.fontSize = "11px";
                badge.style.fontWeight = "bold";
                badge.style.padding = "2px 8px";
                badge.style.borderRadius = "6px";
                badge.style.marginBottom = "8px";
                badge.textContent = `Part ${idx + 1} of ${qrChunks.length}`;
                qrCard.appendChild(badge);
            }

            const qrEl = document.createElement("div");
            qrCard.appendChild(qrEl);
            container.appendChild(qrCard);

            new QRCode(qrEl, {
                text: chunkText,
                width: isMulti ? 200 : 320,
                height: isMulti ? 200 : 320,
                colorDark: "#000000",
                colorLight: "#ffffff",
                correctLevel: QRCode.CorrectLevel.M
            });
        });

        backdrop.classList.add("show");

        const closeBtn = document.getElementById("qr-modal-close-btn");
        const closeFooterBtn = document.getElementById("qr-modal-close-footer-btn");

        const closeModal = () => {
            backdrop.classList.remove("show");
        };

        closeBtn.addEventListener("click", closeModal);
        closeFooterBtn.addEventListener("click", closeModal);
    }
    async function initChatUnreadPolling() {
        const page = document.body.dataset.page;
        if (page === "login" || page === "reset-password") return;

        const loggedIn = await checkLoginStatus();
        if (!loggedIn) return;

        try {
            const settingsResponse = await request("/api/settings?local=true", { timeoutMs: 3000 });
            if (!settingsResponse || !settingsResponse.settings || !settingsResponse.settings.chatEnabled) {
                return;
            }
        } catch (e) {
            console.warn("Failed to fetch settings for chat unreads:", e);
            return;
        }

        async function fetchUnreadStatus() {
            try {
                const status = await request("/api/chat/unread-status", { timeoutMs: 3000 });
                if (status) {
                    updateChatBadge(status.unreadCount, status.mentionCount);
                }
            } catch (e) {
                if (e.status === 401) {
                    clearInterval(pollInterval);
                }
                console.warn("Failed to fetch chat unread status:", e);
            }
        }

        function updateChatBadge(unreadCount, mentionCount) {
            const chatLink = document.getElementById("nav-chat");
            if (!chatLink) return;

            // Remove existing
            const existingBadge = chatLink.querySelector(".nav-chat-badge, .nav-chat-dot");
            if (existingBadge) {
                existingBadge.remove();
            }

            if (mentionCount > 0) {
                const badge = document.createElement("span");
                badge.className = "nav-chat-badge";
                badge.textContent = mentionCount;
                chatLink.appendChild(badge);
            } else if (unreadCount > 0) {
                const dot = document.createElement("span");
                dot.className = "nav-chat-dot";
                chatLink.appendChild(dot);
            }
        }

        // Poll every 10 seconds
        const pollInterval = setInterval(fetchUnreadStatus, 10000);

        // Initial fetch
        fetchUnreadStatus();

        // Listen for immediate update events when user reads messages
        window.addEventListener("obsidianscout:chat-read", () => {
            fetchUnreadStatus();
        });
    }

    async function syncOfflineCache(clearOldOthers = false) {
        if (!navigator.onLine) return;
        const loggedIn = await checkLoginStatus();
        if (!loggedIn) return;

        try {
            const settingsResponse = await request("/api/settings", { timeoutMs: 5000 }).catch(() => null);
            const user = await getMe().catch(() => null);
            if (!settingsResponse || !settingsResponse.settings) {
                return;
            }

            const settings = settingsResponse.settings;
            const eventKey = resolveEventKey(settings);
            const isAdminUser = user && isAdmin(user.role);

            const endpoints = [
                "/api/auth/me",
                "/api/settings",
                "/api/settings?local=true",
                "/api/config",
                "/api/pit-config",
                "/api/qual-config",
                "/api/events?cached=1",
                "/api/summary",
                "/api/scouting",
                "/api/scouting?includePrescout=true",
                "/api/pit-scouting",
                "/api/pit-scouting?includePrescout=true",
                "/api/qual-scouting",
                "/api/qual-scouting?includePrescout=true",
                "/api/prescout/scouting",
                "/api/prescout/pit-scouting",
                "/api/prescout/qual-scouting",
                "/api/alliances",
                "/api/alliances/invites",
                "/api/alliances/invites/count",
                "/api/alliances/import-sources"
            ];

            if (settings.year) {
                endpoints.push(`/api/events?year=${settings.year}&cached=1`);
            }
            if (eventKey) {
                endpoints.push(`/api/teams?eventKey=${eventKey}`);
                endpoints.push(`/api/matches?eventKey=${eventKey}`);
                endpoints.push(`/api/alliance-selection?eventKey=${eventKey}`);
            }
            if (isAdminUser) {
                endpoints.push("/api/admin/users");
                if (currentUser && currentUser.role === "SUPERADMIN") {
                    endpoints.push("/api/admin/email-settings");
                }
            }

            console.log("[Offline Cache] Starting background sync of " + endpoints.length + " endpoints...");
            
            const updatedKeys = new Set();
            let successCount = 0;

            for (const endpoint of endpoints) {
                try {
                    await request(endpoint, { timeoutMs: 8000 });
                    updatedKeys.add("cache:" + endpoint);
                    successCount++;
                } catch (e) {
                    console.warn("[Offline Cache] Sync failed for " + endpoint + ":", e.message || e);
                }
            }
            console.log("[Offline Cache] Background sync complete. Successfully updated " + successCount + " endpoints.");

            if (clearOldOthers && successCount > 0) {
                const keysToRemove = [];
                for (let i = 0; i < localStorage.length; i++) {
                    const key = localStorage.key(i);
                    if (key && key.startsWith("cache:") && key !== "cache:/api/auth/me" && !updatedKeys.has(key)) {
                        keysToRemove.push(key);
                    }
                }
                keysToRemove.forEach(key => safeRemoveItem(key));
                console.log("[Offline Cache] Cleared " + keysToRemove.length + " old/stale cache keys.");
            }
        } catch (err) {
            console.warn("[Offline Cache] Sync loop failed:", err);
        }
    }

    async function ensureSidebarAndFooter(sidebar) {
        if (!sidebar) return;
        if (!sidebar.querySelector(".sidebar-nav")) {
            console.log("[Sidebar] Sidebar is empty, loading base template...");
            let baseHtml = sessionStorage.getItem("obsidianscout:base_html");
            if (!baseHtml) {
                baseHtml = safeGetItem("obsidianscout:base_html");
            }
            if (!baseHtml) {
                try {
                    const res = await fetch("/base.html");
                    if (res.ok) {
                        baseHtml = await res.text();
                        sessionStorage.setItem("obsidianscout:base_html", baseHtml);
                        safeSetItem("obsidianscout:base_html", baseHtml);
                    }
                } catch (e) {
                    console.warn("[Sidebar] Failed to fetch sidebar base template:", e);
                }
            }

            if (baseHtml) {
                console.log("[Sidebar] Successfully acquired baseHtml template.");
                const tempDiv = document.createElement("div");
                tempDiv.innerHTML = baseHtml;
                const templateSidebar = tempDiv.querySelector(".sidebar");
                if (templateSidebar) {
                    sidebar.innerHTML = templateSidebar.innerHTML;
                    console.log("[Sidebar] Injected base template innerHTML into sidebar.");
                    
                    // Re-apply user badge if cached user info is available
                    try {
                        const meText = safeGetItem("cache:/api/auth/me");
                        if (meText) {
                            const parsed = JSON.parse(meText);
                            const user = parsed.user || parsed;
                            if (user) {
                                setUserBadge(user);
                            }
                        }
                    } catch (e) {
                        console.warn("Failed to restore user badge on dynamic sidebar load", e);
                    }
                    
                    // Restore active nav highlight
                    setActiveNav();
                    wireThemeToggle(sidebar);
                    renderServerVersion(sidebar);
                }
            }
        }
    }

    // Set up Service Worker and Global Connection Listeners
    document.addEventListener("DOMContentLoaded", async () => {
        // Clean up legacy i18n caches in localStorage
        try {
            const keysToRemove = [];
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (key && key.startsWith("i18n:")) {
                    keysToRemove.push(key);
                }
            }
            keysToRemove.forEach(k => localStorage.removeItem(k));
        } catch (e) {
            // ignore
        }

        function urlBase64ToUint8Array(base64String) {
            const padding = '='.repeat((4 - base64String.length % 4) % 4);
            const base64 = (base64String + padding)
                .replace(/\-/g, '+')
                .replace(/_/g, '/');
            const rawData = window.atob(base64);
            const outputArray = new Uint8Array(rawData.length);
            for (let i = 0; i < rawData.length; ++i) {
                outputArray[i] = rawData.charCodeAt(i);
            }
            return outputArray;
        }

        async function initPushNotifications(registration) {
            if (!('PushManager' in window)) {
                console.warn('[Push] Push messaging is not supported in this browser');
                return;
            }
            try {
                const permission = await Notification.requestPermission();
                if (permission !== 'granted') {
                    console.warn('[Push] Permission for notifications was denied');
                    return;
                }

                const response = await request("/api/push/public-key");
                if (!response || !response.publicKey) {
                    console.warn('[Push] Failed to load VAPID public key from server');
                    return;
                }
                const serverKey = urlBase64ToUint8Array(response.publicKey);

                let subscription = await registration.pushManager.getSubscription();
                if (subscription) {
                    let keyMismatch = false;
                    if (subscription.options && subscription.options.applicationServerKey) {
                        const subKey = new Uint8Array(subscription.options.applicationServerKey);
                        if (serverKey.length !== subKey.length || !serverKey.every((v, i) => v === subKey[i])) {
                            keyMismatch = true;
                        }
                    } else {
                        keyMismatch = true;
                    }

                    if (keyMismatch) {
                        console.log('[Push] VAPID public key changed or mismatch detected, unsubscribing...');
                        await subscription.unsubscribe();
                        subscription = null;
                    }
                }

                if (!subscription) {
                    subscription = await registration.pushManager.subscribe({
                        userVisibleOnly: true,
                        applicationServerKey: serverKey
                    });
                }

                await request("/api/push/subscribe", {
                    method: "POST",
                    json: subscription
                });
                console.log('[Push] Subscribed successfully and registered on backend');
            } catch (e) {
                console.warn('[Push] Failed to register push subscription:', e);
            }
        }

        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' })
                .then(reg => {
                    console.log('[ServiceWorker] Scope:', reg.scope);
                    // Force an update check on page load
                    reg.update().catch(err => console.warn('[ServiceWorker] Update check failed:', err));
                    navigator.serviceWorker.ready.then(readyReg => {
                        initPushNotifications(readyReg);
                    });
                })
                .catch(err => console.error('[ServiceWorker] Registration failed:', err));
        }

        function initGlobalSidebarAndUser(sidebarEl) {
            if (!sidebarEl) return;

            initTheme();
            wireThemeToggle();
            wireLogout();
            setActiveNav();

            try {
                const meText = safeGetItem("cache:/api/auth/me");
                if (meText) {
                    const parsed = JSON.parse(meText);
                    const user = parsed.user || parsed;
                    if (user && user.username) {
                        setUserBadge(user);
                        adjustNavForRole(user);
                    }
                }
            } catch (e) {
                console.warn("[Sidebar] Failed to load cached user info:", e);
            }

            getMe().then((user) => {
                if (user && user.username) {
                    setUserBadge(user);
                    adjustNavForRole(user);
                }
            }).catch((err) => {
                console.warn("[Sidebar] getMe background update failed:", err);
            });
        }

        initTheme();
        wireThemeToggle();

        const sidebar = document.querySelector(".sidebar");
        if (sidebar) {
            await ensureSidebarAndFooter(sidebar);
            injectConnectionWidget(sidebar);
            injectLanguageSelector(sidebar);
            renderServerVersion(sidebar);
            initGlobalSidebarAndUser(sidebar);
        }

        wireSidebarToggle();
        injectMobileTopBar();

        window.addEventListener("online", () => {
            updateConnectionStatus();
            const isCacheManager = document.body && document.body.dataset.page === "cache-manager";
            if (!isCacheManager) {
                syncOfflineEntries();
            }
            syncOfflineCache();
            const isDataPage = ['dashboard', 'qual-data', 'pit-data', 'all-data', 'analytics', 'graphs', 'events', 'teams', 'matches', 'predictor', 'alliances', 'users', 'config', 'settings'].includes(document.body.dataset.page);
            const isUserEditing = document.querySelector('input:focus, textarea:focus') !== null;
            if (isDataPage && !isUserEditing) {
                saveScrollPositions();
                window.location.reload();
            }
        });
        window.addEventListener("offline", updateConnectionStatus);

        if (navigator.onLine) {
            const isCacheManager = document.body && document.body.dataset.page === "cache-manager";
            if (!isCacheManager) {
                syncOfflineEntries();
            }
            syncOfflineCache();
        }
        // Load selected language bundle and apply translations
        loadLocale(safeGetItem('obsidianscout:lang') || 'en').then(() => applyTranslations());
        restoreScrollPositions();
        initChatUnreadPolling();

        // Run background cache synchronization every 5 minutes
        setInterval(syncOfflineCache, 300000);
    });

    window.addEventListener("beforeunload", (event) => {
        let count = 0;
        for (const type in CACHE_CONFIGS) {
            const config = CACHE_CONFIGS[type];
            const pending = JSON.parse(safeGetItem(config.key) || "[]");
            count += pending.length;
        }
        if (count > 0) {
            const message = (typeof t === 'function') ? t('unsynced_entries','You have unsynced offline scouting entries! If you leave, they might not be synced to the server.') : "You have unsynced offline scouting entries! If you leave, they might not be synced to the server.";
            event.returnValue = message;
            return message;
        }
    });

    function formatTeam(teamKey, teamNumber) {
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

    function showLoadingSpinner(container, text) {
        if (!container) return;
        const spinnerText = text || (typeof t === 'function' ? t('status.loading', 'Loading data...') : 'Loading data...');
        container.innerHTML = `
            <div class="spinner-container">
                <div class="spinner"></div>
                <div class="spinner-text">${spinnerText}</div>
            </div>
        `;
    }

    function showRetryButton(container, message, onRetry) {
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

    /**
     * ─── LIQUID GLASS SVG FILTER ENGINE ────────────────────────────────────────
     * Injects a hidden SVG containing two named filter definitions:
     *   #liquid-glass  — full pixel displacement (refraction warp)
     *   #glass-rim     — softer edge-only distortion for the border glow
     *
     * backdrop-filter: url(#liquid-glass) is supported in Firefox.
     * Chrome/Safari fall back to the standard blur() chain via @supports in CSS.
     */
    function injectLiquidGlassSVG() {
        if (document.getElementById('liquid-glass-defs')) return;
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.id = 'liquid-glass-defs';
        svg.setAttribute('aria-hidden', 'true');
        svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none;';
        svg.innerHTML = `
        <defs>
            <!--
                LAYER 1 — PRIMARY REFRACTION FILTER
                feTurbulence generates organic fractal noise.
                feColorMatrix isolates the R and G channels as displacement vectors.
                feDisplacementMap uses those to warp every pixel of the source.
                scale=18 gives ~18px max warp — visible but not glitchy.
            -->
            <filter id="liquid-glass" x="-10%" y="-10%" width="120%" height="120%" color-interpolation-filters="sRGB">
                <feTurbulence
                    type="fractalNoise"
                    baseFrequency="0.018 0.022"
                    numOctaves="4"
                    seed="3"
                    stitchTiles="stitch"
                    result="noise"/>
                <feColorMatrix
                    in="noise"
                    type="matrix"
                    values="1 0 0 0 0
                            0 1 0 0 0
                            0 0 1 0 0
                            0 0 0 1 0"
                    result="coloredNoise"/>
                <feDisplacementMap
                    in="SourceGraphic"
                    in2="coloredNoise"
                    scale="18"
                    xChannelSelector="R"
                    yChannelSelector="G"
                    result="displaced"/>
            </filter>

            <!--
                LAYER 2 — RIM DISTORTION FILTER (subtler, for borders/edges)
                Lower scale (8) and higher baseFrequency for a finer-grain edge warp.
            -->
            <filter id="glass-rim" x="-5%" y="-5%" width="110%" height="110%" color-interpolation-filters="sRGB">
                <feTurbulence
                    type="fractalNoise"
                    baseFrequency="0.035 0.04"
                    numOctaves="2"
                    seed="7"
                    stitchTiles="stitch"
                    result="rimNoise"/>
                <feDisplacementMap
                    in="SourceGraphic"
                    in2="rimNoise"
                    scale="8"
                    xChannelSelector="R"
                    yChannelSelector="G"/>
            </filter>
        </defs>`;
        document.body.insertAdjacentElement('afterbegin', svg);
    }

    async function loadAndRenderBanners() {
        const mainContent = document.querySelector(".main-content");
        if (!mainContent) return;

        try {
            const page = document.body.dataset.page;
            if (page === "login" || page === "reset-password") return;

            const banners = await request("/api/banners");
            if (!banners || banners.length === 0) return;

            let dismissed = [];
            try {
                const saved = localStorage.getItem("obsidianscout:dismissed_banners");
                if (saved) dismissed = JSON.parse(saved);
            } catch (e) {
                console.warn("Failed to load dismissed banners", e);
            }

            let container = document.querySelector(".banner-container");
            if (!container) {
                container = document.createElement("div");
                container.className = "banner-container";
                mainContent.insertBefore(container, mainContent.firstChild);
            }

            container.innerHTML = "";

            banners.forEach(banner => {
                if (banner.isDismissible && dismissed.includes(banner.id)) {
                    return;
                }

                const item = document.createElement("div");
                item.className = `banner-item banner-${banner.bannerType}`;
                item.dataset.id = banner.id;

                let html = `
                    <div class="banner-body">
                        <div class="banner-message">${banner.message}</div>
                `;

                if (banner.isExpandable && banner.expandableMessage) {
                    html += `
                        <div class="banner-details hidden">${banner.expandableMessage}</div>
                        <button class="btn-banner-toggle" type="button">Read More</button>
                    `;
                }

                html += `</div>`;

                if (banner.isDismissible) {
                    html += `<button class="btn-banner-close" type="button" aria-label="Close banner">&times;</button>`;
                }

                item.innerHTML = html;

                if (banner.isExpandable && banner.expandableMessage) {
                    const toggleBtn = item.querySelector(".btn-banner-toggle");
                    const details = item.querySelector(".banner-details");
                    toggleBtn.addEventListener("click", () => {
                        const isHidden = details.classList.toggle("hidden");
                        toggleBtn.textContent = isHidden ? "Read More" : "Show Less";
                    });
                }

                if (banner.isDismissible) {
                    const closeBtn = item.querySelector(".btn-banner-close");
                    closeBtn.addEventListener("click", () => {
                        item.remove();
                        dismissed.push(banner.id);
                        try {
                            localStorage.setItem("obsidianscout:dismissed_banners", JSON.stringify(dismissed));
                        } catch (e) {
                            console.warn("Failed to save dismissed banners", e);
                        }
                        if (container.children.length === 0) {
                            container.remove();
                        }
                    });
                }

                container.appendChild(item);
            });
        } catch (error) {
            console.error("Failed to load banners:", error);
        }
    }

    // ==========================================================================
    // Interactive Tour Logic
    // ==========================================================================

    const TOUR_STEPS = {
        scout_beginner: [
            { page: "dashboard", target: ".main-content h1", titleKey: "tour.step.dashboard.title", descKey: "tour.step.dashboard.desc" },
            { page: "scout", target: "#scouting-form", titleKey: "tour.step.scout.title", descKey: "tour.step.scout.desc" },
            { page: "pit-scout", target: "#pit-scouting-form", titleKey: "tour.step.pit_scout.title", descKey: "tour.step.pit_scout.desc" },
            { page: "qr-scanner", target: ".main-content", titleKey: "tour.step.qr_scanner.title", descKey: "tour.step.qr_scanner.desc" }
        ],
        scout_intermediate: [
            { page: "all-data", target: ".main-content", titleKey: "tour.step.all_data.title", descKey: "tour.step.all_data.desc" },
            { page: "qual-data", target: ".main-content", titleKey: "tour.step.qual_data.title", descKey: "tour.step.qual_data.desc" },
            { page: "pit-data", target: ".main-content", titleKey: "tour.step.pit_data.title", descKey: "tour.step.pit_data.desc" }
        ],
        scout_advanced: [
            { page: "analytics", target: ".main-content", titleKey: "tour.step.analytics.title", descKey: "tour.step.analytics.desc" },
            { page: "graphs", target: ".main-content", titleKey: "tour.step.graphs.title", descKey: "tour.step.graphs.desc" },
            { page: "predictor", target: ".main-content", titleKey: "tour.step.predictor.title", descKey: "tour.step.predictor.desc" },
            { page: "alliance-selection", target: ".main-content", titleKey: "tour.step.alliance_selection.title", descKey: "tour.step.alliance_selection.desc" }
        ],
        admin_beginner: [
            { page: "dashboard", target: "#nav-user", titleKey: "tour.step.admin_badge.title", descKey: "tour.step.admin_badge.desc" },
            { page: "users", target: ".main-content", titleKey: "tour.step.admin_users.title", descKey: "tour.step.admin_users.desc" },
            { page: "banners", target: ".main-content", titleKey: "tour.step.admin_banners.title", descKey: "tour.step.admin_banners.desc" }
        ],
        admin_intermediate: [
            { page: "admin-settings", target: "#admin-panel", titleKey: "tour.step.admin_panel.title", descKey: "tour.step.admin_panel.desc" },
            { page: "admin-settings", target: "#tab-config", titleKey: "tour.step.admin_tab_config.title", descKey: "tour.step.admin_tab_config.desc", clickToAdvance: true },
            { page: "admin-settings", target: "button[data-config-kind='pit']", titleKey: "tour.step.admin_config_pit.title", descKey: "tour.step.admin_config_pit.desc", clickToAdvance: true },
            { page: "admin-settings", target: "#btn-raw-editor", titleKey: "tour.step.admin_config_raw.title", descKey: "tour.step.admin_config_raw.desc", clickToAdvance: true },
            { page: "admin-settings", target: "#tab-api", titleKey: "tour.step.admin_tab_api.title", descKey: "tour.step.admin_tab_api.desc", clickToAdvance: true },
            { page: "admin-settings", target: "#tab-api", titleKey: "tour.step.admin_api_desc.title", descKey: "tour.step.admin_api_desc.desc" },
            { page: "admin-settings", target: "#tab-permissions", titleKey: "tour.step.admin_tab_permissions.title", descKey: "tour.step.admin_tab_permissions.desc", clickToAdvance: true },
            { page: "admin-settings", target: "#tab-permissions", titleKey: "tour.step.admin_permissions_desc.title", descKey: "tour.step.admin_permissions_desc.desc" }
        ],
        admin_advanced: [
            { page: "backup", target: ".main-content", titleKey: "tour.step.admin_backup.title", descKey: "tour.step.admin_backup.desc" },
            { page: "migration", target: ".main-content", titleKey: "tour.step.admin_migration.title", descKey: "tour.step.admin_migration.desc" }
        ]
    };

    function isPageAccessible(page, role) {
        const bypassPages = ["dashboard", "settings", "login", "index", "theme-editor"];
        if (bypassPages.includes(page)) return true;

        if (["users", "banners", "admin-settings", "default-configs"].includes(page) && !isAdmin(role)) {
            return false;
        }
        if (page === "migration" && !isSuperAdmin(role)) {
            return false;
        }

        try {
            const settingsText = safeGetItem("cache:/api/settings");
            if (settingsText) {
                const parsed = JSON.parse(settingsText);
                const settings = parsed.settings || parsed;
                const allowedPages = role === "SCOUT" ? settings.scoutPages : (role === "ANALYTICS" ? settings.analyticsPages : settings.adminPages);
                if (allowedPages && Array.isArray(allowedPages)) {
                    return allowedPages.includes(page);
                }
            }
        } catch (e) {}

        const link = document.querySelector(`.sidebar-link[data-page="${page}"]`);
        if (link && link.style.display === "none") {
            return false;
        }

        return true;
    }

    function getTourStepsForRoleAndLevel(me, level) {
        const rawSteps = TOUR_STEPS[level] || [];
        return rawSteps.filter(step => isPageAccessible(step.page, me.role));
    }


    async function syncTourProgressToServer() {
        if (!navigator.onLine) return;
        const active = safeGetItem('obsidianscout:tour_active') === 'true' ? safeGetItem('obsidianscout:tour_level') : null;
        const stepIndex = parseInt(safeGetItem('obsidianscout:tour_step_index') || '0', 10);
        const completed = JSON.parse(safeGetItem('obsidianscout:tour_completed_list') || '[]');
        
        try {
            await request('/api/user/tour-progress', {
                method: 'POST',
                json: { active, stepIndex, completed }
            });
        } catch (e) {
            console.warn('[Tour] Failed to sync progress to server:', e);
        }
    }

    async function initTour(me) {
        if (!me) return;

        const sidebar = document.querySelector('.sidebar');
        if (sidebar) {
            const footer = sidebar.querySelector('.sidebar-footer');
            if (footer && !document.getElementById('btn-take-tour')) {
                const btn = document.createElement('button');
                btn.id = 'btn-take-tour';
                btn.className = 'btn ghost';
                btn.type = 'button';
                btn.style.marginTop = '8px';
                btn.style.width = '100%';
                btn.textContent = t('tour.take_tour', 'Take a Tour');
                btn.addEventListener('click', () => {
                    showTourLevelSelector(me);
                });
                const apiAttr = footer.querySelector('#api-attribution');
                if (apiAttr) {
                    footer.insertBefore(btn, apiAttr);
                } else {
                    footer.appendChild(btn);
                }
                wireThemeToggle(footer);
            }
        }

        const hasFetched = sessionStorage.getItem('obsidianscout:tour_fetched') === 'true';
        if (navigator.onLine && !hasFetched) {
            try {
                const res = await request('/api/user/tour-progress');
                if (res) {
                    sessionStorage.setItem('obsidianscout:tour_fetched', 'true');
                    if (res.active) {
                        safeSetItem('obsidianscout:tour_active', 'true');
                        safeSetItem('obsidianscout:tour_level', res.active);
                        safeSetItem('obsidianscout:tour_step_index', String(res.stepIndex || 0));
                    } else {
                        if (safeGetItem('obsidianscout:tour_active') === 'true') {
                            safeRemoveItem('obsidianscout:tour_active');
                            safeRemoveItem('obsidianscout:tour_level');
                            safeRemoveItem('obsidianscout:tour_step_index');
                        }
                    }
                    if (res.completed && Array.isArray(res.completed)) {
                        safeSetItem('obsidianscout:tour_completed_list', JSON.stringify(res.completed));
                    }
                }
            } catch (e) {
                console.warn('[Tour] Failed to load progress from server:', e);
            }
        }

        const isActive = safeGetItem('obsidianscout:tour_active') === 'true';
        if (isActive) {
            runActiveTourStep(me);
        }
    }

    function runActiveTourStep(me) {
        const level = safeGetItem('obsidianscout:tour_level');
        const stepIndexStr = safeGetItem('obsidianscout:tour_step_index');
        let stepIndex = parseInt(stepIndexStr, 10);
        if (isNaN(stepIndex)) stepIndex = 0;

        const steps = getTourStepsForRoleAndLevel(me, level);
        if (!steps || steps.length === 0 || stepIndex >= steps.length) {
            endTour();
            return;
        }

        const step = steps[stepIndex];
        const currentPage = document.body.dataset.page;

        if (step.page !== currentPage) {
            const link = document.querySelector(`.sidebar-link[data-page="${step.page}"]`);
            if (link && link.href) {
                window.location.href = link.href;
            } else {
                const fallbackUrls = {
                    "settings": "/config",
                    "backup": "/backup",
                    "migration": "/migration"
                };
                const url = fallbackUrls[step.page] || ("/" + step.page);
                window.location.href = url;
            }
            return;
        }

        let retries = 0;
        const maxRetries = 16;
        const checkInterval = setInterval(() => {
            const el = document.querySelector(step.target);
            if (el && el.offsetHeight > 0) {
                clearInterval(checkInterval);
                displayTourStepPopup(el, step, stepIndex, steps.length, me);
            } else {
                retries++;
                if (retries >= maxRetries) {
                    clearInterval(checkInterval);
                    displayTourStepPopup(null, step, stepIndex, steps.length, me);
                }
            }
        }, 250);
    }

    let activePopup = null;
    let activeBackdrop = null;

    function displayTourStepPopup(targetEl, step, stepIndex, totalSteps, me) {
        clearTourDOM();

        const backdrop = document.createElement('div');
        backdrop.className = 'tour-backdrop';
        document.body.appendChild(backdrop);
        activeBackdrop = backdrop;

        if (targetEl) {
            targetEl.classList.add('tour-highlighted');
            targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }

        const popup = document.createElement('div');
        popup.className = 'tour-popup';
        
        const currentNum = stepIndex + 1;
        const progressText = t('tour.step_progress', `Step ${currentNum} of ${totalSteps}`)
                                .replace('{current}', currentNum)
                                .replace('{total}', totalSteps);

        const titleText = t(step.titleKey, step.titleKey);
        const descText = t(step.descKey, step.descKey);

        const isLast = stepIndex === totalSteps - 1;

        popup.innerHTML = `
            <button class="tour-popup-close" aria-label="Close">&times;</button>
            <h3>${titleText}</h3>
            <p>${descText}</p>
            <div class="tour-popup-footer">
                <span class="tour-popup-progress">${progressText}</span>
                <div class="tour-popup-nav">
                    ${stepIndex > 0 ? `<button type="button" class="btn-tour-back">${t('tour.btn_back', 'Back')}</button>` : ''}
                    <button type="button" class="btn-tour-next">${isLast ? t('tour.btn_finish', 'Finish') : t('tour.btn_next', 'Next')}</button>
                </div>
            </div>
        `;

        document.body.appendChild(popup);
        activePopup = popup;

        popup.querySelector('.tour-popup-close').addEventListener('click', () => {
            endTour();
        });

        const nextBtn = popup.querySelector('.btn-tour-next');
        const isClickToAdvance = step.clickToAdvance === true && targetEl;

        if (isClickToAdvance) {
            if (nextBtn) {
                nextBtn.style.display = 'none';
            }
            const progress = popup.querySelector('.tour-popup-progress');
            if (progress) {
                progress.innerHTML += ` <span style="display:block;margin-top:4px;color:var(--accent-2);font-weight:700;font-size:10px;">${t('tour.click_to_advance', '(Click highlighted element to continue)')}</span>`;
            }

            const advanceHandler = async () => {
                clearTourDOM();
                safeSetItem('obsidianscout:tour_step_index', stepIndex + 1);
                await syncTourProgressToServer();
                setTimeout(() => {
                    runActiveTourStep(me);
                }, 150);
            };

            targetEl.addEventListener('click', advanceHandler, { once: true });
            targetEl._tourAdvanceHandler = advanceHandler;
        } else {
            if (nextBtn) {
                nextBtn.addEventListener('click', async () => {
                    if (isLast) {
                        const currentLevel = safeGetItem('obsidianscout:tour_level');
                        await endTour(currentLevel);
                        showToast(t('tour.finished_toast', 'Tutorial completed!'), 'success');
                    } else {
                        safeSetItem('obsidianscout:tour_step_index', stepIndex + 1);
                        await syncTourProgressToServer();
                        runActiveTourStep(me);
                    }
                });
            }
        }

        const backBtn = popup.querySelector('.btn-tour-back');
        if (backBtn) {
            backBtn.addEventListener('click', async () => {
                safeSetItem('obsidianscout:tour_step_index', Math.max(0, stepIndex - 1));
                await syncTourProgressToServer();
                runActiveTourStep(me);
            });
        }

        if (targetEl) {
            positionPopupNextToElement(popup, targetEl);
            const reposition = () => positionPopupNextToElement(popup, targetEl);
            window.addEventListener('resize', reposition);
            window.addEventListener('scroll', reposition);
            popup.dataset.repositionListeners = 'true';
            popup._reposition = reposition;
        } else {
            popup.style.position = 'fixed';
            popup.style.top = '50%';
            popup.style.left = '50%';
            popup.style.transform = 'translate(-50%, -50%)';
        }
    }

    function positionPopupNextToElement(popup, targetEl) {
        const rect = targetEl.getBoundingClientRect();
        const popupWidth = popup.offsetWidth || 320;
        const popupHeight = popup.offsetHeight || 150;
        const margin = 12;

        let top = rect.bottom + window.scrollY + margin;
        let left = rect.left + window.scrollX + (rect.width - popupWidth) / 2;

        if (rect.bottom + popupHeight + margin > window.innerHeight) {
            if (rect.top - popupHeight - margin > 0) {
                top = rect.top + window.scrollY - popupHeight - margin;
            } else {
                if (rect.right + popupWidth + margin < window.innerWidth) {
                    top = rect.top + window.scrollY + (rect.height - popupHeight) / 2;
                    left = rect.right + window.scrollX + margin;
                } else if (rect.left - popupWidth - margin > 0) {
                    top = rect.top + window.scrollY + (rect.height - popupHeight) / 2;
                    left = rect.left + window.scrollX - popupWidth - margin;
                }
            }
        }

        const viewportWidth = window.innerWidth;
        if (left < 10) left = 10;
        if (left + popupWidth > viewportWidth - 10) left = viewportWidth - popupWidth - 10;

        popup.style.top = `${top}px`;
        popup.style.left = `${left}px`;
    }

    function clearTourDOM() {
        if (activePopup && activePopup.dataset.repositionListeners === 'true' && activePopup._reposition) {
            window.removeEventListener('resize', activePopup._reposition);
            window.removeEventListener('scroll', activePopup._reposition);
        }

        document.querySelectorAll('.tour-highlighted, [id^="tab-"], [data-config-kind], .sidebar-link').forEach(el => {
            if (el._tourAdvanceHandler) {
                el.removeEventListener('click', el._tourAdvanceHandler);
                delete el._tourAdvanceHandler;
            }
        });

        document.querySelectorAll('.tour-highlighted').forEach(el => {
            el.classList.remove('tour-highlighted');
        });

        if (activePopup) {
            activePopup.remove();
            activePopup = null;
        }
        if (activeBackdrop) {
            activeBackdrop.remove();
            activeBackdrop = null;
        }
    }

    async function endTour(completedLevelKey = null) {
        clearTourDOM();
        safeRemoveItem('obsidianscout:tour_active');
        safeRemoveItem('obsidianscout:tour_level');
        safeRemoveItem('obsidianscout:tour_step_index');
        
        if (completedLevelKey) {
            const completed = JSON.parse(safeGetItem('obsidianscout:tour_completed_list') || '[]');
            if (!completed.includes(completedLevelKey)) {
                completed.push(completedLevelKey);
            }
            safeSetItem('obsidianscout:tour_completed_list', JSON.stringify(completed));
        }

        await syncTourProgressToServer();
    }

    function showTourLevelSelector(me) {
        const existing = document.getElementById('tour-level-modal-overlay');
        if (existing) existing.remove();

        const overlay = document.createElement('div');
        overlay.id = 'tour-level-modal-overlay';
        overlay.className = 'tour-modal-overlay';

        const isUserAdmin = isAdmin(me.role) || isSuperAdmin(me.role);
        const completedList = JSON.parse(safeGetItem('obsidianscout:tour_completed_list') || '[]');
        
        const checkMark = (levelKey) => {
            if (completedList.includes(levelKey)) {
                return ` <span style="color:var(--accent-3);font-size:12px;margin-left:4px;font-weight:700;">✔</span>`;
            }
            return '';
        };

        let adminSectionHtml = '';
        if (isUserAdmin) {
            adminSectionHtml = `
                <div class="tour-modal-section-title">${t('tour.admin_track', 'Administrator-only Tracks')}</div>
                <div class="tour-level-grid">
                    <button type="button" class="tour-level-btn" data-level="admin_beginner">
                        <span class="tour-level-name">${t('tour.level_beginner', 'Beginner Track')}${checkMark('admin_beginner')}</span>
                        <span class="tour-level-desc">${t('tour.step.admin_badge.title', 'User Management & Roles')}</span>
                    </button>
                    <button type="button" class="tour-level-btn" data-level="admin_intermediate">
                        <span class="tour-level-name">${t('tour.level_intermediate', 'Intermediate Track')}${checkMark('admin_intermediate')}</span>
                        <span class="tour-level-desc">${t('tour.step.admin_configs.title', 'Configs & Permissions')}</span>
                    </button>
                    <button type="button" class="tour-level-btn" data-level="admin_advanced">
                        <span class="tour-level-name">${t('tour.level_advanced', 'Advanced Track')}${checkMark('admin_advanced')}</span>
                        <span class="tour-level-desc">${t('tour.step.admin_backup.title', 'Backups & Legacy Migrations')}</span>
                    </button>
                </div>
            `;
        }

        overlay.innerHTML = `
            <div class="tour-modal">
                <button class="tour-modal-close" aria-label="Close">&times;</button>
                <h2>${t('tour.welcome_title', 'Take a Tour!')}</h2>
                <p>${t('tour.welcome_desc', 'Welcome to ObsidianScout! Select your experience level to start a custom, interactive walkthrough of our features.')}</p>
                
                <div class="tour-modal-section-title">${t('tour.scout_analytics_track', 'Scout & Analytics Tracks')}</div>
                <div class="tour-level-grid">
                    <button type="button" class="tour-level-btn" data-level="scout_beginner">
                        <span class="tour-level-name">${t('tour.level_beginner', 'Beginner Track')}${checkMark('scout_beginner')}</span>
                        <span class="tour-level-desc">${t('tour.step.dashboard.title', 'Forms & Basic Entry')}</span>
                    </button>
                    <button type="button" class="tour-level-btn" data-level="scout_intermediate">
                        <span class="tour-level-name">${t('tour.level_intermediate', 'Intermediate Track')}${checkMark('scout_intermediate')}</span>
                        <span class="tour-level-desc">${t('tour.step.all_data.title', 'Data Browsing & Queries')}</span>
                    </button>
                    <button type="button" class="tour-level-btn" data-level="scout_advanced">
                        <span class="tour-level-name">${t('tour.level_advanced', 'Advanced Track')}${checkMark('scout_advanced')}</span>
                        <span class="tour-level-desc">${t('tour.step.analytics.title', 'Charts, Predictions & Synergy')}</span>
                    </button>
                </div>

                ${adminSectionHtml}
            </div>
        `;

        document.body.appendChild(overlay);

        overlay.querySelector('.tour-modal-close').addEventListener('click', () => {
            overlay.remove();
        });

        overlay.querySelectorAll('.tour-level-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const level = btn.dataset.level;
                overlay.remove();
                startTour(me, level);
            });
        });
    }

    async function startTour(me, level) {
        safeSetItem('obsidianscout:tour_active', 'true');
        safeSetItem('obsidianscout:tour_level', level);
        safeSetItem('obsidianscout:tour_step_index', 0);
        await syncTourProgressToServer();
        runActiveTourStep(me);
    }

    function initHapticDelegation() {
        document.addEventListener("click", (e) => {
            const target = e.target;
            const button = target.closest("button");
            if (button) {
                if (button.disabled) return;
                triggerHaptic("light");
                return;
            }
            if (target.type === "checkbox") {
                triggerHaptic("light");
                return;
            }
        }, { passive: true });

        document.addEventListener("input", (e) => {
            if (e.target.type === "range") {
                triggerHaptic("light");
            }
        }, { passive: true });

        document.addEventListener("change", (e) => {
            if (e.target.tagName === "SELECT") {
                triggerHaptic("light");
            }
        }, { passive: true });
    }

    // Run at DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            injectLiquidGlassSVG();
            loadAndRenderBanners();
            initHapticDelegation();
        });
    } else {
        injectLiquidGlassSVG();
        loadAndRenderBanners();
        initHapticDelegation();
    }

    function getProgram() {
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

    function getProgramPrefix() {
        return getProgram().toLowerCase();
    }

    window.Obsidianscout = {
        getProgram,
        getProgramPrefix,
        request,
        getMe,
        checkLoginStatus,
        requireAuth,
        showToast,
        triggerHaptic,
        setUserBadge,
        refreshNavAvatar,
        setActiveNav,
        adjustNavForRole,
        wireLogout,
        initTheme,
        wireThemeToggle,
        formatTimestamp,
        getDeviceTimezone,
        formatTimestampWithVenueTooltip,
        localToUtcEpoch,
        resolveEventKey,
        hasRole,
        isAdmin,
        isSuperAdmin,
        canAccessAnalytics,
        ROLE_HIERARCHY,
        updateConnectionStatus,
        syncOfflineEntries,
        showLoadingSpinner,
        showRetryButton
        ,t
        ,setLanguage
        ,localize
        ,formatTeam
        ,safeGetItem
        ,safeSetItem
        ,safeRemoveItem
        ,downloadJson
        ,showQrModal
        ,compressData
        ,decompressData
        ,CACHE_CONFIGS
        ,startTour
        ,endTour
        ,showTourLevelSelector
        ,showSetupWizardModal
    };
})();
