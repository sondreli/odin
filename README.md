
## TODO
* When recategorizing a transaction marker it needs to be deleted from its previus category
  or the user needs to be made avare

## nginx as revers proxy
recieves traffic on 80 and 443 and redirects it to 8080

config: /etc/nginx/sites-available/odin_reverse_proxy

test key and certificat generated with:
```
openssl req -x509 -newkey rsa:4096 -keyout key.pem -out cert.pem -sha256 -days 365 -subj 'CN=localhost' -nodes
-nodes means no password on private key
```

After doing a change to the config, restart the service:
```
sudo systemctl restart nginx
```
* shadow-cjs-template
A simple template for my shadow-cljs experiments

## Fronend Usage

Install dependencies:

     npm install


Run Shadow CLJS dev server (and REPL):

     npm run watch


In another terminal, recompile Tailwind CSS if it changes:

     npm run watch:css
