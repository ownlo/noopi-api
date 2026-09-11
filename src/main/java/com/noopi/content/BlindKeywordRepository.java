package com.noopi.content;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlindKeywordRepository extends JpaRepository<BlindKeywordEntity, Long> {
    List<BlindKeywordEntity> findByActiveTrueOrderByIdAsc();
}
