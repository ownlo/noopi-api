package com.noopi.content;

import java.util.*;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.noopi.api.ErrorCode.*;

@Service
@Transactional(readOnly = true)
public class JpaLiarContent implements LiarContent {
    private final LiarCategoryRepository categories;
    private final LiarKeywordRepository keywords;
    private final RandomGenerator random;
    public JpaLiarContent(LiarCategoryRepository categories, LiarKeywordRepository keywords, RandomGenerator random) {
        this.categories = categories; this.keywords = keywords; this.random = random;
    }
    public List<Category> categories() {
        List<Category> result = new ArrayList<>();
        result.add(new Category("RANDOM", "랜덤", true));
        categories.findByActiveTrueOrderByDisplayOrderAscIdAsc().forEach(c -> result.add(new Category(c.code, c.name, false)));
        return List.copyOf(result);
    }
    public void validateCategory(String code) {
        INVALID_CATEGORY.require(code != null && (code.equals("RANDOM") || categories.existsByCodeAndActiveTrue(code)));
    }
    public Keyword choose(String code, Collection<Long> recent) {
        validateCategory(code);
        var all = keywords.findAvailable(code);
        NO_AVAILABLE_KEYWORD.require(!all.isEmpty());
        var fresh = all.stream().filter(k -> !recent.contains(k.id)).toList();
        var pool = fresh.isEmpty() ? all : fresh;
        var selected = pool.get(random.nextInt(pool.size()));
        return new Keyword(selected.id, selected.keyword);
    }
}
