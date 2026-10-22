#!/usr/bin/env python3
"""Build the table-churn dashboard and the daily anomaly summary.

Reads the per-pair churn reports (and snapshots, for row-count history) that
daily_table_churn.sh / docker/compare-loads-series.sh write, and produces:

  <out>/index.html      the dashboard: daily totals, a tables-by-days heatmap of
                        real churn, today's unusual changes, row-count trends
  <out>/anomalies.html  just today's unusual changes, for the job's email body
  <out>/anomalies.txt   the same as plain text (one line per finding)
  <out>/row_counts.csv  every table's row count at every snapshot (tables down, dates across)

"Unusual" is judged against each table's OWN recent history, so tables that are
rebuilt every day do not drown out a real spike: a table is flagged when its
real churn (raw churn where real is n/a) is at least FACTOR times its median
over the previous BASELINE days, and at least MIN_ROWS rows above that median.
Tables appearing or disappearing are flagged too, except names matching
IGNORE_TABLES (by default the *_old_<timestamp> leftovers of rename-swap regens).

Standard library only; the page uses no JavaScript or external resources.

Usage:
  churn_dashboard.py <churn_data_dir> [out_dir]     (out_dir default: <data>/dashboard)
Env:
  WINDOW (30)  BASELINE (28)  FACTOR (5)  MIN_ROWS (1000)
  IGNORE_TABLES (regex, default: _old_[0-9])
  FROM, TO     day labels (YYYY.MM.DD.N) bounding the days shown, for a report on any period;
               TO alone builds the page as it looked that day. Without FROM, the last WINDOW
               days up to TO are shown. Reports before FROM still feed the "usual" baseline.
               (AS_OF is accepted as an alias of TO.)
"""
import csv
import datetime
import html
import os
import re
import statistics
import sys
from collections import defaultdict

WINDOW = int(os.environ.get("WINDOW", 30))
BASELINE = int(os.environ.get("BASELINE", 28))
FACTOR = float(os.environ.get("FACTOR", 5))
MIN_ROWS = int(os.environ.get("MIN_ROWS", 1000))
IGNORE = re.compile(os.environ.get("IGNORE_TABLES", r"_old_[0-9]"))
TO = os.environ.get("TO") or os.environ.get("AS_OF", "")
FROM = os.environ.get("FROM", "")
HEATMAP_ROWS = 40
GRID_MAX_CELLS = 40000   # above this the row-count grid is CSV-only (too large for the page)
GRID_CSV = "row_counts.csv"
DAY = re.compile(r"^20\d\d\.\d\d\.\d\d\.\d+$")


