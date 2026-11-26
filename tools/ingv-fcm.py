#!/usr/bin/env python3
"""Poll INGV for recent earthquakes and publish each new event to FCM."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import logging
import math
import os
import sqlite3
import time
from urllib.parse import urlencode
from urllib.request import urlopen

INGV = "https://webservices.ingv.it/fdsnws/event/1/query"
TOPIC = "terremoti-ingv"


def fetch_events():
    end = datetime.now(timezone.utc)
    parameters = {
        "format": "text", "orderby": "time",
        "starttime": (end - timedelta(hours=3)).strftime("%Y-%m-%dT%H:%M:%S"),
        "endtime": end.strftime("%Y-%m-%dT%H:%M:%S"),
        "limit": 1000,
    }
    with urlopen(f"{INGV}?{urlencode(parameters)}", timeout=20) as response:
        lines = response.read().decode("utf-8").splitlines()
    events = []
    for line in lines:
        row = line.split("|")
        if len(row) < 13 or not row[0].isdigit():
            continue
        if len(row) > 13 and row[13].strip().lower() != "earthquake":
            continue
        try:
            if not all(math.isfinite(float(row[index])) for index in (2, 3, 4, 10)):
                continue
        except ValueError:
            continue
        events.append(dict(zip(
            ("id", "time", "latitude", "longitude", "depth", "magnitudeType", "magnitude", "region"),
            (row[index].strip() for index in (0, 1, 2, 3, 4, 9, 10, 12)),
        )))
    return events


def publisher():
    import google.auth
    from google.auth.transport.requests import AuthorizedSession

    credentials, default_project = google.auth.default(
        scopes=["https://www.googleapis.com/auth/firebase.messaging"],
    )
    project = os.environ.get("FIREBASE_PROJECT_ID") or default_project
    if not project:
        raise ValueError("Set FIREBASE_PROJECT_ID to the receiving Firebase project.")
    session = AuthorizedSession(credentials)
    url = f"https://fcm.googleapis.com/v1/projects/{project}/messages:send"

    def publish(event):
        response = session.post(url, json={"message": {
            "topic": TOPIC,
            "data": {"type": "ingv_earthquake", **event},
            "android": {"priority": "HIGH", "ttl": "600s"},
        }}, timeout=20)
        response.raise_for_status()

    return publish


def poll(database, send):
    events = fetch_events()
    now = int(time.time())
    if database.execute("SELECT 1 FROM initialized").fetchone() is None:
        with database:
            database.executemany("INSERT OR IGNORE INTO sent VALUES (?, ?)", ((e["id"], now) for e in events))
            database.execute("INSERT INTO initialized VALUES (1)")
        logging.info("Initial INGV baseline recorded with %d events", len(events))
        return
    for event in reversed(events):
        event_id = event["id"]
        if database.execute("SELECT 1 FROM sent WHERE id = ?", (event_id,)).fetchone():
            continue
        send(event)
        with database:
            database.execute("INSERT INTO sent VALUES (?, ?)", (event_id, now))
        logging.info("Published INGV event %s", event_id)
    with database:
        database.execute("DELETE FROM sent WHERE published < ?", (now - 86400,))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="read INGV and print the latest mapped event")
    parser.add_argument("--state", default="ingv-fcm.sqlite3", help="SQLite file for published IDs")
    args = parser.parse_args()
    if args.dry_run:
        events = fetch_events()
        print(json.dumps(events[0] if events else None, ensure_ascii=False))
        return
    logging.basicConfig(level=logging.INFO)
    send = publisher()
    with sqlite3.connect(args.state) as database:
        database.executescript("""
            CREATE TABLE IF NOT EXISTS initialized (id INTEGER PRIMARY KEY);
            CREATE TABLE IF NOT EXISTS sent (id TEXT PRIMARY KEY, published INTEGER NOT NULL);
        """)
        while True:
            started = time.monotonic()
            try:
                poll(database, send)
            except Exception:
                logging.exception("INGV/FCM polling failed")
            time.sleep(max(1, 60 - (time.monotonic() - started)))


if __name__ == "__main__":
    main()
