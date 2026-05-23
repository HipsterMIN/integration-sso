-- Testcontainers PostgreSQL 초기화 스크립트
-- onepass-support 의 default schema 'support' 를 사전 생성하여
-- Flyway 가 V1/V2/V3 마이그레이션을 정상 실행할 수 있도록 한다.
--
-- 운영 환경에서는 DBA 가 사전에 스키마를 생성하므로 본 SQL 은 테스트 전용이다.

CREATE SCHEMA IF NOT EXISTS support AUTHORIZATION onepass;
