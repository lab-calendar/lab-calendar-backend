-- KAN-76. 일정 출처 GOOGLE_SYNC 를 CARD_IMPORT 로.
--
-- 구글 API 로 직접 읽어 오던 시절의 이름이다. 지금 이 값이 붙는 길은 엑셀 업로드와
-- 구글 시트 동기화 둘인데, 어느 쪽도 "GOOGLE_SYNC" 라고 읽히지 않는다. 둘을 아우르는
-- 이름은 카드 내역을 들여왔다는 사실이다.
--
-- 순서가 있다. CHECK 가 값 목록을 못 박고 있어서, 제약을 먼저 걷지 않으면 UPDATE 가
-- 제 손으로 건 덫에 걸린다. 걷고 → 옮기고 → 새 목록으로 다시 건다.
--
-- DROP CONSTRAINT 를 쓴다. MySQL 전용 DROP CHECK 는 H2 에서 깨지는데, 테스트가 같은
-- 마이그레이션을 H2(MySQL 모드)에서도 돌린다 (V1.1.0.004 와 같은 이유).
ALTER TABLE event DROP CONSTRAINT ck_event_source;

UPDATE event SET source = 'CARD_IMPORT' WHERE source = 'GOOGLE_SYNC';

ALTER TABLE event ADD CONSTRAINT ck_event_source CHECK (
    (source = 'MANUAL' AND research_project_id IS NULL AND card_expense_id IS NULL)
    OR (source = 'AUTO_GENERATED' AND research_project_id IS NOT NULL AND card_expense_id IS NULL)
    OR (source = 'CARD_IMPORT' AND research_project_id IS NULL AND card_expense_id IS NOT NULL)
);
