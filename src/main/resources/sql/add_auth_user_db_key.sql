-- auth_user 테이블에 db_key 컬럼 추가 (DB 라우팅 전용)
-- spjangcd 는 이제 사업장 DB 내 로우 아이솔레이션용으로만 사용
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS db_key VARCHAR(20);

-- 초기값: 기존 spjangcd 값을 그대로 복사
UPDATE auth_user SET db_key = spjangcd WHERE db_key IS NULL;
