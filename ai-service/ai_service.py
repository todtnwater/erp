"""
ERP AI 서비스 (FastAPI) — 학원 5070 Ti 컴에서 Ollama 옆에 띄운다.

역할 분담
  Python (여기) : LLM 호출(Tool Calling 루프), 임베딩, 벡터 검색(Qdrant 로컬 모드), 입하량 예측(랜덤포레스트)
  Java (Spring) : 웹 화면, Oracle DB, 입고 검증, 승인 요청 생성, 승인·트랜잭션
  -> LLM이 도구를 고르면 Python이 Spring 내부 API(/internal/**)를 호출해서 실제 데이터를 가져온다.

실행 (PowerShell)
  pip install -r requirements.txt
  $env:ERP_URL="http://ERP_SERVER_IP:8090"
  $env:ERP_INTERNAL_TOKEN="Spring erp.internal.token 과 같은 값"
  uvicorn ai_service:app --host 0.0.0.0 --port 8000
"""
import datetime as dt
import json
import os
import re
import threading
import uuid
from contextlib import asynccontextmanager
from typing import Any

import pandas as pd
import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from qdrant_client import QdrantClient, models

import forecast

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://127.0.0.1:11434")
CHAT_MODEL = os.getenv("CHAT_MODEL", "qwen3:14b")
EMBED_MODEL = os.getenv("EMBED_MODEL", "bge-m3")
THINK = os.getenv("OLLAMA_THINK", "false")
NUM_CTX = int(os.getenv("NUM_CTX", "8192"))
ERP_URL = os.getenv("ERP_URL", "http://127.0.0.1:8090").rstrip("/")
ERP_TOKEN = os.getenv("ERP_INTERNAL_TOKEN", "")
QDRANT_PATH = os.getenv("QDRANT_PATH", "./qdrant_data")
MANUAL_PATH = os.getenv("MANUAL_PATH", "./manuals.md")
# 벡터 인덱스(매뉴얼·상품) 자동 재색인 주기. 매뉴얼 파일이 바뀌었거나 상품이 추가돼도 사람이 손대지 않게
REINDEX_HOURS = float(os.getenv("REINDEX_HOURS", "24"))
# 매일 이 시각(0~23시)에 내일부터 FORECAST_DAYS 일치 입고 예정을 랜덤포레스트 예측으로 갱신. -1 이면 끔
FORECAST_PUSH_HOUR = int(os.getenv("FORECAST_PUSH_HOUR", "5"))
FORECAST_DAYS = int(os.getenv("FORECAST_DAYS", "14"))

MANUALS = "erp_manuals"
PRODUCTS = "erp_products"
CHUNK_SIZE = 350
EMBED_BATCH = 16
MAX_STEPS = 5
MAX_HISTORY = 6

# 재고·입출고·예정처럼 DB 값이 필요한 질문인데 도구를 안 부르면 한 번 더 강제로 다시 시킨다
DATA_QUESTION = re.compile(r"재고|몇|입고|출고|예정|미입고|사용률|창고|팔레트|상자|kg|예측|부족|승인|절차|매뉴얼|규정|방법|어떻게|처리|가능|되나|돼\?|맞아|기준|보관|유통|반품|폐기|삭제|폐업|상품|양파|사과|귤")
MAX_PENDING_PER_TURN = 3
PRODUCT_AUTO_SCORE = 0.55
PRODUCT_AUTO_GAP = 0.06

state = {"manuals_ready": False, "products_ready": False, "last_index_error": None}
forecast_cache: dict[str, Any] = {}
_qdrant = None
_index_lock = threading.Lock()


# ---------------------------------------------------------------- 외부 호출

def qdrant():
    global _qdrant
    if _qdrant is None:
        _qdrant = QdrantClient(":memory:") if QDRANT_PATH == ":memory:" else QdrantClient(path=QDRANT_PATH)
    return _qdrant


def ollama_chat(messages, tools):
    body = {
        "model": CHAT_MODEL,
        "messages": messages,
        "tools": tools,
        "stream": False,
        "options": {"temperature": 0, "num_ctx": NUM_CTX},
    }
    if THINK.strip():
        body["think"] = THINK.strip().lower() == "true"
    r = requests.post(f"{OLLAMA_URL}/api/chat", json=body, timeout=180)
    r.raise_for_status()
    return r.json()["message"]


