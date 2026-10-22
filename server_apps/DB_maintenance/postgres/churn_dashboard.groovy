#!/bin/bash
//opt/misc/groovy/bin/groovy "$0" "$@"; exit $?

/*
 * Build the table-churn dashboard and the daily anomaly summary.
 *
 * Reads the per-pair churn reports (and snapshots, for row-count history) that
 * daily_table_churn.sh / docker/compare-loads-series.sh write, and produces:
 *
 *   <out>/index.html      the dashboard: daily totals, a tables-by-days heatmap of
 *                         real churn, today's unusual changes, row-count trends
 *   <out>/anomalies.html  just today's unusual changes, for the job's email body
 *   <out>/anomalies.txt   the same as plain text (one line per finding)
 *   <out>/row_counts.csv  every table's row count at every snapshot (tables down, dates across)
 *
 * "Unusual" is judged against each table's OWN recent history, so tables that are
 * rebuilt every day do not drown out a real spike: a table is flagged when its
 * real churn (raw churn where real is n/a) is at least FACTOR times its median
 * over the previous BASELINE days, and at least MIN_ROWS rows above that median.
 * Tables appearing or disappearing are flagged too, except names matching
 * IGNORE_TABLES (by default the *_old_<timestamp> leftovers of rename-swap regens).
 *
 * Groovy/JDK only (no classpath needed); the page uses no JavaScript or external resources.
 *
 * Usage:
 *   churn_dashboard.groovy <churn_data_dir> [out_dir]     (out_dir default: <data>/dashboard)
 * Env:
 *   WINDOW (30)  BASELINE (28)  FACTOR (5)  MIN_ROWS (1000)
 *   IGNORE_TABLES (regex, default: _old_[0-9])
 *   FROM, TO     day labels (YYYY.MM.DD.N) bounding the days shown, for a report on any period;
 *                TO alone builds the page as it looked that day. Without FROM, the last WINDOW
 *                days up to TO are shown. Reports before FROM still feed the "usual" baseline.
 *                (AS_OF is accepted as an alias of TO.)
 */

import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern

def env = System.getenv()
int WINDOW = (env.WINDOW ?: '30') as int
int BASELINE = (env.BASELINE ?: '28') as int
double FACTOR = (env.FACTOR ?: '5') as double
long MIN_ROWS = (env.MIN_ROWS ?: '1000') as long
Pattern IGNORE = Pattern.compile(env.IGNORE_TABLES ?: '_old_[0-9]')
String TO = env.TO ?: (env.AS_OF ?: '')
String FROM = env.FROM ?: ''
int GRID_MAX_CELLS = 40000   // above this the row-count grid is CSV-only (too large for the page)
String GRID_CSV = 'row_counts.csv'
Pattern DAY = ~/^20\d\d\.\d\d\.\d\d\.\d+$/

// ---- small helpers -----------------------------------------------------------

/** Minimal RFC 4180 reader: list of rows (lists of fields); handles quotes and "" escapes. */
List<List<String>> readCsvRows(File f) {
    List<List<String>> rows = []
    List<String> row = []
    StringBuilder field = new StringBuilder()
    boolean quoted = false, any = false
    String s = f.getText('UTF-8')
    int i = 0
    while (i < s.length()) {
        char ch = s.charAt(i)
        if (quoted) {
            if (ch == '"' as char) {
                if (i + 1 < s.length() && s.charAt(i + 1) == '"' as char) { field.append('"'); i++ }
                else quoted = false
            } else field.append(ch)
        } else if (ch == '"' as char) { quoted = true; any = true }
        else if (ch == ',' as char) { row << field.toString(); field.setLength(0); any = true }
        else if (ch == '\r' as char) { /* part of \r\n */ }
        else if (ch == '\n' as char) {
            row << field.toString(); field.setLength(0)
            rows << row; row = []; any = false
        } else { field.append(ch); any = true }
        i++
    }
    if (any || field.length() > 0) { row << field.toString(); rows << row }
    return rows
}

