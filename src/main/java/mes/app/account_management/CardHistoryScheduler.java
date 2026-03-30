package mes.app.account_management;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.CardHistoryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CardHistoryScheduler {

	private final CardHistoryService cardHistoryService;

	@Scheduled(cron = "0 0 * * * *")
//@Scheduled(cron = "0 */1 * * * *")
	public void runCardHistoryScheduler() {
		log.info("========== 카드내역 자동수집 스케줄러 시작 ==========");
		cardHistoryService.collectCardHistoryByScheduler();
		log.info("========== 카드내역 자동수집 스케줄러 종료 ==========");
	}
}
