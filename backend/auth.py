"""SQLite-backed authentication primitives for WakeDrive."""

import hashlib
import hmac
import logging
import os
import secrets
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from threading import Lock
from typing import Any

from fastapi import APIRouter, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field, field_validator

if __package__:
    from backend.account_data import AccountDataMixin
else:
    from account_data import AccountDataMixin


COOKIE_NAME = "wakedrive_session"
PBKDF2_ITERATIONS = 310_000
SESSION_HOURS = 12
REMEMBER_DAYS = 30
DEMO_USERNAME = "admin"
DEMO_PASSWORD = "12345"
login_failures: dict[tuple[str, str | None], list[float]] = {}
_login_lock = Lock()


def _login_keys(client_ip: str, username: str):
    return (client_ip, None), (client_ip, username.strip().casefold())


def check_login_attempts(client_ip: str, username: str) -> None:
    """Apply shared IP and target-account budgets across both login routes."""
    now = time.monotonic()
    with _login_lock:
        for key in list(login_failures):
            recent = [stamp for stamp in login_failures[key] if now - stamp < 60]
            if recent:
                login_failures[key] = recent
            else:
                login_failures.pop(key, None)
        if any(len(login_failures.get(key, [])) >= 10 for key in _login_keys(client_ip, username)):
            raise HTTPException(429, "登录尝试过于频繁，请一分钟后重试", headers={"Retry-After": "60"})


def record_login_failure(client_ip: str, username: str) -> None:
    with _login_lock:
        for key in _login_keys(client_ip, username):
            if key not in login_failures and len(login_failures) >= 4096:
                login_failures.pop(next(iter(login_failures)))
            login_failures.setdefault(key, []).append(time.monotonic())


def clear_login_failures(client_ip: str, username: str) -> None:
    with _login_lock:
        for key in _login_keys(client_ip, username):
            login_failures.pop(key, None)


def demo_login_allowed() -> bool:
    """Shared demo credentials are usable only in explicitly enabled local mode."""
    truthy = {"1", "true", "yes", "on"}
    secure = os.getenv("WAKEDRIVE_COOKIE_SECURE", "false").strip().lower() in truthy
    enabled = os.getenv("WAKEDRIVE_ALLOW_DEMO_LOGIN", "false").strip().lower() in truthy
    return enabled and not secure


def is_demo_user(user) -> bool:
    return user["username"].casefold() == DEMO_USERNAME.casefold() and user.get("role", "user") != "admin"


class DuplicateUsername(Exception):
    """Raised when a normalized username already exists."""


class AuthStorageError(Exception):
    """Raised when authentication changes cannot be persisted."""


