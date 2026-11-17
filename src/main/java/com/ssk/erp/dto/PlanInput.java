package com.ssk.erp.dto;

/** Python 예측 서비스가 보내는 입고 예정 한 건 */
public record PlanInput(String date, String productCode, int plannedQty, String source) {
}
