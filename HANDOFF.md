# HANDOFF — ARAK (Data Access Control Platform)

> อัปเดต: 2026-09-19 · commit ล่าสุดที่ push สำเร็จ `e3aaa52` · **local นำหน้าอยู่ — `5267516` (policy persistence + binding + UI) กับ commit ของ `PolicyBindingMaterializerIT` ยังรอ push, `git push` ค้าง ดู What Didn't Work** · repo https://github.com/sakanarm/ARAK (**public**)
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
| **M0 Foundation** | ✅ เสร็จ — Maven multi-module, Dropwizard 5, Vite+React+Tailwind shell, vendor `ui-core-components`, JSON Schema → Java/TS codegen, OM client จาก swagger ที่ pin ไว้, Flyway V1–V8, docker-compose, CI 4 jobs |
| **M1 OM Connector** | 🚧 ~95% — full crawl + governance + effective facet + FR-1.5 webhook/poller/reconcile + catalog read API + Catalog UI + **governance read API + Governance UI** · **sync กับ OM จริงสำเร็จแล้ว** · เหลือ FR-1.6 (reconcile กับ JDBC จริง — **รอ connection จริง**), FR-1.7 (local tag + push-back — **ผู้ใช้สั่ง read-only ตอนนี้**) |
| **M2 Identity** | 🚧 ~35% — local sign-in ใช้ได้ · schema `principal`/`principal_attribute`/`group_member`/`app_role_assignment` มีตั้งแต่ V2 · **read API + หน้า People & attributes เสร็จ** · ยังไม่มี Entra OIDC / Graph sync |
| **M3 Policy Engine** | 🚧 ~88% — engine 78 tests ผ่าน · persistence (`PolicyStore`) + `policy_binding` materializer + REST · **`PolicyBindingMaterializerIT` 10 tests เขียวรอบนี้ → binding path มี integration coverage ครบแล้ว** · เหลือ decision cache (FR-5.5), ANTLR grammar ของ `expr` (FR-3.2) |
| **M4 Policy Authoring UI** | 🚧 ~70% — **Policy list + Policy builder (selector / subject / RLS / masking) + readback + capability matrix เสร็จรอบนี้** · เหลือ "policy ที่มีผลกับ asset นี้" ในหน้า asset (FR-3.1.5), View-as-user (FR-5.2), impact analysis (FR-5.3) |
| **M5–M8** | ⬜ |

**ที่รันอยู่ตอนนี้**
| | |
|---|---|
| Backend (Dropwizard) | `:8080` (API อยู่ใต้ `/api`), admin `:8081/ping` |
| Frontend (Vite) | `http://127.0.0.1:5274/` |
| App DB (docker `dac-appdb`, postgres:16-alpine) | `:5432` db/user `dac` |
| OpenMetadata ของทีม | `2.0.1` — sync ผ่าน **ingestion-bot JWT** (ดู What Didn't Work) |

เทสต์ทั้งหมดเขียว (รันครบเมื่อ 2026-09-19)

| ชุด | จำนวน | คำสั่ง |
|---|---|---|
| Backend unit | dac-common 6 · **dac-engine 78** · dac-connector-openmetadata 88 · dac-service 25 | `./mvnw -am -pl backend/dac-service test` |
| **Backend integration** (Testcontainers `postgres:16-alpine`) | **50 tests** — `AssetStoreIT` 6 · `CatalogQueryIT` 15 · `GovernanceStoreIT` 9 · `PolicyStoreIT` 10 · **`PolicyBindingMaterializerIT` 10** | `./mvnw -am -pl backend/dac-service verify -Pintegration` |
| Frontend | **5 suites / 20 tests** — LoginPage · SystemStatusPage · CatalogPage 5 · AssetDetailPage 5 · **policyLanguage 6** | `yarn test` ใน `frontend/app` |

`yarn type-check` · `yarn lint` · `yarn build` ผ่านหมด → **BUILD SUCCESS** ทั้งสองฝั่ง

---

## รอบล่าสุดทำอะไรไป

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
- ❌ **`type="badge-modern"`** — ค่าที่ถูกคือ `type="modern"` · และ `Badge` ควรส่ง `type` ชัดเจนเสมอเพื่อให้ generic `BadgeColor<T>` inference ทำงาน
- ❌ **Jest พังทั้ง suite เพราะ `import logo from '…png'`** — แก้ด้วย `moduleNameMapper` → `src/__mocks__/fileMock.cjs` · **บทเรียน: รัน `yarn test` เต็มชุดทุกครั้ง**
- ❌ **`@testing-library/user-event` ไม่ได้ติดตั้ง** — ใช้ `fireEvent`
- ❌ Playwright browsers ไม่ได้ติดตั้ง — ใช้ `chromium.launch({ channel: 'msedge' })` และ script ต้องอยู่ใน `frontend/app/`
- ⚠️ MCP connector หลายตัวของ claude.ai ยังไม่ได้ authorize — session แบบ non-interactive ทำ OAuth ไม่ได้ ต้องไปกดใน claude.ai connector settings

---

## Next Steps

1. **push ให้ขึ้น** — local นำหน้า remote อยู่ (remote main ยังอยู่ที่ `e3aaa52`) · secret scan ผ่านแล้วทั้งสอง commit · แก้เรื่อง `git push` ค้างก่อน (ดู What Didn't Work) แล้วยืนยันด้วย `git ls-remote --heads origin`
2. **ปิด M3** — decision cache (FR-5.5) + ANTLR grammar ของ `expr` (FR-3.2)
3. **ปิด M4** — หน้า asset ต้องโชว์ "policy ที่มีผลกับ asset นี้" (มี endpoint `/policies/affecting/{fqn}` รออยู่แล้ว), View-as-user (FR-5.2), impact analysis (FR-5.3)
4. **FR-1.6** — reconcile cache กับ JDBC introspection จริง (**รอ connection database จริงจากผู้ใช้**)
5. **หน้าเปลี่ยนรหัสผ่าน** — `mustChangePassword` ไหลถึง `auth/authStore.ts` แล้วแต่ไม่มีใครอ่าน
6. **ก่อน M6** ต้องได้คำตอบ: SQL Server production เป็น **2022+** ไหม (ต้องการสำหรับ `GRANT UNMASK` ระดับ column) และลง extension `anon` บน PostgreSQL ได้ไหม

**กติกาที่ต้องถือไว้ทุกครั้งที่ commit:** repo เป็น public → scan หา password / JWT / hostname และ IP ภายใน ก่อน push เสมอ · ค่าจริง (`IDENTITY_BOOTSTRAP_ADMIN_PASSWORD`, `OM_WEBHOOK_SECRET`, bot JWT) อยู่ใน `.env` ที่ gitignore เท่านั้น · `.env.example` มีแต่ placeholder
**ข้อจำกัดที่ผู้ใช้สั่งไว้:** ต่อ OpenMetadata **read อย่างเดียว** ตอนนี้ — ห้าม PATCH กลับ (FR-1.7 จึงยังไม่ทำ)

**คำสั่งที่ใช้บ่อย** — ดูหัวข้อ 7 ของ [docs/DESIGN.md](docs/DESIGN.md)
