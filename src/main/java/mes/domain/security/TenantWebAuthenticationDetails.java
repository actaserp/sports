package mes.domain.security;

import org.springframework.security.web.authentication.WebAuthenticationDetails;

import javax.servlet.http.HttpServletRequest;

public class TenantWebAuthenticationDetails extends WebAuthenticationDetails {

    private final String spjangcd;

    public TenantWebAuthenticationDetails(HttpServletRequest request) {
        super(request);
        // 폼 hidden 필드에서만 읽음 (비어있으면 Main DB 로그인으로 처리)
        String param = request.getParameter("spjangcd");
        this.spjangcd = (param != null && !param.isBlank()) ? param : null;
    }

    public String getSpjangcd() {
        return spjangcd;
    }
}
