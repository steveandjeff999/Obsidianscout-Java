#!/usr/bin/env python3
"""
ObsidianScout Translation Auditor & Sync Utility
=================================================
Finds untranslated, incomplete, missing, and obsolete translations across all
locale files and audits codebase references (HTML, JS, Kotlin).

Usage:
  python scripts/check_translations.py [options]

Examples:
  # Run full audit and display console summary:
  python scripts/check_translations.py

  # Save detailed markdown report to a log file:
  python scripts/check_translations.py --log translation_report.md

  # Export missing keys into patch JSON files for translators:
  python scripts/check_translations.py --export-missing ./scratch/i18n_patches

  # Sync missing keys from en.json into all target locales with English placeholders:
  python scripts/check_translations.py --sync

  # Apply a translated patch file to a specific language:
  python scripts/check_translations.py --apply-patch ./scratch/i18n_patches/missing_es.json --lang es

  # Sort all locale JSON keys alphabetically:
  python scripts/check_translations.py --sort

  # CI/CD check mode (fails if missing keys or broken placeholders exist):
  python scripts/check_translations.py --strict
"""

import argparse
import glob
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

# Force UTF-8 encoding for standard output on Windows consoles
if sys.stdout and hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass
if sys.stderr and hasattr(sys.stderr, 'reconfigure'):
    try:
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

# Allowlisted tokens/proper nouns that are expected to be identical across languages
DEFAULT_ALLOWLIST = {
    "ObsidianScout", "Statbotics", "Statbotics EPA", "EPA", "OPR", "TBA", "TBA OPR",
    "Match 13 EXP", "EXP", "QR", "JAB", "JABCode", "API", "JSON", "CSV", "URL", "ID",
    "UUID", "IP", "NTP", "SSL", "TLS", "HTTP", "HTTPS", "PostgreSQL", "SQLite",
    "Blue Alliance", "The Blue Alliance", "FIRST", "FRC", "FTC", "FLL", "v1.0",
    "OK", "N/A", "NaN", "null", "true", "false", "auto", "General", "Predictor",
    "Banners", "Scouting", "Status", "Admin", "Super Admin", "Role", "Email", "RAM",
    "CPU", "Disk", "GB", "MB", "KB", "ms", "FPS"
}

# Regex to extract placeholders like {0}, {name}, {count}, %s, %d, %1$s, {{var}}
PLACEHOLDER_REGEX = re.compile(r'(?:\{[a-zA-Z0-9_]+\}|\%[0-9]*\$?[a-zA-Z]|\{\{[a-zA-Z0-9_]+\}\})')

# Regex to find explicit translation TODO markers (case sensitive to prevent Spanish word 'todo' from matching)
TODO_REGEX = re.compile(r'(?:\b(?:TODO|FIXME|XXX|TBD|UNTRANSLATED)\b|\[(?:TODO|MISSING|UNTRANSLATED|TRANSLATE)\]|\?\?\?)')

# Regex to extract translation keys used in source code (HTML / JS / Kotlin)
CODE_KEY_PATTERNS = [
    re.compile(r'data-i18n=[\'"]([a-zA-Z0-9_.-]+)[\'"]'),
    re.compile(r'data-i18n-placeholder=[\'"]([a-zA-Z0-9_.-]+)[\'"]'),
    re.compile(r'\b(?:t|localize|safeT)\s*\(\s*[\'"]([a-zA-Z0-9_.-]+)[\'"]'),
    re.compile(r't\(\s*`([a-zA-Z0-9_.-]+)`'),
    re.compile(r'i18nKey:\s*[\'"]([a-zA-Z0-9_.-]+)[\'"]'),
]


def load_json(filepath):
    """Loads a JSON file returning a dictionary and preserves encoding."""
    with open(filepath, 'r', encoding='utf-8') as f:
        return json.load(f)


def save_json(filepath, data, sort_keys=True):
    """Saves a dictionary to JSON formatted with 2 spaces and trailing newline."""
    with open(filepath, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=sort_keys)
        f.write('\n')


def extract_placeholders(text):
    """Returns a sorted list of placeholders found in a string."""
    if not isinstance(text, str):
        return []
    return sorted(PLACEHOLDER_REGEX.findall(text))


