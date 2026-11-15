# Backend seguro — Voz Câmera IA

Este Worker faz a ponte entre o aplicativo Android e a OpenAI Responses API. A chave OpenAI fica somente como segredo no Cloudflare; nunca deve ser colocada no APK, no GitHub ou em mensagens.

## Implantação pelo painel Cloudflare (sem computador)

1. Entre em https://dash.cloudflare.com/
2. Abra **Workers & Pages** e escolha **Create** → **Create Worker**.
3. Dê o nome `voz-camera-ia-api` e crie o Worker.
4. Abra **Edit code** e substitua o conteúdo pelo arquivo `worker/src/index.js` deste repositório. Salve e implante.
5. Abra **Settings** → **Variables and Secrets** → **Add** → **Secret** e crie:
   - `OPENAI_API_KEY`: sua chave secreta da OpenAI.
   - `APP_ACCESS_TOKEN`: um token aleatório longo, exclusivo para este aplicativo (use pelo menos 32 caracteres).
6. Opcionalmente, adicione como variável de texto `OPENAI_MODEL` com valor `gpt-4.1-mini`.
7. Salve e faça novo deploy.

**Nunca** coloque os valores dos segredos no código, em arquivos do GitHub ou no chat. Se uma chave for exposta, revogue-a na plataforma correspondente e crie outra.

## Rotas

- `GET /health`: verifica se o Worker está ativo (não revela segredos).
- `POST /v1/chat`: corpo JSON `{"message":"...","context":"..." }`.
- `POST /v1/analyze`: mesmo corpo, acrescentando `"imageDataUrl":"data:image/jpeg;base64,..."`.

As rotas POST exigem `Authorization: Bearer <APP_ACCESS_TOKEN>`. O token do aplicativo não é a chave OpenAI; ele serve para impedir chamadas anônimas ao Worker. Para uso público, adicione autenticação individual e limites de taxa no Cloudflare antes de distribuir o APK.

## Teste

Abra `https://SEU-WORKER.SEU-SUBDOMINIO.workers.dev/health`. Deve retornar `{"ok":true,...}`. O endereço base do Worker será usado pelo aplicativo.
