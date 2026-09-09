package com.noopi.content;

import java.util.Collection;
import java.util.List;

public interface LiarContent {
    record Category(String code, String name, boolean virtual) {}
    record Keyword(long id, String keyword, List<String> acceptedAnswers) {}
    List<Category> categories();
    void validateCategory(String code);
    Keyword choose(String code, Collection<Long> recent);
}
