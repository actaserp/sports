package mes.app.ledger;

import lombok.extern.slf4j.Slf4j;
import mes.app.settlement.service.IncomeStatementService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/IncomeStatement")
@Slf4j
public class IncomeStatementController { // 세입내역서

	@Autowired
	IncomeStatementService incomeStatementService;

	// 세입내역서 조회
	//   spdate1/spdate2 : yyyyMM (기간)
	//   flag : '1'이면 항 컬럼에 사업명 표기(businm), 그 외 it1nm
	@GetMapping("/read")
	public AjaxResult read(
		@RequestParam(value = "spdate1") String spdate1,
		@RequestParam(value = "spdate2") String spdate2,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd,
		@RequestParam(value = "flag", required = false) String flag) {
		AjaxResult result = new AjaxResult();
		result.data = incomeStatementService.searchList(spdate1, spdate2, mssec, acccd, it1cd, it2cd, flag);
		return result;
	}

	// 전표 팝업 : 헤더 + 분개 (다른 원장과 동일 구조 {head, lines})
	@GetMapping("/readSlip")
	public AjaxResult readSlip(
		@RequestParam(value = "yymmdd") String yymmdd,
		@RequestParam(value = "spnum") String spnum,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = incomeStatementService.selectSlip(yymmdd, spnum);
		return result;
	}
}