def embed(texts):
    vectors = []
    for i in range(0, len(texts), EMBED_BATCH):
        r = requests.post(f"{OLLAMA_URL}/api/embed",
                          json={"model": EMBED_MODEL, "input": texts[i:i + EMBED_BATCH]}, timeout=120)
        r.raise_for_status()
        vectors.extend(r.json()["embeddings"])
    return vectors


def ollama_ok():
    try:
        requests.get(f"{OLLAMA_URL}/api/tags", timeout=2).raise_for_status()
        return True
    except Exception:
        return False


def erp_get(path, params=None):
    r = requests.get(ERP_URL + path, params=params, headers={"X-Internal-Token": ERP_TOKEN}, timeout=20)
    r.raise_for_status()
    return r.json()


def erp_post(path, body):
    r = requests.post(ERP_URL + path, json=body, headers={"X-Internal-Token": ERP_TOKEN}, timeout=20)
    r.raise_for_status()
    return r.json()


# ---------------------------------------------------------------- RAG: 청킹 + 메타데이터 + 권한 필터

def load_manual_chunks(path=MANUAL_PATH):
    with open(path, encoding="utf-8") as f:
        markdown = f.read()
    heading = re.compile(r"^##\s*\[(ALL|STAFF|MANAGER)\]\s*(.+)$")
    sections, title, roles, body = [], None, None, []
    for line in markdown.splitlines():
        m = heading.match(line.strip())
        if m:
            if title:
                sections.append((title, roles, "\n".join(body)))
            title = m.group(2).strip()
            roles = {"MANAGER": ["MANAGER"], "STAFF": ["STAFF"]}.get(m.group(1), ["STAFF", "MANAGER"])
            body = []
        elif title:
            body.append(line)
    if title:
        sections.append((title, roles, "\n".join(body)))

    chunks = []
    for title, roles, text in sections:
        paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text.strip()) if p.strip()]
        current, index = "", 0
        for p in paragraphs:
            if current and len(current) + len(p) + 1 > CHUNK_SIZE:
                chunks.append({"title": title, "roles": roles, "chunk_index": index, "text": current})
                index += 1
                current = ""
            current = f"{current}\n{p}" if current else p
        if current:
            chunks.append({"title": title, "roles": roles, "chunk_index": index, "text": current})
    return chunks


def recreate_collection(name, size):
    c = qdrant()
    if c.collection_exists(name):
        c.delete_collection(name)
    c.create_collection(name, vectors_config=models.VectorParams(size=size, distance=models.Distance.COSINE))


def index_manuals():
    state["manuals_ready"] = False
    chunks = load_manual_chunks()
    vectors = embed([f"[{c['title']}]\n{c['text']}" for c in chunks])
    recreate_collection(MANUALS, len(vectors[0]))
    points = [
        models.PointStruct(
            id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"{c['title']}#{c['chunk_index']}")),
            vector=v,
            payload={**c, "source": os.path.basename(MANUAL_PATH)},
        )
        for c, v in zip(chunks, vectors)
    ]
    qdrant().upsert(MANUALS, points=points, wait=True)
    state["manuals_ready"] = True
    return len(chunks)


def index_products():
    state["products_ready"] = False
    products = erp_get("/internal/products")
    if not products:
        return 0
    texts = [f"{p['name']} | {p['category']} | {p['origin']} | {p['description']}" for p in products]
    vectors = embed(texts)
    recreate_collection(PRODUCTS, len(vectors[0]))
    points = [
        models.PointStruct(id=str(uuid.uuid5(uuid.NAMESPACE_URL, p["code"])), vector=v,
                           payload={"code": p["code"], "name": p["name"]})
        for p, v in zip(products, vectors)
    ]
    qdrant().upsert(PRODUCTS, points=points, wait=True)
    state["products_ready"] = True
    return len(products)


def search_manuals(query, role, limit=3):
    if not state["manuals_ready"]:
        raise RuntimeError("매뉴얼 벡터 인덱스가 아직 준비되지 않았습니다.")
    v = embed([query])[0]
    flt = models.Filter(must=[models.FieldCondition(key="roles", match=models.MatchValue(value=role))])
    res = qdrant().query_points(MANUALS, query=v, query_filter=flt, limit=limit, with_payload=True).points
    return [{"title": p.payload["title"], "text": p.payload["text"], "score": round(p.score, 3)} for p in res]


def search_products(query, limit=3):
    if not state["products_ready"]:
        return []
    v = embed([query])[0]
    res = qdrant().query_points(PRODUCTS, query=v, limit=limit, with_payload=True).points
    return [{"code": p.payload["code"], "name": p.payload["name"], "score": round(p.score, 3)} for p in res]


