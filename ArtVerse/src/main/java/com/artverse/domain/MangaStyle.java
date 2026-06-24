package com.artverse.domain;

/**
 * 漫画风格枚举（12 值）。
 */
public enum MangaStyle {
    JAPANESE_MANGA("japanese_manga", "日式漫画", "Japanese manga style, classic shonen look, bold ink lines, screentone shading"),
    KOREAN_WEBTOON("korean_webtoon", "韩式条漫", "Korean webtoon style, clean lineart, soft cel-shading, manhwa aesthetic"),
    AMERICAN_COMIC("american_comic", "美式漫画", "American comic book style, dynamic poses, vibrant inks, Marvel-like"),
    LIGNE_CLAIRE("ligne_claire", "欧式清线", "European BD style, ligne claire, detailed backgrounds, Franco-Belgian"),
    CHINESE_INK("chinese_ink", "水墨国风", "Chinese ink painting, flowing brushwork, rice paper texture"),
    SEMI_REALISTIC("semi_realistic", "半写实", "Semi-realistic illustration, painterly, cinematic lighting"),
    REALISTIC("realistic", "全写实", "Photorealistic rendering, detailed textures, lifelike proportions"),
    OIL_PAINTING("oil_painting", "厚涂油画", "Oil painting style, thick brush strokes, rich textures, artistic"),
    FLAT_DESIGN("flat_design", "扁平极简", "Flat design, bold geometric shapes, minimal shading, clean"),
    PIXEL_ART("pixel_art", "像素风", "Pixel art style, retro game aesthetic, blocky pixels"),
    WATERCOLOR("watercolor", "水彩淡雅", "Watercolor painting, soft washes, translucent colors, healing vibe"),
    CYBERPUNK("cyberpunk", "赛博朋克", "Cyberpunk style, neon lights, high contrast, futuristic dystopian");

    private final String dbValue;
    private final String displayName;
    private final String promptTemplate;

    MangaStyle(String dbValue, String displayName, String promptTemplate) {
        this.dbValue = dbValue;
        this.displayName = displayName;
        this.promptTemplate = promptTemplate;
    }

    public String getDbValue() { return dbValue; }
    public String getDisplayName() { return displayName; }
    public String getPromptTemplate() { return promptTemplate; }

    public static MangaStyle fromDb(String dbValue) {
        for (MangaStyle s : values()) {
            if (s.dbValue.equalsIgnoreCase(dbValue)) {
                return s;
            }
        }
        return JAPANESE_MANGA;
    }
}
