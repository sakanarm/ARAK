# ARAK — Data Access Control Platform · Design & Feature Catalogue

> เอกสารนี้คือ **บันทึกความจำของโปรเจกต์** — รวม requirement ทุกข้อ, การตัดสินใจที่ตกลงแล้ว, สถานะงานจริง ณ ปัจจุบัน และวิธีรันระบบ
> ถ้าเปิดคุยรอบใหม่ (หรือคนใหม่เข้าทีม) ให้เริ่มอ่านจากไฟล์นี้ก่อน แล้วค่อยไปอ่าน [policy-spec.md](policy-spec.md) และ [adr/](adr/)
>
> อัปเดตล่าสุด: 2026-09-16 · สถานะ: M1 กำลังทำ (FR-1.5 เสร็จ) · Repo: https://github.com/sakanarm/ARAK (**public**)

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
| 6 | **Enforcement ครบ 3 โหมดใน Phase 1** | 5.1.1 native config / 5.1.2 secure view / 5.2 query API — ผู้ใช้ยืนยันว่า "5.1.1 กับ 5.1.2 ต้องใช้งานได้" |
| 7 | **สแตกและ UI เลียนแบบ OpenMetadata 2.0.1** | dev ที่ดูแล OM อ่าน code เราออกทันที และ reuse design token / component ได้ |
| 8 | **Secret อยู่ใน `.env` ที่ gitignore เท่านั้น** | repo เป็น **public** → ทุก commit ต้อง scan ก่อน push · ห้ามมี password / token / IP ภายใน ในไฟล์ที่ commit |

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
| FR-1.7 | สร้าง local tag/classification เอง + `provenance` + ปุ่ม push กลับเข้า OM | ⬜ | — |
| FR-1.8 | Effective facet computation พร้อม `inherited_from` (ไม่พึ่ง tag propagation ของ OM) | ✅ | `om/facet/FacetInheritance.java` · `asset_facet` |
| FR-1.9 | Sync **นิยาม** custom property (type, enum values) เพื่อให้ Policy Builder แสดง operator ถูกชนิด | ✅ | `custom_property_def` · `GovernanceMapper` |

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
| FR-2.2 | Local user / group / attribute (สำหรับ test + service account) | ✅ บางส่วน — local sign-in ใช้ได้แล้ว (`local_credential`, bcrypt, lockout, must-change) |
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
| FR-3.1.6 | Materialize selector → `policy_binding` + re-resolve เมื่อ asset/facet/policy เปลี่ยน | ⬜ |
| FR-3.2 | SubjectRule = predicate เดียว (principals + attributes + expr + time + context) | ✅ `SubjectMatcher`, `TimeMatcher`, `ContextMatcher` |
| FR-3.2a | `assetOwner: true` — dynamic subject จาก owners ของ asset | ✅ |
| FR-3.3 | `ALLOW` / `DENY` — **DENY ชนะเสมอ, default deny** | ✅ |
| FR-3.4 | field `requiresApproval` / `approvers` / `validUntil` มีใน model แล้ว (workflow อยู่ Phase 2) | ✅ schema |
| FR-3.5 | Exemption list + **บังคับใส่วันหมดอายุ** | ✅ schema · ⬜ job |

### FR-4 Data Policy (RLS + Masking) — M3 ✅ schema+engine / ⬜ compiler

| # | Feature | สถานะ |
|---|---|---|
| FR-4.1 | Row-Level Security: เทียบ column กับ user attribute, `IN` จาก multi-value, entitlement table join, `ALWAYS FALSE` | ✅ schema · ⬜ compiler |
| FR-4.2 | เลือก column ที่จะ mask ด้วย **ทุก facet** (name/pattern, classification, tag, glossary, term, dataType, custom property) + ผสม AND/NOT | ✅ |
| FR-4.3 | Cell masking = column mask + row condition | ✅ schema (`ROW_DEPENDENT`) · ⬜ compiler |
| FR-4.4 | Masking library: `NULLIFY`, `CONSTANT`, `HASH` (SHA-256 + salt ต่อ column), `PARTIAL`, `REGEX_REPLACE`, `ROUNDING`, `CONDITIONAL` | ✅ schema + `MaskStrength` · ⬜ SQL |
| FR-4.5 | ซ่อน column ทั้งคอลัมน์ (ไม่โผล่ใน schema) | ✅ `hiddenColumns` |

