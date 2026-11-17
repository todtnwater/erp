package com.ssk.erp.controller;

import com.ssk.erp.config.AuthInterceptor;
import com.ssk.erp.dto.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면(Thymeleaf)에 로그인 사용자 정보를 loginUser 로 넣어 준다 (사이드바·채팅 패널에서 사용) */
@ControllerAdvice(assignableTypes = {PageController.class, LoginController.class})
public class LoginAdvice {

    @ModelAttribute("loginUser")
    public LoginUser loginUser(HttpServletRequest request) {
        return AuthInterceptor.current(request);
    }
}
