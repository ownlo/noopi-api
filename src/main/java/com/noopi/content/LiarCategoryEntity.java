package com.noopi.content;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "liar_category")
public class LiarCategoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false, length = 50, unique = true) String code;
    @Column(nullable = false, length = 100) String name;
    @Column(nullable = false) int displayOrder;
    @Column(nullable = false) boolean active;
    @Column(nullable = false) LocalDateTime createdAt;
    @Column(nullable = false) LocalDateTime updatedAt;
    protected LiarCategoryEntity() {}
}
