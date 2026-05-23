package kr.go.smes.support.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportPhoneConsultationRepository extends JpaRepository<SupportPhoneConsultationEntity, UUID> {
}
