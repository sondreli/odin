
## TODO
* When recategorizing a transaction marker it needs to be deleted from its previus category
  or the user needs to be made avare

## nginx as revers proxy
recieves traffic on 80 and 443 and redirects it to 8080

config at: /etc/nginx/sites-available/odin_reverse_proxy
```
server {
        listen 80;
        listen [::]:80;
        listen 443 ssl;
        listen [::]:443 ssl;

        server_name localhost;
        ssl_certificate /etc/nginx/odin/odin.crt;
        ssl_certificate_key /etc/nginx/odin/odin.key;
        keepalive_timeout 70;

        location / {
                proxy_pass http://localhost:8080;
                include proxy_params;
        }
}
```
after config is created, make symlink from config in sites-available to sites-enabled

generate test key and certificat with:
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

## Backend usage

To start development server, run: clj -X odin.core/-main


## Fronend usage

Install dependencies:

     npm install


Run Shadow CLJS dev server (and REPL):

     npm run watch


In another terminal, recompile Tailwind CSS if it changes:

     npm run watch:css

## Database
Using datomic
Specify path to database file in ~/.datomic/local.edn 