/** CSV as a list of header-keyed maps (like csv.DictReader). */
List<Map<String, String>> readCsv(File f) {
    List<List<String>> rows = readCsvRows(f)
    if (!rows) return []
    List<String> header = rows[0]
    return rows.drop(1).findAll { r -> !(r.size() == 1 && r[0] == '') }.collect { r ->   // DictReader skips blank lines
        Map<String, String> m = [:]
        header.eachWithIndex { h, i -> m[h] = i < r.size() ? r[i] : null }
        m
    }
}

String csvField(Object v) {
    String s = v == null ? '' : v.toString()
    return (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) ?
        '"' + s.replace('"', '""') + '"' : s
}

Long num(String v) {
    if (v == null) return null
    try { return Long.parseLong(v.trim()) } catch (NumberFormatException ignored) { return null }
}

/** Same as Python's html.escape(s, quote=True). */
String e(Object o) {
    String s = o == null ? '' : o.toString()
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;').replace("'", '&#x27;')
}

String fmt(long n) { String.format(Locale.US, '%,d', n) }

/** Python's f"{x:.Nf}": round the exact binary value, ties to even. */
String fixed(double x, int places) {
    new BigDecimal(x).setScale(places, RoundingMode.HALF_EVEN).toPlainString()
}

/** Python's f"{x:g}" for the plain values used here. */
String gfmt(double x) {
    BigDecimal b = new BigDecimal(x).round(new java.math.MathContext(6)).stripTrailingZeros()
    return b.scale() < 0 ? b.setScale(0).toPlainString() : b.toPlainString()
}

String shortDay(String day) { day.substring(5, 10) }   // MM.DD

LocalDate dateOf(String d) {
    String[] p = d.split('\\.')
    LocalDate.of(p[0] as int, p[1] as int, p[2] as int)
}

/** Indexes of days that do not directly follow the previous day (missing reports). */
Set<Integer> gapBefore(List<String> days) {
    Set<Integer> gaps = [] as Set
    for (int i = 1; i < days.size(); i++)
        if (ChronoUnit.DAYS.between(dateOf(days[i - 1]), dateOf(days[i])) > 1) gaps << i
    return gaps
}

int bucket(long real, long rows) {
    if (real <= 0) return 0
    double share = real / (double) Math.max(rows, 1L)
    return share < 0.0001 ? 1 : share < 0.001 ? 2 : share < 0.01 ? 3 : share < 0.1 ? 4 : 5
}

String sparkline(List<Long> series, int w = 90, int h = 18) {
    List<Long> vals = series.findAll { it != null }
    if (vals.size() < 2) return ''
    long lo = vals.min(), hi = vals.max()
    long span = (hi - lo) ?: 1L
    List<String> pts = []
    series.eachWithIndex { Long v, int i ->
        if (v != null) {
            double x = (i * (long) w) / (double) (series.size() - 1)
            double y = h - 2 - ((v - lo) * (long) (h - 4)) / (double) span
            pts << fixed(x, 1) + ',' + fixed(y, 1)
        }
    }
    return "<svg class=\"spark\" width=\"${w}\" height=\"${h}\" viewBox=\"0 0 ${w} ${h}\" role=\"img\" " +
           "aria-label=\"row count ${fmt(vals[0])} to ${fmt(vals[-1])}\"><polyline points=\"${pts.join(' ')}\"/></svg>"
}

// ---- input ---------------------------------------------------------------------

