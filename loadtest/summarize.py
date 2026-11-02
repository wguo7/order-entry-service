"""Turns results/load.csv and the access log from loadtest/run.sh into results/load.md.

    python3 loadtest/summarize.py
"""

import csv
import statistics
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
rows = list(csv.DictReader(open(ROOT / "results/load.csv")))[1:]  # first row is the server warm-up
base = [r for r in rows if r["mode"] == "create" and r["rate"] == "500"]
sweep = [r for r in rows if r["mode"] == "create" and r["rate"] != "500"]
get = [r for r in rows if r["mode"] == "get"]


def ms(x):
    return f"{float(x):.2f}"


def spread(key):
    v = sorted(float(r[key]) for r in base)
    return f"{statistics.median(v):.2f} ({v[0]:.2f} to {v[-1]:.2f})"


def machine():
    chip = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True).stdout.strip()
    mem = int(subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout) // 2**30
    java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
    return f"{chip}, {mem} GB; {java}"


out = [f"# Load test\n\nMachine: {machine()}. Service, Postgres and load generator on the same machine.\n",
       f"## POST /orders at 500 requests/s, {len(base)} runs of {base[0]['seconds']} s",
       "Median across runs, with the range in brackets. Latency in ms, measured from when each request was due.\n",
       "| p50 | p90 | p99 | p99.9 | max | errors |", "|---|---|---|---|---|---|",
       f"| {spread('p50_ms')} | {spread('p90_ms')} | {spread('p99_ms')} | {spread('p999_ms')} | {spread('max_ms')} "
       f"| {sum(int(r['errors']) for r in base)} of {sum(int(r['sent']) for r in base)} |\n",
       "## POST /orders at higher rates, one run each\n",
       "| rate/s | achieved/s | p50 | p90 | p99 | p99.9 | max | errors |", "|---|---|---|---|---|---|---|---|"]
for r in sweep:
    out.append(f"| {r['rate']} | {r['achieved']} | {ms(r['p50_ms'])} | {ms(r['p90_ms'])} | {ms(r['p99_ms'])} "
               f"| {ms(r['p999_ms'])} | {ms(r['max_ms'])} | {r['errors']} of {r['sent']} |")
for r in get:
    out += ["\n## GET /orders/{id} at 500 requests/s\n", "| p50 | p90 | p99 | p99.9 | max | errors |",
            "|---|---|---|---|---|---|",
            f"| {ms(r['p50_ms'])} | {ms(r['p90_ms'])} | {ms(r['p99_ms'])} | {ms(r['p999_ms'])} | {ms(r['max_ms'])} "
            f"| {r['errors']} of {r['sent']} |"]

log = ROOT / "results/raw/access.log"
if log.exists():
    fields = [l.split() for l in open(log)]
    us = sorted(int(f[1]) for f in fields if f[3:5] == ["POST", "/orders"])
    if us:
        def p(q):
            return us[min(len(us) - 1, int(q * len(us)))] / 1000
        out += ["\n## Server-side time for POST /orders (Tomcat access log, every request including warm-ups)\n",
                f"{len(us):,} requests: p50 {p(.5):.2f} ms, p99 {p(.99):.2f} ms, p99.9 {p(.999):.2f} ms, "
                f"max {us[-1] / 1000:.2f} ms."]

(ROOT / "results/load.md").write_text("\n".join(out) + "\n")
print("\n".join(out[:9]))
