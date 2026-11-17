package com.ssk.erp.dto;

import java.time.LocalDateTime;

/** 사용자 (erp_user). is_admin: 1 = 관리자, 0 = 현장 직원 */
public class UserDTO {

    private Long id;
    private String loginId;
    private String passwordHash;
    private String name;
    private Integer isAdmin;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;

    public UserDTO() {
    }

    public UserDTO(String loginId, String passwordHash, String name, int isAdmin) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.isAdmin = isAdmin;
        this.enabled = 1;
        this.createdAt = LocalDateTime.now();
    }

    public boolean admin() {
        return isAdmin != null && isAdmin == 1;
    }

    public boolean active() {
        return enabled != null && enabled == 1;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLoginId() {
        return loginId;
    }

    public void setLoginId(String loginId) {
        this.loginId = loginId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getIsAdmin() {
        return isAdmin;
    }

    public void setIsAdmin(Integer isAdmin) {
        this.isAdmin = isAdmin;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
