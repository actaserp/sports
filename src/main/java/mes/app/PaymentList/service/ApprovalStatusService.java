package mes.app.PaymentList.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ApprovalStatusService {

	@Autowired
	SqlRunner sqlRunner; // 기존 방식 유지

	public List<Map<String, Object>> getCalendarGridList(
		Integer personid,
		String spjangcd,
		String searchStartDate,
		String searchEndDate,
		String searchType) {

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
			personCode = code != null ? code.replaceFirst("^p", "") : null;
		}

		if (personCode == null) {
			log.warn("⚠️ personCode 조회 실패 - personid={}, spjangcd={}", personid, spjangcd);
			return new ArrayList<>();
		}

		// 2. 날짜 포맷 변환
		String startDate = searchStartDate != null ? searchStartDate.replaceAll("-", "") : "";
		String endDate   = searchEndDate   != null ? searchEndDate.replaceAll("-", "")   : "";

		// 3. 결재 현황 조회
		return getOrderList(personCode, spjangcd, startDate, endDate, searchType);
	}

	public List<Map<String, Object>> getOrderList(
		String perid, String spjangcd,
		String searchStartDate, String searchEndDate, String searchType) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("searchStartDate", searchStartDate);
		dicParam.addValue("searchEndDate",   searchEndDate);
		dicParam.addValue("searchType",      searchType);
		dicParam.addValue("as_perid",        perid);
		dicParam.addValue("as_spjangcd",     spjangcd);

		StringBuilder sql = new StringBuilder("""
      SELECT a.papercd,
             a.spjangcd,
             CONVERT(VARCHAR(10),
                 CONVERT(DATE, a.repodate, 112), 120) AS repodate,
             a.appnum,
             b.pernm AS repopernm,
             (SELECT divinm FROM tb_jc002 WHERE divicd = b.divicd) AS divinm,
             a.title,
             CASE a.appgubun
                 WHEN '001' THEN '결재대기'
                 WHEN '101' THEN '결재'
                 WHEN '131' THEN '보류'
                 WHEN '201' THEN '반려'
                 ELSE a.appgubun
             END AS appgubun,
             c.com_cnam AS papernm
        FROM tb_e080 a WITH(NOLOCK)
        JOIN tb_ja001 b ON 'p' + a.repoperid = b.perid
        JOIN tb_ca510 c ON a.papercd = c.com_code
       WHERE a.spjangcd = :as_spjangcd
         AND a.appperid = :as_perid
      """);

		if (searchStartDate != null && !searchStartDate.isEmpty()
					&& searchEndDate != null && !searchEndDate.isEmpty()) {
			sql.append(" AND a.repodate BETWEEN :searchStartDate AND :searchEndDate");
		}

		if (searchType != null && !searchType.isEmpty()) {
			if ("0".equals(searchType)) {
				sql.append(" AND a.appgubun != '001'");
			} else {
				sql.append(" AND a.appgubun LIKE :searchType");
			}
		}

		sql.append(" ORDER BY a.repodate DESC");

		return this.sqlRunner.getRows(sql.toString(), dicParam);
	}
	public List<Map<String, Object>> initDatas(
		Integer personid,
		String spjangcd,
		String searchStartDate,
		String searchEndDate) {

		// 1. personCode 조회
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
			personCode = code != null ? code.replaceFirst("^p", "") : null;
		}

		if (personCode == null) {
			log.warn("⚠️ personCode 조회 실패 - personid={}, spjangcd={}", personid, spjangcd);
			return new ArrayList<>();
		}

		// 2. custcd 조회
		String custcd = getCustcdBySpjangcd(spjangcd);
		if (custcd.isEmpty()) {
			log.warn("⚠️ custcd 조회 실패 - spjangcd={}", spjangcd);
			return new ArrayList<>();
		}

		// 3. 날짜 포맷 변환 (없으면 올해 전체)
		String startDate;
		String endDate;

		if (searchStartDate != null && !searchStartDate.isEmpty()
					&& searchEndDate != null && !searchEndDate.isEmpty()) {
			startDate = searchStartDate.replaceAll("-", "");
			endDate   = searchEndDate.replaceAll("-", "");
		} else {
			LocalDate today = LocalDate.now();
			startDate = today.withDayOfYear(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
			endDate   = today.withMonth(12).withDayOfMonth(31).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
		}

		// 4. 카운트 조회
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("as_perid",    personCode);
		dicParam.addValue("as_custcd",   custcd);
		dicParam.addValue("as_spjangcd", spjangcd);
		dicParam.addValue("as_stdate",   startDate);
		dicParam.addValue("as_enddate",  endDate);

		String sql = """
    SELECT
        (SELECT COUNT(appgubun)
         FROM TB_E080 WITH(NOLOCK)
         WHERE appgubun  = '001'
         AND appperid    = :as_perid
         AND custcd      = :as_custcd
         AND spjangcd    = :as_spjangcd
         AND flag        = '1'
         AND repodate BETWEEN :as_stdate AND :as_enddate) AS appgubun1,

        (SELECT COUNT(appgubun)
         FROM TB_E080 WITH(NOLOCK)
         WHERE appgubun  = '101'
         AND appperid    = :as_perid
         AND custcd      = :as_custcd
         AND spjangcd    = :as_spjangcd
         AND flag        = '1'
         AND repodate BETWEEN :as_stdate AND :as_enddate) AS appgubun2,

        (SELECT COUNT(appgubun)
         FROM TB_E080 WITH(NOLOCK)
         WHERE appgubun  = '131'
         AND appperid    = :as_perid
         AND custcd      = :as_custcd
         AND spjangcd    = :as_spjangcd
         AND flag        = '1'
         AND repodate BETWEEN :as_stdate AND :as_enddate) AS appgubun3,

        (SELECT COUNT(appgubun)
         FROM TB_E080 WITH(NOLOCK)
         WHERE appgubun  = '201'
         AND appperid    = :as_perid
         AND custcd      = :as_custcd
         AND spjangcd    = :as_spjangcd
         AND flag        = '1'
         AND repodate BETWEEN :as_stdate AND :as_enddate) AS appgubun4
    """;

		return this.sqlRunner.getRows(sql, dicParam);
	}

	private String getCustcdBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
      SELECT custcd
      FROM tb_xa012
      WHERE spjangcd = :spjangcd
      """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		if (row == null || row.isEmpty()) return "";

		Object custcd = row.get("custcd");
		return custcd == null ? "" : String.valueOf(custcd).trim();
	}

	public List<Map<String, Object>> getCalendarGridList2(
		Integer personid,
		String spjangcd,
		String searchStartDate,
		String searchEndDate) {

		// 1. personCode 조회
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
			personCode = code != null ? code.replaceFirst("^p", "") : null;
		}

		if (personCode == null) {
			log.warn("⚠️ personCode 조회 실패 - personid={}, spjangcd={}", personid, spjangcd);
			return new ArrayList<>();
		}

		// 2. 데이터 조회
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("as_perid",    personCode);
		dicParam.addValue("as_spjangcd", spjangcd);
		dicParam.addValue("as_stdate",   searchStartDate.replaceAll("-", ""));
		dicParam.addValue("as_enddate",  searchEndDate.replaceAll("-", ""));

		String sql = """
      SELECT
          CASE appgubun
              WHEN '001' THEN '결재대기'
              WHEN '101' THEN '결재'
              WHEN '131' THEN '보류'
              WHEN '201' THEN '반려'
              ELSE appgubun
          END AS appgubun,
          CONVERT(VARCHAR(10), CONVERT(DATE, repodate, 112), 120) AS repodate
        FROM TB_E080 WITH(NOLOCK)
       WHERE spjangcd = :as_spjangcd
         AND appperid = :as_perid
         AND repodate BETWEEN :as_stdate AND :as_enddate
       ORDER BY repodate ASC
      """;

		return this.sqlRunner.getRows(sql, dicParam);
	}

}