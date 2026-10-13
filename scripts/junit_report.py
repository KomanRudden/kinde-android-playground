#!/usr/bin/env python3
"""Turn JUnit XML from the regression suites into one JSON summary and one HTML page.

Usage: junit_report.py OUT_DIR "Suite name|about text|results_dir" [...]

Outcomes: passed, failed, skipped, known (a @KnownIssue test: the SDK defect it
describes is still present). The instrumented runner records assumption
failures as <failure>; those are mapped back to skipped/known here.
"""
from __future__ import annotations

import html
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

# class name -> (area, what the file checks)
EXPLAINS = {
    "SessionRestoreTest": ("Session", "Restoring a saved session and how isAuthenticated() verifies it."),
    "LoginFlowTest": ("Auth", "What login, register, createOrg and invitations send, and the code exchange."),
    "LogoutTest": ("Auth", "The end-session round trip, cancellation and what logout clears."),
    "TokenRefreshTest": ("Tokens", "The automatic refresh schedule, rotation and failure handling."),
    "AccountApiTest": ("Account API", "Profile endpoints, permissions, roles, flags, entitlements, caching and 401/403 handling."),
    "ManagementApiTest": ("Management", "getUsers and createUser against Kinde's real response shapes."),
    "StorageTest": ("Storage", "How tokens are protected at rest."),
    "ClaimsAndConfigTest": ("Core", "Claim helpers and client configuration."),
    "PlaygroundUnitTest": ("Playground", "Config validation, redaction, JWT display, log parsing and check judging."),
    "LiveKindeTest": ("Live", "Your Kinde business: discovery, error shapes, registered URLs, Management models."),
    "PagesSmokeTest": ("Playground", "Every page renders on the emulator."),
    "OnDeviceSdkTest": ("Device", "Storage, one client across activities, self-checks and the security audit on Android."),
    "LiveChecksTest": ("Live", "Live checks with your signed-in session on the emulator."),
    "KindeClientTest": ("SDK", "KindeClient singleton, listeners and logout."),
    "KindeClientConfigTest": ("SDK", "Programmatic configuration."),
    "KindeSDKFacadeTest": ("SDK", "Activity facades sharing one client."),
    "InvitationStateTest": ("SDK", "Invitation de-duplication."),
    "StoreTest": ("SDK", "The encrypted preferences store."),
}


def outcome_of(case: ET.Element) -> tuple[str, str]:
    for child in case:
        text = (child.get("message") or child.text or "").strip()
        if child.tag in ("failure", "error"):
            if "AssumptionViolatedException" in text:
                return ("known" if "KNOWN ISSUE" in text else "skipped"), clean(text)
            return "failed", clean(text)
        if child.tag == "skipped":
            return ("known" if "KNOWN ISSUE" in text else "skipped"), clean(text)
    return "passed", ""


def clean(text: str) -> str:
    text = text.replace("org.junit.AssumptionViolatedException: ", "")
    first = text.split("\n\tat ")[0].split("\tat ")[0]
    return first.strip()[:600]


def load_suite(name: str, about: str, directory: Path) -> dict:
    files: dict[str, dict] = {}
    for xml in sorted(directory.rglob("*.xml")) if directory.is_dir() else []:
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            cls = (case.get("classname") or "").rsplit(".", 1)[-1] or xml.stem
            area, explain = EXPLAINS.get(cls, ("Other", ""))
            entry = files.setdefault(cls, {"name": cls, "area": area, "explain": explain, "tests": []})
            outcome, message = outcome_of(case)
            entry["tests"].append({"name": case.get("name", ""), "outcome": outcome, "message": message})
    tests = [t for f in files.values() for t in f["tests"]]
    count = lambda o: sum(1 for t in tests if t["outcome"] == o)
    return {
        "name": name, "about": about,
        "passed": count("passed"), "failed": count("failed"), "skipped": count("skipped"), "known": count("known"),
        "files": sorted(files.values(), key=lambda f: (f["area"], f["name"])),
    }


def git_describe(path: Path) -> str:
    try:
        out = subprocess.run(["git", "-C", str(path), "log", "-1", "--format=%h %s"], capture_output=True, text=True, timeout=5)
        return out.stdout.strip()
    except Exception:
        return ""


