import test from "node:test";
import assert from "node:assert/strict";
import { sanitizeBannerHtml, BANNER_PURIFY_CONFIG } from "../../main/resources/static/js/utilities/sanitizer.js";

test("sanitizeBannerHtml fallback escapes HTML tags when DOMPurify is not available", () => {
    const raw = "<script>alert('xss')</script><b>Bold Notice</b>";
    const result = sanitizeBannerHtml(raw);
    assert.ok(!result.includes("<script>"), "Should not contain unescaped script tag");
    assert.ok(result.includes("&lt;script&gt;"), "Should escape script tag");
    assert.ok(result.includes("&lt;b&gt;"), "Should escape b tag in fallback mode");
});

test("sanitizeBannerHtml handles null, undefined, and empty string safely", () => {
    assert.equal(sanitizeBannerHtml(null), "");
    assert.equal(sanitizeBannerHtml(undefined), "");
    assert.equal(sanitizeBannerHtml(""), "");
    assert.equal(sanitizeBannerHtml("   "), "");
});

test("BANNER_PURIFY_CONFIG specifies strict allowed tags and URI regexp", () => {
    assert.ok(BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("b"));
    assert.ok(BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("strong"));
    assert.ok(BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("a"));
    assert.ok(!BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("script"));
    assert.ok(!BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("iframe"));
    assert.ok(!BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("style"));
    assert.ok(!BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("img"));
    assert.ok(!BANNER_PURIFY_CONFIG.ALLOWED_TAGS.includes("form"));
});

test("sanitizeBannerHtml integrates with DOMPurify when global is provided", () => {
    let capturedConfig = null;
    globalThis.window = {
        DOMPurify: {
            addHook: () => {},
            sanitize: (input, config) => {
                capturedConfig = config;
                return input.replace(/<script[\s\S]*?<\/script>/gi, "");
            }
        }
    };

    try {
        const out = sanitizeBannerHtml("Hello <script>alert(1)</script>World");
        assert.equal(out, "Hello World");
        assert.ok(capturedConfig !== null);
        assert.deepEqual(capturedConfig.ALLOWED_TAGS, BANNER_PURIFY_CONFIG.ALLOWED_TAGS);
    } finally {
        delete globalThis.window;
    }
});
