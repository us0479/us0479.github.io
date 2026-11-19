"""Compare verbatim QRY-08 (via prepared.sql) with a brute-force R02 reference on random data."""
import os, random, subprocess, sys, datetime as dt
from collections import defaultdict
random.seed(int(sys.argv[2]) if len(sys.argv) > 2 else 7)
KST = dt.timezone(dt.timedelta(hours=9)); base = dt.datetime(2026, 11, 5, 9, 0, tzinfo=KST)
rows = []; eid = 1
for _ in range(int(sys.argv[3]) if len(sys.argv) > 3 else 2000):
    acc = random.randint(1, 3)
    t = base + dt.timedelta(seconds=random.choice([random.randint(0, 3600), random.randint(0, 60) * 60]))
    if random.random() < 0.7: rows.append((t, eid, acc, "LOGIN_FAILED", None))
    else: rows.append((t, eid, acc, "LOGIN_SUCCESS", random.random() < 0.6))
    eid += 1
def bucket(t):
    origin = dt.datetime(2000, 1, 1, tzinfo=KST); s = (t - origin).total_seconds()
    return origin + dt.timedelta(seconds=(s // 600) * 600)
groups = defaultdict(list)
for r in rows: groups[(r[2], bucket(r[0]))].append(r)
ref = {}
for (acc, b), rs in groups.items():
    q = [s for s in rs if s[3] == "LOGIN_SUCCESS" and s[4]
         and sum(1 for f in rs if f[3] == "LOGIN_FAILED" and f[0] < s[0]) >= 5]
    if q:
        a = min(q, key=lambda s: (s[0], s[1]))
        ref[(acc, b.isoformat())] = (a[1], sum(1 for f in rs if f[3] == "LOGIN_FAILED" and f[0] < a[0]), len(q))
vals = ",".join(f"('{t.isoformat()}'::timestamptz,{i},{a},'d','{ty}',{'NULL' if n is None else str(n).lower()})" for t, i, a, ty, n in rows)
pg = "psql -X -q -At -F| -v ON_ERROR_STOP=1"  # PGHOST/PGPORT/PGDATABASE from environment
if not os.environ.get("PGDATABASE", "").startswith("bankwatch_test_"): sys.exit("refuse: set PGDATABASE=bankwatch_test_*")
owner_sql = ("UPDATE bankwatch.customer_account SET lock_incident_id=NULL; TRUNCATE bankwatch.incident_history, bankwatch.alert_event, bankwatch.incident, bankwatch.alert, bankwatch.security_event, bankwatch.customer_account RESTART IDENTITY; INSERT INTO bankwatch.customer_account (customer_ref, display_name) VALUES ('C1001','a'),('C1002','b'),('C1003','c');"
             f"INSERT INTO bankwatch.security_event (occurred_at,event_id,account_id,device_ref,event_type,is_new_device) VALUES {vals};")
_r = subprocess.run(pg.split() + ["-U", os.environ.get("BW_OWNER_ROLE", "bw_schema_owner")], input=owner_sql, text=True, capture_output=True)
if _r.returncode: sys.exit(_r.stderr[-600:])
q = open(sys.argv[1]).read() + "\nSET TIME ZONE 'Asia/Seoul'; EXECUTE detectionservice_candidates('2026-11-05T09:00:00+09:00','2026-11-05T10:10:00+09:00',5);\n"
out = subprocess.run(pg.split() + ["-U", "bankwatch_app"], input=q, text=True, check=True, capture_output=True).stdout
got = {}
for line in out.strip().splitlines():
    acc, b, at, aid, pf, cc = line.split("|")
    b = dt.datetime.fromisoformat(b.replace("+09", "+09:00")).isoformat()
    got[(int(acc), b)] = (int(aid), int(pf), int(cc))
print(f"rows={len(rows)} ref_buckets={len(ref)} sql_buckets={len(got)} equal={ref == got}")
if ref != got:
    for k in sorted(set(ref) | set(got)):
        if ref.get(k) != got.get(k): print(k, "ref", ref.get(k), "sql", got.get(k))
    sys.exit(1)
