package com.ssk.erp.service;

import com.ssk.erp.dao.InboundPlanDAO;
import com.ssk.erp.dao.PendingActionDAO;
import com.ssk.erp.dao.ProductDAO;
import com.ssk.erp.dao.StockTxDAO;
import com.ssk.erp.dto.InboundPlanDTO;
import com.ssk.erp.dto.PendingActionDTO;
import com.ssk.erp.dto.PlanInput;
import com.ssk.erp.dto.ProductDTO;
import com.ssk.erp.dto.StockTxDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryService {

    public static final int MAX_QTY = 10000;
    private static final int PENDING_TTL_MINUTES = 10;

    /** 예외 입고 사유코드 */
    public static final Map<String, String> REASON_CODES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("OVER_DELIVERY", "거래처 초과 납품 (수령 합의)");
        m.put("EXTRA_ORDER", "추가 발주분");
        m.put("PLAN_FIX", "입고 예정 등록 오류");
        m.put("URGENT_NO_PLAN", "긴급 입고 (예정 미등록)");
        m.put("OTHER", "기타 (메모 필수)");
        REASON_CODES = Collections.unmodifiableMap(m);
    }

    public record ResolveResult(ProductDTO product, List<ProductDTO> candidates, String matchedBy) {
        public boolean resolved() {
            return product != null;
        }
    }

    /**
     * 입고 검증 결과
     * level: NORMAL(정상 범위) / SHORT(부분 입고) / OVER(예정 초과) / NO_PLAN(예정 없음)
     * blocked: 창고 용량 초과처럼 누구도 승인할 수 없는 경우
     * exceptionRequired: 관리자 권한 + 사유코드가 있어야만 승인 가능
     */
    public record InboundCheck(Long planId, int planned, int received, int remaining, String level,
                               boolean exceptionRequired, boolean blocked, List<String> messages,
                               int palletsAfter, int capacity) {
    }

    public record WarehouseUsage(int usedPallets, int capacity, int percent, Map<String, Integer> byCategory) {
    }

    public record PendingResult(PendingActionDTO action, InboundCheck check) {
    }

    private final ProductDAO productDAO;
    private final StockTxDAO stockTxDAO;
    private final PendingActionDAO pendingActionDAO;
    private final InboundPlanDAO planDAO;
    private final int capacityPallets;
    private final double operatingCap;
    private final double tolerance;

    public InventoryService(ProductDAO productDAO,
                            StockTxDAO stockTxDAO,
                            PendingActionDAO pendingActionDAO,
                            InboundPlanDAO planDAO,
                            @Value("${erp.warehouse.capacity-pallets:82}") int capacityPallets,
                            @Value("${erp.warehouse.operating-cap:0.8}") double operatingCap,
                            @Value("${erp.inbound.tolerance:0.10}") double tolerance) {
        this.productDAO = productDAO;
        this.stockTxDAO = stockTxDAO;
        this.pendingActionDAO = pendingActionDAO;
        this.planDAO = planDAO;
        this.capacityPallets = capacityPallets;
        this.operatingCap = operatingCap;
        this.tolerance = tolerance;
    }

    public List<ProductDTO> listProducts() {
        return productDAO.findAll();
    }

    public List<ProductDTO> lowStock() {
        return productDAO.findLow();
    }

    public ProductDTO findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return productDAO.findByCode(code.trim());
    }

    public List<StockTxDTO> recentTx() {
        return stockTxDAO.findRecent(30);
    }

    public List<StockTxDTO> txSince(int days) {
        return stockTxDAO.findSince(LocalDateTime.now().minusDays(days));
    }

    public List<StockTxDTO> txBetween(LocalDateTime from, LocalDateTime to) {
        return stockTxDAO.findBetween(from, to);
    }

    public List<InboundPlanDTO> plansBetween(LocalDate from, LocalDate to) {
        return planDAO.findBetween(from, to);
    }

    public WarehouseUsage usage() {
        int used = 0;
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (ProductDTO p : productDAO.findAll()) {
            int pallets = p.getPallets();
            used += pallets;
            byCategory.merge(p.getCategory(), pallets, Integer::sum);
        }
        int percent = capacityPallets == 0 ? 0 : (int) Math.round(used * 100.0 / capacityPallets);
        return new WarehouseUsage(used, capacityPallets, percent, byCategory);
    }

    /** Python 예측 서비스가 만든 입고 예정을 저장 (이미 있으면 예정 수량·출처만 갱신, 입고 실적은 유지) */
    @Transactional
    public int upsertPlans(List<PlanInput> inputs) {
        int count = 0;
        for (PlanInput in : inputs) {
            if (in == null || in.plannedQty() <= 0) {
                continue;
            }
            ProductDTO product = productDAO.findByCode(in.productCode());
            if (product == null) {
                continue;
            }
            LocalDate date = LocalDate.parse(in.date());
            InboundPlanDTO existing = planDAO.findByProductAndDate(product.getId(), date);
            if (existing != null) {
                planDAO.updatePlan(existing.getId(), in.plannedQty(), in.source());
            } else {
                planDAO.insert(new InboundPlanDTO(date, product.getId(), in.plannedQty(), 0, in.source()));
            }
            count++;
        }
        return count;
    }

    /**
     * 상품 확정 (DB 쪽): 코드 정확 일치 -> 이름/코드 부분 일치
     * 여기서 못 찾으면 Python AI 서비스가 벡터 유사도 검색으로 후보를 찾는다.
     */
    public ResolveResult resolveProduct(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new ResolveResult(null, List.of(), "none");
        }
        String k = keyword.trim();

        ProductDTO byCode = productDAO.findByCode(k);
        if (byCode != null) {
            return new ResolveResult(byCode, List.of(), "code");
        }

        List<ProductDTO> like = productDAO.searchByKeyword(k);
        if (like.isEmpty()) {
            like = searchByTokens(k);
        }
        if (like.size() == 1) {
            return new ResolveResult(like.get(0), List.of(), "name");
        }
        if (like.size() > 1) {
            return new ResolveResult(null, new ArrayList<>(like.subList(0, Math.min(5, like.size()))), "name");
        }
        return new ResolveResult(null, List.of(), "none");
    }

    /** 이력 필터용: 키워드에 걸리는 상품 전부 (예: "사과" → 사과 4개 산지, "MK-EHM" → 1개) */
    public List<ProductDTO> matchProducts(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String k = keyword.trim();
        ProductDTO byCode = productDAO.findByCode(k);
        if (byCode != null) {
            return List.of(byCode);
        }
        List<ProductDTO> like = productDAO.searchByKeyword(k);
        return like.isEmpty() ? searchByTokens(k) : like;
    }

    /**
     * "와카야마산 귤", "귤 와카야마" 처럼 단어 순서·조사가 달라도 찾도록
     * 띄어쓰기로 나눈 단어마다 검색해서 모든 단어에 걸리는 상품만 남긴다.
     */
    private List<ProductDTO> searchByTokens(String keyword) {
        List<ProductDTO> result = null;
        for (String raw : keyword.split("\\s+")) {
            String token = raw.trim();
            if (token.endsWith("県産") && token.length() > 2) {
                token = token.substring(0, token.length() - 2);
            } else if ((token.endsWith("산") || token.endsWith("産")) && token.length() > 2) {
                token = token.substring(0, token.length() - 1);
            }
            if (token.length() < 1 || token.matches("\\d+(kg|KG|상자)?")) {
                continue;
            }
            List<ProductDTO> hits = productDAO.searchByKeyword(token);
            if (result == null) {
                result = new ArrayList<>(hits);
            } else {
                List<Long> ids = hits.stream().map(ProductDTO::getId).toList();
                result.removeIf(p -> !ids.contains(p.getId()));
            }
        }
        return result == null ? List.of() : result;
    }

    /** 입고 검증: 사람이 화면에서 넣든 AI가 넣든 같은 규칙을 통과해야 한다 */
    public InboundCheck checkInbound(ProductDTO product, int qty, LocalDate date) {
        List<String> messages = new ArrayList<>();
        ProductDTO fresh = productDAO.findById(product.getId());
        if (fresh == null) {
            throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
        }

        int used = usage().usedPallets();
        int palletsAfter = used - fresh.getPallets() + fresh.palletsFor(fresh.getStockQty() + qty);
        boolean blocked = false;
        if (palletsAfter > capacityPallets) {
            blocked = true;
            messages.add("창고 용량 초과: 입고 후 " + palletsAfter + "/" + capacityPallets + " 팔레트 (승인 불가)");
        } else if (palletsAfter > capacityPallets * operatingCap) {
            messages.add(String.format("운영 상한 %d%% 초과: 입고 후 %d/%d 팔레트 (%d%%) — 출고 일정 확인 필요",
                    Math.round(operatingCap * 100), palletsAfter, capacityPallets,
                    Math.round(palletsAfter * 100.0 / capacityPallets)));
        }

        InboundPlanDTO plan = planDAO.findByProductAndDate(fresh.getId(), date);
        Long planId = null;
        int planned = 0;
        int received = 0;
        int remaining = 0;
        String level;
        boolean exceptionRequired;

        if (plan == null) {
            level = "NO_PLAN";
            exceptionRequired = true;
            messages.add(date + " 입고 예정이 없는 상품입니다. 예외 입고로만 처리할 수 있습니다.");
        } else {
            planId = plan.getId();
            planned = plan.getPlannedQty();
            received = plan.getReceivedQty();
            remaining = plan.getRemaining();
            int upper = (int) Math.floor(remaining * (1 + tolerance));
            int lower = (int) Math.ceil(remaining * (1 - tolerance));

            if (remaining == 0) {
                level = "OVER";
                exceptionRequired = true;
                messages.add("오늘 예정 " + planned + "상자는 이미 모두 입고됐습니다.");
            } else if (qty > upper) {
                level = "OVER";
                exceptionRequired = true;
                double ratio = (double) qty / remaining;
                messages.add(String.format("예정 잔량 %d상자 대비 %d상자 (%.1f배) — 허용 범위 ±%d%% 초과",
                        remaining, qty, ratio, Math.round(tolerance * 100)));
            } else if (qty < lower) {
                level = "SHORT";
                exceptionRequired = false;
                messages.add("부분 입고: 예정 잔량 " + remaining + "상자 중 " + qty + "상자, "
                        + (remaining - qty) + "상자는 미입고로 남습니다.");
            } else {
                level = "NORMAL";
                exceptionRequired = false;
                messages.add("예정 잔량 " + remaining + "상자 대비 정상 범위 (±" + Math.round(tolerance * 100) + "%)");
            }
        }

        Double avg = stockTxDAO.averageInQty(fresh.getId(), LocalDateTime.now().minusDays(7));
        if (avg != null && avg > 0 && qty > avg * 3) {
            messages.add(String.format("최근 7일 평균 입고량 %.0f상자 대비 %.1f배", avg, qty / avg));
        }

        return new InboundCheck(planId, planned, received, remaining, level, exceptionRequired, blocked,
                messages, palletsAfter, capacityPallets);
    }

    /** 화면에서 직접 입출고 (AI를 거치지 않는 기존 ERP 경로). 예외 규칙은 AI와 똑같이 적용 */
    @Transactional
    public StockTxDTO directChange(Long productId, String txType, int qty, String actor, String memo,
                                   String role, String reasonCode) {
        validate(txType, qty);
        ProductDTO product = productDAO.findById(productId);
        if (product == null) {
            throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
        }
        Long planId = null;
        String reason = null;
        if ("IN".equals(txType)) {
            InboundCheck check = checkInbound(product, qty, LocalDate.now());
            enforce(check, false, role, reasonCode, memo);
            planId = check.planId();
            reason = check.exceptionRequired() ? reasonCode : null;
        }
        return applyChange(productId, planId, txType, qty, actor, "UI", reason, memo);
    }

    /** AI가 요청한 입출고: DB에는 반영하지 않고 승인 대기 작업만 만든다 */
    @Transactional
    public PendingResult createPending(ProductDTO product, String txType, int qty, String actor, String memo) {
        validate(txType, qty);
        ProductDTO fresh = productDAO.findById(product.getId());
        if (fresh == null) {
            throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
        }
        int before = fresh.getStockQty();
        int after = "IN".equals(txType) ? before + qty : before - qty;
        if (after < 0) {
            throw new IllegalStateException("재고 부족: 현재 " + before + fresh.getUnit() + ", 출고 요청 " + qty + fresh.getUnit());
        }

        InboundCheck check = null;
        Long planId = null;
        boolean exceptionRequired = false;
        String warning = null;
        if ("IN".equals(txType)) {
            check = checkInbound(fresh, qty, LocalDate.now());
            if (check.blocked()) {
                throw new IllegalStateException(String.join(" / ", check.messages()));
            }
            planId = check.planId();
            exceptionRequired = check.exceptionRequired();
            warning = String.join(" / ", check.messages());
        }

        PendingActionDTO action = new PendingActionDTO(txType, fresh.getId(), planId, qty, before, after,
                exceptionRequired, warning, actor, memo, PENDING_TTL_MINUTES);
        pendingActionDAO.insert(action);
        return new PendingResult(action, check);
    }

    /**
     * 승인: PENDING -> DONE 으로 바뀐 경우에만(=1건 갱신) 반영. 두 번 눌러도 두 번째는 막힌다.
     * 승인 시점에 입고 검증을 다시 돌린다 (그 사이 다른 입고가 있었을 수 있음).
     */
    @Transactional
    public StockTxDTO approve(String actionId, String approver, String role, String reasonCode) {
        int updated = pendingActionDAO.transition(actionId, "DONE", LocalDateTime.now());
        if (updated == 0) {
            throw new IllegalStateException("이미 처리됐거나 만료된 요청입니다.");
        }
        PendingActionDTO action = pendingActionDAO.findById(actionId);
        if (action == null) {
            throw new IllegalArgumentException("요청을 찾을 수 없습니다.");
        }

        Long planId = null;
        String reason = null;
        if ("IN".equals(action.getActionType())) {
            ProductDTO product = productDAO.findById(action.getProductId());
            if (product == null) {
                throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
            }
            InboundCheck check = checkInbound(product, action.getQty(), LocalDate.now());
            enforce(check, action.isExceptionRequired(), role, reasonCode, action.getMemo());
            planId = check.planId();
            reason = (check.exceptionRequired() || action.isExceptionRequired()) ? reasonCode : null;
        }

        String memo = "AI 요청 승인 (요청자 " + action.getActor() + ", 요청 " + action.getId().substring(0, 8) + ")";
        if (action.getMemo() != null && !action.getMemo().isBlank()) {
            memo = memo + " " + action.getMemo();
        }
        return applyChange(action.getProductId(), planId, action.getActionType(), action.getQty(),
                approver, "AI", reason, memo);
    }

    @Transactional
    public void reject(String actionId) {
        int updated = pendingActionDAO.transition(actionId, "REJECTED", LocalDateTime.now());
        if (updated == 0) {
            throw new IllegalStateException("이미 처리됐거나 만료된 요청입니다.");
        }
    }

    private void enforce(InboundCheck check, boolean alreadyException, String role, String reasonCode, String memo) {
        if (check.blocked()) {
            throw new IllegalStateException(String.join(" / ", check.messages()));
        }
        if (check.exceptionRequired() || alreadyException) {
            if (!"MANAGER".equals(role)) {
                throw new IllegalStateException(String.join(" / ", check.messages())
                        + " → 예외 입고는 관리자(MANAGER) 권한으로만 승인할 수 있습니다.");
            }
            if (reasonCode == null || !REASON_CODES.containsKey(reasonCode)) {
                throw new IllegalStateException("예외 입고는 사유코드를 선택해야 합니다.");
            }
            if ("OTHER".equals(reasonCode) && (memo == null || memo.isBlank())) {
                throw new IllegalStateException("사유코드 '기타'는 메모가 필요합니다.");
            }
        }
    }

    /** 실제 반영: 상품 행 잠금(FOR UPDATE) -> 재고 변경 -> 입고 예정 실적 반영 -> 이력(감사 로그) 기록 */
    private StockTxDTO applyChange(Long productId, Long planId, String txType, int qty, String actor,
                                   String channel, String reasonCode, String memo) {
        ProductDTO product = productDAO.findByIdForUpdate(productId);
        if (product == null) {
            throw new IllegalArgumentException("상품을 찾을 수 없습니다.");
        }
        int before = product.getStockQty();
        int after = "IN".equals(txType) ? before + qty : before - qty;
        if (after < 0) {
            throw new IllegalStateException("재고 부족: 현재 " + before + product.getUnit() + ", 출고 요청 " + qty + product.getUnit());
        }
        productDAO.updateStock(productId, after);

        if (planId != null) {
            InboundPlanDTO plan = planDAO.findByIdForUpdate(planId);
            if (plan == null) {
                throw new IllegalArgumentException("입고 예정을 찾을 수 없습니다.");
            }
            planDAO.addReceived(planId, qty);
        }

        StockTxDTO tx = new StockTxDTO(productId, planId, txType, qty, before, after, actor, channel, reasonCode, memo);
        tx.setProductCode(product.getCode());
        tx.setProductName(product.getName());
        tx.setProductUnit(product.getUnit());
        stockTxDAO.insert(tx);
        return tx;
    }

    private void validate(String txType, int qty) {
        if (!"IN".equals(txType) && !"OUT".equals(txType)) {
            throw new IllegalArgumentException("구분은 IN(입고) 또는 OUT(출고)만 가능합니다.");
        }
        if (qty <= 0 || qty > MAX_QTY) {
            throw new IllegalArgumentException("수량은 1 ~ " + MAX_QTY + " 사이여야 합니다.");
        }
    }

    public int getCapacityPallets() {
        return capacityPallets;
    }

    public double getTolerance() {
        return tolerance;
    }
}
