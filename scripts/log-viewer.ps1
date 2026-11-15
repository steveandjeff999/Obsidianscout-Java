<#
.SYNOPSIS
    Viewer for the ObsidianScout server's console log.

.DESCRIPTION
    The server keeps a copy of everything it prints in logs\console-NNNNNN.log (see ConsoleLogFile.kt), the newest
    5 GB of it. This window lists each run of the server with how it ended, shows and filters the output, and helps
    find out why a run stopped: its last output, memory use before the end, the most frequent errors, and what
    Windows itself recorded at that moment (restarts, power loss, application faults).

    Runs on Windows PowerShell 5.1 and PowerShell 7. Keep this file plain ASCII: Windows PowerShell reads a script
    without a byte order mark as ANSI.

.PARAMETER LogDir
    Folder holding the console-*.log files. Defaults to the logs folder next to this script (or one level up).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File log-viewer.ps1
    powershell -ExecutionPolicy Bypass -File log-viewer.ps1 -LogDir D:\obsidianscout\logs
#>
[CmdletBinding()]
param([string]$LogDir)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

# ---------------------------------------------------------------------------------------------------------------
# The engine. Indexing and searching gigabytes line by line is far too slow in PowerShell itself, so that part is
# C#, kept to what the C# 5 compiler in Windows PowerShell 5.1 accepts. It has no user interface of its own: long
# jobs run on a background thread and the window polls them.
#
# Line format (written by RollingLogFile): "yyyy-MM-dd HH:mm:ss.fff M text", where M is | for stdout, ! for stderr
# and # for the logger's own records (start, heartbeat, shutdown...).
# ---------------------------------------------------------------------------------------------------------------
$engine = @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace ObsidianLogViewer
{
    public static class Kind
    {
        public const int Other = 0;
        public const int Debug = 1;
        public const int Info = 2;
        public const int Warn = 3;
        public const int Error = 4;
        public const int Stack = 5;      // stack trace lines under an error or warning
        public const int Meta = 6;       // the logger's own records
        public const int Heartbeat = 7;
        public const int Marker = 8;     // where a run starts and where it shut down
        public const int All = 511;      // one bit for each of the above
    }

    public sealed class LogLine
    {
        public string Raw = "";    // the line as stored
        public string Time = "";   // empty when the line carries no timestamp
        public char Mark = ' ';
        public int Kind;
        public string Text = "";   // without the timestamp, safe to draw
    }

    /// <summary>A stretch of one file that belongs to a single run of the server.</summary>
    public sealed class Part
    {
        public int StartLine;
        public bool Begins;        // opens with a start record, rather than carrying on from the file before
        public string StartText = "", FirstTime = "", LastTime = "", ShutdownTime = "";
        public string Version = "", PreviousRun = "", JvmArgs = "", Heartbeat = "", HeartbeatTime = "";
        public int Errors, Warnings;
        public long PeakHeapMB;
        public Part Clone() { return (Part)MemberwiseClone(); }
    }

    public sealed class Segment
    {
        public string Path = "", Name = "";
        public long Scanned;       // bytes indexed so far; always just past a line end
        public int Lines;
        public string FirstTime = "", LastTime = "";
        public List<long> Checkpoints = new List<long>();   // where every Stride-th line starts
        public List<Part> Parts = new List<Part>();

        public Segment Clone()
        {
            Segment copy = (Segment)MemberwiseClone();
            copy.Checkpoints = new List<long>(Checkpoints);
            copy.Parts = new List<Part>();
            foreach (Part part in Parts) copy.Parts.Add(part.Clone());
            return copy;
        }
    }

    /// <summary>One run of the server, from its start record to the line before the next one.</summary>
    public sealed class Session
    {
        public int Number, FirstLine, EndLine;
        public bool HasStart;
        public string StartText = "", StartTime = "", LastTime = "", ShutdownTime = "";
        public string Version = "", PreviousRun = "", JvmArgs = "", Heartbeat = "", HeartbeatTime = "";
        public string ExitCode = "";   // as reported by the run after this one
        public int Errors, Warnings;
        public long PeakHeapMB;
    }

    /// <summary>Everything known about the folder at one moment. Never changed once built, so the window can keep
    /// reading one while the next is being made.</summary>
    public sealed class Snapshot
    {
        public Segment[] Segments = new Segment[0];
        public int[] First = new int[0];   // number of the first line of each segment, counting from the oldest kept
        public Session[] Sessions = new Session[0];
        public int TotalLines;
        public long TotalBytes;
        public string FirstTime = "", LastTime = "";

        public int SegmentOf(int line)
        {
            int lo = 0, hi = Segments.Length - 1;
            while (lo < hi)
            {
                int mid = (lo + hi + 1) / 2;
                if (First[mid] <= line) lo = mid; else hi = mid - 1;
            }
            return lo;
        }
    }

    /// <summary>The rows on screen: a run of consecutive lines, or the lines a filter picked.</summary>
    public sealed class LineView
    {
        public int First, Count;
        public int[] Index;

        public static LineView Range(int first, int count)
        {
            LineView view = new LineView();
            view.First = first;
            view.Count = Math.Max(0, count);
            return view;
        }

        public static LineView Of(int[] lines)
        {
            LineView view = new LineView();
            view.Index = lines;
            view.Count = lines.Length;
            return view;
        }

        public int LineAt(int row)
        {
            if (row < 0 || row >= Count) return -1;
            return Index == null ? First + row : Index[row];
        }

        /// <summary>The row showing the line, or the nearest one after it.</summary>
        public int RowOf(int line)
        {
            if (Count == 0) return -1;
            if (Index == null) return Math.Max(0, Math.Min(Count - 1, line - First));
            int lo = 0, hi = Count - 1;
            while (lo < hi)
            {
                int mid = (lo + hi) / 2;
                if (Index[mid] < line) lo = mid + 1; else hi = mid;
            }
            return lo;
        }

        /// <summary>Renumbers after the oldest lines were deleted from disk.</summary>
        public void Shift(int dropped)
        {
            if (dropped <= 0 || Index == null) return;
            List<int> kept = new List<int>(Index.Length);
            foreach (int line in Index) if (line >= dropped) kept.Add(line - dropped);
            Index = kept.ToArray();
            Count = Index.Length;
        }

        public void Append(int[] more)
        {
            if (Index == null || more.Length == 0) return;
            int[] joined = new int[Index.Length + more.Length];
            Array.Copy(Index, joined, Index.Length);
            Array.Copy(more, 0, joined, Index.Length, more.Length);
            Index = joined;
            Count = joined.Length;
        }
    }

    public sealed class Query
    {
        public int FirstLine, EndLine;
        public int KindMask = Kind.All;    // one bit per Kind
        public string Text = "";
        public bool UseRegex;
        public string FromTime = "", ToTime = "";   // any leading part of a timestamp, compared as text
        public int MaxResults = 4000000;
    }

    public sealed class SearchResult
    {
        public int[] Lines = new int[0];
        public bool Truncated;
    }

    public sealed class ErrorGroup
    {
        public int Count, Kind, LastLine;
        public string First = "", Last = "", Message = "";
    }

    public sealed class Job
    {
        public volatile bool Done;
        public volatile bool Cancel;
        public volatile int Percent;
        public string Error = "";
        public object Result;
    }

    internal delegate object Work(Job job);
    internal delegate bool LineSink(byte[] buffer, int start, int end, long offset);
    internal delegate bool LineVisitor(byte[] buffer, int start, int end, int line);

    internal sealed class Progress
    {
        readonly Job job;
        readonly long total;
        long done;
        public Progress(Job job, long total) { this.job = job; this.total = Math.Max(1, total); }
        public void Add(long bytes) { done += bytes; job.Percent = (int)Math.Min(100, done * 100 / total); }
    }

    internal static class Bytes
    {
        public const int PrefixLength = 26;
        public static readonly Encoding Utf8 = new UTF8Encoding(false, false);

        public static bool HasPrefix(byte[] b, int s, int e)
        {
            return e - s >= PrefixLength && b[s + 4] == '-' && b[s + 7] == '-' && b[s + 10] == ' ' && b[s + 13] == ':'
                && b[s + 16] == ':' && b[s + 19] == '.' && b[s + 23] == ' ' && b[s + 25] == ' '
                && (b[s + 24] == '|' || b[s + 24] == '!' || b[s + 24] == '#');
        }

        public static bool StartsWith(byte[] b, int s, int e, string ascii)
        {
            if (e - s < ascii.Length) return false;
            for (int i = 0; i < ascii.Length; i++) if (b[s + i] != ascii[i]) return false;
            return true;
        }

        public static bool EndsWith(byte[] b, int s, int e, string ascii)
        {
            return e - s >= ascii.Length && StartsWith(b, e - ascii.Length, e, ascii);
        }

        /// <summary>Works out what kind of line this is from its text alone (s is where the text starts, after the
        /// timestamp), so any line can be judged without reading the ones around it.</summary>
        public static int Classify(byte[] b, int s, int e, byte mark)
        {
            if (mark == '#')
            {
                if (StartsWith(b, s, e, "HEARTBEAT")) return Kind.Heartbeat;
                return StartsWith(b, s, e, "==== SERVER ") ? Kind.Marker : Kind.Meta;
            }
            // Lines from the logger read "HH:mm:ss LEVEL logger - message".
            if (e - s >= 14 && b[s + 2] == ':' && b[s + 5] == ':' && b[s + 8] == ' ')
            {
                int level = s + 9;
                if (StartsWith(b, level, e, "ERROR")) return Kind.Error;
                if (StartsWith(b, level, e, "WARN")) return Kind.Warn;
                if (StartsWith(b, level, e, "INFO")) return Kind.Info;
                if (StartsWith(b, level, e, "DEBUG") || StartsWith(b, level, e, "TRACE")) return Kind.Debug;
            }
            int t = s;
            while (t < e && (b[t] == ' ' || b[t] == 9)) t++;
            if (t > s && (StartsWith(b, t, e, "at ") || StartsWith(b, t, e, "... "))) return Kind.Stack;
            if (StartsWith(b, t, e, "Caused by:") || StartsWith(b, t, e, "Suppressed:")) return Kind.Stack;
            if (mark == '!') return Kind.Error;
            if (StartsWith(b, s, e, "Exception in thread") || StartsWith(b, s, e, "[UncaughtException]") || StartsWith(b, s, e, "[OOM-Guard]")) return Kind.Error;
            // "java.lang.IllegalStateException: ..." on stdout is the exception under a logged error or warning.
            int w = s;
            bool dotted = false;
            while (w < e && b[w] != ' ' && b[w] != ':') { if (b[w] == '.') dotted = true; w++; }
            if (dotted && (EndsWith(b, s, w, "Exception") || EndsWith(b, s, w, "Error"))) return Kind.Stack;
            return Kind.Other;
        }

        /// <summary>Case-insensitive for ASCII letters; needle must already be lower case.</summary>
        public static bool Contains(byte[] b, int s, int e, byte[] needle)
        {
            int n = needle.Length;
            byte first = needle[0];
            for (int i = s; i <= e - n; i++)
            {
                byte c = b[i];
                if (c >= 'A' && c <= 'Z') c += 32;
                if (c != first) continue;
                int k = 1;
                for (; k < n; k++)
                {
                    byte d = b[i + k];
                    if (d >= 'A' && d <= 'Z') d += 32;
                    if (d != needle[k]) break;
                }
                if (k == n) return true;
            }
            return false;
        }

        public static bool InTime(byte[] b, int s, string from, string to)
        {
            return Compare(b, s, from) >= 0 && Compare(b, s, to) <= 0;
        }

        // Compares a line's timestamp with the leading part of one the user typed ("2026-11-14 02" covers that hour).
        static int Compare(byte[] b, int s, string bound)
        {
            int n = Math.Min(bound.Length, 23);
            for (int i = 0; i < n; i++)
            {
                int diff = b[s + i] - bound[i];
                if (diff != 0) return diff;
            }
            return 0;
        }

        public static LogLine Parse(byte[] b, int s, int e, int maxBytes)
        {
            if (e > s && b[e - 1] == 13) e--;
            LogLine line = new LogLine();
            int text = s;
            byte mark = (byte)' ';
            if (HasPrefix(b, s, e))
            {
                line.Time = Encoding.ASCII.GetString(b, s, 23);
                mark = b[s + 24];
                text = s + PrefixLength;
            }
            line.Mark = (char)mark;
            line.Kind = Classify(b, text, e, mark);
            line.Raw = Utf8.GetString(b, s, Math.Min(e - s, maxBytes));
            line.Text = Clean(Utf8.GetString(b, text, Math.Min(e - text, maxBytes)));
            return line;
        }

        /// <summary>Tabs become spaces, colour codes go, and other control characters turn into spaces.</summary>
        static string Clean(string text)
        {
            bool plain = true;
            foreach (char c in text) if (c < ' ' || c == 127) { plain = false; break; }
            if (plain) return text;
            StringBuilder sb = new StringBuilder(text.Length + 16);
            for (int i = 0; i < text.Length; i++)
            {
                char c = text[i];
                if (c == '\t') sb.Append("    ");
                else if (c == 27 && i + 1 < text.Length && text[i + 1] == '[')
                {
                    i += 2;
                    while (i < text.Length && !char.IsLetter(text[i])) i++;
                }
                else if (c < ' ' || c == 127) sb.Append(' ');
                else sb.Append(c);
            }
            return sb.ToString();
        }

        /// <summary>Reads key=value or key="value" out of one of the logger's records.</summary>
        public static string Value(string record, string key)
        {
            int at = record.IndexOf(key + "=", StringComparison.Ordinal);
            while (at > 0 && record[at - 1] != ' ') at = record.IndexOf(key + "=", at + 1, StringComparison.Ordinal);
            if (at < 0) return "";
            int from = at + key.Length + 1;
            if (from < record.Length && record[from] == '"')
            {
                int close = record.IndexOf('"', from + 1);
                return close < 0 ? record.Substring(from + 1) : record.Substring(from + 1, close - from - 1);
            }
            int space = record.IndexOf(' ', from);
            return space < 0 ? record.Substring(from) : record.Substring(from, space - from);
        }
    }

    public sealed class LogStore
    {
        public const int Stride = 256;
        const int MaxLineBytes = 64 * 1024;
        const int MaxBlockBytes = 32 * 1024 * 1024;
        const int MaxBufferBytes = 256 * 1024 * 1024;
        const string StartMarker = "==== SERVER START ====";
        const string ShutdownMarker = "==== SERVER SHUTDOWN ====";

        public string Directory = "";
        public volatile Snapshot Current = new Snapshot();
        readonly Dictionary<string, LogLine[]> cache = new Dictionary<string, LogLine[]>();

        /// <summary>Reads key=value or key="value" out of one of the logger's records.</summary>
        public static string Value(string record, string key) { return Bytes.Value(record, key); }

        // ---- jobs --------------------------------------------------------------------------------------------

        public Job BeginRefresh() { return Run(delegate(Job job) { return Refresh(job); }); }

        public Job BeginSearch(Snapshot snap, Query query) { return Run(delegate(Job job) { return Search(snap, query, job); }); }

        public Job BeginSummary(Snapshot snap, int first, int end) { return Run(delegate(Job job) { return Summarize(snap, first, end, job); }); }

        static Job Run(Work work)
        {
            Job job = new Job();
            Thread thread = new Thread(delegate()
            {
                try { job.Result = work(job); }
                catch (Exception ex) { job.Error = ex.Message; }
                job.Done = true;
            });
            thread.IsBackground = true;
            thread.Start();
            return job;
        }

        // ---- indexing ----------------------------------------------------------------------------------------

        static long Sequence(string name)
        {
            // console-000123.log
            if (!name.StartsWith("console-", StringComparison.OrdinalIgnoreCase) || !name.EndsWith(".log", StringComparison.OrdinalIgnoreCase)) return -1;
            long seq;
            return long.TryParse(name.Substring(8, name.Length - 12), out seq) ? seq : -1;
        }

        static FileStream Open(string path)
        {
            // The server may be writing this file, or about to delete it: share everything.
            return new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 16, FileOptions.SequentialScan);
        }

        Snapshot Refresh(Job job)
        {
            Snapshot old = Current;
            Dictionary<string, Segment> known = new Dictionary<string, Segment>(StringComparer.OrdinalIgnoreCase);
            foreach (Segment segment in old.Segments) known[segment.Name] = segment;

            List<FileInfo> files = new List<FileInfo>();
            DirectoryInfo dir = new DirectoryInfo(Directory);
            if (dir.Exists)
            {
                foreach (FileInfo file in dir.GetFiles("console-*.log")) if (Sequence(file.Name) >= 0) files.Add(file);
            }
            files.Sort(delegate(FileInfo a, FileInfo b) { return Sequence(a.Name).CompareTo(Sequence(b.Name)); });

            long todo = 0;
            foreach (FileInfo file in files)
            {
                Segment have;
                known.TryGetValue(file.Name, out have);
                todo += Math.Max(0, file.Length - (have == null ? 0 : have.Scanned));
            }
            Progress progress = new Progress(job, todo);

            List<Segment> segments = new List<Segment>();
            for (int i = 0; i < files.Count; i++)
            {
                if (job.Cancel) return old;
                FileInfo file = files[i];
                Segment prev;
                known.TryGetValue(file.Name, out prev);
                // A finished file's size on record can be trusted. The newest is still being written, and Windows
                // only updates its recorded size now and then, so that one is always opened to see how long it is.
                if (prev != null && i < files.Count - 1 && file.Length == prev.Scanned)
                {
                    segments.Add(prev);
                    continue;
                }
                Segment seg = prev != null ? prev.Clone() : NewSegment(file);
                try
                {
                    using (FileStream fs = Open(file.FullName))
                    {
                        long length = fs.Length;
                        if (length < seg.Scanned) seg = NewSegment(file);
                        if (length > seg.Scanned) Scan(seg, fs, length, job, progress);
                    }
                }
                catch (IOException) { if (prev == null) continue; seg = prev; }
                catch (UnauthorizedAccessException) { if (prev == null) continue; seg = prev; }
                segments.Add(seg);
            }
            if (job.Cancel) return old;
            Snapshot snap = Build(segments);
            Current = snap;
            return snap;
        }

        static Segment NewSegment(FileInfo file)
        {
            Segment seg = new Segment();
            seg.Path = file.FullName;
            seg.Name = file.Name;
            return seg;
        }

        static void Scan(Segment seg, FileStream fs, long length, Job job, Progress progress)
        {
            Part part = seg.Parts.Count > 0 ? seg.Parts[seg.Parts.Count - 1] : null;
            byte[] time = new byte[23];
            bool timed = false;
            LineSink sink = delegate(byte[] b, int s, int e, long offset)
            {
                if (e > s && b[e - 1] == 13) e--;
                if (seg.Lines % Stride == 0) seg.Checkpoints.Add(offset);
                bool prefixed = Bytes.HasPrefix(b, s, e);
                byte mark = prefixed ? b[s + 24] : (byte)' ';
                int text = prefixed ? s + Bytes.PrefixLength : s;
                int kind = Bytes.Classify(b, text, e, mark);
                bool starts = kind == Kind.Marker && Bytes.StartsWith(b, text, e, StartMarker);
                // The line before a start record is the last one of the run before it.
                if (starts && part != null && timed) part.LastTime = Encoding.ASCII.GetString(time);
                if (prefixed)
                {
                    Buffer.BlockCopy(b, s, time, 0, 23);
                    timed = true;
                    if (seg.FirstTime.Length == 0) seg.FirstTime = Encoding.ASCII.GetString(time);
                }
                if (starts || part == null)
                {
                    part = new Part();
                    part.Begins = starts;
                    part.StartLine = seg.Lines;
                    if (prefixed) part.FirstTime = Encoding.ASCII.GetString(time);
                    if (starts) part.StartText = Bytes.Utf8.GetString(b, text, e - text);
                    seg.Parts.Add(part);
                }
                if (kind == Kind.Marker)
                {
                    if (Bytes.StartsWith(b, text, e, ShutdownMarker)) part.ShutdownTime = Encoding.ASCII.GetString(time);
                }
                else if (kind == Kind.Meta)
                {
                    if (Bytes.StartsWith(b, text, e, "VERSION ")) part.Version = Bytes.Utf8.GetString(b, text + 8, e - text - 8);
                    else if (Bytes.StartsWith(b, text, e, "PREVIOUS RUN ")) part.PreviousRun = Bytes.Utf8.GetString(b, text, e - text);
                    else if (Bytes.StartsWith(b, text, e, "JVM ARGS ")) part.JvmArgs = Bytes.Utf8.GetString(b, text + 9, e - text - 9);
                }
                else if (kind == Kind.Heartbeat)
                {
                    part.Heartbeat = Bytes.Utf8.GetString(b, text, e - text);
                    part.HeartbeatTime = Encoding.ASCII.GetString(time);
                    long heap;
                    if (long.TryParse(Bytes.Value(part.Heartbeat, "heapUsedMB"), out heap) && heap > part.PeakHeapMB) part.PeakHeapMB = heap;
                }
                else if (kind == Kind.Error) part.Errors++;
                else if (kind == Kind.Warn) part.Warnings++;
                seg.Lines++;
                return true;
            };
            seg.Scanned = ReadLines(fs, seg.Scanned, length, job, progress, sink);
            if (timed)
            {
                seg.LastTime = Encoding.ASCII.GetString(time);
                if (part != null) part.LastTime = seg.LastTime;
            }
        }

        /// <summary>Hands every complete line between two offsets to the sink (without its line feed) and returns
        /// the offset just past the last one it took.</summary>
        static long ReadLines(FileStream fs, long from, long to, Job job, Progress progress, LineSink sink)
        {
            byte[] buf = new byte[1 << 20];
            int have = 0;
            long pos = from;   // where buf[0] sits in the file
            fs.Position = from;
            while (!job.Cancel)
            {
                long left = to - (pos + have);
                if (left <= 0) break;
                if (have == buf.Length)
                {
                    // One line longer than the buffer.
                    if (buf.Length >= MaxBufferBytes) break;
                    Array.Resize(ref buf, buf.Length * 2);
                }
                int n = fs.Read(buf, have, (int)Math.Min(buf.Length - have, left));
                if (n <= 0) break;
                int scan = have;
                have += n;
                int lineStart = 0;
                while (true)
                {
                    int feed = Array.IndexOf(buf, (byte)10, scan, have - scan);
                    if (feed < 0) break;
                    if (!sink(buf, lineStart, feed, pos + lineStart)) return pos + feed + 1;
                    lineStart = feed + 1;
                    scan = lineStart;
                }
                if (lineStart > 0)
                {
                    Buffer.BlockCopy(buf, lineStart, buf, 0, have - lineStart);
                    pos += lineStart;
                    have -= lineStart;
                }
                progress.Add(n);
            }
            return pos;
        }

        static Snapshot Build(List<Segment> segments)
        {
            Snapshot snap = new Snapshot();
            snap.Segments = segments.ToArray();
            snap.First = new int[segments.Count];
            List<Session> sessions = new List<Session>();
            Session run = null;
            for (int i = 0; i < segments.Count; i++)
            {
                Segment seg = segments[i];
                snap.First[i] = snap.TotalLines;
                if (snap.FirstTime.Length == 0) snap.FirstTime = seg.FirstTime;
                if (seg.LastTime.Length > 0) snap.LastTime = seg.LastTime;
                foreach (Part part in seg.Parts)
                {
                    int line = snap.TotalLines + part.StartLine;
                    if (part.Begins || run == null)
                    {
                        if (run != null) run.EndLine = line;
                        run = new Session();
                        run.Number = sessions.Count + 1;
                        run.FirstLine = line;
                        run.HasStart = part.Begins;
                        run.StartText = part.StartText;
                        run.StartTime = part.FirstTime;
                        sessions.Add(run);
                    }
                    if (part.LastTime.Length > 0) run.LastTime = part.LastTime;
                    if (part.ShutdownTime.Length > 0) run.ShutdownTime = part.ShutdownTime;
                    if (part.Version.Length > 0) run.Version = part.Version;
                    if (part.PreviousRun.Length > 0 && run.PreviousRun.Length == 0) run.PreviousRun = part.PreviousRun;
                    if (part.JvmArgs.Length > 0) run.JvmArgs = part.JvmArgs;
                    if (part.Heartbeat.Length > 0) { run.Heartbeat = part.Heartbeat; run.HeartbeatTime = part.HeartbeatTime; }
                    run.Errors += part.Errors;
                    run.Warnings += part.Warnings;
                    run.PeakHeapMB = Math.Max(run.PeakHeapMB, part.PeakHeapMB);
                }
                snap.TotalLines += seg.Lines;
                snap.TotalBytes += seg.Scanned;
            }
            if (run != null) run.EndLine = snap.TotalLines;
            // Each run records how the one before it ended, exit code included when the launcher passed it on.
            for (int i = 0; i + 1 < sessions.Count; i++)
            {
                string code = Bytes.Value(sessions[i + 1].PreviousRun, "exitCode");
                sessions[i].ExitCode = code == "unknown" ? "" : code;
            }
            snap.Sessions = sessions.ToArray();
            return snap;
        }

        /// <summary>How many of the oldest lines of one snapshot are gone in the next (the server deleted old files).</summary>
        public static int LinesDropped(Snapshot before, Snapshot after)
        {
            if (before == null || before.Segments.Length == 0) return 0;
            if (after.Segments.Length == 0) return before.TotalLines;
            string first = after.Segments[0].Name;
            for (int i = 0; i < before.Segments.Length; i++) if (before.Segments[i].Name == first) return before.First[i];
            return before.TotalLines;
        }

        // ---- reading -----------------------------------------------------------------------------------------

        static LogLine Notice(string text)
        {
            LogLine line = new LogLine();
            line.Raw = text;
            line.Text = text;
            return line;
        }

        public LogLine GetLine(Snapshot snap, int line)
        {
            if (snap == null || line < 0 || line >= snap.TotalLines) return Notice("");
            int index = snap.SegmentOf(line);
            Segment seg = snap.Segments[index];
            int local = line - snap.First[index];
            int block = local / Stride;
            LogLine[] lines = LoadBlock(seg, block);
            int at = local - block * Stride;
            return at < lines.Length && lines[at] != null ? lines[at] : Notice("");
        }

        LogLine[] LoadBlock(Segment seg, int block)
        {
            int count = Math.Min(Stride, seg.Lines - block * Stride);
            string key = seg.Name + "/" + block + "/" + count;
            LogLine[] lines;
            lock (cache) { if (cache.TryGetValue(key, out lines)) return lines; }
            lines = new LogLine[count];
            int filled = 0;
            try
            {
                long from = seg.Checkpoints[block];
                long to = block + 1 < seg.Checkpoints.Count ? seg.Checkpoints[block + 1] : seg.Scanned;
                byte[] buf = new byte[(int)Math.Min(to - from, MaxBlockBytes)];
                int have = 0;
                using (FileStream fs = Open(seg.Path))
                {
                    fs.Position = from;
                    while (have < buf.Length)
                    {
                        int n = fs.Read(buf, have, buf.Length - have);
                        if (n <= 0) break;
                        have += n;
                    }
                }
                int start = 0;
                for (int i = 0; i < have && filled < count; i++)
                {
                    if (buf[i] != 10) continue;
                    lines[filled++] = Bytes.Parse(buf, start, i, MaxLineBytes);
                    start = i + 1;
                }
                while (filled < count) lines[filled++] = Notice("(line too long to show)");
            }
            catch (IOException) { while (filled < count) lines[filled++] = Notice("(this part of the log is no longer on disk - press Refresh)"); }
            catch (UnauthorizedAccessException) { while (filled < count) lines[filled++] = Notice("(this part of the log cannot be read)"); }
            lock (cache)
            {
                if (cache.Count > 400) cache.Clear();
                cache[key] = lines;
            }
            return lines;
        }

        /// <summary>Writes the lines of a view to a text file and returns how many were written.</summary>
        public int Export(Snapshot snap, LineView view, string path, int max)
        {
            int count = Math.Min(view.Count, max);
            using (StreamWriter writer = new StreamWriter(path, false, new UTF8Encoding(false)))
            {
                for (int i = 0; i < count; i++) writer.WriteLine(GetLine(snap, view.LineAt(i)).Raw);
            }
            return count;
        }

        // ---- searching ---------------------------------------------------------------------------------------

        static bool Before(string time, string bound)
        {
            return time.Length > 0 && string.CompareOrdinal(time, 0, bound, 0, Math.Min(bound.Length, time.Length)) < 0;
        }

        /// <summary>Visits the lines numbered first up to (not including) end, in order, straight from the files.
        /// Files wholly outside the time bounds are skipped without being read.</summary>
        static void Walk(Snapshot snap, int first, int end, string fromTime, string toTime, Job job, LineVisitor visit)
        {
            first = Math.Max(first, 0);
            end = Math.Min(end, snap.TotalLines);
            long total = 0;
            for (int i = 0; i < snap.Segments.Length; i++)
            {
                if (snap.First[i] + snap.Segments[i].Lines > first && snap.First[i] < end) total += snap.Segments[i].Scanned;
            }
            Progress progress = new Progress(job, total);
            for (int i = 0; i < snap.Segments.Length; i++)
            {
                if (job.Cancel) return;
                Segment seg = snap.Segments[i];
                int segFirst = snap.First[i];
                if (segFirst + seg.Lines <= first) continue;
                if (segFirst >= end) break;
                if ((fromTime.Length > 0 && Before(seg.LastTime, fromTime)) || (toTime.Length > 0 && Before(toTime, seg.FirstTime)))
                {
                    progress.Add(seg.Scanned);
                    continue;
                }
                int local = Math.Max(first - segFirst, 0);
                int localEnd = Math.Min(end - segFirst, seg.Lines);
                int block = local / Stride;
                int number = block * Stride;
                bool stop = false;
                try
                {
                    using (FileStream fs = Open(seg.Path))
                    {
                        ReadLines(fs, seg.Checkpoints[block], seg.Scanned, job, progress, delegate(byte[] b, int s, int e, long offset)
                        {
                            int n = number++;
                            if (n < local) return true;
                            if (n >= localEnd) return false;
                            if (visit(b, s, e, segFirst + n)) return true;
                            stop = true;
                            return false;
                        });
                    }
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
                if (stop) return;
            }
        }

        static SearchResult Search(Snapshot snap, Query query, Job job)
        {
            SearchResult result = new SearchResult();
            List<int> hits = new List<int>();
            Regex regex = null;
            byte[] needle = null;
            if (query.Text.Length > 0)
            {
                if (query.UseRegex) regex = new Regex(query.Text, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
                else
                {
                    needle = Bytes.Utf8.GetBytes(query.Text);
                    for (int i = 0; i < needle.Length; i++) if (needle[i] >= 'A' && needle[i] <= 'Z') needle[i] += 32;
                }
            }
            bool timed = query.FromTime.Length > 0 || query.ToTime.Length > 0;
            string to = query.ToTime.Length > 0 ? query.ToTime : "9999";
            bool inTime = true;
            Walk(snap, query.FirstLine, query.EndLine, query.FromTime, query.ToTime, job, delegate(byte[] b, int s, int e, int line)
            {
                if (e > s && b[e - 1] == 13) e--;
                bool prefixed = Bytes.HasPrefix(b, s, e);
                byte mark = prefixed ? b[s + 24] : (byte)' ';
                int text = prefixed ? s + Bytes.PrefixLength : s;
                // A line with no timestamp of its own goes with the one before it.
                if (timed && prefixed) inTime = Bytes.InTime(b, s, query.FromTime, to);
                if (!inTime) return true;
                if ((query.KindMask & (1 << Bytes.Classify(b, text, e, mark))) == 0) return true;
                if (needle != null && !Bytes.Contains(b, s, e, needle)) return true;
                if (regex != null && !regex.IsMatch(Bytes.Utf8.GetString(b, s, e - s))) return true;
                // Over the limit, the oldest matches make room: the end of the log is what a crash hunt needs.
                if (hits.Count >= query.MaxResults)
                {
                    result.Truncated = true;
                    hits.RemoveRange(0, query.MaxResults / 4);
                }
                hits.Add(line);
                return true;
            });
            result.Lines = hits.ToArray();
            return result;
        }

        /// <summary>Groups the errors and warnings in a range by message (numbers blanked out) and returns the most
        /// frequent.</summary>
        static ErrorGroup[] Summarize(Snapshot snap, int first, int end, Job job)
        {
            Dictionary<string, ErrorGroup> groups = new Dictionary<string, ErrorGroup>();
            Walk(snap, first, end, "", "", job, delegate(byte[] b, int s, int e, int line)
            {
                if (e > s && b[e - 1] == 13) e--;
                bool prefixed = Bytes.HasPrefix(b, s, e);
                byte mark = prefixed ? b[s + 24] : (byte)' ';
                int text = prefixed ? s + Bytes.PrefixLength : s;
                int kind = Bytes.Classify(b, text, e, mark);
                if (kind != Kind.Error && kind != Kind.Warn) return true;
                string key = Normalize(Bytes.Utf8.GetString(b, text, Math.Min(e - text, 600)));
                string time = prefixed ? Encoding.ASCII.GetString(b, s, 23) : "";
                ErrorGroup group;
                if (!groups.TryGetValue(key, out group))
                {
                    if (groups.Count >= 50000) return true;
                    group = new ErrorGroup();
                    group.Kind = kind;
                    group.Message = key;
                    group.First = time;
                    groups[key] = group;
                }
                group.Count++;
                group.Last = time;
                group.LastLine = line;
                return true;
            });
            List<ErrorGroup> list = new List<ErrorGroup>(groups.Values);
            list.Sort(delegate(ErrorGroup a, ErrorGroup b)
            {
                int byCount = b.Count.CompareTo(a.Count);
                return byCount != 0 ? byCount : string.CompareOrdinal(b.Last, a.Last);
            });
            if (list.Count > 300) list.RemoveRange(300, list.Count - 300);
            return list.ToArray();
        }

        // Drops the logger's "HH:mm:ss LEVEL " and replaces every run of digits with #, so the same message with
        // different ids, counts or durations lands in one group.
        static string Normalize(string text)
        {
            int i = 0;
            if (text.Length > 14 && text[2] == ':' && text[5] == ':' && text[8] == ' ')
            {
                i = text.IndexOf(' ', 9);
                i = i < 0 ? 0 : i;
                while (i < text.Length && text[i] == ' ') i++;
            }
            StringBuilder sb = new StringBuilder(200);
            bool digits = false;
            for (; i < text.Length && sb.Length < 200; i++)
            {
                char c = text[i];
                if (c >= '0' && c <= '9')
                {
                    if (!digits) sb.Append('#');
                    digits = true;
                }
                else
                {
                    digits = false;
                    sb.Append(c < ' ' ? ' ' : c);
                }
            }
            return sb.ToString();
        }
    }
}
'@
if (-not ('ObsidianLogViewer.LogStore' -as [type])) {
    # PowerShell 7 compiles without optimizations unless asked, and the engine then reads the files half as fast.
    # Windows PowerShell 5.1 has no such switch and is as fast without it.
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        Add-Type -TypeDefinition $engine -CompilerOptions '-optimize+'
    } else {
        Add-Type -TypeDefinition $engine
    }
}

