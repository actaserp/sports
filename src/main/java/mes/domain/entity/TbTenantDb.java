package mes.domain.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tb_tenant_db")
public class TbTenantDb {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "spjangcd", nullable = false, length = 10)
    private String spjangcd;

    // 'main' = 메인 업무 DB, 그 외는 보조 DB 식별자 (예: 'oracle_bom')
    @Column(name = "db_alias", nullable = false, length = 50)
    private String dbAlias;

    @Column(name = "db_url", nullable = false, length = 255)
    private String dbUrl;

    @Column(name = "db_username", length = 100)
    private String dbUsername;

    @Column(name = "db_password", length = 255)
    private String dbPassword;

    // 'postgresql' | 'mssql' | 'oracle'
    @Column(name = "db_type", nullable = false, length = 20)
    private String dbType;

    @Column(name = "pool_size")
    private Integer poolSize;

    @Column(name = "description", length = 200)
    private String description;
}