/** [table: cell]; null for a counts-only report. */
Map<String, Map> readReport(File f) {
    List<Map<String, String>> rows = readCsv(f)
    if (!rows || !rows[0].containsKey('rows_added')) return null
    Map<String, Map> out = [:]   // LinkedHashMap: keeps the report's row order
    for (r in rows) {
        long raw = (num(r.rows_added) ?: 0L) + (num(r.rows_removed) ?: 0L)
        Long ra = num(r.real_rows_added), rr = num(r.real_rows_removed)
        boolean known = ra != null && rr != null
        out[r.schema_name + '.' + r.table_name] = [
            real      : known ? ra + rr : raw,
            raw       : raw,
            added     : known ? ra : (num(r.rows_added) ?: 0L),
            removed   : known ? rr : (num(r.rows_removed) ?: 0L),
            rows      : r.after_count != '' ? num(r.after_count) : (num(r.before_count) ?: 0L),
            status    : r.status,
            real_known: known]
    }
    return out
}

Map<String, Long> readSnapshotCounts(File f) {
    Map<String, Long> m = [:]
    readCsv(f).each { r -> m[r.schema_name + '.' + r.table_name] = Long.parseLong(r.row_count.trim()) }
    return m
}

// ---- report sections -------------------------------------------------------------

String anomalyTable(List<Map> anomalies) {
    List<String> rows = ['<table><thead><tr><th scope="col">Table</th><th scope="col">Finding</th>' +
            '<th scope="col" class="num">Real +</th><th scope="col" class="num">Real &minus;</th>' +
            '<th scope="col" class="num">Usual (median)</th><th scope="col" class="num">Raw</th>' +
            '<th scope="col" class="num">Rows</th></tr></thead><tbody>']
    for (a in anomalies) {
        Map c = a.cell
        rows << ('<tr><th scope="row">' + e(a.table) + '</th><td class="kind">' + e(a.kind) + '</td>' +
                 '<td class="num">' + fmt(c.added) + '</td><td class="num">' + fmt(c.removed) + '</td>' +
                 '<td class="num">' + fmt((long) a.baseline) + '</td><td class="num">' + fmt(c.raw) + '</td>' +
                 '<td class="num">' + fmt(c.rows) + '</td></tr>')
    }
    rows << '</tbody></table>'
    return rows.join('')
}

// ---- main ------------------------------------------------------------------------

if (args.length < 1) {
    System.err.println('usage: churn_dashboard.groovy <churn_data_dir> [out_dir]   (see the header for env settings)')
    System.exit(1)
}
String dataDir = args[0]
File outDir = new File(args.length > 1 ? args[1] : new File(dataDir, 'dashboard').path)
outDir.mkdirs()

// reports: [day, {table: cell}] oldest -> newest, day = the "after" side
List<List> reports = []
new File(dataDir, 'reports').listFiles().each { File f ->
    String name = f.name
    if (!name.endsWith('.csv') || !name.contains('_to_')) return
    String day = name[0..-5].split('_to_', -1)[1]
    if (day ==~ DAY) {
        Map data = readReport(f)
        if (data != null) reports << [day, data]
    }
}
reports.sort { it[0] }
if (TO) reports = reports.findAll { it[0] <= TO }
Map<String, Map<String, Long>> counts = new TreeMap<>()
new File(dataDir, 'snapshots').listFiles().findAll { it.name.endsWith('.csv') }.sort { it.name }.each { File f ->
    String d = f.name[0..-5]
    if (d ==~ DAY && (!TO || d <= TO)) counts[d] = readSnapshotCounts(f)
}

// Unusual changes in the newest report, judged against each table's own history.
List<Map> anomalies = []
if (reports) {
    String day = reports[-1][0]
    Map<String, Map> today = reports[-1][1]
    List<List> history = reports.subList(Math.max(0, reports.size() - 1 - BASELINE), reports.size() - 1)
    today.each { String table, Map c ->
        if (IGNORE.matcher(table).find()) return
        if (c.status in ['added', 'removed']) {
            anomalies << [table: table, day: day, kind: "table ${c.status}".toString(), cell: c, baseline: 0d]
            return
        }
        List<Long> past = history.collect { h -> (h[1][table]?.real ?: 0L) as long }.sort()
        double base = 0
        if (past) {
            int n = past.size()
            base = n % 2 ? past[n.intdiv(2)] : (past[n.intdiv(2) - 1] + past[n.intdiv(2)]) / 2.0d
        }
        if (c.real >= MIN_ROWS + base && c.real >= FACTOR * base)
            anomalies << [table: table, day: day, kind: 'churn spike', cell: c, baseline: base]
    }
    anomalies.sort { a -> -((a.cell.real as long) - (a.baseline as double)) }
}