def num(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def read_report(path):
    """{table: dict(real, raw, rows, status, real_known)}; None for a counts-only report."""
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows or "rows_added" not in rows[0]:
        return None
    out = {}
    for r in rows:
        raw = (num(r["rows_added"]) or 0) + (num(r["rows_removed"]) or 0)
        ra, rr = num(r.get("real_rows_added")), num(r.get("real_rows_removed"))
        known = ra is not None and rr is not None
        out[f'{r["schema_name"]}.{r["table_name"]}'] = dict(
            real=ra + rr if known else raw, raw=raw,
            added=ra if known else num(r["rows_added"]) or 0,
            removed=rr if known else num(r["rows_removed"]) or 0,
            rows=num(r["after_count"]) if r["after_count"] != "" else num(r["before_count"]) or 0,
            status=r["status"], real_known=known)
    return out


def read_snapshot_counts(path):
    with open(path, newline="") as f:
        return {f'{r["schema_name"]}.{r["table_name"]}': int(r["row_count"]) for r in csv.DictReader(f)}


def load(data_dir):
    reports = []   # (day, {table: cell}) oldest -> newest, day = the "after" side
    for name in os.listdir(os.path.join(data_dir, "reports")):
        if not name.endswith(".csv") or "_to_" not in name:
            continue
        day = name[:-4].split("_to_")[1]
        if DAY.match(day):
            data = read_report(os.path.join(data_dir, "reports", name))
            if data is not None:
                reports.append((day, data))
    reports.sort()
    if TO:
        reports = [r for r in reports if r[0] <= TO]
    counts = {}
    snap_dir = os.path.join(data_dir, "snapshots")
    for name in sorted(os.listdir(snap_dir)):
        if name.endswith(".csv") and DAY.match(name[:-4]) and (not TO or name[:-4] <= TO):
            counts[name[:-4]] = read_snapshot_counts(os.path.join(snap_dir, name))
    return reports, counts


def find_anomalies(reports):
    """Unusual changes in the newest report, judged against each table's own history."""
    if not reports:
        return []
    day, today = reports[-1]
    history = reports[-1 - BASELINE:-1]
    found = []
    for table, c in today.items():
        if IGNORE.search(table):
            continue
        if c["status"] in ("added", "removed"):
            found.append(dict(table=table, day=day, kind=f'table {c["status"]}', cell=c, baseline=0))
            continue
        past = [h.get(table, {}).get("real", 0) for _, h in history]
        base = statistics.median(past) if past else 0
        if c["real"] >= MIN_ROWS + base and c["real"] >= FACTOR * base:
            found.append(dict(table=table, day=day, kind="churn spike", cell=c, baseline=base))
    found.sort(key=lambda a: -(a["cell"]["real"] - a["baseline"]))
    return found


def bucket(real, rows):
    """Colour step 0-5 by real churn as a share of the table."""
    if real <= 0:
        return 0
    share = real / max(rows, 1)
    return 1 if share < 0.0001 else 2 if share < 0.001 else 3 if share < 0.01 else 4 if share < 0.1 else 5


def fmt(n):
    return f"{n:,}"


def short(day):
    return day[5:10]    # MM.DD


def gap_before(days):
    """Indexes of days that do not directly follow the previous day (missing reports)."""
    def date(d):
        return datetime.date(*map(int, d.split(".")[:3]))
    return {i for i in range(1, len(days)) if (date(days[i]) - date(days[i - 1])).days > 1}


def sparkline(series, w=90, h=18):
    vals = [v for v in series if v is not None]
    if len(vals) < 2:
        return ""
    lo, hi = min(vals), max(vals)
    span = (hi - lo) or 1
    pts = [(i * w / (len(series) - 1), h - 2 - (v - lo) * (h - 4) / span)
           for i, v in enumerate(series) if v is not None]
    path = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
    return (f'<svg class="spark" width="{w}" height="{h}" viewBox="0 0 {w} {h}" role="img" '
            f'aria-label="row count {fmt(vals[0])} to {fmt(vals[-1])}"><polyline points="{path}"/></svg>')


CSS = """
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
"""


def count_grid(counts, start=""):
    """(snapshot days from start on, tables seen in them) for the row-count grid."""
    days = [s for s in sorted(counts) if s >= start]
    tables = sorted({t for d in days for t in counts[d] if not IGNORE.search(t)})
    return days, tables


def write_count_csv(counts, path):
    days, tables = count_grid(counts)
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["table"] + days)
        for t in tables:
            w.writerow([t] + [counts[d].get(t, "") for d in days])


