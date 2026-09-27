package com.example.lms.assist;

import com.example.lms.domain.ChatMessage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable acceptance and terminal CAS; message bodies stay in ChatMessage. */
@Entity
@Table(name="nova_focus_turn",uniqueConstraints={
    @UniqueConstraint(name="uk_nova_request",columnNames={"profile_id","request_key"}),
    @UniqueConstraint(name="uk_nova_sequence",columnNames={"profile_id","sequence_number"})
},indexes=@Index(name="ix_nova_history",columnList="profile_id,sequence_number"))
@Getter @Setter @NoArgsConstructor
public class NovaFocusTurn {
    @Id @Column(length=36) private String id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="profile_id")
    private NovaFocusProfile profile;
    @Column(name="request_key",nullable=false,length=64) private String requestKey;
    @Column(nullable=false,length=64) private String inputHash;
    @Column(name="sequence_number",nullable=false) private long sequenceNumber;
    @Column(nullable=false,length=128) private String activationId;
    @Column(nullable=false,length=24) private String state;
    @OneToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_message_id")
    private ChatMessage userMessage;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="assistant_message_id")
    private ChatMessage assistantMessage;
}
