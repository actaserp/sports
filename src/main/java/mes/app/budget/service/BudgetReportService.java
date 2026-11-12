package mes.app.budget.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 예산서 출력
 *  - 결산서및예산안대조표: PowerBuilder w_tb_aa009_sh16 이식
 *      dw_3 d_tb_aa009_sh16_1 (수입) · dw_4 d_tb_aa009_sh16_2 (지출) · dw_5 d_tb_aa009_sh16_3 (전기이월금)
 *      을 조회해 좌(세입) · 우(세출)로 붙이고, 합계 · 전기/차기이월금 · 총계 행을 원본 ue_retrieve 식 그대로 계산한다.
 *  - 사업계획서(사업별): w_tb_x0002_sh1 이식 (getPlanByProject)
 *  - 사업계획서(월별): w_tb_x0002_sh2 이식 (getPlanByMonth)
 *  - 세입의부: w_tb_x00071_sh1 이식 (getRevenueLedger)
 *  - 세입내역서: w_tb_x00071_sh2 이식 (getRevenueDetail)
 *  - 원본 SQL 에는 사업장(spjangcd) 조건이 없다(회사 전체). 원본과 숫자를 맞추려고 그대로 두었다.
 */
@Slf4j
@Service
public class BudgetReportService {

	@Autowired
	SqlRunner sqlRunner;

