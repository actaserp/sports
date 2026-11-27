package mes.app.payroll.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 사업소득원천징수영수증 (원본 w_j3013 / d_j3013_list · d_j3013_preview)
 * 기간 : 년간조회면 기준연 1.1 ~ 12.31, 아니면 기준연 1.1 ~ 기준연월 31일
 */
@Slf4j
@Service
public class BizIncomeReceiptService {

	private static final String CLAFI = BizIncomeEarnerService.CLAFI;   // '1' 사업소득

	@Autowired
	SqlRunner sqlRunner;

	private String custcd() {
		Map<String, Object> row = sqlRunner.getRow("SELECT custcd FROM tb_xa012 WHERE spjangcd = :s",
			new MapSqlParameterSource().addValue("s", TenantContext.get()));
		return row == null || row.get("custcd") == null ? "" : String.valueOf(row.get("custcd")).trim();
	}

	private static String digits(String v) { return v == null ? "" : v.replaceAll("[^0-9]", ""); }

	/** 원본 ue_retrieve 의 기간 계산 */
	public static String[] period(String acumy, String acumm, String yearly) {
		String y = digits(acumy), m = digits(acumm);
		if (y.length() != 4) throw new IllegalArgumentException("기준연을 입력하십시오.");
		if (m.isEmpty()) throw new IllegalArgumentException("기준월을 입력하십시오.");
		if (m.length() < 2 || Integer.parseInt(m) > 12 || Integer.parseInt(m) < 1)
			throw new IllegalArgumentException("기준월이 올바르지 않습니다.\n2자리수로 정확히 입력해 주십시요.");
		return "1".equals(yearly) ? new String[]{y + "0101", y + "1231"} : new String[]{y + "0101", y + m + "31"};
	}

	/** 왼쪽 목록 (d_j3013_list) */
	public List<Map<String, Object>> list(String acumy, String acumm, String yearly) {
		String[] p = period(acumy, acumm, yearly);
		return sqlRunner.getRows("""
			SELECT DISTINCT A.seq, A.saupnum, A.foreryn, A.rnum, A.forernum, A.comname, A.pername
			  FROM TB_J3010 A
			  JOIN TB_J3011 B ON B.custcd = A.custcd AND B.seq = A.seq
			 WHERE A.custcd = :custcd AND A.clafi = :clafi
			   AND B.paydate BETWEEN :fr AND :to
			 ORDER BY A.comname, A.pername   -- 원본 d_j3013_list 정렬 (상호 → 성명)
			""", new MapSqlParameterSource().addValue("custcd", custcd()).addValue("clafi", CLAFI)
			.addValue("fr", p[0]).addValue("to", p[1]));
	}

	/** 인쇄 자료 (원본 ds_j3013_paybasic) : 고른 소득자들의 지급내역, 소득자 · 지급일 순 */
	public List<Map<String, Object>> printRows(List<String> seqs, String acumy, String acumm, String yearly) {
		String[] p = period(acumy, acumm, yearly);
		if (seqs == null || seqs.isEmpty()) throw new IllegalArgumentException("선택된 자료가 없습니다.");
		return sqlRunner.getRows("""
			SELECT A.seq, A.saupnum, A.comname, A.resclafi, A.foreryn, A.rnum, A.forernum, A.pername,
			       A.comaddr1, A.comaddr2, A.addr1, A.addr2, A.jobclafi,
			       B.saupnum AS b_saupnum, B.compnm, B.prenm, B.corpnum, B.adresa, B.adresb,
			       C.paybasic, C.paydate, C.paysum, C.taxrate, C.incotax, C.rtax
			  FROM TB_J3010 A
			  JOIN TB_XA012 B ON B.custcd = A.custcd AND B.spjangcd = :spjangcd
			  JOIN TB_J3011 C ON C.custcd = A.custcd AND C.seq = A.seq
			 WHERE A.custcd = :custcd AND A.clafi = :clafi AND C.seq IN (:seqs)
			   AND C.paydate BETWEEN :fr AND :to
			 ORDER BY A.seq, C.paydate
			""", new MapSqlParameterSource().addValue("custcd", custcd()).addValue("clafi", CLAFI)
			.addValue("spjangcd", TenantContext.get()).addValue("seqs", seqs)
			.addValue("fr", p[0]).addValue("to", p[1]));
	}

	/** 오른쪽 지급내역 (d_j3013_preview) — 인쇄 양식용 회사 · 소득자 정보도 함께 */
	public List<Map<String, Object>> detail(String seq, String acumy, String acumm, String yearly) {
		String[] p = period(acumy, acumm, yearly);
		return sqlRunner.getRows("""
			SELECT A.seq, A.condate, A.saupnum, A.comname, A.resclafi, A.foreryn, A.rnum, A.forernum, A.pername,
			       A.comzipcd, A.comaddr1, A.comaddr2, A.zipcd, A.addr1, A.addr2, A.jobclafi, A.conyn,
			       B.saupnum AS b_saupnum, B.compnm, B.prenm, B.corpnum, B.adresa, B.adresb,
			       C.paybasic, C.paydate, C.payclafi, C.paysum, C.taxrate, C.incotax, C.rtax, C.chagamt
			  FROM TB_J3010 A
			  JOIN TB_XA012 B ON B.custcd = A.custcd AND B.spjangcd = :spjangcd
			  JOIN TB_J3011 C ON C.custcd = A.custcd AND C.seq = A.seq
			 WHERE A.custcd = :custcd AND A.clafi = :clafi AND C.seq = :seq
			   AND C.paydate BETWEEN :fr AND :to
			 ORDER BY C.paydate, C.paybasic, C.payclafi
			""", new MapSqlParameterSource().addValue("custcd", custcd()).addValue("clafi", CLAFI)
			.addValue("spjangcd", TenantContext.get()).addValue("seq", seq)
			.addValue("fr", p[0]).addValue("to", p[1]));
	}
}
