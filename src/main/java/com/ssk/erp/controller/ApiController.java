package com.ssk.erp.controller;

import com.ssk.erp.client.AiClient;
import com.ssk.erp.config.AuthInterceptor;
import com.ssk.erp.dto.LoginUser;
import com.ssk.erp.dto.StockTxDTO;
import com.ssk.erp.service.InventoryService;
import com.ssk.erp.service.UserService;
import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record ChatMsg(String role, String content) {
    }

    public record ChatRequest(String message, String role, String actor, List<ChatMsg> history) {
    }

    public record DecisionRequest(String actor, String role, String reasonCode) {
    }

    private final AiClient ai;
    private final InventoryService inventory;
    private final UserService users;
    private final double operatingCap;

    public ApiController(AiClient ai, InventoryService inventory, UserService users,
                         @Value("${erp.warehouse.operating-cap:0.8}") double operatingCap) {
        this.ai = ai;
        this.inventory = inventory;
        this.users = users;
        this.operatingCap = operatingCap;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        JsonNode h = ai.health();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean up = h != null;
        out.put("ai", up);
        out.put("ollama", up && h.path("ollama").asBoolean(false));
        out.put("qdrant", up && h.path("qdrant").asBoolean(false));
        out.put("model", up ? h.path("model").asText("") : "");
        out.put("manualsReady", up && h.path("manuals_ready").asBoolean(false));
        out.put("productsReady", up && h.path("products_ready").asBoolean(false));
        return out;
    }

    @GetMapping("/reason-codes")
    public Map<String, String> reasonCodes() {
        return InventoryService.REASON_CODES;
    }

    /** 브라우저 -> Spring -> Python AI 서비스. LLM 연산은 Python, 도구 실행은 다시 Spring 내부 API */
    @PostMapping("/chat")
    public JsonNode chat(@RequestBody ChatRequest req, HttpServletRequest request) {
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("메시지가 비어 있습니다.");
        }
        if (req.message().length() > 500) {
            throw new IllegalArgumentException("메시지는 500자 이하로 입력해 주세요.");
        }
        LoginUser me = users.refresh(AuthInterceptor.current(request));
        JsonNode res = ai.chat(req.message().trim(), me.role(), me.name(), req.history());
        // 내부 도구 이름·파라미터(시스템 구조)는 관리자에게만 보낸다
        if (!me.admin() && res instanceof ObjectNode node) {
            node.remove("toolLog");
        }
        return res;
    }

    @PostMapping("/actions/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) DecisionRequest req,
                                       HttpServletRequest request) {
        LoginUser me = users.refresh(AuthInterceptor.current(request));
        String actor = me.name();
        String role = me.role();
        String reason = req == null || req.reasonCode() == null || req.reasonCode().isBlank() ? null : req.reasonCode();
        StockTxDTO tx = inventory.approve(id, actor, role, reason);
        String label = "IN".equals(tx.getTxType()) ? "입고" : "출고";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", tx.getProductName() + " " + tx.getQty() + tx.getProductUnit() + " " + label
                + " 반영 완료 (" + tx.getBeforeQty() + " → " + tx.getAfterQty() + ", 승인자 " + actor
                + (tx.getReasonCode() != null ? ", 예외 사유 " + tx.getReasonCode() : "") + ")");
        return out;
    }

    @PostMapping("/actions/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id) {
        inventory.reject(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "요청을 거절했습니다. 재고는 변경되지 않았습니다.");
        return out;
    }

    @PostMapping("/admin/reindex")
    public JsonNode reindex(HttpServletRequest request) {
        LoginUser me = users.refresh(AuthInterceptor.current(request));
        if (!me.admin()) {
            throw new IllegalStateException("관리자만 실행할 수 있습니다.");
        }
        return ai.reindex();
    }

    /** 창고 맵 화면용: 상품별 재고·팔레트 + 오늘 입고 예정 잔량 (승인 후 다시 불러서 칸이 채워지는 걸 보여줌) */
    @GetMapping("/warehouse-map")
    public Map<String, Object> warehouseMap() {
        LocalDate today = LocalDate.now();
        InventoryService.WarehouseUsage usage = inventory.usage();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capacity", usage.capacity());
        out.put("operatingCap", operatingCap);
        out.put("tolerance", inventory.getTolerance());
        out.put("usedPallets", usage.usedPallets());
        out.put("percent", usage.percent());
        out.put("products", inventory.listProducts());
        out.put("todayPlans", inventory.plansBetween(today, today));
        return out;
    }

    /** 입하량 예측 화면용: 브라우저 -> Spring -> Python /forecast/{item} */
    @GetMapping("/forecast/{item}")
    public JsonNode forecast(@PathVariable String item, @RequestParam(defaultValue = "14") int days) {
        if (!List.of("귤", "사과", "양파").contains(item)) {
            throw new IllegalArgumentException("품목은 귤, 사과, 양파 중 하나입니다.");
        }
        return ai.forecast(item, Math.max(1, Math.min(days, 14)));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> aiDown(RestClientException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Python AI 서비스 호출 실패: " + e.getMessage()
                        + " / ERP 화면 기능은 그대로 사용할 수 있습니다."));
    }

}
