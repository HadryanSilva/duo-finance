# Runbook de provisionamento e operação

Guia executado à mão pelo product owner para colocar o Duo Finance no ar e operá-lo.
Cada passo traz o comando exato e o resultado esperado. Referência de arquitetura:
seção 5 da [spec](superpowers/specs/2026-11-05-duo-finance-mvp-design.md).

Convenções: os comandos rodam na instância, como o usuário `deploy`, salvo quando marcados
"na sua máquina" ou "como `ubuntu`". O `deploy` não tem `sudo`: todo passo que precisa de root roda
como `ubuntu` (o usuário padrão do Lightsail, com `sudo`), numa sessão
`ssh -i <CHAVE_DO_LIGHTSAIL>.pem ubuntu@<IP_ESTATICO>` ou no terminal do console.
Caminho de deploy: `/opt/duo-finance` (`DEPLOY_PATH`). O projeto do compose se chama
`duo-finance`, então os volumes são `duo-finance_pgdata`, `duo-finance_certbot-etc` e
`duo-finance_certbot-www`.

## 1. Pré-requisitos

- Conta AWS com acesso ao Lightsail, ao S3 e ao IAM.
- Domínio `duofinance.dev.br` com acesso ao painel de DNS.
- Conta Resend com o domínio `duofinance.dev.br` verificado: registros SPF e DKIM publicados e status
  "Verified" no painel. Anote a API key (`re_...`).
- Repositório GitHub `hadryansilva/duo-finance` com permissão de administrador (secrets, environments,
  branch protection).
- Na sua máquina: `ssh`, `scp`, `dig` e `openssl`.

## 2. Instância

1. No console do Lightsail, crie uma instância Linux/Unix, blueprint **Ubuntu LTS**, plano de **2 GB**
   de RAM. Região: a mais próxima dos usuários disponível no Lightsail (ex.: `us-east-1`; confira a
   lista de regiões no console, pois nem toda região da AWS tem Lightsail). O bucket S3 de backup
   (seção 9) fica na mesma região. Anote a região (`<REGIAO>`) e o nome da instância
   (`<NOME_DA_INSTANCIA>`).
   Resultado esperado: instância em estado "Running".
2. Em **Networking**, crie um **IP estático** e anexe à instância. Anote o IP (`<IP_ESTATICO>`).
3. Firewall (Networking da instância), regras **IPv4**: deixe apenas
   - SSH, TCP 22, restrito ao IP do administrador ("Restrict to IP address");
   - HTTP, TCP 80, qualquer origem;
   - HTTPS, TCP 443, qualquer origem.

   A porta 22 fica fechada ao resto do mundo, mas o deploy do GitHub Actions precisa de SSH a partir
   de runners hospedados pelo GitHub, que têm IPs variáveis. Por isso o workflow `deploy.yml` abre a
   porta 22 para o `/32` do próprio runner (via API do Lightsail, com a role da seção 8.2) só pelo
   tempo do deploy e a fecha em seguida, mesmo se o deploy falhar. Você não precisa liberar faixas
   de IP do GitHub.
4. **IPv6.** O Lightsail tem um firewall IPv6 separado, com regras próprias. Desabilite o IPv6 da
   instância (Networking > IPv6 > Disable) ou espelhe as regras do IPv4 (80 e 443 abertas, **sem
   a 22 sobre IPv6**). Para a instância ficar acessível só por IPv4 (veja também a seção 4),
   prefira desabilitar. Verificação, na sua máquina:

   ```bash
   aws lightsail get-instance-port-states --instance-name <NOME_DA_INSTANCIA> --region <REGIAO> \
     --query 'portStates[].[fromPort,protocol,cidrs,ipv6Cidrs]' --output json
   ```

   Resultado esperado: a porta 22 com somente o seu `/32` em `cidrs` e `ipv6Cidrs` vazio; 80 e 443
   sem 22 em nenhuma regra IPv6. (Sem o AWS CLI, confira em Networking > IPv6 firewall.)
5. Em **Snapshots**, ligue **Automatic snapshots**. O Lightsail só oferece snapshots diários e guarda
   os últimos 7, o que cobre (e supera) o "snapshot automático semanal" da spec.
6. Conecte como `ubuntu` (`ssh -i <CHAVE_DO_LIGHTSAIL>.pem ubuntu@<IP_ESTATICO>` ou o terminal do
   console) e adicione swap de 2 GB (a instância tem 2 GB de RAM; o swap evita OOM em picos):

   ```bash
   sudo fallocate -l 2G /swapfile
   sudo chmod 600 /swapfile
   sudo mkswap /swapfile
   sudo swapon /swapfile
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   free -h
   ```

   Resultado esperado: a linha `Swap:` de `free -h` mostra `2.0Gi`.

## 3. Sistema

Conecte como o usuário padrão do Lightsail (`ubuntu`) e prepare o servidor.