// ---- index.html ------------------------------------------------------------------

String CSS = '''
:root{--bg:#fff;--fg:#1d2330;--muted:#5d6675;--line:#d9dde4;--card:#f6f7f9;--accent:#2457c5;
--h0:#f1f3f6;--h1:#dbe7fb;--h2:#a9c6f3;--h3:#6f9ee6;--h4:#3c72cf;--h5:#1b469a;--flag:#c2410c;--raw:#c9cfd8}
@media (prefers-color-scheme:dark){:root{--bg:#14171c;--fg:#e4e7ec;--muted:#9aa3b2;--line:#2c323c;--card:#1b1f26;
--accent:#7aa5f5;--h0:#1f242c;--h1:#1f3354;--h2:#25467a;--h3:#2f5fa8;--h4:#4d82d6;--h5:#8db4f2;--flag:#fb923c;--raw:#3a414d}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
font:14px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}
main{max-width:1280px;margin:0 auto;padding:24px 16px 48px}
h1{font-size:22px;margin:0 0 4px}h2{font-size:16px;margin:32px 0 8px}
p.sub,.muted{color:var(--muted)}.num{text-align:right;font-variant-numeric:tabular-nums}
.cards{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 14px;min-width:150px}
.card b{display:block;font-size:20px}
table{border-collapse:collapse}th,td{padding:4px 8px;border-bottom:1px solid var(--line);vertical-align:middle}
th{text-align:left;font-weight:600;color:var(--muted)}
.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:8px}
.totals td.bar{width:100%;min-width:200px}
.bar span{display:block;height:10px;border-radius:2px}
.bar .raw{background:var(--raw)}.bar .real{background:var(--accent);margin-top:2px}
.heat td.c{width:22px;min-width:22px;height:22px;padding:0;border:1px solid var(--bg);text-align:center;font-size:11px}
.heat th.d{writing-mode:vertical-rl;transform:rotate(180deg);font-weight:400;font-size:11px;padding:4px 2px}
.heat th.t{white-space:nowrap;font-weight:500;color:var(--fg);position:sticky;left:0;background:var(--bg)}
.b0{background:var(--h0)}.b1{background:var(--h1)}.b2{background:var(--h2)}.b3{background:var(--h3)}
.b4{background:var(--h4);color:#fff}.b5{background:var(--h5);color:#fff}
@media (prefers-color-scheme:dark){.b4,.b5{color:#0b0d10}}
td.c.flag{outline:2px solid var(--flag);outline-offset:-2px;font-weight:700}
.c.raw{background-image:repeating-linear-gradient(135deg,transparent,transparent 3px,rgba(127,127,127,.45) 3px,rgba(127,127,127,.45) 4px)}
.heat .gap{border-left:3px solid var(--fg)}
.nd{background:repeating-linear-gradient(45deg,var(--h0),var(--h0) 3px,var(--bg) 3px,var(--bg) 6px)}
.legend{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin:8px 0;color:var(--muted);font-size:12px}
.legend i{display:inline-block;width:14px;height:14px;vertical-align:middle;margin-right:4px;border-radius:2px}
.spark polyline{fill:none;stroke:var(--accent);stroke-width:1.5}
.kind{color:var(--flag);font-weight:600}
.grid-wrap{max-height:70vh;overflow:auto;border:1px solid var(--line);border-radius:8px}
.grid{font-size:12px}.grid td,.grid th{padding:2px 6px;white-space:nowrap}
.grid thead th{position:sticky;top:0;background:var(--bg);z-index:2}
.grid th.tn{writing-mode:vertical-rl;transform:rotate(180deg);font-weight:400;color:var(--fg);padding:6px 2px}
.grid tbody th{position:sticky;left:0;background:var(--bg);z-index:1}
.grid thead th.corner{left:0;z-index:3}
.grid td.chg{background:var(--h2);font-weight:600}
.nowrap,.heat td.num{white-space:nowrap}
'''

