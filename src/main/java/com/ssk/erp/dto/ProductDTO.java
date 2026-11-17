package com.ssk.erp.dto;

import java.time.LocalDateTime;

/** 상품 (erp_product) */
public class ProductDTO {

    private Long id;
    private String code;
    private String name;
    private String category;
    private String origin;
    private String unit;
    private Integer kgPerBox;
    private Integer boxesPerPallet;
    private String description;
    private Integer stockQty;
    private Integer safetyQty;
    private String location;
    private LocalDateTime updatedAt;

    public ProductDTO() {
    }

    public ProductDTO(String code, String name, String category, String origin, String unit,
                      int kgPerBox, int boxesPerPallet, String description,
                      int stockQty, int safetyQty, String location) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.origin = origin;
        this.unit = unit;
        this.kgPerBox = kgPerBox;
        this.boxesPerPallet = boxesPerPallet;
        this.description = description;
        this.stockQty = stockQty;
        this.safetyQty = safetyQty;
        this.location = location;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isLow() {
        return stockQty < safetyQty;
    }

    /** 이 수량이 차지하는 팔레트 수 (T11 팔레트, 올림) */
    public int palletsFor(int qty) {
        if (qty <= 0) {
            return 0;
        }
        return (qty + boxesPerPallet - 1) / boxesPerPallet;
    }

    public int getPallets() {
        return palletsFor(stockQty);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Integer getKgPerBox() {
        return kgPerBox;
    }

    public void setKgPerBox(Integer kgPerBox) {
        this.kgPerBox = kgPerBox;
    }

    public Integer getBoxesPerPallet() {
        return boxesPerPallet;
    }

    public void setBoxesPerPallet(Integer boxesPerPallet) {
        this.boxesPerPallet = boxesPerPallet;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getStockQty() {
        return stockQty;
    }

    public void setStockQty(Integer stockQty) {
        this.stockQty = stockQty;
    }

    public Integer getSafetyQty() {
        return safetyQty;
    }

    public void setSafetyQty(Integer safetyQty) {
        this.safetyQty = safetyQty;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