1. Usuário `deploy` com a sua chave SSH (a chave pública fica em `~/.ssh/id_ed25519.pub` na sua máquina):

   ```bash
   sudo adduser --disabled-password --gecos "" deploy
   sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
   echo '<CONTEUDO_DA_CHAVE_PUBLICA>' | sudo tee /home/deploy/.ssh/authorized_keys
   sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys
   sudo chmod 600 /home/deploy/.ssh/authorized_keys
   sudo -l -U deploy
   ```

   O `deploy` **não** recebe `sudo`: o último comando deve dizer que `deploy` não pode rodar `sudo`
   ("User deploy is not allowed to run sudo"). Os passos de root deste runbook (pacotes, cron,
   logrotate) rodam como `ubuntu`. Lembre que pertencer ao grupo `docker` (passo 4) já equivale a
   root na máquina: trate a chave SSH do `deploy` como uma chave de root.

   Na sua máquina: `ssh deploy@<IP_ESTATICO> whoami` deve imprimir `deploy`.

2. Desabilite senha e login do root. Mantenha a sessão atual aberta e teste o `deploy` em outra janela.

   ```bash
   sudo tee /etc/ssh/sshd_config.d/00-hardening.conf <<'CONF'
   PasswordAuthentication no
   PermitRootLogin no
   CONF
   sudo sshd -t && sudo systemctl reload ssh
   sudo sshd -T | grep -E '^(passwordauthentication|permitrootlogin)'
   ```

   Resultado esperado: `passwordauthentication no` e `permitrootlogin no`.

3. Atualizações automáticas de segurança:

   ```bash
   sudo apt-get update && sudo apt-get install -y unattended-upgrades
   sudo dpkg-reconfigure -f noninteractive unattended-upgrades
   systemctl is-enabled unattended-upgrades
   ```

   Resultado esperado: `enabled`.

4. Docker Engine e plugin compose pelo repositório oficial:

   ```bash
   sudo apt-get install -y ca-certificates curl unzip
   sudo install -m 0755 -d /etc/apt/keyrings
   sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
   sudo chmod a+r /etc/apt/keyrings/docker.asc
   echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list
   sudo apt-get update
   sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
   sudo usermod -aG docker deploy
   ```

5. Reconecte como `deploy` (o grupo `docker` só vale em uma sessão nova) e confira as versões.
   O compose precisa ser **2.24 ou superior** (o `docker-compose.local.yml` usa `!override`/`!reset`):

   ```bash
   docker compose version
   docker run --rm hello-world
   ```

   Resultado esperado: `Docker Compose version v2.24.x` ou maior, e a mensagem "Hello from Docker!".
   Se a versão for menor, como `ubuntu`: `sudo apt-get install --only-upgrade docker-compose-plugin`.

6. Diretório de deploy, com dono `deploy` (como `ubuntu`):

   ```bash
   sudo install -d -o deploy -g deploy -m 755 /opt/duo-finance
   ```

## 4. DNS

No painel do domínio, crie **somente registros A** (não crie AAAA; veja a seção 2, passo 4, e a
seção 7, passo 6):

| Nome | Tipo | Valor |
| --- | --- | --- |
| `duofinance.dev.br` | A | `<IP_ESTATICO>` |
| `www.duofinance.dev.br` | A | `<IP_ESTATICO>` |

Verificação (a propagação pode levar de minutos a horas):

```bash
dig +short A duofinance.dev.br
dig +short A www.duofinance.dev.br
dig +short AAAA duofinance.dev.br
```

Resultado esperado: os dois primeiros imprimem `<IP_ESTATICO>`; o terceiro não imprime nada.

## 5. Segredos

Dois arquivos de segredos, ambos **fora do git**, criados na instância (não copiados do Windows), com
finais de linha LF, `chmod 600` e dono `deploy`:

- `.env`: configuração dos contêineres (o `api` recebe o arquivo inteiro via `env_file`);
- `backup.env`: bucket e access key do backup, lidos só pelo `backup.sh` e pelo `restore.sh` no
  host. **Nunca** vai para um contêiner; por isso as chaves `AWS_*` e `BACKUP_*` não ficam no `.env`.

1. Copie os arquivos de infraestrutura (na sua máquina, na raiz do repositório):

   ```bash
   scp infra/docker-compose.yml infra/.env.example infra/backup.env.example infra/scripts/*.sh \
     deploy@<IP_ESTATICO>:/opt/duo-finance/
   ```

2. Na instância, torne os scripts executáveis e crie o `.env` e o `backup.env` (o `sed` remove
   qualquer CR; o `umask` faz os dois nascerem com modo 600):

   ```bash
   cd /opt/duo-finance
   chmod +x ./*.sh
   (umask 077 && sed 's/\r$//' .env.example > .env && sed 's/\r$//' backup.env.example > backup.env)
   chmod 600 .env backup.env
   ls -l .env backup.env
   ```

   Resultado esperado: `-rw------- 1 deploy deploy ... .env` e `-rw------- 1 deploy deploy ... backup.env`.
   O `backup.env` é preenchido na seção 9.

3. Gere as chaves JWT. O script imprime duas linhas (PEM em linha única) e não grava nada em disco:

   ```bash
   ./gen-jwt-keys.sh
   ```

   Resultado esperado: duas linhas, `JWT_PRIVATE_KEY=MIIE...` e `JWT_PUBLIC_KEY=MIIB...`.
   O `.env` já tem as linhas `JWT_PRIVATE_KEY=` e `JWT_PUBLIC_KEY=` vazias: **substitua-as** (não
   acrescente linhas duplicadas ao final). Para trocar as chaves no futuro, substitua as duas linhas
   existentes do mesmo modo; todos os usuários serão deslogados.
