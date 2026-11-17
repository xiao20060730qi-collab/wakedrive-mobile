"""MySQL-backed authentication storage for WakeDrive."""

import hmac
import secrets
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Iterator

import pymysql

if __package__:
    from backend.account_data import AccountDataMixin
else:
    from account_data import AccountDataMixin

if __package__:
    from backend.auth import (
        DEMO_PASSWORD,
        DEMO_USERNAME,
        REMEMBER_DAYS,
        SESSION_HOURS,
        AuthStore,
        AuthStorageError,
        DuplicateUsername,
        demo_login_allowed,
    )
else:
    from auth import (
        DEMO_PASSWORD,
        DEMO_USERNAME,
        REMEMBER_DAYS,
        SESSION_HOURS,
        AuthStore,
        AuthStorageError,
        DuplicateUsername,
        demo_login_allowed,
    )


USERS_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(24) NOT NULL,
    password_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_salt CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'user',
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
"""

SESSIONS_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS sessions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_sessions_token_hash (token_hash),
    KEY idx_sessions_expires_at (expires_at),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
"""


class MySQLAuthStore(AccountDataMixin):
    backend_name = "mysql"
    _password_hash = staticmethod(AuthStore._password_hash)
    _token_hash = staticmethod(AuthStore._token_hash)

    def __init__(
        self,
        *,
        host: str,
        port: int,
        user: str,
        password: str,
        database: str,
        connector: Callable[..., Any] | None = None,
    ):
        self._connector = connector or pymysql.connect
        self._connect_kwargs = {
            "host": host,
            "port": port,
            "user": user,
            "password": password,
            "database": database,
            "charset": "utf8mb4",
            "cursorclass": pymysql.cursors.DictCursor,
            "autocommit": False,
            "connect_timeout": 5,
        }
        self._initialize_schema()
        self._initialize_data_schema()
        self._ensure_demo_account()

    @contextmanager
    def _connection(self, *, transaction: bool = False) -> Iterator[Any]:
        connection = self._connector(**self._connect_kwargs)
        try:
            yield connection
            if transaction:
                connection.commit()
        except Exception:
            if transaction:
                connection.rollback()
            raise
        finally:
            connection.close()

    @staticmethod
    def _mysql_datetime(value: datetime) -> datetime:
        return value.astimezone(timezone.utc).replace(tzinfo=None)

    @staticmethod
    def _public_user(row: dict[str, Any]) -> dict[str, Any]:
        created_at = row["created_at"]
        if isinstance(created_at, datetime):
            if created_at.tzinfo is None:
                created_at = created_at.replace(tzinfo=timezone.utc)
            else:
                created_at = created_at.astimezone(timezone.utc)
            created_at = created_at.isoformat()
        return {"id": row["id"], "username": row["username"], "created_at": created_at,
                "role": row.get("role", "user")}

    def _initialize_schema(self) -> None:
        with self._connection(transaction=True) as connection:
            with connection.cursor() as cursor:
                cursor.execute(USERS_TABLE_SQL)
                cursor.execute(SESSIONS_TABLE_SQL)
                cursor.execute("SHOW COLUMNS FROM users LIKE 'role'")
                if cursor.fetchone() is None:
                    cursor.execute("ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'user'")

    def _ensure_demo_account(self) -> None:
        if not demo_login_allowed():
            return
        with self._connection() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT id FROM users WHERE username = %s LIMIT 1", (DEMO_USERNAME,))
                exists = cursor.fetchone()
        if exists is None:
            try:
                self.register(DEMO_USERNAME, DEMO_PASSWORD)
            except DuplicateUsername:
                pass

    def _insert_user(self, cursor, username: str, password: str) -> dict[str, Any]:
        normalized = username.strip()
        salt = secrets.token_bytes(16)
        created_at = datetime.now(timezone.utc)
        try:
            cursor.execute(
                "INSERT INTO users (username, password_hash, password_salt, created_at) VALUES (%s, %s, %s, %s)",
                (normalized, self._password_hash(password, salt), salt.hex(), self._mysql_datetime(created_at)),
            )
        except pymysql.err.IntegrityError as exc:
            if exc.args and exc.args[0] == 1062:
                raise DuplicateUsername(normalized) from exc
            raise
        user_id = cursor.lastrowid
        cursor.execute(
            "SELECT id, username, created_at, role FROM users WHERE id = %s",
            (user_id,),
        )
        row = cursor.fetchone()
        return self._public_user(row)

    def register(self, username: str, password: str) -> dict[str, Any]:
        with self._connection(transaction=True) as connection:
            with connection.cursor() as cursor:
                return self._insert_user(cursor, username, password)

    def register_with_session(self, username: str, password: str) -> tuple[dict[str, Any], str, datetime]:
        try:
            with self._connection(transaction=True) as connection:
                with connection.cursor() as cursor:
                    user = self._insert_user(cursor, username, password)
                    token, expires_at = self._insert_session(cursor, user["id"], remember=False)
            return user, token, expires_at
        except pymysql.MySQLError as exc:
            raise AuthStorageError("Registration transaction failed") from exc

    def authenticate(self, username: str, password: str) -> dict[str, Any] | None:
        with self._connection() as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT id, username, password_hash, password_salt, created_at, role FROM users WHERE username = %s LIMIT 1",
                    (username.strip(),),
                )
                row = cursor.fetchone()
        if row is None:
            return None
        candidate = self._password_hash(password, bytes.fromhex(row["password_salt"]))
        if not hmac.compare_digest(candidate, row["password_hash"]):
            return None
        return self._public_user(row)

    def _insert_session(self, cursor, user_id: int, remember: bool) -> tuple[str, datetime]:
        token = secrets.token_urlsafe(32)
        now = datetime.now(timezone.utc)
        expires_at = now + (timedelta(days=REMEMBER_DAYS) if remember else timedelta(hours=SESSION_HOURS))
        cursor.execute(
            "INSERT INTO sessions (user_id, token_hash, expires_at, created_at) VALUES (%s, %s, %s, %s)",
            (user_id, self._token_hash(token), self._mysql_datetime(expires_at), self._mysql_datetime(now)),
        )
        return token, expires_at

    def create_session(self, user_id: int, remember: bool) -> tuple[str, datetime]:
        with self._connection(transaction=True) as connection:
            with connection.cursor() as cursor:
                return self._insert_session(cursor, user_id, remember)

    def get_user_by_session(self, token: str) -> dict[str, Any] | None:
        now = datetime.now(timezone.utc)
        token_hash = self._token_hash(token)
        with self._connection(transaction=True) as connection:
            with connection.cursor() as cursor:
                cursor.execute("DELETE FROM sessions WHERE expires_at <= %s", (self._mysql_datetime(now),))
                cursor.execute(
                    """
                    SELECT users.id, users.username, users.created_at, users.role
                    FROM sessions
                    JOIN users ON users.id = sessions.user_id
                    WHERE sessions.token_hash = %s AND sessions.expires_at > %s
                    """,
                    (token_hash, self._mysql_datetime(now)),
                )
                row = cursor.fetchone()
        return self._public_user(row) if row else None

    def delete_session(self, token: str) -> None:
        with self._connection(transaction=True) as connection:
            with connection.cursor() as cursor:
                cursor.execute(
                    "DELETE FROM sessions WHERE token_hash = %s",
                    (self._token_hash(token),),
                )
