# HANDOFF — ARAK (Data Access Control Platform)

> อัปเดต: 2026-09-16 · commit ล่าสุด `757ba33` (push แล้ว) · **รอบล่าสุด: catalog read API + Catalog UI** · repo https://github.com/sakanarm/ARAK (**public**)
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
| **M1 OM Connector** | 🚧 ~92% — full crawl + governance + effective facet + FR-1.5 webhook/poller/reconcile + **catalog read API + Catalog UI (`/catalog`, `/catalog/<fqn>`) เสร็จแล้ว** · เหลือ FR-1.6 (reconcile กับ JDBC จริง — **รอ connection จริง**), FR-1.7 (local tag + push-back) |
| **M2 Identity** | ⬜ ยังไม่เริ่ม (มีแต่ local sign-in ที่ใช้ได้แล้ว) |
| **M3 Policy Engine** | 🚧 engine เสร็จ (72 tests ผ่าน) · เหลือ persistence, `policy_binding` materializer, decision cache, ANTLR grammar ของ `expr` |
| **M4–M8** | ⬜ |

**ที่รันอยู่ตอนนี้**
| | |
|---|---|
| Backend (Dropwizard, jar ใหม่) | `:8080` (API อยู่ใต้ `/api`), admin `:8081/ping` |
| Frontend (Vite) | `http://127.0.0.1:5274/` |
| App DB (docker `dac-appdb`, postgres:16-alpine) | `:5432` db/user `dac` |

เทสต์ทั้งหมดเขียว

| ชุด | จำนวน | คำสั่ง |
|---|---|---|
| Backend unit | dac-common 6 · dac-engine 72 · dac-connector-openmetadata 88 · dac-service 25 | `./mvnw -am -pl backend/dac-service test` |
| **Backend integration** (Testcontainers `postgres:16-alpine`) | **`AssetStoreIT` 6 · `CatalogQueryIT` 15** | `./mvnw -am -pl backend/dac-service verify -Pintegration` |
| Frontend | **4 suites / 14 tests** — LoginPage · SystemStatusPage · **CatalogPage 5** · **AssetDetailPage 5** | `yarn test` ใน `frontend/app` |

`yarn type-check` · `yarn lint` · `yarn build` ผ่านหมด → **BUILD SUCCESS** ทั้งสองฝั่ง

---

## รอบล่าสุดทำอะไรไป — Catalog read API + Catalog UI (ปิดงาน M1 ส่วน UI)

### Backend — ฝั่ง "อ่าน" catalog ที่ก่อนหน้านี้ไม่มีเลย

`AssetStore` / `GovernanceStore` เป็น sink ของ crawl อย่างเดียว (write-only) → จะทำ Catalog UI ต้องสร้าง query layer ขึ้นมาก่อนทั้งชั้น

| ไฟล์ | หน้าที่ |
|---|---|
| `backend/dac-service/.../catalog/CatalogQuery.java` | **ใหม่** — read side ทั้งหมด · แยกจาก `AssetStore` โดยตั้งใจ (policy selector อยากได้ index lookup ตัวเดียว แต่หน้าจออยากได้ asset ทั้งหน้าพร้อม facet/owner) |
| `backend/dac-service/.../resources/CatalogResource.java` | **ใหม่** — `@Path("/v1/catalog")` + `@Secured` (authenticated ใครก็อ่านได้ เพราะเห็นแค่ "รูปร่าง" ของข้อมูล ไม่ใช่ตัวข้อมูล) · **read-only แม้กับ platform admin** เพราะ OM เป็นเจ้าของเนื้อหา แก้ตรงนี้เดี๋ยว sync รอบหน้าทับ |
| `backend/dac-service/.../DacApplication.java` | register `CatalogResource` |
| `backend/dac-service/src/test/.../catalog/CatalogQueryIT.java` | **ใหม่** — 15 tests บน Postgres จริง |

Endpoint (อยู่ใต้ `/api` ทั้งหมดเพราะ `rootPath: /api/*`):

| Method | Path | หมายเหตุ |
|---|---|---|
| GET | `/api/v1/catalog/assets` | `q`, `type`, `facet` (ซ้ำได้ รูปแบบ `<type>:<fqn>`), `owner`, `limit`=50 (max 500), `offset` |
| GET | `/api/v1/catalog/assets/{fqn}` | path เป็น `{fqn: .+}` · ไม่มีใน cache = **404** |
| GET | `/api/v1/catalog/facets` | `type`, `limit`=100 → `{"values":[…]}` สำหรับ filter menu |
| GET | `/api/v1/catalog/summary` | ยอดรวมทั้ง cache (asset ต่อชนิด, column, tagged, asset ที่ไม่มี owner) |

