package com.noopi.content;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LiarKeywordRepository extends JpaRepository<LiarKeywordEntity, Long> {
    @Query("select distinct k from LiarKeywordEntity k join fetch k.category c left join fetch k.answers " +
           "where k.active = true and c.active = true and (:code = 'RANDOM' or c.code = :code)")
    List<LiarKeywordEntity> findAvailable(String code);
}
