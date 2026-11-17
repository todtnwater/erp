package com.ssk.erp.controller;

import com.ssk.erp.config.AuthInterceptor;
import com.ssk.erp.dto.InboundPlanDTO;
import com.ssk.erp.dto.LoginUser;
import com.ssk.erp.dto.ProductDTO;
import com.ssk.erp.dto.StockTxDTO;
import com.ssk.erp.service.InventoryService;
import com.ssk.erp.service.UserService;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ERP 메뉴 화면 (Thymeleaf). 모든 화면 오른쪽에 AI 도우미 패널이 붙는다 */
@Controller
public class PageController {

    /** 입출고 처리 후 돌아갈 수 있는 화면 (외부 주소로 리다이렉트 방지) */
    private static final Set<String> BACK_PAGES = Set.of("/", "/inbound", "/outbound", "/stock");

    private final InventoryService inventory;
    private final UserService users;

    public PageController(InventoryService inventory, UserService users) {
        this.inventory = inventory;
        this.users = users;
    }

    /** 대시보드: 오늘 해야 할 일 + 이상 징후를 한 화면에 */
    @GetMapping("/")
    public String dashboard(Model model) {
        LocalDate today = LocalDate.now();
        List<ProductDTO> products = inventory.listProducts();
        List<InboundPlanDTO> todayPlans = inventory.plansBetween(today, today);
        List<InboundPlanDTO> missed = inventory.plansBetween(today.minusDays(7), today.minusDays(1)).stream()
                .filter(p -> !"DONE".equals(p.statusOn(today)))
                .toList();
        List<StockTxDTO> week = inventory.txSince(7);
        List<StockTxDTO> todayTx = week.stream()
                .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().toLocalDate().equals(today))
                .toList();

        int totalBoxes = products.stream().mapToInt(ProductDTO::getStockQty).sum();
        int totalKg = products.stream().mapToInt(p -> p.getStockQty() * p.getKgPerBox()).sum();
        long todayDone = todayPlans.stream().filter(InboundPlanDTO::isComplete).count();
        int todayPlanned = todayPlans.stream().mapToInt(InboundPlanDTO::getPlannedQty).sum();
        int todayReceived = todayPlans.stream().mapToInt(InboundPlanDTO::getReceivedQty).sum();
        int todayIn = todayTx.stream().filter(t -> "IN".equals(t.getTxType())).mapToInt(StockTxDTO::getQty).sum();
        int todayOut = todayTx.stream().filter(t -> "OUT".equals(t.getTxType())).mapToInt(StockTxDTO::getQty).sum();
        long exceptions7d = week.stream().filter(t -> t.getReasonCode() != null).count();

