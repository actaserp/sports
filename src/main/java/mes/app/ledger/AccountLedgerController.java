package mes.app.ledger;

import lombok.extern.slf4j.Slf4j;
import mes.app.ledger.service.AccountLedgerService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/AccountLedger")
@Slf4j
public class AccountLedgerController { // 계좌별원장

	@Autowired
	AccountLedgerService accountLedgerService;

	// tab1 : 계좌별 집계 (계좌번호 기반)
	@GetMapping("/read")
	public AjaxResult searchSummary(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "accnum", required = false) String accnum,
		@RequestParam(value = "accid", required = false) String accid,
		@RequestParam(value = "bankid", required = false) String bankid,
		@RequestParam(value = "useyn", required = false) String useyn) {
		AjaxResult result = new AjaxResult();
		result.data = accountLedgerService.searchSummary(start, end, accnum, accid, bankid, useyn);
		return result;
	}

	// tab2 : 상세내역 마스터
	@GetMapping("/readDetailMaster")
	public AjaxResult searchDetailMaster(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "accnm", required = false) String accnm,
		@RequestParam(value = "spacc", required = false) String spacc) {
		AjaxResult result = new AjaxResult();
		result.data = accountLedgerService.selectDetailMasterList(start, end, acccd, it1cd, spacc);
		return result;
	}

	// tab3 : 일자별 내역
	@GetMapping("/readDetail")
	public AjaxResult searchDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "accnm", required = false) String accnm) {
		AjaxResult result = new AjaxResult();
		result.data = accountLedgerService.selectDetailList(start, end, acccd, it1cd, accnm);
		return result;
	}
	// tab4 : 계좌코드오류전표
	@GetMapping("/readErrorSlip")
	public AjaxResult searchErrorSlip(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = accountLedgerService.selectErrorSlipList(start, end, acccd);
		return result;
	}

	// 전표 팝업 : 헤더 + 분개
	@GetMapping("/readSlip")
	public AjaxResult searchSlip(
		@RequestParam(value = "yymmdd") String yymmdd,
		@RequestParam(value = "spnum") String spnum,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = accountLedgerService.selectSlip(yymmdd, spnum);
		return result;
	}
}