/**
 * Utility Media Module - ObsidianScout
 * Sanitizes, strips EXIF/GPS metadata, downscales robot photos, and re-encodes to
 * WebP (max 1280px, the server's limit; usually ~150–300 KB) before uploading.
 */

/**
 * Checks whether the current browser environment supports WebP export via canvas.
 */
export function isWebpSupported() {
    try {
        if (typeof document === 'undefined') return false;
        const c = document.createElement("canvas");
        c.width = 1;
        c.height = 1;
        return c.toDataURL("image/webp").startsWith("data:image/webp");
    } catch (_) {
        return false;
    }
}

function getScaledDimensions(w, h, maxDim) {
    if (w > maxDim || h > maxDim) {
        if (w > h) {
            return {
                width: maxDim,
                height: Math.max(1, Math.round((h * maxDim) / w))
            };
        } else {
            return {
                width: Math.max(1, Math.round((w * maxDim) / h)),
                height: maxDim
            };
        }
    }
    return { width: w, height: h };
}

export async function processImageUpload(fileOrBlob, maxDimension = 1280, quality = 0.78) {
    return new Promise((resolve, reject) => {
        if (!fileOrBlob) return reject(new Error("No image file provided"));
        const reader = new FileReader();
        reader.onload = (e) => {
            const img = new Image();
            img.onload = () => {
                const origWidth = img.naturalWidth || img.width;
                const origHeight = img.naturalHeight || img.height;

                const format = isWebpSupported() ? "image/webp" : "image/jpeg";
                const formatLabel = format === "image/webp" ? "WebP" : "JPEG";

                let targetQ = quality;
                let currentDim = maxDimension;

                let { width, height } = getScaledDimensions(origWidth, origHeight, currentDim);

                const canvas = document.createElement("canvas");
                canvas.width = width;
                canvas.height = height;
                const ctx = canvas.getContext("2d");
                ctx.imageSmoothingEnabled = true;
                ctx.imageSmoothingQuality = "high";
                ctx.drawImage(img, 0, 0, width, height);

                // Re-encoding through canvas eliminates all EXIF, GPS, camera metadata, and polyglots
                let dataUrl = canvas.toDataURL(format, targetQ);
                let headerLen = dataUrl.indexOf(",") + 1;
                let approxSizeBytes = Math.round((dataUrl.length - headerLen) * 0.75);

                // Adaptive pass to stay near ~300 KB:
                // If high-frequency detail/noise pushes the size over 300 KB, step down quality/dimensions
                if (approxSizeBytes > 300 * 1024) {
                    targetQ = Math.max(0.62, targetQ - 0.12);
                    currentDim = Math.min(currentDim, 1024);
                    const scaled = getScaledDimensions(origWidth, origHeight, currentDim);
                    width = scaled.width;
                    height = scaled.height;

                    canvas.width = width;
                    canvas.height = height;
                    ctx.imageSmoothingEnabled = true;
                    ctx.imageSmoothingQuality = "high";
                    ctx.drawImage(img, 0, 0, width, height);

                    dataUrl = canvas.toDataURL(format, targetQ);
                    headerLen = dataUrl.indexOf(",") + 1;
                    approxSizeBytes = Math.round((dataUrl.length - headerLen) * 0.75);
                }

                resolve({
                    dataUrl,
                    width,
                    height,
                    origWidth,
                    origHeight,
                    format: formatLabel,
                    sizeBytes: approxSizeBytes,
                    formattedSize: `${(approxSizeBytes / 1024).toFixed(1)} KB (${formatLabel})`
                });
            };
            img.onerror = () => reject(new Error("Failed to decode image file. Please provide a valid JPEG, PNG, or WebP."));
            img.src = e.target.result;
        };
        reader.onerror = () => reject(new Error("Failed to read image file"));
        reader.readAsDataURL(fileOrBlob);
    });
}

