package com.ssk.erp.dto;

import java.time.LocalDateTime;

/** 입출고 이력 = 감사 로그 (erp_stock_tx). productCode/productName/productUnit 은 조회 시 조인 */
public class StockTxDTO {

    private Long id;
    private Long productId;
    private String productCode;
    private String productName;
    private String productUnit;
    private Long planId;
    private String txType;
    private Integer qty;
    private Integer beforeQty;
    private Integer afterQty;
    private String actor;
    private String channel;
    private String reasonCode;
    private String memo;
    private LocalDateTime createdAt;

    public StockTxDTO() {
    }

    public StockTxDTO(Long productId, Long planId, String txType, int qty, int beforeQty, int afterQty,
                      String actor, String channel, String reasonCode, String memo) {
        this.productId = productId;
        this.planId = planId;
        this.txType = txType;
        this.qty = qty;
        this.beforeQty = beforeQty;
        this.afterQty = afterQty;
        this.actor = actor;
        this.channel = channel;
        this.reasonCode = reasonCode;
        this.memo = memo;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getProductUnit() {
        return productUnit;
    }

    public void setProductUnit(String productUnit) {
        this.productUnit = productUnit;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
    }

    public String getTxType() {
        return txType;
    }

    public void setTxType(String txType) {
        this.txType = txType;
    }

    public Integer getQty() {
        return qty;
    }

    public void setQty(Integer qty) {
        this.qty = qty;
    }

    public Integer getBeforeQty() {
        return beforeQty;
    }

    public void setBeforeQty(Integer beforeQty) {
        this.beforeQty = beforeQty;
    }

    public Integer getAfterQty() {
        return afterQty;
    }

    public void setAfterQty(Integer afterQty) {
        this.afterQty = afterQty;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
