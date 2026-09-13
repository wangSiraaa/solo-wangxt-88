# periodic-scheduler — 周期任务调度服务

Spring Boot + Quartz + PostgreSQL 的周期任务 API。核心设计：**"每天当地时间 9 点"和"每隔 24 小时"是两条不同的调度规则**，本服务把它们作为两种一等公民类型分开定义、分开校验、分开计算。

无前端，纯 REST API。

## 1. 两种调度规则

| | `FIXED_INTERVAL`（固定间隔） | `CALENDAR`（带时区日历） |
|---|---|---|
| 语义 | `anchor + k × intervalSeconds`，纯 UTC 时间轴 | cron + IANA 时区，锚定本地钟面 |
| 参数 | `intervalSeconds`（必填）、`anchorAt`（可选，默认创建时刻） | `cron`（必填）、`timezone`（必填） |
| 跨夏令时 | UTC 触发时刻**不变**，本地钟面漂移（09:00→10:00） | 本地钟面**不变**，UTC 时刻漂移（08:00Z→07:00Z） |
| 时区 | 无（原始时区记为 `UTC`） | 定义级 `timezone`，如 `Europe/Berlin` |

创建时混用另一类参数（如 CALENDAR 带 `intervalSeconds`）会被 **400** 拒绝。

### cron 子集（文档化，其余一律报错）

- 6 段：`秒 分 时 日 月 周`；5 段视为"第 0 秒"。
- 支持 `*`、列表 `a,b,c`、区间 `a-b`、步进 `*/n`、`a-b/n`、`a/n`；月名 `JAN..DEC`、周名 `MON..SUN`（大小写不敏感）。
- 周字段编号遵循 Unix cron：`0` 和 `7` 都是周日，`1` 是周一。
- `?` 可用于日/周字段表示"不限制"；`L` 单独用于日字段表示"月末最后一天"。
- 日与周同时受限时按 POSIX cron 取 **OR**。
- 单日触发次数超过 10000 的表达式被拒绝（高频请用 `FIXED_INTERVAL`）。

## 2. 夏令时（DST）处理 —— 定义级显式策略

**缺失时刻**（春季拨快，如 Europe/Berlin 2026-03-29 的 02:30 不存在）`dstGapPolicy`：

| 策略 | 行为 |
|---|---|
| `SHIFT_FORWARD`（默认） | 顺延间隙长度到第一个有效时刻：02:30 → 03:30 CEST（01:30Z） |
| `SKIP` | 不触发；实例记为 `SKIPPED`，`skipReason=DST_GAP`，`scheduledAtUtc=null`（该时刻不存在） |

**重复时刻**（秋季拨回，如 2026-10-25 的 02:30 出现两次）`dstOverlapPolicy`：

| 策略 | 行为 |
|---|---|
| `FIRST`（默认） | 只在第一次（UTC 较早，CEST +02:00）触发 |
| `SECOND` | 只在第二次（CET +01:00）触发 |
| `BOTH` | 两次都触发，产生两条实例（`occurrenceKey` 按偏移区分） |

每个实例都返回：`originalZone`（原始时区）、`localTime`（该区钟面时刻）、`offset`（实际使用的偏移）、`scheduledAtUtc`（UTC 触发时刻）、`skipReason`（跳过原因）。

## 3. 数据模型（PostgreSQL，Flyway 迁移）

- `schedule_definition` — 调度定义（类型、cron/时区 或 间隔/锚点、DST 策略、maxRetries）。
- `trigger_instance` — 触发实例。`(definition_id, occurrence_key)` 唯一：物化幂等，N 个规划进程并发也不会产生重复实例。`scheduled_at_utc`（可空）、`sort_at`、`original_zone`、`local_time`、`utc_offset`、`status`、`skip_reason`、`retry_count`、`next_retry_at`。
- `execution_lease` — 执行租约。**`instance_id` 为主键：一个实例至多存在一条租约记录**。
- `pause_window` — 暂停窗口 `[from_ts, to_ts)`，`to_ts` 为 NULL 表示无限期。
- `fencing_counter` — fencing token 单调递增源。

## 4. 多调度器争抢：单租约保证