4. Gere a senha do banco:

   ```bash
   openssl rand -base64 32
   ```

   Resultado esperado: uma linha de 44 caracteres. Se tiver `/`, `+` ou `=`, não há problema: o valor
   vai sem aspas.
5. Edite o `.env` (`nano .env`) e preencha:

   | Variável | Valor |
   | --- | --- |
   | `IMAGE_TAG` | `bootstrap` por ora; o `deploy.sh` reescreve a linha a cada deploy |
   | `POSTGRES_PASSWORD` | a senha gerada |
   | `DB_PASSWORD` | **a mesma** senha: enquanto o Postgres do compose for usado, `POSTGRES_PASSWORD` e `DB_PASSWORD` precisam ser iguais |
   | `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | as duas linhas do passo 3 |
   | `RESEND_API_KEY` | a API key do Resend |
   | `EMAIL_FROM` | `Duo Finance <no-reply@duofinance.dev.br>` (remetente do domínio verificado) |
   | `PUBLIC_BASE_URL` | `https://duofinance.dev.br` |

   As chaves do backup (`BACKUP_S3_*`, `AWS_*`) não existem no `.env`: ficam no `backup.env`
   (seção 9). Nunca as acrescente ao `.env`, pois tudo o que está nele chega ao contêiner `api`.

   Regras do arquivo (valem também para o `backup.env`): valores **sem aspas** (o `env_value` dos
   scripts de backup remove só aspas duplas, e os valores JWT são de linha única, então aspas nunca
   são necessárias); sem espaços ao redor do `=`. `POSTGRES_DB`, `POSTGRES_USER`, `DB_URL` e `DB_USER` já vêm corretos para o Postgres
   do compose.
6. Verifique:

   ```bash
   grep -c $'\r' .env
   grep -E '^(IMAGE_TAG|POSTGRES_PASSWORD|DB_PASSWORD|PUBLIC_BASE_URL)=' .env | sed 's/=.*/=.../'
   grep -cE '^(AWS_|BACKUP_)' .env
   docker compose config --quiet && echo ok
   ```

   Resultado esperado: `0` (nenhum CR), as quatro variáveis listadas, `0` (nenhuma chave de backup no
   `.env`) e `ok`.

## 6. GHCR

As imagens `ghcr.io/hadryansilva/duo-finance-api` e `ghcr.io/hadryansilva/duo-finance-nginx` (nomes
sempre em minúsculas) são **privadas por padrão**, então a instância precisa se autenticar.

1. No GitHub: Settings > Developer settings > Personal access tokens > Tokens (classic) >
   Generate new token, somente o escopo **`read:packages`** e com **expiração** definida (ex.: 90
   dias; anote a data para renovar). Copie o token (`ghp_...`).
2. Na instância, sem deixar o token no histórico do shell:

   ```bash
   read -rs GHCR_TOKEN
   printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u hadryansilva --password-stdin
   unset GHCR_TOKEN
   ```

   Cole o token no prompt (nada é exibido) e tecle Enter. Resultado esperado: `Login Succeeded`.
   As credenciais ficam em `~/.docker/config.json` do `deploy`, codificadas em base64 (**não
   criptografadas**); confirme o modo: `chmod 600 ~/.docker/config.json`. Ao expirar o token, repita o
   passo, senão o `docker compose pull` do deploy falha.

As imagens só existem depois do primeiro build do pipeline (seção 8); o login pode ser feito antes.

## 7. Primeira subida e certificado

O nginx de produção (`NGINX_TEMPLATE=app`, o padrão) lê o certificado em
`/etc/letsencrypt/live/duofinance.dev.br/` e **não sobe sem ele**. O smoke do `deploy.sh` acessa o site
por HTTPS sem aceitar certificado inválido, então o certificado **real** precisa existir antes do
primeiro deploy. O procedimento abaixo o emite antes da primeira subida, com `--webroot` servido por um
nginx temporário na porta 80; assim a renovação automática (que também usa `--webroot`) funciona
depois. Pré-requisito: DNS da seção 4 propagado e `.env` da seção 5 pronto.

1. Crie os volumes do compose, sem iniciar nada, e confira:

   ```bash
   cd /opt/duo-finance
   docker compose up --no-start certbot
   docker volume ls --format '{{.Name}}' | grep certbot
   ```

   Resultado esperado: `duo-finance_certbot-etc` e `duo-finance_certbot-www`.
2. Suba um nginx temporário que serve o diretório do desafio na porta 80:

   ```bash
   docker run -d --rm --name duo-acme -p 80:80 \
     -v duo-finance_certbot-www:/usr/share/nginx/html:ro nginx:stable-alpine
   ```

