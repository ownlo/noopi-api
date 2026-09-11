package com.noopi.api;

import com.noopi.content.LiarContent;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
public class CatalogController {
    private final LiarContent content;
    public CatalogController(LiarContent content) { this.content = content; }
    public record Game(String gameType, String name, int minPlayers, int maxPlayers, boolean enabled) {}
    @GetMapping public Map<String, List<Game>> games() {
        return Map.of("games", List.of(
            new Game("LIAR", "라이어 게임", 3, 12, true),
            new Game("BLIND", "블라인드 게임", 2, 2, true)));
    }
    @GetMapping("/liar/categories") public Map<String, List<LiarContent.Category>> categories() {
        return Map.of("categories", content.categories());
    }
}
