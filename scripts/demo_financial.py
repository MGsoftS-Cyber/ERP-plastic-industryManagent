#!/usr/bin/env python3
"""Creates fictional demo records. Run from project root against the local demo profile.
Requires all manufacturing, workforce and financial demo credentials from .env/environment.
Includes manufacturing and workforce examples, then posts finance and captures costs/recovery.
No physical payments or equipment actions are performed.
"""
import base64
import json
import os
import subprocess
import sys
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from uuid import uuid4

env = {}
if Path('.env').exists():
    for line in Path('.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
roles = {'accountant':'ACCOUNTANT_PASSWORD','finance':'FINANCE_PASSWORD',
         'financeapprover':'FINANCE_APPROVER_PASSWORD','production':'PRODUCTION_PASSWORD'}
passwords = {r:os.environ.get(k, env.get(k,'')) for r,k in roles.items()}
if not all(passwords.values()):
    raise SystemExit('Set accountant, finance, finance approver and manufacturing/workforce demo passwords.')
base = os.environ.get('PLASTIFLOW_API_URL', 'http://127.0.0.1:8080')
suffix = uuid4().hex[:8].upper()
def call(role, method, path, body=None):
    token = base64.b64encode((role+':'+passwords[role]).encode()).decode()
    req = Request(base+'/api/v1/finance'+path, method=method,
                  data=json.dumps(body).encode() if body is not None else None,
                  headers={'Authorization':'Basic '+token,'Content-Type':'application/json'})
    try:
        with urlopen(req, timeout=30) as response:
            raw=response.read()
            return json.loads(raw) if raw else None
    except HTTPError as error:
        raise SystemExit(f'{method} {path}: HTTP {error.code}: {error.read().decode()}') from None
    except URLError:
        raise SystemExit('Cannot reach API. Start the stack and check PLASTIFLOW_API_URL.') from None

def post(role, path, body=None): return call(role, 'POST', path, body)
def demo(file):
    result=subprocess.run([sys.executable,'scripts/'+file],capture_output=True,text=True)
    if result.returncode: raise SystemExit(result.stderr or result.stdout)
    return json.loads(result.stdout)
# These create fresh upstream records rather than mutating existing factory work.
manufacturing=demo('demo_manufacturing.py')
workforce=demo('demo_workforce.py')
data=call('accountant','GET','/overview')
pay_period=next(p for p in data['payPeriods'] if p['id']==workforce['period'])
# Retrieve dates using payroll account; finance overview intentionally omits personal payroll data.
all_env = dict(env);all_env.update(os.environ)
req=Request(base+'/api/v1/payroll/periods/'+workforce['period'],headers={'Authorization':'Basic '+base64.b64encode(('payroll:'+all_env['PAYROLL_PASSWORD']).encode()).decode()})
with urlopen(req,timeout=30) as response: pay_detail=json.load(response)
period=post('accountant','/periods',{'code':'FIN-'+suffix,'startsOn':pay_detail['starts_on'],'endsOn':pay_detail['ends_on'],'currency':'DZD'})['id']
def posting(ref): return {'periodId':period,'bookedOn':pay_detail['starts_on'],'reference':ref+'-'+suffix}
accounts={}
for name,kind in [('bank','ASSET'),('receivable','ASSET'),('payable','LIABILITY'),('sales','REVENUE'),('expense','EXPENSE'),('tax','LIABILITY'),('input_tax','ASSET'),('wages','EXPENSE'),('payroll','LIABILITY'),('deductions','LIABILITY'),('capital','EQUITY')]:
    accounts[name]=post('accountant','/accounts',{'code':name.upper()+'-'+suffix,'name':name.title(),'kind':kind})['id']
manual=post('accountant','/journals',{**posting('OPENING'),'eventId':str(uuid4()),'description':'Fictional opening funds','lines':[{'accountId':accounts['bank'],'debit':1000,'credit':0},{'accountId':accounts['capital'],'debit':0,'credit':1000}]})
for kind,net,tax in [('SALE',100,10),('PURCHASE',50,10)]:
    sale=kind=='SALE'
    invoice=post('finance','/invoices',{'code':kind+'-'+suffix,'kind':kind,'partyReference':'DEMO-PARTY','documentReference':kind+'-DOC-'+suffix,'currency':'DZD','net':net,'tax':tax,'dueOn':pay_detail['ends_on'],'controlAccount':accounts['receivable' if sale else 'payable'],'netAccount':accounts['sales' if sale else 'expense'],'taxAccount':accounts['tax' if sale else 'input_tax']})['id']
    post('accountant',f'/invoices/{invoice}/post',posting(kind))
    post('accountant',f'/invoices/{invoice}/payments',{**posting(kind+'-SETTLEMENT'),'eventId':str(uuid4()),'bankAccount':accounts['bank'],'amount':net+tax,'externalReference':kind+'-BANK-EVIDENCE-'+suffix})
budget=post('finance','/budgets',{'code':'UTILITY-'+suffix,'periodId':period,'amount':1000})['id']
expense=post('finance','/expenses',{'code':'ENERGY-'+suffix,'budgetId':budget,'amount':40,'description':'Fictional energy bill evidence'})['id']
post('financeapprover',f'/expenses/{expense}/approve')
post('accountant',f'/expenses/{expense}/post',{**posting('ENERGY'),'expenseAccount':accounts['expense'],'payableAccount':accounts['payable']})
post('accountant',f"/payroll/{workforce['period']}/post",{**posting('PAYROLL'),'wagesAccount':accounts['wages'],'payableAccount':accounts['payroll'],'deductionsAccount':accounts['deductions']})
pay_line=call('accountant','GET',f"/payroll/{workforce['period']}/lines")[0]['id']
# Read the actual consumed lot for the freshly completed manufacturing example.
req=Request(base+'/api/v1/manufacturing/batches/'+manufacturing['batch']+'/trace',headers={'Authorization':'Basic '+base64.b64encode(('production:'+passwords['production']).encode()).decode()})
with urlopen(req,timeout=30) as response: trace=json.load(response)
lot=trace['source_lots'][0]['id']
post('accountant','/lot-values',{'lotId':lot,'currency':'DZD','unitCost':2,'evidence':'Fictional resin purchase price; analytic valuation'})
cost=post('accountant','/costs',{'orderId':manufacturing['order'],'currency':'DZD','allocations':[{'kind':'EXPENSE','sourceId':expense,'amount':10},{'kind':'PAYROLL','sourceId':pay_line,'amount':100}]})
assert cost['total']==112.24 and cost['unit_cost']==1.1224
scrap=post('production','/scrap',{'code':'SCRAP-'+suffix,'lotId':lot,'kg':0.5,'classification':'Segregated damaged resin','reason':'Fictional measured remaining stock damage'})['id']
recovery=post('production',f'/scrap/{scrap}/recover',{'eventId':str(uuid4()),'lotCode':'REGRIND-'+suffix,'location':'QUARANTINE','inputKg':0.5,'recoveredKg':0.4,'dispositionReference':'Fictional weighing and disposal record'})
recovered=call('production','GET','/recovery-trace/'+recovery['outputLotId'])
assert recovered['outputLot']['quality']=='PENDING' and recovered['recovery']['loss_kg']==0.1
trial=call('accountant','GET',f'/periods/{period}/trial-balance')
assert round(sum(row['debit']-row['credit'] for row in trial),2)==0
print(json.dumps({'financial_period':period,'cost':cost['id'],'total_cost':cost['total'],'cost_per_accepted_unit':cost['unit_cost'],'currency':'DZD','recovered_lot':recovery['outputLotId'],'recovered_quality':'PENDING','trial_balance_balanced':True,'real_bank_transfer':False},indent=2))
