"""List (or find) open Chrome tabs on a USB-connected Android device, via an adb port-forward
to the device's chrome_devtools_remote socket. See CLAUDE.md's "Live device inspection" section
for the full setup.

Usage:
    python find_tab.py <local-port>                 # list every tab (id, title, url)
    python find_tab.py <local-port> <url-substring>  # print just the matching tab's
                                                      # webSocketDebuggerUrl (for cdp_eval.py)

Requires: pip install requests
"""
import sys
import requests


def main():
    if len(sys.argv) < 2:
        print("Usage: python find_tab.py <local-port> [url-substring]", file=sys.stderr)
        sys.exit(1)
    port = sys.argv[1]
    needle = sys.argv[2] if len(sys.argv) > 2 else None

    tabs = requests.get(f"http://localhost:{port}/json", timeout=5).json()

    if needle is None:
        for t in tabs:
            print(f"{t.get('id')}\t{t.get('type')}\t{t.get('title')}\t{t.get('url')}")
        return

    matches = [t for t in tabs if needle in (t.get("url") or "")]
    if not matches:
        print(f"No tab matching {needle!r} found among {len(tabs)} tabs.", file=sys.stderr)
        sys.exit(1)
    # Prefer an actual page over the service worker's own devtools target, if both matched.
    page_matches = [t for t in matches if t.get("type") == "page"] or matches
    print(page_matches[0]["webSocketDebuggerUrl"])


if __name__ == "__main__":
    main()