def scan_codebase_for_keys(root_dir):
    """
    Scans HTML, JS, and KT files in root_dir for i18n key references.
    Returns a dict mapping key -> list of relative file paths.
    """
    key_locations = defaultdict(set)
    extensions = {'.html', '.js', '.kt', '.json'}
    ignore_dirs = {'.git', 'node_modules', 'build', '.gradle', 'dist', 'out', 'scratch', 'vendor'}

    for root, dirs, files in os.walk(root_dir):
        # Filter out ignored directories in-place
        dirs[:] = [d for d in dirs if d not in ignore_dirs and not d.startswith('.')]
        
        # Don't scan the i18n locale json files themselves as code
        if os.path.basename(root) == 'i18n':
            continue

        for file in files:
            ext = os.path.splitext(file)[1].lower()
            if ext not in extensions:
                continue

            rel_path = os.path.relpath(os.path.join(root, file), root_dir).replace('\\', '/')
            full_path = os.path.join(root, file)

            try:
                with open(full_path, 'r', encoding='utf-8', errors='ignore') as f:
                    content = f.read()

                for pat in CODE_KEY_PATTERNS:
                    for match in pat.finditer(content):
                        k = match.group(1).strip()
                        # Exclude likely non-i18n strings or generic attributes
                        if k and not k.startswith('http') and '.' in k:
                            key_locations[k].add(rel_path)
            except Exception as e:
                print(f"Warning: Could not read {rel_path}: {e}", file=sys.stderr)

    return {k: sorted(list(v)) for k, v in key_locations.items()}


def audit_translations(i18n_dir, source_lang="en", target_langs=None, scan_code=True, project_root="."):
    """
    Performs full audit of translations.
    Returns a structured dictionary of results.
    """
    i18n_path = Path(i18n_dir)
    if not i18n_path.exists():
        raise FileNotFoundError(f"i18n directory not found: {i18n_dir}")

    source_file = i18n_path / f"{source_lang}.json"
    if not source_file.exists():
        raise FileNotFoundError(f"Source translation file not found: {source_file}")

    source_dict = load_json(source_file)
    source_keys = set(source_dict.keys())

    # Discover target language files
    all_json_files = sorted(list(i18n_path.glob("*.json")))
    target_files = {}
    for p in all_json_files:
        lang_code = p.stem
        if lang_code == source_lang:
            continue
        if target_langs and lang_code not in target_langs:
            continue
        target_files[lang_code] = p

    report = {
        "source_lang": source_lang,
        "source_key_count": len(source_keys),
        "locales": {},
        "code_audit": None
    }

    # Audit each target locale
    for lang, filepath in target_files.items():
        target_dict = load_json(filepath)
        target_keys = set(target_dict.keys())

        missing_keys = sorted(list(source_keys - target_keys))
        extra_keys = sorted(list(target_keys - source_keys))

        empty_keys = []
        todo_keys = []
        identical_keys = []
        placeholder_mismatch = []

        for key in source_keys:
            if key not in target_dict:
                continue

            src_val = str(source_dict.get(key, ""))
            tgt_val = str(target_dict.get(key, ""))

            # 1. Check for empty or whitespace-only
            if not tgt_val or not tgt_val.strip():
                empty_keys.append({"key": key, "source": src_val})
                continue

            # 2. Check for explicit TODO markers
            if TODO_REGEX.search(tgt_val):
                todo_keys.append({"key": key, "source": src_val, "target": tgt_val})

            # 3. Check for identical to English (excluding short tokens, allowlist, numbers)
            trimmed_src = src_val.strip()
            trimmed_tgt = tgt_val.strip()
            if (trimmed_src == trimmed_tgt and 
                len(trimmed_src) > 3 and 
                trimmed_src not in DEFAULT_ALLOWLIST and
                not trimmed_src.isdigit() and
                not re.match(r'^[0-9\W]+$', trimmed_src)):
                identical_keys.append({"key": key, "value": tgt_val})

            # 4. Check for placeholder / variable discrepancies
            src_placeholders = extract_placeholders(src_val)
            tgt_placeholders = extract_placeholders(tgt_val)
            if src_placeholders != tgt_placeholders:
                placeholder_mismatch.append({
                    "key": key,
                    "source_val": src_val,
                    "target_val": tgt_val,
                    "source_placeholders": src_placeholders,
                    "target_placeholders": tgt_placeholders
                })

        report["locales"][lang] = {
            "total_keys": len(target_keys),
            "missing_keys": [{"key": k, "source": source_dict.get(k, "")} for k in missing_keys],
            "extra_keys": [{"key": k, "value": target_dict.get(k, "")} for k in extra_keys],
            "empty_keys": empty_keys,
            "todo_keys": todo_keys,
            "identical_keys": identical_keys,
            "placeholder_mismatch": placeholder_mismatch,
        }

    # Audit Code References
    if scan_code:
        code_keys_map = scan_codebase_for_keys(project_root)
        used_keys = set(code_keys_map.keys())

        missing_in_source = sorted(list(used_keys - source_keys))
        unused_in_source = sorted(list(source_keys - used_keys))

        report["code_audit"] = {
            "total_code_keys_found": len(used_keys),
            "missing_in_source": [
                {"key": k, "locations": code_keys_map[k]} for k in missing_in_source
            ],
            "unused_in_source": unused_in_source
        }

    return report


