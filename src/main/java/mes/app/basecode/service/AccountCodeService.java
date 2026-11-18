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
 * 계정과목등록 (원본 w_tb_ac001 : d_tb_ac001_1 계정정보 / _2 계정및양식명칭 / _3 관리항목 / _6 세입·세출 및 이월)
 * 원본은 네 탭이 같은 자료를 함께 쓰므로(조회 · 저장 모두 idw_tab[1]) 여기서도 한 번에 조회 · 저장한다.
 */
@Slf4j
@Service
public class AccountCodeService {

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
	private static Object nz(String v) { return v == null || v.isEmpty() ? null : v; }
	private static String flag(Map<String, Object> m, String k) { return "1".equals(str(m, k)) ? "1" : "0"; }

	/** 조회 (원본 ue_retrieve · d_tb_ac001_1) — 빈 조건은 '%' */
	public List<Map<String, Object>> list(String stext, String drcr, String dcpl, String spyn, String useyn) {
		return list(stext, drcr, dcpl, spyn, useyn, "0000", "9999");
	}

	/** 범위 조회 — 세입관등록(w_tb_ac001_2)은 5100 ~ 5599 */
	public List<Map<String, Object>> list(String stext, String drcr, String dcpl, String spyn, String useyn, String fraccd, String toaccd) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz().get("custcd"))
																.addValue("stext", stext == null || stext.trim().isEmpty() ? "%" : "%" + stext.trim() + "%")
																.addValue("drcr", drcr == null || drcr.isEmpty() ? "%" : drcr)
																.addValue("dcpl", dcpl == null || dcpl.isEmpty() ? "%" : dcpl)
																.addValue("spyn", spyn == null || spyn.isEmpty() ? "%" : spyn)
																.addValue("useyn", useyn == null || useyn.isEmpty() ? "%" : useyn)
																.addValue("fraccd", fraccd).addValue("toaccd", toaccd);
		return sqlRunner.getRows("""
			SELECT custcd, acccd, accnm, uacccd,
			       dbo.fn_get_name('TB_AC001', uacccd) AS uaccnm,
			       acclv, drcr, dcpl, spyn, cacccd,
			       dbo.fn_get_name('TB_AC001', cacccd) AS caccnm,
			       formnm, lossnm, fraccd, toaccd, delyn, section, mssec, spacc, useyn,
			       cltflag, divflag, acnflag, cardflag, ewolyn, accseq, i7000, ipflag, chulflag
			  FROM TB_AC001
			 WHERE custcd = :custcd
			   AND (acccd LIKE :stext OR accnm LIKE :stext)
			   AND drcr LIKE :drcr
			   AND dcpl LIKE :dcpl
			   AND spyn LIKE :spyn
			   AND useyn LIKE :useyn + '%'
			   AND acccd BETWEEN :fraccd AND :toaccd
			 ORDER BY acccd
			""", p);
	}

	/** 저장 (원본 wf_save) — 새 행은 INSERT(코드는 화면에서 원본 ue_new 규칙으로 정함), 나머지는 UPDATE */
	@Transactional
	public Map<String, Object> save(List<Map<String, Object>> rows) {
		return save(rows, "0000", "9999");
	}

	/** 범위 저장 — 범위 밖 코드는 받지 않는다 */
	@Transactional
	public Map<String, Object> save(List<Map<String, Object>> rows, String fraccd, String toaccd) {
		for (Map<String, Object> r : rows) {
			String c = str(r, "acccd");
			if (c.compareTo(fraccd) < 0 || c.compareTo(toaccd) > 0)
				throw new IllegalArgumentException("등록 가능한 계정코드 범위(" + fraccd + " ~ " + toaccd + ")를 벗어났습니다. (" + c + ")");
		}
		String custcd = biz().get("custcd");
		int ins = 0, upd = 0;

		// ue_validation 대응 : 계정명칭 필수, 새 코드 중복 금지
		for (Map<String, Object> r : rows) {
			String acccd = str(r, "acccd");
			if (!acccd.matches("\\d{4}")) throw new IllegalArgumentException("계정코드가 올바르지 않습니다. (" + acccd + ")");
			if (str(r, "accnm").isEmpty()) throw new IllegalArgumentException("계정코드 " + acccd + " 의 계정명칭을 입력하세요.");
			if (str(r, "accnm").length() > 100) throw new IllegalArgumentException("계정코드 " + acccd + " 의 계정명칭은 100자까지입니다.");
			if (str(r, "formnm").length() > 30 || str(r, "lossnm").length() > 30)
				throw new IllegalArgumentException("계정코드 " + acccd + " 의 양식명칭은 30자까지입니다.");
			String seq = str(r, "accseq");
			if (!seq.isEmpty() && !seq.matches("\\d{1,3}"))
				throw new IllegalArgumentException("계정코드 " + acccd + " 의 출력순서는 0~999 숫자로 입력하세요.");
			for (String k : new String[]{"uacccd", "cacccd"}) {
				String c = str(r, k);
				if (!c.isEmpty() && !c.matches("\\d{1,4}"))
					throw new IllegalArgumentException("계정코드 " + acccd + " 의 " + ("uacccd".equals(k) ? "상위계정" : "차감계정") + " 코드가 올바르지 않습니다.");
			}
		}

		for (Map<String, Object> r : rows) {
			MapSqlParameterSource p = new MapSqlParameterSource()
																	.addValue("custcd", custcd)
																	.addValue("acccd", str(r, "acccd"))
																	.addValue("accnm", str(r, "accnm"))
																	.addValue("uacccd", nz(str(r, "uacccd")))
																	.addValue("acclv", str(r, "acclv").isEmpty() ? null : Integer.valueOf(str(r, "acclv")))
																	.addValue("drcr", nz(str(r, "drcr")))
																	.addValue("dcpl", nz(str(r, "dcpl")))
																	.addValue("spyn", flag(r, "spyn"))
																	.addValue("useyn", flag(r, "useyn"))
																	.addValue("cacccd", nz(str(r, "cacccd")))
																	.addValue("formnm", nz(str(r, "formnm")))
																	.addValue("lossnm", nz(str(r, "lossnm")))
																	.addValue("i7000", flag(r, "i7000"))
																	.addValue("cltflag", flag(r, "cltflag"))
																	.addValue("acnflag", flag(r, "acnflag"))
																	.addValue("cardflag", flag(r, "cardflag"))
																	.addValue("divflag", flag(r, "divflag"))
																	.addValue("ewolyn", flag(r, "ewolyn"))
																	.addValue("accseq", str(r, "accseq").isEmpty() ? null : Integer.valueOf(str(r, "accseq")))
																	.addValue("ipflag", flag(r, "ipflag"))
																	.addValue("chulflag", flag(r, "chulflag"))
																	// 새 행에서만 쓰는 값 (원본 ue_new : 고른 계정에서 복사)
																	.addValue("fraccd", nz(str(r, "fraccd")))
																	.addValue("toaccd", nz(str(r, "toaccd")))
																	.addValue("delyn", nz(str(r, "delyn")))
																	.addValue("section", nz(str(r, "section")))
																	.addValue("mssec", nz(str(r, "mssec")))
																	.addValue("spacc", nz(str(r, "spacc")));

			if ("1".equals(str(r, "_new"))) {
				Map<String, Object> dup = sqlRunner.getRow(
					"SELECT COUNT(*) AS cnt FROM TB_AC001 WITH (UPDLOCK, HOLDLOCK) WHERE custcd = :custcd AND acccd = :acccd", p);
				if (dup != null && Integer.parseInt(String.valueOf(dup.get("cnt")).trim()) > 0)
					throw new IllegalStateException("이미 있는 계정코드입니다. (" + str(r, "acccd") + ") 다시 조회한 뒤 신규로 추가하세요.");
				sqlRunner.execute("""
					INSERT INTO TB_AC001 (custcd, acccd, accnm, uacccd, acclv, drcr, dcpl, spyn, cacccd,
					                      formnm, lossnm, fraccd, toaccd, delyn, section, mssec, spacc, useyn,
					                      cltflag, divflag, acnflag, cardflag, ewolyn, accseq, i7000, ipflag, chulflag)
					VALUES (:custcd, :acccd, :accnm, :uacccd, :acclv, :drcr, :dcpl, :spyn, :cacccd,
					        :formnm, :lossnm, :fraccd, :toaccd, :delyn, :section, :mssec, :spacc, :useyn,
					        :cltflag, :divflag, :acnflag, :cardflag, :ewolyn, :accseq, :i7000, :ipflag, :chulflag)
					""", p);
				ins++;
			} else {
				sqlRunner.execute("""
					UPDATE TB_AC001
					   SET accnm = :accnm, uacccd = :uacccd, drcr = :drcr, dcpl = :dcpl, spyn = :spyn,
					       cacccd = :cacccd, formnm = :formnm, lossnm = :lossnm, useyn = :useyn,
					       cltflag = :cltflag, divflag = :divflag, acnflag = :acnflag, cardflag = :cardflag,
					       ewolyn = :ewolyn, accseq = :accseq, i7000 = :i7000, ipflag = :ipflag, chulflag = :chulflag
					 WHERE custcd = :custcd AND acccd = :acccd
					""", p);
				upd++;
			}
		}
		Map<String, Object> res = new HashMap<>();
		res.put("inserted", ins);
		res.put("updated", upd);
		return res;
	}

	/** 삭제 (원본 ue_delete) — 전표 라인(TB_AA010)에 쓰인 계정은 막는다 */
	/** 세입관 삭제 (원본 w_tb_ac001_2 ue_delete) — 전표계정이 아닌(spyn='0') 계정과 범위 밖 계정은 막는다 */
	@Transactional
	public void deleteInRange(String acccd, String fraccd, String toaccd) {
		if (acccd.compareTo(fraccd) < 0 || acccd.compareTo(toaccd) > 0)
			throw new IllegalArgumentException("삭제 할 수 없는 계정입니다.");
		Map<String, Object> r = sqlRunner.getRow(
			"SELECT spyn FROM TB_AC001 WHERE custcd = :custcd AND acccd = :acccd",
			new MapSqlParameterSource().addValue("custcd", biz().get("custcd")).addValue("acccd", acccd));
		if (r == null) throw new IllegalStateException("없는 계정코드입니다. 다시 조회하세요.");
		if ("0".equals(String.valueOf(r.get("spyn")).trim()))
			throw new IllegalStateException("삭제 할 수 없는 계정입니다.");
		delete(acccd);
	}

	@Transactional
	public void delete(String acccd) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", biz().get("custcd")).addValue("acccd", acccd);
		Map<String, Object> r = sqlRunner.getRow(
			"SELECT COUNT(acccd) AS cnt FROM TB_AA010 WHERE custcd = :custcd AND acccd = :acccd", p);
		if (r != null && Integer.parseInt(String.valueOf(r.get("cnt")).trim()) > 0)
			throw new IllegalStateException("이미 사용된 계정코드입니다. 삭제 할 수 없습니다.");
		sqlRunner.execute("DELETE FROM TB_AC001 WHERE custcd = :custcd AND acccd = :acccd", p);
	}
}