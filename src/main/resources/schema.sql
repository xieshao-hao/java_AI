-- ============================================================
-- SmartDesk 结构化业务数据表（H2 文件模式）
-- 使用 CREATE TABLE IF NOT EXISTS 保证 spring.sql.init.mode=always 下的幂等性
-- 设计要点：
--   1. 业务编码（device_code/order_code）做关联键，而非自增 ID —— LLM 工具调用
--      拿到的就是用户口中的 "GW-10086"，编码即查询条件
--   2. 不建外键约束 —— IoT 场景高频写入 + 设备可归档，弱关联 + 应用层校验更务实
--   3. 状态/等级用 VARCHAR —— LLM 生成的查询参数是文本（"P1"/"ACTIVE"），直接透传
-- ============================================================

-- 1. 设备表
CREATE TABLE IF NOT EXISTS device (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_code      VARCHAR(32)  NOT NULL UNIQUE,   -- 业务编码：GW-/SN-/CAM-/CTL- 前缀
    name             VARCHAR(64)  NOT NULL,          -- 设备名称
    type             VARCHAR(32)  NOT NULL,          -- 类型：GATEWAY/SENSOR/CAMERA/CONTROLLER
    location         VARCHAR(64)  NOT NULL,          -- 安装位置
    status           VARCHAR(16)  NOT NULL,          -- ONLINE/OFFLINE/FAULT/MAINTENANCE
    last_online_time TIMESTAMP    NOT NULL,          -- 最后在线时间
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. 告警表
CREATE TABLE IF NOT EXISTS alarm (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    alarm_code   VARCHAR(32) NOT NULL UNIQUE,       -- 业务编码：AL-yyyyMMdd-NNN
    device_code  VARCHAR(32) NOT NULL,              -- 关联设备编码（弱关联）
    level        VARCHAR(8)  NOT NULL,              -- P1(紧急)/P2(严重)/P3(一般)/P4(提示)
    content      VARCHAR(255) NOT NULL,             -- 告警内容
    status       VARCHAR(16) NOT NULL,              -- ACTIVE/PROCESSING/RESOLVED
    create_time  TIMESTAMP   NOT NULL,
    resolve_time TIMESTAMP  NULL                    -- NULL = 未解决（"未处理告警"高频查询依赖此判断）
);

-- 3. 工单表
CREATE TABLE IF NOT EXISTS work_order (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_code  VARCHAR(32) NOT NULL UNIQUE,        -- 业务编码：WO-yyyyMMdd-NNNN，由系统生成
    device_code VARCHAR(32) NOT NULL,               -- 关联设备编码（弱关联）
    title       VARCHAR(64)  NOT NULL,              -- 工单标题
    description VARCHAR(500),                       -- 详细描述
    status      VARCHAR(16) NOT NULL,               -- OPEN/IN_PROGRESS/COMPLETED/CLOSED
    priority    VARCHAR(8)  NOT NULL,               -- P1/P2/P3/P4（与告警等级对齐）
    creator     VARCHAR(32) NOT NULL,               -- 创建人（权限拦截的预留字段）
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 覆盖主要查询路径的索引（种子量级足够，结构上预留生产级演进位置）
CREATE INDEX IF NOT EXISTS idx_alarm_device ON alarm(device_code);
CREATE INDEX IF NOT EXISTS idx_alarm_level_status ON alarm(level, status);
CREATE INDEX IF NOT EXISTS idx_wo_device ON work_order(device_code);
