package com.ssk.erp.config;

import com.ssk.erp.dao.InboundPlanDAO;
import com.ssk.erp.dao.ProductDAO;
import com.ssk.erp.dao.StockTxDAO;
import com.ssk.erp.dto.InboundPlanDTO;
import com.ssk.erp.dto.ProductDTO;
import com.ssk.erp.dto.StockTxDTO;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 상품이 하나도 없을 때만 샘플 데이터를 넣는다 (한글·일본어가 깨지지 않게 JDBC로 입력)
 * - 상품 마스터: 도쿄 중앙도매시장 2025년 11월 산지 비중 상위 산지
 * - inbound_plan.csv: 입고 예정 (전년 같은 요일 입하량 × 성장률 × 점유율 0.5% ÷ 상자당 kg)
 * - seed_tx.csv: 지난주 입출고 이력 (감사 로그)
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final DateTimeFormatter TX_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ProductDAO productDAO;
    private final InboundPlanDAO planDAO;
    private final StockTxDAO stockTxDAO;

    public DataSeeder(ProductDAO productDAO, InboundPlanDAO planDAO, StockTxDAO stockTxDAO) {
        this.productDAO = productDAO;
        this.planDAO = planDAO;
        this.stockTxDAO = stockTxDAO;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (productDAO.count() > 0) {
            return;
        }

        List<ProductDTO> products = List.of(
                new ProductDTO("MK-EHM", "귤 愛媛 10kg", "귤", "愛媛", "상자", 10, 54,
                        "みかん 愛媛県産 에히메 귤 감귤 10kg 상자", 420, 250, "C2-01"),
                new ProductDTO("MK-KMM", "귤 熊本 10kg", "귤", "熊本", "상자", 10, 54,
                        "みかん 熊本県産 구마모토 귤 감귤 10kg 상자", 390, 230, "C2-02"),
                new ProductDTO("MK-NGS", "귤 長崎 10kg", "귤", "長崎", "상자", 10, 54,
                        "みかん 長崎県産 나가사키 귤 감귤 10kg 상자", 110, 160, "C2-03"),
                new ProductDTO("MK-WKY", "귤 和歌山 10kg", "귤", "和歌山", "상자", 10, 54,
                        "みかん 和歌山県産 와카야마 귤 감귤 10kg 상자", 170, 100, "C2-04"),
                new ProductDTO("AP-AOM", "사과 青森 10kg", "사과", "青森", "상자", 10, 54,
                        "りんご 青森県産 아오모리 사과 10kg 상자", 260, 160, "C1-01"),
                new ProductDTO("AP-NGN", "사과 長野 10kg", "사과", "長野", "상자", 10, 54,
                        "りんご 長野県産 나가노 사과 10kg 상자", 130, 80, "C1-02"),
                new ProductDTO("AP-YMG", "사과 山形 10kg", "사과", "山形", "상자", 10, 54,
                        "りんご 山形県産 야마가타 사과 10kg 상자", 45, 60, "C1-03"),
                new ProductDTO("AP-IWT", "사과 岩手 10kg", "사과", "岩手", "상자", 10, 54,
                        "りんご 岩手県産 이와테 사과 10kg 상자", 25, 20, "C1-04"),
                new ProductDTO("ON-HKD", "양파 北海道 20kg", "양파", "北海道", "상자", 20, 27,
                        "たまねぎ 北海道産 홋카이도 양파 20kg 상자", 420, 200, "C3-01")
        );

        Map<String, ProductDTO> byCode = new HashMap<>();
        for (ProductDTO p : products) {
            productDAO.insert(p);
            byCode.put(p.getCode(), p);
        }

        Map<String, InboundPlanDTO> planByKey = new HashMap<>();
        for (String[] row : readCsv("inbound_plan.csv")) {
            ProductDTO product = byCode.get(row[1]);
            if (product == null) {
                continue;
            }
            InboundPlanDTO plan = new InboundPlanDTO(LocalDate.parse(row[0]), product.getId(),
                    Integer.parseInt(row[2]), Integer.parseInt(row[3]), row[4]);
            planDAO.insert(plan);
            planByKey.put(row[0] + "|" + row[1], plan);
        }

        for (String[] row : readCsv("seed_tx.csv")) {
            ProductDTO product = byCode.get(row[1]);
            if (product == null) {
                continue;
            }
            String planDate = row.length > 9 ? row[9] : "";
            InboundPlanDTO plan = planDate.isBlank() ? null : planByKey.get(planDate + "|" + row[1]);
            StockTxDTO tx = new StockTxDTO(product.getId(), plan == null ? null : plan.getId(), row[2],
                    Integer.parseInt(row[3]), Integer.parseInt(row[4]), Integer.parseInt(row[5]),
                    row[6], row[7], null, row[8]);
            tx.setCreatedAt(LocalDateTime.parse(row[0], TX_TIME));
            stockTxDAO.insert(tx);
        }
    }

    /** 첫 줄(헤더)을 건너뛰고 쉼표로 나눈 행 목록을 돌려준다 */
    private List<String[]> readCsv(String name) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                rows.add(line.split(",", -1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
