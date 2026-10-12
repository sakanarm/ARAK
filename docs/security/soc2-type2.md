# ARAK — SOC 2 Type 2 Readiness · Control Design & Gap Plan

> เอกสารออกแบบสำหรับผู้ดูแล ARAK และ (ภายหลัง) auditor / consultant ที่เตรียม SOC 2 Type 2
> บอกว่า control ไหนที่ code ของ ARAK ทำให้แล้ว · ช่องว่างที่ยังเหลือ (พร้อม priority) · วิธีปิดช่องโหว่ · วิธีวัดและเก็บหลักฐานตลอด observation window
>
> สถานะ: **Draft / readiness** · อัปเดต: 2026-10-12 · อ่านคู่กับ [DESIGN.md](../DESIGN.md) §5–§8, [CONTRIBUTING.md](../../CONTRIBUTING.md), [ADR 0002](../adr/0002-identity-propagation.md), `deploy/DEPLOY.md`
>
> repo นี้เป็น **public** — เอกสารนี้จึงไม่มีชื่อ host · IP ภายใน · ชื่อบริษัท / ลูกค้า / บุคคล · ค่า secret ใดๆ ตัวอย่างใช้ `example.test` และ `192.0.2.x` เท่านั้น
> path ของ Java ย่อเป็น `…/<package>/<File>.java` ใต้ `backend/<module>/src/main/java`

---

## 1. SOC 2 Type 2 คืออะไร และอะไรอยู่นอกเหนือ code

### 1.1 Trust Services Criteria (TSC) ที่เลือก

| หมวด | บังคับ? | ข้อเสนอสำหรับ ARAK | เหตุผล |
|---|---|---|---|
| **Security** (Common Criteria **CC1–CC9**) | ✅ บังคับทุกรายงาน | **In scope** | CC1 control environment · CC2 communication · CC3 risk assessment · CC4 monitoring · CC5 control activities · CC6 logical & physical access · CC7 system operations · CC8 change management · CC9 risk mitigation / vendor |
| **Availability** (A1) | เลือกได้ | **In scope (แนะนำ)** | ARAK อยู่บนเส้นทาง query (proxy 5.2 / gateway M29) — ล่ม = คนอ่านข้อมูลไม่ได้ · ลูกค้ามักถามเรื่อง capacity · backup · DR |
| **Confidentiality** (C1) | เลือกได้ | **In scope (แนะนำ)** | หน้าที่หลักของ ARAK คือคุมข้อมูลลับ (mask / RLS / hide) และเก็บ metadata + SQL + audit ที่เป็นความลับเอง |
| Processing Integrity (PI1) | เลือกได้ | ไม่แนะนำรอบแรก | ARAK ไม่ได้ประมวลผลธุรกรรม · แต่ "policy ตัดสินถูกต้อง" ใกล้เคียง PI — เก็บไว้รอบสอง หลัง M35 (policy test case) |
| Privacy (P1–P8) | เลือกได้ | ไม่แนะนำรอบแรก | ทับซ้อน **PDPA** มาก (purpose · ฐานกฎหมาย ม.24/26 จาก M31 · baseline พฤติกรรมของ M19 เป็นข้อมูลส่วนบุคคล) — ทำ PDPA gap assessment แยก แล้วค่อยพิจารณา |

### 1.2 Type 1 vs Type 2

| | Type 1 | **Type 2** |
|---|---|---|
| คำถามของ auditor | control **ออกแบบ**ถูกต้อง ณ วันหนึ่งไหม | control **ทำงานได้ผลจริงต่อเนื่อง** ตลอดช่วงเวลาไหม |
| ช่วงเวลา | point in time | **observation window 3–12 เดือน** (รอบแรกมัก 3–6 เดือน · ปีต่อไป 12 เดือน) |
| หลักฐาน | นโยบาย + config + walkthrough | **sample** จากทั้งช่วง เช่น PR 25 ตัวที่ merge · คนที่ลาออก 5 คน · alert 10 ตัว · backup restore ทุกไตรมาส |
| ผลต่อเรา | ทำครั้งเดียวได้ | หลักฐานต้อง **เกิดเองอัตโนมัติและเก็บไว้** ตั้งแต่วันแรกของ window — ย้อนสร้างหลักฐานทีหลังไม่ได้ และ exception ที่เกิดใน window จะถูกเขียนลงรายงาน |

**ผลที่ตามมา:** ทุก control ในเอกสารนี้ต้องตอบ 3 ข้อ — (1) ใครเป็นเจ้าของ (2) ทำงานเองหรือคนต้องทำ ทุกกี่วัน (3) หลักฐานอยู่ที่ไหน เก็บนานเท่าไร

### 1.3 SOC 2 รับรององค์กร ไม่ใช่ repo

SOC 2 รายงานเรื่อง **องค์กรที่ให้บริการ ARAK** (คน · กระบวนการ · infrastructure · vendor) — code ที่ดีที่สุดก็ผ่านไม่ได้ถ้า control ระดับองค์กรไม่มี
ขอบเขตระบบ (system description) ที่เสนอ:

| องค์ประกอบ | อยู่ใน boundary? | หมายเหตุ |
|---|---|---|
| ARAK service (Dropwizard jar) + frontend bundle | ✅ | |
| App DB (PostgreSQL ที่เก็บ policy / grant / audit) | ✅ | |
| Host ที่รัน ARAK + reverse proxy (nginx) + TLS termination | ✅ | host เป็น shared host หลาย application — ต้องระบุ control ของ host owner ด้วย |
| GitHub (repo · Actions · branch protection) | ✅ | เป็น change-management system |
| OpenMetadata | subservice (carve-out) | ARAK อ่าน metadata อย่างเดียว |
| LLM gateway / provider | **subservice (carve-out) + vendor review** | ได้ metadata · ชื่อคนถูกแทนด้วย `[Pn]` ใน dashboard brief |
| Source databases ของลูกค้า | นอก boundary → **CUEC** | firewall ให้รับเฉพาะ ARAK · account ของ ARAK ต้อง least privilege |

**Complementary User Entity Controls (CUEC)** ที่ต้องเขียนในรายงาน: ลูกค้าต้อง firewall source ให้รับเฉพาะ ARAK (FR-6.3.1) · ให้ credential ของ source แบบ read-only · ทบทวนสิทธิ์ใน ARAK ตามรอบ (M32) · แจ้ง offboarding

### 1.4 Control ระดับองค์กรที่ code ทำแทนไม่ได้

| Control | TSC | เจ้าของที่ต้องมี | หลักฐานที่ auditor ขอ |
|---|---|---|---|
| Information Security Policy ชุดหลัก (access · change · incident · vendor · data classification · acceptable use · encryption · backup) อนุมัติและทบทวนปีละครั้ง | CC1.1, CC2.1, CC5.3 | ผู้บริหารที่รับผิดชอบความปลอดภัย (security owner) | เอกสาร + วันที่อนุมัติ + ประวัติ review |
| Risk assessment ประจำปี + risk register (รวม fraud risk) | CC3.1–CC3.4 | security owner | register + บันทึกการประชุม |
| HR onboarding / offboarding · background check ตามกฎหมาย · NDA / สัญญาความลับ | CC1.4, CC6.2, CC6.3 | HR + hiring manager | ticket ต่อคน · เวลาที่ถอนสิทธิ์ (≤ 1 วันทำการ) |
| Security awareness training ปีละครั้ง + ตอนเข้างาน | CC1.4, CC2.2 | HR / security owner | รายชื่อ + วันที่ผ่าน |
| Vendor management — GitHub · host provider · **LLM provider** (data use · retention · region · sub-processor · SOC 2 ของเขา) | CC9.2 | security owner + procurement | vendor register + รายงาน SOC 2 / ISO ของ vendor + review ปีละครั้ง |
| Incident Response Plan + tabletop exercise ปีละครั้ง + ช่องทางแจ้ง (รวม PDPA 72 ชม.) | CC7.3–CC7.5 | incident commander ที่แต่งตั้ง | แผน + บันทึก tabletop + ticket incident จริง |
| BCP / DR plan + **ทดสอบ restore** | A1.2, A1.3, CC7.5 | ผู้ดูแล infrastructure | ผลทดสอบ restore รายไตรมาส · RTO/RPO ที่ทำได้จริง |
| Management / board oversight — ทบทวน KPI ความปลอดภัย (§6.4) รายไตรมาส | CC1.2, CC4.1, CC4.2 | ผู้บริหาร | วาระ + บันทึกการประชุม |
| Asset inventory (host · domain · cert · account ของระบบ) | CC6.1 | ผู้ดูแล infrastructure | inventory export |
| Physical security ของ data center | CC6.4 | host provider (carve-out) | SOC 2 ของ provider |

