"""
도쿄 중앙도매시장 입하량 예측 -> ERP 입고 예정 만들기
mikang_정리.ipynb 의 특징(make_features)과 랜덤포레스트 모델을 그대로 재사용한다.

필요한 CSV (DATA_DIR 폴더, 파일 이름에 연도·번호가 달라도 찾음):
  일별   : 東京_みかん_日別_*.csv / 東京_りんご_日別_*.csv / 東京_たまねぎ_日別_*.csv
           열: date, weekday, market_open, qty_kg, price_yen_per_kg
  산지별 : 東京_みかん_産地別_*.csv / 東京_りんご_産地別_*.csv / 東京_たまねぎ_産地別_*.csv
           열: month, origin, qty_kg
"""
import glob
import os

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error

DATA_DIR = os.getenv("DATA_DIR", ".")
SHARE = 0.005  # 우리 창고 점유율: 도쿄 시장의 0.5%

ITEMS = {
    "귤": {"jp": "みかん", "en": "Mikan", "months": [9, 10, 11, 12, 1, 2, 3]},
    "사과": {"jp": "りんご", "en": "Apple", "months": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]},
    "양파": {"jp": "たまねぎ", "en": "Onion", "months": [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]},
}

BASE_FEATURES = ["weekday_num", "month", "prev_qty", "after_closed",
                 "mean_5", "last_week_qty", "price_mean_5"]
EXTRA_FEATURES = ["gap_days", "days_to_yearend", "last_year_qty"]
FEATURES = BASE_FEATURES + EXTRA_FEATURES

CLOSED_WEEKDAYS = (2, 6)  # 수요일(2), 일요일(6) 휴장
HOLIDAYS = {
    "2026-11-03", "2026-11-23",
    "2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02", "2027-01-03", "2027-01-04",
}


def find_file(item, kind):
    jp = ITEMS[item]["jp"]
    found = sorted(glob.glob(os.path.join(DATA_DIR, f"東京_{jp}_{kind}_*.csv")), key=os.path.getmtime)
    return found[-1] if found else None


def available_items():
    return [item for item in ITEMS if find_file(item, "日別")]


def load_daily(item):
    file = find_file(item, "日別")
    if file is None:
        raise FileNotFoundError(f"{item} 일별 파일이 없습니다: 東京_{ITEMS[item]['jp']}_日別_*.csv")
    df = pd.read_csv(file, parse_dates=["date"], encoding="utf-8-sig")
    df["market_open"] = df["market_open"].astype(str).str.lower().isin(["true", "1"])
    return df.sort_values("date").reset_index(drop=True)


def is_open(d):
    d = pd.Timestamp(d)
    return d.weekday() not in CLOSED_WEEKDAYS and d.strftime("%Y-%m-%d") not in HOLIDAYS


def make_features(df):
    """노트북 make_features 와 같은 특징 (파일 대신 DataFrame을 받음)"""
    qty_by_date = df.set_index("date")["qty_kg"]
    op = df[df["market_open"] & df["qty_kg"].notna()].copy().reset_index(drop=True)

    op["weekday_num"] = op["date"].dt.weekday
    op["month"] = op["date"].dt.month
    op["prev_qty"] = op["qty_kg"].shift(1)
    op["gap_days"] = (op["date"] - op["date"].shift(1)).dt.days
    op["after_closed"] = (op["gap_days"] > 1).astype(int)
    op["mean_5"] = op["qty_kg"].shift(1).rolling(5).mean()
    op["last_week_qty"] = (op["date"] - pd.Timedelta(days=7)).map(qty_by_date)
    op["last_week_qty"] = op["last_week_qty"].fillna(op["mean_5"])
    op["price_mean_5"] = op["price_yen_per_kg"].shift(1).rolling(5, min_periods=1).mean()

    year_end = pd.to_datetime(op["date"].dt.year.astype(str) + "-12-31")
    op["days_to_yearend"] = (year_end - op["date"]).dt.days.where(op["month"] >= 9, 999).clip(upper=120)
    daily = qty_by_date.dropna()

    def last_year_mean(d):
        window = daily[(daily.index >= d - pd.Timedelta(days=367)) & (daily.index <= d - pd.Timedelta(days=361))]
        return window.mean() if len(window) else np.nan

    op["last_year_qty"] = op["date"].map(last_year_mean)
    op["season"] = op["date"].dt.year.where(op["month"] >= 9, op["date"].dt.year - 1)
    return op


def new_model():
    return RandomForestRegressor(n_estimators=300, min_samples_leaf=3, random_state=42, n_jobs=-1)


def train_model(item, df=None):
    if df is None:
        df = load_daily(item)
    data = make_features(df).dropna(subset=FEATURES)
    train = data[data["month"].isin(ITEMS[item]["months"])]
    model = new_model()
    model.fit(train[FEATURES], train["qty_kg"])
    return model, data


