package mes.app.payroll.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사업소득자등록 (원본 w_j3010 / d_j3010_list · d_j3010_input · d_j3010_list_exl)
 * 계좌현황   (원본 w_j3010_list / d_j3010_list02)
 * 테이블 TB_J3010  PK (custcd, clafi, seq)   clafi : 1 사업소득 / 2 기타소득
 */
@Slf4j
@Service
public class BizIncomeEarnerService {

	/** 이 화면이 다루는 소득구분 — 기타소득자등록은 '2' 로 같은 서비스를 쓸 수 있다 */
	public static final String CLAFI = "1";

	@Autowired
	SqlRunner sqlRunner;

	// ── 공통 ─────────────────────────────────────────────

	private Map<String, String> biz() {
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", TenantContext.get()));
		Map<String, String> m = new HashMap<>();
		m.put("custcd",   row == null || row.get("custcd")   == null ? "" : String.valueOf(row.get("custcd")).trim());
		m.put("spjangnm", row == null || row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim());
		return m;
	}

	private static String str(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v == null ? "" : v.toString().trim();
	}

	private static String likeVal(String v) { return v == null || v.trim().isEmpty() ? "%" : v.trim(); }

	private static String digits(String v) { return v == null ? "" : v.replaceAll("[^0-9]", ""); }

	// ── 콤보 ─────────────────────────────────────────────

	/** 구분(com_cls 100) · 상세(com_cls 101) · 은행 */
	public Map<String, Object> codes() {
		Map<String, Object> m = new HashMap<>();
		m.put("gubun1", sqlRunner.getRows(
			"SELECT com_code AS v, com_cnam AS n FROM tb_ca510 WHERE com_cls = '100' AND com_code <> '00' ORDER BY com_code",
			new MapSqlParameterSource()));
		m.put("gubun2", sqlRunner.getRows(
			"SELECT com_code AS v, com_cnam AS n FROM tb_ca510 WHERE com_cls = '101' AND com_code <> '00' ORDER BY com_code",
			new MapSqlParameterSource()));
		m.put("bank", sqlRunner.getRows(
			"SELECT bankcd AS v, banknm AS n FROM TB_XBANK WHERE subcd = '0000' ORDER BY bankcd",
			new MapSqlParameterSource()));
		m.put("spjangnm", biz().get("spjangnm"));
		return m;
	}

	// ── 조회 ─────────────────────────────────────────────

	private static final String COLS = """
		SELECT custcd, clafi, seq, condate, saupnum, comname, resclafi, foreryn, rnum, forernum, pername,
		       comzipcd, comaddr1, comaddr2, zipcd, addr1, addr2, jobclafi,
		       ISNULL(conyn, '0') AS conyn, gubun1, gubun2, bankcd,
		       (SELECT TOP 1 banknm FROM TB_XBANK WHERE bankcd = TB_J3010.bankcd AND subcd = '0000') AS banknm,
		       accnum
		  FROM TB_J3010
		""";

	/** 원본 d_j3010_list / d_j3010_list02 조회 조건 (conyn 은 두 화면 모두 ISNULL(conyn,'0') 로 비교) */
	public List<Map<String, Object>> list(String pernm, String rnum, String clafi, String conyn,
	                                      String gubun1, String gubun2) {
		MapSqlParameterSource p = new MapSqlParameterSource()
			.addValue("custcd", biz().get("custcd"))
			.addValue("clafi",  likeVal(clafi))
			.addValue("conyn",  likeVal(conyn))
			.addValue("comnm",  likeVal(pernm))
			.addValue("gubun1", likeVal(gubun1))
			.addValue("gubun2", likeVal(gubun2))
			.addValue("rnum",   likeVal(digits(rnum).isEmpty() ? rnum : digits(rnum)));
		return sqlRunner.getRows(COLS + """
			 WHERE custcd = :custcd
			   AND clafi LIKE :clafi + '%'
			   AND ISNULL(conyn, '0') LIKE :conyn + '%'
			   AND comname LIKE :comnm + '%'
			   AND (gubun1 LIKE :gubun1 + '%' OR gubun1 IS NULL)
			   AND (gubun2 LIKE :gubun2 + '%' OR gubun2 IS NULL)
			   AND (rnum LIKE :rnum + '%' OR forernum LIKE :rnum + '%')
			 ORDER BY clafi, seq
			""", p);
	}