---

## 2. Control Matrix

คอลัมน์: **ข้อกำหนด** (ย่อหนึ่งบรรทัด) · **ARAK มีแล้ว** (อ้างไฟล์ / feature) · **Gap** · **Fix** (อ้าง ID ใน §3) · **หลักฐานที่ auditor จะ sample**

| TSC | ข้อกำหนด | ARAK มีแล้ว | Gap | Fix | หลักฐาน |
|---|---|---|---|---|---|
| **CC6.1** | logical access: ระบุตัวตน · authenticate · authorize · ปกป้อง credential | Local login · `…/auth/PasswordHasher.java` PBKDF2-HMAC-SHA256 **210,000** รอบ + salt + rehash อัตโนมัติ · lockout `IDENTITY_MAX_FAILED_LOGIN_ATTEMPTS` (5) / `IDENTITY_LOCKOUT_SECONDS` (900) · `DECOY_HASH` ใน `AuthResource` กัน user enumeration ด้วยเวลา · ข้อความ error เดียวทุกกรณี · password ≥ 12 ตัว · บังคับเปลี่ยนรหัสหลัง admin ตั้ง (`pwc` claim + `AuthFilter`) · JWT HS256 key ≥ 32 byte ไม่งั้นไม่ start · RBAC 5 role (`PLATFORM_ADMIN` · `POLICY_AUTHOR` · `DATA_OWNER` · `AUDITOR` · `REQUESTER`) ผ่าน `@Secured` · admin port bind loopback · credential ของ source เป็น reference (`vault://` · `fernet:` · `azurekeyvault://` · `env:`) ไม่ออกทาง API | ไม่มี MFA / SSO (G-09) · session เพิกถอนไม่ได้ (G-01) · ไม่มี per-IP throttle (G-06) · token ใน localStorage และไม่มี CSP (G-05) · vault resolver เป็น stub (G-07) | G-01 G-05 G-06 G-07 G-09 | config export · screenshot การตั้ง MFA · ผล test ของ `AuthResourceTest` / `PasswordHasherTest` |
| **CC6.2** | ลงทะเบียน / ถอนผู้ใช้ตามการอนุมัติ | สร้าง local account จากหน้า People · `audit_identity_change` (V10) บันทึก `GRANT_ROLE` / `REVOKE_ROLE` / `SET_PASSWORD` | ไม่มี feed จาก HR / Entra → offboarding เป็นงานมือ · ปิดบัญชีแล้ว token เดิมยังใช้ได้ถึงหมดอายุ (G-01) | M2 Entra + SCIM / Graph sync · G-01 | ticket onboarding/offboarding เทียบกับ `audit_identity_change` · เวลาจากวันออกถึงวันปิดบัญชี |
| **CC6.3** | least privilege · เพิ่ม / แก้ / ถอนสิทธิ์ตามการอนุมัติ · segregation of duties | **ห้าม self-grant** (`AccessResource`: "Nobody changes their own access") · grant ต้องมี reason · วันจบต้องอยู่ในอนาคต · grant ถูก **ยุติด้วย tombstone** (`revoked_at`/`revoked_by`) ไม่ลบ · `GrantExpiryJob` auto-revoke · access request + workflow หลาย stage (M9) · `audit_grant_change` · policy lifecycle DRAFT→PENDING_APPROVAL→ACTIVE + `audit_policy_change` | **grant แบบ "No expiry" ยังสร้างได้** เมื่อ purpose ไม่มี `maxDays` (`GrantStore.insert` ยอม `validUntil = null`) — ขัดกติกา "grant ต้องมีวันจบ" · ยังไม่มี access review campaign (M32) · role ใน JWT ค้างถึงหมดอายุ | G-12 · M32 · G-01 | export access review ต่อไตรมาส · sample grant 25 ตัว: มี reason / วันจบ / ผู้อนุมัติ ≠ ผู้รับ |
| **CC6.6** | ป้องกันภัยจากนอก boundary (network · TLS · exposure) | admin connector loopback-only · webhook ของ OM ต้อง HMAC-SHA256 (ไม่ตั้ง secret = 503 fail-closed) · nginx reverse proxy | app connector ไม่ได้ bind loopback — host อื่นเรียก port ของ service ตรงได้ (ข้าม nginx / TLS) · ไม่มี security header (G-05) · ไม่มี per-IP rate limit (G-06) · TLS config อยู่นอก repo (ยังไม่ได้ตรวจ) | bind `127.0.0.1` + host firewall · G-04 G-05 G-06 · testssl (§6) | ผล testssl รายเดือน · firewall rule export · ZAP baseline report |
| **CC6.7** | ปกป้องข้อมูลระหว่างส่ง / ตอนส่งออก | Download all rows ผ่าน policy rewrite + audit `exported=true` (V42) · CSV กัน formula injection · MSSQL `encrypt=true` | TLS ไปยัง source ไม่บังคับทุก engine (FR-15) · gateway M29 ต้องบังคับ TLS (§4) | บังคับ `sslmode=verify-full` / `encrypt=true` ทุก source · §4 | config ของ source (ไม่มี credential) · testssl ของ gateway |
| **CC6.8** | ป้องกัน / ตรวจจับ software ไม่พึงประสงค์ · supply chain | `gitleaks` 8.30.1 ตรวจ checksum ก่อนรันใน CI · CI กัน generated source ถูก commit · vendored design system ต้องมี `VENDORED.md` | ไม่มี SAST / SCA / SBOM / container / IaC scan (G-08) · GitHub Actions pin ด้วย tag ไม่ใช่ SHA · `ci.yml` ไม่ประกาศ `permissions:` · production build จาก `git pull` บน host (ไม่มี artifact ที่ verify ได้) · Dockerfile รันเป็น root | G-08 · G-13 | ผล scan ต่อ PR · SBOM ต่อ release |
| **CC7.1** | ตรวจจับ config ผิด / ช่องโหว่ใหม่ | Direct-access detector (FR-6.3.1) · `NativeReadGate` ตรวจว่า role บน source มีผลจริง · health check ของ Dropwizard | ไม่มี vulnerability scan ตามรอบ · ไม่มี drift detection ของ enforcement (FR-6.4 ⬜) · ไม่มี CIS benchmark ของ host | G-08 · §5 · §6 | รายงาน scan รายเดือน · ticket ของ finding |
| **CC7.2** | monitor ความผิดปกติ / security event | `audit_query` · `audit_decision` · `audit_enforcement` · Dashboard (refusal · grant ใกล้หมด · สิทธิ์ไม่ใช้ 90 วัน) · `LOG.warn` ตอน login ผิด | login อยู่ใน log เท่านั้น ไม่มีใน DB (G-02) · ไม่มี SIEM export (FR-8.4) · ไม่มี alert · client IP เป็น `127.0.0.1` ทุกแถวหลัง nginx (G-04) · ไม่มี anomaly detection (M19) | G-02 G-03 G-04 · M19 | alert rule + ตัวอย่าง alert ที่ถูก triage · dashboard SIEM |
| **CC7.3–CC7.5** | ประเมิน event · ตอบสนอง incident · กู้คืน · lessons learned | audit ครบพอ reconstruct "ใครอ่านอะไร เพราะ policy ไหน" · revoke grant / disable policy ได้ทันที | ไม่มี IR plan · ไม่มี runbook "ปิด session ของคนที่ถูก compromise" (ทำไม่ได้เพราะ G-01) · ไม่มี postmortem template | IR plan (§1.4) · G-01 · runbook | ticket incident · postmortem · tabletop record |
| **CC8.1** | change management: อนุมัติ · ทดสอบ · deploy อย่างควบคุม | branch protection บน `main` · CI ต้องผ่าน (backend · frontend · design-system · secrets) · Testcontainers IT หลัง merge (40 `*IT`) · Flyway migration ห้ามแก้ของที่ merge แล้ว · CONTRIBUTING กำหนด 1 approval | ในทางปฏิบัติ PR ถูก merge โดยผู้เขียนเองได้ (G-10) · integration ไม่ใช่ required check ของ PR · ไม่มี emergency change procedure · deploy ไม่มี record ที่ผูก commit SHA | G-10 | sample PR: approval โดยคนอื่น · CI เขียว · deploy log ผูก SHA |
| **CC9.2** | vendor / business partner risk | LLM ได้ metadata เท่านั้น กรองตามสิทธิ์ก่อนสร้าง prompt (M15/M16/M28) · ชื่อคนแทนเป็น `[Pn]` · LLM ไม่ run / save / send อะไรเอง · admin ปิด personal gateway ได้ | **personal LLM gateway** ให้ผู้ใช้ชี้ไป provider ที่องค์กรไม่ได้ review · ไม่มี vendor register | ปิด personal gateway ใน production หรือ allow-list provider · vendor review | vendor register · ค่าตั้ง LLM ที่ export ได้ |
| **A1.2** | backup · infrastructure recovery | DEPLOY.md: `pg_dump` ก่อน deploy ที่มี migration · NFR-3 serve จาก cache ได้เมื่อ OM ล่ม | ไม่มี backup schedule · encryption ของ backup · off-site copy · RPO/RTO (G-11) | G-11 | backup job log · checksum · ที่เก็บ off-site |
| **A1.3** | ทดสอบ recovery | — | ไม่เคยทดสอบ restore (G-11) | restore test รายไตรมาส | restore log + เวลาที่ใช้ |
| **C1.1** | ระบุและปกป้องข้อมูลลับ | Policy engine fail-closed (default deny · DENY ชนะ) · query proxy read-only + `LexicalGate` + allow-list function · mask / RLS / hide · result cache key ด้วย statement หลัง rewrite · sensitive data rule (V44) · Fernet seal ของ personal API key (`…/crypto/SecretBox.java`) · client IP ไม่ออกทาง output · LLM metadata-only | Fernet key ไม่มี rotation · ค่าที่ไม่ใช่ base64 ถูก hash แทนการปฏิเสธ (G-07) · audit table เก็บ SQL ที่อาจมี literal ลับแต่ไม่มี retention (G-03) | G-03 G-07 | data classification policy · key rotation record |
| **C1.2** | ทำลายข้อมูลลับเมื่อหมดความจำเป็น | grant หมดอายุอัตโนมัติ · purpose ถูกปลดแทนลบ | ไม่มี retention / disposal ของ `audit_*` และ result export · ไม่มีขั้นตอนทำลาย backup | G-03 · retention policy | retention job log · disposal record |

