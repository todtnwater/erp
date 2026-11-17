package com.ssk.erp.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** AI가 만든 승인 대기 작업 (erp_pending_action) */
public class PendingActionDTO {

    private String id;
    private String actionType;
    private Long productId;
    private Long planId;
    private Integer qty;
    private Integer beforeQty;
    private Integer afterQty;
    private Integer exceptionFlag;
    private String warning;
    private String actor;
    private String status;
    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime decidedAt;

    public PendingActionDTO() {
    }

    public PendingActionDTO(String actionType, Long productId, Long planId, int qty, int beforeQty, int afterQty,
                            boolean exceptionRequired, String warning, String actor, String memo, int ttlMinutes) {
        LocalDateTime now = LocalDateTime.now();
        this.id = UUID.randomUUID().toString();
        this.actionType = actionType;
        this.productId = productId;
        this.planId = planId;
        this.qty = qty;
        this.beforeQty = beforeQty;
        this.afterQty = afterQty;
        this.exceptionFlag = exceptionRequired ? 1 : 0;
        this.warning = warning;
        this.actor = actor;
        this.status = "PENDING";
        this.memo = memo;
        this.createdAt = now;
        this.expiresAt = now.plusMinutes(ttlMinutes);
    }

    public boolean isExceptionRequired() {
        return exceptionFlag != null && exceptionFlag == 1;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getPlanId() {
        return planId;
    }

    public void setPlanId(Long planId) {
        this.planId = planId;
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

    public Integer getExceptionFlag() {
        return exceptionFlag;
    }

    public void setExceptionFlag(Integer exceptionFlag) {
        this.exceptionFlag = exceptionFlag;
    }

    public String getWarning() {
        return warning;
    }

    public void setWarning(String warning) {
        this.warning = warning;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(LocalDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }
}