        model.addAttribute("today", today);
        model.addAttribute("products", products);
        model.addAttribute("lowList", products.stream().filter(ProductDTO::isLow).toList());
        model.addAttribute("totalBoxes", totalBoxes);
        model.addAttribute("totalTon", String.format("%.1f", totalKg / 1000.0));
        model.addAttribute("usage", inventory.usage());
        model.addAttribute("todayPlans", todayPlans);
        model.addAttribute("todayDone", todayDone);
        model.addAttribute("todayPlanned", todayPlanned);
        model.addAttribute("todayReceived", todayReceived);
        model.addAttribute("todayIn", todayIn);
        model.addAttribute("todayOut", todayOut);
        model.addAttribute("missed", missed);
        model.addAttribute("exceptions7d", exceptions7d);
        model.addAttribute("recentTx", week.stream().limit(8).toList());
        return "dashboard";
    }

    /** 기준정보: 상품 마스터 */
    @GetMapping("/products")
    public String products(@RequestParam(defaultValue = "") String cat, Model model) {
        List<ProductDTO> all = inventory.listProducts();
        List<ProductDTO> shown = cat.isBlank() ? all
                : all.stream().filter(p -> cat.equals(p.getCategory())).toList();
        model.addAttribute("products", shown);
        model.addAttribute("categories", all.stream().map(ProductDTO::getCategory).distinct().toList());
        model.addAttribute("cat", cat);
        model.addAttribute("total", all.size());
        return "products";
    }

    /** 입고 예정 대비 실적 + 입고 처리 */
    @GetMapping("/inbound")
    public String inbound(@RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(defaultValue = "") String status,
                          Model model) {
        LocalDate today = LocalDate.now();
        LocalDate f = parseDate(from, today.minusDays(7));
        LocalDate t = parseDate(to, today.plusDays(7));
        if (t.isBefore(f)) {
            LocalDate tmp = f;
            f = t;
            t = tmp;
        }
        List<InboundPlanDTO> all = inventory.plansBetween(f, t);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PLANNED", 0L);
        counts.put("PARTIAL", 0L);
        counts.put("DONE", 0L);
        counts.put("MISSED", 0L);
        for (InboundPlanDTO p : all) {
            counts.merge(p.statusOn(today), 1L, Long::sum);
        }
        List<InboundPlanDTO> shown = status.isBlank() ? all
                : all.stream().filter(p -> status.equals(p.statusOn(today))).toList();

        model.addAttribute("today", today);
        model.addAttribute("from", f);
        model.addAttribute("to", t);
        model.addAttribute("status", status);
        model.addAttribute("counts", counts);
        model.addAttribute("plans", shown);
        model.addAttribute("todayPlans", inventory.plansBetween(today, today));
        model.addAttribute("products", inventory.listProducts());
        model.addAttribute("reasonCodes", InventoryService.REASON_CODES);
        model.addAttribute("tolerancePct", Math.round(inventory.getTolerance() * 100));
        return "inbound";
    }

    /** 출고 처리 */
    @GetMapping("/outbound")
    public String outbound(Model model) {
        model.addAttribute("products", inventory.listProducts());
        model.addAttribute("outTx", inventory.txSince(7).stream()
                .filter(t -> "OUT".equals(t.getTxType()))
                .limit(30)
                .toList());
        return "outbound";
    }

    /** 재고 현황 + 창고(팔레트) 사용률 */
    @GetMapping("/stock")
    public String stock(@RequestParam(defaultValue = "false") boolean lowOnly, Model model) {
        List<ProductDTO> all = inventory.listProducts();
        model.addAttribute("products", lowOnly ? all.stream().filter(ProductDTO::isLow).toList() : all);
        model.addAttribute("lowCount", all.stream().filter(ProductDTO::isLow).count());
        model.addAttribute("total", all.size());
        model.addAttribute("lowOnly", lowOnly);
        model.addAttribute("usage", inventory.usage());
        return "stock";
    }

    /** 창고 맵: 82개 팔레트 자리를 도면처럼 표시 (데이터는 화면에서 /api/warehouse-map 으로 불러옴) */
    @GetMapping("/warehouse")
    public String warehouse(Model model) {
        model.addAttribute("products", inventory.listProducts());
        return "warehouse";
    }

    /** 입출고 이력 = 감사 로그 (누가, 언제, 어떤 경로로, 어떤 사유로) */
    @GetMapping("/history")
    public String history(@RequestParam(defaultValue = "7") int days,
                          @RequestParam(defaultValue = "") String type,
                          @RequestParam(defaultValue = "") String channel,
                          @RequestParam(defaultValue = "false") boolean exceptionOnly,
                          @RequestParam(defaultValue = "") String q,
                          Model model) {
        int d = Math.max(1, Math.min(days, 90));
        String keyword = q.trim().toLowerCase();
        List<StockTxDTO> txs = inventory.txSince(d).stream()
                .filter(t -> type.isBlank() || type.equals(t.getTxType()))
                .filter(t -> channel.isBlank() || channel.equals(t.getChannel()))
                .filter(t -> !exceptionOnly || t.getReasonCode() != null)
                .filter(t -> keyword.isEmpty()
                        || (t.getProductCode() + " " + t.getProductName() + " " + t.getActor() + " "
                        + (t.getMemo() == null ? "" : t.getMemo())).toLowerCase().contains(keyword))
                .toList();

        model.addAttribute("txs", txs);
        model.addAttribute("days", d);
        model.addAttribute("type", type);
        model.addAttribute("channel", channel);
        model.addAttribute("exceptionOnly", exceptionOnly);
        model.addAttribute("q", q);
        model.addAttribute("inSum", txs.stream().filter(t -> "IN".equals(t.getTxType())).mapToInt(StockTxDTO::getQty).sum());
        model.addAttribute("outSum", txs.stream().filter(t -> "OUT".equals(t.getTxType())).mapToInt(StockTxDTO::getQty).sum());
        model.addAttribute("aiCount", txs.stream().filter(t -> "AI".equals(t.getChannel())).count());
        model.addAttribute("exceptionCount", txs.stream().filter(t -> t.getReasonCode() != null).count());
        model.addAttribute("reasonCodes", InventoryService.REASON_CODES);
        return "history";
    }

    /** 입하량 예측 (Python 랜덤포레스트) + 앞으로 2주 입고 예정 */
    @GetMapping("/forecast")
    public String forecast(Model model) {
        LocalDate today = LocalDate.now();
        List<InboundPlanDTO> plans = inventory.plansBetween(today, today.plusDays(14));
        Map<LocalDate, Integer> byDate = new LinkedHashMap<>();
        for (InboundPlanDTO p : plans) {
            byDate.merge(p.getPlanDate(), p.getPlannedQty(), Integer::sum);
        }
        model.addAttribute("today", today);
        model.addAttribute("plans", plans);
        model.addAttribute("byDate", byDate);
        return "forecast";
    }

    /**
     * 화면에서 직접 입출고. AI 경로와 같은 검증 규칙(InventoryService.directChange)을 탄다.
     * 처리자 이름과 권한은 화면 입력값이 아니라 로그인 정보(DB의 최신 is_admin)로만 정한다.
     */
    @PostMapping("/stock")
    public String change(@RequestParam Long productId,
                         @RequestParam String txType,
                         @RequestParam int qty,
                         @RequestParam(defaultValue = "") String memo,
                         @RequestParam(defaultValue = "") String reasonCode,
                         @RequestParam(defaultValue = "/") String back,
                         HttpServletRequest request,
                         RedirectAttributes ra) {
        String target = BACK_PAGES.contains(back) ? back : "/";
        try {
            LoginUser me = users.refresh(AuthInterceptor.current(request));
            String reason = reasonCode.isBlank() ? null : reasonCode;
            StockTxDTO tx = inventory.directChange(productId, txType, qty, me.name(), memo.trim(), me.role(), reason);
            String label = "IN".equals(tx.getTxType()) ? "입고" : "출고";
            ra.addFlashAttribute("flash", tx.getProductName() + " " + tx.getQty() + tx.getProductUnit()
                    + " " + label + " 완료 (" + tx.getBeforeQty() + " → " + tx.getAfterQty() + ")"
                    + (tx.getReasonCode() != null ? " · 예외 사유 " + tx.getReasonCode() : ""));
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:" + target;
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
