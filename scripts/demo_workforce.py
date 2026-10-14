#!/usr/bin/env python3
"""Create an example attendance/payroll workflow in the local demo stack.
Run from project root: python scripts/demo_workforce.py
Creates new records; uses .env credentials without logging them.
"""
import base64
import json
import os
from pathlib import Path
from datetime import datetime, timezone, timedelta
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from uuid import uuid4

env={}
if Path('.env').exists():
    for line in Path('.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key,value=line.split('=',1)
            env[key.strip()]=value.strip().strip('"').strip("'")
roles={'workforce':'WORKFORCE_PASSWORD','payroll':'PAYROLL_PASSWORD','payrollapprover':'PAYROLL_APPROVER_PASSWORD'}
passwords={r:os.environ.get(k,env.get(k,'')) for r,k in roles.items()}
if not all(passwords.values()):raise SystemExit('Set workforce, payroll and payroll approver passwords first.')
base=os.environ.get('PLASTIFLOW_API_URL','http://127.0.0.1:8080')
suffix=uuid4().hex[:8].upper()

def call(role,method,path,body=None):
    token=base64.b64encode((role+':'+passwords[role]).encode()).decode()
    req=Request(base+path,method=method,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Basic '+token,'Content-Type':'application/json'})
    try:
        with urlopen(req,timeout=30) as response:
            raw=response.read()
            return json.loads(raw) if raw else None
    except HTTPError as error:
        raise SystemExit(f'{method} {path}: HTTP {error.code}: {error.read().decode()}') from None
    except URLError:
        raise SystemExit('Cannot reach the API. Start the stack and check PLASTIFLOW_API_URL.') from None

def post(role,path,body=None):return call(role,'POST','/api/v1/payroll'+path,body)

# Use a new future period/rule date on each run, avoiding existing finalized periods.
existing=call('payroll','GET','/api/v1/payroll/overview')
dates=[datetime.now(timezone.utc).date()]
for rule in existing['rules']:dates.append(datetime.fromisoformat(rule['effective_from']).date()+timedelta(days=1))
for period in existing['periods']:dates.append(datetime.fromisoformat(period['ends_on']).date()+timedelta(days=1))
day=max(dates)
start=datetime.combine(day,datetime.min.time(),tzinfo=timezone.utc)+timedelta(hours=20)
end=start+timedelta(hours=8)
employee=call('workforce','POST','/api/v1/manufacturing/employees',{'code':'PAY-EMP-'+suffix,'name':'Example payroll employee','userSubject':'pay-employee-'+suffix})['id']
post('payroll','/wages',{'employeeId':employee,'effectiveFrom':day.isoformat(),'basis':'HOURLY','amount':60,'currency':'DZD','monthlyMinutes':0})
post('payroll','/rules',{'effectiveFrom':day.isoformat(),'nightStart':'22:00','nightEnd':'06:00','nightType':'PERCENT','nightValue':25,'overtimePercent':50,'stacking':'ADDITIVE'})
raw=[]
for kind,at in [('IN',start),('OUT',end)]:
    event=str(uuid4());post('workforce','/clock-events',{'eventId':event,'employeeId':employee,'kind':kind,'occurredAt':at.isoformat(),'source':'Fictional example evidence'});raw.append(event)
attendance=post('workforce','/attendance',{'employeeId':employee,'start':start.isoformat(),'end':end.isoformat(),'unpaidBreaks':[{'start':(start+timedelta(hours=3)).isoformat(),'end':(start+timedelta(hours=3,minutes=30)).isoformat()}],'rawEventIds':raw,'notes':'Example shift with unpaid break'})['id']
post('workforce',f'/attendance/{attendance}/approve',{'overtimeMinutes':60,'notes':'Net hours and one hour overtime approved'})
period=post('payroll','/periods',{'code':'PAY-'+suffix,'start':day.isoformat(),'end':(day+timedelta(days=2)).isoformat(),'zoneId':'UTC','currency':'DZD','moneyScale':2,'employeeIds':[employee]})['id']
bonus=post('payroll',f'/periods/{period}/adjustments',{'employeeId':employee,'kind':'BONUS','amount':20,'reason':'Fictional performance bonus'})['id']
post('payrollapprover',f'/adjustments/{bonus}/decision',{'approved':True,'notes':'Bonus approved'})
result=post('payroll',f'/periods/{period}/calculate')
line=result['lines'][0]
assert line['worked_minutes']==450 and line['night_minutes']==330
assert line['gross']==582.5 and line['payable']==582.5
post('payrollapprover',f'/periods/{period}/review',{'notes':'Calculation and source versions checked'})
post('payroll',f'/periods/{period}/finalize',{'notes':'Approved calculation finalized; no bank payment submitted'})
print(json.dumps({'period':period,'employee':employee,'worked_minutes':450,'night_minutes':330,'approved_overtime_minutes':60,'gross':582.5,'currency':'DZD','state':'FINALIZED','statutory_taxes_configured':False},indent=2))
