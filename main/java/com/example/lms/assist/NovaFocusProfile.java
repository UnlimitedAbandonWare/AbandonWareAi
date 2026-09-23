package com.example.lms.assist;

import com.example.lms.domain.ChatSession;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One server-derived owner/channel purpose, independent of a transient capture. */
@Entity
@Table(name="nova_focus_profile")
@Getter @Setter @NoArgsConstructor
public class NovaFocusProfile {
    @Id @Column(length=64) private String id;
    @Column(nullable=false,length=128) private String ownerKey;
    @Column(nullable=false,length=128) private String channel;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="chat_session_id",unique=true)
    private ChatSession chatSession;
    @Column(columnDefinition="TEXT") private String settingsJson;
    @Column(nullable=false) private long settingsVersion;
    @Column(nullable=false) private long nextSequence;
    @Column(nullable=false,columnDefinition="bigint default 0") private long memoryRevision;
}