# ---------------------------------------------------------------------------------------------------------------
# State
# ---------------------------------------------------------------------------------------------------------------
$script:ui = @{}
$script:store = New-Object ObsidianLogViewer.LogStore
$script:snap = $null                # the snapshot on screen
$script:view = [ObsidianLogViewer.LineView]::Range(0, 0)
$script:scopeKey = $null            # start time of the selected run; '' for all runs; $null until the first load
$script:scopeRun = $null
$script:scopeFirst = 0
$script:scopeEnd = 0
$script:filtered = $false
$script:job = $null
$script:jobKind = ''
$script:quiet = $false              # set while the script itself changes controls, so their events do nothing
$script:detailDirty = $false
$script:lastRefresh = [datetime]::MinValue

$KindWarn = 3; $KindError = 4; $KindStack = 5; $KindHeartbeat = 7; $KindMarker = 8; $KindAll = 511
$script:kindColors = @(
    [System.Drawing.SystemColors]::WindowText,           # other
    [System.Drawing.Color]::FromArgb(110, 110, 110),     # debug
    [System.Drawing.SystemColors]::WindowText,           # info
    [System.Drawing.Color]::FromArgb(150, 90, 0),        # warn
    [System.Drawing.Color]::FromArgb(190, 20, 20),       # error
    [System.Drawing.Color]::FromArgb(150, 70, 70),       # stack trace
    [System.Drawing.Color]::FromArgb(0, 90, 170),        # logger records
    [System.Drawing.Color]::FromArgb(130, 130, 130),     # heartbeat
    [System.Drawing.Color]::FromArgb(0, 90, 170)         # run start / shutdown
)
$script:colorCrash = [System.Drawing.Color]::FromArgb(190, 20, 20)
$script:colorRunning = [System.Drawing.Color]::FromArgb(0, 120, 60)
$script:colorWarn = [System.Drawing.Color]::FromArgb(150, 90, 0)
$script:markerBack = [System.Drawing.Color]::FromArgb(226, 236, 250)

