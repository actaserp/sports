package mes.config;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Slf4j
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        String dbKey = TenantContext.getDbKey();
        log.debug("[RoutingDataSource] dbKey={}", dbKey);
        return dbKey; // null이면 defaultTargetDataSource(mainDataSource) 사용
    }
}