def format_console_report(report):
    """Generates clean, readable ANSI-formatted console text."""
    lines = []
    lines.append("=" * 80)
    lines.append(" 🌐 OBSIDIAN SCOUT TRANSLATION AUDIT REPORT")
    lines.append("=" * 80)
    lines.append(f"Source Language : {report['source_lang']} ({report['source_key_count']} keys)")
    lines.append(f"Audited Locales : {', '.join(report['locales'].keys())}")
    lines.append("-" * 80)

    # Summary Table
    lines.append(f"{'Locale':<8} | {'Total':<7} | {'Missing':<8} | {'Empty':<7} | {'TODOs':<7} | {'Identical':<10} | {'Mismatches':<10} | {'Extra':<6}")
    lines.append("-" * 80)

    total_issues = 0
    for lang, data in report["locales"].items():
        missing = len(data["missing_keys"])
        empty = len(data["empty_keys"])
        todos = len(data["todo_keys"])
        identical = len(data["identical_keys"])
        mismatches = len(data["placeholder_mismatch"])
        extra = len(data["extra_keys"])
        total_issues += (missing + empty + todos + mismatches)

        lines.append(f"{lang:<8} | {data['total_keys']:<7} | {missing:<8} | {empty:<7} | {todos:<7} | {identical:<10} | {mismatches:<10} | {extra:<6}")

    lines.append("-" * 80)

    # Code Audit Summary
    if report.get("code_audit"):
        ca = report["code_audit"]
        lines.append(f"Codebase Audit: {ca['total_code_keys_found']} unique keys found in static files.")
        if ca["missing_in_source"]:
            lines.append(f" ⚠️  {len(ca['missing_in_source'])} key(s) used in code are MISSING from {report['source_lang']}.json:")
            for item in ca["missing_in_source"][:10]:
                lines.append(f"    - {item['key']} (in {', '.join(item['locations'][:2])})")
            if len(ca["missing_in_source"]) > 10:
                lines.append(f"    ... and {len(ca['missing_in_source']) - 10} more.")
        else:
            lines.append(" ✅ All keys referenced in code exist in source locale.")

    lines.append("-" * 80)

    # Detailed issues per locale
    for lang, data in report["locales"].items():
        has_items = any([
            data["missing_keys"], data["empty_keys"], data["todo_keys"],
            data["placeholder_mismatch"], data["extra_keys"]
        ])
        if not has_items:
            lines.append(f"[{lang.upper()}] ✅ Completely up to date with no detected issues.")
            continue

        lines.append(f"\n[{lang.upper()}] Details:")
        if data["missing_keys"]:
            lines.append(f"  ❌ Missing Keys ({len(data['missing_keys'])}):")
            for item in data["missing_keys"]:
                lines.append(f"    • \"{item['key']}\": \"{item['source']}\"")

        if data["empty_keys"]:
            lines.append(f"  ⚠️  Empty / Blank Keys ({len(data['empty_keys'])}):")
            for item in data["empty_keys"]:
                lines.append(f"    • \"{item['key']}\" (en: \"{item['source']}\")")

        if data["todo_keys"]:
            lines.append(f"  📝 Incomplete / TODO Keys ({len(data['todo_keys'])}):")
            for item in data["todo_keys"]:
                lines.append(f"    • \"{item['key']}\": \"{item['target']}\"")

        if data["placeholder_mismatch"]:
            lines.append(f"  ⚠️  Placeholder / Variable Mismatches ({len(data['placeholder_mismatch'])}):")
            for item in data["placeholder_mismatch"]:
                lines.append(f"    • \"{item['key']}\":")
                lines.append(f"        EN: \"{item['source_val']}\" -> {item['source_placeholders']}")
                lines.append(f"        {lang.upper()}: \"{item['target_val']}\" -> {item['target_placeholders']}")

        if data["extra_keys"]:
            lines.append(f"  🗑️  Obsolete / Extra Keys ({len(data['extra_keys'])}):")
            for item in data["extra_keys"]:
                lines.append(f"    • \"{item['key']}\": \"{item['value']}\"")

        if data["identical_keys"]:
            lines.append(f"  🔍 Potentially Untranslated (Identical to EN, {len(data['identical_keys'])} items, showing sample):")
            for item in data["identical_keys"][:8]:
                lines.append(f"    • \"{item['key']}\": \"{item['value']}\"")
            if len(data["identical_keys"]) > 8:
                lines.append(f"    ... and {len(data['identical_keys']) - 8} more.")

    lines.append("=" * 80)
    return "\n".join(lines)