3. Emita o certificado para os **dois nomes**, `duofinance.dev.br` e `www.duofinance.dev.br`:

   ```bash
   docker compose run --rm --entrypoint certbot certbot certonly --webroot --non-interactive \
     -w /var/www/certbot -d duofinance.dev.br -d www.duofinance.dev.br \
     --email <SEU_EMAIL> --agree-tos --no-eff-email
   ```

   Resultado esperado: `Successfully received certificate.`, com os arquivos em
   `/etc/letsencrypt/live/duofinance.dev.br/` (o primeiro `-d` dá o nome do diretório, igual ao
   `NGINX_SERVER_NAME` padrão da imagem). Se falhar com "Timeout during connect", o DNS ainda não
   propagou ou o firewall bloqueia a porta 80.
4. Pare o nginx temporário e confira o certificado no volume:

   ```bash
   docker stop duo-acme
   docker compose run --rm --entrypoint certbot certbot certificates
   ```

   Resultado esperado: `Domains: duofinance.dev.br www.duofinance.dev.br` e `VALID: 89 days` (ou próximo).
5. Faça o primeiro deploy pelo pipeline (seção 8.5). Ele sobe o nginx com o certificado real.
   Depois do deploy:

   ```bash
   curl -I https://duofinance.dev.br
   curl -I https://www.duofinance.dev.br
   curl -I http://duofinance.dev.br
   ```

   Resultado esperado: `HTTP/2 200` no primeiro; `301` com `location: https://duofinance.dev.br/` no
   segundo e no terceiro.
6. **IP do cliente e limites de taxa.** Se um cliente chegar ao nginx pelo `docker-proxy` (por exemplo,
   por IPv6 sem o IPv6 configurado de ponta a ponta no Docker), todo IP de cliente vira o gateway da
   bridge e todos compartilham o mesmo balde de `limit_req`. Por isso publique **apenas registro A**
   (sem AAAA), como na seção 4. Para verificar, gere um acesso e veja o `remote_addr` nos logs JSON do
   nginx. Na sua máquina, gere o acesso e descubra o seu IP público; na instância, leia o log:

   ```bash
   # na sua máquina
   curl -s -o /dev/null https://duofinance.dev.br/
   curl -s https://checkip.amazonaws.com
   # na instância
   docker compose logs --tail 5 nginx | grep -o '"remote_addr":"[^"]*"'
   ```

   Resultado esperado: o `remote_addr` é igual ao IP impresso pelo `checkip`. Se aparecer `172.x.x.x` (gateway da
   bridge), os limites estão compartilhados: remova qualquer AAAA e repita.
7. Confira a nota em <https://www.ssllabs.com/ssltest/analyze.html?d=duofinance.dev.br>.
   Resultado esperado: nota **A** ou **A+** (o HSTS está ativo).

## 8. GitHub

### 8.1 Environment `production`

Settings > Environments > New environment > `production`. Em "Deployment branches and tags", escolha
**Selected branches and tags** e adicione somente `main`. Opcional: adicione a si mesmo em "Required
reviewers" para aprovar cada deploy. O job `deploy` de `.github/workflows/deploy.yml` usa esse
environment, e o token OIDC dele só é aceito pela AWS com `environment:production` (próximo passo).

### 8.2 Role AWS para abrir a porta 22 durante o deploy

A porta 22 só aceita o IP do administrador, então o job `deploy` assume uma role da AWS por OIDC (sem
chaves de longa duração) e abre a 22 para o `/32` do runner enquanto o deploy roda. Passos no console
IAM (a role é global; a região fica nas variáveis do GitHub):

1. IAM > Identity providers > Add provider: tipo **OpenID Connect**, provider URL
   `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`.
2. IAM > Roles > Create role > Custom trust policy (nome sugerido `duo-finance-deploy-ssh`):

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Principal": {"Federated": "arn:aws:iam::<ID_DA_CONTA>:oidc-provider/token.actions.githubusercontent.com"},
       "Action": "sts:AssumeRoleWithWebIdentity",
       "Condition": {
         "StringEquals": {
           "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
           "token.actions.githubusercontent.com:sub": "repo:HadryanSilva/duo-finance:environment:production"
         }
       }
     }]
   }
   ```

3. Anexe uma **inline policy** com somente as três ações de porta. `OpenInstancePublicPorts` e
   `CloseInstancePublicPorts` aceitam o ARN da instância; `GetInstancePortStates` é avaliada contra
   `arn:aws:lightsail:<REGIAO>:<ID_DA_CONTA>:*` e só funciona com `"Resource": "*"` (é somente
   leitura):

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": [
           "lightsail:OpenInstancePublicPorts",
           "lightsail:CloseInstancePublicPorts"
         ],
         "Resource": "arn:aws:lightsail:<REGIAO>:<ID_DA_CONTA>:Instance/<ID_DA_INSTANCIA>"
       },
       {
         "Effect": "Allow",
         "Action": "lightsail:GetInstancePortStates",
         "Resource": "*"
       }
     ]
   }
   ```

   O ID da instância (um UUID) aparece em `aws lightsail get-instance --instance-name
   <NOME_DA_INSTANCIA> --query instance.arn --output text`. Se a AWS recusar o ARN para abrir ou
   fechar a porta (o erro cita `...:*` como recurso), troque também esse `Resource` por `"*"`: a role
   continua limitada a essas três ações e a este repositório e environment.
4. Anote o ARN da role (`<ARN_DA_ROLE>`).

