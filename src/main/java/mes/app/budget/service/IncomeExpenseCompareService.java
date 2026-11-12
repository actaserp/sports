package mes.app.budget.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 세입세출대조표 — PowerBuilder w_tb_aa009_sh11 이식.
 *  dw_3 d_tb_aa009_sh11_1 (세입부) · dw_4 d_tb_aa009_sh11_2 (세출부) 를 좌우로 붙이고 → 화면 · 출력 d_tb_aa009_sh11_4
 *  세입합계 / 세출합계 → 전기이월금 / 차기이월금 → 세입총계 / 세출총계 행은 원본 ue_retrieve 식 그대로.
 *  원본은 wf_iwolamt 를 부르지만 그 결과를 어디에도 쓰지 않으므로 계산하지 않는다. (dw_5 sh11_3 도 조회만 하고 쓰지 않는다)
 *  정렬은 원본 Sort 설정을 받기 전까지 accseq → 관코드 순.
 */
@Slf4j
@Service
public class IncomeExpenseCompareService {

	@Autowired
	SqlRunner sqlRunner;

	// ── d_tb_aa009_sh11_1 세입부 ──
	private static final String SQL_REV = """
		SELECT x.acccd, x.seq, x.amt,
		       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm
		  FROM (
		  SELECT b.acccd, c.accseq AS seq, SUM(b.cramt) - SUM(b.dramt) AS amt
		    FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND b.acccd = c.acccd
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		     AND b.bumuncd <> 'ZZ'
		     AND ((b.acccd BETWEEN '5000' AND '5599') OR (b.acccd BETWEEN '8100' AND '8299'))
		   GROUP BY b.acccd, c.accseq
		  UNION ALL
		  SELECT A.acccd, A.seq,
		         CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) - SUM(A.ncramt) ELSE SUM(A.ncramt) - SUM(A.ndramt) END AS amt
		    FROM ( SELECT A.custcd AS custcd, A.acccd AS acccd, A.accnm AS accnm, A.drcr AS drcr, A.accseq AS seq,
		                  SUM(ISNULL(B.dramt, 0)) AS ndramt, SUM(ISNULL(B.cramt, 0)) AS ncramt
		             FROM TB_AC001 A, TB_AB001 B
		            WHERE A.custcd = B.custcd AND A.acccd = B.acccd
		              AND b.custcd = :custcd
		              AND b.spjangcd = :spjangcd
		              AND A.dcpl = '1' AND A.ipflag = '1'
		              AND B.yymmdd = :yyyy + '0000'
		            GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.accseq
		           UNION ALL
		           SELECT a.custcd, b.acccd, b.accnm AS accnm, c.drcr, c.accseq AS seq, B.dramt AS ndramt, 0 AS ncramt
		             FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND a.custcd = c.custcd AND b.acccd = c.acccd
		              AND c.ipflag = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		              AND b.bumuncd <> 'ZZ'
		              AND B.dramt <> 0 ) A
		   GROUP BY A.custcd, A.acccd, A.seq, A.accnm, A.drcr
		  ) x
		 ORDER BY x.seq, x.acccd
		""";

	// ── d_tb_aa009_sh11_2 세출부 ──
	private static final String SQL_EXP = """
		SELECT x.acccd, x.seq, x.amt,
		       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm
		  FROM (
		  SELECT b.acccd, c.accseq AS seq, SUM(b.dramt) - SUM(b.cramt) AS amt
		    FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND b.acccd = c.acccd
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		     AND b.bumuncd <> 'ZZ'
		     AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8499'))
		   GROUP BY b.acccd, c.accseq
		  UNION ALL
		  SELECT A.acccd, A.seq, SUM(A.ncramt) AS amt
		    FROM ( SELECT a.custcd, b.acccd, b.accnm AS accnm, c.drcr, c.accseq AS seq, 0 AS ndramt, B.cramt AS ncramt
		             FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND a.custcd = c.custcd AND b.acccd = c.acccd
		              AND c.chulflag = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		              AND b.bumuncd <> 'ZZ'
		              AND B.cramt <> 0 ) A
		   GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.seq
		  ) x
		 ORDER BY x.seq, x.acccd
		""";

	// 원본: 전기이월금 (TB_AB002 이월 계정 차변 - 대변)
	private static final String SQL_PREAMT = """
		SELECT SUM(A.dramt) - SUM(A.cramt) AS amt
		  FROM TB_AB002 A, TB_AC001 B
		 WHERE A.acccd = B.acccd AND B.ewolyn = '1'
		   AND a.yymm = :yyyy + '00'
		   AND a.custcd = :custcd AND a.spjangcd = :spjangcd
		""";

	public Map<String, Object> read(String yyyy) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) throw new IllegalArgumentException("년도를 4자리로 입력하십시오.");
		yyyy = yyyy.trim();
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		MapSqlParameterSource ps = new MapSqlParameterSource()
																 .addValue("custcd", custcd).addValue("spjangcd", spjangcd).addValue("yyyy", yyyy);

		List<Map<String, Object>> rev = sqlRunner.getRows(SQL_REV, ps);
		List<Map<String, Object>> exp = sqlRunner.getRows(SQL_EXP, ps);
		List<Map<String, Object>> rows = new ArrayList<>();
		int n = Math.max(rev.size(), exp.size());
		if (n > 0) {   // 원본: 두 쪽 다 없으면 아무것도 표시하지 않는다
			for (int i = 0; i < n; i++) {
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("kind", "d");
				if (i < rev.size()) { r.put("acc1", name(rev.get(i))); r.put("amt1", num(rev.get(i), "amt")); }
				if (i < exp.size()) { r.put("acc2", name(exp.get(i))); r.put("amt2", num(exp.get(i), "amt")); }
				rows.add(r);
			}
			BigDecimal su = sum(rev), ji = sum(exp);
			BigDecimal pre = num(sqlRunner.getRow(SQL_PREAMT, ps), "amt");
			BigDecimal next = pre.add(su).subtract(ji);
			rows.add(row("s", "세입합계", su, "세출합계", ji));
			rows.add(row("c", "전기이월금", pre, "차기이월금", next));
			rows.add(row("t", "세입총계", su.add(pre), "세출총계", ji.add(next)));
		}

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}

	private static Map<String, Object> row(String kind, String l1, BigDecimal a1, String l2, BigDecimal a2) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("kind", kind);
		r.put("acc1", l1); r.put("amt1", a1);
		r.put("acc2", l2); r.put("amt2", a2);
		return r;
	}

	private static String s(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

	private static String name(Map<String, Object> m) { String x = s(m.get("accnm")); return x.isEmpty() ? s(m.get("acccd")) : x; }

	private static BigDecimal num(Map<String, Object> m, String k) {
		if (m == null || m.get(k) == null) return BigDecimal.ZERO;
		Object v = m.get(k);
		return v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(String.valueOf(v));
	}

	private static BigDecimal sum(List<Map<String, Object>> list) {
		BigDecimal t = BigDecimal.ZERO;
		for (Map<String, Object> m : list) t = t.add(num(m, "amt"));
		return t;
	}
}