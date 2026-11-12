package mes.app.budget.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 세입·세출내역서 등록 (예산, TB_X0007)
 * PowerBuilder w_tb_x00071(세입) 이식. tiosec 1=세입, 2=세출.
 *  - 키: custcd + yyyy + spdate + spnum + tiosec
 *  - 신규 행: spdate = 오늘, spnum = 오늘 날짜(같은 tiosec)의 MAX(spnum)+1 (원본 wf_save 와 동일, spjangcd 무관)
 *  - 이월: yyyy → yyyy+1 복사(iwolflag='1', 사업정보 비움). 이미 이월분이 있으면 이월 취소(삭제).
 */
@Slf4j
@Service
public class BudgetEntryService {

	@Autowired
	SqlRunner sqlRunner;

	/**
	 * 목록 조회.
	 *  세입(1): 원본 d_tb_x00071_3 (관명 TB_AC001 내부 조인)
	 *  세출(2): 원본 d_tb_x00072_1 — 부문(TB_X0001, it1cd 앞 2자리) 내부 조인, 부문 순서(seqno)로 정렬.
	 *           원본은 TB_X0001 조인에 custcd 가 없어 회사가 여러 개면 행이 중복되므로 custcd 를 넣었다.
	 */
	public List<Map<String, Object>> getList(String tiosec, String yyyy) {
		if ("2".equals(tiosec)) return getExpenditureList(yyyy, null, null, null, null, null);
		Map<String, String> biz = getBizInfo();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz.get("custcd"))
																.addValue("spjangcd", biz.get("spjangcd"))
																.addValue("tiosec", tiosec)
																.addValue("yyyy", yyyy);

