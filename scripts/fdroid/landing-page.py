#!/usr/bin/env python3
"""Write the GitHub Pages landing page for the F-Droid repository.

Usage:
    scripts/fdroid/landing-page.py <fdroid-build-dir> <output-html>
"""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CONFIG = REPO_ROOT / "fdroid/config.yml"

TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SmartPlus Wear F-Droid Repository</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{ margin: 0 auto; max-width: 44rem; padding: 2.5rem 1.25rem 4rem;
          font: 16px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif; }}
  h1 {{ display: flex; align-items: center; gap: .75rem; font-size: 1.6rem; }}
  h1 img {{ width: 3rem; height: 3rem; border-radius: .75rem; }}
  code, pre {{ font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
               font-size: .9em; }}
  pre {{ padding: .85rem 1rem; border-radius: .5rem;
         background: rgba(127,127,127,.14); white-space: pre-wrap;
         overflow-wrap: anywhere; }}
  .cta {{ display: inline-block; margin: .5rem 0 1.5rem; padding: .7rem 1.2rem;
          border-radius: .5rem; background: #245bdb; color: #fff;
          font-weight: 600; text-decoration: none; }}
  .warning {{ padding: .85rem 1rem; border-left: .3rem solid #d97706;
              background: rgba(217,119,6,.12); }}
  footer {{ margin-top: 3rem; font-size: .9rem; opacity: .75; }}
</style>
</head>
<body>
<h1><img src="fdroid/repo/icons/icon.png" alt="">SmartPlus Wear F-Droid Repository</h1>

<p>This repository contains the same signed phone APKs published on
<a href="https://github.com/colonelpanic8/akuvox-wear/releases">GitHub Releases</a>,
so either source can update an existing installation.</p>

<p class="warning">This is a modified, unofficial Akuvox SmartPlus build. It
contains proprietary Akuvox code, requires Akuvox's cloud service, and is not
an official F-Droid.org package.</p>

<a class="cta" href="{add_url}">Add this repository to F-Droid</a>
<p>If the button does not open your client, add this address manually:</p>
<pre>{repo_url}</pre>
<p>Verify the repository signing fingerprint (SHA-256):</p>
<pre>{fingerprint_display}</pre>

<p>The repository indexes the phone app. Install the matching Wear OS APK from
GitHub Releases.</p>

<footer>Source and issues:
<a href="https://github.com/colonelpanic8/akuvox-wear">github.com/colonelpanic8/akuvox-wear</a>
</footer>
</body>
</html>
"""


def read_repo_url() -> str:
    match = re.search(
        r"^repo_url:\s*(\S+)\s*$",
        CONFIG.read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    if not match:
        raise SystemExit(f"repo_url not found in {CONFIG}")
    return match.group(1)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2

    build_dir = Path(sys.argv[1])
    output = Path(sys.argv[2])
    repo_url = read_repo_url()
    fingerprint_file = build_dir / "fingerprint.txt"
    fingerprint = (
        fingerprint_file.read_text(encoding="utf-8").strip()
        if fingerprint_file.exists()
        else ""
    )
    if fingerprint:
        add_url = f"{repo_url}?fingerprint={fingerprint}"
        fingerprint_display = " ".join(
            fingerprint[index : index + 2].upper()
            for index in range(0, len(fingerprint), 2)
        )
    else:
        add_url = repo_url
        fingerprint_display = "unavailable"

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        TEMPLATE.format(
            add_url=html.escape(add_url, quote=True),
            repo_url=html.escape(repo_url),
            fingerprint_display=html.escape(fingerprint_display),
        ),
        encoding="utf-8",
    )
    print(f"wrote {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
