package com.noopi.content;

import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.noopi.api.ErrorCode.NO_AVAILABLE_KEYWORD;

@Service
@Transactional(readOnly = true)
public class JpaBlindContent implements BlindContent {
    private final BlindKeywordRepository keywords;
    private final RandomGenerator random;

    public JpaBlindContent(BlindKeywordRepository keywords, RandomGenerator random) {
        this.keywords = keywords;
        this.random = random;
    }

    public List<Keyword> chooseDistinct(int count, Collection<Long> recent) {
        var all = keywords.findByActiveTrueOrderByIdAsc();
        NO_AVAILABLE_KEYWORD.require(all.size() >= count);
        var fresh = all.stream().filter(keyword -> !recent.contains(keyword.id)).toList();
        var pool = new ArrayList<>(fresh.size() >= count ? fresh : all);
        for (int i = pool.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(pool, i, j);
        }
        return pool.subList(0, count).stream().map(keyword -> new Keyword(keyword.id, keyword.keyword)).toList();
    }
}