การตัดสินใจที่สำคัญ:
- **facet filter เป็น AND ไม่ใช่ OR** — `EXISTS` หนึ่งอันต่อหนึ่ง filter และ **เผื่อไปถึง facet ของ column ด้วย** (`f.asset_id = a.id OR fc.asset_id = a.id`) เพราะคนเขียน policy มักรู้ว่า "ตารางนี้มีข้อมูลอ่อนไหว" แต่ไม่รู้ว่าอยู่ column ไหน
- **พิสูจน์แล้วว่าการกาง ancestor ไว้ล่วงหน้าคุ้มบน read path ด้วย** — `domains:Finance` เจอ asset ที่อยู่ `Finance.Risk.Credit` ด้วย equality ธรรมดา ไม่ต้อง recursive query (FR-2A.2)
- อ่านเฉพาะแถว `is_current` → asset ที่ retire หายจากลิสต์ แต่แถวเดิมยังอยู่ให้ audit
- `taggedColumnCount` นับ `count(DISTINCT f.column_id)` ไม่ใช่นับแถว facet
- facet ที่รูปแบบพัง (`facet=abc`) **ถูกข้ามเงียบ ไม่ตอบ 400** — filter อื่นยังทำงาน

### Frontend — `/catalog` และ `/catalog/<fqn>`