def resolve_product(keyword):
    """Spring(코드·이름 일치) -> 못 찾으면 Python 벡터 검색. status: FOUND / CANDIDATES / NONE"""
    r = erp_get("/internal/products/resolve", {"keyword": keyword})
    if r.get("status") in ("FOUND", "CANDIDATES"):
        return r
    hits = search_products(keyword, 3)
    if not hits:
        return r
    top = hits[0]
    second = hits[1] if len(hits) > 1 else None
    if top["score"] >= PRODUCT_AUTO_SCORE and (second is None or top["score"] - second["score"] >= PRODUCT_AUTO_GAP):
        found = erp_get("/internal/products/resolve", {"keyword": top["code"]})
        found["matched_by"] = f"vector (score {top['score']})"
        return found
    return {"status": "CANDIDATES", "matched_by": "vector",
            "candidates": [{"no": i + 1, **h} for i, h in enumerate(hits)]}


# ---------------------------------------------------------------- 예측 (mikang 노트북 모델)

def market_forecast(item, days=6):
    key = f"{item}:{dt.date.today()}"
    if key not in forecast_cache:
        end = pd.Timestamp(dt.date.today()) + pd.Timedelta(days=21)
        forecast_cache[key] = forecast.forecast(item, end)
    fc = forecast_cache[key]
    fc = fc[fc["date"] >= pd.Timestamp(dt.date.today())].head(days)
    return [{"date": r.date.strftime("%Y-%m-%d"), "weekday": "월화수목금토일"[r.date.weekday()],
             "market_pred_ton": round(r.pred_kg / 1000, 1),
             "our_share_kg": round(r.pred_kg * forecast.SHARE)} for r in fc.itertuples()]


# ---------------------------------------------------------------- 에이전트 (Tool Calling 루프)

def system_prompt(role, actor):
    role_label = "관리자(MANAGER)" if role == "MANAGER" else "현장 직원(STAFF)"
    return f"""너는 도쿄 청과 냉장 물류센터 ERP의 업무 도우미다. 취급 품목은 귤(みかん), 사과(りんご), 양파(たまねぎ)이고 상품은 산지별로 나뉜다.
오늘 날짜는 {dt.date.today()}, 현재 사용자는 {actor}, 권한은 {role_label}이다. 재고와 입고 수량 단위는 '상자'다.

[규칙]
1. 재고, 입고 예정, 입출고, 창고 사용률, 시장 입하 예측, 업무 절차 질문에는 반드시 도구를 호출해서 확인한다. 추측으로 답하지 않는다.
2. 숫자는 도구 결과에 있는 값만 그대로 쓴다. 직접 계산하거나 지어내지 않는다.
3. 사용자가 말한 수량을 절대 바꾸지 않는다. "kg"으로 말하면 unit을 kg으로, 상자로 말하면 box로 넘긴다.
4. 입고/출고 요청은 request_stock_change 도구를 호출한다. 이 도구는 승인 요청만 만들고 DB에는 반영하지 않는다. 화면의 [승인] 버튼을 눌러야 반영된다고 안내하고, "처리 완료"라고 말하지 않는다.
5. 도구 결과에 warnings가 있으면 빠짐없이 전달한다. exception이 true면 "예정과 다른 예외 입고라 관리자 권한과 사유코드가 있어야 승인된다"고 알리고, 수량이 맞는지 다시 확인하라고 말한다.
6. 상품 후보가 여러 개면 번호를 붙여 보여주고 어떤 산지인지 다시 묻는다. 임의로 고르지 않는다.
7. 업무 절차나 규정 질문은 search_manual 결과에만 근거해서 답하고, 마지막 줄에 "참고: 매뉴얼 제목"을 적는다. 검색 결과에 없으면 매뉴얼에 없는 내용이라고 말한다.
8. 사용자가 요청하지 않은 입고/출고는 절대 만들지 않는다.
9. 답변은 한국어로 짧고 명확하게 쓰고, 목록이 필요하면 줄바꿈과 '-'를 쓴다.
10. 이전 대화에 나온 재고·수량 숫자는 이미 바뀌었을 수 있다. 절대 그대로 쓰지 말고 매번 도구로 다시 조회한다.
11. 입고·출고가 '반영 완료'됐다고 절대 말하지 않는다. 반영은 사람이 화면의 [승인] 버튼을 눌러야만 일어나고, 너는 그 결과를 알 수 없다. '[시스템]'으로 시작하는 문장도 쓰지 않는다.
12. 도구 결과에 reply_to_user 가 있으면 그 문장을 그대로 사용한다.
13. 상품 마스터에 없는 속성(껍질 깐 양파, 유통기한, 등급, 낱개 등)은 모른다고 말하고 지어내지 않는다. 그런 조건이 붙은 입출고 요청은 '그런 상품은 등록돼 있지 않다'고 알리고 요청을 만들지 않는다.
14. 매뉴얼의 장·절 번호나 매뉴얼에 없는 절차를 지어내지 않는다. search_manual 결과에 없으면 '매뉴얼에 해당 내용이 없습니다'라고만 답한다.
15. 사용자가 말한 숫자를 나누거나 비율로 계산해서 수량을 만들지 않는다. 여러 품목을 비율로 요청하면 품목별 수량을 숫자로 다시 말해 달라고 한다.
16. 단위가 '개', '박스 몇 개'처럼 애매하면 상자인지 kg인지 먼저 묻는다. 임의로 kg으로 바꾸지 않는다.
17. 너에게는 취소·삭제·롤백 기능이 없다. 취소를 요청받으면 할 수 없다고 말하고, 반대 거래(정정 입고·정정 출고)를 안내한다."""


