-- NL2SQL dev demo 库（MySQL）。仅用于本地端到端演示，prod 接真实只读库时
-- 把 app.nl2sql.datasource.seed-script 置空、url/账号指向真库即可，不跑本脚本。
--
-- 前置：本机有 MySQL（默认 localhost:3306），admin 账号（默认 root）有建库/建用户/授权权限。
-- url 带 createDatabaseIfNotExist=true，库 nl2sql_demo 不存在会自动建。
--
-- 三张业务表均带 tenant_id（演示 L6 租户隔离）；orders.status 用中文枚举（演示坑3：
-- SchemaProvider 把 distinct 值带进 schema，帮模型用对中文 WHERE 值）。
-- 列用 MySQL 内联 COMMENT，SchemaProvider 经 useInformationSchema=true 的 getColumns 读到 REMARKS。

DROP TABLE IF EXISTS refunds;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS customers;

CREATE TABLE customers (
    id          INT PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL COMMENT '租户 id，所有查询必须按此过滤',
    name        VARCHAR(128) NOT NULL,
    region      VARCHAR(32)  NOT NULL COMMENT '客户所在大区：华东 / 华北 / 华南 / 西南',
    created_at  DATE         NOT NULL
);

CREATE TABLE orders (
    id          INT PRIMARY KEY,
    tenant_id   VARCHAR(64)   NOT NULL COMMENT '租户 id，所有查询必须按此过滤',
    customer_id INT           NOT NULL,
    amount      DECIMAL(12,2) NOT NULL COMMENT '订单金额，单位元',
    status      VARCHAR(16)   NOT NULL COMMENT '订单状态（中文枚举）：已支付 / 已发货 / 已取消 / 已退款',
    created_at  DATE          NOT NULL
);

CREATE TABLE refunds (
    id          INT PRIMARY KEY,
    tenant_id   VARCHAR(64)   NOT NULL COMMENT '租户 id，所有查询必须按此过滤',
    order_id    INT           NOT NULL,
    customer_id INT           NOT NULL,
    amount      DECIMAL(12,2) NOT NULL COMMENT '退款金额，单位元',
    reason      VARCHAR(128),
    status      VARCHAR(16)   NOT NULL COMMENT '退款审批状态（英文枚举）：pending / approved / rejected',
    created_at  DATE          NOT NULL
);

-- ---- 租户 tenantA（对应 demo key dev-key-tenantA-admin，主数据集，给足数据方便演示） ----
INSERT INTO customers (id, tenant_id, name, region, created_at) VALUES
 (1, 'tenantA', '张三', '华东', '2026-01-10'),
 (2, 'tenantA', '李四', '华北', '2026-02-15'),
 (3, 'tenantA', '王五', '华南', '2026-03-20'),
 (4, 'tenantA', '赵六', '华东', '2026-04-01'),
 (5, 'tenantA', '钱七', '西南', '2026-04-18');

INSERT INTO orders (id, tenant_id, customer_id, amount, status, created_at) VALUES
 (101, 'tenantA', 1, 1200.00, '已支付', '2026-05-03'),
 (102, 'tenantA', 1,  800.00, '已退款', '2026-05-06'),
 (103, 'tenantA', 2, 2500.00, '已发货', '2026-05-09'),
 (104, 'tenantA', 3,  450.00, '已退款', '2026-05-12'),
 (105, 'tenantA', 3, 3200.00, '已支付', '2026-05-20'),
 (106, 'tenantA', 4,  150.00, '已取消', '2026-05-22'),
 (107, 'tenantA', 4, 5400.00, '已退款', '2026-05-25'),
 (108, 'tenantA', 5,  990.00, '已支付', '2026-04-28'),
 (109, 'tenantA', 2, 1750.00, '已退款', '2026-04-15');

INSERT INTO refunds (id, tenant_id, order_id, customer_id, amount, reason, status, created_at) VALUES
 (201, 'tenantA', 102, 1,  800.00, '商品损坏',   'approved', '2026-05-07'),
 (202, 'tenantA', 104, 3,  450.00, '尺码不符',   'approved', '2026-05-13'),
 (203, 'tenantA', 107, 4, 5400.00, '七天无理由', 'approved', '2026-05-26'),
 (204, 'tenantA', 109, 2, 1750.00, '质量问题',   'approved', '2026-04-16'),
 (205, 'tenantA', 105, 3,  300.00, '部分退款',   'pending',  '2026-05-21'),
 (206, 'tenantA', 103, 2,  120.00, '运费补偿',   'rejected', '2026-05-10');

-- ---- 租户 tenantB（对应 demo key dev-key-tenantB-readonly；演示隔离：tenantA 的查询不该看到这些行） ----
INSERT INTO customers (id, tenant_id, name, region, created_at) VALUES
 (1001, 'tenantB', 'ACME-A', '华北', '2026-03-01'),
 (1002, 'tenantB', 'ACME-B', '华东', '2026-03-05');

INSERT INTO orders (id, tenant_id, customer_id, amount, status, created_at) VALUES
 (2001, 'tenantB', 1001, 9999.00, '已退款', '2026-05-15'),
 (2002, 'tenantB', 1002, 8888.00, '已支付', '2026-05-18');

INSERT INTO refunds (id, tenant_id, order_id, customer_id, amount, reason, status, created_at) VALUES
 (3001, 'tenantB', 2001, 1001, 9999.00, '大额退款', 'approved', '2026-05-16');

-- ---- L1：只读账号。SqlQueryTool 用它执行，写/DDL 在 DB 层就被拒（access denied） ----
CREATE USER IF NOT EXISTS 'nl2sql_ro'@'%' IDENTIFIED BY 'nl2sql_ro';
GRANT SELECT ON nl2sql_demo.* TO 'nl2sql_ro'@'%';
FLUSH PRIVILEGES;
