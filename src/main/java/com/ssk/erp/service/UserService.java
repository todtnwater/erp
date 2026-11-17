package com.ssk.erp.service;

import com.ssk.erp.config.PasswordHasher;
import com.ssk.erp.dao.UserDAO;
import com.ssk.erp.dto.LoginUser;
import com.ssk.erp.dto.UserDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {

    private static final int MAX_FAILS = 5;
    private static final int LOCK_MINUTES = 5;

    /** 아이디별 로그인 실패 횟수 (5번 틀리면 5분 잠금) */
    private record Fail(int count, LocalDateTime lockedUntil) {
    }

    private final UserDAO userDAO;
    private final Map<String, Fail> fails = new ConcurrentHashMap<>();

    public UserService(UserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Transactional
    public LoginUser authenticate(String loginId, String password) {
        String id = loginId == null ? "" : loginId.trim().toLowerCase();
        Fail f = fails.get(id);
        if (f != null && f.lockedUntil() != null && f.lockedUntil().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("로그인 실패가 " + MAX_FAILS + "회 넘어 " + LOCK_MINUTES + "분간 잠겼습니다.");
        }
        UserDTO user = id.isEmpty() ? null : userDAO.findByLoginId(id);
        if (user == null || !PasswordHasher.matches(password, user.getPasswordHash())) {
            int count = (f == null ? 0 : f.count()) + 1;
            fails.put(id, new Fail(count, count >= MAX_FAILS ? LocalDateTime.now().plusMinutes(LOCK_MINUTES) : null));
            throw new IllegalArgumentException("아이디 또는 비밀번호가 맞지 않습니다.");
        }
        if (!user.active()) {
            // 비밀번호까지 맞은 경우에만 알려 준다 (아이디 존재 여부를 남에게 흘리지 않도록)
            throw new IllegalStateException("관리자 승인 대기 중이거나 사용이 중지된 계정입니다. 관리자에게 문의하세요.");
        }
        fails.remove(id);
        userDAO.updateLastLogin(user.getId(), LocalDateTime.now());
        return new LoginUser(user.getId(), user.getLoginId(), user.getName(), user.admin());
    }

    public List<UserDTO> list() {
        return userDAO.findAll();
    }

    /** 관리자가 만드는 계정: 바로 사용 가능 */
    @Transactional
    public UserDTO create(String loginId, String name, String password, boolean admin) {
        return insertUser(loginId, name, password, admin ? 1 : 0, 1);
    }

    /** 회원가입: 무조건 현장 직원(is_admin = 0) + 승인 대기(enabled = 0). 관리자가 승인해야 로그인 가능 */
    @Transactional
    public UserDTO signup(String loginId, String name, String password, String passwordConfirm) {
        if (password == null || !password.equals(passwordConfirm)) {
            throw new IllegalArgumentException("비밀번호 확인이 일치하지 않습니다.");
        }
        return insertUser(loginId, name, password, 0, 0);
    }

    /** 내 정보 수정: 이름, 비밀번호 (현재 비밀번호 확인 필수). 권한은 본인이 못 바꿈 */
    @Transactional
    public LoginUser updateProfile(LoginUser me, String name, String currentPassword,
                                   String newPassword, String newPasswordConfirm) {
        UserDTO user = me == null ? null : userDAO.findById(me.id());
        if (user == null) {
            throw new IllegalStateException("로그인이 필요합니다.");
        }
        if (!PasswordHasher.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("현재 비밀번호가 맞지 않습니다.");
        }
        String n = name == null ? "" : name.trim();
        validateName(n);
        if (!n.equals(user.getName())) {
            userDAO.updateName(user.getId(), n);
        }
        boolean changePw = newPassword != null && !newPassword.isEmpty();
        if (changePw) {
            if (!newPassword.equals(newPasswordConfirm)) {
                throw new IllegalArgumentException("새 비밀번호 확인이 일치하지 않습니다.");
            }
            validatePassword(newPassword);
            userDAO.updatePassword(user.getId(), PasswordHasher.hash(newPassword));
        }
        return new LoginUser(user.getId(), user.getLoginId(), n, user.admin());
    }

    private UserDTO insertUser(String loginId, String name, String password, int isAdmin, int enabled) {
        String id = loginId == null ? "" : loginId.trim().toLowerCase();
        String n = name == null ? "" : name.trim();
        if (!id.matches("[a-z0-9_]{3,20}")) {
            throw new IllegalArgumentException("아이디는 영문 소문자·숫자·_ 3~20자입니다.");
        }
        validateName(n);
        validatePassword(password);
        if (userDAO.findByLoginId(id) != null) {
            throw new IllegalArgumentException("이미 있는 아이디입니다: " + id);
        }
        UserDTO user = new UserDTO(id, PasswordHasher.hash(password), n, isAdmin);
        user.setEnabled(enabled);
        userDAO.insert(user);
        return user;
    }

    private void validateName(String n) {
        if (n.isEmpty() || n.length() > 30) {
            throw new IllegalArgumentException("이름은 1~30자입니다.");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new IllegalArgumentException("비밀번호는 8~64자입니다.");
        }
    }

    /** 관리자 권한 키(is_admin) 1 ↔ 0 전환. 자기 자신, 마지막 관리자는 내릴 수 없음 */
    @Transactional
    public UserDTO toggleAdmin(Long targetId, LoginUser me) {
        UserDTO target = userDAO.findById(targetId);
        if (target == null) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        }
        if (target.getId().equals(me.id())) {
            throw new IllegalStateException("자기 자신의 권한은 바꿀 수 없습니다.");
        }
        if (target.admin() && userDAO.countAdmins() <= 1) {
            throw new IllegalStateException("마지막 관리자의 권한은 내릴 수 없습니다.");
        }
        int next = target.admin() ? 0 : 1;
        userDAO.updateAdmin(targetId, next);
        target.setIsAdmin(next);
        return target;
    }

    @Transactional
    public UserDTO toggleEnabled(Long targetId, LoginUser me) {
        UserDTO target = userDAO.findById(targetId);
        if (target == null) {
            throw new IllegalArgumentException("사용자를 찾을 수 없습니다.");
        }
        if (target.getId().equals(me.id())) {
            throw new IllegalStateException("자기 자신은 사용 중지할 수 없습니다.");
        }
        if (target.admin() && target.active() && userDAO.countAdmins() <= 1) {
            throw new IllegalStateException("마지막 관리자는 사용 중지할 수 없습니다.");
        }
        int next = target.active() ? 0 : 1;
        userDAO.updateEnabled(targetId, next);
        target.setEnabled(next);
        return target;
    }

    /**
     * 화면을 보고 있는 동안 관리자가 권한을 바꿨을 수 있으므로,
     * 입출고·승인처럼 중요한 처리 직전에 DB의 최신 권한으로 다시 확인한다.
     */
    public LoginUser refresh(LoginUser me) {
        if (me == null) {
            throw new IllegalStateException("로그인이 필요합니다.");
        }
        UserDTO user = userDAO.findById(me.id());
        if (user == null || !user.active()) {
            throw new IllegalStateException("사용이 중지된 계정입니다. 다시 로그인해 주세요.");
        }
        return new LoginUser(user.getId(), user.getLoginId(), user.getName(), user.admin());
    }
}