**Risco a conhecer.** O workflow guarda a regra atual da porta 22 (`cidrs`, `ipv6Cidrs` e
`cidrListAliases`, este último o alias `lightsail-connect` do SSH pelo navegador) e a reenvia junto
com o `/32` do runner. Ao final, remove só o CIDR do runner com `close-instance-public-ports` e
reafirma a regra original com `open-instance-public-ports`, para que o seu IP e o SSH pelo navegador
sobrevivam qualquer que seja a semântica exata da API (substituir ou mesclar a regra da porta; fechar
um CIDR ou a regra inteira). Se o passo de fechar falhar ou a porta ficar aberta ao runner, o job
termina vermelho com "port 22 is still open". **Depois de um passo de fechar vermelho, remova o `/32`
do runner à mão antes do próximo deploy** (Networking > regra da 22): senão o próximo deploy o guarda
como se fosse parte da regra original e o mantém. Se a sua própria regra sumir, recrie-a em Networking
(adicione o seu IP de novo à regra SSH) ou use o terminal SSH do console do Lightsail.

**Teste a seco, uma vez, antes do primeiro deploy.** Na sua máquina, com um perfil AWS com as mesmas
permissões da role (`aws configure --profile duo-admin`), confirme que abrir e fechar se comportam como
o workflow assume. Use um IP de teste que não seja o seu (ex.: `203.0.113.7/32`):

```bash
P="--profile duo-admin --region <REGIAO> lightsail"
Q='portStates[?fromPort==`22`]'
aws $P get-instance-port-states --instance-name <NOME_DA_INSTANCIA> --query "$Q" --output json
aws $P open-instance-public-ports --instance-name <NOME_DA_INSTANCIA> --port-info \
  '{"fromPort":22,"toPort":22,"protocol":"tcp","cidrs":["<SEU_IP>/32","203.0.113.7/32"],"cidrListAliases":["lightsail-connect"]}'
aws $P get-instance-port-states --instance-name <NOME_DA_INSTANCIA> --query "$Q" --output json
aws $P close-instance-public-ports --instance-name <NOME_DA_INSTANCIA> --port-info \
  '{"fromPort":22,"toPort":22,"protocol":"tcp","cidrs":["203.0.113.7/32"]}'
aws $P get-instance-port-states --instance-name <NOME_DA_INSTANCIA> --query "$Q" --output json
aws $P open-instance-public-ports --instance-name <NOME_DA_INSTANCIA> --port-info \
  '{"fromPort":22,"toPort":22,"protocol":"tcp","cidrs":["<SEU_IP>/32"],"cidrListAliases":["lightsail-connect"]}'
aws $P get-instance-port-states --instance-name <NOME_DA_INSTANCIA> --query "$Q" --output json
```

Ajuste os dois JSON ao que o primeiro `get-instance-port-states` mostrou (inclua `ipv6Cidrs` se houver).
São quatro leituras:

1. **Antes:** a regra atual (o seu `/32` e, se houver, o alias `lightsail-connect`).
2. **Depois do `open`:** a regra com o seu `/32`, o `203.0.113.7/32` e o alias.
3. **Depois do `close`:** mostra qual semântica a API tem. Os dois resultados são aceitáveis, porque
   o workflow reafirma a regra original logo em seguida:
   - **remoção por CIDR:** a regra continua com o seu `/32` e o alias, só sem o `203.0.113.7/32`;
   - **remoção da regra inteira:** a porta 22 some da lista (`[]`). Nesse caso, durante o deploy o
     seu acesso SSH fica fora por alguns segundos, entre o `close` e a reafirmação.
4. **Depois de reafirmar:** idêntica à primeira, sem o IP de teste.

Critério de falha: **se, na quarta leitura, o seu IP ou o alias `lightsail-connect` sumiu, não faça o
deploy**: reveja o workflow antes. O mesmo vale se o `203.0.113.7/32` ainda aparecer.

### 8.3 Secrets e variáveis

Chave SSH dedicada ao deploy (na sua máquina):

```bash
ssh-keygen -t ed25519 -N '' -C duo-finance-deploy -f ./duo-deploy-key
ssh-copy-id -i ./duo-deploy-key.pub deploy@<IP_ESTATICO>
ssh-keyscan -t ed25519 <IP_ESTATICO> > ./duo-known-hosts
ssh-keygen -lf ./duo-known-hosts
```

Compare a impressão digital (`SHA256:...`) com a do servidor (na instância:
`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`) antes de confiar. Em Settings > Environments >
`production`, cadastre exatamente:

| Tipo | Nome | Valor |
| --- | --- | --- |
| Secret | `DEPLOY_SSH_KEY` | conteúdo de `duo-deploy-key` (chave privada) |
| Secret | `DEPLOY_KNOWN_HOSTS` | conteúdo de `duo-known-hosts` |
| Variable | `DEPLOY_HOST` | `<IP_ESTATICO>` |
| Variable | `DEPLOY_USER` | `deploy` |
| Variable | `DEPLOY_PATH` | `/opt/duo-finance` |
| Variable | `AWS_DEPLOY_ROLE_ARN` | `<ARN_DA_ROLE>` (seção 8.2) |
| Variable | `AWS_REGION` | `<REGIAO>` da instância |
| Variable | `LIGHTSAIL_INSTANCE` | `<NOME_DA_INSTANCIA>` |

