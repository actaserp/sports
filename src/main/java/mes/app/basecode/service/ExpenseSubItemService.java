package mes.app.basecode.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 세출목등록 (원본 w_tb_x00042 / d_tb_x00042_1 · d_tb_x0004_print) — TB_X0004, tiosec = '2'
 */
@Slf4j
@Service
public class ExpenseSubItemService {

	@Autowired
	SqlRunner sqlRunner;

	private Map<String, String> biz() {
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", TenantContext.get()));
		Map<String, String> m = new HashMap<>();
		m.put("custcd", row == null || row.get("custcd") == null ? "" : String.valueOf(row.get("custcd")).trim());
		m.put("spjangnm", row == null || row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim());
		return m;
	}

	public String spjangnm() { return biz().get("spjangnm"); }

	private static String str(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v == null ? "" : v.toString().trim();
	}

	private int count(String sql, MapSqlParameterSource p) {
		Map<String, Object> r = sqlRunner.getRow(sql, p);
		return r == null || r.get("cnt") == null ? 0 : Integer.parseInt(String.valueOf(r.get("cnt")).trim());
	}

	/** ue_retrieve : 목코드 또는 목명에 검색어가 들어간 세출목 */
	public List<Map<String, Object>> list(String keyword) {
		String kw = keyword == null ? "" : keyword.trim();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz().get("custcd"))
																.addValue("it2cd", kw.isEmpty() ? "%" : kw);
		return sqlRunner.getRows("""
			SELECT a.custcd, a.tiosec, a.it2cd, a.it2nm, a.useyn
			  FROM TB_X0004 a
			 WHERE a.tiosec = '2'
			   AND a.custcd = :custcd
			   AND (a.it2cd LIKE '%' + :it2cd + '%' OR a.it2nm LIKE '%' + :it2cd + '%')
			 ORDER BY a.it2cd
			""", p);
	}

	/**
	 * ue_save : 목명이 빈 새 행은 버린다.
	 * 새 행에 목코드를 적었으면 그대로, 비었으면 max(it2cd)+1 (3자리, 없으면 001).
	 */
	@Transactional
	public Map<String, Object> save(List<Map<String, Object>> rows) {
		String custcd = biz().get("custcd");
		int inserted = 0, updated = 0;

		for (Map<String, Object> r : rows) {
			boolean isNew = "1".equals(str(r, "_new"));
			if (!isNew && str(r, "it2nm").isEmpty())
				throw new IllegalArgumentException("목코드 " + str(r, "it2cd") + " 의 목명을 입력하세요.");
			if (str(r, "it2nm").length() > 100)
				throw new IllegalArgumentException("목명이 너무 깁니다. (" + str(r, "it2cd") + ")");
			String cd = str(r, "it2cd");
			if (isNew && !cd.isEmpty() && !cd.matches("\\d{1,3}"))
				throw new IllegalArgumentException("목코드는 3자리 이하 숫자로 입력하세요. (" + cd + ")");
		}

		MapSqlParameterSource base = new MapSqlParameterSource().addValue("custcd", custcd);
		Map<String, Object> mx = sqlRunner.getRow(
			"SELECT MAX(it2cd) AS mx FROM TB_X0004 WITH (UPDLOCK, HOLDLOCK) WHERE tiosec = '2' AND custcd = :custcd", base);
		String last = mx == null || mx.get("mx") == null ? "" : String.valueOf(mx.get("mx")).trim();
		long next = last.isEmpty() ? 1 : Long.parseLong(last.replaceAll("\\D", "").isEmpty() ? "0" : last.replaceAll("\\D", "")) + 1;
		Set<String> used = new HashSet<>();

		for (Map<String, Object> r : rows) {
			String nm = str(r, "it2nm");
			String useyn = "0".equals(str(r, "useyn")) ? "0" : "1";
			MapSqlParameterSource p = new MapSqlParameterSource()
																	.addValue("custcd", custcd).addValue("it2nm", nm).addValue("useyn", useyn);

			if ("1".equals(str(r, "_new"))) {
				if (nm.isEmpty()) continue;                       // 목명 없는 새 행은 버린다
				String cd = str(r, "it2cd");
				if (cd.isEmpty()) {
					do {
						if (next > 999) throw new IllegalStateException("목코드(3자리)를 더 만들 수 없습니다.");
						cd = String.format("%03d", next++);
					} while (used.contains(cd));
				} else {
					cd = String.format("%03d", Long.parseLong(cd));
				}
				p.addValue("it2cd", cd);
				if (!used.add(cd) || count("SELECT COUNT(*) AS cnt FROM TB_X0004 WHERE tiosec = '2' AND custcd = :custcd AND it2cd = :it2cd", p) > 0)
					throw new IllegalStateException("이미 있는 목코드입니다. (" + cd + ")");
				sqlRunner.execute("""
					INSERT INTO TB_X0004 (custcd, tiosec, it2cd, it2nm, useyn)
					VALUES (:custcd, '2', :it2cd, :it2nm, :useyn)
					""", p);
				inserted++;
			} else {
				p.addValue("it2cd", str(r, "it2cd"));
				sqlRunner.execute("""
					UPDATE TB_X0004 SET it2nm = :it2nm, useyn = :useyn
					 WHERE tiosec = '2' AND custcd = :custcd AND it2cd = :it2cd
					""", p);
				updated++;
			}
		}
		Map<String, Object> res = new HashMap<>();
		res.put("inserted", inserted);
		res.put("updated", updated);
		return res;
	}

	/** ue_delete : 예산 · 예/결산 · 전표에서 쓰는 목이면 막는다  */
	@Transactional
	public void delete(String it2cd) {
		if (it2cd == null || it2cd.trim().isEmpty()) throw new IllegalArgumentException("삭제할 목코드가 없습니다.");
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz().get("custcd")).addValue("it2cd", it2cd.trim());

		String[][] checks = {
			{"SELECT COUNT(*) AS cnt FROM TB_X0007 WHERE tiosec = '2' AND custcd = :custcd AND it2cd = :it2cd", "예산관리(세출내역서등록)"},
			{"SELECT COUNT(*) AS cnt FROM TB_X0008 WHERE tiosec = '2' AND custcd = :custcd AND it2cd = :it2cd", "예/결산내역서(세출내역서등록)"},
			{"""
			 SELECT COUNT(*) AS cnt
			   FROM TB_AA009 a, TB_AA010 b
			  WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
			    AND b.tiosec = '2' AND a.custcd = :custcd AND a.it2cd = :it2cd
			 """, "전표입력"},
		};
		for (String[] c : checks) {
			if (count(c[0], p) > 0) throw new IllegalStateException("선택하신 코드는 " + c[1] + "에서 사용하고 있습니다.");
		}
		sqlRunner.execute("DELETE FROM TB_X0004 WHERE tiosec = '2' AND custcd = :custcd AND it2cd = :it2cd", p);
	}
}