	public Map<String, Object> getComparison(String yyyy) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();
		String yyyy2 = String.valueOf(Integer.parseInt(yyyy) + 1);

		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		String spjangnm = biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim();

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd)
																.addValue("yyyy", yyyy)
																.addValue("yyyy2", yyyy2);

		// dw_3 수입: 예산(yyyy2, 관 5xxx) + 결산(yyyy 전표 대변, 5000~5599 · 8100~8299)
		List<Map<String, Object>> rev = sqlRunner.getRows("""
			SELECT a.acccd,
			       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = a.acccd) AS accnm,
			       SUM(a.amt1) AS amt1,
			       SUM(a.amt2) AS amt2
			  FROM (SELECT a.acccd, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
			          FROM TB_X0007 a
			         WHERE a.tiosec = '1'
			           AND a.custcd = :custcd
			           AND a.yyyy   = :yyyy2
			           AND LEFT(a.acccd, 1) = '5'
			         GROUP BY a.acccd
			        UNION
			        SELECT b.acccd, CAST(0 AS numeric(13,0)) AS amt1, SUM(b.cramt) AS amt2
			          FROM TB_AA009 a, TB_AA010 b
			         WHERE a.custcd   = b.custcd
			           AND a.spjangcd = b.spjangcd
			           AND a.spdate   = b.spdate
			           AND a.spnum    = b.spnum
			           AND b.drcr     = '2'
			           AND a.custcd   = :custcd
			           AND SUBSTRING(a.spdate, 1, 4) = :yyyy
			           AND ((b.acccd BETWEEN '5000' AND '5599') OR (b.acccd BETWEEN '8100' AND '8299'))
			           AND b.bumuncd <> 'ZZ'
			         GROUP BY b.acccd) a
			 GROUP BY a.acccd
			 ORDER BY a.acccd
			""", p);

		// dw_4 지출: 예산(yyyy2, 관 7xxx) + 결산(yyyy 전표 차변, 7000~7999 · 8300~8399)
		List<Map<String, Object>> exp = sqlRunner.getRows("""
			SELECT a.acccd,
			       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = a.acccd) AS accnm,
			       SUM(a.amt1) AS amt1,
			       SUM(a.amt2) AS amt2
			  FROM (SELECT a.acccd, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
			          FROM TB_X0007 a
			         WHERE a.tiosec = '2'
			           AND a.custcd = :custcd
			           AND a.yyyy   = :yyyy2
			           AND LEFT(a.acccd, 1) = '7'
			         GROUP BY a.acccd
			        UNION
			        SELECT b.acccd, CAST(0 AS numeric(13,0)) AS amt1, SUM(b.dramt) AS amt2
			          FROM TB_AA009 a, TB_AA010 b
			         WHERE a.custcd   = b.custcd
			           AND a.spjangcd = b.spjangcd
			           AND a.spdate   = b.spdate
			           AND a.spnum    = b.spnum
			           AND b.drcr     = '1'
			           AND a.custcd   = :custcd
			           AND SUBSTRING(a.spdate, 1, 4) = :yyyy
			           AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8399'))
			           AND b.bumuncd <> 'ZZ'
			         GROUP BY b.acccd) a
			 GROUP BY a.acccd
			 ORDER BY a.acccd
			""", p);

		// dw_5 전기이월금: TB_AB002 관 3300, yymm = yyyy + '00' (원본은 첫 행만 쓴다)
		List<Map<String, Object>> carry = sqlRunner.getRows("""
			SELECT a.acccd, a.cramt AS amt1, a.cramt AS amt2
			  FROM TB_AB002 a
			 WHERE a.acccd  = '3300'
			   AND a.custcd = :custcd
			   AND a.yymm   = :yyyy + '00'
			""", p);

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("yyyy2", yyyy2);
		result.put("spjangnm", spjangnm);
		result.put("rows", build(rev, exp, carry));
		return result;
	}

	/**
	 * 사업계획서(사업별) — PowerBuilder w_tb_x0002_sh1 / d_tb_x0002_sh1 이식.
	 * 사업년도(bsdate)의 사업 목록을 부문 순서(TB_X0001.seqno)로 보여준다.
	 * 원본처럼 사용여부(useyn)로 거르지 않는다. 같은 부문 안에서는 원본 화면처럼 사업기간(FROM) 순(기간 없는 사업 먼저).
	 */
	public Map<String, Object> getPlanByProject(String yyyy) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("사업년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();

		List<Map<String, Object>> rows = sqlRunner.getRows("""
			SELECT a.bsdate, a.bseccd, b.bsecnm, a.busicd, a.businm,
			       a.frdate, a.todate, a.place, a.entno, a.useyn
			  FROM TB_X0002 a, TB_X0001 b
			 WHERE a.custcd = b.custcd
			   AND a.bseccd = b.bseccd
			   AND a.custcd = :custcd
			   AND a.bsdate = :yyyy
			 ORDER BY b.seqno, a.bseccd, ISNULL(a.frdate, ''), ISNULL(a.todate, ''), a.busicd
			""", new MapSqlParameterSource().addValue("custcd", custcd).addValue("yyyy", yyyy));

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}

	/**
	 * 사업계획서(월별) — PowerBuilder w_tb_x0002_sh2 / d_tb_x0002_sh2 이식.
	 * 월(sdate) = 사업기간(FROM)의 월. 정렬은 원본 그대로 사업기간(FROM) → 부문 순서(seqno), 같으면 사업코드.
	 */
	public Map<String, Object> getPlanByMonth(String yyyy) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("사업년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();

		List<Map<String, Object>> rows = sqlRunner.getRows("""
			SELECT a.bsdate, a.bseccd, b.bsecnm, a.busicd, a.businm,
			       a.frdate, a.todate, a.place, a.entno, a.useyn,
			       SUBSTRING(a.frdate, 5, 2) AS sdate
			  FROM TB_X0002 a, TB_X0001 b
			 WHERE a.custcd = b.custcd
			   AND a.bseccd = b.bseccd
			   AND a.custcd = :custcd
			   AND a.bsdate = :yyyy
			 ORDER BY a.frdate, b.seqno, a.bseccd, a.busicd
			""", new MapSqlParameterSource().addValue("custcd", custcd).addValue("yyyy", yyyy));

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}

	/**
	 * 세입의부 — PowerBuilder w_tb_x00071_sh1 / d_tb_x00071_sh1 이식.
	 * TB_X0007 세입(tiosec 1), 관 5000~5599, 재원 LIKE(전체면 '%'), 관 · 사업 · 항 · 목 단위 금액 합계.
	 * 원본 SQL 에는 사업장 조건이 없다(회사 전체). 정렬은 원본 그룹(관 → 항)에 맞춰 관 → 항 → 사업 → 목.
	 */
	public Map<String, Object> getRevenueLedger(String yyyy, String mssec) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		String ms = mssec == null || mssec.trim().isEmpty() ? "%" : mssec.trim();

		List<Map<String, Object>> rows = sqlRunner.getRows("""
			SELECT x.acccd,
			       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = x.custcd AND acccd = x.acccd) AS accnm,
			       x.it1cd,
			       (SELECT TOP 1 it1nm FROM VW_X0003 WHERE custcd = x.custcd AND tiosec = '1' AND it1cd = x.it1cd) AS it1nm,
			       x.it2cd,
			       (SELECT TOP 1 it2nm FROM TB_X0004 WHERE custcd = x.custcd AND tiosec = '1' AND it2cd = x.it2cd) AS it2nm,
			       x.bsdate, x.bseccd, x.busicd,
			       (SELECT businm FROM tb_x0002
			         WHERE bsdate = x.bsdate AND bseccd = x.bseccd AND busicd = x.busicd) AS businm,
			       x.price
			  FROM (SELECT a.custcd, a.acccd, a.it1cd, a.it2cd, a.bsdate, a.bseccd, a.busicd,
			               SUM(a.price) AS price
			          FROM TB_X0007 a
			         WHERE a.tiosec = '1'
			           AND a.acccd >= '5000'
			           AND a.acccd <= '5599'
			           AND a.custcd = :custcd
			           AND a.yyyy   = :yyyy
			           AND ISNULL(a.mssec, '') LIKE :mssec
			         GROUP BY a.custcd, a.acccd, a.bsdate, a.bseccd, a.busicd, a.it1cd, a.it2cd) x
			 ORDER BY x.acccd, ISNULL(x.it1cd, ''), ISNULL(x.bsdate, ''), ISNULL(x.bseccd, ''),
			          ISNULL(x.busicd, ''), ISNULL(x.it2cd, '')
			""", new MapSqlParameterSource().addValue("custcd", custcd).addValue("yyyy", yyyy).addValue("mssec", ms));

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}

	/**
	 * 세입내역서 — PowerBuilder w_tb_x00071_sh2 이식.
	 *  관항목(d_tb_x00071_sh2) · 재원표시(d_tb_x00071_sh21) 두 양식이 같은 행을 쓴다. 화면에서 모양만 바꾼다.
	 *  TB_X0007 세입(tiosec 1), 관 5000~5599, 재원 LIKE(전체면 '%').
	 *  원본 관항목 SQL 에는 사업장 조건이 없다(회사 전체).
	 *  정렬: 관 → 사업 코드 → 항 → 목 → 등록 순 (관항목 묶음 '관 + 사업 + 항' 이 끊기지 않도록)
	 */
	public Map<String, Object> getRevenueDetail(String yyyy, String mssec) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		String ms = mssec == null || mssec.trim().isEmpty() ? "%" : mssec.trim();

		List<Map<String, Object>> rows = sqlRunner.getRows("""
			SELECT a.acccd,
			       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = a.custcd AND acccd = a.acccd) AS accnm,
			       a.it1cd,
			       COALESCE((SELECT TOP 1 it1nm FROM VW_X0003 WHERE custcd = a.custcd AND tiosec = a.tiosec AND it1cd = a.it1cd),
			                (SELECT TOP 1 it1nm FROM TB_X0003 WHERE '00' + it1cd = a.it1cd AND tiosec = a.tiosec)) AS it1nm,
			       a.it2cd,
			       (SELECT TOP 1 it2nm FROM TB_X0004 WHERE custcd = a.custcd AND tiosec = a.tiosec AND it2cd = a.it2cd) AS it2nm,
			       a.summy, a.price,
			       a.mssec,
			       (SELECT mssecnm FROM tb_x0005 WHERE mssec = a.mssec) AS mssecnm,
			       a.bsdate, a.bseccd, a.busicd,
			       (SELECT businm FROM tb_x0002
			         WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm
			  FROM TB_X0007 a
			 WHERE a.tiosec = '1'
			   AND a.acccd >= '5000'
			   AND a.acccd <= '5599'
			   AND a.custcd = :custcd
			   AND a.yyyy   = :yyyy
			   AND ISNULL(a.mssec, '') LIKE :mssec
			 ORDER BY a.acccd, ISNULL(a.bsdate, ''), ISNULL(a.bseccd, ''), ISNULL(a.busicd, ''),
			          ISNULL(a.it1cd, ''), ISNULL(a.it2cd, ''), a.spdate, a.spnum
			""", new MapSqlParameterSource().addValue("custcd", custcd).addValue("yyyy", yyyy).addValue("mssec", ms));

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}

	/** 원본 ue_retrieve 를 그대로 옮긴 행 조립 */
	private List<Map<String, Object>> build(List<Map<String, Object>> rev, List<Map<String, Object>> exp,
																					List<Map<String, Object>> carry) {
		List<Map<String, Object>> rows = new ArrayList<>();
		int n = Math.max(rev.size(), exp.size());
		if (n < 1) return rows;   // 원본: 조회 건수가 없으면 아무것도 표시하지 않는다

		for (int i = 0; i < n; i++) {
			Map<String, Object> r = new LinkedHashMap<>();
			if (i < rev.size()) {
				r.put("acc1", name(rev.get(i)));
				r.put("amt1", num(rev.get(i), "amt1"));   // 예산
				r.put("amt2", num(rev.get(i), "amt2"));   // 결산
			}
			if (i < exp.size()) {
				r.put("acc2", name(exp.get(i)));
				r.put("amt3", num(exp.get(i), "amt1"));   // 예산
				r.put("amt4", num(exp.get(i), "amt2"));   // 결산
			}
			rows.add(r);
		}

		BigDecimal revBud = sum(rev, "amt1"), revAct = sum(rev, "amt2");   // hap_amt1, hap_amt2 (수입)
		BigDecimal expBud = sum(exp, "amt1"), expAct = sum(exp, "amt2");   // hap_amt1, hap_amt2 (지출)

		Map<String, Object> tot = new LinkedHashMap<>();
		tot.put("kind", "sum");
		tot.put("acc1", "합계"); tot.put("amt1", revBud); tot.put("amt2", revAct);
		tot.put("acc2", "합계"); tot.put("amt3", expBud); tot.put("amt4", expAct);
		rows.add(tot);

		if (!carry.isEmpty()) {
			BigDecimal prev = num(carry.get(0), "amt2");   // dw_5.amt2[1]

			Map<String, Object> c = new LinkedHashMap<>();
			c.put("kind", "carry");
			BigDecimal prevBud = revAct.add(prev).subtract(expAct);
			c.put("acc1", "전기이월금");
			c.put("amt1", prevBud);
			c.put("amt2", prev);
			c.put("acc2", "차기이월금");
			c.put("amt3", revBud.add(prevBud).subtract(expBud));
			c.put("amt4", revAct.add(prev).subtract(expAct));
			rows.add(c);

			Map<String, Object> g = new LinkedHashMap<>();
			g.put("kind", "total");
			BigDecimal gBud = revBud.add(revAct).add(prev).subtract(expAct);
			BigDecimal gAct = revAct.add(prev);
			g.put("acc1", "총 계"); g.put("amt1", gBud); g.put("amt2", gAct);
			g.put("acc2", "총 계"); g.put("amt3", gBud); g.put("amt4", gAct);
			rows.add(g);
		}
		return rows;
	}

	private String name(Map<String, Object> m) {
		Object nm = m.get("accnm");
		if (nm != null && !String.valueOf(nm).trim().isEmpty()) return String.valueOf(nm).trim();
		Object cd = m.get("acccd");
		return cd == null ? "" : String.valueOf(cd).trim();
	}

	private BigDecimal num(Map<String, Object> m, String k) {
		Object v = m.get(k);
		if (v == null) return BigDecimal.ZERO;
		if (v instanceof BigDecimal) return (BigDecimal) v;
		try { return new BigDecimal(String.valueOf(v).trim()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
	}

	private BigDecimal sum(List<Map<String, Object>> list, String k) {
		BigDecimal s = BigDecimal.ZERO;
		for (Map<String, Object> m : list) s = s.add(num(m, k));
		return s;
	}

	/**
	 * 세출의부 — PowerBuilder w_tb_x00072_sh1 이식.
	 *  화면(d_tb_x00072_sh1)과 출력(d_tb_x00072_prt1) SQL 이 그룹·정렬만 달라 두 목록을 함께 돌려준다.
	 *  TB_X0007 세출(tiosec 2), TB_X0001 부문(항 코드 앞 2자리 = 부문) 과 내부 조인, 재원 LIKE(전체면 '%').
	 *  rows    : 화면(sh1) — 부문순서 → 재원 → 관 → 사업 → 항 → 목
	 *  prtRows : 출력(prt1) — 부문순서 → 관 → 사업 → 항 → 목
	 */
	public Map<String, Object> getExpenditureLedger(String yyyy, String mssec) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		String ms = mssec == null || mssec.trim().isEmpty() ? "%" : mssec.trim();
		MapSqlParameterSource ps = new MapSqlParameterSource()
																 .addValue("custcd", custcd).addValue("yyyy", yyyy).addValue("mssec", ms);

		String sql = """
			SELECT x.acccd,
			       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = x.custcd AND acccd = x.acccd) AS accnm,
			       x.it1cd,
			       (SELECT TOP 1 it1nm FROM VW_X0003 WHERE custcd = x.custcd AND tiosec = '2' AND it1cd = x.it1cd) AS it1nm,
			       x.it2cd,
			       (SELECT TOP 1 it2nm FROM TB_X0004 WHERE custcd = x.custcd AND tiosec = '2' AND it2cd = x.it2cd) AS it2nm,
			       x.bsdate, x.bseccd, x.busicd,
			       (SELECT businm FROM tb_x0002
			         WHERE bsdate = x.bsdate AND bseccd = x.bseccd AND busicd = x.busicd) AS businm,
			       x.mssec,
			       (SELECT mssecnm FROM tb_x0005 WHERE mssec = x.mssec) AS re_mssecnm,
			       x.price
			  FROM (SELECT a.custcd, a.acccd, a.it1cd, a.it2cd, b.seqno, a.bsdate, a.bseccd, a.busicd, a.mssec,
			               SUM(a.price) AS price
			          FROM TB_X0007 a, TB_X0001 b
			         WHERE a.custcd = b.custcd
			           AND SUBSTRING(a.it1cd, 1, 2) = b.bseccd
			           AND a.tiosec = '2'
			           AND a.custcd = :custcd
			           AND a.yyyy   = :yyyy
			           AND ISNULL(a.mssec, '') LIKE :mssec
			         GROUP BY a.custcd, a.acccd, a.bsdate, a.bseccd, a.busicd, a.it1cd, a.it2cd, b.seqno, a.mssec) x
			 ORDER BY {ORDER}
			""";
		List<Map<String, Object>> rows = sqlRunner.getRows(sql.replace("{ORDER}",
			"x.seqno, x.mssec, x.acccd, x.bsdate, x.bseccd, x.busicd, x.it1cd, x.it2cd"), ps);
		List<Map<String, Object>> prtRows = sqlRunner.getRows(sql.replace("{ORDER}",
			"x.seqno, x.acccd, x.bsdate, x.bseccd, x.busicd, x.it1cd, x.it2cd"), ps);

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		result.put("prtRows", prtRows);
		return result;
	}

	/**
	 * 세출내역서 — PowerBuilder w_tb_x00072_sh2 이식.
	 *  관별현황(d_tb_x00072_sh2) · 재원별현황(d_tb_x00072_prt330, 크로스탭) 이 같은 행을 쓴다. 화면에서 모양만 바꾼다.
	 *  TB_X0007 세출(tiosec 2), TB_X0001 부문(항 코드 앞 2자리 = 부문) 과 내부 조인, 재원 LIKE(전체면 '%').
	 *  정렬은 원본 sh2 그대로: 부문순서 → 재원 → 관 → 사업 → 항 → 목
	 *  prevTotal: 전년도 세출 예산 합계(원본 amt1, 출력 머리의 '전년도집행액')
	 */
	public Map<String, Object> getExpenditureDetail(String yyyy, String mssec) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) {
			throw new IllegalArgumentException("년도를 4자리로 입력하십시오.");
		}
		yyyy = yyyy.trim();
		String prev = String.format("%04d", Integer.parseInt(yyyy) - 1);
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		String ms = mssec == null || mssec.trim().isEmpty() ? "%" : mssec.trim();
		MapSqlParameterSource ps = new MapSqlParameterSource()
																 .addValue("custcd", custcd).addValue("yyyy", yyyy).addValue("prev", prev).addValue("mssec", ms);

		List<Map<String, Object>> rows = sqlRunner.getRows("""
			SELECT a.acccd,
			       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = a.custcd AND acccd = a.acccd) AS accnm,
			       a.it1cd,
			       COALESCE((SELECT TOP 1 it1nm FROM VW_X0003 WHERE custcd = a.custcd AND tiosec = a.tiosec AND it1cd = a.it1cd),
			                (SELECT TOP 1 it1nm FROM TB_X0003 WHERE '00' + it1cd = a.it1cd AND tiosec = a.tiosec)) AS it1nm,
			       a.it2cd,
			       (SELECT TOP 1 it2nm FROM TB_X0004 WHERE custcd = a.custcd AND tiosec = a.tiosec AND it2cd = a.it2cd) AS it2nm,
			       a.summy, a.price,
			       a.mssec,
			       (SELECT mssecnm FROM tb_x0005 WHERE mssec = a.mssec) AS mssecnm,
			       a.bsdate, a.bseccd, a.busicd,
			       (SELECT businm FROM tb_x0002
			         WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm
			  FROM TB_X0007 a, TB_X0001 b
			 WHERE a.custcd = b.custcd
			   AND SUBSTRING(a.it1cd, 1, 2) = b.bseccd
			   AND a.tiosec = '2'
			   AND a.custcd = :custcd
			   AND a.yyyy   = :yyyy
			   AND ISNULL(a.mssec, '') LIKE :mssec
			 ORDER BY b.seqno, a.mssec, a.acccd, a.bsdate, a.bseccd, a.busicd, a.it1cd, a.it2cd
			""", ps);
		Map<String, Object> pt = sqlRunner.getRow(
			"SELECT SUM(price) AS amt FROM TB_X0007 WHERE custcd = :custcd AND tiosec = '2' AND yyyy = :prev", ps);

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("prevTotal", pt == null || pt.get("amt") == null ? 0 : pt.get("amt"));
		result.put("rows", rows);
		return result;
	}
}