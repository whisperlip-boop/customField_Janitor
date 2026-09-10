package com.bskim.jira.janitor.fields.model;

import java.util.Date;

/**
 * 진행률 폴링용 스냅샷. 기획서 9장: 단계 단위로 보고한다.
 */
public final class ScanProgress {

    public enum State { IDLE, RUNNING, DONE, FAILED }

    /** 기획서 9장의 보고 단계. 순서를 바꾸면 진행률 표시가 어긋난다. */
    public enum Stage {
        FIELDS("janitor.fields.stage.fields"),
        VALUES("janitor.fields.stage.values"),
        MANAGED("janitor.fields.stage.managed"),
        SCREENS("janitor.fields.stage.screens"),
        FIELD_CONFIGS("janitor.fields.stage.fieldConfigs"),
        CONTEXTS("janitor.fields.stage.contexts"),
        WORKFLOWS("janitor.fields.stage.workflows"),
        FILTERS("janitor.fields.stage.filters"),
        SCHEMES("janitor.fields.stage.schemes"),
        GADGETS("janitor.fields.stage.gadgets"),
        COLUMN_LAYOUTS("janitor.fields.stage.columnLayouts"),
        FINISHING("janitor.fields.stage.finishing");

        private final String i18nKey;

        Stage(String i18nKey) {
            this.i18nKey = i18nKey;
        }

        public String getI18nKey() {
            return i18nKey;
        }
    }

    private final State state;
    private final Stage stage;
    private final int stageIndex;
    private final int stageTotal;
    private final Date startedAt;
    private final String error;

    public ScanProgress(State state, Stage stage, Date startedAt, String error) {
        this.state = state;
        this.stage = stage;
        this.stageIndex = stage == null ? 0 : stage.ordinal() + 1;
        this.stageTotal = Stage.values().length;
        this.startedAt = startedAt == null ? null : new Date(startedAt.getTime());
        this.error = error;
    }

    public static ScanProgress idle() {
        return new ScanProgress(State.IDLE, null, null, null);
    }

    public State getState() {
        return state;
    }

    public Stage getStage() {
        return stage;
    }

    public int getStageIndex() {
        return stageIndex;
    }

    public int getStageTotal() {
        return stageTotal;
    }

    public int getPercent() {
        return stageTotal == 0 ? 0 : (int) Math.round(100.0 * stageIndex / stageTotal);
    }

    public Date getStartedAt() {
        return startedAt == null ? null : new Date(startedAt.getTime());
    }

    public String getError() {
        return error;
    }
}