---

## 3. ช่องว่างที่พบจาก code review (พูดตรงๆ)

**Priority:** **P0** = ต้องปิดก่อนเริ่ม observation window (auditor จะเขียนเป็น exception) · **P1** = ปิดภายในช่วงต้นของ window · **P2** = ปรับปรุง

| ID | Gap | ผลกระทบ | Fix | P | Milestone |
|---|---|---|---|---|---|
| **G-01** | JWT session (HS256 · default TTL 3600s) **เพิกถอนก่อนหมดอายุไม่ได้** · ไม่มี logout ฝั่ง server · disable user หรือถอด role ไม่มีผลจนกว่า token หมดอายุ (`JwtService` ใส่ role ไว้ใน claim) · ฝั่ง frontend logout แค่ลบ localStorage | คนที่ถูกปิดบัญชี / ถูก compromise ยังใช้ API ได้สูงสุด 1 ชม. → CC6.2/6.3 fail ใน sample offboarding | ตาราง `auth_session` (jti · principal · issued_at · expires_at · revoked_at · revoked_by · reason) — `AuthFilter` ตรวจ jti + `principal.enabled` ทุก request (cache ≤ 5 วินาที) · `POST /v1/auth/logout` · disable user / ถอด role ⇒ revoke ทุก session ของคนนั้น · หรือ access token 5–10 นาที + refresh token แบบ rotate (HttpOnly cookie) | **P0** | M2 / M8 |
| **G-02** | ไม่มี authentication audit ใน DB — login สำเร็จ / ล้มเหลว / lockout อยู่ใน log line เท่านั้น | ตอบ "ใคร login เมื่อไร จากที่ไหน" ย้อนหลังไม่ได้ · log หมุนทิ้ง | ตาราง `audit_auth_event` (V48+ ตัวถัดไปที่ว่าง): occurred_at · username_attempted · principal_id (nullable) · event (`LOGIN_OK` · `LOGIN_FAIL` · `LOCKED` · `LOGOUT` · `TOKEN_REVOKED` · `PASSWORD_CHANGED` · `PAT_CREATED` · `PAT_USED_FIRST` · `PAT_REVOKED`) · reason_code · client_ip (ไม่ออก output) · user_agent · channel (`WEB` / `API` / `GATEWAY`) | **P0** | M8 |
| **G-03** | audit table **ไม่ append-only ในทางปฏิบัติ**: V5 เขียนว่า "app role ได้แค่ INSERT + SELECT, grant ตอน deploy" แต่ Flyway รันด้วย user เดียวกับ runtime (`migrateOnStartup: true` · `database.user` ตัวเดียว) ⇒ runtime user **เป็น owner** ของตาราง UPDATE/DELETE/TRUNCATE ได้ · ไม่มี trigger · ไม่มี retention job · ไม่มี SIEM export (FR-8.4 ⬜) | auditor ถาม "พิสูจน์ได้ไหมว่า audit ไม่ถูกแก้" → ตอบไม่ได้ | (1) แยก role: `arak_owner` (Flyway, NOLOGIN นอก deploy) / `arak_app` (runtime) · `REVOKE UPDATE, DELETE, TRUNCATE ON audit_* FROM arak_app` (2) trigger `BEFORE UPDATE OR DELETE` ที่ `RAISE EXCEPTION` บนทุก `audit_*` (3) hash chain: คอลัมน์ `prev_hash` / `row_hash` = SHA-256(prev_hash ‖ canonical row) + job ตรวจทุกคืน (4) export รายวันไป WORM storage (object lock) หรือ SIEM (5) retention: ออนไลน์ ≥ 13 เดือน · archive ตาม policy · ลบโดย `arak_owner` เท่านั้น พร้อม record | **P0** | M8 (FR-8.4) |
| **G-04** | หลัง nginx `client_ip` ใน audit เป็น `127.0.0.1` ทุกแถว (`getRemoteAddr()` เท่านั้น — 11 จุด) · ผลข้างเคียง: policy ที่ใช้ `context.ipCidr` ถูกประเมินกับ loopback | audit ตอบไม่ได้ว่ามาจากไหน · ABAC ด้าน network ใช้ไม่ได้จริง | `ClientAddress.resolve(request)` จุดเดียว: เชื่อ `X-Forwarded-For` **เฉพาะเมื่อ** remote addr อยู่ใน `trustedProxies` (config · default ว่าง) และเอา address ขวาสุดที่ไม่ใช่ trusted proxy · bind connector `127.0.0.1` ก่อนเปิดใช้ · test ว่า header จาก client ตรงถูกเพิกเฉย | **P1** | M8 |
| **G-05** | ไม่มี security header เลย (CSP · HSTS · `X-Frame-Options` / `frame-ancestors` · `X-Content-Type-Options` · `Referrer-Policy` · `Permissions-Policy`) · token อยู่ใน `localStorage` (`frontend/app/src/auth/session.ts`) — comment ในไฟล์อ้างว่ามี CSP คุ้มกัน แต่ **CSP ไม่มีจริง** | XSS หนึ่งจุด = ขโมย token ได้ทั้ง session · clickjacking | servlet filter ใน `…/web/` ใส่ header ทุก response: `Content-Security-Policy: default-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'` (+ `connect-src` ของ API) · `Strict-Transport-Security: max-age=31536000` (ที่ nginx) · `X-Content-Type-Options: nosniff` · `Referrer-Policy: no-referrer` · ระยะยาว: ย้าย refresh token ไป HttpOnly + SameSite=Strict cookie | **P1** | — |
| **G-06** | ไม่มี per-IP login rate limit — มีแค่ lockout ต่อ account | password spraying (หนึ่งรหัส × หลาย account) ไม่ติด lockout · lockout เองถูกใช้ล็อกคนอื่นได้ (DoS) | token bucket ต่อ IP (หลัง G-04) + ต่อ username สำหรับ `/v1/auth/login` และ `/v1/auth/password` · ใน memory ต่อ instance + `limit_req` ที่ nginx เป็นชั้นแรก · ตอบ 429 + `Retry-After` · ลง `audit_auth_event` | **P1** | — |
| **G-07** | `vault://` และ `azurekeyvault://` **เป็น stub** (`CredentialResolver` ตอบ "No vault client is configured") · Fernet token ไม่มีวันหมดอายุ (`SecretBox.FOREVER` — ตั้งใจสำหรับ at-rest) · ไม่มี key rotation (เปลี่ยน `FERNET_KEY` = secret เดิมเปิดไม่ได้) · `FERNET_KEY` ที่ไม่ใช่ base64 32 byte **ถูก SHA-256 แทนการปฏิเสธ** | passphrase อ่อนกลายเป็น key ได้เงียบๆ · หมุน key ไม่ได้โดยไม่เสีย secret | (1) refuse key ที่ไม่ใช่ 32 byte ตอน start (log ชื่อ key เท่านั้น) (2) keyring: `FERNET_KEYS` = รายการ key, ตัวแรกใช้ seal, ทุกตัวใช้ open + job re-seal (3) ทำ Vault / Azure Key Vault client จริง (4) runbook rotation ปีละครั้ง + เมื่อสงสัยรั่ว | **P1** (refuse = P0 ทำง่าย) | M22 |
| **G-08** | CI มีแค่ gitleaks — ไม่มี SAST · SCA · SBOM · container / IaC scan · license check · DAST | ช่องโหว่ใน dependency ไม่มีใครเห็น · CC7.1 / CC8.1 ไม่มีหลักฐาน | ดู §6 | **P0** (SCA + SAST) · P1 ที่เหลือ | — |
| **G-09** | Entra ID SSO / MFA ยังไม่สร้าง (FR-2.1 ⬜) — มีแต่ local account | auditor คาดหวัง MFA สำหรับ admin และการเข้าถึงข้อมูลลับ · offboarding ผูกกับ HR ไม่ได้ | M2: OIDC (Authorization Code + PKCE) กับ Entra · MFA ผ่าน Conditional Access · local account เหลือเฉพาะ break-glass (1–2 บัญชี · รหัสในตู้เซฟ · ใช้แล้ว alert) · ระหว่างรอ: TOTP สำหรับ `PLATFORM_ADMIN` | **P0** | M2 |
| **G-10** | merge PR ของตัวเองได้โดยไม่มี reviewer อิสระ (ทำซ้ำในประวัติ) — ขัด segregation of duties ของ CC8.1 · deploy production ทำมือ (`git pull` + restart) ไม่มี record ที่ผูก SHA | auditor sample PR แล้วเจอ self-merge = exception | branch protection: required reviewers ≥ 1 · **dismiss stale approvals** · require review from CODEOWNERS · required status checks (backend · frontend · secrets · SAST · SCA) · **include administrators** · ห้าม force push · emergency change: merge ได้โดยมี reviewer ภายหลัง ≤ 2 วันทำการ + label `emergency` + ticket · deploy log (SHA · เวลา · ผู้ deploy) | **P0** | — |
| **G-11** | backup / restore test / DR ของ App DB ไม่มีเอกสาร (มีแค่ "pg_dump ก่อน migration") | A1.2/A1.3 ไม่มีหลักฐาน · policy + grant + audit หายได้ | backup รายวันเข้ารหัส + off-site · PITR (WAL archiving) ถ้าทำได้ · RPO ≤ 24 ชม. · RTO ≤ 4 ชม. (ตั้งต้น) · restore test รายไตรมาสไป instance แยก + ตรวจ `flyway_schema_history` + hash chain ของ audit · runbook DR | **P0** | — |
| **G-12** | grant แบบ No expiry สร้างได้เมื่อ purpose ไม่มี `maxDays` (ขัดกติกาใน CONTRIBUTING) | สิทธิ์ถาวรสะสม · CC6.3 | เพดานอายุ grant ระดับองค์กร (เช่น ≤ 365 วัน) บังคับที่ `GrantStore.insert` + หน้าจอ · grant เดิมที่ไม่มีวันจบเข้า access review รอบแรก (M32) | **P1** | M31 / M32 |
| **G-13** | supply-chain / hardening เล็กๆ: Actions pin ด้วย tag · `ci.yml` ไม่มี `permissions: contents: read` · Dockerfile ไม่มี `USER` (root) · image `:latest` บางตัวใน compose · app port ไม่ bind loopback | ลดพื้นผิวโจมตี | pin Actions ด้วย SHA + Dependabot สำหรับ actions · `permissions:` ระดับ workflow · `USER 10001` · bind `APP_BIND_HOST=127.0.0.1` | **P2** | — |
| **G-14** | account ที่ถูก lock ตอบ 429 พร้อมเวลาปลด — ต่างจาก "username หรือ password ไม่ถูก" ⇒ ยืนยันได้ว่า account มีอยู่ | user enumeration เล็กน้อย | ตอบ 401 ข้อความเดียวกัน (หรือ 429 ทั้งกรณีมี / ไม่มี account เมื่อ IP ถูก throttle) · แจ้งเจ้าของบัญชีทางอื่น | **P2** | — |