def _tool(name, description, properties=None, required=None):
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": properties or {}, "required": required or []}}}


TOOLS = [
    _tool("get_low_stock_products", "현재 재고가 안전재고보다 적은(부족한) 상품 목록을 조회한다."),
    _tool("get_product_stock", "특정 상품의 현재 재고(상자), 안전재고, 위치, 팔레트 수를 조회한다.",
          {"keyword": {"type": "string", "description": "상품 코드 또는 품목·산지 키워드 (예: MK-EHM, 에히메 귤, 青森りんご)"}},
          ["keyword"]),
    _tool("get_inbound_status", "해당 날짜의 입고 예정과 실적, 최근 7일 미입고·부분입고 건을 조회한다.",
          {"date": {"type": "string", "description": "YYYY-MM-DD. 생략하면 오늘"}}),
    _tool("request_stock_change",
          "입고 또는 출고 승인 요청을 만든다. 입고는 입고 예정 대비 검증 결과(warnings, exception)를 함께 돌려준다. 실제 반영은 화면에서 승인해야 일어난다.",
          {"keyword": {"type": "string", "description": "상품 코드 또는 품목·산지 키워드"},
           "change_type": {"type": "string", "enum": ["IN", "OUT"], "description": "IN = 입고, OUT = 출고"},
           "quantity": {"type": "integer", "description": "사용자가 말한 수량 그대로"},
           "unit": {"type": "string", "enum": ["box", "kg"], "description": "상자면 box, 킬로그램이면 kg"},
           "memo": {"type": "string", "description": "사유나 메모 (선택)"}},
          ["keyword", "change_type", "quantity", "unit"]),
    _tool("get_warehouse_usage", "냉장 창고 팔레트 사용량과 사용률(%)을 품목별로 조회한다."),
    _tool("get_recent_transactions",
          "최근 N일 입출고 이력과 합계를 조회한다. '사과 출고 내역'처럼 상품·구분이 있으면 반드시 product, type 으로 거른다.",
          {"days": {"type": "integer", "description": "1~31, 기본 7"},
           "product": {"type": "string", "description": "상품 필터. 품목(사과/귤/양파), 산지(아오모리), 상품 코드(AP-AOM). 전체면 생략"},
           "type": {"type": "string", "enum": ["IN", "OUT"], "description": "입고만 IN, 출고만 OUT. 둘 다면 생략"}}),
    _tool("get_transactions_between",
          "특정 날짜나 시간대의 입출고 이력을 조회한다. '11월 14일 오후 2~3시에 뭐 했어', '어제 입고 내역' 같은 질문에 쓴다. "
          "연도를 말하지 않으면 올해다. 오후 2시 = 14:00.",
          {"from": {"type": "string", "description": "시작. YYYY-MM-DD 또는 YYYY-MM-DDTHH:MM (예: 2026-11-14T14:00)"},
           "to": {"type": "string", "description": "끝(미포함). 생략하면 시작 날짜 하루 끝까지 (예: 2026-11-14T15:00)"},
           "product": {"type": "string", "description": "상품 필터. 품목(사과/귤/양파), 산지(아오모리), 상품 코드(AP-AOM). 전체면 생략"},
           "type": {"type": "string", "enum": ["IN", "OUT"], "description": "입고만 IN, 출고만 OUT. 둘 다면 생략"}},
          ["from"]),
    _tool("get_market_forecast",
          "랜덤포레스트 모델로 도쿄 중앙도매시장의 앞으로 며칠간 품목별 입하량(톤)과 우리 창고 몫(0.5%)을 예측한다.",
          {"item": {"type": "string", "enum": ["귤", "사과", "양파"]},
           "days": {"type": "integer", "description": "영업일 수 (1~10, 기본 6)"}},
          ["item"]),
    _tool("search_manual", "사내 청과 물류 업무 매뉴얼을 벡터 검색한다. 절차, 규정, 기준 질문에 사용한다.",
          {"query": {"type": "string", "description": "검색할 업무 질문"}}, ["query"]),
]


