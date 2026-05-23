package kr.go.smes.support.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "qna_post")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class QnaPostEntity {

    @Id
    @Column(name = "qna_id", nullable = false)
    private UUID qnaId;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "agency_id")
    private String agencyId;

    @Column(name = "qna_ttl", nullable = false, length = 200)
    private String qnaTitle;

    @Column(name = "qna_cn", nullable = false)
    private String qnaContent;

    @Column(name = "qna_stts_cd", nullable = false, length = 20)
    private String qnaStatusCode;

    @Column(name = "secret_yn", nullable = false, length = 1)
    private String secretYn;

    @Column(name = "anonymous_yn", nullable = false, length = 1)
    private String anonymousYn;

    @Column(name = "writer_user_id", length = 64)
    private String writerUserId;

    @Column(name = "anonymous_display_name", length = 80)
    private String anonymousDisplayName;

    @Column(name = "anonymous_contact_email", length = 120)
    private String anonymousContactEmail;

    @Column(name = "use_yn", nullable = false, length = 1)
    private String useYn;

    @Column(name = "frst_regist_pnttm", nullable = false)
    private Instant frstRegistPnttm;

    @Column(name = "frst_register_id", nullable = false, length = 64)
    private String frstRegisterId;

    @Column(name = "last_updt_pnttm", nullable = false)
    private Instant lastUpdtPnttm;

    @Column(name = "last_updusr_id", nullable = false, length = 64)
    private String lastUpdusrId;

    @Builder.Default
    @OneToMany(mappedBy = "qnaPost", cascade = CascadeType.ALL)
    @OrderBy("frstRegistPnttm ASC")
    private List<QnaAnswerEntity> answers = new ArrayList<>();
}
