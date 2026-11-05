/**
 * Gemma 4's native chat markup: tool calls, thinking channel and special tokens.
 *
 * The model is trained to call tools as
 *     <|tool_call>call:make_table{columns:[<|"|>EPA<|"|>],limit:10}<tool_call|>
 * (keys bare, strings wrapped in the <|"|> token) and to think inside <|channel>thought ... <channel|> when the system
 * turn starts with <|think|>. The worker decodes WITH special tokens so these markers survive; this module turns the
 * raw text into tool calls, a thought and the visible answer. Shared by the worker and the main thread (no imports).
 */

const Q = '<|"|>';

/** Parses one Gemma argument object body ("key:value,...") starting at text[i] === "{". Returns [value, nextIndex]. */
function parseValue(text, i) {
    const skipWs = () => { while (i < text.length && /\s/.test(text[i])) i++; };
    skipWs();
    if (text.startsWith(Q, i)) {
        const end = text.indexOf(Q, i + Q.length);
        if (end < 0) throw new Error("unterminated string");
        return [text.slice(i + Q.length, end), end + Q.length];
    }
    if (text[i] === '"') {
        // Plain JSON string (the model sometimes falls back to it).
        let j = i + 1;
        while (j < text.length && text[j] !== '"') j += text[j] === "\\" ? 2 : 1;
        return [JSON.parse(text.slice(i, j + 1)), j + 1];
    }
    if (text[i] === "{") {
        const obj = {};
        i++;
        skipWs();
        if (text[i] === "}") return [obj, i + 1];
        for (;;) {
            skipWs();
            let key;
            if (text.startsWith(Q, i) || text[i] === '"') {
                [key, i] = parseValue(text, i);
            } else {
                const m = /^[A-Za-z_][\w-]*/.exec(text.slice(i));
                if (!m) throw new Error(`bad key at ${i}`);
                key = m[0];
                i += key.length;
            }
            skipWs();
            if (text[i] !== ":") throw new Error(`expected : at ${i}`);
            let value;
            [value, i] = parseValue(text, i + 1);
            obj[key] = value;
            skipWs();
            if (text[i] === ",") { i++; continue; }
            if (text[i] === "}") return [obj, i + 1];
            throw new Error(`expected , or } at ${i}`);
        }
    }
    if (text[i] === "[") {
        const arr = [];
        i++;
        skipWs();
        if (text[i] === "]") return [arr, i + 1];
        for (;;) {
            let value;
            [value, i] = parseValue(text, i);
            arr.push(value);
            skipWs();
            if (text[i] === ",") { i++; continue; }
            if (text[i] === "]") return [arr, i + 1];
            throw new Error(`expected , or ] at ${i}`);
        }
    }
    const m = /^(?:-?\d+(?:\.\d+)?(?:e[+-]?\d+)?|true|false|null)/i.exec(text.slice(i));
    if (m) {
        const raw = m[0].toLowerCase();
        const value = raw === "true" ? true : raw === "false" ? false : raw === "null" ? null : Number(raw);
        return [value, i + m[0].length];
    }
    // Bare word (e.g. desc): read up to the next delimiter.
    const bare = /^[^,}\]]+/.exec(text.slice(i));
    if (bare) return [bare[0].trim(), i + bare[0].length];
    throw new Error(`unexpected value at ${i}`);
}

/**
 * Tool calls in Gemma's native syntax: [{ tool, args }]. Accepts the call with or without the <|tool_call> markers
 * (they vanish if a caller decoded without special tokens), and a missing closing marker at the end of the output.
 */
export function parseGemmaToolCalls(raw) {
    const text = String(raw || "");
    const calls = [];
    const re = /(?:<\|tool_call>\s*)?call:([A-Za-z_][\w]*)\s*(?=\{)/g;
    let m;
    while ((m = re.exec(text))) {
        try {
            const [args, end] = parseValue(text, m.index + m[0].length);
            calls.push({ tool: m[1], args: args && typeof args === "object" && !Array.isArray(args) ? args : {} });
            re.lastIndex = end;
        } catch (_) {
            calls.push({ tool: m[1], args: null, malformed: true });
        }
    }
    return calls;
}

/** The thinking-channel text (empty when the model did not think). */
export function gemmaThought(raw) {
    const text = String(raw || "");
    const parts = [];
    const re = /<\|channel>(?:thought)?\s*([\s\S]*?)(?:<channel\|>|$)/g;
    let m;
    while ((m = re.exec(text))) parts.push(m[1].trim());
    return parts.filter(Boolean).join("\n\n");
}

/** Removes every special-token marker (Gemma and Llama styles) that may appear when decoding with special tokens. */
export function stripSpecialTokens(text) {
    return String(text || "")
        .replace(/<\|"\|>/g, '"')
        .replace(/<\|(?:[a-z_]+)\|?>|<[a-z_]+\|>|<\/?(?:eos|bos|pad)>|<\|(?:eot_id|end_of_text|begin_of_text|start_header_id|end_header_id)\|>/gi, "");
}

/**
 * The text to show the user: thinking moved into <thought>...</thought> (what the UI's thought drawer reads),
 * tool-call blocks and special tokens removed. Works on partial (streaming) output.
 */
export function gemmaDisplayText(raw) {
    const text = String(raw || "");
    const thought = gemmaThought(text);
    const open = /<\|channel>(?![\s\S]*<channel\|>)/.test(text);
    let body = text
        .replace(/<\|channel>[\s\S]*?(?:<channel\|>|$)/g, "")
        .replace(/<\|tool_call>[\s\S]*?(?:<tool_call\|>|$)/g, "")
        .replace(/<\|tool_response>[\s\S]*?(?:<tool_response\|>|$)/g, "");
    body = stripSpecialTokens(body).trim();
    if (!thought) return body;
    return open && !body ? `<thought>${thought}` : `<thought>${thought}</thought>${body}`;
}

const api = { parseGemmaToolCalls, gemmaThought, gemmaDisplayText, stripSpecialTokens };
export default api;