### 3.1 รายละเอียดการออกแบบ fix ที่เป็น P0

**G-01 Session revocation** — ทางที่แนะนำ (เปลี่ยน code น้อยที่สุด):

| ส่วน | ออกแบบ |
|---|---|
| ตาราง | `auth_session(jti uuid PK, principal_id, issued_at, expires_at, revoked_at, revoked_by, revoke_reason, channel)` — revoke = tombstone ไม่ลบ (แบบเดียวกับ grant) · ลบแถวที่หมดอายุ > 30 วันด้วย job |
| ออก token | `JwtService.issue` เขียนแถวก่อนคืน token (jti มีอยู่แล้ว) |
| ตรวจ | `AuthFilter` หลัง verify: `jti` ไม่ถูก revoke **และ** principal ยัง enabled · cache แบบ negative/positive TTL ≤ 5 วินาที · DB ล่ม = **ปฏิเสธ** (fail-closed) |
| role ค้างใน claim | อ่าน `app_role_assignment` สดสำหรับ endpoint ที่แก้ policy / grant / identity (JwtService เองแนะนำไว้แล้ว) หรือ revoke session ทันทีที่ role เปลี่ยน |
| ทริกเกอร์การ revoke | logout · disable user · ถอด role · reset password · admin กด "Sign out everywhere" · incident runbook |
| test | IT: disable → request ถัดไป 401 · logout → token เดิม 401 · DB ล่ม → 401/503 ไม่ใช่ 200 |

