package kr.go.smes.support.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "qna_answer")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class QnaAnswerEntity {

    @Id
    @Column(name = "qna_answer_id", nullable = false)
    private UUID qnaAnswerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "qna_id", nullable = false)
    private QnaPostEntity qnaPost;

    @Column(name = "answer_cn", nullable = false)
    private String answerContent;

    @Column(name = "secret_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String secretYn;

    @Column(name = "answered_by_user_id", nullable = false, length = 64)
    private String answeredByUserId;

    @Column(name = "use_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private String useYn;

    @Column(name = "frst_regist_pnttm", nullable = false)
    private Instant frstRegistPnttm;

    @Column(name = "frst_register_id", nullable = false, length = 64)
    private String frstRegisterId;

    @Column(name = "last_updt_pnttm", nullable = false)
    private Instant lastUpdtPnttm;

    @Column(name = "last_updusr_id", nullable = false, length = 64)
    private String lastUpdusrId;
}