租约获取（`LeaseService.acquire`）在**同一事务**内：

1. `SELECT ... FOR UPDATE` 锁定实例行 —— 所有竞争者在此串行化；
2. 校验状态为 `PLANNED`（重试中的实例还需 `next_retry_at` 已过）；
3. 校验不存在未过期的 ACTIVE 租约；
4. 取单调递增 fencing token，写入/更新租约行；
5. 状态守卫翻转 `PLANNED → LEASED`。

失败者得到 **409**。因此无论多少进程争抢，**同一实例同一时刻只有一个有效租约**。

- **fencing token**：每次获取递增；`complete`/`heartbeat`/`fail` 携带 token，过期或别人的 token 一律 409。执行侧可用 token 做下游去重。
- **租约回收**：节点崩溃导致租约过期时，dispatcher 每 tick 先运行 reaper，把 `LEASED` 且租约已失效的实例**以原实例 id** 退回 `PLANNED`。
- 执行语义：at-least-once + 单并发租约 + fencing token。

## 5. 失败重试：实例身份不变

`fail(retryable=true)`：若 `retry_count + 1 <= maxRetries`，**同一行**回到 `PLANNED`，`retry_count + 1`，`next_retry_at = now + backoff`；否则进入终态 `FAILED`。重试**从不**新建实例 —— 调度的计划实例数（`trigger_instance` 行数）在整个重试生命周期中保持不变（见 `RetryIdentityIT`）。

## 6. 暂停 / 恢复

- `POST /schedules/{id}/pause`（可带 `{"until": "..."}`，缺省无限期）开启暂停窗口；已物化且落入窗口的 `PLANNED` 实例被翻转为 `SKIPPED/PAUSED`。
- `POST /schedules/{id}/resume` 关闭窗口。**窗口内的发生时刻不回补**（保持 `SKIPPED/PAUSED`）；恢复后未来的发生时刻正常物化。
- 重复 pause / 未暂停时 resume → 409。

## 7. 可注入时钟

全服务唯一时间源是 `Clock` bean（`config/AppConfig.java`）。测试用 `MutableClock` 替换（`@Primary`），因此闰日、夏令时切换、暂停恢复都是确定性验证。租约过期等判断全部使用该时钟，不依赖数据库时钟。

## 8. Quartz 的角色

Quartz 是**进程内 tick 源**：`PlannerJob`（默认 30s）把定义物化为未来 7 天的实例；`DispatcherJob`（默认 2s）租取到期实例并执行。跨进程正确性**不依赖** Quartz，全部由数据库约束保证（唯一 occurrence key、单租约行、状态守卫）。如需让 tick 本身也集群化，可把 `spring.quartz.job-store-type` 切到 `jdbc` —— 上述保证不变。

## 9. API 一览（`/api/v1`）

| 方法/路径 | 说明 |
|---|---|
| `POST /schedules` | 创建定义（201）；两类参数混用 → 400 |
| `GET /schedules` / `GET /schedules/{id}` | 列表 / 详情（含 `paused`） |
| `DELETE /schedules/{id}` | 删除（级联实例与租约） |
| `POST /schedules/{id}/pause` | 暂停，body 可选 `{"until": "ISO instant"}` |
| `POST /schedules/{id}/resume` | 恢复 |
| `GET /schedules/{id}/preview?from&to` | **计算**未来实例集合（不落库）：原始时区、UTC 时刻、跳过原因 |
| `GET /schedules/{id}/instances?from&to` | **已物化**实例集合（含租约信息） |
| `POST /instances/{id}/lease` | 争抢租约 `{"ownerNode","ttlSeconds"}`；被抢 → 409 |
| `POST /instances/{id}/heartbeat` | 续租 `{"fencingToken","ttlSeconds"}` |
| `POST /instances/{id}/complete` | 完成 `{"fencingToken"}` |
| `POST /instances/{id}/fail` | 失败 `{"fencingToken","retryable","backoffSeconds"}` |
| `GET /instances/{id}` | 实例详情（含租约） |

### 示例