	private Map<String, Object> one(String custcd, String clafi, String seq) {
		return sqlRunner.getRow(COLS + " WHERE custcd = :custcd AND clafi = :clafi AND seq = :seq",
			new MapSqlParameterSource().addValue("custcd", custcd).addValue("clafi", clafi).addValue("seq", seq));
	}

	// ── 저장 (원본 ue_Validation + ue_save) ─────────────────

	/**
	 * @param force "1" 이면 「이미 등록된 사업자번호」 확인을 사용자가 [예] 한 것
	 * @return {seq} 또는 확인이 필요하면 {confirm:'saupnum', seq:기존번호}
	 */
	@Transactional
	public Map<String, Object> save(Map<String, Object> r, String force) {
		String custcd = biz().get("custcd");
		String seq    = str(r, "seq");
		boolean isNew = seq.isEmpty();

		String condate  = digits(str(r, "condate"));
		String saupnum  = digits(str(r, "saupnum"));
		String comname  = str(r, "comname");
		String rnum     = digits(str(r, "rnum"));
		String forernum = str(r, "forernum");
		String foreryn  = str(r, "foreryn");

		// 계약일자 : 값이 있으면 올바른 날짜
		if (!condate.isEmpty()) {
			try { LocalDate.parse(condate, DateTimeFormatter.BASIC_ISO_DATE); }
			catch (Exception e) { throw new IllegalArgumentException("계약일자가 올바르지 않습니다."); }
		}
		// 사업자번호 : 새로 등록할 때만 번호 규칙 확인 (원본과 같음)
		if (!saupnum.isEmpty()) {
			if (saupnum.length() != 10) throw new IllegalArgumentException("사업자등록번호는 10자리입니다.");
			if (isNew && !validSaupnum(saupnum)) throw new IllegalArgumentException("사업자등록번호가 올바르지 않습니다.");
		}
		if (comname.isEmpty()) throw new IllegalArgumentException("사업소득자명칭을 입력하십시오.");
		if (comname.length() > 50) throw new IllegalArgumentException("사업소득자명칭은 50자까지 입력할 수 있습니다.");
		if (!rnum.isEmpty() && rnum.length() != 13) throw new IllegalArgumentException("주민등록번호를 13자리 모두 입력하십시오.");
		// 주민번호 체크섬(gf_check_reginum)은 2020.10 이후 발급번호에 맞지 않을 수 있어 화면에서 확인만 받는다.

		// 계약중인 같은 주민번호 / 외국인번호 — 새로 등록하거나 번호를 바꿀 때만 막는다
		Map<String, Object> before = isNew ? null : one(custcd, CLAFI, seq);
		if (!isNew && before == null) throw new IllegalStateException("수정할 자료를 찾을 수 없습니다. 다시 조회하세요.");
		checkDup(custcd, seq, "rnum", rnum, before == null ? "" : digits(str(before, "rnum")), "주민등록번호");
		checkDup(custcd, seq, "forernum", forernum, before == null ? "" : str(before, "forernum"), "외국인번호");

		MapSqlParameterSource p = new MapSqlParameterSource()
			.addValue("custcd", custcd).addValue("clafi", CLAFI)
			.addValue("condate", condate).addValue("saupnum", saupnum).addValue("comname", comname)
			.addValue("resclafi", nz(str(r, "resclafi"), "1")).addValue("foreryn", nz(foreryn, "1"))
			.addValue("rnum", rnum).addValue("forernum", forernum)
			.addValue("pername", str(r, "pername"))
			.addValue("comzipcd", str(r, "comzipcd")).addValue("comaddr1", str(r, "comaddr1")).addValue("comaddr2", str(r, "comaddr2"))
			.addValue("zipcd", str(r, "zipcd")).addValue("addr1", str(r, "addr1")).addValue("addr2", str(r, "addr2"))
			.addValue("jobclafi", nz(str(r, "jobclafi"), "49"))
			.addValue("conyn", "1".equals(str(r, "conyn")) ? "1" : "0")
			.addValue("gubun1", nullIfEmpty(str(r, "gubun1"))).addValue("gubun2", nullIfEmpty(str(r, "gubun2")))
			.addValue("bankcd", nullIfEmpty(str(r, "bankcd"))).addValue("accnum", str(r, "accnum"));

		Map<String, Object> result = new HashMap<>();

		if (isNew) {
			// 같은 사업자번호가 있으면 「이미 등록되었습니다. 계속 진행하시겠습니까?」
			if (!saupnum.isEmpty() && !"1".equals(force)) {
				Map<String, Object> d = sqlRunner.getRow(
					"SELECT TOP 1 seq FROM TB_J3010 WHERE custcd = :custcd AND clafi = :clafi AND saupnum = :saupnum",
					new MapSqlParameterSource().addValue("custcd", custcd).addValue("clafi", CLAFI).addValue("saupnum", saupnum));
				if (d != null && d.get("seq") != null) {
					result.put("confirm", "saupnum");
					result.put("seq", String.valueOf(d.get("seq")).trim());
					return result;
				}
			}
			seq = nextSeq(custcd, CLAFI);
			p.addValue("seq", seq);
			sqlRunner.execute("""
				INSERT INTO TB_J3010 (custcd, clafi, seq, condate, saupnum, comname, resclafi, foreryn, rnum, forernum, pername,
				                      comzipcd, comaddr1, comaddr2, zipcd, addr1, addr2, jobclafi, conyn, gubun1, gubun2, bankcd, accnum)
				VALUES (:custcd, :clafi, :seq, :condate, :saupnum, :comname, :resclafi, :foreryn, :rnum, :forernum, :pername,
				        :comzipcd, :comaddr1, :comaddr2, :zipcd, :addr1, :addr2, :jobclafi, :conyn, :gubun1, :gubun2, :bankcd, :accnum)
				""", p);
		} else {
			p.addValue("seq", seq);
			sqlRunner.execute("""
				UPDATE TB_J3010
				   SET condate = :condate, saupnum = :saupnum, comname = :comname, resclafi = :resclafi, foreryn = :foreryn,
				       rnum = :rnum, forernum = :forernum, pername = :pername,
				       comzipcd = :comzipcd, comaddr1 = :comaddr1, comaddr2 = :comaddr2,
				       zipcd = :zipcd, addr1 = :addr1, addr2 = :addr2, jobclafi = :jobclafi, conyn = :conyn,
				       gubun1 = :gubun1, gubun2 = :gubun2, bankcd = :bankcd, accnum = :accnum
				 WHERE custcd = :custcd AND clafi = :clafi AND seq = :seq
				""", p);
		}
		result.put("seq", seq);
		return result;
	}