| ไฟล์ | หน้าที่ |
|---|---|
| `frontend/app/src/api/client.ts` | เพิ่ม type + fetcher ของ catalog · **ใช้ `URLSearchParams` ไม่ใช่ axios `params`** เพราะ axios จะยุบ array เป็น `facet[]=` แล้ว backend มองไม่เห็น filter เลยสักอัน |
| `frontend/app/src/pages/catalog/facets.tsx` | **ใหม่** — `FacetChip` / `OwnerChip` / `groupFacets` / `listFacets` / `FACET_LABELS` / `FILTERABLE_FACETS` · สีต่อชนิด facet คงที่ทุกหน้าจอ |
| `frontend/app/src/pages/catalog/CatalogPage.tsx` | **ใหม่** — search + type filter + facet picker + paging + summary header |
| `frontend/app/src/pages/catalog/AssetDetailPage.tsx` | **ใหม่** — header, governance แยกกลุ่ม, owners, custom properties, ตาราง column พร้อม facet ของแต่ละ column |
| `frontend/app/src/App.tsx` | route `/catalog` และ `/catalog/*` |
| `frontend/app/src/layout/navigation.ts` | Catalog `milestone: 'M1'` → `null` (เลิกเป็น placeholder) |
| `frontend/app/src/__mocks__/fileMock.cjs` + `jest.config.cjs` | map ไฟล์รูปให้ Jest (ดู What Didn't Work) |
| `CatalogPage.test.tsx` · `AssetDetailPage.test.tsx` | **ใหม่** — 10 tests |

การตัดสินใจที่สำคัญ:
- **filter state อยู่ใน URL** (`?q=&type=&facet=&offset=`) → ลิงก์เดียวส่งต่อผลการกรองให้คนอื่นเปิดเห็นเหมือนกัน
- เปลี่ยน filter ใดๆ = กลับหน้าแรกเสมอ (ไม่งั้นค้างหน้า 4 ของผลลัพธ์ที่แคบลงแล้วเห็นตารางว่าง อ่านเหมือน "ไม่เจออะไรเลย")
- **facet ที่ inherit มา วาดจางลง + มี `↑` + `title` บอกว่ามาจากไหน** (FR-2A.1) · **`Suggested` วาดสีเทา + `?`** เพราะ default ไม่ enforce (FR-1.3a) — ถ้าวาดเหมือน `Confirmed` คนจะเข้าใจผิดว่าข้อมูลถูกป้องกันแล้ว
- facet เชิงกายภาพ (service/database/schema/columnName/dataType) **ไม่โชว์ในลิสต์** (ซ้ำกับ FQN) แต่ **โชว์ในหน้า asset** เพราะเป็นคำตอบของ "policy ที่เขียนว่า `schema = dbo` ครอบอะไรบ้าง"
- route ใช้ **splat `/catalog/*` ไม่ใช่ `:fqn`** — FQN มีจุด และชื่อ service บางตัวมี `/`
- หน้า asset ที่ **ไม่มี owner เตือนเป็นสีเหลือง** เพราะแปลว่าไม่มีใครเขียน local policy ให้ asset นั้นได้ (FR-3.1.2)
- 404 ของหน้า asset เขียนชัดว่า **"ไม่อยู่ใน cache ≠ ไม่มีใน OpenMetadata"** เพราะทางแก้คนละเรื่องกัน (sync vs. ไปคุยกับทีม catalog)

ยังไม่มีในหน้า asset (ตั้งใจ รอ milestone): **policy ที่มีผลกับ asset นี้** (FR-3.1.5 → M3) และ **enforcement state** (M5)

---

## What Worked

- **re-read by FQN แทนการ replay payload ของ event** — ทำให้ apply ซ้ำฟรี → poller rewind cursor 1 นาทีได้โดยไม่ต้องจำ event id และ webhook รับ redelivery ได้
- **`AssetRefresher` เข้าไปใช้ descent ของ `AssetCrawler` กลางทาง** (เปิด 3 method เป็น package-private) แทนการเขียน inheritance ซ้ำ → logic อยู่ที่เดียว
- **แยก 404 ออกจาก error อื่น** — 404 = race ปกติ (`MissingAncestorException`, ข้าม) · error อื่น = failed (cursor ไม่ขยับ)
- **retire ด้วย prefix ที่ผูกจุดและ escape LIKE** (`fqn + ".%"`, escape `_ % \`) — กันลบ `prod.Sales` แล้วลาก `prod.SalesArchive` ไปด้วย
- **webhook ไม่ใส่ `@Secured`** (Jersey `@NameBinding` — resource ที่ไม่มี annotation คือ unauthenticated โดยตั้งใจ) แล้วใช้ HMAC แทน · unconfigured = **503 ไม่ใช่รับทุกอย่าง**
- **รับ body เป็น raw `String`** เพราะ signature เซ็นบน byte จริง — ถ้าให้ Jersey deserialize ก่อนจะเทียบไม่ตรง
- **cursor hold เมื่อ apply ไม่ผ่าน + เพดาน 10 รอบ** — ได้ทั้ง "ไม่เสียของเงียบๆ" และ "ไม่ค้างถาวรจน window โตไม่หยุด"
- **แยก `CatalogQuery` (read) ออกจาก `AssetStore` (write)** แทนที่จะเพิ่ม method อ่านเข้าไปใน writer — คนละ query shape คนละเหตุผลในการเปลี่ยน
- **สองรอบ query ต่อหนึ่งหน้า** (asset หนึ่งรอบ · facet + owner อีกรอบโดย key ด้วย FQN ที่เพิ่งได้มา) — ไม่ใช่ N+1 และไม่ใช่ join ที่ทำให้แถว asset ซ้ำตามจำนวน facet
- **เก็บ filter state ไว้ใน URL** — หน้าผลการกรอง governance เป็นของที่คนส่งต่อกัน ลิงก์มีค่ากว่า scroll position
- เขียนไฟล์ Java ขนาดใหญ่ด้วย **Write tool หรือ python heredoc** เชื่อถือได้

## What Didn't Work

- ❌ **Bash heredoc เขียนไฟล์ Java ใหญ่** — พังด้วย `unexpected EOF while looking for matching` ทั้งที่ใช้ `<<'JAVA'` → ใช้ Write tool / python แทน
- ❌ **curl ไปที่ `/v1/...` ตรงๆ** — ได้ 404/405 เพราะ `conf/dac.yml` ตั้ง `rootPath: /api/*` → ต้องเป็น `/api/v1/...`
- ❌ **ลืม restart backend หลัง build** — โปรเซสเก่ายังถือ jar เดิม ทำให้ endpoint ใหม่ไม่โผล่
- ❌ **`./mvnw` โดยไม่มี `-am`** — module ต้นน้ำไม่ถูก build
- ❌ **`-DfailIfNoSpecifiedTests=false`** — flag ที่ถูกคือ `-Dsurefire.failIfNoSpecifiedTests=false`
- ❌ **Vite พอร์ต 3000** — `listen EACCES` บนเครื่องนี้ ต้องส่ง `--port` ทุกครั้ง (ใช้ 5274)
- ❌ **`.env` ไม่ถูกโหลดเอง** — ไม่มี dotenv loader ต้อง `set -a && . ./.env && set +a` ก่อนรัน
- ❌ **`sleep 25 && tail`** ถูก harness บล็อก → ใช้ `until <check>; do sleep 2; done`
- ❌ **`TRUNCATE asset, …` ใน IT ล้มทั้งชุด** — `PSQLException: cannot truncate a table referenced in a foreign key constraint` เพราะ `policy_binding` (V3) และ `enforcement_state` (V4) อ้าง `asset(id)` · `AssetStoreIT` **ไม่เคยถูกรันมาก่อน** ของพังจึงเพิ่งโผล่ตอนนี้ · แก้โดย **ไล่ชื่อตารางให้ครบ ไม่ใช้ `CASCADE`** — ตารางที่เพิ่มมาทีหลังจะได้ fail ดังๆ ตรงนี้ ไม่ใช่ถูกล้างเงียบๆ โดยเทสต์ที่ไม่ได้ตั้งใจแตะ
- ❌ **`assertThat(jdbi.withHandle(…))` → `reference to assertThat is ambiguous`** — inference เลือกไม่ได้ระหว่าง `IntPredicate` / `Predicate<T>` · แก้โดยดึงออกมาเป็น local ที่ type ชัด (`int rowsForOrder = …`)
- ❌ **Jest พังทั้ง suite เพราะ `import logo from '…png'`** — `LoginPage.test.tsx` ตายมาตั้งแต่ตอนใส่โลโก้ (`SyntaxError: Invalid or unexpected token` ที่ไฟล์ PNG) แต่ไม่มีใครเห็นเพราะรันเฉพาะ suite ที่เพิ่งแก้ · แก้ด้วย `moduleNameMapper` → `src/__mocks__/fileMock.cjs` · **บทเรียน: รัน `yarn test` เต็มชุดทุกครั้ง ไม่ใช่เฉพาะไฟล์ที่แตะ**
- ❌ **`type="badge-modern"` ของ `Badge`** — ค่าที่ถูกคือ `type="modern"` (`badgeTypes = { pillColor: 'pill-color', badgeColor: 'color', badgeModern: 'modern' }`)
- ❌ **`onClick` บน `Button` ของ design system** — เป็น react-aria `Button` ต้องใช้ **`onPress`** (`<button>` ธรรมดายังใช้ `onClick` ตามปกติ)
- ❌ **`@testing-library/user-event` ไม่ได้ติดตั้งในเรโป** — ใช้ `fireEvent` แทน
- ❌ Playwright browsers ไม่ได้ติดตั้ง — ใช้ `chromium.launch({ channel: 'msedge' })` และ script ต้องอยู่ใน `frontend/app/`

---

## Next Steps

1. ~~Catalog UI~~ ✅ เสร็จรอบนี้ · ~~รัน `AssetStoreIT`~~ ✅ รันแล้ว (เจอของพังจริง แก้แล้ว)
2. **ลอง `/catalog` กับข้อมูลจริง** — cache ยังว่าง ต้อง `POST /api/v1/sync/full` ก่อนถึงจะเห็นอะไร · และเขียน **`GovernanceStoreIT`** ที่ยังไม่มี
3. **FR-1.6** — reconcile cache กับ JDBC introspection จริง + รายงาน orphan / column ใหม่ที่ยังไม่มี policy (**รอ connection database จริงจากผู้ใช้**)
4. **M3 ต่อ** — persistence ของ policy, `policy_binding` materializer (re-resolve เมื่อ facet/asset/policy เปลี่ยน), decision cache, ANTLR grammar ของ `expr`
5. **หน้าเปลี่ยนรหัสผ่าน** — `mustChangePassword` ไหลถึง `auth/authStore.ts` แล้วแต่ไม่มีใครอ่าน (ไม่มี route/guard)
6. **ก่อน M6** ต้องได้คำตอบ: SQL Server production เป็น **2022+** ไหม (ต้องการสำหรับ `GRANT UNMASK` ระดับ column) และลง extension `anon` บน PostgreSQL ได้ไหม

**กติกาที่ต้องถือไว้ทุกครั้งที่ commit:** repo เป็น public → scan หา password / JWT / IP ภายใน ก่อน push เสมอ · ค่าจริงอยู่ใน `.env` ที่ gitignore เท่านั้น · `.env.example` มีแต่ placeholder

**คำสั่งที่ใช้บ่อย** — ดูหัวข้อ 7 ของ [docs/DESIGN.md](docs/DESIGN.md)
