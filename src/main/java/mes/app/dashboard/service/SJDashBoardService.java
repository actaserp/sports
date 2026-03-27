package mes.app.dashboard.service;

import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SJDashBoardService {

    @Autowired
    SqlRunner sqlRunner;

    public List<Map<String, Object>> getList(String startDate, String endDate, String searchType) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                -- 1. 발주처 출고 누적 상태
                WITH total_items AS (
                    SELECT baljunum, COUNT(*) AS total_cnt
                    FROM tb_ca661
                    GROUP BY baljunum
                ),
                chul_progress AS (
                    SELECT baljunum, chuldate, COUNT(*) AS done_cnt
                    FROM tb_ca661
                    WHERE chulflag = '1' AND chuldate IS NOT NULL
                    GROUP BY baljunum, chuldate
                ),
                cumulative_chul AS (
                    SELECT
                        cp.baljunum,
                        cp.chuldate,
                        SUM(cp.done_cnt) OVER (
                            PARTITION BY cp.baljunum
                            ORDER BY cp.chuldate
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                        ) AS cumulative_done,
                        ti.total_cnt
                    FROM chul_progress cp
                    JOIN total_items ti ON cp.baljunum = ti.baljunum
                ),
                final_chul_status AS (
                    SELECT
                        b.procd AS project_no,
                        a.project_nm,
                        b.actnm,
                        cp.chuldate AS reqdate,
                        b.ichdate,
                        CASE
                            WHEN cp.cumulative_done = cp.total_cnt THEN '발주처-출고완료'
                            ELSE '발주처-부분출고'
                        END AS ordflag
                    FROM cumulative_chul cp
                    JOIN tb_ca660 b ON cp.baljunum = b.baljunum
                    JOIN tb_ca664 a ON a.project_no = b.procd
                ),

                -- 2. 공장 출고 누적 상태
                fac_total AS (
                    SELECT baljunum, COUNT(*) AS total_cnt
                    FROM tb_ca661
                    WHERE facflag IS NOT NULL
                    GROUP BY baljunum
                ),
                fac_progress AS (
                    SELECT baljunum, facdate, COUNT(*) AS done_cnt
                    FROM tb_ca661
                    WHERE facflag = '1' AND facdate IS NOT NULL
                    GROUP BY baljunum, facdate
                ),
                cumulative_fac AS (
                    SELECT
                        fp.baljunum,
                        fp.facdate,
                        SUM(fp.done_cnt) OVER (
                            PARTITION BY fp.baljunum
                            ORDER BY fp.facdate
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                        ) AS cumulative_done,
                        ft.total_cnt
                    FROM fac_progress fp
                    JOIN fac_total ft ON fp.baljunum = ft.baljunum
                ),
                final_fac_status AS (
                    SELECT
                        b.procd AS project_no,
                        a.project_nm,
                        b.actnm,
                        cf.facdate AS reqdate,
                        b.ichdate,
                        CASE
                            WHEN cf.cumulative_done = cf.total_cnt THEN '공장-출고완료'
                            ELSE '공장-부분출고'
                        END AS ordflag
                    FROM cumulative_fac cf
                    JOIN tb_ca660 b ON cf.baljunum = b.baljunum
                    JOIN tb_ca664 a ON a.project_no = b.procd
                ),

                -- 3. 현장 확인 누적 상태
                hyun_total AS (
                    SELECT baljunum, COUNT(*) AS total_cnt
                    FROM tb_ca661
                    WHERE hyunflag IS NOT NULL
                    GROUP BY baljunum
                ),
                hyun_progress AS (
                    SELECT baljunum, hyundate, COUNT(*) AS done_cnt
                    FROM tb_ca661
                    WHERE hyunflag = '1' AND hyundate IS NOT NULL
                    GROUP BY baljunum, hyundate
                ),
                cumulative_hyun AS (
                    SELECT
                        hp.baljunum,
                        hp.hyundate,
                        SUM(hp.done_cnt) OVER (
                            PARTITION BY hp.baljunum
                            ORDER BY hp.hyundate
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                        ) AS cumulative_done,
                        ht.total_cnt
                    FROM hyun_progress hp
                    JOIN hyun_total ht ON hp.baljunum = ht.baljunum
                ),
                final_hyun_status AS (
                    SELECT
                        b.procd AS project_no,
                        a.project_nm,
                        b.actnm,
                        ch.hyundate AS reqdate,
                        b.ichdate,
                        CASE
                            WHEN ch.cumulative_done = ch.total_cnt THEN '현장-확인완료'
                            ELSE '현장-부분확인'
                        END AS ordflag
                    FROM cumulative_hyun ch
                    JOIN tb_ca660 b ON ch.baljunum = b.baljunum
                    JOIN tb_ca664 a ON a.project_no = b.procd
                )

                -- 4. 전체 UNION
                SELECT * FROM (
                    -- 프로젝트 등록
                    SELECT
                        '프로젝트 등록' AS ordflag,
                        a.project_no,
                        a.project_nm,
                        NULL AS actnm,
                        a.bpdate AS reqdate,
                        NULL AS ichdate
                    FROM tb_ca664 a
                            
                    UNION ALL
                            
                    -- 발주 등록
                    SELECT
                        '발주등록' AS ordflag,
                        b.procd AS project_no,
                        a.project_nm,
                        b.actnm,
                        b.baljudate AS reqdate,
                        b.ichdate
                    FROM tb_ca660 b
                    JOIN tb_ca664 a ON a.project_no = b.procd
                            
                    UNION ALL
                            
                    -- 누적: 발주처
                    SELECT ordflag, project_no, project_nm, actnm, reqdate, ichdate FROM final_chul_status
                            
                    UNION ALL
                            
                    -- 누적: 공장
                    SELECT ordflag, project_no, project_nm, actnm, reqdate, ichdate FROM final_fac_status
                            
                    UNION ALL
                            
                    -- 누적: 현장
                    SELECT ordflag, project_no, project_nm, actnm, reqdate, ichdate FROM final_hyun_status
                ) t
                where 1=1
            """);


        if (startDate != null && !startDate.isEmpty()) {
            sql.append(" AND t.reqdate >= :startDate");
            dicParam.addValue("startDate", startDate);
        }

        if (endDate != null && !endDate.isEmpty()) {
            sql.append(" AND t.reqdate <= :endDate");
            dicParam.addValue("endDate", endDate);
        }

        if (searchType != null && !searchType.isEmpty()) {
            List<String> flags = switch (searchType) {
                case "1" -> List.of("프로젝트 등록");
                case "2" -> List.of("발주등록");
                case "3" -> List.of("발주처-출고완료", "발주처-부분출고");
                case "4" -> List.of("공장-출고완료", "공장-부분출고");
                case "5" -> List.of("현장-확인완료", "현장-부분확인");
                default -> List.of();
            };

            if (!flags.isEmpty()) {
                String inClause = flags.stream()
                        .map(flag -> "'" + flag + "'")
                        .collect(Collectors.joining(", "));  // SQL IN ('a','b')

                sql.append(" AND t.ordflag IN (" + inClause + ")");
            }
        }

        sql.append("""
            ORDER BY 
              t.reqdate DESC,
              CASE t.ordflag
                WHEN '현장-확인완료' THEN 1
                WHEN '현장-부분확인' THEN 2
                WHEN '공장-출고완료' THEN 3
                WHEN '공장-부분출고' THEN 4
                WHEN '발주처-출고완료' THEN 5
                WHEN '발주처-부분출고' THEN 6
                WHEN '발주등록' THEN 7
                WHEN '프로젝트 등록' THEN 8
                ELSE 99
              END
        """);

        return this.sqlRunner.getRows(sql.toString(), dicParam);
    }

    public List<Map<String, Object>> getYearCountList(String year) {

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                WITH base_data AS (
                     -- 1. 프로젝트 등록
                     SELECT
                         a.project_no,
                         a.project_nm,
                         NULL AS actnm,
                         a.bpdate AS reqdate,
                         NULL AS ichdate,
                         '프로젝트 등록' AS ordflag
                     FROM tb_ca664 a
                 
                     UNION ALL
                 
                     -- 2. 발주 등록
                     SELECT
                         b.procd AS project_no,
                         a.project_nm,
                         b.actnm,
                         b.baljudate AS reqdate,
                         b.ichdate,
                         '발주등록' AS ordflag
                     FROM tb_ca660 b
                     JOIN tb_ca664 a ON a.project_no = b.procd
                 
                     UNION ALL
                 
                     -- 3. 발주처 출고
                     SELECT
                         b.procd AS project_no,
                         a.project_nm,
                         b.actnm,
                         cp.chuldate AS reqdate,
                         b.ichdate,
                         CASE
                             WHEN SUM(cp.done_cnt) OVER (PARTITION BY cp.baljunum ORDER BY cp.chuldate) = ti.total_cnt
                             THEN '발주처-출고완료'
                             ELSE '발주처-부분출고'
                         END AS ordflag
                     FROM (
                         SELECT baljunum, COUNT(*) AS total_cnt
                         FROM tb_ca661
                         GROUP BY baljunum
                     ) ti
                     JOIN (
                         SELECT baljunum, chuldate, COUNT(*) AS done_cnt
                         FROM tb_ca661
                         WHERE chulflag = '1' AND chuldate IS NOT NULL
                         GROUP BY baljunum, chuldate
                     ) cp ON cp.baljunum = ti.baljunum
                     JOIN tb_ca660 b ON cp.baljunum = b.baljunum
                     JOIN tb_ca664 a ON a.project_no = b.procd
                 
                     UNION ALL
                 
                     -- 4. 공장 출고
                     SELECT
                         b.procd AS project_no,
                         a.project_nm,
                         b.actnm,
                         fp.facdate AS reqdate,
                         b.ichdate,
                         CASE
                             WHEN SUM(fp.done_cnt) OVER (PARTITION BY fp.baljunum ORDER BY fp.facdate) = ft.total_cnt
                             THEN '공장-출고완료'
                             ELSE '공장-부분출고'
                         END AS ordflag
                     FROM (
                         SELECT baljunum, COUNT(*) AS total_cnt
                         FROM tb_ca661
                         WHERE facflag IS NOT NULL
                         GROUP BY baljunum
                     ) ft
                     JOIN (
                         SELECT baljunum, facdate, COUNT(*) AS done_cnt
                         FROM tb_ca661
                         WHERE facflag = '1' AND facdate IS NOT NULL
                         GROUP BY baljunum, facdate
                     ) fp ON fp.baljunum = ft.baljunum
                     JOIN tb_ca660 b ON fp.baljunum = b.baljunum
                     JOIN tb_ca664 a ON a.project_no = b.procd
                 
                     UNION ALL
                 
                     -- 5. 현장 확인
                     SELECT
                         b.procd AS project_no,
                         a.project_nm,
                         b.actnm,
                         hp.hyundate AS reqdate,
                         b.ichdate,
                         CASE
                             WHEN SUM(hp.done_cnt) OVER (PARTITION BY hp.baljunum ORDER BY hp.hyundate) = ht.total_cnt
                             THEN '현장-확인완료'
                             ELSE '현장-부분확인'
                         END AS ordflag
                     FROM (
                         SELECT baljunum, COUNT(*) AS total_cnt
                         FROM tb_ca661
                         WHERE hyunflag IS NOT NULL
                         GROUP BY baljunum
                     ) ht
                     JOIN (
                         SELECT baljunum, hyundate, COUNT(*) AS done_cnt
                         FROM tb_ca661
                         WHERE hyunflag = '1' AND hyundate IS NOT NULL
                         GROUP BY baljunum, hyundate
                     ) hp ON hp.baljunum = ht.baljunum
                     JOIN tb_ca660 b ON hp.baljunum = b.baljunum
                     JOIN tb_ca664 a ON a.project_no = b.procd
                 )
                 
                 -- 최종 카테고리별 요약
                 SELECT
                     CASE
                         WHEN ordflag IN ('발주처-출고완료', '발주처-부분출고') THEN '발주출고'
                         WHEN ordflag IN ('공장-출고완료', '공장-부분출고') THEN '공장출고'
                         WHEN ordflag IN ('현장-확인완료', '현장-부분확인') THEN '현장확인'
                         ELSE ordflag
                     END AS ordflag,
                     COUNT(*) AS ordflag_count
                 FROM base_data
                 WHERE reqdate >= CONVERT(DATE, :year + '0101')
                       AND reqdate <  DATEADD(YEAR, 1, CONVERT(DATE, :year + '0101'))
                 GROUP BY
                     CASE
                         WHEN ordflag IN ('발주처-출고완료', '발주처-부분출고') THEN '발주출고'
                         WHEN ordflag IN ('공장-출고완료', '공장-부분출고') THEN '공장출고'
                         WHEN ordflag IN ('현장-확인완료', '현장-부분확인') THEN '현장확인'
                         ELSE ordflag
                     END
                 
            """);

        dicParam.addValue("year", year);

        return this.sqlRunner.getRows(sql.toString(), dicParam);
    }

    public List<Map<String, Object>> getListGantt(String startDate, String endDate, String searchType) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();

        // 오늘 날짜 yyyyMMdd
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        dicParam.addValue("today", today);

        StringBuilder sql = new StringBuilder("""
        SELECT
            CONCAT(
                P.PROJECT_NM,
                CASE WHEN C.ACTNM IS NOT NULL THEN ' - ' + C.ACTNM ELSE '' END,
                ' (',
                CASE
                    WHEN C.PROCD IS NULL THEN '프로젝트 등록'
                    WHEN MAX(D.HYUNFLAG) = '1' THEN '현장확인'
                    WHEN MAX(D.FACFLAG) = '1' THEN '공장출고'
                    WHEN MAX(D.CHULFLAG) = '1' THEN '발주출고'
                    ELSE '발주등록'
                END,
                ')'
            ) AS title,

            P.BPDATE AS start,

            CONVERT(VARCHAR(10),
                ISNULL(
                    MAX(NULLIF(D.HYUNDATE, '')),
                    ISNULL(NULLIF(C.ICHDATE, ''), :today)
                ), 112
            ) AS [end],

            CASE
                WHEN C.PROCD IS NULL THEN '프로젝트 등록'
                WHEN MAX(D.HYUNFLAG) = '1' THEN '현장확인'
                WHEN MAX(D.FACFLAG) = '1' THEN '공장출고'
                WHEN MAX(D.CHULFLAG) = '1' THEN '발주출고'
                ELSE '발주등록'
            END AS ordflag

        FROM TB_CA664 P
        LEFT JOIN TB_CA660 C ON C.PROCD = P.PROJECT_NO
        LEFT JOIN TB_CA661 D ON D.BALJUNUM = C.BALJUNUM
        WHERE 1=1
    """);

        if (startDate != null && !startDate.isEmpty()) {
            sql.append("""
            AND (
                P.BPDATE >= :startDate OR
                C.BALJUDATE >= :startDate
            )
        """);
            dicParam.addValue("startDate", startDate);
        }

        if (endDate != null && !endDate.isEmpty()) {
            sql.append("""
            AND (
                P.BPDATE <= :endDate OR
                C.BALJUDATE <= :endDate
            )
        """);
            dicParam.addValue("endDate", endDate);
        }

        sql.append("""
        GROUP BY P.PROJECT_NM, P.BPDATE, C.ACTNM, C.PROCD, C.ICHDATE, C.BALJUDATE
        ORDER BY P.BPDATE
    """);

        return this.sqlRunner.getRows(sql.toString(), dicParam);
    }

    public List<Map<String, Object>> getListDay(String date, String searchType) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("date", date);
        dicParam.addValue("searchType", searchType);

        StringBuilder sql = new StringBuilder("""
        SELECT
            t.ordflag,
            t.reqdate,
            t.project_nm,
            t.actnm,
            t.ichdate
        FROM (
            SELECT
                CASE
                    WHEN C.PROCD IS NULL THEN '프로젝트 등록'
                    WHEN MAX(D.HYUNFLAG) = '1' THEN '현장-확인완료'
                    WHEN MAX(D.FACFLAG) = '1' THEN '공장-출고완료'
                    WHEN MAX(D.CHULFLAG) = '1' THEN '발주처-출고완료'
                    ELSE '발주등록'
                END AS ordflag,

                P.BPDATE AS reqdate,  -- 시작일
                P.PROJECT_NM AS project_nm,
                C.ACTNM AS actnm,

                -- 종료일: HYUNDATE > ICHDATE > 오늘날짜
                CONVERT(VARCHAR(8), 
                    ISNULL(
                        MAX(NULLIF(D.HYUNDATE, '')),
                        ISNULL(NULLIF(C.ICHDATE, ''), CONVERT(VARCHAR(8), GETDATE(), 112))
                    ), 112
                ) AS ichdate

            FROM TB_CA664 P
            LEFT JOIN TB_CA660 C ON C.PROCD = P.PROJECT_NO
            LEFT JOIN TB_CA661 D ON D.BALJUNUM = C.BALJUNUM

            GROUP BY P.BPDATE, P.PROJECT_NM, C.ACTNM, C.PROCD, C.ICHDATE
        ) t
        WHERE 1=1
          AND t.reqdate <= :date
          AND t.ichdate >= :date
    """);

        if (searchType != null && !searchType.isEmpty()) {
            List<String> flags = switch (searchType) {
                case "1" -> List.of("프로젝트 등록");
                case "2" -> List.of("발주등록");
                case "3" -> List.of("발주처-출고완료", "발주처-부분출고");
                case "4" -> List.of("공장-출고완료", "공장-부분출고");
                case "5" -> List.of("현장-확인완료", "현장-부분확인");
                default -> List.of();
            };

            if (!flags.isEmpty()) {
                String inClause = flags.stream()
                        .map(flag -> "'" + flag + "'")
                        .collect(Collectors.joining(", "));
                sql.append(" AND t.ordflag IN (" + inClause + ")");
            }
        }

        return this.sqlRunner.getRows(sql.toString(), dicParam);
    }



}
