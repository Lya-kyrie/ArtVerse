package com.artverse.domain;

/**
 * 章节状态机。
 * <p>
 * 迁移规则：
 * <pre>
 *                ┌──────────────┐
 *       ┌────────►│    DRAFT     │◄────────┐
 *       │         └──────┬───────┘         │
 *       │                │ generate-scenes │
 *       │                ▼                  │
 *       │         ┌──────────────┐         │
 *       │         │ SCENES_READY │         │
 *       │         └──────┬───────┘         │
 *       │                │ start-manga      │
 *       │                ▼                  │
 *       │         ┌─────────────────────┐   │
 *       │         │ IMAGES_GENERATING   │   │
 *       │         └──────┬────────┬─────┘   │
 *       │                │        │         │
 *       │        all-done│        │error    │
 *       │                ▼        ▼         │
 *       │         ┌──────────┐ ┌────────┐   │
 *       └─────────┤COMPLETED │ │ FAILED │───┘
 *                 └──────────┘ └────────┘
 * </pre>
 *
 * 不变量：
 * - DRAFT → 唯一可写入 novelContent / scenes_text 的状态
 * - SCENES_READY → 唯一可启动漫画生成的状态
 * - IMAGES_GENERATING → 唯一可推进图片生成的状态
 * - COMPLETED / FAILED → 终态，可被重置回 DRAFT
 *
 * @see <a href="docs/knowledge/modules/chapter/references/state-machine.md">状态机详情</a>
 */
public enum ChapterStatus {
    /** 草稿（创建后默认） */
    DRAFT,
    /** 分镜已生成 */
    SCENES_READY,
    /** 图像生成中 */
    IMAGES_GENERATING,
    /** 全部完成 */
    COMPLETED,
    /** 失败（任何中间态可进入） */
    FAILED;

    /**
     * 是否终态（不可自动迁移）
     */
    public boolean isTerminal() {
        return this == COMPLETED;
    }

    /**
     * 是否可启动图像生成
     */
    public boolean canStartImageGeneration() {
        return this == SCENES_READY;
    }

    /**
     * 是否可生成/重写分镜
     */
    public boolean canGenerateScenes() {
        return this == DRAFT || this == FAILED;
    }
}