def render_html(report: dict) -> str:
    rows = []
    for suite in report["suites"]:
        badge = "fail" if suite["failed"] else "pass"
        rows.append(f'<section><h2><span class="b {badge}">{badge}</span> {html.escape(suite["name"])}</h2>'
                    f'<p class="m">{suite["passed"]} passed · {suite["failed"]} failed · {suite["known"]} known SDK issues · '
                    f'{suite["skipped"]} skipped<br>{html.escape(suite["about"])}</p>')
        for f in suite["files"]:
            rows.append(f'<details{" open" if any(t["outcome"] == "failed" for t in f["tests"]) else ""}><summary>'
                        f'<code>{html.escape(f["name"])}</code> <span class="m">{html.escape(f["area"])} · {html.escape(f["explain"])}</span></summary><ul>')
            for t in f["tests"]:
                msg = f'<div class="msg">{html.escape(t["message"])}</div>' if t["message"] and t["outcome"] != "passed" else ""
                rows.append(f'<li class="{t["outcome"]}"><span class="b {t["outcome"]}">{t["outcome"]}</span> {html.escape(t["name"])}{msg}</li>')
            rows.append("</ul></details>")
        rows.append("</section>")
    return f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kinde Android regression</title><style>
:root{{--bg:#fafafb;--fg:#0f0f12;--m:#5b5b66;--line:#e7e7ec;--pass:#13612e;--passbg:#e3f4e8;--fail:#a4161a;--failbg:#fbe7e7;--known:#8a5300;--knownbg:#fdf1dc;--skip:#5b5b66;--skipbg:#efeff2}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0e0e11;--fg:#ededf0;--m:#a9a9b4;--line:#2a2a31;--pass:#8edba6;--passbg:#16301f;--fail:#ff9c9c;--failbg:#3a1717;--known:#ffcb7a;--knownbg:#392a10;--skip:#a7a7b2;--skipbg:#26262c}}}}
body{{font:14px/1.5 -apple-system,system-ui,sans-serif;background:var(--bg);color:var(--fg);max-width:980px;margin:0 auto;padding:24px 16px}}
h1{{font-size:22px;margin:0 0 4px}}h2{{font-size:17px;margin:28px 0 4px}}.m{{color:var(--m)}}
details{{border:1px solid var(--line);border-radius:10px;padding:8px 12px;margin:6px 0}}summary{{cursor:pointer}}
ul{{list-style:none;padding-left:4px}}li{{margin:4px 0}}.msg{{font:12px ui-monospace,monospace;color:var(--m);margin:2px 0 6px 56px;white-space:pre-wrap}}
.b{{display:inline-block;min-width:44px;text-align:center;font-size:11px;font-weight:600;text-transform:uppercase;border-radius:6px;padding:1px 6px}}
.pass,.passed{{color:var(--pass);background:var(--passbg)}}.fail,.failed{{color:var(--fail);background:var(--failbg)}}
.known{{color:var(--known);background:var(--knownbg)}}.skipped{{color:var(--skip);background:var(--skipbg)}}li.passed,li.failed,li.known,li.skipped{{background:none}}
</style></head><body><h1>Kinde Android SDK regression</h1><p class="m">Generated {html.escape(report["generatedAt"])} · SDK {html.escape(report["git"])}</p>
{''.join(rows)}</body></html>"""


def main() -> int:
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    suites = []
    for spec in sys.argv[2:]:
        name, about, directory = spec.split("|", 2)
        suites.append(load_suite(name, about, Path(directory)))
    sdk_dir = Path(__file__).resolve().parents[1].parent / "kinde-sdk-android"
    report = {"generatedAt": time.strftime("%Y-%m-%d %H:%M"), "git": git_describe(sdk_dir), "suites": suites}
    (out / "regression.json").write_text(json.dumps(report, indent=1))
    (out / "regression.html").write_text(render_html(report))
    failed = sum(s["failed"] for s in suites)
    for s in suites:
        print(f'  {s["name"]:<34} {s["passed"]:>4} passed  {s["failed"]:>3} failed  {s["known"]:>3} known  {s["skipped"]:>3} skipped')
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