	/** 계약중(conyn 0)인 다른 사람이 같은 번호를 쓰고 있으면 막는다. 번호를 바꾸지 않았으면 검사하지 않는다(기존 중복 자료 보호). */
	private void checkDup(String custcd, String seq, String col, String val, String beforeVal, String label) {
		if (val.isEmpty() || val.equals(beforeVal)) return;
		Map<String, Object> d = sqlRunner.getRow(
			"SELECT TOP 1 comname FROM TB_J3010 WHERE custcd = :custcd AND clafi = :clafi AND " + col + " = :val "
				+ "AND ISNULL(conyn, '0') = '0' AND seq <> :seq",
			new MapSqlParameterSource().addValue("custcd", custcd).addValue("clafi", CLAFI)
				.addValue("val", val).addValue("seq", seq));
		if (d != null)
			throw new IllegalStateException(str(d, "comname") + " : 계약중인 동일한 " + label + "가 있습니다. 확인하세요.");
	}

	/** 등록번호 : 오늘날짜(8) + (그 소득구분 최대 seq 끝 5자리 + 1). 동시에 저장해도 겹치지 않게 잠그고 읽는다. */
	private String nextSeq(String custcd, String clafi) {
		Map<String, Object> m = sqlRunner.getRow(
			"SELECT MAX(seq) AS seq FROM TB_J3010 WITH (UPDLOCK, HOLDLOCK) WHERE custcd = :custcd AND clafi = :clafi",
			new MapSqlParameterSource().addValue("custcd", custcd).addValue("clafi", clafi));
		String max = m == null || m.get("seq") == null ? "" : String.valueOf(m.get("seq")).trim();
		String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
		long n = 1;
		if (max.length() >= 5) {
			try { n = Long.parseLong(max.substring(max.length() - 5)) + 1; } catch (NumberFormatException ignore) { n = 1; }
		}
		return today + String.format("%05d", n);
	}

