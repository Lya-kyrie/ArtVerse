-- V9__fix_double_encoded_utf8.sql
-- 修复历史脏数据：早期数据写入路径未强制 client_encoding=UTF8，
-- 中文字段以 LATIN1 单字节方式落地，读取时再被以 UTF-8 解读，导致"双重编码"。
--
-- 修复公式：text → bytea(以 SQL_ASCII 不做字符验证) → text(以 UTF-8 解码)
--
-- 安全策略：仅当"转换后字节长度缩短且确实改变了值"时替换。
--   - 若原值就是合法 UTF-8（即使字符是错的），SQL_ASCII 转 bytea 后再 UTF-8 解读
--     通常是无操作，函数会返回原值
--   - 若原值含 Latin-1 范围内但 UTF-8 解读为"双重编码"模式的字符，
--     解码后会还原成原始的多字节 UTF-8 字符（字节数缩短）
--
-- 副作用：Flyway 默认在同一事务内执行，若函数异常会回滚整个 V9 迁移。
-- 改为每个语句独立提交（由 Spring 配置），同时函数体内捕获异常回退到原值。

CREATE OR REPLACE FUNCTION artverse_fix_double_utf8(t TEXT) RETURNS TEXT AS $$
DECLARE
    raw_bytes BYTEA;
    fixed TEXT;
    orig_bytes BYTEA;
    fixed_bytes BYTEA;
BEGIN
    IF t IS NULL OR t = '' THEN
        RETURN t;
    END IF;

    -- 把当前 text 按 SQL_ASCII 字节流还原（不验证字符）
    BEGIN
        raw_bytes := convert_to(t, 'SQL_ASCII');
        orig_bytes := convert_to(t, 'SQL_ASCII');
        fixed := convert_from(raw_bytes, 'UTF8');
        fixed_bytes := convert_to(fixed, 'SQL_ASCII');
    EXCEPTION WHEN OTHERS THEN
        RETURN t;
    END;

    -- 双重编码的典型特征：UTF-8 解读后字节会缩短（因为 N 个 Latin-1 字节 → 1 个 UTF-8 字符）
    -- 若长度未变化或变长，说明不是双重编码，保持原值
    IF octet_length(fixed_bytes) < octet_length(orig_bytes) THEN
        RETURN fixed;
    ELSE
        RETURN t;
    END IF;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- ==================== stories ====================
UPDATE stories SET
    title              = artverse_fix_double_utf8(title),
    description        = artverse_fix_double_utf8(description),
    character_profiles = artverse_fix_double_utf8(character_profiles)
WHERE title IS NOT NULL OR description IS NOT NULL OR character_profiles IS NOT NULL;

-- ==================== chapters ====================
UPDATE chapters SET
    novel_content      = artverse_fix_double_utf8(novel_content),
    scenes_text        = artverse_fix_double_utf8(scenes_text),
    character_profiles = artverse_fix_double_utf8(character_profiles)
WHERE novel_content IS NOT NULL OR scenes_text IS NOT NULL OR character_profiles IS NOT NULL;

-- ==================== chat_messages ====================
UPDATE chat_messages SET
    content = artverse_fix_double_utf8(content)
WHERE content IS NOT NULL;

-- ==================== manga_images ====================
UPDATE manga_images SET
    prompt = artverse_fix_double_utf8(prompt)
WHERE prompt IS NOT NULL;