/** [snapshot days from start on, tables seen in them] for the row-count grid. */
List countGrid(Map<String, Map<String, Long>> cts, String start, Pattern ignore) {
    List<String> days = (cts.keySet() as List<String>).findAll { it >= start }
    Set<String> tables = new TreeSet<>()
    days.each { d -> cts[d].keySet().each { t -> if (!ignore.matcher(t).find()) tables << t } }
    return [days, tables as List]
}

List<List> window = FROM ? reports.findAll { it[0] >= FROM } : reports.subList(Math.max(0, reports.size() - WINDOW), reports.size())
List<String> days = window.collect { it[0] as String }
String period = days ? "${days[0]} to ${days[-1]}".toString() : 'no reports'
Set<String> flagged = anomalies.collect { "${it.table}\u0000${it.day}".toString() } as Set

// Per-table stats over the window (every table, including ones that never changed).
Map<String, Map> stats = [:]
window.each { w -> w[1].each { String t, Map c ->
    if (IGNORE.matcher(t).find()) return
    Map s = stats.computeIfAbsent(t) { [days: 0, changed: 0, real: 0L, last: null] }
    s.days++
    s.real += c.real as long
    if ((c.real as long) > 0) { s.changed++; s.last = w[0] }
} }
stats.values().each { it.perDay = it.days ? (it.real as long) / (double) it.days : 0d }
// Heatmap order: tables flagged today first, then real churn per day (highest first), then name.
Set<String> anomalyTables = anomalies.collect { it.table as String } as Set
List<String> tableOrder = (stats.keySet() as List<String>).sort(false) { a, b ->
    int fa = anomalyTables.contains(a) ? 0 : 1, fb = anomalyTables.contains(b) ? 0 : 1
    fa <=> fb ?: (stats[b].perDay as double) <=> (stats[a].perDay as double) ?: a <=> b
}

String newest = days ? days[-1] : 'n/a'
long realToday = window ? window[-1][1].values().sum(0L) { it.real } as long : 0L
long rawToday = window ? window[-1][1].values().sum(0L) { it.raw } as long : 0L
List<String> out = []
out << ("""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Table Churn Dashboard</title>
<style>${CSS}</style></head><body><main>
<h1>Table churn</h1>
<p class="sub">Daily row churn per table, ${e(period)} (${days.size()} days), from <code>${e(dataDir)}</code>. <em>Real</em> churn leaves out surrogate
ids and load timestamps (see <code>churn_columns.tsv</code>); <em>raw</em> is the whole row.</p>
<div class="cards">
<div class="card"><span class="muted">Latest day</span><b>${e(newest)}</b></div>
<div class="card"><span class="muted">Real churn that day</span><b>${fmt(realToday)}</b></div>
<div class="card"><span class="muted">Raw churn that day</span><b>${fmt(rawToday)}</b></div>
<div class="card"><span class="muted">Unusual changes</span><b>${anomalies.size()}</b></div>
</div>""" as String)

out << ('<h2 id="anomalies">Unusual changes on ' + e(newest) + '</h2>')
out << (anomalies ? anomalyTable(anomalies) : '<p>None. Every table stayed within its usual range.</p>')
out << ('<p class="muted">Flagged when real churn is at least ' + gfmt(FACTOR) + '&times; the table\'s median over the ' +
        'previous ' + BASELINE + ' days and at least ' + fmt(MIN_ROWS) + ' rows above it, or when a table appears or ' +
        'disappears (ignoring <code>' + e(IGNORE.pattern()) + '</code>).</p>')