	// ── 삭제 ─────────────────────────────────────────────

	@Transactional
	public void delete(String seq) {
		if (seq == null || seq.isBlank()) throw new IllegalArgumentException("삭제할 자료가 없습니다.");
		String custcd = biz().get("custcd");
		Map<String, Object> c = sqlRunner.getRow(
			"SELECT COUNT(*) AS cnt FROM TB_J3011 WHERE spjangcd = :spjangcd AND seq = :seq",
			new MapSqlParameterSource().addValue("spjangcd", TenantContext.get()).addValue("seq", seq.trim()));
		if (c != null && c.get("cnt") != null && ((Number) c.get("cnt")).intValue() > 0)
			throw new IllegalStateException("등록된 급여자료가 있습니다.");
		sqlRunner.execute("DELETE FROM TB_J3010 WHERE custcd = :custcd AND clafi = :clafi AND seq = :seq",
			new MapSqlParameterSource().addValue("custcd", custcd).addValue("clafi", CLAFI).addValue("seq", seq.trim()));
	}

	// ── 복사 (사업소득 ↔ 기타소득) ─────────────────────────

	@Transactional
	public String copy(String clafi, String seq) {
		String custcd = biz().get("custcd");
		Map<String, Object> src = one(custcd, clafi, seq);
		if (src == null) throw new IllegalStateException("복사할 자료를 찾을 수 없습니다.");
		String to = "1".equals(clafi) ? "2" : "1";

		String saupnum = str(src, "saupnum"), rnum = str(src, "rnum");
		MapSqlParameterSource q = new MapSqlParameterSource().addValue("custcd", custcd).addValue("to", to);
		if (!saupnum.isEmpty() && sqlRunner.getRow(
			"SELECT TOP 1 seq FROM TB_J3010 WHERE custcd = :custcd AND clafi = :to AND saupnum = :v", q.addValue("v", saupnum)) != null)
			throw new IllegalStateException("이미 등록되었습니다.");
		if (!rnum.isEmpty() && sqlRunner.getRow(
			"SELECT TOP 1 seq FROM TB_J3010 WHERE custcd = :custcd AND clafi = :to AND rnum = :v", q.addValue("v", rnum)) != null)
			throw new IllegalStateException("이미 등록되었습니다.");

		String newSeq = nextSeq(custcd, to);
		sqlRunner.execute("""
			INSERT INTO TB_J3010 (custcd, clafi, seq, condate, saupnum, comname, resclafi, foreryn, rnum, forernum, pername,
			                      comzipcd, comaddr1, comaddr2, zipcd, addr1, addr2, jobclafi, conyn, gubun1, gubun2, bankcd, accnum)
			SELECT custcd, :to, :newSeq, condate, saupnum, comname, resclafi, foreryn, rnum, forernum, pername,
			       comzipcd, comaddr1, comaddr2, zipcd, addr1, addr2, jobclafi, conyn, gubun1, gubun2, bankcd, accnum
			  FROM TB_J3010
			 WHERE custcd = :custcd AND clafi = :clafi AND seq = :seq
			""", new MapSqlParameterSource().addValue("custcd", custcd).addValue("clafi", clafi).addValue("seq", seq)
			.addValue("to", to).addValue("newSeq", newSeq));
		return newSeq;
	}

	// ── 번호 규칙 ────────────────────────────────────────

	/** 사업자등록번호 끝자리 검증 (국세청 규칙) */
	public static boolean validSaupnum(String n) {
		if (n == null || !n.matches("\\d{10}")) return false;
		int[] w = {1, 3, 7, 1, 3, 7, 1, 3, 5};
		int sum = 0;
		for (int i = 0; i < 9; i++) sum += (n.charAt(i) - '0') * w[i];
		sum += ((n.charAt(8) - '0') * 5) / 10;
		return (10 - sum % 10) % 10 == n.charAt(9) - '0';
	}

	private static String nz(String v, String d) { return v == null || v.isEmpty() ? d : v; }

	private static String nullIfEmpty(String v) { return v == null || v.isEmpty() ? null : v; }
}
