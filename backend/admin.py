"""Administrator authentication uses its own server-side session namespace."""
from datetime import datetime, timezone
import os

from fastapi import APIRouter, HTTPException, Request, Response
from pydantic import BaseModel, ConfigDict, Field

if __package__:
    from backend.auth import (LoginRequest, login_failures, check_login_attempts,
                              record_login_failure, clear_login_failures)
    from backend.account_data import ProtectedAccountError
else:
    from auth import (LoginRequest, login_failures, check_login_attempts,
                      record_login_failure, clear_login_failures)
    from account_data import ProtectedAccountError


ADMIN_COOKIE_NAME = "wakedrive_admin_session"
ADMIN_COOKIE_PATH = "/api/admin"
SUPER_ADMIN_USERNAME = "xiaoqi"
admin_login_failures = login_failures
router = APIRouter(prefix="/api/admin", tags=["administration"])

USERNAME_PATTERN = r"^[A-Za-z0-9_\u4e00-\u9fff]{3,24}$"


class AdminCreateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=3, max_length=24, pattern=USERNAME_PATTERN)
    password: str = Field(min_length=8, max_length=128)


class AdminPasswordRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    password: str = Field(min_length=8, max_length=128)


def require_admin(request: Request):
    token = request.cookies.get(ADMIN_COOKIE_NAME)
    store = request.app.state.auth_store
    user = store.get_admin_user_by_session(token) if token else None
    if user is None:
        raise HTTPException(401, "请先登录管理员后台")
    if user.get("role") != "admin":
        raise HTTPException(403, "此账号没有管理员权限")
    return user


def require_super_admin(request: Request):
    user = require_admin(request)
    if str(user.get("username", "")).casefold() != SUPER_ADMIN_USERNAME:
        raise HTTPException(403, "只有 xiaoqi 管理员可以管理管理员账号")
    return user


@router.post("/login")
def admin_login(body: LoginRequest, request: Request, response: Response):
    # request.client is set through Uvicorn's explicitly trusted proxy configuration.
    client_ip = request.client.host if request.client else "unknown"
    check_login_attempts(client_ip, body.username)
    store = request.app.state.auth_store
    user = store.authenticate(body.username, body.password)
    if user is None:
        record_login_failure(client_ip, body.username)
        raise HTTPException(401, "管理员账号或密码不正确")
    if user.get("role") != "admin":
        record_login_failure(client_ip, body.username)
        raise HTTPException(403, "此账号没有管理员权限")
    token, expires_at = store.create_admin_session(user["id"], body.remember)
    clear_login_failures(client_ip, body.username)
    max_age = max(1, int((expires_at - datetime.now(timezone.utc)).total_seconds())) if body.remember else None
    response.set_cookie(ADMIN_COOKIE_NAME, token, max_age=max_age, httponly=True,
                        samesite="strict", path=ADMIN_COOKIE_PATH,
                        secure=os.getenv("WAKEDRIVE_COOKIE_SECURE", "false").strip().lower() in {"1", "true", "yes", "on"})
    response.headers["Cache-Control"] = "no-store"
    return {"ok": True, "user": user}


@router.get("/me")
def admin_me(request: Request, response: Response):
    user = require_admin(request)
    response.headers["Cache-Control"] = "no-store"
    return {"ok": True, "user": user}


@router.post("/logout")
def admin_logout(request: Request, response: Response):
    token = request.cookies.get(ADMIN_COOKIE_NAME)
    if token:
        request.app.state.auth_store.delete_admin_session(token)
    response.delete_cookie(ADMIN_COOKIE_NAME, path=ADMIN_COOKIE_PATH, httponly=True, samesite="strict")
    response.headers["Cache-Control"] = "no-store"
    return {"ok": True}


@router.get("/admins")
def list_admins(request: Request, response: Response):
    require_super_admin(request)
    response.headers["Cache-Control"] = "no-store"
    return {"ok": True, "items": request.app.state.auth_store.list_admins()}


@router.post("/admins", status_code=201)
def create_admin(body: AdminCreateRequest, request: Request, response: Response):
    require_super_admin(request)
    try:
        user = request.app.state.auth_store.ensure_admin(body.username, body.password)
    except ValueError as exc:
        raise HTTPException(422, "管理员账号格式不正确") from exc
    response.headers["Cache-Control"] = "no-store"
    return {"ok": True, "user": user}


@router.put("/admins/{admin_id}/password")
def reset_admin_password(admin_id: int, body: AdminPasswordRequest, request: Request, response: Response):
    require_super_admin(request)
    try:
        updated = request.app.state.auth_store.reset_admin_password(admin_id, body.password)
    except ValueError as exc:
        raise HTTPException(422, "密码长度需为 8 到 128 个字符") from exc
    if not updated:
        raise HTTPException(404, "管理员账号不存在")
    response.headers["Cache-Control"] = "no-store"
    return {"ok": True}


@router.delete("/admins/{admin_id}")
def delete_admin(admin_id: int, request: Request):
    operator = require_super_admin(request)
    if int(admin_id) == int(operator["id"]):
        raise HTTPException(400, "不能删除当前登录的管理员账号")
    try:
        deleted = request.app.state.auth_store.delete_admin(admin_id)
    except ProtectedAccountError as exc:
        raise HTTPException(400, str(exc)) from exc
    if not deleted:
        raise HTTPException(404, "管理员账号不存在")
    return {"ok": True}
