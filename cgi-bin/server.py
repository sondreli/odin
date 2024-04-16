#!/usr/bin/env python3.8

import cgi
import requests, json

form = cgi.FieldStorage()
authorization_code = form.getvalue('code')

client_id = 'c46be8db-9767-4286-96a0-c8aaacee4b26'
client_secret = '2732c9ad-815d-46f1-8336-ef29bd03e882'
fid = 'fid-ostlandet'

redirect_uri = "http://localhost:8000/cgi-bin/tutorial.py"

authorize_uri = "https://api.sparebank1.no/oauth/authorize"
token_uri = "https://api.sparebank1.no/oauth/token"

if (authorization_code):
    data = {'grant_type': 'authorization_code', 'code': authorization_code, 'redirect_uri': redirect_uri}
    token_response = requests.post(token_uri, data=data, verify=False, allow_redirects=False, auth=(client_id, client_secret))
    access_tokens = json.loads(token_response.text)
    access_token = access_tokens['access_token']
    
    content = 'Access token: ' + access_token

else:
    content = '<a href=' +  authorize_uri + \
        '?response_type=code&client_id=' + client_id + \
        '&redirect_uri=' + redirect_uri + \
        '&finInst=' + fid + \
        '&state=state' + \
        '>Login</a>'

print("Content-type:text/html;charset=utf-8\r\n\r\n")
print("<html>")
print("<head><title>Personal client</title></head>")
print("<body>")
print(content)
print("</body>")
print("</html>")
