package mes.app.system.service;

import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 환경설정 (시스템관리 > 환경 설정) — 결재라인. 2026-11-13
 *  테넌트 DB TB_XENV (custcd + spjangcd 한 행)
 *   settle1~8 결재명 · setchk1~8 숨김 · junchk1~8 전결 · crolin1~8 사선
 *  출력 결재란은 SlipStatusController.resolveApproverList 가 이 값을 읽는다.
 */
@Service
public class PreferencesService {

	@Autowired
	SqlRunner sqlRunner;   // 테넌트 DB

	private static final String COLS;
	static {
		StringBuilder b = new StringBuilder();
		for (String k : new String[]{"settle", "setchk", "junchk", "crolin"})
			for (int i = 1; i <= 8; i++) b.append(b.length() == 0 ? "" : ", ").append(k).append(i);
		COLS = b.toString();
	}

	public Map<String, Object> readSettle() {
		MapSqlParameterSource p = keys();
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT TOP 1 " + COLS + " FROM TB_XENV WHERE custcd = :custcd AND spjangcd = :spjangcd", p);
		return row == null ? new java.util.HashMap<>() : row;
	}

	public void saveSettle(Map<String, String> in) {
		MapSqlParameterSource p = keys();
		StringBuilder set = new StringBuilder();
		for (int i = 1; i <= 8; i++) {
			String nm = in.getOrDefault("settle" + i, "");
			nm = nm == null ? "" : nm.trim();
			if (nm.getBytes(java.nio.charset.Charset.forName("MS949")).length > 10)
				throw new IllegalArgumentException(i + "번 결재명이 너무 깁니다. (10바이트, 한글 5자까지)");
			p.addValue("settle" + i, nm);
			for (String k : new String[]{"setchk", "junchk", "crolin"})
				p.addValue(k + i, "1".equals(in.get(k + i)) ? "1" : "0");
		}
		for (String k : new String[]{"settle", "setchk", "junchk", "crolin"})
			for (int i = 1; i <= 8; i++) set.append(set.length() == 0 ? "" : ", ").append(k).append(i).append(" = :").append(k).append(i);

		int n = sqlRunner.execute("UPDATE TB_XENV SET " + set + " WHERE custcd = :custcd AND spjangcd = :spjangcd", p);
		if (n == 0) {
			// 이 사업장 환경설정 행이 아직 없으면 결재라인만 넣어 만든다
			StringBuilder vals = new StringBuilder();
			for (String k : new String[]{"settle", "setchk", "junchk", "crolin"})
				for (int i = 1; i <= 8; i++) vals.append(", :").append(k).append(i);
			sqlRunner.execute("INSERT INTO TB_XENV (custcd, spjangcd, " + COLS + ") VALUES (:custcd, :spjangcd" + vals + ")", p);
		}
	}

	private MapSqlParameterSource keys() {
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow("SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		return new MapSqlParameterSource().addValue("custcd", custcd).addValue("spjangcd", spjangcd);
	}
}