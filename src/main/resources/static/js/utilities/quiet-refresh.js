/**
 * Utility Quiet-Refresh Module - ObsidianScout
 * Runs a page's load code in the background so new data swaps in without the page moving. While one runs, spinners
 * and retry screens stay away (the page keeps showing what it has), the page can't shrink under the reader, nested
 * scroll areas keep their place when their contents are rebuilt, and entry animations don't replay.
 */

const REFRESHING_CLASS = "live-refreshing";

let depth = 0;

export function isQuietRefresh() {
    return depth > 0;
}

function pathTo(element, root) {
    const path = [];
    for (let node = element; node && node !== root; node = node.parentElement) {
        if (!node.parentElement) return null;
        path.unshift(Array.prototype.indexOf.call(node.parentElement.children, node));
    }
    return path.join(".");
}

function follow(root, path) {
    let node = root;
    for (const index of path.split(".")) {
        node = node && node.children[Number(index)];
    }
    return node;
}

/** Keeps the page as it is while the DOM changes; returns the function that lets go. */
function holdLayout() {
    if (typeof document === "undefined" || typeof MutationObserver === "undefined") return () => {};
    const root = document.querySelector(".main-content") || document.body;
    if (!root) return () => {};

    const positions = new Map();
    const remember = (element) => {
        const path = pathTo(element, root);
        if (path) positions.set(path, { top: element.scrollTop, left: element.scrollLeft });
    };
    root.querySelectorAll("*").forEach((element) => {
        if (element.scrollTop || element.scrollLeft) remember(element);
    });
    // The reader can keep scrolling during the fetch; where they end up is what counts.
    const onScroll = (event) => {
        if (event.target instanceof Element && root.contains(event.target) && event.target !== root) remember(event.target);
    };
    const restore = () => {
        positions.forEach(({ top, left }, path) => {
            const element = follow(root, path);
            if (!element) return;
            if (element.scrollTop !== top) element.scrollTop = top;
            if (element.scrollLeft !== left) element.scrollLeft = left;
        });
    };

    const minHeight = root.style.minHeight;
    root.style.minHeight = `${root.offsetHeight}px`;
    document.documentElement.classList.add(REFRESHING_CLASS);
    document.addEventListener("scroll", onScroll, true);
    // Mutation callbacks run before the browser paints, so a rebuilt table never shows at its old scroll position.
    const observer = new MutationObserver(restore);
    observer.observe(root, { childList: true, subtree: true });

    return () => {
        observer.disconnect();
        document.removeEventListener("scroll", onScroll, true);
        restore();
        requestAnimationFrame(() => requestAnimationFrame(() => {
            root.style.minHeight = minHeight;
            if (depth === 0) document.documentElement.classList.remove(REFRESHING_CLASS);
        }));
    };
}

export async function runQuietly(task) {
    depth++;
    const release = holdLayout();
    try {
        return await task();
    } finally {
        depth--;
        release();
    }
}
