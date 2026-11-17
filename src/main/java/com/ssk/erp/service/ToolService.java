package com.ssk.erp.service;

import com.ssk.erp.dto.InboundPlanDTO;
import com.ssk.erp.dto.PendingActionDTO;
import com.ssk.erp.dto.ProductDTO;
import com.ssk.erp.dto.StockTxDTO;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Python AI 서비스가 부르는 "도구"의 실제 구현.
 * LLM은 Python에 있지만, 데이터 조회·검증·승인 요청 생성은 전부 여기(Spring)에서 한다.
 * 결과는 LLM에 그대로 넘길 수 있는 Map(JSON) 형태로 돌려준다.
 */
@Service
public class ToolService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public record PendingCard(String actionId, String productCode, String productName, String unit,
                              String type, int qty, int kgTotal, int before, int after, String expiresAt,
                              boolean exception, List<String> warnings) {
    }

    private final InventoryService inventory;

    public ToolService(InventoryService inventory) {
        this.inventory = inventory;
    }

    /** 벡터 인덱스용 상품 마스터 */
    public List<Map<String, Object>> productsForIndex() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ProductDTO p : inventory.listProducts()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("category", p.getCategory());
            m.put("origin", p.getOrigin());
            m.put("kg_per_box", p.getKgPerBox());
            m.put("description", p.getDescription());
            list.add(m);
        }
        return list;
    }

    public Map<String, Object> lowStock() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductDTO p : inventory.lowStock()) {
            Map<String, Object> m = productMap(p);
            m.put("shortage", p.getSafetyQty() - p.getStockQty());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", items.size());
        out.put("items", items);
        return out;
    }

    /** 코드 정확 일치 -> 이름 부분 일치. status: FOUND / CANDIDATES / NONE */
    public Map<String, Object> resolve(String keyword) {
        InventoryService.ResolveResult r = inventory.resolveProduct(keyword);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.resolved()) {
            out.put("status", "FOUND");
            out.put("matched_by", r.matchedBy());
            out.put("product", productMap(r.product()));
        } else if (!r.candidates().isEmpty()) {
            out.put("status", "CANDIDATES");
            out.put("candidates", candidateList(r.candidates()));
        } else {
            out.put("status", "NONE");
        }
        return out;
    }

    public Map<String, Object> inboundStatus(String dateText) {
        LocalDate today = LocalDate.now();
        LocalDate date = today;
        if (dateText != null && !dateText.isBlank()) {
            try {
                date = LocalDate.parse(dateText.trim());
            } catch (DateTimeParseException e) {
                return Map.of("error", "날짜 형식은 YYYY-MM-DD 입니다: " + dateText);
            }
        }

        List<Map<String, Object>> plans = new ArrayList<>();
        int plannedTotal = 0;
        int receivedTotal = 0;
        for (InboundPlanDTO p : inventory.plansBetween(date, date)) {
            plans.add(planMap(p, today));
            plannedTotal += p.getPlannedQty();
            receivedTotal += p.getReceivedQty();
        }

        List<Map<String, Object>> overdue = new ArrayList<>();
        for (InboundPlanDTO p : inventory.plansBetween(today.minusDays(7), today.minusDays(1))) {
            String status = p.statusOn(today);
            if ("MISSED".equals(status) || "PARTIAL".equals(status)) {
                overdue.add(planMap(p, today));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("market_closed_or_no_plan", plans.isEmpty());
        out.put("planned_total_boxes", plannedTotal);
        out.put("received_total_boxes", receivedTotal);
        out.put("plans", plans);
        out.put("overdue_last_7_days", overdue);
        return out;
    }

    /**
     * 입출고 승인 요청 생성. productCode는 Python에서 이미 확정한 정확한 코드여야 한다.
     * 응답의 card는 화면 승인 카드용, 나머지는 LLM에게 전달할 내용.
     */
    public Map<String, Object> stockRequest(String productCode, String changeType, int quantity, String unit,
                                            String memo, String actor) {
        String type = changeType == null ? "" : changeType.trim().toUpperCase();
        if ("입고".equals(type)) {
            type = "IN";
        } else if ("출고".equals(type)) {
            type = "OUT";
        }
        if (!"IN".equals(type) && !"OUT".equals(type)) {
            return Map.of("status", "ERROR", "reason", "change_type은 IN 또는 OUT이어야 합니다.");
        }
        if (quantity <= 0) {
            return Map.of("status", "ERROR", "reason", "수량을 알 수 없습니다. 사용자에게 수량을 다시 확인하세요.");
        }

        ProductDTO p = inventory.findByCode(productCode);
        if (p == null) {
            return Map.of("status", "ERROR", "reason", "상품 코드가 없습니다: " + productCode);
        }

        int boxes = quantity;
        String u = unit == null ? "box" : unit.trim().toLowerCase();
        if ("kg".equals(u)) {
            if (quantity % p.getKgPerBox() != 0) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "UNIT_MISMATCH");
                out.put("reply_to_user", p.getName() + "은 1상자 " + p.getKgPerBox() + "kg입니다. " + quantity
                        + "kg은 상자 단위로 나누어 떨어지지 않습니다. 상자 수로 다시 말씀해 주세요.");
                return out;
            }
            boxes = quantity / p.getKgPerBox();
        }
        if (boxes > InventoryService.MAX_QTY) {
            return Map.of("status", "ERROR", "reason", "수량이 너무 큽니다 (최대 " + InventoryService.MAX_QTY + "상자).");
        }

        InventoryService.PendingResult result;
        try {
            result = inventory.createPending(p, type, boxes, actor, memo == null ? "" : memo.trim());
        } catch (IllegalStateException | IllegalArgumentException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "REJECTED");
            out.put("reason", e.getMessage());
            out.put("reply_to_user", "승인 요청을 만들지 않았습니다. 이유: " + e.getMessage());
            return out;
        }

        PendingActionDTO action = result.action();
        InventoryService.InboundCheck check = result.check();
        List<String> warnings = check == null ? List.of() : check.messages();
        boolean exception = action.isExceptionRequired();

        PendingCard card = new PendingCard(action.getId(), p.getCode(), p.getName(), p.getUnit(), type,
                action.getQty(), action.getQty() * p.getKgPerBox(), action.getBeforeQty(), action.getAfterQty(),
                action.getExpiresAt().format(HHMM), exception, warnings);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "APPROVAL_REQUIRED");
        out.put("product", p.getCode() + " " + p.getName());
        out.put("type", "IN".equals(type) ? "입고" : "출고");
        out.put("boxes", action.getQty());
        out.put("kg_total", action.getQty() * p.getKgPerBox());
        out.put("before", action.getBeforeQty());
        out.put("after", action.getAfterQty());
        if (check != null) {
            out.put("plan_planned", check.planned());
            out.put("plan_received", check.received());
            out.put("plan_remaining", check.remaining());
            out.put("check_level", check.level());
            out.put("pallets_after", check.palletsAfter() + "/" + check.capacity());
        }
        out.put("exception", exception);
        out.put("warnings", warnings);
        out.put("reply_to_user", exception
                ? p.getName() + " " + action.getQty() + "상자 입고 요청은 입고 예정과 맞지 않는 예외 입고입니다. "
                + String.join(" / ", warnings)
                + " → 관리자(MANAGER) 권한과 사유코드가 있어야 승인할 수 있습니다. 수량이 맞는지 다시 확인해 주세요."
                : p.getName() + " " + action.getQty() + "상자 " + ("IN".equals(type) ? "입고" : "출고")
                + " 승인 요청을 만들었습니다 (재고 "
                + action.getBeforeQty() + " → " + action.getAfterQty() + "상자). "
                + "아직 DB에는 반영되지 않았고, 아래 카드의 [승인]을 눌러야 반영됩니다.");
        out.put("card", card);
        return out;
    }

    public Map<String, Object> warehouseUsage() {
        InventoryService.WarehouseUsage u = inventory.usage();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("used_pallets", u.usedPallets());
        out.put("capacity_pallets", u.capacity());
        out.put("percent", u.percent());
        out.put("operating_cap_percent", 80);
        out.put("by_category", u.byCategory());
        return out;
    }

    public Map<String, Object> recentTransactions(int days, String product, String type) {
        int d = Math.max(1, Math.min(31, days));
        Map<String, Object> out = filtered(inventory.txSince(d), product, type, 100);
        out.put("days", d);
        return out;
    }

    /**
     * 상품(키워드)·구분(IN/OUT)으로 DB 결과를 걸러서 합계를 낸다.
     * LLM이 전체 합계를 특정 상품 합계처럼 말하지 않도록, 거른 결과의 합계만 돌려준다.
     */
    private Map<String, Object> filtered(List<StockTxDTO> list, String product, String type, int maxItems) {
        List<StockTxDTO> rows = list;
        List<String> productNames = List.of();
        if (product != null && !product.isBlank()) {
            List<ProductDTO> matched = inventory.matchProducts(product);
            if (matched.isEmpty()) {
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("status", "NO_PRODUCT");
                none.put("summary", Map.of());
                none.put("items", List.of());
                none.put("reply_to_user", "'" + product + "'에 해당하는 상품이 등록돼 있지 않습니다.");
                return none;
            }
            Set<Long> ids = matched.stream().map(ProductDTO::getId).collect(Collectors.toSet());
            rows = rows.stream().filter(t -> ids.contains(t.getProductId())).toList();
            productNames = matched.stream().map(p -> p.getCode() + " " + p.getName()).toList();
        }
        String tx = normalizeType(type);
        if (tx != null) {
            rows = rows.stream().filter(t -> tx.equals(t.getTxType())).toList();
        }
        Map<String, Object> out = summarize(rows, maxItems);
        out.put("filter_products", productNames.isEmpty() ? "전체 상품" : productNames);
        out.put("filter_type", tx == null ? "입고+출고" : ("IN".equals(tx) ? "입고만" : "출고만"));
        return out;
    }

    private String normalizeType(String type) {
        if (type == null) {
            return null;
        }
        String t = type.trim().toUpperCase();
        if (t.equals("IN") || t.equals("입고")) {
            return "IN";
        }
        if (t.equals("OUT") || t.equals("출고")) {
            return "OUT";
        }
        return null;
    }

    /**
     * 특정 날짜·시간대 입출고 이력 (예: 11월 14일 14:00~15:00).
     * from/to 는 "2026-11-14" 또는 "2026-11-14T14:00". to 가 날짜만이면 그날 끝까지.
     */
    public Map<String, Object> transactionsBetween(String from, String to, String product, String type) {
        LocalDateTime f = parseDateTime(from, false);
        LocalDateTime t = (to == null || to.isBlank()) ? f.toLocalDate().plusDays(1).atStartOfDay() : parseDateTime(to, true);
        if (!t.isAfter(f)) {
            throw new IllegalArgumentException("끝 시각이 시작 시각보다 늦어야 합니다.");
        }
        if (f.plusDays(31).isBefore(t)) {
            throw new IllegalArgumentException("한 번에 조회할 수 있는 기간은 최대 31일입니다.");
        }
        Map<String, Object> out = filtered(inventory.txBetween(f, t), product, type, 100);
        out.put("from", f.toString());
        out.put("to", t.toString());
        if (((List<?>) out.get("items")).isEmpty() && !out.containsKey("reply_to_user")) {
            out.put("reply_to_user", f.format(TIME) + " ~ " + t.format(TIME) + " 사이에는 입출고 이력이 없습니다.");
        }
        return out;
    }

    private LocalDateTime parseDateTime(String value, boolean endOfDayIfDateOnly) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("조회 시작 날짜가 필요합니다. 예) 2026-11-14T14:00");
        }
        String v = value.trim().replace(' ', 'T');
        try {
            if (v.length() <= 10) {
                LocalDate d = LocalDate.parse(v);
                return endOfDayIfDateOnly ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
            }
            if (v.length() == 16) {
                return LocalDateTime.parse(v + ":00");
            }
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("날짜 형식은 2026-11-14 또는 2026-11-14T14:00 입니다: " + value);
        }
    }

    private Map<String, Object> summarize(List<StockTxDTO> list, int maxItems) {
        int inQty = 0;
        int outQty = 0;
        int inCount = 0;
        int outCount = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (StockTxDTO tx : list) {
            if ("IN".equals(tx.getTxType())) {
                inQty += tx.getQty();
                inCount++;
            } else {
                outQty += tx.getQty();
                outCount++;
            }
            if (items.size() < maxItems) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", tx.getCreatedAt().format(TIME));
                m.put("product", tx.getProductCode() + " " + tx.getProductName());
                m.put("type", "IN".equals(tx.getTxType()) ? "입고" : "출고");
                m.put("boxes", tx.getQty());
                m.put("before", tx.getBeforeQty());
                m.put("after", tx.getAfterQty());
                m.put("channel", tx.getChannel());
                m.put("actor", tx.getActor());
                if (tx.getReasonCode() != null) {
                    m.put("reason_code", tx.getReasonCode());
                }
                items.add(m);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("in_count", inCount);
        summary.put("in_boxes_total", inQty);
        summary.put("out_count", outCount);
        summary.put("out_boxes_total", outQty);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("total_count", list.size());
        out.put("items", items);
        return out;
    }

    private Map<String, Object> productMap(ProductDTO p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.getCode());
        m.put("name", p.getName());
        m.put("origin", p.getOrigin());
        m.put("stock_boxes", p.getStockQty());
        m.put("safety_boxes", p.getSafetyQty());
        m.put("kg_per_box", p.getKgPerBox());
        m.put("pallets", p.getPallets());
        m.put("location", p.getLocation());
        m.put("low", p.isLow());
        return m;
    }

    private Map<String, Object> planMap(InboundPlanDTO p, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", p.getPlanDate().toString());
        m.put("product", p.getProductCode() + " " + p.getProductName());
        m.put("planned", p.getPlannedQty());
        m.put("received", p.getReceivedQty());
        m.put("remaining", p.getRemaining());
        m.put("status", p.statusLabelOn(today));
        m.put("source", p.getSource());
        return m;
    }

    private List<Map<String, Object>> candidateList(List<ProductDTO> products) {
        List<Map<String, Object>> list = new ArrayList<>();
        int no = 1;
        for (ProductDTO p : products) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("no", no++);
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("stock_boxes", p.getStockQty());
            list.add(m);
        }
        return list;
    }
}
