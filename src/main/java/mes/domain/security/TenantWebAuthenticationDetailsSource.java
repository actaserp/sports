package mes.domain.security;

import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

@Component
public class TenantWebAuthenticationDetailsSource
        implements AuthenticationDetailsSource<HttpServletRequest, TenantWebAuthenticationDetails> {

    @Override
    public TenantWebAuthenticationDetails buildDetails(HttpServletRequest request) {
        return new TenantWebAuthenticationDetails(request);
    }
}