// Daily totals
long peak = window ? (window.collect { w -> w[1].values().sum(0L) { it.raw } as long }.max()) : 1L
if (peak == 0) peak = 1
out << ('<h2>Daily totals, ' + e(period) + '</h2><div class="scroll"><table class="totals">' +
        '<caption class="muted" style="text-align:left;padding:6px 8px">Grey bar: raw churn. Blue bar: real churn (same scale).</caption>' +
        '<thead><tr><th scope="col">Day</th><th scope="col" class="num">Real</th>' +
        '<th scope="col" class="num">Raw</th><th scope="col">Real vs raw</th></tr></thead><tbody>')
window.reverse().each { w ->
    long real = w[1].values().sum(0L) { it.real } as long
    long raw = w[1].values().sum(0L) { it.raw } as long
    double realW = Math.max(100.0d * real / peak, real ? 0.3d : 0d)
    out << ('<tr><th scope="row">' + e(w[0]) + '</th><td class="num">' + fmt(real) + '</td><td class="num">' + fmt(raw) + '</td>' +
            '<td class="bar"><span class="raw" style="width:' + fixed(100.0d * raw / peak, 2) + '%"></span>' +
            '<span class="real" style="width:' + fixed(realW, 2) + '%"></span></td></tr>')
}
out << '</tbody></table></div>'

// Tables that appeared or disappeared during the period
List<List> changes = []
long ignoredChanges = 0
window.each { w -> w[1].each { String t, Map c ->
    if (c.status in ['added', 'removed']) {
        if (IGNORE.matcher(t).find()) ignoredChanges++
        else changes << [w[0], t, c]
    }
} }
out << ('<h2>Tables added or removed, ' + e(period) + '</h2>')
if (changes) {
    out << ('<table><thead><tr><th scope="col">Day</th><th scope="col">Table</th><th scope="col">Change</th>' +
            '<th scope="col" class="num">Rows</th></tr></thead><tbody>')
    changes.sort(false) { a, b -> b[0] <=> a[0] ?: b[1] <=> a[1] }.each { ch ->
        out << ('<tr><td>' + e(ch[0]) + '</td><th scope="row">' + e(ch[1]) + '</th><td class="kind">table ' + e(ch[2].status) + '</td>' +
                '<td class="num">' + fmt(ch[2].rows as long) + '</td></tr>')
    }
    out << '</tbody></table>'
} else {
    out << '<p>None.</p>'
}
if (ignoredChanges)
    out << ('<p class="muted">Not listed: ' + fmt(ignoredChanges) + ' added/removed tables matching ' +
            '<code>' + e(IGNORE.pattern()) + '</code> (leftovers of rename-swap rebuilds).</p>')

// Heatmap: every table, one row each, with its numbers for the period.
int unchanged = stats.values().count { it.changed == 0 } as int
out << ('<h2>Real churn by table and day</h2>' +
        '<p class="muted">' + fmt(stats.size()) + ' tables: ' + fmt(stats.size() - unchanged) + ' changed in the period, ' +
        fmt(unchanged) + ' did not.</p>' +
        '<div class="legend">Share of the table that changed:' +
        '<span><i class="b0"></i>none</span><span><i class="b1"></i>&lt;0.01%</span>' +
        '<span><i class="b2"></i>&lt;0.1%</span><span><i class="b3"></i>&lt;1%</span>' +
        '<span><i class="b4"></i>&lt;10%</span><span><i class="b5"></i>&ge;10%</span>' +
        '<span><i class="nd"></i>no report</span><span><i class="c raw b3"></i>raw only (real n/a)</span>' +
        '<span><b style="border-left:3px solid var(--fg);padding-left:4px">|</b> days missing</span>' +
        '<span><b style="color:var(--flag)">!</b> unusual</span>' +
        '<span>Hover a coloured cell for the numbers.</span></div>')
