import test from "node:test";
import assert from "node:assert/strict";
import { isWebpSupported, processImageUpload } from "../../main/resources/static/js/utilities/media.js";

test("isWebpSupported returns false in headless node environment without throwing", () => {
    const supported = isWebpSupported();
    assert.equal(typeof supported, "boolean");
    assert.equal(supported, false);
});

test("processImageUpload scales dimensions correctly and produces WebP in simulated browser", async () => {
    // Mock minimal browser globals for testing canvas/FileReader pipeline
    const originalDocument = globalThis.document;
    const originalFileReader = globalThis.FileReader;
    const originalImage = globalThis.Image;

    try {
        let createdCanvas = null;

        globalThis.document = {
            createElement(tag) {
                if (tag === "canvas") {
                    const canvas = {
                        width: 0,
                        height: 0,
                        getContext(type) {
                            return {
                                imageSmoothingEnabled: false,
                                imageSmoothingQuality: "low",
                                drawImage: (img, sx, sy, sw, sh) => {}
                            };
                        },
                        toDataURL(format, quality) {
                            if (format === "image/webp") {
                                return `data:image/webp;base64,${"AAAA".repeat(80000)}`; // ~320 KB payload
                            }
                            return `data:image/jpeg;base64,${"BBBB".repeat(80000)}`;
                        }
                    };
                    createdCanvas = canvas;
                    return canvas;
                }
                return {};
            }
        };

        globalThis.FileReader = class {
            readAsDataURL(blob) {
                setTimeout(() => {
                    this.onload({ target: { result: "data:image/jpeg;base64,mock" } });
                }, 5);
            }
        };

        globalThis.Image = class {
            constructor() {
                this.naturalWidth = 3840;
                this.naturalHeight = 2160;
                setTimeout(() => {
                    if (this.onload) this.onload();
                }, 5);
            }
            set src(val) {}
        };

        const mockFile = { name: "robot_4k.jpg", size: 8 * 1024 * 1024 };
        const result = await processImageUpload(mockFile, 1920, 0.78);

        assert.ok(result);
        assert.equal(result.format, "WebP");
        assert.ok(result.dataUrl.startsWith("data:image/webp;base64,"));
        assert.equal(result.width, 1920);
        assert.equal(result.height, 1080); // 3840x2160 scaled to 1920x1080
        assert.ok(result.sizeBytes > 100 * 1024 && result.sizeBytes < 500 * 1024);
        assert.ok(result.formattedSize.includes("KB (WebP)"));
    } finally {
        globalThis.document = originalDocument;
        globalThis.FileReader = originalFileReader;
        globalThis.Image = originalImage;
    }
});

test("processImageUpload falls back to JPEG when browser canvas lacks WebP support", async () => {
    const originalDocument = globalThis.document;
    const originalFileReader = globalThis.FileReader;
    const originalImage = globalThis.Image;

    try {
        globalThis.document = {
            createElement(tag) {
                if (tag === "canvas") {
                    return {
                        width: 0,
                        height: 0,
                        getContext: () => ({
                            drawImage: () => {}
                        }),
                        toDataURL(format) {
                            // Browsers without WebP export support return PNG when WebP is requested
                            if (format === "image/webp") {
                                return "data:image/png;base64,fallback";
                            }
                            return `data:image/jpeg;base64,${"CCCC".repeat(80000)}`;
                        }
                    };
                }
                return {};
            }
        };

        globalThis.FileReader = class {
            readAsDataURL(blob) {
                setTimeout(() => {
                    this.onload({ target: { result: "data:image/png;base64,mock" } });
                }, 5);
            }
        };

        globalThis.Image = class {
            constructor() {
                this.naturalWidth = 1200;
                this.naturalHeight = 900;
                setTimeout(() => {
                    if (this.onload) this.onload();
                }, 5);
            }
            set src(val) {}
        };

        const mockFile = { name: "robot.png" };
        const result = await processImageUpload(mockFile, 1920, 0.78);

        assert.ok(result);
        assert.equal(result.format, "JPEG");
        assert.ok(result.dataUrl.startsWith("data:image/jpeg;base64,"));
        assert.equal(result.width, 1200);
        assert.equal(result.height, 900);
        assert.ok(result.formattedSize.includes("KB (JPEG)"));
    } finally {
        globalThis.document = originalDocument;
        globalThis.FileReader = originalFileReader;
        globalThis.Image = originalImage;
    }
});
