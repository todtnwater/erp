package com.ssk.erp.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 입고 예정 (erp_inbound_plan). productCode/productName 은 조회 시 erp_product 와 조인해서 채움 */
public class InboundPlanDTO {

    private Long id;
    private LocalDate planDate;
    private Long productId;
    private String productCode;
    private String productName;
    private Integer plannedQty;
    private Integer receivedQty;
    private String source;
    private LocalDateTime updatedAt;

    public InboundPlanDTO() {
    }

    public InboundPlanDTO(LocalDate planDate, Long productId, int plannedQty, int receivedQty, String source) {
        this.planDate = planDate;
        this.productId = productId;
        this.plannedQty = plannedQty;
        this.receivedQty = receivedQty;
        this.source = source;
        this.updatedAt = LocalDateTime.now();
    }

    public int getRemaining() {
        return Math.max(plannedQty - receivedQty, 0);
    }

    /** 예정 대비 90% 이상 들어오면 완료로 본다 */
    public boolean isComplete() {
        return receivedQty * 10 >= plannedQty * 9;
    }

    /** PLANNED(예정) / PARTIAL(부분입고) / DONE(완료) / MISSED(미입고) */
    public String statusOn(LocalDate today) {
        if (isComplete()) {
            return "DONE";
        }
        if (planDate.isBefore(today)) {
            return receivedQty == 0 ? "MISSED" : "PARTIAL";
        }
        return receivedQty == 0 ? "PLANNED" : "PARTIAL";
    }

    public String statusLabelOn(LocalDate today) {
        return switch (statusOn(today)) {
            case "DONE" -> "완료";
            case "MISSED" -> "미입고";
            case "PARTIAL" -> "부분입고";
            default -> "예정";
        };
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getPlanDate() {
        return planDate;
    }

    public void setPlanDate(LocalDate planDate) {
        this.planDate = planDate;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Integer getPlannedQty() {
        return plannedQty;
    }

    public void setPlannedQty(Integer plannedQty) {
        this.plannedQty = plannedQty;
    }

    public Integer getReceivedQty() {
        return receivedQty;
    }

    public void setReceivedQty(Integer receivedQty) {
        this.receivedQty = receivedQty;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