### FR-5 Policy Engine — M3

| # | Feature | สถานะ |
|---|---|---|
| FR-5.1 | Conflict resolution: DENY ชนะ · row filter **AND** กัน · mask เข้มสุดชนะ (`NULLIFY > CONSTANT > HASH > REGEX > PARTIAL > ROUNDING > plaintext`) · ไม่ match = deny | ✅ `PolicyEngine`, `MaskStrength` (72 tests ผ่าน) |
| FR-5.2 | **Simulator / "View as user"** — SQL ที่จะ generate + preview data + policy ที่ match | ⬜ (M4) |
| FR-5.3 | Impact analysis ก่อน publish global policy | ⬜ (M4) |
| FR-5.4 | Explainability — ทุก decision บอกได้ว่าเพราะ policy ตัวไหน เงื่อนไขข้อไหน ชั้นไหน | ✅ `decisionReason` ใน `PolicyDecision` |
| FR-5.5 | Decision cache + invalidate เมื่อ policy/attribute/tag เปลี่ยน (< 10ms cached / < 100ms cold) | ⬜ (มี `cacheKey`, `fromCache` ใน schema แล้ว) |

### FR-6 Enforcement — ทั้ง 3 โหมดเป็น first-class เท่ากัน

| | **5.1.1 Native config** (M6) | **5.1.2 Secure view** (M5) | **5.2 App proxy** (M7) |
|---|---|---|---|
| บังคับใช้ที่ | object เดิม (RLS/DDM/GRANT) | view ใหม่ `*_secure` | ชั้น app ตอน runtime |
| user query ที่ไหน | **ตารางชื่อเดิม** | ต้องชี้ไป view ใหม่ | endpoint ของเรา |
| ต้องมี DB login ต่อคน | ต้องมี | ต้องมี (หรือผ่าน proxy) | ไม่ต้อง |
| แตะ object เดิม | **ใช่ — ALTER production** | ไม่ (แค่ REVOKE) | ไม่แตะ |
| Cell mask | ❌ | ✅ | ✅ |
| BI ต่อตรง | ✅ | ✅ | ❌ (รอ 5.2b) |
| สถานะ | ⬜ | ⬜ | ⬜ |

- **FR-6.0a** เลือกโหมดได้ต่อ source/asset และ **ผสมกันได้** (เช่น RLS ด้วย 5.1.1 + masking ด้วย 5.1.2) ⬜
- **FR-6.0b** **Capability matrix** ต่อ engine/เวอร์ชัน + เตือนตอนเลือกโหมดว่า policy ข้อไหน enforce ไม่ได้ ⬜ (ตาราง `engine_capability` มีแล้ว)
- **FR-6.0c** **Cross-mode consistency** — asset+policy เดียวกัน 3 โหมดต้องได้ผลเหมือนกันทุก byte + test ใน CI ⬜ (M7b)
- **FR-6.4** Dry-run **เสมอ** · rollback script ทุกครั้ง · **drift detection** ทุก N ชม. · state ต่อ asset: `NOT_ENFORCED / PENDING / APPLIED / DRIFTED / FAILED` ⬜

**ข้อจำกัดที่ต้องบอกผู้ใช้ตรงๆ:**
- SQL Server DDM mask แบบ conditional ต่อ user ไม่ได้ → **cell masking ทำไม่ได้ในโหมด 5.1.1**
- SQL Server ต้อง **2022+** ถึงจะ `GRANT UNMASK` ระดับ column ได้ (รุ่นเก่าเป็น db-wide = ใช้จริงไม่ได้) — **ยังไม่ยืนยันเวอร์ชัน production**
- PostgreSQL ไม่มี column masking ใน core → ต้องลง extension `anon` (managed service หลายเจ้าไม่ให้) — **ยังไม่ยืนยันว่าลงได้ไหม**
- โหมด 5.2 ถูก bypass ได้ถ้าต่อ DB ตรง → ต้อง firewall + ระบบต้องตรวจและเตือน (FR-6.3.1)

