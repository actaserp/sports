package mes.config;

import java.util.HashMap;
import java.util.List;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.interceptor.TenantSqlInspector;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@EnableJpaRepositories(basePackages = "mes.domain.repository", entityManagerFactoryRef = "entityManagerFactory", transactionManagerRef = "transactionManagerCustom")
@Configuration
public class DataSourceConfig {

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Main DataSource (메인 DB – 인증/JPA 엔티티 전용, 항상 메인 DB)
    // ──────────────────────────────────────────────────────────────────────────

    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @Bean(name = "mainDataSource")
    @Primary
    DataSource mainDataSource() {
        return DataSourceBuilder.create().build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Routing DataSource (SqlRunner 전용 – 테넌트 DB 라우팅)
    //    dbKey 없으면 mainDataSource 로 fallback
    // ──────────────────────────────────────────────────────────────────────────

    @Bean
    RoutingDataSource routingDataSource(@Qualifier("mainDataSource") DataSource mainDataSource) {
        RoutingDataSource routingDataSource = new RoutingDataSource();
        routingDataSource.setDefaultTargetDataSource(mainDataSource);
        routingDataSource.setTargetDataSources(new HashMap<>());
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. JdbcTemplate / NamedParameterJdbcTemplate → routingDataSource 사용
    // ──────────────────────────────────────────────────────────────────────────

    @Bean("jdbcTemplate")
    JdbcTemplate jdbcTemplate(RoutingDataSource routingDataSource) {
        return new JdbcTemplate(routingDataSource) {
            @Override
            public <T> T queryForObject(String sql, RowMapper<T> rowMapper) {
                validateTenantIsolation(sql);
                return super.queryForObject(sql, rowMapper);
            }

            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
                validateTenantIsolation(sql);
                return super.query(sql, rowMapper);
            }

            @Override
            public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
                validateTenantIsolation(sql);
                return super.query(sql, args, rowMapper);
            }

            @Override
            public int update(String sql, Object... args) {
                validateTenantIsolation(sql);
                return super.update(sql, args);
            }

            @Override
            public void execute(String sql) {
                validateTenantIsolation(sql);
                super.execute(sql);
            }
        };
    }

    @Bean("namedParameterJdbcTemplate")
    @Primary
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. JPA → mainDataSource 고정 (라우팅 없이 항상 메인 DB)
    // ──────────────────────────────────────────────────────────────────────────

    @Bean(name = "entityManagerFactory")
    LocalContainerEntityManagerFactoryBean entityManagerFactory(@Qualifier("mainDataSource") DataSource mainDataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(mainDataSource);
        emf.setPackagesToScan(new String[]{"mes.domain.entity"});
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        emf.setJpaVendorAdapter(vendorAdapter);
        HashMap<String, Object> properties = new HashMap<>();
        properties.put("hibernate.session_factory.statement_inspector", new TenantSqlInspector());
        properties.put("hibernate.ddl-auto", "validate");
        properties.put("hibernate.format_sql", true);
        properties.put("hibernate.show-sql", true);
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        emf.setJpaPropertyMap(properties);
        return emf;
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManagerCustom(@Qualifier("mainDataSource") DataSource mainDataSource) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(this.entityManagerFactory(mainDataSource).getObject());
        return transactionManager;
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. Main DB 전용 SqlRunner (메인 DB 시스템 테이블 전용)
    // ──────────────────────────────────────────────────────────────────────────

    @Bean("mainSqlRunner")
    mes.domain.services.SqlRunner mainSqlRunner(@Qualifier("mainDataSource") DataSource mainDataSource) {
        return new mes.domain.services.impl.MainSqlRunnerImpl(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(mainDataSource)
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. Extra DataSource (외부 MSSQL – 특정 페이지 전용)
    // ──────────────────────────────────────────────────────────────────────────

    @Bean("extraDataSource")
    @ConfigurationProperties(prefix = "extra.datasource")
    DataSource extraDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("extraSqlRunner")
    mes.domain.services.SqlRunner extraSqlRunner(
            @Qualifier("extraDataSource") DataSource extraDataSource) {
        return new mes.domain.services.impl.MainSqlRunnerImpl(
                new NamedParameterJdbcTemplate(extraDataSource)
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. MyBatis → mainDataSource (로그/공통 mapper 용)
    // ──────────────────────────────────────────────────────────────────────────

    @Bean
    SqlSessionFactory sqlSessionFactory(@Qualifier("mainDataSource") DataSource mainDataSource) throws Exception {
        final SqlSessionFactoryBean sessionFactory = new SqlSessionFactoryBean();
        sessionFactory.setDataSource(mainDataSource);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        sessionFactory.setMapperLocations(resolver.getResources("mapper/*.xml"));
        return sessionFactory.getObject();
    }

    @Bean
    SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) throws Exception {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 공통 검증 로직
    // ──────────────────────────────────────────────────────────────────────────

    private void validateTenantIsolation(String sql) {
        // routingDataSource 기반 jdbcTemplate은 사업장 DB 쿼리 전용
        // 메인 DB 테이블은 mainSqlRunner(@Qualifier("mainSqlRunner"))를 사용할 것
        String tenantId = TenantContext.get();
        if (tenantId != null && !"SYSTEM".equals(tenantId)) {
            String lowSql = sql.toLowerCase();
            if (!lowSql.contains("spjangcd") && !lowSql.contains("/* skip_tenant_check */")) {
                log.warn("⚠️ [테넌트 격리 권고] spjangcd 누락 감지 (사업장 DB 쿼리): {}", sql);
            }
        }
    }
}
