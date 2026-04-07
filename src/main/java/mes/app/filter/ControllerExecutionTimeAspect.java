package mes.app.filter;


import lombok.Getter;
import mes.app.util.redis.RedisService;
import mes.domain.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;


@Component
public class ControllerExecutionTimeAspect implements Filter {

    Logger log = LoggerFactory.getLogger("API_EXEC_TIME_LOGGER");

    @Autowired
    RedisService redisService;

    @Value("${mes.project-name}")
    private String projectName;

    @Autowired
    @Qualifier("asyncExecutor")
    private Executor asyncExecutor;

    // ────────────────────────────────────────────
    // TeeOutputStream: 실제 스트림 + 카운터 스트림 동시 기록
    // ────────────────────────────────────────────
    private static class TeeOutputStream extends ServletOutputStream {
        private final OutputStream main;
        private final ByteArrayOutputStream counter;
        @Getter
        private long byteCount = 0;

        TeeOutputStream(OutputStream main){
            this.main = main;
            this.counter = new ByteArrayOutputStream(0); // 실제 버퍼링 X, 카운트만

        }

        @Override
        public void write(int b) throws IOException {
            main.write(b);
            byteCount++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            main.write(b, off, len);
            byteCount += len;
        }

        @Override
        public boolean isReady() {
            return ((ServletOutputStream) main).isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            ((ServletOutputStream) main).setWriteListener(writeListener);
        }
    }

    // ────────────────────────────────────────────
    // Response Wrapper
    // ────────────────────────────────────────────
    private static class ByteCountingResponseWrapper extends HttpServletResponseWrapper {
        private TeeOutputStream teeOutputStream;
        private PrintWriter writer;

        ByteCountingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (teeOutputStream == null) {
                teeOutputStream = new TeeOutputStream(super.getOutputStream());
            }
            return teeOutputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                teeOutputStream = new TeeOutputStream(super.getOutputStream());
                writer = new PrintWriter(new OutputStreamWriter(teeOutputStream, getCharacterEncoding()));
            }
            return writer;
        }

        /** body 바이트 */
        public long getBodyBytes() {
            if (writer != null) writer.flush();
            return teeOutputStream != null ? teeOutputStream.getByteCount() : 0;
        }

        /**
         * HTTP 응답 헤더 바이트 추정
         * Status-Line + 헤더 각 라인 + CRLF 구분자
         * ex) "HTTP/1.1 200 OK\r\n", "Content-Type: application/json\r\n", "\r\n"
         */
        public long getHeaderBytes() {
            // Status line: "HTTP/1.1 XXX Reason\r\n"
            long size = ("HTTP/1.1 " + getStatus() + " \r\n").length();

            Collection<String> headerNames = getHeaderNames();
            for (String name : headerNames) {
                for (String value : getHeaders(name)) {
                    // "Header-Name: value\r\n"
                    size += name.length() + 2 + value.length() + 2;
                }
            }
            size += 2; // 헤더 끝 빈 줄 \r\n
            return size;
        }

        /** 헤더 + 바디 합산 */
        public long getTotalOutboundBytes() {
            return getHeaderBytes() + getBodyBytes();
        }
    }
    // ────────────────────────────────────────────
    // 세션에서 User 추출
    // ───────────────────────────────────────────
    private User extractUser(HttpServletRequest req){
        HttpSession session = req.getSession(false);
        if(session == null) return null;

        SecurityContext ctx = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY
        );
        if(ctx == null) return null;

        Authentication auth = ctx.getAuthentication();
        if(auth == null) return null;

        Object principal = auth.getPrincipal();
        if(principal instanceof User) return (User) principal;
        return null;
    }

    // ────────────────────────────────────────────
    // 메인 필터
    // ────────────────────────────────────────────
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();

        if (uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }

        if (!uri.startsWith("/api")) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        final String finalUri = uri;

        ByteCountingResponseWrapper wrappedResponse = new ByteCountingResponseWrapper(res);

        try{
            chain.doFilter(request, wrappedResponse);
        } finally {

            long elapsed = System.currentTimeMillis() - start;

            long totalBytes  = wrappedResponse.getTotalOutboundBytes();

            User user = extractUser(req);  // ← 이거 빠져있었음

            if(user != null){
                String dbKey = user.getDbKey();
                String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String hashKey = projectName + ":STATS:" + dbKey + ":" + today;

                CompletableFuture.runAsync(() -> {
                    redisService.incrementHash(hashKey, "total_count", 1);
                    redisService.incrementHash(hashKey, "total_bytes", totalBytes);
                    redisService.incrementHash(hashKey, finalUri + ":count", 1);
                    redisService.incrementHash(hashKey, finalUri + ":bytes", totalBytes);
                    redisService.incrementHash(hashKey, finalUri + ":elapsed", elapsed);
                    redisService.expireIfAbsent(hashKey, 2, TimeUnit.DAYS);
                }, asyncExecutor);
            }

            //위에서 응답 바이트 측정하는 것보다 이거 로그찍는게 성능에 더 안좋아서 주석처리해놓음
//            log.info("[OUTBOUND] {} {} → {:.3f}s | header={}B body={}B total={}B",
//                    method, finalUri, seconds, headerBytes, bodyBytes, totalBytes);
        }

    }

    private boolean isExcludedPath(String uri){
        return uri.startsWith("/api/system/") || uri.startsWith("/api/common")
                || uri.contains("/pages") || uri.contains("/api/monitoring");
    }

}
