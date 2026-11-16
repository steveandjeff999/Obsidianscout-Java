/*
 * Power BI export: an Excel workbook (.xlsx) holding one flat Excel table, which Power BI's
 * "Excel workbook" connector lists by name and loads with typed columns.
 *  - one column per scouting field (no packed "Details" text)
 *  - numbers, true/false and date/times stored as real Excel types, text as text
 *  - the data is a named Excel table, so Power BI picks it up with headers already promoted
 * The workbook is written here directly (a small uncompressed zip of the XML parts) so no
 * library has to be loaded.
 */
(function () {
    const PHOTO_TYPES = new Set(["image", "image_upload", "photo"]);
    const NUMBER_TYPES = new Set(["number", "counter", "rating"]);
    const MAX_CELL_TEXT = 32767; // Excel's per-cell limit

    function localize(label) {
        if (window.Obsidianscout && typeof Obsidianscout.localize === "function") {
            return Obsidianscout.localize(label);
        }
        return label == null ? "" : String(label);
    }

    function isPhotoValue(value) {
        return typeof value === "string" && (value.startsWith("data:image/") || value.startsWith("/api/blobs/"));
    }

    /** Column type a scouting field exports as. */
    function fieldColumnType(field) {
        const type = String((field && field.type) || "").toLowerCase();
        if (type === "checkbox") return "boolean";
        if (NUMBER_TYPES.has(type)) return "number";
        return "text";
    }

    function photoLink(value) {
        if (typeof value !== "string") return "";
        if (value.startsWith("/api/blobs/")) return window.location.origin + value;
        return ""; // inline data: images are far too large for a table cell
    }

    /** A field's raw stored value as the typed value Power BI should see. */
    function fieldValue(field, raw) {
        if (raw === null || raw === undefined || raw === "") return null;
        const type = String((field && field.type) || "").toLowerCase();

        if (PHOTO_TYPES.has(type) || isPhotoValue(raw)) {
            const list = Array.isArray(raw) ? raw : [raw];
            return list.map(photoLink).filter(Boolean).join(" ") || null;
        }
        if (type === "checkbox") {
            return raw === true || raw === "true" || raw === 1 || raw === "1";
        }
        if (NUMBER_TYPES.has(type)) {
            const num = Number(raw);
            return Number.isFinite(num) ? num : null;
        }
        if (type === "select") {
            const opt = (field.options || []).find((o) =>
                typeof o === "string" ? o === raw : (o.value === raw || o.label === raw));
            if (opt) return typeof opt === "string" ? opt : localize(opt.label);
        }
        if (Array.isArray(raw)) return raw.map((v) => (typeof v === "object" ? JSON.stringify(v) : String(v))).join("; ");
        if (typeof raw === "object") return JSON.stringify(raw);
        return String(raw);
    }

    /** Fields worth a column: everything except section headers and the given reserved ids. */
    function exportableFields(fields, reservedIds) {
        const reserved = reservedIds instanceof Set ? reservedIds : new Set(reservedIds || []);
        return (fields || []).filter((f) => f && f.id && f.type !== "section" && !reserved.has(f.id));
    }

    /** True when a match field can award points, so it gets a "<label> Points" column. */
    function fieldScores(field) {
        const type = String((field && field.type) || "").toLowerCase();
        if (type === "select") {
            return (field.options || []).some((o) => o && typeof o === "object" && Number(o.points));
        }
        return (NUMBER_TYPES.has(type) || type === "checkbox") && Number(field.pointsPer || 0) !== 0;
    }

    function pad(n) {
        return String(n).padStart(2, "0");
    }

    function todayStamp() {
        const d = new Date();
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    }

    /** Excel date serial (days since 1899-12-30) for the value's local wall-clock time. */
    function excelDate(value) {
        if (!value) return null;
        const d = value instanceof Date ? value : new Date(value);
        if (Number.isNaN(d.getTime())) return null;
        const wallClock = Date.UTC(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds());
        return (wallClock - Date.UTC(1899, 11, 30)) / 86400000;
    }

    /** Makes header names unique, since Excel tables and Power BI reject duplicate column names. */
    function uniqueNames(columns) {
        const seen = new Map();
        return columns.map((col) => {
            const base = String(col.name || "Column").replace(/[\r\n\t]+/g, " ").trim() || "Column";
            const count = seen.get(base.toLowerCase()) || 0;
            seen.set(base.toLowerCase(), count + 1);
            return count ? `${base} (${count + 1})` : base;
        });
    }

    function safeFilePart(text) {
        return String(text || "all").replace(/[^A-Za-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "") || "all";
    }

    // ---------- xlsx writing ----------

    function xmlEscape(text) {
        return String(text)
            // characters XML 1.0 cannot hold at all
            .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function columnLetter(index) {
        let n = index + 1;
        let out = "";
        while (n > 0) {
            const rem = (n - 1) % 26;
            out = String.fromCharCode(65 + rem) + out;
            n = Math.floor((n - 1) / 26);
        }
        return out;
    }

    function textCell(ref, value) {
        let text = String(value);
        if (text.length > MAX_CELL_TEXT) text = text.slice(0, MAX_CELL_TEXT);
        return `<c r="${ref}" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(text)}</t></is></c>`;
    }

    function cellXml(ref, value, type) {
        if (value === null || value === undefined || value === "") return "";
        if (type === "number") {
            const num = Number(value);
            return Number.isFinite(num) ? `<c r="${ref}"><v>${num}</v></c>` : "";
        }
        if (type === "boolean") {
            if (typeof value === "boolean") return `<c r="${ref}" t="b"><v>${value ? 1 : 0}</v></c>`;
            return textCell(ref, value);
        }
        if (type === "datetime") {
            const serial = excelDate(value);
            return serial === null ? "" : `<c r="${ref}" s="1"><v>${serial}</v></c>`;
        }
        return textCell(ref, value);
    }

    function sheetXml(headers, columns, rows) {
        const lastCol = columnLetter(Math.max(columns.length - 1, 0));
        const lastRow = rows.length + 1;
        const out = [];
        out.push('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>');
        out.push('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">');
        out.push(`<dimension ref="A1:${lastCol}${lastRow}"/>`);
        out.push('<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>');
        out.push('<cols>');
        columns.forEach((col, i) => {
            const width = col.type === "datetime" ? 20 : Math.min(Math.max(headers[i].length + 4, 12), 45);
            out.push(`<col min="${i + 1}" max="${i + 1}" width="${width}" customWidth="1"/>`);
        });
        out.push('</cols><sheetData>');
        out.push('<row r="1">' + headers.map((h, i) => textCell(`${columnLetter(i)}1`, h)).join("") + '</row>');
        rows.forEach((row, r) => {
            const rowNum = r + 2;
            const cells = columns.map((col, i) => cellXml(`${columnLetter(i)}${rowNum}`, row[i], col.type)).join("");
            out.push(`<row r="${rowNum}">${cells}</row>`);
        });
        out.push('</sheetData><tableParts count="1"><tablePart r:id="rId1"/></tableParts></worksheet>');
        return out.join("");
    }

    function tableXml(tableName, headers, rowCount) {
        const ref = `A1:${columnLetter(Math.max(headers.length - 1, 0))}${rowCount + 1}`;
        const cols = headers.map((h, i) => `<tableColumn id="${i + 1}" name="${xmlEscape(h)}"/>`).join("");
        return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            `<table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="1" name="${tableName}" displayName="${tableName}" ref="${ref}" totalsRowShown="0">` +
            `<autoFilter ref="${ref}"/><tableColumns count="${headers.length}">${cols}</tableColumns>` +
            '<tableStyleInfo name="TableStyleMedium2" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/></table>';
    }

    const STATIC_PARTS = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
            '<Default Extension="xml" ContentType="application/xml"/>' +
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>' +
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' +
            '<Override PartName="/xl/tables/table1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/>' +
            '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' +
            '</Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>' +
            '</Relationships>',
        "xl/_rels/workbook.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>' +
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' +
            '</Relationships>',
        "xl/worksheets/_rels/sheet1.xml.rels": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table1.xml"/>' +
            '</Relationships>',
        // Style 1 is a date + time format so date columns show as dates and Power BI types them as datetime.
        "xl/styles.xml": '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">' +
            '<numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm:ss"/></numFmts>' +
            '<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>' +
            '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>' +
            '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>' +
            '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>' +
            '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>' +
            '<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs>' +
            '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>' +
            '</styleSheet>'
    };

    function workbookXml(sheetName) {
        return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">' +
            `<sheets><sheet name="${xmlEscape(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>`;
    }

    // ---------- zip (stored, no compression) ----------

    const CRC_TABLE = (() => {
        const table = new Uint32Array(256);
        for (let n = 0; n < 256; n++) {
            let c = n;
            for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            table[n] = c >>> 0;
        }
        return table;
    })();

    function crc32(bytes) {
        let crc = 0xFFFFFFFF;
        for (let i = 0; i < bytes.length; i++) crc = CRC_TABLE[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
        return (crc ^ 0xFFFFFFFF) >>> 0;
    }

    function zip(files) {
        const encoder = new TextEncoder();
        const chunks = [];
        const central = [];
        let offset = 0;
        const now = new Date();
        const dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | Math.floor(now.getSeconds() / 2);
        const dosDate = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();

        files.forEach(({ name, content }) => {
            const nameBytes = encoder.encode(name);
            const data = encoder.encode(content);
            const crc = crc32(data);

            const local = new DataView(new ArrayBuffer(30));
            local.setUint32(0, 0x04034b50, true);
            local.setUint16(4, 20, true);
            local.setUint16(6, 0x0800, true); // UTF-8 names
            local.setUint16(8, 0, true); // stored
            local.setUint16(10, dosTime, true);
            local.setUint16(12, dosDate, true);
            local.setUint32(14, crc, true);
            local.setUint32(18, data.length, true);
            local.setUint32(22, data.length, true);
            local.setUint16(26, nameBytes.length, true);
            local.setUint16(28, 0, true);
            chunks.push(new Uint8Array(local.buffer), nameBytes, data);

            const entry = new DataView(new ArrayBuffer(46));
            entry.setUint32(0, 0x02014b50, true);
            entry.setUint16(4, 20, true);
            entry.setUint16(6, 20, true);
            entry.setUint16(8, 0x0800, true);
            entry.setUint16(10, 0, true);
            entry.setUint16(12, dosTime, true);
            entry.setUint16(14, dosDate, true);
            entry.setUint32(16, crc, true);
            entry.setUint32(20, data.length, true);
            entry.setUint32(24, data.length, true);
            entry.setUint16(28, nameBytes.length, true);
            entry.setUint32(42, offset, true);
            central.push(new Uint8Array(entry.buffer), nameBytes);

            offset += 30 + nameBytes.length + data.length;
        });

        const centralSize = central.reduce((sum, part) => sum + part.length, 0);
        const end = new DataView(new ArrayBuffer(22));
        end.setUint32(0, 0x06054b50, true);
        end.setUint16(8, files.length, true);
        end.setUint16(10, files.length, true);
        end.setUint32(12, centralSize, true);
        end.setUint32(16, offset, true);

        return new Blob(chunks.concat(central, [new Uint8Array(end.buffer)]), {
            type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        });
    }

    /** The workbook as a Blob. tableName must be a letters/digits/underscore name. */
    function buildWorkbook(tableName, columns, rows) {
        const headers = uniqueNames(columns);
        const safeTable = String(tableName || "ScoutingData").replace(/[^A-Za-z0-9_]/g, "_").replace(/^[^A-Za-z_]/, "_");
        const files = [
            { name: "[Content_Types].xml", content: STATIC_PARTS["[Content_Types].xml"] },
            { name: "_rels/.rels", content: STATIC_PARTS["_rels/.rels"] },
            { name: "xl/workbook.xml", content: workbookXml(safeTable.slice(0, 31)) },
            { name: "xl/_rels/workbook.xml.rels", content: STATIC_PARTS["xl/_rels/workbook.xml.rels"] },
            { name: "xl/styles.xml", content: STATIC_PARTS["xl/styles.xml"] },
            { name: "xl/worksheets/sheet1.xml", content: sheetXml(headers, columns, rows) },
            { name: "xl/worksheets/_rels/sheet1.xml.rels", content: STATIC_PARTS["xl/worksheets/_rels/sheet1.xml.rels"] },
            { name: "xl/tables/table1.xml", content: tableXml(safeTable, headers, rows.length) }
        ];
        return zip(files);
    }

    /**
     * Downloads the table as a Power BI ready Excel workbook.
     * columns: [{ name, type: "text" | "number" | "boolean" | "datetime" }]
     * rows: arrays of raw values in column order.
     * tableName: the Excel table (and sheet) name Power BI will list, e.g. "MatchData".
     */
    function download(baseName, columns, rows, tableName) {
        const blob = buildWorkbook(tableName, columns, rows);
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = `${safeFilePart(baseName)}-powerbi-${todayStamp()}.xlsx`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    window.ObsidianscoutPowerBI = {
        download,
        buildWorkbook,
        fieldValue,
        fieldColumnType,
        exportableFields,
        fieldScores,
        localize,
        safeFilePart
    };
})();