# What the launcher's exit code says about how the process ended. Windows reports its own fatal statuses as large
# negative numbers (0xC0000005 shows up as -1073741819).
$script:exitCodes = @{
    0           = 'normal exit'
    1           = 'ended with an error, or was ended from Task Manager / taskkill'
    3           = 'the Java VM ran out of memory and stopped itself (ExitOnOutOfMemoryError); the launcher raises the heap limit for the next start'
    10          = 'an update was rolled back'
    130         = 'interrupted with Ctrl+C'
    137         = 'the server halted itself after an OutOfMemoryError (OOM guard)'
    143         = 'asked to stop (SIGTERM)'
    -1073741510 = 'the console window was closed, or Ctrl+C (0xC000013A)'
    -1073741819 = 'access violation: a crash in native code (0xC0000005)'
    -1073741571 = 'stack overflow in native code (0xC00000FD)'
    -1073740791 = 'the process aborted itself: fail-fast or stack buffer overrun (0xC0000409)'
    -1073740940 = 'heap corruption found in native code (0xC0000374)'
    -1073741515 = 'a DLL the process needs was not found (0xC0000135)'
    -1073741502 = 'a DLL failed to start, usually because Windows was shutting down (0xC0000142)'
    1073807364  = 'ended by Windows at shutdown or logoff (0x40010004)'
}