def render(reports, counts, anomalies, data_dir):
    window = [r for r in reports if r[0] >= FROM] if FROM else reports[-WINDOW:]
    days = [d for d, _ in window]
    period = f"{days[0]} to {days[-1]}" if days else "no reports"
    flagged = {(a["table"], a["day"]) for a in anomalies}
    e = html.escape

    # Heatmap rows: tables with the most real churn in the window, plus anything flagged today.
    total = defaultdict(int)
    for _, rep in window:
        for t, c in rep.items():
            if not IGNORE.search(t):
                total[t] += c["real"]
    picked = sorted((t for t in total if total[t] > 0), key=lambda t: -total[t])[:HEATMAP_ROWS]
    for a in anomalies:
        if a["table"] not in picked:
            picked.insert(0, a["table"])
    picked.sort(key=lambda t: (not any(a["table"] == t for a in anomalies), -total[t]))

    newest = days[-1] if days else "n/a"
    real_today = sum(c["real"] for c in window[-1][1].values()) if window else 0
    raw_today = sum(c["raw"] for c in window[-1][1].values()) if window else 0
    out = [f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Table Churn Dashboard</title>
<style>{CSS}</style></head><body><main>
<h1>Table churn</h1>
<p class="sub">Daily row churn per table, {e(period)} ({len(days)} days), from <code>{e(data_dir)}</code>. <em>Real</em> churn leaves out surrogate
ids and load timestamps (see <code>churn_columns.tsv</code>); <em>raw</em> is the whole row.</p>
<div class="cards">
<div class="card"><span class="muted">Latest day</span><b>{e(newest)}</b></div>
<div class="card"><span class="muted">Real churn that day</span><b>{fmt(real_today)}</b></div>
<div class="card"><span class="muted">Raw churn that day</span><b>{fmt(raw_today)}</b></div>
<div class="card"><span class="muted">Unusual changes</span><b>{len(anomalies)}</b></div>
</div>"""]

    out.append(f'<h2 id="anomalies">Unusual changes on {e(newest)}</h2>')
    out.append(anomaly_table(anomalies) if anomalies else
               '<p>None. Every table stayed within its usual range.</p>')
    out.append(f'<p class="muted">Flagged when real churn is at least {FACTOR:g}&times; the table\'s median over the '
               f'previous {BASELINE} days and at least {fmt(MIN_ROWS)} rows above it, or when a table appears or '
               f'disappears (ignoring <code>{e(IGNORE.pattern)}</code>).</p>')

    # Daily totals
    peak = max((sum(c["raw"] for c in r.values()) for _, r in window), default=1) or 1
    out.append(f'<h2>Daily totals, {e(period)}</h2><div class="scroll"><table class="totals">'
               '<caption class="muted" style="text-align:left;padding:6px 8px">Grey bar: raw churn. Blue bar: real churn (same scale).</caption>'
               '<thead><tr><th scope="col">Day</th><th scope="col" class="num">Real</th>'
               '<th scope="col" class="num">Raw</th><th scope="col">Real vs raw</th></tr></thead><tbody>')
    for d, rep in reversed(window):
        real = sum(c["real"] for c in rep.values())
        raw = sum(c["raw"] for c in rep.values())
        out.append(f'<tr><th scope="row">{e(d)}</th><td class="num">{fmt(real)}</td><td class="num">{fmt(raw)}</td>'
                   f'<td class="bar"><span class="raw" style="width:{100 * raw / peak:.2f}%"></span>'
                   f'<span class="real" style="width:{max(100 * real / peak, 0.3 if real else 0):.2f}%"></span></td></tr>')
    out.append("</tbody></table></div>")

    # Tables that appeared or disappeared during the period
    changes, ignored = [], 0
    for d, rep in window:
        for t, c in rep.items():
            if c["status"] in ("added", "removed"):
                if IGNORE.search(t):
                    ignored += 1
                else:
                    changes.append((d, t, c))
    out.append(f'<h2>Tables added or removed, {e(period)}</h2>')
    if changes:
        out.append('<table><thead><tr><th scope="col">Day</th><th scope="col">Table</th><th scope="col">Change</th>'
                   '<th scope="col" class="num">Rows</th></tr></thead><tbody>')
        for d, t, c in sorted(changes, reverse=True):
            out.append(f'<tr><td>{e(d)}</td><th scope="row">{e(t)}</th><td class="kind">table {e(c["status"])}</td>'
                       f'<td class="num">{fmt(c["rows"])}</td></tr>')
        out.append('</tbody></table>')
    else:
        out.append('<p>None.</p>')
    if ignored:
        out.append(f'<p class="muted">Not listed: {fmt(ignored)} added/removed tables matching '
                   f'<code>{e(IGNORE.pattern)}</code> (leftovers of rename-swap rebuilds).</p>')

    # Heatmap
    out.append(f'<h2>Real churn by table and day</h2>'
               '<div class="legend">Share of the table that changed:'
               '<span><i class="b0"></i>none</span><span><i class="b1"></i>&lt;0.01%</span>'
               '<span><i class="b2"></i>&lt;0.1%</span><span><i class="b3"></i>&lt;1%</span>'
               '<span><i class="b4"></i>&lt;10%</span><span><i class="b5"></i>&ge;10%</span>'
               '<span><i class="nd"></i>no report</span><span><i class="c raw b3"></i>raw only (real n/a)</span>'
               '<span><b style="border-left:3px solid var(--fg);padding-left:4px">|</b> days missing</span>'
               '<span><b style="color:var(--flag)">!</b> unusual</span>'
               '<span>Hover a cell for the numbers.</span></div>')
    gaps = gap_before(days)
    out.append('<div class="scroll"><table class="heat"><thead><tr><th scope="col" class="t">Table</th>'
               '<th scope="col" class="num">Rows now</th><th scope="col" class="num">Real churn / day</th>'
               '<th scope="col">Table size over time</th>')
    out += [f'<th scope="col" class="d{" gap" if i in gaps else ""}">{e(short(d))}</th>' for i, d in enumerate(days)]
    out.append('</tr></thead><tbody>')
    snap_days = [s for s in sorted(counts) if not FROM or s >= FROM]
    for t in picked:
        reported = sum(1 for _, rep in window if t in rep)
        per_day = total[t] / reported if reported else 0
        series = [counts[s].get(t) for s in snap_days]
        last = next((v for v in reversed(series) if v is not None), None)
        out.append(f'<tr><th scope="row" class="t">{e(t)}</th>'
                   f'<td class="num">{fmt(last) if last is not None else ""}</td>'
                   f'<td class="num">{fmt(round(per_day))}</td><td>{sparkline(series)}</td>')
        for i, (d, rep) in enumerate(window):
            g = " gap" if i in gaps else ""
            c = rep.get(t)
            if c is None:
                out.append(f'<td class="c nd{g}" title="no data"></td>')
                continue
            b = bucket(c["real"], c["rows"])
            fl = (t, d) in flagged
            tip = (f'{t} on {d}: real +{fmt(c["added"])} / -{fmt(c["removed"])}'
                   f'{"" if c["real_known"] else " (raw; real n/a)"}; raw {fmt(c["raw"])}; {fmt(c["rows"])} rows'
                   f'{"; UNUSUAL" if fl else ""}')
            cls = f'c b{b}{"" if c["real_known"] else " raw"}{" flag" if fl else ""}{g}'
            out.append(f'<td class="{cls}" title="{e(tip)}">{"!" if fl else ""}</td>')
        out.append('</tr>')
    out.append("</tbody></table></div>")
    out.append(f'<p class="muted">Top {HEATMAP_ROWS} tables by real churn over the period, flagged tables first. '
               f'"Real churn / day" is the table\'s average real churn per reported day in the period. '
               f'"Table size over time" is the table\'s total row count at each of the {len(snap_days)} snapshots, not churn ({e(snap_days[0]) if snap_days else ""} '
               f'to {e(snap_days[-1]) if snap_days else ""}).</p>')

    # Every table, including the ones that never changed: one compact row each (a
    # per-day heatmap of all ~1,200 tables would be far too large for long periods).
    stats = {}
    for d, rep in window:
        for t, c in rep.items():
            if IGNORE.search(t):
                continue
            s = stats.setdefault(t, {"days": 0, "changed": 0, "real": 0, "last": None})
            s["days"] += 1
            s["real"] += c["real"]
            if c["real"] > 0:
                s["changed"] += 1
                s["last"] = d
    unchanged = sum(1 for s in stats.values() if s["changed"] == 0)
    out.append(f'<details><summary><h2 style="display:inline">All {fmt(len(stats))} tables</h2> '
               f'<span class="muted">({fmt(len(stats) - unchanged)} changed in the period, {fmt(unchanged)} did not)</span>'
               f'</summary>')
    out.append('<div class="scroll"><table><thead><tr><th scope="col">Table</th><th scope="col" class="num">Rows now</th>'
               '<th scope="col" class="num">Days changed</th><th scope="col" class="num">Real churn, total</th>'
               '<th scope="col">Last changed</th></tr></thead><tbody>')
    last_counts = counts[snap_days[-1]] if snap_days else {}
    # How long each table's whole content has been identical (unchanged_since.sh, over all
    # snapshots up to now), for the tables with no change in the period. Skipped for a TO
    # page, which shows an earlier day.
    identical = {}
    us = os.path.join(data_dir, "unchanged_since.csv")
    if not TO and os.path.exists(us):
        with open(us, newline="") as f:
            for r in csv.DictReader(f):
                identical[f'{r["schema_name"]}.{r["table_name"]}'] = (
                    ("&ge; " if r.get("bound") == "at_least" else "") + e(r["unchanged_since"]))
    def no_change(t):
        since = identical.get(t)
        return f'<span class=muted>no change{f"; identical since {since}" if since else ""}</span>'

    for t in sorted(stats, key=lambda t: (-stats[t]["real"], t)):
        s = stats[t]
        rows_now = last_counts.get(t)
        out.append(f'<tr><th scope="row">{e(t)}</th><td class="num">{fmt(rows_now) if rows_now is not None else ""}</td>'
                   f'<td class="num">{s["changed"]} of {s["days"]}</td><td class="num">{fmt(s["real"])}</td>'
                   f'<td>{e(s["last"]) if s["last"] else no_change(t)}</td></tr>')
    out.append('</tbody></table></div>')
    out.append('<p class="muted">Most real churn first; tables that did not change in the period are last, by name. '
               '"Days changed" counts reported days with any real change (raw where real is n/a). '
               '"Identical since" is the first snapshot from which the whole table (every column) stayed the same; '
               '&ge; means since the first snapshot on disk.</p></details>')
    # Row counts: snapshot dates down, tables across. Always downloadable as CSV; shown
    # on the page too when it is small enough.
    # The page's period starts at the snapshot before its first report day.
    start = FROM or next((s for s in reversed(sorted(counts)) if days and s < days[0]), "")
    gdays, gtables = count_grid(counts, start)
    cells = len(gdays) * len(gtables)
    out.append(f'<h2>Row counts by date</h2><p>Every table\'s row count at each snapshot. '
               f'<a href="{GRID_CSV}" download>Download CSV</a> <span class="muted">(all {len(counts)} snapshots on '
               f'disk; rows: tables, columns: snapshot dates)</span></p>')
    if cells <= GRID_MAX_CELLS:
        out.append(f'<details><summary>Show the grid ({fmt(cells)} cells; a highlighted cell differs from the '
                   f'snapshot to its left)</summary><div class="grid-wrap"><table class="grid"><thead><tr>'
                   f'<th scope="col" class="corner">Table</th>')
        out += [f'<th scope="col" class="tn">{e(short(d))}</th>' for d in gdays]
        out.append('</tr></thead><tbody>')
        for t in gtables:
            out.append(f'<tr><th scope="row">{e(t)}</th>')
            prev = None
            for i, d in enumerate(gdays):
                v = counts[d].get(t)
                cls = ' class="num chg"' if i > 0 and v != prev else ' class="num"'
                out.append(f'<td{cls}>{fmt(v) if v is not None else ""}</td>')
                prev = v
            out.append('</tr>')
        out.append('</tbody></table></div></details>')
    else:
        out.append(f'<p class="muted">The grid has {fmt(cells)} cells, too many to show here; use the CSV '
                   f'(or a shorter period with FROM/TO).</p>')
    out.append("</main></body></html>")
    return "\n".join(out)


def anomaly_table(anomalies):
    e = html.escape
    rows = ['<table><thead><tr><th scope="col">Table</th><th scope="col">Finding</th>'
            '<th scope="col" class="num">Real +</th><th scope="col" class="num">Real &minus;</th>'
            '<th scope="col" class="num">Usual (median)</th><th scope="col" class="num">Raw</th>'
            '<th scope="col" class="num">Rows</th></tr></thead><tbody>']
    for a in anomalies:
        c = a["cell"]
        rows.append(f'<tr><th scope="row">{e(a["table"])}</th><td class="kind">{e(a["kind"])}</td>'
                    f'<td class="num">{fmt(c["added"])}</td><td class="num">{fmt(c["removed"])}</td>'
                    f'<td class="num">{fmt(int(a["baseline"]))}</td><td class="num">{fmt(c["raw"])}</td>'
                    f'<td class="num">{fmt(c["rows"])}</td></tr>')
    rows.append("</tbody></table>")
    return "".join(rows)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    data_dir = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(data_dir, "dashboard")
    os.makedirs(out_dir, exist_ok=True)
    reports, counts = load(data_dir)
    anomalies = find_anomalies(reports)

    with open(os.path.join(out_dir, "index.html"), "w") as f:
        f.write(render(reports, counts, anomalies, data_dir))
    write_count_csv(counts, os.path.join(out_dir, GRID_CSV))   # full history, whatever the page's period
    day = reports[-1][0] if reports else "n/a"
    with open(os.path.join(out_dir, "anomalies.html"), "w") as f:
        f.write(f"<h3>Table churn: unusual changes on {html.escape(day)}</h3>"
                + (anomaly_table(anomalies) if anomalies else "<p>None.</p>"))
    with open(os.path.join(out_dir, "anomalies.txt"), "w") as f:
        for a in anomalies:
            c = a["cell"]
            f.write(f'{a["day"]}\t{a["table"]}\t{a["kind"]}\treal +{c["added"]} -{c["removed"]}'
                    f'\tusual {int(a["baseline"])}\traw {c["raw"]}\trows {c["rows"]}\n')
    print(f"dashboard: {out_dir}/index.html  ({len(reports)} reports, {len(anomalies)} unusual on {day})")


if __name__ == "__main__":
    main()
