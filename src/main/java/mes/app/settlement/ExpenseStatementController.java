package mes.app.settlement;

import lombok.extern.slf4j.Slf4j;
import mes.app.settlement.service.ExpenseStatementService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/ExpenseStatement")
@Slf4j
public class ExpenseStatementController { // 세출내역서

	@Autowired
	ExpenseStatementService expenseStatementService;

	// 탭1 : 관별내역서
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
		result.data = expenseStatementService.searchTab1(spdate1, spdate2, mssec, acccd, it1cd, it2cd, flag);
		return result;
	}

	// 탭2 : 재원별현황(관항목)
	@GetMapping("/readFund")
	public AjaxResult readFund(
		@RequestParam(value = "spdate1") String spdate1,
		@RequestParam(value = "spdate2") String spdate2,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd) {
		AjaxResult result = new AjaxResult();
		result.data = expenseStatementService.searchFund(spdate1, spdate2, mssec, acccd, it1cd, it2cd);
		return result;
	}

	// 탭3 : 재원별현황(관항) — 사업 필터 + 재원 크로스탭 (화면에서 관/항 그룹)
	@GetMapping("/readFund2")
	public AjaxResult readFund2(
		@RequestParam(value = "spdate1") String spdate1,
		@RequestParam(value = "spdate2") String spdate2,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd) {
		AjaxResult result = new AjaxResult();
		result.data = expenseStatementService.searchFundBusi(
			spdate1, spdate2, mssec, acccd, it1cd, it2cd, bsdate, bseccd, busicd);
		return result;
	}

	// 탭4 : 재원별현황(사업별) — 사업 필터 + 재원 크로스탭 (화면에서 사업명 그룹)
	@GetMapping("/readFund3")
	public AjaxResult readFund3(
		@RequestParam(value = "spdate1") String spdate1,
		@RequestParam(value = "spdate2") String spdate2,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd) {
		AjaxResult result = new AjaxResult();
		result.data = expenseStatementService.searchFundBusi(
			spdate1, spdate2, mssec, acccd, it1cd, it2cd, bsdate, bseccd, busicd);
		return result;
	}

	// 전표 팝업
	@GetMapping("/readSlip")
	public AjaxResult readSlip(
		@RequestParam(value = "yymmdd") String yymmdd,
		@RequestParam(value = "spnum") String spnum,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = expenseStatementService.selectSlip(yymmdd, spnum);
		return result;
	}
}