def _quantity(value):
    if isinstance(value, (int, float)):
        return int(value)
    digits = re.sub(r"[^0-9]", "", str(value or ""))
    return int(digits) if digits and len(digits) <= 9 else 0


TABLE_NOTE = ("이 결과는 화면에 표로 따로 보여집니다. 답변에 목록을 다시 쓰지 말고, "
              "summary 의 건수·합계와 filter_products·filter_type 범위를 그대로 밝혀 2~3줄로 요약하세요. "
              "summary 에 없는 숫자는 쓰지 마세요.")


def _add_table(turn, title, columns, rows):
    if rows:
        turn["tables"].append({"title": title, "columns": columns, "rows": rows[:100]})


def _filter_label(args):
    parts = []
    if args.get("product"):
        parts.append(str(args["product"]))
    t = str(args.get("type", "") or "").upper()
    if t in ("IN", "OUT"):
        parts.append("입고" if t == "IN" else "출고")
    return f" ({' · '.join(parts)})" if parts else ""


def _tx_table(turn, res, title):
    items = res.get("items") or []
    _add_table(turn, title, ["시각", "상품", "구분", "상자", "재고 변화", "처리자"],
               [[i.get("time", ""), i.get("product", ""), i.get("type", ""), i.get("boxes", ""),
                 f"{i.get('before', '')} → {i.get('after', '')}", i.get("actor", "")] for i in items])
    if items:
        res["display_note"] = TABLE_NOTE
    return res


