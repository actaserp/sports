package mes.app.Scheduler;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.Scheduler.SchedulerService.AccountSyncService;
import mes.app.Scheduler.SchedulerService.ApiUsageService;
import mes.app.Scheduler.SchedulerService.BillingScheduler;
import mes.app.Scheduler.SchedulerService.CardHistoryScheduler;
import mes.app.account_management.service.CardHistoryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;

@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduledTaskRunner {

    private final Executor schedulerExecutor;

    private final AccountSyncService accountSyncService;
    private final BillingScheduler billingScheduler;
    private final CardHistoryScheduler cardHistoryScheduler;

    //@Scheduled(cron = "0 5 * * * *")
    @Scheduled(cron = "0 0 * * * *") //// 매시 정각
    public void runScheduledTasks() {
        //TODO: 작업 추가할거면 SchedulerThreadPoolConfig 에서 쓰레드풀 조정해주삼 지금 작업하나밖에 없어서 6개로 해놨삼

        //log.info("[스케줄러 시작] 계좌수집 작업 시작 - Thread: {}", Thread.currentThread().getName());
        schedulerExecutor.execute(() -> safeRun(accountSyncService::run, "계좌수집"));
        schedulerExecutor.execute(() -> safeRun(cardHistoryScheduler::runCardHistoryScheduler, "카드내역수집"));
    }

    //빌링 풀백인데 5분마다 동작하지만, redis만 잘살아있으면 핑만 보내고 말아서 성능병목 x
    @Scheduled(fixedDelay = 300_000) // 5분마다
    public void runBillingFallbackSync() {
        schedulerExecutor.execute(() -> safeRun(billingScheduler::syncFallback, "빌링 폴백 동기화"));
    }

    private void safeRun(Runnable task, String taskName){
        try{
            task.run();
        }catch (Exception e){
            log.error("스케줄러 작업 실패: {} - {}", taskName, e.getMessage(), e);
        }
    }
}
