-- V26: 첨부 file_path 인덱스 (Track 82·D-176 결정 7)
-- 작성일: 2026-09-17
-- 참조: decisions.md D-176(클레임 첨부 인가 서빙 — 서빙 요청 key → attachment 행 조회가 file_path 등치 검색인데 인덱스가 없어
--       살아있는 전 행 스캔(EXPLAIN key=ix_attachment_deleted_at)이 이미지 GET마다 발생)
--
-- prefix 255 선택 근거: file_path는 varchar(2048) utf8mb4(8,192 bytes)라 InnoDB 키 상한 3,072 bytes를 넘어 전체 컬럼 인덱스 불가.
-- 실제 저장값은 /api/v1/files/claims/yyyy/MM/{ULID}.{ext} 약 50자라 prefix 255(1,020 bytes)로 등치 탐색이 충분하다.
ALTER TABLE attachment
  ADD KEY ix_attachment_file_path (file_path(255));

-- rollback:
--   ALTER TABLE attachment DROP KEY ix_attachment_file_path;
