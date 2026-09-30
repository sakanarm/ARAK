# ARAK — Data Access Control Platform · Design & Feature Catalogue

> เอกสารนี้คือ **บันทึกความจำของโปรเจกต์** — รวม requirement ทุกข้อ, การตัดสินใจที่ตกลงแล้ว, สถานะงานจริง ณ ปัจจุบัน และวิธีรันระบบ
> ถ้าเปิดคุยรอบใหม่ (หรือคนใหม่เข้าทีม) ให้เริ่มอ่านจากไฟล์นี้ก่อน แล้วค่อยไปอ่าน [policy-spec.md](policy-spec.md) และ [adr/](adr/)
>
> อัปเดตล่าสุด: 2026-09-26 · สถานะ: M1 กำลังทำ (FR-1.5 เสร็จ) · Repo: https://github.com/sakanarm/ARAK (**public**)

---

## 1. ภาพรวม

ARAK คือแพลตฟอร์ม **Data Access Control** แบบเดียวกับ Immuta / Denodo — กำหนดว่า "ใครเห็นข้อมูลอะไรได้บ้าง" ที่ระดับ table / row / column / cell แล้ว **บังคับใช้จริงที่ database** ไม่ใช่แค่แสดงผลใน UI

### 1.1 หลักการที่ตกลงกันแล้ว (ห้ามหลุด)

| # | หลักการ | เหตุผล |
|---|---|---|
| 1 | **ไม่สร้าง catalog ใหม่ — integrate กับ OpenMetadata เท่านั้น** | ตาราง `asset` / `tag` / `glossary` / `domain` ของเราเป็น **cache** เพื่อ availability + audit ย้อนหลัง ไม่ใช่ catalog ตัวที่สอง · อ่านอย่างเดียวจาก OM ยกเว้นปุ่ม push-back (FR-1.7) |
| 2 | **Policy IR ชุดเดียว** | ห้ามเขียน logic การ enforce แยก 3 ชุด — `PolicyDecision` เป็นสัญญากลาง แล้ว compiler 3 ตัวแปลงไปตาม target |
| 3 | **RBAC / ABAC / Rule / Time = predicate เดียว** | ไม่ใช่ 4 engine — เป็น `SubjectRule` ตัวเดียวที่ evaluate คนละมุม |
| 4 | **Global + Local, compose แบบ intersection** | ชั้นล่างเข้มขึ้นได้ ผ่อนไม่ได้ เว้นแต่ชั้นบนเปิด `allowLocalOverride` |
| 5 | **Fail-closed ทุกจุด** | ไม่มี policy match = deny · parse SQL ไม่ได้ = reject · webhook ไม่มี secret = 503 · expression ตัดสินไม่ได้ = deny |
| 6 | **Enforcement: Phase 1 ส่ง 5.1.2 + 5.2 · 5.1.1 เป็น opt-in** | ผู้ใช้เคยยืนยันว่า "5.1.1 กับ 5.1.2 ต้องใช้งานได้" แต่ต่อมาสั่งเพิ่มว่า **ไม่อยากให้เขียนทับ table เดิม** → ดูข้อ 8 |
| 7 | **สแตกและ UI เลียนแบบ OpenMetadata 2.0.1** | dev ที่ดูแล OM อ่าน code เราออกทันที และ reuse design token / component ได้ |
| 8 | **ARAK ไม่เขียนคำนิยามของ table ลูกค้า** | สร้าง/ลบได้เฉพาะ **object ที่ตัวเองเป็นเจ้าของ** (view, policy object, entitlement table) + GRANT/REVOKE เท่านั้น · **ห้าม `ALTER TABLE ... ALTER COLUMN`** — ดูข้อ FR-6 |
| 9 | **Secret อยู่ใน `.env` ที่ gitignore เท่านั้น** | repo เป็น **public** → ทุก commit ต้อง scan ก่อน push · ห้ามมี password / token / IP ภายใน ในไฟล์ที่ commit |

### 1.2 เส้นแบ่งกับ OpenMetadata

| | OpenMetadata Policy/Role | ARAK |
|---|---|---|
| คุมอะไร | ใครแก้ description/tag/owner **ใน catalog** ได้ | ใคร **SELECT ข้อมูลจริง** ได้ เห็นแถวไหน column ไหน |
| Enforce ที่ไหน | OM backend | SQL Server / PostgreSQL |

→ เรา **ยืม concept + UI pattern** ของเขา และ **import Team/Role** มาเป็น principal group ได้ แต่ไม่ extend policy engine ของเขา

---

## 2. สถาปัตยกรรม

```
┌──────────────────────────────────────────────────────────────┐
│  Frontend — Vite 7 · React 18 · TS 5 · Tailwind 4            │
│             react-aria-components · @om/ui-core-components     │
│  Home │ Catalog │ Policies │ Simulator │ Enforcement │ Access │
└───────────────────────────┬──────────────────────────────────┘
                            │ REST (/api/v1/*)
┌───────────────────────────▼──────────────────────────────────┐
│  Backend — Java 21 · Dropwizard 5 · Jersey 3 · JDBI3         │
│  dac-service    REST + auth + catalog sync + webhook         │
│  dac-engine     PolicyEngine · ConflictResolver · Simulator  │
│  dac-compiler-sql  ViewCompiler · NativeCompiler · Rewriter  │
│  dac-connector-openmetadata  client · crawl · events · facet │
│  dac-connector-identity      Entra · Local · OM Team         │
│  dac-connector-source        JDBC introspect · DDL · drift   │
│  dac-proxy       QueryApi · JSqlParser rewrite               │
│  dac-spec        JSON Schema → Java POJO + TS type           │
│  dac-common      Fqns (จัดการ FQN แบบแยก segment)             │
└──────┬─────────────────┬──────────────────┬──────────────────┘
       │                 │                  │
┌──────▼──────┐   ┌──────▼──────┐   ┌───────▼────────┐
│ App DB      │   │OpenMetadata │   │  Data Sources  │
│ Postgres 16 │   │ REST+Webhook│   │ MSSQL / PG     │
└─────────────┘   └─────────────┘   └────────────────┘
```

### 2.1 สแตก (ล็อกตาม OpenMetadata 2.0.1)

**Backend** — Java 21 (`.tools/jdk-21.0.12.1+1`) · Dropwizard 5 · Jersey 3 (Jakarta) · JDBI3 · HikariCP · Flyway 9.22.3 · Jackson · java-jwt · Fernet · Maven multi-module
**Codegen** — `jsonschema2pojo` (Java) + `json-schema-to-typescript` (TS) จาก JSON Schema ชุดเดียวใน `dac-spec` · OM client generate ด้วย `openapi-generator` (`jersey3`, `useJakartaEe`) จาก `spec/openmetadata-2.0.1-swagger.json` (pin ไว้ 3.7 MB)
**Frontend** — Vite 7 · React 18 · TS 5 · Tailwind 4 (`prefix(tw)` → `tw:flex`) · react-aria-components · `@untitledui/icons` · `@openmetadata/ui-core-components` (vendor source มา, Apache-2.0, **ห้ามแก้มือ** — CI job `design-system-drift` เช็ค)
**Test** — JUnit 5 · AssertJ · Mockito · Testcontainers (BE) · Jest/Vitest + Playwright (FE)

### 2.2 โครงสร้างเรโป

```
backend/
  dac-spec/                  JSON Schema (policy IR) + codegen
  dac-common/                Fqns
  dac-engine/                PolicyEngine + matchers
  dac-compiler-sql/          (ว่าง — M5/M6/M7)
  dac-connector-openmetadata/ client · crawl · events · facet
  dac-connector-identity/    (ว่าง — M2)
  dac-connector-source/      (ว่าง — M5)
  dac-proxy/                 (ว่าง — M7)
  dac-service/               Dropwizard app (entry point)
frontend/
  app/                       console
  ui-core-components/        vendored จาก OpenMetadata
deploy/                      Dockerfile · docker-compose.yml (appdb, srcpg, mssql, vault, app) · seed/
docs/                        DESIGN.md (ไฟล์นี้) · policy-spec.md · adr/
spec/                        openmetadata-2.0.1-swagger.json (pinned)
conf/dac.yml                 config เดียวที่ commit — ใช้ ${ENV:-default}
.env                         **gitignored** — ค่าจริงทั้งหมดอยู่ที่นี่
```

---

## 3. โมเดลข้อมูล (App DB — Flyway V1…V8)

| Migration | ตาราง |
|---|---|
| `V1__catalog.sql` | `data_source`, `asset`, `asset_column`, `asset_fqn_map`, `asset_facet`, `asset_owner`, `classification`, `tag`, `glossary`, `glossary_term`, `domain`, `data_product`, `custom_property_def`, `sync_state` |
| `V2__identity.sql` | `principal`, `principal_attribute`, `group_member`, `app_role_assignment` |
| `V3__policy.sql` | `policy`, `policy_version`, `policy_binding` |
| `V4__enforcement.sql` | `enforcement_state`, `row_entitlement`, `db_principal_map`, `engine_capability`, `access_grant` |
| `V5__audit.sql` | `audit_policy_change`, `audit_decision`, `audit_query` (append-only) |
| `V6__local_auth.sql` | `local_credential` (bcrypt + lockout + must-change) |
| `V7__facet_label_type_generated.sql` | generated column สำหรับ `om_label_type` |
| `V8__crawl_generation.sql` | stamp ของ crawl (`last_seen_at` / generation) ใช้ทำ retirement sweep |

**หัวใจคือ `asset_facet`** — `(asset_id, facet_type, facet_fqn, depth, is_direct, provenance, inherited_from, om_state, om_label_type)`
กาง ancestor ทั้งสายเก็บไว้ล่วงหน้า (asset ใน `Finance.Risk.Credit` มี 3 แถว: `Finance`, `Finance.Risk`, `Finance.Risk.Credit`) → selector กลายเป็น index lookup ธรรมดา ไม่ต้อง recursive query ตอน evaluate (NFR-2)

---

## 4. Policy IR (JSON Schema — source of truth เดียวของทั้ง Java และ TS)

`backend/dac-spec/src/main/resources/json/schema/`

| Schema | field |
|---|---|
| `entity/policy/policy.json` | `id, name, displayName, description, policyType, scopeLevel, scopeFqn, selector, subject, effect, data, allowLocalOverride, requiresApproval, approvers, validFrom, validUntil, exemptions, lifecycleState, version, environment, updatedAt, updatedBy` |
| `entity/policy/subjectRule.json` | `principals, attributes, expression, time, context` (+ `principalMatch`, `attributeCondition`, `timeWindow`, `timeRule`, `contextRule`) |
| `entity/policy/dataPolicy.json` | `rowFilters, columnRules` (+ `maskingFunction`, `maskingSpec`, `rowFilter`, `columnRule`) |
| `api/policyDecision.json` | `principal, assetFqn, allowed, rowPredicates, columnMasks, hiddenColumns, reasons, unenforceable, evaluatedAt, cacheKey, fromCache` |
| `type/facet.json` | `facetType`, `facetOperator`, `facetCondition`, `assetSelector` |

> `unenforceable` ใน `PolicyDecision` คือช่องที่ capability matrix (FR-6.0b) ใช้บอกว่า "ข้อนี้โหมดที่เลือกทำไม่ได้" — ต้องเตือน ห้ามเงียบ

---

## 5. Feature Catalogue — ทุก Requirement พร้อมสถานะ

สถานะ: ✅ เสร็จ · 🚧 กำลังทำ · ⬜ ยังไม่เริ่ม · ⏭ เลื่อน Phase 2

### FR-1 Metadata Integration (OpenMetadata) — M1