```bash
# 每天柏林时间 09:00（日历规则）
curl -X POST localhost:8080/api/v1/schedules -H 'Content-Type: application/json' -d \
  '{"name":"daily-9am","type":"CALENDAR","cron":"0 0 9 * * *","timezone":"Europe/Berlin",
    "dstGapPolicy":"SHIFT_FORWARD","dstOverlapPolicy":"FIRST"}'

# 每隔 24 小时（固定间隔规则）—— 与上面不是同一条规则
curl -X POST localhost:8080/api/v1/schedules -H 'Content-Type: application/json' -d \
  '{"name":"every-24h","type":"FIXED_INTERVAL","intervalSeconds":86400,"anchorAt":"2026-03-28T08:00:00Z"}'

# 查看跨夏令时切换的一整段未来实例
curl "localhost:8080/api/v1/schedules/<id>/preview?from=2026-03-27T00:00:00Z&to=2026-03-31T00:00:00Z"
```

## 10. 运行

### 单节点（默认，内存 H2，便于演示）

```bash
mvn spring-boot:run
```

### PostgreSQL

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/scheduler
export DATABASE_USER=scheduler DATABASE_PASSWORD=CHANGE_ME
java -jar target/periodic-scheduler-1.0.0.jar --spring.profiles.active=postgres
```

### 多进程（多调度器争抢同一实例）

```bash
# 方式一：指向同一个 PostgreSQL
DATABASE_URL=jdbc:postgresql://localhost:5432/scheduler ./scripts/start-two-nodes.sh

# 方式二：无 PostgreSQL 的演示模式（脚本启动 H2 TCP 服务器，两个进程共享同一库）
./scripts/start-two-nodes.sh
# 另开一个终端观察两个节点争抢执行：
./scripts/smoke-test.sh
```

每个进程用 `APP_NODE_ID` 区分身份（租约的 `ownerNode`）。两个节点都会跑 planner/dispatcher tick；单租约保证由数据库强制执行。

### Docker Compose（PostgreSQL + 两个调度节点）

```bash
POSTGRES_PASSWORD=CHANGE_ME docker compose up --build
# 节点1: localhost:8081  节点2: localhost:8082
```

## 11. 验证（测试即规格）

```bash
mvn test
```

44 个测试，重点不是"算一次下次运行时间"，而是**整段未来实例集合**的预期 vs 实际比对：

- `FutureInstanceSetVerificationIT` — 通过 REST API 拉取整段窗口的实例集合，与独立计算的预期集合做**多重集比对**，打印完整差异报告（`EXPECTED-ONLY` / `ACTUAL-ONLY`）：
  - 闰日：`0 0 9 29 2 *` 在 2027..2032 只在 2028、2032 触发；`L` 月末规则覆盖 2 月 28/29；
  - DST 缺口：`SHIFT_FORWARD`（02:30→03:30）与 `SKIP`（`DST_GAP` 原因）；
  - DST 重叠：`FIRST`/`SECOND`/`BOTH`；
  - 固定间隔 vs 日历：同一锚点跨夏令时产生**不同**的 UTC 触发集合；
  - 暂停/恢复：窗口内 `PAUSED`、窗口外正常、恢复不回补、物化集合与预览集合一致。
- `OccurrenceEngineTest` — 引擎层 4 年（1461 天、8 次 DST 切换）全集合与独立暴力计算逐点相等。
- `LeaseConcurrencyIT` — 16 线程争抢同一实例仅 1 个获租约；过期租约 fencing token 失效；两个 dispatcher 竞争同一实例只执行一次。
- `RetryIdentityIT` — 重试复用实例 id、计划实例数不变、退避生效、超限进入终态。
- `PauseResumeIT`、`ApiValidationIT`、`EndToEndSmokeIT`（Quartz tick 全链路冒烟）。

## 12. 实现业务逻辑

实现 `exec/TaskHandler` 接口并注册为 Spring bean（替换默认的 `LoggingTaskHandler`）：

```java
@Component
public class MyTaskHandler implements TaskHandler {
    public void handle(UUID instanceId, UUID definitionId) { /* 业务逻辑 */ }
}
```

抛异常即视为失败，按 `maxRetries` / 退避策略重试（实例身份不变）。