**G-03 Audit append-only** — migration ตัวถัดไป (ห้ามแก้ V5/V10) ราวๆ:

```sql
-- รันโดย owner role เท่านั้น; ชื่อ role จริงมาจาก deploy config ไม่ใช่จากไฟล์นี้
CREATE OR REPLACE FUNCTION audit_is_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit tables are append-only (%)', TG_TABLE_NAME;
END $$;
-- ทำซ้ำทุกตาราง audit_* (15 ตารางวันนี้ + audit_auth_event)
CREATE TRIGGER audit_query_no_change BEFORE UPDATE OR DELETE ON audit_query
  FOR EACH ROW EXECUTE FUNCTION audit_is_append_only();
CREATE TRIGGER audit_query_no_truncate BEFORE TRUNCATE ON audit_query
  FOR EACH STATEMENT EXECUTE FUNCTION audit_is_append_only();
```

trigger กันได้แค่ runtime role ที่ไม่ใช่ owner — owner `ALTER TABLE … DISABLE TRIGGER` ได้ จึงต้องมีทั้ง **(ก) แยก role** (runtime ไม่ใช่ owner) **(ข) hash chain** และ **(ค) export ออกนอก DB** ทุกวัน — สามชั้นนี้รวมกันคือสิ่งที่ auditor ยอมรับว่า tamper-evident

---

## 4. ARAK Gateway (M29 · pgwire บน port 5439 · Personal Access Token)

Gateway เปิดช่องทางใหม่ที่ **ไม่ผ่าน browser และไม่ผ่าน nginx** — ทุก control ของหน้าเว็บต้องมีคู่ของมันที่นี่ หลักเดียว: **gateway ไม่มี enforcement ของตัวเอง ทุก statement ลง `QueryService` เส้นเดียวกับหน้า Query**

### 4.1 Threat model (STRIDE)

| STRIDE | ภัย | Control |
|---|---|---|
| **S**poofing | เดา / ขโมย PAT · ปลอม server (MITM ขโมย password message) · ใช้ PAT ของคนที่ออกไปแล้ว | TLS บังคับ · PAT 256-bit · hash-only · หมดอายุ ≤ 90 วัน · revoke ได้ · ปิดบัญชี = PAT ทุกตัวใช้ไม่ได้ทันที · throttle ต่อ IP + ต่อ token |
| **T**ampering | SQL ที่ parser กับ source อ่านไม่ตรงกัน · simple-query หลาย statement ใน message เดียว · แก้ข้อมูลผ่าน DML / `COPY` / function | parse ทุก statement ด้วย `QueryRewriter` + `LexicalGate` · read-only: DML / DDL / `COPY` / `DO` / `CALL` / `LISTEN` / `SET ROLE` ปฏิเสธ · multi-statement แยกแล้วตัดสินทีละตัว · function allow-list (`ProxyFunctions`) |
| **R**epudiation | "ไม่ได้ query นี้" · tool ไม่ระบุตัว | audit `channel = GATEWAY` + `application_name` + PAT id (ไม่ใช่ค่า token) + session id ทุก statement · `audit_auth_event` ทุก connect |
| **I**nformation disclosure | `pg_catalog` / `information_schema` เผยชื่อตารางที่ไม่มีสิทธิ์ · error message ของ source หลุด · plaintext บนสาย · statement timing | catalog query ตอบจาก **sandbox PostgreSQL แยก** ที่ ARAK สร้างให้แต่ละ session เฉพาะ object ที่คนนั้นอ่านได้ (ไม่มีแถวข้อมูล) — **ไม่ส่งต่อไป source เด็ดขาด** · error จาก source ถูก map เป็นข้อความกลาง · mask / RLS / hide เหมือน 5.2 |
| **D**enial of service | เปิด connection ค้าง · startup ช้า (slowloris) · query หนัก · DirectQuery ยิงถี่ | connection cap ต่อ token / ต่อคน / ทั้ง gateway · auth timeout ≤ 10 วินาที · idle timeout · statement timeout + row limit · concurrency limit + cost guard + result cache ชุดเดิมของ FR-6.3 |
| **E**levation of privilege | bypass ไปที่ source ตรง · ใช้ PAT ทำงาน admin · ใช้ `SET` เปลี่ยน session ของ source | source firewall ให้รับเฉพาะ ARAK (FR-6.3.1 · CUEC) · PAT มี scope (`query` เท่านั้นโดย default) · `SET` ตอบ OK ฝั่ง gateway แต่ไม่ส่งต่อ · session setup ของ source ตั้งโดย `SourceEngine.sessionSetup()` เท่านั้น |

### 4.2 Control ของ gateway

| Control | ข้อกำหนด | ทดสอบอย่างไร |
|---|---|---|
| TLS | **TLS 1.2+ บังคับ** · `SSLRequest` ไม่มา = ปิด connection ก่อนรับ password · ไม่มี plaintext auth (ห้าม `AuthenticationCleartextPassword` บนช่องไม่เข้ารหัส) · cipher ตาม Mozilla "intermediate" · cert จาก CA ที่ client verify ได้ (`sslmode=verify-full`) | `testssl.sh --starttls postgres <host>:5439` · IT: connect ด้วย `sslmode=disable` ต้องล้ม |
| PAT format | random 256-bit จาก `SecureRandom` · prefix ระบุชนิด (เช่น `arak_pat_…`) ให้ secret scanner จับได้ · checksum ท้าย token กัน typo | unit test entropy / format · ลง pattern ใน `.gitleaks.toml` |
| PAT storage | เก็บ **SHA-256 ของ token เท่านั้น** (token สุ่ม 256-bit ไม่ต้องใช้ slow hash) · เทียบแบบ constant-time · แสดงค่าจริง **ครั้งเดียว** ตอนสร้าง · ไม่มี API อ่านกลับ | IT: ไม่มี endpoint ไหนคืน token · DB dump ไม่มีค่า token |
| PAT lifetime | วันหมดอายุ **บังคับ ≤ 90 วัน** · revoke = tombstone (`revoked_at` / `revoked_by` / reason) · `last_used_at` + `last_used_ip` (ไม่ออก output) · แจ้งเตือนก่อนหมด 7 วัน · ไม่ใช้ 30 วัน = แจ้ง / 90 วัน = revoke อัตโนมัติ | IT: หมดอายุ / revoke → auth fail · disable user → PAT ทุกตัว fail |
| Throttling | auth fail ต่อ IP และต่อ token prefix (token bucket) · ลง `audit_auth_event` | test: N ครั้งผิด → ถูกหน่วง / ปฏิเสธ |
| Limits | max connection ต่อคน / ต่อ PAT / ทั้งหมด · auth timeout · idle timeout · statement timeout · row limit | load test |
| Read-only + fail-closed | parse ไม่ได้ · resolve ตารางไม่ได้ · message ที่ไม่รู้จัก ⇒ ปฏิเสธ · `BEGIN` / `COMMIT` / `SET` ตอบ OK เฉพาะฝั่ง gateway | protocol fuzzing (§6) · golden test ของ SQL ที่ BI tool สร้าง (M29b) |
| เส้นทางเดียว | ทุก statement → `QueryService` (rewrite · mask · RLS · `NativeReadGate` · purpose check · audit) — ห้ามมี code path ที่ query source เอง | ArchUnit rule: package gateway ห้าม import JDBC ของ source |
| Catalog sandbox | `pg_catalog` / `information_schema` / `pg_*` function ตอบจาก PostgreSQL sandbox ที่มีแค่ schema ว่างของตารางที่คนนั้นอ่านได้ (column ที่ถูก hide ไม่มี) · สร้าง / ทิ้งต่อ session หรือ cache ต่อ decision generation | IT: คน A กับคน B เห็น `information_schema.tables` ต่างกันตามสิทธิ์ · ไม่มี query ไหนไปถึง source |
| Audit | `audit_query.channel = 'GATEWAY'` · `application_name` (ตัดความยาว · กรองตัวอักษร) · PAT id · session id · ผล ALLOW / DENY ทุก statement รวม catalog query ที่สำคัญ | sample จาก audit ใน window |
| Network | source รับเฉพาะ ARAK · gateway port เปิดเฉพาะเครือข่ายที่ต้องใช้ (เช่น 192.0.2.0/24 ในตัวอย่าง) · ไม่มี superuser account ของ source ใน ARAK | direct-access check (FR-6.3.1) รายเดือน |

