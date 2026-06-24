-- V13: Expand manga_style to 12 values

-- Step 1: Drop old constraint FIRST (otherwise UPDATE will violate it)
ALTER TABLE stories DROP CONSTRAINT IF EXISTS ck_stories_manga_style;

-- Step 2: Migrate all old values to new ones
UPDATE stories SET manga_style = CASE manga_style
    WHEN 'japanese' THEN 'japanese_manga'
    WHEN 'japanese_bw' THEN 'japanese_manga'
    WHEN 'japanese_color' THEN 'japanese_manga'
    WHEN 'korean' THEN 'korean_webtoon'
    WHEN 'american' THEN 'american_comic'
    WHEN 'european' THEN 'ligne_claire'
    ELSE manga_style
END;

-- Safety: any value not in the new set defaults to japanese_manga
UPDATE stories SET manga_style = 'japanese_manga'
WHERE manga_style NOT IN (
    'japanese_manga', 'korean_webtoon', 'american_comic', 'ligne_claire',
    'chinese_ink', 'semi_realistic', 'realistic', 'oil_painting',
    'flat_design', 'pixel_art', 'watercolor', 'cyberpunk'
);

-- Step 3: Add new constraint
ALTER TABLE stories
    ADD CONSTRAINT ck_stories_manga_style CHECK (manga_style IN (
        'japanese_manga', 'korean_webtoon', 'american_comic', 'ligne_claire',
        'chinese_ink', 'semi_realistic', 'realistic', 'oil_painting',
        'flat_design', 'pixel_art', 'watercolor', 'cyberpunk'
    ));

ALTER TABLE stories ALTER COLUMN manga_style SET DEFAULT 'japanese_manga';
