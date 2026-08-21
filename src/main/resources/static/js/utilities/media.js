/**
 * Utility Media Module - ObsidianScout
 * Sanitizes, strips EXIF/GPS metadata, downscales UHD robot photos, and re-encodes to optimized JPEG data URLs.
 */

export async function processImageUpload(fileOrBlob, maxDimension = 540, quality = 0.65) {
    return new Promise((resolve, reject) => {
        if (!fileOrBlob) return reject(new Error("No image file provided"));
        const reader = new FileReader();
        reader.onload = (e) => {
            const img = new Image();
            img.onload = () => {
                let width = img.naturalWidth || img.width;
                let height = img.naturalHeight || img.height;
                const origWidth = width;
                const origHeight = height;

                if (width > maxDimension || height > maxDimension) {
                    if (width > height) {
                        height = Math.round((height * maxDimension) / width);
                        width = maxDimension;
                    } else {
                        width = Math.round((width * maxDimension) / height);
                        height = maxDimension;
                    }
                }

                const canvas = document.createElement("canvas");
                canvas.width = width;
                canvas.height = height;
                const ctx = canvas.getContext("2d");
                ctx.imageSmoothingEnabled = true;
                ctx.imageSmoothingQuality = "high";
                ctx.drawImage(img, 0, 0, width, height);

                // Re-encoding through canvas eliminates all EXIF, GPS, camera metadata, and polyglots
                const dataUrl = canvas.toDataURL("image/jpeg", quality);
                const approxSizeBytes = Math.round((dataUrl.length - 23) * 0.75);

                resolve({
                    dataUrl,
                    width,
                    height,
                    origWidth,
                    origHeight,
                    sizeBytes: approxSizeBytes,
                    formattedSize: (approxSizeBytes / 1024).toFixed(1) + " KB"
                });
            };
            img.onerror = () => reject(new Error("Failed to decode image file. Please provide a valid JPEG, PNG, or WebP."));
            img.src = e.target.result;
        };
        reader.onerror = () => reject(new Error("Failed to read image file"));
        reader.readAsDataURL(fileOrBlob);
    });
}
