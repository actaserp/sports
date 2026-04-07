package mes.app.util.redis;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final BillingFallbackHandler fallbackHandler;

    public RedisService(RedisTemplate<String, Object> redisTemplate,
                        BillingFallbackHandler fallbackHandler) {
        this.redisTemplate = redisTemplate;
        this.fallbackHandler = fallbackHandler;
    }

    public void setValues(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("Redis set 실패 - Key: {}", key);
        }
    }

    public void setValues(String key, Object value, long duration, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, duration, unit);
        } catch (Exception e) {
            log.error("Redis set 실패 - Key: {}", key);
        }
    }

    public Object getValues(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("Redis get 실패 - Key: {}", key);
            return null;
        }
    }

    public Long incrementValue(String key) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, 35, TimeUnit.DAYS);
            }
            return count;
        } catch (Exception e) {
            log.error("Redis INCR 실패 - Key: {}", key);
            return null;
        }
    }

    public Long incrementHashValue(String key, String field) {
        try {
            Long count = redisTemplate.opsForHash().increment(key, field, 1L);
            if (count != null && count == 1) {
                redisTemplate.expire(key, 45, TimeUnit.DAYS);
            }
            return count;
        } catch (Exception e) {
            log.error("Redis HINCRBY 실패 - Key: {}, Field: {}", key, field);
            return null;
        }
    }

    public Long incrementValue(String key, Long value) {
        try {
            return redisTemplate.opsForValue().increment(key, value);
        } catch (Exception e) {
            log.error("Redis INCRBY 실패 - Key: {}", key);
            return null;
        }
    }

    public void deleteValues(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Redis delete 실패 - Key: {}", key);
        }
    }

    public Map<String, Integer> getValuesByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            Map<String, Integer> usageMap = new HashMap<>();
            if (keys != null) {
                for (String key : keys) {
                    Object value = redisTemplate.opsForValue().get(key);
                    if (value != null) {
                        usageMap.put(key, Integer.parseInt(value.toString()));
                    }
                }
            }
            return usageMap;
        } catch (Exception e) {
            log.error("Redis 패턴 조회 실패 - pattern: {}", pattern);
            return Collections.emptyMap();
        }
    }

    //현재 레디스가 연결된 상태인지 확인
    public boolean isRedisAvailable(){
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equals(pong);
        }catch(Exception e){
            log.error("Redis 연결상태 확인 실패");
            return false;
        }
    }

    /// TODO: 얘랑 다른애 보면 catch문이 좀 다름. 위에 애들은 범용적으로 사용할거고 얘는 빌링용임. 빌링풀백핸들러를 공유하면 안됨
    // ────────────────────────────────────────────
    // 빌링용 메서드 - 폴백은 BillingFallbackHandler 위임
    // ────────────────────────────────────────────
    public void incrementHash(String hashKey, String field, long amount) {
        try {
            redisTemplate.opsForHash().increment(hashKey, field, amount);
        } catch (Exception e) {
            log.error("Redis HINCRBY 실패 - Key: {}, Field: {}", hashKey, field);
            fallbackHandler.fallback(hashKey, field, amount);
        }
    }

    public void expireIfAbsent(String hashKey, long timeout, TimeUnit unit) {
        try {
            Long ttl = redisTemplate.getExpire(hashKey);
            if (ttl == null || ttl == -1) {
                redisTemplate.expire(hashKey, timeout, unit);
            }
        } catch (Exception e) {
            log.error("Redis EXPIRE 실패 - Key: {}", hashKey);
        }
    }

    public void syncFallbackToRedis() {
        if (fallbackHandler.hasLocalCache()) {
            fallbackHandler.syncLocalCacheToRedis(redisTemplate);
        }
        if (fallbackHandler.hasFileBackup()) {
            fallbackHandler.syncFileCacheToRedis(redisTemplate);
        }
    }
}
