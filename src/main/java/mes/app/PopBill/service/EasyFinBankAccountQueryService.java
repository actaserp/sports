package mes.app.PopBill.service;

import com.popbill.api.easyfin.EasyFinBankSearchDetail;
import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EasyFinBankAccountQueryService {

	@Autowired
	SqlRunner sqlRunner;

	@Qualifier("extraSqlRunner")
	@Autowired
	SqlRunner extraSqlRunner;

	public Map<String, Object> getAccountInfo(String custcd, String bank, String bankcd) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("bank",   bank);
		param.addValue("bankcd", bankcd);

		String sql = """
        SELECT
            custcd,
            bank,
            bankcd,
            accnum,
            banknm,
            bnkpaypw,
            bnkid,
            bnkpw,
            cmsid,
            cmspw,
            accname,
            popsort,
            accbirthday,
            popflag,
            spjangcd,
             popuserid
        FROM tb_aa040
        WHERE custcd = :custcd
          AND bank   = :bank
          AND bankcd = :bankcd
        """;

		return sqlRunner.getRow(sql, param);
	}

	public int saveRegistAccount(Map<String, Object> params) {
		MapSqlParameterSource param = new MapSqlParameterSource(params);

		String sql = """
        UPDATE tb_aa040
        SET
            popflag = '1',
            bnkid   = :bnkid,
            cmsid   = :cmsid,
            cmspw   = :cmspw,
            accname = :accname
        WHERE custcd = :custcd
          AND bank   = :bank
          AND bankcd = :bankcd
        """;

		return sqlRunner.execute(sql, param);
	}

	public void saveBankDataAsync(
		List<EasyFinBankSearchDetail> list,
		String jobID,
		String accountNumber,
		String custcd,
		String bank,
		String bankcd,
		String bankname,
		String spjangcd) {

		// ✅ 비동기 스레드에 테넌트 컨텍스트 세팅 (없으면 dbKey=null로 라우팅 실패)
		TenantContext.set(spjangcd);
		log.info("===== saveBankDataAsync 시작 - spjangcd={}, custcd={} =====", spjangcd, custcd);

		try {
			if (list == null || list.isEmpty()) {
				log.info("저장할 거래내역이 없습니다.");
				return;
			}

			List<String> tidList = list.stream()
															 .map(EasyFinBankSearchDetail::getTid)
															 .toList();

			log.info("중복 체크 tid 총 {}건", tidList.size());

			List<Map<String, Object>> existingList = getExistingTids(custcd, spjangcd, bankcd, tidList);
			if (existingList == null) existingList = List.of();

			log.info("기존 저장된 tid 수: {}건", existingList.size());

			Set<String> existingTids = existingList.stream()
																	 .map(row -> (String) row.get("fintech_use_num"))
																	 .collect(Collectors.toSet());

			int savedCount = 0;
			int skipCount  = 0;

			for (EasyFinBankSearchDetail detail : list) {
				String tid = detail.getTid();

				if (existingTids.contains(tid)) {
					log.debug("중복 스킵 tid={}", tid);
					skipCount++;
					continue;
				}

				try {
					String trdt     = detail.getTrdt();
					String tranDate = (trdt != null && trdt.length() >= 8)
															? trdt.substring(0, 8) : detail.getTrdate();
					String tranTime = (trdt != null && trdt.length() >= 14)
															? trdt.substring(8, 14) : null;

					String accIn     = detail.getAccIn();
					String inoutType = (accIn != null && !accIn.isBlank() && !accIn.equals("0"))
															 ? "0" : "1";

					// ✅ 필드별 값 + 길이 로그
//					log.info("=== 필드 길이 체크 tid={} ===", tid);
//					log.info("custcd={}(len:{})",       custcd,              custcd != null ? custcd.length() : 0);
//					log.info("spjangcd={}(len:{})",     spjangcd,            spjangcd != null ? spjangcd.length() : 0);
//					log.info("bnkcode={}(len:{})",      bankcd,              bankcd != null ? bankcd.length() : 0);
//					log.info("fintech_use_num={}(len:{})", tid,              tid != null ? tid.length() : 0);
//					log.info("tran_date={}(len:{})",    tranDate,            tranDate != null ? tranDate.length() : 0);
//					log.info("tran_time={}(len:{})",    tranTime,            tranTime != null ? tranTime.length() : 0);
//					log.info("inout_type={}(len:{})",   inoutType,           inoutType != null ? inoutType.length() : 0);
//					log.info("tran_amt={}",             detail.getAccIn());
//					log.info("wdr_amt={}",              detail.getAccOut());
//					log.info("after_balance_amt={}",    detail.getBalance());
//					log.info("print_content={}(len:{})", detail.getRemark1(), detail.getRemark1() != null ? detail.getRemark1().length() : 0);
//					log.info("bank_cd={}(len:{})",      bank,                bank != null ? bank.length() : 0);
//					log.info("bank_nm={}(len:{})",      bankname,            bankname != null ? bankname.length() : 0);
//					log.info("remark1={}(len:{})",      detail.getRemark1(), detail.getRemark1() != null ? detail.getRemark1().length() : 0);
//					log.info("remark2={}(len:{})",      detail.getRemark2(), detail.getRemark2() != null ? detail.getRemark2().length() : 0);
//					log.info("remark3={}(len:{})",      detail.getRemark3(), detail.getRemark3() != null ? detail.getRemark3().length() : 0);
//					log.info("remark4={}(len:{})",      detail.getRemark4(), detail.getRemark4() != null ? detail.getRemark4().length() : 0);
//					log.info("accnum={}(len:{})",       accountNumber,       accountNumber != null ? accountNumber.length() : 0);
//					log.info("=== 필드 길이 체크 끝 ===");

					MapSqlParameterSource param = new MapSqlParameterSource();
					param.addValue("custcd",            custcd);
					param.addValue("spjangcd",          spjangcd);
					param.addValue("bnkcode",           bankcd);
					param.addValue("fintech_use_num",   tid);           // ✅ tid → PK
					param.addValue("bank_tran_id",      accountNumber); // ✅ 계좌번호
					param.addValue("tran_date",         tranDate);
					param.addValue("tran_time",         tranTime);
					param.addValue("inout_type",        inoutType);
					param.addValue("tran_amt",          parseBigDecimal(detail.getAccIn()));
					param.addValue("wdr_amt",           parseBigDecimal(detail.getAccOut()));
					param.addValue("after_balance_amt", detail.getBalance());
					param.addValue("print_content",     detail.getRemark1());
					param.addValue("bank_cd",           bank);
					param.addValue("bank_nm",           bankname);
					param.addValue("remark1",           detail.getRemark1());
					param.addValue("remark2",           detail.getRemark2());
					param.addValue("remark3",           detail.getRemark3());
					param.addValue("remark4",           detail.getRemark4());
					param.addValue("accnum",            accountNumber);

					String sql = """
                INSERT INTO TB_bank_accsave (
                    custcd, spjangcd, bnkcode, fintech_use_num,
                    bank_tran_id,
                    tran_date, tran_time, inout_type,
                    tran_amt, wdr_amt, after_balance_amt,
                    print_content, bank_cd, bank_nm,
                    remark1, remark2, remark3, remark4, accnum
                ) VALUES (
                    :custcd, :spjangcd, :bnkcode, :fintech_use_num,
                    :bank_tran_id,
                    :tran_date, :tran_time, :inout_type,
                    :tran_amt, :wdr_amt, :after_balance_amt,
                    :print_content, :bank_cd, :bank_nm,
                    :remark1, :remark2, :remark3, :remark4, :accnum
                )
                """;

					sqlRunner.execute(sql, param);
					log.info("저장 성공 tid={}", tid);
					savedCount++;

				} catch (Exception e) {
					log.error("거래내역 저장 실패 tid={}, error={}", tid, e.getMessage(), e);
				}
			}

			log.info("===== saveBankDataAsync 완료 - 저장: {}건, 스킵: {}건 =====", savedCount, skipCount);

		} finally {
			// ✅ 스레드 재사용 시 컨텍스트 오염 방지
			TenantContext.clear();
		}
	}

	// 5. 금액 파싱 유틸
	private BigDecimal parseBigDecimal(String value) {
		if (value == null || value.isBlank()) return BigDecimal.ZERO;
		try {
			return new BigDecimal(value.replaceAll(",", "").trim());
		} catch (NumberFormatException e) {
			log.warn("금액 파싱 실패: {}", value);
			return BigDecimal.ZERO;
		}
	}

	// 중복 tid 조회
	private List<Map<String, Object>> getExistingTids(
		String custcd, String spjangcd, String bankcd, List<String> tidList) {

		log.info("중복 tid 조회");

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd",   custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("bnkcode",  bankcd);
		param.addValue("tidList",  tidList);

		String sql = """
        SELECT fintech_use_num
        FROM tb_bank_accsave
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND bnkcode  = :bnkcode
          AND fintech_use_num IN (:tidList)
        """;

		try {
			List<Map<String, Object>> rows = sqlRunner.getRows(sql, param);
			return rows != null ? rows : List.of(); // ✅ null 방어
		} catch (Exception e) {
			log.error("getExistingTids 조회 실패: {}", e.getMessage());
			return List.of(); // ✅ 오류 시 빈 리스트 반환
		}
	}

	public Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
        select saupnum, custcd, spjangnm
        from tb_xa012
        where spjangcd = :spjangcd
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		Map<String, String> result = new HashMap<>();
		result.put("saupnum", "");
		result.put("custcd", "");
		result.put("spjangnm", "");

		if (row == null || row.isEmpty()) {
			return result;
		}

		Object saupnum = row.get("saupnum");
		Object custcd = row.get("custcd");
		Object spjangnm = row.get("spjangnm");

		result.put("saupnum", saupnum == null ? "" : String.valueOf(saupnum).trim());
		result.put("custcd", custcd == null ? "" : String.valueOf(custcd).trim());
		result.put("spjangnm", custcd == null ? "" : String.valueOf(spjangnm).trim());

		return result;
	}

	public void registerOnitErpPcode(String saupnum, String accnum, String banknm, String today) {
		// 1. actcd, cltcd 조회
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("saupnum", saupnum);

		String sql1 = """
        SELECT TOP 1 A.actcd, B.cltcd
        FROM tb_e601 A, tb_xclient B
        WHERE A.cltcd = B.cltcd
          AND B.saupnum = :saupnum
        """;

		Map<String, Object> actInfo = extraSqlRunner.getRow(sql1, param);
		if (actInfo == null) {
			log.warn("OnitErp actcd 조회 실패: saupnum={}", saupnum);
			return;
		}
		String actcd = (String) actInfo.get("actcd");
		String cltcd = (String) actInfo.get("cltcd");

		// 2. 계좌번호 중복 체크
		MapSqlParameterSource param2 = new MapSqlParameterSource();
		param2.addValue("actcd",  actcd);
		param2.addValue("accnum", accnum);

		String sql2 = """
        SELECT seq
        FROM TB_E101_PCODE WITH (NOLOCK)
        WHERE spjangcd = 'ZZ'
          AND actcd = :actcd
          AND psize = :accnum
        """;

		Map<String, Object> existing = extraSqlRunner.getRow(sql2, param2);
		if (existing != null) {
			log.info("이미 등록된 과금 레코드: actcd={}, accnum={}", actcd, accnum);
			return;
		}

		// 3. max seq 조회 → 새 seq 생성
		MapSqlParameterSource param3 = new MapSqlParameterSource();
		param3.addValue("actcd", actcd);

		String sql3 = """
        SELECT MAX(seq) AS seq
        FROM TB_E101_PCODE WITH (NOLOCK)
        WHERE spjangcd = 'ZZ'
          AND actcd = :actcd
        """;

		Map<String, Object> maxRow = extraSqlRunner.getRow(sql3, param3);
		String maxSeq = (maxRow != null) ? (String) maxRow.get("seq") : null;
		String newSeq = (maxSeq == null) ? "01"
											: String.format("%02d", Long.parseLong(maxSeq) + 1);

		// 4. INSERT
		MapSqlParameterSource param4 = new MapSqlParameterSource();
		param4.addValue("actcd",  actcd);
		param4.addValue("seq",    newSeq);
		param4.addValue("cltcd",  cltcd);
		param4.addValue("pname",  banknm + " 연동 서비스");
		param4.addValue("accnum", accnum);
		param4.addValue("indate", today);

		String sql4 = """
        INSERT INTO TB_E101_PCODE (
            custcd, spjangcd, actcd, seq, cltcd,
            pname, psize, qty, amt, indate,
            inperid, flag, samt, addamt, uamt
        ) VALUES (
            'onit_erp', 'ZZ', :actcd, :seq, :cltcd,
            :pname, :accnum, 1, 3300, :indate,
            'onit', '1', 3000, 300, 3000
        )
        """;

		extraSqlRunner.execute(sql4, param4);
		log.info("OnitErp 과금 등록 완료: actcd={}, seq={}, accnum={}", actcd, newSeq, accnum);
	}

	// 정액 정지
	public void updatePopflag(String custcd, String bank, String bankcd, String popflag) {
		Map<String, Object> params = new HashMap<>();
		params.put("custcd", custcd);
		params.put("bank", bank);
		params.put("bankcd", bankcd);
		params.put("popflag", popflag);

		String sql = """
         UPDATE tb_aa040
         SET
             popflag = :popflag
         WHERE custcd = :custcd
           AND bank   = :bank
           AND bankcd = :bankcd
         """;

		sqlRunner.execute(sql, new MapSqlParameterSource(params));
	}

	public int updateAccountInfo(Map<String, Object> params) {
		MapSqlParameterSource param = new MapSqlParameterSource(params);

		String sql = """
         UPDATE tb_aa040
         SET
             bnkpaypw = :bnkpaypw,
             bnkid    = :bnkid,
             accname  = :accname,
             cmsid    = :cmsid,
             cmspw    = :cmspw
         WHERE custcd = :custcd
           AND bank   = :bank
           AND bankcd = :bankcd
         """;

		return sqlRunner.execute(sql, param);
	}

}
