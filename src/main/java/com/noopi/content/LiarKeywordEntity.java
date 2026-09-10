package com.noopi.content;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "liar_keyword")
public class LiarKeywordEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id") LiarCategoryEntity category;
    @Column(nullable = false) String keyword;
    @Column(nullable = false) boolean active;
    @Column(nullable = false) LocalDateTime createdAt;
    @Column(nullable = false) LocalDateTime updatedAt;
    protected LiarKeywordEntity() {}
}
