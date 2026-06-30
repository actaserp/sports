package mes.app.ledger;

import lombok.extern.slf4j.Slf4j;
import mes.app.ledger.service.CategoryLedgerService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/CategoryLedger")
@Slf4j
public class CategoryLedgerController { //관별원장

	@Autowired
	CategoryLedgerService categoryLedgerService;

	@GetMapping("/read")
	public AjaxResult selectCategoryLedgerList(@RequestParam(value = "start")String start,
																						 @RequestParam(value = "end")String end ,
																						 @RequestParam(value = "mssec", required = false) String mssec,
																						 @RequestParam(value = "accnm", required = false)String accnm)
	{
		AjaxResult result = new AjaxResult();

		result.data = categoryLedgerService.selectCategoryLedgerList(start,end ,mssec, accnm);

		return result;
	}

	// 탭2 보통예금
	@GetMapping("/readDeposit")
	public AjaxResult selectDepositList(
		@RequestParam("start") String start,
		@RequestParam("end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "accnm", required = false) String accnm) {
		AjaxResult result = new AjaxResult();
		result.data = categoryLedgerService.selectDepositList(start, end, mssec, accnm);
		return result;
	}

	// 탭3 상세내역
	@GetMapping("/readDetail")
	public AjaxResult selectDetailList(
		@RequestParam("start") String start,
		@RequestParam("end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "accnm", required = false) String accnm) {
		AjaxResult result = new AjaxResult();
		result.data = categoryLedgerService.selectDetailList(start, end, mssec, accnm);
		return result;
	}

	// 탭4 사업별
	@GetMapping("/readBusiness")
	public AjaxResult selectBusinessList(
		@RequestParam("start") String start,
		@RequestParam("end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "accnm", required = false) String accnm) {
		AjaxResult result = new AjaxResult();
		result.data = categoryLedgerService.selectBusinessList(start, end, mssec, accnm);
		return result;
	}
	// 탭5 상세내역2
	@GetMapping("/readDetail2")
	public AjaxResult selectDetail2List(
		@RequestParam("start") String start,
		@RequestParam("end") String end,
		@RequestParam(value = "mssec", required = false) String mssec,
		@RequestParam(value = "accnm", required = false) String accnm) {
		AjaxResult result = new AjaxResult();
		result.data = categoryLedgerService.selectDetail2List(start, end, mssec, accnm);
		return result;
	}
}