// The period's snapshots: from the one before the first report day (or FROM) to the newest.
String start = FROM ?: ((days ? (counts.keySet() as List<String>).findAll { it < days[0] } : []).with { it ? it.max() : '' })
List<String> snapDays = (counts.keySet() as List<String>).findAll { it >= start }
Map<String, Long> firstCounts = snapDays ? counts[snapDays[0]] : [:]
Map<String, Long> lastCounts = snapDays ? counts[snapDays[-1]] : [:]
// How long each table's whole content has been identical (unchanged_since.sh, over all
// snapshots up to now), for the tables with no change in the period. Skipped for a TO
// page, which shows an earlier day.
Map<String, String> identical = [:]
File us = new File(dataDir, 'unchanged_since.csv')
if (!TO && us.exists())
    readCsv(us).each { r -> identical[r.schema_name + '.' + r.table_name] = (r.bound == 'at_least' ? '&ge; ' : '') + e(r.unchanged_since) }
Set<Integer> gaps = gapBefore(days)
out << ('<div class="scroll"><table class="heat"><thead><tr><th scope="col" class="t">Table</th>' +
        '<th scope="col" class="num">Starting rows</th><th scope="col" class="num">Rows now</th>' +
        '<th scope="col" class="num">Real churn / day</th><th scope="col" class="num">Days changed</th>' +
        '<th scope="col">Last changed</th><th scope="col">Table size over time</th>')
days.eachWithIndex { String d, int i -> out << ('<th scope="col" class="d' + (i in gaps ? ' gap' : '') + '">' + e(shortDay(d)) + '</th>') }
out << '</tr></thead><tbody>'
for (String t in tableOrder) {
    Map s = stats[t]
    Long rowsStart = firstCounts[t], rowsNow = lastCounts[t]
    String since = identical[t]
    String lastCell = s.last ? e(s.last) : ('<span class=muted>no change' + (since ? '; identical since ' + since : '') + '</span>')
    StringBuilder row = new StringBuilder()
    row << '<tr><th scope="row" class="t">' << e(t) << '</th>' <<
           '<td class="num">' << (rowsStart != null ? fmt(rowsStart) : '') << '</td>' <<
           '<td class="num">' << (rowsNow != null ? fmt(rowsNow) : '') << '</td>' <<
           '<td class="num">' << fmt(Math.rint(s.perDay as double) as long) << '</td>' <<
           '<td class="num">' << s.changed << ' of ' << s.days << '</td>' <<
           '<td class="nowrap">' << lastCell << '</td>' <<
           '<td>' << sparkline(snapDays.collect { counts[it][t] }) << '</td>'
    // Cells are kept small (the full-year page has ~200k): no tooltip where nothing happened.
    window.eachWithIndex { w, int i ->
        String g = i in gaps ? ' gap' : ''
        Map c = w[1][t]
        if (c == null) { row << '<td class="c nd' << g << '"></td>'; return }
        int b = bucket(c.real as long, c.rows as long)
        boolean fl = flagged.contains("${t}\u0000${w[0]}".toString())
        String cls = 'c b' + b + (c.real_known ? '' : ' raw') + (fl ? ' flag' : '') + g
        if ((c.real as long) == 0 && (c.raw as long) == 0 && !fl) { row << '<td class="' << cls << '"></td>'; return }
        String tip = w[0] + ': real +' + fmt(c.added as long) + ' / -' + fmt(c.removed as long) +
                (c.real_known ? '' : ' (raw; real n/a)') + '; raw ' + fmt(c.raw as long) + '; ' + fmt(c.rows as long) + ' rows' +
                (fl ? '; UNUSUAL' : '')
        row << '<td class="' << cls << '" title="' << e(tip) << '">' << (fl ? '!' : '') << '</td>'
    }
    row << '</tr>'
    out << row.toString()
}
out << '</tbody></table></div>'
out << ('<p class="muted">Tables flagged today first, then by real churn per day, highest first. ' +
        '"Starting rows" and "Rows now" are the row counts at the first and last snapshot of the period (' +
        (snapDays ? e(snapDays[0]) : '') + ' and ' + (snapDays ? e(snapDays[-1]) : '') + '; blank: the table did not exist then). ' +
        '"Real churn / day" is the average real churn per reported day; "Days changed" counts reported days with any real ' +
        'change (raw where real is n/a). For a table with no change in the period, "identical since" is the first snapshot ' +
        'from which the whole table (every column) stayed the same; &ge; means since the first snapshot on disk. ' +
        '"Table size over time" is the row count at each of the ' + snapDays.size() + ' snapshots, not churn.</p>')

