package mes.app.ledger;

import lombok.extern.slf4j.Slf4j;
import mes.app.ledger.service.CategoryClientLedgerService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/CategoryClientLedger")
@Slf4j
public class CategoryClientLedgerController { // 관별거래처원장

	@Autowired
	CategoryClientLedgerService categoryClientLedgerService;

	// tab1 좌측 : 관(계정) 목록
	@GetMapping("/read")
	public AjaxResult searchSummary(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryClientLedgerService.searchSummary(start, end, acccd);
		return result;
	}

	// tab1 우측 : 선택 관의 거래처별 내역
	@GetMapping("/readClient")
	public AjaxResult searchClient(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryClientLedgerService.searchClient(start, end, acccd, it1cd);
		return result;
	}

	// tab2 : 상세내역 마스터
	@GetMapping("/readDetailMaster")
	public AjaxResult searchDetailMaster(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryClientLedgerService.selectDetailMasterList(start, end, acccd, it1cd);
		return result;
	}

	// tab3 : 상세내역
	@GetMapping("/readDetail")
	public AjaxResult searchDetail(
		@RequestParam(value = "start") String start,
		@RequestParam(value = "end") String end,
		@RequestParam(value = "acccd", required = false) String acccd,
		@RequestParam(value = "it1cd", required = false) String it1cd) {
		AjaxResult result = new AjaxResult();
		result.data = categoryClientLedgerService.selectDetailList(start, end, acccd, it1cd);
		return result;
	}

}