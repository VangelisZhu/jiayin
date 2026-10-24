import json
import os
import secrets
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path

from cryptography.fernet import Fernet


def stamp():
    return int(time.time())


class Store:
    def __init__(self, path: Path):
        self.path = path.resolve()
        self.path.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.path / "manager.sqlite3", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS documents(kind TEXT, id TEXT, body TEXT NOT NULL,
                PRIMARY KEY(kind,id));
            CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY, expires INTEGER NOT NULL);
        """)
        self.db.commit()
        key_path = self.path / "vault.key"
        if not key_path.exists():
            fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as out:
                out.write(Fernet.generate_key())
        self.vault = Fernet(key_path.read_bytes())

    @contextmanager
    def transaction(self):
        with self.lock:
            try:
                yield self.db
                self.db.commit()
            except BaseException:
                self.db.rollback()
                raise

    def put(self, kind, key, value):
        with self.transaction() as db:
            db.execute("INSERT OR REPLACE INTO documents VALUES(?,?,?)", (kind, key, json.dumps(value, ensure_ascii=False)))
        return value

    def get(self, kind, key, default=None):
        with self.lock:
            row = self.db.execute("SELECT body FROM documents WHERE kind=? AND id=?", (kind, key)).fetchone()
        return json.loads(row[0]) if row else default

    def all(self, kind):
        with self.lock:
            rows = self.db.execute("SELECT body FROM documents WHERE kind=? ORDER BY rowid", (kind,)).fetchall()
        return [json.loads(row[0]) for row in rows]

    def delete(self, kind, key):
        with self.transaction() as db:
            db.execute("DELETE FROM documents WHERE kind=? AND id=?", (kind, key))

    def set_secret(self, key, value):
        self.put("secret", key, self.vault.encrypt(value.encode()).decode())

    def secret(self, key):
        value = self.get("secret", key)
        return self.vault.decrypt(value.encode()).decode() if value else ""

    def session(self):
        token = secrets.token_urlsafe(32)
        with self.transaction() as db:
            db.execute("DELETE FROM sessions WHERE expires<?", (stamp(),))
            db.execute("INSERT INTO sessions VALUES(?,?)", (token, stamp() + 3600 * 12))
        return token

    def authenticated(self, token):
        with self.lock:
            return self.db.execute("SELECT 1 FROM sessions WHERE token=? AND expires>?", (token, stamp())).fetchone() is not None
