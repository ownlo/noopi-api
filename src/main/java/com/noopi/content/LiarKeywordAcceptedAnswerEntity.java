package com.noopi.content;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "liar_keyword_accepted_answer")
public class LiarKeywordAcceptedAnswerEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "keyword_id") LiarKeywordEntity keyword;
    @Column(nullable = false) String answer;
    @Column(nullable = false) LocalDateTime createdAt;
    protected LiarKeywordAcceptedAnswerEntity() {}
}