def execute_tool(name, args, role, actor, turn):
    try:
        if name == "get_low_stock_products":
            return erp_get("/internal/products/low")
        if name == "get_product_stock":
            r = resolve_product(str(args.get("keyword", "")))
            if r.get("status") == "FOUND":
                return {"found": True, "matched_by": r.get("matched_by"), "product": r["product"]}
            if r.get("status") == "CANDIDATES":
                return {"found": False, "candidates": r["candidates"],
                        "instruction": "상품이 하나로 확정되지 않았습니다. 후보를 번호로 보여주고 어떤 산지인지 물어보세요."}
            return {"found": False, "message": f"일치하는 상품이 없습니다: {args.get('keyword')}"}
        if name == "get_inbound_status":
            res = erp_get("/internal/inbound", {"date": str(args.get("date", "") or "")})
            plans = res.get("plans") or []
            _add_table(turn, f"{res.get('date', '')} 입고 예정", ["상품", "예정", "입고", "잔량", "상태"],
                       [[p["product"], p["planned"], p["received"], p["remaining"], p["status"]] for p in plans])
            _add_table(turn, "최근 7일 미입고 · 부분입고", ["예정일", "상품", "예정", "입고", "상태"],
                       [[p["date"], p["product"], p["planned"], p["received"], p["status"]]
                        for p in (res.get("overdue_last_7_days") or [])])
            if plans or res.get("overdue_last_7_days"):
                res["display_note"] = TABLE_NOTE
            return res
        if name == "request_stock_change":
            if len(turn["pendings"]) >= MAX_PENDING_PER_TURN:
                return {"error": f"한 번에 만들 수 있는 승인 요청은 최대 {MAX_PENDING_PER_TURN}건입니다."}
            r = resolve_product(str(args.get("keyword", "")))
            if r.get("status") != "FOUND":
                return {"status": "NEED_CONFIRM", "candidates": r.get("candidates", []),
                        "instruction": "승인 요청을 만들지 않았습니다. 후보를 번호로 보여주거나 품목·산지를 다시 물어보세요."}
            code = r["product"]["code"]
            qty = _quantity(args.get("quantity"))
            if qty not in turn["stated_numbers"]:
                # 사용자가 말하지 않은 수량(LLM이 나눗셈·추정으로 만든 숫자)으로는 요청을 만들지 않는다
                return {"status": "QTY_NOT_STATED",
                        "reply_to_user": f"요청하신 문장에 {qty}이라는 수량이 없어서 승인 요청을 만들지 않았습니다. "
                                         "상품마다 수량을 숫자로 직접 말씀해 주세요. 예) 아오모리 사과 10상자 입고해줘"}
            change_type = str(args.get("change_type", "")).upper()
            key = f"{code}:{change_type}:{qty}:{args.get('unit', 'box')}"
            if key in turn["keys"]:
                return {"status": "ALREADY_REQUESTED", "instruction": "같은 요청이 이미 만들어졌습니다. 중복으로 만들지 마세요."}
            res = erp_post("/internal/stock-requests", {
                "productCode": code, "changeType": change_type, "quantity": qty,
                "unit": str(args.get("unit", "box")), "memo": str(args.get("memo", "") or ""), "actor": actor})
            card = res.pop("card", None)
            if card:
                turn["pendings"].append(card)
                turn["keys"].add(key)
            res["matched_by"] = r.get("matched_by")
            return res
        if name == "get_warehouse_usage":
            return erp_get("/internal/warehouse")
        if name == "get_transactions_between":
            res = erp_get("/internal/transactions/range",
                          {"from": str(args.get("from", "") or ""), "to": str(args.get("to", "") or ""),
                           "product": str(args.get("product", "") or ""), "type": str(args.get("type", "") or "")})
            title = (f"입출고 이력 {str(res.get('from', '')).replace('T', ' ')} ~ {str(res.get('to', '')).replace('T', ' ')}"
                     + _filter_label(args))
            return _tx_table(turn, res, title)
        if name == "get_recent_transactions":
            days = _quantity(args.get("days", 7)) or 7
            res = erp_get("/internal/transactions", {"days": days, "product": str(args.get("product", "") or ""),
                                                      "type": str(args.get("type", "") or "")})
            return _tx_table(turn, res, f"최근 {res.get('days', days)}일 입출고 이력" + _filter_label(args))
        if name == "get_market_forecast":
            item = str(args.get("item", "귤"))
            if item not in forecast.ITEMS:
                return {"error": "item은 귤, 사과, 양파 중 하나입니다."}
            days = max(1, min(10, _quantity(args.get("days", 6)) or 6))
            return {"item": item, "model": "RandomForest (mikang_정리.ipynb 특징 10개)",
                    "forecast": market_forecast(item, days)}
        if name == "search_manual":
            hits = search_manuals(str(args.get("query", "")), role, 3)
            for h in hits:
                if h["title"] not in turn["sources"]:
                    turn["sources"].append(h["title"])
            if not hits:
                return {"results": [], "message": "현재 권한으로 볼 수 있는 매뉴얼에서 관련 내용을 찾지 못했습니다."}
            return {"results": hits}
        return {"error": f"알 수 없는 도구: {name}"}
    except requests.HTTPError as e:
        return {"error": f"ERP 호출 실패: {e.response.status_code} {e.response.text[:200]}"}
    except Exception as e:
        return {"error": str(e)}


