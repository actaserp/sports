package mes.app.basecode.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 재원구분등록 (원본 w_tb_x0005 / d_tb_x0005)
 */
@Slf4j
@Service
public class MssecService {

	@Autowired
	SqlRunner sqlRunner;

	private String custcd() {
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", TenantContext.get()));
		return row == null || row.get("custcd") == null ? "" : String.valueOf(row.get("custcd")).trim();
	}

	private String str(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v == null ? "" : v.toString().trim();
	}

	/** ue_retrieve : 코드 또는 이름에 검색어가 들어간 재원 */
	public List<Map<String, Object>> list(String keyword) {
		String kw = (keyword == null || keyword.trim().isEmpty()) ? "%" : keyword.trim();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd())
																.addValue("mssec", kw);
		return sqlRunner.getRows("""
			SELECT a.custcd, a.mssec, a.mssecnm, a.rem, a.sortnum
			  FROM TB_X0005 a
			 WHERE a.custcd = :custcd
			   AND (a.mssec LIKE '%' + :mssec + '%' OR a.mssecnm LIKE '%' + :mssec + '%')
			 ORDER BY a.mssec
			""", p);
	}

	/**
	 * ue_save : 새 행은 코드를 max(mssec)+1 (3자리, 없으면 001) 로 매기고, 기존 행은 이름·정렬순서·비고만 고친다.
	 * 재원명이 빈 새 행은 원본처럼 버린다.
	 */
	@Transactional
	public Map<String, Object> save(List<Map<String, Object>> rows) {
		String custcd = custcd();
		int inserted = 0, updated = 0;

		// 기존 행 검사 (원본 ue_datachk 대응 : 재원명 필수)
		for (Map<String, Object> r : rows) {
			boolean isNew = str(r, "mssec").isEmpty();
			if (!isNew && str(r, "mssecnm").isEmpty()) {
				throw new IllegalArgumentException("재원코드 " + str(r, "mssec") + " 의 재원명을 입력하세요.");
			}
		}

		Map<String, Object> mx = sqlRunner.getRow(
			"SELECT MAX(mssec) AS mx FROM TB_X0005 WITH (UPDLOCK, HOLDLOCK) WHERE custcd = :custcd",
			new MapSqlParameterSource().addValue("custcd", custcd));
		String last = mx == null || mx.get("mx") == null ? "" : String.valueOf(mx.get("mx")).trim();
		long next = last.isEmpty() ? 1 : Long.parseLong(last) + 1;

		for (Map<String, Object> r : rows) {
			String mssec = str(r, "mssec");
			String nm = str(r, "mssecnm");
			String sortStr = str(r, "sortnum");
			MapSqlParameterSource p = new MapSqlParameterSource()
																	.addValue("custcd", custcd)
																	.addValue("mssecnm", nm)
																	.addValue("rem", str(r, "rem").isEmpty() ? null : str(r, "rem"))
																	.addValue("sortnum", sortStr.isEmpty() ? null : Integer.valueOf(sortStr.replaceAll("[^0-9-]", "")));

			if (mssec.isEmpty()) {
				if (nm.isEmpty()) continue;          // 이름 없는 새 행은 버린다
				if (next > 999) throw new IllegalStateException("재원코드(3자리)를 더 만들 수 없습니다.");
				p.addValue("mssec", String.format("%03d", next++));
				sqlRunner.execute("""
					INSERT INTO TB_X0005 (custcd, mssec, mssecnm, rem, sortnum)
					VALUES (:custcd, :mssec, :mssecnm, :rem, :sortnum)
					""", p);
				inserted++;
			} else {
				p.addValue("mssec", mssec);
				sqlRunner.execute("""
					UPDATE TB_X0005 SET mssecnm = :mssecnm, rem = :rem, sortnum = :sortnum
					 WHERE custcd = :custcd AND mssec = :mssec
					""", p);
				updated++;
			}
		}
		Map<String, Object> res = new HashMap<>();
		res.put("inserted", inserted);
		res.put("updated", updated);
		return res;
	}

	/** ue_delete : 예산·예/결산·전표에서 쓰는 코드면 막는다 */
	@Transactional
	public void delete(String mssec) {
		if (mssec == null || mssec.trim().isEmpty()) throw new IllegalArgumentException("삭제할 재원코드가 없습니다.");
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd()).addValue("mssec", mssec.trim());

		String[][] checks = {
			{"SELECT COUNT(*) AS cnt FROM TB_X0007 WHERE tiosec = '1' AND custcd = :custcd AND mssec = :mssec", "예산관리(세입내역서등록)"},
			{"SELECT COUNT(*) AS cnt FROM TB_X0008 WHERE tiosec = '1' AND custcd = :custcd AND mssec = :mssec", "예/결산내역서(세입내역서등록)"},
			{"SELECT COUNT(*) AS cnt FROM TB_X0007 WHERE tiosec = '2' AND custcd = :custcd AND mssec = :mssec", "예산관리(세출내역서등록)"},
			{"SELECT COUNT(*) AS cnt FROM TB_X0008 WHERE tiosec = '2' AND custcd = :custcd AND mssec = :mssec", "예/결산내역서(세출내역서등록)"},
			{"SELECT COUNT(*) AS cnt FROM TB_AA009 WHERE custcd = :custcd AND mssec = :mssec", "전표등록"},
		};
		for (String[] c : checks) {
			Map<String, Object> r = sqlRunner.getRow(c[0], p);
			int cnt = r == null || r.get("cnt") == null ? 0 : Integer.parseInt(String.valueOf(r.get("cnt")).trim());
			if (cnt > 0) throw new IllegalStateException("선택하신 코드는 " + c[1] + "에서 사용하고 있습니다.");
		}
		sqlRunner.execute("DELETE FROM TB_X0005 WHERE custcd = :custcd AND mssec = :mssec", p);
	}
}