Depois apague `duo-deploy-key`, `duo-deploy-key.pub` e `duo-known-hosts` da sua máquina. O
`GITHUB_TOKEN` usado para publicar no GHCR é automático; não há secret a criar para isso.

### 8.4 Proteção da `main`

Settings > Branches > Add branch protection rule para `main`: **Require a pull request before merging**
e **Require status checks to pass before merging**, com os checks `backend`, `frontend`, `api-types`,
`e2e` e `infra` (jobs de `.github/workflows/ci.yml`). Deixe **Do not allow bypassing the above
settings** marcado (administradores não furam a regra). Os checks só aparecem na lista depois de
rodarem ao menos uma vez em um pull request.

### 8.5 Primeiro deploy

O primeiro deploy sempre passa pelo pipeline: faça merge de um pull request na `main`. Quando o
workflow `CI` termina com sucesso, o workflow `Deploy` constrói e publica as imagens (job `images`),
copia o compose e os scripts para `$DEPLOY_PATH/.incoming/` e roda `.incoming/deploy.sh sha-<12>`
(job `deploy`). Na instância:

```bash
cd /opt/duo-finance
tail -n 5 deploy.log
cat .last-good-tag
docker compose ps
curl -s https://duofinance.dev.br/api/actuator/health
```

Resultado esperado: os dois jobs verdes; `deploy.log` termina com sucesso; `.last-good-tag` contém
`sha-<12>`; `nginx`, `api`, `postgres` e `certbot` estão `Up` (`api` e `postgres` `healthy`); o health
devolve `{"status":"UP"}`.

No primeiro deploy não há tag anterior para voltar: se ele falhar, o `deploy.sh` registra
"nothing to roll back to" e deixa a tag nova no lugar; corrija a causa (`docker compose logs api`) e
repita. Numa instância provisionada à mão que já roda uma versão boa e ainda não tem `.last-good-tag`,
semeie o arquivo para o rollback funcionar:

```bash
cd /opt/duo-finance
grep '^IMAGE_TAG=' .env
echo 'sha-<12_DA_TAG_BOA>' > .last-good-tag
```

## 9. Backup

Os dumps vão para um bucket **S3** comum (não o Object Storage do Lightsail), acessado por um usuário
IAM que só enxerga o prefixo `daily/` desse bucket.

1. **Bucket.** S3 > Create bucket: tipo *General purpose*, região `<REGIAO>` (a da instância), nome
   único na AWS (ex.: `duofinance-backup-<sufixo>`; é o `<BUCKET>`). Mantenha **Block all public
   access** ligado, **Bucket Versioning** desligado (com versionamento, os dumps apagados pela
   retenção continuam guardados e cobrados como versões antigas) e a criptografia padrão (SSE-S3).
   Resultado esperado: bucket listado no S3, com "Access: Bucket and objects not public".
2. **Ciclo de vida de 30 dias** (segunda camada de retenção; o `backup.sh` também apaga dumps com mais
   de 30 dias). No bucket > Management > Create lifecycle rule: nome `expire-daily-30d`, escopo
   *Limit the scope using filters* com prefixo `daily/`, ações **Expire current versions of objects**
   (30 dias) e **Delete expired object delete markers or incomplete multipart uploads** (uploads
   incompletos após 1 dia). Resultado esperado: regra "Enabled" na aba Management.
