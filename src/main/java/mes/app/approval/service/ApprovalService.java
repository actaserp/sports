package mes.app.approval.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ApprovalService {

    @Autowired
    SqlRunner sqlRunner;

    //결재라인등록 그리드 리스트 불러오기
    public List<Map<String, Object>> getCheckPaymentList(Integer personid, String papercd, String spjangcd, String comcd) {

        // 1. tenant DB에서 personid(PK)로 Code(사번) 조회
        String personSql = """
        SELECT Code AS personCode 
        FROM person 
        WHERE id = :pid 
        AND spjangcd = :spjangcd
    """;
        MapSqlParameterSource personParam = new MapSqlParameterSource();
        personParam.addValue("pid", personid);
        personParam.addValue("spjangcd", spjangcd);

        Map<String, Object> personRow = sqlRunner.getRow(personSql, personParam);
        String personCode = null;
        if (personRow != null) {
            String code = (String) personRow.get("personCode");
            personCode = code != null ? code.replaceFirst("^p", "") : null; // p 제거
        }

        // 2. personCode로 기존 쿼리 실행
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("perid", personCode); // ✅ personid → personCode 로 교체

        String sql = """
          SELECT
              e.perid,
              e.no,
              e.kcperid,
              a.pernm  AS kcpernm,
              e.papercd,
              c.com_cnam AS papernm,
              s.Value AS gubunnm,
              e.seq
          FROM TB_E064 e
          LEFT JOIN tb_ja001 a
              ON a.spjangcd = e.spjangcd
              AND a.perid = 'p' + e.kcperid
          LEFT JOIN tb_ca510 c
              ON c.com_cls = '620'
              AND c.com_code = e.papercd
              AND c.com_code <> '00'
          LEFT JOIN sys_code s
              ON e.gubun = s."Code"
              AND s."CodeType" = 'Payment'
          WHERE e.perid = :perid
            """;

        if (papercd != null && !papercd.isEmpty()) {
            dicParam.addValue("papercd", papercd);
            sql += " AND e.papercd = :papercd";
        }
        if (comcd != null && !comcd.isEmpty()) {
            dicParam.addValue("comcd", comcd);
            sql += " and e.papercd = :comcd";
        }
        if (spjangcd != null && !spjangcd.isEmpty()) {
            dicParam.addValue("spjangcd", spjangcd);
            sql += " AND e.spjangcd = :spjangcd";
        }

        sql += " ORDER BY e.seq ASC";

        return this.sqlRunner.getRows(sql, dicParam);
    }

    //결재라인등록 사원 그리드 리스트 불러오기
    public List<Map<String, Object>> getListPapercd(String papercd, String spjangcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                select
                e.*,
                s."Value" as papernm,
                p."Name"
                from TB_E063 e
                LEFT JOIN sys_code s ON e.papercd = s."Code"
                LEFT JOIN person p ON e.personid = p.id
                WHERE 1=1
                AND s."CodeType" = 'appr_doc'
                """;
            dicParam.addValue("papercd", papercd);
            sql += " AND e.papercd = :papercd";
        if(spjangcd != null && !spjangcd.isEmpty()) {
            dicParam.addValue("spjangcd", spjangcd);
            sql += " AND e.spjangcd = :spjangcd";
        }
        sql += " order by e.papercd ASC";
        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    // 문서코드 옵션 불러오기
    public List<Map<String, Object>> getComcd() {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                SELECT com_cls,
                         com_code,
                       com_cls + com_code as asmc,
                         com_cnam,
                         com_rem1,
                         com_rem2,
                         com_order
                    FROM tb_ca510
                 WHERE com_cls = '620'
                   AND com_code <> '00'
                
                """;

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }
    // 문서에 따른 결재자 옵션 불러오기
    public List<Map<String, Object>> getKcperid() {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
                SELECT Right(perid, Len(perid) - 1) cd  ,
                 pernm           cdnm,
                 b.divinm        arg2
                  FROM TB_JA001 WITH(NOLOCK)
                 join TB_JC002 b ON  b.divicd = tb_ja001.divicd and b.spjangcd = tb_ja001.spjangcd
                 WHERE rtclafi = '001'
                """;

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }
    // 공통코드 구분 옵션 조회
    public String getGubuncd(String gubuncd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                SELECT Value
                FROM user_code
                WHERE Parent_id = 333
                AND Code = :gubuncd
                """;
        dicParam.addValue("gubuncd", gubuncd);
        Map<String, Object> userInfo = this.sqlRunner.getRow(sql, dicParam);
        String gubunnm = (String) userInfo.get("Value");
        return gubunnm;
    }

    // username으로 cltcd, cltnm, saupnum, custcd 가지고 오기
    public Map<String, Object> getUserInfo(String username) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                select agencycd as perid, -- 사원 코드
                       first_name as pernm
                FROM auth_user
                WHERE username = :username
                """;
        dicParam.addValue("username", username);
        Map<String, Object> userInfo = this.sqlRunner.getRow(sql, dicParam);
        return userInfo;
    }

    /**
     * 결재라인 삭제
     * - TB_E064 해당 행 삭제
     * - 삭제 후 해당 063 키에 064 데이터가 없으면 TB_E063도 함께 삭제
     */
    public void deleteApprovalLine(Map<String, String> params) {

        String perid   = params.get("perid");
        String papercd = params.get("papercd");
        String no      = params.get("no");
        String kcperid = params.get("kcperid");

        // 1. TB_E064 삭제
        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        paramSource.addValue("perid",   perid);
        paramSource.addValue("papercd", papercd);
        paramSource.addValue("no",      no);
        paramSource.addValue("kcperid", kcperid);

        String deleteSql = """
        DELETE FROM TB_E064
        WHERE perid   = :perid
          AND papercd = :papercd
          AND no      = :no
          AND kcperid = :kcperid
        """;

        try {
            this.sqlRunner.execute(deleteSql, paramSource);
           // log.info("[TB_E064 삭제] perid={}, papercd={}, no={}, kcperid={}", perid, papercd, no, kcperid);
        } catch (Exception e) {
            log.error("[TB_E064 삭제 실패] perid={}, papercd={}, no={}, kcperid={}", perid, papercd, no, kcperid, e);
            throw e;
        }

        // 2. TB_E064 잔여 행 확인 (모든 행 삭제됐으면 TB_E063도 삭제)
        String countSql = """
        SELECT COUNT(*) AS cnt
        FROM TB_E064
        WHERE perid   = :perid
          AND papercd = :papercd
          AND no      = :no
        """;

        List<Map<String, Object>> countResult = this.sqlRunner.getRows(countSql, paramSource);
        int remaining = ((Number) countResult.get(0).get("cnt")).intValue();

        if (remaining == 0) {
            String deleteHeadSql = """
            DELETE FROM TB_E063
            WHERE perid   = :perid
              AND papercd = :papercd
            """;

            try {
                this.sqlRunner.execute(deleteHeadSql, paramSource);
               // log.info("[TB_E063 삭제] perid={}, papercd={}", perid, papercd);
            } catch (Exception e) {
                log.error("[TB_E063 삭제 실패] perid={}, papercd={}", perid, papercd, e);
                throw e;
            }
        }
    }

    public Map<String, Object> getCheckPaymentDetail(String no, String papercd, String perid, String spjangcd) {
        String sql = """
        SELECT
            e.no,
            e.kcperid,
            a.pernm AS kcpernm,
            e.gubun,
            s.Value AS gubunnm,
            e.seq,
            e.papercd,
            e.perid
        FROM TB_E064 e
        LEFT JOIN tb_ja001 a 
            ON a.spjangcd = e.spjangcd 
            AND a.perid = 'p' + e.perid
        LEFT JOIN sys_code s 
            ON e.gubun = s."Code" 
            AND s."CodeType" = 'Payment'
        WHERE e.no = :no
          AND e.perid = :perid
          AND e.papercd = :papercd
          AND e.spjangcd = :spjangcd
    """;
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("no", no);
        params.addValue("perid", perid);
        params.addValue("papercd", papercd);
        params.addValue("spjangcd", spjangcd);

        return sqlRunner.getRow(sql, params);
    }

    /**
     * 결재라인 저장 (TB_E063 + TB_E064)
     */
    public void saveApprovalLine(Map<String, Object> params) {

        String spjangcd  = (String) params.get("spjangcd");
        String custcd    = (String) params.get("custcd");
        Integer personid = (Integer) params.get("personid");
        String papercd   = (String) params.get("papercd");

        // 1. personid → personCode(사번) 조회
        MapSqlParameterSource personParam = new MapSqlParameterSource();
        personParam.addValue("pid",      personid);
        personParam.addValue("spjangcd", spjangcd);

        String personSql = """
        SELECT Code AS personCode
        FROM person
        WHERE id       = :pid
          AND spjangcd = :spjangcd
        """;

        Map<String, Object> personRow = this.sqlRunner.getRow(personSql, personParam);
        String personCode = null;
        if (personRow != null) {
            String code = (String) personRow.get("personCode");
            personCode = code != null ? code.replaceFirst("^p", "") : null;
        }

        if (personCode == null) {
            //log.error("[결재라인 저장 실패] personCode 조회 불가 - personid={}, spjangcd={}", personid, spjangcd);
            throw new IllegalArgumentException("사번 정보를 찾을 수 없습니다. personid=" + personid);
        }

        // 2. kcpersonid → kcperid(사번) 조회
        String kcpersonidStr = String.valueOf(params.get("kcpersonid"));
        Integer kcpersonid = Integer.valueOf(kcpersonidStr);

        MapSqlParameterSource kcPersonParam = new MapSqlParameterSource();
        kcPersonParam.addValue("pid",      kcpersonid);
        kcPersonParam.addValue("spjangcd", spjangcd);

        Map<String, Object> kcPersonRow = this.sqlRunner.getRow(personSql, kcPersonParam);
        String kcperid = null;
        if (kcPersonRow != null) {
            String code = (String) kcPersonRow.get("personCode");
            kcperid = code != null ? code.replaceFirst("^p", "") : null;
        }

        if (kcperid == null) {
            log.error("[결재라인 저장 실패] kcperid 조회 불가 - kcpersonid={}, spjangcd={}", kcpersonid, spjangcd);
            throw new IllegalArgumentException("결재자 사번 정보를 찾을 수 없습니다. kcpersonid=" + kcpersonid);
        }

        //log.info("[사번 조회 완료] personCode={}, kcperid={}", personCode, kcperid);

        // 3. 파라미터 구성
        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        paramSource.addValue("spjangcd", spjangcd);
        paramSource.addValue("custcd",   custcd);
        paramSource.addValue("papercd",  papercd);
        paramSource.addValue("perid",    personCode);  // personid → 사번
        paramSource.addValue("kcperid",  kcperid);     // kcpersonid → 사번
        paramSource.addValue("gubun",    params.get("gubun"));
        paramSource.addValue("seq",      params.get("seq"));
        paramSource.addValue("no",       params.get("no"));
        paramSource.addValue("indate",   params.get("indate"));

        // 4. TB_E063 중복 체크
        String checkSql = """
        SELECT COUNT(*) AS cnt
        FROM TB_E063
        WHERE spjangcd = :spjangcd
          AND papercd  = :papercd
          AND perid    = :perid
        """;

        List<Map<String, Object>> checkResult = this.sqlRunner.getRows(checkSql, paramSource);
        int count = ((Number) checkResult.get(0).get("cnt")).intValue();

        // 5. TB_E063 INSERT (없을 때만)
        if (count == 0) {
            String insertHeadSql = """
            INSERT INTO TB_E063 (custcd, spjangcd, papercd, perid)
            VALUES (:custcd, :spjangcd, :papercd, :perid)
            """;
            try {
                this.sqlRunner.execute(insertHeadSql, paramSource);
            } catch (Exception e) {
                log.error("[TB_E063 저장 실패] custcd={}, spjangcd={}, papercd={}, perid={}",
                  custcd, spjangcd, papercd, personCode, e);
                throw e;
            }
        }

        // 6. TB_E064 INSERT
        String insertBodySql = """
        INSERT INTO TB_E064 (
            custcd, spjangcd, papercd, no, perid,
            seq, kcperid, gubun, indate
        ) VALUES (
            :custcd, :spjangcd, :papercd, :no, :perid,
            :seq, :kcperid, :gubun, :indate
        )
        """;
        try {
            this.sqlRunner.execute(insertBodySql, paramSource);
        } catch (Exception e) {
            log.error("[TB_E064 저장 실패] custcd={}, spjangcd={}, papercd={}, no={}, perid={}, kcperid={}",
              custcd, spjangcd, papercd, params.get("no"), personCode, kcperid, e);
            throw e;
        }
    }

    /**
     * TB_E064 no 컬럼 Max + 1 조회
     */
    public String getNextNo(String spjangcd, Integer personid, String papercd) {

        // personid → personCode 변환
        MapSqlParameterSource personParam = new MapSqlParameterSource();
        personParam.addValue("pid",      personid);
        personParam.addValue("spjangcd", spjangcd);

        String personSql = """
        SELECT Code AS personCode
        FROM person
        WHERE id       = :pid
          AND spjangcd = :spjangcd
        """;

        Map<String, Object> personRow = this.sqlRunner.getRow(personSql, personParam);
        String personCode = null;
        if (personRow != null) {
            String code = (String) personRow.get("personCode");
            personCode = code != null ? code.replaceFirst("^p", "") : null;
        }

        if (personCode == null) {
            log.warn("[getNextNo] personCode 조회 불가 - personid={}, spjangcd={}", personid, spjangcd);
            return "1";
        }

        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        paramSource.addValue("spjangcd", spjangcd);
        paramSource.addValue("perid",    personCode);
        paramSource.addValue("papercd",  papercd);

        String sql = """
        SELECT COALESCE(MAX(CAST(no AS INT)), 0) + 1 AS nextno
        FROM TB_E064
        WHERE spjangcd = :spjangcd
          AND perid    = :perid
          AND papercd  = :papercd
        """;

        List<Map<String, Object>> result = this.sqlRunner.getRows(sql, paramSource);

        if (result != null && !result.isEmpty() && result.get(0).get("nextno") != null) {
            return String.valueOf(result.get(0).get("nextno"));
        }
        return "1";
    }

    public Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
        MapSqlParameterSource sqlParam = new MapSqlParameterSource();
        sqlParam.addValue("spjangcd", spjangcd);

        String sql = """
        select saupnum, custcd, spjangnm
        from tb_xa012
        where spjangcd = :spjangcd
    """;

        Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

        Map<String, String> result = new HashMap<>();
        result.put("saupnum", "");
        result.put("custcd", "");
        result.put("spjangnm", "");

        if (row == null || row.isEmpty()) {
            return result;
        }

        Object saupnum = row.get("saupnum");
        Object custcd = row.get("custcd");
        Object spjangnm = row.get("spjangnm");

        result.put("saupnum", saupnum == null ? "" : String.valueOf(saupnum).trim());
        result.put("custcd", custcd == null ? "" : String.valueOf(custcd).trim());
        result.put("spjangnm", custcd == null ? "" : String.valueOf(spjangnm).trim());

        return result;
    }
}
