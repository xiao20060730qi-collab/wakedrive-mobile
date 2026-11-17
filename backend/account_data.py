"""Persistent account monitoring shared by the SQLite and MySQL auth stores."""
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
import re
import secrets
from typing import Any
from uuid import uuid4


SHANGHAI = timezone(timedelta(hours=8))
ONLINE_SECONDS = 120
DEFAULT_SETTINGS = {
    "earThreshold": 0.25, "marThreshold": 0.55, "mildFrames": 2, "mediumFrames": 5,
    "heavyFrames": 9, "cooldownMs": 5000, "detectIntervalMs": 333, "alarmEnabled": True,
    "alarmStyle": "bell", "driverName": "", "plateNumber": "",
}


class ProtectedAccountError(Exception):
    """Raised when deletion targets an account that is not an ordinary user."""


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def as_utc(value: datetime | str) -> datetime:
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


class AccountDataMixin:
    """Use the same configured database and user IDs as authentication."""

    @contextmanager
    def _data_cursor(self, *, write=False):
        if self.backend_name == "sqlite":
            connection = self._connect()
            try:
                with connection:
                    yield connection.cursor()
            finally:
                connection.close()
        else:
            with self._connection(transaction=write) as connection:
                with connection.cursor() as cursor:
                    yield cursor

    def _execute(self, cursor, sql, params=()):
        cursor.execute(sql.replace("?", "%s") if self.backend_name == "mysql" else sql, params)
        return cursor

    def _initialize_data_schema(self):
        mysql = self.backend_name == "mysql"
        user_type = "BIGINT UNSIGNED" if mysql else "INTEGER"
        suffix = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" if mysql else ""
        event_index = ", KEY idx_fatigue_user_created (user_id, created_at)" if mysql else ""
        report_index = ", KEY idx_reports_user_started (user_id, started_at)" if mysql else ""
        client_collation = " COLLATE utf8mb4_bin" if mysql else ""
        report_payload_type = "MEDIUMTEXT" if mysql else "TEXT"
        definitions = [
            f"""CREATE TABLE IF NOT EXISTS admin_sessions (
                token_hash VARCHAR(64) PRIMARY KEY, user_id {user_type} NOT NULL,
                expires_at VARCHAR(40) NOT NULL, created_at VARCHAR(40) NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            ){suffix}""",
            f"""CREATE TABLE IF NOT EXISTS fatigue_events (
                id VARCHAR(64) PRIMARY KEY, user_id {user_type} NOT NULL,
                created_at VARCHAR(40) NOT NULL, severity VARCHAR(16) NOT NULL,
                status VARCHAR(16) NOT NULL, payload_json TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                {event_index}
            ){suffix}""",
            f"""CREATE TABLE IF NOT EXISTS monitor_status (
                user_id {user_type} PRIMARY KEY, updated_at VARCHAR(40) NOT NULL,
                payload_json TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            ){suffix}""",
            f"""CREATE TABLE IF NOT EXISTS user_settings (
                user_id {user_type} PRIMARY KEY, payload_json TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            ){suffix}""",
            f"""CREATE TABLE IF NOT EXISTS monitoring_reports (
                id VARCHAR(64) PRIMARY KEY, user_id {user_type} NOT NULL,
                client_id VARCHAR(80){client_collation} NOT NULL,
                started_at VARCHAR(40) NOT NULL, source VARCHAR(16) NOT NULL,
                payload_json {report_payload_type} NOT NULL,
                UNIQUE (user_id, client_id),
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                {report_index}
            ){suffix}""",
        ]
        with self._data_cursor(write=True) as cursor:
            for statement in definitions:
                cursor.execute(statement)
            if not mysql:
                cursor.execute("CREATE INDEX IF NOT EXISTS idx_fatigue_user_created ON fatigue_events(user_id, created_at)")
                cursor.execute("CREATE INDEX IF NOT EXISTS idx_admin_sessions_expiry ON admin_sessions(expires_at)")
                cursor.execute("CREATE INDEX IF NOT EXISTS idx_reports_user_started ON monitoring_reports(user_id, started_at)")

    def create_report(self, user_id: int, data: dict[str, Any]):
        """Atomically preserve the first submission for a user's client ID."""
        report = {**data, "id": f"rpt-{uuid4()}", "createdAt": utc_now().isoformat()}
        # Fixed UTC precision makes text ordering match chronological ordering.
        started_at = as_utc(report["startedAt"]).isoformat(timespec="microseconds")
        conflict = ("ON DUPLICATE KEY UPDATE client_id = client_id" if self.backend_name == "mysql"
                    else "ON CONFLICT(user_id, client_id) DO NOTHING")
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, f"""INSERT INTO monitoring_reports
                (id, user_id, client_id, started_at, source, payload_json) VALUES (?, ?, ?, ?, ?, ?) {conflict}""",
                (report["id"], user_id, report["clientId"], started_at, report["source"],
                 json.dumps(report, ensure_ascii=False, allow_nan=False)))
            self._execute(cursor, "SELECT payload_json FROM monitoring_reports WHERE user_id = ? AND client_id = ?",
                          (user_id, report["clientId"]))
            return json.loads(cursor.fetchone()["payload_json"])

    def get_report(self, user_id: int, report_id: str):
        with self._data_cursor() as cursor:
            self._execute(cursor, "SELECT payload_json FROM monitoring_reports WHERE user_id = ? AND id = ?",
                          (user_id, report_id))
            row = cursor.fetchone()
        return json.loads(row["payload_json"]) if row else None

    def list_reports(self, user_id: int, *, source=None, limit=50, offset=0):
        where = "user_id = ?" + (" AND source = ?" if source else "")
        params = (user_id, source) if source else (user_id,)
        with self._data_cursor() as cursor:
            self._execute(cursor, f"SELECT COUNT(*) AS total FROM monitoring_reports WHERE {where}", params)
            total = cursor.fetchone()["total"]
            self._execute(cursor, f"""SELECT payload_json FROM monitoring_reports WHERE {where}
                ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?""", (*params, limit, offset))
            items = []
            for row in cursor.fetchall():
                report = json.loads(row["payload_json"])
                items.append({key: value for key, value in report.items() if key not in {"samples", "events"}})
        return {"items": items, "total": total}

    def ensure_admin(self, username: str, password: str) -> dict[str, Any]:
        """Provision one authorized administrator without resetting other accounts."""
        username = username.strip()
        if not re.fullmatch(r"[A-Za-z0-9_\u4e00-\u9fff]{3,24}", username):
            raise ValueError("Invalid administrator username")
        if not isinstance(password, str) or not 8 <= len(password) <= 128:
            raise ValueError("Administrator password must contain 8 to 128 characters")
        salt = secrets.token_bytes(16)
        password_hash = self._password_hash(password, salt)
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "SELECT id FROM users WHERE username = ?", (username,))
            row = cursor.fetchone()
            if row:
                user_id = row["id"]
                self._execute(cursor, "UPDATE users SET username = ?, password_hash = ?, password_salt = ?, role = 'admin' WHERE id = ?",
                              (username, password_hash, salt.hex(), user_id))
                self._execute(cursor, "DELETE FROM sessions WHERE user_id = ?", (user_id,))
                self._execute(cursor, "DELETE FROM admin_sessions WHERE user_id = ?", (user_id,))
            else:
                created_at = utc_now()
                stored_time = self._mysql_datetime(created_at) if self.backend_name == "mysql" else created_at.isoformat()
                self._execute(cursor, "INSERT INTO users(username, password_hash, password_salt, created_at, role) VALUES(?, ?, ?, ?, 'admin')",
                              (username, password_hash, salt.hex(), stored_time))
                user_id = cursor.lastrowid
            self._execute(cursor, "SELECT id, username, created_at, role FROM users WHERE id = ?", (user_id,))
            return self._public_user(cursor.fetchone())

    def list_admins(self):
        with self._data_cursor() as cursor:
            self._execute(cursor, "SELECT id, username, created_at, role FROM users WHERE role = 'admin' ORDER BY id")
            return [self._public_user(row) for row in cursor.fetchall()]

    def reset_admin_password(self, user_id: int, password: str) -> bool:
        """Reset one administrator password and revoke that account's sessions."""
        if not isinstance(password, str) or not 8 <= len(password) <= 128:
            raise ValueError("Administrator password must contain 8 to 128 characters")
        salt = secrets.token_bytes(16)
        password_hash = self._password_hash(password, salt)
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "UPDATE users SET password_hash = ?, password_salt = ? WHERE id = ? AND role = 'admin'",
                          (password_hash, salt.hex(), user_id))
            if not cursor.rowcount:
                return False
            self._execute(cursor, "DELETE FROM sessions WHERE user_id = ?", (user_id,))
            self._execute(cursor, "DELETE FROM admin_sessions WHERE user_id = ?", (user_id,))
            return True

    def delete_admin(self, user_id: int) -> bool:
        """Delete one administrator while guaranteeing at least one admin remains."""
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "SELECT id FROM users WHERE id = ? AND role = 'admin'", (user_id,))
            if cursor.fetchone() is None:
                return False
            self._execute(cursor, "SELECT COUNT(*) AS total FROM users WHERE role = 'admin'")
            row = cursor.fetchone()
            total = (row["total"] if row else 0) or 0
            if total <= 1:
                raise ProtectedAccountError("至少需要保留一个管理员账号")
            self._execute(cursor, "DELETE FROM users WHERE id = ? AND role = 'admin'", (user_id,))
            return bool(cursor.rowcount)

    def create_admin_session(self, user_id: int, remember: bool):
        token = secrets.token_urlsafe(32)
        now = utc_now()
        expires = now + (timedelta(days=30) if remember else timedelta(hours=12))
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "INSERT INTO admin_sessions(token_hash, user_id, expires_at, created_at) VALUES(?, ?, ?, ?)",
                          (self._token_hash(token), user_id, expires.isoformat(), now.isoformat()))
        return token, expires

    def get_admin_user_by_session(self, token: str):
        with self._data_cursor(write=True) as cursor:
            now = utc_now().isoformat()
            self._execute(cursor, "DELETE FROM admin_sessions WHERE expires_at <= ?", (now,))
            self._execute(cursor, """SELECT users.id, users.username, users.created_at, users.role
                FROM admin_sessions JOIN users ON users.id = admin_sessions.user_id
                WHERE admin_sessions.token_hash = ? AND admin_sessions.expires_at > ?""",
                          (self._token_hash(token), now))
            row = cursor.fetchone()
        return self._public_user(row) if row else None

    def delete_admin_session(self, token: str):
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "DELETE FROM admin_sessions WHERE token_hash = ?", (self._token_hash(token),))

    def get_user(self, user_id: int):
        with self._data_cursor() as cursor:
            self._execute(cursor, "SELECT id, username, created_at, role FROM users WHERE id = ?", (user_id,))
            row = cursor.fetchone()
        return self._public_user(row) if row else None

    def delete_user(self, user_id: int) -> bool:
        """Delete one ordinary account and its dependent data in one transaction."""
        with self._data_cursor(write=True) as cursor:
            # Keep the role guard in the mutation so a concurrent promotion is safe.
            # Both database schemas cascade the account's sessions and private data.
            self._execute(cursor, "DELETE FROM users WHERE id = ? AND role = 'user'", (user_id,))
            if cursor.rowcount:
                return True
            self._execute(cursor, "SELECT id FROM users WHERE id = ?", (user_id,))
            if cursor.fetchone() is not None:
                raise ProtectedAccountError("不能删除管理员账号")
            return False

    def create_event(self, user_id: int, data: dict[str, Any]):
        event = {**data, "id": f"evt-{uuid4()}", "status": "待处理",
                 "createdAt": as_utc(data.get("createdAt") or utc_now()).isoformat()}
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "INSERT INTO fatigue_events(id, user_id, created_at, severity, status, payload_json) VALUES(?, ?, ?, ?, ?, ?)",
                          (event["id"], user_id, event["createdAt"], event["severity"], event["status"], json.dumps(event, ensure_ascii=False)))
        return event

    def list_events(self, user_id: int):
        with self._data_cursor() as cursor:
            self._execute(cursor, "SELECT payload_json FROM fatigue_events WHERE user_id = ? ORDER BY created_at DESC, id DESC", (user_id,))
            return [json.loads(row["payload_json"]) for row in cursor.fetchall()]

    def patch_event(self, user_id: int, event_id: str, updates: dict[str, Any]):
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "SELECT payload_json FROM fatigue_events WHERE user_id = ? AND id = ?", (user_id, event_id))
            row = cursor.fetchone()
            if row is None:
                return None
            event = {**json.loads(row["payload_json"]), **updates}
            if event["status"] == "已处理" and not event.get("handledAt"):
                event["handledAt"] = utc_now().isoformat()
            if event["status"] == "待处理":
                event.pop("handledAt", None)
                event.pop("handledBy", None)
            self._execute(cursor, "UPDATE fatigue_events SET status = ?, payload_json = ? WHERE user_id = ? AND id = ?",
                          (event["status"], json.dumps(event, ensure_ascii=False), user_id, event_id))
            return event

    def delete_events(self, user_id: int):
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "DELETE FROM fatigue_events WHERE user_id = ?", (user_id,))
            return cursor.rowcount

    def save_monitor_status(self, user_id: int, result: dict[str, Any], *, received_at=None):
        updated_at = as_utc(received_at or utc_now()).isoformat()
        latest = {key: result.get(key) for key in ("score", "risk", "severity", "face_detected")}
        latest.update({"updatedAt": updated_at, "monitoring": True})
        self._upsert_payload("monitor_status", user_id, latest, updated_at=updated_at)
        return latest

    def stop_monitoring(self, user_id: int):
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, "SELECT payload_json FROM monitor_status WHERE user_id = ?", (user_id,))
            row = cursor.fetchone()
            if row:
                latest = json.loads(row["payload_json"])
                latest["monitoring"] = False
                self._execute(cursor, "UPDATE monitor_status SET payload_json = ? WHERE user_id = ?",
                              (json.dumps(latest, ensure_ascii=False), user_id))

    def _upsert_payload(self, table: str, user_id: int, payload: dict[str, Any], *, updated_at=None):
        # Table names are internal constants; all user-controlled values are parameters.
        if table not in {"monitor_status", "user_settings"}:
            raise ValueError("Invalid account data table")
        columns = "user_id, payload_json" + (", updated_at" if updated_at else "")
        parameters = [user_id, json.dumps(payload, ensure_ascii=False)]
        if updated_at:
            parameters.append(updated_at)
        placeholders = ", ".join("?" for _ in parameters)
        assignments = "payload_json = VALUES(payload_json)" if self.backend_name == "mysql" else "payload_json = excluded.payload_json"
        if updated_at:
            assignments += ", updated_at = VALUES(updated_at)" if self.backend_name == "mysql" else ", updated_at = excluded.updated_at"
        conflict = "ON DUPLICATE KEY UPDATE" if self.backend_name == "mysql" else "ON CONFLICT(user_id) DO UPDATE SET"
        with self._data_cursor(write=True) as cursor:
            self._execute(cursor, f"INSERT INTO {table}({columns}) VALUES({placeholders}) {conflict} {assignments}", parameters)

    def get_settings(self, user_id: int):
        with self._data_cursor() as cursor:
            self._execute(cursor, "SELECT payload_json FROM user_settings WHERE user_id = ?", (user_id,))
            row = cursor.fetchone()
        return {**DEFAULT_SETTINGS, **(json.loads(row["payload_json"]) if row else {})}

    def save_settings(self, user_id: int, updates: dict[str, Any]):
        settings = {**self.get_settings(user_id), **updates}
        self._upsert_payload("user_settings", user_id, settings)
        return settings

    @staticmethod
    def _monitor_category(latest, now):
        if latest is None:
            return "unmonitored"
        if latest.get("monitoring") is False or (now - as_utc(latest["updatedAt"])).total_seconds() > ONLINE_SECONDS:
            return "offline"
        if not latest.get("face_detected"):
            return "unknown"
        severity = latest.get("severity", "unknown")
        return "light" if severity == "mild" else severity

    def admin_users(self, search="", severity="", limit=100, offset=0, *, now=None):
        now = as_utc(now or utc_now())
        day_start = now.astimezone(SHANGHAI).replace(hour=0, minute=0, second=0, microsecond=0).astimezone(timezone.utc)
        day_end = day_start + timedelta(days=1)
        with self._data_cursor() as cursor:
            self._execute(cursor, """SELECT users.id, users.username, users.created_at, users.role,
                monitor_status.payload_json AS latest_json
                FROM users LEFT JOIN monitor_status ON monitor_status.user_id = users.id
                ORDER BY users.created_at DESC, users.id DESC""")
            rows = cursor.fetchall()
            self._execute(cursor, """SELECT user_id, COUNT(*) AS total,
                SUM(CASE WHEN severity = '轻度疲劳' THEN 1 ELSE 0 END) AS light,
                SUM(CASE WHEN severity = '中度疲劳' THEN 1 ELSE 0 END) AS medium,
                SUM(CASE WHEN severity = '重度疲劳' THEN 1 ELSE 0 END) AS heavy,
                SUM(CASE WHEN status = '待处理' THEN 1 ELSE 0 END) AS pending,
                SUM(CASE WHEN created_at >= ? AND created_at < ? THEN 1 ELSE 0 END) AS today,
                MAX(created_at) AS last_event_at
                FROM fatigue_events GROUP BY user_id""", (day_start.isoformat(), day_end.isoformat()))
            counts_by_user = {row["user_id"]: row for row in cursor.fetchall()}
        items = []
        overview = {"totalUsers": len(rows), "onlineUsers": 0, "highRiskUsers": 0, "todayEvents": 0}
        for row in rows:
            counts = counts_by_user.get(row["id"], {})
            latest = json.loads(row["latest_json"]) if row["latest_json"] else None
            event_counts = {key: int(counts[key] or 0) if key in counts.keys() else 0
                            for key in ("total", "light", "medium", "heavy", "pending", "today")}
            highest = next((label for key, label in (("heavy", "重度疲劳"), ("medium", "中度疲劳"), ("light", "轻度疲劳"))
                            if event_counts[key]), None)
            item = {**self._public_user(row), "latest": latest, "lastEventAt": counts["last_event_at"] if counts else None,
                    "eventCounts": event_counts, "highestSeverity": highest}
            category = self._monitor_category(latest, now)
            overview["onlineUsers"] += int(category not in {"unmonitored", "offline"})
            overview["highRiskUsers"] += int(category == "high")
            overview["todayEvents"] += event_counts["today"]
            aliases = {"重度疲劳": "high", "中度疲劳": "medium", "轻度疲劳": "light", "none": "unknown", "未监测": "unmonitored"}
            wanted = aliases.get(severity, severity)
            if search and search.casefold() not in item["username"].casefold():
                continue
            if wanted and category != wanted:
                continue
            items.append(item)
        priority = {"high": 0, "medium": 1, "light": 2, "normal": 3,
                    "unknown": 4, "offline": 5, "unmonitored": 6}
        items.sort(key=lambda item: (
            priority.get(self._monitor_category(item["latest"], now), 4),
            -as_utc(item["latest"]["updatedAt"]).timestamp() if item["latest"] else 0,
            -as_utc(item["created_at"]).timestamp(), -item["id"],
        ))
        return {"items": items[offset:offset + limit], "total": len(items), "overview": overview,
                "generatedAt": now.isoformat()}
