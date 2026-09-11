package com.noopi.content;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "blind_keyword")
public class BlindKeywordEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false, unique = true) String keyword;
    @Column(nullable = false) boolean active;
    @Column(nullable = false) LocalDateTime createdAt;
    @Column(nullable = false) LocalDateTime updatedAt;
    protected BlindKeywordEntity() {}
}