PAT และ service account (M36) ใช้ตาราง / กติกาเดียวกัน: เจ้าของเป็นคน · scope · hash-only · หมดอายุมีเพดาน · เข้ารอบ access review (M32)

---

## 5. วิธีปิดช่องโหว่ — Vulnerability Management Process

### 5.1 แหล่งที่มาของ finding

| แหล่ง | ความถี่ | เข้า tracker อย่างไร |
|---|---|---|
| SCA (dependency) · SAST · container / IaC / secret scan | ทุก PR + รายสัปดาห์บน `main` | GitHub Security tab (SARIF) → issue อัตโนมัติ label `vuln` |
| DAST (ZAP) · TLS scan | รายสัปดาห์ / รายเดือน | report แนบ issue |
| Penetration test ภายนอก | ปีละครั้ง + ก่อนเปิด gateway (M29) | finding ละหนึ่ง issue (private) |
| Internal pentest / review | รายไตรมาส | เหมือนกัน |
| Bug report จากภายนอก | ต่อเนื่อง | **`SECURITY.md`** (ต้องสร้าง): ช่องทาง private (GitHub Private Vulnerability Reporting) · ตอบรับ ≤ 3 วันทำการ · ไม่มีการเปิดเผยก่อนแก้ |
| Advisory ของ vendor (JDK · Dropwizard · PostgreSQL · nginx · OS) | ต่อเนื่อง | Dependabot / ผู้ดูแล host |

finding ด้าน security เก็บใน **private** tracker (GitHub Security Advisory draft หรือ repo private) — repo หลักเป็น public ห้ามเปิด issue รายละเอียดช่องโหว่ที่ยังไม่แก้

### 5.2 Triage

1. **ยืนยันว่าจริง** (reachable ไหม — dependency ที่ไม่ถูกเรียกใช้ ≠ ไม่ต้องแก้ แต่ลด priority ได้พร้อมเหตุผล)
2. ให้คะแนน **CVSS v3.1/v4 base** แล้วปรับตามบริบทของ ARAK: exploit มีจริง (CISA KEV · EPSS สูง) → ขึ้นหนึ่งระดับ · ต้อง login + role admin → ลงได้หนึ่งระดับ
3. **กฎพิเศษของ ARAK:** อะไรก็ตามที่ทำให้ fail-open (bypass policy · อ่านเกินสิทธิ์ · audit หาย · credential ออก API · LLM เห็นแถวข้อมูล) = **Critical** โดยไม่ต้องดู CVSS
4. กำหนด owner + due date ตาม SLA

### 5.3 SLA

| Severity | CVSS | แก้และ deploy ภายใน | หมายเหตุ |
|---|---|---|---|
| **Critical** | 9.0–10 หรือกฎข้อ 3 | **7 วัน** | ถ้าแก้ไม่ทันต้องมี mitigation ภายใน 72 ชม. (ปิด feature · WAF rule · revoke) |
| **High** | 7.0–8.9 | **30 วัน** | |
| **Medium** | 4.0–6.9 | **90 วัน** | |
| **Low** | 0.1–3.9 | best effort (ทบทวนทุกไตรมาส) | |

นับจากวันที่ finding ถูกยืนยัน · เลย SLA = รายงานใน KPI (§6.4) และต้องมี exception

### 5.4 Exception / Risk acceptance

| ฟิลด์ | บังคับ |
|---|---|
| finding id · severity · เหตุผลที่แก้ไม่ได้ตอนนี้ | ✅ |
| compensating control | ✅ |
| ผู้อนุมัติ (security owner — **ไม่ใช่คนที่ต้องแก้**) | ✅ |
| **วันหมดอายุ** (Critical ≤ 30 วัน · High ≤ 90 · Medium ≤ 180) — หมดแล้วต้องทบทวนใหม่ | ✅ |

กติกาเดียวกับ grant ของ ARAK เอง: มีเหตุผล · มีวันจบ · ห้ามอนุมัติให้ตัวเอง

### 5.5 Verify และปิด

1. PR ที่แก้อ้าง finding id (ไม่ใส่รายละเอียด exploit ใน commit message — repo public)
2. regression test ที่พิสูจน์ว่าช่องปิด (unit / IT / ZAP rule)
3. scanner ตัวที่เจอรันซ้ำแล้วไม่เจอ
4. deploy production + บันทึก SHA
5. ปิด ticket พร้อมลิงก์ PR · scan ผลใหม่ · deploy record → นี่คือ **evidence ticket** ที่ auditor sample

---

## 6. วิธีวัดและ scan

### 6.1 Toolchain

| ชั้น | เครื่องมือ | รันที่ไหน / เมื่อไร | Gate |
|---|---|---|---|
| Secrets | **gitleaks** (มีแล้ว) + GitHub **secret scanning + push protection** (ฟรีสำหรับ public repo) | ทุก push / PR · push protection ที่ GitHub | finding ใดๆ = fail |
| SAST | **GitHub CodeQL** (`java-kotlin` + `javascript-typescript` · ฟรีสำหรับ public repo) และ/หรือ **Semgrep** (`p/java` · `p/typescript` · `p/owasp-top-ten`) | ทุก PR + รายสัปดาห์บน `main` | High+ ใหม่ = fail |
| SCA | **osv-scanner** (อ่าน `pom.xml` / Maven lock และ `yarn.lock` v1) หรือ **OWASP Dependency-Check** (`-DfailBuildOnCVSS=7`) · `yarn audit --level high` · **Dependabot** (maven · npm · github-actions) หรือ Renovate | ทุก PR + รายวัน | High+ ที่แก้ได้ = fail · ที่แก้ไม่ได้ต้องมี exception |
| SBOM | **CycloneDX Maven plugin** (`cyclonedx-maven-plugin` · `makeAggregateBom`) · frontend: `@cyclonedx/yarn-plugin` ใช้ได้กับ Yarn Berry เท่านั้น — repo นี้ใช้ **yarn lockfile v1** จึงใช้ `cdxgen` หรือ `syft` แทนจนกว่าจะย้าย | ทุก release / tag | แนบกับ GitHub Release ทุกครั้ง |
| Container / FS / IaC | **Trivy** `fs` (dependency + secret) · `config` (Dockerfile · compose · nginx) · `image` (ถ้า build image) | ทุก PR (fs/config) · รายสัปดาห์ (image) | Critical / High = fail |
| License | Trivy license หรือ `license-maven-plugin` + `license-checker` | ทุก PR | license ห้ามใช้ (เช่น AGPL ใน dependency ที่ ship) = fail |
| DAST | **OWASP ZAP baseline** กับ staging · **authenticated scan** ด้วย test account (role ต่ำ + role admin แยกรอบ) | รายสัปดาห์ (schedule) | High = issue อัตโนมัติ (ไม่ block PR) |
| TLS | **testssl.sh** / **sslyze** กับ HTTPS endpoint และ gateway: `testssl.sh --starttls postgres <host>:5439` | รายเดือน + หลังเปลี่ยน cert / config | grade ต่ำกว่าเกณฑ์ / TLS < 1.2 = issue High |
| Protocol fuzzing (M29) | fuzz message pgwire ผิดรูป (startup · password · Parse/Bind/Execute ความยาวผิด · type OID แปลก) · **Jazzer** สำหรับ parser ฝั่ง Java (`QueryRewriter` · `LexicalGate` · decoder ของ pgwire) | nightly 30 นาที + ก่อน release | crash / hang / fail-open ใหม่ = Critical |
| Host hardening | CIS Benchmark ผ่าน **Lynis** (หรือ OpenSCAP) · patch OS รายเดือน · critical patch ≤ 7 วัน | รายเดือน | hardening index ลดลง = issue |
| Pentest | ภายนอกปีละครั้ง (และก่อนเปิด gateway) · ภายในรายไตรมาส | ตามรอบ | ผลเข้า §5 |

