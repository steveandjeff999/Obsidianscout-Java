/**
 * Service Data-Compression Module - ObsidianScout
 * Deflate/deflate-raw stream compression, base64 payload packaging, chunking for QR transmission, and JSON file exporter.
 */

import { showToast } from '../components/toast.js';

export function downloadJson(payload, filename) {
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

export async function compressData(dataStr) {
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

export async function decompressData(compressedStr) {
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

export async function compressAndChunkData(dataStr, chunkSize = 550) {
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
