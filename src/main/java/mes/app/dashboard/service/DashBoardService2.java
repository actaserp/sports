package mes.app.dashboard.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashBoardService2 {
    @Autowired
    SqlRunner sqlRunner;

    // 사용자의 사업장코드 return
    public String getSpjangcd(String username
                            , String searchSpjangcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                SELECT spjangcd
                FROM auth_user
                WHERE username = :username
                """;
        dicParam.addValue("username", username);
        Map<String, Object> spjangcdMap = this.sqlRunner.getRow(sql, dicParam);
        String userSpjangcd = (String)spjangcdMap.get("spjangcd");

        String spjangcd = searchSpjangcd(searchSpjangcd, userSpjangcd);
        return spjangcd;
    }
    // 사용자 사원코드 조회(맨앞 'p'제거 필요)
    public String getPerid(String username) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                SELECT perid
                FROM tb_xusers
                WHERE userid = :username
                """;
        dicParam.addValue("username", username);
        Map<String, Object> perid = this.sqlRunner.getRow(sql, dicParam);
        String Perid = "";
        if(perid != null && perid.containsKey("perid")) {
            Perid = (String) perid.get("perid");
        }
        return Perid;
    }
    // init에 필요한 사업장코드 반환
    public String searchSpjangcd(String searchSpjangcd, String userSpjangcd){

        String resultSpjangcd = "";
        switch (searchSpjangcd){
            case "ZZ":
                resultSpjangcd = searchSpjangcd;
                break;
                case "PP":
                    resultSpjangcd= searchSpjangcd;
                    break;
                    default:
                        resultSpjangcd = userSpjangcd;
        }
        return resultSpjangcd;
    }

    // username으로 cltcd, cltnm, saupnum, custcd 가지고 오기
    public Map<String, Object> getUserInfo(String username) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        String sql = """
                select custcd,
                        cltcd,
                        cltnm
                FROM TB_XCLIENT
                WHERE saupnum = :username
                """;
        dicParam.addValue("username", username);
        Map<String, Object> userInfo = this.sqlRunner.getRow(sql, dicParam);
        return userInfo;
    }

    // 작년 진행구분(appgubun)별 데이터 개수
    public List<Map<String, Object>> LastYearCnt(String spjangcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
            WITH DateRanges AS (
               SELECT
                   CAST(YEAR(GETDATE()) - 1 AS CHAR(4)) + '0101' AS PrevYearStart, -- 전년도 1월 1일
                   CAST(YEAR(GETDATE()) - 1 AS CHAR(4)) + RIGHT(CONVERT(VARCHAR(8), GETDATE(), 112), 4) AS PrevYearEnd, -- 전년도 오늘 날짜
                   CAST(YEAR(GETDATE()) AS CHAR(4)) + '0101' AS ThisYearStart, -- 올해 1월 1일
                   CAST(YEAR(GETDATE()) AS CHAR(4)) + RIGHT(CONVERT(VARCHAR(8), GETDATE(), 112), 4) AS ThisYearEnd, -- 올해 오늘 날짜
                   CAST(YEAR(GETDATE()) AS CHAR(4)) + LEFT(CONVERT(VARCHAR(8), GETDATE(), 112), 6) + '01' AS ThisMonthStart, -- 올해 당월 1일
                   CAST(YEAR(GETDATE()) - 1 AS CHAR(4)) + LEFT(CONVERT(VARCHAR(8), GETDATE(), 112), 6) + '01' AS LastYearThisMonthStart -- 작년 당월 1일
            )
            SELECT
                   appgubun,
                   COUNT(*) AS TotalCount
               FROM TB_E080
               CROSS JOIN DateRanges
               WHERE
                   LEN(indate) = 8 AND                        -- 8자리 문자열인지 확인
                   indate LIKE '[0-9][0-9][0-9][0-9][0-1][0-9][0-3][0-9]' AND -- YYYYMMDD 형식인지 확인
                   CONVERT(DATE, indate, 112) BETWEEN CONVERT(DATE, PrevYearStart, 112) AND CONVERT(DATE, PrevYearEnd, 112)
                   AND (
                               (spjangcd = :spjangcd AND appperid = :appperid)
                               OR repoperid = :appperid
                           )
               GROUP BY appgubun
            """;
        dicParam.addValue("spjangcd", spjangcd);
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 올해 진행구분(appgubun)별 데이터 개수
    public List<Map<String, Object>> ThisYearCnt(String spjangcd, String perid) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
                 WITH DateRanges AS (
                               SELECT
                                   CAST(YEAR(GETDATE()) - 1 AS CHAR(4)) + '0101' AS PrevYearStart, -- 전년도 1월 1일
                                   CAST(YEAR(GETDATE()) - 1 AS CHAR(4)) + RIGHT(CONVERT(VARCHAR(8), GETDATE(), 112), 4) AS PrevYearEnd, -- 전년도 오늘 날짜
                                   CAST(YEAR(GETDATE()) AS CHAR(4)) + '0101' AS ThisYearStart, -- 올해 1월 1일
                                   CAST(YEAR(GETDATE()) AS CHAR(4)) + '1231' AS ThisYearEnd, -- 올해 오늘 날짜
                                   CAST(YEAR(GETDATE()) AS CHAR(4)) + LEFT(CONVERT(VARCHAR(8), GETDATE(), 112), 6) + '01' AS ThisMonthStart, -- 올해 당월 1일
                                   CAST(YEAR(GETDATE()) - 1 AS CHAR(4)) + LEFT(CONVERT(VARCHAR(8), GETDATE(), 112), 6) + '01' AS LastYearThisMonthStart -- 작년 당월 1일
                            )
                            SELECT
                                   appgubun,
                                   COUNT(*) AS TotalCount
                               FROM TB_E080
                               CROSS JOIN DateRanges
                               WHERE
                               CONVERT(DATE, repodate, 112) BETWEEN CONVERT(DATE, ThisYearStart, 112) AND CONVERT(DATE, ThisYearEnd, 112)
                               AND spjangcd = :spjangcd AND (appperid = :as_perid)
                               AND flag = '1'
                               GROUP BY appgubun
            """;
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("as_perid", perid);
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }

    // 결재요청받은(일별) 데이터
    public List<Map<String, Object>> ThisMonthResCntOfDate(String spjangcd, String as_perid) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
            WITH DateSeries AS (
                     SELECT
                         FORMAT(DATEADD(DAY, v.number, DATEFROMPARTS(YEAR(GETDATE()), MONTH(GETDATE()), 1)), 'yyyyMMdd') AS repodate
                     FROM master.dbo.spt_values v
                     WHERE v.type = 'P' AND v.number BETWEEN 0 AND 30
                 )
                 SELECT
                     d.repodate,
                     COUNT(CASE WHEN e.appgubun = '001' THEN 1 END) AS appgubun001,    -- appgubun = '001'
                     COUNT(CASE WHEN e.appgubun = '101' THEN 1 END) AS appgubun101,    -- appgubun = '101'
                     COUNT(CASE WHEN e.appgubun = '131' THEN 1 END) AS appgubun131,    -- appgubun = '131'
                     COUNT(CASE WHEN e.appgubun = '201' THEN 1 END) AS appgubun201,    -- appgubun = '201'
                     COUNT(CASE WHEN e.appgubun IN ('101', '131', '201') THEN 1 END) AS totalCnt -- ✅ 001(대기) 제외한 합계
                 FROM DateSeries d
                 LEFT JOIN TB_E080 e
                     ON d.repodate = e.repodate
                     AND e.appperid = :as_perid
                     AND e.spjangcd = :spjangcd
                 WHERE d.repodate BETWEEN FORMAT(DATEFROMPARTS(YEAR(GETDATE()), MONTH(GETDATE()), 1), 'yyyyMMdd')
                                    AND FORMAT(EOMONTH(GETDATE()), 'yyyyMMdd')
                 GROUP BY d.repodate
                 ORDER BY d.repodate;
           """;
        dicParam.addValue("as_perid", as_perid);
        dicParam.addValue("spjangcd", spjangcd);
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 결재요청받은(월별) 데이터
    public List<Map<String, Object>> ThisYearResCntOfMonth(String spjangcd, String as_perid) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
            WITH MonthSeries AS (
                    SELECT
                        FORMAT(DATEFROMPARTS(YEAR(GETDATE()), v.number, 1), 'yyyyMM') AS YearMonth
                    FROM (VALUES (1), (2), (3), (4), (5), (6), (7), (8), (9), (10), (11), (12)) v(number)
                )
                SELECT
                    m.YearMonth,
                    COUNT(CASE WHEN e.appgubun = '001' THEN 1 END) AS appgubun001,    -- 대기
                    COUNT(CASE WHEN e.appgubun = '101' THEN 1 END) AS appgubun101,    -- 결제 완료
                    COUNT(CASE WHEN e.appgubun = '131' THEN 1 END) AS appgubun131,    -- 반려
                    COUNT(CASE WHEN e.appgubun = '201' THEN 1 END) AS appgubun201,    -- 보류
                    COUNT(CASE WHEN e.appgubun IN ('101', '131', '201') THEN 1 END) AS totalCnt -- ✅ 001(대기) 제외한 합계
                FROM MonthSeries m
                LEFT JOIN TB_E080 e
                    ON FORMAT(CONVERT(DATE, e.repodate, 112), 'yyyyMM') = m.YearMonth
                    AND e.appperid = :as_perid
                    AND e.spjangcd = :spjangcd
                    AND e.repodate BETWEEN FORMAT(DATEFROMPARTS(YEAR(GETDATE()), 1, 1), 'yyyyMMdd')
                                     AND FORMAT(DATEFROMPARTS(YEAR(GETDATE()), 12, 31), 'yyyyMMdd')
                GROUP BY m.YearMonth
                ORDER BY m.YearMonth;
            """;
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("as_perid", as_perid);
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 결재 올린(일별) 데이터 개수
    public List<Map<String, Object>> ThisMonthReqCntOfDate(String spjangcd, String as_perid) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
            WITH DateSeries AS (
                 SELECT
                     FORMAT(DATEADD(DAY, v.number, DATEFROMPARTS(YEAR(GETDATE()), MONTH(GETDATE()), 1)), 'yyyyMMdd') AS repodate
                 FROM master.dbo.spt_values v
                 WHERE v.type = 'P' AND v.number BETWEEN 0 AND 30
             )
             SELECT
                 d.repodate,
                 COUNT(CASE WHEN e.appgubun = '001' THEN 1 END) AS appgubun001,    -- appgubun = '001'
                 COUNT(CASE WHEN e.appgubun = '101' THEN 1 END) AS appgubun101,    -- appgubun = '101'
                 COUNT(CASE WHEN e.appgubun = '131' THEN 1 END) AS appgubun131,    -- appgubun = '131'
                 COUNT(CASE WHEN e.appgubun = '201' THEN 1 END) AS appgubun201,    -- appgubun = '201'
                 COUNT(CASE WHEN e.appgubun IN ('101', '131', '201') THEN 1 END) AS totalCnt -- ✅ 001(대기) 제외한 합계
             FROM DateSeries d
             LEFT JOIN TB_E080 e
                 ON d.repodate = e.repodate
                 AND e.inperid = :as_perid
                 AND e.spjangcd = :spjangcd
             WHERE d.repodate BETWEEN FORMAT(DATEFROMPARTS(YEAR(GETDATE()), MONTH(GETDATE()), 1), 'yyyyMMdd')
                                AND FORMAT(EOMONTH(GETDATE()), 'yyyyMMdd')
             GROUP BY d.repodate
             ORDER BY d.repodate;
            """;
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("as_perid", as_perid);
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 결재 올린(월별) 데이터 개수
    public List<Map<String, Object>> ThisYearReqCntOfMonth(String spjangcd, String as_perid) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
            WITH MonthSeries AS (
                  SELECT
                      FORMAT(DATEFROMPARTS(YEAR(GETDATE()), v.number, 1), 'yyyyMM') AS YearMonth
                  FROM (VALUES (1), (2), (3), (4), (5), (6), (7), (8), (9), (10), (11), (12)) v(number)
              )
              SELECT
                  m.YearMonth,
                  COUNT(CASE WHEN e.appgubun = '001' THEN 1 END) AS appgubun001,    -- 대기
                  COUNT(CASE WHEN e.appgubun = '101' THEN 1 END) AS appgubun101,    -- 결제 완료
                  COUNT(CASE WHEN e.appgubun = '131' THEN 1 END) AS appgubun131,    -- 반려
                  COUNT(CASE WHEN e.appgubun = '201' THEN 1 END) AS appgubun201,    -- 보류
                  COUNT(CASE WHEN e.appgubun IN ('101', '131', '201') THEN 1 END) AS totalCnt -- ✅ 001(대기) 제외한 합계
              FROM MonthSeries m
              LEFT JOIN TB_E080 e
                  ON FORMAT(CONVERT(DATE, e.repodate, 112), 'yyyyMM') = m.YearMonth
                  AND e.inperid = :as_perid
                  AND e.spjangcd = :spjangcd
                  AND e.repodate BETWEEN FORMAT(DATEFROMPARTS(YEAR(GETDATE()), 1, 1), 'yyyyMMdd')
                                   AND FORMAT(DATEFROMPARTS(YEAR(GETDATE()), 12, 31), 'yyyyMMdd')
              GROUP BY m.YearMonth
              ORDER BY m.YearMonth;
            """;
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("as_perid", as_perid);
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;
    }
    // 공지사항 데이터 조회
    public List<Map<String,Object>> isNotice(){

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String sql = """
            SELECT TOP 1
                  b.*,
                  xu.pernm,
                  COALESCE((
                      SELECT JSON_QUERY((
                          SELECT
                              f.FILESEQ AS fileseq,
                              f.FILESIZE AS filesize,
                              f.FILEEXTNS AS fileextns,
                              f.FILEORNM AS fileornm,
                              f.FILEPATH AS filepath,
                              f.FILESVNM AS filesvnm
                          FROM TB_FILEINFO f
                          WHERE b.BBSSEQ = f.BBSSEQ
                          AND f.CHECKSEQ = '01'
                          FOR JSON PATH
                      ))
                  ), '[]') AS fileInfos
              FROM
                  TB_BBSINFO b
              left join tb_xusers xu on b.BBSUSER = xu.userid
              WHERE
                  b.BBSFRDATE <= CONVERT(VARCHAR(8), GETDATE(), 112)
                  AND b.BBSTODATE >= CONVERT(VARCHAR(8), GETDATE(), 112)
              ORDER BY
                  b.INDATEM DESC
            """;
        List<Map<String,Object>> items = this.sqlRunner.getRows(sql, dicParam);

        return items;

    }
}
