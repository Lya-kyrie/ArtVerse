-- V8__expand_manga_style.sql
-- 扩展 manga_style 枚举到 6 个值（替代 V4 的 4 值旧方案）
-- 详见 docs/knowledge/modules/story/references/manga-style-rules.md

-- 1. 删除旧 CHECK 约束
ALTER TABLE stories DROP CONSTRAINT IF EXISTS ck_stories_manga_style;

-- 2. 数据迁移：旧值 → 新值
-- japanese_bw / japanese_color → japanese（颜色信息已拆分到 color_mode 字段）
-- korean_webtoon → korean
-- american_comic → american
UPDATE stories SET manga_style = 'japanese' WHERE manga_style IN ('japanese_bw', 'japanese_color');
UPDATE stories SET manga_style = 'korean' WHERE manga_style = 'korean_webtoon';
UPDATE stories SET manga_style = 'american' WHERE manga_style = 'american_comic';

-- 3. 添加新 CHECK 约束（6 值）
ALTER TABLE stories
    ADD CONSTRAINT ck_stories_manga_style CHECK (manga_style IN (
        'japanese', 'korean', 'american', 'european', 'chinese_ink', 'semi_realistic'
    ));

-- 4. 更新默认值为 'japanese'（已是，无需改）
ALTER TABLE stories ALTER COLUMN manga_style SET DEFAULT 'japanese';