### 6.2 Job layout ที่เสนอ (sketch — **ยังไม่แก้ `ci.yml`**)

| Job | Trigger | Fail เมื่อ |
|---|---|---|
| `backend` / `frontend` / `design-system-drift` / `secrets` / `integration` | มีแล้ว | ตามเดิม · เสนอให้ `integration` เป็น required check ของ PR ที่แตะ SQL / migration |
| `codeql` | PR · push `main` · weekly | alert severity ≥ high ใหม่ |
| `sca` | PR · daily | vuln ≥ High ที่มี fix และไม่มี exception |
| `trivy-fs-config` | PR | Critical / High ใน fs หรือ misconfig High |
| `sbom` | tag `v*` | สร้าง SBOM ไม่ได้ |
| `zap-baseline` | weekly (staging) | ไม่ fail build — เปิด issue |
| `fuzz-nightly` | nightly (หลัง M29) | crash / timeout |
| `evidence-archive` | push `main` · weekly | upload artifact ไม่สำเร็จ |

```yaml
# Sketch only — not wired into .github/workflows/ci.yml.
permissions:
  contents: read            # least privilege by default; jobs widen it explicitly

on:
  pull_request:
  push:
    branches: [main]
  schedule:
    - cron: '0 2 * * 1'     # weekly deep scans

jobs:
  codeql:
    runs-on: ubuntu-latest
    permissions: { contents: read, security-events: write }
    strategy:
      matrix: { language: [java-kotlin, javascript-typescript] }
    steps:
      - uses: actions/checkout@<pinned-sha>
      - uses: github/codeql-action/init@<pinned-sha>
        with: { languages: '${{ matrix.language }}', queries: security-extended }
      - if: matrix.language == 'java-kotlin'
        run: ./mvnw -B -DskipTests package
      - uses: github/codeql-action/analyze@<pinned-sha>

  sca:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<pinned-sha>
      - name: osv-scanner (Maven + yarn.lock)
        run: osv-scanner scan --recursive --format sarif --output osv.sarif . # fails on findings
      - name: yarn audit
        working-directory: frontend/app
        run: yarn audit --level high --groups dependencies

  trivy-fs-config:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<pinned-sha>
      - run: trivy fs --scanners vuln,secret,misconfig,license --severity HIGH,CRITICAL --exit-code 1 .

  sbom:
    if: startsWith(github.ref, 'refs/tags/v')
    runs-on: ubuntu-latest
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@<pinned-sha>
      - run: ./mvnw -B -DskipTests org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
      - run: npx @cyclonedx/cdxgen -o frontend-bom.json frontend/app
      - run: gh release upload "$GITHUB_REF_NAME" target/bom.json frontend-bom.json
        env: { GH_TOKEN: '${{ github.token }}' }

  zap-baseline:
    if: github.event_name == 'schedule'
    runs-on: ubuntu-latest
    steps:
      - uses: zaproxy/action-baseline@<pinned-sha>
        with:
          target: 'https://staging.example.test/'
          fail_action: false   # opens an issue instead of failing the run
```

เครื่องมือแต่ละตัวต้อง pin version และ verify checksum แบบเดียวกับที่ job `secrets` ทำกับ gitleaks อยู่แล้ว · staging ต้องเป็นข้อมูลปลอม (ไม่ใช่ source ของลูกค้า)

### 6.3 Scan ที่ต้องทำมือ / นอก CI

| รายการ | ความถี่ | ผู้รับผิดชอบ |
|---|---|---|
| Direct-access check (FR-6.3.1) ทุก source ที่เป็นโหมด proxy / secure view | รายเดือน | data platform owner |
| ทบทวน `trustedProxies` · firewall rule · port ที่เปิดบน host | รายไตรมาส | ผู้ดูแล infrastructure |
| ทบทวน account ที่มี `PLATFORM_ADMIN` | รายเดือน | security owner |
| ทบทวนค่าตั้ง LLM (shared / personal gateway · feature access ต่อ role) | รายไตรมาส | security owner |

### 6.4 KPI รายเดือน (รายงานผู้บริหาร — CC4.1/CC4.2)

| KPI | สูตร / แหล่ง | เป้า |
|---|---|---|
| Open vulns ตาม severity และอายุ เทียบ SLA | tracker | 0 Critical/High เลย SLA |
| MTTR ต่อ severity | วันปิด − วันยืนยัน | Critical ≤ 7 · High ≤ 30 |
| Scanner coverage | % ของ repo / service / image ที่มีแต่ละ scanner | 100% |
| Failed-login spike | `audit_auth_event` LOGIN_FAIL ต่อชั่วโมงเทียบ baseline | alert เมื่อ > 3σ |
| Lockout ต่อสัปดาห์ | `audit_auth_event` LOCKED | ติดตามแนวโน้ม |
| PAT: ทั้งหมด / ใกล้หมด ≤ 7 วัน / ไม่ใช้ ≥ 30 วัน / ไม่มีเจ้าของที่ active | ตาราง PAT | 0 ไม่มีเจ้าของ |
| Access review completion (M32) | รายการที่ตัดสินแล้ว / ทั้งหมด ภายในกำหนด | ≥ 95% |
| Grant ที่ไม่มีวันจบ | `access_grant` `valid_until IS NULL AND revoked_at IS NULL` | ลดลงเป็น 0 หลัง G-12 |
| Audit pipeline health | export รายวันสำเร็จ · hash chain ตรวจผ่าน · ช่องว่างของ id | 100% |
| Backup restore test | ผ่าน / ไม่ผ่าน · RTO ที่วัดได้ | ผ่านทุกไตรมาส |
| PR ที่ merge โดยไม่มี reviewer อิสระ | GitHub API | 0 (ยกเว้น emergency ที่มี review ตามหลัง) |
| Offboarding SLA | วันออก → วันปิดบัญชี + revoke session | ≤ 1 วันทำการ |

---

## 7. เก็บหลักฐานตลอด Type 2 window

**หลัก:** หลักฐานต้องเกิดเองจากระบบ · มี timestamp · แก้ย้อนหลังไม่ได้ · เก็บ **≥ 1 ปี** (แนะนำ 13 เดือนเพื่อครอบ window 12 เดือน + เวลาของ auditor)

