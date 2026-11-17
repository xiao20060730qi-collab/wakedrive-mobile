"""Environment-driven authentication database selection."""

import os
from pathlib import Path
from typing import Mapping

if __package__:
    from backend.auth import AuthStore
else:
    from auth import AuthStore


class DatabaseConfigurationError(RuntimeError):
    """Raised when the configured authentication database is invalid."""


MYSQL_REQUIRED = (
    "WAKEDRIVE_MYSQL_HOST",
    "WAKEDRIVE_MYSQL_USER",
    "WAKEDRIVE_MYSQL_PASSWORD",
    "WAKEDRIVE_MYSQL_DATABASE",
)


def create_auth_store(
    environ: Mapping[str, str] | None = None,
    mysql_store_class=None,
):
    """Create the configured authentication store."""
    config = os.environ if environ is None else environ
    engine = (config.get("WAKEDRIVE_DB_ENGINE") or "").strip().lower() or "auto"
    if engine not in {"auto", "sqlite", "mysql"}:
        raise DatabaseConfigurationError(
            "WAKEDRIVE_DB_ENGINE must be sqlite, mysql or auto"
        )

    if engine == "auto":
        engine = "mysql" if (config.get("WAKEDRIVE_MYSQL_HOST") or "").strip() else "sqlite"

    if engine == "sqlite":
        db_path = config.get("WAKEDRIVE_DB_PATH") or Path(__file__).with_name("wakedrive.db")
        return AuthStore(db_path)

    mysql_config = {name: (config.get(name) or "").strip() for name in MYSQL_REQUIRED}
    missing = [name for name, value in mysql_config.items() if not value]
    if missing:
        raise DatabaseConfigurationError(
            "Missing required MySQL configuration: " + ", ".join(missing)
        )

    port_value = config.get("WAKEDRIVE_MYSQL_PORT", "3306")
    try:
        port = int(port_value)
    except (TypeError, ValueError) as exc:
        raise DatabaseConfigurationError(
            "WAKEDRIVE_MYSQL_PORT must be an integer"
        ) from exc

    if mysql_store_class is None:
        if __package__:
            from backend.mysql_auth import MySQLAuthStore
        else:
            from mysql_auth import MySQLAuthStore
        mysql_store_class = MySQLAuthStore

    return mysql_store_class(
        host=mysql_config["WAKEDRIVE_MYSQL_HOST"],
        port=port,
        user=mysql_config["WAKEDRIVE_MYSQL_USER"],
        password=config["WAKEDRIVE_MYSQL_PASSWORD"],
        database=mysql_config["WAKEDRIVE_MYSQL_DATABASE"],
    )
