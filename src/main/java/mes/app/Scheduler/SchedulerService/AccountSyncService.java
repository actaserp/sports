package mes.app.Scheduler.SchedulerService;

import com.popbill.api.EasyFinBankService;
import com.popbill.api.PopbillException;
import com.popbill.api.easyfin.EasyFinBankJobState;
import com.popbill.api.easyfin.EasyFinBankSearchDetail;
import com.popbill.api.easyfin.EasyFinBankSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.PopBill.service.EasyFinBankAccountQueryService;
import mes.app.common.TenantContext;
import mes.app.util.UtilClass;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountSyncService {

    private final SqlRunner sqlRunner;
    private final EasyFinBankService easyFinBankService;
    private final EasyFinBankAccountQueryService easyFinBankAccountQueryService;

    // ✅ 메인 PostgreSQL DB 전용 SqlRunner
    @Qualifier("mainSqlRunner")
    private final SqlRunner mainSqlRunner;

    public void run() {

        log.info("[{}] 시작 - Thread: {}", "계좌수집", Thread.currentThread().getName());
        LocalDateTime startTime = LocalDateTime.now();

        // ✅ 1단계: 메인 DB의 tb_tenant_db에서 사업장코드(dbKey) 목록 조회
        List<Map<String, Object>> tenantList = getTenantList();

        for (Map<String, Object> tenant : tenantList) {
            // ✅ dbKey = spjangcd (db_alias='main'이면 spjangcd 그대로 라우팅 키)
            String dbKey = UtilClass.getStringSafe(tenant.get("spjangcd"));

            if (StringUtils.isEmpty(dbKey)) continue;

            // ✅ 2단계: 테넌트 DB 연결 세팅
            TenantContext.setDbKey(dbKey);

            try {
                // ✅ 3단계: 테넌트 DB의 tb_xa012에서 spjangcd(SQL 필터용) 목록 조회
                List<Map<String, Object>> spjangcdList = getSpjangcdListFromTenant();

                for (Map<String, Object> spjangRow : spjangcdList) {
                    String spjangcd = UtilClass.getStringSafe(spjangRow.get("spjangcd"));

                    if (StringUtils.isEmpty(spjangcd)) continue;

                    // ✅ 4단계: SQL 필터용 spjangcd 세팅
                    TenantContext.set(spjangcd);

                    try {
                        // ✅ 5단계: tb_aa040에서 popflag='1' 계좌 목록 조회
                        List<Map<String, Object>> accountList = getAccountList();

                        for (Map<String, Object> account : accountList) {
                            String custcd   = UtilClass.getStringSafe(account.get("custcd"));
                            String bank     = UtilClass.getStringSafe(account.get("bank")).trim();
                            String bankcd   = UtilClass.getStringSafe(account.get("bankcd"));
                            String bankname = UtilClass.getStringSafe(account.get("banknm"));

                            try {
                                // ✅ saupnum 조회
                                Map<String, String> bizInfo = easyFinBankAccountQueryService.getBizInfoBySpjangcd(spjangcd);
                                String saupnum = bizInfo.get("saupnum");

                                if (StringUtils.isEmpty(saupnum)) {
                                    log.warn("사업자번호 없음. spjangcd={}, custcd={}", spjangcd, custcd);
                                    continue;
                                }

                                String corpNum = saupnum.replaceAll("-", "");

                                // ✅ 계좌 정보 조회
                                Map<String, Object> accInfo = easyFinBankAccountQueryService.getAccountInfo(custcd, bank, bankcd);
                                if (accInfo == null) {
                                    log.warn("계좌 정보 없음. custcd={}, bank={}, bankcd={}", custcd, bank, bankcd);
                                    continue;
                                }

                                String plainAccountNum = UtilClass.getStringSafe(accInfo.get("accnum")).replaceAll("-", "");
                                String popBillBankCode = String.format("%04d", Integer.parseInt(bank));
                                String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                                // ✅ 팝빌 수집 요청
                                String jobId;
                                try {
                                    jobId = easyFinBankService.requestJob(corpNum, popBillBankCode, plainAccountNum, today, today);
                                } catch (PopbillException e) {
                                    log.error("팝빌 수집 요청 실패: {}, custcd={}, bank={}", e.getMessage(), custcd, bank);
                                    continue;
                                }

                                // ✅ 완료 대기
                                String jobState = waitForJobComplete(corpNum, jobId);
                                if (!"3".equals(jobState)) {
                                    log.warn("수집 미완료. jobState={}, custcd={}, bank={}", jobState, custcd, bank);
                                    continue;
                                }

                                // ✅ 거래내역 조회
                                EasyFinBankSearchResult searchResult = easyFinBankService.search(
                                  corpNum, jobId, null, null, null, null, null);

                                if (searchResult.getCode() != 1) {
                                    log.error("팝빌 검색 실패: {}, custcd={}, bank={}", searchResult.getMessage(), custcd, bank);
                                    continue;
                                }

                                List<EasyFinBankSearchDetail> list = searchResult.getList();

                                // ✅ requestJob과 동일한 저장 호출
                                easyFinBankAccountQueryService.saveBankDataAsync(
                                  list,
                                  jobId,
                                  plainAccountNum,
                                  custcd,
                                  bank,
                                  bankcd,
                                  bankname,
                                  spjangcd
                                );

                                log.info("수집 완료. dbKey={}, spjangcd={}, custcd={}, bank={}", dbKey, spjangcd, custcd, bank);

                            } catch (Exception e) {
                                log.error("계좌 수집 예외: {}, custcd={}, bank={}", e.getMessage(), custcd, bank);
                            }
                        }

                    } finally {
                        // ✅ spjangcd(SQL 필터) clear
                        TenantContext.set(null);
                    }
                }

            } catch (Exception e) {
                log.error("테넌트 처리 예외: {}, dbKey={}", e.getMessage(), dbKey);
            } finally {
                // ✅ dbKey(DB 라우팅) clear
                TenantContext.clear();
            }
        }

        LocalDateTime endTime = LocalDateTime.now();
        log.info("[계좌수집] 완료 - 소요시간: {}초", Duration.between(startTime, endTime).getSeconds());
    }

    // ✅ 메인 DB에서 테넌트 목록 조회 (db_alias='main'만)
    private List<Map<String, Object>> getTenantList() {
        String sql = """
            SELECT spjangcd
              FROM tb_tenant_db
             WHERE db_alias = 'main'
        """;
        try {
            return mainSqlRunner.getRows(sql, new MapSqlParameterSource());
        } catch (Exception e) {
            log.error("테넌트 목록 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    // ✅ 테넌트 DB의 tb_xa012에서 spjangcd(SQL 필터용) 목록 조회
    private List<Map<String, Object>> getSpjangcdListFromTenant() {
        String sql = """
            SELECT spjangcd
              FROM tb_xa012
        """;
        try {
            return sqlRunner.getRows(sql, new MapSqlParameterSource());
        } catch (Exception e) {
            log.error("spjangcd 목록 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    // ✅ 테넌트 DB의 tb_aa040에서 popflag='1' 계좌 조회
    private List<Map<String, Object>> getAccountList() {
        String sql = """
            SELECT custcd    AS "custcd",
                   bank      AS "bank",
                   bankcd    AS "bankcd",
                   banknm    AS "banknm",
                   spjangcd  AS "spjangcd"
              FROM tb_aa040
             WHERE popflag = '1'
        """;
        try {
            return sqlRunner.getRows(sql, new MapSqlParameterSource());
        } catch (Exception e) {
            log.error("tb_aa040 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private String waitForJobComplete(String corpNum, String jobId) {
        final int maxRetry = 10;
        final int interval = 1000;

        try {
            for (int i = 0; i < maxRetry; i++) {
                EasyFinBankJobState jobState = easyFinBankService.getJobState(corpNum, jobId);
                String code      = jobState.getJobState();
                long   errorCode = jobState.getErrorCode();

                if (errorCode != 1 && errorCode != 0) {
                    log.warn("에러코드 발생: {}, jobId={}", errorCode, jobId);
                    return "에러발생";
                }

                if ("3".equals(code)) {
                    log.info("수집완료 jobId={}", jobId);
                    return "3";
                }

                Thread.sleep(interval);
            }
        } catch (PopbillException e) {
            log.error("팝빌 상태 확인 예외: {}, jobId={}", e.getMessage(), jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("waitForJobComplete interrupted, jobId={}", jobId);
        }

        log.warn("수집 타임아웃 jobId={}", jobId);
        return "TIMEOUT";
    }
}