def format_markdown_report(report):
    """Generates clean GitHub Flavored Markdown report."""
    md = []
    md.append("# 🌐 ObsidianScout Translation Audit Report\n")
    md.append(f"- **Source Language:** `{report['source_lang']}` ({report['source_key_count']} keys)")
    md.append(f"- **Target Locales:** {', '.join([f'`{l}`' for l in report['locales'].keys()])}\n")

    md.append("## Overview Summary\n")
    md.append("| Locale | Total Keys | Missing | Empty | TODOs | Identical to EN | Placeholder Mismatch | Extra / Obsolete |")
    md.append("|---|---|---|---|---|---|---|---|")
    for lang, data in report["locales"].items():
        md.append(
            f"| **{lang}** | {data['total_keys']} | {len(data['missing_keys'])} | "
            f"{len(data['empty_keys'])} | {len(data['todo_keys'])} | {len(data['identical_keys'])} | "
            f"{len(data['placeholder_mismatch'])} | {len(data['extra_keys'])} |"
        )
    md.append("")

    if report.get("code_audit"):
        ca = report["code_audit"]
        md.append("## Codebase Usage Audit\n")
        md.append(f"- Total unique i18n keys identified in HTML/JS: `{ca['total_code_keys_found']}`")
        if ca["missing_in_source"]:
            md.append(f"\n### ⚠️ Keys Used in Code but Missing from `{report['source_lang']}.json`\n")
            for item in ca["missing_in_source"]:
                locs = ", ".join([f"`{loc}`" for loc in item["locations"]])
                md.append(f"- `{item['key']}` (used in: {locs})")
        else:
            md.append("- ✅ All keys used in code are present in the source locale.")
        md.append("")

    md.append("## Detailed Locale Breakdown\n")
    for lang, data in report["locales"].items():
        md.append(f"### Locale: `{lang}`\n")
        if data["missing_keys"]:
            md.append(f"#### ❌ Missing Keys ({len(data['missing_keys'])})\n")
            md.append("```json")
            missing_dict = {item["key"]: item["source"] for item in data["missing_keys"]}
            md.append(json.dumps(missing_dict, ensure_ascii=False, indent=2))
            md.append("```\n")

        if data["empty_keys"]:
            md.append(f"#### ⚠️ Empty / Blank Keys ({len(data['empty_keys'])})\n")
            for item in data["empty_keys"]:
                md.append(f"- `{item['key']}` (Source EN: *\"{item['source']}\"*)")
            md.append("")

        if data["todo_keys"]:
            md.append(f"#### 📝 Incomplete / TODO Keys ({len(data['todo_keys'])})\n")
            for item in data["todo_keys"]:
                md.append(f"- `{item['key']}`: `{item['target']}` (Source EN: *\"{item['source']}\"*)")
            md.append("")

        if data["placeholder_mismatch"]:
            md.append(f"#### ⚠️ Variable / Placeholder Mismatches ({len(data['placeholder_mismatch'])})\n")
            for item in data["placeholder_mismatch"]:
                md.append(f"- **`{item['key']}`**:")
                md.append(f"  - English (`{report['source_lang']}`): `{item['source_val']}` (Placeholders: `{item['source_placeholders']}`)")
                md.append(f"  - Target (`{lang}`): `{item['target_val']}` (Placeholders: `{item['target_placeholders']}`)")
            md.append("")

        if data["extra_keys"]:
            md.append(f"#### 🗑️ Extra / Obsolete Keys ({len(data['extra_keys'])})\n")
            for item in data["extra_keys"]:
                md.append(f"- `{item['key']}`: *\"{item['value']}\"*")
            md.append("")

        if data["identical_keys"]:
            md.append(f"<details><summary><b>🔍 Identical to English Strings ({len(data['identical_keys'])} items)</b></summary>\n")
            for item in data["identical_keys"]:
                md.append(f"- `{item['key']}`: *\"{item['value']}\"*")
            md.append("\n</details>\n")

    return "\n".join(md)


