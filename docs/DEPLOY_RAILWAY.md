# Deploy do backend no Railway

## Objetivo
Subir o backend v0.4 com HTTPS público e banco SQLite persistente.

## 1. Criar o serviço
1. No Railway, crie um novo projeto.
2. Escolha **Deploy from GitHub repo**.
3. Selecione `mauricio-gamedev/Monetizei`.
4. O Railway deve detectar o `Dockerfile` da raiz automaticamente.

## 2. Adicionar volume persistente
1. Adicione um Volume ao serviço do Monetizei.
2. Use o mount path `/data`.
3. Crie a variável:

`MONETIZEI_DB_PATH=/data/monetizei.db`

O backend usa a variável `PORT` fornecida pelo host e escuta em `0.0.0.0`.

## 3. Expor por HTTPS
1. Abra **Settings** do serviço.
2. Vá em **Networking / Public Networking**.
3. Clique em **Generate Domain**.
4. Confirme que `https://SEU-DOMINIO/health` responde JSON com `ok: true`.

## 4. Ligar o Android
Não coloque segredos no APK. O único valor necessário no cliente será a URL pública HTTPS do backend. Depois que o domínio existir, atualizar `MONETIZEI_API_BASE_URL` e gerar um novo APK.

## Persistência
O arquivo `/data/monetizei.db` contém:
- instalações e chaves públicas;
- sessões aceitas;
- sequência usada para anti-replay;
- ledger de score verificado e timestamps.

O servidor não grava saldo PayPal nem autoriza dinheiro real nesta etapa.
