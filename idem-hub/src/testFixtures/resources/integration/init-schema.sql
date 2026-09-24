-- Integration Test DB 초기화 스크립트
-- Flyway 마이그레이션 실행 전 스키마 사전 생성
-- (PostgreSQLContainer withInitScript()로 실행됨)
CREATE SCHEMA IF NOT EXISTS ido;