def export_missing_patches(report, output_dir):
    """
    Exports clean JSON patch files containing all missing, empty, and TODO keys
    for each target locale, pre-filled with English source values.
    """
    out_path = Path(output_dir)
    out_path.mkdir(parents=True, exist_ok=True)

    exported_files = []
    for lang, data in report["locales"].items():
        patch_dict = {}
        # 1. Missing keys
        for item in data["missing_keys"]:
            patch_dict[item["key"]] = item["source"]
        # 2. Empty keys
        for item in data["empty_keys"]:
            patch_dict[item["key"]] = item["source"]
        # 3. TODO keys
        for item in data["todo_keys"]:
            patch_dict[item["key"]] = item["source"]

        if patch_dict:
            target_file = out_path / f"missing_{lang}.json"
            save_json(target_file, patch_dict)
            exported_files.append((lang, target_file, len(patch_dict)))

    return exported_files


def sync_missing_keys(i18n_dir, source_lang="en", placeholder_prefix="", remove_obsolete=False):
    """
    Directly updates target locale files by copying missing keys from source_lang.
    Optionally removes obsolete keys if remove_obsolete=True.
    """
    i18n_path = Path(i18n_dir)
    source_file = i18n_path / f"{source_lang}.json"
    source_dict = load_json(source_file)

    synced_stats = {}
    for p in sorted(list(i18n_path.glob("*.json"))):
        if p.stem == source_lang:
            continue
        target_dict = load_json(p)
        original_count = len(target_dict)
        added_count = 0
        removed_count = 0

        # Add missing keys
        for k, v in source_dict.items():
            if k not in target_dict:
                val = f"{placeholder_prefix}{v}" if placeholder_prefix else v
                target_dict[k] = val
                added_count += 1

        # Remove obsolete keys
        if remove_obsolete:
            for k in list(target_dict.keys()):
                if k not in source_dict:
                    del target_dict[k]
                    removed_count += 1

        save_json(p, target_dict, sort_keys=True)
        synced_stats[p.stem] = {
            "added": added_count,
            "removed": removed_count,
            "total": len(target_dict)
        }

    # Also sort source file
    save_json(source_file, source_dict, sort_keys=True)
    return synced_stats


def apply_patch_file(patch_file_path, target_lang, i18n_dir="src/main/resources/static/i18n"):
    """
    Merges keys from a patch file into the target locale JSON.
    """
    i18n_path = Path(i18n_dir)
    target_file = i18n_path / f"{target_lang}.json"
    if not target_file.exists():
        raise FileNotFoundError(f"Target locale file not found: {target_file}")

    patch_dict = load_json(patch_file_path)
    target_dict = load_json(target_file)

    updated_count = 0
    for k, v in patch_dict.items():
        target_dict[k] = v
        updated_count += 1

    save_json(target_file, target_dict, sort_keys=True)
    return updated_count


def sort_all_locales(i18n_dir):
    """Alphabetically sorts all JSON files in the i18n directory."""
    i18n_path = Path(i18n_dir)
    count = 0
    for p in i18n_path.glob("*.json"):
        data = load_json(p)
        save_json(p, data, sort_keys=True)
        count += 1
    return count


