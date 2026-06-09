package com.artverse.domain;

/**
 * 漫画风格枚举（6 值）。
 * <p>
 * 详见 docs/knowledge/modules/story/references/manga-style-rules.md
 *
 * DB 约束：{@code manga_style IN ('japanese', 'korean', 'american', 'european', 'chinese_ink', 'semi_realistic')}
 */
public enum MangaStyle {
    /** 日式 */
    JAPANESE("japanese", "日式", "Japanese manga style, classic shonen look, bold ink lines, screentone shading"),
    /** 韩式 */
    KOREAN("korean", "韩式", "Korean webtoon style, clean lineart, soft cel-shading, manhwa aesthetic"),
    /** 美式 */
    AMERICAN("american", "美式", "American comic book style, dynamic poses, vibrant inks, Marvel-like"),
    /** 欧式 */
    EUROPEAN("european", "欧式", "European BD style, ligne claire, detailed backgrounds, Franco-Belgian"),
    /** 国风水墨 */
    CHINESE_INK("chinese_ink", "水墨", "Chinese ink painting, 水墨画 style, flowing brushwork, rice paper texture"),
    /** 半写实 */
    SEMI_REALISTIC("semi_realistic", "半写实", "Semi-realistic illustration, painterly, cinematic lighting");

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
        return JAPANESE;  // fallback
    }
}