# ---------------------------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------------------------
function Format-Size([long]$bytes) {
    if ($bytes -ge 1GB) { return '{0:N2} GB' -f ($bytes / 1GB) }
    if ($bytes -ge 1MB) { return '{0:N1} MB' -f ($bytes / 1MB) }
    return '{0:N0} KB' -f [Math]::Ceiling($bytes / 1KB)
}

function ConvertTo-Time([string]$text) {
    $time = [datetime]::MinValue
    $culture = [Globalization.CultureInfo]::InvariantCulture
    if ($text -and [datetime]::TryParseExact($text, 'yyyy-MM-dd HH:mm:ss.fff', $culture, [Globalization.DateTimeStyles]::None, [ref]$time)) {
        return $time
    }
    return $null
}

function Format-Span($from, $to) {
    if (-not $from -or -not $to) { return '' }
    $span = $to - $from
    if ($span.TotalSeconds -lt 0) { return '' }
    if ($span.TotalDays -ge 1) { return '{0}d {1}h' -f [Math]::Floor($span.TotalDays), $span.Hours }
    if ($span.TotalHours -ge 1) { return '{0}h {1:00}m' -f [Math]::Floor($span.TotalHours), $span.Minutes }
    if ($span.TotalMinutes -ge 1) { return '{0}m {1:00}s' -f $span.Minutes, $span.Seconds }
    return '{0}s' -f [Math]::Floor($span.TotalSeconds)
}

function Get-ShortTime([string]$time) {
    if ($time.Length -ge 19) { return $time.Substring(0, 19) }
    return $time
}

function Get-ExitCodeMeaning([string]$code) {
    $number = 0
    if (-not [int]::TryParse($code, [ref]$number)) { return '' }
    if ($script:exitCodes.ContainsKey($number)) { return $script:exitCodes[$number] }
    if ($number -lt 0) { return ('Windows ended the process abnormally (status 0x{0:X8})' -f $number) }
    return 'ended with an error'
}

function Get-RunResult($run, [bool]$newest) {
    if ($run.ShutdownTime) {
        # Being stopped with Ctrl+C or by closing the window is an ordinary stop, whatever code Windows gives it.
        if ($run.ExitCode -and @('0', '130', '143', '-1073741510') -notcontains $run.ExitCode) {
            return @{ Text = 'Stopped with an error'; Color = $script:colorWarn; State = 'stopped' }
        }
        return @{ Text = 'Stopped normally'; Color = [System.Drawing.SystemColors]::WindowText; State = 'stopped' }
    }
    if ($newest) {
        # A running server writes a heartbeat every minute.
        $last = ConvertTo-Time $run.LastTime
        if ($last -and ((Get-Date) - $last).TotalSeconds -lt 150) {
            return @{ Text = 'Running'; Color = $script:colorRunning; State = 'running' }
        }
        return @{ Text = 'Died, not restarted'; Color = $script:colorCrash; State = 'crashed' }
    }
    return @{ Text = 'Crashed or killed'; Color = $script:colorCrash; State = 'crashed' }
}

function Set-Status([string]$text) {
    $script:ui.Status.Text = $text
}

function Show-Busy([string]$text) {
    Set-Status $text
    $script:ui.Progress.Value = 0
    $script:ui.Progress.Visible = $true
    $script:ui.Cancel.Visible = $true
}

function Hide-Busy {
    $script:ui.Progress.Visible = $false
    $script:ui.Cancel.Visible = $false
}

# ---------------------------------------------------------------------------------------------------------------
# The list of lines
# ---------------------------------------------------------------------------------------------------------------
function Select-Row([int]$row) {
    $list = $script:ui.List
    if ($row -lt 0 -or $row -ge $list.VirtualListSize) { return }
    $list.SelectedIndices.Clear()
    [void]$list.SelectedIndices.Add($row)
    $list.EnsureVisible($row)
    try { $list.FocusedItem = $list.Items[$row] } catch { }
}

# Where the list stands, in line numbers, so it can be put back after the view is rebuilt.
function Get-Anchor {
    $list = $script:ui.List
    $anchor = @{ AtEnd = $true; Top = -1; Selected = -1 }
    if ($script:view.Count -eq 0 -or $list.VirtualListSize -eq 0) { return $anchor }
    $selected = -1
    if ($list.SelectedIndices.Count -gt 0) { $selected = $list.SelectedIndices[0] }
    $anchor.AtEnd = $script:ui.Follow.Checked -or ($selected -eq $script:view.Count - 1)
    $anchor.Selected = $script:view.LineAt($selected)
    try { if ($list.TopItem) { $anchor.Top = $script:view.LineAt($list.TopItem.Index) } } catch { }
    return $anchor
}

# Shows $script:view in the list. $dropped is how many of the oldest lines have gone since the anchor was taken.
function Sync-List($anchor, [int]$dropped) {
    $list = $script:ui.List
    $view = $script:view
    $list.BeginUpdate()
    try {
        $list.SelectedIndices.Clear()
        $list.VirtualListSize = $view.Count
    } finally {
        $list.EndUpdate()
    }
    if ($view.Count -gt 0) {
        if ($anchor.AtEnd) {
            Select-Row ($view.Count - 1)
        } else {
            if ($anchor.Top -ge 0) {
                # Scrolling to the end first makes the wanted row come to rest at the top.
                $list.EnsureVisible($view.Count - 1)
                $list.EnsureVisible($view.RowOf($anchor.Top - $dropped))
            }
            if ($anchor.Selected -ge $dropped) {
                $row = $view.RowOf($anchor.Selected - $dropped)
                [void]$list.SelectedIndices.Add($row)
                try { $list.FocusedItem = $list.Items[$row] } catch { }
            }
        }
    }
    $list.Invalidate()
    $script:detailDirty = $true
    Update-ViewStatus
}

function Update-ViewStatus {
    if (-not $script:snap) { return }
    $scope = 'all runs'
    if ($script:scopeRun) { $scope = 'run ' + $script:scopeRun.Number }
    $inScope = $script:scopeEnd - $script:scopeFirst
    if ($script:filtered) {
        Set-Status ('{0:N0} of {1:N0} lines in {2} match the filter. Double-click a line to see it in context.' -f $script:view.Count, $inScope, $scope)
    } else {
        Set-Status ('{0:N0} lines in {1}.' -f $inScope, $scope)
    }
    $snap = $script:snap
    $script:ui.Totals.Text = '{0} in {1} files, {2:N0} lines, {3} runs' -f (Format-Size $snap.TotalBytes), $snap.Segments.Length, $snap.TotalLines, $snap.Sessions.Length
}

