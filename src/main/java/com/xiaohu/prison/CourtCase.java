package com.xiaohu.prison;

import java.util.UUID;

/**
 * 法院案件模型（record，不可变），字段与 cases.yml schema 一一对应（PLAN.md §17.2）。
 *
 * <p>状态机（v4.x，定稿；无陪审团投票）：
 * <ul>
 *   <li>PENDING → TRIAL → GUILTY；</li>
 *   <li>TRIAL →（被告离线且 auto-execute 宣判有罪）AWAITING_EXECUTION →（重进服执行）GUILTY；</li>
 *   <li>TRIAL → INNOCENT；PENDING/TRIAL → DISMISSED。</li>
 * </ul>
 * 变更一律通过 with* 方法构造新实例（与 {@link Prisoner} 的 withProgress 同风格）。
 */
public record CourtCase(
        int id,
        State state,
        UUID defendantUuid,
        String defendantName,
        UUID plaintiffUuid,          // null = 法院公诉（法官/管理员起诉，无玩家原告）
        String plaintiffName,        // 公诉时 "法院公诉"
        UUID defenseLawyerUuid,      // 被告律师（可选，§18.11）
        String defenseLawyerName,
        UUID prosecutionLawyerUuid,  // 原告律师（可选）
        String prosecutionLawyerName,
        String reason,
        long filedAt,
        UUID judgeUuid,
        String judgeName,
        long trialStartedAt,
        int sentenceAmount,          // 有罪刑期（挖 N 个黑曜石）
        long verdictAt
) {

    /** 案件状态（无 AWAITING_SENTENCE / 无投票环节）。 */
    public enum State {
        PENDING, TRIAL, AWAITING_EXECUTION, GUILTY, INNOCENT, DISMISSED;

        /** 容错解析：null / 非法 → null（加载时跳过损坏记录）。 */
        public static State fromStateName(String name) {
            if (name == null) {
                return null;
            }
            try {
                return State.valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** 是否进行中（PENDING / TRIAL / AWAITING_EXECUTION）。 */
    public boolean isActive() {
        return state == State.PENDING || state == State.TRIAL || state == State.AWAITING_EXECUTION;
    }

    /** 是否已结案。 */
    public boolean isClosed() {
        return !isActive();
    }

    /** 原告显示名（公诉时「法院公诉」）。 */
    public String plaintiffDisplayName() {
        return plaintiffUuid == null ? "法院公诉" : plaintiffName;
    }

    /** 案件是否是公诉（无玩家原告）。 */
    public boolean isProsecution() {
        return plaintiffUuid == null;
    }

    /** 仅改状态（用于 DISMISSED 等外部已判定案件；不触碰刑期/宣判时间）。 */
    public CourtCase withState(State newState) {
        return new CourtCase(id, newState, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                defenseLawyerUuid, defenseLawyerName,
                prosecutionLawyerUuid, prosecutionLawyerName,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentenceAmount, verdictAt);
    }

    /** PENDING → TRIAL：记录开庭法官与开庭时间。 */
    public CourtCase startTrial(UUID judgeUuid, String judgeName, long trialStartedAt) {
        return new CourtCase(id, State.TRIAL, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                defenseLawyerUuid, defenseLawyerName,
                prosecutionLawyerUuid, prosecutionLawyerName,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentenceAmount, verdictAt);
    }

    /** 记录有罪刑期（不改变状态；AWAITING_EXECUTION / GUILTY 用）。 */
    public CourtCase withSentence(int sentence) {
        return new CourtCase(id, state, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                defenseLawyerUuid, defenseLawyerName,
                prosecutionLawyerUuid, prosecutionLawyerName,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentence, verdictAt);
    }

    /** 宣判结案（GUILTY / INNOCENT / DISMISSED）：改状态 + 刑期 + 宣判时间。 */
    public CourtCase closeVerdict(State newState, int sentence, long verdictAt) {
        return new CourtCase(id, newState, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                defenseLawyerUuid, defenseLawyerName,
                prosecutionLawyerUuid, prosecutionLawyerName,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentence, verdictAt);
    }

    /** 有罪但被告离线 + auto-execute=true：进入 AWAITING_EXECUTION（记录刑期与宣判时间）。 */
    public CourtCase awaitingSentence(int sentence, long verdictAt) {
        return new CourtCase(id, State.AWAITING_EXECUTION, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                defenseLawyerUuid, defenseLawyerName,
                prosecutionLawyerUuid, prosecutionLawyerName,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentence, verdictAt);
    }

    /** AWAITING_EXECUTION → GUILTY：重进服执行入狱成功后补记宣判时间。 */
    public CourtCase executeAwaiting(long verdictAt) {
        return new CourtCase(id, State.GUILTY, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                defenseLawyerUuid, defenseLawyerName,
                prosecutionLawyerUuid, prosecutionLawyerName,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentenceAmount, verdictAt);
    }

    /** 指定被告律师（LawyerPick 用；调用方是被告）。 */
    public CourtCase withDefenseLawyer(UUID uuid, String name) {
        return new CourtCase(id, state, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                uuid, name,
                prosecutionLawyerUuid, prosecutionLawyerName,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentenceAmount, verdictAt);
    }

    /** 指定原告律师（LawyerPick 用；调用方是原告）。 */
    public CourtCase withProsecutionLawyer(UUID uuid, String name) {
        return new CourtCase(id, state, defendantUuid, defendantName,
                plaintiffUuid, plaintiffName,
                defenseLawyerUuid, defenseLawyerName,
                uuid, name,
                reason, filedAt, judgeUuid, judgeName, trialStartedAt, sentenceAmount, verdictAt);
    }
}
