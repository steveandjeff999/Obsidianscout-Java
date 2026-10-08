/**
 * Regenerates src/test/resources/config-collab-vectors.json from the browser engine
 * (static/js/services/config-collab.js). The Kotlin, JavaScript and Dart engine tests all replay these
 * vectors, so the three implementations must produce identical documents and identical ops.
 * Run: node src/test/js/generate-config-collab-vectors.mjs   (then copy the file to the app's test/fixtures/)
 */
import { writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { engine } = require(path.resolve(here, "../../main/resources/static/js/services/config-collab.js"));

const f = (id, extra = {}) => ({ id, label: id.toUpperCase(), type: "counter", ...extra });
const state = (doc, keys) => ({ doc, keys });
const base3 = () => state({ title: "T", version: 1, fields: [f("a"), f("b"), f("c")] }, ["k1", "k2", "k3"]);

const applyCases = [
    ["set top-level", base3(), [{ op: "set", path: ["title"], value: "New" }]],
    ["set creates nested objects", base3(), [{ op: "set", path: ["meta", "a", "b"], value: 3 }]],
    ["set replaces non-object intermediate", state({ title: "x", fields: [] }, []), [{ op: "set", path: ["title", "en"], value: "y" }]],
    ["unset top-level", base3(), [{ op: "unset", path: ["version"] }]],
    ["unset missing is a no-op", base3(), [{ op: "unset", path: ["nope", "deeper"] }]],
    ["set field property", base3(), [{ op: "set", path: ["fields", "k2", "label"], value: "Bee" }]],
    ["set field property on missing key", base3(), [{ op: "set", path: ["fields", "zz", "label"], value: "x" }]],
    ["set whole field", base3(), [{ op: "set", path: ["fields", "k1"], value: { id: "a2", type: "text" } }]],
    ["unset whole field is a no-op", base3(), [{ op: "unset", path: ["fields", "k1"] }]],
    ["set label language on string label", base3(), [{ op: "set", path: ["fields", "k1", "label", "en"], value: "Hi" }]],
    ["unset nested through non-object", base3(), [{ op: "unset", path: ["fields", "k1", "label", "en"] }]],
    ["set on fields itself is a no-op", base3(), [{ op: "set", path: ["fields"], value: [] }]],
    ["insert at start", base3(), [{ op: "insert", key: "n", after: null, value: f("n") }]],
    ["insert after middle", base3(), [{ op: "insert", key: "n", after: "k2", value: f("n") }]],
    ["insert after missing goes last", base3(), [{ op: "insert", key: "n", after: "gone", value: f("n") }]],
    ["insert existing key is a no-op", base3(), [{ op: "insert", key: "k1", after: null, value: f("dup") }]],
    ["delete", base3(), [{ op: "delete", key: "k2" }]],
    ["delete missing", base3(), [{ op: "delete", key: "zz" }]],
    ["move to start", base3(), [{ op: "move", key: "k3", after: null }]],
    ["move after other", base3(), [{ op: "move", key: "k1", after: "k3" }]],
    ["move after missing goes last", base3(), [{ op: "move", key: "k1", after: "gone" }]],
    ["move after itself is a no-op", base3(), [{ op: "move", key: "k2", after: "k2" }]],
    ["move missing key", base3(), [{ op: "move", key: "zz", after: null }]],
    ["unknown op ignored", base3(), [{ op: "explode", key: "k1" }]],
    ["sequence", base3(), [
        { op: "delete", key: "k1" },
        { op: "insert", key: "n1", after: "k3", value: f("n1") },
        { op: "move", key: "k3", after: null },
        { op: "set", path: ["fields", "n1", "min"], value: 0 },
        { op: "set", path: ["title"], value: "Seq" }
    ]],
    ["doc without fields", state({ title: "x" }, []), [{ op: "insert", key: "n", after: null, value: f("n") }]],
];

const diffCases = [
    ["identical", base3(), { title: "T", version: 1, fields: [f("a"), f("b"), f("c")] }],
    ["title changed", base3(), { title: "T2", version: 1, fields: [f("a"), f("b"), f("c")] }],
    ["label changed", base3(), { title: "T", version: 1, fields: [f("a"), f("b", { label: "Bee" }), f("c")] }],
    ["label and id changed", base3(), { title: "T", version: 1, fields: [f("a"), { id: "bee", label: "Bee", type: "counter" }, f("c")] }],
    ["append", base3(), { title: "T", version: 1, fields: [f("a"), f("b"), f("c"), f("d")] }],
    ["prepend", base3(), { title: "T", version: 1, fields: [f("d"), f("a"), f("b"), f("c")] }],
    ["insert middle", base3(), { title: "T", version: 1, fields: [f("a"), f("d"), f("b"), f("c")] }],
    ["delete middle", base3(), { title: "T", version: 1, fields: [f("a"), f("c")] }],
    ["swap", base3(), { title: "T", version: 1, fields: [f("b"), f("a"), f("c")] }],
    ["first to last", base3(), { title: "T", version: 1, fields: [f("b"), f("c"), f("a")] }],
    ["reverse", base3(), { title: "T", version: 1, fields: [f("c"), f("b"), f("a")] }],
    ["combined", base3(), { title: "T", version: 3, fields: [f("c", { min: 2 }), f("x"), f("a"), f("y")] }],
    ["duplicates", state({ fields: [f("a"), f("a"), f("b")] }, ["k1", "k2", "k3"]), { fields: [f("a"), f("b")] }],
    ["nested label", state({ fields: [{ id: "a", label: { en: "A" }, type: "text" }] }, ["k1"]), { fields: [{ id: "a", label: { en: "A", es: "Ah" }, type: "text" }] }],
    ["property removed and added", base3(), { title: "T", version: 1, fields: [{ id: "a", label: "A", type: "counter", max: 9 }, { id: "b", type: "counter" }, f("c")] }],
    ["top-level keys", base3(), { title: "T", fields: [f("a"), f("b"), f("c")], extra: { on: true } }],
    ["target without fields", base3(), { title: "T", version: 1 }],
    ["non-object fields", state({ fields: ["x", f("a"), 3] }, ["k1", "k2", "k3"]), { fields: [f("a"), "y", 3] }],
    ["numbers", state({ version: 1, fields: [{ id: "a", type: "counter", pointsPer: 1.5 }] }, ["k1"]), { version: 2, fields: [{ id: "a", type: "counter", pointsPer: 2 }] }],
    ["options array", state({ fields: [{ id: "s", type: "select", options: [{ label: "A", value: "a" }] }] }, ["k1"]), { fields: [{ id: "s", type: "select", options: [{ label: "A", value: "a" }, { label: "B", value: "b" }] }] }],
    ["empty base", state({ fields: [] }, []), { title: "New", fields: [f("a"), f("b")] }],
];

const counterKeys = () => { let n = 0; return () => `n${n++}`; };

const vectors = {
    apply: applyCases.map(([name, s, ops]) => ({ name, state: s, ops, expect: engine.applyOps(engine.clone(s), ops) })),
    diff: diffCases.map(([name, base, target]) => {
        const result = engine.diff(engine.clone(base), engine.clone(target), counterKeys());
        const applied = engine.applyOps(engine.clone(base), result.ops);
        if (!engine.deepEqual(applied.doc, engine.ensureFields(engine.clone(target)))) throw new Error(`diff case "${name}" does not round-trip`);
        if (applied.keys.join() !== result.keys.join()) throw new Error(`diff case "${name}" keys mismatch`);
        return { name, base, target, ops: result.ops, keys: result.keys };
    }),
};

const out = path.resolve(here, "../resources/config-collab-vectors.json");
writeFileSync(out, JSON.stringify(vectors, null, 2) + "\n");
console.log(`wrote ${vectors.apply.length} apply and ${vectors.diff.length} diff vectors to ${out}`);