		String sql = """
			SELECT a.yyyy, a.spdate, a.spnum, a.tiosec,
			       a.acccd, c.accnm,
			       a.it1cd, d.it1nm,
			       a.it2cd, b.it2nm,
			       a.summy, a.price, a.mssec,
			       (SELECT mssecnm FROM tb_x0005 WHERE mssec = a.mssec) AS mssecnm,
			       a.bsdate, a.bseccd, a.busicd,
			       (SELECT businm FROM tb_x0002
			         WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm,
			       a.iwolflag
			  FROM TB_X0007 a
			       LEFT OUTER JOIN TB_X0004 b WITH (NOLOCK)
			         ON a.custcd = b.custcd AND a.tiosec = b.tiosec AND a.it2cd = b.it2cd
			       LEFT OUTER JOIN VW_X0003 d WITH (NOLOCK)
			         ON a.custcd = d.custcd AND a.tiosec = d.tiosec AND a.it1cd = d.it1cd,
			       TB_AC001 c
			 WHERE a.custcd   = c.custcd
			   AND a.acccd    = c.acccd
			   AND a.tiosec   = :tiosec
			   AND a.custcd   = :custcd
			   AND a.spjangcd = :spjangcd
			   AND a.yyyy     = :yyyy
			 ORDER BY a.acccd, ISNULL(a.bsdate,''), ISNULL(a.bseccd,''), ISNULL(a.busicd,''), ISNULL(a.it1cd,''), ISNULL(a.it2cd,''), a.spdate, a.spnum
			""";
		return sqlRunner.getRows(sql, p);
	}

	/**
	 * 세출 조회. acccd 가 있으면 입력 탭(관 · 항 단위), 없으면 목록(그해 전체).
	 */
	public List<Map<String, Object>> getExpenditureList(String yyyy, String acccd, String it1cd,
																											String bsdate, String bseccd, String busicd) {
		Map<String, String> biz = getBizInfo();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz.get("custcd"))
																.addValue("spjangcd", biz.get("spjangcd"))
																.addValue("yyyy", yyyy);

		StringBuilder where = new StringBuilder();
		if (acccd != null && !acccd.isBlank()) {
			// 원본 d_tb_x00072_3: acccd = , it1cd = , 사업은 LIKE (헤더 사업이 비어 있으면 전체)
			where.append(" AND a.acccd = :acccd AND a.it1cd = :it1cd")
				.append(" AND ISNULL(a.bsdate,'') LIKE :bsdate + '%'")
				.append(" AND ISNULL(a.bseccd,'') LIKE :bseccd + '%'")
				.append(" AND ISNULL(a.busicd,'') LIKE :busicd + '%'");
			p.addValue("acccd", acccd.trim())
				.addValue("it1cd", it1cd == null ? "" : it1cd.trim())
				.addValue("bsdate", bsdate == null ? "" : bsdate.trim())
				.addValue("bseccd", bseccd == null ? "" : bseccd.trim())
				.addValue("busicd", busicd == null ? "" : busicd.trim());
		}

		String sql = """
			SELECT a.yyyy, a.spdate, a.spnum, a.tiosec,
			       a.acccd, c.accnm,
			       a.it1cd,
			       COALESCE(d.it1nm, (SELECT TOP 1 it1nm FROM TB_X0003
			                           WHERE it1cd = SUBSTRING(a.it1cd, 3, 3) AND tiosec = a.tiosec)) AS it1nm,
			       b.bsecnm, b.seqno,
			       a.it2cd, e.it2nm,
			       a.summy, a.price, a.mssec,
			       (SELECT mssecnm FROM tb_x0005 WHERE mssec = a.mssec) AS mssecnm,
			       a.bsdate, a.bseccd, a.busicd,
			       (SELECT businm FROM tb_x0002
			         WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm,
			       a.iwolflag
			  FROM TB_X0007 a
			       {JOIN} JOIN TB_X0001 b
			         ON b.custcd = a.custcd AND b.bseccd = SUBSTRING(a.it1cd, 1, 2)
			       LEFT OUTER JOIN TB_AC001 c
			         ON c.custcd = a.custcd AND c.acccd = a.acccd
			       LEFT OUTER JOIN VW_X0003 d WITH (NOLOCK)
			         ON d.custcd = a.custcd AND d.tiosec = a.tiosec AND d.it1cd = a.it1cd
			       LEFT OUTER JOIN TB_X0004 e WITH (NOLOCK)
			         ON e.custcd = a.custcd AND e.tiosec = a.tiosec AND e.it2cd = a.it2cd
			 WHERE a.tiosec   = '2'
			   AND a.custcd   = :custcd
			   AND a.spjangcd = :spjangcd
			   AND a.yyyy     = :yyyy
			""" + where + """
			 ORDER BY a.acccd, ISNULL(a.bsdate,''), ISNULL(a.bseccd,''), ISNULL(a.busicd,''), ISNULL(a.it1cd,''), ISNULL(a.it2cd,''), a.spdate, a.spnum
			""";
		// 목록은 부문 내부 조인(원본 d_tb_x00072_1), 입력 탭은 부문 조인 없음(원본 d_tb_x00072_3) → 외부 조인
		sql = sql.replace("{JOIN}", where.length() > 0 ? "LEFT OUTER" : "INNER");
		// 입력 탭은 원본 d_tb_x00072_3 처럼 정렬 없이 등록 순(키 순서)
		if (where.length() > 0) {
			sql = sql.replace(
				"ORDER BY a.acccd, ISNULL(a.bsdate,''), ISNULL(a.bseccd,''), ISNULL(a.busicd,''), ISNULL(a.it1cd,''), ISNULL(a.it2cd,''), a.spdate, a.spnum",
				"ORDER BY a.spdate, a.spnum");
		}
		return sqlRunner.getRows(sql, p);
	}

	/** 세출 항 조회 (부문 2자리 + 항 3자리). VW_X0003 은 원본 입력 SQL 이 5자리 it1cd 로 조인하는 뷰다. */
	public List<Map<String, Object>> searchIt1(String tiosec, String keyword) {
		Map<String, String> biz = getBizInfo();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz.get("custcd"))
																.addValue("tiosec", tiosec)
																.addValue("kw", keyword == null ? "" : keyword.trim());
		return sqlRunner.getRows("""
			SELECT d.it1cd, d.it1nm, b.bsecnm
			  FROM VW_X0003 d
			       LEFT OUTER JOIN TB_X0001 b
			         ON b.custcd = d.custcd AND b.bseccd = SUBSTRING(d.it1cd, 1, 2)
			 WHERE d.custcd = :custcd AND d.tiosec = :tiosec
			   AND REPLACE(ISNULL(d.it1nm, '') + ISNULL(b.bsecnm, ''), ' ', '')
			       LIKE '%' + REPLACE(:kw, ' ', '') + '%'
			 ORDER BY b.seqno, d.it1cd
			""", p);
	}

	/** 상단 세입예산금액 · 세출예산금액 · 잔액 (그해 합계) */
	public Map<String, Object> getSummary(String yyyy) {
		Map<String, String> biz = getBizInfo();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz.get("custcd"))
																.addValue("spjangcd", biz.get("spjangcd"))
																.addValue("yyyy", yyyy);
		return sqlRunner.getRow("""
			SELECT ISNULL(SUM(CASE WHEN tiosec = '1' THEN price END), 0) AS revenue,
			       ISNULL(SUM(CASE WHEN tiosec = '2' THEN price END), 0) AS expenditure,
			       ISNULL(SUM(CASE WHEN tiosec = '1' THEN price END), 0)
			     - ISNULL(SUM(CASE WHEN tiosec = '2' THEN price END), 0) AS balance
			  FROM TB_X0007
			 WHERE custcd = :custcd AND spjangcd = :spjangcd AND yyyy = :yyyy
			""", p);
	}

	/**
	 * 저장. rows 의 spnum 이 비어 있으면 신규, 있으면 수정.
	 * header 가 있으면(세출) 원본 w_tb_x00072.wf_save 처럼 모든 행의 관 · 항 · 사업을 헤더 값으로 맞춘다.
	 * 원본처럼 관명(acccd)이 빈 행은 버리고, 사업년도 · 관 · 금액을 검증한다.
	 */
	@Transactional
	public int save(String tiosec, String yyyy, List<Map<String, Object>> rows) {
		return save(tiosec, yyyy, rows, null);
	}

	@Transactional
	public int save(String tiosec, String yyyy, List<Map<String, Object>> rows, Map<String, Object> header) {
		if (header != null) {
			if (str(header, "acccd").isEmpty()) throw new IllegalArgumentException("관명(계정)을 입력하십시오.");
			if (str(header, "it1cd").isEmpty()) throw new IllegalArgumentException("항명을 입력하십시오.");
			for (Map<String, Object> r : rows) {
				for (String k : new String[]{"acccd", "it1cd", "bsdate", "bseccd", "busicd"}) {
					r.put(k, str(header, k));
				}
			}
		}
		Map<String, String> biz = getBizInfo();
		String custcd = biz.get("custcd");
		String spjangcd = biz.get("spjangcd");
		String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("사업년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();

		Integer nextNum = null;   // 신규 행이 있을 때만 채번
		int count = 0;

		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> r = rows.get(i);
			String acccd = str(r, "acccd");
			if (acccd.isEmpty()) continue;   // 원본: 관명 빈 행은 삭제(무시)

			BigDecimal price = num(r, "price");
			if (price.signum() == 0) {
				throw new IllegalArgumentException((i + 1) + "번째 행의 금액을 입력하십시오.");
			}

			MapSqlParameterSource p = new MapSqlParameterSource()
																	.addValue("custcd", custcd)
																	.addValue("spjangcd", spjangcd)
																	.addValue("yyyy", yyyy)
																	.addValue("tiosec", tiosec)
																	.addValue("acccd", acccd)
																	.addValue("it1cd", emptyToNull(str(r, "it1cd")))
																	.addValue("it2cd", emptyToNull(str(r, "it2cd")))
																	.addValue("summy", str(r, "summy"))
																	.addValue("price", price)
																	.addValue("mssec", emptyToNull(str(r, "mssec")))
																	.addValue("bsdate", str(r, "bsdate"))
																	.addValue("bseccd", str(r, "bseccd"))
																	.addValue("busicd", str(r, "busicd"));

			String spnum = str(r, "spnum");
			if (spnum.isEmpty()) {
				if (nextNum == null) nextNum = nextSpnum(custcd, tiosec, today);
				p.addValue("spdate", today).addValue("spnum", String.format("%04d", nextNum++));
				sqlRunner.execute("""
					INSERT INTO TB_X0007 (custcd, spjangcd, yyyy, spdate, spnum, tiosec,
					                      acccd, it1cd, it2cd, summy, price, mssec,
					                      bsdate, bseccd, busicd)
					VALUES (:custcd, :spjangcd, :yyyy, :spdate, :spnum, :tiosec,
					        :acccd, :it1cd, :it2cd, :summy, :price, :mssec,
					        :bsdate, :bseccd, :busicd)
					""", p);
			} else {
				p.addValue("spdate", str(r, "spdate")).addValue("spnum", spnum);
				sqlRunner.execute("""
					UPDATE TB_X0007
					   SET acccd = :acccd, it1cd = :it1cd, it2cd = :it2cd,
					       summy = :summy, price = :price, mssec = :mssec,
					       bsdate = :bsdate, bseccd = :bseccd, busicd = :busicd
					 WHERE custcd = :custcd AND yyyy = :yyyy AND spdate = :spdate
					   AND spnum = :spnum AND tiosec = :tiosec AND spjangcd = :spjangcd
					""", p);
			}
			count++;
		}
		return count;
	}

	/** 선택 행 삭제 (A안) */
	@Transactional
	public int delete(String tiosec, List<Map<String, Object>> keys) {
		Map<String, String> biz = getBizInfo();
		int n = 0;
		for (Map<String, Object> k : keys) {
			MapSqlParameterSource p = new MapSqlParameterSource()
																	.addValue("custcd", biz.get("custcd"))
																	.addValue("spjangcd", biz.get("spjangcd"))
																	.addValue("tiosec", tiosec)
																	.addValue("yyyy", str(k, "yyyy"))
																	.addValue("spdate", str(k, "spdate"))
																	.addValue("spnum", str(k, "spnum"));
			n += sqlRunner.execute("""
				DELETE FROM TB_X0007
				 WHERE custcd = :custcd AND yyyy = :yyyy AND spdate = :spdate
				   AND spnum = :spnum AND tiosec = :tiosec AND spjangcd = :spjangcd
				""", p);
		}
		return n;
	}

	/** 다음 해에 이미 이월된 건수. 원본 COUNT 에 빠져 있던 custcd · spjangcd 조건을 넣었다. */
	public int carryoverCount(String tiosec, String yyyy) {
		Map<String, String> biz = getBizInfo();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz.get("custcd"))
																.addValue("spjangcd", biz.get("spjangcd"))
																.addValue("tiosec", tiosec)
																.addValue("nyyyy", nextYear(yyyy));
		return sqlRunner.queryForCount("""
			SELECT COUNT(*) FROM TB_X0007
			 WHERE tiosec = :tiosec AND iwolflag = '1' AND yyyy = :nyyyy
			   AND custcd = :custcd AND spjangcd = :spjangcd
			""", p);
	}

	/** 이월 취소: 다음 해 이월분 삭제 */
	@Transactional
	public int carryoverCancel(String tiosec, String yyyy) {
		Map<String, String> biz = getBizInfo();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz.get("custcd"))
																.addValue("spjangcd", biz.get("spjangcd"))
																.addValue("tiosec", tiosec)
																.addValue("nyyyy", nextYear(yyyy));
		return sqlRunner.execute("""
			DELETE FROM TB_X0007
			 WHERE tiosec = :tiosec AND iwolflag = '1' AND yyyy = :nyyyy
			   AND custcd = :custcd AND spjangcd = :spjangcd
			""", p);
	}

	/** 이월 실행: yyyy → yyyy+1 복사 (사업정보 비움, iwolflag='1') */
	@Transactional
	public int carryoverRun(String tiosec, String yyyy) {
		if (carryoverCount(tiosec, yyyy) > 0) {
			throw new IllegalStateException("이미 이월된 자료가 있습니다. 이월 취소 후 다시 실행하세요.");
		}
		Map<String, String> biz = getBizInfo();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz.get("custcd"))
																.addValue("spjangcd", biz.get("spjangcd"))
																.addValue("tiosec", tiosec)
																.addValue("yyyy", yyyy)
																.addValue("nyyyy", nextYear(yyyy));
		return sqlRunner.execute("""
			INSERT INTO TB_X0007 (custcd, spjangcd, yyyy, spdate, spnum, tiosec,
			                      acccd, it1cd, it2cd, summy, price, mssec,
			                      bsdate, bseccd, busicd, iwolflag)
			SELECT custcd, spjangcd, :nyyyy, :nyyyy + SUBSTRING(spdate, 5, 4), spnum, tiosec,
			       acccd, it1cd, it2cd, summy, price, mssec,
			       '', '', '', '1'
			  FROM TB_X0007
			 WHERE tiosec = :tiosec AND yyyy = :yyyy
			   AND custcd = :custcd AND spjangcd = :spjangcd
			""", p);
	}

	// ── 내부 ──

	/** 오늘 날짜 MAX(spnum)+1. 동시 저장 시 번호 충돌을 막으려고 잠금을 건다. */
	private int nextSpnum(String custcd, String tiosec, String spdate) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd)
																.addValue("tiosec", tiosec)
																.addValue("spdate", spdate);
		Map<String, Object> row = sqlRunner.getRow("""
			SELECT ISNULL(MAX(CAST(spnum AS INT)), 0) + 1 AS next_spnum
			  FROM TB_X0007 WITH (UPDLOCK, HOLDLOCK)
			 WHERE tiosec = :tiosec AND custcd = :custcd AND spdate = :spdate
			   AND ISNUMERIC(spnum) = 1
			""", p);
		if (row == null || row.get("next_spnum") == null) return 1;
		return Integer.parseInt(String.valueOf(row.get("next_spnum")).trim());
	}

	private String nextYear(String yyyy) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("년도를 입력하세요.");
		}
		return String.valueOf(Integer.parseInt(yyyy.trim()) + 1);
	}

	private Map<String, String> getBizInfo() {
		String spjangcd = TenantContext.get();
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		Map<String, String> m = new HashMap<>();
		m.put("spjangcd", spjangcd);
		m.put("custcd", row == null || row.get("custcd") == null ? "" : String.valueOf(row.get("custcd")).trim());
		return m;
	}

	private String str(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v == null ? "" : String.valueOf(v).trim();
	}

	private String emptyToNull(String v) {
		return v == null || v.isEmpty() ? null : v;
	}

	private BigDecimal num(Map<String, Object> m, String k) {
		String s = str(m, k).replace(",", "");
		if (s.isEmpty()) return BigDecimal.ZERO;
		try { return new BigDecimal(s); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
	}
}