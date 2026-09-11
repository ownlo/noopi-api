package com.noopi.content;

import java.util.Collection;
import java.util.List;

public interface BlindContent {
    record Keyword(long id, String keyword) {}
    List<Keyword> chooseDistinct(int count, Collection<Long> recent);
}