| # | Feature | สถานะ | ที่อยู่ |
|---|---|---|---|
| FR-1.1 | เชื่อม OM ผ่าน REST `/api/v1/*` ด้วย JWT bot token · config ได้หลาย instance | ✅ | `om/OpenMetadataClient.java` · `config/OpenMetadataConfiguration.java` |
| FR-1.2 | ดึง governance object ครบทุกตัว (service/db/schema/table/column, classification, tag, glossary, term, domain, data product, owners, tier, custom property, user/team/role) | ✅ | `om/crawl/GovernanceCrawler.java`, `GovernanceMapper.java`, `AssetCrawler.java`, `AssetMapper.java` |
| FR-1.3 | ใช้ `?fields=...&include=...` ดึงครบรอบเดียว · FQN เป็น business key + UUID เป็น technical key | ✅ | `AssetCrawler.Fields` · `om/OmPager.java` |
| FR-1.3a | อ่าน `tagLabel.source/labelType/state` · แยก Classification tag ออกจาก Glossary term · default enforce เฉพาะ `Confirmed` | ✅ | `om/facet/FacetExtractor.java` · column `om_state`, `om_label_type` |
| FR-1.4 | cache แบบ versioned (SCD2) เพื่อ audit ย้อนหลัง | 🚧 มีตาราง + generation stamp แล้ว แต่ยังไม่ได้ทำ time-travel query | `V1`, `V8` · `catalog/AssetStore.java` |
| FR-1.5 | **Incremental sync: webhook (push) + change-event poller (pull) + nightly reconcile + ปุ่ม manual sync** | ✅ **เสร็จรอบนี้** | `resources/WebhookResource.java`, `catalog/ChangeEventPoller.java`, `NightlyReconcile.java`, `CatalogChangeApplier.java`, `om/crawl/AssetRefresher.java`, `om/events/*` |
| FR-1.6 | ตาราง mapping `om_fqn ↔ physical` + reconcile กับ JDBC introspection จริง + รายงาน orphan / column ที่ยังไม่มี policy | ⬜ (ต้องรอ connection จริง) | `asset_fqn_map` มีแล้ว |
| FR-1.6a | Catalog บอกทุกแถว 2 แกน: **Connected · <source>** (มี `asset_fqn_map` ที่ไม่ ORPHANED ชี้ไป source ที่ enabled — ตัวเดียวกับที่ query proxy resolve) หรือ **Not connected** (เดิมชื่อ Queryable / Metadata only) · และ **OpenMetadata / Read from source / ARAK only** (จาก `asset.provenance`) · container ติด Connected ถ้ามีอะไรข้างใต้ query ได้ · filter `reach=queryable\|metadata` (ชื่อใน URL/API คงเดิม) + `origin=` · ป้ายเรื่องตารางไม่พูดว่า "you" | ✅ | `CatalogQuery.QUERY_SOURCE` · `AssetSummary.querySource/provenance` · `pages/catalog/reach.tsx` · `CatalogQueryIT.marksWhatAQueryCanReach` |
| FR-1.6c | ป้ายเรื่อง **คนดู** (TABLE/VIEW ที่ connected เท่านั้น) ขึ้นต้นด้วย "You" เสมอ: **You can query** · **You requested access** · **You can request** · **You have no access** · ถามทีเดียวต่อหน้า `POST /v1/access-requests/eligibility` (≤50 ตาราง, ตัวเองเท่านั้น, ไม่บอกชื่อ policy) · ตารางที่ not connected ไม่มีปุ่ม Request access และ server ตอบ 409 | ✅ | `AccessEligibility.briefs` · `pages/catalog/myAccess.ts` · `reach.tsx AccessBadge` · `AccessRequestIT.Briefs/NotConnected` |
| FR-1.6d | ปุ่ม **Query** ที่หัวหน้าตาราง (ข้อ CH · ผู้ใช้หาปุ่มไม่เจอ 2026-09-28) — คนที่อ่านตารางได้แล้วเห็นปุ่ม Query แทนป้าย "You can read this" ที่ซ้ำกับ "You can query" · กดแล้วเปิดหน้า Query บน source ที่ตารางต่ออยู่ (หา id จากชื่อ `querySource` ใน `GET /v1/sources` ด้วย key เดียวกับหน้า Query) พร้อม `SELECT * FROM schema.table` ใน editor · **ไม่รันเอง** · หา source ไม่เจอ → ส่ง SQL ไปโดยไม่ระบุ source | ✅ | `AssetRequestAccess.tsx QueryThisTable` · `assistStore.deliverSql` · `AssetRequestAccess.test` |
| FR-1.6e | Explorer ในหน้า Query (ข้อ CL) — hover / focus column → tooltip ชื่อ · type · description (ไม่มี → บอกว่าเพิ่มได้ที่แท็บ Columns) · tag / term ที่เจาะจงที่สุด · **คลิกขวา** ตาราง / column → *Open in the Data Catalog* (แท็บใหม่) · *Insert into the editor* · *Copy the full name* (ใช้คีย์บอร์ดได้) · แผงลากขนาดได้ 3 จุด: explorer ซ้ายขวา · editor ขึ้นลง (ต่ำสุด 40px · ผลลัพธ์เหลือ ≥ 96px) · explanation ซ้ายขวา (editor เหลือ ≥ 240px) · จำขนาดต่อ browser | ✅ | `SchemaExplorer.tsx columnHint` · `Splitters.tsx` · `SchemaExplorer.test` · `Splitters.test` |
| FR-1.6b | **Add new connection** แบบ OM (stepper · card · Test connection) + **Table scope** ตอน import (Scan all / Only · Always exclude · text compare ไม่ใช่ regex · ไม่ใช่สิทธิ์) + Preview กับ login จริง · import ตั้งชื่อตาม `omServiceFqn` ถ้าผูกไว้ → ตารางที่ OM crawl มาเป็น asset เดียวกัน (MATCHED) · ชื่อเก่า retire | ✅ (V32) | `TableScope` · `SourceCatalogImporter.serviceOf/retireSuperseded` · `pages/sources/ConnectionWizard.tsx` · `SourceCatalogImporterIT` |
| FR-1.7 | สร้าง local tag/classification เอง + `provenance` + ปุ่ม push กลับเข้า OM | 🚧 | ติด tag ของ OM ลง table/column ใน ARAK ได้แล้ว (`local_tag`, provenance `local`, sync ไม่ลบ, คนที่ govern table + เหตุผล + audit) · **สร้าง classification และ tag เองได้ในหน้า Governance** (`V41` · `LocalVocabularyStore` · `/v1/local-vocabulary` · Platform Admin / Policy Author · tag ใต้ classification ของ OM ได้ · แก้ได้เฉพาะของ local · ชื่อแก้ไม่ได้ · ไม่ลบ ใช้ Disabled · `audit_vocabulary` · HANDOFF ข้อ CE) — glossary / domain ยังเป็นของ OM อย่างเดียว · **column description เขียนใน ARAK ได้** (`V40` · `column_description` ชนะของ OM · คนที่ govern table · NokRak ร่างจาก metadata แล้วคนกด Save · **เลือกร่างเฉพาะบาง column ได้ รวม column ที่มีคำอธิบายแล้ว (ข้อ CM)** · แสดงใน Columns tab และใน Access Request ticket · HANDOFF ข้อ CD) · ปุ่ม push กลับเข้า OM ยังไม่ทำ (รอผู้ใช้อนุญาตเขียน OM) |
| FR-1.8 | Effective facet computation พร้อม `inherited_from` (ไม่พึ่ง tag propagation ของ OM) | ✅ | `om/facet/FacetInheritance.java` · `asset_facet` |
| FR-1.9 | Sync **นิยาม** custom property (type, enum values) เพื่อให้ Policy Builder แสดง operator ถูกชนิด | ✅ | `custom_property_def` · `GovernanceMapper` |
| FR-1.10 | **Catalog แบบ hierarchy** — Service → Database → Schema → Table: tree ที่โหลดทีละชั้น (`?view=tree`) + แท็บ Databases / Schemas / Tables ในหน้า container · `GET /v1/catalog/assets?parent=` + `childCount` (ผู้ใช้ขอ 2026-09-26) | ✅ | `catalog/CatalogQuery.java` · `pages/catalog/hierarchy.tsx` |

#### รายละเอียด FR-1.5 (ที่เพิ่งทำเสร็จ)

**ทางเข้า 2 ทาง → จุดรวมเดียว `CatalogChangeApplier`**

1. **Webhook** `POST /api/v1/webhooks/openmetadata` — ไม่มี `@Secured` โดยตั้งใจ (ผู้เรียกคือ server ไม่มี session) ใช้ **HMAC-SHA256** แทน
   - รับ body เป็น raw `String` เพราะ signature เซ็นบน byte ที่ส่งมาจริง (ถ้าให้ Jersey deserialize ก่อนจะเทียบไม่ตรงทุกครั้ง)
   - `503` = ยังไม่ตั้ง `OM_WEBHOOK_SECRET` (fail-closed — ไม่ใช่ "รับทุกอย่าง") · `401` = signature ไม่ผ่าน (ไม่บอกรายละเอียด) · `400` = body ไม่ใช่ JSON (ส่งซ้ำก็ไม่ช่วย) · `200` = สำเร็จ / ไม่มีอะไรต้องทำ · `500` = **มี change ที่ apply ไม่ผ่าน → ขอให้ OM ส่งซ้ำ**
   - รับ signature ได้ทั้ง hex และ base64, มี/ไม่มี prefix `sha256=`, ไม่สนตัวพิมพ์ (OM เปลี่ยน encoding ระหว่างเวอร์ชัน)
2. **Poller** — `ChangeEventPoller` ทุก 60 วิ (config ได้) อ่าน `GET /api/v1/events`
   - **rewind cursor 1 นาทีทุกครั้ง** แทนการจำ event id ที่เห็นแล้ว — ทำได้เพราะ applier **re-read by FQN** ไม่ replay payload → apply ซ้ำฟรี
   - ข้ามรอบถ้า full crawl กำลังรัน (sweep ของ crawl จะ retire ของที่ stamp ไม่ตรง)
   - **cursor ขยับหลังงานเสร็จเท่านั้น** และถ้ามี change ที่ล้มเหลว จะ **ค้าง cursor ไว้** ให้ tick ถัดไปอ่านซ้ำ สูงสุด 10 รอบ (`MAX_STALLED_POLLS`) แล้วค่อยขยับ พร้อม log ERROR — กันทั้ง "เสียของเงียบๆ" และ "cursor ค้างถาวรจน window โตไม่หยุด"
   - cold start: cursor ← `lastEventTs` − 1 นาที → `lastFullCrawlAt` − 1 นาที → now − 1 ชม.
3. **Nightly reconcile** 02:30 Asia/Bangkok — full crawl + sweep เพราะมีการเปลี่ยนแปลงที่ **ไม่มี event ตัวไหนบรรยายได้**: classification เปลี่ยนเป็น mutually exclusive, domain ย้าย parent, soft delete ที่ feed ไม่มี filter ให้ขอ · reschedule ใหม่ทุกครั้งหลังรัน (ไม่ใช่ fixed period) เพื่อให้รอดตอนเปลี่ยน DST

**`AssetRefresher`** — re-read เฉพาะจุด โดย **เข้าไปใช้ descent ของ `AssetCrawler` กลางทาง** (ไม่ implement inheritance ซ้ำ)
- ดึง ancestor จาก OM ไม่ใช่จาก cache ของเรา — เพราะ cache คือสิ่งที่กำลังจะถูกแก้
- ใช้ **own facet** ของ ancestor ไม่ใช่ effective facet → `inherited_from` ยังพูดความจริง
- ancestor หายไป (404) → `MissingAncestorException` = race ปกติ ข้ามไป ปล่อยให้ reconcile จัดการ · error อื่น = นับเป็น failed
- **ไม่เคยเรียก `sink.finished()`** — retirement sweep เป็นของ full crawl เท่านั้น

**`CatalogChangeApplier`**
- `collapse()` ยุบ event ซ้ำด้วย `LinkedHashMap.merge` โดยเอา **timestamp สูงสุด ไม่ใช่ตัวที่มาถึงทีหลัง** → webhook retry ที่มาช้าจะ undo การลบไม่ได้
- governance change ทุกชนิดยุบเป็น **หนึ่ง** re-read และรัน **ก่อน** งาน asset เสมอ (flag `mutuallyExclusive` เป็นตัวตัดสินว่า tag จะแทนที่หรือรวมกับ tag ที่ inherit มา)
- `retire()` ใช้ `fqn = :fqn OR fqn LIKE :prefix ESCAPE '\'` โดย prefix ผูกจุด (`fqn + ".%"`) และ escape `_ % \` → ลบ `prod.Sales` ต้องไม่ลาก `prod.SalesArchive` ไปด้วย และ `cust_data` ต้องไม่ลาก `custXdata`
- change ตัวเดียวล้มไม่ทำให้ทั้ง batch ล้ม

**ที่ผู้ดูแลระบบต้องทำเอง:** ไปสร้าง Event Subscription ใน OpenMetadata ชี้มาที่ `POST http://<arak-host>:8080/api/v1/webhooks/openmetadata` พร้อม shared secret ตัวเดียวกับ `OM_WEBHOOK_SECRET` (ระบบ **ไม่** สร้าง subscription ให้เองโดยตั้งใจ — ไม่ควรไปเขียนอะไรใน OM โดยไม่ได้สั่ง)

### FR-2 Identity & Attribute — M2 ⬜ ยังไม่เริ่ม (ยกเว้น local auth)

| # | Feature | สถานะ |
|---|---|---|
| FR-2.1 | Login ผ่าน Entra ID (OIDC) + ดึง group/attribute ผ่าน Microsoft Graph | ⬜ |
| FR-2.2 | Local user / group / attribute (สำหรับ test + service account) | ✅ บางส่วน — local sign-in ใช้ได้แล้ว (`local_credential`, bcrypt, lockout, must-change) · **เปลี่ยนรหัสผ่านเองได้ (ข้อ CG): Profile → Password และหน้าบังคับ "Choose a new password" เมื่อรหัสผ่านถูก admin ตั้ง (บัญชีใหม่ / reset / bootstrap) · `POST /v1/auth/password` ตรวจรหัสเดิม (ผิดนับเป็น failed attempt · lock แล้วได้ 429) · ≥ 12 ตัว ไม่ซ้ำ username ไม่ซ้ำของเดิม · audit `SET_PASSWORD` โดยเจ้าของบัญชี** · **local group (V35): admin สร้าง group และเพิ่ม/เอาสมาชิกออกได้ พร้อม reason บังคับ และ audit `ADD_MEMBER`/`REMOVE_MEMBER` · group ที่ sync มาแก้ใน ARAK ไม่ได้** |
| FR-2.3 | ใช้ OpenMetadata Team เป็นแหล่ง group | ⬜ |
| FR-2.4 | Attribute store key–value รองรับ multi-value + `source` (entra/om/local) + ลำดับความสำคัญ | ⬜ (ตาราง `principal_attribute` มีแล้ว) |
| FR-2.5 | Sync membership ตามรอบ + cache TTL · token claim ใช้ได้ทันที | ⬜ |
| FR-2.6 | App RBAC: Platform Admin / Policy Author / Data Owner / Auditor / Requester + **separation of duty** | ⬜ (ตาราง `app_role_assignment` มีแล้ว) |

> **ค้าง:** หน้าเปลี่ยนรหัสผ่าน — `mustChangePassword` ไหลจาก backend → `api/client.ts` → `auth/authStore.ts` แล้ว แต่ยังไม่มี route/หน้าจอ/guard ที่อ่านมันไปใช้

### FR-2A Asset Governance Facets — ✅ เสร็จ (M1)

