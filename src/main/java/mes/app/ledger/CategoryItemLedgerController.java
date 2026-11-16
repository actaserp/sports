package mes.app.ledger;

import lombok.extern.slf4j.Slf4j;
import mes.app.ledger.service.CategoryItemLedgerService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/CategoryItemLedger")
@Slf4j
public class CategoryItemLedgerController { //관항별원장

	@Autowired
	CategoryItemLedgerService categoryItemLedgerService;

	// tab1 좌측 : 관(계정) 목록
	@GetMapping("/read")
	public AjaxResult searchSummary(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.searchSummary(start, end, mssec, acccd, it1cd); // ← 인자 전달
		return result;
	}

	// tab1 우측 : 선택한 관의 항별 내역
	@GetMapping("/readItem")
	public AjaxResult searchItem(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.searchItem(start, end, mssec, acccd, it1cd);
		return result;
	}

	// tab2 상세내역
	@GetMapping("/readItemDetail")
	public AjaxResult searchItemDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.selectItemDetailList(start, end, mssec, acccd, it1cd);
		return result;
	}

	// tab3 상세내역
	@GetMapping("/readDetail")
	public AjaxResult searchDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd,
		@RequestParam(value = "tiosec", required = false) String tiosec,
		@RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.selectDetailList(start, end, acccd, it1cd, tiosec, mssec);
		return result;
	}

	// 전표 팝업 : 헤더 + 분개
	@GetMapping("/readSlip")
	public AjaxResult searchSlip(
		@RequestParam(value = "yymmdd") String yymmdd,
		@RequestParam(value = "spnum") String spnum,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.selectSlip(yymmdd, spnum);
		return result;
	}


	// tab2 : 계정 상세내역 (d_book10_1)
	@GetMapping("/readAccDetail")
	public AjaxResult searchAccDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.selectAccDetailList(start, end, mssec, acccd);
		return result;
	}

	// tab3 : 계정 원장 (d_book10_2)
	@GetMapping("/readAccLedger")
	public AjaxResult searchAccLedger(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd") String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.selectAccLedgerList(start, end, mssec, acccd);
		return result;
	}

	// tab4 : 사업구분-사업별 (d_book10_4)
	@GetMapping("/readBusi")
	public AjaxResult searchBusi(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "busipur", required = false) String busipur) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.selectBusiSummaryList(start, end, mssec, acccd, busipur);
		return result;
	}

	// tab5 : 사업구분-사업별 계정 원장 (d_book10_6)
	@GetMapping("/readBusiLedger")
	public AjaxResult searchBusiLedger(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "acccd") String acccd,
		@RequestParam(value = "busipur", required = false) String busipur,
		@RequestParam(value = "bsdate", required = false) String bsdate) {
		AjaxResult result = new AjaxResult();
		result.data = categoryItemLedgerService.selectBusiLedgerList(start, end, mssec, acccd, busipur, bsdate);
		return result;
	}
}