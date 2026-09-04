package kr.go.smes.batch.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 멀티 DataSource TransactionManager 설정
 *
 * <h2>트랜잭션 매니저 목록</h2>
 * <pre>
 * idoTransactionManager   → ido PostgreSQL (Primary)
 * qimTransactionManager   → q-im MariaDB
 * qsignTransactionManager → q-sign PostgreSQL
 * authzTransactionManager → q-authz PostgreSQL
 * </pre>
 *
 * <h2>사용 방법</h2>
 * {@code @Transactional(transactionManager = "idoTransactionManager")}
 * 처럼 트랜잭션 매니저 이름을 명시하여 사용.
 * FOR UPDATE SKIP LOCKED가 트랜잭션 내에서만 유효하므로 반드시 @Transactional 필요.
 *
 * <h2>JTA 미사용 이유</h2>
 * 각 Job은 하나의 DataSource만 접근하므로 XA 트랜잭션 불필요.
 * DataSourceTransactionManager로 충분.
 */
@Configuration
public class BatchTransactionConfig {

    /**
     * ido PostgreSQL TransactionManager (Primary)
     */
    @Bean(name = "idoTransactionManager")
    @Primary
    public PlatformTransactionManager idoTransactionManager(
            @Qualifier("idoDataSource") DataSource idoDataSource) {
        return new DataSourceTransactionManager(idoDataSource);
    }

    /**
     * q-im MariaDB TransactionManager
     */
    @Bean(name = "qimTransactionManager")
    public PlatformTransactionManager qimTransactionManager(
            @Qualifier("qimDataSource") DataSource qimDataSource) {
        return new DataSourceTransactionManager(qimDataSource);
    }

    /**
     * q-sign PostgreSQL TransactionManager
     */
    @Bean(name = "qsignTransactionManager")
    public PlatformTransactionManager qsignTransactionManager(
            @Qualifier("qsignDataSource") DataSource qsignDataSource) {
        return new DataSourceTransactionManager(qsignDataSource);
    }

    /**
     * q-authz PostgreSQL TransactionManager
     */
    @Bean(name = "authzTransactionManager")
    public PlatformTransactionManager authzTransactionManager(
            @Qualifier("authzDataSource") DataSource authzDataSource) {
        return new DataSourceTransactionManager(authzDataSource);
    }
}
