-- KAN-84. 네 번째 카테고리 '개인 일정'.
--
-- 코드 목록을 CHECK 으로 못 박아 두었으므로 제약을 다시 걸어야 한다. MySQL 8.0.19+ 와
-- H2 2.x 가 모두 받는 DROP CONSTRAINT 를 쓴다 (MySQL 전용 DROP CHECK 는 H2 에서 깨진다).
--
-- 등급에 따라 가리지 않는다. 랩실이 함께 보려고 올리는 '공유할 개인 일정'이라서,
-- 조회 등급에서 빠지는 것은 여전히 카드/경비 하나뿐이다.
ALTER TABLE category DROP CONSTRAINT ck_category_code;
ALTER TABLE category ADD CONSTRAINT ck_category_code
    CHECK (code IN ('project', 'lab', 'card', 'personal'));

INSERT INTO category (code, name, sort_order, created_at, updated_at) VALUES
    ('personal', '개인 일정', 3, '2026-09-30 00:00:00', '2026-09-30 00:00:00');
