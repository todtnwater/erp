package com.ssk.erp.controller;

import com.ssk.erp.dto.PlanInput;
import com.ssk.erp.service.InventoryService;
import com.ssk.erp.service.ToolService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Python AI 서비스 전용 내부 API.
 * 모든 요청은 X-Internal-Token 헤더가 erp.internal.token 과 같아야 한다 (토큰이 비어 있으면 전부 거부).
 */
@RestController
@RequestMapping("/internal")
public class InternalApiController {

    public record StockRequest(String productCode, String changeType, int quantity, String unit,
                               String memo, String actor) {
    }

    private static class UnauthorizedException extends RuntimeException {
    }

    private final ToolService tools;
    private final InventoryService inventory;
    private final String token;

    public InternalApiController(ToolService tools, InventoryService inventory,
                                 @Value("${erp.internal.token:}") String token) {
        this.tools = tools;
        this.inventory = inventory;
        this.token = token == null ? "" : token.trim();
    }

    private void check(String header) {
        if (token.isEmpty() || header == null || !token.equals(header)) {
            throw new UnauthorizedException();
        }
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(@RequestHeader(value = "X-Internal-Token", required = false) String t) {
        check(t);
        return tools.productsForIndex();
    }

    @GetMapping("/products/low")
    public Map<String, Object> low(@RequestHeader(value = "X-Internal-Token", required = false) String t) {
        check(t);
        return tools.lowStock();
    }

    @GetMapping("/products/resolve")
    public Map<String, Object> resolve(@RequestHeader(value = "X-Internal-Token", required = false) String t,
                                       @RequestParam String keyword) {
        check(t);
        return tools.resolve(keyword);
    }

    @GetMapping("/inbound")
    public Map<String, Object> inbound(@RequestHeader(value = "X-Internal-Token", required = false) String t,
                                       @RequestParam(defaultValue = "") String date) {
        check(t);
        return tools.inboundStatus(date);
    }

    @PostMapping("/inbound/plans")
    public Map<String, Object> savePlans(@RequestHeader(value = "X-Internal-Token", required = false) String t,
                                         @RequestBody List<PlanInput> plans) {
        check(t);
        return Map.of("saved", inventory.upsertPlans(plans));
    }

    @PostMapping("/stock-requests")
    public Map<String, Object> stockRequest(@RequestHeader(value = "X-Internal-Token", required = false) String t,
                                            @RequestBody StockRequest req) {
        check(t);
        return tools.stockRequest(req.productCode(), req.changeType(), req.quantity(), req.unit(),
                req.memo(), req.actor() == null || req.actor().isBlank() ? "담당자" : req.actor());
    }

    @GetMapping("/warehouse")
    public Map<String, Object> warehouse(@RequestHeader(value = "X-Internal-Token", required = false) String t) {
        check(t);
        return tools.warehouseUsage();
    }

    @GetMapping("/transactions")
    public Map<String, Object> transactions(@RequestHeader(value = "X-Internal-Token", required = false) String t,
                                            @RequestParam(defaultValue = "7") int days,
                                            @RequestParam(defaultValue = "") String product,
                                            @RequestParam(defaultValue = "") String type) {
        check(t);
        return tools.recentTransactions(days, product, type);
    }

    /** 특정 날짜·시간대 입출고 이력 (from/to: 2026-11-14 또는 2026-11-14T14:00) */
    @GetMapping("/transactions/range")
    public Map<String, Object> transactionsRange(@RequestHeader(value = "X-Internal-Token", required = false) String t,
                                                 @RequestParam String from,
                                                 @RequestParam(defaultValue = "") String to,
                                                 @RequestParam(defaultValue = "") String product,
                                                 @RequestParam(defaultValue = "") String type) {
        check(t);
        return tools.transactionsBetween(from, to, product, type);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid internal token"));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
