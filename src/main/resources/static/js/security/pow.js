/**
 * Proof-of-work security check - ObsidianScout
 * The server asks for this instead of a CAPTCHA when a sign-in or unblock request looks risky: find a counter such
 * that SHA-256("nonce:counter") starts with `difficulty` zero bits. Takes about a second; nothing is shown to solve.
 */

const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]);
const W = new Uint32Array(64);

/** First 32 bits of SHA-256 of an ASCII string (all the check needs). */
function sha256FirstWord(text) {
    const len = text.length;
    const blocks = ((len + 9 + 63) >> 6);
    const bytes = new Uint8Array(blocks * 64);
    for (let i = 0; i < len; i++) bytes[i] = text.charCodeAt(i) & 0xff;
    bytes[len] = 0x80;
    const bitLen = len * 8;
    bytes[bytes.length - 4] = (bitLen >>> 24) & 0xff;
    bytes[bytes.length - 3] = (bitLen >>> 16) & 0xff;
    bytes[bytes.length - 2] = (bitLen >>> 8) & 0xff;
    bytes[bytes.length - 1] = bitLen & 0xff;

    let h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
    let h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
    for (let b = 0; b < blocks; b++) {
        const off = b * 64;
        for (let i = 0; i < 16; i++) {
            const j = off + i * 4;
            W[i] = (bytes[j] << 24) | (bytes[j + 1] << 16) | (bytes[j + 2] << 8) | bytes[j + 3];
        }
        for (let i = 16; i < 64; i++) {
            const w15 = W[i - 15], w2 = W[i - 2];
            const s0 = ((w15 >>> 7) | (w15 << 25)) ^ ((w15 >>> 18) | (w15 << 14)) ^ (w15 >>> 3);
            const s1 = ((w2 >>> 17) | (w2 << 15)) ^ ((w2 >>> 19) | (w2 << 13)) ^ (w2 >>> 10);
            W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
        }
        let a = h0, bb = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
        for (let i = 0; i < 64; i++) {
            const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
            const ch = (e & f) ^ (~e & g);
            const t1 = (h + S1 + ch + K[i] + W[i]) | 0;
            const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
            const maj = (a & bb) ^ (a & c) ^ (bb & c);
            const t2 = (S0 + maj) | 0;
            h = g; g = f; f = e; e = (d + t1) | 0;
            d = c; c = bb; bb = a; a = (t1 + t2) | 0;
        }
        h0 = (h0 + a) | 0; h1 = (h1 + bb) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0;
        h4 = (h4 + e) | 0; h5 = (h5 + f) | 0; h6 = (h6 + g) | 0; h7 = (h7 + h) | 0;
    }
    return h0 >>> 0;
}

function meets(nonce, counter, bits) {
    const word = sha256FirstWord(`${nonce}:${counter}`);
    if (bits >= 32) return word === 0;
    return (word >>> (32 - bits)) === 0;
}

/**
 * Solves a challenge from the server ({ token, nonce, difficulty }) without freezing the page, and resolves to the
 * answer the server expects back ("token:counter").
 */
export async function solveChallenge(challenge) {
    const { token, nonce } = challenge;
    const bits = Math.max(1, Math.min(24, Number(challenge.difficulty) || 16));
    let counter = 0;
    for (;;) {
        const sliceEnd = counter + 20000;
        for (; counter < sliceEnd; counter++) {
            if (meets(nonce, counter, bits)) return `${token}:${counter}`;
        }
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
}

/** Fetches a fresh challenge and solves it. */
export async function fetchAndSolveChallenge() {
    const response = await fetch("/api/security/challenge", {
        credentials: "same-origin",
        headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" }
    });
    if (!response.ok) throw new Error(`Security check unavailable (HTTP ${response.status})`);
    return solveChallenge(await response.json());
}

export const CHALLENGE_HEADER = "X-Security-Challenge";

// For tests in a console.
export const _internal = { sha256FirstWord, meets };
