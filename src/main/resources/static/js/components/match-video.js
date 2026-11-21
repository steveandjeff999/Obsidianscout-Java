/*
 * Inline match video player for TBA-published YouTube videos.
 *
 * Nothing is loaded from YouTube until the viewer presses "Watch match": the player is the
 * privacy-enhanced youtube-nocookie.com embed, and the page CSP only allows frames from there.
 * Only https://www.youtube.com/watch?v=<id> URLs from the server are accepted; the ID is
 * re-validated before it is put into the embed URL.
 */

const YT_URL = /^https:\/\/www\.youtube\.com\/watch\?v=([A-Za-z0-9_-]{6,32})(?:&t=(\d+)s)?$/;

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

function parseVideos(videos) {
    if (!Array.isArray(videos)) return [];
    const out = [];
    for (const v of videos) {
        const m = v && typeof v.url === "string" ? v.url.match(YT_URL) : null;
        if (m) out.push({ id: m[1], start: m[2] ? Number(m[2]) : 0, url: v.url });
    }
    return out;
}

function icon(name) {
    const span = document.createElement("span");
    span.className = "material-symbols-outlined";
    span.setAttribute("aria-hidden", "true");
    span.textContent = name;
    return span;
}

/**
 * Returns an element with a "Watch match" button (one per video) that swaps in an embedded
 * player, plus an "Open on YouTube" link. Returns null when there are no playable videos.
 */
export function createMatchVideoPlayer(videos, { label } = {}) {
    const list = parseVideos(videos);
    if (list.length === 0) return null;

    const root = document.createElement("div");
    root.className = "match-video-player";

    const bar = document.createElement("div");
    bar.className = "match-video-bar";
    const frameHost = document.createElement("div");
    frameHost.className = "match-video-frame hidden";

    let activeIdx = -1;
    const buttons = [];

    function play(idx) {
        const v = list[idx];
        if (!v) return;
        if (activeIdx === idx) {
            // Second press hides the player.
            frameHost.replaceChildren();
            frameHost.classList.add("hidden");
            activeIdx = -1;
            buttons.forEach((b) => b.classList.remove("active"));
            return;
        }
        const iframe = document.createElement("iframe");
        const params = new URLSearchParams({ autoplay: "1", rel: "0", modestbranding: "1" });
        if (v.start) params.set("start", String(v.start));
        iframe.src = `https://www.youtube-nocookie.com/embed/${encodeURIComponent(v.id)}?${params}`;
        iframe.title = label || t("match_video.title", "Match video");
        iframe.allow = "autoplay; encrypted-media; picture-in-picture; fullscreen";
        iframe.allowFullscreen = true;
        iframe.referrerPolicy = "strict-origin-when-cross-origin";
        iframe.loading = "lazy";
        frameHost.replaceChildren(iframe);
        frameHost.classList.remove("hidden");
        activeIdx = idx;
        buttons.forEach((b, i) => b.classList.toggle("active", i === idx));
    }

    list.forEach((v, idx) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn secondary btn-sm match-video-play";
        btn.appendChild(icon("play_circle"));
        const text = document.createElement("span");
        text.textContent = list.length > 1
            ? `${t("match_video.watch_n", "Video")} ${idx + 1}`
            : t("match_video.watch", "Watch match");
        btn.appendChild(text);
        btn.addEventListener("click", (e) => {
            e.preventDefault();
            e.stopPropagation();
            play(idx);
        });
        buttons.push(btn);
        bar.appendChild(btn);
    });

    const open = document.createElement("a");
    open.className = "match-video-open";
    open.href = list[0].url;
    open.target = "_blank";
    open.rel = "noopener noreferrer";
    open.appendChild(icon("open_in_new"));
    const openText = document.createElement("span");
    openText.textContent = t("match_video.open", "Open on YouTube");
    open.appendChild(openText);
    open.addEventListener("click", (e) => e.stopPropagation());
    bar.appendChild(open);

    root.append(bar, frameHost);
    return root;
}

const matchesCache = new Map(); // eventKey -> Promise<Map<matchKey, videos>>

/**
 * Looks up a match's videos from /api/matches, caching one request per event.
 * Resolves to [] when the match, event or videos aren't known.
 */
export async function loadMatchVideos(request, eventKey, matchKey) {
    const ev = String(eventKey || "").trim().toLowerCase();
    const mk = String(matchKey || "").trim().toLowerCase();
    if (!ev || !mk || typeof request !== "function") return [];
    if (!matchesCache.has(ev)) {
        const p = request(`/api/matches?eventKey=${encodeURIComponent(ev)}`)
            .then((rows) => {
                const map = new Map();
                (Array.isArray(rows) ? rows : (rows && Array.isArray(rows.matches) ? rows.matches : [])).forEach((m) => {
                    if (m && m.matchKey && Array.isArray(m.videos) && m.videos.length) {
                        map.set(String(m.matchKey).toLowerCase(), m.videos);
                    }
                });
                return map;
            })
            .catch(() => {
                matchesCache.delete(ev); // let a later call retry
                return new Map();
            });
        matchesCache.set(ev, p);
    }
    const map = await matchesCache.get(ev);
    return map.get(mk) || [];
}

/**
 * Appends a player for the entry's match to `container` once its videos are known.
 * Does nothing when there are none. Safe to call for entries without a match key.
 */
export async function appendEntryMatchVideo(request, container, entry) {
    if (!container || !entry || !entry.matchKey) return;
    const eventKey = entry.eventKey || String(entry.matchKey).split("_")[0];
    const videos = await loadMatchVideos(request, eventKey, entry.matchKey);
    if (!videos.length || !container.isConnected) return;
    const player = createMatchVideoPlayer(videos);
    if (player) container.appendChild(player);
}