### FR-7 Manual Grant (Phase 1) — M8 ⬜
FR-7.1 owner สร้าง grant ตรงๆ พร้อม `validFrom`/`validUntil` + เหตุผล · FR-7.2 job auto-revoke · FR-7.3 หน้า "สิทธิ์ของฉัน" / "ใครมีสิทธิ์ใน asset นี้"
(ตาราง `access_grant` มี `source` = manual|request และ `request_id` nullable ไว้แล้วเพื่อไม่ต้อง migrate ตอน Phase 2)

### FR-8 Audit & Compliance — M8 ⬜
policy change log (append-only, ค่าเดิม→ค่าใหม่) · access decision log · query log (SQL ต้นฉบับ + หลัง rewrite) · export ไป SIEM · compliance report ("ใครเข้าถึง PII ได้บ้าง", "table ที่มี tag PII แต่ยังไม่มี policy", "สิทธิ์ที่ไม่ได้ใช้เกิน 90 วัน")

### FR-9 Policy Lifecycle — ⬜
state `DRAFT → PENDING_APPROVAL → ACTIVE → DISABLED → ARCHIVED` ✅ (มีใน schema) · version + diff + rollback (ตาราง `policy_version` มีแล้ว) · **Policy-as-Code** export/import YAML · แยก environment dev/uat/prod + promote

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

| M | งาน | ประเมิน | สถานะ |
|---|---|---|---|
| **M0** | Maven multi-module + Dropwizard skeleton · Vite+React+Tailwind shell + vendor ui-core-components · JSON Schema codegen · OM client จาก swagger · Flyway · docker-compose · CI | 3 wk | ✅ **เสร็จ** |
| **M1** | OM connector: REST client · entity mapper ครบทุก governance object · FQN mapping · full crawl · **webhook + poller** · `asset_facet` + effective facet · nightly reconcile · **Catalog UI** | 4 wk | 🚧 **~85%** — เหลือ Catalog UI + FR-1.6 + FR-1.7 |
| **M2** | Entra OIDC · Graph sync · LocalProvider · OmTeamProvider · AttributeResolver · app RBAC | 2 wk | ⬜ (local auth ทำไปแล้ว) |
| **M3** | Policy IR · AssetSelector resolver + `policy_binding` materializer · SubjectRule evaluator · layered composer · ConflictResolver · decision cache · Simulator | 5 wk | 🚧 **engine เสร็จ (72 tests)** — เหลือ persistence, binding materializer, cache, ANTLR |
| **M4** | Policy Authoring UI (global + local builder, data policy builder, หน้า effective policy, view-as-user, impact analysis) | 4 wk | ⬜ |
| **M5** | **5.1.2 Secure View** — ViewCompiler + dialect · `row_entitlement` maintainer · `DbPrincipalProvisioner` · cutover helper · dry-run/rollback · golden-file + Testcontainers | 4 wk | ⬜ |
| **M6** | **5.1.1 Native Config** — PG RLS + column GRANT + `anon` · MSSQL Security Policy + DDM + UNMASK + `CREATE USER FROM EXTERNAL PROVIDER` · capability matrix | 3 wk | ⬜ |
| **M7** | **5.2a Query API** — JSqlParser rewrite · table resolution (CTE/sub-query/`SELECT *`) · fail-closed · stream · row limit/timeout · direct-access detector | 3 wk | ⬜ |
| **M7b** | Cross-mode consistency harness + CI | 1 wk | ⬜ |
| **M8** | Audit 3 ตาราง · DriftDetector + re-apply · manual grant + auto-revoke · compliance report · metrics · Vault | 3 wk | ⬜ |

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
