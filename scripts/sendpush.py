#!/usr/bin/env python3
"""
Send a high-priority DATA push to a single device via the FCM v1 API.

This bypasses the Obscura server — it talks straight to Firebase — so it proves the
device side of the pipeline (FCM delivery → ObscuraFcmService.onMessageReceived →
processPendingMessages → notification) independently of server send-on-queue logic.

Data-only (no "notification" block) so Android always routes it to onMessageReceived
rather than drawing its own tray entry. priority=high wakes a backgrounded/killed app.

Usage:
  scripts/sendpush.py --key ~/Downloads/serviceAccount.json --token <FCM_DEVICE_TOKEN>

Get the device token from your server DB (it was stored via PUT /v1/push-tokens), or
from the device's logcat line: "push token registered: <...>".
"""
import argparse
import json
import sys

import requests
import google.auth.transport.requests
from google.oauth2 import service_account

SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", required=True, help="Path to Firebase service-account JSON")
    ap.add_argument("--token", required=True, help="Target device FCM token")
    ap.add_argument("--data", action="append", default=[],
                    help="Extra data payload as k=v (repeatable)")
    args = ap.parse_args()

    with open(args.key) as f:
        project_id = json.load(f)["project_id"]

    creds = service_account.Credentials.from_service_account_file(args.key, scopes=[SCOPE])
    creds.refresh(google.auth.transport.requests.Request())

    data = {"type": "wake"}
    for kv in args.data:
        k, _, v = kv.partition("=")
        data[k] = v

    message = {
        "message": {
            "token": args.token,
            "android": {"priority": "high"},
            "data": data,
        }
    }

    url = f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    resp = requests.post(
        url,
        headers={"Authorization": f"Bearer {creds.token}",
                 "Content-Type": "application/json"},
        data=json.dumps(message),
    )
    print(f"project={project_id} status={resp.status_code}")
    print(resp.text)
    if resp.status_code != 200:
        sys.exit(1)
    print("\nSent. Watch the device: adb logcat -s ObscuraKit ObscuraApp")


if __name__ == "__main__":
    main()