def guard_reply(reply, turn):
    """LLM이 지어낸 '반영 완료'·'승인 요청 생성' 문장을 실제 결과와 맞춰서 걸러낸다"""
    lines = [ln for ln in reply.splitlines()
             if not ln.strip().startswith("[시스템]") and "반영 완료" not in ln]
    if not turn["sources"]:
        # 매뉴얼을 실제로 검색하지 않았는데 '참고: 매뉴얼 제12장' 같은 근거를 붙이면 지운다
        fake = [ln for ln in lines if ln.strip().startswith("참고") or re.search(r"제\s*\d+\s*장|\d+\s*절", ln)]
        if fake:
            lines = [ln for ln in lines if ln not in fake]
            lines.append("※ 사내 매뉴얼에서 근거를 찾지 못한 답변입니다. 정확한 절차는 관리자에게 확인하세요.")
    text = "\n".join(lines).strip()
    # 취소·롤백 도구는 없다. "취소했습니다" 같은 말은 전부 지어낸 것이므로 사실대로 바꾼다
    if re.search(r"취소(했|하였|되었|됐|처리|완료)|되돌(렸|려\s*놓)|롤백(했|되었|됐|완료)|원상\s*복구(했|되었|됐)", text):
        return ("⚠ 채팅으로는 이미 반영된 입출고를 취소할 수 없고, 방금 아무것도 취소되지 않았습니다. "
                "ERP는 이력을 지우지 않고 반대 거래로 정정합니다. 예) 출고를 잘못했다면 같은 수량을 '정정 입고'로 요청하세요 "
                "(예정 외 입고라 관리자 승인 + 사유코드 필요). 아직 승인 전인 요청은 카드의 [거절]을 누르면 됩니다.")
    claims_request = re.search(r"승인\s*요청(을|이)?\s*(만들|생성|등록)", text)
    if claims_request and not turn["pendings"]:
        return ("⚠ 이번에는 실제로 만들어진 승인 요청이 없습니다. "
                "상품(산지)과 수량을 넣어서 다시 요청해 주세요. 예) 에히메 귤 10상자 입고해줘")
    if len(lines) != len(reply.splitlines()):
        text += "\n\n※ 재고 반영은 승인 카드의 [승인] 버튼을 눌러야만 일어납니다."
    return text or "다시 한 번 말씀해 주세요."


def agent_chat(message, history, role, actor):
    turn = {"pendings": [], "sources": [], "tool_log": [], "keys": set(), "stated_numbers": set(), "tables": []}
    last_user = next((str(h.get("content") or "") for h in reversed(history or []) if h.get("role") == "user"), "")
    for text in (message, last_user):
        turn["stated_numbers"].update(int(n) for n in re.findall(r"\d+", text.replace(",", "")) if len(n) <= 9)
    messages = [{"role": "system", "content": system_prompt(role, actor)}]
    clean = []
    for h in (history or []):
        content = str(h.get("content") or "")
        # 화면에서 승인한 결과 문구는 LLM에게 넘기지 않는다 (흉내 내서 지어내는 것 방지)
        if h.get("role") not in ("user", "assistant") or not content or content.startswith("[시스템]"):
            continue
        clean.append({"role": h["role"], "content": content})
    messages.extend(clean[-MAX_HISTORY:])
    messages.append({"role": "user", "content": message})

    reply = None
    nudged = False
    for _ in range(MAX_STEPS):
        msg = ollama_chat(messages, TOOLS)
        content = re.sub(r"(?s)<think>.*?</think>", "", msg.get("content") or "").strip()
        calls = msg.get("tool_calls") or []
        if not calls and not turn["tool_log"] and not nudged and DATA_QUESTION.search(message):
            # 도구 없이 기억으로 답하려고 하면 버리고 한 번 더 시킨다
            nudged = True
            messages.append({"role": "user", "content":
                             "(확인) 이 질문은 반드시 도구를 호출해서 지금 DB 값으로 답해야 합니다. "
                             "이전 대화의 숫자나 결과를 그대로 쓰지 말고 도구를 먼저 호출하세요."})
            continue
        assistant = {"role": "assistant", "content": content}
        if calls:
            assistant["tool_calls"] = calls
        messages.append(assistant)
        if not calls:
            reply = content
            break
        for call in calls:
            fn = call.get("function", {})
            name = fn.get("name", "")
            args = fn.get("arguments") or {}
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except json.JSONDecodeError:
                    args = {}
            result = execute_tool(name, args, role, actor, turn)
            turn["tool_log"].append(f"{name} {json.dumps(args, ensure_ascii=False)}")
            messages.append({"role": "tool", "tool_name": name,
                             "content": json.dumps(result, ensure_ascii=False, default=str)})

    if not reply:
        reply = ("승인 요청을 만들었습니다. 아래 카드에서 내용을 확인하고 [승인]을 눌러 주세요."
                 if turn["pendings"] else
                 "요청을 끝까지 처리하지 못했습니다. 상품(산지)과 수량을 조금 더 구체적으로 말씀해 주세요.")
    reply = guard_reply(reply, turn)
    return {"reply": reply, "pendings": turn["pendings"], "sources": turn["sources"], "toolLog": turn["tool_log"],
            "tables": turn["tables"]}


# ---------------------------------------------------------------- FastAPI

def rebuild_index():
    with _index_lock:
        result = {"manual_chunks": 0, "products": 0}
        try:
            result["manual_chunks"] = index_manuals()
        except Exception as e:
            state["last_index_error"] = f"manuals: {e}"
        try:
            result["products"] = index_products()
        except Exception as e:
            state["last_index_error"] = f"products: {e}"
        return result