def backtest(item, last_n=60):
    """최근 last_n 영업일을 그 이전 데이터만으로 학습해서 맞혀보기 (하루 앞 예측)"""
    df = load_daily(item)
    data = make_features(df).dropna(subset=FEATURES)
    data = data[data["month"].isin(ITEMS[item]["months"])].reset_index(drop=True)
    data = data[data["qty_kg"] > 0].reset_index(drop=True)
    cut = len(data) - last_n
    train, test = data.iloc[:cut], data.iloc[cut:].copy()
    model = new_model()
    model.fit(train[FEATURES], train["qty_kg"])
    test["pred_kg"] = model.predict(test[FEATURES])
    mae = mean_absolute_error(test["qty_kg"], test["pred_kg"])
    base = mean_absolute_error(test["qty_kg"], test["last_week_qty"])
    summary = {
        "item": item,
        "days": int(len(test)),
        "mae_ton": round(float(mae) / 1000, 1),
        "wape_pct": round(float(mae / test["qty_kg"].mean()) * 100, 1),
        "baseline_last_week_wape_pct": round(float(base / test["qty_kg"].mean()) * 100, 1),
    }
    importance = pd.Series(model.feature_importances_, index=FEATURES).sort_values(ascending=False)
    return test[["date", "qty_kg", "pred_kg", "last_week_qty"]], summary, importance


def _features_for(target, hist):
    """목표 날짜의 특징값을 '그 전날까지 아는 정보'로만 만든다 (노트북 predict_next_day 와 같음)"""
    qty_by_date = hist.set_index("date")["qty_kg"]
    daily = qty_by_date.dropna()
    recent = hist.tail(5)
    mean_5 = recent["qty_kg"].mean()
    last_week = qty_by_date.get(target - pd.Timedelta(days=7), np.nan)
    gap = (target - hist["date"].iloc[-1]).days
    year_end = pd.Timestamp(f"{target.year}-12-31")
    window = daily[(daily.index >= target - pd.Timedelta(days=367)) & (daily.index <= target - pd.Timedelta(days=361))]
    return pd.DataFrame([{
        "weekday_num": target.weekday(),
        "month": target.month,
        "prev_qty": hist["qty_kg"].iloc[-1],
        "after_closed": int(gap > 1),
        "mean_5": mean_5,
        "last_week_qty": mean_5 if pd.isna(last_week) else last_week,
        "price_mean_5": recent["price_yen_per_kg"].mean(),
        "gap_days": gap,
        "days_to_yearend": min((year_end - target).days, 120) if target.month >= 9 else 999,
        "last_year_qty": window.mean() if len(window) else mean_5,
    }])[FEATURES]


def forecast(item, end_date):
    """마지막 데이터 다음 날부터 end_date 까지 영업일마다 하루씩 예측 (예측값을 다음 날 입력으로 이어 붙임)"""
    df = load_daily(item)
    model, _ = train_model(item, df)
    hist = df[df["market_open"] & df["qty_kg"].notna()][["date", "qty_kg", "price_yen_per_kg"]].copy()
    hist = hist.reset_index(drop=True)
    last_price = hist["price_yen_per_kg"].tail(5).mean()

    rows = []
    d = hist["date"].max() + pd.Timedelta(days=1)
    end_date = pd.Timestamp(end_date)
    while d <= end_date:
        if is_open(d) and d.month in ITEMS[item]["months"]:
            pred = float(max(model.predict(_features_for(d, hist))[0], 0.0))
            rows.append({"date": d, "pred_kg": pred})
            hist = pd.concat([hist, pd.DataFrame([{"date": d, "qty_kg": pred, "price_yen_per_kg": last_price}])],
                             ignore_index=True)
        d += pd.Timedelta(days=1)
    return pd.DataFrame(rows, columns=["date", "pred_kg"])


def origin_shares(item, target):
    """산지 비중: 작년 같은 달 산지별 비중 (없으면 가장 최근 달)"""
    file = find_file(item, "産地別")
    if file is None:
        return {}
    m = pd.read_csv(file, encoding="utf-8-sig")
    key = f"{target.year - 1}-{target.month:02d}"
    month_df = m[m["month"] == key]
    if month_df.empty:
        month_df = m[m["month"] == m["month"].max()]
    total = month_df["qty_kg"].sum()
    return {row.origin: row.qty_kg / total for row in month_df.itertuples()} if total else {}


def build_plans(products, start, end, min_boxes=3):
    """
    products: ERP 상품 마스터 [{code, category(귤/사과/양파), origin, kg_per_box}, ...]
    반환: ERP /internal/inbound/plans 에 바로 보낼 수 있는 목록 + 설명용 표
    """
    start, end = pd.Timestamp(start), pd.Timestamp(end)
    plans, detail = [], []
    for item in available_items():
        item_products = [p for p in products if p.get("category") == item]
        if not item_products:
            continue
        fc = forecast(item, end)
        fc = fc[(fc["date"] >= start) & (fc["date"] <= end)]
        for row in fc.itertuples():
            shares = origin_shares(item, row.date)
            for p in item_products:
                share = shares.get(p["origin"], 0.0)
                boxes = int(round(row.pred_kg * SHARE * share / p["kg_per_box"]))
                detail.append({"date": row.date.strftime("%Y-%m-%d"), "item": item, "code": p["code"],
                               "origin": p["origin"], "market_pred_ton": round(row.pred_kg / 1000, 1),
                               "origin_share": round(share, 3), "boxes": boxes})
                if boxes >= min_boxes:
                    plans.append({"date": row.date.strftime("%Y-%m-%d"), "productCode": p["code"],
                                  "plannedQty": boxes, "source": "RF_MODEL"})
    return plans, pd.DataFrame(detail)
