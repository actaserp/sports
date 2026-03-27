package mes.domain.security;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.entity.UserGroup;
import mes.domain.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Slf4j
@Component
public class CustomAuthenticationManager implements AuthenticationManager {

    @Autowired
    private UserRepository userRepository;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {

        String username = authentication.getName();
        String password = authentication.getCredentials().toString();

        // 폼에서 넘어온 spjangcd 추출
        String spjangcd = null;
        if (authentication.getDetails() instanceof TenantWebAuthenticationDetails details) {
            spjangcd = details.getSpjangcd();
        }

        // 항상 Main DB에서 인증 — spjangcd(=db_key) 있으면 필터, 없으면 전체 조회 (관리자)
        User user;
        if (spjangcd != null && !spjangcd.isBlank()) {
            user = userRepository.findByUsernameAndDbKey(username, spjangcd)
                    .orElseThrow(() -> new UsernameNotFoundException(username));
        } else {
            user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException(username));
        }

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new BadCredentialsException("비활성화된 계정입니다.");
        }

        if (!Pbkdf2Sha256.verification(password, user.getPassword())) {
            throw new BadCredentialsException("비밀번호가 일치하지 않습니다.");
        }

        if (user.getUserProfile() == null) {
            throw new InsufficientAuthenticationException("UserProfile is null");
        }

        UserGroup group = user.getUserProfile().getUserGroup();
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(group.getCode());
        ArrayList<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(authority);

        log.info("로그인 성공: spjangcd={}, username={}", spjangcd != null ? spjangcd : "main", username);
        return new CustomAuthenticationToken(user, password, authorities);
    }
}
