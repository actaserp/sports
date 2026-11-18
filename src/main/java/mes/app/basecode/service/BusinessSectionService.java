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
 * 사업구분등록 (원본 w_tb_x0001 / d_tb_x0001_1, 출력 d_tb_x0001_print)
 */
@Slf4j
@Service
public class BusinessSectionService {

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

	/** 출력 머리글용 사업장명 (원본 gf_spjangnm()) */
	public String spjangnm() {
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", TenantContext.get()));
		return row == null || row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim();
	}

	/** ue_retrieve : 코드 또는 이름에 검색어가 들어간 사업구분 ('00' 제외) */
	public List<Map<String, Object>> list(String keyword) {
		String kw = (keyword == null || keyword.trim().isEmpty()) ? "%" : keyword.trim();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd())
																.addValue("bseccd", kw);
		return sqlRunner.getRows("""
			SELECT a.custcd, a.bseccd, a.bsecnm, a.useyn, a.seqno
			  FROM TB_X0001 a
			 WHERE a.bseccd <> '00'
			   AND a.custcd = :custcd
			   AND (a.bseccd LIKE '%' + :bseccd + '%' OR a.bsecnm LIKE '%' + :bseccd + '%')
			 ORDER BY a.bseccd
			""", p);
	}

	/**
	 * ue_save : 새 행은 코드를 max(bseccd)+1 (2자리, '00' 제외, 없으면 01) 로 매기고, 기존 행은 이름·정렬순서·사용여부만 고친다.
	 * 사업구분명이 빈 새 행은 원본처럼 버린다.
	 */
	@Transactional
	public Map<String, Object> save(List<Map<String, Object>> rows) {
		String custcd = custcd();
		int inserted = 0, updated = 0;

		for (Map<String, Object> r : rows) {
			if (!str(r, "bseccd").isEmpty() && str(r, "bsecnm").isEmpty()) {
				throw new IllegalArgumentException("사업구분코드 " + str(r, "bseccd") + " 의 사업구분명을 입력하세요.");
			}
		}

		Map<String, Object> mx = sqlRunner.getRow(
			"SELECT MAX(bseccd) AS mx FROM TB_X0001 WITH (UPDLOCK, HOLDLOCK) WHERE bseccd <> '00' AND custcd = :custcd",
			new MapSqlParameterSource().addValue("custcd", custcd));
		String last = mx == null || mx.get("mx") == null ? "" : String.valueOf(mx.get("mx")).trim();
		long next = last.isEmpty() ? 1 : Long.parseLong(last) + 1;

		for (Map<String, Object> r : rows) {
			String code = str(r, "bseccd");
			String nm = str(r, "bsecnm");
			String seq = str(r, "seqno");
			String useyn = "0".equals(str(r, "useyn")) ? "0" : "1";
			MapSqlParameterSource p = new MapSqlParameterSource()
																	.addValue("custcd", custcd)
																	.addValue("bsecnm", nm)
																	.addValue("useyn", useyn)
																	.addValue("seqno", seq.isEmpty() ? null : Integer.valueOf(seq.replaceAll("[^0-9-]", "")));

			if (code.isEmpty()) {
				if (nm.isEmpty()) continue;
				if (next > 99) throw new IllegalStateException("사업구분코드(2자리)를 더 만들 수 없습니다.");
				p.addValue("bseccd", String.format("%02d", next++));
				sqlRunner.execute("""
					INSERT INTO TB_X0001 (custcd, bseccd, bsecnm, useyn, seqno)
					VALUES (:custcd, :bseccd, :bsecnm, :useyn, :seqno)
					""", p);
				inserted++;
			} else {
				p.addValue("bseccd", code);
				sqlRunner.execute("""
					UPDATE TB_X0001 SET bsecnm = :bsecnm, useyn = :useyn, seqno = :seqno
					 WHERE custcd = :custcd AND bseccd = :bseccd
					""", p);
				updated++;
			}
		}
		Map<String, Object> res = new HashMap<>();
		res.put("inserted", inserted);
		res.put("updated", updated);
		return res;
	}

	/** ue_delete : 사업계획(TB_X0002)에서 쓰는 코드면 막는다 */
	@Transactional
	public void delete(String bseccd) {
		if (bseccd == null || bseccd.trim().isEmpty()) throw new IllegalArgumentException("삭제할 사업구분코드가 없습니다.");
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd()).addValue("bseccd", bseccd.trim());
		Map<String, Object> r = sqlRunner.getRow(
			"SELECT COUNT(*) AS cnt FROM TB_X0002 WHERE custcd = :custcd AND bseccd = :bseccd", p);
		int cnt = r == null || r.get("cnt") == null ? 0 : Integer.parseInt(String.valueOf(r.get("cnt")).trim());
		if (cnt > 0) throw new IllegalStateException("선택하신 코드는 사업계획등록에서 사용하고 있습니다.");
		sqlRunner.execute("DELETE FROM TB_X0001 WHERE custcd = :custcd AND bseccd = :bseccd", p);
	}
}