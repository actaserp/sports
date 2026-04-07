package mes.app.util.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class BillingFallbackHandler {

    private static final int LOCAL_CACHE_MAX_SIZE = 10_000;
    private static final String BACKUP_DIR = "/app/logs/billing-backup/";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Long> localCache = new ConcurrentHashMap<>();
    private final String backupFile;

    public BillingFallbackHandler(@Value("${mes.project-name}") String projectName) {
        this.backupFile = BACKUP_DIR + projectName + "-billing-fallback.jsonl";
    }

    /**
     * Redis 실패 시 호출 - 로컬캐시 → 파일 순으로 폴백
     */
    public void fallback(String hashKey, String field, long amount) {
        String localKey = hashKey + ":" + field;

        if (localCache.size() < LOCAL_CACHE_MAX_SIZE) {
            localCache.merge(localKey, amount, Long::sum);
            log.warn("[Billing Fallback] 로컬캐시 저장 - Key: {}:{}", hashKey, field);
        } else {
            writeToFile(hashKey, field, amount);
        }
    }

    /**
     * Redis 복구 시 로컬캐시 → Redis 동기화
     */
    public void syncLocalCacheToRedis(RedisTemplate<String, Object> redisTemplate) {
        if (localCache.isEmpty()) return;

        Iterator<Map.Entry<String, Long>> iter = localCache.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Long> entry = iter.next();

            int lastColon = entry.getKey().lastIndexOf(":");
            String hashKey = entry.getKey().substring(0, lastColon);
            String field   = entry.getKey().substring(lastColon + 1);

            try {
                redisTemplate.opsForHash().increment(hashKey, field, entry.getValue());
                iter.remove();
                log.info("[Billing Fallback] 로컬캐시 → Redis 복구 완료 - Key: {}:{}", hashKey, field);
            } catch (Exception e) {
                log.error("[Billing Fallback] Redis 복구 실패 - Key: {}", entry.getKey());
                break; // 아직 Redis 불안정, 다음 스케줄에 재시도
            }
        }
    }

    /**
     * Redis 복구 시 파일 → Redis 동기화
     */
    public void syncFileCacheToRedis(RedisTemplate<String, Object> redisTemplate) {
        File file = new File(backupFile);
        if (!file.exists()) return;

        List<String> failedLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode node = objectMapper.readTree(line);
                    String hashKey = node.get("hashKey").asText();
                    String field   = node.get("field").asText();
                    long amount    = node.get("amount").asLong();

                    redisTemplate.opsForHash().increment(hashKey, field, amount);
                    log.info("[Billing Fallback] 파일 → Redis 복구 완료 - {}:{}", hashKey, field);
                } catch (Exception e) {
                    log.error("[Billing Fallback] 파일 → Redis 복구 실패 - line: {}", line);
                    failedLines.add(line);
                }
            }
        } catch (IOException e) {
            log.error("[Billing Fallback] 백업 파일 읽기 실패", e);
            return;
        }

        rewriteFile(failedLines);
    }

    private void writeToFile(String hashKey, String field, long amount) {
        try {
            Files.createDirectories(Paths.get(BACKUP_DIR));
            String json = objectMapper.writeValueAsString(Map.of(
                    "hashKey", hashKey,
                    "field",   field,
                    "amount",  amount,
                    "ts",      System.currentTimeMillis()
            ));
            Files.writeString(Paths.get(backupFile), json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.warn("[Billing Fallback] 파일 백업 - {}:{}", hashKey, field);
        } catch (IOException e) {
            log.error("[Billing Fallback] 파일 백업 실패 - 데이터 유실 Key: {}:{}", hashKey, field);
        }
    }

    private void rewriteFile(List<String> lines) {
        try {
            if (lines.isEmpty()) {
                Files.deleteIfExists(Paths.get(backupFile));
            } else {
                Files.write(Paths.get(backupFile), lines,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[Billing Fallback] 파일 재작성 실패", e);
        }
    }

    public boolean hasLocalCache() {
        return !localCache.isEmpty();
    }

    public boolean hasFileBackup() {
        return new File(backupFile).exists();
    }
}