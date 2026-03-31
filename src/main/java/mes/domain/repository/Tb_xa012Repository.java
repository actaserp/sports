package mes.domain.repository;

import mes.domain.entity.Tb_xa012;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface Tb_xa012Repository extends JpaRepository<Tb_xa012, String> {
    List<Tb_xa012> findBySpjangcd(String spjangcd, Sort sort);
}
