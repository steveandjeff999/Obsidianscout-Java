/**
 * Component Slack Banner Module - ObsidianScout
 * Top-right popup banners for Slack messages that matched a channel rule. At most three show at once; older ones
 * fold into a "+N more" chip. Banners auto-dismiss after 10 s (paused on hover) unless the user was mentioned.
 */

import { faIcon } from '../utilities/fa-icons.js';

const ROOT_ID = "slack-banner-root";
const MAX_VISIBLE = 3;
const AUTO_DISMISS_MS = 10000;
let hiddenCount = 0;

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

function root() {
    let el = document.getElementById(ROOT_ID);
    if (!el) {
        el = document.createElement("div");
        el.id = ROOT_ID;
        el.className = "slack-banner-root";
        el.setAttribute("aria-live", "polite");
        document.body.appendChild(el);
    }
    return el;
}

function renderOverflow(container) {
    let chip = container.querySelector(".slack-banner-more");
    if (hiddenCount <= 0) {
        if (chip) chip.remove();
        return;
    }
    if (!chip) {
        chip = document.createElement("button");
        chip.type = "button";
        chip.className = "slack-banner-more";
        chip.addEventListener("click", () => {
            hiddenCount = 0;
            container.querySelectorAll(".slack-banner").forEach((b) => b.remove());
            chip.remove();
        });
        container.appendChild(chip);
    }
    chip.textContent = t("slack.banner_more", "+{n} more Slack messages · Dismiss all").replace("{n}", hiddenCount);
}

function dismiss(banner) {
    if (!banner.isConnected) return;
    banner.classList.add("leaving");
    setTimeout(() => {
        const container = banner.parentElement;
        banner.remove();
        if (container) renderOverflow(container);
    }, 180);
}

/** Only https links into Slack are opened; anything else is dropped. */
function safeUrl(url) {
    try {
        const u = new URL(url);
        return u.protocol === "https:" && /(^|\.)slack\.com$/i.test(u.hostname) ? u.href : "";
    } catch (e) {
        return "";
    }
}

/** Shows one Slack banner. [n] is the server's SlackBannerDto. */
export function showSlackBanner(n) {
    if (!n || !document.body) return;
    const container = root();

    const banner = document.createElement("div");
    banner.className = "slack-banner" + (n.mentioned ? " mentioned" : "");
    banner.setAttribute("role", "status");
    banner.dataset.id = n.id || "";

    const icon = document.createElement("span");
    icon.className = "slack-banner-icon";
    icon.innerHTML = faIcon("slack", 18);

    const body = document.createElement("div");
    body.className = "slack-banner-body";

    const head = document.createElement("div");
    head.className = "slack-banner-head";
    const channel = document.createElement("span");
    channel.className = "slack-banner-channel";
    channel.textContent = n.channel || "#slack";
    const author = document.createElement("span");
    author.className = "slack-banner-author";
    author.textContent = n.author || "";
    head.append(channel, author);
    if (n.mentioned) {
        const tag = document.createElement("span");
        tag.className = "slack-banner-tag";
        tag.textContent = t("slack.banner_mentioned", "Mentioned");
        head.appendChild(tag);
    }

    const text = document.createElement("div");
    text.className = "slack-banner-text";
    text.textContent = n.text || "";

    body.append(head, text);

    const url = safeUrl(n.url || "");
    if (url) {
        const open = document.createElement("a");
        open.className = "slack-banner-open";
        open.href = url;
        open.target = "_blank";
        open.rel = "noopener noreferrer";
        open.innerHTML = faIcon("arrow-up-right-from-square", 12);
        open.append(" " + t("slack.banner_open", "Open in Slack"));
        open.addEventListener("click", () => dismiss(banner));
        body.appendChild(open);
    }

    const close = document.createElement("button");
    close.type = "button";
    close.className = "slack-banner-close";
    close.setAttribute("aria-label", t("slack.banner_dismiss", "Dismiss"));
    close.innerHTML = faIcon("xmark", 14);
    close.addEventListener("click", () => dismiss(banner));

    banner.append(icon, body, close);
    container.insertBefore(banner, container.firstChild);

    // Keep the newest few; older ones collapse into the "+N more" chip, mentions last.
    const banners = Array.from(container.querySelectorAll(".slack-banner")).reverse();
    let excess = banners.length - MAX_VISIBLE;
    [...banners.filter((b) => !b.classList.contains("mentioned")), ...banners.filter((b) => b.classList.contains("mentioned"))]
        .forEach((b) => {
            if (excess <= 0) return;
            b.remove();
            hiddenCount++;
            excess--;
        });
    renderOverflow(container);

    if (!n.mentioned) {
        let timer = setTimeout(() => dismiss(banner), AUTO_DISMISS_MS);
        banner.addEventListener("mouseenter", () => clearTimeout(timer));
        banner.addEventListener("mouseleave", () => {
            timer = setTimeout(() => dismiss(banner), AUTO_DISMISS_MS / 2);
        });
    }
}
