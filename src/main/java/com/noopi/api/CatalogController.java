package com.noopi.api;

import com.noopi.content.LiarContent;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
public class CatalogController {
    private static final List<CatalogCategory> CATALOG_CATEGORIES = List.of(
        new CatalogCategory("MINI_GAME", "미니게임", 1),
        new CatalogCategory("PARTY_GAME", "파티게임", 2),
        new CatalogCategory("DEDUCTION", "추리", 3),
        new CatalogCategory("STRATEGY", "전략", 4),
        new CatalogCategory("LUCK", "운빨", 5),
        new CatalogCategory("INDIVIDUAL", "개인전", 6),
        new CatalogCategory("TEAM", "팀전", 7));
    private static final List<Game> GAMES = List.of(
        new Game("LIAR", "라이어 게임", List.of("PARTY_GAME", "DEDUCTION", "TEAM"), 3, 12, true),
        new Game("BLIND", "블라인드 게임", List.of("PARTY_GAME", "DEDUCTION", "INDIVIDUAL"), 2, 2, true),
        new Game("MAFIA", "마피아 게임", List.of("PARTY_GAME", "DEDUCTION", "TEAM"), 4, 12, true),
        new Game("YUT", "윷놀이", List.of("PARTY_GAME", "STRATEGY", "LUCK", "INDIVIDUAL", "TEAM"), 2, 4, true),
        new Game("PIG", "피그", List.of("MINI_GAME", "LUCK", "INDIVIDUAL", "STRATEGY"), 2, 6, true),
        new Game("TOOTH", "누피 콱!", List.of("MINI_GAME", "PARTY_GAME", "LUCK", "INDIVIDUAL"), 2, 8, true));

    private final LiarContent content;
    public CatalogController(LiarContent content) { this.content = content; }
    public record CatalogCategory(String code, String name, int order) {}
    public record Game(String gameType, String name, List<String> catalogCategoryCodes, int minPlayers, int maxPlayers, boolean enabled) {}
    public record GameCatalog(List<CatalogCategory> catalogCategories, List<Game> games) {}
    @GetMapping public GameCatalog games() { return new GameCatalog(CATALOG_CATEGORIES, GAMES); }
    @GetMapping("/liar/categories") public Map<String, List<LiarContent.Category>> categories() {
        return Map.of("categories", content.categories());
    }
}
