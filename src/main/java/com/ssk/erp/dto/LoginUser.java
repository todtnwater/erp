package com.ssk.erp.dto;

import java.io.Serializable;

/** 세션에 저장하는 로그인 사용자 정보 (비밀번호 해시는 넣지 않음) */
public record LoginUser(Long id, String loginId, String name, boolean admin) implements Serializable {

    /** AI 서비스·검증 로직에서 쓰는 권한 이름 */
    public String role() {
        return admin ? "MANAGER" : "STAFF";
    }

    public String roleLabel() {
        return admin ? "관리자" : "현장 직원";
    }
}
