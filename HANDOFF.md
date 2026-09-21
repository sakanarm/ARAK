# HANDOFF — ARAK (Data Access Control Platform)

> อัปเดต: 2026-09-20 · commit ล่าสุดที่ push สำเร็จ `e3aaa52` · **local นำหน้าอยู่หลาย commit — `git push` ยังค้าง ดู What Didn't Work** · repo https://github.com/sakanarm/ARAK (**public**)
>
> อ่านคู่กับ **[docs/DESIGN.md](docs/DESIGN.md)** — ไฟล์นั้นคือ requirement + feature catalogue + สถานะครบทุกข้อ
> ไฟล์นี้บอกเฉพาะ "ทำถึงไหน จะไปต่อยังไง อะไรที่ลองแล้วไม่เวิร์ค"

---

## Goal

สร้างแพลตฟอร์ม Data Access Control แบบ Immuta/Denodo: ดึง metadata + governance object จาก **OpenMetadata 2.0.1** (integrate เฉยๆ **ไม่สร้าง catalog ใหม่**) → เขียน policy (subscription + data policy, global + local, RBAC/ABAC/rule/time เป็น predicate เดียว) → **บังคับใช้จริงที่ database** ครบ 3 โหมดใน Phase 1:

- **5.1.1** native source config (RLS / DDM / GRANT บน object เดิม)
- **5.1.2** secure view `*_secure` + REVOKE base table
- **5.2a** Query API ที่ rewrite SQL ตอน runtime

Phase 1 รองรับ SQL Server + PostgreSQL · identity หลักคือ Entra ID (local user ไว้เทสต์) · access-request workflow เลื่อน Phase 2
แผนเต็มอยู่ที่ `C:\Users\Sakan P\.claude\plans\requirement-access-optimized-dahl.md` (approved แล้ว)

---

## Current Progress

| Milestone | สถานะ |
|---|---|
| **M0 Foundation** | ✅ เสร็จ — Maven multi-module, Dropwizard 5, Vite+React+Tailwind shell, vendor `ui-core-components`, JSON Schema → Java/TS codegen, OM client จาก swagger ที่ pin ไว้, Flyway V1–V9, docker-compose, CI 4 jobs |
| **M1 OM Connector** | 🚧 ~95% — full crawl + governance + effective facet + FR-1.5 webhook/poller/reconcile + catalog read API + Catalog UI + governance read API + Governance UI · **sync กับ OM จริงสำเร็จแล้ว** · เหลือ FR-1.6 (reconcile กับ JDBC จริง), FR-1.7 (local tag + push-back — **ผู้ใช้สั่ง read-only ตอนนี้**) |
| **M2 Identity** | 🚧 ~35% — local sign-in ใช้ได้ · schema `principal`/`principal_attribute`/`group_member`/`app_role_assignment` มีตั้งแต่ V2 · read API + หน้า People & attributes + **หน้า Application roles (`/settings/roles`) อ่านอย่างเดียว** เสร็จ · **ยังไม่มี write API สำหรับ principal/attribute — ต้อง seed ด้วย SQL** · ยังไม่มี Entra OIDC / Graph sync |
| **M3 Policy Engine** | 🚧 ~93% — engine **162 tests** (data policy 26 + subscription 45 เพิ่มรอบนี้ · เจอบั๊กจริง 2 ตัว ดูข้อ P) · persistence (`PolicyStore`) + `policy_binding` materializer + REST · `PolicyBindingMaterializerIT` 10 tests บน Postgres จริง · เหลือ decision cache (FR-5.5), ANTLR grammar ของ `expr` (FR-3.2) |
| **M4 Policy Authoring UI** | 🚧 ~70% — Policy list + Policy builder (selector / subject / RLS / masking) + readback + capability matrix · เหลือ "policy ที่มีผลกับ asset นี้" ในหน้า asset (FR-3.1.5), View-as-user (FR-5.2), impact analysis (FR-5.3) |
| **M5 Secure View (5.1.2)** | ⬜ — `DecisionSql` + dialect ทั้งสองตัวพร้อมแล้ว (ใช้ร่วมกับ 5.2) เหลือ ViewCompiler + `row_entitlement` maintainer + DDL apply/rollback |
| **M6 Source Config (5.1.1)** | ⬜ |
| **M7 Query API (5.2a)** | 🚧 ~80% — **`POST /v1/query` + Query console ใช้งานได้จริงรอบนี้** · rewrite → RLS + mask + hidden column → execute → audit ครบ · พิสูจน์กับ Postgres จริงแล้วทั้ง allow / RLS / mask / refuse · เหลือ direct-access detector (FR-6.3.1) และ result cache |
| **M7b Cross-mode consistency** | ⬜ — ต้องมี M5/M6 ก่อน |
| **M8 Audit + Ops** | 🚧 ~20% — `audit_query` / `audit_decision` / `audit_policy_change` เขียนจริงแล้วและอ่านได้ · **ยังไม่มี audit ของการ configure** (เปลี่ยน data source / OM settings ไม่ถูกบันทึกที่ไหนเลย) · ยังไม่มี compliance report / drift detector / auto-revoke / SIEM export |

