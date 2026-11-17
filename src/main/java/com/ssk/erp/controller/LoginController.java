package com.ssk.erp.controller;

import com.ssk.erp.config.AuthInterceptor;
import com.ssk.erp.dto.LoginUser;
import com.ssk.erp.dto.UserDTO;
import com.ssk.erp.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 로그인 / 로그아웃 / 회원가입 / 내 정보 / 사용자 관리(관리자 전용) */
@Controller
public class LoginController {

    private final UserService users;

    public LoginController(UserService users) {
        this.users = users;
    }

    @GetMapping("/login")
    public String loginForm(HttpServletRequest request) {
        if (AuthInterceptor.current(request) != null) {
            return "redirect:/";
        }
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam(defaultValue = "") String loginId,
                        @RequestParam(defaultValue = "") String password,
                        HttpServletRequest request,
                        RedirectAttributes ra) {
        try {
            LoginUser user = users.authenticate(loginId, password);
            HttpSession old = request.getSession(false);
            if (old != null) {
                old.invalidate();
            }
            HttpSession session = request.getSession(true);
            session.setMaxInactiveInterval(60 * 60 * 8);
            session.setAttribute(AuthInterceptor.SESSION_KEY, user);
            return "redirect:/";
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            ra.addFlashAttribute("loginId", loginId);
            return "redirect:/login";
        }
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return "redirect:/login";
    }

    /** 회원가입: 승인 대기(enabled = 0) 직원 계정으로 만들어짐 */
    @GetMapping("/signup")
    public String signupForm(HttpServletRequest request) {
        if (AuthInterceptor.current(request) != null) {
            return "redirect:/";
        }
        return "signup";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam(defaultValue = "") String loginId,
                         @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String password,
                         @RequestParam(defaultValue = "") String passwordConfirm,
                         RedirectAttributes ra) {
        try {
            UserDTO u = users.signup(loginId, name, password, passwordConfirm);
            ra.addFlashAttribute("flash", u.getLoginId() + " 가입 신청이 접수됐습니다. 관리자가 승인하면 로그인할 수 있습니다.");
            return "redirect:/login";
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            ra.addFlashAttribute("loginId", loginId);
            ra.addFlashAttribute("name", name);
            return "redirect:/signup";
        }
    }

    /** 내 정보 수정 */
    @GetMapping("/me")
    public String myInfo() {
        return "me";
    }

    @PostMapping("/me")
    public String updateMyInfo(@RequestParam(defaultValue = "") String name,
                               @RequestParam(defaultValue = "") String currentPassword,
                               @RequestParam(defaultValue = "") String newPassword,
                               @RequestParam(defaultValue = "") String newPasswordConfirm,
                               HttpServletRequest request,
                               RedirectAttributes ra) {
        try {
            LoginUser updated = users.updateProfile(AuthInterceptor.current(request), name, currentPassword,
                    newPassword, newPasswordConfirm);
            request.getSession().setAttribute(AuthInterceptor.SESSION_KEY, updated);
            ra.addFlashAttribute("flash", "내 정보를 저장했습니다." + (newPassword.isEmpty() ? "" : " 비밀번호도 변경됐습니다."));
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/me";
    }

    /** 사용자 관리 (AuthInterceptor 가 is_admin = 1 만 통과시킴) */
    @GetMapping("/users")
    public String userList(Model model) {
        model.addAttribute("users", users.list());
        return "users";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam(defaultValue = "") String loginId,
                             @RequestParam(defaultValue = "") String name,
                             @RequestParam(defaultValue = "") String password,
                             @RequestParam(defaultValue = "0") int isAdmin,
                             RedirectAttributes ra) {
        try {
            UserDTO u = users.create(loginId, name, password, isAdmin == 1);
            ra.addFlashAttribute("flash", u.getName() + "(" + u.getLoginId() + ") 계정을 " + (u.admin() ? "관리자" : "현장 직원") + "로 만들었습니다.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/users/{id}/admin")
    public String toggleAdmin(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        try {
            UserDTO u = users.toggleAdmin(id, AuthInterceptor.current(request));
            ra.addFlashAttribute("flash", u.getName() + " 님을 " + (u.admin() ? "관리자로 지정" : "현장 직원으로 변경")
                    + "했습니다. 다음 처리부터 바로 적용됩니다.");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/users";
    }

    @PostMapping("/users/{id}/enabled")
    public String toggleEnabled(@PathVariable Long id, HttpServletRequest request, RedirectAttributes ra) {
        try {
            UserDTO u = users.toggleEnabled(id, AuthInterceptor.current(request));
            ra.addFlashAttribute("flash", u.getName() + " 계정을 " + (u.active() ? "승인했습니다. 이제 로그인할 수 있습니다." : "사용 중지했습니다."));
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/users";
    }
}
