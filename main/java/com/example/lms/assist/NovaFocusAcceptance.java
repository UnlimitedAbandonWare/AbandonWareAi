package com.example.lms.assist;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Content-free receipt ledger. Legacy nova_focus_turn/message rows remain untouched. */
@Entity
@Table(name="nova_focus_acceptance",uniqueConstraints={
    @UniqueConstraint(name="uk_nova_accept_request",columnNames={"profile_id","request_key"}),
    @UniqueConstraint(name="uk_nova_accept_sequence",columnNames={"profile_id","sequence_number"})
})
@Getter @Setter @NoArgsConstructor
public class NovaFocusAcceptance {
    @Id @Column(length=36) private String id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="profile_id")
    private NovaFocusProfile profile;
    @Column(name="request_key",nullable=false,length=64) private String requestKey;
    @Column(nullable=false,length=64) private String inputHash;
    @Column(name="sequence_number",nullable=false) private long sequenceNumber;
    @Column(nullable=false,length=128) private String activationId;
    @Column(nullable=false,length=24) private String state;
}
