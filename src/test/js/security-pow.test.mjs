import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { _internal, solveChallenge } from "../../main/resources/static/js/security/pow.js";

test("the inline SHA-256 matches Node's for short and multi-block inputs", () => {
    for (const s of ["abc", "", "a".repeat(55), "a".repeat(56), "a".repeat(64), "a".repeat(119), "deadbeef:12345"]) {
        const want = parseInt(crypto.createHash("sha256").update(s).digest("hex").slice(0, 8), 16);
        assert.equal(_internal.sha256FirstWord(s), want, JSON.stringify(s));
    }
});

test("a solved challenge has the required leading zero bits, as the server checks it", async () => {
    const nonce = "00112233445566778899aabbccddeeff";
    const answer = await solveChallenge({ token: "abc.def", nonce, difficulty: 12 });
    const [token, counter] = [answer.slice(0, answer.lastIndexOf(":")), answer.slice(answer.lastIndexOf(":") + 1)];
    assert.equal(token, "abc.def");
    const digest = crypto.createHash("sha256").update(`${nonce}:${counter}`).digest();
    assert.equal(digest[0], 0);
    assert.equal(digest[1] >> 4, 0);
});
