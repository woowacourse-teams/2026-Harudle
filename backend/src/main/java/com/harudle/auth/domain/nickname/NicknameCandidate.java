package com.harudle.auth.domain.nickname;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "nickname_candidates")
public class NicknameCandidate {

    @Id
    @Column(length = 30)
    private String nickname;

    @Column(name = "allocation_order", nullable = false)
    private long allocationOrder;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    protected NicknameCandidate() {
    }
}
