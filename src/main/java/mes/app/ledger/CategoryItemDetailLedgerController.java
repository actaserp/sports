package mes.app.ledger;

import lombok.extern.slf4j.Slf4j;
import mes.app.ledger.service.CategoryItemDetailLedgerService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/CategoryItemDetailLedger")
@Slf4j
public class CategoryItemDetailLedgerController { //관항목별원장

	@Autowired
	CategoryItemDetailLedgerService categoryItemDetailLedgerService;

	// tab1 좌측 : 관(계정) 목록
	@GetMapping("/read")
	public AjaxResult searchSummary(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemDetailLedgerService.searchSummary(start, end, mssec, acccd);
		return result;
	}

	// tab1 우측 : 선택 관의 항+목별 내역
	@GetMapping("/readItem")
	public AjaxResult searchItem(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemDetailLedgerService.searchItem(start, end, mssec, acccd, it1cd, it2cd);
		return result;
	}

	// ── tab2 : 상세내역 (관+항+목+전표일자, 마스터) ──
	@GetMapping("/readItemDetail")
	public AjaxResult searchItemDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemDetailLedgerService.selectItemDetailList(start, end, mssec, acccd, it1cd, it2cd);
		return result;
	}

	// tab3 상세내역(재원별)
	@GetMapping("/readDetail")
	public AjaxResult searchDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd,
		@RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemDetailLedgerService.selectDetailList(start, end, acccd, it1cd, it2cd, mssec);
		return result;
	}

	// tab4 상세내역
	@GetMapping("/readDetail2")
	public AjaxResult searchDetail2(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "it2cd", required = false) String it2cd,
		@RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemDetailLedgerService.selectDetail2List(start, end, acccd, it1cd, it2cd, mssec);
		return result;
	}

	// 전표 팝업 : 헤더 + 분개
	@GetMapping("/readSlip")
	public AjaxResult searchSlip(
		@RequestParam(value = "yymmdd") String yymmdd,
		@RequestParam(value = "spnum") String spnum,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemDetailLedgerService.selectSlip(yymmdd, spnum);
		return result;
	}
}
