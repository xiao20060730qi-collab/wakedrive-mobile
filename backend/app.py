"""WakeDrive inference, private account data, and administration API."""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Literal

from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field, field_validator

if __package__:
    from backend.auth import router as auth_router, optional_user, require_user
    from backend.admin import router as admin_router, require_admin
    from backend.account_data import SHANGHAI, ProtectedAccountError
    from backend.database import create_auth_store
    from backend.risk_scoring import evaluate_risk
    from backend.reports import router as reports_router
else:
    from auth import router as auth_router, optional_user, require_user
    from admin import router as admin_router, require_admin
    from account_data import SHANGHAI, ProtectedAccountError
    from database import create_auth_store
    from risk_scoring import evaluate_risk
    from reports import router as reports_router

app = FastAPI(title="WakeDrive AI Inference API", version="1.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.state.auth_store = create_auth_store()
app.include_router(auth_router)
app.include_router(admin_router)
app.include_router(reports_router)


@app.exception_handler(RequestValidationError)
async def safe_validation_error(request: Request, error: RequestValidationError):
    details = [{key: item[key] for key in ("type", "loc", "msg") if key in item} for item in error.errors()]
    return JSONResponse(status_code=422, content={"detail": details}, headers={"Cache-Control": "no-store"})


@app.middleware("http")
async def private_api_responses(request: Request, call_next):
    response = await call_next(request)
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


class FrameRequest(BaseModel):
    image: str
    timestamp: float | None = None


class MetricsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    timestamp: float | None = Field(default=None, ge=0, allow_inf_nan=False)
    face_detected: bool = False
    ear: float | None = Field(default=None, ge=0, le=2, allow_inf_nan=False)
    mar: float | None = Field(default=None, ge=0, le=5, allow_inf_nan=False)
    blink_score: float = Field(default=0, ge=0, le=1)
    yawn_score: float = Field(default=0, ge=0, le=1)
    closed_duration_ms: int = Field(default=0, ge=0, le=600000)
    yawn_duration_ms: int = Field(default=0, ge=0, le=600000)
    closed_ratio: float | None = Field(default=None, ge=0, le=1, allow_inf_nan=False)
    window_valid_ms: int = Field(default=0, ge=0, le=60000)
    bbox: dict[str, float] | None = None


class EventCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: Literal["EYE_CLOSE", "YAWN", "PHONE_USE", "HEAD_DOWN", "HEAD_AWAY", "FACE_LOST", "DRINK"]
    severity: Literal["轻度疲劳", "中度疲劳", "重度疲劳"]
    createdAt: datetime | None = None
    earValue: float = Field(ge=0, le=2)
    marValue: float = Field(ge=0, le=5)
    routeName: str = Field(default="", max_length=200)
    headPose: str = Field(default="", max_length=100)
    message: str = Field(default="", max_length=1000)
    snapshot: str | None = Field(default=None, max_length=100000)

    @field_validator("snapshot")
    @classmethod
    def validate_snapshot(cls, value: str | None) -> str | None:
        if value is not None and not value.startswith("data:image/"):
            raise ValueError("snapshot must be an inline data:image URL")
        return value


class EventPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: Literal["待处理", "已处理"] | None = None
    handledBy: str | None = Field(default=None, max_length=100)
    handledAt: datetime | None = None


class SettingsUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    earThreshold: float | None = Field(default=None, ge=0.05, le=0.8)
    marThreshold: float | None = Field(default=None, ge=0.1, le=1.0)
    mildFrames: int | None = Field(default=None, ge=1, le=30)
    mediumFrames: int | None = Field(default=None, ge=1, le=60)
    heavyFrames: int | None = Field(default=None, ge=1, le=120)
    cooldownMs: int | None = Field(default=None, ge=100, le=60000)
    detectIntervalMs: int | None = Field(default=None, ge=100, le=10000)
    alarmEnabled: bool | None = None
    alarmStyle: str | None = Field(default=None, max_length=40)
    driverName: str | None = Field(default=None, max_length=100)
    plateNumber: str | None = Field(default=None, max_length=40)


class AiChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: str = Field(min_length=1, max_length=1000)


AI_RATE_LIMIT = 20
AI_RATE_WINDOW_SECONDS = 60
ai_request_times: dict[str, list[float]] = {}


def enforce_ai_rate_limit(request: Request) -> None:
    """Limit public AI proxy usage before it can spend the server-side key."""
    client_ip = request.headers.get("x-real-ip") or (request.client.host if request.client else "unknown")
    now = time.monotonic()
    recent = [stamp for stamp in ai_request_times.get(client_ip, []) if now - stamp < AI_RATE_WINDOW_SECONDS]
    if len(recent) >= AI_RATE_LIMIT:
        raise HTTPException(429, "AI 请求过于频繁，请稍后重试", headers={"Retry-After": "60"})
    recent.append(now)
    ai_request_times[client_ip] = recent


def score_metrics(req: MetricsRequest, settings: dict[str, Any] | None = None) -> dict[str, Any]:
    """Score metrics produced by the browser Face Landmarker model."""
    if not req.face_detected:
        return {"face_detected": False, "ear": None, "mar": None, "blink_score": 0, "yawn_score": 0,
                "closed_duration_ms": req.closed_duration_ms, "bbox": None, "score": 0,
                "risk": "未检测到人脸", "severity": "none"}
    config = settings or {}
    metrics = req.model_dump()
    if req.ear is not None and req.ear >= config.get("earThreshold", .25):
        metrics["closed_duration_ms"] = 0
    if req.mar is not None and req.mar <= config.get("marThreshold", .55):
        metrics["yawn_duration_ms"] = 0
    result = evaluate_risk(metrics, config)
    return {"face_detected": True, "ear": req.ear, "mar": req.mar, "blink_score": req.blink_score,
            "yawn_score": req.yawn_score, "closed_duration_ms": metrics["closed_duration_ms"], "bbox": req.bbox,
            "yawn_duration_ms": metrics["yawn_duration_ms"], "closed_ratio": req.closed_ratio,
            "window_valid_ms": req.window_valid_ms, **result}


def build_ai_context(events: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "total_events": len(events),
        "pending_events": sum(item.get("status") == "待处理" for item in events),
        "severity_counts": {
            "light": sum(item.get("severity") == "轻度疲劳" for item in events),
            "medium": sum(item.get("severity") == "中度疲劳" for item in events),
            "heavy": sum(item.get("severity") == "重度疲劳" for item in events),
        },
        "event_type_counts": {
            "eye_close": sum(item.get("type") == "EYE_CLOSE" for item in events),
            "yawn": sum(item.get("type") == "YAWN" for item in events),
            "phone_use": sum(item.get("type") == "PHONE_USE" for item in events),
            "head_down": sum(item.get("type") == "HEAD_DOWN" for item in events),
            "head_away": sum(item.get("type") == "HEAD_AWAY" for item in events),
            "face_lost": sum(item.get("type") == "FACE_LOST" for item in events),
            "drink": sum(item.get("type") == "DRINK" for item in events),
        },
    }


def request_deepseek(question: str, context: dict[str, Any]) -> str:
    api_key = os.getenv("DEEPSEEK_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(503, "DeepSeek API is not configured")

    payload = {
        "model": "deepseek-v4-pro",
        "thinking": {"type": "disabled"},
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是 WakeDrive 通用 AI 助手。无论用户询问驾驶安全、常识、学习、生活、技术或其他合法问题，"
                    "都要直接针对问题回答，并使用简洁中文。匿名疲劳事件统计只是可选背景："
                    "仅当用户询问当前驾驶数据时才引用；无论当前是否有事件记录，都要继续回答能够回答的问题。"
                    "不要因为问题与疲劳驾驶无关而拒绝回答，也不要把无关问题强行解释成疲劳风险。"
                    "对于需要实时外部数据而当前上下文没有提供的问题，应明确说明信息时效限制，不得编造。"
                    "涉及驾驶高风险时，建议在安全地点停车休息。"
                ),
            },
            {
                "role": "user",
                "content": (
                    f"匿名疲劳事件统计：{json.dumps(context, ensure_ascii=False)}\n"
                    f"用户问题：{question}"
                ),
            },
        ],
        "temperature": 0.3,
        "max_tokens": 500,
    }
    request = urllib.request.Request(
        "https://api.deepseek.com/v1/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.loads(response.read().decode("utf-8"))
        answer = result["choices"][0]["message"]["content"].strip()
    except (urllib.error.URLError, TimeoutError, ValueError, KeyError, IndexError, TypeError):
        raise HTTPException(502, "DeepSeek API request failed")
    if not answer:
        raise HTTPException(502, "DeepSeek API returned an empty answer")
    return answer


def _parse_event_date(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(value[:10])
    except ValueError:
        raise HTTPException(400, "from/to must be ISO dates")


def _parse_created_at(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def filter_events(items: list[dict[str, Any]], severity: str | None, status: str | None, type_: str | None,
                  from_: str | None, to: str | None) -> list[dict[str, Any]]:
    start, end = _parse_event_date(from_), _parse_event_date(to)
    result = []
    for item in items:
        if severity and item.get("severity") != severity:
            continue
        if status and item.get("status") != status:
            continue
        if type_ and item.get("type") != type_:
            continue
        created = _parse_created_at(item["createdAt"]).astimezone(SHANGHAI).date()
        if start and created < start:
            continue
        if end and created > end:
            continue
        result.append(item)
    return result


def event_summary(items: list[dict[str, Any]]) -> dict[str, int]:
    today = datetime.now(SHANGHAI).date()
    return {"total": len(items), "pending": sum(item.get("status") == "待处理" for item in items),
            "highRisk": sum(item.get("severity") == "重度疲劳" for item in items),
            "today": sum(_parse_created_at(item["createdAt"]).astimezone(SHANGHAI).date() == today for item in items)}


def summary_for_range(range_name: str, events: list[dict[str, Any]]) -> dict[str, Any]:
    days = 30 if range_name == "30d" else 7 if range_name == "7d" else 1
    now = datetime.now(timezone.utc)
    start = (now.astimezone(SHANGHAI).replace(hour=0, minute=0, second=0, microsecond=0)
             if range_name == "today" else now - timedelta(days=days))
    selected = [item for item in events if _parse_created_at(item["createdAt"]) >= start]
    severity = {"轻度疲劳": 0, "中度疲劳": 0, "重度疲劳": 0}
    types = {"EYE_CLOSE": 0, "YAWN": 0, "PHONE_USE": 0, "HEAD_DOWN": 0, "HEAD_AWAY": 0, "FACE_LOST": 0, "DRINK": 0}
    for item in selected:
        if item.get("severity") in severity:
            severity[item["severity"]] += 1
        if item.get("type") in types:
            types[item["type"]] += 1
    trend = []
    for offset in range(6, -1, -1):
        day_end = (now.astimezone(SHANGHAI) - timedelta(days=offset)).date()
        trend.append({"label": f"{day_end.month}/{day_end.day}", "count": sum(_parse_created_at(item["createdAt"]).astimezone(SHANGHAI).date() == day_end for item in selected)})
    return {"totalRecords": len(selected), "typeDistribution": types, "severityDistribution": severity, "trend": trend}


@app.get("/api/health")
def health():
    return {"status": "ok", "data_mode": "database", "auth_database": app.state.auth_store.backend_name, "models": {"face_landmarker": {"source": "browser", "status": "ready"}, "risk_scoring": {"source": "python", "status": "ready"}, "legacy_image_endpoint": {"status": "disabled"}}}


@app.post("/api/ai/chat")
def ai_chat(req: AiChatRequest, request: Request):
    enforce_ai_rate_limit(request)
    question = req.question.strip()
    if not question:
        raise HTTPException(422, "question must not be blank")
    user = optional_user(request)
    events = request.app.state.auth_store.list_events(user["id"]) if user else []
    return {
        "answer": request_deepseek(question, build_ai_context(events)),
        "provider": "deepseek",
    }


def _amap_env(name: str) -> str:
    return os.getenv(name, "").strip()


def _amap_get_json(url: str) -> dict[str, Any]:
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError):
        raise HTTPException(502, "高德地图服务请求失败")


def _amap_gps_to_gcj(key: str, lat: float, lng: float) -> tuple[float, float]:
    # 浏览器定位是 WGS-84，高德检索与地图使用 GCJ-02，不转换会偏移数百米。
    params = urllib.parse.urlencode({"key": key, "locations": f"{lng:.6f},{lat:.6f}", "coordsys": "gps"})
    try:
        payload = _amap_get_json(f"https://restapi.amap.com/v3/assistant/coordinate/convert?{params}")
        if payload.get("status") == "1" and payload.get("locations"):
            converted_lng, converted_lat = (float(value) for value in payload["locations"].split(","))
            return converted_lat, converted_lng
    except (HTTPException, ValueError):
        pass
    return lat, lng


@app.get("/api/map-config")
def map_config(request: Request):
    require_user(request)
    js_key, js_code = _amap_env("AMAP_JS_KEY"), _amap_env("AMAP_JS_CODE")
    map_enabled = bool(js_key and js_code)
    return {
        "restStopsEnabled": bool(_amap_env("AMAP_WEB_KEY")),
        "mapEnabled": map_enabled,
        "jsKey": js_key if map_enabled else "",
        "serviceHost": "/_AMapService",
    }


@app.get("/api/rest-stops")
def rest_stops(request: Request, lat: float = Query(ge=-90, le=90), lng: float = Query(ge=-180, le=180)):
    require_user(request)
    key = _amap_env("AMAP_WEB_KEY")
    if not key:
        raise HTTPException(503, "高德地图 Key 未配置，暂无法查询休息点")
    lat, lng = _amap_gps_to_gcj(key, lat, lng)
    params = urllib.parse.urlencode({
        "key": key, "location": f"{lng:.6f},{lat:.6f}", "keywords": "服务区",
        "radius": 50000, "sortrule": "distance", "offset": 5, "page": 1,
    })
    payload = _amap_get_json(f"https://restapi.amap.com/v3/place/around?{params}")
    if payload.get("status") != "1":
        raise HTTPException(502, "高德地图服务返回异常")
    items = []
    for poi in payload.get("pois") or []:
        try:
            poi_lng, poi_lat = (float(value) for value in str(poi.get("location", "")).split(","))
            distance = int(float(poi.get("distance") or 0))
        except ValueError:
            continue
        address = poi.get("address")
        items.append({
            "name": str(poi.get("name") or "未命名服务区"),
            "distanceMeters": distance,
            "address": address if isinstance(address, str) else "",
            "lng": poi_lng, "lat": poi_lat,
        })
    return {"items": items[:5], "center": {"lat": lat, "lng": lng}}


@app.get("/_AMapService/{path:path}")
def amap_js_proxy(path: str, request: Request):
    require_user(request)
    js_code = _amap_env("AMAP_JS_CODE")
    if not js_code:
        raise HTTPException(503, "高德地图安全密钥未配置")
    if not path.startswith(("v3/", "v4/", "v5/", "ws/")):
        raise HTTPException(404, "Unsupported AMap service path")
    query = {**request.query_params, "jscode": js_code}
    url = f"https://restapi.amap.com/{path}?{urllib.parse.urlencode(query)}"
    try:
        with urllib.request.urlopen(url, timeout=15) as upstream:
            body = upstream.read()
            content_type = upstream.headers.get("Content-Type", "application/json")
    except (urllib.error.URLError, TimeoutError):
        raise HTTPException(502, "高德地图服务请求失败")
    return Response(content=body, media_type=content_type)


@app.post("/api/analyze/metrics")
def analyze_metrics(req: MetricsRequest, request: Request):
    user = require_user(request)
    result = score_metrics(req, request.app.state.auth_store.get_settings(user["id"]))
    result["timestamp"] = req.timestamp or time.time()
    request.app.state.auth_store.save_monitor_status(user["id"], result)
    return {"ok": True, "models": {"face_landmarker": {"source": "browser", "face_detected": req.face_detected}, "risk_scoring": {"source": "python"}}, "result": result}


@app.post("/api/monitor/stop")
def stop_monitoring(request: Request):
    user = require_user(request)
    request.app.state.auth_store.stop_monitoring(user["id"])
    return {"ok": True}


@app.post("/api/analyze")
def analyze(req: FrameRequest):
    _ = req
    raise HTTPException(501, "Image inference is disabled because no server-side model is configured; use the browser Face Landmarker and /api/analyze/metrics.")


@app.get("/api/events")
def get_events(request: Request, severity: str | None = None, status: str | None = None,
               type_: str | None = Query(default=None, alias="type"),
               from_: str | None = Query(default=None, alias="from"), to: str | None = None,
               limit: int = 100):
    user = require_user(request)
    filtered = filter_events(request.app.state.auth_store.list_events(user["id"]), severity, status, type_, from_, to)
    summary = event_summary(filtered)
    return {"items": filtered[:max(1, min(limit, 500))], "summary": summary}


@app.post("/api/events", status_code=201)
def create_event(event: EventCreate, request: Request):
    user = require_user(request)
    return request.app.state.auth_store.create_event(user["id"], event.model_dump(mode="json", exclude_none=True))


@app.patch("/api/events/{event_id}")
def patch_event(event_id: str, patch: EventPatch, request: Request):
    user = require_user(request)
    updates = patch.model_dump(mode="json", exclude_none=True)
    event = request.app.state.auth_store.patch_event(user["id"], event_id, updates)
    if event is not None:
        return event
    raise HTTPException(404, "Event not found")


@app.delete("/api/events")
def delete_events(request: Request):
    user = require_user(request)
    count = request.app.state.auth_store.delete_events(user["id"])
    return {"ok": True, "deleted": count}


@app.get("/api/analysis/summary")
def get_summary(request: Request, range: str = "today"):
    user = require_user(request)
    if range not in {"today", "7d", "30d"}:
        raise HTTPException(400, "range must be today, 7d or 30d")
    return summary_for_range(range, request.app.state.auth_store.list_events(user["id"]))


@app.get("/api/settings")
def get_settings(request: Request):
    user = require_user(request)
    return request.app.state.auth_store.get_settings(user["id"])


@app.put("/api/settings")
def update_settings(patch: SettingsUpdate, request: Request):
    user = require_user(request)
    updates = patch.model_dump(exclude_none=True)
    settings = {**request.app.state.auth_store.get_settings(user["id"]), **updates}
    if not settings["mildFrames"] <= settings["mediumFrames"] <= settings["heavyFrames"]:
        raise HTTPException(422, "疲劳触发帧数须按轻、中、重递增。")
    return request.app.state.auth_store.save_settings(user["id"], updates)


@app.get("/api/admin/users")
def admin_users(request: Request, response: Response, search: str = Query(default="", max_length=100),
                severity: str = Query(default="", max_length=24), limit: int = Query(default=100, ge=1, le=500),
                offset: int = Query(default=0, ge=0)):
    require_admin(request)
    response.headers["Cache-Control"] = "no-store"
    return request.app.state.auth_store.admin_users(search=search.strip(), severity=severity, limit=limit, offset=offset)


@app.get("/api/admin/users/{user_id}/events")
def admin_user_events(user_id: int, request: Request, response: Response,
                      severity: str | None = None, limit: int = Query(default=100, ge=1, le=500)):
    require_admin(request)
    store = request.app.state.auth_store
    user = store.get_user(user_id)
    if user is None:
        raise HTTPException(404, "账号不存在")
    events = filter_events(store.list_events(user_id), severity, None, None, None, None)
    response.headers["Cache-Control"] = "no-store"
    return {"user": {"id": user["id"], "username": user["username"]},
            "items": events[:limit], "summary": event_summary(events)}


@app.delete("/api/admin/users/{user_id}")
def admin_delete_user(user_id: int, request: Request):
    require_admin(request)
    try:
        deleted = request.app.state.auth_store.delete_user(user_id)
    except ProtectedAccountError as exc:
        raise HTTPException(409, "不能删除管理员账号") from exc
    if not deleted:
        raise HTTPException(404, "账号不存在")
    return {"ok": True, "deletedUserId": user_id}


PROJECT_ROOT = Path(__file__).resolve().parent.parent
FRONTEND_ROOT = PROJECT_ROOT / "frontend"


@app.get("/", include_in_schema=False)
@app.get("/index.html", include_in_schema=False)
def frontend_home():
    return FileResponse(PROJECT_ROOT / "index.html")


app.mount(
    "/frontend",
    StaticFiles(directory=FRONTEND_ROOT, html=True),
    name="frontend",
)


def main() -> None:
    """Run the API directly without a separate launcher script."""
    import uvicorn

    uvicorn.run(
        app,
        host=os.getenv("WAKEDRIVE_HOST", "127.0.0.1"),
        port=int(os.getenv("WAKEDRIVE_PORT", "8000")),
    )


if __name__ == "__main__":
    main()