// Row counts: tables down, snapshot dates across. Always downloadable as CSV; shown on
// the page too when it is small enough. The page's period starts at the snapshot before
// its first report day.
def (List<String> gdays, List<String> gtables) = countGrid(counts, start, IGNORE)
long cells = (long) gdays.size() * gtables.size()
out << ('<h2>Row counts by date</h2><p>Every table\'s row count at each snapshot. ' +
        '<a href="' + GRID_CSV + '" download>Download CSV</a> <span class="muted">(all ' + counts.size() + ' snapshots on ' +
        'disk; rows: tables, columns: snapshot dates)</span></p>')
if (cells <= GRID_MAX_CELLS) {
    out << ('<details><summary>Show the grid (' + fmt(cells) + ' cells; a highlighted cell differs from the ' +
            'snapshot to its left)</summary><div class="grid-wrap"><table class="grid"><thead><tr>' +
            '<th scope="col" class="corner">Table</th>')
    gdays.each { d -> out << ('<th scope="col" class="tn">' + e(shortDay(d)) + '</th>') }
    out << '</tr></thead><tbody>'
    for (String t in gtables) {
        out << ('<tr><th scope="row">' + e(t) + '</th>')
        Long prev = null
        gdays.eachWithIndex { String d, int i ->
            Long v = counts[d][t]
            String cls = i > 0 && v != prev ? ' class="num chg"' : ' class="num"'
            out << ('<td' + cls + '>' + (v != null ? fmt(v) : '') + '</td>')
            prev = v
        }
        out << '</tr>'
    }
    out << '</tbody></table></div></details>'
} else {
    out << ('<p class="muted">The grid has ' + fmt(cells) + ' cells, too many to show here; use the CSV ' +
            '(or a shorter period with FROM/TO).</p>')
}
out << '</main></body></html>'
new File(outDir, 'index.html').setText(out.join('\n'), 'UTF-8')

// ---- row_counts.csv: full history, whatever the page's period ------------------------

def (List<String> cdays, List<String> ctables) = countGrid(counts, '', IGNORE)
new File(outDir, GRID_CSV).withWriter('UTF-8') { wr ->
    wr << (['table'] + cdays).collect { csvField(it) }.join(',') << '\r\n'
    for (String t in ctables)
        wr << ([t] + cdays.collect { d -> counts[d][t] }).collect { csvField(it) }.join(',') << '\r\n'
}

// ---- anomalies.html / anomalies.txt ----------------------------------------------------

String day = reports ? reports[-1][0] : 'n/a'
new File(outDir, 'anomalies.html').setText('<h3>Table churn: unusual changes on ' + e(day) + '</h3>' +
        (anomalies ? anomalyTable(anomalies) : '<p>None.</p>'), 'UTF-8')
new File(outDir, 'anomalies.txt').withWriter('UTF-8') { wr ->
    anomalies.each { a ->
        Map c = a.cell
        wr << "${a.day}\t${a.table}\t${a.kind}\treal +${c.added} -${c.removed}\tusual ${(long) a.baseline}\traw ${c.raw}\trows ${c.rows}\n"
    }
}
println "dashboard: ${outDir.path}/index.html  (${reports.size()} reports, ${anomalies.size()} unusual on ${day})"