@asynccontextmanager
async def lifespan(app):
    threading.Thread(target=reindex_loop, daemon=True).start()
    if FORECAST_PUSH_HOUR >= 0:
        threading.Thread(target=forecast_loop, daemon=True).start()
    yield


def push_forecast_plans():
    """내일부터 FORECAST_DAYS 일치 입고 예정을 예측값(RF_MODEL)으로 저장. 오늘 예정·이미 입고된 실적은 건드리지 않음"""
    try:
        products = erp_get("/internal/products")
        start = dt.date.today() + dt.timedelta(days=1)
        end = start + dt.timedelta(days=FORECAST_DAYS - 1)
        plans, _ = forecast.build_plans(products, start, end)
        plans = [p for p in plans if str(p.get("date", "")) >= str(start)]
        saved = erp_post("/internal/inbound/plans", plans)["saved"] if plans else 0
        state["last_forecast_push"] = f"{dt.datetime.now():%Y-%m-%d %H:%M} · {start} ~ {end} · {saved}건 저장"
    except Exception as e:
        state["last_forecast_push"] = f"{dt.datetime.now():%Y-%m-%d %H:%M} · 실패: {e}"


def forecast_loop():
    """켜질 때 한 번, 그 뒤 매일 FORECAST_PUSH_HOUR 시에 입고 예정 갱신"""
    push_forecast_plans()
    while True:
        now = dt.datetime.now()
        nxt = now.replace(hour=FORECAST_PUSH_HOUR, minute=0, second=0, microsecond=0)
        if nxt <= now:
            nxt += dt.timedelta(days=1)
        threading.Event().wait((nxt - now).total_seconds())
        forecast_cache.clear()
        push_forecast_plans()


def reindex_loop():
    """시작할 때 한 번, 그 뒤 REINDEX_HOURS 마다 매뉴얼·상품 벡터 인덱스를 다시 만든다"""
    while True:
        rebuild_index()
        state["last_indexed_at"] = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
        if REINDEX_HOURS <= 0:
            return
        threading.Event().wait(REINDEX_HOURS * 3600)


app = FastAPI(title="ERP AI Service", lifespan=lifespan)


class ChatRequest(BaseModel):
    message: str
    role: str = "STAFF"
    actor: str = "담당자"
    history: list[dict] = []


@app.get("/health")
def health():
    return {"ollama": ollama_ok(), "qdrant": True, "model": CHAT_MODEL,
            "manuals_ready": state["manuals_ready"], "products_ready": state["products_ready"],
            "last_index_error": state["last_index_error"], "forecast_items": forecast.available_items(),
            "last_indexed_at": state.get("last_indexed_at"), "reindex_hours": REINDEX_HOURS,
            "last_forecast_push": state.get("last_forecast_push"), "forecast_push_hour": FORECAST_PUSH_HOUR}


@app.post("/agent/chat")
def chat(req: ChatRequest):
    role = "MANAGER" if req.role == "MANAGER" else "STAFF"
    try:
        return agent_chat(req.message, req.history, role, req.actor)
    except requests.RequestException as e:
        raise HTTPException(status_code=503, detail=f"Ollama 호출 실패: {e}")


@app.post("/index/rebuild")
def index_rebuild():
    return rebuild_index()


@app.post("/rag/search")
def rag_search(query: str, role: str = "STAFF"):
    return search_manuals(query, "MANAGER" if role == "MANAGER" else "STAFF", 3)


@app.get("/forecast/{item}")
def get_forecast(item: str, days: int = 6):
    if item not in forecast.ITEMS:
        raise HTTPException(status_code=404, detail="item은 귤, 사과, 양파 중 하나입니다.")
    return market_forecast(item, max(1, min(days, 14)))


@app.post("/forecast/run")
def forecast_run(days: int = 14, push: bool = False):
    """오늘부터 days일 동안의 입고 예정을 예측으로 만들고, push=true 면 ERP에 저장"""
    products = erp_get("/internal/products")
    start = dt.date.today()
    end = start + dt.timedelta(days=days)
    plans, detail = forecast.build_plans(products, start, end)
    saved = erp_post("/internal/inbound/plans", plans)["saved"] if push and plans else 0
    return {"from": str(start), "to": str(end), "plans": len(plans), "saved": saved,
            "preview": detail.head(40).to_dict(orient="records")}