function Update-Detail {
    $list = $script:ui.List
    $count = $list.SelectedIndices.Count
    if ($count -eq 0 -or -not $script:snap) {
        $script:ui.Detail.Text = ''
        return
    }
    $text = New-Object System.Text.StringBuilder
    $shown = [Math]::Min($count, 200)
    for ($i = 0; $i -lt $shown; $i++) {
        $line = $script:store.GetLine($script:snap, $script:view.LineAt($list.SelectedIndices[$i]))
        [void]$text.AppendLine($line.Raw)
    }
    if ($count -gt $shown) { [void]$text.AppendLine(('... and {0:N0} more selected lines' -f ($count - $shown))) }
    $script:ui.Detail.Text = $text.ToString()
}

function Copy-SelectedLines {
    $list = $script:ui.List
    $count = $list.SelectedIndices.Count
    if ($count -eq 0) { return }
    if ($count -gt 200000) {
        [void][System.Windows.Forms.MessageBox]::Show('That is too many lines for the clipboard. Use "Save shown lines" instead.', 'Copy')
        return
    }
    $text = New-Object System.Text.StringBuilder
    foreach ($row in $list.SelectedIndices) {
        [void]$text.AppendLine($script:store.GetLine($script:snap, $script:view.LineAt($row)).Raw)
    }
    [System.Windows.Forms.Clipboard]::SetText($text.ToString())
}

# ---------------------------------------------------------------------------------------------------------------
# Runs and scope
# ---------------------------------------------------------------------------------------------------------------
function Update-RunList {
    $runs = $script:ui.Runs
    $snap = $script:snap
    $sessions = $snap.Sessions
    $script:quiet = $true
    $runs.BeginUpdate()
    try {
        $runs.Items.Clear()
        $all = New-Object System.Windows.Forms.ListViewItem('All')
        $cells = @((Get-ShortTime $snap.FirstTime), (Get-ShortTime $snap.LastTime), (Format-Span (ConvertTo-Time $snap.FirstTime) (ConvertTo-Time $snap.LastTime)), 'Everything on disk', '', '', '', '')
        foreach ($cell in $cells) { [void]$all.SubItems.Add([string]$cell) }
        $all.Tag = ''
        [void]$runs.Items.Add($all)

        $script:scopeRun = $null
        for ($i = $sessions.Length - 1; $i -ge 0; $i--) {
            $run = $sessions[$i]
            $result = Get-RunResult $run ($i -eq $sessions.Length - 1)
            $started = Get-ShortTime $run.StartTime
            if (-not $run.HasStart) { $started = $started + ' (earlier part deleted)' }
            $item = New-Object System.Windows.Forms.ListViewItem([string]$run.Number)
            $cells = @($started, (Get-ShortTime $run.LastTime), (Format-Span (ConvertTo-Time $run.StartTime) (ConvertTo-Time $run.LastTime)), $result.Text, $run.ExitCode, $run.Errors, $run.Warnings, $run.Version)
            foreach ($cell in $cells) { [void]$item.SubItems.Add([string]$cell) }
            $item.ForeColor = $result.Color
            $item.Tag = $run.StartTime
            [void]$runs.Items.Add($item)
            if ($script:scopeKey -and $run.StartTime -eq $script:scopeKey) {
                $script:scopeRun = $run
                $item.Selected = $true
            }
        }
        if (-not $script:scopeRun) {
            $script:scopeKey = ''
            $runs.Items[0].Selected = $true
        }
    } finally {
        $runs.EndUpdate()
        $script:quiet = $false
    }
    if ($script:scopeRun) {
        $script:scopeFirst = $script:scopeRun.FirstLine
        $script:scopeEnd = $script:scopeRun.EndLine
    } else {
        $script:scopeFirst = 0
        $script:scopeEnd = $snap.TotalLines
    }
    if ($runs.SelectedItems.Count -gt 0) { $runs.SelectedItems[0].EnsureVisible() }
    Update-RunReport
}

# On opening a folder: the most recent run that died, since that is usually why the viewer was opened.
function Get-DefaultRunKey($snap) {
    $sessions = $snap.Sessions
    for ($i = $sessions.Length - 1; $i -ge 0; $i--) {
        if ((Get-RunResult $sessions[$i] ($i -eq $sessions.Length - 1)).State -eq 'crashed') { return $sessions[$i].StartTime }
    }
    if ($sessions.Length -gt 0) { return $sessions[$sessions.Length - 1].StartTime }
    return ''
}

function Select-Scope([string]$key) {
    if (-not $script:snap) { return }
    $script:scopeKey = $key
    $script:scopeRun = $null
    foreach ($run in $script:snap.Sessions) {
        if ($key -and $run.StartTime -eq $key) { $script:scopeRun = $run }
    }
    if ($script:scopeRun) {
        $script:scopeFirst = $script:scopeRun.FirstLine
        $script:scopeEnd = $script:scopeRun.EndLine
    } else {
        $script:scopeKey = ''
        $script:scopeFirst = 0
        $script:scopeEnd = $script:snap.TotalLines
    }
    Update-RunReport
    $script:ui.Errors.Items.Clear()
    $script:ui.Events.Items.Clear()
    Start-Filter
}

function Get-CrashFiles($run) {
    # The JVM writes hs_err_pid<pid>.log (and Windows may leave a dump) in the server's folder when it crashes natively.
    $found = @()
    $last = ConvertTo-Time $run.LastTime
    if (-not $last) { return $found }
    $folders = @($script:store.Directory, (Split-Path -Parent $script:store.Directory)) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    foreach ($folder in $folders) {
        foreach ($pattern in 'hs_err_pid*.log', '*.mdmp', '*.hprof') {
            foreach ($file in @(Get-ChildItem -LiteralPath $folder -Filter $pattern -File -ErrorAction SilentlyContinue)) {
                if ([Math]::Abs(($file.LastWriteTime - $last).TotalMinutes) -le 10) { $found += $file }
            }
        }
    }
    return $found
}

function Update-RunReport {
    $box = $script:ui.RunText
    $snap = $script:snap
    if (-not $snap) { $box.Text = ''; return }
    $run = $script:scopeRun
    $out = New-Object System.Text.StringBuilder
    if (-not $run) {
        [void]$out.AppendLine('All runs on disk')
        [void]$out.AppendLine('')
        [void]$out.AppendLine(('From         {0}' -f (Get-ShortTime $snap.FirstTime)))
        [void]$out.AppendLine(('To           {0}' -f (Get-ShortTime $snap.LastTime)))
        [void]$out.AppendLine(('Size         {0} in {1} files, {2:N0} lines' -f (Format-Size $snap.TotalBytes), $snap.Segments.Length, $snap.TotalLines))
        $crashed = 0
        for ($i = 0; $i -lt $snap.Sessions.Length; $i++) {
            if ((Get-RunResult $snap.Sessions[$i] ($i -eq $snap.Sessions.Length - 1)).State -eq 'crashed') { $crashed++ }
        }
        [void]$out.AppendLine(('Runs         {0}, of which {1} ended without a shutdown record' -f $snap.Sessions.Length, $crashed))
        [void]$out.AppendLine('')
        [void]$out.AppendLine('Pick a run above to see how it ended.')
        if ($snap.Segments.Length -eq 0) {
            [void]$out.AppendLine('')
            [void]$out.AppendLine('No console-*.log files here. The server creates them in its logs folder when it starts.')
        }
        $box.Text = $out.ToString()
        return
    }

    $newest = $run.Number -eq $snap.Sessions.Length
    $result = Get-RunResult $run $newest
    $started = ConvertTo-Time $run.StartTime
    $last = ConvertTo-Time $run.LastTime
    $processId = [ObsidianLogViewer.LogStore]::Value($run.StartText, 'pid')
    [void]$out.AppendLine(('Run {0} of {1}' -f $run.Number, $snap.Sessions.Length))
    [void]$out.AppendLine('')
    $about = @()
    if ($processId) { $about += 'process ' + $processId }
    if ($run.Version) { $about += 'version ' + $run.Version }
    $startLine = 'Started      ' + (Get-ShortTime $run.StartTime)
    if (-not $run.HasStart) { $startLine += '   (the beginning of this run is no longer on disk)' }
    elseif ($about.Count -gt 0) { $startLine += '   (' + ($about -join ', ') + ')' }
    [void]$out.AppendLine($startLine)
    [void]$out.AppendLine(('Last output  {0}   (ran {1})' -f (Get-ShortTime $run.LastTime), (Format-Span $started $last)))
    [void]$out.AppendLine(('Result       {0}' -f $result.Text.ToUpper()))
    if ($run.ExitCode) {
        [void]$out.AppendLine(('Exit code    {0}: {1}' -f $run.ExitCode, (Get-ExitCodeMeaning $run.ExitCode)))
    } elseif (-not $newest) {
        [void]$out.AppendLine('Exit code    not recorded (the launcher script passes it on; run.bat from this version or later)')
    }
    [void]$out.AppendLine(('Output       {0:N0} lines, {1:N0} errors, {2:N0} warnings' -f ($run.EndLine - $run.FirstLine), $run.Errors, $run.Warnings))
    [void]$out.AppendLine('')

    if ($result.State -eq 'crashed') {
        [void]$out.AppendLine('What this means')
        [void]$out.AppendLine('  The process stopped without running its shutdown steps. A normal stop (Ctrl+C, closing the window,')
        [void]$out.AppendLine('  an update or restart from the admin pages) always leaves a shutdown record. Without one, the process')
        [void]$out.AppendLine('  was killed, crashed in native code, ran out of memory, or the whole machine went down.')
        if ($run.ExitCode -eq '3' -or $run.ExitCode -eq '137') {
            [void]$out.AppendLine('  The exit code says it ran out of memory: look at the heap figures below and at what was running last.')
        }
        [void]$out.AppendLine('  Check the Windows events tab for a restart, power loss or application fault at that time.')
        [void]$out.AppendLine('')
    }

    if ($run.Heartbeat) {
        $beat = ConvertTo-Time $run.HeartbeatTime
        $gap = ''
        if ($beat -and $last) { $gap = ', ' + (Format-Span $beat $last) + ' before the last output' }
        [void]$out.AppendLine(('Last heartbeat ({0}{1})' -f (Get-ShortTime $run.HeartbeatTime), $gap))
        [void]$out.AppendLine('  ' + $run.Heartbeat.Substring([Math]::Min(10, $run.Heartbeat.Length)))
        $used = 0L; $max = 0L
        if ([long]::TryParse([ObsidianLogViewer.LogStore]::Value($run.Heartbeat, 'heapUsedMB'), [ref]$used) -and
            [long]::TryParse([ObsidianLogViewer.LogStore]::Value($run.Heartbeat, 'heapMaxMB'), [ref]$max) -and $max -gt 0) {
            [void]$out.AppendLine(('  Heap in use: {0:N0} of {1:N0} MB ({2:N0}%). Highest seen in this run: {3:N0} MB ({4:N0}%).' -f $used, $max, ($used * 100 / $max), $run.PeakHeapMB, ($run.PeakHeapMB * 100 / $max)))
        }
        [void]$out.AppendLine('')
    } elseif ($result.State -eq 'crashed') {
        [void]$out.AppendLine('No heartbeat was recorded: the run lasted less than a minute.')
        [void]$out.AppendLine('')
    }

    $crashFiles = @(Get-CrashFiles $run)
    if ($crashFiles.Count -gt 0) {
        [void]$out.AppendLine('Crash files written around the end of this run')
        foreach ($file in $crashFiles) {
            [void]$out.AppendLine(('  {0}   ({1:yyyy-MM-dd HH:mm:ss}, {2})' -f $file.FullName, $file.LastWriteTime, (Format-Size $file.Length)))
        }
        [void]$out.AppendLine('')
    }

    if ($run.StartText) {
        [void]$out.AppendLine('Environment')
        [void]$out.AppendLine('  ' + $run.StartText.Replace('==== SERVER START ==== ', ''))
        if ($run.JvmArgs) { [void]$out.AppendLine('  JVM options: ' + $run.JvmArgs) }
        [void]$out.AppendLine('')
    }

    # The last things the server printed. Heartbeats are left out (they are summed up above), or an idle server's
    # final output would be buried under an hour of them.
    $tail = New-Object System.Collections.Generic.List[string]
    $floor = [Math]::Max($run.FirstLine, $run.EndLine - 3000)
    for ($line = $run.EndLine - 1; $line -ge $floor -and $tail.Count -lt 40; $line--) {
        $entry = $script:store.GetLine($snap, $line)
        if ($entry.Kind -ne $KindHeartbeat) { $tail.Add('  ' + $entry.Raw) }
    }
    $tail.Reverse()
    [void]$out.AppendLine(('Last {0} lines of output' -f $tail.Count))
    foreach ($text in $tail) { [void]$out.AppendLine($text) }
    $box.Text = $out.ToString()
}

