package com.noopi.content;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiarCategoryRepository extends JpaRepository<LiarCategoryEntity, Long> {
    List<LiarCategoryEntity> findByActiveTrueOrderByDisplayOrderAscIdAsc();
    boolean existsByCodeAndActiveTrue(String code);
}
