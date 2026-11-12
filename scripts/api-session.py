#!/usr/bin/env python3
"""Export authenticated OpenAPI from the running local development API."""
import http.cookiejar,json,os,urllib.request
from pathlib import Path
origin=os.environ.get('API_ORIGIN','http://localhost:8085')
jar=http.cookiejar.CookieJar();client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def call(path,body=None,headers=None):
 return client.open(urllib.request.Request(origin+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers or {}))
csrf=json.load(call('/auth/csrf'))
call('/auth/login',{'email':'owner@northstar.test','password':os.environ.get('DEMO_PASSWORD','HeliosDemo!2026')},{'Content-Type':'application/json',csrf['headerName']:csrf['token']})
contract=json.load(call('/openapi'));contract['servers']=[{'url':'/','description':'Same origin'}]
Path('packages/api-contract/openapi.json').write_text(json.dumps(contract,indent=2,sort_keys=True)+'\n')
