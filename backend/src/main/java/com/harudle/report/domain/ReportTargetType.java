package com.harudle.report.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ReportTargetType {
    USER(false, EnumSet.allOf(ReportReason.class)),
    FEED(false, EnumSet.allOf(ReportReason.class)),
    COMMENT(false, EnumSet.allOf(ReportReason.class)),
    /** 작성자 본인만 볼 수 있는 개인 4컷 만화의 AI 생성 결과. 다른 사람이 볼 수 없어 스팸·괴롭힘 사유는 받지 않는다. */
    DIARY(true, EnumSet.of(ReportReason.INAPPROPRIATE_CONTENT, ReportReason.OTHER));

    private final boolean ownerOnly;
    private final Set<ReportReason> allowedReasons;

    ReportTargetType(boolean ownerOnly, Set<ReportReason> allowedReasons) {
        this.ownerOnly = ownerOnly;
        this.allowedReasons = Set.copyOf(allowedReasons);
    }

    /** true면 작성자 본인만 신고할 수 있고, false면 작성자 본인은 신고할 수 없다. */
    public boolean ownerOnly() {
        return ownerOnly;
    }

    /** 이 대상에 새로 접수할 수 있는 신고 사유인지 확인한다. */
    public boolean allows(ReportReason reason) {
        return allowedReasons.contains(reason);
    }
}
