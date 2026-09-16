# HANDOFF — ARAK (Data Access Control Platform)

> อัปเดต: 2026-09-16 · commit ล่าสุด `3f9b91e` (push แล้ว) · repo https://github.com/sakanarm/ARAK (**public**)
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
| **M1 OM Connector** | 🚧 ~85% — full crawl + governance + effective facet + **FR-1.5 webhook/poller/reconcile เสร็จและ push แล้ว** · เหลือ **Catalog UI**, FR-1.6 (reconcile กับ JDBC จริง), FR-1.7 (local tag + push-back) |
| **M2 Identity** | ⬜ ยังไม่เริ่ม (มีแต่ local sign-in ที่ใช้ได้แล้ว) |
| **M3 Policy Engine** | 🚧 engine เสร็จ (72 tests ผ่าน) · เหลือ persistence, `policy_binding` materializer, decision cache, ANTLR grammar ของ `expr` |
| **M4–M8** | ⬜ |

**ที่รันอยู่ตอนนี้**
| | |
|---|---|
| Backend (Dropwizard, jar ใหม่) | `:8080` (API อยู่ใต้ `/api`), admin `:8081/ping` |
| Frontend (Vite) | `http://127.0.0.1:5274/` |
| App DB (docker `dac-appdb`, postgres:16-alpine) | `:5432` db/user `dac` |

เทสต์ทั้งหมดเขียว: dac-common 6 · dac-engine 72 · dac-connector-openmetadata 88 · dac-service 25 → **BUILD SUCCESS**

---

## What Worked

- **re-read by FQN แทนการ replay payload ของ event** — ทำให้ apply ซ้ำฟรี → poller rewind cursor 1 นาทีได้โดยไม่ต้องจำ event id และ webhook รับ redelivery ได้
- **`AssetRefresher` เข้าไปใช้ descent ของ `AssetCrawler` กลางทาง** (เปิด 3 method เป็น package-private) แทนการเขียน inheritance ซ้ำ → logic อยู่ที่เดียว
- **แยก 404 ออกจาก error อื่น** — 404 = race ปกติ (`MissingAncestorException`, ข้าม) · error อื่น = failed (cursor ไม่ขยับ)
- **retire ด้วย prefix ที่ผูกจุดและ escape LIKE** (`fqn + ".%"`, escape `_ % \`) — กันลบ `prod.Sales` แล้วลาก `prod.SalesArchive` ไปด้วย
- **webhook ไม่ใส่ `@Secured`** (Jersey `@NameBinding` — resource ที่ไม่มี annotation คือ unauthenticated โดยตั้งใจ) แล้วใช้ HMAC แทน · unconfigured = **503 ไม่ใช่รับทุกอย่าง**
- **รับ body เป็น raw `String`** เพราะ signature เซ็นบน byte จริง — ถ้าให้ Jersey deserialize ก่อนจะเทียบไม่ตรง
- **cursor hold เมื่อ apply ไม่ผ่าน + เพดาน 10 รอบ** — ได้ทั้ง "ไม่เสียของเงียบๆ" และ "ไม่ค้างถาวรจน window โตไม่หยุด"
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
- ❌ Playwright browsers ไม่ได้ติดตั้ง — ใช้ `chromium.launch({ channel: 'msedge' })` และ script ต้องอยู่ใน `frontend/app/`

---

## Next Steps

1. **Catalog UI (`/catalog`)** — ตอนนี้ยังเป็น `NotBuiltYetPage` badge M1 · ต้องมี list asset + facet (tag / term / domain / owner / custom property) + `inherited_from` เพื่อปิด M1
2. **รัน `AssetStoreIT`** ด้วย `./mvnw verify -Pintegration` (Docker พร้อมแล้ว) และเขียน `GovernanceStoreIT` เพิ่ม
3. **FR-1.6** — reconcile cache กับ JDBC introspection จริง + รายงาน orphan / column ใหม่ที่ยังไม่มี policy (**รอ connection database จริงจากผู้ใช้**)
4. **M3 ต่อ** — persistence ของ policy, `policy_binding` materializer (re-resolve เมื่อ facet/asset/policy เปลี่ยน), decision cache, ANTLR grammar ของ `expr`
5. **หน้าเปลี่ยนรหัสผ่าน** — `mustChangePassword` ไหลถึง `auth/authStore.ts` แล้วแต่ไม่มีใครอ่าน (ไม่มี route/guard)
6. **ก่อน M6** ต้องได้คำตอบ: SQL Server production เป็น **2022+** ไหม (ต้องการสำหรับ `GRANT UNMASK` ระดับ column) และลง extension `anon` บน PostgreSQL ได้ไหม

**กติกาที่ต้องถือไว้ทุกครั้งที่ commit:** repo เป็น public → scan หา password / JWT / IP ภายใน ก่อน push เสมอ · ค่าจริงอยู่ใน `.env` ที่ gitignore เท่านั้น · `.env.example` มีแต่ placeholder

**คำสั่งที่ใช้บ่อย** — ดูหัวข้อ 7 ของ [docs/DESIGN.md](docs/DESIGN.md)