3. **Usuário IAM.** IAM > Users > Create user `duo-finance-backup`, **sem** acesso ao console e sem
   grupos ou policies gerenciadas. Depois, no usuário > Permissions > Add permissions > Create inline
   policy (JSON), nome `duo-finance-backup-s3`:

   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": "s3:ListBucket",
         "Resource": "arn:aws:s3:::<BUCKET>",
         "Condition": {"StringLike": {"s3:prefix": ["daily/*"]}}
       },
       {
         "Effect": "Allow",
         "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
         "Resource": "arn:aws:s3:::<BUCKET>/daily/*"
       }
     ]
   }
   ```

   O `backup.sh` envia para `daily/.partial/` e move para `daily/` (`PutObject`, `GetObject`,
   `DeleteObject`), lista `daily/` para a retenção (`ListBucket`) e apaga os dumps antigos
   (`DeleteObject`); o `restore.sh` baixa com `GetObject`. Em Security credentials > Create access key,
   escolha "Application running outside AWS". O Access key ID e o Secret aparecem uma única vez;
   guarde-os num gerenciador de senhas.
4. Na instância, **como `ubuntu`**, instale o AWS CLI v2 em `/usr/local/bin` (o PATH do cron não
   inclui `/snap/bin`):

   ```bash
   curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip
   unzip -q /tmp/awscliv2.zip -d /tmp && sudo /tmp/aws/install
   rm -rf /tmp/aws /tmp/awscliv2.zip
   ```

   Depois, como `deploy`:

   ```bash
   aws --version
   aws configure --profile duo-backup   # access key do usuário IAM do passo 3, só para conferir o upload
   ```

   Resultado esperado: `aws-cli/2.x.x`.
5. Preencha o `/opt/duo-finance/backup.env` (criado na seção 5, `chmod 600`, dono `deploy`) com `nano`
   (nunca com `echo`/`export` na linha de comando), sem aspas: `BACKUP_S3_BUCKET=<BUCKET>`,
   `BACKUP_S3_ENDPOINT=` **vazio** (S3 padrão da AWS; o aws CLI resolve o endpoint pela região),
   `AWS_ACCESS_KEY_ID` e `AWS_SECRET_ACCESS_KEY` do usuário IAM e `AWS_DEFAULT_REGION=<REGIAO>`.
   Essas chaves **não** vão no `.env` (o `.env` inteiro chega ao contêiner `api`). Confira:

   ```bash
   cd /opt/duo-finance
   ls -l backup.env
   grep -c $'\r' backup.env
   grep -cE '^(AWS_|BACKUP_)' .env
   ```

   Resultado esperado: `-rw------- 1 deploy deploy ... backup.env`, `0` e `0`.
6. Teste manual, com a stack no ar:

   ```bash
   cd /opt/duo-finance
   ./backup.sh
   echo $?
   aws --profile duo-backup s3 ls s3://<BUCKET>/daily/
   ```

   Resultado esperado: linhas `backup: ...`, exit code `0` e o objeto
   `duofinance-<AAAAMMDD-HHMMSS>.dump` listado. O `backup.sh` valida o dump com `pg_restore --list`,
   que confere **somente o índice (TOC)** do arquivo; só um restore de verdade prova que os dados
   voltam (passo 8).
7. Instale o cron como **root**, numa sessão **como `ubuntu`** (grava `/etc/cron.d/duo-finance-backup`:
   todo dia às 03:30 UTC, com log em `/var/log/duo-finance-backup.log`; o job roda como root, que lê
   o `.env` e o `backup.env`):

   ```bash
   cd /opt/duo-finance
   sudo ./install-cron.sh
   cat /etc/cron.d/duo-finance-backup
   ```

   Resultado esperado: `installed /etc/cron.d/duo-finance-backup` e a linha
   `30 3 * * * root cd /opt/duo-finance && ./backup.sh >> /var/log/duo-finance-backup.log 2>&1`.
8. Teste `backup.sh` e `restore.sh` num **banco descartável**, nunca no de produção. Um projeto
   compose separado (`COMPOSE_PROJECT_NAME` vence o `name:` do arquivo) com volume próprio:

   ```bash
   mkdir -p ~/duo-test && cd ~/duo-test
   cp /opt/duo-finance/{docker-compose.yml,backup.sh,restore.sh} .
   printf 'IMAGE_TAG=x\nPOSTGRES_DB=duofinance\nPOSTGRES_USER=duofinance\nPOSTGRES_PASSWORD=teste\n' > .env
   export COMPOSE_PROJECT_NAME=duo-test BACKUP_TARGET=file://$HOME/duo-test/backups
   mkdir -p backups
   docker compose up -d --wait postgres
   docker compose exec postgres psql -U duofinance -d duofinance -c 'CREATE TABLE t(x int); INSERT INTO t VALUES (42);'
   ./backup.sh
   docker compose exec postgres psql -U duofinance -d duofinance -c 'DROP TABLE t;'
   ./restore.sh "$(ls backups/daily/duofinance-*.dump | head -n 1)" --yes --drop-schema
   docker compose exec postgres psql -U duofinance -d duofinance -c 'SELECT x FROM t;'
   ```

   Resultado esperado: o último comando imprime `42`.

   Para testar também o restore a partir do bucket, antes da limpeza rode
   `./restore.sh s3://<BUCKET>/daily/<ARQUIVO>.dump --yes` nesse mesmo diretório de teste, com uma
   cópia do backup.env: `(umask 077 && cp /opt/duo-finance/backup.env .)`.

   Limpeza:

   ```bash
   docker compose down -v
   cd ~ && rm -rf ~/duo-test && unset COMPOSE_PROJECT_NAME BACKUP_TARGET
   ```

## 10. Admin

Depois de criar a sua conta pelo site e verificar o e-mail, promova-a a administradora:

```bash
cd /opt/duo-finance
docker compose exec postgres psql -U duofinance -d duofinance \
  -c "UPDATE users SET role = 'ADMIN' WHERE lower(email) = lower('voce@exemplo.com');"
```

Resultado esperado: `UPDATE 1`. Se for `UPDATE 0`, o e-mail está errado. Faça logout e login de novo
para o papel valer.

## 11. Operação

### Logs

```bash
cd /opt/duo-finance
docker compose logs -f api
docker compose logs --tail 100 nginx
tail -n 50 deploy.log
```

Os logs dos contêineres são JSON no stdout, com rotação do driver do Docker (10 MB x 5 arquivos por
serviço). Os dois arquivos de log do host precisam de rotação própria (como `ubuntu`):

```bash
sudo tee /etc/logrotate.d/duo-finance <<'CONF'
/opt/duo-finance/deploy.log /var/log/duo-finance-backup.log {
    weekly
    rotate 8
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
CONF
sudo logrotate --debug /etc/logrotate.d/duo-finance
```

Resultado esperado: a saída de `--debug` cita os dois arquivos, sem erros.

### Deploy manual e volta manual