def main():
    parser = argparse.ArgumentParser(
        description="Audit, log, export, and sync untranslated or incomplete i18n translations for ObsidianScout."
    )
    parser.add_argument("--i18n-dir", default="src/main/resources/static/i18n",
                        help="Path to directory containing locale JSON files (default: src/main/resources/static/i18n)")
    parser.add_argument("--source-lang", default="en",
                        help="Base source language code (default: en)")
    parser.add_argument("--target-lang", nargs="*", default=None,
                        help="Specific target language(s) to check (default: all discovered JSON files)")
    parser.add_argument("--no-scan-code", action="store_true",
                        help="Skip scanning frontend codebase for key references")
    parser.add_argument("--log", "-o", metavar="FILE",
                        help="Write full markdown audit report to specified file (e.g. translation_report.md)")
    parser.add_argument("--json-log", metavar="FILE",
                        help="Write machine-readable audit report to specified JSON file")
    parser.add_argument("--export-missing", metavar="DIR",
                        help="Export missing/incomplete keys as patch templates into DIR (e.g. ./scratch/i18n_patches)")
    parser.add_argument("--sync", action="store_true",
                        help="Sync all missing keys from source language into target locale files with source text")
    parser.add_argument("--clean", action="store_true",
                        help="When used with --sync, removes obsolete keys not present in source language")
    parser.add_argument("--prefix", default="",
                        help="Prefix to prepend to synced values (e.g. '[TODO] ')")
    parser.add_argument("--apply-patch", metavar="FILE",
                        help="Apply a translated patch file to a locale")
    parser.add_argument("--lang", metavar="CODE",
                        help="Language code to use when applying a patch (e.g. es, he, tr)")
    parser.add_argument("--sort", action="store_true",
                        help="Alphabetically sort all keys in all locale JSON files")
    parser.add_argument("--strict", action="store_true",
                        help="Exit with code 1 if any missing keys, empty values, or placeholder errors exist")

    args = parser.parse_args()

    # Apply Patch mode
    if args.apply_patch:
        if not args.lang:
            print("Error: --lang is required when using --apply-patch (e.g. --lang es)", file=sys.stderr)
            sys.exit(1)
        count = apply_patch_file(args.apply_patch, args.lang, args.i18n_dir)
        print(f" Successfully applied {count} translated keys to {args.lang}.json")
        sys.exit(0)

    # Sort Mode
    if args.sort and not args.sync:
        sorted_files = sort_all_locales(args.i18n_dir)
        print(f"✨ Alphabetically sorted {sorted_files} locale files in {args.i18n_dir}")

    # Sync Mode
    if args.sync:
        print(f"🔄 Syncing missing keys from {args.source_lang}.json into target locales...")
        stats = sync_missing_keys(
            args.i18n_dir,
            source_lang=args.source_lang,
            placeholder_prefix=args.prefix,
            remove_obsolete=args.clean
        )
        for lang, s in stats.items():
            print(f"  • {lang}: +{s['added']} added, -{s['removed']} removed (total keys: {s['total']})")
        print("✅ Sync complete.\n")

    # Run Translation Audit
    report = audit_translations(
        i18n_dir=args.i18n_dir,
        source_lang=args.source_lang,
        target_langs=args.target_lang,
        scan_code=not args.no_scan_code,
        project_root="."
    )

    # Print Console Report
    console_output = format_console_report(report)
    print(console_output)

    # Export Missing Patches if requested
    if args.export_missing:
        patches = export_missing_patches(report, args.export_missing)
        if patches:
            print(f"\n📦 Exported {len(patches)} patch template file(s) to: {args.export_missing}")
            for lang, fpath, count in patches:
                print(f"  • {lang}: {fpath} ({count} keys)")
        else:
            print(f"\n📦 No missing keys to export. All locales are complete!")

    # Write Markdown Log if requested
    if args.log:
        md_content = format_markdown_report(report)
        log_path = Path(args.log)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, 'w', encoding='utf-8') as f:
            f.write(md_content)
        print(f"\n📝 Detailed Markdown report saved to: {args.log}")

    # Write JSON Log if requested
    if args.json_log:
        json_path = Path(args.json_log)
        json_path.parent.mkdir(parents=True, exist_ok=True)
        save_json(json_path, report)
        print(f"📊 Detailed JSON audit log saved to: {args.json_log}")

    # Strict mode exit code for CI/CD
    if args.strict:
        has_critical = False
        for lang, data in report["locales"].items():
            if data["missing_keys"] or data["empty_keys"] or data["placeholder_mismatch"]:
                has_critical = True
                break
        if report.get("code_audit") and report["code_audit"]["missing_in_source"]:
            has_critical = True

        if has_critical:
            print("\n❌ [STRICT CHECK FAILED] Missing or incomplete translations found.")
            sys.exit(1)
        else:
            print("\n✅ [STRICT CHECK PASSED] All translations valid.")


if __name__ == "__main__":
    main()
