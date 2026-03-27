-- tenant_menu: 사업장별 사용 가능 메뉴 정의 (Main DB)
-- spjangcd 사업장이 어떤 menu_item을 사용하는지 매핑
-- superuser: tenant_menu 전체 표시
-- 일반 user: tenant_menu ∩ user_group_menu(spjangcd+group_id 일치)

CREATE TABLE IF NOT EXISTS tenant_menu (
    id          SERIAL PRIMARY KEY,
    spjangcd    VARCHAR(10)  NOT NULL,
    menu_code   VARCHAR(50)  NOT NULL,
    CONSTRAINT uq_tenant_menu UNIQUE (spjangcd, menu_code)
);

-- 예시: sam 사업장에 메뉴 등록
-- INSERT INTO tenant_menu (spjangcd, menu_code) VALUES ('sam', 'wm_mat');
-- INSERT INTO tenant_menu (spjangcd, menu_code) VALUES ('sam', 'wm_stock');

-- 기존 menu_item의 모든 MenuCode를 특정 사업장에 일괄 등록하려면:
-- INSERT INTO tenant_menu (spjangcd, menu_code)
-- SELECT 'sam', "MenuCode" FROM menu_item
-- ON CONFLICT DO NOTHING;
