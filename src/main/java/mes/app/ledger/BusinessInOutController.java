package mes.app.ledger;

import lombok.extern.slf4j.Slf4j;
import mes.app.ledger.service.BusinessInOutService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/BusinessInOut")
@Slf4j
public class BusinessInOutController { // 사업별입출현황

	@Autowired
	BusinessInOutService businessInOutService;

	// tab2 : 세입내역 (tiosec=1)
	@GetMapping("/readIn")
	public AjaxResult searchIn(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.searchInList(start, end, bsdate, bseccd, busicd);
		return result;
	}

	// tab5 : 세출내역 (tiosec=2)
	@GetMapping("/readOut")
	public AjaxResult searchOut(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd,
		@RequestParam(value = "businm", required = false) String businm) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.searchOutList(start, end, bsdate, bseccd, busicd);
		return result;
	}

	// 전표 팝업 : 헤더 + 분개
	@GetMapping("/readSlip")
	public AjaxResult searchSlip(
		@RequestParam(value = "yymmdd") String yymmdd,
		@RequestParam(value = "spnum") String spnum,
		@RequestParam(value = "acccd", required = false) String acccd) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.selectSlip(yymmdd, spnum);
		return result;
	}

	// tab3 : 관련전표
	@GetMapping("/readRelated")
	public AjaxResult searchRelated(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd,
		@RequestParam(value = "tiosec", required = false) String tiosec) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.searchRelatedList(start, end, bsdate, bseccd, busicd, tiosec);
		return result;
	}

	// tab4 : 계좌현황
	@GetMapping("/readAccount")
	public AjaxResult searchAccount(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.searchAccountList(start, end, bsdate, bseccd, busicd);
		return result;
	}

	// tab6 : 사업손익집계현황 (사업상세, d_book23_3)
	@GetMapping("/readProfitDetail")
	public AjaxResult readProfitDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.searchProfitDetailList(start, end, bsdate, bseccd, busicd);
		return result;
	}

	// tab6 : 사업손익집계현황 (사업별현황, d_book23_4)
	@GetMapping("/readProfitSummary")
	public AjaxResult readProfitSummary(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.searchProfitSummaryList(start, end);
		return result;
	}

	// tab5(신규) : 사업손익상세 (d_book23_5)
	@GetMapping("/readProfitLoss")
	public AjaxResult readProfitLoss(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "bsdate", required = false) String bsdate,
		@RequestParam(value = "bseccd", required = false) String bseccd,
		@RequestParam(value = "busicd", required = false) String busicd,
		@RequestParam(value = "businm", required = false) String businm) {
		AjaxResult result = new AjaxResult();
		result.data = businessInOutService.searchProfitLossList(start, end, bsdate, bseccd, busicd, businm);
		return result;
	}

}