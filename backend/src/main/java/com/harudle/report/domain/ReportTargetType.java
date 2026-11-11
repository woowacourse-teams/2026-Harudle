package com.harudle.report.domain;

public enum ReportTargetType {
    USER(false), FEED(false), COMMENT(false),
    /** 작성자 본인만 볼 수 있는 개인 4컷 만화의 AI 생성 결과. */
    DIARY(true);

    private final boolean ownerOnly;

    ReportTargetType(boolean ownerOnly) {
        this.ownerOnly = ownerOnly;
    }

    /** true면 작성자 본인만 신고할 수 있고, false면 작성자 본인은 신고할 수 없다. */
    public boolean ownerOnly() {
        return ownerOnly;
    }
}