# ---------------------------------------------------------------------------------------------------------------
# Loading, filtering and the other background jobs
# ---------------------------------------------------------------------------------------------------------------
function Start-Work($job, [string]$kind, [string]$status) {
    if ($script:job) { $script:job.Cancel = $true }
    $script:job = $job
    $script:jobKind = $kind
    Show-Busy $status
}

function Start-Refresh {
    if ($script:job) { return }
    $script:lastRefresh = Get-Date
    $status = 'Looking for new output...'
    if (-not $script:snap) { $status = 'Reading the log files...' }
    Start-Work ($script:store.BeginRefresh()) 'refresh' $status
}

function Open-LogFolder([string]$dir) {
    if ($script:job) { $script:job.Cancel = $true; $script:job = $null }
    $script:store = New-Object ObsidianLogViewer.LogStore
    $script:store.Directory = $dir
    $script:snap = $null
    $script:scopeKey = $null
    $script:scopeRun = $null
    $script:filtered = $false
    $script:view = [ObsidianLogViewer.LineView]::Range(0, 0)
    $script:ui.List.SelectedIndices.Clear()
    $script:ui.List.VirtualListSize = 0
    $script:ui.Runs.Items.Clear()
    $script:ui.Errors.Items.Clear()
    $script:ui.Events.Items.Clear()
    $script:ui.RunText.Text = ''
    $script:ui.Detail.Text = ''
    $script:ui.Folder.Text = $dir
    $script:ui.Form.Text = 'ObsidianScout Log Viewer - ' + $dir
    Start-Refresh
}

function Get-Query([int]$first, [int]$end) {
    $ui = $script:ui
    $query = New-Object ObsidianLogViewer.Query
    $query.FirstLine = $first
    $query.EndLine = $end
    # Where runs start and stop stays visible with the errors, to show which run they belong to.
    $errors = (1 -shl $KindError) -bor (1 -shl $KindStack) -bor (1 -shl $KindMarker)
    switch ($ui.Show.SelectedIndex) {
        1 { $query.KindMask = $errors -bor (1 -shl $KindWarn) }
        2 { $query.KindMask = $errors }
        default {
            $query.KindMask = $KindAll
            if ($ui.HideBeats.Checked) { $query.KindMask = $KindAll -bxor (1 -shl $KindHeartbeat) }
        }
    }
    $query.Text = $ui.Find.Text
    $query.UseRegex = $ui.Regex.Checked
    $query.FromTime = $ui.From.Text.Trim()
    $query.ToTime = $ui.To.Text.Trim()
    return $query
}

function Test-Unfiltered($query) {
    return $query.KindMask -eq $KindAll -and -not $query.Text -and -not $query.FromTime -and -not $query.ToTime
}

# Applies the filter controls to the selected run (or to everything).
function Start-Filter {
    if (-not $script:snap) { return }
    $query = Get-Query $script:scopeFirst $script:scopeEnd
    if (Test-Unfiltered $query) {
        if ($script:job -and $script:jobKind -ne 'refresh') { $script:job.Cancel = $true; $script:job = $null; Hide-Busy }
        $script:filtered = $false
        $script:view = [ObsidianLogViewer.LineView]::Range($script:scopeFirst, $script:scopeEnd - $script:scopeFirst)
        Sync-List @{ AtEnd = $true; Top = -1; Selected = -1 } 0
        return
    }
    $script:filtered = $true
    Start-Work ($script:store.BeginSearch($script:snap, $query)) 'search' 'Filtering...'
}

function Clear-Filter {
    $ui = $script:ui
    $script:quiet = $true
    try {
        $ui.Show.SelectedIndex = 0
        $ui.HideBeats.Checked = $false
        $ui.Regex.Checked = $false
        $ui.Find.Text = ''
        $ui.From.Text = ''
        $ui.To.Text = ''
    } finally {
        $script:quiet = $false
    }
}

# Drops the filter and shows the given line among its neighbours.
function Show-InContext([int]$line) {
    if ($line -lt 0) { return }
    Clear-Filter
    if ($script:job -and $script:jobKind -ne 'refresh') { $script:job.Cancel = $true; $script:job = $null; Hide-Busy }
    if ($line -lt $script:scopeFirst -or $line -ge $script:scopeEnd) {
        # The line belongs to another run than the one selected: widen to everything.
        $script:quiet = $true
        try { $script:ui.Runs.Items[0].Selected = $true } finally { $script:quiet = $false }
        $script:scopeKey = ''
        $script:scopeRun = $null
        $script:scopeFirst = 0
        $script:scopeEnd = $script:snap.TotalLines
        Update-RunReport
    }
    $script:filtered = $false
    $script:view = [ObsidianLogViewer.LineView]::Range($script:scopeFirst, $script:scopeEnd - $script:scopeFirst)
    Sync-List @{ AtEnd = $false; Top = -1; Selected = -1 } 0
    Select-Row ($script:view.RowOf($line))
    [void]$script:ui.List.Focus()
}

# A new snapshot arrived: bring the run list and the lines up to date without losing the user's place.
function Use-Snapshot($new) {
    $old = $script:snap
    if ($old -and [object]::ReferenceEquals($old, $new)) { return }
    $dropped = 0
    if ($old) { $dropped = [ObsidianLogViewer.LogStore]::LinesDropped($old, $new) }
    if ($old -and $dropped -eq 0 -and $old.TotalLines -eq $new.TotalLines) {
        $script:snap = $new
        return
    }
    $anchor = Get-Anchor
    $previousEnd = $script:scopeEnd - $dropped
    $script:snap = $new
    if (-not $old) { $script:scopeKey = Get-DefaultRunKey $new }
    Update-RunList
    if ($script:filtered) {
        $script:view.Shift($dropped)
        $from = [Math]::Max($previousEnd, $script:scopeFirst)
        if ($script:scopeEnd -gt $from) {
            # Only the new lines need filtering; the matches found so far stay.
            Start-Work ($script:store.BeginSearch($new, (Get-Query $from $script:scopeEnd))) 'append' 'Filtering new output...'
        } else {
            Sync-List $anchor $dropped
        }
    } else {
        $script:view = [ObsidianLogViewer.LineView]::Range($script:scopeFirst, $script:scopeEnd - $script:scopeFirst)
        Sync-List $anchor $dropped
    }
}

function Complete-Job($job, [string]$kind) {
    Hide-Busy
    if ($job.Error) {
        Set-Status ('Could not finish: ' + $job.Error)
        if ($kind -eq 'search') {
            $script:view = [ObsidianLogViewer.LineView]::Of((New-Object int[] 0))
            Sync-List @{ AtEnd = $true; Top = -1; Selected = -1 } 0
            Set-Status ('The filter could not be applied: ' + $job.Error)
        }
        return
    }
    switch ($kind) {
        'refresh' {
            Use-Snapshot $job.Result
            if (-not $script:job) { Update-ViewStatus }
        }
        'search' {
            $script:view = [ObsidianLogViewer.LineView]::Of($job.Result.Lines)
            Sync-List @{ AtEnd = $true; Top = -1; Selected = -1 } 0
            if ($job.Result.Truncated) {
                Set-Status ('Showing the newest {0:N0} matching lines; narrow the filter or pick one run to see earlier ones.' -f $script:view.Count)
            }
        }
        'append' {
            $anchor = Get-Anchor
            $script:view.Append($job.Result.Lines)
            Sync-List $anchor 0
        }
        'summary' {
            $errors = $script:ui.Errors
            $errors.BeginUpdate()
            try {
                $errors.Items.Clear()
                foreach ($group in $job.Result) {
                    $kindName = 'Error'
                    if ($group.Kind -eq $KindWarn) { $kindName = 'Warning' }
                    $item = New-Object System.Windows.Forms.ListViewItem(('{0:N0}' -f $group.Count))
                    foreach ($cell in @($kindName, (Get-ShortTime $group.First), (Get-ShortTime $group.Last), $group.Message)) { [void]$item.SubItems.Add([string]$cell) }
                    $item.ForeColor = $script:kindColors[$group.Kind]
                    $item.Tag = $group
                    [void]$errors.Items.Add($item)
                }
            } finally {
                $errors.EndUpdate()
            }
            Update-ViewStatus
            if ($job.Result.Length -eq 0) { Set-Status 'No errors or warnings in this selection.' }
        }
    }
}

function Start-Summary {
    if (-not $script:snap) { return }
    Start-Work ($script:store.BeginSummary($script:snap, $script:scopeFirst, $script:scopeEnd)) 'summary' 'Counting errors and warnings...'
}