| Facet | ตัวแปรใน policy | ระดับที่ผูกได้ | ลำดับชั้น |
|---|---|---|---|
| Classification | `asset.classifications` | db/schema/table/**column** | match ทุก tag ใต้หมวด |
| Tag | `asset.tags` | db/schema/table/**column** | hierarchical |
| Glossary | `asset.glossaries` | db/schema/table/**column** | match ทุก term ในเล่ม |
| Glossary Term | `asset.terms` | db/schema/table/**column** | hierarchical |
| Domain / Sub-domain | `asset.domains` | service/db/schema/table | **ซ้อนได้ไม่จำกัดชั้น** |
| Data Product | `asset.dataProducts` | table | flat แต่สังกัด domain |
| Owners | `asset.owners` | ทุกระดับ | — |
| Tier / Certification | `asset.tier`, `.certification` | table | เป็น Classification ตัวหนึ่งใน OM |
| Custom Property | `asset.prop('<name>')` | ตามที่นิยามใน OM | typed |
| Physical | `asset.service/.database/.schema/.table/.column/.dataType` | — | — |

- **FR-2A.1** effective facet = ผูกตรง ∪ ตกทอดจากชั้นบน พร้อม `inherited_from` ✅
- **FR-2A.2** operator `contains` (รวมลูกหลาน) / `eq` (เฉพาะชั้นนั้น) / `startsWith` ✅ · match แบบ **แยก segment** ไม่ใช่ `LIKE 'Finance.%'` ดิบๆ (กัน `Finance Ops` ชนกับ `Finance`) ✅ `dac-common/Fqns.java`
- **FR-2A.2a** domain ตกทอดจาก service/db/schema + asset รับ domain ของ data product ✅
- **FR-2A.3** `relatedTerms` / `synonyms` **ไม่ match อัตโนมัติ** ✅
- **FR-2A.3a** เก็บ `mutuallyExclusive`, `provider`, `disabled` ของ classification ✅
- **FR-2A.4** cross-side comparison (`user.country == asset.prop('dataResidency')`) — 🚧 interface `ExpressionEvaluator` พร้อมแล้ว แต่ **ANTLR grammar ยังไม่ได้เขียน** · พฤติกรรมตอนตัดสินไม่ได้ถูกกำหนดและเทสต์ไว้แล้ว (deny) และมี `ROW_DEPENDENT` ไว้ส่งต่อให้ compiler ทำ cell masking

### FR-3 Subscription Policy — M3 ✅ engine เสร็จ / ⬜ persistence + UI ยังไม่ทำ

| # | Feature | สถานะ |
|---|---|---|
| FR-3.1 | Scope 7 ระดับ: `ORG → DOMAIN → SERVICE → DATABASE → SCHEMA → TABLE → COLUMN` | ✅ schema + engine |
| FR-3.1.1 | Selector ผสม AND/OR | ✅ `SelectorMatcher` |
| FR-3.1.2 | Local policy ผูกกับ ownership (Data Owner เห็นเฉพาะ scope ตัวเอง) | ⬜ |
| FR-3.1.3 | Precedence + compose ทุกชั้น (sub-domain ลึกกว่า = ชั้นล่างกว่า) | ✅ `PolicyEngine` |
| FR-3.1.4 | Local เพิ่มความเข้มได้อย่างเดียว เว้นแต่ `allowLocalOverride` + audit | ✅ engine · ⬜ audit trail |
| FR-3.1.5 | หน้า "policy ทั้งหมดที่มีผลกับ asset นี้" | ⬜ (M4) |
| FR-3.1.6 | Materialize selector → `policy_binding` + re-resolve เมื่อ asset/facet/policy เปลี่ยน | ✅ policy แก้ → materialize · local tag → refresh · webhook/poller → refresh table ที่เปลี่ยน (ลบ schema ถอด table ใต้มัน) · governance → ทุก policy · nightly → ทั้งหมด |
| FR-3.2 | SubjectRule = predicate เดียว (principals + attributes + expr + time + context) | ✅ `SubjectMatcher`, `TimeMatcher`, `ContextMatcher` |
| FR-3.2a | `assetOwner: true` — dynamic subject จาก owners ของ asset | ✅ |
| FR-3.3 | `ALLOW` / `DENY` — **DENY ชนะเสมอ, default deny** | ✅ |
| FR-3.4 | field `requiresApproval` / `approvers` / `validUntil` มีใน model แล้ว (workflow อยู่ Phase 2) | ✅ schema |
| FR-3.5 | Exemption list + **บังคับใส่วันหมดอายุ** | ✅ schema · ⬜ job |

### FR-4 Data Policy (RLS + Masking) — M3 ✅ schema+engine / ⬜ compiler

| # | Feature | สถานะ |
|---|---|---|
| FR-4.1 | Row-Level Security: เทียบ column กับ user attribute, `IN` จาก multi-value, entitlement table join, `ALWAYS FALSE` · **ค่าที่เห็นได้มาจาก mapping table** (`LOOKUP` — department AA → division A · key หลายตัว AND กัน · `SUBQUERY` join ใน query บน source เดียวกัน / `READ_VALUES` อ่านค่าก่อน ข้าม source ได้ ≤ 1,000 ค่า) | ✅ schema · ⬜ compiler · `LOOKUP` ✅ 2026-09-30 ผ่าน query API เท่านั้น (ข้อ CT) — secure view ได้ 0 แถว · native ไม่รองรับ · `LOOKUP` เลือก column ด้วย tag ได้ ✅ 2026-09-30 (ข้อ CU) |
| FR-4.2 | เลือก column ที่จะ mask ด้วย **ทุก facet** (name/pattern, classification, tag, glossary, term, dataType, custom property) + ผสม AND/NOT | ✅ |
| FR-4.3 | Cell masking = column mask + row condition | ✅ schema (`ROW_DEPENDENT`) · ⬜ compiler |
| FR-4.4 | Masking library: `NULLIFY`, `CONSTANT`, `HASH` (SHA-256 + salt ต่อ column), `PARTIAL`, `REGEX_REPLACE`, `ROUNDING`, `CONDITIONAL` | ✅ schema + `MaskStrength` · ⬜ SQL |
| FR-4.5 | ซ่อน column ทั้งคอลัมน์ (ไม่โผล่ใน schema) | ✅ `hiddenColumns` |

### FR-5 Policy Engine — M3

> **ข้อควรระวังตอนเขียน policy (เจอตอนทำ demo บน prod):** เมื่อ ALLOW ระดับ global ทุกตัวตั้ง `allowLocalOverride: true` (ซึ่งต้องตั้งถ้าอยากให้ grant ผ่าน gate ได้) ALLOW ที่มี selector ในชั้นล่างกว่าจะผ่อน gate ของ global ให้**ทุกคนที่มัน match** ถ้าจะบีบให้แคบลงในชั้นล่าง ให้ใช้ **DENY** · ถ้าจะให้คนคนเดียวเข้า ให้ใช้ **grant**

| # | Feature | สถานะ |
|---|---|---|
| FR-5.1 | Conflict resolution: DENY ชนะ · row filter **AND** กัน · mask เข้มสุดชนะ (`NULLIFY > CONSTANT > HASH > REGEX > PARTIAL > ROUNDING > plaintext`) · ไม่ match = deny | ✅ `PolicyEngine`, `MaskStrength` (72 tests ผ่าน) |
| FR-5.2 | **Simulator / "View as user"** — SQL ที่จะ generate + preview data + policy ที่ match | ✅ `/simulator` — ถามแทนคนอื่นที่เวลา/IP/purpose ที่กำหนดเอง · ตอบ projection ต่อ column + row filter + เหตุผลต่อ policy · ใช้ `POST /v1/decisions` ตัวเดียวกับที่ query บังคับใช้ (ไม่ได้จำลอง) |
| FR-5.3 | Impact analysis ก่อน publish global policy | ✅ `GET /v1/policies/{id}/impact` · **นับ binding ไม่ใช่คำตอบ** — evaluate ทุกคน×ทุก table สองรอบ (มี/ไม่มี policy นี้) แล้วรายงานเฉพาะส่วนต่าง → policy ที่ grant ซ้ำกับชั้นบนรายงาน 0 คน · cap 25 table × 200 principal แล้วประกาศ `sampled` (UI พูด “at least”) · panel “Who it changes things for” ในหน้า policy detail |
| FR-5.4 | Explainability — ทุก decision บอกได้ว่าเพราะ policy ตัวไหน เงื่อนไขข้อไหน ชั้นไหน | ✅ `decisionReason` ใน `PolicyDecision` |
| FR-5.5 | Decision cache + invalidate เมื่อ policy/attribute/tag เปลี่ยน (< 10ms cached / < 100ms cold) | ✅ `DecisionCache` (LRU + TTL) ใน `DecisionService` · **เข้าใหม่ได้ 3 ทาง ตายได้ 3 ทาง** — flush เมื่อมีคนเขียน (5 publisher ผ่าน `ChangeNotifier`) · `DecisionValidity` คำนวณขอบเวลาของ policy เอง (time window / `validUntil` / exemption) · TTL 60s เป็น backstop · `generation` counter กัน evaluation ที่เริ่มก่อน flush มาลงทีหลัง · วัดจริง cold 116ms / warm 15–25ms end-to-end ผ่าน HTTP · `GET /v1/system/decision-cache` · 25 tests |

### FR-6 Enforcement — ทั้ง 3 โหมดเป็น first-class เท่ากัน

| | **5.1.1 Push config** (M6) | **5.1.2 Secure view** (M5) | **5.2 App proxy** (M7) |
|---|---|---|---|
| บังคับใช้ที่ | **policy object ของ engine** ที่ผูกเข้ากับ table | view ใหม่ `*_secure` | ชั้น app ตอน runtime |
| user query ที่ไหน | **ตารางชื่อเดิม** | ต้องชี้ไป view ใหม่ (หรือ rename swap) | endpoint ของเรา |
| ต้องมี DB login ต่อคน | ต้องมี | ต้องมี (หรือผ่าน proxy) | ไม่ต้อง |
| เขียนคำนิยาม table ไหม | **ไม่ — ห้ามตามกติกาข้อ 8** | ไม่ (แค่ REVOKE) | ไม่แตะ |
| Cell mask | ❌ | ✅ | ✅ |
| BI ต่อตรง | ✅ | ✅ | ❌ (รอ 5.2b) |
| Phase 1 | **opt-in ต่อ source · ทำทีหลัง** | ⭐ **หลัก** | ⭐ **หลัก** |
| สถานะ | ⬜ | ⬜ | ⬜ |

#### FR-6.2a เส้นแบ่งของ 5.1.1 — ผูก policy เข้ากับ table ได้ แต่ห้ามเขียนทับ table

สิ่งที่เคยเรียกรวมกันว่า "native config" จริงๆ แล้วเป็น**ของสองแบบที่ความเสี่ยงต่างกันคนละเรื่อง**:

| คำสั่ง | เกิดอะไรกับ table | Phase 1 |
|---|---|---|
| PG `CREATE POLICY` | object แยกต่างหาก — table ไม่เปลี่ยน | ✅ ทำได้ |
| PG `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` | **flag บน table** (กลับได้ ไม่แตะข้อมูล) | ⚠ทำได้เมื่อ source เปิดสวิตช์ไว้ |
| PG `SECURITY LABEL` (`anon`) | catalog entry แยก | ✅ ถ้าลง extension ได้ |
| MSSQL `CREATE SECURITY POLICY` | object แยกต่างหาก | ✅ ทำได้ |
| MSSQL `GRANT/DENY SELECT(col)` | permission ล้วนๆ | ✅ ทำได้ |
| **MSSQL DDM** (`ALTER TABLE ... ALTER COLUMN ... ADD MASKED WITH`) | **เขียนคำนิยาม column ทับ** | ❌ **ตัดออกจาก Phase 1** — ใช้ 5.1.2 แทน (และ DDM ทำ cell mask ไม่ได้อยู่แล้ว) |
| BigQuery row access policy / policy tag | binding แยก | 🔮 Phase 2 — คือเคสที่โมเดลนี้เกิดมาเพื่อ |
| Snowflake row access / masking policy | binding แยก | 🔮 Phase 2 |

> **กติกา:** ARAK สร้างและลบ **object ที่ตัวเองเป็นเจ้าของ** ได้ และ GRANT/REVOKE ได้ — แต่**ไม่เขียนคำนิยามของ table ที่ลูกค้าเป็นคนสร้าง** เหตุผลไม่ใช่เรื่องเทคนิค — rollback ของ object ที่เราสร้างคือ `DROP` ซึ่งสะอาดเสมอ ส่วน rollback ของ `ALTER COLUMN` คือการเดาว่าเราจำนิยามเดิมได้ถูก

> **ที่แลกมา:** เมื่อไม่ทำ DDM แล้ว คุณสมบัติ "query ตารางชื่อเดิมได้เลยโดยไม่ต้องแก้ report" จะหายไปในส่วนของ masking — ทางทดแทนคือ **rename swap ใน FR-6.1.1** (`customer` → `customer_raw`, view ชื่อ `customer`) ซึ่งให้ผลเท่ากันโดยไม่ต้องแตะคำนิยามของ table เลย

- **FR-6.0a** เลือกโหมดได้ต่อ source/asset และ **ผสมกันได้** (เช่น RLS ด้วย 5.1.1 + masking ด้วย 5.1.2) ⬜
- **FR-6.0b** **Capability matrix** ต่อ engine/เวอร์ชัน + เตือนตอนเลือกโหมดว่า policy ข้อไหน enforce ไม่ได้ ⬜ (ตาราง `engine_capability` มีแล้ว)
- **FR-6.0c** **Cross-mode consistency** — asset+policy เดียวกัน 3 โหมดต้องได้ผลเหมือนกันทุก byte + test ใน CI ⬜ (M7b)
- **FR-6.3.1** **Direct-access detector** ✅ — ปุ่ม *Check at the source* ใน tab Access ของ table (`POST /v1/direct-access/check`) ถาม permission ของ source เองว่าใครอ่าน table ได้โดยไม่ผ่าน ARAK: PG = owner · GRANT บน table · GRANT ระดับ column · PUBLIC · superuser · `pg_read_all_data` (ต้องมี USAGE บน schema ด้วยถึงนับ) · MSSQL = GRANT / CONTROL บน object · column · schema · database · `db_datareader` / `db_owner` · owner · sysadmin (ตัดคนที่โดน DENY ตรงออก) · role ที่ไม่ใช่ login กางสมาชิกที่เป็น login ให้ (สูงสุด 50) · login ของ ARAK เองถูกติดป้าย *ARAK itself* ไม่นับ · ผล: `EXPOSED` (mode PROXY / SECURE_VIEW หรือมี secure view ติดตั้งอยู่ แล้วมีคนอื่นถือ table) · `CLOSED` · `OPEN` (NATIVE_CONFIG / NONE — รายชื่อเป็นข้อมูลเฉยๆ) · `NOT_FOUND` · ไม่อ่านข้อมูลใน table · ไม่แก้สิทธิ์ที่ source (ARAK ไม่ REVOKE ให้เอง) · รันเมื่อกดเท่านั้น และเขียน `audit_enforcement` ทุกครั้ง (V37: action `DIRECT_ACCESS_CHECK` outcome `CHECKED` / `FAILED` / `REFUSED`) · ใครกดได้ = คนที่ดูแล table (`Stewardship.oversees`) + auditor + admin
- **FR-6.4** Dry-run **เสมอ** · rollback script ทุกครั้ง · **drift detection** ทุก N ชม. · state ต่อ asset: `NOT_ENFORCED / PENDING / APPLIED / DRIFTED / FAILED` ⬜

**ข้อจำกัดที่ต้องบอกผู้ใช้ตรงๆ:**
- SQL Server DDM mask แบบ conditional ต่อ user ไม่ได้ → **cell masking ทำไม่ได้ในโหมด 5.1.1**
- SQL Server ต้อง **2022+** ถึงจะ `GRANT UNMASK` ระดับ column ได้ (รุ่นเก่าเป็น db-wide = ใช้จริงไม่ได้) — **ยังไม่ยืนยันเวอร์ชัน production**
- PostgreSQL ไม่มี column masking ใน core → ต้องลง extension `anon` (managed service หลายเจ้าไม่ให้) — **ยังไม่ยืนยันว่าลงได้ไหม**
- **FR-6.3** **Result cache** ✅ — ผลของ `POST /v1/query` เก็บในหน่วยความจำ 30 วินาที (500 statement · 1M cell · LRU) · key = SQL หลัง rewrite + ตัวตน source (id / host / port / database / credentialRef / `updated_at`) + row cap จึงไม่ต้องมี principal ใน key: คนที่ได้ผลร่วมกันคือคนที่ statement ที่บังคับ policy แล้วเหมือนกันทุกตัวอักษร · lookup หลัง rewrite → deny ไม่มีทางได้ผลจาก cache และ decision ลง audit ทุกครั้ง · statement ที่มี `now()` / `random()` / `current_user` / `SESSION_CONTEXT(` ฯลฯ ไม่ cache · flush เมื่อ policy / binding / identity / grant / tag / OM sync เปลี่ยน · `fresh: true` = อ่านใหม่ · ผลบอก `cached` + `readAt` · V38 `audit_query.served_from_cache` · สถิติที่ `GET /v1/system/result-cache`
- **FR-6.3** **Concurrency limit** ✅ — เพดานสามชั้น คน (2) → source (4) → ทั้ง service (16) · รอ 3 วินาทีแล้ว `429` + `Retry-After` · กิน slot เฉพาะ read ที่ไปถึง source (cache hit / policy deny ไม่กิน) · คิดกับคนที่ส่ง ไม่ใช่คนที่ถูก preview แทน · ลง audit `REJECTED` หมวด BUSY (ไม่มี outcome ใหม่) · นับต่อ process — หลาย instance = เพดานคูณตามจำนวน
- **FR-6.3** **Cost guard** ✅ — ถาม planner ของ source ก่อนยิง โดยคิด row cap ด้วย (PG `EXPLAIN` ของ statement ที่ห่อ `LIMIT` · SQL Server `SET ROWCOUNT` + `SHOWPLAN_XML`) · เกินเพดานของ engine → ไม่ยิง `403 fixable` หมวด TOO_COSTLY · เพดานเป็นหน่วยของ planner แต่ละ engine จึงตั้งแยก (PG 10,000,000 · MSSQL 5,000) · ถาม planner ไม่ได้ = fail-open + log ครั้งเดียวต่อเหตุผล · ปิด SHOWPLAN ไม่ได้ = fail-closed · ผลมี `estimatedCost` · สถิติที่ `GET /v1/system/query-limits`
- **FR-6.3** **Download all rows** ✅ (ข้อ CL) — จอคงเพดาน 5,000 แถว (grid ไม่ virtualize · JSON ถือทุกแถวใน browser) · เมื่อผลถูกตัด ปุ่ม **All rows** เรียก `POST /v1/query/export` → CSV แบบ stream (BOM · CRLF · RFC 4180 · กัน formula injection · กติกาเดียวกับปุ่ม CSV บนจอ) · ผ่าน policy rewrite · admission slot · cost guard (ตีราคา **ไม่มี row limit**) ชุดเดียวกับ `run` ก่อนไบต์แรก · ถูกปฏิเสธ = 403 JSON ก่อนเริ่มไฟล์ · **เป็นตัวเองเท่านั้น** (`asPrincipal` คนอื่น → 403) · ไม่ใช้ result cache · พังกลางทาง = ตัด response ไม่เซฟครึ่งไฟล์ · เวลาสูงสุด `queryLimits.exportTimeoutSeconds` (600) · audit `exported=true` (V42) ทั้งสำเร็จ / ปฏิเสธ / ล้มเหลว → badge **downloaded** ใน Query log
- โหมด 5.2 ถูก bypass ได้ถ้าต่อ DB ตรง → ต้อง firewall + ระบบต้องตรวจและเตือน (FR-6.3.1 ✅ ตัวตรวจมีแล้ว — firewall ยังเป็นหน้าที่ของ source)
- โหมด 5.2 เรียก function ได้เฉพาะ built-in ที่คำนวณจากค่าที่ส่งเข้าไป (allow-list ต่อ engine ใน `ProxyFunctions`) · function นอกรายการ ชื่อที่มี schema นำหน้า `{fn ...}` sequence และ session variable ถูกปฏิเสธ เพราะ statement รันในนาม account ของ source และ function ที่รัน SQL จากข้อความ proxy มองไม่เห็นว่าอ่านตารางอะไร · ทุก reference ของตารางใน statement ต้องเป็นตัวที่ถูก rewrite แล้ว (เทียบ identity ทั้งต้นไม้ ไม่ใช่ชื่อ)

### FR-7 Manual Grant (Phase 1) — M8 ✅ เสร็จ (grant ตรง + auto-revoke + audit trail + หน้าจอ)
FR-7.1 owner สร้าง grant ตรงๆ พร้อม `validFrom`/`validUntil` + เหตุผล · FR-7.2 job auto-revoke · FR-7.3 หน้า "สิทธิ์ของฉัน" / "ใครมีสิทธิ์ใน asset นี้"
(tab **Access** ของ table ออกแบบให้รับ list ยาว: แถบสรุปตัวเลขที่กดกรองได้ · grant บรรทัดละตัวพร้อมสถานะเดียว In force / Overruled / Not started / Expired (default ซ่อน Expired · Overruled ขึ้นก่อน) · ค้นหา · แบ่งหน้า · เมนู ⋯ Edit / Revoke · กดชื่อหรือเหตุผลเปิดรายละเอียดเต็มรวม *Lets in* = ใครที่ grant นี้พาเข้ามาจริง · Who can read มีค้นหา · chip ที่มา · *Only those who see less than all* · แบ่งหน้า — ทั้งหมดทำในเบราว์เซอร์จากคำตอบเดียวของ `GET /v1/access/assets/{fqn}` · HANDOFF ข้อ CC)
("ใครมีสิทธิ์ใน asset นี้" กับประวัติ grant ของ table เห็นได้เฉพาะคนที่ govern table นั้น — admin, policy author, data owner ของ scope — และ auditor · server ตอบ 403 คนอื่น, หน้าเว็บซ่อน tab Access/Audit · คนทั่วไปดูของตัวเองที่ `/mine`)
(ตาราง `access_grant` มี `source` = manual|request และ `request_id` nullable ไว้แล้วเพื่อไม่ต้อง migrate ตอน Phase 2)

### FR-8 Audit & Compliance — M8 / M10 🚧 (FR-8.1–8.3 ✅ · FR-8.5 ✅ บน Dashboard (export ⬜) · FR-8.4 SIEM ⬜)
policy change log (append-only, ค่าเดิม→ค่าใหม่) · access decision log · query log (SQL ต้นฉบับ + หลัง rewrite) · export ไป SIEM · compliance report ("ใครเข้าถึง PII ได้บ้าง", "table ที่มี tag PII แต่ยังไม่มี policy", "สิทธิ์ที่ไม่ได้ใช้เกิน 90 วัน")

| ข้อ | สถานะ | อยู่ที่ไหน |
|---|---|---|
| FR-8.1 policy change log | ✅ **M17** | `audit_policy_change` append-only — หนึ่งแถวต่อทุกเวอร์ชัน ใน transaction เดียวกับ `policy_version` (create / update / submit / return / publish / disable / archive / rollback) · V39 `from_state` · `to_state` · `restored_from` · `client_ip` เก็บจาก remote address เท่านั้น และไม่ออกไปกับคำตอบใด |
| FR-8.2 access decision log | ✅ | `audit_decision` (+ `evaluation_ms`) |
| FR-8.3 query log | ✅ **M10** | `audit_query` + V25 (`asset_fqns` · `run_by`) · `GET /v1/audit/queries` + หน้า `/audit` · อ่านได้ตามหน้าที่: admin / author / auditor ทุกแถว · owner แถวบน table ของตัวเอง (SQL ซ่อนถ้าแตะ table อื่น) · คนอื่นของตัวเอง |
| FR-8.4 SIEM export + retention | ⬜ | — |
| FR-8.5 compliance report | ✅ **M10** (บนหน้าจอ · export CSV/PDF ⬜) | `GET /v1/dashboard` + หน้า `/dashboard` — ใครเข้าถึง label ที่เลือกได้ (default PII) · table sensitive ที่ไม่มี data policy (แยก ถูกอ่าน / เข้าได้ / ปิดอยู่) · สิทธิ์ที่ไม่ได้ใช้เกิน 90 วัน · grant ใกล้หมด / ไม่มีวันหมด · ไม่มี IP / SQL ในคำตอบ |

### FR-9 Policy Lifecycle — 🚧 (FR-9.1 ✅ · FR-9.2 ✅ M17 · FR-9.3 / 9.4 ⬜ → M30)
state `DRAFT → PENDING_APPROVAL → ACTIVE → DISABLED → ARCHIVED` ✅ ·
**FR-9.2 version + diff + rollback** ✅ — `GET /v1/policies/{id}/versions` (ทุกเวอร์ชัน + action + เหตุผล + restoredFrom) · `GET …/versions/{v}/impact` (resolve selector เก่ากับ estate ปัจจุบัน) · `POST …/rollback/{v}` `{expectedVersion, reason}` เขียนเวอร์ชันใหม่ที่ document เท่ากับ v · state ไม่เปลี่ยน · เหตุผลบังคับ · สิทธิ์ตรวจทั้งสอง document · ARCHIVED ห้าม · tab History บนหน้า policy: diff ตามความหมาย (`policyDiff.ts`) + impact ก่อน restore · หัวหน้า policy และหน้า Edit บอก `vN` · แก้ล่าสุดเมื่อไหร่ · โดยใคร (+ ลิงก์ History) · **Policy-as-Code** export/import YAML · แยก environment dev/uat/prod + promote → ทำรวมใน **FR-20 / M30**

### FR-11 Access Request Management — M9 🚧 ~75% · M13 ✅
ขอสิทธิ์เอง · workflow หลาย step ต่อ scope (ALL / ANY / AT_LEAST n · Reject เลือกได้ต่อ stage) · inbox + กระดิ่ง · review ก่อนตอบ · กัน grant ที่ policy ยังปฏิเสธ ·
Dashboard ใครใกล้หมดสิทธิ์ + นับถอยหลัง · สถิติคำขอต่อ table · ปุ่มขอสิทธิ์จากจุดที่โดนปฏิเสธ (M13)
- **FR-11.1 ไฟล์แนบ** ⬜ (ผู้ใช้ขอ 2026-09-25) — ไม่บังคับโดย default · workflow step ตั้งได้ว่าต้องแนบ · เก็บบน disk ของ server (ชื่อไฟล์เป็น UUID · เข้ารหัส · ≤ 10 MB · ≤ 5 ไฟล์ ·
  ตรวจชนิดจาก magic bytes) · ดาวน์โหลดได้เฉพาะคนขอ / approver / admin / auditor เป็น `attachment` + `nosniff` เท่านั้น · ลบไม่ได้หลังตัดสินแล้ว · audit ทุก upload / download
- **FR-11.2 เลข Ticket** ✅ (ผู้ใช้ขอ 2026-09-26 · V28) — ทุกคำขอมีเลข `REQ-000042` จาก sequence (คำขอเดิมเรียงตามวันที่สร้าง) · ค้นด้วย `REQ-42` / `#42` / `42` ได้ ·
  `GET /v1/access-requests/ticket/{n}` ตรวจสิทธิ์เดียวกับค้นด้วย id — ไม่มีกับไม่ใช่ของเรา ตอบ 404 เหมือนกัน ไม่หลุด UUID · หน้า Access requests มีช่องค้นหา (กด Enter) + ปุ่ม copy เลข · เปิดเต็มหน้าที่ `/requests/REQ-000042` (แชร์ URL ได้)
- **FR-11.3 Request access template** ✅ (ผู้ใช้ขอ 2026-09-26 · V31) — ฟอร์มขอสิทธิ์ตั้งค่าได้ใน Settings → Request templates ต่อ scope (หรือทั้งองค์กร) และเฉพาะตารางที่มี tag / classification / glossary term ที่ระบุ (บนตารางหรือ column) · กำหนด purpose (รายการ + บังคับ) · เลขอ้างอิง (label + บังคับ) · reason ขั้นต่ำ · preset วัน + สูงสุด / until revoked · guidance **plain text** · ลำดับ: ตัวระบุ facet → scope ลึกสุด → ชื่อ → Built-in · `GET /v1/request-templates/effective/{fqn}` ไม่เปิดเผย scope / facet · **server ตรวจคำขอกับ template ของตารางเองทุกครั้ง** · คำขอเก็บชื่อ template + reference ไว้ แก้/ลบ template ไม่กระทบ · template ไม่อนุมัติ ไม่เปลี่ยนผู้อนุมัติ ไม่เปิด policy · ทั้งองค์กร = PLATFORM_ADMIN · มี scope = ผู้ดูแล scope · auditor อ่านอย่างเดียว · audit append-only
- **FR-11.4 Preauthorization** ✅ (V30) — คำขอชนิด `PREAUTHORIZATION`: ขอล่วงหน้าให้ group / team / คนที่มี attribute สำหรับทุกตารางใต้ scope ที่มี tag / classification / glossary term / domain / data product (`contains` | `eq`) รวมตารางที่ถูกติดแบบเดียวกันในอนาคต · workflow + owner ของ scope ตัดสิน · `POST /v1/access-requests/preauthorization/coverage` นับตาราง/คน **ไม่บอกชื่อคน** (ชื่อเห็นเฉพาะใน review) · review เสนอ subscription policy เป็น **DRAFT** ต้อง activate ตาม lifecycle · GRANT ถูกปฏิเสธ (CHECK ใน DB ด้วย) · template ของ scope (FR-11.3) บังคับเหมือนคำขอทั่วไป
- **FR-11.5 Workflow Builder** ✅ (ผู้ใช้ขอ 2026-09-26 · ข้อ BR) — `/settings/workflows/:id` แบบ OpenMetadata: diagram ของ step / stage (ALL / ANY / AT_LEAST · Reject) + แผงแก้ stage + tab Executions (`GET /v1/access-workflows/{id}/requests` — ไม่มี IP ของคำขอ) · ใช้ `FlowDiagram` ตัวเดียวกับ Policy Diagram (FR-3 / M4: policy อ่านได้ 3 แบบ Text / Flowchart / Diagram ทั้งตอนสร้างและตอนอ่าน)
- ยังไม่ทำ: recertification ทุก 90 วัน · break-glass · email / Teams

### FR-12 Access Control Models — DAC / MAC / RBAC / ABAC
| Model | สถานะ | ที่อยู่ |
|---|---|---|
| DAC | ✅ | owner ให้ grant เอง + อนุมัติคำขอ · แต่ให้ทะลุ policy กลางไม่ได้ |
| RBAC | ✅ | `principals` / `requiredPrincipals`: `role` · `team` · `group` · `user` · `assetOwner` |
| ABAC | ✅ | `attributes` · `expression` · `time` · `context.ipCidr` / `purpose` |
| MAC | ⚠️ บางส่วน → **M20** | ORG-level DENY ได้ แต่ `gte` เรียงตามตัวอักษร (`Public < Internal < Confidential < Secret` เรียงผิด) · ยังไม่มี sensitivity scheme ที่เรียงลำดับได้ · ยังไม่มี guardrail no-read-up ระดับองค์กร |

### FR-13 Anomalous Access Detection — M19 ⬜
baseline ต่อคน และต่อคน × table (ปริมาณแถว · ชั่วโมงที่ใช้ · table ที่แตะ · อัตราโดนปฏิเสธ · รูปแบบ SQL · peer group) · risk score 0–100 พร้อมเหตุผลทีละข้อ ·
ตอบสนอง ALERT / STEP_UP (ผ่าน flow ของ M9) / BLOCK + พักสิทธิ์ · **block ทันทีได้เฉพาะทาง proxy 5.2** (native ตรวจย้อนหลัง) · shadow mode ก่อนเสมอ ·
ตัวตรวจล่มต้องไม่ทำให้ query ทั้งองค์กรพัง · baseline เป็นข้อมูลส่วนบุคคล (PDPA) · role ใหม่ `SECURITY_ANALYST` · LLM อธิบายเหตุผลเท่านั้น ไม่สั่ง block

### FR-14 AI Data Access Control — M21 ⬜
AI model / agent ดึงข้อมูลได้ไม่เกินสิทธิ์ของ user ที่มันทำงานแทน · agent registry (เพดานสิทธิ์ · วันหมดอายุ · kill switch · tool ที่อนุญาต) ·
delegated token (RFC 8693, claim `act`) · ARAK MCP server (`search_assets` · `describe_asset` · `run_query` · `request_access` เป็นร่าง) · `purpose = ai-agent` ใช้ ABAC ที่มีอยู่ ·
AI output guard (PII ใน prompt / output / citation) · pre-filter สำหรับ vector store · retention ตาม label (M20) · anomaly ต่อ agent (M19)

### FR-15 Encryption / Decryption ของข้อมูล — M22 ⬜ (ผู้ใช้ถาม 2026-09-25)
มีแล้ว: credential ของ source และ LLM key seal ด้วย Fernet · mask `HASH` ทางเดียว · MSSQL `encrypt=true` ·
ยังไม่มี: keyring + key rotation · Vault / Azure Key Vault client จริง · envelope encryption (DEK ห่อด้วย KEK) ·
`DECRYPT` เป็น action ของ data policy — ถอดรหัส column ที่ source เก็บเป็น ciphertext ให้เฉพาะคนที่มีสิทธิ์ **ในโหมด proxy เท่านั้น** (กุญแจห้ามอยู่ใน DDL ของ view) ·
`TOKENIZE` / `FPE` (FF1) แบบย้อนกลับได้ + `DETOKENIZE` ขอผ่าน access request · TLS บังคับทุก source · เข้ารหัส SQL ใน `audit_query` ได้ ·
ไม่เข้ารหัสข้อมูลใน table ของลูกค้าแทนเขา (ข้อตัดสินใจที่ 8)

### FR-16 Personal Security Health Dashboard — M23 ⬜ (ผู้ใช้ขอ 2026-09-25)
ทุกคนเปิดได้ เห็นเฉพาะของตัวเอง: table ที่เข้าถึงได้ + ได้มาทางไหน (grant · policy · owner) + mask / row filter · grant ใกล้หมดอายุ + นับถอยหลัง ·
query และการถูกปฏิเสธของตัวเอง · สิทธิ์ที่ไม่ได้ใช้ ≥ 90 วัน + คืนสิทธิ์เอง · checklist ความปลอดภัย (PII ที่ไม่ได้ใช้ · grant ไม่มีวันหมด · นอกเวลา · ความผิดปกติจาก M19) · ไม่โชว์ IP ดิบ

### FR-17 Automated Query Risk Blocker — M24 ⬜ (ผู้ใช้ขอ 2026-09-25)
ด่านหลัง rewrite ก่อน execute: กฎจาก AST (ไม่มี WHERE บน table ใหญ่ · cartesian join · `SELECT *` บน PII · PII จำนวนมาก) · `EXPLAIN` ประเมิน cost ·
คะแนนความเสี่ยง → `ALLOW` / `WARN` / `REQUIRE_PURPOSE` / `BLOCK` ตั้งได้ใน Configure · LLM อธิบายจากโครงสร้างที่ตัด literal ออกแล้ว แต่ไม่เป็นคนตัดสิน ·
ใช้ baseline ของ M19 · ผลลง `audit_query` · ต่อยอดของที่มีแล้ว (read-only · fail-closed · `MAX_ROWS` 5,000 · timeout 30 วินาที)

### FR-18 On-Demand Test Data Synthesis — M25 ⬜ (ผู้ใช้ขอ 2026-09-25)
profile สถิติผ่าน proxy ด้วยสิทธิ์ของคนขอ + Differential Privacy (ε ตั้งได้) · generate ใน ARAK (Gaussian copula → CTGAN / TVAE) รักษา PK / FK ·
LLM เห็นแค่ชื่อ / คำอธิบาย column ไม่เห็นแถวจริง · ตรวจ exact / near match + membership inference ก่อนส่งมอบ · รายงาน ε + คะแนน fidelity / privacy แทนการอ้าง 100% ·
ส่งออกเป็นไฟล์ หรือเขียนลง sandbox ที่ ARAK เป็นเจ้าของ (ข้อตัดสินใจที่ 8)

### FR-19 ARAK Gateway — เครื่องมือภายนอกต่อตรง แต่ query วิ่งผ่าน ARAK — M29 ⬜ (ผู้ใช้ถาม 2026-09-26)
*"ถ้า user จะไปต่อ Query tool ตัวอื่น แต่ตอน Query ต้องวิ่งผ่านเรา"* — DBeaver · Excel · Power BI · Tableau · database tool อื่นๆ ·
ARAK ฟัง **PostgreSQL wire protocol (v3)** บน TLS (เช่น `:5439`) — ทุกตัวข้างบนมี PostgreSQL connector ในตัว (Power BI = Npgsql, Import และ DirectQuery ·
Power BI Service ต้องมี On-premises Data Gateway · Excel = Power Query "From PostgreSQL" หรือ psqlODBC · Tableau / DBeaver / DataGrip / pgAdmin / psql = driver ปกติ) ·
**login ด้วย Personal Access Token ของ ARAK** (user = username, password = token · เพิกถอนได้ · มีวันหมดอายุ) ไม่ใช่รหัส DB · ชื่อ database = source ·
ทุก statement เข้า `QueryService` เส้นเดียวกับหน้า Query (rewrite + mask + RLS + audit ทุก byte เท่ากัน · query log บอก tool จาก `application_name`) ·
**query ต่อ `pg_catalog` / `information_schema` ตอบจาก catalog ของ ARAK เฉพาะที่คนนั้นมีสิทธิ์ — ห้ามส่งต่อไป source** ·
read-only: `BEGIN` / `SET` ตอบ OK · DML / DDL ปฏิเสธ (fail-closed) · row limit + timeout · extended protocol (prepared statement) + cancel + stream ·
BI tool สร้าง `SELECT … FROM (SELECT …) "_"` ซ้อนหลายชั้น → rewriter ต้องรองรับ derived table ทุกตำแหน่งโดยไม่หลุด (M29b) · DirectQuery ยิงถี่ → result cache สั้น + concurrency limit ·
SQL Server source: tool ส่ง SQL แบบ PG → แปลง dialect หรือทำ TDS แยก (M29c · SSMS ต้องใช้ TDS) ·
**source ต้อง firewall ให้รับเฉพาะ ARAK** ไม่งั้น bypass ได้ (FR-6.3.1) ·
ทางลัดสำหรับ Excel ก่อน gateway เสร็จ: saved query → feed link (CSV) + token — ใช้ได้เฉพาะ query ที่ save ไว้

**ช่องทางเชื่อมต่อ — ทุกช่องลงที่ `QueryService` เส้นเดียวกัน ไม่มีช่องไหนมี enforcement ของตัวเอง**
| ช่องทาง | ใช้อะไร | ใครใช้ | งาน |
|---|---|---|---|
| **JDBC** | driver PostgreSQL มาตรฐาน (pgjdbc) `jdbc:postgresql://<arak>:5439/<source>?sslmode=verify-full` | DBeaver · DataGrip · SQuirreL · Tableau (JDBC) · app Java | ได้จาก pgwire (M29a) — **ไม่ทำ driver ของเราเอง** (ต้องดูแลทุกเวอร์ชัน · driver มาตรฐานพูด pgwire ได้อยู่แล้ว) |
| **ODBC** | psqlODBC (DSN) | Excel · Power BI (ODBC) · Tableau · Qlik · SAS | ได้จาก pgwire (M29a) · คู่มือตั้ง DSN + TLS |
| **Connector** | Power BI custom connector (`.mez`, Power Query M) · Tableau connector (`.taco`) — เปลือกบาง ๆ บน pgwire เดิม | ผู้ใช้ BI ที่อยากเห็น "ARAK" ในรายการ source + ช่องใส่ token ที่ชัด | M29d · optional · ไม่มี code path ใหม่ฝั่ง server · Power BI Service ยังต้อง On-premises Data Gateway |
| **API** | REST Query API ที่มีอยู่แล้ว (`POST /v1/query`, 5.2a) + **PAT เป็น Bearer** · saved query → CSV feed | Python / notebook · script · app ภายใน · Excel "From Web" | ต้องมี PAT (M29a) — ตอนนี้ใช้ได้แค่ session token ของหน้าเว็บ |

### FR-20 Infrastructure as Code + Configuration as Code — M30 ⬜ (ผู้ใช้ขอ 2026-09-26)
*"การทำ Infrastructure as a code IaC, Configuration as a code · อาจจะทำผ่าน CI/CD Gitlab Github หรืออะไรที่สร้างเป็น template ไว้ให้เลย · CLI, API, Yaml file"*

แยกเป็น 2 ชั้น ที่ไม่ปนกัน:
| ชั้น | คุมอะไร | ของที่ส่งมอบ |
|---|---|---|
| **IaC — ติดตั้งตัว ARAK** | image · App DB · reverse proxy · TLS · secret store · scale | `deploy/docker-compose.yml` (มีแล้ว) · **Helm chart** (Kubernetes / OpenShift) · **Terraform module** (VM / AKS / EKS + Postgres managed) · ค่าทุกตัวมาจาก variable / secret ของ platform — ไม่มี secret ในไฟล์ |
| **CaC — ค่าตั้งใน ARAK** | data source · policy (subscription + data) · workflow การอนุมัติ · app role · feature access ของ AI Assist · home persona · การตั้งค่า OpenMetadata / LLM gateway (ไม่รวม key) | ไฟล์ **YAML** ใน Git · CLI `arak` · API plan / apply · template CI/CD |

**รูปแบบไฟล์** — แบบ Kubernetes: `apiVersion: arak/v1` · `kind: DataSource | Policy | Workflow | RoleBinding | FeatureAccess | HomePersona` · `metadata.name` เป็น key ·
`spec` ของ `Policy` คือ **Policy IR ตัวเดิม** (JSON Schema เดียวกับ backend และ Policy Builder — ไม่มี schema ที่สอง) · publish JSON Schema ให้ VS Code / IntelliJ เติมคำให้ได้ ·
ไฟล์เดียวมีหลาย document ได้ (`---`) · แยกโฟลเดอร์ต่อ environment (`envs/dev`, `envs/uat`, `envs/prod`) + overlay

**Secret ห้ามอยู่ในไฟล์** — ช่อง credential รับแค่ reference ที่ `DataSourceStore.validate()` ยอมอยู่แล้ว (`vault://` · `azurekeyvault://` · `env:`) ·
`fernet:` และค่าดิบถูกปฏิเสธตอน `validate` (ไม่ใช่ตอน apply) · `export` เขียน reference เท่านั้น ไม่เคยเขียนค่าจริง

**คำสั่ง (CLI `arak` = API ตัวเดียวกัน ไม่มีทางลัดเข้า DB)**
| คำสั่ง | ทำอะไร |
|---|---|
| `arak validate` | ตรวจ schema + selector + expression + reference ของ secret · ไม่ต้องต่อ server ก็ได้ (offline) |
| `arak plan` | diff ไฟล์กับของจริงบน server → เพิ่ม / แก้ / ลบ อะไร · **impact analysis** (FR-5.3) กี่ table กี่คน · ไม่เปลี่ยนอะไร |
| `arak apply` | ทำตาม plan ที่ได้ (ต้องส่ง plan id — ถ้า server เปลี่ยนไปหลัง plan ต้อง plan ใหม่) · transactional · audit ทุกแถวพร้อม commit SHA + ชื่อ pipeline |
| `arak export` | ดึงค่าปัจจุบันออกเป็น YAML — เริ่มจากระบบที่ตั้งด้วยหน้าจอมาแล้วได้ |
| `arak test` | รัน **policy test** — ไฟล์ YAML ที่บอกว่า "analyst_a ต้องเห็น `email` ถูก mask" แล้วเช็คผ่าน Simulator (FR-5.2) |
| `arak drift` | ของที่ถูกแก้บนหน้าจอหลัง apply ล่าสุด |

API: `POST /v1/config/validate` · `/plan` · `/apply` · `GET /v1/config/export` — ผ่าน permission เดียวกับหน้าจอทุกข้อ (Data Owner apply ได้เฉพาะ scope ของตัวเอง FR-3.1.2) ·
login ด้วย **PAT / service account** (M14) ไม่ใช่รหัสผ่าน

**Template CI/CD ที่ให้ไปใช้ได้เลย** — GitHub Actions (`arak/setup-action` + workflow ตัวอย่าง) · GitLab CI (`include:` template) · Azure DevOps (ทีหลัง)
- **Pull / Merge request** → `validate` + `test` + `plan` แล้ว comment diff + impact ลงใน PR
- **Merge เข้า main** → `apply` ไป dev อัตโนมัติ
- **uat / prod** → environment ที่ต้องมีคนกดอนุมัติ (GitHub Environments / GitLab protected environment) แล้ว `apply` ด้วย plan เดิม

**กติกา (ห้ามหลุด)**
- **policy ที่ apply ผ่าน pipeline ลงเป็น `DRAFT` เสมอ** เว้นแต่ไฟล์ขอ `ACTIVE` **และ** PR ได้ approve จากคนที่ไม่ใช่ผู้เขียน (separation of duty FR-2.6 — ตรวจจาก commit / approval ที่ pipeline ส่งมา) · LLM ไม่มีสิทธิ์ในเส้นนี้
- **ไม่ลบของที่ไม่อยู่ในไฟล์** เว้นแต่สั่ง `--prune` · plan แสดงรายการลบแยกให้เห็นชัด
- ของแต่ละชิ้นเลือกได้ว่า **managed by Git** (หน้าจอแก้ไม่ได้ มีป้ายบอก + ลิงก์ไป repo) หรือ **แก้บนหน้าจอได้** (drift ถูกรายงาน ไม่ถูกทับเงียบๆ)
- apply เป็น config ของ ARAK เท่านั้น — การ enforce ลง source ยังผ่าน dry-run / apply ของ Enforcement เหมือนเดิม (ข้อตัดสินใจที่ 8)
- ไม่มี `client_ip` / `requester_ip` / credential ใน export ใดๆ

**ต่อยอดทีหลัง** — Terraform provider (`arak_policy`, `arak_data_source`) สำหรับทีมที่ทำทุกอย่างบน Terraform อยู่แล้ว · GitOps แบบ pull (ARAK ดึงจาก repo เอง)

### FR-21 – FR-26 Object ที่ ARAK เก็บเพิ่ม นอกจาก Data Access Policy — M31–M36 🟡 M31a ✅ · M31b slice 1 ✅ · slice 2a ✅ (ผู้ใช้ขอ 2026-09-28)
*"นอกจาก Data Access Policy แล้ว ควรมี Object อื่นอีกไหมที่เก็บ" → "ใส่ใน Roadmap"*

**เส้นแบ่ง** — OpenMetadata เก็บว่า**ข้อมูลคืออะไร** (glossary · domain · data product · lineage · quality) · ARAK เก็บว่า**ใครใช้ได้ ใช้เพื่ออะไร และมีหลักฐานอะไรยืนยัน** · ของที่ OM มีแล้วไม่เก็บซ้ำ · consent และ retention เป็นของระบบต้นทาง ไม่ใช่ของ ARAK ·
ที่มีแล้ว: policy · grant · access request + workflow + template · local group · local tag / vocabulary · column description · saved query · audit

#### FR-21 Purpose — วัตถุประสงค์การใช้ข้อมูล (M31) — 🟡 M31a ✅ 2026-09-28 · M31b slice 1 ✅ 2026-09-29 · slice 2a ✅ 2026-09-29
- วันนี้ purpose เป็นข้อความพิมพ์เองอยู่ 4 ที่: `context.purpose` ใน subject rule · คำขอสิทธิ์ · `purposes` ของ template · `audit_query.purpose` — เทียบกันไม่ได้ นับไม่ได้
- **object `purpose`**: key · ชื่อ · คำอธิบาย · **ฐานกฎหมายตาม PDPA** (ม.24: consent / สัญญา / หน้าที่ตามกฎหมาย / ประโยชน์สำคัญต่อชีวิต / ภารกิจสาธารณะ / ประโยชน์โดยชอบด้วยกฎหมาย) · ใช้กับข้อมูลอ่อนไหว (ม.26) ได้ไหม · เจ้าของ (DPO / steward) · ระยะสิทธิ์สูงสุด · สถานะ `ACTIVE` / `RETIRED` (ปลดแทนลบ) · ประวัติทุกครั้งที่แก้
- ต่อเข้า: policy อ้าง purpose ด้วย key (ตรวจตอน save ว่ามีจริง) · ฟอร์มขอสิทธิ์และ template เลือกจากรายการ · grant เก็บ `purpose_id` และมีอายุไม่เกินของ purpose · query / export เลือก purpose · Dashboard ตอบ *"ข้อมูล PII ถูกใช้เพื่ออะไรบ้าง"*
- ข้อความเดิมเก็บไว้เป็น legacy text · ปลด purpose → grant ที่อ้างอยู่เข้ารอบทบทวน (FR-22)
- **M31a ✅ (HANDOFF ข้อ CN)** — V43 `purpose` + `audit_purpose` (append-only · CREATE / UPDATE / RETIRE / REINSTATE พร้อม before / after) · key ตัวเล็ก `^[a-z0-9][a-z0-9._-]{0,62}$` แก้ไม่ได้ · ชื่อและ key ห้ามซ้ำ (ไม่สนตัวพิมพ์) · ฐานกฎหมาย 7 แบบ (consent · ม.24(1)–(6)) หรือยังไม่ระบุ · ระยะสิทธิ์สูงสุด 1–365 วัน · seed 3 ตัวที่หน้า Query เคยมี (fraud-analysis · reporting · support — ฐานกฎหมายเว้นว่างให้คนที่รู้มาใส่) · `GET/POST /v1/purposes` · `PUT /{key}` · `POST /{key}/retire|reinstate` (ต้องมีเหตุผล) · `GET /usage` · `GET /{key}/history` · แก้ได้เฉพาะ admin / POLICY_AUTHOR · usage + history เห็นได้ admin / author / DATA_OWNER / AUDITOR · รายการเห็นได้ทุกคนที่ login
  - **server ตรวจ**: policy (`subject.context.purpose`) และ template (`form.purposes`) ต้องอ้างตัวที่อยู่ในทะเบียนและยังใช้อยู่ — ค่าที่ของชิ้นนั้นมีอยู่แล้วผ่านได้ (แก้ของเก่าได้โดยไม่ติดสิ่งที่คนแก้ไม่ได้ก่อ) · Query / Simulator ประกาศ purpose ที่ไม่อยู่ในทะเบียนหรือถูกปลด → 400 · คำขอสิทธิ์: ปลดแล้ว = ปฏิเสธ · purpose มีระยะสูงสุด → ขอเกิน / ขอแบบ until revoked ไม่ได้ · template มีรายการ → ต้องอยู่ในรายการนั้น
  - **หน้าจอ**: Settings → Purposes (In use · Named but not listed + List it · Retired · History) · picker ในฟอร์ม policy · template · ฟอร์มขอสิทธิ์ (3 ที่) · Pre-authorize · Query · Simulator · ระยะวันในฟอร์มขอถูกตัดตาม purpose
- **M31b slice 1 ✅ (HANDOFF ข้อ CO) — อะไรนับเป็นข้อมูลอ่อนไหว และ purpose ที่ไม่อนุญาตเจอมันแล้วเกิดอะไร**
  - **กติกาเดียวใช้ 3 ที่ จึงไม่ขัดกันเอง**: proxy ตัดสินว่า purpose ใช้กับตารางนี้ได้ไหม · review ของคำขอบอกว่า column ไหน sensitive · (slice 2) Dashboard นับ
  - V44 `sensitive_data_rule` (แถวเดียว): `built_in` (กติกาที่เคยเขียนใน code — `PII` · `PersonalData` · classification / tag ที่ชื่อบอก sensitive / confidential / restricted / secret แต่ไม่ใช่ non-sensitive / public — **เปิดไว้ ของเดิมจึงไม่เปลี่ยนวันที่ขึ้น**) · `include` / `exclude` เป็น classification / tag / glossary / term (label ครอบลูกหลาน · exclude ชนะ · อยู่ทั้งสองรายการไม่ได้ · ≤ 100 ต่อรายการ) · `mode` `OFF` / **`WARN` (default)** / `ENFORCE` · `audit_sensitive_data_rule` append-only (เหตุผลบังคับ · before / after)
  - นับเฉพาะ label ที่ `Confirmed` — `Suggested` ไม่นับ · กติกา built-in ใช้กับ classification / tag เท่านั้น glossary / term นับเมื่ออยู่ใน include · ตาราง sensitive เมื่อตัวตารางหรือ column ใดมี label ที่นับ
  - **Query proxy**: หลัง policy ตัดสินแล้วเท่านั้น (ตารางที่ policy ปฏิเสธถูกปฏิเสธด้วยเหตุนั้น ไม่ถูกตัดสินเรื่อง purpose) · purpose ไม่ได้ `sensitiveAllowed` / ไม่อยู่ในทะเบียน / ไม่ได้ระบุ บนตาราง sensitive → `WARN`: รันต่อ · ผลมี `warnings[]` · `audit_decision.purpose_check = WARNED` · `ENFORCE`: ปฏิเสธ ข้อความบอกชื่อ purpose · `deniedAsset` null (ไม่ใช่เรื่องสิทธิ์ ไม่เสนอฟอร์มขอ) · หมวด refusal `PURPOSE_NOT_ALLOWED` · `purpose_check = REFUSED` · `audit_decision.sensitive` บันทึกว่าตอนตัดสินนับเป็น sensitive ไหม (`OFF` = null) — รายงานจึงอ่านสิ่งที่จริงตอนนั้น ไม่ใช่กติกาวันนี้
  - **คำขอสิทธิ์**: `ENFORCE` → server ปฏิเสธก่อนเก็บ (ข้อความเดียวกับ proxy) · `WARN` → ส่งได้ และ review ของผู้ตัดสินมี factor `PURPOSE_NOT_FOR_SENSITIVE` (MEDIUM · ENFORCE = HIGH) · Pre-authorize ไม่ตรวจ (ยังไม่มีใครอ่านอะไรจนกว่าจะใช้) · ฟอร์มขอทั้งสองที่ถาม `GET /v1/sensitive-data/check` ต่อตาราง แล้วเตือน / ไม่ยอมส่งก่อนกด
  - `GET /v1/sensitive-data` (ทุกคนที่ login · `canEdit`) · `PUT` (admin / POLICY_AUTHOR · เหตุผลบังคับ) · `POST /preview` (กติกาที่ใช้อยู่หรือร่าง → ครอบกี่ตาราง / column · ตัวอย่าง ≤ 50 · label ละเท่าไร ≤ 30) + `GET /history` (admin / author / DATA_OWNER / AUDITOR) · `GET /check?asset=&purpose=` (ทุกคนที่ login — label ของตารางเห็นใน catalog อยู่แล้ว)
  - **หน้าจอ**: Settings → Purposes ส่วน *What counts as sensitive data* (อ่าน · Measure coverage · History · Edit พร้อม Measure what it covers ก่อน save · เตือนเมื่อเลือก Enforce) · หน้า Query แสดงคำเตือนเหนือผล · ฟอร์มขอ (จากหน้า Query · New request) เตือนหรือไม่ยอมส่ง · Query log / Dashboard มีหมวด *Purpose not for sensitive data*
  - **ยังไม่ครอบ**: Decision API และ Simulator ยังไม่ตรวจเรื่องนี้
- **M31b slice 2a ✅ (HANDOFF ข้อ CS) — grant เก็บ purpose และอายุไม่เกินของ purpose**
  - V45 `grant_access.purpose` (key หรือคำเดิมของ template · nullable) · อยู่ใน history ของ grant ด้วย
  - **ตรวจที่ `GrantStore` ที่เดียว** ตามที่มาของ grant: ให้ตรง (`DIRECT`) → ต้องอยู่ในทะเบียนและยังใช้อยู่ · จากคำขอ (`REQUEST`) → คำที่คำขอเก็บไว้ผ่านได้แม้ไม่อยู่ในทะเบียน (template เก่า) แต่ถูกปลดแล้ว = ปฏิเสธ · แก้ grant (`EDIT`) → คง purpose เดิม
  - purpose มีระยะสูงสุด → grant ต้องมีวันจบ และจบภายใน `maxDays` นับจากวันเริ่มเดิม (เผื่อ 10 นาทีให้เวลาที่ฟอร์มใช้) · ระยะที่ถูกลดหลังส่งคำขอ → complete ถูกปฏิเสธทั้งก้อน (`INVALID`) คำขอยังรออยู่
  - **หน้าจอ**: ฟอร์ม Grant access มีช่อง *What for* · ระยะเวลา / No expiry ที่เกินถูกซ่อน · วันถูกตัดให้พอดี · บอกเหตุผลก่อนส่ง · tab Access แสดงชื่อ purpose · รายละเอียดบอก purpose + ฐานกฎหมาย · ค้นด้วย purpose ได้
  - **ยังไม่ทำ (M31b slice 2b)**: รายงาน "ข้อมูล PII ถูกใช้เพื่ออะไร" บน Dashboard (จาก `audit_decision.sensitive`) · ปลดแล้วส่ง grant เข้ารอบทบทวน (ต้องมี M32)

#### FR-22 Access Review — รอบทบทวนสิทธิ์ (M32)
- `access_review_campaign`: ขอบเขต (selector เดียวกับ policy เช่น ทุก table ที่ติด PII) · ผู้ทบทวน (owner ของ table / หัวหน้า / ระบุชื่อ) · กำหนดส่ง · รอบซ้ำ (เช่น ทุก 90 วัน)
- `access_review_item` ต่อ grant ที่อยู่ในขอบเขตตอนเปิดรอบ: `KEEP` / `REVOKE` / `SHORTEN` + เหตุผล + ใครตัดสิน เมื่อไหร่ · ผู้ทบทวนเห็น**วันที่ใช้ล่าสุด**จาก query log (M10)
- กติกา: ทบทวนสิทธิ์ตัวเองไม่ได้ · `REVOKE` ผ่านทาง revoke เดิม (tombstone + audit) · เลยกำหนด = ถอนอัตโนมัติ หรือส่งต่อหัวหน้า (ตั้งได้) · export หลักฐานให้ auditor (CSV / PDF) · รายการเข้า inbox เดียวกับคำขอสิทธิ์

#### FR-23 Project / Workspace (M33)
- `project`: ชื่อ · **purpose (FR-21 บังคับ)** · เจ้าของ · สมาชิก (คน + local group) · table (FQN หรือ selector) · วันเริ่ม / วันจบ
- project เป็น **principal** แบบเดียวกับ group: grant ให้ project ครั้งเดียว · สมาชิกได้สิทธิ์เฉพาะตอนเป็นสมาชิกและ project ยังไม่จบ · ออกจาก project = สิทธิ์หายทันที · project จบ = grant ทั้งชุดหมดอายุ
- query ที่รันในนาม project ลง purpose ของ project ใน audit · ขอสิทธิ์ในนาม project = M27

#### FR-24 Rule ที่ใช้ซ้ำได้ — mask / row filter (M34)
- `policy_rule`: ชนิด `MASK` (function + parameter เช่น `PARTIAL` แสดง 4 ตัวท้าย) หรือ `ROW_FILTER` (predicate template เช่น `department = @user.department`) · ชื่อ · คำอธิบาย · เจ้าของ · version
- policy อ้าง rule ด้วย key (ตรึง version หรือตาม latest) · แก้ rule แล้วโชว์ impact ว่ากระทบ policy / table ไหน (FR-5.3) · การแก้เข้าประวัติ policy (M17) จึง rollback ได้
- ชุดตั้งต้น: เลขบัตรประชาชน 4 ตัวท้าย · อีเมลเหลือ domain · เบอร์โทร 4 ตัวท้าย · เห็นเฉพาะแถวหน่วยงานตัวเอง

#### FR-25 Policy test case (M35)
- `policy_test`: คน (ตัวจริง หรือ persona ที่ตั้ง attribute เอง) · table · ผลที่คาด (`ALLOW` / `DENY` · column ที่ต้องถูก mask · column ที่ต้องถูกซ่อน · ต้องมี row filter)
- รันผ่าน Simulator (FR-5.2) — ไม่อ่านข้อมูลจริง · รันทุกครั้งที่ save / activate policy (**activate ไม่ได้ถ้า test พัง** เว้นแต่ใส่เหตุผลยกเว้น) · รันทุกคืน · กดรันเองได้
- ไฟล์รูปแบบเดียวกับ `arak test` ของ M30b — M35 เก็บและรันใน ARAK · M30b รันชุดเดียวกันจาก CLI / CI

#### FR-26 Service account / API client (M36)
- `service_account`: ชื่อ · **เจ้าของที่เป็นคน (บังคับ)** · purpose (FR-21) · scope ของ API (query · อ่าน catalog · apply config …) · token เก็บแค่ hash + วันหมดอายุ (มีเพดาน) · IP allowlist (ไม่บังคับ) · ใช้ล่าสุดเมื่อไหร่
- เป็น principal: policy และ grant ใช้กติกาเดียวกับคน · ไม่มี role admin เป็นค่าเริ่มต้น · อนุมัติคำขอไม่ได้ · เจ้าของออก = ระงับ + เข้ารอบทบทวน (FR-22)
- เป็นฐานของ M14 (Org Key) · M29 (เครื่องมือ BI) · M30 (pipeline CI) · M21 (AI agent ทำงานในนาม service account + ในนามผู้ใช้)

### FR-10 Non-Functional
| # | | สถานะ |
|---|---|---|
| NFR-1 | credential ใน Vault (มี Fernet เป็น fallback) · encryption at rest · mTLS | 🚧 Fernet + `SECRETS_PROVIDER` มีแล้ว · Vault ⬜ |
| NFR-2 | decision p95 < 50ms · secure view overhead ≤ 20% · sync 100k asset ใน 30 นาที | ⬜ ยังไม่วัด |
| NFR-3 | backend stateless · **OM/Entra ล่มต้องยัง serve จาก cache ได้** | ✅ (ทดสอบแล้ว — start ได้ทั้งที่ OM ต่อไม่ติด) |
| NFR-4 | Prometheus metrics · health ของ sync job · dashboard enforcement | 🚧 health check มี · metrics ⬜ |
| NFR-5 | golden-file test ของ SQL codegen ทุก dialect + Testcontainers | 🚧 CI job `integration` มีแล้ว · `AssetStoreIT` **ยังไม่เคยรัน** |

---

## 6. แผน Milestone และสถานะจริง

สถานะ ณ 2026-09-28 · รายละเอียดทีละรอบอยู่ใน `HANDOFF.md`

| M | งาน | ประเมิน | สถานะ |
|---|---|---|---|
| **M0** | Maven multi-module + Dropwizard skeleton · Vite+React+Tailwind shell + vendor ui-core-components · JSON Schema codegen · OM client จาก swagger · Flyway · docker-compose · CI | 3 wk | ✅ **เสร็จ** |
| **M1** | OM connector: REST client · entity mapper ครบทุก governance object · FQN mapping · full crawl · **webhook + poller** · `asset_facet` + effective facet · nightly reconcile · **Catalog UI** | 4 wk | 🚧 **~95%** — เหลือ FR-1.6 (reconcile กับ JDBC) · FR-1.7 push-back (ผู้ใช้สั่ง read-only) |
| **M2** | Entra OIDC · Graph sync · LocalProvider · OmTeamProvider · AttributeResolver · app RBAC | 2 wk | 🚧 **~55%** — local sign-in + local account + app role จาก UI + **เปลี่ยนรหัสผ่านเอง / บังคับเปลี่ยนหลัง admin ตั้ง (ข้อ CG)** · server บังคับด้วย (token ที่ยังไม่เปลี่ยนได้ 403 ยกเว้น /me และ /password) + เพิ่ม local account จากหน้า People ✅ 2026-09-30 (ข้อ CX) · ยังไม่มี Entra / Graph / write API ของ attribute |
| **M3** | Policy IR · AssetSelector resolver + `policy_binding` materializer · SubjectRule evaluator · layered composer · ConflictResolver · decision cache · Simulator | 5 wk | 🚧 **~97%** — เหลือ ANTLR grammar ของ `expr` |
| **M4** | Policy Authoring UI (global + local builder, data policy builder, หน้า effective policy, view-as-user, impact analysis) | 4 wk | ✅ **เสร็จ** · หน้า *Where it runs* ก่อนฟอร์ม (kind · connection · โหมด) ✅ 2026-09-30 (ข้อ CU) · step 3 แสดง table ที่ draft จะครอบ + subscription เลือกได้ทุกระดับ + connection เดียวเลือกได้แค่โหมดของมัน ✅ 2026-09-30 (ข้อ CV) · หน้า Policies แสดง connection + โหมดของแต่ละ policy และ filter ได้ ✅ 2026-09-30 (ข้อ CW) · *Where it runs* เลือกยี่ห้อ database (มีโลโก้) ก่อนแล้วค่อยเลือก connection + การ์ด Databricks ไปหน้าแยก (ยังเป็นหน้าว่างรอ builder) ✅ 2026-09-30 (ข้อ CY) |
| **M5** | **5.1.2 Secure View** — ViewCompiler + dialect · `row_entitlement` maintainer · `DbPrincipalProvisioner` · cutover helper · dry-run/rollback · golden-file + Testcontainers | 4 wk | 🚧 **~85%** |
| **M6** | **5.1.1 Push config** — PG `CREATE POLICY` + column GRANT + `anon` · MSSQL `CREATE SECURITY POLICY` + granular UNMASK + `CREATE USER FROM EXTERNAL PROVIDER` · capability matrix · **ไม่ทำ DDM** (FR-6.2a) | 3 wk | ⏸️ **ON HOLD** (ผู้ใช้สั่ง 2026-09-24) |
| **M7** | **5.2a Query API** — JSqlParser rewrite · table resolution (CTE/sub-query/`SELECT *`) · fail-closed · stream · row limit/timeout · direct-access detector | 3 wk | ✅ — direct-access detector ✅ (FR-6.3.1) · result cache ✅ (FR-6.3) |
| **M7b** | Cross-mode consistency harness + CI | 1 wk | ⬜ ต้องมี M5 / M6 ก่อน |
| **M8** | Audit 3 ตาราง · DriftDetector + re-apply · manual grant + auto-revoke · compliance report · metrics · Vault | 3 wk | 🚧 **~35%** |
| **M9** | Access Request Management (FR-11) — ขอสิทธิ์ · workflow หลาย step · inbox · review · Dashboard ใกล้หมดสิทธิ์ · สถิติต่อ table · **ไฟล์แนบ** | – | 🚧 **~75%** — เหลือไฟล์แนบ · recertification · break-glass · email / Teams |
| **M10** | Access Control Dashboard — Coverage · Exposure · Activity · Health · **Query log** | – | ✅ **เสร็จ** — query log ตามหน้าที่ (V25) + dashboard หน้าเดียว · เหลือ export CSV/PDF · drift จริงรอ DriftDetector (M8) |
| **M11** | LLM Assist — per-user gateway | – | 🚧 **~60%** |
| **M12** | Home ที่จัดเองได้ต่อ account | – | ✅ **เสร็จ** |
| **M13** | Request access จากจุดที่โดนปฏิเสธ | – | ✅ **เสร็จ** |
| **M14** | Public API + Swagger + Org Key | – | ⬜ |
| **M15** | LLM อธิบาย policy และ dashboard | – | ✅ **(ก) อธิบาย policy ✅ 2026-09-28 (ข้อ CI)**<br>• endpoint `POST /v1/llm/assist/explain-policy` (body `{policyId, language, model}`) · feature `EXPLAIN_POLICY`<br>• server โหลด policy เอง แล้วส่ง document ที่ตัดออกแล้ว (ไม่มี id / version / lifecycleState / updatedAt / updatedBy · exemptions กับ approvers เหลือแค่จำนวน) + coverage (นับ + FQN ≤ 20) + verdict ของ overlap (≤ 10)<br>• **ไม่มีแถวข้อมูล · ไม่มีทางเขียน**<br>• ปุ่ม *Explain with NokRak* ในหน้า policy ติดป้ายว่า NokRak เขียน และให้ Simulator เป็นตัวตัดสิน<br>• อ่านได้ทุกคนที่เปิด policy ได้ (GET เปิดอยู่แล้วตาม FR-3.1.5)<br>**(ข) อธิบาย dashboard ✅ 2026-09-28 (ข้อ CJ)**<br>• endpoint `POST /v1/llm/assist/explain-dashboard` (body `{days, label, focus, language, model}` · focus = ALL / COVERAGE / ACTIVITY / ACCESS / REQUESTS / HEALTH) · feature `EXPLAIN_DASHBOARD`<br>• server อ่าน dashboard เองด้วยสิทธิ์ของคนถาม (`DashboardResource.dashboard` → คนที่ไม่ใช่ admin / policy author / auditor ได้ 403 เหมือนหน้า dashboard)<br>• `DashboardBrief` เขียนตัวเลขสรุปเป็นข้อความ: attention · coverage ≤ 15 table (owner เป็นจำนวน) · query ต่อวัน (> 31 วันรวมเป็นช่วง) · refusal · grant · request · health<br>• **ชื่อคนถูกแทนเป็น `[P1]`, `[P2]` ก่อนส่ง และแทนกลับที่ server** · ไม่มีแถวข้อมูล · ไม่มี SQL · ไม่มี IP · ไม่มีทางเขียน<br>• panel *Ask NokRak* ตัวเดียวใต้ key figures เลือกหัวข้อได้ (ไม่ใส่ปุ่มทุกการ์ด เพราะการ์ด 1/3 แคบเกินจะใส่คำตอบ) · ติดป้ายว่าตัวเลขบน dashboard คือตัวจริง<br>• ทดสอบกับ LLM จริงแล้ว (ข้อ CJ.1): ไม่มี `[Pn]` ค้าง ชื่อคนกลับครบ · prompt แยก query กับคำขอสิทธิ์ (ภาษาไทยเขียน "query" / "คำขอสิทธิ์" ไม่เรียก query ว่า "คำขอ") |
| **M16** | LLM ช่วยหา asset จากสิ่งที่อยากได้ — จุดเข้าในหน้า Query **และหน้า Catalog** | – | ✅ **2026-09-28 (ข้อ CK)**<br>• endpoint `POST /v1/llm/assist/find-data` (body `{want, language, model}`) · feature `CATALOG_SEARCH`<br>• โมเดลถูกถามสองครั้ง: ครั้งแรกเห็นแค่ประโยค (ขอคำค้น) · **ตรวจสิทธิ์ทุกตารางที่ค้นเจอก่อนครั้งที่สอง** — อ่านไม่ได้และขอไม่ได้ถูกทิ้งก่อนเขียนลง prompt · column ที่ decision ซ่อนถูกตัด · ไม่เหลือเลยก็ไม่ถามครั้งที่สอง<br>• ครั้งที่สองเห็น metadata เท่านั้น (ไม่มีแถว ไม่มี sample) · ตาราง / column ที่โมเดลแต่งถูกทิ้ง · access มาจากการตรวจ ไม่ใช่จากโมเดล<br>• หน้า Query: ปุ่ม **Find data** → การ์ดต่อตาราง · อ่านได้ → *Put in the editor* (`SELECT <column> FROM schema.table` บน source ของตาราง) · ขอได้ → ฟอร์มขอสิทธิ์ของ M13 · ไม่ run / ไม่ส่งคำขอเอง<br>• หน้า Catalog: ปุ่ม *Ask NokRak* เดิม (แชทค้นด้วย tool ที่กรองสิทธิ์ชุดเดียวกัน)<br>• **ข้อ CM**: ตารางที่ค้นเจอแต่อ่านไม่ได้และขอไม่ได้ (DENY / ชั้นบนไม่รับ / ไม่มี source) ถูก**นับ ไม่ถูกเอ่ยชื่อ** — `outOfReach` ใน find-data และใน tool `search_catalog` ของแชท · โมเดลไม่เห็นชื่อ / column เหมือนเดิม · เดิมตอบ "ไม่พบ" ซึ่งอ่านเหมือนไม่มีข้อมูล ทั้งที่ Catalog แสดงตารางนั้นพร้อมเหตุผลให้ทุกคนเห็นอยู่แล้ว<br>• ค้าง: flag "ปฏิเสธโดยไม่บอกอะไร" ต่อ policy (AP.1) — ต้องครอบ Catalog · แชท · find-data พร้อมกัน |
| **M17** | ประวัติย้อนหลังของ policy (diff + rollback) | – | ✅ V39 · tab History · rollback + impact · `audit_policy_change` เขียนจริง |
| **M18** | รองรับ database type ใหม่โดยไม่ต้องไล่แก้ 14 จุด | – | 🚧 **~75%** |
| **M19** | AI-Driven Anomalous Access Detection (FR-13) | – | ⬜ ต้องมี M10 (query log + `asset_fqns`) |
| **M20** | MAC เต็มรูปแบบ — sensitivity level ที่เรียงลำดับได้ (FR-12) | – | ⬜ |
| **M21** | AI Data Access Control (FR-14) | – | ⬜ ต้องมี M14 |
| **M22** | Encryption / Decryption ของข้อมูล — key rotation · Vault · decrypt-on-read · tokenization / FPE (FR-15) | – | ⬜ |
| **M23** | Personal Security Health Dashboard (FR-16) | – | ⬜ ต้องมี M10 · ส่วนความผิดปกติต้องมี M19 |
| **M24** | Automated Query Risk Blocker (FR-17) | – | ⬜ ต้องมี M10 · ส่วน AI ต้องมี M11 |
| **M25** | On-Demand Test Data Synthesis (FR-18) | – | ⬜ |
| **M26** | LLM Fix with AI (query ที่พัง) + Explain query — SQL + error + metadata เท่านั้น · ผลเป็นข้อเสนอใน editor | – | ✅ 2026-09-26 — `POST /v1/llm/assist/fix` + `/explain` · refusal มี `fixable` (ไม่เคย true กับ refusal ที่ policy ตัดสิน) · error ตัดค่าที่ quote ออกก่อนส่ง LLM · ปุ่ม Explain + Fix with AI ในหน้า Query · ไม่ run เอง |
| **M27** | ขอสิทธิ์ในนามกลุ่ม | – | ⬜ ต่อยอด M9 |
| **M29** | **ARAK Gateway** — ให้ DBeaver / Excel / Power BI / Tableau / pgAdmin ต่อตรงแต่ query วิ่งผ่าน ARAK (FR-19 · 5.2b) · M29a pgwire + PAT + pg_catalog emulation (PG source) · M29b SQL ที่ BI tool สร้าง (subquery ซ้อน) + result cache · M29c SQL Server source (แปลง dialect หรือ TDS) · M29d Power BI / Tableau connector (optional) · JDBC/ODBC = driver PG มาตรฐาน · API = `/v1/query` + PAT | 3–4 wk | ⬜ ผู้ใช้ถาม 2026-09-26 · ต่อยอด M7 |
| **M28** | Conversational ARAK Agent — แชทใน mascot + Catalog · ค้น catalog · ตอบ SQL syntax · เขียน query · พาไปหน้าในแอพ · metadata เท่านั้น · ไม่ apply อะไรเอง | – | ✅ 2026-09-26 — `POST /v1/llm/assist/chat` (tool calling ตามสิทธิ์คนคุย) · Ask NokRak ใน Catalog + global search · สิทธิ์ต่องานของ AI ต่อ role (V29 `llm_feature_access`, admin ตั้งใน Settings, บังคับที่ server) · **ตอบคำถามวิธีใช้ ARAK จากคู่มือ** (2026-09-27 · tool `search_docs` ให้ทุกคน · `docs/user-guide.md` + เอกสาร policy แพ็กเข้า jar ใต้ `help/` · ห้ามอธิบายแอปจากความรู้ทั่วไป · HANDOFF ข้อ CF) |
| **M28b** | ปุ่ม NokRak บนหน้างาน — New policy "NokRak, help me" (draft เข้า form · ตัด id/lifecycleState · ไม่ save/activate) · Query "NokRak, write it" (Use this = ใส่ editor ไม่ run) · Query console จัดแบบ BigQuery (แถบ editor แถวเดียว · Query settings popover · panel ลอย · Explain ข้าง editor) · prompt `/policy` ส่ง `type/facet.json` ด้วย · **หน้า Edit ก็มี "NokRak, help me" (2026-09-27)** — ส่ง form ปัจจุบันเป็น `current` (server ตัด id / version / lifecycleState / updatedAt / updatedBy · ไม่ใช่ JSON object หรือเกิน 50,000 ตัวอักษร = 400) · form เปลี่ยน + panel Before / Suggested + Undo · ACTIVE เตือนว่า Save = มีผลทันที · **Save เท่านั้นที่ถึง store** | – | ✅ 2026-09-26 · edit 2026-09-27 |
| **M30** | **IaC + Configuration as Code** (FR-20) — M30a YAML (`apiVersion: arak/v1`) + `validate` / `plan` / `apply` / `export` API · M30b CLI `arak` + policy test ผ่าน Simulator · M30c template GitHub Actions + GitLab CI (PR = plan + comment · main = dev · uat/prod ต้องกดอนุมัติ) · M30d Helm chart + Terraform module ติดตั้ง ARAK · M30e Terraform provider (optional) · secret เป็น reference เท่านั้น · policy ลงเป็น DRAFT เว้นแต่ PR ผ่านคนอื่น approve | 3–4 wk | ⬜ ผู้ใช้ขอ 2026-09-26 · ต้องมี M14 (PAT) · ต่อยอด FR-9 + M17 |
| **M31** | **Purpose** — วัตถุประสงค์การใช้ข้อมูลเป็น object (FR-21) · ฐานกฎหมายตาม PDPA · policy / คำขอ / template / grant / query อ้าง key เดียวกัน · รายงาน "PII ถูกใช้เพื่ออะไร" | – | 🟡 **M31a ✅ 2026-09-28** (ข้อ CN) — ทะเบียน purpose + ฐานกฎหมาย + ประวัติ · picker ใน policy / template / ฟอร์มขอ / Pre-authorize / Query / Simulator · server ตรวจทุกที่ · ระยะสิทธิ์สูงสุดบังคับที่คำขอ · **M31b slice 1 ✅ 2026-09-29** (ข้อ CO) — ตั้งได้ว่าอะไรนับเป็นข้อมูลอ่อนไหว + Off / Warn / Enforce ที่ Query และคำขอ · **slice 2a ✅ 2026-09-29** (ข้อ CS) — grant เก็บ purpose + อายุไม่เกินของ purpose · **เหลือ slice 2b**: รายงาน PII ใช้เพื่ออะไร |
| **M32** | **Access Review** — รอบทบทวนสิทธิ์ (FR-22) · campaign + รายการต่อ grant · keep / revoke / shorten + เหตุผล · เห็นวันที่ใช้ล่าสุด · หลักฐานให้ auditor | – | ⬜ ผู้ใช้ขอ 2026-09-28 · ใช้ query log ของ M10 · ดีขึ้นเมื่อมี M31 |
| **M33** | **Project / Workspace** (FR-23) — ทีม + purpose + table + วันจบ · project เป็น principal · ออกจาก project สิทธิ์หาย | – | ⬜ ผู้ใช้ขอ 2026-09-28 · ต้องมี M31 · รวม M27 |
| **M34** | **Rule ที่ใช้ซ้ำได้** (FR-24) — mask / row filter ตั้งชื่อครั้งเดียว หลาย policy อ้าง · impact + ประวัติ (M17) ตอนแก้ | – | ⬜ ผู้ใช้ขอ 2026-09-28 · ทำแยกได้ |
| **M35** | **Policy test case** (FR-25) — ผลที่คาดต่อคน × table รันผ่าน Simulator ทุกครั้งที่ save / activate และทุกคืน | – | ⬜ ผู้ใช้ขอ 2026-09-28 · ทำแยกได้ · M30b ใช้ชุดเดียวกัน |
| **M36** | **Service account / API client** (FR-26) — principal ที่ไม่ใช่คน · เจ้าของ · scope · token hash + วันหมดอายุ | – | ⬜ ผู้ใช้ขอ 2026-09-28 · ต้องมีก่อน M14 / M21 / M29 / M30 |

**ลำดับ:** M0 → M1 → M2 → M3 → (M4 ‖ M5 ‖ M6 ‖ M7) → M7b → M8
หลัง M3 fix `PolicyDecision` แล้ว **compiler 3 ตัวทำขนานกันได้** — นี่คือผลตอบแทนของการลงทุนทำ Policy IR ตั้งแต่ต้น

### Definition of Done — Phase 1
1. Sync จาก OM ครบทุก governance object ถึงระดับ column + webhook ทำงานจริง
2. สร้าง **global policy** "mask ทุก column ที่ tag = `PII.Sensitive` ยกเว้น `clearance >= L2` ในเวลาทำการ" จาก UI ได้
3. สร้าง **local policy** ระดับ schema/table ทับได้ และ compose ถูกต้อง (เข้มขึ้นได้ / ผ่อนไม่ได้)
4. Simulator แสดง SQL + preview + policy ที่ match พร้อมบอกว่ามาจากชั้นไหน
5. **Enforcement ครบ 3 โหมด** ใช้งานจริงบนทั้ง PG และ MSSQL
6. **Cross-mode consistency test ผ่าน** — 3 โหมดให้ผลเหมือนกันทุก byte (ยกเว้นที่ capability matrix ประกาศไว้ล่วงหน้า)
7. Audit log ตอบได้ว่าใครเห็นอะไร เพราะ policy ไหน ผ่านโหมดไหน

---

## 7. วิธีรัน (สำคัญ — เก็บไว้ใช้รอบหน้า)

```bash
# Backend build (ต้องมี -am เสมอ ไม่งั้น module ต้นน้ำไม่ถูก build)
JAVA_HOME="$PWD/.tools/jdk-21.0.12.1+1" ./mvnw -q -am -pl backend/dac-service package -DskipTests
JAVA_HOME="$PWD/.tools/jdk-21.0.12.1+1" ./mvnw -am -pl backend/dac-service test
# รันเทสต์ตัวเดียว: -Dsurefire.failIfNoSpecifiedTests=false (ไม่ใช่ -DfailIfNoSpecifiedTests)

# Backend run — .env ไม่มี loader ต้อง export เอง
set -a && . ./.env && set +a
"$PWD/.tools/jdk-21.0.12.1+1/bin/java" -jar backend/dac-service/target/dac-service.jar server conf/dac.yml
#   API   http://127.0.0.1:8080/api/...   (conf/dac.yml ตั้ง rootPath: /api/*)
#   Admin http://127.0.0.1:8081/ping

# App DB (docker)
docker run -d --name dac-appdb -e POSTGRES_USER=dac -e POSTGRES_PASSWORD=... -e POSTGRES_DB=dac -p 5432:5432 postgres:16-alpine

# Frontend — ห้ามใช้พอร์ต 3000 (listen EACCES บนเครื่องนี้)
cd frontend/app && yarn dev --port 5274
```

**Endpoint ที่มีตอนนี้**
```
GET  /api/v1/auth/config      POST /api/v1/auth/login    GET /api/v1/auth/me
POST /api/v1/auth/password    GET  /api/v1/system/version
GET  /api/v1/sync/openmetadata   POST /api/v1/sync/openmetadata
POST /api/v1/webhooks/openmetadata      ← HMAC เท่านั้น ไม่มี JWT
```

**ตัวแปรใน `.env` (gitignored — `.env.example` มีแต่ placeholder)**
`APP_DB_*` · `OM_BASE_URL` `OM_JWT_TOKEN` `OM_EXPECTED_VERSION` `OM_FAIL_ON_VERSION_MISMATCH` · **`OM_WEBHOOK_SECRET`** `OM_POLL_ENABLED` `OM_POLL_INTERVAL_SECONDS` `OM_MAX_EVENTS_PER_POLL` `OM_RECONCILE_ENABLED` `OM_RECONCILE_AT` `OM_RECONCILE_ZONE` · `IDENTITY_*` (JWT secret, TTL, lockout, bootstrap admin password) · `SECRETS_PROVIDER` `FERNET_KEY`

**ทดสอบ webhook ด้วยมือ**
```bash
set -a && . ./.env && set +a
BODY='{"eventType":"entityUpdated","entityType":"table","entityFullyQualifiedName":"s.db.dbo.a","timestamp":1}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$OM_WEBHOOK_SECRET" -r | cut -d' ' -f1)
curl -i -X POST -H "X-OM-Event-Signature: sha256=$SIG" -H 'Content-Type: application/json' \
     -d "$BODY" http://127.0.0.1:8080/api/v1/webhooks/openmetadata
```

---

## 8. เรื่องที่ยังค้าง / ต้องตัดสินใจ

| เรื่อง | รายละเอียด | บล็อกอะไร |
|---|---|---|
| **Connection database จริง** | ผู้ใช้จะให้ทีหลัง | FR-1.6, M5–M7 (งาน backend อื่นไม่บล็อก) |
| **SQL Server version** | ต้อง 2022+ ถึงจะ `GRANT UNMASK` ระดับ column ได้ | M6 |
| **PostgreSQL `anon` extension** | ลงได้ไหมบน production | M6 |
| **Identity propagation** | ADR-0002 เลือก **Option A (per-user DB principal)** แล้ว — ต้องได้รับอนุญาตให้สร้าง/จัดการ DB login และ `ALTER` object บน production จริง มิฉะนั้น 5.1.1/5.1.2 ใช้ไม่ได้ | M5, M6 |
| **หน้าเปลี่ยนรหัสผ่าน** | `mustChangePassword` ไหลถึง frontend แล้วแต่ยังไม่มีหน้าจอ | UX ของ local auth |
| **Catalog UI** | `/catalog` ยังเป็น `NotBuiltYetPage` badge M1 | ปิด M1 |
| `AssetStoreIT` | เขียนไว้แล้วแต่ยังไม่เคยรัน (Docker พร้อมแล้ว) · ควรเขียน `GovernanceStoreIT` ด้วย | ความมั่นใจของ M1 |
| OM client generation | generate จาก spec เต็ม 3.7 MB (104 API / 974 model) ทำให้ build ช้า — จะ narrow ไหม | ความเร็ว build |
| ไฟล์โลโก้ | PNG ที่ root ยัง untracked — จะเอาเข้า repo ไหม | — |
| `vite.config.ts` | ยัง default พอร์ต 3000 ที่ใช้ไม่ได้บนเครื่องนี้ | DX |

**ความปลอดภัยที่ต้องถือไว้ตลอด**
- repo เป็น **public** → ก่อน commit ทุกครั้งต้อง scan หา password, JWT, IP/hostname ภายใน
- credential ของ OM ที่เคยวางใน chat **ควร rotate** และควรเปลี่ยนไปใช้ **bot token** แทน account คน
- bootstrap password ของ admin (`IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`) เป็นของ dev เท่านั้น อยู่ใน `.env` — ห้ามเขียนค่าจริงลงไฟล์ที่ commit หรือใน commit message
- `OM_WEBHOOK_SECRET` ก็อยู่ใต้กติกาเดียวกัน

---

## 9. Phase 2+

Data Access Workflow เต็มรูปแบบ (ขอสิทธิ์เอง → routing หา approver จาก owners → approval chain → recertification ทุก 90 วัน → break-glass → notification/inbox) · **Wire-protocol gateway** (pgwire แล้วค่อย TDS ให้ Power BI/Tableau/DBeaver ต่อตรง) · source เพิ่ม (Snowflake, Databricks, BigQuery, Oracle, Trino) · ต่อยอด auto-classification ของ OM แล้วเขียน tag กลับ · purpose-based access + project workspace · masking ขั้นสูง (FPE, k-anonymity, differential privacy) · data sharing / external user · GitOps · policy conflict static analyzer

---

## 10. ADR

| ADR | เรื่อง |
|---|---|
| [0001](adr/0001-mirror-openmetadata-stack.md) | เลียนแบบสแตกของ OpenMetadata 2.0.1 (ไม่ใช้ Spring Boot, ไม่ใช้ antd) |
| [0002](adr/0002-identity-propagation.md) | Identity propagation — เลือก Option A (per-user DB principal) |