**ที่รันอยู่ตอนนี้**
| | |
|---|---|
| Backend (Dropwizard) | `:8080` (API อยู่ใต้ `/api`), admin `:8081/ping` |
| Frontend (Vite) | `http://127.0.0.1:5274/` |
| App DB (docker `dac-appdb`, postgres:16-alpine) | `:5432` db/user `dac` |
| OpenMetadata ของทีม | `2.0.1` — sync ผ่าน **ingestion-bot JWT** (ดู What Didn't Work) |

เทสต์ทั้งหมดเขียว — **dac-engine รันใหม่ 2026-09-21** (162 เขียว), module อื่นและ integration รันครบเมื่อ 2026-09-20, frontend `npx jest` + `npx tsc --noEmit` รันใหม่ 2026-09-21

| ชุด | จำนวน | คำสั่ง |
|---|---|---|
| Backend unit | dac-common 6 · dac-engine **162** · dac-compiler-sql 7 · dac-connector-openmetadata 88 · dac-proxy 16 · dac-service 50 = **329** | `./mvnw -am -pl backend/dac-service test` |
| Backend integration (Testcontainers `postgres:16-alpine`) | **50 tests** — `AssetStoreIT` 6 · `CatalogQueryIT` 15 · `GovernanceStoreIT` 9 · `PolicyStoreIT` 10 · `PolicyBindingMaterializerIT` 10 | `./mvnw -am -pl backend/dac-service verify -Pintegration` |
| Frontend | **10 suites / 35 tests** | `yarn test` ใน `frontend/app` |

`yarn type-check` · `yarn lint` · `yarn build` ผ่านหมด → **BUILD SUCCESS** ทั้งสองฝั่ง

---

## รอบล่าสุดทำอะไรไป — Query console (5.2a ใช้งานได้จริง) + บั๊กร้ายแรงสามตัว

รอบนี้คือรอบที่ **โหมด 5.2 เดินจากต้นจนจบได้จริงเป็นครั้งแรก** — พิมพ์ SQL ในเบราว์เซอร์ -> rewrite ตาม policy -> ยิงลง Postgres จริง -> ได้แถวที่ถูก filter และ column ที่ถูก mask กลับมา พร้อม audit ครบ

### A. หน้า Query console (M7 / FR-6.3, 5.2a)

ผู้ใช้ถามว่า *"เวลา Query ข้อมูลใน Database นี่ต้องกดตรงไหน"* — คำตอบคือยังไม่มี จึงสร้างขึ้นรอบนี้ แบบเดียวกับ BigQuery / Denodo

| ไฟล์ | หน้าที่ |
|---|---|
| `frontend/app/src/api/query.ts` (ใหม่) | `runQuery()` + type `QueryResult` · `MAX_ROWS = 5000` · `DEFAULT_ROWS = 200` |
| `frontend/app/src/pages/query/SqlEditor.tsx` (ใหม่) | editor สามชั้น: gutter + `<pre>` ที่ไฮไลต์ + `<textarea>` ตัวอักษรใสทับอยู่บนสุด · tokenizer เขียนเอง · Ctrl/Cmd+Enter = run · Tab = indent · **ไม่ใช้ Monaco** (ไม่กี่ร้อยบรรทัด เทียบกับ bundle หลาย MB และไม่มีอะไรบนจอนี้ต้องใช้ language server) |
| `frontend/app/src/pages/query/SchemaExplorer.tsx` (ใหม่) | tree ฝั่งซ้าย สร้างจาก **asset cache ไม่ใช่ `information_schema`** — table ที่ crawl ยังไม่เห็น = table ที่ engine ยังไม่มี decision ให้ กดไปก็ได้แค่คำปฏิเสธ · column lazy-load · column ที่มี tag ติดป้าย `tagged` |
| `frontend/app/src/pages/query/QueryPage.tsx` (ใหม่) | เลือก source · **Run as** (ดูแบบคนอื่น FR-5.2) · purpose · row limit · ตาราง result · **แท็บ "Rewritten SQL" โชว์ SQL จริงที่ถูกส่งไป** (FR-5.4) · แถบ unenforceable |
| `navigation.ts` · `App.tsx` | เพิ่มเมนู Query + route |

### B. QueryRewriter — บั๊กสองตัวที่ทำให้ gate สองชั้นปฏิเสธทุก query

1. **gate ชั้นสองดู output แทน input** — หลัง rewrite แล้ว derived table **ต้อง** มีชื่อ table จริงอยู่ข้างในเสมอ gate ที่ไล่หาชื่อ table ใน SQL ผลลัพธ์จึงปฏิเสธทุกอย่าง รวมถึงของที่ rewrite สำเร็จ -> เปลี่ยนไปเทียบ "table ที่ walk เจอ" กับ "table ที่ parser เห็นทั้งหมด" แทน
2. **เหตุผลที่ตอบกลับตอนถูกปฏิเสธเป็นคนละเรื่อง** — ขึ้นว่า `access is denied: subject rule satisfied` เพราะหยิบ `reasons[0]` มาโดยไม่ดู `matched`/`effect` -> เขียนใหม่ให้เลือก **DENY ที่ matched ก่อน แล้วค่อย ALLOW ที่ไม่ matched**

เขียน `QueryRewriterTest` **16 tests** คุมทั้ง happy path, gate ทั้งสองชั้น, CTE, UNION, alias, hidden column, และ subquery ใน `WHERE EXISTS` ที่ต้อง **fail-closed**

### C. รูปร่างของ SQL ที่ proxy ใส่เข้าไป (ตัวอย่างจริงที่ capture มา)

ผู้ใช้ถามว่า *"ตอน proxy ใส่ Syntax อะไรเข้าไป"* — pattern คือ **ห่อ table ที่ถูกคุมไว้เป็น derived table**: mask อยู่ใน projection · row filter อยู่ใน `WHERE` ของ derived table · column ที่ซ่อนคือไม่ถูก list ออกมาเลย · `WHERE`/alias/`ORDER BY` ของผู้ใช้อยู่ **ข้างนอก**

ผู้ใช้เขียน:

```sql
SELECT full_name, email, salary FROM sales.customer
```

สิ่งที่ถูกส่งไปที่ Postgres จริง (`analyst_a`):

```sql
SELECT full_name, email, salary
FROM (SELECT "t"."id", "t"."full_name",
             regexp_replace(CAST("t"."email" AS text), '^[^@]+', '***', 'g') AS "email",
             CASE WHEN "t"."citizen_id" IS NULL THEN NULL
                  ELSE repeat('*', GREATEST(length(CAST("t"."citizen_id" AS text)) - 4, 0))
                       || right(CAST("t"."citizen_id" AS text), 4) END AS "citizen_id",
             "t"."phone", "t"."salary", "t"."branch_code", "t"."country", "t"."created_at"
      FROM "sales"."customer" "t"
      WHERE ("t"."branch_code" IN ('BKK-01'))) "customer"
```

### D. พิสูจน์โหมด 5.2 กับข้อมูลจริง

Source `demo-pg` (`dac-srcpg`, db `salesdb`) · `sales.customer` 4 แถว · policy 3 ตัว (`finance-subscription` ORG · `pii-masking-below-l2` ORG · `sales-branch-rls` SCHEMA)

| Run as | attribute | ผลลัพธ์ | โดนอะไร |
|---|---|---|---|
| `analyst_a` | clearance=L1 · country=TH · branch=BKK-01 | 2 แถว · `email` = `***@example.co.th` · `citizen_id` = `*********3456` | **RLS + Mask** |
| `steward_c` | clearance=L2 · country=TH · branch=BKK-01 **และ** CNX-01 | **3 แถว** · email/citizen_id เห็นเต็ม | RLS อย่างเดียว |
| `analyst_b` | country=SG | ถูกปฏิเสธ พร้อมชื่อ policy + เงื่อนไขที่ไม่ผ่าน | Subscription ตัด |
| admin (ไม่เลือก Run as) | – | ถูกปฏิเสธ — ไม่มี principal จึงไม่มี decision | fail-closed |
| ใครก็ได้ + `WHERE EXISTS (SELECT 1 FROM sales.customer)` | – | ถูกปฏิเสธ `does not rewrite` | gate ชั้นสอง |

### E. BUG — audit row หายหมดทุกแถว เพราะ `inet` ไม่รับสิ่งที่ Jetty ส่งมา

`ERROR: invalid input syntax for type inet: "[0:0:0:0:0:0:0:1]"` — Jetty คืน IPv6 loopback **พร้อมวงเล็บเหลี่ยม** แต่ `inet` ของ Postgres ไม่รับ -> insert throw -> `QueryService` catch แล้ว log ต่อ (ตั้งใจ ไม่ให้ audit ที่ล้มไปฆ่า query ที่ถูกอนุญาต) **ผลคือทุก query รันโดยไม่ถูกบันทึกเลย** ซึ่งคือการเสียหลักฐาน compliance ทั้งชุด (FR-8.2, FR-8.3)

แก้ด้วย `QueryService.inet()`: ตัดวงเล็บ · ตัด zone index (`fe80::1%eth0`) · **กรองด้วย regex ให้เหลือเฉพาะ IP literal ก่อน** แล้วค่อยเรียก `InetAddress.getByName` (ถ้าไม่กรองก่อน มันจะไป resolve DNS ซึ่งเป็นที่สุดท้ายที่ตอนเขียน audit ควรแตะ network) · **เสียที่อยู่ยอมได้ เสียทั้งแถวไม่ได้** · `QueryServiceInetTest` **6 tests**

หลังแก้: `grep -c "Could not write" backend-run.log` -> **0** · `client_ip` = `::1`

### F. BUG — `audit_decision.matched_policy_ids` ว่างทุกแถว

engine ใส่ `.withPolicyId(policy.getId())` ถูกแล้ว แต่ **`id` เป็น column ไม่ได้อยู่ใน JSON document** -> policy ที่ deserialize ออกมาจาก `document` มี `id == null` ทุกตัว -> audit บอกชื่อ policy ได้แต่ชี้กลับไปหาไม่ได้ (FR-5.4, FR-8.2)

แก้ที่ `PolicyStore.deserialise(id, document)` ให้ประทับ id ของ row กลับลงบน POJO และแก้จุดเดียวกันใน `PolicyBindingMaterializer` · **verify แล้ว** — แถวใหม่มี `matched_policy_ids` 2–3 ตัว

### G. BUG — multi-value attribute ไม่กลายเป็น `IN (...)` (FR-4.1 พัง)

`steward_c` มี `branch` สองค่า (BKK-01 **และ** CNX-01) แต่ SQL ที่ออกมาคือ `branch_code = 'BKK-01'` เฉยๆ -> เห็น 2 แถวแทนที่จะเป็น 3

ไล่แล้วไม่ใช่ `PrincipalLoader` (select ครบ) ไม่ใช่ `Principal.attributeValues` (เก็บครบ) ไม่ใช่ `PolicyEngine` (ส่ง `values` ครบ) — **อยู่ที่ `DecisionSql.compare`**: มัน switch ตาม `operator` ไม่ใช่ `kind` และ policy document เขียนว่า `{"kind":"IN_LIST","column":"branch_code","userAttribute":"branch"}` **ไม่มี `operator`** -> default เป็น `EQ` -> `EQ` อ่าน `values.get(0)` ตัวเดียว **ค่าที่เหลือหายเงียบ**

นี่คือบั๊กชนิดที่แย่ที่สุดในงานนี้: **SQL ที่ generate ออกมาหน้าตาถูกต้องทุกอย่าง ผิดแค่จำนวนแถว** และผิดในทางที่เข้มเกินไป เลยไม่มีใครร้อง

แก้ด้วย `DecisionSql.membership(kind, operator, count)`:

- `operator == null` + `kind == IN_LIST` -> `IN`
- `operator == null` อื่นๆ -> `EQ` (เหมือนเดิม)
- `EQ` แต่ได้ค่ามามากกว่าหนึ่ง -> **ขยายเป็น `IN`** (คนเขียนหมายถึง "ตรงกับค่าของผู้อ่าน" และผู้อ่านมีหลายค่า — กว้างขึ้นดีกว่าทิ้งทั้งหมดยกเว้นตัวแรก)
- `NE` ได้หลายค่า -> `NOT_IN`

`DecisionSqlRowFilterTest` **7 tests** (module `dac-compiler-sql` เพิ่งมี test เป็นครั้งแรก) · verify สด: `steward_c` ได้ `IN ('BKK-01', 'CNX-01')` -> **3 แถว**

### H. สามเรื่อง UI ที่ผู้ใช้ชี้

1. *"อันนี้ไม่ต้องมีสิ ให้เขาใส่ได้เองไหม"* — dropdown row limit 4 ตัวเลือก ไม่มีวันเป็นตัวเลขที่คนต้องการ -> เปลี่ยนเป็น `TextField` กรองเฉพาะตัวเลข + ข้อความ `(capped at 5000)` ข้างๆ เพราะ server clamp อยู่แล้ว field อิสระจึงพังอะไรไม่ได้
2. *"เวลา highlight แล้วมันหาย"* — selection ถูกวาดโดย `<textarea>` ที่อยู่ **บน** ชั้นที่มีตัวอักษรจริง สี selection ทึบจึงบังสิ่งที่กำลังเลือกอยู่ -> `tw:selection:bg-[rgba(41,112,255,0.28)]`
   > **บทเรียนทั่วไป: editor ที่ซ้อนชั้น ต้องใช้สี selection ที่มี alpha เสมอ**
3. *"อยากให้มี Expand, Collapse all"* — หน้า Governance เพิ่มปุ่ม Expand all / Collapse all ข้างช่อง filter · implement เป็น **คำสั่งครั้งเดียว (`{nonce, open}`) ไม่ใช่โหมด** — ถ้าเก็บเป็น boolean mode แล้ว "expand all" จะคอยง้างแถวที่ผู้อ่านเพิ่งปิดให้เปิดใหม่ตลอด

### I. คำถามที่ตอบไประหว่างรอบนี้ (เก็บไว้เพราะจะถูกถามอีก)

- **"เปลี่ยน database type แล้ว logic proxy ต้องเปลี่ยนไหม"** — ไม่ต้อง `QueryRewriter` + `PolicyEngine` ใช้ร่วมกันหมด ที่เปลี่ยนอยู่หลัง `SqlDialect` 4 method (`quote` / `literal` / `toText` / `mask`) · `QueryService.dialectFor(source)` เลือกจาก `source.engine()` · row limit ใช้ JDBC `setMaxRows` ไม่ได้เขียน `LIMIT`/`TOP` ลง SQL · **ความเสี่ยงจริงอยู่ที่ parser** — JSqlParser เข้าใจ ANSI เป็นหลัก T-SQL เฉพาะทาง (`OUTER APPLY`, query hint) อาจ parse ไม่ผ่านแล้วถูก reject ทิ้ง (ปลอดภัยแต่ผู้ใช้จะงง) ต้องทดสอบจริงตอนต่อ MSSQL · source ที่ไม่ใช่ SQL ต้องเขียน compiler คนละตัวที่กิน `PolicyDecision` เดียวกัน
- **"ต้องมี list ของ type ที่รองรับใช่ไหม"** — ใช่ มีแล้ว 3 ชั้น: (1) `DataSourceStore.Engine` + `Engine` ใน `enforcement.ts` = `POSTGRES | SQLSERVER` (2) capability matrix ต่อโหมด ใน `enforcement.ts` คำนวณสดตอนพิมพ์ policy (3) masking function ที่แต่ละ dialect สะกดได้ — **`REGEX_REPLACE` บน SQL Server โยน `UnsupportedMaskingException` ทิ้ง ไม่ degrade** · **ช่องว่าง:** ชั้น (3) ยังไม่ถูกดึงขึ้นมาใน matrix ของ UI และ matrix ยังไม่รู้จัก *เวอร์ชัน* (MSSQL 2019/2022/2025, PG มี extension `anon` ไหม)
- **"Access เป็น local policy ใช่ไหม"** — ไม่ใช่ เมนู Access คือ **Grant** (FR-7) ให้สิทธิ์ตรงๆ พร้อมวันหมดอายุ คนละเรื่องกับ policy ที่เขียนเป็นเงื่อนไข
- **"มีเก็บ log การรัน การ configure ไว้ไหม"** — query กับ decision กับ policy change มีครบ · **การ configure ไม่มีเลย** (เปลี่ยน data source / OM settings ไม่ถูกบันทึกที่ไหน) ดู Next Steps

### J. 🔴 Secret scan ก่อน commit — เจอของจริงสองรายการ

repo เป็น **public** ทุก commit อ่านได้ทั้งโลก ก่อน commit รอบนี้จึง scan สองชั้น:
ชั้นแรกเทียบกับ **ค่าจริงใน `.env`** (`IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`, `OM_WEBHOOK_SECRET`, `FERNET_KEY`, `OM_JWT_TOKEN`, `SRC_PG_ARAK_CREDENTIAL`, `DAC_DB_PASSWORD`) → ผ่าน
ชั้นสองเป็น **pattern ทั่วไป** (password ที่พบบ่อย, JWT, private key, IP ภายใน) → **เจอสองรายการ และทั้งคู่เป็นของจริง**

| ไฟล์ | ของที่หลุด | แก้เป็น |
|---|---|---|
| `dac-service/.../policy/QueryServiceInetTest.java:23` | **IP ภายในของ OpenMetadata instance ของทีม** (`10.6.x.x` — ไม่เขียนเต็มซ้ำที่นี่) ถูกใช้เป็น fixture ของ IPv4 | `192.0.2.10` (TEST-NET-1, RFC 5737) + คอมเมนต์บอกเหตุผล |
| `dac-service/.../source/DataSourceStoreIT.java:95` | **รหัสผ่าน OpenMetadata จริงที่ผู้ใช้เคยพิมพ์ในแชท** ถูกใช้เป็น fixture ของ "รหัสผ่านที่ต้องถูกปฏิเสธ" | `not-a-real-secret` / `sa/not-a-real-secret` |

**บทเรียน:** fixture ทั้งสองตัว *ทำงานถูกต้อง* ทุกประการ — test เขียวมาตลอด สิ่งที่ผิดคือ**ค่าที่หยิบมาใช้** คนเขียน test มักหยิบค่าที่อยู่ตรงหน้า (IP ที่เพิ่ง curl, รหัสผ่านที่เพิ่งอ่าน) โดยไม่คิดว่ามันจะถูก push ขึ้น public repo
ข้อที่เจ็บกว่าคือ `DataSourceStoreIT` เป็น test ที่ตั้งใจพิสูจน์ว่า **`credential_ref` ต้องไม่รับรหัสผ่านดิบ** — แล้วดันเอารหัสผ่านจริงมาเป็นตัวอย่าง

⚠️ **รอบถัดมาเกิดซ้ำในที่เดิม** — หัวข้อ J ฉบับแรก **เขียน IP กับรหัสผ่านตัวจริงลงในตาราง** เพื่ออธิบายว่าลบอะไรออก — คือเอาค่าที่เพิ่งลบกลับมา commit ใหม่ในเอกสารที่ห้ามทำสิ่งนั้น secret scan รอบถัดไปจับได้และ redact แล้ว
> **ข้อสรุป:** เวลาบันทึกว่า “ลบค่า X ออก” **ห้ามเขียน X ลงไป** — ให้บรรยายว่ามันคืออะไร อย่าเขียนว่ามันคืออะไร

> **กติกาถาวร:** ค่าใน fixture ต้องเป็นค่าที่ **ใช้กับอะไรไม่ได้เลย** — IP ใช้ `192.0.2.x` / `198.51.100.x` / `203.0.113.x` (RFC 5737), host ใช้ `example.com`, secret ใช้สตริงที่อ่านแล้วรู้ทันทีว่าปลอม ห้ามหยิบค่าจาก environment จริงมาวาง

### K. คืนค่า time window ของ `finance-subscription` (ปิดช่องว่างข้อ 8)

ระหว่างรอบพิสูจน์ 5.2 มีการแก้ window เป็น `["MON-FRI","SAT","SUN"]` เพื่อไม่ให้ test ที่รันวันเสาร์ถูกปฏิเสธด้วยเหตุผลผิดเรื่อง — แต่ปล่อยไว้แปลว่า demo **ไม่ได้ demo time predicate อีกต่อไป**
คืนกลับเป็น `["MON-FRI"]` แล้ว (policy `b594579f-a825-42bc-9dac-64de9b279d03` → **version 4**) ผ่าน `PUT /api/v1/policies/{id}?version=&reason=`

> ⚠️ **API shape ที่หลงทางง่าย** — `PUT /policies/{id}` รับ **`Policy` document ตรงๆ เป็น body** ส่วน `version` กับ `reason` เป็น **query parameter** ไม่ใช่ `{document, expectedVersion, reason}` ใน body (ลองแบบหลังแล้วได้ 400)

ยืนยันผลสด (วันที่รัน = เสาร์ 2026-09-20):

```
POST /api/v1/query {"sourceId":"…","sql":"select * from sales.customer","asPrincipal":"analyst_a"}
→ 403 "Access to demo-pg.salesdb.sales.customer is denied.
        finance-subscription did not apply: outside the policy's permitted time window"
```

เท่ากับพิสูจน์ **E2E ข้อ 15** (นอกเวลาทำการต้อง deny) และ **FR-5.4 explainability** (บอกชื่อ policy + เหตุผลที่ไม่ผ่าน) ไปพร้อมกัน
**ผลข้างเคียงที่ต้องรู้:** ถ้าเปิดหน้า Query ในวันเสาร์-อาทิตย์ หรือนอก 08:00–18:00 Asia/Bangkok จะถูกปฏิเสธ — **นั่นคือพฤติกรรมที่ถูกต้อง ไม่ใช่บั๊ก**

> ⚠️ **`QueryResource.Ask` ใช้ชื่อ field `sourceId` ไม่ใช่ `dataSourceId`** — ส่งผิดชื่อได้ 400 `"Say which source to run against"` ซึ่งอ่านแล้วเหมือน id หายไป ทั้งที่ส่งไปแล้ว

### L. Catalog — เปลี่ยนแถวชิป facet เป็น filter rail แบบ OpenMetadata Explore

เดิม facet filter เป็น**แถวชิปที่ wrap ไปเรื่อยๆ** (Tags / Domains / Classifications / Tier) — ผู้ใช้บอกว่า "ไม่สวย ทำให้เหมือน openmetadata สิ"
ปัญหาจริงไม่ใช่แค่ความสวย แต่คือ **ชิปเป็นรูปทรงที่ผิดสำหรับข้อมูลที่เป็นลำดับชั้น**: sub-domain สามตัวใต้ `Premium Service Delivery` ขึ้นต้นด้วยอักษรชุดเดียวกัน 24 ตัว พอถูก CSS ตัดให้พอดีชิป มันเหลือเป็นข้อความ**เหมือนกันเป๊ะทั้งสามอัน** — filter ที่แยกตัวเลือกไม่ออกแย่กว่าไม่มี filter

แทนที่ `FacetPicker` ด้วย `FacetRail` + `FacetGroup` ใน [CatalogPage.tsx](frontend/app/src/pages/catalog/CatalogPage.tsx):

| ของเดิม | ของใหม่ |
|---|---|
| ชิป wrap แนวนอนเหนือผลลัพธ์ | rail ซ้าย `w-64` sticky · หนึ่งค่า = หนึ่งบรรทัด |
| `shortFqn()` (ตัดหัว เหลือ `… / tail`) | `leaf()` + **indent ตามความลึกของ FQN** (12px/ชั้น, cap 3 ชั้น) + `title` เป็น FQN เต็ม |
| เรียงตามจำนวน asset | **เรียงตาม FQN** เพื่อให้ parent อยู่บรรทัดเหนือลูกเสมอ — ไม่งั้น indent จะโกหกว่าใครอยู่ใต้ใคร |
| ไม่มี | กลุ่มพับได้ · badge นับ filter ที่เลือก · search ในกลุ่มเมื่อมี ≥10 ค่า · `Show all N` เมื่อเกิน 6 ค่า |
| ไม่มี | ค่าที่**ติ๊กไว้แล้วถูกวาดเสมอ** แม้หลุดจาก preview หรือ search — filter ที่มองไม่เห็นคือ filter ที่ปลดไม่ได้ |

ตรวจแล้ว: `tsc --noEmit` ผ่าน · `npx jest` **7 suites / 25 tests เขียวหมด** · screenshot ผ่าน Playwright (Edge) ยืนยันหน้าตา

**กับดักสี่อันที่เจอระหว่างทาง — จดไว้ให้ไม่ต้องเสียเวลาซ้ำ:**

1. **Tailwind 4 prefix ต้องมาก่อน breakpoint** — `tw:lg:block` ✅ / `lg:tw:block` ❌ (เขียนผิดแล้ว class เงียบหาย Playwright ฟ้อง "locator resolved to hidden")
2. **JSX comment `{/* */}` วางระหว่าง `return (` กับ element ไม่ได้** — กลายเป็นสอง expression → Babel ฟ้อง `Unexpected token, expected ","` ต้องใช้ `//` เหนือ `return (` แทน
3. **test runner ของโปรเจกต์นี้คือ Jest ไม่ใช่ vitest** — `npx vitest run` จะไปกวาด `e2e/smoke.spec.ts` แล้วพังทั้ง 8 suites ใช้ `npx jest`
4. **`Checkbox` ที่ vendor มาจาก OM render `<label>` ของตัวเองอยู่แล้ว** — เอาไปซ้อนใน `<label>` อีกชั้น input จะ**ไม่มี accessible name เลย** ต้องใช้ `<div>` + `aria-label` ชัดๆ (ใส่เป็น FQN เต็ม เพราะ "Sensitive" เฉยๆ ไม่บอกว่าเป็นของ classification ไหน) แล้วให้ข้อความที่มองเห็นเป็น `<button tabIndex={-1}>`

> icon `Collapse01` **ไม่มี** ใน `@untitledui/icons` — ที่มีคือ `ChevronDown`, `ChevronRight`, `FilterLines`, `Minimize01`, `Maximize01`, `Expand01`

### M. 🔴 คำแนะนำ "ลองใน UI ยังไงให้เห็น RLS + Mask" ที่เคยให้ไว้ — **ผิด** แก้แล้ว

ผู้ใช้ลองทำตามแล้วได้ `Refused` ทันที สาเหตุมีสองข้อ **และเป็นความผิดของคำแนะนำทั้งคู่**

**ข้อ 1 — ตารางเดิมบอกว่า "ไม่เลือก Run as → query ในฐานะ `admin` → ไม่โดนอะไรเลย" ซึ่งกลับหัวกลับหางกับความจริง**
`admin` **ไม่ได้อยู่ใน subject ของ `finance-subscription`** เลย → โดน **default-deny** ตาม FR-3.3 ไม่ใช่ "เห็นครบทุกแถว"
พิสูจน์สด:

```
run as (nobody -> admin)
→ "finance-subscription did not apply:
   principal is none of the roles, teams, groups or users the policy names"
```

> นี่เป็นพฤติกรรมที่**ถูกต้อง** — platform admin คุม *ระบบ* ไม่ได้แปลว่าเห็น *ข้อมูล* (separation of duty ตาม FR-2.6) แต่คำแนะนำเดิมเขียนตรงข้าม

**ข้อ 2 — วันที่ลองคือเสาร์ ส่วน window เพิ่งถูกคืนเป็น `MON-FRI` ในรอบเดียวกัน (ดูหัวข้อ K)** → ต่อให้เลือก `analyst_a` ถูกก็ยังโดนปฏิเสธอยู่ดี ด้วยเหตุผลคนละอัน

ผลจริงทั้งสี่เคส วันเสาร์ 2026-09-20:

| Run as | ผลลัพธ์ |
|---|---|
| `admin` (ค่า default) | ❌ `principal is none of the roles, teams, groups or users the policy names` |
| `analyst_a` | ❌ `outside the policy's permitted time window` |
| `steward_c` | ❌ `outside the policy's permitted time window` |
| `analyst_b` | ❌ `expression is false for this principal: user.country == asset.prop('dataResidency')` ← **อันนี้ถูกต้องตามดีไซน์** |

**ตารางที่ถูกต้อง** (ใช้ได้เมื่อ window เปิดอยู่ — ดูสวิตช์ด้านล่าง):

| Run as | attribute | เห็นอะไร | โดนอะไร |
|---|---|---|---|
| `analyst_a` | clearance=L1 · country=TH · branch=BKK-01 | 2 แถว · `email` = `***@example.co.th` · `citizen_id` = `*****3456` | **โดนทั้ง RLS และ Mask ← อันนี้คืออันที่ต้องลอง** |
| `steward_c` | clearance=L2 · country=TH · branch=BKK-01 + CNX-01 | 2 แถว · email/citizen_id เห็นเต็ม | โดน RLS อย่างเดียว — L2 ผ่าน mask |
| `analyst_b` | country=SG | ไม่เห็นอะไรเลย | โดน Subscription policy ตัด |
| `admin` / ไม่เลือก | – | **ไม่เห็นอะไรเลย** | **โดน default-deny — ไม่ใช่ "เห็นหมด"** |

#### สวิตช์ time window — [scripts/demo-time-window.py](scripts/demo-time-window.py)

ปัญหาเชิงออกแบบที่โผล่มาจากเรื่องนี้: **ไม่มีค่า default ของ window ที่ถูกทั้งสองทาง**
ตั้งเป็นเวลาทำการ → demo ปฏิเสธทุกเย็นและทุกเสาร์อาทิตย์ ซึ่งเป็นเวลาที่คนมานั่งดูจริงๆ และสิ่งที่เห็นคือ refusal ที่ไม่ได้พูดถึง RLS หรือ mask เลย
ตั้งเป็นเปิดตลอด → ไม่เคยได้ demo time predicate

จึงทำเป็น**สวิตช์** แทนที่จะเถียงกันว่า default ควรเป็นอะไร:

```bash
python scripts/demo-time-window.py open     # ทุกวัน ทุกเวลา — ใช้ตอน demo RLS/mask
python scripts/demo-time-window.py office   # MON-FRI 08:00-18:00 — ใช้ตอน demo time predicate
```

> ⚠️ **สถานะตอนนี้ = `office`** (จากหัวข้อ K) — แปลว่า **หน้า Query จะปฏิเสธจนถึงวันจันทร์ 08:00 Asia/Bangkok**
> สคริปต์ถูกเขียนแล้วแต่ **ยังไม่ได้รัน** เพราะ auto-mode classifier บล็อกด้วยเหตุผล `Security Weaken` (การขยาย time window ของ access policy อ่านแล้วเหมือนการลดความปลอดภัย ซึ่งเป็นการบล็อกที่สมเหตุสมผล) — **ผู้ใช้ต้องรันเองหนึ่งครั้ง**

### N. ตรวจหน้า Query ด้วยตาเป็นครั้งแรก (ปิดช่องว่างข้อ 7) — เจอ bug ใน `Select` ที่ vendor มา

หน้า `/query` **ไม่เคยถูกเปิดดูจริงเลย** ตั้งแต่สร้างมา — screenshot ผ่าน Playwright (Edge) แล้วเจอของจริงสองอย่าง

**1. Select ของ source แสดงชื่อเหลือตัวเดียว** — `demo-pg  POSTGRES · localhost:5433` ถูก render ออกมาเป็น `c POSTGRES · localhost:5433`
ต้นเหตุอยู่ใน [ui-core-components/.../select.tsx](frontend/ui-core-components/src/components/base/select/select.tsx) — ใน trigger นั้น `label` มี `tw:truncate` (หดได้) แต่ `supportingText` **ไม่มีทั้ง `truncate` และ `min-w-0`** → ใน flex row ตัวที่หดไม่ได้จะยืนยันความกว้างเต็ม แล้วโยนการหดทั้งหมดไปให้ label
นั่นแปลว่า **ข้อความรอง ชนะ ข้อความหลัก** — ส่วนที่บอกว่าผู้ใช้เลือกอะไรอยู่คือส่วนที่หาย กระทบ**ทุก Select ในแอป** ไม่ใช่แค่หน้านี้
แก้โดยให้ `supportingText` เป็น `tw:min-w-0 tw:shrink-[9999] tw:truncate` → มันยอมหดก่อนเสมอ ชื่อ source จึงอยู่ครบ

**2. ตัวเลือก Run as ชื่อ `As myself` ทำให้เข้าใจผิด** — แก้เป็น `As myself (policies apply)` และให้ banner อธิบายทั้งสองกรณี (เดิมขึ้นเฉพาะตอนเลือกคน) — การเงียบตอนไม่ได้เลือกคือสิ่งที่ทำให้คนอ่าน refusal แล้วคิดว่าระบบพัง

### O. หน้า Query บอกได้แล้วว่า "โดน policy อะไรไปบ้าง" ไม่ใช่แค่ส่ง SQL ที่ rewrite แล้วมาให้อ่านเอง (FR-5.4)

**ที่มา:** ผู้ใช้ขอ "ให้ผมลอง RLS, masking ให้เห็นภาพใน UI"
ปัญหาคือก่อนหน้านี้ผลลัพธ์**ไม่ได้บอกอะไรเลย**ว่าเกิดอะไรขึ้น:

- แถวที่ถูก RLS ตัดออก → **หายไปเฉยๆ** ไม่มีอะไรบอกว่าหายเพราะอะไร
- column ที่ถูก mask → ขึ้นเป็น `***@example.co.th` ซึ่ง**อ่านแล้วเหมือนข้อมูลจริงที่หน้าตาแปลก** มากกว่าเหมือนถูก mask
- คนที่จะรู้ความจริงได้ต้องไปเปิดแท็บ `Statement that ran` แล้วอ่าน SQL ที่ generate มา ซึ่งเป็นการขอมากเกินไปจากคนที่แค่อยากดูว่า policy ทำงานไหม

> นี่คือช่องว่าง FR-5.4 ในหน้าจอนี้ — "decision ที่อธิบายไม่ได้ คือ decision ที่ไม่มีใครกล้า enforce"

#### สิ่งที่ทำ — ลาก `PolicyDecision` ออกมาจนถึงหน้าจอ

เดิม `QueryRewriter` เก็บ `Map<String, Governed>` (มี `PolicyDecision` เต็มๆ อยู่ในมือ) แล้ว **โยนทิ้ง** เหลือแต่ชื่อ asset

| ชั้น | เปลี่ยนอะไร |
|---|---|
| [QueryRewriter.java](backend/dac-proxy/src/main/java/com/mfec/dac/proxy/QueryRewriter.java) | `Rewritten` เพิ่ม field `List<Governed> governed` — เก็บ decision ไว้แทนที่จะทิ้ง (ยังคง `assets` ไว้เหมือนเดิม ไม่ทำให้ของเดิมพัง) |
| [QueryService.java](backend/dac-service/src/main/java/com/mfec/dac/policy/QueryService.java) | เพิ่ม record `Explanation(asset, maskedColumns, hiddenColumns, rowFilters, policies)` + เมธอด `explain()` ที่แปลง decision เป็นภาษาคน |
| [QueryResource.java](backend/dac-service/src/main/java/com/mfec/dac/resources/QueryResource.java) | ใส่ `explanations` ลงใน response body |
| [api/query.ts](frontend/app/src/api/query.ts) | type `Explanation` |
| [QueryPage.tsx](frontend/app/src/pages/query/QueryPage.tsx) | `AppliedPolicies` แถบเหนือตาราง + badge `masked` บนหัว column |

**สิ่งที่เห็นบนจอตอนนี้** (แถบเหนือตารางผลลัพธ์):

```
🛡 demo-pg.salesdb.sales.customer   [sales-branch-rls (SCHEMA)] [pii-masking-below-l2 (ORG)]
   ▽ Rows kept where branch_code is one of BKK-01
   ◎ citizen_id  hidden except the last 4 characters
   ◎ email       rewritten by pattern ^[^@]+
```

และบนหัว column ของ `email` / `citizen_id` มีป้าย `masked` (hover เห็นรายละเอียด)

#### การตัดสินใจเชิงออกแบบสามข้อ

1. **สร้างคำอธิบายจาก `PolicyDecision` ไม่ใช่จาก SQL** — SQL เป็นแค่ *หนึ่งใน* สาม rendering ของ decision เดียวกัน (FR-6.0c) ถ้าไปอ่านจาก SQL คำอธิบายจะจริงเฉพาะโหมด proxy แล้วต้องเขียนใหม่อีกสองรอบตอนทำ 5.1.1 / 5.1.2
2. **วางไว้เหนือตาราง ไม่ใช่ในแท็บ Job details** — คนที่เปิดหน้านี้กำลังเทียบ principal สองคนอยู่ สายตาอยู่ที่แถว ไม่ได้อยู่ที่แท็บอื่น
3. **ติดป้ายที่หัว column ด้วย ไม่ใช่แค่ในแถบ** — column ที่ mask แล้วค่ายัง**ดูสมเหตุสมผล**คือ column ที่มีโอกาสถูกอ่านว่าเป็นของจริงมากที่สุด

#### เคสที่จงใจเขียนไว้ — `IN_LIST` ที่ values ว่าง

```
branch_code must match one of the principal's values, and they have none
```

ตารางว่างเพราะ principal ไม่มี attribute ที่ filter นั้นใช้เทียบ = **คำตอบที่ถูก ไม่ใช่หน้าจอพัง** ก่อนหน้านี้สองอย่างนี้หน้าตาเหมือนกันเป๊ะ

#### Test — [QueryExplanationTest.java](backend/dac-service/src/test/java/com/mfec/dac/policy/QueryExplanationTest.java) (6 tests)

เขียน test ให้ทุกประโยคที่จะขึ้นจอ เพราะ**คำอธิบายที่เลิกตรงกับ enforcement ที่มันอธิบาย แย่กว่าไม่มีคำอธิบาย — เพราะคนเชื่อมัน**

#### 🐛 บั๊กที่เจอระหว่างทาง — `scripts/demo-time-window.py` เขียน key ผิด

```python
'tz': 'Asia/Bangkok'        # ❌ ผิด — schema ใช้ `timezone`
'timezone': 'Asia/Bangkok'  # ✅ แก้แล้ว
```

key ที่ไม่รู้จัก**ไม่ error** แต่ถูกทิ้งเงียบๆ → window จะเสีย timezone ไปแล้ว fallback ไปใช้นาฬิกาของ server ซึ่งเป็นบั๊กที่ FR-3.2 พูดถึงตรงๆ ("timezone เป็นส่วนหนึ่งของกฎ ไม่ใช่เอามาจากที่ engine บังเอิญรันอยู่") **สคริปต์ยังไม่เคยรันสำเร็จ จึงยังไม่เคยสร้างความเสียหาย**

#### 🔴 ยังค้าง — ผู้ใช้ต้องเปิด time window เอง

ผู้ใช้สั่ง "แก้ให้หน่อย" ผมพยายามรัน `demo-time-window.py open` **สองครั้ง ถูก classifier บล็อกทั้งสองครั้ง** ด้วยเหตุผล `[Security Weaken]` ไม่ได้พยายามเลี่ยง

**ทางที่สั้นที่สุดสำหรับผู้ใช้ — ไม่ต้องใช้สคริปต์เลย:**
`/policies/b594579f-a825-42bc-9dac-64de9b279d03` → Time windows → dropdown วัน `weekdays` → **`every day`** → Save
ไม่ต้องแตะช่วงเวลา เพราะ [TimeMatcher.java:95-100](backend/dac-engine/src/main/java/com/mfec/dac/engine/TimeMatcher.java#L95-L100) ตีความ day list ว่างว่า "ทุกวัน" อยู่แล้ว และได้ทดสอบหน้า Policy Builder + versioning ไปในตัว

### O2. 🐛 Time window แสดงผิดไป 12 ชั่วโมง — `18:00` ขึ้นจอว่า `06:00`

**เจอตอน:** ผู้ใช้ถามว่า "ทำไมมันยังขึ้น Refused" แล้วผมเปิดหน้า policy ด้วย Playwright ไปดู

ช่อง from/to ใน [SubjectBuilder.tsx](frontend/app/src/pages/policies/SubjectBuilder.tsx) เป็น `<input type="time">` กว้าง `tw:w-24` (96px)
บน Edge locale en-US เบราว์เซอร์เรนเดอร์เป็น **12 ชั่วโมง** คือ `06:00 PM` + ไอคอนนาฬิกา ซึ่ง**ไม่พอ** → ส่วน `PM` ถูกตัดทิ้ง เหลือบนจอว่า `06:00`

| | ค่าจริงใน DB | ที่ขึ้นบนจอ |
|---|---|---|
| `to` | `18:00` | **`06:00`** |

> ข้อมูลไม่ได้เสีย (`input.value` ยังเป็น `"18:00"`) แต่**คนอ่านผิดไป 12 ชั่วโมง** บนหน้าจอเดียวที่มีหน้าที่บอกว่า "สิทธิ์นี้ใช้ได้ถึงกี่โมง" — ในแอป access control อันนี้คือบั๊กที่ยอมไม่ได้

**แก้:** `tw:w-24` → `tw:w-36` ทั้งสองช่อง
**พิสูจน์:** วัดจาก DOM — เดิม `scrollWidth > clientWidth` (ล้น) ตอนนี้ `clientWidth === scrollWidth === 142` ทั้งคู่ → `clipped: false`

### O3. ยืนยันแล้วว่าปุ่ม Save ของ Policy Builder **ไม่ได้พัง**

ผู้ใช้รายงานว่าแก้ time window แล้วยัง Refused อยู่ → ไล่ดูพบว่า `updatedAt` ฝั่ง server ยังเป็น `2026-09-20T04:04:14Z` (11:04 น.) **แปลว่าไม่มี save เข้ามาเลย**

ทดสอบ client path ด้วย Playwright โดย **route intercept + abort** (ดูว่าจะส่งอะไร โดยไม่ให้ถึง server จริง):

```
>>> PUT /api/v1/policies/b594579f-…?version=4
>>> subject.time = {"windows":[{"from":"08:00","to":"18:00","timezone":"Asia/Bangkok"}]}
```

- dropdown มีครบ 3 ตัวเลือก: `every day` / `weekdays` / `weekends`
- เลือก `every day` แล้ว `days` **หายออกจาก payload** ถูกต้องตาม [TimeMatcher.java:95-100](backend/dac-engine/src/main/java/com/mfec/dac/engine/TimeMatcher.java#L95-L100) (list ว่าง = ทุกวัน)
- ปุ่ม Save ไม่ disabled, ไม่มี error บนจอ, ยิง request 1 ครั้ง

→ **สรุป: ทั้ง form, serialization และ mutation ทำงานถูกหมด** ที่ยังไม่เปลี่ยนคือ save ไม่เคยถูกกดจนสำเร็จ วิธีเช็กว่าสำเร็จ: ป้าย version บนหัวหน้าต้องเปลี่ยนจาก **v4** เป็น **v5**

> ~~หมายเหตุสำหรับรอบหน้า: console มี warning `Maximum update depth exceeded ... at Navigate (react-router-dom)` บนหน้านี้ — ยังไม่ได้ไล่ ยังไม่เห็นว่ากระทบการทำงาน แต่เป็น render loop ที่ควรตามต่อ~~ → **ปิดแล้วใน O8** และ "ยังไม่เห็นว่ากระทบการทำงาน" คือการอ่านที่ผิด มันคือต้นเหตุของหน้าขาวตอน login

### O4. 🐛 แถบ explanation ไปเบียดตารางจนแถวหาย — แก้ด้วย splitter ลากได้

**ที่มา:** ผู้ใช้เปิด time window สำเร็จแล้ว (policy เป็น v5) query ได้ แถบ explanation ขึ้นครบ **แต่ไม่เห็นแถวเลย**

ตรวจ API ก่อน — backend ถูกต้อง 100%:
```
rows : [[1,"Somchai Wong","***@example.co.th","*********3456",…,"BKK-01"],
        [2,"Nattaporn Sri","***@example.co.th","*********4567",…,"BKK-01"]]
```
RLS + masking ทำงานครบ → **ปัญหาอยู่ที่ layout ที่ผมเพิ่งแก้ในรอบ O**

วัดจาก DOM บน viewport 1600×1000:

| | สูง |
|---|---|
| ช่องผลลัพธ์ทั้งหมด | 163px |
| แถบ explanation ที่ผมเพิ่งแทรก | ~85px |
| **เหลือให้ตาราง** | **~78px** |

> ผมเพิ่ม block เข้าไปใน pane ที่มีความสูง**ตายตัว** โดยไม่ได้เพิ่มพื้นที่ให้ — บนจอผมเห็น 2 แถวแบบเฉียดฉิว จอเตี้ยกว่านั้นนิดเดียวแถวหายหมด **เป็นบั๊กที่ผมทำเอง**

#### ทางแก้ที่ **ไม่** เลือก และเหตุผล

ตอนแรกแก้เป็น `flex-[1.1]` → `flex-[2]` (ให้ผลลัพธ์กินพื้นที่มากขึ้น) แต่ผู้ใช้ท้วงว่า **"ต้องทำให้เห็นครบสิ ในอนาคตอาจจะมี Row เยอะ"** — ถูกต้อง สัดส่วนตายตัวตอบโจทย์ไม่ได้ เพราะ SQL บรรทัดเดียว + 1000 แถว กับ SQL 40 บรรทัด + 3 แถว ต้องการสัดส่วน**ตรงข้ามกัน** และทั้งคู่เป็นเคสปกติ

#### ทางแก้ที่เลือก — `Splitter` ลากได้ (แบบ BigQuery)

[QueryPage.tsx](frontend/app/src/pages/query/QueryPage.tsx) — component `Splitter`

- ลากเส้นคั่นระหว่าง editor กับผลลัพธ์ได้ตามใจ
- **จำค่าไว้ใน `localStorage`** (`arak.query.editorHeight`) — ห่อ try/catch ทั้ง read และ write เพราะ private window โยน exception และ preference ที่พังต้องไม่ทำให้หน้าพัง
- clamp: editor ต่ำสุด 72px, ผลลัพธ์**การันตีขั้นต่ำ 140px เสมอ** วัดจาก `clientHeight` ของ column จริง ไม่ใช่ค่าคงที่
- **ใช้คีย์บอร์ดได้ด้วย** — `role="separator"` + `tabIndex=0` + `ArrowUp/Down` (Shift = ก้าวใหญ่) เส้นคั่นที่ตอบสนองแค่เมาส์คือการยึดตารางไปจากคนที่ใช้เมาส์ไม่ได้
- แถบ explanation ได้ `max-h-32 overflow-auto` — asset ที่มี masked column สิบกว่าตัวต้องไม่สามารถดันแถวที่มันกำลังอธิบายหลุดจอได้ **คำอธิบายมีไว้ทำให้ตารางอ่านง่ายขึ้น จึงไม่มีทางสำคัญกว่าตาราง**

**พิสูจน์ด้วย Playwright:** ลากขึ้น 130px → พื้นที่ตาราง `234px → 362px`, `localStorage` เก็บ `72` (ชน min พอดี = clamp ทำงาน) · `tsc` ผ่าน · `jest` 7 suites / 25 tests ผ่าน

### O5. ทำไม "Run แล้วไม่เห็น Row" — สาเหตุจริง บวกโหมดเต็มจอแบบ BigQuery

**คำอธิบายสองรอบก่อนของผมผิดทั้งคู่** — ไม่ใช่ "แถบอธิบายแย่งที่กับ grid" (แก้ด้วย ratio) และไม่ใช่ "ลาก splitter เอา" — **แถวถูก render ออกมาถูกต้องตลอด แต่มันอยู่นอกจอ และหน้า scroll ลงไปดูไม่ได้**

#### หลักฐาน — วัดด้วย Playwright 4 ขนาดจอ (ก่อนแก้)

| จอ | แถวแรกสิ้นสุดที่ | จอสูง | หลุดจอ? | scroll ได้? |
|---|---|---|---|---|
| 1920×1080 | 805 | 1080 | ✅ | — |
| 1536×864 | 823 | 864 | ✅ | — |
| **1366×768** | **841** | 768 | ❌ | **ไม่ได้** |
| **1280×720** | **862** | 720 | ❌ | **ไม่ได้** |

`document.scrollHeight === document.clientHeight` ทุกขนาด → แถวถูก render จริง (`rows: 2` ทุกขนาด) แต่**เข้าถึงไม่ได้เลย**

#### สาเหตุจริงมี 4 ชั้นซ้อนกัน ไม่ใช่ชั้นเดียว

1. **`tw:h-[calc(100vh-8rem)]` ที่ root ของหน้า** — กัน chrome ไว้ 8rem (128px) แต่ header + ชื่อหน้า + คำอธิบายกินจริง ~230px และ**คำอธิบาย wrap เพิ่มบรรทัดเมื่อจอแคบลง** → จอยิ่งเล็ก เนื้อหายิ่งล้นล่าง
   → แก้ด้วย `useFillViewport()` — **วัด `getBoundingClientRect().top` จริง แล้วเอา `window.innerHeight` ลบ** (`innerHeight` ไม่ใช่ `100vh` — บนมือถือสองค่านี้ต่างกัน) + `ResizeObserver`
2. **console column ไม่มี `tw:min-h-0`** — flex child ที่ขาด `min-h-0` **หดตัวต่ำกว่า content ไม่ได้** → root สูงตามที่วัดมา แต่ของข้างในทะลุออกมา
3. **แถบอธิบาย policy เป็น `tw:max-h-32` + `tw:shrink-0`** — นี่คือตัวร้ายตัวจริง: panel สูง 140px แต่ tab bar 36 + strip 128 = 164 → **กล่องตารางเหลือสูง `0px` และถูกดันไปวางใต้ panel ที่ top=735 ขณะที่ panel จบที่ 728**
   → `tw:max-h-[30%]` (สัดส่วนของ panel ไม่ใช่ค่าคงที่) + `MIN_RESULT_HEIGHT` 140 → **240**
4. **`useRef` + `ResizeObserver` กับ portal** — เข้า/ออกโหมดเต็มจอย้าย DOM ทั้งก้อน → node ใหม่ แต่ ref object ตัวเดิม → effect ไม่ rerun → **observer เกาะ node ที่หลุดออกจาก document ไปแล้ว** → ออกจากเต็มจอที 1280×720 แถวหลุดอีก
   → เปลี่ยนเป็น **callback ref** (`useState<HTMLDivElement | null>`) ให้ effect rerun ตอน node เปลี่ยน

#### โหมดเต็มจอ (ผู้ใช้ขอ: "เต็มหน้า ไม่ต้องเห็น menu ข้างๆ หรือข้างบน")

ปุ่ม **Full screen** ใน toolbar + **Esc** ออก → ซ่อน top bar, side nav และหัวข้อ "Query" + คำอธิบายทั้งย่อหน้า

> **กับดักที่เสียเวลานานที่สุด:** `tw:fixed tw:inset-0 tw:z-50` **ไม่พอ** — `.arak-page-enter` มี `animation: ... both` ที่ keyframes มี `transform` → มันกลายเป็น **containing block ของ `position: fixed`** และเป็น **stacking context ของตัวเอง** → `inset-0` กลายเป็นขอบเขตของ *หน้า* ไม่ใช่ viewport และ top bar (`sticky z-50`) ยังทับทับขึ้นมาถึงแม้ z-index จะสูงกว่า
> **ทางแก้:** `createPortal(consoleTree, document.body)` — ออกจาก subtree ที่ transform ไปเลย ไม่ใช่ไล่ z-index เอา

#### หลักฐานหลังแก้ — 4 ขนาดจอ × 3 สถานะ (normal / fullscreen / กด Esc กลับ)

```
1920x1080 normal   belowFold:false     fullscr belowFold:false gridH:602   after-Esc belowFold:false
1536x864  normal   belowFold:false     fullscr belowFold:false gridH:386   after-Esc belowFold:false
1366x768  normal   belowFold:false     fullscr belowFold:false gridH:246   after-Esc belowFold:false
1280x720  normal   belowFold:false     fullscr belowFold:false gridH:202   after-Esc belowFold:false
fs-chrome (ทุกขนาด): overlayInBody:true topbarCovered:true sidebarCovered:true titleVisible:false
```

`npx tsc --noEmit` ผ่าน · `npx jest` → 7 suites / 25 tests ผ่าน

#### ไฟล์ที่แก้ — [QueryPage.tsx](frontend/app/src/pages/query/QueryPage.tsx) ไฟล์เดียว

| สิ่งที่เพิ่ม | หน้าที่ |
|---|---|
| `useFillViewport(node, disabled)` | วัดที่ว่างจริงใต้ chrome แทนที่จะเดา |
| `useElementHeight(node)` | ความสูง pane สำหรับ clamp ค่า splitter ที่ save ไว้ |
| `fullscreen` state + `createPortal` | โหมดเต็มจอ |
| `shownEditorHeight` | ค่า split ที่ save จากจอใหญ่ **ไม่ไปกินที่ grid บนจอเล็ก** (clamp ตอนอ่าน ไม่ตอนเขียน → preference ไม่หาย) |

> **บทเรียน:** ผมอธิบายสองรอบแรกจากการเดา ไม่ใช่การวัด รอบนี้เดิน DOM จาก `tbody` ขึ้นไปทีละชั้นจนเจอชั้นที่ `top` มากกว่า `bottom` ของ parent — สามบรรทัดของตารางเดียวชี้ชัดกว่าการเดาสามรอบรวมกัน

### O6. 🐛 Cursor ใน SQL editor หาย—วาดอยู่ แต่วาดด้วยสีโปร่งใส

**ที่มา:** ผู้ใช้ถาม "Cursor ในนี้หายไปไหน" พร้อมภาพ SQL editor

**สาเหตุ:** [SqlEditor.tsx](frontend/app/src/pages/query/SqlEditor.tsx) เป็น `<textarea>` ที่วางทับบน `<pre>` ที่ทำ syntax highlight — ตัว textarea จึงต้องเป็น `tw:text-transparent` เพื่อให้ชั้นสีข้างล่างทะลุขึ้นมา แต่ class เดิมเขียนไว้ว่า:

```
tw:text-transparent tw:caret-current
```

`caret-current` = `caret-color: currentColor` → **currentColor ก็คือ transparent นั่นเอง** cursor จึงถูกวาด ถูกตำแหน่ง และกะพริบอยู่ตลอด—ด้วยสีโปร่งใส คนใช้เลยอ่านว่า editor หลุด focus

**แก้เป็น:** `tw:caret-text-primary` (ตาม theme → dark mode ตามไปเอง)

#### ⚠️ กับดักของ prefix `tw:` — ห้ามเขียน `tw:xxx-[var(--color-...)]`

ความพยายามแรกของผมคือ `tw:caret-[var(--color-text-primary)]` — **compile ผ่าน ไม่ error แต่ไม่ทำงาน** วัดใน browser ได้:

```
caret-color: rgba(0, 0, 0, 0)        ❌ ยังโปร่งใสเหมือนเดิม
getComputedStyle(root)['--color-text-primary'] === ''    ← ต้นเหตุ
```

**เพราะ Tailwind 4 ที่ตั้ง prefix `tw` เปลี่ยนชื่อ CSS variable ของ theme ทุกตัวเป็น `--tw-<name>` ด้วย** ไม่ใช่แค่ชื่อ class:

| เขียน | ได้ CSS | runtime |
|---|---|---|
| `tw:caret-[var(--color-text-primary)]` | `caret-color: var(--color-text-primary)` | ❌ ตัวแปรไม่มีจริง → transparent |
| `tw:caret-text-primary` | `caret-color: var(--tw-color-text-primary)` | ✅ `rgb(24, 29, 39)` |

> **กฎ:** ในโปรเจกต์นี้ **ให้เรียก utility ตามชื่อ token (`tw:caret-text-primary`) เสมอ** อย่าเขียน `var(--color-*)` ดิบใน arbitrary value — ถ้าจำเป็นจริงต้องเขียน `var(--tw-color-*)`
> grep ทั้ง `app/src` และ `ui-core-components/src` แล้ว **ไม่มีที่อื่นที่พลาดแบบเดียวกัน**

#### วิธียืนยัน — วัด ไม่ใช่ดู

screenshot พิสูจน์ cursor ไม่ได้ (มันกะพริบ) จึงใช้ Playwright อ่าน computed style แทน:

```
{ caretColor: "rgb(24, 29, 39)", color: "rgba(0, 0, 0, 0)", focused: true }
```

ตัวอักษรยังโปร่งใสตามที่ตั้งใจ (ชั้น highlight ต้องทะลุขึ้นมา) แต่ cursor ทึบแล้ว

#### Test ใหม่ — [SqlEditor.test.tsx](frontend/app/src/pages/query/SqlEditor.test.tsx) (4 tests)

jsdom ไม่ resolve Tailwind จึง assert ที่ class name ตรงๆ — ซึ่งเป็นที่ที่บั๊กนี้อยู่พอดี:

| test | กันอะไร |
|---|---|
| caret มีสีของตัวเอง | กันการกลับไปใช้ `caret-current` คู่กับ `text-transparent` อีก |
| gutter มีเลขครบทุกบรรทัด + `aria-hidden` | screen reader ไม่ต้องอ่าน "1 2 3" นำ |
| Tab = เว้นวรรค ไม่ใช่ออกจากช่อง | |
| Ctrl+Enter = run และไม่ขึ้นบรรทัดใหม่ | |

`npx tsc --noEmit` ผ่าน · `npx jest` → **8 suites / 29 tests** (เดิม 7/25)

### O7. หัวหน้าหน้า Query — ตัดคำอธิบายทิ้ง ย้ายปุ่ม Full screen ไปขวาบน

**ที่มา:** ผู้ใช้สั่ง "เอาคำนี้ออกได้ไหม มันเกะกะ" (ย่อหน้าห้าบรรทัดใต้หัวข้อ Query) และ "เอา Full screen ไปไว้ขวาบน"

| เดิม | ใหม่ |
|---|---|
| `<h1>Query</h1>` + ย่อหน้า 5 บรรทัด | `<header>` เป็น flex row — `Query` ซ้าย / ปุ่ม **Full screen** ขวา |
| ปุ่ม Full screen อยู่ใน toolbar ข้างๆ Run | อยู่ขวาบนสุดของหน้า |
| `tw:mt-6` ใต้ header | `tw:mt-4` (header เหลือบรรทัดเดียวแล้ว) |

#### จุดที่ต้องระวัง — โหมดเต็มจอ**ซ่อน header ทั้งอัน**

ถ้าย้ายปุ่มไป header เฉยๆ พอเข้าโหมดเต็มจอแล้ว **ปุ่มออกจะหายไปด้วย** จึงประกาศ element ไว้ครั้งเดียว (`fullscreenToggle`) แล้ววางสองที่:

```
โหมดปกติ   → อยู่ใน <header> ขวาสุด คู่กับชื่อหน้า
โหมดเต็มจอ → <header> หาย → ย้ายไปอยู่ขวาสุดของ toolbar (chrome เดียวที่เหลือ)
```

Esc ยังใช้ได้เหมือนเดิม (ตั้งแต่ O5)

#### วัดแล้ว 3 viewport

```
1920x1080  blurbGone:true sameRowAsTitle:true docBelowFold:false  fs→exitInToolbar:true titleVisible:false  Esc→กลับปกติ docBelowFold:false
1366x768   เหมือนกัน  (gapToRightEdge 32px = ชิดขอบขวาของ content column)
1280x720   เหมือนกัน
```

> ที่ 1920 ระยะห่างขอบขวา 212px เพราะหน้าถูกครอบด้วย `max-w-7xl` ตาม AppShell — ปุ่มชิดขอบขวาของ **คอลัมน์เนื้อหา** ตรงกับทุกหน้าในแอป ไม่ใช่ขอบจอ

### O8. 🐛 หน้าขาวเปล่าที่ `/login` ตอน login เสร็จ — `<Navigate>` วน redirect ตัวเองจนเกิน 25 รอบ

**อาการที่ผู้ใช้เจอ:** "เป็นหน้าขาวเปล่า http://localhost:5274/login ตอน login เสร็จ"

**สิ่งที่ตัดออกไปก่อน:** `POST /api/v1/auth/login` ตอบ 200 ใน ~77ms ทั้งยิงตรงและผ่าน Vite proxy → พังฝั่ง client ล้วนๆ และ **profile ใหม่สะอาดๆ reproduce ไม่ได้** (login แล้วถึง `/` ใน 300ms, console เงียบสนิท) → เป็นบั๊กที่ขึ้นกับ *เส้นทางที่เดินมา* ไม่ใช่ขึ้นกับ state ที่ค้างใน localStorage

#### วิธีที่จับได้ — เลิกทดสอบทางที่ถูก แล้วไล่ทดสอบทางที่คนใช้จริงเดิน

เขียนสคริปต์ยิง 6 สถานการณ์รวดเดียว แล้วนับ console error:

| สถานการณ์ | ผล |
|---|---|
| **เปิด deep link `/query` ทั้งที่ยังไม่ login แล้วค่อย login** | 🔴 **`Maximum update depth exceeded` × 7** |
| กด Sign in รัวสองที | สะอาด |
| กด Enter สองที | สะอาด |
| login → กลับมา `/login` → login ใหม่ | สะอาด |
| กด Back หลัง login | `about:blank` — เป็นธรรมชาติของ `replace` (เหลือ history แค่ entry เดียว) ไม่ใช่บั๊ก |
| navigate ออกกลางม่าน | สะอาด (ตอนนั้น) |

**component stack ชี้ตรงไปที่ `RequireAuth` ไม่ใช่ `LoginPage`** และ **error เกิดตั้งแต่ก่อนกด login** — คนละที่กับที่ตั้งสมมติฐานไว้ทั้งสองข้อ

#### สาเหตุ

```tsx
// RequireAuth.tsx — ของเดิม
return <Navigate replace state={{ from: location }} to="/login" />;
```

`{ from: location }` เป็น **object ใหม่ทุก render** และ react-router 6.30 ใส่ `state` ไว้ใน dependency array ของ effect ที่เรียก `navigate()`:

```js
// node_modules/.vite/deps/react-router-dom.js:4509-4513
React.useEffect(() => navigate(JSON.parse(jsonPath), { replace, state, relative }),
                [navigate, jsonPath, relative, replace2, state]);
//                                                       ^^^^^ เปลี่ยน identity ทุก render
```

→ effect รันใหม่ → `navigate()` → render ใหม่ → object ใหม่ → effect รันใหม่ → **วน**

วงจรนี้หยุดเองเมื่อ route match เปลี่ยนแล้ว `RequireAuth` unmount ซึ่งเป็นการ **แข่งกัน ไม่ใช่การรับประกัน** — เครื่องผมแพ้ 7 รอบแล้วหลุด **แต่ถ้าแพ้ครบ 25 รอบ React เลิก warn แล้ว throw** → tree ทั้งก้อนถูก unmount → **หน้าขาวเปล่า โดย URL ค้างที่ `/login` เป๊ะตามที่ผู้ใช้รายงาน** เครื่องช้ากว่า / render เยอะกว่า = แพ้ง่ายกว่า ซึ่งอธิบายว่าทำไมผู้ใช้เจอแต่ผมไม่เจอ

> ⚠️ ตัวอย่างใน doc ของ react-router เองก็เขียน `state={{ from: location }}` แบบนี้ — มันรอดเพราะปกติ redirect unmount component ทันก่อน render รอบสอง **ไม่ใช่เพราะมันถูก**

#### สิ่งที่แก้

**1. [RequireAuth.tsx](frontend/app/src/auth/RequireAuth.tsx) — ทำให้ state มี identity คงที่**

```tsx
const from = useMemo(
  () => ({ from: { pathname: location.pathname, search: location.search, hash: location.hash } }),
  [location.pathname, location.search, location.hash]
);
...
return <Navigate replace state={from} to="/login" />;
```

memo จาก **primitive** ไม่ใช่จากตัว `location` (ซึ่งเปลี่ยน identity เอง) และเก็บ `search`/`hash` มาด้วย — คนที่ถูกเด้งมาจาก `/query?sql=...` กำลังดูของชิ้นหนึ่งอยู่ ทิ้ง query string = ส่งเขากลับไปหน้าเปล่าที่ถูกหน้าจอ

**2. [AuthSplash.tsx](frontend/app/src/auth/AuthSplash.tsx) — ย้าย timer ของม่านเข้ามาไว้ใน store**

ของเดิม `LoginPage` ตั้ง `setTimeout` แล้วให้ callback เรียก `splash.hide()` + `navigate()` — **แต่ `<Navigate>` unmount `LoginPage` ทิ้งตั้งแต่ token ลงทันที คือก่อน callback ราว 900ms**

นี่คือกับดักที่เกือบพลาด: ตอนแรกแก้ด้วยการ `clearTimeout` ตอน unmount ซึ่ง **จะยิ่งพังหนักกว่าเดิม** เพราะ callback ตัวนั้นคือสิ่งเดียวที่ลดม่านลง และม่านคือ `tw:fixed tw:inset-0 tw:z-200 tw:bg-primary` = **แผ่นทึบเต็มจอ → ม่านที่ไม่ลง ก็คือหน้าขาวเปล่า**

ทางที่ถูกคือ **ม่านต้องลดตัวเอง** ไม่ใช่ฝากคนที่ไม่อยู่แล้ว:

```ts
let pending: number | undefined;          // module scope — ม่านอายุยืนกว่าคนที่ยกมัน
show:      (phase) => { clearTimeout(pending); set({ phase }); },
hide:      ()      => { clearTimeout(pending); set({ phase: 'idle' }); },
hideAfter: (ms)    => { clearTimeout(pending); pending = setTimeout(() => set({ phase: 'idle' }), ms); },
```

`clearTimeout` ที่หัวทุกตัวทำหน้าที่เป็น generation guard ฟรีๆ — ม่าน sign-out ที่เพิ่งยกขึ้น จะไม่ถูก timer ของ sign-in รอบก่อนดึงลง

**3. [LoginPage.tsx](frontend/app/src/pages/LoginPage.tsx)**

```tsx
await signIn(username.trim(), password);
splash.hideAfter(AUTH_SPLASH_MS);     // แทน setTimeout ที่มี navigate ซ้ำอยู่ข้างใน
```

- `navigate()` ใน timer ถูกลบทิ้ง — มันคือ redirect ตัวเดิมยิงซ้ำรอบสอง 900ms ให้หลัง ใส่ tree ที่เดินไปไกลแล้ว (ผลข้างเคียงจริง: เดิมถ้า user กดไปหน้าอื่นระหว่างม่านขึ้น จะโดนลากกลับ — เทสต์ `nav-mid-curtain` เดิมจบที่ `/` ตอนนี้จบที่ `/policies` ตามที่ควรเป็น)
- `target` ไม่รับ `/login` เป็นปลายทาง (กันไว้ — ถ้าเกิดขึ้นจริงจะ redirect หาตัวเองจนหน้าขาว) และต่อ `search`/`hash` กลับเข้าไป
- `useNavigate` ไม่ถูกใช้แล้ว ลบ import ออก

#### ผลหลังแก้ (วัดด้วยสคริปต์เดิม)

```
at login, state.from = {"from":{"pathname":"/query","search":"","hash":""}}
t~500ms   loops=0    t~1000ms  loops=0    ...    t~7000ms  loops=0
total loop errors: 0
```

ทั้ง 6 สถานการณ์สะอาดหมด

#### Test — 6 ตัวใหม่ (รวมเป็น 10 suites / 35 tests)

| ไฟล์ | ทดสอบอะไร |
|---|---|
| [RequireAuth.test.tsx](frontend/app/src/auth/RequireAuth.test.tsx) | mock `<Navigate>` เก็บ prop ไว้ → render สองรอบ แล้ว assert `state` เป็น **object เดียวกัน** (`toBe`) — ทดสอบที่ *สาเหตุ* ไม่ใช่ที่อาการ เพราะอาการต้องแพ้ race ครบ 25 รอบถึงจะโผล่ ซึ่ง reproduce ใน jsdom ไม่ได้; + เก็บ `search`/`hash` ครบ; + มี token แล้วต้อง render ของที่มันเฝ้า |
| [AuthSplash.test.tsx](frontend/app/src/auth/AuthSplash.test.tsx) | ม่านลดเองได้โดยไม่ต้องพึ่งคนเรียก; `show` ใหม่ไม่ถูก timer เก่าดึงลง; `hide()` ตรงๆ ลงทันที (เคส password ผิด) |

#### บทเรียนที่ควรจำ

1. **"warning ใน console ที่ยังไม่เห็นว่ากระทบอะไร" ไม่ใช่เรื่องรอได้** — O3 บันทึกไว้ว่า "ยังไม่เห็นว่ากระทบการทำงาน" ทั้งที่มันคือหน้าขาวที่ผู้ใช้เจอ render loop ที่หยุดเองได้เพราะ**ชนะการแข่ง** จะแพ้เมื่อไหร่ก็ได้บนเครื่องที่ช้ากว่า
2. **reproduce ไม่ได้ใน profile สะอาด ≠ ขึ้นกับ state ที่ค้าง** — คราวนี้มันขึ้นกับ *เส้นทางที่เดินเข้ามา* (deep link) สมมติฐานสองข้อแรก (`target` เป็น `/login`, timer ค้าง) ผิดทั้งคู่ สิ่งที่ได้คำตอบคือการยิง 6 เส้นทางจริงรวดเดียวแล้วนับ error
3. **`clearTimeout` ตอน unmount ไม่ใช่คำตอบเสมอไป** — ถ้า callback นั้นคือสิ่งเดียวที่ปลดสถานะ global การยกเลิกมันคือการล็อกสถานะนั้นไว้ถาวร ของที่อายุยืนกว่า component ต้องไม่ถูกถือไว้ใน component

### P. ทดสอบ Data policy ให้ครบทุกเคส — เจอบั๊กจริงสองตัว

**ที่มา:** ผู้ใช้สั่ง *"ทดสอบเรื่อง Subscription และ Data policy ให้เยอะๆ อย่าให้มี Bug ทดสอบทุกแบบที่เป็นไปได้"*

ไฟล์ใหม่ [DataPolicyCompositionTest.java](backend/dac-engine/src/test/java/com/mfec/dac/engine/DataPolicyCompositionTest.java) — **26 tests / 4 nested class**

| nested class | tests | คุมอะไร |
|---|---|---|
| `RowFilters` | 6 | RLS หลายชั้น AND กัน, multi-value → `IN`, attribute ที่ principal ไม่มี, `ALWAYS_FALSE` |
| `ColumnMasks` | 8 | mask ชนกันบน column เดียว → เข้มสุดชนะ, cell mask (`condition`), mask จาก facet |
| `Hide` | 4 | ซ่อน column ทั้งคอลัมน์ ต่างจาก mask ยังไง, ซ่อน + mask พร้อมกัน |
| `Release` | 8 | `allowLocalOverride` + release จากชั้นที่ลึกกว่า, ชั้นที่ไม่ยอม override |

#### 🐛 บั๊ก 1 — `rowPredicates` ไม่ถูก sort → **FR-6.0c พังเงียบๆ**

`masks` กับ `hidden` ถูก sort ไว้แล้ว แต่ `rowPredicates` **ไม่** → ลำดับของ predicate เป็นไปตามลำดับที่ policy ถูกโหลดมา
predicate ทุกตัวถูก AND กันอยู่แล้ว **ลำดับจึงไม่มีความหมายเชิงตรรกะ** แต่มันมีผลกับ SQL ที่ compiler ทั้งสามตัว generate ออกมา
→ cross-mode consistency test (DoD ข้อ 6) ที่เทียบ **ทีละ byte** จะ fail/pass สลับกันไปตามลำดับแถวที่ query คืนมา ซึ่งเป็นบั๊กที่จะเสียเวลาไล่ที่สุดตอน M7b

```java
rowPredicates.sort(Comparator.comparing(PolicyEngine::signature));
```
`signature()` เอา**ทุก field** มาต่อกัน รวม `sourcePolicyId` ด้วย เพื่อให้สองตัวที่เท่ากันคือสองตัวที่พูดเรื่องเดียวกันจริงๆ (ตัวไหนอยู่ก่อนจึงไม่สำคัญ)

#### 🐛 บั๊ก 2 — column rule `ALLOW` ที่มี condition ต้องดูแถว → **ปล่อย plaintext ให้คนที่ condition นั้นตั้งใจกัน**

`ColumnRule.Action.ALLOW` = การ**ปลด** mask (release) ส่วน `MASK` = การ**ใส่** mask
เวลา expression ตีเป็น `ROW_DEPENDENT` (เช่นอ้าง `row.`) ของเดิมทำเหมือนกันทั้งสองทาง คือส่ง condition ต่อลงไปให้ compiler กลายเป็น cell mask
แต่ **release ไม่มีที่ให้เก็บ condition** — decision บันทึกได้แค่ "ปลด" กับ "ไม่ปลด" ไม่มีช่อง "ปลดเฉพาะบางแถว"
→ ของเดิมจึง**ปลด mask ทิ้งทั้ง column** ให้ principal ที่ condition เขียนไว้เพื่อกันออกไป

แก้ให้ release ที่ตัดสินไม่ได้ **ไม่ปลดอะไรเลย** พร้อมเหตุผลใน audit:
> `column rule with action ALLOW carries a condition that cannot be decided without a row (...); it releases nothing, because a release cannot be made conditional`

> นี่คือ invariant กลางของ engine — **input ที่ตัดสินไม่ได้ ต้องนับเป็นโทษของ principal เสมอ** ที่นี่เป็นจุดเดียวที่มันเคยรั่ว

---

### Q. ทดสอบ Subscription policy 45 เคส — **ไม่เจอบั๊กใหม่ เขียวหมดตั้งแต่รันแรก**

ไฟล์ใหม่ [SubscriptionPolicyTest.java](backend/dac-engine/src/test/java/com/mfec/dac/engine/SubscriptionPolicyTest.java) — **45 tests / 7 nested class**

| nested class | tests | คุมอะไร |
|---|---|---|
| `Exemptions` | 5 | exemption ที่ระบุ **team** ครอบสมาชิกด้วย · ตัวที่หมดอายุ "พอดีวินาทีนี้" = หมดแล้ว |
| `Lifecycle` | 8 | `DRAFT`/`DISABLED`/`ARCHIVED` ไม่มีผล · `validFrom` วินาทีนี้ = มีผลแล้ว · `validUntil` วินาทีนี้ = หมดแล้ว |
| `Environments` | 5 | DENY ที่ผูก env อื่น **ไม่** deny ที่นี่ (environment ขยายสิทธิ์ได้พอๆ กับที่บีบ) |
| `Gates` | 10 | DENY ที่ไม่ match = ไม่ใช่ประตู · **ALLOW ที่ไม่ match = ปิดทั้งชั้น** · sub-domain ที่ลึกกว่า override ตัวที่ตื้นกว่าได้ ไม่ใช่ทางกลับ |
| `Groups` | 10 | ฟีเจอร์ใหม่ในข้อ R |
| `Undecidable` | 3 | expression ที่ตัดสินไม่ได้ → ALLOW ไม่ให้อะไรเลย / DENY deny · คำอธิบายมีคำว่า `failing closed` |
| `NoBinding` | 4 | มีแต่ data policy → `no subscription policy binds` · policy เป็น null ข้าม · list เป็น null → deny · `effect` null อ่านเป็น ALLOW |

**พฤติกรรมที่อ่านแล้วสะดุด แต่จงใจ pin ไว้:** *exemption ที่ไปตกบน ALLOW จะ**ลบ grant ทิ้ง*** (exemption แปลว่า "ไม่ต้องอยู่ใต้ policy ตัวนี้" ไม่ใช่ "ได้รับการยกเว้นให้ผ่าน") ถ้าจะเปลี่ยนความหมายนี้ในอนาคต test จะพังให้เห็น ไม่ใช่เปลี่ยนเงียบๆ

#### ⚠️ กับดักที่เสียเวลาไปรอบหนึ่ง — Surefire + `@Nested`

`-Dtest=SubscriptionPolicyTest` **exit 0** และ `TEST-...SubscriptionPolicyTest.xml` เขียนว่า `tests="0"`
เพราะ **nested class เขียน report ของตัวเอง** (`TEST-...SubscriptionPolicyTest$Groups.xml`)

> **exit code 0 ไม่ได้พิสูจน์ว่า test ได้รัน** — ต้องนับจากไฟล์เสมอ:
> ```bash
> grep -ho 'tests="[0-9]*"' backend/dac-engine/target/surefire-reports/TEST-*.xml
> ```

**engine: 91 → 162 tests** (เดิม 91 + data policy 26 + subscription 45) เขียวทั้งหมด

---

### R. Subscription/Data policy กำหนด **by group หลายกลุ่ม AND/OR** ได้แล้ว (ผู้ใช้สั่ง)

เดิม `subjectRule.principals` เป็น **OR list** อย่างเดียว → เขียน "อยู่กลุ่ม A **หรือ** B" ได้ แต่เขียน "และต้องอยู่ C ด้วย" ไม่ได้

เพิ่ม field ที่สอง `requiredPrincipals` = **AND list** ทำงานทับบน `principals`:

| ชั้น | ไฟล์ | เปลี่ยนอะไร |
|---|---|---|
| Schema | [subjectRule.json](backend/dac-spec/src/main/resources/json/schema/entity/policy/subjectRule.json) | `requiredPrincipals: principalMatch[]` |
| Engine | [SubjectMatcher.java](backend/dac-engine/src/main/java/com/mfec/dac/engine/SubjectMatcher.java) | loop AND ก่อนเช็ค attribute + `describe()` เขียนเหตุผลลง audit ว่า**ขาดข้อไหน** |
| Test | `SubscriptionPolicyTest$Groups` | 10 tests |
| TS type | `src/generated/entity/policy/subjectRule.ts` | `yarn parse-schema` |
| Builder UI | [SubjectBuilder.tsx](frontend/app/src/pages/policies/SubjectBuilder.tsx) | แยก `PrincipalList` ออกมาแล้ว render สองครั้ง — *"Anyone who is"* / *"And who is also"* |
| ประโยคสรุป | [policyLanguage.ts](frontend/app/src/pages/policies/policyLanguage.ts) | `describePrincipal()` + clause `who is also ...` |

ครอบคลุมรูปแบบ **`(A or B) and C and D`** ซึ่งเป็นรูปที่คนเขียนจริง

#### ทำไมสองลิสต์แบน ไม่ใช่ boolean tree (บันทึกไว้จะได้ไม่ต้องเถียงใหม่)

| ทางเลือก | ทำไมไม่เอา |
|---|---|
| tree แบบ `assetSelector` (`condition`/`and`/`or`/`not`) | ต้องแก้ POJO ให้ recursive, matcher recursive, UI recursive, migrate policy ที่เก็บไว้แล้ว — จ่ายแพงเพื่อ nesting ที่ไม่มีใครเขียน |
| toggle ตัวเดียวพลิก `principals` จาก OR เป็น AND | เขียน `(A or B) and C` ไม่ได้ |
| ใช้ชื่อ `allOf` / `anyOf` | ชนกับ keyword ของ JSON Schema (เหตุผลเดียวกับที่ `assetSelector` เลี่ยงไว้ตั้งแต่แรก) |

เกินรูปนี้ไป ให้ไปใช้ `expression` ที่มีอยู่แล้ว

**UI จงใจซ่อนลิสต์ที่สองไว้จนกว่าลิสต์แรกจะมีอะไร** — ฟอร์มเปล่าที่มีสองลิสต์หน้าตาเหมือนกันคือวิธีที่คนจะพลาดความต่างระหว่างสองอันนี้
และแถวใหม่ตอนนี้ default เป็น `{ group: '' }` (เดิม `role`) ตามที่ผู้ใช้เน้นเรื่อง group

> 🔴 **jar ที่รันอยู่ตอนนี้เก่ากว่า `SubjectMatcher`** — service ที่รันค้างไว้จะยัง**ไม่**รู้จัก `requiredPrincipals` จนกว่าจะ rebuild + restart

---

### S. หน้า Settings แบ่งเป็นสามหัวข้อแบบ OpenMetadata + หน้า Application roles

ผู้ใช้สั่งหัวข้อมาตรงๆ สามอัน — ทำตามนั้นเป๊ะ เป็น card group แบบหน้า Settings ของ OM

ไฟล์ใหม่ [SettingsPage.tsx](frontend/app/src/pages/settings/SettingsPage.tsx)

| หัวข้อ | card |
|---|---|
| **Catalog & metadata** | OpenMetadata connection (admin) · Governance vocabulary · Catalog |
| **People & platform access** | **Application roles** · People & attributes · Local groups (M2, admin) |
| **Data source connections** | Registered sources (admin) · Service & build · Sync & reconcile (admin) |

card ที่ `adminOnly` ถูกกรองด้วย `hasRole('PLATFORM_ADMIN')` และหัวข้อที่ไม่เหลือ card เลยจะไม่ render

> เหตุผลที่หัวข้อ 2 กับ 3 **ต้องแยกกัน** ไม่ใช่เรื่องความสวย — *"ใครใช้ ARAK ได้"* กับ *"ใครอ่าน table ได้"* เป็นสิทธิ์คนละเรื่องที่ blast radius ต่างกันมาก console ที่เอามาไว้ด้วยกันคือ console ที่ชวนให้คนกดอันหนึ่งโดยคิดว่ากดอีกอัน

#### หน้าใหม่ — [AppRolesPage.tsx](frontend/app/src/pages/settings/AppRolesPage.tsx) ที่ `/settings/roles`

**ทุกบรรทัดใต้ "May" อ่านมาจาก `@Secured` / โค้ด authorise จริง ไม่ได้อ่านจาก requirement** และโชว์ไฟล์+บรรทัดไว้ข้างๆ ให้คนถัดไปตรวจซ้ำได้

| role | อ่านมาจาก |
|---|---|
| `PLATFORM_ADMIN` | `OpenMetadataSettingsResource.java:36` · `SyncResource.java:31` · `SourceResource.java:213` |
| `POLICY_AUTHOR` | `PolicyResource.java:187` (`authorise()` ผ่านทันที) |
| `DATA_OWNER` | `PolicyResource.java:197` — ต้องเป็น `isDescendantOrSelf` ของสิ่งที่ตัวเองเป็นเจ้าของ · scope ว่าง = ปฏิเสธ · **update authorise ทั้ง document เก่าและใหม่** จึงย้าย policy เข้ามาใน scope ตัวเองไม่ได้ |
| `AUDITOR` | `PolicyResource.java:41` (อ่านได้) — เขียนไม่ได้ |
| `REQUESTER` | `CatalogResource.java:31` — และ `QueryResource.java:83` **ไม่รับ** role นี้ |

**อ่านอย่างเดียว** เพราะ `PrincipalResource` จงใจเป็น read-only ตอน Entra เป็นเจ้าของข้อมูล — ฟอร์ม assign บนหน้านี้จะถูก sync รอบหน้าลบทิ้ง หน้าจึงบอกตรงๆ ว่า assignment จะไปอยู่ตรงไหนแทนที่จะวางปุ่มที่ไม่ทำอะไร

**Route ที่ต่อแล้ว** — `/settings` เดิม `Navigate` ไป `/settings/openmetadata` ตอนนี้เป็นหน้า hub จริง, เพิ่ม `/settings/roles`, และ [navigation.ts](frontend/app/src/layout/navigation.ts) ชี้ Settings ไปที่ `/settings`

#### 💬 ตอบคำถามผู้ใช้ — *"group/user ควรอยู่ในหน้า people เหมือนกันไหม / ควรแบ่ง Role ยังไง"*

**แยกสองหน้า เพราะเป็นคนละคำถาม แม้จะเป็น object เดียวกัน**

| | **People & attributes** (`/principals`) | **Settings → People & platform access** |
|---|---|---|
| ตอบคำถามว่า | *"มีใครบ้าง เขาถือ attribute/group อะไร"* | *"ใครควรทำอะไรใน ARAK ได้"* |
| ใครเปิด | คนเขียน policy — เปิดดู**ระหว่างเขียน** subject rule | admin — เปิดตอน onboard คนใหม่ |
| เป็นอะไร | **directory อ่านอย่างเดียว** | **การบริหาร** |

เอามารวมกันแล้วจะได้ปุ่ม "ทำให้เป็น platform admin" โผล่ให้คนที่แค่มาเปิดดูว่ามีใครอยู่ในกลุ่ม `credit-analysts` บ้าง
ทางเชื่อมที่ควรมี: ในแถวของคนใน People → ลิงก์ "Manage app roles" (เห็นเฉพาะ admin)

**การแบ่ง role ยึดแกนเดียว — "คนเขียน" ต้องไม่ใช่ "คนเปิดใช้"** (FR-9.1 `DRAFT → PENDING_APPROVAL → ACTIVE` บังคับด้วย `separationOfDuty()`)

| role | แกนที่มันแบ่ง |
|---|---|
| `POLICY_AUTHOR` | เขียนได้ทั้งองค์กร แต่เปิดใช้ของตัวเองไม่ได้ |
| `DATA_OWNER` | เหมือนกัน แต่ถูกล้อมด้วย ownership จาก OM |
| `AUDITOR` | อ่านได้หมด เขียนไม่ได้เลย — role ที่ทำให้ audit มีน้ำหนัก |
| `REQUESTER` | เห็นแค่ของตัวเอง (default ของทุกคน) |
| `PLATFORM_ADMIN` | คุมแพลตฟอร์ม **แต่ไม่ควรเป็นคนอนุมัติ policy ของตัวเอง** |

> ห้าตัวนี้ถูก fix ไว้ด้วย CHECK constraint ใน Flyway V2 แล้ว — เพิ่ม role ใหม่ต้อง migrate ไม่ใช่แก้ค่าคงที่ใน Java

---

### T. สองเรื่อง UI ที่ผู้ใช้ชี้

#### 1. icon ของ Data policy — cylinder → **`EyeOff`**

cylinder แปลว่า "database" อยู่ทุกที่ในเชลล์นี้ รวมถึงรางซ้ายที่อยู่ติดกันเลย
data policy ไม่ได้พูดถึงฐานข้อมูล มันพูดถึง**สิ่งที่ถูกปิดไว้จาก table ที่คนเข้าถึงได้อยู่แล้ว** — และ `EyeOff` แบกความหมายนี้อยู่แล้วบนผลลัพธ์หน้า Query

#### 2. 🐛 scrollbar ในรางซ้ายตอน zoom 100% — **วัดก่อน ไม่เดา**

```
12 ลิงก์ × 44px + 11 ช่อง × 2px + 12px บน + 24px ล่าง ≈ 586px
+ footer copyright ≈ 83px                              ≈ 669px
พื้นที่จริงบนจอ laptop                                  ≈ 680px
```
เกินไปไม่กี่ px → ได้ scrollbar **เต็มความสูงที่ thumb ก็เต็มความสูง** = ตัวควบคุมที่เลื่อนอะไรไม่ได้ บนลิสต์ที่เห็นครบอยู่แล้ว

สาเหตุ: ทั้งคอลัมน์เป็นกล่อง scroll เดียว → บรรทัด copyright **นับรวมเข้าไปในความสูง**ด้วย

แก้ที่ [AppShell.tsx](frontend/app/src/layout/AppShell.tsx): `<nav>` เป็น `flex-col overflow-hidden`, ให้ **`<ul>` เท่านั้นที่ scroll** (`min-h-0 flex-1 overflow-y-auto`), footer เปลี่ยนจาก `mt-auto` เป็น `shrink-0` อยู่นอกกล่อง scroll
→ จอสูงพอ = ไม่มี scrollbar เลย · จอเตี้ยจริง = bar โผล่เฉพาะตรงลิสต์ ซึ่งเป็นส่วนเดียวที่มีที่ให้ไป

---

## รอบก่อนหน้า — Global search ข้ามทุก entity + transition ตอนเปลี่ยนหน้า/เข้า-ออกระบบ + ชิป governance ที่อ่านออก

> รอบนี้เพิ่ม **endpoint ใหม่หนึ่งตัว** (`GET /api/v1/search`) และงาน UX ล้วนๆ อีกสามเรื่องที่ผู้ใช้สั่งระหว่างทาง
> milestone ไม่ขยับ แต่ search เป็นของที่ M1 ค้างไว้ (ก่อนหน้านี้ช่องค้นหาบน header แค่พาไปหน้า Catalog พร้อม query string)

### A. Global search — ค้นได้ทุกชนิด ไม่ใช่แค่ asset

**โจทย์จากผู้ใช้:** *"search ข้างบน ให้ Search ได้ทั้งหมดอะ Term, tag, domain ,..."*

**Backend — `backend/dac-service/src/main/java/com/mfec/dac/catalog/SearchQuery.java` (ใหม่)**

`UNION ALL` เก้า branch ในคิวรีเดียว แล้ว rank รวมกันทั้งก้อน:

| branch | ตาราง | `kind` | `facet_type` ที่คืน |
|---|---|---|---|
| asset | `asset` (`is_current`) | `asset` | – |
| column | `asset_column` ⨝ `asset` | `column` | – |
| tag | `tag` | `tag` | `tags` |
| classification | `classification` | `classification` | `classifications` |
| glossary term | `glossary_term` | `term` | `terms` |
| glossary | `glossary` | `glossary` | `glossaries` |
| domain (รวม sub-domain) | `domain` | `domain` | `domains` |
| data product | `data_product` | `dataProduct` | `dataProducts` |
| policy (ไม่เอา `ARCHIVED`) | `policy` | `policy` | – |

- **rank ต้องเป็น global** ถึงจะเรียงถูก: exact name = 0 · name prefix = 1 · fqn prefix = 2 · ที่เหลือ = 3 · เท่ากันแล้วตัดด้วย `length(name)` (match ใน `customer` ตรงกว่า match ใน `customer_address_history_archive`) → นี่คือเหตุผลที่ใช้ `UNION ALL` ก้อนเดียว ไม่ใช่ยิงเก้าคิวรีแล้วเอามาต่อกันฝั่ง Java
- branch ของ **vocabulary ทั้งหกสร้างจาก template เดียว** (`vocabulary(kind, table, facetType, parentColumn)`) พร้อม `LEFT JOIN` นับ `asset_facet` → ไม่ต้องเขียนซ้ำหกรอบแล้วปล่อยให้ค่อยๆ ต่างกัน
- **column match เฉพาะ `name` ไม่ match `fqn`** — เพราะ fqn ของ column ลงท้ายด้วย fqn ของ table คำว่า `cus` จึงจะลาก column ทั้ง 40 ตัวของ `customers` ขึ้นมากลบตัว table เอง (เจอตอนเทสจริง แล้วแก้)
- ตั้งใจ **ไม่ใช้ full-text (`tsvector`)** — ของพวกนี้เป็น identifier ไม่ใช่ prose คนพิมพ์ `cust` แล้วคาดหวัง prefix ไม่ใช่ lexeme · ถ้าโตเกิน `ILIKE` คำตอบคือ OpenSearch ตามแผน Phase 1.5 ไม่ใช่ index ที่แย่กว่าเดิมในนี้
- `q` สั้นกว่า 2 ตัวอักษร → คืน list ว่างทันที ไม่แตะ DB · `limit` cap ที่ 50

**`resources/SearchResource.java` (ใหม่)** — `GET /api/v1/search?q=&limit=` `@Secured` · แยกจาก `/v1/catalog/assets?search=` ตั้งใจ: อันนั้น filter ตารางและ paginate อันนี้ตอบ "ของชื่อนี้อยู่ไหน" แล้ว cap สั้น · รวมกันแปลว่าหน้า Catalog ต้องจ่ายค่า UNION อีกแปด branch ทุกครั้งที่เปลี่ยนหน้า

ลงทะเบียนใน `DacApplication.run()` ต่อจาก `GovernanceResource`

**Frontend**
- `frontend/app/src/api/search.ts` (ใหม่) — type `SearchHit` / `SearchKind` · `hitHref(hit)` ตัดสินปลายทาง: asset → `/catalog/<fqn>` · column → หน้า table ที่มันอยู่ (`parentFqn`) · policy → `/policies/<id>` · vocabulary → `/catalog?facet=<facetType>:<fqn>` (**ไปที่ข้อมูลที่ติด value นั้น ไม่ใช่หน้าอธิบาย value**) · `KIND_GROUPS` คุมลำดับหัวข้อ
- `layout/TopNav.tsx` → `GlobalSearch` เขียนใหม่เป็น **type-ahead** : debounce 200ms, ขั้นต่ำ 2 ตัวอักษร, TanStack Query `staleTime` 30s, `placeholderData` ไว้กัน list กระพริบตอนพิมพ์ต่อ
  - ผลลัพธ์จัดกลุ่มตาม kind แต่เก็บ flat list คู่กันไว้ให้ **ลูกศรขึ้น/ลงเดินตามที่ตาเห็น** (วนรอบ) · Enter เปิดตัวที่เลือก · ถ้ายังไม่ได้เลือกอะไร Enter ยังพาไป `/catalog?q=` เหมือนเดิม · Esc ปิด
  - **ทิ้ง dropdown `SCOPES` (All/Tables/Views/…) ไปแล้ว** — ตอนนี้ผลลัพธ์ข้ามชนิดอยู่แล้ว การให้เลือก asset type ก่อนค้นขัดกับโจทย์ และหน้า Catalog ก็ยังมี filter นั้นของตัวเอง
  - spinner **แทนที่** ปุ่มแว่นขยาย ไม่ใช่โผล่ข้างๆ ไม่งั้นความกว้างช่องกระตุกทุกคีย์
  - แถวผลลัพธ์ผูก `onMouseDown` + `preventDefault()` ไม่ใช่ `onClick` — ไม่งั้น blur ของ input ปิด panel ทิ้งก่อนคลิกจะลง

### B. Transition ตอนเปลี่ยนหน้า

**โจทย์:** *"เวลาเปลี่ยน Tab ให้มี transition load ด้วย"* แล้วตามด้วย *"ตอนนี้มันแวบเร็วไป"*

**โจทย์เพิ่มรอบที่สาม:** *"ทำไมเวลาเปลี่ยนหน้า มันเปลี่ยนเลย แล้วค่อยมีแวบๆ Transition"* — เวอร์ชันแรกหน่วงไม่จริง เนื้อหาสลับทันทีแล้ว transition ค่อยเล่นตามหลัง (ดู What Didn't Work) · **เขียนใหม่ทั้งกลไก**

**กลไกที่ถูก — หน่วง "location" ไม่ใช่หน่วง "children"**

`frontend/app/src/layout/RouteTransition.tsx` (เขียนใหม่) แยกเป็นสามชิ้น:

| export | อยู่ที่ไหน | ทำอะไร |
|---|---|---|
| `useRouteLag()` | เรียกใน `App.tsx` **เหนือ `<Routes>`** | คืน `{ display, loading }` — `display` คือ location ที่ตามหลัง address bar อยู่ `HOLD_MS` |
| `RouteProgress` | `App.tsx` เหนือ `<Routes>` | บาร์บนสุด ขับด้วย location **จริง** → ขึ้นตั้งแต่วินาทีที่คลิก |
| `RouteTransition` (default) | ครอบ `<Outlet />` ใน `AppShell` | เหลือแค่ `<div className="arak-page-enter" key={pathname+search}>` เฉยๆ ไม่มี timer แล้ว |

`App.tsx` เปลี่ยนเป็น **`<Routes location={display}>`**

- **หัวใจ:** `<Outlet />` resolve จาก router context ตอน render → **เก็บ element เก่าไว้ก็ไม่ช่วย** element เดิมมันก็ render หน้าใหม่อยู่ดี · ทางเดียวที่จะค้างหน้าเก่าได้จริงคือ **ค้าง location ที่ใช้ render**
- `useRouteLag()` **ต้องเรียกเหนือ `<Routes location=...>`** เพราะ react-router จะห่อ `LocationContext.Provider` ใหม่ให้ทุกอย่างที่อยู่ข้างใน (ยืนยันจาก `useRoutesImpl` ใน `react-router@6.30.6`) ถ้าเรียกข้างในมันจะอ่านค่าที่ตัวเองผลิต แล้วค้างตายอยู่กับที่
- **ผลพลอยได้:** `useLocation()` ใน `AppShell` ก็เป็นตัวหน่วงด้วย → **highlight ของ nav ขยับพร้อมหน้าที่มาถึงจริง** ไม่ใช่ขยับก่อนเนื้อหา (แบบเดียวกับตอนโหลดหน้าเว็บจริง)
- **`HOLD_MS = 420`** — หน้าที่คลิกออกมาค้างไว้ 420ms ระหว่างบาร์วิ่ง แล้วหน้าใหม่ค่อย mount + `arak-page-enter` (320ms) · คลิกซ้ำระหว่างหน่วง → อ่าน `latest` ref ตอนครบเวลา จึงไปหน้าที่กด**ล่าสุด** ไม่ใช่หน้าที่เริ่มหน่วง
- บาร์อยู่ต่อหลังสลับ จนกว่า **`useIsFetching() === 0`** → เข้าหน้าที่ query ช้า 2 วิ บาร์ก็อยู่ 2 วิ ไม่ใช่ประกาศชัยชนะแล้วโชว์ตารางเปล่า · เพดาน `CEILING_MS = 4000` วัดจาก `startedAt` ref (ไม่ใช่ `setTimeout` ที่จะถูกรีทุกครั้งที่ `fetching` ขยับ)
- render แรกของแอปไม่มี transition — `display` ตั้งต้นเท่ากับ location จริงอยู่แล้ว ไม่ต้องมี `first` ref อีก
- progress bar บางๆ ติดขอบบน (`z-100`) วิ่งไปหยุดที่ **88%** ด้วย easing ที่โหลดหนักช่วงต้น (ตอบสนองทันทีว่า "กดติดแล้ว") แล้วคลานต่อ → พอเสร็จจึงวิ่งไป 100% แล้วค่อย fade · บาร์ที่ถึงปลายแล้วค้างคือบาร์ที่โกหก

### C. Loading ตอน Sign in / Sign out

**โจทย์:** *"เวลา Sign in Sign out ให้มี Loading ออกแบบให้หน่อย"* แล้วตามด้วย *"ตอน Sign in หน้ามันแวบๆ ก่อนไปเจอ loading screen อะ"*

`frontend/app/src/auth/AuthSplash.tsx` (ใหม่) — ม่านเต็มจอ: โลโก้เต้นเบาๆ + หัวข้อ + บรรทัดบอกว่ากำลังทำอะไร + แถบ sweep แบบ indeterminate (ไม่มีใครรู้เปอร์เซ็นต์ บาร์ที่เดาตัวเลขคือบาร์ที่โกหก) · ค้าง `AUTH_SPLASH_MS = 900`

- ขับด้วย zustand store เล็กๆ (`useAuthSplash`) เพราะสองปลายอยู่คนละที่: ฟอร์ม login ยกม่าน, เมนู account บน header ก็ยกม่าน, ส่วนตัวที่วาดต้องอยู่ **เหนือ `<Routes>`** ใน `App.tsx` ไม่งั้นมันจะถูก unmount โดย navigation ที่มันกำลังคลุมอยู่
- **Sign out** — ยกม่านก่อน แล้วค่อย `signOut()` หลัง 900ms · ถ้าล้าง session ก่อน เมนู/shell/หน้าทั้งหมด unmount พร้อมกันแล้วหน้า login มาถึงก่อนใครจะทันเห็นว่าเกิดอะไร
- **Sign in — บั๊กที่ผู้ใช้ชี้** รอบแรกยกม่าน *หลัง* `await signIn()` สำเร็จ (เหตุผลตอนนั้น: รหัสผิดจะได้ไม่เห็น "Signing you in" แล้วโดนดึงกลับ) · **ผลคือมีเฟรมหนึ่งที่ token มีแล้วแต่ม่านยังไม่ขึ้น** → `<Navigate>` ใน `LoginPage` ยิงทันที หน้า guarded layout วาดแลบผ่านช่องนั้น = อาการ "แวบๆ ก่อนเจอ loading screen"
  **แก้:** ยกม่าน **ตั้งแต่กด submit** · รหัสผิด → `splash.hide()` ทันทีแล้วโชว์ error บนฟอร์ม
- keyframes ทั้งหมดอยู่ใน `theme/overrides.css` (`arak-page-enter`, `arak-progress-creep`, `arak-progress-finish`, `arak-splash-*`) พร้อม `@media (prefers-reduced-motion: reduce)` ที่ **หยุดการเคลื่อนไหวแต่ยังคงตัวบ่งชี้ไว้**

### D. Copyright ในราง — ชิดซ้าย

**โจทย์:** *"© 2026 MFEC / All rights reserved จัดวางไปสวย ชิดซ้านได้ไหม"* → `AppShell.tsx` เปลี่ยนจาก `text-center` เป็นชิดซ้ายบน gutter `px-3.5` เดียวกับ label ของ nav ด้านบน เส้นคั่นเยื้องเข้า `mx-3.5` ให้ตรงกัน → รางอ่านเป็นคอลัมน์เดียว

### E. ชิป governance บนแถว Catalog — อ่านไม่ออก

**โจทย์:** *"ตรงนี้ไม่สวยเลย ดูลำบาก"* (แนบสองรูป) — แถว filter พิมพ์ FQN ของ domain ยาว 110 ตัวอักษรจนตัดบรรทัด และการ์ด asset หนึ่งใบมี **11 ชิป** ที่พูดเรื่องเดียวกันซ้ำๆ (`PII ↑`, `Tier ↑`, domain สามชั้น, `PII.Sensitive`, `Tier.Tier2`, `Tier2`, …)

อาการเดียวแต่มาจาก **สามสาเหตุ** จึงแก้แยกกันสามที่:

| สาเหตุ | แก้ที่ไหน |
|---|---|
| พิมพ์ FQN เต็ม | `frontend/app/src/lib/fqn.ts` (ใหม่) — `shortFqn()` |
| `asset_facet` กาง ancestor ทั้งสายไว้ (ตั้งใจตาม FR-2A.2) | `facets.tsx` → `listFacets()` ตัด facet ที่เป็น **ancestor แท้** ของอีกตัวในชนิดเดียวกัน |
| facet บางชนิดพูดซ้ำกับส่วนอื่นของแถว | `OFF_THE_LIST = ['classifications', 'tier']` |

- **`src/lib/fqn.ts`** — มิเรอร์ `Fqns.java` ฝั่ง backend: `segments()` แยก segment แบบรู้จัก `"quoted.segment"`, `leaf()`, `isAncestor()` (เทียบ **ทีละ segment** ไม่ใช่ prefix ของ string — `Finance` ไม่ใช่ ancestor ของ `Finance Ops` และไม่ใช่ ancestor ของตัวเอง)
- **`shortFqn()` — กติกาคือ "งบของ parent" (`PARENT_BUDGET = 18`)** ไม่ใช่ "เอาสอง segment ท้าย" · `PII.Sensitive` → `PII / Sensitive` · `Finance.Risk.Credit` → `… / Risk / Credit` · sub-domain ของจริงที่ parent ยาว 45 ตัวอักษร → `… / Premium Service Delivery - IOS Data/DTP - Sub Domain` · **ตัด parent ทิ้งเสมอไม่ได้** เพราะ `PII.Sensitive` กับ `MFEC-PDPA.Sentitive` จะเหลือหน้าตาเหมือนกันเป๊ะ
- **ตัด ancestor ออกจากแถว** — `asset_facet` ตั้งใจกางทั้งสายเพื่อให้ selector เป็น index lookup (FR-2A.2) แต่แถวที่พิมพ์ออกมาทุกข้อต่อคือ 11 ชิปที่มีความหมายจริง 4 ชิป · ตัวที่ลึกสุด implies ตัวบน และ tooltip บอก FQN เต็มอยู่แล้ว · **หน้า asset detail ยังโชว์ครบทุกตัวเหมือนเดิม** — ที่นั่นความครบคือประเด็น
- **ชิปถูก truncate ไม่ใช่ wrap** — `Badge` ของ design system เป็น `size-max whitespace-nowrap` → ใส่ `tw:max-w-72` (ชิปบนการ์ด) / `tw:max-w-80` (ชิป filter) แล้วครอบข้อความด้วย `<span className="tw:truncate">` · ชิปที่ยืดตาม FQN ร้อยตัวอักษรจะลากทั้งแถวไปด้วย
- ทุกชิปมี `title` เป็น FQN เต็ม + ที่มา (direct/inherited from …, labelType, state) → ข้อมูลไม่ได้หายไปไหน แค่ไม่ต้องอ่านทั้งหมดพร้อมกัน

**เทสต์ใหม่:** `src/lib/fqn.test.ts` (3) + `src/pages/catalog/facets.test.ts` (2) · รวมฝั่ง FE เป็น **7 suites / 25 tests ผ่านหมด** · ยืนยันด้วยภาพจาก Playwright แล้ว: การ์ด `dtp-iprm` เหลือ 4 ชิป และไม่มีชิปไหนตัดบรรทัดอีก

---


## รอบก่อนหน้านั้น — App shell แบบ OpenMetadata, หน้า Settings, และบั๊กสองตัวที่ผู้ใช้ชี้

> รอบนี้เป็นงาน **UI shell + integration hardening** เกือบทั้งหมด milestone ไม่ขยับเป็นเปอร์เซ็นต์ใหญ่
> แต่ปิดบั๊กที่กระทบ **ทุก endpoint** ไปหนึ่งตัว (Jackson เขียนวันที่เป็น epoch) และเพิ่มหน้า Settings ของ FR-1.1

### A. Top navigation bar (`frontend/app/src/layout/TopNav.tsx` — เขียนใหม่ทั้งไฟล์)

เลียน header ของ OM 2.0.1 ตามภาพที่ผู้ใช้ส่งมา เรียงจากซ้าย:

| ส่วน | รายละเอียด |
|---|---|
| **`Brand`** | โลโก้ + `ARAK` / `Data access control` — **ย้ายจากรางซ้ายมาอยู่บนซ้ายสุดของ header** ตามที่ผู้ใช้สั่ง · กว้าง `tw:lg:w-66` = **เท่ากับความกว้างของรางพอดี** เพื่อให้ปุ่มพับตกอยู่ถัดจากขอบรางเป๊ะ · **ตอนพับหดเหลือ `tw:lg:w-14` (= ราง `w-18` ลบ padding ของ header) และซ่อนตัวหนังสือ เหลือแต่ mark** ด้วย transition 400ms ชุดเดียวกับราง -> โลโก้กับปุ่มพับขยับไปทางซ้ายพร้อมกัน แบบ OM · mark `size-10`, ชื่อ `text-lg` |
| ปุ่มพับราง | `LayoutLeft` ใน **`Tooltip` ของ design system** ("Collapse" / "Expand", `placement="right"`, มีลูกศร) |
| **`GlobalSearch`** | pill `h-10` + scope dropdown ในตัว (All / Tables / Views / Schemas / Databases / Services) + ปุ่ม `SearchLg` · submit → `/catalog?q=&type=` · โฟกัสด้วย `/` หรือ Cmd/Ctrl-K |
| **`DomainPicker`** | `Globe01` + รายชื่อ domain กาง sub-domain (indent ตาม depth, addon = จำนวน asset) → `/catalog?facet=domains:<fqn>` · **ไม่ render เลยถ้าไม่มี domain** |
| **`CreateMenu`** | Subscription / Data policy (`?kind=`) · Register a source |
| **`Notifications`** | `Bell01` — **แทน chip "Synced ..." ที่ผู้ใช้สั่งให้เอาออก** · จุดแดงขึ้นเฉพาะตอนมี alert จริง (crawl ล่าสุด `FAILED` หรือยัง `NEVER_RUN`) · admin เท่านั้น เพราะ endpoint เป็น admin |
| **`HelpMenu`** | `HelpCircle` — ทางลัด + ประโยคอธิบาย "strictest wins" |
| **`AccountMenu`** | avatar + ชื่อ + role · System status / Sources / Sign out |

### B. รางซ้าย (`frontend/app/src/layout/AppShell.tsx` — เขียนใหม่ทั้งไฟล์)

- **เลิกใช้ `NavList` ของ design system** แล้วเขียน `NavItem` เอง เพราะ `NavItemBase` **ประกาศ prop `iconOnly` ไว้แต่ไม่ได้ใช้จริงสักบรรทัด** ทำ icon-only rail ไม่ได้
- ใช้ **`NavLink` ของ react-router** (ไม่ใช่ `AriaLink`) — ได้ `aria-current="page"` มาฟรีและไม่ reload หน้า
- **พับแล้วย่อเป็นรางไอคอน `w-18` ไม่ใช่หายไป** ตามภาพ OM ที่ผู้ใช้ส่ง · ความกว้าง transition **400ms ease-in-out** (ผู้ใช้ขอให้ช้ากว่าเดิม) · จำสถานะไว้ใน `localStorage['arak.sidebar.collapsed']`
- ตอนพับ: ซ่อน label + badge, ใส่ `title` และ `aria-label` แทน
- `isCurrent()` match แบบ prefix ของ segment แรก (`/catalog/asset/x` -> Catalog ยังติด) แต่ `/` ต้องตรงเป๊ะ
- **แท็บที่เลือกอยู่เป็น pill สีแบรนด์ทึบ ตัวอักษรกับไอคอนขาว** (`tw:bg-brand-solid` / `tw:hover:bg-brand-solid_hover`) แบบเดียวกับ OM
- แถวเมนู `h-11` ไอคอน `size-6` (ผู้ใช้ขอให้ใหญ่ขึ้น) · ปุ่มพับสลับไอคอน **`LayoutLeft` ตอนกาง / `LayoutRight` ตอนพับ** ให้รู้ว่ากดแล้วจะเกิดอะไร
- ท้ายราง: เส้นคั่น + **`© 2026 MFEC — All rights reserved`** (แทนคำอธิบาย badge เดิม) · ซ่อนตอนพับ

> ⚠️ เคยลองทำ highlight นี้เป็น rule ใน `theme/overrides.css` ที่ผูกกับ `a[aria-current="page"]` **แล้วถอดออก** — ผู้ใช้ยังมองไม่เห็นความต่าง การประกาศสีไว้บน element ตรงๆ ตรวจสอบได้จาก DOM และไม่ต้องเดาว่า layer ไหนชนะ

### C. 🐛 Jackson เขียน `Instant` เป็น epoch — บั๊กที่กระทบทุก endpoint

อาการที่ผู้ใช้เห็น: **"Synced 20695d ago"** · ของจริงที่ API คืนคือ `"lastFullCrawlAt": 1789832026.308722`

- `environment.getObjectMapper().disable(WRITE_DATES_AS_TIMESTAMPS)` **ไม่มีผล**
- ใส่ที่ `bootstrap.getObjectMapper()` ด้วย **ก็ยังไม่มีผล**
- log ออกมาว่า flag เป็น `false` แล้ว แต่ response ยังเป็น epoch -> **Jersey ใช้ mapper คนละตัว**
- **สาเหตุ: message body writer ของ Jersey resolve ObjectMapper ผ่าน `ContextResolver<ObjectMapper>` ก่อนเสมอ** ถ้าไม่มี มันสร้าง default instance ของมันเอง
- แก้ด้วย `backend/dac-service/src/main/java/com/mfec/dac/json/JsonMapperProvider.java` (`@Provider implements ContextResolver<ObjectMapper>`) แล้ว register เข้า Jersey ใน `DacApplication.run()`
- ยืนยันแล้ว: `{"lastFullCrawlAt":"2026-09-19T15:33:46.308722Z", ...}` — **ทุก resource ได้ ISO-8601 ตามไปหมด ไม่ต้องไล่ format ทีละที่**

### D. 🐛 description ของ OM โผล่มาเป็น HTML ดิบ

ผู้ใช้เห็น `<p>Premium Service Delivery Domain</p>` บนหน้าจอ — OM 2.0 เก็บ description เป็น HTML และ React escape ให้

- เพิ่ม `frontend/app/src/lib/text.ts` -> `plainText()` แปลง block tag กับ `<br>` เป็นช่องว่าง, ตัด tag ที่เหลือ, decode entity (`&amp;`, `&#39;`, `&#x2F;`, ...), ยุบ whitespace
- ใช้ทุกจุดที่ render description: `AssetDetailPage` (asset + column), `CatalogPage` (การ์ด), `GovernancePage` (value + property)
- **จงใจไม่ใช้ `dangerouslySetInnerHTML` และไม่ลง sanitizer** — description ใครก็แก้ได้จาก catalog ถ้า render เป็น HTML ทุก description จะกลายเป็นที่วาง script · แถม `line-clamp` ตัดข้อความธรรมดาได้ตรงกว่า

### E. หน้า Settings — OpenMetadata connection (FR-1.1)

| ชิ้น | ไฟล์ |
|---|---|
| Endpoint | `backend/dac-service/src/main/java/com/mfec/dac/resources/OpenMetadataSettingsResource.java` — `GET /v1/settings/openmetadata`, `POST /v1/settings/openmetadata/test` · `@Secured("PLATFORM_ADMIN")` |
| API client | `frontend/app/src/api/system.ts` — `fetchOpenMetadataSettings()`, `testOpenMetadata()` |
| หน้าจอ | `frontend/app/src/pages/settings/OpenMetadataSettingsPage.tsx` |
| Route / nav | `/settings/openmetadata` (+ `/settings` redirect) ใน `App.tsx` · เมนู **Settings** ใน `navigation.ts` |

**อ่านอย่างเดียวโดยตั้งใจ และบอกไว้ใน payload เลยว่า `editable: false`** ไม่ใช่โชว์ปุ่มที่กดไม่ได้
`OM_JWT_TOKEN` / `OM_WEBHOOK_SECRET` มาจาก environment เท่านั้น · endpoint คืนแค่ **boolean ว่ามีไหม** ไม่คืนค่า ไม่คืน prefix ไม่คืนความยาว (บอกรูปร่าง token คือบอก token)
probe แยกเป็น `POST` ต่างหาก เปิดหน้าจะได้ไม่ค้างรอ host ที่ต่อไม่ติด · ทดสอบกับ instance จริงแล้ว: `{"reachable":true,"version":"2.0.1","versionMatches":true,"tookMs":25,"message":"Connected."}`

---

## รอบเก่ากว่านั้น — M3/M4

### 1. Backend — policy persistence + binding (ปิดช่องว่างใหญ่ที่สุดของ M3)

ก่อนหน้านี้ engine evaluate ได้แต่ **ไม่มีที่เก็บ policy** — policy มีอยู่แค่ใน unit test

| ไฟล์ | หน้าที่ |
|---|---|
| `backend/dac-service/.../policy/PolicyStore.java` | **ใหม่** — CRUD + lifecycle + versioning บน `policy` / `policy_version` |
| `backend/dac-service/.../policy/AssetContextLoader.java` | **ใหม่** — โหลด asset + facet + column facet + custom property เป็น `AssetContext` ที่ engine กิน · ใช้ร่วมกันทั้ง materializer และ (ต่อไป) decision path |
| `backend/dac-service/.../policy/PolicyBindingMaterializer.java` | **ใหม่** — resolve selector → `policy_binding` (FR-3.1.6) · `materialize(id)` / `materializeAll()` / `refresh(fqns)` |
| `backend/dac-service/.../resources/PolicyResource.java` | **ใหม่** — `@Path("/v1/policies")` |
| `backend/dac-service/src/test/.../policy/PolicyStoreIT.java` | **ใหม่** — 10 tests บน Postgres จริง |

การตัดสินใจที่สำคัญ:
- **ทุก write append เข้า `policy_version` ก่อนแล้วค่อยแก้ `policy`** — version history ไม่ขึ้นกับว่าใครจำได้ว่าต้องเขียน log
- **optimistic locking ด้วย `expectedVersion`** — UI ส่ง version ที่เปิดฟอร์มมา ถ้าไม่ตรง = `StaleVersionException` → 409 · **ไม่ใช่ last-write-wins** เพราะ policy ที่ถูกเขียนทับเงียบๆ = ข้อจำกัดหายไปโดยไม่มีใครรู้
- **เช็ค `ARCHIVED` ก่อนเช็ค version** — archived policy ถูกปฏิเสธไม่ว่าถืออยู่ version ไหน และข้อความ "reload แล้วลองใหม่" จะผิดถ้าตอบ version conflict
- **`transition()` bump version ด้วย** — การ activate คือการเปลี่ยนความหมายของระบบ คนที่ถือ v3 อยู่ระหว่างที่อีกคน activate ต้องกลับไปดูก่อน
- transition ที่ถูกกฎหมาย: `DRAFT → {PENDING_APPROVAL, ACTIVE, ARCHIVED}` · `PENDING_APPROVAL → {ACTIVE, DRAFT, ARCHIVED}` · `ACTIVE → {DISABLED, ARCHIVED}` · `DISABLED → {ACTIVE, ARCHIVED}` · **`ARCHIVED` เป็น terminal**
- **materializer รับ `AssetContextLoader` เข้ามา ไม่สร้างเอง** — webhook path กับ authoring path จะได้อ่าน facet ด้วย code ชุดเดียวกัน ไม่มีทาง drift
- `refresh(fqns)` แตะเฉพาะ asset ที่ระบุ → webhook เปลี่ยน tag 1 ตาราง ไม่ต้อง re-resolve ทั้ง estate

### 2. Backend — governance + identity read API (ของที่ UI ฝั่ง policy ต้องใช้)

| ไฟล์ | หน้าที่ |
|---|---|
| `backend/dac-service/.../catalog/GovernanceQuery.java` | **ใหม่** — อ่าน classification/tag, glossary/term, domain/sub-domain, data product, custom property def เป็น **tree** พร้อมยอด asset (รวม + direct) และยอด policy ที่อ้างถึงค่านั้น |
| `backend/dac-service/.../resources/GovernanceResource.java` | **ใหม่** — `@Path("/v1/governance")` |
| `backend/dac-service/.../identity/PrincipalQuery.java` | **ใหม่** — list principal, detail, **`attributeKeys()`** (key + จำนวนคนที่ถือ + ค่าที่มีจริง) |
| `backend/dac-service/.../resources/PrincipalResource.java` | **ใหม่** — `@Path("/v1/principals")` |
| `backend/dac-service/.../catalog/GovernanceStore.java` | **แก้** — `customProperties()` ตอน snapshot ว่าง เดิม `return` เฉยๆ ทำให้ definition ที่ถูกลบใน OM ค้างอยู่ตลอดกาล → เพิ่ม `DELETE FROM custom_property_def` ให้เหมือนตารางอื่น (ไม่งั้น builder เสนอ ABAC attribute ที่ไม่มี asset ไหนถืออยู่) |
| `backend/dac-service/src/test/.../catalog/GovernanceStoreIT.java` | **ใหม่** — 9 tests (เป็นตัวที่จับ bug ข้างบน) |
| `backend/dac-service/.../DacApplication.java` | register `PolicyResource`, `GovernanceResource`, `PrincipalResource` |

**ตัวเลขคู่ที่เป็นเหตุผลทั้งหมดของหน้า Governance:** OM แสดง tag/term/domain อยู่แล้ว สิ่งที่มันตอบไม่ได้คือ "ค่านี้แตะ asset กี่ตัวในระบบเรา และมี policy กี่ตัวพึ่งอยู่" — สองเลขนี้คือตัวบอกว่า tag ตัวไหน load-bearing และการลบมันจะปลดการป้องกันอะไรไปบ้าง

### 3. Endpoint ที่เพิ่มรอบนี้ (อยู่ใต้ `/api` ทั้งหมดเพราะ `rootPath: /api/*`)

| Method | Path | หมายเหตุ |
|---|---|---|
| GET | `/api/v1/policies` | `state`, `type`, `scopeLevel`, `limit`, `offset` |
| GET | `/api/v1/policies/{id}` | |
| GET | `/api/v1/policies/{id}/versions` | ประวัติจาก `policy_version` |
| GET | `/api/v1/policies/affecting/{fqn: .+}` | policy ที่ ACTIVE และผูกกับ asset นี้ (ฐานของ FR-3.1.5) |
| POST | `/api/v1/policies` | สร้าง (DRAFT) |
| PUT | `/api/v1/policies/{id}` | ต้องส่ง `expectedVersion` · mismatch = **409** |
| POST | `/api/v1/policies/{id}/lifecycle` | body `{"to":"ACTIVE"}` |
| POST | `/api/v1/policies/{id}/bindings/resolve` | re-resolve selector → คืน `{scanned, matched, added, removed}` |
| GET | `/api/v1/governance/vocabulary` | ก้อนเดียวจบ: classifications + glossaries + domains + dataProducts + customProperties |
| GET | `/api/v1/governance/{classifications,glossaries,domains,data-products,custom-properties}` | แยกชิ้น |
| GET | `/api/v1/principals` | `type`, `source`, `search`, `limit` |
| GET | `/api/v1/principals/attributes` | **vocabulary ของ attribute** — key, source, จำนวนคน, ค่าที่มีจริง |
| GET | `/api/v1/principals/{username}` | |

### 4. Frontend — Policy Builder + Governance + People

| ไฟล์ | หน้าที่ |
|---|---|
| `frontend/app/src/api/policies.ts` | **ใหม่** — fetch/create/update/transition/resolveBindings + re-export `Policy` จาก generated IR |
| `frontend/app/src/api/governance.ts` | **ใหม่** — vocabulary, principals, attribute vocabulary, `flatten()` |
| `frontend/app/src/pages/policies/controls.tsx` | **ใหม่** — `Select` / `TextField` / ปุ่มเล็ก ที่ใช้ `<select>`/`<input>` ดิบ + token class ร่วม (convention ของเรโป) |
| `frontend/app/src/pages/policies/policyLanguage.ts` | **ใหม่** — readback: แปลง policy document เป็นประโยคภาษาคน |
| `frontend/app/src/pages/policies/enforcement.ts` | **ใหม่** — **capability matrix (FR-6.0b)** ต่อโหมด × engine |
| `frontend/app/src/pages/policies/SelectorBuilder.tsx` | **ใหม่** — "ผูกกับ asset ไหน" (facet + operator + value, and/or/not) |
| `frontend/app/src/pages/policies/SubjectBuilder.tsx` | **ใหม่** — "ใคร" — principal OR-list + attribute AND-list + `expr` + time window + context |
| `frontend/app/src/pages/policies/DataPolicyBuilder.tsx` | **ใหม่** — RLS (5 kinds) + column rule (MASK/HIDE/ALLOW) + masking function 6 ตัว + cell condition |
| `frontend/app/src/pages/policies/PolicyListPage.tsx` | **ใหม่** — `/policies` · ทุกแถวมี readback ของตัวเอง |
| `frontend/app/src/pages/policies/PolicyBuilderPage.tsx` | **ใหม่** — `/policies/new`, `/policies/:id` · ฟอร์มซ้าย + rail ขวา |
| `frontend/app/src/pages/policies/policyLanguage.test.ts` | **ใหม่** — 6 tests คุมสองความผิดพลาดที่อันตรายที่สุด (ดูด้านล่าง) |
| `frontend/app/src/pages/governance/GovernancePage.tsx` | **ใหม่** — `/governance` · 4 แท็บ tree |
| `frontend/app/src/pages/governance/PrincipalsPage.tsx` | **ใหม่** — `/principals` · read-only |
| `frontend/app/src/App.tsx` | route `/governance`, `/principals`, `/policies`, `/policies/new`, `/policies/:id` |
| `frontend/app/src/layout/navigation.ts` | เพิ่มหมวด **Governance** + **People** · `Policies` เลิกเป็น placeholder (`milestone: 'M3'` → `null`) |

**การตัดสินใจที่สำคัญของ Policy Builder:**
- **ฟอร์มอยู่ซ้าย ความหมายของฟอร์มอยู่ขวา และเห็นตลอดเวลา** — rail ขวาอ่าน policy กลับมาเป็นประโยค, บอกว่าจะแตะ asset กี่ตัว, และบอกว่า **โหมดไหนใน 3 โหมดแบกมันไหว** — *ก่อน* กด apply ไม่ใช่หลัง
- **ไม่แยกแท็บ RBAC / ABAC / rule / time** — comment ในไฟล์เขียนไว้ตรงๆ ว่ามันคือ predicate เดียวกันมองคนละมุม ทุกบล็อกถูก AND เข้าด้วยกัน การแยกแท็บจะสื่อว่าเป็น 4 ระบบ
- **selector ว่าง = save ไม่ได้** — `hasCondition()` กันไว้ที่ปุ่ม เพราะ policy ที่ผูกกับศูนย์ asset จะขึ้นเป็น ACTIVE ในลิสต์ทั้งที่ไม่ทำอะไรเลย
- **เปลี่ยน action จาก MASK เป็น HIDE/ALLOW แล้ว `masking` ถูกทิ้ง** — document ที่พูดสองเรื่องพร้อมกัน compiler ต้องเลือกเอง และคนอ่านไม่มีทางรู้ว่ามันเลือกอันไหน
- **`describeSelector(ว่าง)` ต้องคืน `'nothing'` ห้ามคืน `'everything'`** — เป็น mistranslation ที่อันตรายที่สุดที่หน้านี้ทำได้ → มี unit test คุมไว้
- rail แสดง **PostgreSQL / SQL Server สลับได้** เพราะข้อจำกัดต่างกันจริง (DDM ของ MSSQL เป็น on/off ต่อ column · PG ไม่มี column masking ใน core)

**หน้า Governance:** filter tree แบบ **เก็บ ancestor ของทุก match** (ตัด node ที่ไม่ match ทิ้งดื้อๆ = ซ่อน parent ของ match ซึ่งคือสิ่งเดียวที่ต้องเห็นใน hierarchy)

**หน้า People:** อ่านอย่างเดียวโดยตั้งใจ — Entra กับ OM เป็นเจ้าของเนื้อหา sync รอบหน้าจะทับทุกอย่างที่พิมพ์ที่นี่ · ส่วนที่มีค่าที่สุดคือ **รายการ attribute key + จำนวนคนที่ถือจริง** เพราะ condition ที่อ้าง attribute ที่ไม่มีใครถือ = deny ทุกคน เงียบๆ และถูกต้องตาม logic ซึ่งเป็นความผิดพลาดที่มองเห็นยากที่สุดหลังจากนั้น

---

### 5. `PolicyBindingMaterializerIT` — 10 tests บน Postgres จริง

ไฟล์: `backend/dac-service/src/test/java/com/mfec/dac/policy/PolicyBindingMaterializerIT.java`

fixture คือ crawl เต็มรอบผ่าน `AssetStore` (ไม่ใช่ INSERT มือ) — เพราะ "tag ถูกถอดใน OM" กับ "crawl รอบใหม่ไม่มี tag นั้น" ต้องเป็นเหตุการณ์เดียวกัน
estate ที่ใช้: `prod-mssql.SalesDB.dbo.{customer, order}` + **`prod-mssql.SalesDBArchive.dbo.customer`** ที่ติด tag เหมือนกันเป๊ะ

| test | คุมอะไร |
|---|---|
| `bindsMatchingAssets` | ORG policy → `scanned 3 / matched 2 / added 2` และไม่ผูก table ที่ไม่มี tag |
| `bindingsAreJoinedToTheAsset` | แถว binding มี `asset_id` จริง ไม่ใช่แค่ FQN — FR-3.1.5 จะได้ไม่ต้อง match string ที่ rename แล้วพัง |
| `scopeIsComparedBySegment` | **กับดักหลัก** — scope `…SalesDB` ต้องเห็นแค่ 2 ตาราง และ **ไม่ลาก `SalesDBArchive`** (archive ติด tag เดียวกัน ตัวกันมีแค่ prefix guard) |
| `columnRulesBindColumns` | data policy ผูกถึง column + `match_reason` บอก `columnRule: 0` และ `MASK` |
| `resolvedAtSurvivesReResolve` | re-resolve ที่ไม่มีอะไรเปลี่ยน → `changed() == false` และ **`resolved_at` ไม่ขยับ** (เขียนเป็น diff ไม่ใช่ rebuild) |
| `unbindsWhenTagRemoved` | ถอด tag → crawl ใหม่ → `removed 1`, binding หายจริง |
| `newlyTaggedAssetIsCoveredOnRefresh` | ติด tag ให้ `order` แล้ว `refresh([order])` → ถูกคุ้มครองเองโดยไม่มีใครกดอะไร (FR-3.1.6) และ `resolved_at` ของ `customer` ไม่ถูกแตะ |
| `refreshOnlyTouchesNamedAssets` | `customer` เสีย tag แต่ refresh บอกแค่ `order` → **`customer` ต้องยังผูกอยู่** · ถ้าลบสิ่งที่ไม่ได้ evaluate = webhook ใบเดียวปลด policy ทั้ง estate |
| `refreshSkipsPoliciesOutOfScope` | refresh asset นอก scope → `scanned 0`, ไม่มีอะไรเปลี่ยน |
| `materializeAllCoversEveryPolicy` | nightly reconcile ครอบ policy ทุกตัว (รวม `DRAFT`) และแต่ละตัวได้ binding ตาม scope ของตัวเอง |

---

## What Worked

- **re-read by FQN แทนการ replay payload ของ event** — apply ซ้ำฟรี → poller rewind cursor ได้ และ webhook รับ redelivery ได้
- **`AssetRefresher` ใช้ descent ของ `AssetCrawler` กลางทาง** แทนเขียน inheritance ซ้ำ
- **แยก 404 ออกจาก error อื่น** — 404 = race ปกติ (ข้าม) · error อื่น = failed (cursor ไม่ขยับ)
- **retire ด้วย prefix ที่ผูกจุดและ escape LIKE** — กันลบ `prod.Sales` แล้วลาก `prod.SalesArchive` ไปด้วย
- **webhook ไม่ใส่ `@Secured` + ใช้ HMAC · unconfigured = 503**
- **แยก read query ออกจาก write store** (`CatalogQuery`/`GovernanceQuery`/`PrincipalQuery` vs `AssetStore`/`GovernanceStore`) — คนละ query shape คนละเหตุผลในการเปลี่ยน
- **เก็บ filter state ไว้ใน URL** ทั้ง `/catalog`, `/policies`, `/governance`
- **`AssetContextLoader` ตัวเดียวใช้ทั้ง materializer และ decision path** — ส่งเข้าไปทาง constructor ไม่ให้ใครสร้างเอง
- **append `policy_version` ก่อนแก้ `policy` เสมอ** — history ไม่ขึ้นกับความจำของคนเขียน code
- **unit test ที่เลือกคุมเฉพาะ failure mode ที่อันตราย** ไม่ใช่คุม output ทุกบรรทัด — `describeSelector(ว่าง) === 'nothing'` และ capability gap ของ cell masking
- เขียนไฟล์ใหญ่ด้วย **Write tool หรือ python heredoc** เชื่อถือได้

## What Didn't Work

### Build / รันระบบ
- ❌ **Bash heredoc เขียนไฟล์ใหญ่** — พังซ้ำอีกรอบนี้ (`unexpected EOF while looking for matching '` ตอนเขียน `DataPolicyBuilder.tsx`) ทั้งที่ใช้ `<<'TSX'` → **ใช้ Write tool หรือ python patch script เท่านั้น**
- ❌ **`-DfailIfNoSpecifiedTests=false` อย่างเดียว** — ต้องมี **ทั้งสองตัว**: `-Dsurefire.failIfNoSpecifiedTests=false` **และ** `-Dfailsafe.failIfNoSpecifiedTests=false` ไม่งั้นรัน IT ตัวเดียวแล้ว build แดง
- ❌ **`./mvnw` โดยไม่มี `-am`** — module ต้นน้ำไม่ถูก build
- ❌ **curl ไปที่ `/v1/...` ตรงๆ** — 404/405 เพราะ `rootPath: /api/*` → ต้อง `/api/v1/...`
- ❌ **ลืม restart backend หลัง build** — โปรเซสเก่ายังถือ jar เดิม
- ❌ **`pkill java` บน Windows** — ไม่มี `pkill` ใน Git Bash ของเครื่องนี้ → ใช้ PowerShell `Get-CimInstance Win32_Process` กรอง `CommandLine` แล้ว `Stop-Process`
- ❌ **Vite พอร์ต 3000** — `listen EACCES` บนเครื่องนี้ (`vite.config.ts` ยัง default 3000 อยู่) ต้องส่ง `--port 5274`
- ❌ **`.env` ไม่ถูกโหลดเอง** — ต้อง `set -a && . ./.env && set +a` ก่อนรัน
- ❌ **`sleep 25 && tail`** ถูก harness บล็อก → ใช้ `until <check>; do sleep 2; done`
- ❌ ผลการรัน IT อ่านจาก `backend/dac-service/target/failsafe-reports/*.txt` **ไม่ใช่** `surefire-reports`
- ❌ **`git push origin main` ค้าง — หาเจอสาเหตุแล้ว** `credential.helper = manager` (Git Credential Manager) ถูกตั้งไว้ระดับ repo · GCM จะเปิดหน้าต่าง GUI ขอ login ซึ่ง session แบบ non-interactive กดไม่ได้ → push แขวนไปจน timeout (`GIT_TERMINAL_PROMPT=0` ไม่ช่วย เพราะมันกันแค่ prompt บน terminal) · `git ls-remote origin` ตอบปกติเพราะ repo เป็น public อ่านได้โดยไม่ต้อง auth · `gh` **ไม่ได้ติดตั้งบนเครื่องนี้** → **ผู้ใช้ต้อง `git push origin main` เองใน terminal ของตัวเองหนึ่งครั้ง** (หรือตั้ง PAT ไว้) · หลัง push ยืนยันด้วย `git ls-remote --heads origin` เสมอ อย่าเชื่อว่าสำเร็จเพราะคำสั่งไม่ error

### API / integration
- ❌ **ตั้งค่า `environment.getObjectMapper()` แล้วคิดว่า response จะเปลี่ยนตาม** — **ไม่เปลี่ยน** · Jersey resolve ObjectMapper ผ่าน **`ContextResolver<ObjectMapper>`** ก่อน ถ้าไม่มีก็ใช้ default ของมันเอง -> ต้อง register `@Provider` เอง (`json/JsonMapperProvider.java`) · วิธีพิสูจน์ที่เร็วที่สุดคือ log ค่า flag เทียบกับ response จริง ถ้า flag ถูกแต่ response ผิด แปลว่าคนละ instance
- ❌ **คิดว่า description จาก OM เป็น plain text** — มันเป็น **HTML** (`<p>...</p>`) ตั้งแต่ 2.0 -> ต้องผ่าน `lib/text.ts::plainText()` ก่อน render เสมอ
- ❌ **login แล้วอ่าน `token`** — field ที่ backend คืนคือ **`accessToken`** ไม่ใช่ `token`
- ❌ **ใช้ account คนจริงของ OpenMetadata เป็น connector credential** — เปลี่ยนไปใช้ **ingestion-bot JWT** แล้ว (mint ครั้งเดียว เก็บใน `.env`) · password ของ account คนจริงที่เคยวางในแชต **ผู้ใช้ควร rotate**
- ✅ **full sync กับ OM จริงสำเร็จ**: ~9 วินาที · **33 tables · 352 columns · 4,302 แถวใน `asset_facet`**
- ⚠️ **glossary facet ว่างเปล่า** — ไม่ใช่บั๊ก: OM instance นี้ยังไม่มีใครติด glossary term ให้ asset เลย (มีแต่ตัว glossary) → อย่าไปไล่หาสาเหตุใน mapper
- ❌ **`TRUNCATE asset, …` ใน IT** — `cannot truncate a table referenced in a foreign key constraint` เพราะ `policy_binding` (V3) / `enforcement_state` (V4) อ้าง `asset(id)` · แก้โดย **ไล่ชื่อตารางให้ครบ ไม่ใช้ `CASCADE`** — ตารางใหม่จะได้ fail ดังๆ ไม่ใช่ถูกล้างเงียบๆ
- ❌ **`assertThat(jdbi.withHandle(…))` → ambiguous** — ดึงออกมาเป็น local ที่ type ชัดก่อน

### Frontend
- ❌ **`apiErrorMessage(error)`** — signature คือ **`apiErrorMessage(error: unknown, fallback: string)` สองอาร์กิวเมนต์** ลืมตัวที่สอง = compile error (เจอ 4 จุดรอบนี้)
- ❌ **`<Button href="/policies/new">`** — design-system `Button` จะ render เป็น `AriaLink` = **full page reload กลางๆ SPA** → ใช้ `useNavigate()` + `onPress` แทน
- ❌ **`onClick` บน `Button` ของ design system** — ต้อง **`onPress`** และ **`isDisabled`** ไม่ใช่ `disabled` (`<button>` ธรรมดายังใช้ `onClick` ตามปกติ)
- ❌ **ใช้ชื่อแท็บเป็น facet type ตอนลิงก์ไป catalog** — `facet_type` จริงคือ `tags | classifications | terms | glossaries | domains | dataProducts` · root ใต้ Classifications เป็น `classifications` แต่ลูกของมันเป็น `tags` → ถ้าส่งชื่อแท็บไปทั้งก้อน แถว tag จะลิงก์ไปที่ filter ที่ match ศูนย์แถว · แก้ด้วย `facetOf(tab, value)`
- ❌ **`NavItemBase.iconOnly` ใช้ไม่ได้** — prop ประกาศไว้ใน interface แต่ **ไม่ถูก destructure หรือใช้ใน body เลย** ทั้งสาม branch -> จะทำ icon rail ต้องเขียน nav item เอง อย่าส่ง prop นี้แล้วรอให้มันทำงาน
- ❌ **สไตล์ active state โดยแก้ vendored component** — `scripts/sync-om-design-system.sh` จะทับกลับ · และการเขียนเป็น rule ใน `theme/overrides.css` ก็พิสูจน์แล้วว่าตามยาก -> **ประกาศ class สีไว้บน element ใน component ของเราเอง**
- ❌ **`Tooltip` ครอบ `<button>` ธรรมดา** — ไม่ขึ้น · `TooltipTrigger` ของ react-aria ส่ง hover/focus handler ให้เฉพาะ trigger ที่รับมันเป็น (`AriaButton`, `Focusable`) · `<a>` ของ `NavLink` ก็เหมือนกัน ตอนพับรางจึงใช้ `title` ธรรมดาแทน
- ❌ **JDBI + `ESCAPE '\'` ในคิวรี** — JDBI สแกน SQL เองเพื่อหา named parameter · backslash ใน string literal ทำให้มันคิดว่า string ยังไม่ปิด แล้ว `:name` ทุกตัวหลังจุดนั้น **ไม่ถูก bind** → Postgres ตอบ `syntax error at or near ":"` (เสีย 1 รอบ build กับเรื่องนี้) · **ใช้ `ESCAPE '!'` แทน** แล้ว escape เป็น `!!` / `!%` / `!_`
- ❌ **ให้ search ของ column match `fqn` ด้วย** — fqn ของ column ลงท้ายด้วย fqn ของ table ผลคือพิมพ์ชื่อ table แล้วได้ column ทุกตัวในตารางนั้นขึ้นมากลบตัว table เอง → column ต้อง match **เฉพาะ `name`**
- ❌ **ยกม่าน loading หลัง `await signIn()` สำเร็จ** — มีเฟรมที่ token มีแล้วแต่ม่านยังไม่ขึ้น หน้า guarded วาดแลบผ่าน (ผู้ใช้เห็นและชี้) → **ยกม่านตั้งแต่ submit** แล้วปิดทิ้งตอน error
- ❌ **fade หน้าใหม่โดยไม่ใส่ `key`** — element เดิมอยู่ยาว animation เล่นครั้งเดียวตอนเปิดหน้าแรกแล้วเงียบตลอดกาล → `key={pathname+search}` บังคับ remount
- ❌ **หน่วง transition ด้วยการเลื่อน `key` ของ wrapper** (เวอร์ชันแรกของ `RouteTransition`) — `key` คุมแค่ "remount เมื่อไหร่" **ไม่ได้คุมว่า render อะไร** · `children` เป็น `<Outlet />` ซึ่งอ่าน router context ตอน render อยู่แล้ว หน้าใหม่จึงขึ้นทันทีที่คลิก พอครบ 520ms `key` เปลี่ยน มันก็ remount หน้าเดิมที่เห็นอยู่แล้วพร้อม fade → อาการที่ผู้ใช้เจอคือ **"เปลี่ยนเลย แล้วค่อยแวบ"** เป๊ะตามที่ code ทำ (คอมเมนต์ในไฟล์เขียนว่า "หน้าเก่าค้างไว้" ทั้งที่ไม่เคยเก็บ element เก่าไว้เลย — comment ที่โกหกตัวเอง)
- ❌ **snapshot `children` ใส่ state เพื่อค้างหน้าเก่า** — คิดไว้เป็นทางแก้แล้วตัดทิ้ง เพราะ **ใช้ไม่ได้**: `<Outlet />` เป็น element ที่ผูกกับ context ไม่ใช่ snapshot ของ DOM · เก็บ element เดิมไว้ มันก็ render route ใหม่อยู่ดี → ต้องหน่วงที่ **location** (`<Routes location={display}>`) เท่านั้น
- ❌ **`onClick` บนแถวผลลัพธ์ที่อยู่ใน dropdown ของ input** — blur ของ input ปิด panel ทิ้งก่อนคลิกจะลง → `onMouseDown` + `preventDefault()`
- ❌ **`type="badge-modern"`** — ค่าที่ถูกคือ `type="modern"` · และ `Badge` ควรส่ง `type` ชัดเจนเสมอเพื่อให้ generic `BadgeColor<T>` inference ทำงาน
- ❌ **Jest พังทั้ง suite เพราะ `import logo from '…png'`** — แก้ด้วย `moduleNameMapper` → `src/__mocks__/fileMock.cjs` · **บทเรียน: รัน `yarn test` เต็มชุดทุกครั้ง**
- ❌ **`@testing-library/user-event` ไม่ได้ติดตั้ง** — ใช้ `fireEvent`
- ❌ Playwright browsers ไม่ได้ติดตั้ง — ใช้ `chromium.launch({ channel: 'msedge' })` และ script ต้องอยู่ใน `frontend/app/`
- ❌ **`shortFqn` ฉบับแรกคืน "สอง segment ท้าย"** — ใช้ไม่ได้กับข้อมูลจริง เพราะ domain สองชั้นท้ายยาวชั้นละ ~45 ตัวอักษร ผลคือชิปยาวเท่าเดิม → ต้องคิดเป็น **งบของ parent** (`PARENT_BUDGET = 18`) แล้วยุบ parent ที่ยาวเกินงบเป็น `…`
- ❌ **คิดจะตัด parent ทิ้งเสมอให้เหลือแค่ leaf** — `PII.Sensitive` กับ `MFEC-PDPA.Sentitive` จะเหลือ `Sensitive`/`Sentitive` ที่แยกไม่ออกว่ามาจากหมวดไหน = ชิปที่อ่านง่ายแต่ผิด
- ❌ **ปล่อยให้ `Badge` wrap เอง** — `Badge` ของ design system เป็น `size-max whitespace-nowrap` มันจะไม่ wrap แต่จะ**ยืด**จนดันทั้งแถว → ต้อง `max-w-*` ที่ `Badge` + `truncate` ที่ span ข้างใน
- ⚠️ MCP connector หลายตัวของ claude.ai ยังไม่ได้ authorize — session แบบ non-interactive ทำ OAuth ไม่ได้ ต้องไปกดใน claude.ai connector settings

---

## ช่องว่างที่รู้ตัวแล้วแต่ยังไม่ได้แก้

เขียนไว้ตรงนี้เพราะทุกข้อ **ดูเหมือนทำงานปกติจากข้างนอก** — เป็นชนิดที่จะถูกค้นพบตอนผิดแล้ว ถ้าไม่จด

1. **`scopeFqn` ของ policy ระดับ DOMAIN / SERVICE / DATABASE ถูกใช้เป็น prefix ของ FQN ทางกายภาพ** — ผูก policy ไว้ที่ชื่อ domain (`Finance.Risk`) มันจะ bind ไม่ติดอะไรเลย ทั้งที่หน้าจอดูเหมือนสร้างสำเร็จ ต้องแยก scope เชิง governance ออกจาก scope เชิงกายภาพ
2. **ไม่มี write API สำหรับ local principal / attribute (FR-2.2) และการติด facet แบบ local (FR-1.7)** — `analyst_a` / `steward_c` และ tag ของ demo ถูก seed ด้วย SQL ตรงๆ ไม่มีทางทำผ่าน UI
3. **`PolicyResource.affecting` default `environment` เป็น `"dev"` แต่ `DecisionService.DEFAULT_ENVIRONMENT` เป็น `"prod"`** — หน้าจอ "policy ที่มีผลกับ asset นี้" กับสิ่งที่ engine ตัดสินจริง จะตอบคนละชุดโดยไม่มีใครรู้
4. **ไม่มี audit ของการ configure เลย** — เปลี่ยน data source, เปลี่ยน OM settings, enable/disable source ไม่ถูกบันทึกที่ไหน มีแต่ audit ของ query / decision / policy change (FR-8.1 ครอบแค่ policy)
5. **`audit_decision.evaluation_ms` ไม่เคยถูกเขียนค่า** — เป็น NULL ทุกแถว ทำให้ยืนยัน NFR-2 (p95 < 50ms) ไม่ได้
6. **capability matrix ยังไม่รู้จัก masking function ต่อ dialect และไม่รู้จักเวอร์ชันของ engine** — ดูหัวข้อ I ข้างบน

---

## Next Steps

1. **push ให้ขึ้น** — local นำหน้า remote อยู่ (remote main ยังอยู่ที่ `e3aaa52`) · แก้เรื่อง `git push` ค้างก่อน (ดู What Didn't Work) แล้วยืนยันด้วย `git ls-remote --heads origin` · **scan secret ก่อน push ทุกครั้ง**
2. **ปิด M3** — decision cache (FR-5.5) + ANTLR grammar ของ `expr` (FR-3.2)
3. **ปิด M4** — หน้า asset ต้องโชว์ "policy ที่มีผลกับ asset นี้" (มี endpoint `/policies/affecting/{fqn}` รออยู่แล้ว — แก้ข้อ 3 ของช่องว่างด้วย), View-as-user (FR-5.2), impact analysis (FR-5.3)
4. **ปิดช่องว่างข้อ 1–5 ข้างบน** โดยเฉพาะ **ข้อ 4 (audit ของการ configure)** ซึ่งเป็นของที่ auditor จะถามหาแน่นอน
5. **M2** — write API ของ principal/attribute แล้วต่อ (ก) filter ตาม attribute ในหน้า People (ผู้ใช้ขอไว้: *"อยากให้สามารถ Filter ตาม attribute ได้"*) (ข) การ assign application role จริงในหน้า `/settings/roles` (ค) หน้า local group ที่ `/settings/groups` ซึ่ง card ในหน้า Settings ลิงก์ไปรออยู่แล้ว
6. **M5 (secure view)** — `DecisionSql` + dialect ทั้งสองตัวพร้อมแล้ว เหลือ ViewCompiler + `row_entitlement` maintainer + dry-run/apply/rollback + golden-file test
7. **FR-1.6** — reconcile cache กับ JDBC introspection จริง (**รอ connection database จริงจากผู้ใช้**)
8. **หน้าเปลี่ยนรหัสผ่าน** — `mustChangePassword` ไหลถึง `auth/authStore.ts` แล้วแต่ไม่มีใครอ่าน
9. **ก่อน M6** ต้องได้คำตอบ: SQL Server production เป็น **2022+** ไหม (ต้องการสำหรับ `GRANT UNMASK` ระดับ column) และลง extension `anon` บน PostgreSQL ได้ไหม
10. **rebuild + restart backend** — jar ที่รันค้างอยู่เก่ากว่า `SubjectMatcher` ของรอบนี้ จึงยังไม่รู้จัก `requiredPrincipals` (ข้อ R) · เขียน policy ที่ใช้ลิสต์ที่สองแล้วทดสอบกับ service ที่รันอยู่จะได้ผลผิด
11. งานเล็กที่ค้าง: refactor `jdbcUrl` ที่ยังเป็น private ใน `SourceProbe` ให้ไปอยู่บน `JdbcTargets` · golden-file test ของ dialect ทั้งสองตัว

**กติกาที่ต้องถือไว้ทุกครั้งที่ commit:** repo เป็น public -> scan หา password / JWT / hostname และ IP ภายใน ก่อน push เสมอ · ค่าจริง (`IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`, `OM_WEBHOOK_SECRET`, `FERNET_KEY`, `SRC_PG_ARAK_CREDENTIAL`, bot JWT) อยู่ใน `.env` ที่ gitignore เท่านั้น · `.env.example` มีแต่ placeholder
**ผู้ใช้สั่งไว้:** *"อัพเดตไฟล์ handoff ทุกครั้งที่เอาขึ้น git"* — commit ที่ไม่มี HANDOFF.md ติดไปด้วย ถือว่ายังไม่เสร็จ
**ข้อจำกัดที่ผู้ใช้สั่งไว้:** ต่อ OpenMetadata **read อย่างเดียว** ตอนนี้ — ห้าม PATCH กลับ (FR-1.7 จึงยังไม่ทำ)

**คำสั่งที่ใช้บ่อย** — ดูหัวข้อ 7 ของ [docs/DESIGN.md](docs/DESIGN.md)
