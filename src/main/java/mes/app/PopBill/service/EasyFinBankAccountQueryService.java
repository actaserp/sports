package mes.app.PopBill.service;

import com.popbill.api.easyfin.EasyFinBankSearchDetail;
import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EasyFinBankAccountQueryService {

	@Autowired
	SqlRunner sqlRunner;

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
            spjangcd
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
            popflag = 'Y',
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
	@Async
	public void saveBankDataAsync(
		List<EasyFinBankSearchDetail> list,
		String jobID,
		String accountNumber,
		String custcd,
		String bank,
		String bankcd,
		String bankname,
		String spjangcd) {

		if (list == null || list.isEmpty()) {
			log.info("저장할 거래내역이 없습니다.");
			return;
		}

		// 1. 중복 체크용 tid 목록 추출
		List<String> tidList = list.stream()
														 .map(EasyFinBankSearchDetail::getTid)
														 .toList();

		List<Map<String, Object>> existingList = getExistingTids(custcd, spjangcd, bankcd, tidList);
		List<String> existingTids = existingList.stream()
																	.map(row -> (String) row.get("fintech_use_num"))
																	.toList();

		int savedCount = 0;
		int skipCount  = 0;

		for (EasyFinBankSearchDetail detail : list) {
			String tid = detail.getTid();

			// 2. 중복 스킵
			if (existingTids.contains(tid)) {
				skipCount++;
				continue;
			}

			try {
				MapSqlParameterSource param = new MapSqlParameterSource();
				param.addValue("custcd",           custcd);
				param.addValue("spjangcd",         spjangcd);
				param.addValue("bnkcode",          bankcd);
				param.addValue("fintech_use_num",  tid);
				param.addValue("tran_date",        detail.getTrdate());   // 거래일자
				param.addValue("tran_time",        detail.getTrdt());     // 거래시간
				param.addValue("tran_amt",         detail.getAccIn());    // 입금액
				param.addValue("wdr_amt",          detail.getAccOut());   // 출금액
				param.addValue("after_balance_amt",detail.getBalance());  // 잔액
				param.addValue("print_content",    detail.getRemark1());  // 적요
				param.addValue("bank_cd",          bank);
				param.addValue("bank_nm",          bankname);
				param.addValue("remark1",          detail.getRemark1());
				param.addValue("remark2",          detail.getRemark2());
				param.addValue("remark3",          detail.getRemark3());
				param.addValue("remark4",          detail.getRemark4());
				param.addValue("accnum",           accountNumber);

				String sql = """
                INSERT INTO TB_bank_accsave (
                    custcd, spjangcd, bnkcode, fintech_use_num,
                    tran_date, tran_time,
                    tran_amt, wdr_amt, after_balance_amt,
                    print_content,
                    bank_cd, bank_nm,
                    remark1, remark2, remark3, remark4,
                    accnum
                ) VALUES (
                    :custcd, :spjangcd, :bnkcode, :fintech_use_num,
                    :tran_date, :tran_time,
                    :tran_amt, :wdr_amt, :after_balance_amt,
                    :print_content,
                    :bank_cd, :bank_nm,
                    :remark1, :remark2, :remark3, :remark4,
                    :accnum
                )
                """;

				sqlRunner.execute(sql, param);
				savedCount++;

			} catch (Exception e) {
				log.error("거래내역 저장 실패 tid={}, error={}", tid, e.getMessage());
			}
		}

		log.info("거래내역 저장 완료 - 저장: {}건, 스킵: {}건", savedCount, skipCount);
	}

	// 중복 tid 조회
	private List<Map<String, Object>> getExistingTids(
		String custcd, String spjangcd, String bankcd, List<String> tidList) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd",   custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("bnkcode",  bankcd);
		param.addValue("tidList",  tidList);

		String sql = """
        SELECT fintech_use_num
        FROM TB_bank_accsave
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND bnkcode  = :bnkcode
          AND fintech_use_num IN (:tidList)
        """;

		return sqlRunner.getRows(sql, param);
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
}
