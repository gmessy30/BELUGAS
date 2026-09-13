"""Evaluate JavaScript in a live page on a USB-connected Android device, via the Chrome
DevTools Protocol (CDP), over an adb port-forward. See CLAUDE.md's "Live device inspection"
section for the full setup (forwarding the port, finding the tab's webSocketDebuggerUrl).

Usage:
    python cdp_eval.py <websocket-debugger-url> "<js expression>"

Requires: pip install websocket-client
"""
import sys
import json
import websocket


def eval_js(ws_url, expression, timeout=10):
    # suppress_origin=True is required: recent Chrome versions reject an incoming DevTools
    # WebSocket connection whose Origin header isn't on an explicit allow-list (403 Forbidden,
    # "Rejected an incoming WebSocket connection from the http://... origin"), and there's no
    # practical way to pass --remote-allow-origins to a stock installed Android Chrome. Omitting
    # the Origin header entirely (rather than trying to guess an allowed value) avoids the check.
    ws = websocket.create_connection(ws_url, timeout=timeout, suppress_origin=True)
    try:
        msg = {
            "id": 1,
            "method": "Runtime.evaluate",
            # awaitPromise + returnByValue: an async IIFE expression's resolved value comes back
            # as a plain JSON-serializable result instead of an opaque Promise object reference.
            "params": {"expression": expression, "returnByValue": True, "awaitPromise": True}
        }
        ws.send(json.dumps(msg))
        while True:
            raw = ws.recv()
            data = json.loads(raw)
            if data.get("id") == 1:
                return data
    finally:
        ws.close()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python cdp_eval.py <websocket-debugger-url> \"<js expression>\"", file=sys.stderr)
        sys.exit(1)
    result = eval_js(sys.argv[1], sys.argv[2])
    print(json.dumps(result, indent=2))