**Nunca rode um deploy manual enquanto um deploy do pipeline estiver em andamento** (confira em
GitHub > Actions). O `deploy.sh` usa um lock (`.deploy.lock`) e sai com código 75 se estiver ocupado,
mas não conte só com isso.

Um `.incoming/` que sobrou de um deploy do pipeline é usado pelo `deploy.sh` (arquivos antigos
poderiam substituir o compose e os scripts atuais). Confira e remova antes de rodar à mão:

```bash
cd /opt/duo-finance
ls -A .incoming 2>/dev/null && rm -rf .incoming
./deploy.sh sha-<12>
echo $?
```

Resultado esperado: `0` se a tag subiu e o smoke passou. Código `1`: falhou e voltou para a última tag
boa; `2`: falhou e a volta também falhou (intervenção manual).

Voltar à mão para a última tag boa:

```bash
rm -rf .incoming
./deploy.sh "$(cat .last-good-tag)"
curl -s https://duofinance.dev.br/api/actuator/health
```

Resultado esperado: exit code `0` e `{"status":"UP"}`. Para ver a tag em uso: `grep '^IMAGE_TAG=' .env`.

### Restaurar um backup

```bash
cd /opt/duo-finance
./restore.sh s3://<BUCKET>/daily/<ARQUIVO>.dump --yes --stop-api
```

O script mostra o plano e recusa rodar sem `--yes`. Ele roda `pg_restore --clean --if-exists
--single-transaction`: numa falha, o banco volta ao estado anterior. Opções:

- `--stop-api`: para o `api` antes e o inicia depois. Use em produção, porque o restore apaga tabelas
  que a API está usando. Sem a flag, rode `docker compose stop api` antes e `docker compose start api`
  depois.
- `--drop-schema`: recria o schema `public` do zero antes de restaurar. Use quando o dump é **mais
  antigo** que o banco atual (objetos do Flyway do schema novo, ausentes no dump, causariam conflito).
  Para um dump recente do mesmo schema, `--clean --if-exists` basta e a flag é desnecessária.

Resultado esperado: `docker compose ps` com `api` `healthy` e `./smoke.sh https://duofinance.dev.br`
terminando em `smoke: all checks passed`.

### Renovação do certificado

Automática: o contêiner `certbot` roda `certbot renew` a cada 12 h e o nginx recarrega a configuração
a cada 6 h. Verificação:

```bash
docker compose run --rm --entrypoint certbot certbot renew --dry-run
docker compose run --rm --entrypoint certbot certbot certificates
```

Resultado esperado: "Congratulations, all simulated renewals succeeded" e `VALID: NN days` (a renovação
real acontece com 30 dias restantes).

### Migração para o Managed Database

1. Crie o banco no Lightsail Managed Database (PostgreSQL 16) e restaure nele o último backup com
   `pg_restore`.
2. No `.env`, troque só `DB_URL` (`jdbc:postgresql://<host>:5432/<banco>?sslmode=require`), `DB_USER` e
   `DB_PASSWORD`. O `backup.sh` e o `restore.sh` falam com o serviço `postgres` do compose: ao migrar,
   adapte-os ou desative o cron antes.
3. `docker compose up -d api` e confira `docker compose logs -f api` (o Flyway valida o schema).

## 12. Antes do lançamento

- [ ] Revisar com um advogado e preencher os marcadores entre colchetes da política de privacidade e
  dos termos de uso (redigidos segundo o RGPD e o direito do consumidor da UE) em
  `frontend/src/shared/i18n/locales/pt-BR.json` e `en.json`, chaves `legal.privacy.body` e
  `legal.terms.body`: responsável, identificação fiscal, endereço, representante na UE (se aplicável),
  `[E-MAIL DE CONTATO]`, `[PAÍS]`, prazo dos registros técnicos, mecanismo de transferência de cada
  provedor e datas de publicação. Para achar os que faltam: `grep -o '\[[A-ZÁÉÍÓÚÂÊÔÃÕÇ][^]]*\]'`
  nos dois arquivos. O vue-i18n interpreta caracteres especiais: escreva o e-mail com `{'@'}` no
  lugar de `@` (ex.: `contato{'@'}duofinance.dev.br`) e escape `{`, `}` e `|` como `{'{'}`, `{'}'}` e
  `{'|'}`. Os parágrafos são separados por uma linha em branco (`\n\n`). Confira as páginas de
  privacidade e termos no navegador depois do build.
- [ ] Revisar `TERMS_VERSION` em `frontend/src/shared/lib/legal.ts` (hoje `'2026-11'`): altere se o
  texto mudou em relação ao que os usuários já aceitaram.
- [ ] Conferir `PUBLIC_BASE_URL=https://duofinance.dev.br` no `.env` (links dos e-mails).
- [ ] Checar os e-mails reais no Resend: verificação de e-mail, redefinição de senha, convite e código
  de acesso. Resultado esperado: chegam à caixa de entrada (não ao spam) e constam como "Delivered".
- [ ] Rodar `./smoke.sh https://duofinance.dev.br` na instância. Resultado esperado: todas as linhas
  `smoke: ok` e `smoke: all checks passed`.
- [ ] Confirmar, no dia seguinte, que o backup das 03:30 UTC apareceu no bucket e que
  `/var/log/duo-finance-backup.log` não tem erros.
