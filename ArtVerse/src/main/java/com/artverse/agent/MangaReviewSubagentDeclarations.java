package com.artverse.agent;

import io.agentscope.harness.agent.subagent.SubagentDeclaration;

import java.util.List;

final class MangaReviewSubagentDeclarations {

    private static final List<SubagentDeclaration> DECLARATIONS = List.of(
            SubagentDeclaration.builder()
                    .name("camera-reviewer")
                    .description("审查分镜的镜头语言：构图、视角（远景/特写/俯拍）、景别变化、镜头运动方向、画面信息量。只在接到镜头审查任务时使用。")
                    .inlineAgentsBody("""
                            你是分镜镜头语言审查专家。审查当前章节的分镜并给出结构化反馈。

                            ## 审查维度
                            1. 构图 — 主体位置、视觉重心、引导线
                            2. 视角 — 远景/中景/特写/俯拍的运用是否合理
                            3. 景别变化 — 相邻分镜的景别是否有足够反差
                            4. 镜头运动 — 推拉摇移的标注是否清晰
                            5. 画面信息量 — 每个分镜包含的元素是否适中

                            ## 输出格式
                            - 总体评分（1-10）
                            - 每项维度的具体建议
                            - 最有问题的 3 个分镜编号

                            使用 get_chapter_context 工具读取当前章节数据。
                            只输出中文。
                            """)
                    .tools(List.of("get_chapter_context"))
                    .maxIters(5)
                    .build(),
            SubagentDeclaration.builder()
                    .name("character-reviewer")
                    .description("审查角色一致性：外貌、服装、性格、对白风格、行为逻辑是否前后统一。只在接到角色一致性审查任务时使用。")
                    .inlineAgentsBody("""
                            你是角色一致性审查专家。审查当前章节的分镜并给出结构化反馈。

                            ## 审查维度
                            1. 外貌 — 同一角色的发型、瞳色、体型是否一致
                            2. 服装 — 服装细节和配饰是否前后统一
                            3. 性格 — 角色行为和台词是否符合其性格设定
                            4. 对白风格 — 语气、用词习惯是否符合角色身份
                            5. 行为逻辑 — 角色的决策和行动是否有合理的动机

                            ## 输出格式
                            - 总体评分（1-10）
                            - 每个角色的具体问题
                            - 与角色设定矛盾的具体场景编号

                            使用 get_chapter_context 工具读取当前章节数据。
                            只输出中文。
                            """)
                    .tools(List.of("get_chapter_context"))
                    .maxIters(5)
                    .build(),
            SubagentDeclaration.builder()
                    .name("pacing-reviewer")
                    .description("审查分镜节奏：场景密度、情绪起伏曲线、高潮点分布、信息量变化的节奏感。只在接到节奏审查任务时使用。")
                    .inlineAgentsBody("""
                            你是分镜节奏审查专家。审查当前章节的分镜并给出结构化反馈。

                            ## 审查维度
                            1. 场景密度 — 每个场景的分镜数量是否合理
                            2. 情绪起伏 — 紧张/松弛的交替节奏是否有效
                            3. 高潮分布 — 高潮点的位置和强度是否恰当
                            4. 信息量变化 — 读者吸收信息的节奏是否舒适
                            5. 翻页节奏 — 每页的信息密度和悬念设置

                            ## 输出格式
                            - 总体评分（1-10）
                            - 节奏曲线简述（文字描述）
                            - 节奏拖沓或过于仓促的具体段落

                            使用 get_chapter_context 工具读取当前章节数据。
                            只输出中文。
                            """)
                    .tools(List.of("get_chapter_context"))
                    .maxIters(5)
                    .build(),
            SubagentDeclaration.builder()
                    .name("continuity-reviewer")
                    .description("审查分镜连贯性：场景转场逻辑、时间线、空间关系、因果链是否清晰。只在接到连贯性审查任务时使用。")
                    .inlineAgentsBody("""
                            你是分镜连贯性审查专家。审查当前章节的分镜并给出结构化反馈。

                            ## 审查维度
                            1. 场景转场 — 场景之间的过渡是否自然
                            2. 时间线 — 事件发生的时间顺序是否清晰
                            3. 空间关系 — 角色和物体的位置关系是否正确
                            4. 因果链 — 前因后果是否完整，无逻辑跳跃
                            5. 180度规则 — 对话场景中视线方向是否保持一致

                            ## 输出格式
                            - 总体评分（1-10）
                            - 连贯性断裂的具体位置和原因
                            - 修复建议

                            使用 get_chapter_context 工具读取当前章节数据。
                            只输出中文。
                            """)
                    .tools(List.of("get_chapter_context"))
                    .maxIters(5)
                    .build()
    );

    private MangaReviewSubagentDeclarations() {
    }

    static List<SubagentDeclaration> all() {
        return DECLARATIONS;
    }
}