class AuthStore(AccountDataMixin):
    backend_name = "sqlite"

    def __init__(self, db_path: str | Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize_schema()
        self._initialize_data_schema()
        self._ensure_demo_account()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=10, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    @property
    def connection(self) -> sqlite3.Connection:
        return self._connect()

    def _initialize_schema(self) -> None:
        with self._connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                    password_hash TEXT NOT NULL,
                    password_salt TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'user'
                );
                CREATE TABLE IF NOT EXISTS sessions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE,
                    expires_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                );
                CREATE INDEX IF NOT EXISTS idx_sessions_expires_at ON sessions(expires_at);
                """
            )
            columns = {row["name"] for row in connection.execute("PRAGMA table_info(users)")}
            if "role" not in columns:
                connection.execute("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user'")

    def _ensure_demo_account(self) -> None:
        if not demo_login_allowed():
            return
        with self._connect() as connection:
            exists = connection.execute(
                "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE",
                (DEMO_USERNAME,),
            ).fetchone()
        if exists is None:
            try:
                self.register(DEMO_USERNAME, DEMO_PASSWORD)
            except DuplicateUsername:
                pass

    @staticmethod
    def _public_user(row: sqlite3.Row) -> dict[str, Any]:
        return {"id": row["id"], "username": row["username"], "created_at": row["created_at"],
                "role": row["role"] if "role" in row.keys() else "user"}

    @staticmethod
    def _password_hash(password: str, salt: bytes) -> str:
        return hashlib.pbkdf2_hmac(
            "sha256",
            password.encode("utf-8"),
            salt,
            PBKDF2_ITERATIONS,
        ).hex()

    @staticmethod
    def _token_hash(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    def _insert_user(self, connection: sqlite3.Connection, username: str, password: str) -> dict[str, Any]:
        normalized = username.strip()
        salt = secrets.token_bytes(16)
        created_at = datetime.now(timezone.utc).isoformat()
        try:
            cursor = connection.execute(
                "INSERT INTO users (username, password_hash, password_salt, created_at) VALUES (?, ?, ?, ?)",
                (normalized, self._password_hash(password, salt), salt.hex(), created_at),
            )
        except sqlite3.IntegrityError as exc:
            # Match the exact column; sqlite_errorcode is unavailable on Python 3.10.
            if str(exc) == "UNIQUE constraint failed: users.username":
                raise DuplicateUsername(normalized) from exc
            raise
        row = connection.execute(
            "SELECT id, username, created_at, role FROM users WHERE id = ?",
            (cursor.lastrowid,),
        ).fetchone()
        return self._public_user(row)

    def register(self, username: str, password: str) -> dict[str, Any]:
        with self._connect() as connection:
            return self._insert_user(connection, username, password)

    def register_with_session(self, username: str, password: str) -> tuple[dict[str, Any], str, datetime]:
        """Commit the account and its first session together so failed registrations can retry."""
        try:
            with self._connect() as connection:
                user = self._insert_user(connection, username, password)
                token, expires_at = self._insert_session(connection, user["id"], remember=False)
            return user, token, expires_at
        except sqlite3.Error as exc:
            raise AuthStorageError("Registration transaction failed") from exc

    def authenticate(self, username: str, password: str) -> dict[str, Any] | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT id, username, password_hash, password_salt, created_at, role FROM users WHERE username = ? COLLATE NOCASE",
                (username.strip(),),
            ).fetchone()
        if row is None:
            return None
        candidate = self._password_hash(password, bytes.fromhex(row["password_salt"]))
        if not hmac.compare_digest(candidate, row["password_hash"]):
            return None
        return self._public_user(row)

    def _insert_session(self, connection: sqlite3.Connection, user_id: int, remember: bool) -> tuple[str, datetime]:
        token = secrets.token_urlsafe(32)
        now = datetime.now(timezone.utc)
        expires_at = now + (timedelta(days=REMEMBER_DAYS) if remember else timedelta(hours=SESSION_HOURS))
        connection.execute(
            "INSERT INTO sessions (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
            (user_id, self._token_hash(token), expires_at.isoformat(), now.isoformat()),
        )
        return token, expires_at

    def create_session(self, user_id: int, remember: bool) -> tuple[str, datetime]:
        with self._connect() as connection:
            return self._insert_session(connection, user_id, remember)

    def get_user_by_session(self, token: str) -> dict[str, Any] | None:
        now = datetime.now(timezone.utc).isoformat()
        token_hash = self._token_hash(token)
        with self._connect() as connection:
            connection.execute("DELETE FROM sessions WHERE expires_at <= ?", (now,))
            row = connection.execute(
                """
                SELECT users.id, users.username, users.created_at, users.role
                FROM sessions
                JOIN users ON users.id = sessions.user_id
                WHERE sessions.token_hash = ? AND sessions.expires_at > ?
                """,
                (token_hash, now),
            ).fetchone()
        return self._public_user(row) if row else None

    def delete_session(self, token: str) -> None:
        with self._connect() as connection:
            connection.execute("DELETE FROM sessions WHERE token_hash = ?", (self._token_hash(token),))


class RegisterRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=3, max_length=24, pattern=r"^[A-Za-z0-9_\u4e00-\u9fff]+$")
    password: str = Field(min_length=8, max_length=128)

    @field_validator("username", mode="before")
    @classmethod
    def normalize_username(cls, value: Any) -> Any:
        return value.strip() if isinstance(value, str) else value


class LoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=3, max_length=24, pattern=r"^[A-Za-z0-9_\u4e00-\u9fff]+$")
    password: str = Field(min_length=1, max_length=128)
    remember: bool = False

    @field_validator("username", mode="before")
    @classmethod
    def normalize_username(cls, value: Any) -> Any:
        return value.strip() if isinstance(value, str) else value


router = APIRouter(prefix="/api/auth", tags=["authentication"])


def _store(request: Request) -> AuthStore:
    return request.app.state.auth_store


def optional_user(request: Request):
    token = request.cookies.get(COOKIE_NAME)
    user = _store(request).get_user_by_session(token) if token else None
    if user and is_demo_user(user) and not demo_login_allowed():
        return None
    return user


def require_user(request: Request):
    user = optional_user(request)
    if user is None:
        raise HTTPException(401, "请先登录后修改驾驶数据")
    return user


def _set_session_cookie(response: Response, token: str, expires_at: datetime, remember: bool) -> None:
    max_age = None
    if remember:
        max_age = max(1, int((expires_at - datetime.now(timezone.utc)).total_seconds()))
    response.set_cookie(
        COOKIE_NAME,
        token,
        max_age=max_age,
        httponly=True,
        samesite="lax",
        secure=(os.getenv("WAKEDRIVE_COOKIE_SECURE", "false").strip().lower() in {"1", "true", "yes", "on"}),
        path="/",
    )


@router.post("/register", status_code=201)
def register(body: RegisterRequest, request: Request, response: Response):
    store = _store(request)
    try:
        user, token, expires_at = store.register_with_session(body.username, body.password)
    except DuplicateUsername as exc:
        raise HTTPException(409, "用户名已存在") from exc
    except AuthStorageError as exc:
        logging.getLogger(__name__).exception("Registration database transaction failed")
        raise HTTPException(503, "注册服务暂时不可用，请稍后重试") from exc
    _set_session_cookie(response, token, expires_at, remember=False)
    return {"ok": True, "user": user}


@router.post("/login")
def login(body: LoginRequest, request: Request, response: Response):
    client_ip = request.client.host if request.client else "unknown"
    check_login_attempts(client_ip, body.username)
    store = _store(request)
    user = store.authenticate(body.username, body.password)
    if user is None or (is_demo_user(user) and not demo_login_allowed()):
        record_login_failure(client_ip, body.username)
        raise HTTPException(401, "用户名或密码不正确")
    token, expires_at = store.create_session(user["id"], body.remember)
    clear_login_failures(client_ip, body.username)
    _set_session_cookie(response, token, expires_at, body.remember)
    return {"ok": True, "user": user}


@router.get("/me")
def me(request: Request):
    user = optional_user(request)
    if user is None:
        raise HTTPException(401, "登录状态已失效")
    return {"ok": True, "user": user}


@router.post("/logout")
def logout(request: Request, response: Response):
    token = request.cookies.get(COOKIE_NAME)
    if token:
        _store(request).delete_session(token)
    response.delete_cookie(COOKIE_NAME, path="/", httponly=True, samesite="lax")
    return {"ok": True}