| หลักฐาน | สร้างจาก | ความถี่ | ที่เก็บ | TSC |
|---|---|---|---|---|
| CI run log + ผล scan (SARIF · SBOM · Trivy · ZAP · testssl) | GitHub Actions artifact (default เก็บ 90 วัน → **job `evidence-archive` คัดลอกไป storage ที่เก็บ ≥ 13 เดือน**) | ทุก run | object storage แบบ immutable | CC7.1 CC8.1 |
| PR + review + status check | GitHub API export (PR · reviewer · approval · merge by · checks) | รายเดือน | storage เดียวกัน | CC8.1 |
| Deploy record (SHA · เวลา · ผู้ deploy · ผล smoke test) | script deploy เขียน log | ทุก deploy | storage | CC8.1 |
| Branch protection settings | GitHub API snapshot | รายเดือน | storage | CC8.1 |
| Access review export (M32) | CSV / PDF จาก ARAK | ทุกรอบ | storage | CC6.2 CC6.3 |
| `audit_*` export (รวม `audit_auth_event`) + hash chain checkpoint | job รายวัน | รายวัน | WORM / SIEM | CC7.2 C1.1 |
| User list + role (`PLATFORM_ADMIN` ฯลฯ) | ARAK export | รายเดือน | storage | CC6.1 CC6.3 |
| Onboarding / offboarding ticket | HR + IT ticketing | ต่อเหตุการณ์ | ticket system | CC6.2 |
| On-call / incident ticket + postmortem | ticketing | ต่อเหตุการณ์ | ticket system | CC7.3–7.5 |
| Vulnerability ticket + exception | tracker (private) | ต่อ finding | tracker | CC7.1 |
| Backup job log + restore test log | DB host / script | รายวัน / รายไตรมาส | storage | A1.2 A1.3 |
| Training · policy acknowledgement | HR | รายปี / ตอนเข้างาน | HR system | CC1.4 CC2.2 |
| Vendor review (รวม LLM provider) | security owner | รายปี | document store | CC9.2 |
| Risk assessment + management review | ผู้บริหาร | รายปี / รายไตรมาส | document store | CC3 CC4 |

**เครื่องมือ compliance automation (ทางเลือก ไม่ได้รับรองตัวใด):** Vanta · Drata · Secureframe — เชื่อม GitHub / cloud / HR / identity provider แล้วเก็บหลักฐานซ้ำๆ ให้อัตโนมัติ + ทำ policy template + ติดตาม training · ข้อควรระวัง: ไม่ต่อเข้ากับ App DB ของ ARAK หรือ source ของลูกค้าตรงๆ · ให้ข้อมูลผ่าน export ที่ไม่มี client IP / SQL / credential (กติกาเดียวกับ FR-20)

---

## 8. Roadmap

| Phase | ระยะ (ประมาณ) | งาน | Exit criteria | ARAK milestone |
|---|---|---|---|---|
| **0 · Readiness gap assessment** | 2–4 สัปดาห์ | เลือก TSC (§1.1) · เขียน system description + boundary (§1.3) · แต่งตั้ง owner ทุก control ใน §1.4 · ร่าง policy ชุดหลัก · risk assessment ครั้งแรก · เลือก auditor / consultant · เปิด tracker (private) | ทุกแถวใน §2 มี owner + แผน | — |
| **1 · ปิด P0** | 6–10 สัปดาห์ | G-01 session revocation · G-02 `audit_auth_event` · G-03 audit append-only + export · G-08 CodeQL + SCA ใน CI · G-09 Entra SSO + MFA · G-10 branch protection เต็มรูป + emergency procedure · G-11 backup + restore test ครั้งแรก · refuse Fernet key ผิดรูป (G-07 ส่วนแรก) · `SECURITY.md` | P0 = 0 · restore test ผ่าน 1 ครั้ง · evidence pipeline (§7) เก็บได้ครบ 2 สัปดาห์ติด | **M2** (Entra) · **M8** (audit · FR-8.4 SIEM · Vault) |
| **2 · เริ่ม observation window** | วันที่ 1 ของ window | freeze control design · เริ่มนับ · KPI รายเดือน (§6.4) · ปิด P1 ในช่วงต้น (G-04 G-05 G-06 G-07 G-12) · access review รอบแรก | หลักฐานรายเดือนครบทุกเดือน | **M32** (Access Review) · M31 (purpose cap ของ grant) |
| **Type 1 (optional)** | ระหว่าง window เดือนที่ 1–2 | ให้ auditor ยืนยัน design ก่อน — ลดความเสี่ยงเจอ design gap ตอนจบ window | รายงาน Type 1 | — |
| **3 · ขยาย control ระหว่าง window** | ระหว่าง window | gateway เปิดใช้เฉพาะหลัง control ใน §4 ครบ + pentest เฉพาะ gateway · service account แทน PAT ของคนสำหรับ pipeline · anomaly detection ช่วย CC7.2 | control ใหม่ทุกตัวมีหลักฐานตั้งแต่วันเปิด | **M29** (gateway) · **M36** (service accounts) · **M19** (anomaly detection) · M14 (PAT / API) |
| **4 · Type 2 report** | หลัง window 3–6 เดือน (รอบแรก) | auditor sample · ตอบ exception · management response | รายงาน Type 2 | — |
| **ปีต่อไป** | ต่อเนื่อง | window 12 เดือน · พิจารณาเพิ่ม Privacy (PDPA) และ Processing Integrity (หลัง M35 policy test case) | — | M35 |

**ข้อควรระวังเรื่องลำดับ:** อย่าเปิด gateway (M29) ใน production ก่อนเริ่ม window ถ้า control ใน §4 ยังไม่ครบ — ช่องทางใหม่ที่ยังไม่มีหลักฐานจะกลายเป็น scope ที่ต้องอธิบายใน exception · ถ้าเปิดระหว่าง window ให้ระบุวันเปิดใน system description

---

## ภาคผนวก A — สิ่งที่ยังไม่ได้ยืนยันจาก repo

| เรื่อง | สถานะ |
|---|---|
| TLS ของ HTTPS endpoint (version · cipher · HSTS) | อยู่ใน nginx ของ host ที่แชร์ — ไม่อยู่ใน repo · ต้องตรวจด้วย testssl |
| ค่าจริงของ branch protection บน GitHub (required reviewers · include administrators) | ตั้งที่ GitHub ไม่อยู่ใน repo · CONTRIBUTING บอก 1 approval |
| production ทำ `GRANT INSERT, SELECT` ให้ runtime role แยกจาก owner ตามที่ V5 เขียนไว้หรือไม่ | DEPLOY.md บอก schema "owned by one role" และ Flyway รันตอน start ด้วย user เดียวกัน ⇒ น่าจะไม่ได้แยก · ต้องตรวจบน host |
| backup ของ DB host มีอยู่แล้วโดย host owner หรือไม่ | ไม่มีในเอกสาร |
| LLM provider ที่ gateway ปลายทางใช้ · data retention ของ provider | ตั้งค่า runtime ไม่อยู่ใน repo |

## ภาคผนวก B — คำศัพท์

**TSC** Trust Services Criteria · **CC** Common Criteria · **CUEC** Complementary User Entity Control (สิ่งที่ลูกค้าต้องทำเอง) · **Carve-out** ตัด subservice ออกจาก scope แต่ต้องอธิบาย · **Observation window** ช่วงที่ auditor sample หลักฐาน · **PAT** Personal Access Token · **WORM** Write Once Read Many · **SBOM** Software Bill of Materials · **SAST / SCA / DAST** Static / Software Composition / Dynamic Analysis · **MTTR** Mean Time To Remediate
