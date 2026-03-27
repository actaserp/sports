package mes.domain.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;

@Getter
@Setter
@Entity
@Table(name = "tenant_menu",
        uniqueConstraints = @UniqueConstraint(columnNames = {"spjangcd", "menu_code"}))
public class TenantMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "spjangcd", nullable = false, length = 10)
    private String spjangcd;

    @Column(name = "menu_code", nullable = false, length = 50)
    private String menuCode;
}
