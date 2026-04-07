package mes.app.Scheduler.SchedulerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.app.util.redis.BillingFallbackHandler;
import mes.app.util.redis.RedisService;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BillingScheduler {

    private final RedisService redisService;
    private final BillingFallbackHandler fallbackHandler;

    public void syncFallback() {
        if (!redisService.isRedisAvailable()) return;

        if (fallbackHandler.hasLocalCache() || fallbackHandler.hasFileBackup()) {
            log.info("[Billing Scheduler] 폴백 데이터 Redis 동기화 시작");
            redisService.syncFallbackToRedis();
            log.info("[Billing Scheduler] 폴백 데이터 Redis 동기화 완료");
        }
    }
}