# ---------------------------------------------------------------------------------------------------------------
# What Windows recorded around the end of a run
# ---------------------------------------------------------------------------------------------------------------
function Find-WindowsEvents {
    $events = $script:ui.Events
    $events.Items.Clear()
    $run = $script:scopeRun
    if (-not $run) {
        $script:ui.EventsNote.Text = 'Pick a run first: this looks at the time that run ended.'
        return
    }
    $last = ConvertTo-Time $run.LastTime
    if (-not $last) { return }
    $from = $last.AddMinutes(-10)
    $to = $last.AddMinutes(15)
    $script:ui.Form.Cursor = [System.Windows.Forms.Cursors]::WaitCursor
    $found = @()
    $problems = @()
    try {
        # System: unexpected power loss (41), bugcheck (1001), shutdown or restart requested (1074), event log
        # stopped/started/previous shutdown unexpected (6006/6005/6008), low memory (2004), OS start/stop (12/13).
        # Application: application fault (1000), fault report (1001), application hang (1002).
        $queries = @(
            @{ LogName = 'System'; Id = 12, 13, 41, 109, 1001, 1074, 1076, 2004, 6005, 6006, 6008 },
            @{ LogName = 'Application'; Id = 1000, 1001, 1002 }
        )
        foreach ($query in $queries) {
            $query.StartTime = $from
            $query.EndTime = $to
            try {
                $found += @(Get-WinEvent -FilterHashtable $query -MaxEvents 400 -ErrorAction Stop)
            } catch {
                if ($_.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*') { $problems += ($query.LogName + ': ' + $_.Exception.Message) }
            }
        }
    } finally {
        $script:ui.Form.Cursor = [System.Windows.Forms.Cursors]::Default
    }
    $serious = 41, 1000, 1002, 2004, 6008
    $shown = 0
    foreach ($entry in ($found | Sort-Object TimeCreated)) {
        $message = [string]$entry.Message
        $ours = $message -match 'java|obsidianscout|graal'
        # Fault reports are written for every program on the machine: keep the server's.
        if ($entry.LogName -eq 'Application' -and $entry.Id -eq 1001 -and -not $ours) { continue }
        if ($entry.LogName -eq 'System' -and $entry.Id -eq 1001 -and $entry.ProviderName -notlike '*WER-SystemErrorReporting*') { continue }
        $firstLine = ($message -split "`r?`n")[0]
        $item = New-Object System.Windows.Forms.ListViewItem($entry.TimeCreated.ToString('yyyy-MM-dd HH:mm:ss'))
        foreach ($cell in @($entry.LogName, $entry.Id, $entry.ProviderName, $firstLine)) { [void]$item.SubItems.Add([string]$cell) }
        if (($serious -contains $entry.Id) -or ($entry.LogName -eq 'System' -and $entry.Id -eq 1001) -or $ours) { $item.ForeColor = $script:colorCrash }
        elseif ($entry.Id -eq 1074 -or $entry.Id -eq 1076) { $item.ForeColor = $script:colorWarn }
        $item.Tag = ('{0:yyyy-MM-dd HH:mm:ss}  {1}  event {2}  {3}' -f $entry.TimeCreated, $entry.LogName, $entry.Id, $entry.ProviderName) + "`r`n`r`n" + $message
        [void]$events.Items.Add($item)
        $shown++
    }
    $note = '{0} events between {1:HH:mm} and {2:HH:mm} on {2:yyyy-MM-dd} (run {3} ended {4:HH:mm:ss}).' -f $shown, $from, $to, $run.Number, $last
    if ($shown -eq 0) { $note += ' Windows recorded no restart, power loss or application fault then.' }
    if ($problems.Count -gt 0) { $note += ' Could not read: ' + ($problems -join '; ') }
    $script:ui.EventsNote.Text = $note
}

# ---------------------------------------------------------------------------------------------------------------
# The window
# ---------------------------------------------------------------------------------------------------------------
function Add-Columns($list, $columns) {
    foreach ($column in $columns) { [void]$list.Columns.Add($column[0], $column[1]) }
}

function New-Label([string]$text) {
    $label = New-Object System.Windows.Forms.Label
    $label.Text = $text
    $label.AutoSize = $true
    $label.Margin = New-Object System.Windows.Forms.Padding(8, 7, 0, 0)
    return $label
}

function New-Button([string]$text) {
    $button = New-Object System.Windows.Forms.Button
    $button.Text = $text
    $button.AutoSize = $true
    $button.Margin = New-Object System.Windows.Forms.Padding(6, 2, 0, 2)
    return $button
}

function New-ViewerForm {
    $ui = $script:ui
    $mono = New-Object System.Drawing.Font('Consolas', 9.5)

    $form = New-Object System.Windows.Forms.Form
    $form.Text = 'ObsidianScout Log Viewer'
    $form.Size = New-Object System.Drawing.Size(1560, 920)
    $form.MinimumSize = New-Object System.Drawing.Size(900, 500)
    $form.StartPosition = 'CenterScreen'
    $form.Font = New-Object System.Drawing.Font('Segoe UI', 9)
    $form.KeyPreview = $true
    $ui.Form = $form

    $tips = New-Object System.Windows.Forms.ToolTip

    # ---- top bar ----
    $top = New-Object System.Windows.Forms.FlowLayoutPanel
    $top.Dock = 'Top'
    $top.AutoSize = $true
    $top.WrapContents = $false
    $top.Padding = New-Object System.Windows.Forms.Padding(4, 4, 4, 2)
    $open = New-Button 'Open folder...'
    $refresh = New-Button 'Refresh (F5)'
    $follow = New-Object System.Windows.Forms.CheckBox
    $follow.Text = 'Follow new output'
    $follow.AutoSize = $true
    $follow.Margin = New-Object System.Windows.Forms.Padding(12, 6, 0, 0)
    $tips.SetToolTip($follow, 'Reads new output every two seconds and keeps the list at the end.')
    $folder = New-Label ''
    $folder.ForeColor = [System.Drawing.SystemColors]::GrayText
    $top.Controls.AddRange(@($open, $refresh, $follow, $folder))
    $ui.Follow = $follow
    $ui.Folder = $folder

    # ---- status bar ----
    $strip = New-Object System.Windows.Forms.StatusStrip
    $status = New-Object System.Windows.Forms.ToolStripStatusLabel
    $status.Spring = $true
    $status.TextAlign = 'MiddleLeft'
    $progress = New-Object System.Windows.Forms.ToolStripProgressBar
    $progress.Size = New-Object System.Drawing.Size(160, 16)
    $progress.Visible = $false
    $cancel = New-Object System.Windows.Forms.ToolStripStatusLabel
    $cancel.Text = 'Cancel'
    $cancel.IsLink = $true
    $cancel.Visible = $false
    $totals = New-Object System.Windows.Forms.ToolStripStatusLabel
    [void]$strip.Items.AddRange(@($status, $progress, $cancel, $totals))
    $ui.Status = $status
    $ui.Progress = $progress
    $ui.Cancel = $cancel
    $ui.Totals = $totals

    # ---- left: runs above, analysis tabs below ----
    $runs = New-Object System.Windows.Forms.ListView
    $runs.Dock = 'Fill'
    $runs.View = 'Details'
    $runs.FullRowSelect = $true
    $runs.HideSelection = $false
    $runs.MultiSelect = $false
    Add-Columns $runs @(@('Run', 36), @('Started', 122), @('Last output', 122), @('Ran for', 60), @('Result', 138), @('Exit', 90), @('Errors', 46), @('Warn', 42), @('Version', 62))
    $ui.Runs = $runs

    $runText = New-Object System.Windows.Forms.TextBox
    $runText.Multiline = $true
    $runText.ReadOnly = $true
    $runText.Dock = 'Fill'
    $runText.ScrollBars = 'Both'
    $runText.WordWrap = $false
    $runText.Font = $mono
    $runText.BackColor = [System.Drawing.SystemColors]::Window
    $ui.RunText = $runText

    $errors = New-Object System.Windows.Forms.ListView
    $errors.Dock = 'Fill'
    $errors.View = 'Details'
    $errors.FullRowSelect = $true
    $errors.HideSelection = $false
    $errors.MultiSelect = $false
    Add-Columns $errors @(@('Count', 60), @('Kind', 58), @('First', 122), @('Last', 122), @('Message (numbers shown as #)', 900))
    $ui.Errors = $errors
    $errorBar = New-Object System.Windows.Forms.FlowLayoutPanel
    $errorBar.Dock = 'Top'
    $errorBar.AutoSize = $true
    $analyze = New-Button 'Count errors in the selected run'
    $errorBar.Controls.AddRange(@($analyze, (New-Label 'Double-click a row to jump to its latest occurrence.')))

    $events = New-Object System.Windows.Forms.ListView
    $events.Dock = 'Fill'
    $events.View = 'Details'
    $events.FullRowSelect = $true
    $events.HideSelection = $false
    $events.MultiSelect = $false
    Add-Columns $events @(@('Time', 122), @('Log', 70), @('Event', 46), @('Source', 170), @('Message', 900))
    $ui.Events = $events
    $eventBar = New-Object System.Windows.Forms.FlowLayoutPanel
    $eventBar.Dock = 'Top'
    $eventBar.AutoSize = $true
    $lookup = New-Button 'Look up what Windows recorded'
    $eventsNote = New-Label 'Restarts, power loss, low memory and application faults from 10 minutes before the run ended to 15 after.'
    $eventBar.Controls.AddRange(@($lookup, $eventsNote))
    $ui.EventsNote = $eventsNote

    $tabs = New-Object System.Windows.Forms.TabControl
    $tabs.Dock = 'Fill'
    $pageRun = New-Object System.Windows.Forms.TabPage('How the run ended')
    $pageRun.Controls.Add($runText)
    $pageErrors = New-Object System.Windows.Forms.TabPage('Top errors')
    $pageErrors.Controls.Add($errors)
    $pageErrors.Controls.Add($errorBar)
    $pageEvents = New-Object System.Windows.Forms.TabPage('Windows events')
    $pageEvents.Controls.Add($events)
    $pageEvents.Controls.Add($eventBar)
    $tabs.TabPages.AddRange(@($pageRun, $pageErrors, $pageEvents))

    $leftSplit = New-Object System.Windows.Forms.SplitContainer
    $leftSplit.Dock = 'Fill'
    $leftSplit.Orientation = 'Horizontal'
    $leftSplit.Panel1.Controls.Add($runs)
    $leftSplit.Panel2.Controls.Add($tabs)

    # ---- right: filter bar, lines, selected line in full ----
    $bar = New-Object System.Windows.Forms.FlowLayoutPanel
    $bar.Dock = 'Top'
    $bar.AutoSize = $true
    $bar.Padding = New-Object System.Windows.Forms.Padding(0, 2, 0, 2)
    $show = New-Object System.Windows.Forms.ComboBox
    $show.DropDownStyle = 'DropDownList'
    [void]$show.Items.AddRange(@('All lines', 'Warnings and errors', 'Errors only'))
    $show.SelectedIndex = 0
    $show.Width = 150
    $show.Margin = New-Object System.Windows.Forms.Padding(6, 4, 0, 0)
    $hideBeats = New-Object System.Windows.Forms.CheckBox
    $hideBeats.Text = 'Hide heartbeats'
    $hideBeats.AutoSize = $true
    $hideBeats.Margin = New-Object System.Windows.Forms.Padding(10, 6, 0, 0)
    $find = New-Object System.Windows.Forms.TextBox
    $find.Width = 240
    $find.Margin = New-Object System.Windows.Forms.Padding(6, 4, 0, 0)
    $regex = New-Object System.Windows.Forms.CheckBox
    $regex.Text = 'Regex'
    $regex.AutoSize = $true
    $regex.Margin = New-Object System.Windows.Forms.Padding(6, 6, 0, 0)
    $from = New-Object System.Windows.Forms.TextBox
    $from.Width = 130
    $from.Margin = New-Object System.Windows.Forms.Padding(6, 4, 0, 0)
    $to = New-Object System.Windows.Forms.TextBox
    $to.Width = 130
    $to.Margin = New-Object System.Windows.Forms.Padding(6, 4, 0, 0)
    $timeTip = 'A date and as much of the time as you like: 2026-11-14, 2026-11-14 02 or 2026-11-14 02:15:30'
    $tips.SetToolTip($from, $timeTip)
    $tips.SetToolTip($to, $timeTip)
    $tips.SetToolTip($find, 'Text to look for anywhere in the line, upper or lower case. Press Enter to apply.')
    $apply = New-Button 'Apply'
    $clear = New-Button 'Clear'
    $save = New-Button 'Save shown lines...'
    $bar.Controls.AddRange(@((New-Label 'Show'), $show, $hideBeats, (New-Label 'Find'), $find, $regex, (New-Label 'From'), $from, (New-Label 'To'), $to, $apply, $clear, $save))
    # Two tidy rows at any window width: what to show and look for, then the time range and the buttons.
    $bar.SetFlowBreak($regex, $true)
    $ui.Show = $show
    $ui.HideBeats = $hideBeats
    $ui.Find = $find
    $ui.Regex = $regex
    $ui.From = $from
    $ui.To = $to

    $list = New-Object System.Windows.Forms.ListView
    $list.Dock = 'Fill'
    $list.View = 'Details'
    $list.VirtualMode = $true
    $list.FullRowSelect = $true
    $list.HideSelection = $false
    $list.Font = $mono
    Add-Columns $list @(@('Time', 178), @('From', 44), @('Text', 2600))
    # Without double buffering a virtual list flickers while it scrolls.
    $list.GetType().GetProperty('DoubleBuffered', [System.Reflection.BindingFlags]'NonPublic,Instance').SetValue($list, $true, $null)
    $ui.List = $list

    $menu = New-Object System.Windows.Forms.ContextMenuStrip
    $menuCopy = $menu.Items.Add('Copy')
    $menuContext = $menu.Items.Add('Show in context (drop the filter)')
    $menuAround = $menu.Items.Add('Filter to 5 minutes around this line')
    $list.ContextMenuStrip = $menu

    $detail = New-Object System.Windows.Forms.TextBox
    $detail.Multiline = $true
    $detail.ReadOnly = $true
    $detail.Dock = 'Fill'
    $detail.ScrollBars = 'Both'
    $detail.WordWrap = $true
    $detail.Font = $mono
    $detail.BackColor = [System.Drawing.SystemColors]::Window
    $ui.Detail = $detail

    $rightSplit = New-Object System.Windows.Forms.SplitContainer
    $rightSplit.Dock = 'Fill'
    $rightSplit.Orientation = 'Horizontal'
    $rightSplit.Panel1.Controls.Add($list)
    $rightSplit.Panel1.Controls.Add($bar)
    $rightSplit.Panel2.Controls.Add($detail)

    $split = New-Object System.Windows.Forms.SplitContainer
    $split.Dock = 'Fill'
    $split.FixedPanel = 'Panel1'
    $split.Panel1.Controls.Add($leftSplit)
    $split.Panel2.Controls.Add($rightSplit)

    $form.Controls.Add($split)
    $form.Controls.Add($top)
    $form.Controls.Add($strip)

    $timer = New-Object System.Windows.Forms.Timer
    $timer.Interval = 120
    $ui.Timer = $timer

    # ---- events ----
    $ui.Split = $split
    $ui.LeftSplit = $leftSplit
    $ui.RightSplit = $rightSplit
    $form.add_Shown({
        # Splitter positions can only be set once the window has its real size.
        try {
            $script:ui.Split.SplitterDistance = 745
            $script:ui.LeftSplit.SplitterDistance = [int]($script:ui.LeftSplit.Height * 0.36)
            $script:ui.RightSplit.SplitterDistance = [int]($script:ui.RightSplit.Height * 0.78)
        } catch { }
        $script:ui.Timer.Start()
    })
    $form.add_FormClosed({
        $script:ui.Timer.Stop()
        if ($script:job) { $script:job.Cancel = $true }
    })

    $timer.add_Tick({
        try {
            $job = $script:job
            if ($job) {
                if ($job.Done) {
                    $kind = $script:jobKind
                    $script:job = $null
                    Complete-Job $job $kind
                } else {
                    $script:ui.Progress.Value = [Math]::Max(0, [Math]::Min(100, $job.Percent))
                }
            } elseif ($script:ui.Follow.Checked -and $script:snap -and ((Get-Date) - $script:lastRefresh).TotalSeconds -ge 2) {
                Start-Refresh
            }
            if ($script:detailDirty -and -not $script:job) {
                $script:detailDirty = $false
                Update-Detail
            }
        } catch {
            Set-Status ('Something went wrong: ' + $_.Exception.Message)
        }
    })

    $list.add_RetrieveVirtualItem({
        param($sender, $e)
        # This must always hand back an item: the list throws if it gets none.
        try {
            $line = $script:store.GetLine($script:snap, $script:view.LineAt($e.ItemIndex))
            $source = ''
            switch ($line.Mark) { '|' { $source = 'out' } '!' { $source = 'err' } '#' { $source = 'log' } }
            $text = $line.Text
            # The list control draws at most 259 characters of a cell; the box below shows the whole line.
            if ($text.Length -gt 259) { $text = $text.Substring(0, 256) + '...' }
            $item = [System.Windows.Forms.ListViewItem]::new([string[]]@($line.Time, $source, $text))
            $item.ForeColor = $script:kindColors[$line.Kind]
            if ($line.Kind -eq $KindMarker) { $item.BackColor = $script:markerBack }
            $e.Item = $item
        } catch {
            $e.Item = [System.Windows.Forms.ListViewItem]::new([string[]]@('', '', ''))
        }
    })
    $list.add_SelectedIndexChanged({ $script:detailDirty = $true })
    $list.add_VirtualItemsSelectionRangeChanged({ $script:detailDirty = $true })
    $list.add_DoubleClick({
        if ($script:filtered -and $script:ui.List.SelectedIndices.Count -gt 0) {
            Show-InContext ($script:view.LineAt($script:ui.List.SelectedIndices[0]))
        }
    })
    $list.add_KeyDown({
        param($sender, $e)
        if ($e.Control -and $e.KeyCode -eq 'C') { Copy-SelectedLines; $e.Handled = $true }
    })
    $menuCopy.add_Click({ Copy-SelectedLines })
    $menuContext.add_Click({
        if ($script:ui.List.SelectedIndices.Count -gt 0) { Show-InContext ($script:view.LineAt($script:ui.List.SelectedIndices[0])) }
    })
    $menuAround.add_Click({
        if ($script:ui.List.SelectedIndices.Count -eq 0) { return }
        $line = $script:store.GetLine($script:snap, $script:view.LineAt($script:ui.List.SelectedIndices[0]))
        $time = ConvertTo-Time $line.Time
        if (-not $time) { return }
        $script:quiet = $true
        try {
            $script:ui.From.Text = $time.AddMinutes(-5).ToString('yyyy-MM-dd HH:mm:ss')
            $script:ui.To.Text = $time.AddMinutes(5).ToString('yyyy-MM-dd HH:mm:ss')
        } finally { $script:quiet = $false }
        Start-Filter
    })

    $runs.add_SelectedIndexChanged({
        if ($script:quiet -or $script:ui.Runs.SelectedItems.Count -eq 0) { return }
        Select-Scope ([string]$script:ui.Runs.SelectedItems[0].Tag)
    })

    $show.add_SelectedIndexChanged({ if (-not $script:quiet) { Start-Filter } })
    $hideBeats.add_CheckedChanged({ if (-not $script:quiet) { Start-Filter } })
    $apply.add_Click({ Start-Filter })
    $clear.add_Click({ Clear-Filter; Start-Filter })
    $filterOnEnter = {
        param($sender, $e)
        if ($e.KeyCode -eq 'Return') { $e.SuppressKeyPress = $true; Start-Filter }
    }
    $find.add_KeyDown($filterOnEnter)
    $from.add_KeyDown($filterOnEnter)
    $to.add_KeyDown($filterOnEnter)

    $save.add_Click({
        if (-not $script:snap -or $script:view.Count -eq 0) { return }
        $dialog = New-Object System.Windows.Forms.SaveFileDialog
        $dialog.Filter = 'Text file (*.txt)|*.txt|All files (*.*)|*.*'
        $dialog.FileName = 'obsidianscout-log-excerpt.txt'
        if ($dialog.ShowDialog($script:ui.Form) -ne 'OK') { return }
        $script:ui.Form.Cursor = [System.Windows.Forms.Cursors]::WaitCursor
        try {
            $limit = 5000000
            $written = $script:store.Export($script:snap, $script:view, $dialog.FileName, $limit)
            $note = 'Saved {0:N0} lines to {1}' -f $written, $dialog.FileName
            if ($script:view.Count -gt $limit) { $note += ' (the first {0:N0}; filter to save a smaller part)' -f $limit }
            Set-Status $note
        } catch {
            [void][System.Windows.Forms.MessageBox]::Show('Could not save: ' + $_.Exception.Message, 'Save shown lines')
        } finally {
            $script:ui.Form.Cursor = [System.Windows.Forms.Cursors]::Default
        }
    })

    $open.add_Click({
        $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
        $dialog.Description = 'Folder with the server''s console-*.log files (the logs folder next to run.bat)'
        if ($script:store.Directory) { $dialog.SelectedPath = $script:store.Directory }
        if ($dialog.ShowDialog($script:ui.Form) -eq 'OK') { Open-LogFolder $dialog.SelectedPath }
    })
    $refresh.add_Click({ if ($script:store.Directory) { Start-Refresh } })
    $cancel.add_Click({ if ($script:job) { $script:job.Cancel = $true } })

    $analyze.add_Click({ Start-Summary })
    $errors.add_DoubleClick({
        if ($script:ui.Errors.SelectedItems.Count -gt 0) { Show-InContext $script:ui.Errors.SelectedItems[0].Tag.LastLine }
    })
    $lookup.add_Click({
        try { Find-WindowsEvents } catch { $script:ui.EventsNote.Text = 'Could not read the Windows event log: ' + $_.Exception.Message }
    })
    $events.add_SelectedIndexChanged({
        if ($script:ui.Events.SelectedItems.Count -gt 0) { $script:ui.Detail.Text = [string]$script:ui.Events.SelectedItems[0].Tag }
    })

    $form.add_KeyDown({
        param($sender, $e)
        if ($e.KeyCode -eq 'F5') { if ($script:store.Directory) { Start-Refresh }; $e.Handled = $true }
        elseif ($e.Control -and $e.KeyCode -eq 'F') { $script:ui.Find.Focus(); $script:ui.Find.SelectAll(); $e.Handled = $true }
        elseif ($e.KeyCode -eq 'Escape' -and $script:filtered) { Clear-Filter; Start-Filter; $e.Handled = $true }
    })

    return $form
}

function Resolve-LogFolder([string]$given) {
    if ($given) {
        if (Test-Path -LiteralPath $given -PathType Container) { return (Resolve-Path -LiteralPath $given).Path }
        throw "The folder '$given' does not exist."
    }
    # Next to run.bat in an installed server; one level up when run from the source tree's scripts folder.
    foreach ($candidate in @((Join-Path $PSScriptRoot 'logs'), (Join-Path (Split-Path -Parent $PSScriptRoot) 'logs'))) {
        if (Test-Path -LiteralPath $candidate -PathType Container) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    return ''
}

try {
    [System.Windows.Forms.Application]::EnableVisualStyles()
    $startFolder = Resolve-LogFolder $LogDir
    $script:form = New-ViewerForm
    if ($startFolder) {
        Open-LogFolder $startFolder
    } else {
        Set-Status 'No logs folder next to this script. Use "Open folder..." to pick the server''s logs folder.'
    }
} catch {
    [void][System.Windows.Forms.MessageBox]::Show("The log viewer could not start:`r`n`r`n" + $_.Exception.Message, 'ObsidianScout Log Viewer')
    exit 1
}
[void]$script:form.ShowDialog()
